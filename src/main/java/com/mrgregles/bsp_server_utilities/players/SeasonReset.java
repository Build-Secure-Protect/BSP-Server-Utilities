package com.mrgregles.bsp_server_utilities.players;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkService;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What this mod forgets when BSP-Core's {@code /bsp season reset} starts every player over (owner decision SR15), heard
 * through BSP-Core's {@code SeasonEvent.Ended} with {@code isSeasonReset()} on every server the reset reaches (at once
 * where it was typed, later on the others).
 *
 * <ul>
 *   <li>Chunk unloading on every server ({@link ChunkService#forgetEveryone}): what the player reset does for one
 *   player, for every player with an entry here. Admins' holds forgotten; unloaded tickets stay unloaded, each one's
 *   owner found again a second later (one that was a player's only through a claim given up for the reset is kept
 *   unloaded with no owner), and loaded tickets that were a player's only through a claim checked then too. The checks
 *   waiting are kept in the world, so a stop right after the reset does not lose them.</li>
 * </ul>
 *
 * Kept: the rules agreement (SR15), the first-join row, bans, staff panel notes and tags, staff talks, the Action Log,
 * totem steals, last seen, who placed each machine and the always-loaded list. Chunk unloading's records are this
 * server's own, so nothing shared changes, where it was typed or elsewhere. The same reset heard twice does nothing the
 * second time.
 */
public final class SeasonReset {
    /** Season resets already applied in this run of the server, by the number of the season that ended. */
    private static final Set<Integer> APPLIED = Collections.synchronizedSet(new HashSet<>());

    /** What one season reset did here. */
    public record Result(boolean applied, boolean origin, int chunkPlayers) {
        public static final Result ALREADY = new Result(false, false, 0);
    }

    private SeasonReset() {
    }

    /**
     * At each server start: listens for BSP-Core's season resets (nothing without BSP-Core; never called by one older than
     * the reset). What was applied is forgotten, as it was another world's (a single-player game opens several in one run).
     */
    public static void register() {
        APPLIED.clear();
        if (BspCore.onSeasonReset(SeasonReset::heard)) {
            BSPServerUtilities.LOGGER.debug("Listening for BSP-Core season resets");
        }
    }

    private static void heard(MinecraftServer server, int endedNumber, boolean origin, String by) {
        apply(server, endedNumber, origin, by);
    }

    /** Forgets what the season reset says, on the server thread. */
    public static Result apply(MinecraftServer server, int endedNumber, boolean origin, String by) {
        if (!APPLIED.add(endedNumber)) {
            return Result.ALREADY;
        }
        int players;
        try {
            players = ChunkService.forgetEveryone(server);
        } catch (RuntimeException | LinkageError e) {
            APPLIED.remove(endedNumber); // not applied: the same reset heard again may try again
            throw e;
        }
        BSPServerUtilities.LOGGER.info("BSP-Core season reset (season {} ended by {}{}): chunk unloading forgot {} player(s), owners checked again in a second; rules agreements kept",
                endedNumber, by.isEmpty() ? "?" : by, origin ? ", typed here" : "", players);
        return new Result(true, origin, players);
    }

    /** Forgets that a season reset was applied (tests reuse season numbers). */
    public static void forgetAppliedForTests(int endedNumber) {
        APPLIED.remove(endedNumber);
    }
}
