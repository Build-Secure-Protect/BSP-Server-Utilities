package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCommands;
import com.mrgregles.bsp_server_utilities.lag.LagNetwork;
import com.mrgregles.bsp_server_utilities.lag.LagProfiler;
import com.mrgregles.bsp_server_utilities.lag.LagResult;
import com.mrgregles.bsp_server_utilities.lag.LagView;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.LagGoPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * The LAG tab's PROFILER (SU-152; D749 to D751, D759, D760): the ms ramp and the colour of a square with a timed chunk,
 * a loaded one without and one not loaded; one world's timed chunks summed into squares at a zoom with their slowest
 * chunk; a running sample's time left, progress and when the screen asks again; the outline's clock, how long ago, the
 * ms text, the share, the worst chunks and one world's blocks. On the server: TP beside a block (D750) finds the nearest
 * place to stand within reach, never the block's own, falls back to the chunk's safe spot when there is none and loads
 * nothing; through the screen's packet only editors are moved, beside the block, logged once as {@code chunk_tp}; the
 * packet reads back the same.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class LagScreenGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    /** High in the sky, clear of any land. */
    private static final int SKY = 300;

    private static LagResult.Chunk chunk(String dim, int x, int z, double ms) {
        return new LagResult.Chunk(dim, x, z, ms, ChunkCensus.Why.PLAYER, "Kestrel_", List.of());
    }

    private static LagResult result(List<LagResult.Chunk> chunks, List<LagResult.Block> blocks) {
        return new LagResult("survival", "Mrgregles", 1000, 30, 600, 600, 38.2, 12.1, false, 31_000, "", chunks, blocks);
    }

    @GameTest(template = EMPTY)
    public static void rampAndSquareColours(GameTestHelper h) {
        LagProfiler.resetForTests();
        int[] r = LagView.MS_RAMP;
        h.assertTrue(r[0] == 0xFF26303C && r[1] == 0xFF5E7F45 && r[2] == 0xFFF2B544 && r[3] == 0xFFFF6B5C && LagView.UNLOADED == 0xFF0E1117, "the ramp's colours");
        h.assertTrue(LagView.msColour(0) == r[0] && LagView.msColour(0.49) == r[0], "under 0.5 ms the darkest");
        h.assertTrue(LagView.msColour(0.5) == r[1] && LagView.msColour(1.99) == r[1], "0.5 to 2 ms");
        h.assertTrue(LagView.msColour(2) == r[2] && LagView.msColour(4.99) == r[2], "2 to 5 ms");
        h.assertTrue(LagView.msColour(5) == r[3] && LagView.msColour(80) == r[3], "5 ms and more");
        LagView.Square slow = new LagView.Square(6.1, 5, -6, 6.1);
        h.assertTrue(LagView.squareColour(slow, false) == r[3] && LagView.squareColour(slow, true) == r[3], "a timed chunk by its ms, loaded or not");
        h.assertTrue(LagView.squareColour(null, true) == r[0], "loaded with no time: the darkest loaded colour");
        h.assertTrue(LagView.squareColour(null, false) == LagView.UNLOADED, "not loaded and not timed: not loaded");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void squaresSumOneWorldAtAZoom(GameTestHelper h) {
        LagProfiler.resetForTests();
        List<LagResult.Chunk> cs = List.of(chunk(OVERWORLD, 5, -6, 9.8), chunk(OVERWORLD, 4, -6, 1.7), chunk(OVERWORLD, 2, 1, 3.4), chunk(NETHER, 5, -6, 2.2));
        Map<Long, LagView.Square> z0 = LagView.squares(cs, OVERWORLD, 0);
        h.assertTrue(z0.size() == 3, "one square a chunk, this world only: " + z0.size());
        LagView.Square a = z0.get(ChunkPos.asLong(5, -6));
        h.assertTrue(a != null && a.ms() == 9.8 && a.worstX() == 5 && a.worstZ() == -6, "a chunk's own time: " + a);
        h.assertTrue(!z0.containsKey(ChunkPos.asLong(0, 0)), "no square where nothing was timed");
        Map<Long, LagView.Square> z1 = LagView.squares(cs, OVERWORLD, 1);
        LagView.Square b = z1.get(ChunkPos.asLong(2, -3)); // chunks 4..5, -6..-5
        h.assertTrue(b != null && Math.abs(b.ms() - 11.5) < 1e-9 && b.worstX() == 5 && b.worstZ() == -6 && b.worstMs() == 9.8,
                "zoomed out, the chunks' times summed and the slowest kept: " + b);
        h.assertTrue(z1.size() == 2, "two squares at ×2: " + z1.size());
        h.assertTrue(LagView.squares(cs, NETHER, 0).size() == 1, "the other world's on its own");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void runningSampleTimeLeftAndAsks(GameTestHelper h) {
        LagProfiler.resetForTests();
        // 30 s = 600 ticks; the answer said 240 done
        h.assertTrue(LagView.leftMs(600, 240, 0) == 18_000, "360 ticks to go is 18 s");
        h.assertTrue(LagView.leftMs(600, 240, 2_000) == 16_000, "two seconds after the answer, 16 s");
        h.assertTrue(LagView.leftMs(600, 240, 60_000) == 0 && LagView.leftMs(600, 700, 0) == 0, "never under 0");
        h.assertTrue(Math.abs(LagView.progress(600, 240, 0) - 0.4) < 1e-9, "40 % done");
        h.assertTrue(Math.abs(LagView.progress(600, 240, 3_000) - 0.5) < 1e-9, "and half three seconds on");
        h.assertTrue(LagView.progress(600, 600, 0) == 1 && LagView.progress(0, 0, 0) == 1, "done");
        h.assertTrue(LagView.secondsLeft(600, 240, 500) == 18 && LagView.secondsLeft(600, 599, 0) == 1 && LagView.secondsLeft(600, 600, 0) == 0, "whole seconds, rounded up");
        long now = 100_000;
        h.assertTrue(!LagView.askDue(now, now - 1_999, now + 10_000, false), "not again within 2 s");
        h.assertTrue(LagView.askDue(now, now - 2_000, now + 10_000, false), "again after 2 s");
        h.assertTrue(LagView.askDue(now, now - 500, now - 1, false), "once more as soon as it should have ended");
        h.assertTrue(!LagView.askDue(now, now - 500, now - 1, true), "but only once");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void clockAgoAndFigures(GameTestHelper h) {
        LagProfiler.resetForTests();
        h.assertTrue(LagView.clock(LagView.OUTLINE_MS).equals("5:00") && LagView.clock(278_000).equals("4:38") && LagView.clock(1).equals("0:01") && LagView.clock(0).equals("0:00"),
                "the outline's m:ss, seconds rounded up");
        h.assertTrue(LagView.OUTLINE_MS == 300_000, "the outline lasts five minutes (D751)");
        long[] s = LagView.ago(42_000), m = LagView.ago(120_000), hr = LagView.ago(3 * 3_600_000L), d = LagView.ago(72 * 3_600_000L);
        h.assertTrue(s[0] == 42 && s[1] == 0 && m[0] == 2 && m[1] == 1 && hr[0] == 3 && hr[1] == 2 && d[0] == 3 && d[1] == 3, "seconds, minutes, hours, days ago");
        h.assertTrue(LagView.ago(-5)[0] == 0, "a clock a little ahead is 0 s");
        h.assertTrue(LagView.ms(0.42).equals("0.42") && LagView.ms(9.84).equals("9.8") && LagView.ms(38.2).equals("38.2"), "two decimals under 1 ms, one from there");
        h.assertTrue(LagView.sharePercent(9.8) == 20 && LagView.sharePercent(50) == 100, "the share of a 50 ms tick");
        LagResult r = result(List.of(chunk(OVERWORLD, 2, 1, 3.4), chunk(NETHER, -40, 12, 2.2), chunk(OVERWORLD, 5, -6, 9.8)),
                List.of(new LagResult.Block(OVERWORLD, 56, 64, 150, "mekanism:digital_miner", 4.1), new LagResult.Block(NETHER, 1, 2, 3, "minecraft:hopper", 0.4)));
        List<LagResult.Chunk> worst = LagView.worst(r, 2);
        h.assertTrue(worst.size() == 2 && worst.get(0).ms() == 9.8 && worst.get(1).ms() == 3.4, "the worst chunks of every world, slowest first");
        h.assertTrue(LagView.worst(r, 10).size() == 3 && LagView.worst(r, 0).isEmpty(), "at most what there is");
        h.assertTrue(LagView.blocksIn(r.blocks(), OVERWORLD).size() == 1 && LagView.blocksIn(r.blocks(), "minecraft:the_end").isEmpty(), "the outline's blocks of one world");
        h.succeed();
    }

    private static ChunkPos far(GameTestHelper h, int east) {
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(base.x + east, base.z);
    }

    private static void hold(ServerLevel level, ChunkPos c, boolean add) {
        ForgeChunkManager.forceChunk(level, BSPServerUtilities.MODID, new BlockPos(c.getMiddleBlockX(), 0, c.getMiddleBlockZ()), c.x, c.z, add, false);
    }

    /** A furnace in the sky on a stone floor two blocks round it. */
    private static BlockPos machine(ServerLevel level, ChunkPos c) {
        BlockPos b = new BlockPos(c.getMiddleBlockX(), SKY, c.getMiddleBlockZ());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(b.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(b, Blocks.FURNACE.defaultBlockState());
        return b;
    }

    private static void clearAround(ServerLevel level, BlockPos b, int r) {
        for (BlockPos p : BlockPos.betweenClosed(b.offset(-r, -r, -r), b.offset(r, r, r))) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 800)
    public static void besideFindsTheNearestPlaceOrTheChunk(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        ChunkPos c = far(h, 480);
        hold(level, c, false);
        hold(level, c, true);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(c.x, c.z) != null, "the chunk is loaded"))
                .thenExecute(() -> {
                    ServerPlayer player = TestPlayers.mockServerPlayer(h);
                    BlockPos b = machine(level, c);
                    try {
                        BlockPos spot = ChunkCommands.spotNear(level, b, ChunkCommands.BESIDE_REACH);
                        h.assertTrue(b.west().equals(spot), "the nearest place on the block's own level, in a fixed order: " + spot);
                        h.assertTrue(ChunkCommands.standable(level, spot) && ChunkCommands.near(b, spot, 1), "standable, beside it");

                        ChunkCommands.Went w = ChunkCommands.goBeside(player, level, b);
                        h.assertTrue(w.outcome() == ChunkCommands.Outcome.WENT && b.west().equals(w.spot()) && player.blockPosition().equals(b.west()), "the player stands there: " + w);
                        h.assertTrue(Math.abs(player.getYRot() - (-90f)) < 0.5f, "facing the block (east): " + player.getYRot());

                        // walled in: nothing within reach, so the chunk's safe spot
                        for (BlockPos p : BlockPos.betweenClosed(b.offset(-5, -5, -5), b.offset(5, 5, 5))) {
                            if (!p.equals(b)) {
                                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
                            }
                        }
                        h.assertTrue(ChunkCommands.spotNear(level, b, ChunkCommands.BESIDE_REACH) == null, "no place within reach of a walled-in block");
                        ChunkCommands.Went far = ChunkCommands.goBeside(player, level, b);
                        h.assertTrue(far.outcome() == ChunkCommands.Outcome.WENT && new ChunkPos(far.spot()).equals(c) && !ChunkCommands.near(b, far.spot(), ChunkCommands.BESIDE_REACH),
                                "then to the chunk's safe spot: " + far);
                        h.assertTrue(far.spot().equals(ChunkCommands.safeSpot(level, c)), "the one GO THERE uses");

                        ChunkPos away = far(h, 90_000);
                        BlockPos there = new BlockPos(away.getMiddleBlockX(), 64, away.getMiddleBlockZ());
                        h.assertTrue(ChunkCommands.spotNear(level, there, 3) == null, "a block in a chunk not loaded has no place near it");
                        h.assertTrue(ChunkCommands.goBeside(player, level, there).outcome() == ChunkCommands.Outcome.NOT_LOADED, "and is not gone to");
                        h.assertTrue(level.getChunkSource().getChunkNow(away.x, away.z) == null, "nothing was loaded for it");
                    } finally {
                        clearAround(level, b, 6);
                        hold(level, c, false);
                    }
                })
                .thenSucceed();
    }

    private static long logged(MinecraftServer server, ServerPlayer p, long since, String startsWith) {
        return ActionLog.recent(server, 50, false, List.of(LogEntry.CHUNK_TP)).stream()
                .filter(e -> e.atMs() >= since && e.actorId().equals(p.getUUID().toString()) && e.text().startsWith(startsWith)).count();
    }

    @GameTest(template = EMPTY, timeoutTicks = 800)
    public static void tpBesideMovesOnlyEditorsAndLogsOnce(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos c = far(h, 520);
        hold(level, c, false);
        hold(level, c, true);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(c.x, c.z) != null, "the chunk is loaded"))
                .thenExecute(() -> {
                    ServerPlayer player = TestPlayers.mockServerPlayer(h), admin = TestPlayers.mockServerPlayer(h);
                    server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
                    BlockPos b = machine(level, c);
                    long start = System.currentTimeMillis();
                    LagGoPacket beside = new LagGoPacket("", OVERWORLD, c.x, c.z, b.getX(), b.getY(), b.getZ(), true);
                    String text = "went beside " + b.getX() + " " + b.getY() + " " + b.getZ() + " in chunk " + c.x + ", " + c.z + " in " + OVERWORLD;
                    try {
                        BlockPos was = player.blockPosition();
                        h.assertTrue(!LagNetwork.go(player, beside), "a player who may not edit is refused");
                        h.assertTrue(player.blockPosition().equals(was) && logged(server, player, start, "") == 0, "and moves nowhere, with nothing logged");

                        h.assertTrue(LagNetwork.go(admin, beside), "an editor goes beside the block");
                        h.assertTrue(admin.blockPosition().equals(b.west()), "and stands beside it: " + admin.blockPosition());
                        h.assertTrue(logged(server, admin, start, text) == 1, "logged once as chunk_tp");

                        h.assertTrue(LagNetwork.go(admin, new LagGoPacket("", OVERWORLD, c.x, c.z, 0, 0, 0)), "GO THERE without beside still goes to the chunk");
                        h.assertTrue(new ChunkPos(admin.blockPosition()).equals(c) && logged(server, admin, start, text) == 1, "to the chunk, not beside the block");

                        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                        beside.encode(buf);
                        h.assertTrue(beside.equals(LagGoPacket.decode(buf)), "the packet reads back the same, beside kept");
                        LagGoPacket plain = new LagGoPacket("elsewhere", OVERWORLD, 3, 4, 50, 70, 60);
                        FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
                        plain.encode(buf2);
                        h.assertTrue(!plain.beside() && plain.equals(LagGoPacket.decode(buf2)), "GO THERE's is not beside");
                    } finally {
                        clearAround(level, b, 3);
                        hold(level, c, false);
                        server.getPlayerList().getOps().remove(admin.getGameProfile());
                    }
                })
                .thenSucceed();
    }
}
