package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client for the LAG tab (SU-149): one server's answer, in {@link com.mrgregles.bsp_server_utilities.lag.LagSnapshot}'s
 * text form for its kind (summaries, a world's map rows, or a chunk's detail).
 */
public record LagAnswerPacket(LagAskPacket.Kind kind, String server, String payload) {
    /** The longest payload sent (a world of some 60,000 loaded chunks); a longer one is cut by whole rows by the sender. */
    public static final int MAX_PAYLOAD = 1_000_000;

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        buf.writeUtf(server, 64);
        buf.writeUtf(payload, MAX_PAYLOAD);
    }

    public static LagAnswerPacket decode(FriendlyByteBuf buf) {
        return new LagAnswerPacket(buf.readEnum(LagAskPacket.Kind.class), buf.readUtf(64), buf.readUtf(MAX_PAYLOAD));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientLag.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
