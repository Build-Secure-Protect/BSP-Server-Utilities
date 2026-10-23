package com.mrgregles.bsp_server_utilities.log;

import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.network.LogViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * Keeps every player's copy of the newest log entries current (owner decision D61), as {@code StaffSync} does the
 * roster: when the log changes it is sent to all players at most once a second, and to a player when they join.
 */
public final class LogSync {
    private static volatile boolean dirty;
    private static int wait;

    private LogSync() {
    }

    public static void init() {
        ActionLog.onChange(() -> dirty = true);
    }

    public static LogViewPacket view(MinecraftServer server) {
        return LogViewPacket.of(ActionLog.recent(server, LogViewPacket.SENT, true, LogEntry.PUBLIC_KINDS), Storage.currentServerId(),
                ServerUtilsConfig.getOr(ServerUtilsConfig.LOG_KEEP_DAYS, 7), System.currentTimeMillis());
    }

    public static void tick(MinecraftServer server) {
        if (wait > 0) {
            wait--;
            return;
        }
        if (dirty) {
            dirty = false;
            wait = 20;
            ServerUtilsNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), view(server));
        }
    }

    public static void sendTo(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, view(player.server));
    }

    /** {@code /actionlog} for everyone (D58): a fresh copy, and the read-only screen opens on it. */
    public static void open(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, new com.mrgregles.bsp_server_utilities.network.LogOpenPacket(view(player.server)));
    }
}
