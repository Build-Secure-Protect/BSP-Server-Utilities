package com.mrgregles.bsp_server_utilities.check;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The client check on this server (owner decisions D120 to D141).
 *
 * <ul>
 *   <li>Every connecting client is asked for its report in Forge's login handshake, and the login is held (a negotiation
 *   future Forge waits on) until the answer has been judged on the server thread: a refused player sees the reason on
 *   the "failed to connect" screen and never enters the world. A client that does not answer within
 *   {@value #ANSWER_SECONDS} seconds is turned away.</li>
 *   <li>The rules and the trusted setup in force are the network's, or this server's own when it has switched (D134);
 *   the network's are shared through the table and notices, the newest revision winning.</li>
 *   <li>A report sent again in game (resources reloaded, D127) kicks with the same message only for something it did
 *   not have before; a change of rules or setup judges everyone here again without kicking anyone (D139, D141): what
 *   would now refuse them shows as blocked since they joined.</li>
 *   <li>A player whose brightness is past the top is told once per join that staff can see it (D133).</li>
 *   <li>Each server tells the others about its players' reports (on change, gone on leaving, a list every 30 seconds) so
 *   the CLIENTS tab shows the network; refusals and kicks go to the Action Log and to every server's list of today's
 *   refusals (D138, D140); a staff kick reaches the server the player is on.</li>
 *   <li>TRUST MY SETUP takes the admin's own report; it and {@code /su clientcheck export} write
 *   {@code config/bsp_server_utilities/trusted_setup.json}, which a server reads at start-up and on
 *   {@code /su clientcheck reload} when it is newer than the setup in use (D132).</li>
 * </ul>
 */
public final class ClientCheckService {
    public static final String KIND_RULES = "check_rules", KIND_SETUP = "check_setup", KIND_REPORT = "check_report", KIND_GONE = "check_gone",
            KIND_PRESENT = "check_present", KIND_REFUSED = "check_refused", KIND_KICK = "check_kick";
    public static final int ANSWER_SECONDS = 20, MAX_REFUSALS = 20, MAX_REASON = 160;
    /** At most one report in game per player per second (a reload sends one; anything faster is ignored). */
    static final long REPORT_GAP_MS = 1000;
    static final int HEARTBEAT_TICKS = 600;
    static final long STALE_MS = 90_000;
    static final String CHECKER = "Client check";

    /** A connection waiting for its answer: who is logging in and the future Forge's handshake waits on. */
    private record Pending(GameProfile profile, CompletableFuture<Void> done) {
    }

    /** A player's latest report, with Forge's mod list from their handshake, when it came and what was made of it. */
    public record Held(UUID id, String name, ClientReport report, List<String> forgeMods, long atMs, Verdict verdict) {
        Held with(Verdict v) {
            return new Held(id, name, report, forgeMods, atMs, v);
        }
    }

    /** A player on the network, for the tab: where they are and their latest report. */
    public record Row(String server, Held held) {
    }

    /** A refusal (or a kick by the check), for today's list. */
    public record Refusal(long atMs, String server, String name, String reason) {
    }

    private record Remote(String server, Held held) {
    }

    /** What reading the setup file did. */
    public enum FileResult { LOADED, OLDER, NONE, BAD }

    private static final Map<Connection, Pending> PENDING = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<UUID, Held> REPORTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Remote> REMOTE = new HashMap<>();
    private static final Map<String, Long> SERVER_SEEN = new HashMap<>();
    private static final Deque<Refusal> REFUSALS = new ArrayDeque<>();
    private static final Set<UUID> TOLD = ConcurrentHashMap.newKeySet();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static Supplier<Collection<ServerPlayer>> playersOverride;
    private static BiConsumer<ServerPlayer, Component> kicker = (p, why) -> p.connection.disconnect(why);
    @Nullable
    private static Path dirOverride;
    private static long ticks;

    private ClientCheckService() {
    }

    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ rules and setup

    public static boolean useOwn(MinecraftServer server) {
        return CheckData.get(server).useOwn();
    }

    /** The rules in force here. */
    public static CheckRules rules(MinecraftServer server) {
        CheckData d = CheckData.get(server);
        return d.useOwn() ? d.ownRules() : d.networkRules();
    }

    /** The trusted setup in force here, or null when none was taken. */
    @Nullable
    public static TrustedSetup setup(MinecraftServer server) {
        CheckData d = CheckData.get(server);
        return d.useOwn() ? d.ownSetup() : d.networkSetup();
    }

    /** Replaces the rules in force: the network's (saved and announced), or this server's own. */
    public static CheckRules setRules(MinecraftServer server, CheckRules r) {
        com.mrgregles.bsp_server_utilities.setup.SetupService.touched(server, com.mrgregles.bsp_server_utilities.setup.Feature.CHECK); // changed in its tab: set up (D152)
        CheckData d = CheckData.get(server);
        CheckRules next = r.withRevision(Math.max(clock.getAsLong(), rules(server).revision() + 1)).sanitised();
        if (d.useOwn()) {
            d.setOwnRules(next);
        } else {
            d.setNetworkRules(next);
            Storage st = Storage.active();
            if (st != null) {
                st.submit(() -> st.backend().saveCheckRules(next));
                st.publish(KIND_RULES, next.encode());
            }
        }
        rejudge(server);
        return next;
    }

    public static void applyRules(MinecraftServer server, CheckRules shared) {
        CheckData d = CheckData.get(server);
        if (shared.revision() > d.networkRules().revision()) {
            d.setNetworkRules(shared);
            if (!d.useOwn()) {
                rejudge(server);
            }
        }
    }

    /** Replaces the trusted setup in force: the network's (saved and announced), or this server's own. */
    public static TrustedSetup setSetup(MinecraftServer server, TrustedSetup t) {
        com.mrgregles.bsp_server_utilities.setup.SetupService.touched(server, com.mrgregles.bsp_server_utilities.setup.Feature.CHECK); // changed in its tab: set up (D152)
        CheckData d = CheckData.get(server);
        TrustedSetup old = setup(server);
        TrustedSetup next = t.withRevision(Math.max(clock.getAsLong(), old == null ? 0 : old.revision() + 1));
        if (d.useOwn()) {
            d.setOwnSetup(next);
        } else {
            d.setNetworkSetup(next);
            Storage st = Storage.active();
            if (st != null) {
                st.submit(() -> st.backend().saveTrustedSetup(next));
                st.publish(KIND_SETUP, next.toJson());
            }
        }
        rejudge(server);
        return next;
    }

    public static void applySetup(MinecraftServer server, TrustedSetup shared) {
        CheckData d = CheckData.get(server);
        TrustedSetup mine = d.networkSetup();
        if (mine == null || shared.revision() > mine.revision()) {
            d.setNetworkSetup(shared);
            if (!d.useOwn()) {
                rejudge(server);
            }
        }
    }

    /** Switches between the network's rules and this server's own; own rules start as a copy of the network's (D134). */
    public static void setUseOwn(MinecraftServer server, boolean own) {
        com.mrgregles.bsp_server_utilities.setup.SetupService.touched(server, com.mrgregles.bsp_server_utilities.setup.Feature.CHECK); // changed in its tab: set up (D152)
        CheckData d = CheckData.get(server);
        if (d.useOwn() == own) {
            return;
        }
        if (own) {
            d.setOwnRules(d.networkRules());
            d.setOwnSetup(d.networkSetup());
        }
        d.setUseOwn(own);
        rejudge(server);
    }

    /** TRUST MY SETUP: the admin's own report becomes the trusted setup, and the file is written. False without a report. */
    public static boolean trust(MinecraftServer server, ServerPlayer admin) {
        Held h = REPORTS.get(admin.getUUID());
        if (h == null) {
            return false;
        }
        setSetup(server, TrustedSetup.of(h.report(), admin.getGameProfile().getName(), clock.getAsLong()));
        exportFile(server);
        return true;
    }

    /** Adds an entry to the block list in force (BLOCK from the tab); nobody is kicked by it (D139). */
    public static void block(MinecraftServer server, CheckRules.Entry e) {
        CheckRules r = rules(server);
        List<CheckRules.Entry> b = new ArrayList<>(r.block());
        b.add(e);
        setRules(server, r.withBlock(b));
    }

    // ------------------------------------------------------------------ the file (D132)

    public static Path file() {
        Path dir = dirOverride != null ? dirOverride : FMLPaths.CONFIGDIR.get().resolve(BSPServerUtilities.MODID);
        return dir.resolve(TrustedSetup.FILE);
    }

    /** Writes the setup in force to the file; the path, or null when there is no setup or it could not be written. */
    @Nullable
    public static Path exportFile(MinecraftServer server) {
        TrustedSetup t = setup(server);
        if (t == null) {
            return null;
        }
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, t.toFileJson(), StandardCharsets.UTF_8);
            return f;
        } catch (Exception e) {
            BSPServerUtilities.LOGGER.warn("Client check: could not write {}", f, e);
            return null;
        }
    }

    /** Reads the file: taken when it is newer than the setup in force (and shared on when that is the network's). */
    public static FileResult readFile(MinecraftServer server) {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return FileResult.NONE;
        }
        TrustedSetup t;
        try {
            t = TrustedSetup.fromJson(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            t = null;
        }
        if (t == null) {
            BSPServerUtilities.LOGGER.warn("Client check: {} is not a trusted setup", f);
            return FileResult.BAD;
        }
        TrustedSetup now = setup(server);
        if (now != null && t.takenAtMs() <= now.takenAtMs()) {
            BSPServerUtilities.LOGGER.info("Client check: {} (taken {}) is not newer than the trusted setup in use; ignored", f, Instant.ofEpochMilli(t.takenAtMs()));
            return FileResult.OLDER;
        }
        setSetup(server, t);
        BSPServerUtilities.LOGGER.info("Client check: trusted setup read from {} (taken {} by {})", f, Instant.ofEpochMilli(t.takenAtMs()), t.takenBy());
        return FileResult.LOADED;
    }

    // ------------------------------------------------------------------ the handshake

    /** Forge's handshake starts: hold the login until the answer is judged (none is asked of a singleplayer or LAN game). */
    static void onNegotiation(PlayerNegotiationEvent e) {
        Connection connection = e.getConnection();
        if (connection.isMemoryConnection() || e.getProfile() == null || Switches.off(Feature.CHECK)) {
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        PENDING.put(connection, new Pending(e.getProfile(), done));
        e.enqueueWork(done);
        CompletableFuture.delayedExecutor(ANSWER_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (PENDING.remove(connection) != null) {
                connection.disconnect(Component.translatable("message.bsp_server_utilities.check.no_answer"));
                done.complete(null);
            }
        });
    }

    /** The client's answer, on the network thread. */
    public static void onAnswer(Connection connection, ClientReport report) {
        Pending p = PENDING.remove(connection);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (p == null || server == null) {
            return;
        }
        ConnectionData data = NetworkHooks.getConnectionData(connection);
        List<String> forgeMods = data == null ? List.of() : List.copyOf(data.getModList());
        server.execute(() -> {
            try {
                Component refusal = accept(server, p.profile(), report, forgeMods);
                if (refusal != null) {
                    connection.disconnect(refusal);
                }
            } catch (RuntimeException ex) {
                BSPServerUtilities.LOGGER.error("Client check failed for {}", p.profile().getName(), ex);
            } finally {
                p.done().complete(null);
            }
        });
    }

    /**
     * Judges a login's report and keeps it: the refusal to show, or null to let them in. On the server thread. (Also the
     * way tests put a report in, without a handshake.)
     */
    @Nullable
    public static Component accept(MinecraftServer server, GameProfile profile, ClientReport report, List<String> forgeMods) {
        Verdict v = Verdict.judge(report, forgeMods, rules(server), setup(server), profile.getName());
        if (v.refused()) {
            REPORTS.remove(profile.getId());
            refused(server, profile.getName(), v.refuse(), "refused");
            return refusal(rules(server).message(), v.refuse());
        }
        REPORTS.put(profile.getId(), new Held(profile.getId(), profile.getName(), report, List.copyOf(forgeMods), clock.getAsLong(), v));
        return null;
    }

    /** In the world: the brightness line once (D133), and the other servers told. */
    public static void onLoggedIn(ServerPlayer player) {
        Held h = REPORTS.get(player.getUUID());
        if (h != null) {
            tellBright(player, h.verdict());
            publish(h);
            changed();
        }
    }

    /** A report sent again in game (D127). */
    public static void onReport(ServerPlayer player, ClientReport report) {
        if (Switches.off(Feature.CHECK)) {
            return; // turned off in the setup guide (D150)
        }
        Held old = REPORTS.get(player.getUUID());
        long now = clock.getAsLong();
        if (old != null && now - old.atMs() < REPORT_GAP_MS) {
            return;
        }
        MinecraftServer server = player.server;
        String name = player.getGameProfile().getName();
        List<String> forge = old != null ? old.forgeMods() : List.of();
        Verdict v = Verdict.judge(report, forge, rules(server), setup(server), name);
        List<Verdict.Item> fresh = new ArrayList<>(v.refuse());
        if (old != null) {
            fresh.removeAll(old.verdict().refuse());
        }
        Held h = new Held(player.getUUID(), name, report, forge, now, v);
        REPORTS.put(player.getUUID(), h);
        if (!v.exempt() && !fresh.isEmpty()) {
            refused(server, name, fresh, "removed");
            kicker.accept(player, refusal(rules(server).message(), fresh));
            return;
        }
        tellBright(player, v);
        publish(h);
        changed();
    }

    private static void tellBright(ServerPlayer player, Verdict v) {
        if (v.brightness() && TOLD.add(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.check.bright").withStyle(ChatFormatting.GOLD));
        }
    }

    /** Everyone here judged again under the rules and setup in force; nobody kicked (D139, D141). */
    public static void rejudge(MinecraftServer server) {
        CheckRules r = rules(server);
        TrustedSetup t = setup(server);
        for (Held h : List.copyOf(REPORTS.values())) {
            Held next = h.with(Verdict.judge(h.report(), h.forgeMods(), r, t, h.name()));
            REPORTS.put(h.id(), next);
            if (localPlayer(server, h.id()) != null) {
                publish(next);
            }
        }
        changed();
    }

    /** The message a refused player reads (D128): the admin's text, the list where {@code <list>} stands. */
    public static Component refusal(String message, List<Verdict.Item> items) {
        MutableComponent list = Component.empty();
        for (Verdict.Item i : items) {
            list.append(Component.literal("\n"));
            list.append(Component.literal(i.name().isEmpty() ? "?" : i.name()).withStyle(ChatFormatting.GOLD));
            list.append(Component.literal(" (").withStyle(ChatFormatting.GRAY));
            list.append(Component.translatable(kindKey(i)).withStyle(ChatFormatting.GRAY));
            list.append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }
        list.append(Component.literal("\n"));
        int at = message.indexOf(CheckRules.LIST);
        String before = at < 0 ? message : message.substring(0, at);
        String after = at < 0 ? "" : message.substring(at + CheckRules.LIST.length());
        if (after.startsWith(".")) {
            after = after.substring(1); // "with: <list>. Remove": the list ends its own line
        }
        return Component.empty().append(Component.literal(before.strip()).withStyle(ChatFormatting.RED)).append(list)
                .append(Component.literal(after.strip()).withStyle(ChatFormatting.WHITE));
    }

    private static String kindKey(Verdict.Item i) {
        if (i.what() == Verdict.What.MOD) {
            return "message.bsp_server_utilities.check.kind.mod";
        }
        if (i.what() == Verdict.What.PACK) {
            return "message.bsp_server_utilities.check.kind.pack";
        }
        if (i.what() == Verdict.What.SHADER) {
            return "message.bsp_server_utilities.check.kind.shader";
        }
        if (i.name().isEmpty()) {
            return "message.bsp_server_utilities.check.kind.stone_unknown";
        }
        return "message.bsp_server_utilities.check.kind.stone";
    }

    /** The same list as plain text, for the log and today's refusals. */
    public static String describe(List<Verdict.Item> items) {
        List<String> out = new ArrayList<>();
        for (Verdict.Item i : items) {
            String what = switch (i.what()) {
                case MOD -> "mod";
                case PACK -> "resource pack";
                case SHADER -> "shader pack";
                case STONE -> "see-through stone";
            };
            out.add((i.name().isEmpty() ? "resource packs" : i.name()) + " (" + what + ")");
        }
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ refusals and kicks

    private static void refused(MinecraftServer server, String name, List<Verdict.Item> items, String verb) {
        String reason = describe(items);
        Refusal r = new Refusal(clock.getAsLong(), Storage.currentServerId(), name, reason);
        addRefusal(r);
        Storage st = Storage.active();
        if (st != null) {
            st.publish(KIND_REFUSED, r.atMs() + "\n" + r.server() + "\n" + r.name() + "\n" + r.reason());
        }
        ActionLog.record(server, ActionLog.entryForServer(CHECKER, LogEntry.CHECK, verb + " " + name + ": " + reason));
        changed();
    }

    private static void addRefusal(Refusal r) {
        synchronized (REFUSALS) {
            REFUSALS.addFirst(r);
            while (REFUSALS.size() > 200) {
                REFUSALS.removeLast();
            }
        }
    }

    /** Today's refusals across the network (since midnight on this server's clock), newest first, up to 20 (D140). */
    public static List<Refusal> refusedToday() {
        long now = clock.getAsLong();
        ZoneId zone = ZoneId.systemDefault();
        long midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
        synchronized (REFUSALS) {
            return REFUSALS.stream().filter(r -> r.atMs() >= midnight).sorted(Comparator.comparingLong(Refusal::atMs).reversed()).limit(MAX_REFUSALS).toList();
        }
    }

    /** A typed reason kept to the tab's length, without line breaks or formatting codes. */
    public static String cleanReason(@Nullable String s) {
        String t = s == null ? "" : s.replaceAll("\\u00a7.?", "").replaceAll("[\\u0000-\\u001f]", " ").strip();
        return t.length() <= MAX_REASON ? t : t.substring(0, MAX_REASON);
    }

    private static Component staffKick(String why) {
        return Component.empty().append(Component.translatable("message.bsp_server_utilities.check.removed_by_staff").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("\n" + why).withStyle(ChatFormatting.WHITE));
    }

    /** A staff kick with a typed reason (D136), here or on the server the player is on. False when nobody was found. */
    public static boolean kickByStaff(MinecraftServer server, @Nullable ServerPlayer admin, UUID target, String reason) {
        String why = cleanReason(reason);
        String by = admin == null ? "Console" : admin.getGameProfile().getName();
        ServerPlayer p = localPlayer(server, target);
        if (p != null) {
            logKick(server, admin, by, p.getGameProfile().getName(), why);
            kicker.accept(p, staffKick(why));
            return true;
        }
        Remote r = REMOTE.get(target);
        Storage st = Storage.active();
        if (r == null || st == null) {
            return false;
        }
        st.publish(KIND_KICK, target + "\n" + why);
        logKick(server, admin, by, r.held().name(), why);
        return true;
    }

    private static void logKick(MinecraftServer server, @Nullable ServerPlayer admin, String by, String name, String why) {
        String text = "kicked " + name + (why.isEmpty() ? "" : ": " + why);
        Optional<Staff.Role> role = admin == null ? Optional.empty() : ActionLog.tracked(admin);
        ActionLog.record(server, admin != null && role.isPresent() ? ActionLog.entryFor(admin, role.get(), LogEntry.CHECK, text) : ActionLog.entryForServer(by, LogEntry.CHECK, text));
    }

    // ------------------------------------------------------------------ across the network

    private static void publish(Held h) {
        Storage st = Storage.active();
        if (st != null) {
            st.publish(KIND_REPORT, String.join("\n", st.serverId(), h.id().toString(), h.name(), Long.toString(h.atMs()), h.verdict().encode(),
                    h.report().toText(), String.join(",", h.forgeMods())));
        }
    }

    static void applyReport(String payload) {
        String[] f = payload.split("\n", -1);
        if (f.length != 7 || f[0].equals(Storage.currentServerId())) {
            return;
        }
        try {
            Verdict v = Verdict.decode(f[4]);
            ClientReport r = ClientReport.fromText(f[5]);
            if (v == null || r == null) {
                return;
            }
            UUID id = UUID.fromString(f[1]);
            List<String> forge = f[6].isEmpty() ? List.of() : List.of(f[6].split(","));
            REMOTE.put(id, new Remote(f[0], new Held(id, f[2], r, forge, Long.parseLong(f[3]), v)));
            SERVER_SEEN.put(f[0], clock.getAsLong());
            changed();
        } catch (IllegalArgumentException ignored) {
            // a bad notice
        }
    }

    static void applyGone(String payload) {
        String[] f = payload.split("\n", -1);
        if (f.length != 2) {
            return;
        }
        try {
            UUID id = UUID.fromString(f[1]);
            Remote r = REMOTE.get(id);
            if (r != null && r.server().equals(f[0])) {
                REMOTE.remove(id);
                changed();
            }
        } catch (IllegalArgumentException ignored) {
            // a bad notice
        }
    }

    static void applyPresent(String payload) {
        String[] f = payload.split("\n", -1);
        if (f.length != 2 || f[0].equals(Storage.currentServerId())) {
            return;
        }
        Set<String> there = f[1].isEmpty() ? Set.of() : Set.of(f[1].split(","));
        SERVER_SEEN.put(f[0], clock.getAsLong());
        if (REMOTE.entrySet().removeIf(e -> e.getValue().server().equals(f[0]) && !there.contains(e.getKey().toString()))) {
            changed();
        }
    }

    static void applyRefused(String payload) {
        String[] f = payload.split("\n", 4);
        if (f.length == 4 && !f[1].equals(Storage.currentServerId())) {
            try {
                addRefusal(new Refusal(Long.parseLong(f[0]), f[1], f[2], f[3]));
                changed();
            } catch (NumberFormatException ignored) {
                // a bad notice
            }
        }
    }

    /** A staff kick pressed on another server, for a player here (logged where it was pressed). */
    static void applyKick(MinecraftServer server, String payload) {
        String[] f = payload.split("\n", 2);
        if (f.length == 2) {
            try {
                ServerPlayer p = localPlayer(server, UUID.fromString(f[0]));
                if (p != null) {
                    kicker.accept(p, staffKick(cleanReason(f[1])));
                }
            } catch (IllegalArgumentException ignored) {
                // a bad notice
            }
        }
    }

    /**
     * Everyone on the network with a report: this server's players and the others' (a server not heard from for
     * {@value #STALE_MS} ms left out); flagged first, then by name (refined clients page).
     */
    public static List<Row> players(MinecraftServer server) {
        String here = Storage.currentServerId();
        long now = clock.getAsLong();
        List<Row> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : localPlayers(server)) {
            Held h = REPORTS.get(p.getUUID());
            if (h != null && seen.add(h.id())) {
                out.add(new Row(here, h));
            }
        }
        for (Remote r : REMOTE.values()) {
            Long at = SERVER_SEEN.get(r.server());
            if (at != null && now - at <= STALE_MS && seen.add(r.held().id())) {
                out.add(new Row(r.server(), r.held()));
            }
        }
        out.sort(Comparator.comparing((Row r) -> r.held().verdict().flagCount() == 0 || r.held().verdict().exempt())
                .thenComparing(r -> r.held().name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** A player's latest report on the network: here, or as another server told it. */
    @Nullable
    public static Row find(UUID player) {
        Held h = REPORTS.get(player);
        if (h != null) {
            return new Row(Storage.currentServerId(), h);
        }
        Remote r = REMOTE.get(player);
        return r == null ? null : new Row(r.server(), r.held());
    }

    @Nullable
    public static Row findByName(String name) {
        for (Held h : REPORTS.values()) {
            if (h.name().equalsIgnoreCase(name)) {
                return new Row(Storage.currentServerId(), h);
            }
        }
        for (Remote r : REMOTE.values()) {
            if (r.held().name().equalsIgnoreCase(name)) {
                return new Row(r.server(), r.held());
            }
        }
        return null;
    }

    /** A player's latest report here, or null. */
    @Nullable
    public static Held report(UUID player) {
        return REPORTS.get(player);
    }

    private static Collection<ServerPlayer> localPlayers(MinecraftServer server) {
        return playersOverride != null ? playersOverride.get() : server.getPlayerList().getPlayers();
    }

    @Nullable
    private static ServerPlayer localPlayer(MinecraftServer server, UUID id) {
        for (ServerPlayer p : localPlayers(server)) {
            if (p.getUUID().equals(id)) {
                return p;
            }
        }
        return null;
    }

    private static void publishPresent(MinecraftServer server) {
        Storage st = Storage.active();
        if (st != null) {
            List<String> ids = new ArrayList<>();
            for (ServerPlayer p : localPlayers(server)) {
                if (REPORTS.containsKey(p.getUUID())) {
                    ids.add(p.getUUID().toString());
                }
            }
            st.publish(KIND_PRESENT, st.serverId() + "\n" + String.join(",", ids));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Once, in the mod constructor. */
    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener(ClientCheckService::onNegotiation);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                onLoggedIn(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                onLogout(p);
            }
        });
    }

    public static void onLogout(ServerPlayer p) {
        ClientsAdmin.forget(p.getUUID());
        TOLD.remove(p.getUUID());
        if (REPORTS.remove(p.getUUID()) != null) {
            Storage st = Storage.active();
            if (st != null) {
                st.publish(KIND_GONE, st.serverId() + "\n" + p.getUUID());
            }
            changed();
        }
    }

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_RULES, (srv, n) -> {
            CheckRules r = CheckRules.decode(n.payload());
            if (r != null) {
                applyRules(srv, r);
            }
        });
        Storage.on(KIND_SETUP, (srv, n) -> {
            TrustedSetup t = TrustedSetup.fromJson(n.payload());
            if (t != null) {
                applySetup(srv, t);
            }
        });
        Storage.on(KIND_REPORT, (srv, n) -> applyReport(n.payload()));
        Storage.on(KIND_GONE, (srv, n) -> applyGone(n.payload()));
        Storage.on(KIND_PRESENT, (srv, n) -> applyPresent(n.payload()));
        Storage.on(KIND_REFUSED, (srv, n) -> applyRefused(n.payload()));
        Storage.on(KIND_KICK, (srv, n) -> applyKick(srv, n.payload()));
    }

    public static void onServerStarted(MinecraftServer server) {
        ticks = 0;
        REMOTE.clear();
        SERVER_SEEN.clear();
        Storage s = Storage.active();
        if (s == null) {
            readFile(server);
            return;
        }
        s.submit(() -> {
            CheckRules rules = s.backend().loadCheckRules();
            TrustedSetup setup = s.backend().loadTrustedSetup();
            if (rules == null) {
                s.backend().saveCheckRules(CheckData.get(server).networkRules());
            }
            if (setup == null && CheckData.get(server).networkSetup() != null) {
                s.backend().saveTrustedSetup(CheckData.get(server).networkSetup());
            }
            server.execute(() -> {
                if (rules != null) {
                    applyRules(server, rules);
                }
                if (setup != null) {
                    applySetup(server, setup);
                }
                readFile(server);
            });
        });
    }

    public static void tick(MinecraftServer server) {
        if (++ticks % HEARTBEAT_TICKS == 0) {
            publishPresent(server);
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock, @Nullable Supplier<Collection<ServerPlayer>> players, @Nullable BiConsumer<ServerPlayer, Component> kick, @Nullable Path dir) {
        clock = testClock;
        playersOverride = players;
        if (kick != null) {
            kicker = kick;
        }
        dirOverride = dir;
    }

    public static void useForTests(LongSupplier testClock) {
        useForTests(testClock, null, null, null);
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        playersOverride = null;
        kicker = (p, why) -> p.connection.disconnect(why);
        dirOverride = null;
    }

    public static void forget(UUID player) {
        REPORTS.remove(player);
        REMOTE.remove(player);
        TOLD.remove(player);
    }

    /** Whether a player has been told about their brightness this join. */
    public static boolean toldBright(UUID player) {
        return TOLD.contains(player);
    }

    public static void clearRefusalsForTests() {
        synchronized (REFUSALS) {
            REFUSALS.clear();
        }
    }
}
