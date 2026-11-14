package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the player's Compact Machines rooms for {@code /su rooms} (SU-58; owner decisions D443, D452, D453):
 * the limit (0: none), how many count, whether across the network, whether the player is exempt, this server's name,
 * the grace days, and each room in the order the page shows them (this server's, other servers', lost; biggest first).
 */
public record RoomsViewPacket(int limit, int count, boolean network, boolean exempt, String server, int graceDays, List<Room> rooms) {
    public static final int MAX_ROOMS = 300;

    /** Why a room can or cannot be given back here. */
    public static final int GIVE_OK = 0, GIVE_PLACED = 1, GIVE_ELSEWHERE = 2, GIVE_INSIDE = 3, GIVE_GRACE = 4;

    /**
     * A room: its server and chunk, inside size, name (or empty), where a placed machine stands ("dimension x y z" or
     * empty) and how many, whether it is in grace with the time left (ms) and why, and {@code give} (one of GIVE_*).
     */
    public record Room(String server, int x, int z, int size, String name, String machine, int machines, boolean grace, long graceLeftMs, String reason, int give) {
        public boolean here(String self) {
            return server.equals(self);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(limit);
        buf.writeVarInt(count);
        buf.writeBoolean(network);
        buf.writeBoolean(exempt);
        buf.writeUtf(cut(server, 64), 64);
        buf.writeVarInt(graceDays);
        List<Room> sent = rooms.size() > MAX_ROOMS ? rooms.subList(0, MAX_ROOMS) : rooms;
        buf.writeVarInt(sent.size());
        for (Room r : sent) {
            writeRoom(buf, r);
        }
    }

    /** One room on the wire (also the admins' ROOMS tab, SU-59). */
    public static void writeRoom(FriendlyByteBuf buf, Room r) {
        buf.writeUtf(cut(r.server(), 64), 64);
        buf.writeVarInt(r.x());
        buf.writeVarInt(r.z());
        buf.writeVarInt(r.size());
        buf.writeUtf(cut(r.name(), 64), 64);
        buf.writeUtf(cut(r.machine(), 160), 160);
        buf.writeVarInt(r.machines());
        buf.writeBoolean(r.grace());
        buf.writeVarLong(Math.max(0, r.graceLeftMs()));
        buf.writeUtf(cut(r.reason(), 32), 32);
        buf.writeVarInt(r.give());
    }

    /** {@code s} cut to {@code max} characters, so a long server id or name never stops the packet being written. */
    public static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static Room readRoom(FriendlyByteBuf buf) {
        return new Room(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readUtf(160), buf.readVarInt(),
                buf.readBoolean(), buf.readVarLong(), buf.readUtf(32), buf.readVarInt());
    }

    public static RoomsViewPacket decode(FriendlyByteBuf buf) {
        int limit = buf.readVarInt(), count = buf.readVarInt();
        boolean network = buf.readBoolean(), exempt = buf.readBoolean();
        String server = buf.readUtf(64);
        int graceDays = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), MAX_ROOMS);
        List<Room> rooms = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rooms.add(readRoom(buf));
        }
        return new RoomsViewPacket(limit, count, network, exempt, server, graceDays, List.copyOf(rooms));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientRooms.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
