package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.chunks.ChunkAdmin;
import com.mrgregles.bsp_server_utilities.chunks.ChunkSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server from the CHUNKS tab: an action with its argument (a player's UUID, a machine's key or a player's
 * name), or the settings as they now stand.
 */
public record AdminChunksEditPacket(Action action, String arg, ChunkSettings settings) {
    public enum Action { REFRESH, SETTINGS, RESTORE_PLAYER, UNLOAD_PLAYER, UNLOAD_MACHINE, RESTORE_MACHINE, ADD_ALWAYS }

    public static AdminChunksEditPacket of(Action action, String arg) {
        return new AdminChunksEditPacket(action, arg, ChunkSettings.DEFAULTS);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(arg, 256);
        buf.writeUtf(settings.encode(), 8192);
    }

    public static AdminChunksEditPacket decode(FriendlyByteBuf buf) {
        Action a = buf.readEnum(Action.class);
        String arg = buf.readUtf(256);
        ChunkSettings s = ChunkSettings.decode(buf.readUtf(8192));
        return new AdminChunksEditPacket(s == null && a == Action.SETTINGS ? Action.REFRESH : a, arg, s == null ? ChunkSettings.DEFAULTS : s);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            ChunkAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
