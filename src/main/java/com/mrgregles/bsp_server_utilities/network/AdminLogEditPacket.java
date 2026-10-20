package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the LOG tab: the filters it shows (a server or "" for all, a kind or "" for all), and, when
 * {@code root} is not empty, a command's switch turned to {@code on}.
 */
public record AdminLogEditPacket(String server, String kind, String root, boolean on) {
    public static AdminLogEditPacket refresh(String server, String kind) {
        return new AdminLogEditPacket(server, kind, "", false);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(server, 64);
        buf.writeUtf(kind, 16);
        buf.writeUtf(root, 64);
        buf.writeBoolean(on);
    }

    public static AdminLogEditPacket decode(FriendlyByteBuf buf) {
        return new AdminLogEditPacket(buf.readUtf(64), buf.readUtf(16), buf.readUtf(64), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            AdminPanel.applyLog(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
