package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Every server's Compact Machines rooms (SU-54; owner decisions D438, D453 R5). Each server with Compact Machines reads
 * its own rooms from the mod every minute, and at once after {@link #changed()}: when they differ from what it last
 * wrote, it replaces its rows in {@code <prefix>cm_rooms} and tells the other servers (notice {@value #KIND}), which read
 * the table again; otherwise it only records that it was heard from. A server not heard from for {@link #STALE_MS} is
 * stale: its last rows still count (R5).
 *
 * <p>A player's count ({@link #count}) is this server's rooms read live from Compact Machines (so a room made a moment ago
 * counts) plus, with {@code scope = network}, the counting rows of every other server. Without shared storage there
 * are no other servers. Server thread only, except the database work on the storage worker.
 */
public final class RoomList {
    public static final String KIND = "cm_rooms";
    /** How often this server's rooms are read and written (ticks). */
    static final int REFRESH_TICKS = 1200;
    /** The least time between two refreshes asked for by {@link #changed()} (ticks): placing machines over and over rewrites the rows at most this often. */
    static final int CHANGED_TICKS = 100;
    /** The longest machine place kept (the table's column). */
    static final int MACHINE_MAX = 160;
    /** A server not heard from for this long is named as stale; its rooms still count (R5). */
    public static final long STALE_MS = 5 * 60_000L;

    private static RoomTable table = RoomTable.EMPTY;
    private static List<RoomRow> mine = List.of();
    /** This server's rows as last sent to be written (null again on the worker when the write failed, so the next refresh writes). */
    @Nullable
    private static volatile List<RoomRow> written;
    /** Whether the shared table has been read since this server started (false: other servers' rooms are not known yet). */
    private static volatile boolean read;
    private static int ticks, sinceRefresh;
    private static boolean dirty;
    private static LongSupplier clock = System::currentTimeMillis;

    private RoomList() {
    }

    // ------------------------------------------------------------------ lifecycle

    public static void registerNotices() {
        Storage.on(KIND, (server, n) -> reload(server));
    }

    public static void onServerStarted(MinecraftServer server) {
        table = RoomTable.EMPTY;
        mine = List.of();
        written = null;
        read = false;
        ticks = 0;
        sinceRefresh = 0;
        dirty = false;
        refresh(server);
    }

    public static void tick(MinecraftServer server) {
        sinceRefresh++;
        if (++ticks >= REFRESH_TICKS || (dirty && sinceRefresh >= CHANGED_TICKS)) {
            ticks = 0;
            sinceRefresh = 0;
            dirty = false;
            refresh(server);
        }
    }

    /** This server's rooms changed (a room made, given back, freed): they are read and shared at the next tick. */
    public static void changed() {
        dirty = true;
    }

    /**
     * Reads this server's rooms from Compact Machines, writes them when they changed (or only the time heard when not),
     * and reads every server's rows back. Nothing without Compact Machines 6.0.3.
     */
    public static void refresh(MinecraftServer server) {
        if (!CompactMachines.present()) {
            return;
        }
        String self = Storage.currentServerId();
        revive(server);
        List<RoomRow> rows = snapshot(server, self);
        mine = rows;
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        long now = clock.getAsLong();
        if (!rows.equals(written)) {
            written = rows; // compared with what was last sent, not last written: a change undone before its write is still written
            s.submit(() -> {
                try {
                    s.backend().saveRooms(self, now, rows);
                } catch (Exception e) {
                    written = null; // written again at the next refresh
                    throw e;
                }
                s.publish(KIND, self); // after the write, so the others read what was written
            });
        } else {
            s.submit(() -> s.backend().touchRooms(self, now));
        }
        reload(server);
    }

    /** Reads every server's rows from the table, handed to the server thread. */
    public static void reload(MinecraftServer server) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        s.submit(() -> {
            RoomTable t = s.backend().loadRooms();
            server.execute(() -> {
                table = t;
                read = true;
            });
        });
    }

    // ------------------------------------------------------------------ this server's rooms

    /**
     * Rooms in grace that have a placed machine bound to them again are taken out of grace (D446: placing a machine keeps
     * the room), and entries whose room is gone or kept for nobody are dropped.
     */
    static void revive(MinecraftServer server) {
        RoomGrace grace = RoomGrace.get(server);
        for (RoomGrace.Entry e : grace.all()) {
            java.util.Optional<CompactMachines.Room> r = CompactMachines.room(server, e.room());
            if (r.isEmpty() || r.get().freed()) {
                grace.remove(e.room());
            } else if (!CompactMachines.machinesFor(server, e.room()).isEmpty()) {
                grace.remove(e.room());
                com.mrgregles.bsp_server_utilities.BSPServerUtilities.LOGGER.info("Compact Machines room {} of {} has a placed machine again: it counts again and is not cleared", e.room(), e.owner());
            }
        }
    }

    /** This server's rooms as rows: every room with an owner, its placed machines found, its grace if any, in chunk order. */
    public static List<RoomRow> snapshot(MinecraftServer server, String self) {
        return snapshot(server, self, null);
    }

    /** This server's rooms of one owner (all owners when null), as {@link #snapshot(MinecraftServer, String)}. */
    public static List<RoomRow> snapshot(MinecraftServer server, String self, @Nullable UUID owner) {
        List<RoomRow> out = new ArrayList<>();
        RoomGrace grace = RoomGrace.get(server);
        for (CompactMachines.Room r : CompactMachines.rooms(server)) {
            if (r.freed() || (owner != null && !owner.equals(r.owner()))) {
                continue; // a slot kept for nobody is nobody's room
            }
            List<GlobalPos> placed = new ArrayList<>(CompactMachines.machinesFor(server, r.pos()));
            placed.sort(Comparator.comparing(RoomList::where));
            java.util.Optional<RoomGrace.Entry> g = grace.get(r.pos());
            out.add(new RoomRow(self, r.pos().x, r.pos().z, r.owner(), r.size(), clip(r.name()), placed.isEmpty() ? "" : clip(where(placed.get(0)), MACHINE_MAX),
                    placed.size(), g.isPresent() ? RoomRow.GRACE : RoomRow.ACTIVE, g.map(RoomGrace.Entry::until).orElse(0L), g.map(RoomGrace.Entry::reason).orElse(""),
                    g.map(RoomGrace.Entry::told).orElse(false), ownerName(server, r.owner())));
        }
        out.sort(Comparator.comparingInt(RoomRow::x).thenComparingInt(RoomRow::z));
        return out;
    }

    /** The owner's name as this server knows it (online, else the server's name cache), or empty. */
    static String ownerName(MinecraftServer server, UUID owner) {
        net.minecraft.server.level.ServerPlayer online = server.getPlayerList().getPlayer(owner);
        String name = online != null ? online.getGameProfile().getName()
                : server.getProfileCache() == null ? "" : server.getProfileCache().get(owner).map(com.mojang.authlib.GameProfile::getName).orElse("");
        return name == null ? "" : name.length() <= RoomRow.OWNER_NAME_MAX ? name : name.substring(0, RoomRow.OWNER_NAME_MAX);
    }

    /** A machine's place as the rows hold it: "dimension x y z". */
    public static String where(GlobalPos pos) {
        return pos.dimension().location() + " " + pos.pos().getX() + " " + pos.pos().getY() + " " + pos.pos().getZ();
    }

    static String clip(@Nullable String name) {
        return clip(name, RoomRow.NAME_MAX);
    }

    static String clip(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ counting

    /** How many rooms count against the player, by the current settings (D438): rooms in grace do not (D446). */
    public static int count(MinecraftServer server, UUID player) {
        int local = Math.max(0, CompactMachines.countOwnedBy(server, player) - RoomGrace.get(server).countFor(player));
        return count(local, player, RoomSettings.current().network(), table, Storage.currentServerId());
    }

    /**
     * {@code local} (this server's rooms, read live) plus, when {@code network}, the counting rows of every other server in
     * {@code t}; this server's own rows in the table are left out, since {@code local} is newer.
     */
    public static int count(int local, UUID player, boolean network, RoomTable t, String self) {
        if (!network) {
            return local;
        }
        int n = local;
        for (RoomRow r : t.rows()) {
            if (!r.server().equalsIgnoreCase(self) && r.owner().equals(player) && r.counts()) {
                n++;
            }
        }
        return n;
    }

    /** The servers, other than this one, not heard from for {@link #STALE_MS}, by name. */
    public static List<String> stale(RoomTable t, String self, long now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : t.heard().entrySet()) {
            if (!e.getKey().equalsIgnoreCase(self) && now - e.getValue() >= STALE_MS) {
                out.add(e.getKey());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Every server's rows as last read. */
    public static RoomTable table() {
        return table;
    }

    /** Whether the shared table has been read since this server started (with shared storage). */
    public static boolean read() {
        return read;
    }

    /** This server's rows as last read from Compact Machines. */
    public static List<RoomRow> mine() {
        return mine;
    }

    // ------------------------------------------------------------------ tests

    /** A test clock (null: the real one) and a clean slate. */
    public static void resetForTests(@Nullable LongSupplier testClock) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        table = RoomTable.EMPTY;
        mine = List.of();
        written = null;
        read = false;
        ticks = 0;
        sinceRefresh = 0;
        dirty = false;
    }

    /** Whether a refresh waits for the next tick (tests). */
    public static boolean dirty() {
        return dirty;
    }
}
