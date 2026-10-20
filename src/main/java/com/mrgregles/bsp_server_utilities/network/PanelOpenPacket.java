package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.image.ImageService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: open a panel's settings screen: its bottom-left block, its size, its settings, this server's name
 * and the files in its images folder.
 */
public record PanelOpenPacket(BlockPos origin, int width, int height, CompoundTag settings, String server, List<ImageService.FolderFile> files) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(origin);
        buf.writeVarInt(width);
        buf.writeVarInt(height);
        buf.writeNbt(settings);
        buf.writeUtf(server, 64);
        buf.writeVarInt(files.size());
        for (ImageService.FolderFile f : files) {
            buf.writeUtf(f.name(), 128);
            buf.writeVarLong(f.bytes());
            buf.writeVarInt(f.width());
            buf.writeVarInt(f.height());
        }
    }

    public static PanelOpenPacket decode(FriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        int w = buf.readVarInt(), h = buf.readVarInt();
        CompoundTag tag = buf.readNbt();
        String server = buf.readUtf(64);
        int n = Math.min(buf.readVarInt(), 200);
        List<ImageService.FolderFile> files = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            files.add(new ImageService.FolderFile(buf.readUtf(128), buf.readVarLong(), buf.readVarInt(), buf.readVarInt()));
        }
        return new PanelOpenPacket(origin, w, h, tag == null ? new CompoundTag() : tag, server, files);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.PanelScreen.open(this));
        ctx.get().setPacketHandled(true);
    }
}
