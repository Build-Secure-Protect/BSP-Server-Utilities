package com.mrgregles.bsp_server_utilities.talk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TalkPopupPacket;
import com.mrgregles.bsp_server_utilities.network.TalkStatePacket;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Private staff messages reaching people (SU-106; owner decisions D633 to D639, D652 R3 and R4). Every line written on any
 * server is told to the others (notice kind {@value #KIND}) and handled the same way on each:
 * <ul>
 * <li>The server holding the player keeps their conversation's state and sends it to their client (the MESSAGE STAFF key
 * shown while open, flashing while the newest line is from staff); on a staff line it sends the popup (never the words)
 * and the line "[Staff] You have a private message from staff. [OPEN]", and records that they were told; on the end, the
 * line "Staff ended your conversation. Thank you!" and the key goes.</li>
 * <li>Every server tells its staff online of each answer ("[Staff msg] Wren_88 replied: ... [OPEN]") and of each end made
 * by someone else, so nobody answers into a closed conversation.</li>
 * <li>At login the player's open conversation is read; staff lines they were never told of bring the popup
 * {@value #LOGIN_DELAY_MS} ms after the login (after the Support BSP line), later while the rules popup holds them, with the
 * line "[Staff] While you were away, staff sent you a private message. [OPEN]". A move between servers never tells them
 * again of lines already told ({@code told_at} is shared).</li>
 * </ul>
 */
public final class TalkDelivery {
    public static final String KIND = "talk";
    public static final long LOGIN_DELAY_MS = 6_000L;
    public static final int STAFF_COLOUR = 0xB58CFF, REPLY_CUT = 80;
    /** The player's open conversation, for every player online here whose conversation was read (absent: none). */
    private static final Map<UUID, TalkStore.Talk> OPEN = new ConcurrentHashMap<>();
    /** Players whose conversation is being read at login, with what arrived meanwhile (replayed once it is read). */
    private static final Map<UUID, List<Event>> LOADING = new ConcurrentHashMap<>();
    private static final Map<UUID, Due> DUE = new ConcurrentHashMap<>();

    private static LongSupplier clock = System::currentTimeMillis;
    private static Function<MinecraftServer, Collection<ServerPlayer>> players = s -> List.copyOf(s.getPlayerList().getPlayers());
    private static Predicate<ServerPlayer> staffHere = Staff::isStaff;
    private static Predicate<UUID> atTheRules = RulesService::busy;
    private static BiConsumer<ServerPlayer, Object> packets = ServerUtilsNetwork::sendTo;
    private static BiConsumer<ServerPlayer, Component> chat = ServerPlayer::sendSystemMessage;

    private TalkDelivery() {
    }

    /** A login popup waiting for its moment (the lines are read again then, audit of SU-111: never shown twice after a quick move). */
    private record Due(ServerPlayer player, long[] dueMs) {
    }

    /** Conversations this server has seen end: a late line of one never brings the key back (audit of SU-111). */
    private static final Map<Long, Boolean> ENDED = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, .75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) {
            return size() > 5000;
        }
    });
    /** Players whose login read failed (the database away), with when it is tried again. */
    private static final Map<UUID, Due> RETRY = new ConcurrentHashMap<>();
    static final long RETRY_MS = 30_000L;

    /** One line, as the servers tell each other (and as the server that wrote it handles it itself). */
    public record Event(long talk, UUID player, String name, String kind, String staff, String text, long at, long lastStaffAt, long lastPlayerAt, long endedAt,
                        String lastKind) {
        static Event of(TalkBook.Written w) {
            TalkStore.Talk t = w.talk();
            TalkStore.Line l = w.line();
            return new Event(t.id(), t.player(), t.name(), l.kind(), l.staff(), l.text(), l.at(), t.lastStaffAt(), t.lastPlayerAt(), t.endedAt(), t.lastKind());
        }

        String encode() {
            JsonObject o = new JsonObject();
            o.addProperty("talk", talk);
            o.addProperty("player", player.toString());
            o.addProperty("name", name);
            o.addProperty("kind", kind);
            o.addProperty("staff", staff);
            o.addProperty("text", text);
            o.addProperty("at", at);
            o.addProperty("lastStaffAt", lastStaffAt);
            o.addProperty("lastPlayerAt", lastPlayerAt);
            o.addProperty("endedAt", endedAt);
            o.addProperty("lastKind", lastKind);
            return o.toString();
        }

        @Nullable
        static Event decode(String payload) {
            try {
                JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                return new Event(o.get("talk").getAsLong(), UUID.fromString(o.get("player").getAsString()), o.get("name").getAsString(), o.get("kind").getAsString(),
                        o.get("staff").getAsString(), o.get("text").getAsString(), o.get("at").getAsLong(), o.get("lastStaffAt").getAsLong(),
                        o.get("lastPlayerAt").getAsLong(), o.get("endedAt").getAsLong(), o.has("lastKind") ? o.get("lastKind").getAsString() : "");
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** The conversation as it stands after this line (what the state is drawn from). */
        TalkStore.Talk talkAfter() {
            return new TalkStore.Talk(talk, player, name, 0, "", endedAt, kind.equals(TalkStore.KIND_END) ? staff : "", lastStaffAt, lastPlayerAt, 0,
                    lastKind.isEmpty() ? kind : lastKind);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Once, at mod start: hears every line written here and every line the other servers tell. */
    public static void register() {
        TalkService.onChange((server, w) -> {
            Event e = Event.of(w);
            Storage s = Storage.active();
            if (s != null) {
                s.publish(KIND, e.encode()); // never acts on this server: handled here below
            }
            deliver(server, e);
        });
        Storage.on(KIND, (server, n) -> {
            Event e = Event.decode(n.payload());
            if (e != null) {
                deliver(server, e);
            }
        });
    }

    /** The player arrived: their conversation is read; the key's state is sent, and the popup is due for staff lines never told. */
    public static void onLogin(ServerPlayer player) {
        UUID id = player.getUUID();
        OPEN.remove(id);
        DUE.remove(id);
        RETRY.remove(id);
        read(player, clock.getAsLong());
    }

    /** Reads the player's open conversation; a failed read (the database away) is tried again every {@value #RETRY_MS} ms. */
    private static void read(ServerPlayer player, long arrived) {
        UUID id = player.getUUID();
        List<Event> mine = new ArrayList<>();
        LOADING.put(id, mine);
        TalkService.playerView(player.server, id).whenComplete((h, err) -> {
            boolean current = LOADING.remove(id, mine); // an earlier login's read never takes a later one's buffer (audit)
            if (!current || player.hasDisconnected()) {
                return;
            }
            if (err != null) {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities staff messages: reading {}'s conversation: {}; trying again in 30 s", player.getGameProfile().getName(), err.toString());
                RETRY.put(id, new Due(player, new long[]{clock.getAsLong() + RETRY_MS}));
            } else {
                TalkStore.Talk t = h.open();
                if (t != null) {
                    OPEN.put(id, t);
                    if (t.untold()) {
                        DUE.put(id, new Due(player, new long[]{arrived + LOGIN_DELAY_MS}));
                    }
                }
                sendState(player);
            }
            mine.forEach(e -> toPlayer(player.server, player, e)); // the player's part only: staff had theirs already (audit)
        });
    }

    public static void onLogout(ServerPlayer player) {
        UUID id = player.getUUID();
        OPEN.remove(id);
        LOADING.remove(id);
        DUE.remove(id);
        RETRY.remove(id);
    }

    /**
     * Every server tick: login popups that are due go out, after reading the conversation again (a quick move from a
     * server that has just shown the popup is never told twice); a player still at the rules waits {@value #LOGIN_DELAY_MS}
     * ms after. Failed login reads are tried again.
     */
    public static void tick(MinecraftServer server) {
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<UUID, Due>> it = RETRY.entrySet().iterator(); it.hasNext(); ) {
            Due d = it.next().getValue();
            if (d.player().hasDisconnected()) {
                it.remove();
            } else if (now >= d.dueMs()[0]) {
                it.remove();
                read(d.player(), now - LOGIN_DELAY_MS); // late already: the popup, if any, at once
            }
        }
        for (Iterator<Map.Entry<UUID, Due>> it = DUE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Due> e = it.next();
            Due d = e.getValue();
            if (d.player().hasDisconnected()) {
                it.remove();
            } else if (atTheRules.test(e.getKey())) {
                d.dueMs()[0] = Math.max(d.dueMs()[0], now + LOGIN_DELAY_MS);
            } else if (now >= d.dueMs()[0]) {
                it.remove();
                awayPopup(server, d.player());
            }
        }
    }

    private static void awayPopup(MinecraftServer server, ServerPlayer player) {
        UUID id = player.getUUID();
        TalkService.playerView(server, id).thenAccept(h -> {
            TalkStore.Talk t = h.open();
            if (t == null || !t.untold() || player.hasDisconnected() || OPEN.get(id) == null || OPEN.get(id).id() != t.id()) {
                return; // told meanwhile (on another server, or by a live line here), or ended
            }
            List<TalkStore.Line> untold = h.lines().stream().filter(l -> l.kind().equals(TalkStore.KIND_STAFF) && l.at() > t.toldAt()).toList();
            if (untold.isEmpty()) {
                return;
            }
            long writers = untold.stream().map(TalkStore.Line::staff).distinct().count();
            packets.accept(player, new TalkPopupPacket(writers == 1 ? untold.get(0).staff() : "", untold.size(), true));
            chat.accept(player, playerLine("message.bsp_server_utilities.talk.away", "While you were away, staff sent you a private message."));
            told(server, id, t, t.lastStaffAt());
        });
    }

    // ------------------------------------------------------------------ one line, on this server

    /** Handles one line on this server: the player's state, popup and lines when they are here; the staff's lines. */
    public static void deliver(MinecraftServer server, Event e) {
        if (e.kind().equals(TalkStore.KIND_END) || e.endedAt() > 0) {
            ENDED.put(e.talk(), Boolean.TRUE);
        }
        Collection<ServerPlayer> here = players.apply(server);
        ServerPlayer player = here.stream().filter(p -> p.getUUID().equals(e.player())).findFirst().orElse(null);
        if (player != null) {
            List<Event> loading = LOADING.get(e.player());
            if (loading != null) {
                loading.add(e); // the login read is on its way: the player's part after it
            } else {
                toPlayer(server, player, e);
            }
        }
        if (e.kind().equals(TalkStore.KIND_PLAYER)) {
            Component line = replyLine(e);
            here.stream().filter(p -> !p.getUUID().equals(e.player()) && staffHere.test(p)).forEach(p -> chat.accept(p, line));
        } else if (e.kind().equals(TalkStore.KIND_END) && !e.staff().isEmpty()) {
            Component line = endedLine(e);
            here.stream().filter(p -> !p.getGameProfile().getName().equalsIgnoreCase(e.staff()) && staffHere.test(p)).forEach(p -> chat.accept(p, line));
        }
        TalkWindows.lineFor(here, e); // open private windows showing this conversation get the line
    }

    private static void toPlayer(MinecraftServer server, ServerPlayer player, Event e) {
        UUID id = e.player();
        TalkStore.Talk was = OPEN.get(id);
        if (e.kind().equals(TalkStore.KIND_END)) {
            if (was == null || was.id() == e.talk()) {
                OPEN.remove(id);
                DUE.remove(id);
                sendState(player);
                chat.accept(player, playerLine("message.bsp_server_utilities.talk.ended", "Staff ended your conversation. Thank you!"));
            }
            return;
        }
        if (e.endedAt() > 0 || ENDED.containsKey(e.talk())) {
            return; // a line of a conversation already ended (a late notice): the key stays gone (audit)
        }
        TalkStore.Talk after = e.talkAfter();
        if (was != null && was.id() == e.talk()) {
            boolean newer = e.at() >= Math.max(was.lastStaffAt(), was.lastPlayerAt());
            after = new TalkStore.Talk(was.id(), was.player(), e.name(), was.openedAt(), was.openedBy(), 0, "", Math.max(was.lastStaffAt(), e.lastStaffAt()),
                    Math.max(was.lastPlayerAt(), e.lastPlayerAt()), was.toldAt(), newer ? after.lastKind() : was.lastKind());
        }
        OPEN.put(id, after);
        sendState(player);
        if (e.kind().equals(TalkStore.KIND_STAFF)) {
            DUE.remove(id); // told now: the login popup would only repeat it
            packets.accept(player, new TalkPopupPacket(e.staff(), 1, false));
            chat.accept(player, playerLine("message.bsp_server_utilities.talk.new", "You have a private message from staff."));
            told(server, id, after, e.at());
        }
    }

    private static void told(MinecraftServer server, UUID id, TalkStore.Talk t, long at) {
        OPEN.computeIfPresent(id, (k, v) -> v.id() == t.id() ? new TalkStore.Talk(v.id(), v.player(), v.name(), v.openedAt(), v.openedBy(), v.endedAt(), v.endedBy(),
                v.lastStaffAt(), v.lastPlayerAt(), Math.max(v.toldAt(), at), v.lastKind()) : v);
        TalkService.told(server, t.id(), at);
    }

    /** Sends the player their key's state as this server knows it. */
    public static void sendState(ServerPlayer player) {
        TalkStore.Talk t = OPEN.get(player.getUUID());
        packets.accept(player, t == null ? TalkStatePacket.NONE : new TalkStatePacket(true, t.waitingOnPlayer()));
    }

    // ------------------------------------------------------------------ the chat lines (D652 R4)

    private static MutableComponent tag(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback).withStyle(s -> s.withColor(TextColor.fromRgb(STAFF_COLOUR)));
    }

    private static Component open(String command) {
        return Component.translatableWithFallback("message.bsp_server_utilities.talk.open", "[OPEN]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatableWithFallback("message.bsp_server_utilities.talk.open_hover", "Open the conversation"))));
    }

    /** "[Staff] {words} [OPEN]" for the player; the end line has no [OPEN]. */
    static Component playerLine(String key, String fallback) {
        MutableComponent out = Component.empty().append(tag("message.bsp_server_utilities.talk.tag", "[Staff]")).append(" ")
                .append(Component.translatableWithFallback(key, fallback).withStyle(ChatFormatting.WHITE));
        if (!key.endsWith(".ended")) {
            out.append(" ").append(open("/su talk"));
        }
        return out;
    }

    /** "[Staff msg] Wren_88 replied: ... [OPEN]" for staff, the words cut to one line. */
    static Component replyLine(Event e) {
        String text = e.text().length() <= REPLY_CUT ? e.text() : e.text().substring(0, REPLY_CUT - 1) + "…";
        return Component.empty().append(tag("message.bsp_server_utilities.talk.staff_tag", "[Staff msg]")).append(" ")
                .append(Component.translatableWithFallback("message.bsp_server_utilities.talk.replied", "%s replied:", e.name())
                        .withStyle(s -> s.withColor(TextColor.fromRgb(STAFF_COLOUR))))
                .append(Component.literal(" " + text + " ").withStyle(ChatFormatting.WHITE)).append(open("/su talk " + e.name()));
    }

    /** "[Staff msg] Ava_Builds ended the conversation with Wren_88." in grey for staff. */
    static Component endedLine(Event e) {
        return Component.empty().append(tag("message.bsp_server_utilities.talk.staff_tag", "[Staff msg]")).append(" ")
                .append(Component.translatableWithFallback("message.bsp_server_utilities.talk.staff_ended", "%s ended the conversation with %s.", e.staff(), e.name())
                        .withStyle(ChatFormatting.GRAY));
    }

    /** The players on this server (tests give their own: mock players are not in the player list). */
    public static Collection<ServerPlayer> here(MinecraftServer server) {
        return players.apply(server);
    }

    /** Sends a packet to a player (tests catch it). */
    public static void send(ServerPlayer p, Object packet) {
        packets.accept(p, packet);
    }

    // ------------------------------------------------------------------ tests and reading

    /** The open conversation this server holds for an online player, or null (tests; the commands). */
    @Nullable
    public static TalkStore.Talk openOf(UUID player) {
        return OPEN.get(player);
    }

    /** Whether a failed login read waits to be tried again (tests). */
    public static boolean retryDue(UUID player) {
        return RETRY.containsKey(player);
    }

    /** Whether a login popup waits for the player (tests). */
    public static boolean popupDue(UUID player) {
        return DUE.containsKey(player);
    }

    /**
     * Tests: the clock, who is on this server and who of them is staff (mock players are not in the player list), who is
     * at the rules, and where packets and chat lines go; null puts each back.
     */
    public static void useForTests(@Nullable LongSupplier testClock, @Nullable Function<MinecraftServer, Collection<ServerPlayer>> who,
                                   @Nullable Predicate<ServerPlayer> staff, @Nullable Predicate<UUID> rules,
                                   @Nullable BiConsumer<ServerPlayer, Object> sent, @Nullable BiConsumer<ServerPlayer, Component> lines) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        players = who != null ? who : s -> List.copyOf(s.getPlayerList().getPlayers());
        staffHere = staff != null ? staff : Staff::isStaff;
        atTheRules = rules != null ? rules : RulesService::busy;
        packets = sent != null ? sent : ServerUtilsNetwork::sendTo;
        chat = lines != null ? lines : ServerPlayer::sendSystemMessage;
        OPEN.clear();
        LOADING.clear();
        DUE.clear();
        RETRY.clear();
        ENDED.clear();
    }

    public static void useReal() {
        useForTests(null, null, null, null, null, null);
    }
}
