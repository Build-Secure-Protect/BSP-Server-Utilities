package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /su rooms} (SU-58; owner decisions D443, D448, D452 Z3, Z4, Z7, D453 R1 to R3): the page of a player's own rooms on
 * every server, and giving one back. A room can be given back only on its own server, when no machine bound to it is
 * placed, nobody is inside and it is not in grace already; it then goes into grace like a lost one, its owner is told
 * and the action log says so.
 */
public final class RoomsPage {
    /** What a give-back came to. */
    public enum GiveBack { GIVEN, NOT_YOURS, ELSEWHERE, PLACED, INSIDE, GRACE }

    /** The tick of each player's last give-back (at most one every {@link #ACT_TICKS}: a modified client cannot flood it). */
    private static final java.util.Map<ServerPlayer, Integer> LAST_ACT = new java.util.WeakHashMap<>();
    static final int ACT_TICKS = 10;

    private RoomsPage() {
    }

    /** Whether the player may give back a room now (server thread). */
    public static boolean mayAct(ServerPlayer player) {
        Integer last = LAST_ACT.get(player);
        if (last != null && player.tickCount - last < ACT_TICKS && player.tickCount >= last) {
            return false;
        }
        LAST_ACT.put(player, player.tickCount);
        return true;
    }

    /** Sends the page to the player (opens it, or refreshes it when open). */
    public static void open(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, view(player));
    }

    public static RoomsViewPacket view(ServerPlayer player) {
        MinecraftServer server = player.server;
        String self = Storage.currentServerId();
        RoomSettings s = RoomSettings.current();
        UUID me = player.getUUID();
        long now = RoomLosses.now();
        List<RoomsViewPacket.Room> rooms = new ArrayList<>();
        for (RoomRow r : RoomList.snapshot(server, self, me)) {
            rooms.add(room(r, now, giveCode(server, r)));
        }
        if (s.network()) {
            for (RoomRow r : RoomList.table().rows()) {
                if (!r.server().equalsIgnoreCase(self) && r.owner().equals(me)) {
                    rooms.add(room(r, now, RoomRow.GRACE.equals(r.state()) ? RoomsViewPacket.GIVE_GRACE : RoomsViewPacket.GIVE_ELSEWHERE));
                }
            }
        }
        rooms.sort(order(self));
        boolean exempt = RoomLimit.exempt(player.hasPermissions(2), LuckPermsBridge.groupsOf(me), s.exemptGroup());
        return new RoomsViewPacket(s.limit(), RoomList.count(server, me), s.network(), exempt, self, s.graceDays(), List.copyOf(rooms));
    }

    private static RoomsViewPacket.Room room(RoomRow r, long now, int give) {
        boolean grace = RoomRow.GRACE.equals(r.state());
        return new RoomsViewPacket.Room(r.server(), r.x(), r.z(), r.size(), r.name(), dimensionOnly(r.machine()), r.machines(), grace, grace ? r.graceUntil() - now : 0, r.reason(), give);
    }

    /**
     * D457: players see only the dimension a machine is placed in, never its coordinates (a machine sold or given away
     * stands in someone else's base); staff see the place on the ROOMS tab. "minecraft:overworld 1 2 3" → "minecraft:overworld".
     */
    public static String dimensionOnly(String machine) {
        int space = machine.indexOf(' ');
        return space < 0 ? machine : machine.substring(0, space);
    }

    /** R1: this server's rooms in use, then other servers', then rooms in grace; biggest first in each, then by name. */
    public static Comparator<RoomsViewPacket.Room> order(String self) {
        return Comparator.<RoomsViewPacket.Room>comparingInt(r -> r.grace() ? 2 : r.here(self) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(RoomsViewPacket.Room::size).reversed())
                .thenComparing(RoomsViewPacket.Room::name)
                .thenComparing(RoomsViewPacket.Room::server);
    }

    /** Whether this server's room can be given back now, and if not why (R3: a placed machine says pick it up first). */
    static int giveCode(MinecraftServer server, RoomRow r) {
        if (RoomRow.GRACE.equals(r.state())) {
            return RoomsViewPacket.GIVE_GRACE;
        }
        if (r.machines() > 0) {
            return RoomsViewPacket.GIVE_PLACED;
        }
        return occupied(server, new ChunkPos(r.x(), r.z())) ? RoomsViewPacket.GIVE_INSIDE : RoomsViewPacket.GIVE_OK;
    }

    /** Whether any player is inside the room. */
    static boolean occupied(MinecraftServer server, ChunkPos room) {
        ServerLevel rooms = server.getLevel(CompactMachines.ROOMS_LEVEL);
        if (rooms == null) {
            return false;
        }
        return occupied(rooms.players(), room);
    }

    /** Whether any of {@code players} (in the room dimension) stands in the room's chunk. */
    public static boolean occupied(Iterable<? extends net.minecraft.world.entity.player.Player> players, ChunkPos room) {
        for (net.minecraft.world.entity.player.Player p : players) {
            if (new ChunkPos(p.blockPosition()).equals(room)) {
                return true;
            }
        }
        return false;
    }

    /** Gives back one of the player's rooms on this server (D448), checking everything again. */
    public static GiveBack giveBack(ServerPlayer player, ChunkPos room) {
        MinecraftServer server = player.server;
        Optional<CompactMachines.Room> read = CompactMachines.room(server, room);
        if (read.isEmpty() || !player.getUUID().equals(read.get().owner())) {
            return GiveBack.NOT_YOURS;
        }
        if (RoomGrace.get(server).contains(room)) {
            return GiveBack.GRACE;
        }
        if (!CompactMachines.machinesFor(server, room).isEmpty()) {
            return GiveBack.PLACED;
        }
        if (occupied(server, room)) {
            return GiveBack.INSIDE;
        }
        if (!RoomLosses.lose(server, room, "given_back")) {
            return GiveBack.PLACED;
        }
        ActionLog.record(server, ActionLog.entryForPlayer(player, LogEntry.ROOM,
                "gave back their Compact Machine room at " + room.x + ", " + room.z + " (" + read.get().size() + " blocks inside); cleared in " + RoomSettings.current().graceDays() + " days"));
        return GiveBack.GIVEN;
    }
}
