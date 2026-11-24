package com.mrgregles.bsp_server_utilities.chunks;

import com.mrgregles.bsp_server_utilities.mixin.ChunkMapAccess;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every chunk this server holds loaded, with why it is loaded and what is in it ({@code /su chunks}, SU-147). Read on the
 * server thread when asked; nothing is kept or run between calls.
 * <p>
 * Minecraft does not say which ticket loaded a chunk (a ticket loads its neighbours too), so the reason is worked out
 * from distance, first match wins: within the view distance plus two of a player; within two of a mod's Forge ticket
 * (machines, BSP-Core's totems); within two of a vanilla {@code /forceload}; within eleven of the overworld's spawn;
 * anything else (portals, a player who just left, mods' temporary tickets, Bukkit plugins' chunks, and on Mohist the
 * spawn areas Bukkit keeps loaded in every world) is "other". Spectators count only where they load chunks.
 */
public final class ChunkCensus {
    /** A ticket at level 31 (Forge's and {@code /forceload}) keeps chunks loaded up to two away. */
    static final int TICKET_REACH = 2;
    /** The spawn ticket (level 22) keeps chunks loaded up to eleven away. */
    static final int SPAWN_REACH = 11;

    private ChunkCensus() {
    }

    /** Why a chunk is loaded, as far as distance tells. */
    public enum Why {
        PLAYER, TICKET, FORCED, SPAWN, OTHER
    }

    /**
     * One loaded chunk: its packed position, why, who (a player's name or a mod id; empty when no one), entities other
     * than players, block entities, whether its blocks tick (inside the simulation distance or a ticket's reach, which can be
     * fewer chunks than are loaded), and its most common entity type with how many.
     */
    public record Row(long chunk, Why why, String who, int entities, int blockEntities, boolean ticking, String topType, int topCount) {
        public ChunkPos pos() {
            return new ChunkPos(chunk);
        }

        /** The middle of the chunk, at sea level, for showing in block coordinates. */
        public BlockPos middle() {
            return new BlockPos(pos().getMiddleBlockX(), 64, pos().getMiddleBlockZ());
        }
    }

    /** One dimension: its id and its rows. */
    public record Dim(String dimension, List<Row> rows) {
        public int chunks() {
            return rows.size();
        }

        public int ticking() {
            return (int) rows.stream().filter(Row::ticking).count();
        }

        public int entities() {
            return rows.stream().mapToInt(Row::entities).sum();
        }

        public int blockEntities() {
            return rows.stream().mapToInt(Row::blockEntities).sum();
        }

        public Map<Why, Integer> byWhy() {
            Map<Why, Integer> m = new EnumMap<>(Why.class);
            for (Why w : Why.values()) {
                m.put(w, 0);
            }
            rows.forEach(r -> m.merge(r.why(), 1, Integer::sum));
            return m;
        }
    }

    /** A chunk with the dimension it is in. */
    public record Placed(String dimension, Row row) {
    }

    /** Every dimension with at least one loaded chunk, overworld first, then by id. */
    public static List<Dim> take(MinecraftServer server) {
        List<Dim> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            Dim d = of(level);
            if (!d.rows().isEmpty()) {
                out.add(d);
            }
        }
        out.sort(Comparator.comparing((Dim d) -> !d.dimension().equals(Level.OVERWORLD.location().toString())).thenComparing(Dim::dimension));
        return out;
    }

    /** The chunks with the most entities across every dimension (block entities break ties), at most {@code limit}. */
    public static List<Placed> busiest(List<Dim> dims, int limit) {
        List<Placed> all = new ArrayList<>();
        for (Dim d : dims) {
            for (Row r : d.rows()) {
                if (r.entities() > 0 || r.blockEntities() > 0) {
                    all.add(new Placed(d.dimension(), r));
                }
            }
        }
        all.sort(Comparator.comparingInt((Placed p) -> p.row().entities()).thenComparingInt(p -> p.row().blockEntities()).reversed()
                .thenComparing(Placed::dimension).thenComparingLong(p -> p.row().chunk()));
        return all.size() > limit ? List.copyOf(all.subList(0, limit)) : all;
    }

    /** How many loaded chunks each mod's tickets hold across these dimensions (only chunks the ticket itself is in). */
    public static Map<String, Integer> ticketsByMod(MinecraftServer server) {
        Map<String, Integer> out = new TreeMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ChunkTickets.Ticket t : ChunkTickets.live(level)) {
                if (seen.add(t.modId() + "@" + t.chunk())) {
                    out.merge(t.modId(), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** One level's loaded chunks. */
    public static Dim of(ServerLevel level) {
        String dim = level.dimension().location().toString();
        // who is near what, as packed chunk positions
        Long2ObjectOpenHashMap<String> players = new Long2ObjectOpenHashMap<>();
        int view = level.getServer().getPlayerList().getViewDistance() + TICKET_REACH;
        boolean spectatorsLoad = level.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() && !spectatorsLoad) {
                continue; // Minecraft loads nothing for them
            }
            spread(players, p.chunkPosition(), view, p.getGameProfile().getName());
        }
        Long2ObjectOpenHashMap<String> tickets = new Long2ObjectOpenHashMap<>();
        for (ChunkTickets.Ticket t : ChunkTickets.live(level)) {
            spread(tickets, t.chunkPos(), TICKET_REACH, t.modId());
        }
        Long2ObjectOpenHashMap<String> forced = new Long2ObjectOpenHashMap<>();
        for (long c : level.getForcedChunks().toLongArray()) {
            spread(forced, new ChunkPos(c), TICKET_REACH, "");
        }
        Long2ObjectOpenHashMap<String> spawn = new Long2ObjectOpenHashMap<>();
        if (level.dimension() == Level.OVERWORLD) {
            spread(spawn, new ChunkPos(level.getSharedSpawnPos()), SPAWN_REACH, "");
        }
        // entities other than players, by chunk, with their types
        Long2IntOpenHashMap entities = new Long2IntOpenHashMap();
        Long2ObjectOpenHashMap<Object2IntOpenHashMap<EntityType<?>>> types = new Long2ObjectOpenHashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (e == null || e instanceof Player) {
                continue;
            }
            long c = e.chunkPosition().toLong();
            entities.addTo(c, 1);
            types.computeIfAbsent(c, k -> new Object2IntOpenHashMap<>()).addTo(e.getType(), 1);
        }
        List<Row> rows = new ArrayList<>();
        for (ChunkHolder h : ((ChunkMapAccess) level.getChunkSource().chunkMap).bspSu$chunks()) {
            LevelChunk chunk = h.getFullChunk();
            if (chunk == null) {
                continue; // still generating or loading: not a loaded chunk yet
            }
            long c = h.getPos().toLong();
            Why why;
            String who;
            if (players.containsKey(c)) {
                why = Why.PLAYER;
                who = players.get(c);
            } else if (tickets.containsKey(c)) {
                why = Why.TICKET;
                who = tickets.get(c);
            } else if (forced.containsKey(c)) {
                why = Why.FORCED;
                who = "";
            } else if (spawn.containsKey(c)) {
                why = Why.SPAWN;
                who = "";
            } else {
                why = Why.OTHER;
                who = "";
            }
            String topType = "";
            int topCount = 0;
            Object2IntOpenHashMap<EntityType<?>> t = types.get(c);
            if (t != null) {
                for (Object2IntOpenHashMap.Entry<EntityType<?>> e : t.object2IntEntrySet()) {
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()).toString();
                    if (e.getIntValue() > topCount || (e.getIntValue() == topCount && id.compareTo(topType) < 0)) {
                        topCount = e.getIntValue();
                        topType = id;
                    }
                }
            }
            rows.add(new Row(c, why, who, entities.get(c), chunk.getBlockEntities().size(), level.shouldTickBlocksAt(c), topType, topCount));
        }
        rows.sort(Comparator.comparingLong(Row::chunk));
        return new Dim(dim, rows);
    }

    /** Why one chunk is loaded and by whom (a player's name or a mod id; empty when no one). */
    public record Reason(Why why, String who) {
    }

    /**
     * Why one chunk of a level is loaded, by the same rules as {@link #of} (first match wins, in the same order of players
     * and tickets), without taking the census of the whole level: the LAG tab's detail of one chunk (audit of SU-153).
     * Meant for a loaded chunk; for one that is not, it says what would hold it.
     */
    public static Reason reason(ServerLevel level, int x, int z) {
        return reasons(level).at(x, z);
    }

    /** What {@link #reason} reads from a level, once, for asking about several of its chunks. */
    public static Reasons reasons(ServerLevel level) {
        return new Reasons(level);
    }

    /** A level's players, tickets, forced chunks and spawn as {@link #of} reads them, asked one chunk at a time. */
    public static final class Reasons {
        private final List<ChunkPos> players = new ArrayList<>(), tickets = new ArrayList<>();
        private final List<String> names = new ArrayList<>(), mods = new ArrayList<>();
        private final long[] forced;
        @org.jetbrains.annotations.Nullable
        private final ChunkPos spawn;
        private final int view;

        Reasons(ServerLevel level) {
            view = level.getServer().getPlayerList().getViewDistance() + TICKET_REACH;
            boolean spectatorsLoad = level.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator() && !spectatorsLoad) {
                    continue;
                }
                players.add(p.chunkPosition());
                names.add(p.getGameProfile().getName());
            }
            for (ChunkTickets.Ticket t : ChunkTickets.live(level)) {
                tickets.add(t.chunkPos());
                mods.add(t.modId());
            }
            forced = level.getForcedChunks().toLongArray();
            spawn = level.dimension() == Level.OVERWORLD ? new ChunkPos(level.getSharedSpawnPos()) : null;
        }

        private static boolean near(ChunkPos c, int x, int z, int reach) {
            return Math.abs(c.x - x) <= reach && Math.abs(c.z - z) <= reach;
        }

        public Reason at(int x, int z) {
            for (int i = 0; i < players.size(); i++) {
                if (near(players.get(i), x, z, view)) {
                    return new Reason(Why.PLAYER, names.get(i));
                }
            }
            for (int i = 0; i < tickets.size(); i++) {
                if (near(tickets.get(i), x, z, TICKET_REACH)) {
                    return new Reason(Why.TICKET, mods.get(i));
                }
            }
            for (long f : forced) {
                if (near(new ChunkPos(f), x, z, TICKET_REACH)) {
                    return new Reason(Why.FORCED, "");
                }
            }
            if (spawn != null && near(spawn, x, z, SPAWN_REACH)) {
                return new Reason(Why.SPAWN, "");
            }
            return new Reason(Why.OTHER, "");
        }
    }

    /** Marks every chunk within {@code reach} (square) of {@code centre} with {@code who}, keeping the first mark. */
    private static void spread(Long2ObjectOpenHashMap<String> into, ChunkPos centre, int reach, String who) {
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                into.putIfAbsent(ChunkPos.asLong(centre.x + dx, centre.z + dz), who);
            }
        }
    }
}
