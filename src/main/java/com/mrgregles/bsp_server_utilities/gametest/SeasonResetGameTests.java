package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkData;
import com.mrgregles.bsp_server_utilities.chunks.ChunkOwners;
import com.mrgregles.bsp_server_utilities.chunks.ChunkService;
import com.mrgregles.bsp_server_utilities.chunks.ChunkSettings;
import com.mrgregles.bsp_server_utilities.chunks.ChunkTickets;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.players.SeasonReset;
import com.mrgregles.bsp_server_utilities.rules.Agreement;
import com.mrgregles.bsp_server_utilities.rules.RulesData;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BSP-Core's season reset (SR15): heard by class name, its {@code isSeasonReset} and {@code isOrigin} read by
 * reflection (missing: false); every player's chunk-unloading entries forgotten as the player reset forgets one
 * player's; the rules agreement kept. The chunk test runs in a batch of its own, because it forgets every player and
 * would clear the other chunk tests' holds.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class SeasonResetGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long MIN = 60_000L;

    /** Stands in for BSP-Core's {@code SeasonEvent$Ended} with the season reset's getters (the jar this mod builds against lacks them). */
    public static class SeasonStandIn extends Event {
        private final MinecraftServer server;
        private final int endedNumber;
        private final boolean seasonReset, origin;
        private final String by;

        public SeasonStandIn(MinecraftServer server, int endedNumber, boolean seasonReset, boolean origin, String by) {
            this.server = server;
            this.endedNumber = endedNumber;
            this.seasonReset = seasonReset;
            this.origin = origin;
            this.by = by;
        }

        public MinecraftServer getServer() {
            return server;
        }

        public int getEndedNumber() {
            return endedNumber;
        }

        public boolean isFullReset() {
            return seasonReset;
        }

        public boolean isSeasonReset() {
            return seasonReset;
        }

        public boolean isOrigin() {
            return origin;
        }

        public String getBy() {
            return by;
        }
    }

    /** An older BSP-Core's season end: no {@code isSeasonReset}, no {@code isOrigin}. */
    public static class OldSeasonStandIn extends Event {
        private final MinecraftServer server;
        private final int endedNumber;

        public OldSeasonStandIn(MinecraftServer server, int endedNumber) {
            this.server = server;
            this.endedNumber = endedNumber;
        }

        public MinecraftServer getServer() {
            return server;
        }

        public int getEndedNumber() {
            return endedNumber;
        }

        public boolean isFullReset() {
            return true;
        }

        public String getBy() {
            return "Admin";
        }
    }

    @GameTest(template = EMPTY)
    public void the_season_reset_is_heard_by_class_name_and_an_older_bsp_cores_season_end_is_not_one(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicReference<String> got = new AtomicReference<>();
        BspCore.SeasonResetListener listener = (srv, number, origin, by) -> got.set((srv == server) + " " + number + " " + origin + " " + by);
        h.assertTrue(BspCore.hookSeasonReset("com.mrgregles.bsp_core.api.event.NoSuchEvent$Ended", listener) == null, "a missing class: not listening");
        h.assertTrue(BspCore.hookSeasonReset("java.lang.String", listener) == null, "a class that is not an event: not listening");
        h.assertTrue(BspCore.hookSeasonReset(RulesGameTests.ResetStandIn.class.getName(), listener) == null, "an event without getEndedNumber: not listening");
        boolean real;
        try {
            Class.forName(BspCore.SEASON_ENDED_EVENT);
            real = true;
        } catch (ClassNotFoundException e) {
            real = false;
        }
        h.assertTrue(BspCore.onSeasonReset(listener) == (real && BspCore.isLoaded()),
                "listening at server start exactly when BSP-Core has the season end (it " + (real ? "has" : "does not") + " here)");

        Object hooked = BspCore.hookSeasonReset(SeasonStandIn.class.getName(), listener);
        Object old = BspCore.hookSeasonReset(OldSeasonStandIn.class.getName(), listener);
        try {
            h.assertTrue(hooked != null && old != null, "both classes have the needed getters: listening");
            MinecraftForge.EVENT_BUS.post(new SeasonStandIn(server, 7, false, true, "Admin"));
            h.assertTrue(got.get() == null, "an ordinary season end is not a season reset");
            MinecraftForge.EVENT_BUS.post(new SeasonStandIn(server, 7, true, true, "Admin"));
            h.assertTrue("true 7 true Admin".equals(got.get()), "a season reset typed here, every getter read by reflection: " + got.get());
            MinecraftForge.EVENT_BUS.post(new SeasonStandIn(server, 8, true, false, "network"));
            h.assertTrue("true 8 false network".equals(got.get()), "heard from another server: not the origin: " + got.get());
            got.set(null);
            MinecraftForge.EVENT_BUS.post(new OldSeasonStandIn(server, 9));
            h.assertTrue(got.get() == null, "an older BSP-Core's season end, without isSeasonReset: read as false, nothing reset");
        } finally {
            if (hooked != null) {
                MinecraftForge.EVENT_BUS.unregister(hooked);
            }
            if (old != null) {
                MinecraftForge.EVENT_BUS.unregister(old);
            }
        }
        MinecraftForge.EVENT_BUS.post(new SeasonStandIn(server, 10, true, true, "Admin"));
        h.assertTrue(got.get() == null, "unregistered when done: nothing hears it any more");
        h.succeed();
    }

    private record Machine(ServerLevel level, BlockPos pos, ChunkTickets.Ticket ticket) {
        boolean live() {
            return ChunkTickets.live(level).stream().anyMatch(t -> t.key().equals(ticket.key()));
        }

        boolean suspended() {
            return ChunkData.get(level.getServer()).suspended().containsKey(ticket.key());
        }

        void close() {
            ForgeChunkManager.forceChunk(level, ticket.modId(), pos, ticket.chunkPos().x, ticket.chunkPos().z, false, false);
            ChunkData.get(level.getServer()).takeSuspended(ticket.key());
            ChunkData.get(level.getServer()).putPlacer(ChunkOwners.posKey(level, pos), null);
        }
    }

    /** A chest held loaded by a ticket under this mod's id, its placer recorded when given. */
    private static Machine machine(GameTestHelper h, BlockPos rel, ServerPlayer placer) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(rel);
        h.setBlock(rel, Blocks.CHEST);
        ChunkPos c = new ChunkPos(pos);
        ForgeChunkManager.forceChunk(level, BSPServerUtilities.MODID, pos, c.x, c.z, true, false);
        if (placer != null) {
            ChunkData.get(level.getServer()).putPlacer(ChunkOwners.posKey(level, pos), placer.getUUID());
        }
        return new Machine(level, pos, ChunkTickets.Ticket.ofBlock(level, BSPServerUtilities.MODID, pos, c.toLong(), false));
    }

    @GameTest(template = EMPTY, batch = "season-reset")
    public void a_season_reset_forgets_every_players_chunk_entries_as_the_player_reset_does_and_keeps_the_rules_agreement(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        ServerPlayer other = TestPlayers.mockServerPlayer(h);
        ServerPlayer holder = TestPlayers.mockServerPlayer(h);
        ChunkData data = ChunkData.get(server);
        ChunkSettings before = data.settings();
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        List<ServerPlayer> online = new ArrayList<>();
        RulesData rules = RulesData.get(server);
        int season = 900_000 + (int) (Math.random() * 90_000);
        List<Machine> machines = new ArrayList<>();
        try {
            ChunkService.useForTests(now::get, () -> online);
            data.setSettings(new ChunkSettings(true, 5, List.of(), List.of(), 0).withRevision(before.revision()).sanitised());
            Machine mine = machine(h, new BlockPos(2, 2, 2), owner);
            Machine theirs = machine(h, new BlockPos(6, 2, 2), other);
            Machine claim = machine(h, new BlockPos(10, 2, 2), null);
            Machine orphan = machine(h, new BlockPos(14, 2, 2), null);
            Machine liveClaim = machine(h, new BlockPos(2, 2, 10), null);
            Machine loose = machine(h, new BlockPos(6, 2, 10), null);
            machines.addAll(List.of(mine, theirs, claim, orphan, liveClaim, loose));
            rules.putAgreementForTests(owner.getUUID(), new Agreement(RulesService.rules(server).version(), 1_000L, "test", "Season"));
            ChunkService.onLogout(owner);
            ChunkService.onLogout(other);
            now.addAndGet(5 * MIN);
            ChunkService.sweep(server);
            h.assertTrue(mine.suspended() && theirs.suspended() && !mine.live() && !theirs.live(), "both away five minutes: both machines unloaded");
            h.assertTrue(liveClaim.live() && loose.live(), "machines with no owner found stay loaded (D96)");
            // a machine charged to the owner only through their claim (no saved owner, no placer): unloaded as theirs
            ChunkTickets.remove(claim.level(), claim.ticket());
            data.putSuspended(new ChunkData.Suspended(claim.ticket(), owner.getUUID(), "Season", now.get()));
            h.assertTrue(ChunkService.unloadTicket(server, orphan.ticket().key()) && orphan.suspended(), "a machine with no owner, unloaded by an admin");
            // a loaded machine the last check charged to the owner through their claim (BSP-Region Guard is not here to say so)
            ChunkService.ownerForTests(liveClaim.ticket().key(), new ChunkOwners.Found(owner.getUUID(), ChunkOwners.How.CLAIM));
            data.hold(owner.getUUID(), true);
            data.hold(other.getUUID(), true);
            data.hold(holder.getUUID(), true); // a hold and nothing unloaded
            long ownerLeft = data.lastLeft(owner.getUUID()), otherLeft = data.lastLeft(other.getUUID());

            SeasonReset.Result res = SeasonReset.apply(server, season, false, "network");
            h.assertTrue(res.applied() && !res.origin() && res.chunkPlayers() >= 3, "the season reset is applied for every player with an entry: " + res);
            h.assertTrue(!data.held().contains(owner.getUUID()) && !data.held().contains(other.getUUID()) && !data.held().contains(holder.getUUID()),
                    "every admin's hold is forgotten, not only one player's, a hold with nothing unloaded too");
            h.assertTrue(Long.valueOf(ownerLeft).equals(data.lastLeft(owner.getUUID())) && Long.valueOf(otherLeft).equals(data.lastLeft(other.getUUID())),
                    "when each left is kept, so nothing loads because of the reset");
            h.assertTrue(owner.getUUID().equals(data.placer(ChunkOwners.posKey(mine.level(), mine.pos())))
                    && other.getUUID().equals(data.placer(ChunkOwners.posKey(theirs.level(), theirs.pos()))), "who placed each machine is kept (the blocks stay)");
            h.assertTrue(mine.suspended() && theirs.suspended() && !mine.live() && !theirs.live(), "unloaded tickets stay unloaded: none is given back by the reset itself");
            h.assertTrue(ChunkService.rejudgePending(owner.getUUID()) && ChunkService.rejudgePending(other.getUUID()) && ChunkService.rejudgePending(holder.getUUID())
                            && owner.getUUID().equals(data.suspended().get(claim.ticket().key()).player()),
                    "every player's owners are checked again a second later (after BSP-Region Guard gives the claims up), not at once");
            h.assertTrue(ChunkService.claimsPending() && data.claimChecks().contains(liveClaim.ticket().key()) && !data.claimChecks().contains(loose.ticket().key())
                    && liveClaim.live(), "the loaded claim machine is checked then too, not at once; a machine with no owner is not");
            h.assertTrue(RulesService.agreement(server, owner.getUUID()) != null, "the rules agreement stays (SR15)");
            h.assertTrue(data.rejudges().contains(owner.getUUID()) && data.rejudges().contains(other.getUUID()), "the owner checks waiting are kept in the world too");
            ChunkService.restartRejudgesForTests(server);
            h.assertTrue(ChunkService.rejudgePending(owner.getUUID()) && ChunkService.rejudgePending(other.getUUID()) && ChunkService.claimsPending(),
                    "a stop before the checks (a reset heard as the server stops): queued again at the next start, not lost");

            h.assertTrue(ChunkService.rejudgeNowForTests(server, owner.getUUID()) == 1, "one of the owner's tickets changes owner: the claim machine's");
            h.assertTrue(ChunkService.rejudgeNowForTests(server, other.getUUID()) == 0, "the other player's machine is still theirs (its placer)");
            h.assertTrue(!data.rejudges().contains(owner.getUUID()) && !data.rejudges().contains(other.getUUID()), "a check done is no longer kept");
            h.assertTrue(ChunkService.checkClaims(server) == 1 && !ChunkService.claimsPending() && data.claimChecks().isEmpty(), "one loaded ticket had only the claim");
            ChunkService.drainRestores(server, 1000);
            ChunkData.Suspended c = data.suspended().get(claim.ticket().key());
            h.assertTrue(c != null && c.player() == null && !claim.live(), "the claim machine has no owner now: kept unloaded with no owner, listed (D96)");
            ChunkData.Suspended lc = data.suspended().get(liveClaim.ticket().key());
            h.assertTrue(lc != null && lc.player() == null && !liveClaim.live(), "the loaded claim machine too: unloaded with no owner, not loaded for good");
            h.assertTrue(loose.live() && !loose.suspended(), "a machine that never had an owner found stays loaded (D96)");
            ChunkData.Suspended o = data.suspended().get(orphan.ticket().key());
            h.assertTrue(o != null && o.player() == null && !orphan.live(), "the admin's unload of a machine with no owner stays");
            h.assertTrue(mine.suspended() && theirs.suspended(), "each player's own machine stays unloaded, still theirs");

            h.assertTrue(SeasonReset.apply(server, season, true, "Admin") == SeasonReset.Result.ALREADY, "the same season reset heard twice does nothing the second time");

            online.add(owner);
            ChunkService.onLogin(owner);
            ChunkService.drainRestores(server, 1000);
            h.assertTrue(mine.live() && !mine.suspended(), "their own machine comes back when they join, as for anyone");
            h.assertTrue(claim.suspended() && !claim.live() && liveClaim.suspended() && !liveClaim.live(), "the claim machines do not: they are no longer theirs");
        } finally {
            SeasonReset.forgetAppliedForTests(season);
            rules.putAgreementForTests(owner.getUUID(), null);
            for (ServerPlayer p : List.of(owner, other, holder)) {
                data.hold(p.getUUID(), false);
                data.forgetLastLeftForTests(p.getUUID());
            }
            for (UUID id : data.rejudges()) { // the reset forgot the test world's leftovers too: their checks done here, not in a later batch
                ChunkService.rejudgeNowForTests(server, id);
            }
            for (String key : data.claimChecks()) {
                data.claimCheck(key, false);
            }
            ChunkService.checkClaims(server);
            for (Machine m : machines) {
                ChunkService.ownerForTests(m.ticket().key(), null);
                m.close();
            }
            data.setSettings(before);
            ChunkService.drainRestores(server, 1000);
            ChunkService.useReal();
        }
        h.succeed();
    }
}
