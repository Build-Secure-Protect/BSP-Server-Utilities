package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.tutorial.TutorialAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the tutorial builder (SU-142 to SU-144): open it (a tutorial to show), close it, make a
 * tutorial, publish, delete, set a point's place to where the admin stands, go to a point, draw a link's path, set the
 * start area, or take the published tutorial. The server checks the admin and every value.
 */
public record TutorialEditPacket(Action action, String tutorial, int a, int b, String text) {
    public enum Action { OPEN, CLOSE, CREATE, PUBLISH, DELETE, PLACE_HERE, GO_THERE, DRAW_PATH, SET_AREA, TEST }

    public static TutorialEditPacket of(Action action, String tutorial) {
        return new TutorialEditPacket(action, tutorial, -1, -1, "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(tutorial, 64);
        buf.writeVarInt(a);
        buf.writeVarInt(b);
        buf.writeUtf(text, 256);
    }

    public static TutorialEditPacket decode(FriendlyByteBuf buf) {
        return new TutorialEditPacket(buf.readEnum(Action.class), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            TutorialAdmin.edit(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
