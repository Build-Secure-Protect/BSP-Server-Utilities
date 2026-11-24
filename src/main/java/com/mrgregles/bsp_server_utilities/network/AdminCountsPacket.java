package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client, for the staff screen's title bar and tab list (SU-154, D754): this server's name and how many are on
 * it, the client check's refusals today, and the chunks over a limit on the network (mobs over their number, or dropped
 * items or arrows over theirs). Sent when asked, on opening and every 10 seconds while the screen is open.
 */
public record AdminCountsPacket(String here, int online, int refused, int crowded) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(here, 64);
        buf.writeVarInt(online);
        buf.writeVarInt(refused);
        buf.writeVarInt(crowded);
    }

    public static AdminCountsPacket decode(FriendlyByteBuf buf) {
        return new AdminCountsPacket(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.counts(this));
        ctx.get().setPacketHandled(true);
    }
}
