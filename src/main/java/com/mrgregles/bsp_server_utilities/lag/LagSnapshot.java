package com.mrgregles.bsp_server_utilities.lag;

import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCommands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the LAG tab's LOADED half shows (SU-149; D746, D748, D758; {@code tools/preview/lag_refined.html}), as plain data
 * with one text form for the notices between servers and the packet to the screen:
 * <ul>
 *   <li>{@link Summary}: one world on one server: its loaded and ticking chunks, entities, block entities, the chunks
 *   by why they are loaded, and its busiest {@value #LIST_ROWS} chunks for the LIST (by entities, then block entities).</li>
 *   <li>{@link MapRow}: every loaded chunk of one world, as small as it can be, for the map.</li>
 *   <li>{@link Detail}: one chunk opened up: why and whose, and its entities and block entities by type.</li>
 * </ul>
 * Built on the server thread when asked, from {@link ChunkCensus}; only the summaries are kept, for about
 * {@value #SUMMARY_CACHE_MS} ms, so editors on many servers asking at once take one census.
 */
public final class LagSnapshot {
    /** The busiest chunks of each world a summary carries for the list. */
    public static final int LIST_ROWS = 50;
    /** The kinds a chunk's detail lists, most numerous first, for each of entities and block entities. */
    public static final int DETAIL_TYPES = 12;
    /** How long the summaries answered to asks are kept. */
    static final long SUMMARY_CACHE_MS = 1000;
    /**
     * A map ask's middle when the editor's map has none yet in that world (they are not in it): both {@code x} and {@code z}
     * this value mean "the world's busiest chunk" (most entities, then block entities), so a cut map keeps the rows nearest it.
     */
    public static final int BUSIEST = Integer.MIN_VALUE;
    private static final String SEP = "|";
    @Nullable
    private static List<Summary> cached;
    @Nullable
    private static MinecraftServer cachedFor;
    private static long cachedAt;

    private LagSnapshot() {
    }

    /** A chunk in a summary's list: where, why and whose, how many entities and block entities. */
    public record ListRow(int x, int z, ChunkCensus.Why why, String who, int entities, int blockEntities) {
    }

    /** One world: totals, the chunks by reason (in {@link ChunkCensus.Why} order), and its busiest chunks. */
    public record Summary(String dimension, int chunks, int ticking, int entities, int blockEntities, int[] byWhy, List<ListRow> rows) {
        public Summary {
            byWhy = byWhy.clone();
            rows = List.copyOf(rows);
        }

        /** The chunks by reason, a copy (a summary is shared by the asks of one second). */
        @Override
        public int[] byWhy() {
            return byWhy.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Summary s && s.dimension.equals(dimension) && s.chunks == chunks && s.ticking == ticking && s.entities == entities
                    && s.blockEntities == blockEntities && java.util.Arrays.equals(s.byWhy, byWhy) && s.rows.equals(rows);
        }

        @Override
        public int hashCode() {
            return dimension.hashCode() * 31 + chunks;
        }
    }

    /** A loaded chunk on the map: where, why, how many entities and block entities, whether it ticks. */
    public record MapRow(int x, int z, ChunkCensus.Why why, int entities, int blockEntities, boolean ticking) {
    }

    /** A map answer: the world it is of (so a screen showing another world leaves it) and its rows. */
    public record MapAnswer(String dimension, List<MapRow> rows) {
        public MapAnswer {
            rows = List.copyOf(rows);
        }
    }

    /** A kind of entity or block entity and how many. */
    public record TypeCount(String type, int count) {
    }

    /**
     * One chunk opened up. {@code loaded} false: it is not loaded (any more), and the rest is empty. {@code spot} is where
     * GO THERE stands a player ({@link ChunkCommands#safeSpot}, D750), worked out by the server the chunk is on; null when
     * there is no safe place or the chunk is not loaded.
     */
    public record Detail(String dimension, int x, int z, boolean loaded, ChunkCensus.Why why, String who, boolean ticking,
                         List<TypeCount> entities, List<TypeCount> blockEntities, @Nullable BlockPos spot) {
        public Detail {
            entities = List.copyOf(entities);
            blockEntities = List.copyOf(blockEntities);
        }

        public int entityTotal() {
            return entities.stream().mapToInt(TypeCount::count).sum();
        }

        public int blockEntityTotal() {
            return blockEntities.stream().mapToInt(TypeCount::count).sum();
        }
    }

    // ------------------------------------------------------------------ building

    /** Every world of this server with a loaded chunk, overworld first. */
    public static List<Summary> summaries(MinecraftServer server) {
        List<Summary> out = new ArrayList<>();
        for (ChunkCensus.Dim d : ChunkCensus.take(server)) {
            out.add(summary(d));
        }
        return out;
    }

    /** {@link #summaries} for an editor's ask: one census each {@value #SUMMARY_CACHE_MS} ms at most. */
    public static List<Summary> summariesForAsk(MinecraftServer server) {
        long now = System.currentTimeMillis();
        List<Summary> c = cached;
        if (c == null || cachedFor != server || now - cachedAt >= SUMMARY_CACHE_MS || now < cachedAt) {
            c = summaries(server);
            cached = c;
            cachedFor = server;
            cachedAt = now;
        }
        return c;
    }

    /** The summaries kept for asks are dropped (they hold the server): when it starts and stops, and by the tests. */
    public static void clearCache() {
        cached = null;
        cachedFor = null;
        cachedAt = 0;
    }

    /** Tests: the summaries kept for asks are dropped. */
    public static void clearCacheForTests() {
        clearCache();
    }

    /** Tests: whether summaries are kept for asks. */
    public static boolean cachedForTests() {
        return cached != null || cachedFor != null;
    }

    static Summary summary(ChunkCensus.Dim d) {
        int[] byWhy = new int[ChunkCensus.Why.values().length];
        d.rows().forEach(r -> byWhy[r.why().ordinal()]++);
        List<ChunkCensus.Row> busy = new ArrayList<>(d.rows());
        busy.removeIf(r -> r.entities() == 0 && r.blockEntities() == 0);
        busy.sort(Comparator.comparingInt(ChunkCensus.Row::entities).thenComparingInt(ChunkCensus.Row::blockEntities).reversed().thenComparingLong(ChunkCensus.Row::chunk));
        List<ListRow> rows = new ArrayList<>();
        for (ChunkCensus.Row r : busy.subList(0, Math.min(LIST_ROWS, busy.size()))) {
            rows.add(new ListRow(r.pos().x, r.pos().z, r.why(), r.who(), r.entities(), r.blockEntities()));
        }
        return new Summary(d.dimension(), d.chunks(), d.ticking(), d.entities(), d.blockEntities(), byWhy, rows);
    }

    /** Every loaded chunk of one world for the map; empty when the world is not on this server. */
    public static List<MapRow> map(MinecraftServer server, String dimension) {
        ServerLevel level = level(server, dimension);
        if (level == null) {
            return List.of();
        }
        List<MapRow> out = new ArrayList<>();
        for (ChunkCensus.Row r : ChunkCensus.of(level).rows()) {
            out.add(new MapRow(r.pos().x, r.pos().z, r.why(), r.entities(), r.blockEntities(), r.ticking()));
        }
        return out;
    }

    /** One chunk opened up, read only if it is loaded (asking never loads a chunk). */
    public static Detail detail(MinecraftServer server, String dimension, int x, int z) {
        ServerLevel level = level(server, dimension);
        LevelChunk chunk = level == null ? null : level.getChunkSource().getChunkNow(x, z);
        if (chunk == null) {
            return new Detail(dimension, x, z, false, ChunkCensus.Why.OTHER, "", false, List.of(), List.of(), null);
        }
        long key = ChunkPos.asLong(x, z);
        ChunkCensus.Reason row = ChunkCensus.reason(level, x, z); // this chunk only, by the census's rules
        ChunkPos c = new ChunkPos(x, z);
        AABB box = new AABB(c.getMinBlockX(), level.getMinBuildHeight(), c.getMinBlockZ(), c.getMaxBlockX() + 1, level.getMaxBuildHeight(), c.getMaxBlockZ() + 1);
        Map<String, Integer> entities = new HashMap<>();
        for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) {
            if (e.chunkPosition().equals(c)) {
                entities.merge(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), 1, Integer::sum);
            }
        }
        Map<String, Integer> blocks = new HashMap<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            blocks.merge(id == null ? "unknown" : id.toString(), 1, Integer::sum);
        }
        return new Detail(dimension, x, z, true, row.why(), row.who(),
                level.shouldTickBlocksAt(key), top(entities), top(blocks), ChunkCommands.safeSpot(level, c));
    }

    private static List<TypeCount> top(Map<String, Integer> counts) {
        List<TypeCount> all = new ArrayList<>();
        counts.forEach((k, v) -> all.add(new TypeCount(k, v)));
        all.sort(Comparator.comparingInt(TypeCount::count).reversed().thenComparing(TypeCount::type));
        return all.size() > DETAIL_TYPES ? List.copyOf(all.subList(0, DETAIL_TYPES)) : all;
    }

    @Nullable
    static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** The middle of a chunk, for a place in block coordinates. */
    public static BlockPos middle(int x, int z) {
        return new BlockPos(new ChunkPos(x, z).getMiddleBlockX(), 64, new ChunkPos(x, z).getMiddleBlockZ());
    }

    // ------------------------------------------------------------------ text form

    private static String clean(String s) {
        return s.replace('|', '/').replace('\n', ' ').replace(';', ',');
    }

    private static ChunkCensus.Why why(String s) {
        try {
            int i = Integer.parseInt(s);
            return i >= 0 && i < ChunkCensus.Why.values().length ? ChunkCensus.Why.values()[i] : ChunkCensus.Why.OTHER;
        } catch (NumberFormatException e) {
            return ChunkCensus.Why.OTHER;
        }
    }

    /** Summaries, one world a line, its busiest chunks after it separated by {@code ;}. */
    public static String encodeSummaries(List<Summary> list) {
        StringBuilder b = new StringBuilder();
        for (Summary s : list) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(clean(s.dimension())).append(SEP).append(s.chunks()).append(SEP).append(s.ticking()).append(SEP).append(s.entities())
                    .append(SEP).append(s.blockEntities()).append(SEP);
            int[] byWhy = s.byWhy();
            for (int i = 0; i < byWhy.length; i++) {
                b.append(i == 0 ? "" : ",").append(byWhy[i]);
            }
            for (ListRow r : s.rows()) {
                b.append(';').append(r.x()).append(',').append(r.z()).append(',').append(r.why().ordinal()).append(',').append(r.entities())
                        .append(',').append(r.blockEntities()).append(',').append(clean(r.who()).replace(',', ' '));
            }
        }
        return b.toString();
    }

    public static List<Summary> decodeSummaries(String text) {
        List<Summary> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        for (String line : text.split("\n")) {
            String[] parts = line.split(";", -1);
            String[] f = parts[0].split("\\|", -1);
            if (f.length != 6) {
                continue;
            }
            try {
                String[] w = f[5].split(",");
                int[] byWhy = new int[ChunkCensus.Why.values().length];
                for (int i = 0; i < Math.min(byWhy.length, w.length); i++) {
                    byWhy[i] = Integer.parseInt(w[i]);
                }
                List<ListRow> rows = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    String[] r = parts[i].split(",", 6);
                    if (r.length == 6) {
                        rows.add(new ListRow(Integer.parseInt(r[0]), Integer.parseInt(r[1]), why(r[2]), r[5], Integer.parseInt(r[3]), Integer.parseInt(r[4])));
                    }
                }
                out.add(new Summary(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), byWhy, rows));
            } catch (NumberFormatException e) {
                // a bad line is left out
            }
        }
        return out;
    }

    /** A world's map rows in full. */
    public static String encodeMap(String dimension, List<MapRow> rows) {
        return encodeMap(dimension, rows, Integer.MAX_VALUE, 0, 0);
    }

    /**
     * A head line with the world, then the rows, {@code x,z,why,entities,blockEntities,ticking} separated by {@code ;}. Longer
     * than {@code budget}: whole rows, the nearest {@code cx, cz} first ({@link #BUSIEST} for both: the nearest the busiest
     * chunk), as many as fit.
     */
    public static String encodeMap(String dimension, List<MapRow> rows, int budget, int cx, int cz) {
        StringBuilder b = new StringBuilder(clean(dimension)).append('\n');
        int body = b.length();
        for (MapRow r : rows) {
            row(b.length() > body ? b.append(';') : b, r);
        }
        if (b.length() <= budget) {
            return b.toString();
        }
        if (cx == BUSIEST && cz == BUSIEST) {
            MapRow top = busiest(rows);
            cx = top == null ? 0 : top.x();
            cz = top == null ? 0 : top.z();
        }
        int mx = cx, mz = cz;
        List<MapRow> near = new ArrayList<>(rows);
        near.sort(Comparator.comparingLong((MapRow r) -> distance(r, mx, mz)).thenComparingInt(MapRow::x).thenComparingInt(MapRow::z));
        b.setLength(body);
        StringBuilder one = new StringBuilder();
        for (MapRow r : near) {
            one.setLength(0);
            row(one, r);
            if (b.length() + (b.length() > body ? 1 : 0) + one.length() > budget) {
                break;
            }
            (b.length() > body ? b.append(';') : b).append(one);
        }
        return b.toString();
    }

    /** The busiest of the rows: most entities, then block entities; the first of equals, as the screen centres on (null for none). */
    @Nullable
    public static MapRow busiest(List<MapRow> rows) {
        return rows.stream().max(Comparator.comparingInt(MapRow::entities).thenComparingInt(MapRow::blockEntities)).orElse(null);
    }

    private static long distance(MapRow r, int cx, int cz) {
        long dx = r.x() - (long) cx, dz = r.z() - (long) cz;
        return dx * dx + dz * dz;
    }

    private static void row(StringBuilder b, MapRow r) {
        b.append(r.x()).append(',').append(r.z()).append(',').append(r.why().ordinal()).append(',').append(r.entities()).append(',')
                .append(r.blockEntities()).append(',').append(r.ticking() ? 1 : 0);
    }

    /** A map answer: its world (empty when the head line is missing) and its rows. */
    public static MapAnswer decodeMap(String text) {
        int nl = text.indexOf('\n');
        String dimension = nl < 0 ? "" : text.substring(0, nl), body = nl < 0 ? text : text.substring(nl + 1);
        List<MapRow> out = new ArrayList<>();
        if (body.isEmpty()) {
            return new MapAnswer(dimension, out);
        }
        for (String part : body.split(";")) {
            String[] f = part.split(",");
            if (f.length != 6) {
                continue;
            }
            try {
                out.add(new MapRow(Integer.parseInt(f[0]), Integer.parseInt(f[1]), why(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), f[5].equals("1")));
            } catch (NumberFormatException e) {
                // a bad row is left out
            }
        }
        return new MapAnswer(dimension, out);
    }

    /**
     * A chunk's detail: a head line (its last field the safe spot as {@code x,y,z}, empty for none), then the entities line
     * and the block entities line ({@code type,count} by {@code ;}).
     */
    public static String encodeDetail(Detail d) {
        StringBuilder b = new StringBuilder();
        b.append(clean(d.dimension())).append(SEP).append(d.x()).append(SEP).append(d.z()).append(SEP).append(d.loaded() ? 1 : 0).append(SEP)
                .append(d.why().ordinal()).append(SEP).append(clean(d.who())).append(SEP).append(d.ticking() ? 1 : 0).append(SEP);
        if (d.spot() != null) {
            b.append(d.spot().getX()).append(',').append(d.spot().getY()).append(',').append(d.spot().getZ());
        }
        b.append('\n');
        types(b, d.entities());
        b.append('\n');
        types(b, d.blockEntities());
        return b.toString();
    }

    private static void types(StringBuilder b, List<TypeCount> list) {
        for (int i = 0; i < list.size(); i++) {
            b.append(i == 0 ? "" : ";").append(clean(list.get(i).type()).replace(',', ' ')).append(',').append(list.get(i).count());
        }
    }

    @Nullable
    public static Detail decodeDetail(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length != 3) {
            return null;
        }
        String[] f = lines[0].split("\\|", -1);
        if (f.length != 8) {
            return null;
        }
        try {
            BlockPos spot = null;
            if (!f[7].isEmpty()) {
                String[] p = f[7].split(",");
                if (p.length != 3) {
                    return null;
                }
                spot = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
            return new Detail(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3].equals("1"), why(f[4]), f[5], f[6].equals("1"),
                    decodeTypes(lines[1]), decodeTypes(lines[2]), spot);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<TypeCount> decodeTypes(String line) {
        List<TypeCount> out = new ArrayList<>();
        if (line.isEmpty()) {
            return out;
        }
        for (String part : line.split(";")) {
            int comma = part.lastIndexOf(',');
            if (comma > 0) {
                try {
                    out.add(new TypeCount(part.substring(0, comma), Integer.parseInt(part.substring(comma + 1))));
                } catch (NumberFormatException e) {
                    // left out
                }
            }
        }
        return out;
    }
}
