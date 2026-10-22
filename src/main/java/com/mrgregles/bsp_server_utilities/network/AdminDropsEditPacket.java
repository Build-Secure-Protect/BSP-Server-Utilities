package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.despawn.DespawnSettings;
import com.mrgregles.bsp_server_utilities.despawn.DropsAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server from the DROPS tab: a refresh, the settings as they now stand, or a clear of {@code arg} (here, all or a server's name). */
public record AdminDropsEditPacket(Action action, String arg, DespawnSettings settings) {
    public enum Action { REFRESH, SETTINGS, CLEAR }

    public static AdminDropsEditPacket of(Action action, String arg) {
        return new AdminDropsEditPacket(action, arg, DespawnSettings.DEFAULTS);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(arg, 64);
        buf.writeUtf(settings.encode(), 32767);
    }

    public static AdminDropsEditPacket decode(FriendlyByteBuf buf) {
        Action a = buf.readEnum(Action.class);
        String arg = buf.readUtf(64);
        DespawnSettings s = DespawnSettings.decode(buf.readUtf(32767));
        return new AdminDropsEditPacket(s == null && a == Action.SETTINGS ? Action.REFRESH : a, arg, s == null ? DespawnSettings.DEFAULTS : s);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            DropsAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
