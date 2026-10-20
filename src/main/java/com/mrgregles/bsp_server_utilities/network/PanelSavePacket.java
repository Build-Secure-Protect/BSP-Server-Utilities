package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.panel.PanelEditing;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: SAVE on a panel's settings screen; the server checks it ({@link PanelEditing#save}). */
public record PanelSavePacket(BlockPos pos, CompoundTag settings) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeNbt(settings);
    }

    public static PanelSavePacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        CompoundTag tag = buf.readNbt();
        return new PanelSavePacket(pos, tag == null ? new CompoundTag() : tag);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            PanelEditing.save(player, pos, settings);
        }
        ctx.get().setPacketHandled(true);
    }
}
