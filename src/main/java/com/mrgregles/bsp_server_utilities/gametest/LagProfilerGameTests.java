package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.lag.LagNetwork;
import com.mrgregles.bsp_server_utilities.lag.LagProfiler;
import com.mrgregles.bsp_server_utilities.lag.LagResult;
import com.mrgregles.bsp_server_utilities.lag.LagTimer;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.LagAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.LagAskPacket;
import com.mrgregles.bsp_server_utilities.network.LagSamplePacket;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The lag profiler on the server (SU-151; D749, D751, D752, D759 to D761): nothing is recorded outside a sample; timings
 * driven by hand are added up per chunk and kind, ranked, with the worst one's place, OUTLINE's blocks and the part not
 * in any chunk; the text form reads back the same and stays under the packet's limit; a real sample times the test's
 * hoppers and armour stands, runs its length in ticks, is kept and logged once; a second start is refused, a player who
 * may not edit starts nothing, STOP ends it early with what it has; across servers a START for another server is passed
 * on by notice, a notice naming this server starts and stops here, and PROFILE is answered. Audit of SU-153: the best of
 * many kept without sorting them all; a PROFILE cut to the cap between servers keeps the slowest chunks; START and STOP
 * closer than the gap are dropped; a sample the server's stop cuts short is logged once. Review of the audit fixes: a
 * nested block entity tick of another level gives the outer level back as it returns; a block entity and a scheduled tick
 * (or two types) at one place and time keep one order.
 * <p>
 * The tests that run a sample each have a batch of their own, so no two samples overlap (one runs per server). Every test
 * starts with the LAG test hooks reset ({@link LagProfiler#resetForTests}), so a test that timed out leaves nothing behind.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class LagProfilerGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String OVERWORLD = "minecraft:overworld";
    private static final long MS = 1_000_000L;

    private static ChunkPos far(GameTestHelper h, int east) {
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(base.x + east, base.z);
    }

    private static ServerPlayer editor(GameTestHelper h) {
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        h.getLevel().getServer().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        return admin;
    }

    private static LagProfiler.Starter starter(String name) {
        return new LagProfiler.Starter(UUID.randomUUID().toString(), name, "admin", "Admin", 0xFFD23A);
    }

    private static long logged(MinecraftServer server, String actorId, String contains) {
        return ActionLog.recent(server, 100, false, List.of(LogEntry.LAG_SAMPLE)).stream()
                .filter(e -> e.actorId().equals(actorId) && e.text().contains(contains)).count();
    }

    private static List<StorageBackend.Notice> notices(MemoryBackend db, String kind) {
        try {
            return db.fetchNoticesAfter(0, 500).stream().filter(n -> n.kind().equals(kind)).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean ranked(LagResult r) {
        for (int i = 1; i < r.chunks().size(); i++) {
            if (r.chunks().get(i).ms() > r.chunks().get(i - 1).ms()) {
                return false;
            }
        }
        return true;
    }

    @GameTest(template = EMPTY)
    public static void nothingRecordedOutsideASample(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        ChunkPos c = far(h, 420);
        h.assertTrue(!LagTimer.on && !LagProfiler.runningForTests(), "no sample runs in this batch");
        BlockPos at = new BlockPos(c.getMiddleBlockX(), 64, c.getMiddleBlockZ());
        LagTimer.recordBlockEntity(level, at, BlockEntityType.HOPPER, 5 * MS);
        LagTimer.recordEntity(level, c.toLong(), EntityType.ARMOR_STAND, 1, at.asLong(), 5 * MS);
        LagTimer.recordChunk(level, c.toLong(), 5 * MS);
        LagTimer.recordScheduled(level, at.asLong(), Blocks.REDSTONE_WIRE, 5 * MS);
        h.assertTrue(LagTimer.chunksForTests() == 0, "the collector stays empty outside a sample");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void timingsAddUpRankedPerChunkAndKind(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos c = far(h, 440), quiet = far(h, 441);
        BlockPos slow = new BlockPos(c.getMinBlockX() + 1, 70, c.getMinBlockZ() + 1), other = slow.east(), wire = slow.south(2);
        BlockPos stand1 = slow.above(), stand2 = slow.above(2);
        LagResult r;
        // the timings are driven by hand inside this one call: no tick runs in between, so nothing real joins them
        LagProfiler.beginForTests();
        try {
            for (int t = 0; t < 20; t++) {
                LagTimer.recordBlockEntity(level, slow, BlockEntityType.HOPPER, 5 * MS);
                LagTimer.recordBlockEntity(level, other, BlockEntityType.HOPPER, MS);
                LagTimer.recordEntity(level, c.toLong(), EntityType.ARMOR_STAND, -101, stand1.asLong(), 2 * MS);
                LagTimer.recordEntity(level, c.toLong(), EntityType.ARMOR_STAND, -102, stand2.asLong(), MS / 2);
                LagTimer.recordChunk(level, c.toLong(), MS / 10);
                LagTimer.recordChunk(level, quiet.toLong(), MS / 5);
            }
            for (int t = 0; t < 10; t++) {
                LagTimer.recordScheduled(level, wire.asLong(), Blocks.REDSTONE_WIRE, MS);
            }
            h.assertTrue(LagTimer.nanosForTests(level, c, LagResult.Kind.BLOCK_ENTITY, BlockEntityType.HOPPER) == 120 * MS, "the hoppers' time is added up");
        } finally {
            r = LagProfiler.resultForTests(server, starter("tilly_t"), 20, 20 * 50 * MS);
        }
        h.assertTrue(!LagTimer.on && LagTimer.chunksForTests() == 0, "the timings stop and are cleared");
        h.assertTrue(r.ticks() == 20 && r.tickMs() == 50.0 && !r.running(), "the whole tick averaged over the ticks: " + r.tickMs());
        h.assertTrue(r.chunks().size() == 2 && ranked(r), "the chunks ranked by ms per tick: " + r.chunks());
        LagResult.Chunk top = r.chunks().get(0);
        h.assertTrue(top.dimension().equals(OVERWORLD) && top.x() == c.x && top.z() == c.z && top.ms() == 9.1, "the busy chunk first at 9.1 ms: " + top);
        h.assertTrue(Math.abs(top.share() - 9.1 / 50) < 1e-9, "with its share of the 50 ms tick");
        h.assertTrue(r.outsideMs() == 40.7, "the rest of the tick is not in any chunk: " + r.outsideMs());
        List<LagResult.Entry> e = top.entries();
        h.assertTrue(e.size() == 4, "four kinds in the chunk: " + e);
        h.assertTrue(e.get(0).kind() == LagResult.Kind.BLOCK_ENTITY && e.get(0).label().equals("minecraft:hopper") && e.get(0).count() == 2
                && e.get(0).ms() == 6.0 && slow.equals(e.get(0).worst()), "the hoppers first, two of them, the slow one worst: " + e.get(0));
        h.assertTrue(e.get(1).kind() == LagResult.Kind.ENTITY && e.get(1).label().equals("minecraft:armor_stand") && e.get(1).count() == 2
                && e.get(1).ms() == 2.5 && stand1.equals(e.get(1).worst()), "then the armour stands, with the worst one's place: " + e.get(1));
        h.assertTrue(e.get(2).kind() == LagResult.Kind.SCHEDULED && e.get(2).label().equals(LagResult.SCHEDULED_LABEL) && e.get(2).ms() == 0.5
                && wire.equals(e.get(2).worst()), "then the scheduled ticks: " + e.get(2));
        h.assertTrue(e.get(3).kind() == LagResult.Kind.CHUNK && e.get(3).label().equals(LagResult.CHUNK_LABEL) && e.get(3).ms() == 0.1 && e.get(3).worst() == null,
                "and the chunk's own tick as one line: " + e.get(3));
        h.assertTrue(r.chunks().get(1).ms() == 0.2 && r.chunks().get(1).why() == ChunkCensus.Why.OTHER, "a chunk not loaded at the end is put down as other");
        h.assertTrue(r.blocks().size() == 3, "OUTLINE has the three timed blocks: " + r.blocks());
        LagResult.Block b = r.blocks().get(0);
        h.assertTrue(b.x() == slow.getX() && b.y() == slow.getY() && b.z() == slow.getZ() && b.label().equals("minecraft:hopper") && b.ms() == 5.0,
                "the slowest block first: " + b);
        h.assertTrue(r.blocks().stream().anyMatch(k -> k.label().equals("minecraft:redstone_wire") && k.x() == wire.getX()), "a scheduled tick's block by its id");
        h.assertTrue(r.equals(LagResult.decode(LagResult.encode(r))), "the result reads back the same");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void textFormReadsBackUnderTheLimit(GameTestHelper h) {
        LagProfiler.resetForTests();
        String label = "somemod:" + "a_very_long_block_entity_name_".repeat(4);
        List<LagResult.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < LagResult.TOP_CHUNKS; i++) {
            List<LagResult.Entry> entries = new ArrayList<>();
            for (int k = 0; k < LagResult.TOP_ENTRIES; k++) {
                entries.add(new LagResult.Entry(LagResult.Kind.values()[k % 4], label + k, k + 1, (300 - i) / 100.0, k % 3 == 0 ? null : new BlockPos(i * 16, -60 + k, -i * 16)));
            }
            chunks.add(new LagResult.Chunk(i % 2 == 0 ? OVERWORLD : "minecraft:the_nether", i, -i, (300 - i) / 10.0, ChunkCensus.Why.values()[i % 5], i % 3 == 0 ? "" : "player_" + i, entries));
        }
        List<LagResult.Block> blocks = new ArrayList<>();
        for (int i = 0; i < LagResult.TOP_BLOCKS; i++) {
            blocks.add(new LagResult.Block(OVERWORLD, i, -64 + i, -i, label, 1.234 - i / 1000.0));
        }
        LagResult r = new LagResult("survival", "tilly_t", 1_700_000_000_000L, 30, 600, 600, 38.217, 12.004, false, 1_700_000_030_000L, "", chunks, blocks);
        String text = LagResult.encode(r);
        h.assertTrue(text.length() < LagAnswerPacket.MAX_PAYLOAD, "200 full chunks fit: " + text.length());
        h.assertTrue(r.equals(LagResult.decode(text)), "and read back the same");

        LagResult running = new LagResult("survival", "pip|py", 1L, 60, 1200, 300, 41.5, 0, true, 99L, "", List.of(), List.of());
        LagResult.Profile both = LagResult.decodeProfile(LagResult.encodeProfile(new LagResult.Profile(running, r)));
        h.assertTrue(both.running() != null && both.running().running() && both.running().by().equals("pip/py") && r.equals(both.last()),
                "a profile carries the running sample and the latest finished one");
        LagResult.Profile none = LagResult.decodeProfile(LagResult.encodeProfile(new LagResult.Profile(null, null)));
        h.assertTrue(none.running() == null && none.last() == null, "and neither");

        String huge = "somemod:" + "x".repeat(5_000);
        List<LagResult.Chunk> big = new ArrayList<>();
        for (int i = 0; i < LagResult.TOP_CHUNKS; i++) {
            List<LagResult.Entry> entries = new ArrayList<>();
            for (int k = 0; k < LagResult.TOP_ENTRIES; k++) {
                entries.add(new LagResult.Entry(LagResult.Kind.ENTITY, huge, 1, 1.0, null));
            }
            big.add(new LagResult.Chunk(OVERWORLD, i, i, 2.0, ChunkCensus.Why.PLAYER, "", entries));
        }
        LagResult tooBig = new LagResult("survival", "tilly_t", 1L, 30, 600, 600, 50, 0, false, 2L, "", big, blocks);
        String cut = LagResult.encodeProfile(new LagResult.Profile(running, tooBig));
        LagResult.Profile back = LagResult.decodeProfile(cut);
        h.assertTrue(cut.length() <= LagAnswerPacket.MAX_PAYLOAD, "a result too big is cut to the limit: " + cut.length());
        h.assertTrue(back.last() != null && !back.last().chunks().isEmpty() && back.last().chunks().size() < LagResult.TOP_CHUNKS
                && back.last().blocks().size() == LagResult.TOP_BLOCKS, "by whole chunks, keeping the blocks");
        // between servers: the cap of a notice, the slowest chunks kept in their order
        String notice = LagResult.encodeProfile(new LagResult.Profile(running, r), LagNetwork.NOTICE_MAX - 64);
        LagResult.Profile shared = LagResult.decodeProfile(notice);
        h.assertTrue(text.length() > LagNetwork.NOTICE_MAX && notice.length() <= LagNetwork.NOTICE_MAX - 64, "a full result is cut to the cap between servers: " + notice.length());
        List<LagResult.Chunk> kept = shared.last() == null ? List.of() : shared.last().chunks();
        h.assertTrue(!kept.isEmpty() && kept.size() < r.chunks().size() && kept.equals(r.chunks().subList(0, kept.size())), "keeping the slowest chunks, in rank order: " + kept.size());
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 800, batch = "lag-profiler-run")
    public static void sampleTimesRunsItsLengthAndStops(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer admin = editor(h), player = TestPlayers.mockServerPlayer(h);
        BlockPos rel = new BlockPos(3, 2, 3);
        ChunkPos c = new ChunkPos(h.absolutePos(rel));
        for (int i = 0; i < 4; i++) {
            h.setBlock(rel.above(i), Blocks.HOPPER); // one column, so one chunk
        }
        for (int i = 0; i < 3; i++) {
            h.spawn(EntityType.ARMOR_STAND, rel.above(4)); // on top of the hoppers, in their chunk
        }
        String id = admin.getUUID().toString();
        long[] since = new long[1];
        AtomicLong t = new AtomicLong(1_000_000L);
        LagNetwork.useForTests((p, packet) -> {
        }, () -> t.addAndGet(1_000)); // the PROFILE answers to the test players go nowhere; each request a second after the last
        LagProfiler.useForTests(100, null);
        h.startSequence()
                .thenExecute(() -> {
                    since[0] = logged(server, id, "sampled");
                    h.assertTrue(!LagProfiler.request(player, new LagSamplePacket("", LagSamplePacket.Action.START, 10)) && !LagProfiler.runningForTests(),
                            "a player who may not edit starts nothing");
                    h.assertTrue(!LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.START, 15)), "a length not offered is refused");
                    h.assertTrue(LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.START, 10)) && LagProfiler.runningForTests(), "an editor starts one");
                    h.assertTrue(!LagProfiler.request(admin, new LagSamplePacket(Storage.currentServerId(), LagSamplePacket.Action.START, 30)), "a second start is refused while one runs");
                    LagResult.Profile p = LagProfiler.profile();
                    h.assertTrue(p.running() != null && p.running().by().equals(admin.getGameProfile().getName()) && p.running().ticksAsked() == 100
                            && p.running().chunks().isEmpty(), "while it runs PROFILE says who, how far and nothing more: " + p.running());
                })
                // a chunk's own tick needs a player near (none in a GameTest), so only the timings driven by hand test it
                .thenWaitUntil(() -> h.assertTrue(LagProfiler.timingForTests()
                        && LagTimer.hasForTests(level, c, LagResult.Kind.BLOCK_ENTITY, BlockEntityType.HOPPER)
                        && LagTimer.hasForTests(level, c, LagResult.Kind.ENTITY, EntityType.ARMOR_STAND), "the test chunk's hoppers and armour stands are timed: timing "
                        + LagProfiler.timingForTests() + ", hoppers " + LagTimer.hasForTests(level, c, LagResult.Kind.BLOCK_ENTITY, BlockEntityType.HOPPER)
                        + ", armour stands " + LagTimer.hasForTests(level, c, LagResult.Kind.ENTITY, EntityType.ARMOR_STAND) + ", running " + LagProfiler.runningForTests()))
                .thenWaitUntil(() -> h.assertTrue(!LagProfiler.runningForTests(), "the sample ends by itself"))
                .thenExecute(() -> {
                    LagResult r = LagProfiler.profile().last();
                    h.assertTrue(r != null && !r.running() && r.ticks() == 100 && r.lengthS() == 10 && r.stoppedBy().isEmpty(), "it ran its 100 ticks and is kept: " + r);
                    h.assertTrue(!r.chunks().isEmpty() && ranked(r) && r.chunks().size() <= LagResult.TOP_CHUNKS, "its chunks ranked by ms per tick");
                    h.assertTrue(r.tickMs() > 0 && r.outsideMs() >= 0 && r.blocks().size() <= LagResult.TOP_BLOCKS && !r.blocks().isEmpty(), "a tick, the part outside chunks, OUTLINE's blocks");
                    double sum = r.chunks().stream().mapToDouble(LagResult.Chunk::ms).sum();
                    h.assertTrue(sum <= r.tickMs() + 0.01 * r.chunks().size() + 0.01, "the chunks fit in the tick: " + sum + " of " + r.tickMs());
                    h.assertTrue(r.chunks().stream().allMatch(ch -> ch.entries().size() <= LagResult.TOP_ENTRIES), "each chunk's entries capped");
                    h.assertTrue(logged(server, id, "sampled 10 s on ") == since[0] + 1, "logged once as lag_sample by who started it");
                    h.assertTrue(LagTimer.chunksForTests() == 0 && !LagTimer.on, "and the timings are cleared");
                    h.assertTrue(!LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.STOP, 0)), "STOP with nothing running does nothing");
                    LagProfiler.useForTests(20_000, null);
                    h.assertTrue(LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.START, 60)), "a longer one starts");
                    h.assertTrue(LagProfiler.profile().last() == r, "the last result stays while the next one runs");
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    h.assertTrue(LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.STOP, 0)) && !LagProfiler.runningForTests(), "STOP ends it");
                    LagResult r = LagProfiler.profile().last();
                    h.assertTrue(r != null && r.ticks() > 0 && r.ticks() < 20_000 && r.lengthS() == 60 && r.stoppedBy().equals(admin.getGameProfile().getName()),
                            "with what it had: " + r);
                    h.assertTrue(logged(server, id, ", stopped by " + admin.getGameProfile().getName()) >= 1, "logged with who stopped it");
                })
                .thenExecute(() -> {
                    LagProfiler.resetForTests();
                    server.getPlayerList().getOps().remove(admin.getGameProfile());
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "lag-profiler-storage")
    public static void samplesPassedOnAndAnsweredAcrossServers(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = editor(h);
        List<LagAnswerPacket> got = new ArrayList<>();
        LagNetwork.useForTests((p, packet) -> {
            if (p == admin) {
                got.add(packet);
            }
        }, null);
        LagProfiler.useForTests(20_000, null);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        LagProfiler.Starter beta = starter("beta_admin");
        String by = String.join("|", beta.uuid(), beta.name(), beta.roleGroup(), beta.roleLabel(), Integer.toString(beta.colour()));
        try {
            h.assertTrue(LagProfiler.request(admin, new LagSamplePacket("beta", LagSamplePacket.Action.START, 30)), "a start for another server is passed on");
            alpha.drain();
            h.assertTrue(!LagProfiler.runningForTests(), "and does not start here");
            List<StorageBackend.Notice> sent = notices(db, LagProfiler.KIND_SAMPLE);
            String[] f = sent.isEmpty() ? new String[0] : sent.get(sent.size() - 1).payload().split("\\|", -1);
            h.assertTrue(f.length == 9 && f[1].equals("beta") && f[2].equals("START") && f[3].equals("30") && f[4].equals(admin.getUUID().toString()),
                    "the notice names that server, the length and who: " + String.join("|", f));
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-1", System.currentTimeMillis(), "beta", LagNetwork.KIND_ANSWER, f[0] + "|beta|PROFILE\n" + LagResult.encodeProfile(new LagResult.Profile(
                    new LagResult("beta", admin.getGameProfile().getName(), 1L, 30, 600, 0, 0, 0, true, 30_001L, "", List.of(), List.of()), null))));
            h.assertTrue(got.size() == 1 && got.get(0).kind() == LagAskPacket.Kind.PROFILE && got.get(0).server().equals("beta")
                    && LagResult.decodeProfile(got.get(0).payload()).running() != null, "that server's PROFILE is passed to the editor");

            alpha.deliverForTests(new StorageBackend.Notice(0, "n-2", System.currentTimeMillis(), "beta", LagProfiler.KIND_SAMPLE, "q-1|gamma|START|30|" + by));
            h.assertTrue(!LagProfiler.runningForTests(), "a notice naming another server starts nothing here");
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-3", System.currentTimeMillis(), "beta", LagProfiler.KIND_SAMPLE, "q-2|alpha|START|30|" + by));
            alpha.drain();
            h.assertTrue(LagProfiler.runningForTests(), "a notice naming this server starts here");
            StorageBackend.Notice answer = notices(db, LagNetwork.KIND_ANSWER).stream().filter(n -> n.payload().startsWith("q-2|")).findFirst().orElse(null);
            h.assertTrue(answer != null && answer.payload().startsWith("q-2|alpha|PROFILE\n"), "and this server's PROFILE goes back");
            LagResult.Profile running = LagResult.decodeProfile(answer.payload().substring(answer.payload().indexOf('\n') + 1));
            h.assertTrue(running.running() != null && running.running().by().equals("beta_admin") && running.running().server().equals("alpha"), "saying who runs it: " + running.running());

            alpha.deliverForTests(new StorageBackend.Notice(0, "n-4", System.currentTimeMillis(), "beta", LagProfiler.KIND_SAMPLE, "q-3|alpha|START|10|" + by));
            alpha.drain();
            h.assertTrue(notices(db, LagProfiler.KIND_SAID).stream().anyMatch(n -> n.payload().startsWith(beta.uuid() + "|sample_running|alpha|beta_admin|")),
                    "a second start is refused, and its asker told who runs it and the time left, on their server");

            alpha.deliverForTests(new StorageBackend.Notice(0, "n-5", System.currentTimeMillis(), "beta", LagNetwork.KIND_ASK, "q-4|alpha|PROFILE||0|0"));
            alpha.drain();
            StorageBackend.Notice asked = notices(db, LagNetwork.KIND_ANSWER).stream().filter(n -> n.payload().startsWith("q-4|")).findFirst().orElse(null);
            h.assertTrue(asked != null && asked.payload().startsWith("q-4|alpha|PROFILE\n")
                    && LagResult.decodeProfile(asked.payload().substring(asked.payload().indexOf('\n') + 1)).running() != null, "a PROFILE ask naming this server is answered");

            long before = logged(server, beta.uuid(), "sampled");
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-6", System.currentTimeMillis(), "beta", LagProfiler.KIND_SAMPLE, "q-5|alpha|STOP|0|" + by));
            h.assertTrue(!LagProfiler.runningForTests(), "a STOP naming this server stops it");
            LagResult last = LagProfiler.profile().last();
            h.assertTrue(last != null && last.server().equals("alpha") && last.by().equals("beta_admin") && last.stoppedBy().equals("beta_admin"), "and keeps what it had: " + last);
            h.assertTrue(logged(server, beta.uuid(), "sampled 0 of 30 s on alpha, stopped by beta_admin") == before + 1, "logged once on this server by who started it");
        } finally {
            if (LagProfiler.runningForTests()) {
                LagProfiler.stop(server, beta);
            }
            Storage.useForTests(null);
            alpha.close();
            LagNetwork.useForTests(null, null);
            LagProfiler.useForTests(0, null);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void topKeepsTheBestWithoutSortingAll(GameTestHelper h) {
        LagProfiler.resetForTests();
        Random rnd = new Random(153);
        List<Long> all = new ArrayList<>();
        LagProfiler.Top<Long> top = new LagProfiler.Top<>(10, java.util.Comparator.<Long>reverseOrder());
        for (int i = 0; i < 20_000; i++) {
            long v = rnd.nextInt(1_000_000);
            all.add(v);
            top.offer(v);
        }
        all.sort(java.util.Comparator.reverseOrder());
        h.assertTrue(top.sorted().equals(all.subList(0, 10)), "the ten biggest, biggest first: " + top.sorted());
        LagProfiler.Top<Long> few = new LagProfiler.Top<>(10, java.util.Comparator.<Long>reverseOrder());
        few.offer(3L);
        few.offer(9L);
        h.assertTrue(few.sorted().equals(List.of(9L, 3L)), "fewer than ten: all of them");
        LagProfiler.Top<Long> none = new LagProfiler.Top<>(0, java.util.Comparator.<Long>reverseOrder());
        none.offer(1L);
        h.assertTrue(none.sorted().isEmpty(), "none kept of none asked");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void startAndStopCloserThanTheGapAreDropped(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = editor(h);
        List<LagAnswerPacket> got = new ArrayList<>();
        AtomicLong now = new AtomicLong(5_000_000L);
        LagNetwork.useForTests((p, packet) -> {
            if (p == admin) {
                got.add(packet);
            }
        }, now::get);
        try {
            // nothing runs and the length is not offered, so no sample starts: each request heard is answered with PROFILE
            LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.STOP, 0));
            h.assertTrue(got.size() == 1, "a STOP is heard");
            LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.STOP, 0));
            h.assertTrue(got.size() == 1, "a second STOP within the gap is dropped");
            LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.START, 15));
            h.assertTrue(got.size() == 2 && !LagProfiler.runningForTests(), "a START is its own kind, heard");
            LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.START, 15));
            h.assertTrue(got.size() == 2, "a second START within the gap is dropped");
            now.addAndGet(300);
            LagProfiler.request(admin, new LagSamplePacket("", LagSamplePacket.Action.STOP, 0));
            h.assertTrue(got.size() == 3, "and heard again after the gap");
        } finally {
            LagProfiler.resetForTests();
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "lag-profiler-stopping")
    public static void sampleCutByTheServerStoppingIsLoggedOnce(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        LagProfiler.Starter by = starter("stop_t");
        try {
            LagProfiler.useForTests(20_000, null);
            h.assertTrue(LagProfiler.start(server, by, 30) == LagProfiler.Outcome.STARTED, "a sample starts");
            // what the server's stopping hook calls (a GameTest cannot stop the server)
            LagProfiler.serverStopping(server);
            h.assertTrue(!LagProfiler.runningForTests() && !LagTimer.on && LagTimer.chunksForTests() == 0, "it ends, and the timings are cleared");
            h.assertTrue(logged(server, by.uuid(), "sampled 0 of 30 s on ") == 1 && logged(server, by.uuid(), "cut short by the server stopping") == 1,
                    "logged once by who started it, as cut short by the server stopping");
            LagProfiler.serverStopping(server);
            h.assertTrue(logged(server, by.uuid(), "sampled") == 1, "a second stop logs nothing more");
            h.assertTrue(LagProfiler.profile().running() == null && LagProfiler.profile().last() == null, "nothing is kept past the stop");
        } finally {
            LagProfiler.resetForTests();
        }
        h.succeed();
    }

    private static TickingBlockEntity ticker(BlockPos pos) {
        return new TickingBlockEntity() {
            @Override
            public void tick() {
            }

            @Override
            public boolean isRemoved() {
                return false;
            }

            @Override
            public BlockPos getPos() {
                return pos;
            }

            @Override
            public String getType() {
                return "minecraft:hopper";
            }
        };
    }

    @GameTest(template = EMPTY)
    public static void nestedBlockEntityLevelsRestoreTheOuter(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel outer = h.getLevel();
        ServerLevel inner = null;
        for (ServerLevel l : outer.getServer().getAllLevels()) {
            if (l != outer) {
                inner = l;
                break;
            }
        }
        h.assertTrue(inner != null, "the test server has a second world");
        ChunkPos a = far(h, 480), b = far(h, 481), c = far(h, 482);
        // the timings are driven by hand through the hooks the mixins call, inside this one call
        LagProfiler.beginForTests();
        try {
            LagTimer.blockEntitiesStart(outer);
            LagTimer.blockEntitiesStart(inner); // a mod ticking another world's block entities from inside one
            LagTimer.blockEntityStart();
            LagTimer.blockEntityEnd(ticker(new BlockPos(a.getMinBlockX(), 70, a.getMinBlockZ())));
            LagTimer.blockEntitiesEnd();
            LagTimer.blockEntityStart();
            LagTimer.blockEntityEnd(ticker(new BlockPos(b.getMinBlockX(), 70, b.getMinBlockZ())));
            LagTimer.blockEntitiesEnd();
            LagTimer.blockEntityStart();
            LagTimer.blockEntityEnd(ticker(new BlockPos(c.getMinBlockX(), 70, c.getMinBlockZ())));
            h.assertTrue(LagTimer.hasForTests(inner, a, LagResult.Kind.BLOCK_ENTITY, null) && !LagTimer.hasForTests(outer, a, LagResult.Kind.BLOCK_ENTITY, null),
                    "the nested tick is the inner world's");
            h.assertTrue(LagTimer.hasForTests(outer, b, LagResult.Kind.BLOCK_ENTITY, BlockEntityType.HOPPER) && !LagTimer.hasForTests(inner, b, LagResult.Kind.BLOCK_ENTITY, null),
                    "once it returns, the outer world's again (not none)");
            h.assertTrue(!LagTimer.hasForTests(outer, c, LagResult.Kind.BLOCK_ENTITY, null) && !LagTimer.hasForTests(inner, c, LagResult.Kind.BLOCK_ENTITY, null),
                    "and once the outer returns, no world: nothing is timed");
            LagTimer.blockEntitiesEnd();
            LagTimer.blockEntityStart();
            LagTimer.blockEntityEnd(ticker(new BlockPos(c.getMinBlockX(), 70, c.getMinBlockZ())));
            h.assertTrue(!LagTimer.hasForTests(outer, c, LagResult.Kind.BLOCK_ENTITY, null), "a return with none open leaves no world");
        } finally {
            LagProfiler.resetForTests();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void spotsAtOnePlaceAndTimeKeepOneOrder(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos c = far(h, 490);
        BlockPos both = new BlockPos(c.getMinBlockX() + 2, 70, c.getMinBlockZ() + 2), two = both.east(3);
        List<String> first = new ArrayList<>(), second = new ArrayList<>();
        for (int order = 0; order < 2; order++) {
            LagResult r;
            LagProfiler.beginForTests();
            try {
                if (order == 0) {
                    LagTimer.recordBlockEntity(level, both, BlockEntityType.HOPPER, 3 * MS);
                    LagTimer.recordScheduled(level, both.asLong(), Blocks.REPEATER, 3 * MS);
                    LagTimer.recordBlockEntity(level, two, BlockEntityType.HOPPER, 2 * MS);
                    LagTimer.recordBlockEntity(level, two, BlockEntityType.CHEST, 2 * MS);
                } else {
                    LagTimer.recordBlockEntity(level, two, BlockEntityType.CHEST, 2 * MS);
                    LagTimer.recordBlockEntity(level, two, BlockEntityType.HOPPER, 2 * MS);
                    LagTimer.recordScheduled(level, both.asLong(), Blocks.REPEATER, 3 * MS);
                    LagTimer.recordBlockEntity(level, both, BlockEntityType.HOPPER, 3 * MS);
                }
            } finally {
                r = LagProfiler.resultForTests(server, starter("tie_t"), 20, 20 * 50 * MS);
            }
            List<String> into = order == 0 ? first : second;
            for (LagResult.Block b : r.blocks()) {
                into.add(b.x() + "," + b.y() + "," + b.z() + "=" + b.label());
            }
        }
        h.assertTrue(first.size() == 2 && first.equals(second), "one block a place, in one order whatever came first: " + first + " / " + second);
        h.assertTrue(first.get(0).endsWith("=minecraft:hopper"), "a block entity before a scheduled tick at one place and time: " + first);
        h.assertTrue(first.get(1).endsWith("=minecraft:chest"), "and two types at one place and time by label: " + first);
        h.succeed();
    }
}
