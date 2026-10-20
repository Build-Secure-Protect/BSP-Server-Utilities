package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.panel.PanelEditing;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: REFRESH NOW on a panel's Image pane. */
public record PanelRefreshPacket(BlockPos pos) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
    }

    public static PanelRefreshPacket decode(FriendlyByteBuf buf) {
        return new PanelRefreshPacket(buf.readBlockPos());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            PanelEditing.refresh(player, pos);
        }
        ctx.get().setPacketHandled(true);
    }
}
