package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.lag.LagNetwork;
import com.mrgregles.bsp_server_utilities.lag.LagProfiler;
import com.mrgregles.bsp_server_utilities.lag.LagSnapshot;
import com.mrgregles.bsp_server_utilities.network.LagAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.LagAskPacket;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The LAG tab's loaded chunks (SU-149): a world's summary and map carry a chunk a mod's ticket holds, with the reason;
 * a chunk's detail counts its entities and block entities by kind and never loads a chunk; the text forms read back the
 * same; an editor's question is answered here at once and asked of the other servers, another server's question naming
 * this one is answered with a notice, and an answer is passed to the editor who asked while the question is open; a
 * player who may not edit gets nothing, and asks closer together than the gap are dropped. Audit of SU-153: an answer
 * between servers stays under its cap, a map keeping the chunks nearest the middle asked for, and names its world; a
 * question more than a minute old is not answered; a server name with {@code |} in it is the same server on both sides;
 * one chunk's reason is the census's for it. Review of the audit fixes: the server's stop drops the summaries kept for
 * asks, the open questions and the ask gaps (where answers go and the clock stay); a map ask naming the busiest chunk as
 * its middle keeps the rows nearest it; a question is answered only while it is open where it was asked (a START or STOP
 * for a minute); a player's ask gaps go as they log out; a summary hands out a copy of its counts by reason. Every test
 * starts with the LAG test hooks reset.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class LagGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String OVERWORLD = "minecraft:overworld";

    private static ChunkPos far(GameTestHelper h, int east) {
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(base.x + east, base.z);
    }

    private static void hold(ServerLevel level, ChunkPos c, boolean add) {
        ForgeChunkManager.forceChunk(level, BSPServerUtilities.MODID, new BlockPos(c.getMiddleBlockX(), 0, c.getMiddleBlockZ()), c.x, c.z, add, false);
    }

    private static List<StorageBackend.Notice> notices(MemoryBackend db) {
        try {
            return db.fetchNoticesAfter(0, 500);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @GameTest(template = EMPTY, timeoutTicks = 800)
    public static void summaryAndMapCarryALoadedChunk(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos c = far(h, 300);
        hold(level, c, false); // a ticket left by a run that timed out
        hold(level, c, true);
        h.startSequence()
                // the chunk map lists a chunk as loaded a tick or so after getChunkNow can see it
                .thenWaitUntil(() -> h.assertTrue(LagSnapshot.map(server, OVERWORLD).stream().anyMatch(r -> r.x() == c.x && r.z() == c.z), "the chunk is loaded"))
                .thenExecute(() -> {
                    try {
                        List<LagSnapshot.Summary> sums = LagSnapshot.summaries(server);
                        LagSnapshot.Summary over = sums.stream().filter(s -> s.dimension().equals(OVERWORLD)).findFirst().orElseThrow();
                        h.assertTrue(over.chunks() > 0 && over.byWhy()[ChunkCensus.Why.TICKET.ordinal()] >= 1, "the overworld's summary counts the ticket's chunk: " + java.util.Arrays.toString(over.byWhy())
                                + " row " + LagSnapshot.map(server, OVERWORLD).stream().filter(r -> r.x() == c.x && r.z() == c.z).findFirst().orElse(null)
                                + " tickets " + com.mrgregles.bsp_server_utilities.chunks.ChunkTickets.live(level).size());
                        h.assertTrue(java.util.Arrays.stream(over.byWhy()).sum() == over.chunks(), "every chunk has one reason");
                        h.assertTrue(over.rows().size() <= LagSnapshot.LIST_ROWS, "the list is capped");
                        h.assertTrue(LagSnapshot.decodeSummaries(LagSnapshot.encodeSummaries(sums)).equals(sums), "the summaries read back the same");
                        List<LagSnapshot.MapRow> map = LagSnapshot.map(server, OVERWORLD);
                        LagSnapshot.MapRow row = map.stream().filter(r -> r.x() == c.x && r.z() == c.z).findFirst().orElseThrow();
                        h.assertTrue(row.why() == ChunkCensus.Why.TICKET && row.ticking(), "the map has the chunk, put down to the ticket, ticking");
                        h.assertTrue(map.size() == over.chunks(), "the map has every loaded chunk of the world");
                        h.assertTrue(LagSnapshot.decodeMap(LagSnapshot.encodeMap(OVERWORLD, map)).equals(new LagSnapshot.MapAnswer(OVERWORLD, map)), "the map reads back the same, with its world");
                        ChunkCensus.Reasons reasons = ChunkCensus.reasons(level);
                        for (ChunkCensus.Row r : ChunkCensus.of(level).rows()) {
                            ChunkCensus.Reason one = reasons.at(r.pos().x, r.pos().z);
                            h.assertTrue(one.why() == r.why() && one.who().equals(r.who()), "one chunk's reason is the census's: " + r + " vs " + one);
                        }
                        ChunkCensus.Reason held = ChunkCensus.reason(level, c.x, c.z);
                        h.assertTrue(held.why() == ChunkCensus.Why.TICKET && held.who().equals(BSPServerUtilities.MODID), "the held chunk is put down to its mod's ticket: " + held);
                        LagSnapshot.Detail d = LagSnapshot.detail(server, OVERWORLD, c.x, c.z);
                        h.assertTrue(d.why() == row.why(), "and the chunk's detail says the same as the map");
                        h.assertTrue(LagSnapshot.map(server, "minecraft:nowhere").isEmpty(), "a world not on this server has no map");
                    } finally {
                        hold(level, c, false);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void detailCountsKindsAndNeverLoads(GameTestHelper h) {
        LagProfiler.resetForTests();
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos rel = new BlockPos(4, 2, 4);
        ChunkPos c = new ChunkPos(h.absolutePos(rel));
        h.setBlock(rel, Blocks.CHEST);
        h.setBlock(rel.above(), Blocks.CHEST); // one column, so one chunk
        for (int i = 0; i < 3; i++) {
            h.spawn(EntityType.ARMOR_STAND, rel.above(2));
        }
        LagSnapshot.Detail d = LagSnapshot.detail(server, OVERWORLD, c.x, c.z);
        h.assertTrue(d.loaded(), "the test's chunk is loaded");
        h.assertTrue(d.entities().stream().anyMatch(t -> t.type().equals("minecraft:armor_stand") && t.count() >= 3), "the armour stands by kind: " + d.entities());
        h.assertTrue(d.blockEntities().stream().anyMatch(t -> t.type().equals("minecraft:chest") && t.count() >= 2), "the chests by kind: " + d.blockEntities());
        h.assertTrue(d.entities().size() <= LagSnapshot.DETAIL_TYPES && d.blockEntities().size() <= LagSnapshot.DETAIL_TYPES, "the kinds are capped");
        h.assertTrue(d.why() != ChunkCensus.Why.OTHER, "with why it is loaded");
        h.assertTrue(d.equals(LagSnapshot.decodeDetail(LagSnapshot.encodeDetail(d))), "the detail reads back the same");
        ChunkPos away = far(h, 90_000);
        LagSnapshot.Detail none = LagSnapshot.detail(server, OVERWORLD, away.x, away.z);
        h.assertTrue(!none.loaded() && none.entities().isEmpty(), "a chunk not loaded is said so");
        h.assertTrue(level.getChunkSource().getChunkNow(away.x, away.z) == null, "and asking did not load it");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void questionsAnsweredHereAskedThereAndRelayed(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h), player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        List<LagAnswerPacket> got = new ArrayList<>();
        AtomicLong now = new AtomicLong(1_000_000L);
        LagNetwork.useForTests((p, packet) -> {
            if (p == admin) {
                got.add(packet);
            }
        }, now::get);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            LagNetwork.ask(player, LagAskPacket.summaries());
            h.assertTrue(got.isEmpty() && LagNetwork.openForTests() == 0, "a player who may not edit gets nothing and asks nothing");

            LagNetwork.ask(admin, LagAskPacket.summaries());
            alpha.drain();
            h.assertTrue(got.size() == 1 && got.get(0).kind() == LagAskPacket.Kind.SUMMARY && got.get(0).server().equals("alpha")
                    && !LagSnapshot.decodeSummaries(got.get(0).payload()).isEmpty(), "this server's summaries at once");
            StorageBackend.Notice ask = notices(db).stream().filter(n -> n.kind().equals(LagNetwork.KIND_ASK)).reduce((a, b) -> b).orElseThrow();
            String[] f = ask.payload().split("\\|", -1);
            h.assertTrue(f.length == 6 && f[1].isEmpty() && f[2].equals("SUMMARY"), "and every other server asked: " + ask.payload());

            LagNetwork.ask(admin, LagAskPacket.summaries());
            h.assertTrue(got.size() == 1, "an ask closer than the gap is dropped");

            String beta = "overworld_b|4|4|10|2|4,0,0,0,0;1,2,0,7,1,tilly_t";
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-1", now.get(), "beta", LagNetwork.KIND_ANSWER, f[0] + "|beta|SUMMARY\n" + beta));
            h.assertTrue(got.size() == 2 && got.get(1).server().equals("beta") && got.get(1).payload().equals(beta), "another server's answer passed on");
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-2", now.get(), "gamma", LagNetwork.KIND_ANSWER, f[0] + "|gamma|SUMMARY\n"));
            h.assertTrue(got.size() == 3 && got.get(2).server().equals("gamma"), "the summaries stay open for every server's answer");

            now.addAndGet(20_000L);
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-3", now.get(), "delta", LagNetwork.KIND_ANSWER, f[0] + "|delta|SUMMARY\n"));
            h.assertTrue(got.size() == 3, "an answer after the question closed is dropped");

            LagNetwork.ask(admin, new LagAskPacket(LagAskPacket.Kind.MAP, "beta", OVERWORLD, 0, 0));
            alpha.drain();
            h.assertTrue(got.size() == 3, "a map of another server is not answered here");
            StorageBackend.Notice mapAsk = notices(db).stream().filter(n -> n.kind().equals(LagNetwork.KIND_ASK)).reduce((a, b) -> b).orElseThrow();
            String[] g = mapAsk.payload().split("\\|", -1);
            h.assertTrue(g[1].equals("beta") && g[2].equals("MAP"), "it is asked of that server only");

            int before = notices(db).size();
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-4", now.get(), "beta", LagNetwork.KIND_ASK, "q-1|gamma|DETAIL|" + OVERWORLD + "|0|0"));
            alpha.drain();
            h.assertTrue(notices(db).size() == before, "a question naming another server is not answered");
            alpha.deliverForTests(new StorageBackend.Notice(0, "n-5", now.get(), "beta", LagNetwork.KIND_ASK, "q-2|alpha|MAP|" + OVERWORLD + "|0|0"));
            alpha.drain();
            StorageBackend.Notice answer = notices(db).stream().filter(n -> n.kind().equals(LagNetwork.KIND_ANSWER)).reduce((a, b) -> b).orElseThrow();
            LagSnapshot.MapAnswer m = LagSnapshot.decodeMap(answer.payload().substring(answer.payload().indexOf('\n') + 1));
            h.assertTrue(answer.payload().startsWith("q-2|alpha|MAP\n") && !m.rows().isEmpty() && m.dimension().equals(OVERWORLD),
                    "a question naming this server is answered with its map, naming the world");
            h.assertTrue(answer.payload().length() <= LagNetwork.NOTICE_MAX, "under the cap between servers");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            LagNetwork.useForTests(null, null);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void mapCutKeepsTheNearestAndNamesItsWorld(GameTestHelper h) {
        LagProfiler.resetForTests();
        List<LagSnapshot.MapRow> rows = new ArrayList<>();
        for (int x = -120; x < 120; x++) {
            for (int z = -120; z < 120; z++) {
                rows.add(new LagSnapshot.MapRow(x * 7, z * 7, ChunkCensus.Why.values()[Math.floorMod(x + z, 5)], 1000 + x, 2000 + z, (x & 1) == 0));
            }
        }
        String full = LagSnapshot.encodeMap(OVERWORLD, rows);
        h.assertTrue(full.length() > LagNetwork.NOTICE_MAX, "the test's map is over the cap between servers: " + full.length());
        h.assertTrue(LagSnapshot.decodeMap(full).equals(new LagSnapshot.MapAnswer(OVERWORLD, rows)), "in full it reads back the same, with its world");
        int cx = 300, cz = -200;
        String cut = LagSnapshot.encodeMap(OVERWORLD, rows, LagNetwork.NOTICE_MAX, cx, cz);
        LagSnapshot.MapAnswer back = LagSnapshot.decodeMap(cut);
        h.assertTrue(cut.length() <= LagNetwork.NOTICE_MAX && back.dimension().equals(OVERWORLD), "cut to the cap, still naming its world: " + cut.length());
        h.assertTrue(!back.rows().isEmpty() && back.rows().size() < rows.size() && rows.containsAll(back.rows()), "by whole rows, each as it was");
        long farthestKept = 0;
        for (LagSnapshot.MapRow r : back.rows()) {
            farthestKept = Math.max(farthestKept, (long) (r.x() - cx) * (r.x() - cx) + (long) (r.z() - cz) * (r.z() - cz));
        }
        java.util.Set<LagSnapshot.MapRow> kept = new java.util.HashSet<>(back.rows());
        boolean nearestKept = true;
        for (LagSnapshot.MapRow r : rows) {
            if (!kept.contains(r) && (long) (r.x() - cx) * (r.x() - cx) + (long) (r.z() - cz) * (r.z() - cz) < farthestKept) {
                nearestKept = false;
                break;
            }
        }
        h.assertTrue(nearestKept, "the rows kept are the nearest the middle asked for");
        h.assertTrue(LagSnapshot.encodeMap(OVERWORLD, rows, LagAnswerPacket.MAX_PAYLOAD, cx, cz).length() <= LagAnswerPacket.MAX_PAYLOAD, "an answer here keeps the packet's own limit");
        h.assertTrue(LagSnapshot.encodeMap(OVERWORLD, rows, LagAnswerPacket.MAX_PAYLOAD, cx, cz).length() > LagNetwork.NOTICE_MAX, "which is more than goes between servers");
        LagSnapshot.MapAnswer none = LagSnapshot.decodeMap(LagSnapshot.encodeMap("minecraft:the_nether", List.of()));
        h.assertTrue(none.dimension().equals("minecraft:the_nether") && none.rows().isEmpty(), "an empty map still names its world");
        h.assertTrue(LagSnapshot.decodeMap("1,2,0,3,4,1").dimension().isEmpty(), "an answer without the head names no world (the screen leaves it)");
        MinecraftServer server = h.getLevel().getServer();
        String small = LagNetwork.payloadForTests(server, LagAskPacket.Kind.MAP, OVERWORLD, 0, 0, 200);
        h.assertTrue(small.length() <= 200 && LagSnapshot.decodeMap(small).dimension().equals(OVERWORLD), "this server's own map cut to a budget: " + small.length());
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void lateQuestionsAndSamplesAreDropped(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            long now = System.currentTimeMillis();
            int before = notices(db).size();
            alpha.deliverForTests(new StorageBackend.Notice(0, "late-1", now - LagNetwork.LATE_MS - 1_000, "beta", LagNetwork.KIND_ASK, "q-1|alpha|MAP|" + OVERWORLD + "|0|0"));
            alpha.deliverForTests(new StorageBackend.Notice(0, "late-2", now - LagNetwork.LATE_MS - 1_000, "beta", LagNetwork.KIND_ASK, "q-2||SUMMARY||0|0"));
            String by = String.join("|", UUID.randomUUID().toString(), "beta_admin", "admin", "Admin", "0");
            alpha.deliverForTests(new StorageBackend.Notice(0, "late-3", now - LagNetwork.LATE_MS - 1_000, "beta", LagProfiler.KIND_SAMPLE, "q-3|alpha|STOP|0|" + by));
            alpha.drain();
            h.assertTrue(notices(db).size() == before, "a question or a STOP over a minute old is not answered: " + (notices(db).size() - before));
            alpha.deliverForTests(new StorageBackend.Notice(0, "fresh-1", now - 5_000, "beta", LagNetwork.KIND_ASK, "q-4|alpha|MAP|" + OVERWORLD + "|0|0"));
            alpha.deliverForTests(new StorageBackend.Notice(0, "fresh-2", now - 5_000, "beta", LagProfiler.KIND_SAMPLE, "q-5|alpha|STOP|0|" + by));
            alpha.drain();
            h.assertTrue(notices(db).stream().anyMatch(n -> n.payload().startsWith("q-4|alpha|MAP\n")), "a question seconds old is answered");
            h.assertTrue(notices(db).stream().anyMatch(n -> n.payload().startsWith("q-5|alpha|PROFILE\n")), "and a STOP seconds old is acted on");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            LagProfiler.resetForTests();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void serverNamesWithABarMatchOnBothSides(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        List<LagAnswerPacket> got = new ArrayList<>();
        LagNetwork.useForTests((p, packet) -> {
            if (p == admin) {
                got.add(packet);
            }
        }, null);
        MemoryBackend db = new MemoryBackend();
        Storage odd = Storage.startWith(server, db, "al|pha");
        odd.drain();
        Storage.useForTests(odd);
        try {
            h.assertTrue(LagNetwork.sameServer("al|pha", "al/pha") && !LagNetwork.sameServer("alpha", "al/pha"), "names compared as the notices write them");
            long now = System.currentTimeMillis();
            odd.deliverForTests(new StorageBackend.Notice(0, "bar-1", now, "beta", LagNetwork.KIND_ASK, "q-1|al/pha|MAP|" + OVERWORLD + "|0|0"));
            odd.drain();
            h.assertTrue(notices(db).stream().anyMatch(n -> n.payload().startsWith("q-1|al/pha|MAP\n")), "a question naming the cleaned name is answered here");
            String by = String.join("|", UUID.randomUUID().toString(), "beta_admin", "admin", "Admin", "0");
            odd.deliverForTests(new StorageBackend.Notice(0, "bar-2", now, "beta", LagProfiler.KIND_SAMPLE, "q-2|al/pha|STOP|0|" + by));
            odd.drain();
            h.assertTrue(notices(db).stream().anyMatch(n -> n.payload().startsWith("q-2|al/pha|PROFILE\n")), "and so is a STOP naming it");
            LagNetwork.ask(admin, new LagAskPacket(LagAskPacket.Kind.MAP, "al/pha", OVERWORLD, 0, 0));
            h.assertTrue(got.size() == 1 && got.get(0).kind() == LagAskPacket.Kind.MAP, "an editor here asking for the cleaned name is answered at once, not asked of the others");
        } finally {
            Storage.useForTests(null);
            odd.close();
            LagProfiler.resetForTests();
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, batch = "lag-stop-clears")
    public static void stoppingClearsTheCacheAndOpenQuestions(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        List<LagAnswerPacket> got = new ArrayList<>();
        AtomicLong now = new AtomicLong(7_000_000L);
        LagNetwork.useForTests((p, packet) -> {
            if (p == admin) {
                got.add(packet);
            }
        }, now::get);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            LagSnapshot.summariesForAsk(server);
            LagNetwork.ask(admin, new LagAskPacket(LagAskPacket.Kind.MAP, "beta", OVERWORLD, 0, 0));
            h.assertTrue(LagSnapshot.cachedForTests() && LagNetwork.openForTests() == 1 && LagNetwork.askersForTests() == 1,
                    "a census kept for asks, a question open and an ask gap kept before the stop");
            // what the server's stopping hook calls (a GameTest cannot stop the server)
            LagProfiler.serverStopping(server);
            h.assertTrue(!LagSnapshot.cachedForTests(), "the summaries kept for asks (which hold the server) are dropped");
            h.assertTrue(LagNetwork.openForTests() == 0 && LagNetwork.askersForTests() == 0, "and the open questions and ask gaps (which hold its players)");
            LagNetwork.ask(admin, LagAskPacket.summaries());
            h.assertTrue(got.size() == 1 && got.get(0).kind() == LagAskPacket.Kind.SUMMARY, "where answers go is kept");
            LagNetwork.ask(admin, LagAskPacket.summaries());
            h.assertTrue(got.size() == 1, "an ask within the gap is dropped");
            now.addAndGet(300);
            LagNetwork.ask(admin, LagAskPacket.summaries());
            h.assertTrue(got.size() == 2, "and heard 300 ms on by the test's clock, which is kept too");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            LagProfiler.resetForTests();
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void busiestFlagCutKeepsTheRowsNearestTheBusiest(GameTestHelper h) {
        LagProfiler.resetForTests();
        List<LagSnapshot.MapRow> rows = new ArrayList<>();
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 200; z++) {
                rows.add(new LagSnapshot.MapRow(x, z, ChunkCensus.Why.PLAYER, 1, 0, true));
            }
        }
        LagSnapshot.MapRow top = new LagSnapshot.MapRow(170, 180, ChunkCensus.Why.TICKET, 400, 9, true);
        rows.set(170 * 200 + 180, top);
        h.assertTrue(LagSnapshot.busiest(rows) == top && LagSnapshot.busiest(List.of()) == null, "the busiest row is the one with most entities; none of none");
        int budget = 4_000;
        LagSnapshot.MapAnswer back = LagSnapshot.decodeMap(LagSnapshot.encodeMap(OVERWORLD, rows, budget, LagSnapshot.BUSIEST, LagSnapshot.BUSIEST));
        h.assertTrue(!back.rows().isEmpty() && back.rows().size() < rows.size() && back.rows().get(0).equals(top), "cut, the busiest chunk kept first: " + back.rows().size());
        long farthest = 0;
        for (LagSnapshot.MapRow r : back.rows()) {
            farthest = Math.max(farthest, (long) (r.x() - 170) * (r.x() - 170) + (long) (r.z() - 180) * (r.z() - 180));
        }
        h.assertTrue(farthest < 40 * 40, "every row kept is near the busiest chunk, not near 0, 0: " + farthest);
        h.assertTrue(back.rows().stream().noneMatch(r -> r.x() < 100 && r.z() < 100), "none from round the old middle");
        String asked = LagSnapshot.encodeMap(OVERWORLD, rows, budget, 170, 180);
        h.assertTrue(asked.equals(LagSnapshot.encodeMap(OVERWORLD, rows, budget, LagSnapshot.BUSIEST, LagSnapshot.BUSIEST)), "the same as asking for its place");
        MinecraftServer server = h.getLevel().getServer();
        String small = LagNetwork.payloadForTests(server, LagAskPacket.Kind.MAP, OVERWORLD, LagSnapshot.BUSIEST, LagSnapshot.BUSIEST, 200);
        h.assertTrue(small.length() <= 200 && LagSnapshot.decodeMap(small).dimension().equals(OVERWORLD), "this server's own map cut round its busiest chunk: " + small.length());
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            alpha.deliverForTests(new StorageBackend.Notice(0, "busy-1", System.currentTimeMillis(), "beta", LagNetwork.KIND_ASK,
                    "q-1|alpha|MAP|" + OVERWORLD + "|" + LagSnapshot.BUSIEST + "|" + LagSnapshot.BUSIEST));
            alpha.drain();
            h.assertTrue(notices(db).stream().anyMatch(n -> n.payload().startsWith("q-1|alpha|MAP\n" + OVERWORLD)), "the flag goes between servers and is answered");
        } finally {
            Storage.useForTests(null);
            alpha.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void questionsAreAnsweredOnlyWhileOpen(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            long now = System.currentTimeMillis();
            h.assertTrue(LagNetwork.ASK_LATE_MS == LagNetwork.OPEN_MS && LagNetwork.LATE_MS == 60_000L, "a question is answered while it is open; a START or STOP for a minute");
            alpha.deliverForTests(new StorageBackend.Notice(0, "open-1", now - 20_000, "beta", LagNetwork.KIND_ASK, "q-1|alpha|MAP|" + OVERWORLD + "|0|0"));
            alpha.deliverForTests(new StorageBackend.Notice(0, "open-2", now - 10_000, "beta", LagNetwork.KIND_ASK, "q-2|alpha|MAP|" + OVERWORLD + "|0|0"));
            String by = String.join("|", UUID.randomUUID().toString(), "beta_admin", "admin", "Admin", "0");
            alpha.deliverForTests(new StorageBackend.Notice(0, "open-3", now - 20_000, "beta", LagProfiler.KIND_SAMPLE, "q-3|alpha|STOP|0|" + by));
            alpha.drain();
            h.assertTrue(notices(db).stream().noneMatch(n -> n.payload().startsWith("q-1|")), "a question 20 s old (closed where it was asked) is not answered");
            h.assertTrue(notices(db).stream().anyMatch(n -> n.payload().startsWith("q-2|alpha|MAP\n")), "one 10 s old is answered");
            h.assertTrue(notices(db).stream().anyMatch(n -> n.payload().startsWith("q-3|alpha|PROFILE\n")), "and a STOP 20 s old is still acted on");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            LagProfiler.resetForTests();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void logoutDropsTheAskGapAndCountsAreCopies(GameTestHelper h) {
        LagProfiler.resetForTests();
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        LagNetwork.useForTests((p, packet) -> {
        }, null);
        try {
            LagNetwork.ask(admin, LagAskPacket.summaries());
            h.assertTrue(LagNetwork.askersForTests() == 1, "an editor's ask gap is kept");
            LagNetwork.loggedOut(admin.getUUID());
            h.assertTrue(LagNetwork.askersForTests() == 0, "and dropped as they log out");
            LagSnapshot.Summary one = new LagSnapshot.Summary(OVERWORLD, 15, 15, 0, 0, new int[]{1, 2, 3, 4, 5}, List.of());
            one.byWhy()[0] = 99;
            h.assertTrue(one.byWhy()[0] == 1, "a summary's counts by reason are handed out as a copy");
            List<LagSnapshot.Summary> kept = LagSnapshot.summariesForAsk(server);
            h.assertTrue(!kept.isEmpty(), "this server has a world with chunks");
            int was = kept.get(0).byWhy()[0];
            kept.get(0).byWhy()[0] = was + 1000;
            h.assertTrue(kept.get(0).byWhy()[0] == was, "so the ones kept for asks cannot be changed by a caller");
        } finally {
            LagProfiler.resetForTests();
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }
}
