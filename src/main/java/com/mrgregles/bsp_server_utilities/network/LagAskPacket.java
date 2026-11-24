package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.lag.LagNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the LAG tab (SU-149): asks for every server's world summaries ({@code server} empty), one world's
 * map, one chunk's detail, or one server's profiler sample (PROFILE, SU-151: the one running and the latest finished), on
 * this server or another; only players who may edit get an answer ({@link LagAnswerPacket}).
 */
public record LagAskPacket(Kind kind, String server, String dimension, int x, int z) {
    public enum Kind { SUMMARY, MAP, DETAIL, PROFILE }

    public static LagAskPacket summaries() {
        return new LagAskPacket(Kind.SUMMARY, "", "", 0, 0);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        buf.writeUtf(server, 64);
        buf.writeUtf(dimension, 256);
        buf.writeVarInt(x);
        buf.writeVarInt(z);
    }

    public static LagAskPacket decode(FriendlyByteBuf buf) {
        return new LagAskPacket(buf.readEnum(Kind.class), buf.readUtf(64), buf.readUtf(256), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            LagNetwork.ask(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
