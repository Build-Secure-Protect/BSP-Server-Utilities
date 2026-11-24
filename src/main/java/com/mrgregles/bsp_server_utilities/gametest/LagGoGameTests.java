package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCommands;
import com.mrgregles.bsp_server_utilities.compat.BspTeleport;
import com.mrgregles.bsp_server_utilities.lag.LagNetwork;
import com.mrgregles.bsp_server_utilities.lag.LagProfiler;
import com.mrgregles.bsp_server_utilities.lag.LagSnapshot;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.LagGoPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The LAG tab's GO THERE (SU-150; D750, D752): a loaded chunk's detail carries the safe spot its own server worked out,
 * standable and inside the chunk, and its text form reads back the same with a spot and without; an editor's GO THERE on
 * this server moves them into the chunk and is logged once as {@code chunk_tp}; on another server it goes through
 * BSP-Teleport to the given spot and is logged once; a player who may not edit moves nowhere, and with no BSP-Teleport
 * nothing happens.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class LagGoGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String OVERWORLD = "minecraft:overworld";
    /** A server name no test run is called. */
    private static final String ELSEWHERE = "lag_test_elsewhere";

    private static ChunkPos far(GameTestHelper h, int east) {
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(base.x + east, base.z);
    }

    private static void hold(ServerLevel level, ChunkPos c, boolean add) {
        ForgeChunkManager.forceChunk(level, BSPServerUtilities.MODID, new BlockPos(c.getMiddleBlockX(), 0, c.getMiddleBlockZ()), c.x, c.z, add, false);
    }

    private static long logged(MinecraftServer server, ServerPlayer p, long since, String startsWith) {
        return ActionLog.recent(server, 50, false, List.of(LogEntry.CHUNK_TP)).stream()
                .filter(e -> e.atMs() >= since && e.actorId().equals(p.getUUID().toString()) && e.text().startsWith(startsWith)).count();
    }

    private static ServerPlayer editor(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        h.getLevel().getServer().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        return admin;
    }

    @GameTest(template = EMPTY, timeoutTicks = 800)
    public static void detailCarriesAStandableSpotInTheChunk(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos c = far(h, 340);
        hold(level, c, false); // a ticket left by a run that timed out
        hold(level, c, true);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(c.x, c.z) != null, "the chunk is loaded"))
                .thenExecute(() -> {
                    try {
                        LagSnapshot.Detail d = LagSnapshot.detail(server, OVERWORLD, c.x, c.z);
                        h.assertTrue(d.loaded() && d.spot() != null, "a loaded chunk of generated land has a safe spot");
                        h.assertTrue(new ChunkPos(d.spot()).equals(c), "the spot is in the chunk: " + d.spot());
                        h.assertTrue(ChunkCommands.standable(level, d.spot()) || level.getFluidState(d.spot().below()).is(FluidTags.WATER), "the spot is safe to stand on");
                        h.assertTrue(d.spot().equals(ChunkCommands.safeSpot(level, c)), "it is the spot /su chunks tp uses");
                        h.assertTrue(d.equals(LagSnapshot.decodeDetail(LagSnapshot.encodeDetail(d))), "the detail with a spot reads back the same");
                        LagSnapshot.Detail none = new LagSnapshot.Detail(OVERWORLD, 5, -6, true, ChunkCensus.Why.TICKET, "mekanism", true,
                                List.of(new LagSnapshot.TypeCount("minecraft:item", 140)), List.of(), null);
                        h.assertTrue(none.equals(LagSnapshot.decodeDetail(LagSnapshot.encodeDetail(none))), "a detail without a spot reads back the same");
                        ChunkPos away = far(h, 90_000);
                        LagSnapshot.Detail unloaded = LagSnapshot.detail(server, OVERWORLD, away.x, away.z);
                        h.assertTrue(unloaded.spot() == null && level.getChunkSource().getChunkNow(away.x, away.z) == null, "a chunk not loaded has no spot, and is not loaded for one");
                    } finally {
                        hold(level, c, false);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 800)
    public static void goHereMovesOnlyEditorsAndLogsOnce(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos c = far(h, 380);
        hold(level, c, false);
        hold(level, c, true);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(c.x, c.z) != null, "the chunk is loaded"))
                .thenExecute(() -> {
                    ServerPlayer player = TestPlayers.mockServerPlayer(h), admin = editor(h);
                    long start = System.currentTimeMillis();
                    String text = "went to chunk " + c.x + ", " + c.z + " in " + OVERWORLD;
                    try {
                        BlockPos was = player.blockPosition();
                        h.assertTrue(!LagNetwork.go(player, new LagGoPacket("", OVERWORLD, c.x, c.z, 0, 0, 0)), "a player who may not edit is refused");
                        h.assertTrue(player.blockPosition().equals(was) && logged(server, player, start, "") == 0, "and moves nowhere, with nothing logged");

                        h.assertTrue(LagNetwork.go(admin, new LagGoPacket("", OVERWORLD, c.x, c.z, 0, 0, 0)), "an editor goes to this server's chunk");
                        h.assertTrue(new ChunkPos(admin.blockPosition()).equals(c), "and stands in it: " + admin.blockPosition());
                        h.assertTrue(logged(server, admin, start, text) == 1, "logged once as chunk_tp");

                        h.assertTrue(LagNetwork.go(admin, new LagGoPacket(Storage.currentServerId(), OVERWORLD, c.x, c.z, 0, 0, 0)), "this server named is this server too");
                        h.assertTrue(logged(server, admin, start, text) == 2, "each go logged once");
                    } finally {
                        hold(level, c, false);
                        server.getPlayerList().getOps().remove(admin.getGameProfile());
                    }
                })
                .thenSucceed();
    }

    record Move(BspTeleport.Place to, String message) {
    }

    static final class FakeTp implements BspTeleport.Api {
        final List<Move> moves = new ArrayList<>();
        boolean works = true;

        @Override
        public String serverName() {
            return "test";
        }

        @Override
        public List<BspTeleport.Warp> warps() {
            return List.of();
        }

        @Override
        public Optional<BspTeleport.Place> spawn(String server) {
            return Optional.empty();
        }

        @Override
        public boolean teleport(ServerPlayer player, BspTeleport.Place to, String message) {
            moves.add(new Move(to, message));
            return works;
        }
    }

    @GameTest(template = EMPTY)
    public static void goElsewhereThroughBspTeleportOnly(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h), admin = editor(h);
        FakeTp tp = new FakeTp();
        long start = System.currentTimeMillis();
        LagGoPacket there = new LagGoPacket(ELSEWHERE, OVERWORLD, 3, 4, 50, 70, 60);
        String text = "went to chunk 3, 4 in " + OVERWORLD + " on " + ELSEWHERE + " (50 70 60)";
        try {
            BspTeleport.useForTests(tp);
            h.assertTrue(!LagNetwork.go(player, there) && tp.moves.isEmpty(), "a player who may not edit is not moved");

            h.assertTrue(LagNetwork.go(admin, there), "an editor's move to another server starts");
            h.assertTrue(tp.moves.size() == 1, "through BSP-Teleport");
            h.assertTrue(tp.moves.get(0).message().equals(LagNetwork.ARRIVED) && !tp.moves.get(0).message().contains("bsp_server_utilities"),
                    "with the arrival line as plain text, never a lang key (a dedicated server has no mod language loaded)");
            BspTeleport.Place to = tp.moves.get(0).to();
            h.assertTrue(to.server().equals(ELSEWHERE) && to.world().equals(OVERWORLD) && to.x() == 50.5 && to.y() == 70 && to.z() == 60.5,
                    "to the middle of the spot that server gave: " + to);
            h.assertTrue(logged(server, admin, start, text) == 1, "logged once as chunk_tp with the server");

            tp.works = false;
            h.assertTrue(!LagNetwork.go(admin, there) && tp.moves.size() == 2, "a move BSP-Teleport could not start");
            h.assertTrue(logged(server, admin, start, "") == 1, "is not logged");

            BspTeleport.useForTests(null);
            BlockPos was = admin.blockPosition();
            h.assertTrue(!LagNetwork.go(admin, there), "without BSP-Teleport nothing happens");
            h.assertTrue(admin.blockPosition().equals(was) && logged(server, admin, start, "") == 1, "no move and nothing logged");
        } finally {
            BspTeleport.useForTests(null);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }
}
