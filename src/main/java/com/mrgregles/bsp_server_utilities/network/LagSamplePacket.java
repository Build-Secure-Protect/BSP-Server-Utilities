package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.lag.LagProfiler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the LAG tab's PROFILER (SU-151; D749, D761): start a sample of {@code seconds} (10, 30 or 60) on
 * a server, or STOP the one running there. On this server ({@code server} empty or this one) it starts or stops here; on
 * another it is passed on with a notice naming that server. Only players who may edit are heard; the answer is that
 * server's PROFILE ({@link LagAnswerPacket}).
 */
public record LagSamplePacket(String server, Action action, int seconds) {
    public enum Action { START, STOP }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(server, 64);
        buf.writeEnum(action);
        buf.writeVarInt(seconds);
    }

    public static LagSamplePacket decode(FriendlyByteBuf buf) {
        return new LagSamplePacket(buf.readUtf(64), buf.readEnum(Action.class), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            LagProfiler.request(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
