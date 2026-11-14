package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.rooms.RoomsPage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: give back one of this server's rooms (D448, after SURE? on the page); the server checks everything again. */
public record RoomGiveBackPacket(int x, int z) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(x);
        buf.writeVarInt(z);
    }

    public static RoomGiveBackPacket decode(FriendlyByteBuf buf) {
        return new RoomGiveBackPacket(buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null && com.mrgregles.bsp_server_utilities.compat.CompactMachines.present() && RoomsPage.mayAct(player)) {
            RoomsPage.giveBack(player, new ChunkPos(x, z));
            RoomsPage.open(player);
        }
        ctx.get().setPacketHandled(true);
    }
}
