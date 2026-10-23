package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the setup card and guide: a refresh; open the guide; NEXT, SETUP LATER, TURN OFF or TURN ON a
 * page; the guide closed on a page; this server's name for the config file; the card's SETUP LATER or IGNORE.
 */
public record SetupEditPacket(Action action, Feature feature, String arg) {
    public enum Action { REFRESH, OPEN, NEXT, LATER, OFF, ON, CLOSE, NAME, CARD_LATER, IGNORE }

    public static SetupEditPacket of(Action action) {
        return new SetupEditPacket(action, Feature.WELCOME, "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeEnum(feature);
        buf.writeUtf(arg, 64);
    }

    public static SetupEditPacket decode(FriendlyByteBuf buf) {
        return new SetupEditPacket(buf.readEnum(Action.class), buf.readEnum(Feature.class), buf.readUtf(64));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            SetupAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
