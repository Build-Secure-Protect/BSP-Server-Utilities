package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.afk.AfkService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: the key a player clicked on the presence check's card; the server checks it against its own copy (D222). */
public record AfkAnswerPacket(int id, int round, int word) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeVarInt(round);
        buf.writeVarInt(word);
    }

    public static AfkAnswerPacket decode(FriendlyByteBuf buf) {
        return new AfkAnswerPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            AfkService.answer(player, id, round, word);
        }
        ctx.get().setPacketHandled(true);
    }
}
