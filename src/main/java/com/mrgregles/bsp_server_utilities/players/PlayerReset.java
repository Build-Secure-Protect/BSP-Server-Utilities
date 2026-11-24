package com.mrgregles.bsp_server_utilities.players;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkService;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What this mod forgets when BSP-Core's {@code /bsp player reset} starts a player over (owner decision PR17), heard
 * through BSP-Core's {@code PlayerResetEvent.Forgotten} on every server the reset reaches (at once where it was typed,
 * possibly hours late on a server that was down).
 *
 * <ul>
 *   <li>The rules agreement: where the reset was typed, cleared for the network ({@link RulesService#reset}), so they
 *   see the rules popup again; on the other servers only this world's copy. Either way only an agreement made before
 *   the reset (a late server, or the same reset heard again after a restart, must not undo one given since).</li>
 *   <li>Chunk unloading on every server ({@link ChunkService#forgetPlayer}): an admin's hold forgotten; their unloaded
 *   tickets stay unloaded, each one's owner found again a second later (one that was theirs only through an abandoned
 *   claim is kept unloaded with no owner).</li>
 * </ul>
 *
 * Kept: the first-join row (D170: the public counter stays honest and there is no second welcome), bans, staff panel
 * notes and tags, staff talks, the Action Log, totem steals, last seen, and who placed each machine. The same reset
 * heard twice does nothing the second time.
 */
public final class PlayerReset {
    private static final int REMEMBERED = 512;
    /** Resets already applied here, by player and reset time, newest last. */
    private static final Map<String, Boolean> APPLIED = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED;
        }
    });

    /** What one reset did here. */
    public record Result(boolean applied, boolean rulesForNetwork, boolean rulesHere) {
        public static final Result ALREADY = new Result(false, false, false);
    }

    private PlayerReset() {
    }

    /** At server start: listens for BSP-Core's resets (nothing without BSP-Core or with one older than the event). */
    public static void register() {
        if (BspCore.onPlayerReset(PlayerReset::forgotten)) {
            BSPServerUtilities.LOGGER.debug("Listening for BSP-Core player resets");
        }
    }

    private static void forgotten(MinecraftServer server, UUID player, String name, long resetMs, boolean origin, String by) {
        forget(server, player, name, resetMs, origin);
    }

    /** Forgets the player as the reset says; on the server thread. */
    public static Result forget(MinecraftServer server, UUID player, String name, long resetMs, boolean origin) {
        if (APPLIED.putIfAbsent(player + "|" + resetMs, Boolean.TRUE) != null) {
            return Result.ALREADY;
        }
        boolean network = false, here;
        if (origin) {
            com.mrgregles.bsp_server_utilities.rules.Agreement a = RulesService.agreement(server, player);
            network = a == null || a.agreedAtMs() < resetMs; // an agreement given since the reset stays (a repeat after a restart)
            if (network) {
                RulesService.reset(server, player);
            }
            here = network;
        } else {
            here = RulesService.forgetAgreedBefore(server, player, resetMs);
        }
        ChunkService.forgetPlayer(server, player);
        BSPServerUtilities.LOGGER.info("BSP-Core reset {} ({}): rules agreement {}; chunk owners checked again in a second", name, player,
                network ? "cleared for the network" : here ? "cleared on this server" : "unchanged here");
        return new Result(true, network, here);
    }
}
