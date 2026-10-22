package com.mrgregles.bsp_server_utilities.despawn;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.network.AdminDropsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminDropsPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The DROPS tab's server side (owner decisions D108, D111, D115): only players who may edit; every action answered with the tab's data. */
public final class DropsAdmin {
    private DropsAdmin() {
    }

    public static boolean apply(ServerPlayer player, AdminDropsEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        MinecraftServer server = player.server;
        switch (p.action()) {
            case SETTINGS -> DespawnService.setSettings(server, p.settings());
            case CLEAR -> {
                String w = p.arg().strip();
                if (w.equalsIgnoreCase("here") || w.equalsIgnoreCase("all") || w.equals(Storage.currentServerId()) || StaffDirectory.knownServers().contains(w)) {
                    ClearService.start(server, w, player);
                }
            }
            case REFRESH -> {
            }
        }
        ServerUtilsNetwork.sendTo(player, packet(server));
        return true;
    }

    public static AdminDropsPacket packet(MinecraftServer server) {
        long[] gone = DespawnService.removedLastHour(server);
        return new AdminDropsPacket(DespawnService.settings(server), Storage.currentServerId(), StaffDirectory.knownServers(), gone[0], gone[1], ClearService.counting());
    }
}
