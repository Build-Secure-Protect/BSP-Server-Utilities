package com.mrgregles.bsp_server_utilities.afk;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.chunks.ChunkService;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.network.AfkCheckPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * AFK detection (owner decisions D70 to D77, D83 to D85) with the presence check and macros (D207 to D224), on the
 * server thread.
 *
 * <ul>
 *   <li><b>Active</b> is turning the head, or anything the server sees a player do: chat, a command, breaking, placing,
 *   using an item or block, attacking, clicking in a container, dropping, steering a vehicle. Being moved by water, a
 *   minecart or a push is not (D70). A player held by the rules popup is never idle. Every input goes through
 *   {@link #input}, which also keeps it for the player's {@link MacroWatch}.</li>
 *   <li><b>Macros</b> (with the macros switch on): while a pattern holds, the inputs it is made of do not count as
 *   activity, so the player goes AFK and is checked like anyone idle (D217). A correct answer clears them for
 *   {@value #CLEAR_MS} ms (D214).</li>
 *   <li>After {@code afkMinutes} with nothing active a player is AFK and told so; their next action brings them back
 *   (D71, D73). {@code /afk} marks them at once (D77). Staff who are AFK show as away on the staff panels (D72), and
 *   everyone AFK counts as away for chunk unloading (D210).</li>
 *   <li>With kicking on, a player AFK for {@code kickMinutes} gets the presence check instead of a kick, warned a minute
 *   before (D216): a card with a word to click among three keys, for {@value #CHECK_MS} ms. The right key brings them
 *   back; a wrong one gives a new word and order, the time running on (D223); no answer kicks them with the message,
 *   or for staff leaves them AFK (D218). Admins and operators are never checked (D212). Each unanswered check is an
 *   Action Log entry saying what was seen (D213). With kicking off there are no checks (D219).</li>
 *   <li>The settings are one set for the network (table and a {@value #KIND_SETTINGS} notice, the newest revision wins);
 *   each server sends who is AFK on it on every change and every {@value #HEARTBEAT_TICKS} ticks, for the AFK tab.</li>
 * </ul>
 */
public final class AfkService {
    public static final String KIND_SETTINGS = "afk_settings", KIND_PRESENCE = "afk_presence", CHECKER = "AFK check";
    public static final int CHECK_TICKS = 20, HEARTBEAT_TICKS = 600, WORDS = 8, KEYS = 3;
    public static final long STALE_MS = 90_000L, WARN_MS = 60_000L, CHECK_MS = 120_000L, CLEAR_MS = 3_600_000L;
    /** How far (in degrees) the head must turn to count. */
    private static final float TURN = 0.5f;

    private static final class Track {
        long lastActive, afkSince, clearedUntil;
        boolean warned, checkedOut, macro;
        float yaw, pitch;
        MacroWatch watch;
        @Nullable
        MacroWatch.Pattern pattern;
        @Nullable
        Check check;
    }

    private static final class Check {
        final int id;
        final long deadline;
        final boolean kick;
        int round, word;
        final int[] keys = new int[KEYS];

        Check(int id, long deadline, boolean kick) {
            this.id = id;
            this.deadline = deadline;
            this.kick = kick;
        }
    }

    /** A check under way, as the player was sent it (tests). */
    public record CheckView(int id, int round, int word, List<Integer> keys, long msLeft, boolean kick) {
    }

    /**
     * One AFK player as a server reports them: how long they have been AFK, whether they can never be kicked, whether a
     * macro pattern holds and what was seen, and how long is left of a check under way (0 when none).
     */
    public record Afk(UUID id, String name, long afkForMs, boolean exempt, boolean macro, long checkLeftMs, String seen) {
    }

    /** One AFK player anywhere on the network, for the AFK tab. */
    public record Row(UUID id, String name, String server, long afkForMs, boolean exempt, boolean macro, long checkLeftMs, String seen) {
    }

    private record Remote(long atMs, List<Afk> afk) {
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    private static final Map<String, Remote> REMOTE = new HashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Random RANDOM = new Random();
    private static long ticks;
    private static int checks;
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static Supplier<Collection<ServerPlayer>> playersOverride;
    private static BiConsumer<ServerPlayer, Component> kicker = com.mrgregles.bsp_server_utilities.web.ProxyKick::kick; // off the network (D254)
    private static BiConsumer<ServerPlayer, AfkCheckPacket> sender = ServerUtilsNetwork::sendTo;
    private static Predicate<ServerPlayer> staff = Staff::isStaff;

    private AfkService() {
    }

    /** Called when who is AFK, or the settings, may have changed (the AFK tab follows). */
    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ settings

    public static AfkSettings settings(MinecraftServer server) {
        return AfkData.get(server).settings();
    }

    /** Replaces the settings for the whole network. */
    public static AfkSettings setSettings(MinecraftServer server, AfkSettings s) {
        com.mrgregles.bsp_server_utilities.setup.SetupService.touched(server, com.mrgregles.bsp_server_utilities.setup.Feature.AFK); // changed in its tab: set up (D152)
        AfkData data = AfkData.get(server);
        AfkSettings next = s.withRevision(Math.max(System.currentTimeMillis(), data.settings().revision() + 1)).sanitised();
        data.setSettings(next);
        Storage st = Storage.active();
        if (st != null) {
            st.submit(() -> st.backend().saveAfkSettings(next));
            st.publish(KIND_SETTINGS, next.encode());
        }
        changed();
        return next;
    }

    /** Takes a shared copy of the settings when it is newer. */
    public static void apply(MinecraftServer server, AfkSettings shared) {
        AfkData data = AfkData.get(server);
        if (shared.revision() > data.settings().revision()) {
            data.setSettings(shared);
            changed();
        }
    }

    // ------------------------------------------------------------------ activity

    private static Collection<ServerPlayer> localPlayers(MinecraftServer server) {
        return playersOverride != null ? playersOverride.get() : server.getPlayerList().getPlayers();
    }

    private static Track track(ServerPlayer p) {
        return TRACKS.computeIfAbsent(p.getUUID(), id -> {
            Track t = new Track();
            t.lastActive = clock.getAsLong();
            t.yaw = p.getYRot();
            t.pitch = p.getXRot();
            t.watch = new MacroWatch(t.lastActive);
            return t;
        });
    }

    public static boolean isAfk(UUID player) {
        Track t = TRACKS.get(player);
        return t != null && t.afkSince > 0;
    }

    /** How long the player has been AFK; 0 when they are not. */
    public static long afkForMs(UUID player) {
        Track t = TRACKS.get(player);
        return t == null || t.afkSince == 0 ? 0 : Math.max(0, clock.getAsLong() - t.afkSince);
    }

    /** Whether a macro pattern holds for the player now (tests, the AFK tab). */
    public static boolean suspected(UUID player) {
        Track t = TRACKS.get(player);
        return t != null && t.pattern != null;
    }

    /**
     * Something the player did ({@code kind}, and {@code target} what it was done to, or empty): kept for the macro
     * watch, then active unless it is part of a macro pattern that holds (D217).
     */
    public static void input(ServerPlayer p, String kind, String target) {
        if (!p.server.isSameThread()) {
            return; // packet handlers run first on the network thread; the server thread's run counts
        }
        Track t = track(p);
        String key = target.isEmpty() ? kind : kind + ":" + target;
        t.watch.add(new MacroWatch.Input(key, clock.getAsLong(), p.getYRot(), p.getXRot(), p.getX(), p.getY(), p.getZ()));
        if (t.pattern != null && t.pattern.keys().contains(key)) {
            return;
        }
        active(p);
    }

    /** The player is active: the idle clock starts again and, when they were AFK, they are back (a check under way ends). */
    public static void active(ServerPlayer p) {
        if (!p.server.isSameThread()) {
            return;
        }
        back(p, track(p), "message.bsp_server_utilities.afk.back");
    }

    private static void back(ServerPlayer p, Track t, String messageKey) {
        t.lastActive = clock.getAsLong();
        if (t.check != null) {
            t.check = null;
            sender.accept(p, AfkCheckPacket.HIDE);
        }
        if (t.afkSince > 0) {
            t.afkSince = 0;
            t.warned = false;
            t.checkedOut = false;
            p.sendSystemMessage(Component.translatable(messageKey).withStyle(ChatFormatting.GREEN));
            ChunkService.onActive(p); // their machines load again (D210)
            afkChanged(p.server);
        }
    }

    /** Marks the player AFK now ({@code /afk}, or idle long enough); nothing when they already are. */
    public static void markAfk(ServerPlayer p) {
        Track t = track(p);
        if (t.afkSince > 0) {
            return;
        }
        t.afkSince = clock.getAsLong();
        t.warned = false;
        p.sendSystemMessage(Component.translatable("message.bsp_server_utilities.afk.now").withStyle(ChatFormatting.GOLD));
        afkChanged(p.server);
    }

    /** Staff, admins and operators are never kicked for being AFK (D85, D218). */
    public static boolean exempt(ServerPlayer p) {
        return staff.test(p) || Perms.canEdit(p);
    }

    /** Admins and operators are never checked (D212). */
    public static boolean neverChecked(ServerPlayer p) {
        return Perms.canEdit(p);
    }

    /** Once a second: head turns and steering, the macro watch, then who has gone AFK, who is warned and who is checked. */
    public static void check(MinecraftServer server) {
        long now = clock.getAsLong();
        AfkSettings s = settings(server);
        for (ServerPlayer p : List.copyOf(localPlayers(server))) {
            Track t = track(p);
            if (RulesService.isHeld(p.getUUID())) {
                t.lastActive = now; // reading the rules is not idling
                continue;
            }
            float yaw = p.getYRot(), pitch = p.getXRot();
            boolean turned = Math.abs(Mth.wrapDegrees(yaw - t.yaw)) > TURN || Math.abs(pitch - t.pitch) > TURN;
            t.yaw = yaw;
            t.pitch = pitch;
            if (turned) {
                input(p, "turn", MacroWatch.angles(yaw, pitch));
            }
            if (p.isPassenger() && (p.xxa != 0 || p.zza != 0)) {
                input(p, "steer", "");
            }
            if (s.enabled() && s.macros() && now >= t.clearedUntil) {
                t.pattern = t.watch.find(now);
            } else {
                t.watch.prune(now);
                t.pattern = null;
            }
            if (t.macro != (t.pattern != null)) {
                t.macro = t.pattern != null;
                if (t.afkSince > 0) {
                    afkChanged(server); // the AFK tab's "macro?"
                }
            }
            if (!s.enabled()) {
                endCheck(server, p, t);
                continue;
            }
            if (t.afkSince == 0 && now - t.lastActive >= s.afkMs()) {
                markAfk(p);
            }
            if (t.check != null) {
                if (!s.kick() || neverChecked(p)) {
                    endCheck(server, p, t);
                } else if (now >= t.check.deadline) {
                    unanswered(server, p, t, s, now);
                }
                continue;
            }
            if (s.kick() && t.afkSince > 0 && !t.checkedOut && !neverChecked(p)) {
                long afkFor = now - t.afkSince;
                if (afkFor >= s.kickMs()) {
                    startCheck(server, p, t, now);
                } else if (afkFor >= s.kickMs() - WARN_MS && !t.warned) {
                    t.warned = true;
                    p.sendSystemMessage(Component.translatable("message.bsp_server_utilities.afk.warn").withStyle(ChatFormatting.RED));
                }
            }
        }
    }

    /** Whether the player has been warned of a check (tests). */
    public static boolean warned(UUID player) {
        Track t = TRACKS.get(player);
        return t != null && t.warned;
    }

    // ------------------------------------------------------------------ the presence check

    private static void startCheck(MinecraftServer server, ServerPlayer p, Track t, long now) {
        Check c = new Check(++checks, now + CHECK_MS, !staff.test(p));
        newWord(c);
        t.check = c;
        send(p, c, now, false);
        p.sendSystemMessage(Component.translatable(c.kick ? "message.bsp_server_utilities.afk.check" : "message.bsp_server_utilities.afk.check_staff").withStyle(ChatFormatting.RED));
        afkChanged(server);
    }

    /** A new word (never the last one) and a new order of the three keys. */
    private static void newWord(Check c) {
        int last = c.round == 0 ? -1 : c.word;
        c.round++;
        do {
            c.word = RANDOM.nextInt(WORDS);
        } while (c.word == last);
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < WORDS; i++) {
            if (i != c.word) {
                pool.add(i);
            }
        }
        java.util.Collections.shuffle(pool, RANDOM);
        List<Integer> keys = new ArrayList<>(List.of(c.word, pool.get(0), pool.get(1)));
        java.util.Collections.shuffle(keys, RANDOM);
        for (int i = 0; i < KEYS; i++) {
            c.keys[i] = keys.get(i);
        }
    }

    private static void send(ServerPlayer p, Check c, long now, boolean wrong) {
        sender.accept(p, new AfkCheckPacket(c.id, c.round, List.of(c.keys[0], c.keys[1], c.keys[2]), c.word, Math.max(0, c.deadline - now), c.kick, wrong));
    }

    private static void endCheck(MinecraftServer server, ServerPlayer p, Track t) {
        if (t.check != null) {
            t.check = null;
            sender.accept(p, AfkCheckPacket.HIDE);
            afkChanged(server);
        }
    }

    /**
     * A key clicked on the card: the right word for the check and round under way, in time, brings the player back and
     * clears them for an hour (D214); a wrong one gives a new word and order (D223). Anything else is ignored.
     */
    public static boolean answer(ServerPlayer p, int id, int round, int word) {
        Track t = TRACKS.get(p.getUUID());
        Check c = t == null ? null : t.check;
        long now = clock.getAsLong();
        if (c == null || c.id != id || c.round != round || now >= c.deadline) {
            return false;
        }
        if (word != c.word) {
            newWord(c);
            send(p, c, now, true);
            return false;
        }
        t.clearedUntil = now + CLEAR_MS;
        t.pattern = null;
        back(p, t, "message.bsp_server_utilities.afk.thanks");
        return true;
    }

    /** No answer in time: an Action Log entry with what was seen (D213), then the kick, or for staff AFK they stay (D218). */
    private static void unanswered(MinecraftServer server, ServerPlayer p, Track t, AfkSettings s, long now) {
        String seen = t.pattern != null ? t.pattern.seen(now) : "no input for " + Math.max(1, Math.round((now - t.lastActive) / 60_000.0)) + " min";
        boolean kick = !staff.test(p);
        ActionLog.record(server, ActionLog.entryForServer(CHECKER, LogEntry.AFK, p.getGameProfile().getName() + " did not answer the presence check: " + seen
                + (kick ? "" : ". Left AFK: staff are never kicked")));
        if (kick) {
            TRACKS.remove(p.getUUID());
            kicker.accept(p, Component.literal(s.message()));
            afkChanged(server);
        } else {
            t.checkedOut = true;
            endCheck(server, p, t);
        }
    }

    /** The check under way for the player, as they were last sent it; null when none (tests). */
    @Nullable
    public static CheckView checkFor(UUID player) {
        Track t = TRACKS.get(player);
        Check c = t == null ? null : t.check;
        return c == null ? null : new CheckView(c.id, c.round, c.word, List.of(c.keys[0], c.keys[1], c.keys[2]), Math.max(0, c.deadline - clock.getAsLong()), c.kick);
    }

    // ------------------------------------------------------------------ who is AFK, across the network

    private static void afkChanged(MinecraftServer server) {
        StaffDirectory.awayChanged(server);
        publish(server, null);
        changed();
    }

    public static List<Afk> localAfk(MinecraftServer server) {
        return localAfk(server, null);
    }

    private static List<Afk> localAfk(MinecraftServer server, @Nullable UUID leaving) {
        long now = clock.getAsLong();
        List<Afk> out = new ArrayList<>();
        for (ServerPlayer p : localPlayers(server)) {
            Track t = TRACKS.get(p.getUUID());
            if (t != null && t.afkSince > 0 && !p.getUUID().equals(leaving)) {
                long left = t.check == null ? 0 : Math.max(1, t.check.deadline - now);
                out.add(new Afk(p.getUUID(), p.getGameProfile().getName(), Math.max(0, now - t.afkSince), exempt(p), t.pattern != null, left,
                        t.pattern == null ? "" : t.pattern.seen(now)));
            }
        }
        return out;
    }

    private static void publish(MinecraftServer server, @Nullable UUID leaving) {
        Storage s = Storage.active();
        if (s != null) {
            s.publish(KIND_PRESENCE, encode(s.serverId(), localAfk(server, leaving)));
        }
    }

    public static String encode(String serverId, List<Afk> afk) {
        StringBuilder b = new StringBuilder(serverId);
        for (Afk a : afk) {
            b.append('\n').append(a.id()).append('|').append(a.name()).append('|').append(a.afkForMs()).append('|').append(a.exempt() ? '1' : '0')
                    .append('|').append(a.macro() ? '1' : '0').append('|').append(a.checkLeftMs()).append('|').append(a.seen().replace('|', '/').replace('\n', ' '));
        }
        return b.toString();
    }

    /** Reads an AFK notice (lines of 4 fields from servers before the presence check); null when it is not one. */
    @Nullable
    public static Map.Entry<String, List<Afk>> decode(String payload) {
        String[] lines = payload.split("\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return null;
        }
        List<Afk> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            if (f.length == 4 || f.length == 7) {
                try {
                    boolean full = f.length == 7;
                    out.add(new Afk(UUID.fromString(f[0]), f[1], Long.parseLong(f[2]), f[3].equals("1"), full && f[4].equals("1"), full ? Long.parseLong(f[5]) : 0, full ? f[6] : ""));
                } catch (IllegalArgumentException ignored) {
                    // a bad line
                }
            }
        }
        return Map.entry(lines[0], out);
    }

    /** Another server's list of AFK players. */
    public static void applyPresence(String payload) {
        Map.Entry<String, List<Afk>> e = decode(payload);
        if (e != null && !e.getKey().equals(Storage.currentServerId())) {
            REMOTE.put(e.getKey(), new Remote(clock.getAsLong(), List.copyOf(e.getValue())));
            changed();
        }
    }

    /** Everyone AFK on the network (lists older than {@value #STALE_MS} ms left out), longest AFK first. */
    public static List<Row> afkNow(MinecraftServer server) {
        String here = Storage.currentServerId();
        long now = clock.getAsLong();
        List<Row> out = new ArrayList<>();
        for (Afk a : localAfk(server)) {
            out.add(new Row(a.id(), a.name(), here, a.afkForMs(), a.exempt(), a.macro(), a.checkLeftMs(), a.seen()));
        }
        REMOTE.forEach((srv, r) -> {
            if (now - r.atMs() <= STALE_MS) {
                long age = now - r.atMs();
                for (Afk a : r.afk()) {
                    out.add(new Row(a.id(), a.name(), srv, a.afkForMs() + age, a.exempt(), a.macro(), a.checkLeftMs() == 0 ? 0 : Math.max(1, a.checkLeftMs() - age), a.seen()));
                }
            }
        });
        out.sort(Comparator.comparingLong(Row::afkForMs).reversed());
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void onLogin(ServerPlayer p) {
        TRACKS.remove(p.getUUID());
        track(p);
    }

    public static void onLogout(ServerPlayer p) {
        boolean was = isAfk(p.getUUID());
        TRACKS.remove(p.getUUID());
        if (was) {
            publish(p.server, p.getUUID());
            changed();
        }
    }

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_SETTINGS, (srv, n) -> {
            AfkSettings s = AfkSettings.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
        Storage.on(KIND_PRESENCE, (srv, n) -> applyPresence(n.payload()));
    }

    /** The actions that count as active (D70), each with what it was done to; once, in the mod constructor. Container clicks come through a mixin. */
    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((ServerChatEvent e) -> input(e.getPlayer(), "chat", ""));
        MinecraftForge.EVENT_BUS.addListener((CommandEvent e) -> {
            ServerPlayer p = e.getParseResults().getContext().getSource().getPlayer();
            String root = LogText.root(e.getParseResults().getReader().getString());
            if (p != null && !root.equals("afk")) {
                input(p, "cmd", root);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getPlayer() instanceof ServerPlayer p) {
                input(p, "break", block(e.getState()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                input(p, "place", block(e.getPlacedBlock()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> interacted(e, "block", block(e.getLevel().getBlockState(e.getPos()))));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem e) -> interacted(e, "item", item(e.getItemStack())));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock e) -> interacted(e, "hit", block(e.getLevel().getBlockState(e.getPos()))));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract e) -> interacted(e, "entity", entity(e.getTarget())));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteractSpecific e) -> interacted(e, "entity", entity(e.getTarget())));
        MinecraftForge.EVENT_BUS.addListener((AttackEntityEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                input(p, "attack", entity(e.getTarget()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ItemTossEvent e) -> {
            if (e.getPlayer() instanceof ServerPlayer p) {
                input(p, "toss", item(e.getEntity().getItem()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerContainerEvent.Open e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                input(p, "open", "");
            }
        });
    }

    private static void interacted(PlayerInteractEvent e, String kind, String target) {
        if (e.getEntity() instanceof ServerPlayer p) {
            input(p, kind, target);
        }
    }

    private static String block(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String item(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String entity(@Nullable Entity e) {
        return e == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** At server start, after the storage: the shared settings (or ours written as the first). */
    public static void onServerStarted(MinecraftServer server) {
        TRACKS.clear();
        REMOTE.clear();
        StaffDirectory.setAutoAway(AfkService::isAfk);
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                AfkSettings shared = s.backend().loadAfkSettings();
                if (shared == null) {
                    s.backend().saveAfkSettings(settings(server));
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % CHECK_TICKS == 0) {
            check(server);
        }
        if (ticks % HEARTBEAT_TICKS == 0) {
            publish(server, null);
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock, @Nullable Supplier<Collection<ServerPlayer>> players, @Nullable BiConsumer<ServerPlayer, Component> kick) {
        clock = testClock;
        playersOverride = players;
        if (kick != null) {
            kicker = kick;
        }
    }

    /** The kick as an unanswered check makes it (tests). */
    public static void kickForTests(ServerPlayer p, Component why) {
        kicker.accept(p, why);
    }

    /** Where check cards go instead of the player's connection (tests). */
    public static void sendChecksForTests(BiConsumer<ServerPlayer, AfkCheckPacket> to) {
        sender = to;
    }

    /** Who counts as staff instead of the staff groups (tests). */
    public static void staffForTests(Predicate<ServerPlayer> isStaff) {
        staff = isStaff;
    }

    public static void useReal() {
        staff = Staff::isStaff;
        clock = System::currentTimeMillis;
        playersOverride = null;
        kicker = com.mrgregles.bsp_server_utilities.web.ProxyKick::kick;
        sender = ServerUtilsNetwork::sendTo;
    }

    public static void forget(UUID player) {
        TRACKS.remove(player);
    }
}
