package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.rooms.RoomsAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client to server from the ROOMS tab (SU-59): OPEN (the shared table read again, then the tab's data), REFRESH (the
 * data), or FREE the room at {@code x, z} on this server, which must still be {@code owner}'s (after SURE? on the tab).
 * Every answer shows {@code owner}'s rooms ({@link AdminRoomsPacket#NONE}: nobody picked).
 */
public record AdminRoomsEditPacket(Action action, UUID owner, int x, int z) {
    public enum Action { OPEN, REFRESH, FREE }

    public static AdminRoomsEditPacket of(Action action, UUID owner) {
        return new AdminRoomsEditPacket(action, owner, 0, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUUID(owner);
        buf.writeVarInt(x);
        buf.writeVarInt(z);
    }

    public static AdminRoomsEditPacket decode(FriendlyByteBuf buf) {
        return new AdminRoomsEditPacket(buf.readEnum(Action.class), buf.readUUID(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            RoomsAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
