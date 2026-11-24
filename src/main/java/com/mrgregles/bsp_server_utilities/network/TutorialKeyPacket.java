package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.tutorial.PathRecorder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server in path or area mode (SU-144; owner decisions D731, D737, D742): G (start, pause, resume; set a
 * corner), Z (undo the last point or corner), Delete (cancel), and the cards' FINISH PATH, KEEP WALKING, SAVE AREA and RESET.
 */
public record TutorialKeyPacket(Action action) {
    public enum Action { GO, UNDO, CANCEL, FINISH, KEEP, SAVE_AREA, RESET_AREA }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
    }

    public static TutorialKeyPacket decode(FriendlyByteBuf buf) {
        return new TutorialKeyPacket(buf.readEnum(Action.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            PathRecorder.key(player, action);
        }
        ctx.get().setPacketHandled(true);
    }
}
