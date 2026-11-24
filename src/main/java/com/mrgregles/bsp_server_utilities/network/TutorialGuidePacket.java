package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the golden path to the next point and the sign over it (SU-141; owner decision D732), drawn only in
 * the given world; or nothing to draw ({@code tutorial} empty).
 */
public record TutorialGuidePacket(String tutorial, int point, String dimension, List<BlockPos> path, BlockPos target, int radius, String title) {
    public static final TutorialGuidePacket CLEAR = new TutorialGuidePacket("", -1, "", List.of(), BlockPos.ZERO, 0, "");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(tutorial, 64);
        buf.writeVarInt(point);
        buf.writeUtf(dimension, 128);
        buf.writeCollection(path, FriendlyByteBuf::writeBlockPos);
        buf.writeBlockPos(target);
        buf.writeVarInt(radius);
        buf.writeUtf(title, 128);
    }

    public static TutorialGuidePacket decode(FriendlyByteBuf buf) {
        String t = buf.readUtf(64);
        int point = buf.readVarInt();
        String dim = buf.readUtf(128);
        List<BlockPos> path = buf.readCollection(ArrayList::new, FriendlyByteBuf::readBlockPos);
        if (path.size() > 600) {
            path = path.subList(0, 600);
        }
        return new TutorialGuidePacket(t, point, dim, path, buf.readBlockPos(), buf.readVarInt(), buf.readUtf(128));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTutorial.guide(this));
        ctx.get().setPacketHandled(true);
    }
}
