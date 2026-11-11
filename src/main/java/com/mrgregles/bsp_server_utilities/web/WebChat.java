package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Chat for the staff web panel (SU-36, D261 to D263, D304 to D313). Every public chat line said on this server (one that
 * no mod cancelled: not team chat, not a muted player, not someone held by the rules popup) goes to the panel signed with
 * the action key, live on Redis's web channel (kind {@code chat}) and into {@code <prefix>web_chat}, where the panel keeps
 * 30 days of it. Each server sends only its own players' lines, so every line reaches the panel once.
 *
 * <p>The other way, the panel's signed action {@code say} shows a line on this server: "[Staff] Name: text" in violet with
 * the text in white, or "[Server] text" with [Server] in gold (D305, D307 Admins and Owners only), with a hover saying
 * where it came from (D310). Plain chat components: no client change. Every line said is an Action Log entry (D306).
 */
public final class WebChat {
    public static final String KIND_CHAT = "chat";
    public static final int MAX_TEXT = 256;
    public static final int STAFF_COLOUR = 0xB58CFF, SERVER_COLOUR = 0xFFAA00;

    private static LongSupplier clock = System::currentTimeMillis;
    private static Function<MinecraftServer, Collection<ServerPlayer>> players = s -> List.copyOf(s.getPlayerList().getPlayers());
    private static java.util.function.BiConsumer<ServerPlayer, Component> send = ServerPlayer::sendSystemMessage;
    @Nullable
    private static Consumer<String> sink;

    private WebChat() {
    }

    public static void registerEvents() {
        // last, and only lines nobody cancelled: what the other players really saw
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerChatEvent.class, WebChat::onChat);
    }

    public static void onChat(ServerChatEvent e) {
        if (!e.isCanceled()) {
            capture(e.getPlayer(), e.getRawText());
        }
    }

    /** The text as it is kept and shown: no control characters or section signs, trimmed, at most {@value #MAX_TEXT} characters. */
    public static String clean(String raw) {
        // control characters (C0, C1) and invisible format characters such as right-to-left overrides become spaces (audit)
        String t = raw == null ? "" : raw.replace('§', ' ').replaceAll("[\\p{Cntrl}\\p{Cf}\\u0080-\\u009F]", " ").strip();
        return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT);
    }

    /**
     * Sends one line said by a player to the panel; returns its JSON, or null when nothing was sent (the panel is off here,
     * no action key, no shared storage, or nothing to say).
     */
    @Nullable
    public static String capture(ServerPlayer p, String raw) {
        if (!WebAgent.enabled()) {
            return null;
        }
        byte[] key = WebActions.currentKey();
        Storage s = Storage.active();
        if (key == null || (s == null && sink == null)) {
            return null;
        }
        String text = clean(raw);
        if (text.isEmpty()) {
            return null;
        }
        String uid = UUID.randomUUID().toString(), server = Storage.currentServerId(), name = p.getGameProfile().getName();
        long now = clock.getAsLong();
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat"); // never read as a snapshot or an answer (they carry other fields)
        o.addProperty("v", 1);
        o.addProperty("uid", uid);
        o.addProperty("at", now);
        o.addProperty("server", server);
        o.addProperty("kind", "player");
        o.addProperty("player", p.getUUID().toString());
        o.addProperty("name", name);
        o.addProperty("text", text);
        String json = o.toString(), signed = WebActions.signText(key, json);
        if (sink != null) {
            sink.accept(signed);
        } else {
            s.publishChat(KIND_CHAT, signed, new StorageBackend.WebChat(uid, now, server, name, text, signed));
        }
        return json;
    }

    // ------------------------------------------------------------------ the panel's "say"

    /** The line players see (D305, D310): voice "staff" or "server". */
    public static Component line(String voice, String by, String text) {
        boolean staff = voice.equals("staff");
        Component hover = staff
                ? Component.translatableWithFallback("message.bsp_server_utilities.web.staff_hover", "%s, staff",
                        Component.literal(by).withStyle(s -> s.withColor(TextColor.fromRgb(STAFF_COLOUR))))
                .append(Component.literal("\n"))
                .append(Component.translatableWithFallback("message.bsp_server_utilities.web.staff_hover_from", "from the staff panel").withStyle(ChatFormatting.GRAY))
                : Component.translatableWithFallback("message.bsp_server_utilities.web.server_hover", "From the staff");
        MutableComponent tag = staff
                ? Component.translatableWithFallback("message.bsp_server_utilities.web.staff_line", "[Staff] %s:", by)
                : Component.translatableWithFallback("message.bsp_server_utilities.web.server_line", "[Server]");
        tag.withStyle(s -> s.withColor(TextColor.fromRgb(staff ? STAFF_COLOUR : SERVER_COLOUR)));
        return Component.empty().withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)))
                .append(tag).append(Component.literal(" " + text).withStyle(ChatFormatting.WHITE));
    }

    static WebActions.Answer say(MinecraftServer server, JsonObject a, String id, String by) {
        String voice = str(a, "voice"), rank = str(a, "rank"), raw = str(a, "text");
        if (!voice.equals("staff") && !voice.equals("server")) {
            return new WebActions.Answer(id, false, "Speak as yourself or as [Server].");
        }
        if (voice.equals("server") && !rank.equals("ADMIN") && !rank.equals("OWNER")) {
            return new WebActions.Answer(id, false, "Only Admins and Owners speak as [Server]."); // the panel checks too (D307)
        }
        if (!by.matches("[A-Za-z0-9_]{1,16}")) {
            return new WebActions.Answer(id, false, "The staff member's Minecraft name is not a name.");
        }
        if (raw.length() > MAX_TEXT) {
            return new WebActions.Answer(id, false, "At most " + MAX_TEXT + " characters.");
        }
        String text = clean(raw);
        if (text.isEmpty()) {
            return new WebActions.Answer(id, false, "There is nothing to say.");
        }
        Component line = line(voice, by, text);
        Collection<ServerPlayer> here = players.apply(server);
        here.forEach(p -> send.accept(p, line));
        server.sendSystemMessage(line); // its console and log
        String where = Storage.currentServerId();
        ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "chat " + (voice.equals("server") ? "as [Server] " : "") + "on " + where + ": " + text));
        return new WebActions.Answer(id, true, "Shown to " + here.size() + (here.size() == 1 ? " player" : " players") + " on " + where + ".");
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    // ------------------------------------------------------------------ tests

    /**
     * Tests: the clock, who is on this server, how a said line reaches them, and where captured lines go instead of Redis
     * and the table (null: there).
     */
    public static void useForTests(@Nullable LongSupplier testClock, @Nullable Function<MinecraftServer, Collection<ServerPlayer>> who,
                                   @Nullable java.util.function.BiConsumer<ServerPlayer, Component> shown, @Nullable Consumer<String> captured) {
        if (testClock != null) {
            clock = testClock;
        }
        if (who != null) {
            players = who;
        }
        if (shown != null) {
            send = shown;
        }
        sink = captured;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        players = s -> List.copyOf(s.getPlayerList().getPlayers());
        send = ServerPlayer::sendSystemMessage;
        sink = null;
    }
}
