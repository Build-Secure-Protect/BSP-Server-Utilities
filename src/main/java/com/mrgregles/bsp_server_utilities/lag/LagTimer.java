package com.mrgregles.bsp_server_utilities.lag;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import com.mrgregles.bsp_server_utilities.lag.LagResult.Kind;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What the profiler's mixins time during a sample (SU-151): every block entity's tick, every entity's tick (with its
 * passengers), each chunk's own tick (random ticks, spawning, lightning) and each scheduled block or fluid tick, added up
 * per world, chunk and kind. The mixins call in only while {@link #on} is true, so a server not sampling pays one volatile
 * read per hook. Everything here runs on the server thread (ticks run nowhere else); a call from another thread, or from a
 * client world, is ignored. The data is cleared when a sample starts and when it ends.
 * <p>
 * The timings do not nest where it matters for a chunk's sum: a passenger is ticked inside its vehicle's
 * {@code tickNonPassenger} and so counted once, with the vehicle; block entity ticks, chunk ticks and scheduled ticks
 * run one after another, never inside each other.
 */
public final class LagTimer {
    /** Whether a sample is taking timings. Read by every hook first. */
    public static volatile boolean on;

    /** The key a chunk's scheduled ticks and its own tick are added up under (block entities and entities: their type). */
    static final Object SCHEDULED_KEY = new Object(), CHUNK_KEY = new Object();

    /** One kind in one chunk: its total, and each thing's own total (a block's packed position, an entity's id). */
    static final class Acc {
        final Kind kind;
        final Object type;
        long nanos;
        final Long2LongOpenHashMap each = new Long2LongOpenHashMap();
        /** Entities: each one's packed block position at its last tick. */
        @Nullable
        final Long2LongOpenHashMap where;
        /** Scheduled ticks: the block or fluid at each position. */
        @Nullable
        final Long2ObjectOpenHashMap<Object> what;

        Acc(Kind kind, Object type) {
            this.kind = kind;
            this.type = type;
            this.where = kind == Kind.ENTITY ? new Long2LongOpenHashMap() : null;
            this.what = kind == Kind.SCHEDULED ? new Long2ObjectOpenHashMap<>() : null;
        }
    }

    /** One chunk: its kinds by type (identity). */
    static final class ChunkAcc {
        final Map<Object, Acc> kinds = new IdentityHashMap<>();
        long nanos;

        Acc acc(Kind kind, Object type) {
            Acc a = kinds.get(type);
            if (a == null) {
                a = new Acc(kind, type);
                kinds.put(type, a);
            }
            return a;
        }
    }

    static final Map<ServerLevel, Long2ObjectOpenHashMap<ChunkAcc>> LEVELS = new IdentityHashMap<>();
    @Nullable
    private static Thread thread;
    @Nullable
    private static ServerLevel lastLevel;
    @Nullable
    private static Long2ObjectOpenHashMap<ChunkAcc> lastChunks;

    // the start of the tick being timed, one per kind (none nests in another of its kind); 0 when none
    private static long beStart, entityStart, chunkStart, scheduledStart;
    private static long entityChunk, entityPos;
    /** The server level whose block entities tick ({@code Level.tickBlockEntities}); null outside it. */
    @Nullable
    private static ServerLevel beLevel;
    /**
     * The levels a nested {@code tickBlockEntities} (a mod ticking another level's block entities from inside one) left,
     * restored as each returns; {@link #beDepth} deep (only the first {@code OUTER.length} kept: deeper is not met).
     */
    private static final ServerLevel[] OUTER = new ServerLevel[8];
    private static int beDepth;
    /** Block entity types by the id their tickers give, looked up once each. */
    private static final Map<String, Object> BE_TYPES = new HashMap<>();

    private LagTimer() {
    }

    /** Starts taking timings on this thread (the server's), with nothing kept from before. */
    static void begin() {
        clear();
        thread = Thread.currentThread();
        on = true;
    }

    /** Stops taking timings; the data stays until {@link #clear()}. */
    static void end() {
        on = false;
    }

    static void clear() {
        LEVELS.clear();
        lastLevel = null;
        lastChunks = null;
        beLevel = null;
        java.util.Arrays.fill(OUTER, null);
        beDepth = 0;
        beStart = entityStart = chunkStart = scheduledStart = 0;
    }

    private static boolean here() {
        return on && Thread.currentThread() == thread;
    }

    private static ChunkAcc chunk(ServerLevel level, long chunk) {
        Long2ObjectOpenHashMap<ChunkAcc> chunks = lastLevel == level ? lastChunks : null;
        if (chunks == null) {
            chunks = LEVELS.computeIfAbsent(level, k -> new Long2ObjectOpenHashMap<>());
            lastLevel = level;
            lastChunks = chunks;
        }
        ChunkAcc c = chunks.get(chunk);
        if (c == null) {
            c = new ChunkAcc();
            chunks.put(chunk, c);
        }
        return c;
    }

    // ------------------------------------------------------------------ what the mixins call (only while on)

    /**
     * A level starts ticking its block entities: a server level is kept for the block entity timings (another kind of level
     * on this thread keeps none), the one it replaces put aside for {@link #blockEntitiesEnd}.
     */
    public static void blockEntitiesStart(Level level) {
        if (Thread.currentThread() != thread) {
            return;
        }
        if (beDepth < OUTER.length) {
            OUTER[beDepth] = beLevel;
        }
        beDepth++;
        beLevel = level instanceof ServerLevel s ? s : null;
    }

    /** A level's block entities are done: the level ticking them before (an outer one, or none) is back. */
    public static void blockEntitiesEnd() {
        if (Thread.currentThread() != thread) {
            return;
        }
        if (beDepth == 0) {
            beLevel = null; // its start came before the sample did
            return;
        }
        beDepth--;
        beLevel = beDepth < OUTER.length ? OUTER[beDepth] : null;
        if (beDepth < OUTER.length) {
            OUTER[beDepth] = null;
        }
    }

    public static void blockEntityStart() {
        if (Thread.currentThread() != thread) {
            return; // a client world in a single player game
        }
        beStart = System.nanoTime();
    }

    /** A block entity's ticker is done: its place and type from the ticker itself, the level from {@link #blockEntitiesStart}. */
    public static void blockEntityEnd(TickingBlockEntity ticker) {
        if (Thread.currentThread() != thread) {
            return;
        }
        long start = beStart;
        beStart = 0;
        ServerLevel level = beLevel;
        if (start == 0 || level == null) {
            return;
        }
        long nanos = System.nanoTime() - start;
        BlockPos pos;
        String id;
        try {
            pos = ticker.getPos();
            id = ticker.getType();
        } catch (RuntimeException e) {
            return; // a type with no id: not timed
        }
        recordBlockEntity(level, pos, beType(id), nanos);
    }

    /** A block entity type by its id (the id itself when the registry has no such type). */
    static Object beType(String id) {
        Object t = BE_TYPES.get(id);
        if (t == null) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            BlockEntityType<?> type = rl == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.get(rl);
            t = type != null ? type : id;
            BE_TYPES.put(id, t);
        }
        return t;
    }

    public static void entityStart(Entity e) {
        if (Thread.currentThread() != thread) {
            return;
        }
        entityStart = System.nanoTime();
        entityChunk = e.chunkPosition().toLong();
        entityPos = e.blockPosition().asLong();
    }

    public static void entityEnd(Level level, Entity e) {
        if (Thread.currentThread() != thread) {
            return;
        }
        long start = entityStart;
        entityStart = 0;
        if (start != 0 && level instanceof ServerLevel s) {
            recordEntity(s, entityChunk, e.getType(), e.getId(), entityPos, System.nanoTime() - start);
        }
    }

    public static void chunkStart() {
        if (Thread.currentThread() != thread) {
            return; // a client world in a single player game
        }
        chunkStart = System.nanoTime();
    }

    public static void chunkEnd(Level level, LevelChunk chunk) {
        if (Thread.currentThread() != thread) {
            return;
        }
        long start = chunkStart;
        chunkStart = 0;
        if (start != 0 && level instanceof ServerLevel s) {
            recordChunk(s, chunk.getPos().toLong(), System.nanoTime() - start);
        }
    }

    public static void scheduledStart() {
        if (Thread.currentThread() != thread) {
            return; // a client world in a single player game
        }
        scheduledStart = System.nanoTime();
    }

    /** {@code what}: the block or fluid ticked. */
    public static void scheduledEnd(Level level, BlockPos pos, Object what) {
        if (Thread.currentThread() != thread) {
            return;
        }
        long start = scheduledStart;
        scheduledStart = 0;
        if (start != 0 && level instanceof ServerLevel s) {
            recordScheduled(s, pos.asLong(), what, System.nanoTime() - start);
        }
    }

    // ------------------------------------------------------------------ the records (also driven by the GameTests)

    /** A block entity's tick took {@code nanos}. Nothing is kept outside a sample. */
    public static void recordBlockEntity(ServerLevel level, BlockPos pos, Object type, long nanos) {
        if (!here()) {
            return;
        }
        ChunkAcc c = chunk(level, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        Acc a = c.acc(Kind.BLOCK_ENTITY, type);
        a.nanos += nanos;
        a.each.addTo(pos.asLong(), nanos);
        c.nanos += nanos;
    }

    /** An entity's tick (with its passengers) took {@code nanos}; {@code chunk} and {@code pos} where it was as it started. */
    public static void recordEntity(ServerLevel level, long chunk, Object type, int id, long pos, long nanos) {
        if (!here()) {
            return;
        }
        ChunkAcc c = chunk(level, chunk);
        Acc a = c.acc(Kind.ENTITY, type);
        a.nanos += nanos;
        a.each.addTo(id, nanos);
        a.where.put(id, pos);
        c.nanos += nanos;
    }

    /** A chunk's own tick took {@code nanos}. */
    public static void recordChunk(ServerLevel level, long chunk, long nanos) {
        if (!here()) {
            return;
        }
        ChunkAcc c = chunk(level, chunk);
        Acc a = c.acc(Kind.CHUNK, CHUNK_KEY);
        a.nanos += nanos;
        a.each.addTo(chunk, nanos);
        c.nanos += nanos;
    }

    /** A scheduled tick of the block or fluid {@code what} at {@code pos} took {@code nanos}. */
    public static void recordScheduled(ServerLevel level, long pos, Object what, long nanos) {
        if (!here()) {
            return;
        }
        ChunkAcc c = chunk(level, ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4));
        Acc a = c.acc(Kind.SCHEDULED, SCHEDULED_KEY);
        a.nanos += nanos;
        a.each.addTo(pos, nanos);
        a.what.put(pos, what);
        c.nanos += nanos;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: how many chunks have a timing, in every world. */
    public static int chunksForTests() {
        return LEVELS.values().stream().mapToInt(Map::size).sum();
    }

    /** Tests: the nanoseconds a chunk has for one kind so far (0 for none); {@code type} null for any type of the kind. */
    public static long nanosForTests(ServerLevel level, ChunkPos chunk, Kind kind, @Nullable Object type) {
        Long2ObjectOpenHashMap<ChunkAcc> chunks = LEVELS.get(level);
        ChunkAcc c = chunks == null ? null : chunks.get(chunk.toLong());
        if (c == null) {
            return 0;
        }
        return c.kinds.values().stream().filter(a -> a.kind == kind && (type == null || a.type == type)).mapToLong(a -> a.nanos).sum();
    }

    /** Tests: whether a chunk has any entry of a kind (a timing may round to 0 ns on a fast machine). */
    public static boolean hasForTests(ServerLevel level, ChunkPos chunk, Kind kind, @Nullable Object type) {
        Long2ObjectOpenHashMap<ChunkAcc> chunks = LEVELS.get(level);
        ChunkAcc c = chunks == null ? null : chunks.get(chunk.toLong());
        return c != null && c.kinds.values().stream().anyMatch(a -> a.kind == kind && (type == null || a.type == type));
    }
}
