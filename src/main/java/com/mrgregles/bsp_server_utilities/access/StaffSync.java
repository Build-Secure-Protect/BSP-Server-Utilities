package com.mrgregles.bsp_server_utilities.access;

import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.StaffViewPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * Keeps every player's copy of the staff roster current (owner decision D61): when anything changes it is sent to all
 * players at most once a second, and to a player when they join.
 */
public final class StaffSync {
    private static volatile boolean dirty;
    private static int wait;

    private StaffSync() {
    }

    public static void init() {
        StaffDirectory.onChange(() -> dirty = true);
    }

    public static void tick(MinecraftServer server) {
        if (wait > 0) {
            wait--;
            return;
        }
        if (dirty) {
            dirty = false;
            wait = 20;
            ServerUtilsNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), StaffViewPacket.of(shown(server), System.currentTimeMillis()));
        }
    }

    public static void sendTo(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, StaffViewPacket.of(shown(player.server), System.currentTimeMillis()));
    }

    /** What players' panels and boards show: nobody while staff online is turned off (D150). */
    public static java.util.List<StaffDirectory.Member> shown(MinecraftServer server) {
        return com.mrgregles.bsp_server_utilities.setup.Switches.off(com.mrgregles.bsp_server_utilities.setup.Feature.STAFF) ? java.util.List.of() : StaffDirectory.roster(server);
    }
}
