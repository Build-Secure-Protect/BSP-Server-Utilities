package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.image.ImageService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** Server to client: one piece of a picture's PNG bytes; {@code index} from 0 to {@code total - 1}. */
public record ImageChunkPacket(String hash, int index, int total, byte[] data) {
    /** A picture cut into pieces of {@link ImageService#CHUNK} bytes. */
    public static List<ImageChunkPacket> split(String hash, byte[] png) {
        int total = Math.max(1, (png.length + ImageService.CHUNK - 1) / ImageService.CHUNK);
        List<ImageChunkPacket> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            out.add(new ImageChunkPacket(hash, i, total, Arrays.copyOfRange(png, i * ImageService.CHUNK, Math.min(png.length, (i + 1) * ImageService.CHUNK))));
        }
        return out;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(hash, 64);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static ImageChunkPacket decode(FriendlyByteBuf buf) {
        return new ImageChunkPacket(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(ImageService.CHUNK));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientImages.piece(this));
        ctx.get().setPacketHandled(true);
    }
}
