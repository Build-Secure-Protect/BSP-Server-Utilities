package com.mrgregles.bsp_server_utilities.mobs;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.BabyEntitySpawnEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Mob limits (owner decisions D471 to D486), on the server thread.
 *
 * <ul>
 *   <li><b>What counts</b> is a mob (anything alive with a mind of its own: monsters, animals, villagers, golems, bats,
 *   fish; never players or armour stands), whose type is not on the never-counted list, standing in the chunk (D471,
 *   D477).</li>
 *   <li><b>At the limit</b> a new mob is refused in that chunk, from wherever it comes (natural spawns, spawners,
 *   mob-farm machines that spawn through the game, breeding, spawn eggs, {@code /summon}: D473, D474), where the game
 *   asks whether to spawn it ({@link #REFUSED}). Mobs loaded with their chunk, moving between dimensions, turning into
 *   another kind, let out of a bucket, raiders and jockeys are never refused; nor are mobs walking in from a neighbour,
 *   which count all the same. A player whose breeding or spawn egg was refused is told on the action bar, and keeps the egg.</li>
 *   <li><b>The settings</b> are one set for the network (table and a {@value #KIND_SETTINGS} notice, the newest revision
 *   wins: D476).</li>
 *   <li><b>The fullest chunks</b> on every server (D479, D486): while an admin has the MOBS tab open it asks every few
 *   seconds, and each server answers with its own 8 fullest chunks of 10 mobs or more and the dimensions it has. A server
 *   not heard from for {@value #DIM_MS} ms is shown dimmed, and left out after {@value #DROP_MS} ms.</li>
 *   <li><b>Removing extras</b> from a chunk of this server (D475, D485), on an admin's word: {@link MobPlan}.</li>
 * </ul>
 */
public final class MobLimits {
    public static final String KIND_SETTINGS = "mob_settings", KIND_ASK = "mob_ask", KIND_FULLEST = "mob_fullest";
    public static final int SHOWN = 8, MIN_SHOWN = 10, MAX_TYPES = 16;
    public static final long DIM_MS = 30_000L, DROP_MS = 120_000L, ANSWER_MS = 4_000L;
    private static final String SEP = "\u001f";

    /** A chunk as one server reports it: where, how many mobs, its number and where that comes from, a room's owner, and what is in it. */
    public record Row(String server, String dim, int x, int z, int count, int limit, MobSettings.From from, String owner, List<MobPlan.TypeCount> types) {
        public Row {
            types = List.copyOf(types);
        }
    }

    /** A row as the tab shows it, with how long ago its server was heard from. */
    public record Seen(Row row, long ageMs) {
        public boolean stale() {
            return ageMs > DIM_MS;
        }
    }

    private record Remote(long atMs, List<Row> rows, List<String> dims) {
    }

    private static final Map<String, Remote> REMOTE = new HashMap<>();
    private static long lastAsk, lastAnswer, refused;
    private static LongSupplier clock = System::currentTimeMillis;

    private MobLimits() {
    }

    // ------------------------------------------------------------------ settings

    public static MobSettings settings(MinecraftServer server) {
        return MobData.get(server).settings();
    }

    /** Replaces the settings for the whole network. */
    public static MobSettings setSettings(MinecraftServer server, MobSettings s) {
        MobData data = MobData.get(server);
        MobSettings next = s.withRevision(Math.max(System.currentTimeMillis(), data.settings().revision() + 1)).sanitised();
        data.setSettings(next);
        Storage st = Storage.active();
        if (st != null) {
            st.submit(() -> st.backend().saveMobSettings(next));
            st.publish(KIND_SETTINGS, next.encode());
        }
        return next;
    }

    /** Takes a shared copy of the settings when it is newer. */
    public static void apply(MinecraftServer server, MobSettings shared) {
        MobData data = MobData.get(server);
        if (shared.revision() > data.settings().revision()) {
            data.setSettings(shared);
        }
    }

    // ------------------------------------------------------------------ counting

    public static String dimId(Level level) {
        return level.dimension().location().toString();
    }

    public static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** A mob that counts: alive, not a player or an armour stand (only {@link Mob}s), not on the never-counted list. */
    public static boolean counts(Entity e, MobSettings s) {
        return e instanceof Mob && e.isAlive() && !s.never().contains(typeId(e));
    }

    /** Tamed or name-tagged: counted, never removed (D475). */
    public static boolean kept(Mob m) {
        return m.hasCustomName() || m instanceof OwnableEntity o && o.getOwnerUUID() != null;
    }

    /** The mobs that count in a chunk. */
    public static List<Mob> mobsIn(ServerLevel level, ChunkPos c, MobSettings s) {
        AABB box = new AABB(c.getMinBlockX(), level.getMinBuildHeight(), c.getMinBlockZ(), c.getMaxBlockX() + 1, level.getMaxBuildHeight(), c.getMaxBlockZ() + 1);
        List<Mob> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, e -> counts(e, s) && new ChunkPos(e.blockPosition()).equals(c))) {
            out.add((Mob) e);
        }
        return out;
    }

    public static int count(ServerLevel level, ChunkPos c) {
        return mobsIn(level, c, settings(level.getServer())).size();
    }

    public static MobSettings.Limit limitAt(ServerLevel level, ChunkPos c) {
        return settings(level.getServer()).limitFor(Storage.currentServerId(), dimId(level), c.x, c.z);
    }

    /** The chunk's number when it is full (a new mob there is refused), else null. */
    @Nullable
    public static MobSettings.Limit full(ServerLevel level, BlockPos pos) {
        MobSettings s = settings(level.getServer());
        if (!s.anyOn()) {
            return null;
        }
        ChunkPos c = new ChunkPos(pos);
        MobSettings.Limit l = s.limitFor(Storage.currentServerId(), dimId(level), c.x, c.z);
        return l.on() && mobsIn(level, c, s).size() >= l.limit() ? l : null;
    }

    private static void tell(ServerPlayer p, MobSettings.Limit l) {
        p.displayClientMessage(Component.translatable("message.bsp_server_utilities.mobs.full", l.limit()), true);
    }

    /** New mobs refused at their chunk's limit since the server started ({@code /su status}). */
    public static long refused() {
        return refused;
    }

    /**
     * The reasons a mob is refused for (D474): natural spawns, spawners, structures and world generation, mobs summoned by
     * other mobs, reinforcements, patrols, spawn eggs, dispensers and {@code /summon}. Never: a jockey's mount or rider (the
     * mob it belongs to decides), raiders (a refused one would hold its raid open), mobs turning into another kind, mobs
     * let out of a bucket, and trap-triggered mobs, all of which the game adds whatever the answer (audit).
     */
    public static final Set<MobSpawnType> REFUSED = Set.copyOf(EnumSet.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.SPAWNER, MobSpawnType.STRUCTURE,
            MobSpawnType.BREEDING, MobSpawnType.MOB_SUMMONED, MobSpawnType.REINFORCEMENT, MobSpawnType.SPAWN_EGG, MobSpawnType.COMMAND, MobSpawnType.DISPENSER, MobSpawnType.PATROL));

    /** Whether a new mob of {@code type} counts at all (never-counted types are never refused, D477). */
    private static boolean countsType(MinecraftServer server, @Nullable EntityType<?> type) {
        return type == null || !settings(server).never().contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    /**
     * Once, in the mod constructor. New mobs are refused where the game asks whether to spawn them (Forge's
     * {@code FinalizeSpawn}, which also skips their own set-up, so a refused zombie brings no chicken), never as they join
     * the level: many of the game's paths add a new mob before removing the old one and ignore the answer (audit).
     */
    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((MobSpawnEvent.FinalizeSpawn e) -> {
            if (!REFUSED.contains(e.getSpawnType()) || !(e.getLevel() instanceof ServerLevel level) || !counts(e.getEntity(), settings(level.getServer()))) {
                return; // world generation runs in a region, not the level: its chunk is not loaded yet, nothing to count
            }
            if (full(level, BlockPos.containing(e.getX(), e.getY(), e.getZ())) != null) {
                e.setSpawnCancelled(true);
                e.setCanceled(true);
                refused++;
            }
        });
        MinecraftForge.EVENT_BUS.addListener((BabyEntitySpawnEvent e) -> {
            if (e.getParentA().level() instanceof ServerLevel level && countsType(level.getServer(), e.getChild() != null ? e.getChild().getType() : e.getParentA().getType())) {
                MobSettings.Limit l = full(level, e.getParentA().blockPosition());
                if (l != null) {
                    e.setCanceled(true);
                    refused++;
                    if (e.getCausedByPlayer() instanceof ServerPlayer p) {
                        tell(p, l);
                    }
                }
            }
        });
        // spawn eggs are checked before they are used, so a refused egg is kept (D474); one fired from a dispenser is used up
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (e.getEntity() instanceof ServerPlayer p && e.getItemStack().getItem() instanceof SpawnEggItem egg) {
                ServerLevel level = p.serverLevel();
                BlockPos pos = e.getPos();
                if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity) {
                    return; // an egg on a spawner sets its mob: nothing is born here
                }
                Direction face = e.getFace() == null ? Direction.UP : e.getFace();
                BlockPos at = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() ? pos : pos.relative(face);
                if (eggRefused(p, egg, e.getItemStack(), at)) {
                    e.setCanceled(true);
                    e.setCancellationResult(InteractionResult.FAIL);
                }
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem e) -> { // an egg used on water, where the player is
            if (e.getEntity() instanceof ServerPlayer p && e.getItemStack().getItem() instanceof SpawnEggItem egg && eggRefused(p, egg, e.getItemStack(), p.blockPosition())) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.FAIL);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract e) -> { // an egg used on a mob of its kind makes a baby
            if (e.getEntity() instanceof ServerPlayer p && e.getItemStack().getItem() instanceof SpawnEggItem egg && e.getTarget() instanceof Mob
                    && eggRefused(p, egg, e.getItemStack(), e.getTarget().blockPosition())) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.FAIL);
            }
        });
    }

    private static boolean eggRefused(ServerPlayer p, SpawnEggItem egg, ItemStack stack, BlockPos at) {
        ServerLevel level = p.serverLevel();
        if (!countsType(level.getServer(), egg.getType(stack.getTag()))) {
            return false;
        }
        MobSettings.Limit l = full(level, at);
        if (l == null) {
            return false;
        }
        refused++;
        tell(p, l);
        return true;
    }

    // ------------------------------------------------------------------ the fullest chunks

    private static double fullness(Row r) {
        return r.limit() > MobSettings.OFF ? r.count() / (double) r.limit() : 0;
    }

    /** Over their limit most first, then the most mobs. */
    public static final Comparator<Row> WORST = Comparator.comparingDouble(MobLimits::fullness).reversed().thenComparing(Comparator.comparingInt(Row::count).reversed());

    private static String ownerName(MinecraftServer server, UUID id) {
        if (server.getProfileCache() == null) {
            return "";
        }
        return server.getProfileCache().get(id).map(GameProfile::getName).orElse("");
    }

    /** The owner of the Compact Machines room at chunk {@code c}, when {@code level} is the rooms' dimension; else "". */
    public static String roomOwner(MinecraftServer server, ServerLevel level, ChunkPos c) {
        if (!CompactMachines.present() || !level.dimension().equals(CompactMachines.ROOMS_LEVEL)) {
            return "";
        }
        return CompactMachines.room(server, c).map(r -> ownerName(server, r.owner())).orElse("");
    }

    /** This server's fullest chunks: {@value #SHOWN} at most, of {@value #MIN_SHOWN} mobs or more, worst first. */
    public static List<Row> localFullest(MinecraftServer server) {
        MobSettings s = settings(server);
        String here = Storage.currentServerId();
        List<Row> rows = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            Map<Long, List<Mob>> byChunk = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (counts(e, s)) {
                    byChunk.computeIfAbsent(new ChunkPos(e.blockPosition()).toLong(), k -> new ArrayList<>()).add((Mob) e);
                }
            }
            String dim = dimId(level);
            boolean rooms = CompactMachines.present() && level.dimension().equals(CompactMachines.ROOMS_LEVEL);
            for (Map.Entry<Long, List<Mob>> en : byChunk.entrySet()) {
                if (en.getValue().size() < MIN_SHOWN) {
                    continue;
                }
                ChunkPos c = new ChunkPos(en.getKey());
                MobSettings.Limit l = s.limitFor(here, dim, c.x, c.z);
                String owner = rooms ? CompactMachines.room(server, c).map(r -> ownerName(server, r.owner())).orElse("") : "";
                rows.add(new Row(here, dim, c.x, c.z, en.getValue().size(), l.limit(), l.from(), owner, types(en.getValue())));
            }
        }
        rows.sort(WORST);
        return rows.size() > SHOWN ? List.copyOf(rows.subList(0, SHOWN)) : rows;
    }

    /** How many of this server's chunks hold more mobs than their number, every chunk (not only the fullest shown). */
    public static int localOverCount(MinecraftServer server) {
        MobSettings s = settings(server);
        String here = Storage.currentServerId();
        int over = 0;
        for (ServerLevel level : server.getAllLevels()) {
            Map<Long, Integer> byChunk = new HashMap<>();
            for (Entity e : level.getAllEntities()) {
                if (counts(e, s)) {
                    byChunk.merge(new ChunkPos(e.blockPosition()).toLong(), 1, Integer::sum);
                }
            }
            String dim = dimId(level);
            for (Map.Entry<Long, Integer> en : byChunk.entrySet()) {
                ChunkPos c = new ChunkPos(en.getKey());
                MobSettings.Limit l = s.limitFor(here, dim, c.x, c.z);
                if (l.on() && en.getValue() > l.limit()) {
                    over++;
                }
            }
        }
        return over;
    }

    /** What is in a chunk, most numerous first, {@value #MAX_TYPES} kinds at most (the list). */
    public static List<MobPlan.TypeCount> types(List<Mob> mobs) {
        List<MobPlan.TypeCount> all = allTypes(mobs);
        return all.size() > MAX_TYPES ? List.copyOf(all.subList(0, MAX_TYPES)) : all;
    }

    /** Every kind in a chunk, most numerous first (removing extras plans over all of them, audit). */
    public static List<MobPlan.TypeCount> allTypes(List<Mob> mobs) {
        Map<String, int[]> by = new LinkedHashMap<>();
        for (Mob m : mobs) {
            int[] n = by.computeIfAbsent(typeId(m), k -> new int[2]);
            n[0]++;
            if (kept(m)) {
                n[1]++;
            }
        }
        List<MobPlan.TypeCount> out = new ArrayList<>();
        by.forEach((t, n) -> out.add(new MobPlan.TypeCount(t, n[0], n[1])));
        out.sort(MobPlan.ORDER);
        return out;
    }

    /** The dimensions this server has. */
    public static List<String> localDims(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.add(dimId(level));
        }
        return out;
    }

    /** The tab is open: every other server is asked for its list (at most every {@value #ANSWER_MS} ms). */
    public static void askNetwork() {
        Storage s = Storage.active();
        long now = clock.getAsLong();
        if (s != null && now - lastAsk >= ANSWER_MS) {
            lastAsk = now;
            s.publish(KIND_ASK, s.serverId());
        }
    }

    private static void answer(MinecraftServer server) {
        Storage s = Storage.active();
        long now = clock.getAsLong();
        if (s != null && now - lastAnswer >= ANSWER_MS) {
            lastAnswer = now;
            s.publish(KIND_FULLEST, encode(s.serverId(), localDims(server), localFullest(server)));
            Crowding.publish(server); // the DROPS & ARROWS page's list goes with it
        }
    }

    public static String encode(String serverId, List<String> dims, List<Row> rows) {
        StringBuilder b = new StringBuilder(serverId).append(SEP).append(String.join(",", dims));
        for (Row r : rows) {
            StringBuilder t = new StringBuilder();
            for (MobPlan.TypeCount c : r.types()) {
                t.append(t.length() == 0 ? "" : ",").append(c.type()).append('=').append(c.count()).append('/').append(c.kept());
            }
            b.append('\n').append(r.dim()).append('|').append(r.x()).append('|').append(r.z()).append('|').append(r.count()).append('|').append(r.limit())
                    .append('|').append(r.from().name()).append('|').append(r.owner().replace('|', '/').replace('\n', ' ')).append('|').append(t);
        }
        return b.toString();
    }

    /** Another server's answer: its id, dimensions and rows; null when it is not one. */
    @Nullable
    private static Remote decode(String payload, String[] serverOut) {
        String[] lines = payload.split("\n");
        String[] head = lines[0].split(SEP, -1);
        if (head.length != 2 || head[0].isBlank()) {
            return null;
        }
        serverOut[0] = head[0];
        List<String> dims = head[1].isEmpty() ? List.of() : List.of(head[1].split(","));
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            if (f.length != 8) {
                continue;
            }
            try {
                List<MobPlan.TypeCount> types = new ArrayList<>();
                for (String t : f[7].isEmpty() ? new String[0] : f[7].split(",")) {
                    int eq = t.lastIndexOf('='), sl = t.lastIndexOf('/');
                    if (eq > 0 && sl > eq) {
                        types.add(new MobPlan.TypeCount(t.substring(0, eq), Integer.parseInt(t.substring(eq + 1, sl)), Integer.parseInt(t.substring(sl + 1))));
                    }
                }
                rows.add(new Row(head[0], f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), MobSettings.From.valueOf(f[5]), f[6], types));
            } catch (IllegalArgumentException ignored) {
                // a bad line
            }
        }
        return new Remote(clock.getAsLong(), List.copyOf(rows), dims);
    }

    /** Another server's answer, kept with when it came. */
    public static void applyFullest(String payload) {
        String[] server = new String[1];
        Remote r = decode(payload, server);
        if (r != null && !server[0].equals(Storage.currentServerId())) {
            REMOTE.put(server[0], r);
        }
    }

    /** The fullest chunks on the network: this server's now, the others' as last heard (dropped after {@value #DROP_MS} ms), worst first. */
    public static List<Seen> networkFullest(MinecraftServer server) {
        long now = clock.getAsLong();
        List<Seen> out = new ArrayList<>();
        for (Row r : localFullest(server)) {
            out.add(new Seen(r, 0));
        }
        REMOTE.forEach((srv, r) -> {
            if (now - r.atMs() <= DROP_MS) {
                for (Row row : r.rows()) {
                    out.add(new Seen(row, now - r.atMs()));
                }
            }
        });
        out.sort(Comparator.comparing(Seen::row, WORST));
        return out.size() > SHOWN ? List.copyOf(out.subList(0, SHOWN)) : out;
    }

    /** Every dimension any server of the network has (D483), sorted. */
    public static List<String> networkDims(MinecraftServer server) {
        long now = clock.getAsLong();
        Set<String> all = new TreeSet<>(localDims(server));
        REMOTE.forEach((srv, r) -> {
            if (now - r.atMs() <= DROP_MS) {
                all.addAll(r.dims());
            }
        });
        return List.copyOf(all);
    }

    // ------------------------------------------------------------------ the admin's actions

    /**
     * A single chunk added with its current number (D481): the number that applies to it now, or when none, how many
     * mobs it holds (at least 1), so nothing more is born there until the admin changes it. Nothing when it is listed.
     */
    public static MobSettings addChunk(MinecraftServer server, String onServer, String dim, int x, int z, int count) {
        MobSettings s = settings(server);
        if (s.chunks().stream().anyMatch(c -> c.is(onServer, dim, x, z))) {
            return s;
        }
        MobSettings.Limit l = s.limitFor(onServer, dim, x, z);
        return setSettings(server, s.withChunk(onServer, dim, x, z, l.on() ? l.limit() : Math.max(1, count)));
    }

    /** A chunk of this server in words for the log: "Steve_42's room" or "overworld 120 -45". */
    public static String place(MinecraftServer server, ServerLevel level, ChunkPos c) {
        if (CompactMachines.present() && level.dimension().equals(CompactMachines.ROOMS_LEVEL)) {
            String owner = CompactMachines.room(server, c).map(r -> ownerName(server, r.owner())).orElse("");
            if (!owner.isEmpty()) {
                return owner + "'s room";
            }
        }
        return MobPlan.words(dimId(level)) + " " + c.x + " " + c.z;
    }

    /**
     * Removes a chunk's extras (D475, D485): the most numerous kind first, never tamed or name-tagged mobs, down to the
     * chunk's limit; written to the Action Log by {@code by}. Returns how many went (0: not loaded, no limit, not over).
     */
    public static int removeExtras(ServerPlayer by, String dim, int x, int z) {
        MinecraftServer server = by.server;
        ResourceLocation id = ResourceLocation.tryParse(dim);
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null || !level.getChunkSource().hasChunk(x, z)) {
            return 0;
        }
        MobSettings s = settings(server);
        ChunkPos c = new ChunkPos(x, z);
        MobSettings.Limit l = s.limitFor(Storage.currentServerId(), dim, x, z);
        List<Mob> mobs = mobsIn(level, c, s);
        if (!l.on() || mobs.size() <= l.limit()) {
            return 0;
        }
        List<MobPlan.TypeCount> plan = MobPlan.plan(allTypes(mobs), mobs.size(), l.limit());
        int removed = 0;
        for (MobPlan.TypeCount t : plan) {
            int left = t.count();
            for (Mob m : mobs) {
                if (left > 0 && m.isAlive() && typeId(m).equals(t.type()) && !kept(m)) {
                    m.discard();
                    left--;
                    removed++;
                }
            }
        }
        if (removed == 0) {
            return 0; // every extra is tamed or named: nothing to log
        }
        String text = by.getGameProfile().getName() + " removed " + removed + " mobs from " + place(server, level, c) + " on " + Storage.currentServerId()
                + " (" + mobs.size() + ", limit " + l.limit() + "): " + MobPlan.describe(plan);
        ActionLog.record(server, ActionLog.entryFor(by, ActionLog.tracked(by).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.MOB, text));
        return removed;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_SETTINGS, (srv, n) -> {
            MobSettings s = MobSettings.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
        Storage.on(KIND_ASK, (srv, n) -> answer(srv));
        Storage.on(KIND_FULLEST, (srv, n) -> applyFullest(n.payload()));
    }

    /** At server start, after the storage: the shared settings (or ours written as the first). */
    public static void onServerStarted(MinecraftServer server) {
        REMOTE.clear();
        refused = 0;
        Storage s = Storage.active();
        if (s != null) {
            MobSettings ours = settings(server); // read here, on the server thread
            s.submit(() -> {
                MobSettings shared = s.backend().loadMobSettings();
                if (shared == null) {
                    s.backend().saveMobSettings(ours);
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock) {
        clock = testClock;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        REMOTE.clear();
        lastAsk = lastAnswer = 0;
    }
}
