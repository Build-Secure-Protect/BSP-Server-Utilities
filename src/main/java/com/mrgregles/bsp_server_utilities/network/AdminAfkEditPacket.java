package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.afk.AfkSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server from the AFK tab: the settings as they now stand ({@code refresh} only asks for the data). */
public record AdminAfkEditPacket(boolean refresh, AfkSettings settings) {
    public static AdminAfkEditPacket ask() {
        return new AdminAfkEditPacket(true, AfkSettings.DEFAULTS);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(refresh);
        buf.writeUtf(settings.encode(), 1024);
    }

    public static AdminAfkEditPacket decode(FriendlyByteBuf buf) {
        boolean refresh = buf.readBoolean();
        AfkSettings s = AfkSettings.decode(buf.readUtf(1024));
        return new AdminAfkEditPacket(refresh || s == null, s == null ? AfkSettings.DEFAULTS : s);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            AdminPanel.applyAfk(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
