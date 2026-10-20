package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.image.ImageService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: send me the picture with this hash (a panel the player can see shows it). */
public record ImageRequestPacket(String hash) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(hash, 64);
    }

    public static ImageRequestPacket decode(FriendlyByteBuf buf) {
        return new ImageRequestPacket(buf.readUtf(64));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null && hash.matches("[0-9a-f]{32}")) {
            ImageService.request(player, hash);
        }
        ctx.get().setPacketHandled(true);
    }
}
