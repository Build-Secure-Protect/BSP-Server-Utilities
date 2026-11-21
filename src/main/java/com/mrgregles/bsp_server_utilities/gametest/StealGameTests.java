package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.steals.StealRecorder;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Totem steals recorded for the staff web panel (SU-95, owner decisions D615 to D617, D619): BSP-Core's own steal events
 * posted on the bus become one row each in the shared table, with where, who, the tier and points, the start and end and
 * the seconds BSP-Core gave; a failed steal keeps its reason; another thief's start is never used; nothing is kept without
 * shared storage; a failed write is tried again within a minute; rows older than 90 days go; the real lookup reads a
 * placed Shatter Totem's tier and never answers for an empty block.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class StealGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private interface Body {
        void run(MinecraftServer server, MemoryBackend db, Storage s, AtomicLong now) throws Exception;
    }

    /** A shared storage on a memory table, a clock the test moves, and a clean recorder; all put back after. */
    private static void withStorage(GameTestHelper h, Body body) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        StealRecorder.useClockForTests(now::get);
        StealRecorder.resetForTests();
        Storage before = Storage.active();
        Storage.useForTests(s);
        try {
            body.run(server, db, s, now);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Storage.useForTests(before); // as it was found (audit)
            s.close();
            BspCore.useTotemInfoForTests(null);
            StealRecorder.useClockForTests(null);
            StealRecorder.resetForTests();
        }
        h.succeed();
    }

    private static GlobalPos place(GameTestHelper h, int x) {
        return GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(x, 2, 1)));
    }

    /** Posted on the bus as BSP-Core does: safe here, the test world's BSP-Core keeps its storage local ({@code storage.mode = "local"}), so nothing reaches a shared table or Discord. */
    private static void start(MinecraftServer server, GlobalPos where, UUID owner, UUID thief, String name, int seconds) {
        MinecraftForge.EVENT_BUS.post(new com.mrgregles.bsp_core.api.event.TotemEvent.StealStarted(server, where, owner, thief, name, seconds));
    }

    private static void end(MinecraftServer server, GlobalPos where, UUID owner, UUID thief, String name, boolean ok, String reason) {
        MinecraftForge.EVENT_BUS.post(new com.mrgregles.bsp_core.api.event.TotemEvent.StealEnded(server, where, owner, thief, name, ok, reason));
    }

    @GameTest(template = EMPTY)
    public void a_completed_steal_is_recorded_once_with_where_who_and_how_long(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            BspCore.useTotemInfoForTests((srv, where) -> new BspCore.TotemInfo(StealRecorder.SOURCE_TOTEM, 3, 4, "Wrenna"));
            AtomicLong tick = new AtomicLong(1000);
            StealRecorder.useTicksForTests(srv -> tick.get());
            GlobalPos where = place(h, 1);
            UUID owner = UUID.randomUUID(), thief = UUID.randomUUID();
            long startedAt = now.get();
            start(server, where, owner, thief, "Swiftfoot", 45);
            h.assertTrue(StealRecorder.startsWaiting() == 1, "the start is remembered until the end");
            now.addAndGet(31_000);
            tick.addAndGet(900); // 45 s of ticks in 31 s of clock: a server catching up after lag (audit of 0.16.0)
            end(server, where, owner, thief, "Swiftfoot", true, "");
            s.drain();
            List<StorageBackend.Steal> rows = db.loadSteals(0, 10);
            h.assertTrue(rows.size() == 1, "one row: " + rows);
            StorageBackend.Steal r = rows.get(0);
            h.assertTrue(r.server().equals("alpha") && r.dimension().equals("minecraft:overworld")
                    && r.x() == where.pos().getX() && r.y() == where.pos().getY() && r.z() == where.pos().getZ(), "where: " + r);
            h.assertTrue(r.thief().equals(thief) && r.thiefName().equals("Swiftfoot") && owner.equals(r.owner()) && r.ownerName().equals("Wrenna"), "who, the owner named by BSP-Core: " + r);
            h.assertTrue(r.ticks() == 900, "the server ticks it took, which BSP-Core counts in: " + r.ticks());
            h.assertTrue(r.source().equals("totem") && r.tier() == 3 && r.points() == 4, "what: " + r);
            h.assertTrue(r.startedMs() == startedAt && r.endedMs() == startedAt + 31_000 && r.expectedSeconds() == 45, "when, and the seconds given: " + r);
            h.assertTrue(r.success() && r.reason().isEmpty(), "completed, no reason");
            h.assertTrue(StealRecorder.startsWaiting() == 0 && StealRecorder.waiting() == 0, "nothing left waiting");
        });
    }

    @GameTest(template = EMPTY)
    public void a_failed_steal_keeps_its_reason_and_another_thiefs_start_is_never_used(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            BspCore.useTotemInfoForTests((srv, where) -> new BspCore.TotemInfo(StealRecorder.SOURCE_FORGE, 1, 1));
            GlobalPos where = place(h, 2);
            UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
            start(server, where, owner, first, "First", 60);
            now.addAndGet(5_000);
            end(server, where, owner, first, "First", false, "message.bsp_core.steal.failed_dead");
            start(server, where, owner, first, "First", 60);
            now.addAndGet(2_000);
            end(server, where, owner, second, "Second", true, "");
            s.drain();
            List<StorageBackend.Steal> rows = db.loadSteals(0, 10);
            h.assertTrue(rows.size() == 2, "two rows: " + rows);
            StorageBackend.Steal won = rows.get(0), lost = rows.get(1);
            h.assertTrue(!lost.success() && lost.reason().equals("message.bsp_core.steal.failed_dead") && lost.startedMs() > 0 && lost.expectedSeconds() == 60,
                    "the failed one, with its reason and start: " + lost);
            h.assertTrue(won.success() && won.thief().equals(second) && won.startedMs() == 0 && won.expectedSeconds() == 0,
                    "another thief's start is not this end's: " + won);
            h.assertTrue(won.source().equals("forge") && won.tier() == 1, "a Totem Forge's pour: " + won);
        });
    }

    @GameTest(template = EMPTY)
    public void an_unclaimed_totem_and_long_names_are_kept_within_the_columns(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            GlobalPos where = place(h, 3);
            UUID thief = UUID.randomUUID();
            StealRecorder.ended(server, where, null, thief, "x".repeat(40), false, "k".repeat(200));
            s.drain();
            StorageBackend.Steal r = db.loadSteals(0, 10).get(0);
            h.assertTrue(r.owner() == null && r.ownerName().isEmpty(), "no owner: " + r);
            h.assertTrue(r.thiefName().length() == 32 && r.reason().length() == 128, "clipped to the columns");
            h.assertTrue(r.source().equals("other") && r.tier() == 0 && r.points() == 0, "an empty block is unknown, never guessed: " + r);
        });
    }

    @GameTest(template = EMPTY)
    public void nothing_is_kept_without_shared_storage(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        StealRecorder.resetForTests();
        Storage before = Storage.active();
        Storage.useForTests(null);
        try {
            StealRecorder.ended(server, place(h, 4), UUID.randomUUID(), UUID.randomUUID(), "Nobody", true, "");
            h.assertTrue(StealRecorder.waiting() == 0, "no row waits for a database that is not there");
        } finally {
            Storage.useForTests(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_failed_write_waits_and_is_tried_again_each_minute(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            db.setFailing(true);
            StealRecorder.ended(server, place(h, 5), UUID.randomUUID(), UUID.randomUUID(), "Patient", true, "");
            s.drain();
            h.assertTrue(StealRecorder.waiting() == 1, "the row waits while the database is down");
            StealRecorder.ended(server, place(h, 11), UUID.randomUUID(), UUID.randomUUID(), "Second", true, "");
            h.assertTrue(StealRecorder.waiting() == 2 && s.queued() == 0, "a steal during the outage queues no write of its own: only the minute's retry tries (audit)");
            db.setFailing(false);
            now.addAndGet(30_000);
            StealRecorder.tick(server);
            s.drain();
            h.assertTrue(db.stealCount() == 0, "not before a minute");
            now.addAndGet(31_000);
            StealRecorder.tick(server);
            s.drain();
            h.assertTrue(db.stealCount() == 2 && StealRecorder.waiting() == 0, "both written on the next minute's try");
        });
    }

    @GameTest(template = EMPTY)
    public void steals_older_than_90_days_go_once_an_hour(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            UUID thief = UUID.randomUUID();
            long old = now.get() - StealRecorder.KEEP_MS - 60_000, fresh = now.get() - 60_000;
            db.insertSteals(List.of(
                    new StorageBackend.Steal(UUID.randomUUID().toString(), "beta", "minecraft:overworld", 0, 64, 0, thief, "Old", null, "", "totem", 1, 1, 0, old, 0, true, "", 0, ""),
                    new StorageBackend.Steal(UUID.randomUUID().toString(), "beta", "minecraft:overworld", 0, 64, 0, thief, "New", null, "", "totem", 1, 1, 0, fresh, 0, true, "", 0, "")));
            StealRecorder.onServerStarted(); // the first tick after a start prunes
            StealRecorder.tick(server);
            s.drain();
            List<StorageBackend.Steal> left = db.loadSteals(0, 10);
            h.assertTrue(left.size() == 1 && left.get(0).thiefName().equals("New"), "only the one within 90 days: " + left);
            db.insertSteals(List.of(new StorageBackend.Steal(UUID.randomUUID().toString(), "beta", "minecraft:overworld", 0, 64, 0, thief, "Older", null, "", "totem", 1, 1, 0, old, 0, true, "", 0, "")));
            now.addAndGet(30 * 60_000L);
            StealRecorder.tick(server);
            s.drain();
            h.assertTrue(db.stealCount() == 2, "not again within the hour");
            now.addAndGet(31 * 60_000L);
            StealRecorder.tick(server);
            s.drain();
            h.assertTrue(db.stealCount() == 1, "an hour later, again");
        });
    }

    @GameTest(template = EMPTY)
    public void the_real_lookup_reads_a_placed_totems_tier(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        BspCore.useTotemInfoForTests(null);
        BlockPos rel = new BlockPos(6, 2, 1);
        GlobalPos where = GlobalPos.of(h.getLevel().dimension(), h.absolutePos(rel));
        h.assertTrue(BspCore.totemInfo(server, where).equals(BspCore.TotemInfo.UNKNOWN), "an empty block is unknown");
        h.setBlock(rel, com.mrgregles.bsp_core.registry.ModBlocks.SHATTER_TOTEM.get());
        try {
            if (h.getLevel().getBlockEntity(where.pos()) instanceof com.mrgregles.bsp_core.totem.ShatterTotemBlockEntity be) {
                be.setTier(2); // Tier III, no owner: nothing reaches BSP-Core's ledger
            } else {
                throw new IllegalStateException("no totem block entity");
            }
            BspCore.TotemInfo info = BspCore.totemInfo(server, where);
            // 4: Tier III in the test world's scoring.tierPoints [1, 2, 4, 7, 10] (BSP-Core's default), not read back from BSP-Core (audit)
            h.assertTrue(info.source().equals("totem") && info.tier() == 3 && info.points() == 4, "Tier III and its points: " + info);
        } finally {
            h.setBlock(rel, Blocks.AIR);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_database_down_at_start_still_gets_the_steals_once_it_answers(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        db.setFailing(true); // before the first task: the tables cannot even be made
        Storage s = Storage.startWith(server, db, "alpha");
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        StealRecorder.useClockForTests(now::get);
        StealRecorder.resetForTests();
        Storage before = Storage.active();
        Storage.useForTests(s);
        try {
            StealRecorder.ended(server, place(h, 7), UUID.randomUUID(), UUID.randomUUID(), "Early", true, "");
            s.drain();
            h.assertTrue(StealRecorder.waiting() == 1 && !s.isReady(), "the row waits; the storage has not reached the database");
            db.setFailing(false);
            now.addAndGet(61_000);
            StealRecorder.tick(server);
            s.drain();
            h.assertTrue(db.stealCount() == 1 && StealRecorder.waiting() == 0, "written once the database answers (audit: it never was)");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Storage.useForTests(before);
            s.close();
            StealRecorder.useClockForTests(null);
            StealRecorder.resetForTests();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_failure_in_the_recording_never_reaches_bsp_cores_steal(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            BspCore.useTotemInfoForTests((srv, where) -> {
                throw new IllegalStateException("broken lookup (test)");
            });
            GlobalPos where = place(h, 8);
            UUID thief = UUID.randomUUID();
            start(server, where, null, thief, "Lucky", 30); // through the bus, as BSP-Core posts it
            end(server, where, null, thief, "Lucky", true, ""); // would throw into BSP-Core's completeSteal without the guard
            s.drain();
            h.assertTrue(db.stealCount() == 0, "nothing recorded, and nothing thrown back to the poster");
        });
    }

    @GameTest(template = EMPTY)
    public void each_row_carries_its_signed_copy(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            byte[] key = new byte[32];
            java.util.Arrays.fill(key, (byte) 7);
            com.mrgregles.bsp_server_utilities.web.WebActions.useForTests(key, null, null);
            try {
                StealRecorder.ended(server, place(h, 9), UUID.randomUUID(), UUID.randomUUID(), "Signed", true, "");
                s.drain();
                StorageBackend.Steal r = db.loadSteals(0, 5).get(0);
                int dot = r.line().indexOf('.');
                String json = r.line().substring(dot + 1);
                javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
                m.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
                String want = java.util.HexFormat.of().formatHex(m.doFinal(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                h.assertTrue(want.equals(r.line().substring(0, dot)) && o.get("uid").getAsString().equals(r.uid()) && o.get("event").getAsString().equals("end")
                        && o.get("thiefName").getAsString().equals("Signed") && o.get("ended").getAsLong() == r.endedMs() && o.get("server").getAsString().equals(r.server()),
                        "the row's line is its own fields, signed with the action key: " + r.line());
            } finally {
                com.mrgregles.bsp_server_utilities.web.WebActions.useReal();
            }
            com.mrgregles.bsp_server_utilities.web.WebActions.useForTests(null, null, null);
            try {
                StealRecorder.ended(server, place(h, 10), UUID.randomUUID(), UUID.randomUUID(), "Unsigned", true, "");
                s.drain();
                h.assertTrue(db.loadSteals(0, 5).get(0).line().isEmpty(), "without a key the line is empty");
            } finally {
                com.mrgregles.bsp_server_utilities.web.WebActions.useReal();
            }
        });
    }

    @GameTest(template = EMPTY)
    public void failed_attempts_are_folded_and_capped_but_completed_steals_never(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            now.set((now.get() / 3_600_000L) * 3_600_000L + 60_000L); // a minute into an hour: the hourly cap's count never resets mid-test
            UUID thief = UUID.randomUUID(), owner = UUID.randomUUID();
            GlobalPos a = place(h, 12), b = place(h, 13);
            for (int i = 0; i < 5; i++) {
                StealRecorder.ended(server, a, owner, thief, "Flood", false, "message.bsp_core.steal.failed_thief_gone");
                now.addAndGet(5_000);
            }
            s.drain();
            h.assertTrue(db.stealCount() == 1 && StealRecorder.folded() == 4, "five failures at one totem within a minute: one row (" + db.stealCount() + ")");
            StealRecorder.ended(server, a, owner, thief, "Flood", true, "");
            s.drain();
            h.assertTrue(db.stealCount() == 2, "a completed steal is always recorded");
            now.addAndGet(61_000);
            StealRecorder.ended(server, a, owner, thief, "Flood", false, "message.bsp_core.steal.failed_left");
            s.drain();
            h.assertTrue(db.stealCount() == 3, "a minute later, the next failure there is recorded");
            int before = db.stealCount();
            for (int i = 0; i < StealRecorder.FAILS_PER_HOUR + 10; i++) {
                StealRecorder.ended(server, GlobalPos.of(b.dimension(), b.pos().offset(i, 0, 0)), owner, thief, "Flood", false, "message.bsp_core.steal.failed_thief_gone");
            }
            s.drain();
            h.assertTrue(db.stealCount() - before == StealRecorder.FAILS_PER_HOUR - 2, "at most " + StealRecorder.FAILS_PER_HOUR + " failed rows a thief an hour (2 used above): " + (db.stealCount() - before));
        });
    }

    @GameTest(template = EMPTY)
    public void rows_waiting_at_the_stop_get_one_last_try(GameTestHelper h) {
        withStorage(h, (server, db, s, now) -> {
            db.setFailing(true);
            StealRecorder.ended(server, place(h, 14), UUID.randomUUID(), UUID.randomUUID(), "Late", true, "");
            s.drain();
            db.setFailing(false);
            StealRecorder.onServerStopping();
            s.drain();
            h.assertTrue(db.stealCount() == 1 && StealRecorder.waiting() == 0, "written as the server stops, without waiting for the minute");
        });
    }
}
