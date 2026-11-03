package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.serverimages.ServersAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server from the SERVERS tab: VIEW (the tab's data, with the folder read again) or REFRESH, the second click (SURE?) confirmed. */
public record AdminServersEditPacket(Action action, boolean confirmed) {
    public enum Action { VIEW, REFRESH }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeBoolean(confirmed);
    }

    public static AdminServersEditPacket decode(FriendlyByteBuf buf) {
        return new AdminServersEditPacket(buf.readEnum(Action.class), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            ServersAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
