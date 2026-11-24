package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.lag.LagNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the LAG tab (SU-150, SU-152): GO THERE on a chunk, or TP beside a block (D750, D752). On this
 * server ({@code server} empty or this one) the spot is worked out again here: the chunk's safe spot, or with
 * {@code beside} the nearest place to stand by the block at {@code x y z}. On another, {@code x y z} is the safe spot that
 * server gave in the chunk's detail, the move goes through BSP-Teleport, and {@code beside} is not used. Only players who
 * may edit are moved.
 */
public record LagGoPacket(String server, String dimension, int chunkX, int chunkZ, int x, int y, int z, boolean beside) {
    /** GO THERE on a chunk. */
    public LagGoPacket(String server, String dimension, int chunkX, int chunkZ, int x, int y, int z) {
        this(server, dimension, chunkX, chunkZ, x, y, z, false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(server, 64);
        buf.writeUtf(dimension, 256);
        buf.writeVarInt(chunkX);
        buf.writeVarInt(chunkZ);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
        buf.writeBoolean(beside);
    }

    public static LagGoPacket decode(FriendlyByteBuf buf) {
        return new LagGoPacket(buf.readUtf(64), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            LagNetwork.go(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
