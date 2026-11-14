package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: the ROOMS tab's data (SU-59; owner decisions D443, D448, D452 look A, D453 R4 to R7): the settings,
 * this server's name, every owner (most over the limit first, then most rooms), how many rooms are in grace, the servers
 * not heard from for five minutes with how long ago, and the picked owner's rooms (with {@code give} as on the player's
 * page: whether FREE can be used here and if not why), plus a note on the last action ({@code freed}, {@code placed},
 * {@code grace}, {@code gone} or empty). {@code unread}: with network scope, the shared list has not been read since this
 * server started (its database was down), so other servers' rooms are not counted yet.
 */
public record AdminRoomsPacket(int limit, boolean network, boolean unread, String server, int graceDays, int inGrace, List<Stale> stale, List<Owner> owners,
                               UUID picked, String pickedName, List<RoomsViewPacket.Room> rooms, String note) {
    public static final int MAX_OWNERS = 1000, MAX_STALE = 32;
    /** No owner picked. */
    public static final UUID NONE = new UUID(0L, 0L);

    /**
     * An owner: their name (empty when no server knows it), the rooms that count against them, every room of theirs listed
     * (in grace too), on how many servers, and whether they are exempt (an operator, or online in the exempt group).
     */
    public record Owner(UUID id, String name, int count, int rooms, int servers, boolean exempt) {
        /** How many over the limit (0 when there is none or the owner is exempt). */
        public int over(int limit) {
            return limit > 0 && !exempt ? Math.max(0, count - limit) : 0;
        }
    }

    /** A server not heard from, and for how long (ms). */
    public record Stale(String server, long ageMs) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(limit);
        buf.writeBoolean(network);
        buf.writeBoolean(unread);
        buf.writeUtf(RoomsViewPacket.cut(server, 64), 64);
        buf.writeVarInt(graceDays);
        buf.writeVarInt(inGrace);
        List<Stale> st = stale.size() > MAX_STALE ? stale.subList(0, MAX_STALE) : stale;
        buf.writeVarInt(st.size());
        for (Stale s : st) {
            buf.writeUtf(RoomsViewPacket.cut(s.server(), 64), 64);
            buf.writeVarLong(Math.max(0, s.ageMs()));
        }
        List<Owner> os = owners.size() > MAX_OWNERS ? owners.subList(0, MAX_OWNERS) : owners;
        buf.writeVarInt(os.size());
        for (Owner o : os) {
            buf.writeUUID(o.id());
            buf.writeUtf(RoomsViewPacket.cut(o.name(), 16), 16);
            buf.writeVarInt(o.count());
            buf.writeVarInt(o.rooms());
            buf.writeVarInt(o.servers());
            buf.writeBoolean(o.exempt());
        }
        buf.writeUUID(picked);
        buf.writeUtf(RoomsViewPacket.cut(pickedName, 16), 16);
        List<RoomsViewPacket.Room> rs = rooms.size() > RoomsViewPacket.MAX_ROOMS ? rooms.subList(0, RoomsViewPacket.MAX_ROOMS) : rooms;
        buf.writeVarInt(rs.size());
        rs.forEach(r -> RoomsViewPacket.writeRoom(buf, r));
        buf.writeUtf(RoomsViewPacket.cut(note, 16), 16);
    }

    public static AdminRoomsPacket decode(FriendlyByteBuf buf) {
        int limit = buf.readVarInt();
        boolean network = buf.readBoolean(), unread = buf.readBoolean();
        String server = buf.readUtf(64);
        int graceDays = buf.readVarInt(), inGrace = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), MAX_STALE);
        List<Stale> stale = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            stale.add(new Stale(buf.readUtf(64), buf.readVarLong()));
        }
        n = Math.min(buf.readVarInt(), MAX_OWNERS);
        List<Owner> owners = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            owners.add(new Owner(buf.readUUID(), buf.readUtf(16), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        UUID picked = buf.readUUID();
        String pickedName = buf.readUtf(16);
        n = Math.min(buf.readVarInt(), RoomsViewPacket.MAX_ROOMS);
        List<RoomsViewPacket.Room> rooms = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rooms.add(RoomsViewPacket.readRoom(buf));
        }
        return new AdminRoomsPacket(limit, network, unread, server, graceDays, inGrace, List.copyOf(stale), List.copyOf(owners), picked, pickedName, List.copyOf(rooms), buf.readUtf(16));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.rooms(this));
        ctx.get().setPacketHandled(true);
    }
}
