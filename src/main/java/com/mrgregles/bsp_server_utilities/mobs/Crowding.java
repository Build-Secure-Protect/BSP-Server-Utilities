package com.mrgregles.bsp_server_utilities.mobs;

import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.despawn.DespawnService;
import com.mrgregles.bsp_server_utilities.despawn.DespawnSettings;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Crowded drops and arrows (owner decisions D487 to D501), on the server thread.
 *
 * <ul>
 *   <li>Every {@value #CHECK_TICKS} ticks the loaded chunks are counted: dropped items (each stack one) and arrows
 *   (normal and spectral; never a trident a player threw, D487).</li>
 *   <li>A chunk over a number ({@link MobSettings.Drops}) for the set minutes without a break is cleared down to it,
 *   oldest first (D489, D490). Dropping back under starts the time again. Death drops, BSP-Core's items, items a mod
 *   times itself and items set to "never" are kept, as {@code /su clearitems} keeps them (D491).</li>
 *   <li>Players within {@value #NEAR} blocks are told in chat and each clear is an Action Log entry, kind
 *   {@link LogEntry#DROPS} (D492). CLEAR NOW does the same at once for an admin (D499).</li>
 *   <li>The fullest chunks (D500) go with the mob list: each server answers the MOBS tab's ask with both.</li>
 * </ul>
 */
public final class Crowding {
    public static final int CHECK_TICKS = 600, NEAR = 32, SHOWN = 8;
    public static final String KIND_FULLEST = "drop_fullest", CHECKER = "Drops check";
    private static final long HOUR_MS = 3_600_000L;
    private static final String SEP = "\u001f";

    /** A chunk as one server reports it: items, arrows, how many items are kept, how long each kind has been over (0: not), a room's owner. */
    public record Row(String server, String dim, int x, int z, int items, int arrows, int kept, long itemsOverMs, long arrowsOverMs, String owner) {
    }

    /** A row with how long ago its server was heard from. */
    public record Seen(Row row, long ageMs) {
    }

    private record Remote(long atMs, List<Row> rows) {
    }

    /** What lies in one chunk. */
    private static final class Pile {
        final List<ItemEntity> items = new ArrayList<>();
        final List<AbstractArrow> arrows = new ArrayList<>();
    }

    private static final Map<String, Long> ITEMS_OVER = new HashMap<>(), ARROWS_OVER = new HashMap<>();
    private static final Deque<long[]> CLEARS = new ArrayDeque<>();
    private static final Map<String, Remote> REMOTE = new HashMap<>();
    private static long ticks;
    private static LongSupplier clock = System::currentTimeMillis;

    private Crowding() {
    }

    // ------------------------------------------------------------------ counting

    /** An arrow that counts: any arrow but a trident (D487). */
    public static boolean isArrow(Entity e) {
        return e instanceof AbstractArrow && !(e instanceof ThrownTrident) && e.isAlive();
    }

    /** Kept, as {@code /su clearitems} keeps them, and death drops (D491). */
    public static boolean kept(ItemEntity item, DespawnSettings s) {
        return item.getPersistentData().getBoolean(DespawnService.DEATH) || DespawnService.clearKeeps(item, s);
    }

    private static String key(ServerLevel level, long chunk) {
        return MobLimits.dimId(level) + "@" + chunk;
    }

    private static Map<Long, Pile> scan(ServerLevel level) {
        Map<Long, Pile> out = new HashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof ItemEntity item && item.isAlive()) {
                out.computeIfAbsent(new ChunkPos(e.blockPosition()).toLong(), k -> new Pile()).items.add(item);
            } else if (isArrow(e)) {
                out.computeIfAbsent(new ChunkPos(e.blockPosition()).toLong(), k -> new Pile()).arrows.add((AbstractArrow) e);
            }
        }
        return out;
    }

    /**
     * When an item was dropped, in the despawn timer's running time: its stamp, or for an item never stamped (despawn
     * times off) the running time less its own age, which the game saves with it (audit: unstamped items no longer all
     * count as the oldest).
     */
    private static long dropped(ItemEntity i, long now) {
        return i.getPersistentData().contains(DespawnService.STAMP) ? i.getPersistentData().getLong(DespawnService.STAMP) : now - i.getAge();
    }

    /** Oldest first: arrows by how long they have been about (not saved: after a reload they tie and keep their order). */
    private static final Comparator<AbstractArrow> OLD_ARROWS = Comparator.comparingInt((AbstractArrow a) -> a.tickCount).reversed();

    // ------------------------------------------------------------------ the check and the clear

    public static void tick(MinecraftServer server) {
        if (++ticks % CHECK_TICKS == 0) {
            check(server);
        }
    }

    /** One check: the time over for each crowded chunk, and the clear of those over for long enough. */
    public static void check(MinecraftServer server) {
        MobSettings.Drops d = MobLimits.settings(server).drops();
        DespawnSettings ds = DespawnService.settings(server);
        long now = clock.getAsLong(), wait = d.minutes() * 60_000L;
        Set<String> overItems = new HashSet<>(), overArrows = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Map.Entry<Long, Pile> en : scan(level).entrySet()) {
                String k = key(level, en.getKey());
                ChunkPos c = new ChunkPos(en.getKey());
                Pile p = en.getValue();
                if (d.items() > MobSettings.OFF && p.items.size() > d.items()) {
                    long since = ITEMS_OVER.computeIfAbsent(k, x -> now);
                    if (now - since >= wait) {
                        clearItems(level, c, p.items, d, ds, null);
                    } else {
                        overItems.add(k);
                    }
                }
                if (d.arrows() > MobSettings.OFF && p.arrows.size() > d.arrows()) {
                    long since = ARROWS_OVER.computeIfAbsent(k, x -> now);
                    if (now - since >= wait) {
                        clearArrows(level, c, p.arrows, d, null);
                    } else {
                        overArrows.add(k);
                    }
                }
            }
        }
        ITEMS_OVER.keySet().retainAll(overItems); // back under, cleared, or unloaded: the time starts again
        ARROWS_OVER.keySet().retainAll(overArrows);
    }

    private static int clearItems(ServerLevel level, ChunkPos c, List<ItemEntity> items, MobSettings.Drops d, DespawnSettings ds, @Nullable ServerPlayer by) {
        List<ItemEntity> removable = new ArrayList<>();
        for (ItemEntity i : items) {
            if (!kept(i, ds)) {
                removable.add(i);
            }
        }
        long now = DespawnService.now(level.getServer());
        removable.sort(Comparator.comparingLong(i -> dropped(i, now)));
        int extra = Math.min(items.size() - d.items(), removable.size());
        for (int n = 0; n < extra; n++) {
            com.mrgregles.bsp_server_utilities.rooms.RoomLosses.itemGone(removable.get(n), "cleared"); // a bound Compact Machine frees its room, as /su clearitems does (audit)
            removable.get(n).discard();
        }
        if (extra > 0) {
            told(level, c, items.size(), extra, 0, d, by);
        }
        return Math.max(0, extra);
    }

    private static int clearArrows(ServerLevel level, ChunkPos c, List<AbstractArrow> arrows, MobSettings.Drops d, @Nullable ServerPlayer by) {
        List<AbstractArrow> sorted = new ArrayList<>(arrows);
        sorted.sort(OLD_ARROWS);
        int extra = arrows.size() - d.arrows();
        for (int n = 0; n < extra; n++) {
            sorted.get(n).discard();
        }
        if (extra > 0) {
            told(level, c, arrows.size(), 0, extra, d, by);
        }
        return Math.max(0, extra);
    }

    /** The chat line to players near the chunk, the Action Log entry and the last hour's count. */
    private static void told(ServerLevel level, ChunkPos c, int had, int items, int arrows, MobSettings.Drops d, @Nullable ServerPlayer by) {
        MinecraftServer server = level.getServer();
        long now = clock.getAsLong();
        lastHour(); // trimmed as it grows, not only when read (audit)
        CLEARS.addLast(new long[]{now, items, arrows});
        boolean isItems = items > 0;
        int n = isItems ? items : arrows, limit = isItems ? d.items() : d.arrows();
        String which = isItems ? "items" : "arrows";
        Component line;
        if (by == null) {
            line = isItems ? Component.translatable("message.bsp_server_utilities.drops.cleared_items", n, d.minutes())
                    : Component.translatable("message.bsp_server_utilities.drops.cleared_arrows", n, d.minutes());
        } else {
            line = isItems ? Component.translatable("message.bsp_server_utilities.drops.cleared_staff_items", n)
                    : Component.translatable("message.bsp_server_utilities.drops.cleared_staff_arrows", n);
        }
        for (ServerPlayer p : level.players()) {
            // within NEAR blocks of the chunk itself, corners included (audit)
            double dx = Math.max(0, Math.max(c.getMinBlockX() - p.getX(), p.getX() - (c.getMaxBlockX() + 1)));
            double dz = Math.max(0, Math.max(c.getMinBlockZ() - p.getZ(), p.getZ() - (c.getMaxBlockZ() + 1)));
            if (dx * dx + dz * dz <= NEAR * NEAR) {
                p.sendSystemMessage(line.copy().withStyle(ChatFormatting.GOLD));
            }
        }
        String owner = MobLimits.roomOwner(server, level, c);
        String place = !owner.isEmpty() ? owner + "'s room" : MobPlan.words(MobLimits.dimId(level)) + " " + c.x + " " + c.z;
        String text = n + " " + which + " cleared on " + Storage.currentServerId() + ", " + place + " (" + had + (by == null ? " for " + d.minutes() + " min" : "") + ", limit " + limit + ")"
                + (by == null ? "" : " with CLEAR NOW");
        ActionLog.record(server, by == null ? ActionLog.entryForServer(CHECKER, LogEntry.DROPS, text)
                : ActionLog.entryFor(by, ActionLog.tracked(by).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.DROPS, text));
    }

    /**
     * CLEAR NOW (D499): a chunk of this server cleared down to its numbers at once, items and arrows each when over and
     * on, by {@code by}. Returns how many went (0: not loaded, not over, all kept).
     */
    public static int clearNow(ServerPlayer by, String dim, int x, int z) {
        MinecraftServer server = by.server;
        ResourceLocation id = ResourceLocation.tryParse(dim);
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null || !level.getChunkSource().hasChunk(x, z)) {
            return 0;
        }
        MobSettings.Drops d = MobLimits.settings(server).drops();
        Pile p = scan(level).get(ChunkPos.asLong(x, z));
        if (p == null) {
            return 0;
        }
        ChunkPos c = new ChunkPos(x, z);
        int gone = 0;
        if (d.items() > MobSettings.OFF && p.items.size() > d.items()) {
            gone += clearItems(level, c, p.items, d, DespawnService.settings(server), by);
        }
        if (d.arrows() > MobSettings.OFF && p.arrows.size() > d.arrows()) {
            gone += clearArrows(level, c, p.arrows, d, by);
        }
        String k = key(level, c.toLong());
        ITEMS_OVER.remove(k);
        ARROWS_OVER.remove(k);
        return gone;
    }

    /** This server's clears in the last hour: {clears, items, arrows} (D501). */
    public static long[] lastHour() {
        long now = clock.getAsLong();
        while (!CLEARS.isEmpty() && now - CLEARS.peekFirst()[0] > HOUR_MS) {
            CLEARS.pollFirst();
        }
        long[] out = new long[3];
        for (long[] c : CLEARS) {
            out[0]++;
            out[1] += c[1];
            out[2] += c[2];
        }
        return out;
    }

    // ------------------------------------------------------------------ the fullest chunks

    /** How full a row is against the numbers that are on: over 1 is over. */
    private static double fullness(Row r, MobSettings.Drops d) {
        double f = 0;
        if (d.items() > MobSettings.OFF) {
            f = Math.max(f, r.items() / (double) d.items());
        }
        if (d.arrows() > MobSettings.OFF) {
            f = Math.max(f, r.arrows() / (double) d.arrows());
        }
        return f;
    }

    private static int threshold(int number, int fallback) {
        return number > MobSettings.OFF ? Math.max(1, number / 4) : fallback / 4;
    }

    /** This server's fullest chunks: {@value #SHOWN} of a quarter of a number or more (D500), the most over first. */
    public static List<Row> localFullest(MinecraftServer server) {
        MobSettings.Drops d = MobLimits.settings(server).drops();
        DespawnSettings ds = DespawnService.settings(server);
        int minItems = threshold(d.items(), MobSettings.Drops.DEFAULTS.items()), minArrows = threshold(d.arrows(), MobSettings.Drops.DEFAULTS.arrows());
        long now = clock.getAsLong();
        String here = Storage.currentServerId();
        List<Row> rows = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Map.Entry<Long, Pile> en : scan(level).entrySet()) {
                Pile p = en.getValue();
                if (p.items.size() < minItems && p.arrows.size() < minArrows) {
                    continue;
                }
                ChunkPos c = new ChunkPos(en.getKey());
                String k = key(level, en.getKey());
                int kept = (int) p.items.stream().filter(i -> kept(i, ds)).count();
                Long is = ITEMS_OVER.get(k), as = ARROWS_OVER.get(k);
                rows.add(new Row(here, MobLimits.dimId(level), c.x, c.z, p.items.size(), p.arrows.size(), kept, is == null ? 0 : Math.max(1, now - is), as == null ? 0 : Math.max(1, now - as),
                        MobLimits.roomOwner(server, level, c)));
            }
        }
        rows.sort(Comparator.comparingDouble((Row r) -> fullness(r, d)).reversed().thenComparing(Comparator.comparingInt((Row r) -> r.items() + r.arrows()).reversed()));
        return rows.size() > SHOWN ? List.copyOf(rows.subList(0, SHOWN)) : rows;
    }

    /** Sent with the mob list when the MOBS tab asks. */
    static void publish(MinecraftServer server) {
        Storage s = Storage.active();
        if (s != null) {
            s.publish(KIND_FULLEST, encode(s.serverId(), localFullest(server)));
        }
    }

    public static String encode(String serverId, List<Row> rows) {
        StringBuilder b = new StringBuilder(serverId);
        for (Row r : rows) {
            b.append('\n').append(r.dim()).append('|').append(r.x()).append('|').append(r.z()).append('|').append(r.items()).append('|').append(r.arrows()).append('|').append(r.kept())
                    .append('|').append(r.itemsOverMs()).append('|').append(r.arrowsOverMs()).append('|').append(r.owner().replace('|', '/').replace('\n', ' '));
        }
        return b.toString();
    }

    /** Another server's list, kept with when it came. */
    public static void applyFullest(String payload) {
        String[] lines = payload.split("\n");
        if (lines.length == 0 || lines[0].isBlank() || lines[0].contains(SEP) || lines[0].equals(Storage.currentServerId())) {
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            if (f.length != 9) {
                continue;
            }
            try {
                rows.add(new Row(lines[0], f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5]),
                        Long.parseLong(f[6]), Long.parseLong(f[7]), f[8]));
            } catch (NumberFormatException ignored) {
                // a bad line
            }
        }
        REMOTE.put(lines[0], new Remote(clock.getAsLong(), List.copyOf(rows)));
    }

    /** The fullest chunks on the network: this server's now, the others' as last heard (dropped after 2 min), the most over first. */
    public static List<Seen> networkFullest(MinecraftServer server) {
        MobSettings.Drops d = MobLimits.settings(server).drops();
        long now = clock.getAsLong();
        List<Seen> out = new ArrayList<>();
        for (Row r : localFullest(server)) {
            out.add(new Seen(r, 0));
        }
        REMOTE.forEach((srv, r) -> {
            if (now - r.atMs() <= MobLimits.DROP_MS) {
                for (Row row : r.rows()) {
                    out.add(new Seen(row, now - r.atMs()));
                }
            }
        });
        out.sort(Comparator.comparingDouble((Seen s) -> fullness(s.row(), d)).reversed().thenComparing(Comparator.comparingInt((Seen s) -> s.row().items() + s.row().arrows()).reversed()));
        return out.size() > SHOWN ? List.copyOf(out.subList(0, SHOWN)) : out;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_FULLEST, (srv, n) -> applyFullest(n.payload()));
    }

    public static void onServerStarted() {
        ITEMS_OVER.clear();
        ARROWS_OVER.clear();
        CLEARS.clear();
        REMOTE.clear();
        ticks = 0;
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock) {
        clock = testClock;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        REMOTE.clear();
        ITEMS_OVER.clear(); // a test clock's times never reach real time (audit)
        ARROWS_OVER.clear();
    }
}
