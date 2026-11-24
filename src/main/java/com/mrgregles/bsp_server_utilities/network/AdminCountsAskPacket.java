package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.admin.AdminCounts;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Client to server from the staff screen: asks for {@link AdminCountsPacket}; only players who may edit get an answer. */
public record AdminCountsAskPacket() {
    public void encode(FriendlyByteBuf buf) {
    }

    public static AdminCountsAskPacket decode(FriendlyByteBuf buf) {
        return new AdminCountsAskPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null && Perms.canEdit(player)) {
            ServerUtilsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), AdminCounts.of(player.server));
        }
        ctx.get().setPacketHandled(true);
    }
}
