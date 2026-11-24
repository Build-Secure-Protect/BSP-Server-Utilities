package com.mrgregles.bsp_server_utilities.admin;

import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.mobs.Crowding;
import com.mrgregles.bsp_server_utilities.mobs.MobLimits;
import com.mrgregles.bsp_server_utilities.network.AdminCountsPacket;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;

/**
 * The staff screen's counts (SU-154): this server and its players; the client check's refusals today (the list keeps the
 * newest 20); and the chunks over a limit: every one on this server (mobs over their number, and dropped items or arrows
 * over theirs at the last check, a chunk over both counting once for each), plus other servers' as last heard while
 * someone had the ENTITIES tab open (their fullest 8 of each). Nothing is asked of the other servers for it.
 */
public final class AdminCounts {
    private AdminCounts() {
    }

    public static AdminCountsPacket of(MinecraftServer server) {
        String here = Storage.currentServerId();
        int refused = ClientCheckService.refusedToday().size();
        int crowded = MobLimits.localOverCount(server) + Crowding.localOverCount();
        for (MobLimits.Seen s : MobLimits.networkFullest(server)) {
            if (!s.row().server().equals(here) && s.row().limit() > 0 && s.row().count() > s.row().limit()) {
                crowded++;
            }
        }
        for (Crowding.Seen s : Crowding.networkFullest(server)) {
            if (!s.row().server().equals(here) && (s.row().itemsOverMs() > 0 || s.row().arrowsOverMs() > 0)) {
                crowded++;
            }
        }
        return new AdminCountsPacket(RoomsViewPacket.cut(here, 64), server.getPlayerCount(), refused, crowded);
    }
}
