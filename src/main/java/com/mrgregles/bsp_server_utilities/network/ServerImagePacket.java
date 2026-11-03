package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.serverimages.ServerImages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: one server image, by its id (the hash of its bytes) and its PNG bytes (at most 256 KB), sent once a
 * login for every image in use, and to an admin for the SERVERS tab's previews of the folder's files.
 */
public record ServerImagePacket(String id, byte[] png) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id, 40);
        buf.writeByteArray(png);
    }

    public static ServerImagePacket decode(FriendlyByteBuf buf) {
        return new ServerImagePacket(buf.readUtf(40), buf.readByteArray(ServerImages.MAX_BYTES));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ServerImageFont.receive(id, png));
        ctx.get().setPacketHandled(true);
    }
}
