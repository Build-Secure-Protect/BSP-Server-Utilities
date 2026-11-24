package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.tutorial.TutorialRunner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server: the player's answer to an offer (YES, LATER, NO), a button pressed on a point's popup (its index),
 * a popup that kept them walking having faded (WALKED), or a popup hidden with Escape (HIDDEN: not shown again until
 * {@code /tutorial resume}). The server checks it against what it showed.
 */
public record TutorialAnswerPacket(Action action, String tutorial, int point, int index) {
    public enum Action { YES, LATER, NO, BUTTON, WALKED, HIDDEN }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(tutorial, 64);
        buf.writeVarInt(point);
        buf.writeVarInt(index);
    }

    public static TutorialAnswerPacket decode(FriendlyByteBuf buf) {
        return new TutorialAnswerPacket(buf.readEnum(Action.class), buf.readUtf(64), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            TutorialRunner.answer(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
