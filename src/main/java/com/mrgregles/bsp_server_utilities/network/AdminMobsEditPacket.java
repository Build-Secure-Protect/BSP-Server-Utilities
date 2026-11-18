package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.mobs.MobsAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the MOBS tab (SU-65), one change at a time so admins on two servers never undo each other's
 * (audit): OPEN and REFRESH (the network asked, then the tab's data); EVERY ({@code count}); DIM ({@code dim} set to
 * {@code count}, or taken away when {@link #REMOVE_ROW}); CHUNK (the same for one chunk of {@code server}); NEVER
 * ({@code dim} holds the mob type: added when {@code count} is 1, taken off when 0); HERE (the chunk the admin stands
 * in, D481); LIMIT (a listed chunk on {@code server}, with its {@code count}); REMOVE (a listed chunk's extras, this
 * server only, after SURE?, D475); on the DROPS &amp; ARROWS page ITEMS, ARROWS and MINUTES ({@code count}) and CLEAR (a
 * listed chunk cleared down to its numbers now, this server only, after SURE?, D499).
 */
public record AdminMobsEditPacket(Action action, String server, String dim, int x, int z, int count) {
    public enum Action { OPEN, REFRESH, EVERY, DIM, CHUNK, NEVER, HERE, LIMIT, REMOVE, ITEMS, ARROWS, MINUTES, CLEAR }

    public static final int REMOVE_ROW = -1;

    public static AdminMobsEditPacket of(Action action) {
        return new AdminMobsEditPacket(action, "", "", 0, 0, 0);
    }

    public static AdminMobsEditPacket chunk(Action action, String server, String dim, int x, int z, int count) {
        return new AdminMobsEditPacket(action, server, dim, x, z, count);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(RoomsViewPacket.cut(server, 64), 64);
        buf.writeUtf(RoomsViewPacket.cut(dim, 128), 128);
        buf.writeVarInt(x);
        buf.writeVarInt(z);
        buf.writeVarInt(count);
    }

    public static AdminMobsEditPacket decode(FriendlyByteBuf buf) {
        return new AdminMobsEditPacket(buf.readEnum(Action.class), buf.readUtf(64), buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            MobsAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
