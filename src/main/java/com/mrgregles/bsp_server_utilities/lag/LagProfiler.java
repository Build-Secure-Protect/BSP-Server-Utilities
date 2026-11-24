package com.mrgregles.bsp_server_utilities.lag;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.LagAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.LagAskPacket;
import com.mrgregles.bsp_server_utilities.network.LagSamplePacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The LAG tab's profiler on the server (SU-151): one sample at a time on each server (D761), of 10, 30 or 60 seconds
 * (D749), started and stopped by editors, here or on another server through a notice ({@value #KIND_SAMPLE}) naming it.
 * A sample starts timing at the next server tick and runs for its length in ticks ({@link LagTimer} takes the timings);
 * STOP ends it early with what it has. The finished result stays until the next sample or a restart (D759) and is what
 * PROFILE answers, with the running sample's progress, to any editor; each sample is in the Action Log as
 * {@link LogEntry#LAG_SAMPLE} by whoever started it (D752), a sample the server's stop cuts short too.
 * <p>
 * What an editor is told (refused, started, stopped, done) reaches them on whichever server they are: here at once, on
 * another through a notice ({@value #KIND_SAID}) every server reads and the one they are on passes on.
 */
public final class LagProfiler {
    public static final String KIND_SAMPLE = "lag_sample", KIND_SAID = "lag_said";
    /** The lengths offered, in seconds (D749); 30 is the default. */
    public static final List<Integer> LENGTHS = List.of(10, 30, 60);
    public static final int DEFAULT_SECONDS = 30;
    /** The hooks that did not go in, as {@code LagMixinPlugin} found them (that class is in the mixin package, so its name is not used here). */
    static final String MISSING_PROPERTY = "bsp_server_utilities.lag.missing";
    /** The lag mixins the plugin expects, and those it saw applied (a mixin whose target class is missing is never applied). */
    static final String EXPECTED_PROPERTY = "bsp_server_utilities.lag.expected", APPLIED_PROPERTY = "bsp_server_utilities.lag.applied";
    /** The one target class not loaded by the time a sample is asked for (block entities' tickers come with the first one). */
    private static final String BOUND_TICKER = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity";
    private static final String SEP = "|";

    /** Who started or stopped a sample: as the Action Log names them. */
    public record Starter(String uuid, String name, String roleGroup, String roleLabel, int colour) {
        public static Starter of(ServerPlayer p) {
            Staff.Role role = ActionLog.tracked(p).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR));
            return new Starter(p.getUUID().toString(), p.getGameProfile().getName(), role.group(), role.label(), role.colour());
        }
    }

    /** What a start or stop came to. */
    public enum Outcome { STARTED, STOPPED, RUNNING, NOT_RUNNING, BAD_LENGTH, UNAVAILABLE }

    private static final class Run {
        final Starter by;
        final long startedAtMs;
        final int lengthS, ticksAsked;
        boolean timing;
        int ticks;
        long tickNanos, tickStart;

        Run(Starter by, long startedAtMs, int lengthS, int ticksAsked) {
            this.by = by;
            this.startedAtMs = startedAtMs;
            this.lengthS = lengthS;
            this.ticksAsked = ticksAsked;
        }
    }

    @Nullable
    private static Run run;
    @Nullable
    private static LagResult last;
    private static int testTicks;
    private static LongSupplier clock = System::currentTimeMillis;
    private static boolean warned, touched;

    private LagProfiler() {
    }

    // ------------------------------------------------------------------ starting and stopping

    /**
     * The hooks missing on this server (empty when all went in); logged once. A lag mixin never applied (its target class
     * is not there) counts as missing: its target classes are loaded first, so a mixin not yet applied is applied here.
     */
    static String missing() {
        if (!touched) {
            touched = true;
            try {
                Class.forName(BOUND_TICKER, false, LagProfiler.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                // not there: its mixin is never applied, and is counted below
            }
        }
        StringBuilder b = new StringBuilder(System.getProperty(MISSING_PROPERTY, ""));
        Set<String> applied = new HashSet<>(List.of(System.getProperty(APPLIED_PROPERTY, "").split(" ")));
        for (String e : System.getProperty(EXPECTED_PROPERTY, "").split(" ")) {
            if (!e.isEmpty() && !applied.contains(e)) {
                b.append(b.length() == 0 ? "" : " ").append(e).append(".notApplied");
            }
        }
        String m = b.toString();
        if (!m.isEmpty() && !warned) {
            warned = true;
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the lag profiler is unavailable on this server; this server's code differs where it times ticks (missing: {})", m);
        }
        return m;
    }

    /** Starts a sample of {@code seconds} here, unless one runs. Its timings start at the next tick. */
    public static Outcome start(MinecraftServer server, Starter by, int seconds) {
        String here = Storage.currentServerId();
        if (!LENGTHS.contains(seconds)) {
            return Outcome.BAD_LENGTH;
        }
        if (!missing().isEmpty()) {
            tell(server, by.uuid(), "sample_unavailable", here);
            return Outcome.UNAVAILABLE;
        }
        Run r = run;
        if (r != null) {
            tell(server, by.uuid(), "sample_running", here, r.by.name(), Long.toString(secondsLeft(r)));
            return Outcome.RUNNING;
        }
        run = new Run(by, clock.getAsLong(), seconds, testTicks > 0 ? testTicks : seconds * 20);
        tell(server, by.uuid(), "sample_started", here, Integer.toString(seconds));
        return Outcome.STARTED;
    }

    /** Ends the running sample here with what it has. */
    public static Outcome stop(MinecraftServer server, Starter by) {
        if (run == null) {
            tell(server, by.uuid(), "sample_not_running", Storage.currentServerId());
            return Outcome.NOT_RUNNING;
        }
        finish(server, by, false);
        return Outcome.STOPPED;
    }

    private static long secondsLeft(Run r) {
        return (Math.max(0, r.ticksAsked - r.ticks) + 19) / 20;
    }

    /** The server tick's start and end: a waiting sample starts timing, and each whole tick is added to it. */
    static void onTick(MinecraftServer server, boolean start) {
        Run r = run;
        if (r == null) {
            return;
        }
        if (start) {
            if (!r.timing) {
                LagTimer.begin();
                r.timing = true;
            }
            r.tickStart = System.nanoTime();
            return;
        }
        if (!r.timing || r.tickStart == 0) {
            return;
        }
        r.tickNanos += System.nanoTime() - r.tickStart;
        r.tickStart = 0;
        r.ticks++;
        if (r.ticks >= r.ticksAsked) {
            finish(server, null, false);
        }
    }

    /**
     * The sample ends: its result is kept and logged by who started it. Ran its length: they are told; stopped: who
     * stopped it is told, and who started it too when that is someone else; cut short by the server stopping
     * ({@code stopping}): logged as such, and no one is told (the network is closing).
     */
    private static void finish(MinecraftServer server, @Nullable Starter stoppedBy, boolean stopping) {
        Run r = run;
        if (r == null) {
            return;
        }
        LagTimer.end();
        LagResult result = build(server, r, stoppedBy == null ? "" : stoppedBy.name());
        LagTimer.clear();
        run = null;
        last = result;
        Starter by = r.by;
        ActionLog.record(server, new LogEntry(0, UUID.randomUUID().toString(), clock.getAsLong(), Storage.currentServerId(), by.uuid(), by.name(),
                by.roleGroup(), by.roleLabel(), by.colour(), LogEntry.LAG_SAMPLE, logText(result, stopping)));
        if (stopping) {
            return;
        }
        String here = Storage.currentServerId();
        String done = Long.toString(Math.round(result.ticks() / 20.0));
        if (stoppedBy == null) {
            tell(server, by.uuid(), "sample_done", here);
        } else {
            tell(server, stoppedBy.uuid(), "sample_stopped", here, done);
            if (!stoppedBy.uuid().equals(by.uuid())) {
                tell(server, by.uuid(), "sample_stopped_by", here, stoppedBy.name(), done);
            }
        }
    }

    /** The server is stopping: a running sample ends with what it has and is logged once, then everything is cleared. */
    public static void serverStopping(MinecraftServer server) {
        if (run != null) {
            finish(server, null, true);
        }
        reset();
    }

    /** The Action Log's line: how long, where, the tick, and the worst chunk. */
    static String logText(LagResult r) {
        return logText(r, false);
    }

    /** {@code stopping}: the server's stop cut the sample short. */
    static String logText(LagResult r, boolean stopping) {
        StringBuilder b = new StringBuilder("sampled ");
        long done = Math.round(r.ticks() / 20.0);
        if (stopping) {
            b.append(done).append(" of ").append(r.lengthS()).append(" s on ").append(r.server()).append(", cut short by the server stopping");
        } else if (r.stoppedBy().isEmpty()) {
            b.append(r.lengthS()).append(" s on ").append(r.server());
        } else {
            b.append(done).append(" of ").append(r.lengthS()).append(" s on ").append(r.server()).append(", stopped by ").append(r.stoppedBy());
        }
        b.append(": tick ").append(one(r.tickMs())).append(" ms, ");
        if (r.chunks().isEmpty()) {
            b.append("no chunk timed");
        } else {
            LagResult.Chunk c = r.chunks().get(0);
            b.append("worst chunk ").append(c.x()).append(", ").append(c.z()).append(" in ").append(c.dimension()).append(" at ").append(one(c.ms())).append(" ms");
        }
        return b.toString();
    }

    private static String one(double ms) {
        return String.format(Locale.ROOT, "%.1f", ms);
    }

    // ------------------------------------------------------------------ the result

    private record Ranked(ServerLevel level, long chunk, LagTimer.ChunkAcc acc) {
    }

    /** A timed place: a block entity or a scheduled tick ({@code kind}), its block or type ({@code what}) and its time. */
    private record Spot(ServerLevel level, long pos, LagResult.Kind kind, Object what, long nanos) {
    }

    /**
     * The spots' order, slowest first; at the same time, by world and place, then a block entity before a scheduled tick at
     * the same place and by label, so that equal times never compare equal (audit of the LAG fixes).
     */
    private static final Comparator<Spot> SPOT_RANK = Comparator.comparingLong(Spot::nanos).reversed()
            .thenComparing(k -> k.level().dimension().location().toString()).thenComparingLong(Spot::pos)
            .thenComparing(Spot::kind).thenComparing(k -> label(k.what()));

    /**
     * The best {@code n} of what is offered, by {@code rank} (best first), kept in a heap of {@code n} so that finishing a
     * sample never sorts every chunk or place it timed (audit of SU-153).
     */
    public static final class Top<T> {
        private final int n;
        private final Comparator<T> rank;
        private final PriorityQueue<T> heap;

        public Top(int n, Comparator<T> rank) {
            this.n = Math.max(0, n);
            this.rank = rank;
            this.heap = new PriorityQueue<>(this.n + 1, rank.reversed()); // the worst kept on top
        }

        public void offer(T t) {
            if (n == 0) {
                return;
            }
            if (heap.size() < n) {
                heap.add(t);
            } else if (rank.compare(t, heap.peek()) < 0) {
                heap.poll();
                heap.add(t);
            }
        }

        /** What is kept, the best first. */
        public List<T> sorted() {
            List<T> out = new ArrayList<>(heap);
            out.sort(rank);
            return out;
        }
    }

    /** The result of what {@link LagTimer} holds for a run. */
    private static LagResult build(MinecraftServer server, Run r, String stoppedBy) {
        int ticks = r.ticks;
        Top<Ranked> topChunks = new Top<>(LagResult.TOP_CHUNKS, Comparator.comparingLong((Ranked k) -> k.acc().nanos).reversed()
                .thenComparing(k -> k.level().dimension().location().toString()).thenComparingLong(Ranked::chunk));
        // a place is at most twice among the spots (a block entity and a scheduled tick), so twice the blocks are enough
        Top<Spot> topSpots = new Top<>(2 * LagResult.TOP_BLOCKS, SPOT_RANK);
        long inside = 0;
        for (Map.Entry<ServerLevel, Long2ObjectOpenHashMap<LagTimer.ChunkAcc>> lv : LagTimer.LEVELS.entrySet()) {
            for (Long2ObjectMap.Entry<LagTimer.ChunkAcc> c : lv.getValue().long2ObjectEntrySet()) {
                topChunks.offer(new Ranked(lv.getKey(), c.getLongKey(), c.getValue()));
                inside += c.getValue().nanos;
                for (LagTimer.Acc a : c.getValue().kinds.values()) {
                    if (a.kind == LagResult.Kind.BLOCK_ENTITY || a.kind == LagResult.Kind.SCHEDULED) {
                        for (Long2LongMap.Entry e : a.each.long2LongEntrySet()) {
                            topSpots.offer(new Spot(lv.getKey(), e.getLongKey(), a.kind, a.kind == LagResult.Kind.SCHEDULED ? a.what.get(e.getLongKey()) : a.type, e.getLongValue()));
                        }
                    }
                }
            }
        }
        // why each kept chunk is loaded, at the end of the sample: by the census's rules, one chunk at a time
        Map<ServerLevel, ChunkCensus.Reasons> reasons = new IdentityHashMap<>();
        List<LagResult.Chunk> chunks = new ArrayList<>();
        for (Ranked k : topChunks.sorted()) {
            ChunkPos p = new ChunkPos(k.chunk());
            ChunkCensus.Reason row = k.level().getChunkSource().getChunkNow(p.x, p.z) == null ? null
                    : reasons.computeIfAbsent(k.level(), ChunkCensus::reasons).at(p.x, p.z);
            List<LagTimer.Acc> accs = new ArrayList<>(k.acc().kinds.values());
            accs.sort(Comparator.comparingLong((LagTimer.Acc a) -> a.nanos).reversed().thenComparing(a -> a.kind));
            List<LagResult.Entry> entries = new ArrayList<>();
            for (LagTimer.Acc a : accs.subList(0, Math.min(LagResult.TOP_ENTRIES, accs.size()))) {
                entries.add(entry(a, ticks));
            }
            chunks.add(new LagResult.Chunk(k.level().dimension().location().toString(), p.x, p.z, LagResult.ms(k.acc().nanos, ticks),
                    row == null ? ChunkCensus.Why.OTHER : row.why(), row == null ? "" : row.who(), entries));
        }
        // OUTLINE's blocks: the slowest places of the whole sample, one each (D751)
        List<LagResult.Block> blocks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Spot s : topSpots.sorted()) {
            if (blocks.size() >= LagResult.TOP_BLOCKS) {
                break;
            }
            String dim = s.level().dimension().location().toString();
            if (seen.add(dim + "@" + s.pos())) {
                BlockPos b = BlockPos.of(s.pos());
                blocks.add(new LagResult.Block(dim, b.getX(), b.getY(), b.getZ(), label(s.what()), LagResult.ms(s.nanos(), ticks)));
            }
        }
        long tickNanos = r.tickNanos;
        return new LagResult(Storage.currentServerId(), r.by.name(), r.startedAtMs, r.lengthS, r.ticksAsked, ticks, LagResult.ms(tickNanos, ticks),
                LagResult.ms(Math.max(0, tickNanos - inside), ticks), false, clock.getAsLong(), stoppedBy, chunks, blocks);
    }

    private static LagResult.Entry entry(LagTimer.Acc a, int ticks) {
        long worstKey = 0, worst = -1;
        for (Long2LongMap.Entry e : a.each.long2LongEntrySet()) {
            if (e.getLongValue() > worst) {
                worst = e.getLongValue();
                worstKey = e.getLongKey();
            }
        }
        BlockPos at = switch (a.kind) {
            case BLOCK_ENTITY, SCHEDULED -> worst < 0 ? null : BlockPos.of(worstKey);
            case ENTITY -> worst < 0 || !a.where.containsKey(worstKey) ? null : BlockPos.of(a.where.get(worstKey));
            case CHUNK -> null;
        };
        String label = switch (a.kind) {
            case SCHEDULED -> LagResult.SCHEDULED_LABEL;
            case CHUNK -> LagResult.CHUNK_LABEL;
            default -> label(a.type);
        };
        return new LagResult.Entry(a.kind, label, a.kind == LagResult.Kind.CHUNK ? 1 : a.each.size(), LagResult.ms(a.nanos, ticks), at);
    }

    /** A type's id: a block entity type, entity type, block or fluid (or a block entity's id where its type was not found). */
    static String label(@Nullable Object type) {
        ResourceLocation id = null;
        if (type instanceof String s) {
            return s;
        } else if (type instanceof BlockEntityType<?> t) {
            id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(t);
        } else if (type instanceof EntityType<?> t) {
            id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
        } else if (type instanceof Block t) {
            id = BuiltInRegistries.BLOCK.getKey(t);
        } else if (type instanceof Fluid t) {
            id = BuiltInRegistries.FLUID.getKey(t);
        }
        return id == null ? "unknown" : id.toString();
    }

    /** What PROFILE answers here: the running sample (who, when, how far; no chunks yet) and the latest finished one. */
    public static LagResult.Profile profile() {
        Run r = run;
        LagResult running = null;
        if (r != null) {
            long now = clock.getAsLong();
            running = new LagResult(Storage.currentServerId(), r.by.name(), r.startedAtMs, r.lengthS, r.ticksAsked, r.ticks, LagResult.ms(r.tickNanos, r.ticks), 0,
                    true, now + Math.max(0, r.ticksAsked - r.ticks) * 50L, "", List.of(), List.of());
        }
        return new LagResult.Profile(running, last);
    }

    public static String profilePayload() {
        return profilePayload(LagAnswerPacket.MAX_PAYLOAD);
    }

    /** PROFILE's text in at most {@code budget} characters, the finished sample cut by whole chunks, the slowest kept. */
    public static String profilePayload(int budget) {
        return LagResult.encodeProfile(profile(), budget);
    }

    // ------------------------------------------------------------------ editors and other servers

    private static String clean(String s) {
        return s.replace('|', '/').replace('\n', ' ');
    }

    /**
     * An editor's START or STOP from the screen: on this server at once, answered with this server's PROFILE; on another,
     * passed on with a notice naming it, whose PROFILE comes back to the editor while the question is open. True when it
     * was done here or passed on.
     */
    public static boolean request(ServerPlayer player, LagSamplePacket p) {
        if (!Perms.canEdit(player) || !LagNetwork.pass(player, LagNetwork.SAMPLE_SLOT + p.action().ordinal())) {
            return false; // one START and one STOP each gap, as the asks (audit of SU-153)
        }
        MinecraftServer server = player.server;
        String here = Storage.currentServerId();
        Starter by = Starter.of(player);
        if (p.server().isEmpty() || LagNetwork.sameServer(p.server(), here)) {
            Outcome o = p.action() == LagSamplePacket.Action.START ? start(server, by, p.seconds()) : stop(server, by);
            LagNetwork.sendHere(player, LagAskPacket.Kind.PROFILE, here, profilePayload());
            return o == Outcome.STARTED || o == Outcome.STOPPED;
        }
        Storage s = Storage.active();
        if (s == null) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.lag.sample_no_storage", p.server()).withStyle(ChatFormatting.RED));
            return false;
        }
        String id = LagNetwork.open(player, LagAskPacket.Kind.PROFILE);
        s.publish(KIND_SAMPLE, String.join(SEP, id, clean(p.server()), p.action().name(), Integer.toString(p.seconds()),
                by.uuid(), clean(by.name()), clean(by.roleGroup()), clean(by.roleLabel()), Integer.toString(by.colour())));
        return true;
    }

    /** Another server passing on a START or STOP: done when it names this server and is not late, then this server's PROFILE sent back. */
    static void onSample(MinecraftServer server, StorageBackend.Notice n) {
        Storage s = Storage.active();
        String[] f = n.payload().split("\\|", -1);
        if (s == null || f.length != 9 || !f[1].equals(clean(s.serverId())) || LagNetwork.late(n, clock.getAsLong(), LagNetwork.LATE_MS)) {
            return;
        }
        LagSamplePacket.Action action;
        int seconds, colour;
        try {
            action = LagSamplePacket.Action.valueOf(f[2]);
            seconds = Integer.parseInt(f[3]);
            colour = Integer.parseInt(f[8]);
        } catch (IllegalArgumentException e) {
            return;
        }
        Starter by = new Starter(f[4], f[5], f[6], f[7], colour);
        if (action == LagSamplePacket.Action.START) {
            start(server, by, seconds);
        } else {
            stop(server, by);
        }
        LagNetwork.answer(s, f[0], LagAskPacket.Kind.PROFILE, LagProfiler::profilePayload);
    }

    /** Tells an editor something, on this server or, through a notice, on the one they are on. */
    private static void tell(MinecraftServer server, String uuid, String key, String... args) {
        ServerPlayer p = player(server, uuid);
        if (p != null) {
            boolean bad = key.equals("sample_running") || key.equals("sample_unavailable") || key.equals("sample_not_running");
            p.sendSystemMessage(Component.translatable("message.bsp_server_utilities.lag." + key, (Object[]) args).withStyle(bad ? ChatFormatting.RED : ChatFormatting.GRAY));
            return;
        }
        Storage s = Storage.active();
        if (s != null) {
            List<String> f = new ArrayList<>(List.of(uuid, key));
            for (String a : args) {
                f.add(clean(a));
            }
            s.publish(KIND_SAID, String.join(SEP, f));
        }
    }

    /** Another server telling an editor something: passed on when they are on this server, and it is not late. */
    static void onSaid(MinecraftServer server, StorageBackend.Notice n) {
        String[] f = n.payload().split("\\|", -1);
        if (f.length < 2 || !f[1].startsWith("sample_") || player(server, f[0]) == null || LagNetwork.late(n, clock.getAsLong(), LagNetwork.LATE_MS)) {
            return;
        }
        String[] args = java.util.Arrays.copyOfRange(f, 2, f.length);
        tell(server, f[0], f[1], args);
    }

    @Nullable
    private static ServerPlayer player(MinecraftServer server, String uuid) {
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ wiring

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_SAMPLE, LagProfiler::onSample);
        Storage.on(KIND_SAID, LagProfiler::onSaid);
    }

    /** The tick's start (first of all) and end (last of all), and a clean start and stop of the server; once, in the mod constructor. */
    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.START) {
                onTick(e.getServer(), true);
            }
        });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, (TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) {
                onTick(e.getServer(), false);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            reset();
            missing();
        });
        // before the storage closes (at the normal priority), so the cut sample's log entry is shared
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerStoppingEvent e) -> serverStopping(e.getServer()));
    }

    /** A clean start or stop: the sample, the timings, the summaries kept for asks and the open questions are dropped. */
    private static void reset() {
        LagTimer.end();
        LagTimer.clear();
        run = null;
        last = null;
        LagSnapshot.clearCache(); // it holds the server
        LagNetwork.serverStopped(); // they hold its players
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a sample's length in ticks (0: its seconds), and the clock (null: the real one). */
    public static void useForTests(int ticks, @Nullable LongSupplier c) {
        testTicks = ticks;
        clock = c != null ? c : System::currentTimeMillis;
    }

    /**
     * Tests, first thing in every LAG GameTest (audit of SU-153): a sample left running by a test that timed out is
     * dropped (not logged), the real clocks and senders are back, and nothing is kept from an ask, so one failure cannot
     * spill into the next test.
     */
    public static void resetForTests() {
        LagTimer.end();
        LagTimer.clear();
        run = null;
        testTicks = 0;
        clock = System::currentTimeMillis;
        LagNetwork.useForTests(null, null);
        LagSnapshot.clearCacheForTests();
    }

    /** Tests: whether a sample runs here, and whether it has started timing. */
    public static boolean runningForTests() {
        return run != null;
    }

    public static boolean timingForTests() {
        return run != null && run.timing && LagTimer.on;
    }

    /** Tests: takes timings at once, outside the ticks (the records are then driven by hand). */
    public static void beginForTests() {
        LagTimer.begin();
    }

    /** Tests: the result of what the timings hold, as if {@code ticks} ticks of {@code tickNanos} in all had run; the timings are then cleared. */
    public static LagResult resultForTests(MinecraftServer server, Starter by, int ticks, long tickNanos) {
        LagTimer.end();
        Run r = new Run(by, clock.getAsLong(), DEFAULT_SECONDS, ticks);
        r.ticks = ticks;
        r.tickNanos = tickNanos;
        try {
            return build(server, r, "");
        } finally {
            LagTimer.clear();
        }
    }
}
