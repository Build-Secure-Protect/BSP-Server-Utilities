package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.players.LastSeen;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where each player was last seen, for the staff panel's offline players (SU-114; owner decisions D656, D657): a logout
 * writes the player's server, dimension, block and time, signed with the action key; everyone online is written every 5
 * minutes and an arrival after 10 seconds; a newer row always wins over an older one, whichever comes last; a server
 * stop writes everyone there as logged out; a failed write waits (the newest row per player) and is tried again within a
 * minute; nothing is kept without shared storage; without a key the row is written unsigned.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class LastSeenGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = "last-seen-test-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private interface Body {
        void run(MinecraftServer server, MemoryBackend db, Storage s, AtomicLong now, List<ServerPlayer> online) throws Exception;
    }

    /** A shared storage on a memory table, a clock the test moves, a player list the test fills, the action key; all put back after. */
    private static void withStorage(GameTestHelper h, Body body) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        List<ServerPlayer> online = new ArrayList<>();
        LastSeen.useClockForTests(now::get);
        LastSeen.usePlayersForTests(srv -> online);
        LastSeen.resetForTests();
        WebActions.useForTests(KEY, null, null);
        Storage before = Storage.active();
        Storage.useForTests(s);
        try {
            body.run(server, db, s, now, online);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Storage.useForTests(before); // as it was found
            s.close();
            WebActions.useReal();
            LastSeen.useClockForTests(null);
            LastSeen.usePlayersForTests(null);
            LastSeen.resetForTests();
        }
        h.succeed();
    }

    private static ServerPlayer playerAt(GameTestHelper h, int x, int y, int z) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        BlockPos at = h.absolutePos(new BlockPos(x, y, z));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return p;
    }

    @GameTest(template = EMPTY)
    public void a_logout_writes_where_and_when_signed_with_the_action_key(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer p = playerAt(h, 3, 2, 4);
            BlockPos at = p.blockPosition();
            LastSeen.onLogout(p);
            s.drain();
            StorageBackend.Seen r = db.loadSeen(p.getUUID());
            h.assertTrue(r != null, "a row for them");
            h.assertTrue(r.kind().equals(StorageBackend.SEEN_LOGOUT) && r.atMs() == now.get(), "a logout, at the moment: " + r);
            h.assertTrue(r.server().equals("alpha") && r.dimension().equals("minecraft:overworld") && r.x() == at.getX() && r.y() == at.getY() && r.z() == at.getZ(),
                    "this server, their world and block: " + r);
            h.assertTrue(r.name().equals(p.getGameProfile().getName()), "their name: " + r.name());
            h.assertTrue(r.line().equals(WebActions.signText(KEY, LastSeen.text(r))), "the fields signed with the action key: " + r.line());
            JsonObject o = JsonParser.parseString(r.line().substring(65)).getAsJsonObject();
            h.assertTrue(o.get("type").getAsString().equals("seen") && o.get("player").getAsString().equals(p.getUUID().toString())
                    && o.get("x").getAsInt() == at.getX() && o.get("kind").getAsString().equals("logout") && o.get("at").getAsLong() == now.get(),
                    "the signed copy holds the same: " + o);
            h.assertTrue(LastSeen.waiting() == 0, "nothing left waiting");
        });
    }

    @GameTest(template = EMPTY)
    public void everyone_online_is_written_every_five_minutes_and_an_arrival_after_ten_seconds(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer a = playerAt(h, 1, 2, 1), b = playerAt(h, 5, 2, 5);
            online.add(a);
            now.addAndGet(LastSeen.REFRESH_MS - 1000);
            LastSeen.tick(server);
            s.drain();
            h.assertTrue(db.loadSeen(a.getUUID()) == null, "not before 5 minutes");
            now.addAndGet(1000);
            LastSeen.tick(server);
            s.drain();
            StorageBackend.Seen ra = db.loadSeen(a.getUUID());
            h.assertTrue(ra != null && ra.kind().equals(StorageBackend.SEEN_REFRESH) && ra.atMs() == now.get(), "the 5-minute row: " + ra);
            // b arrives (a move from another server): a row from here 10 s later, not before
            online.add(b);
            LastSeen.onLogin(b);
            now.addAndGet(LastSeen.ARRIVAL_MS - 1000);
            LastSeen.tick(server);
            s.drain();
            h.assertTrue(db.loadSeen(b.getUUID()) == null, "not before 10 s");
            now.addAndGet(1000);
            LastSeen.tick(server);
            s.drain();
            StorageBackend.Seen rb = db.loadSeen(b.getUUID());
            h.assertTrue(rb != null && rb.kind().equals(StorageBackend.SEEN_REFRESH) && rb.server().equals("alpha"), "their arrival written: " + rb);
            h.assertTrue(db.loadSeen(a.getUUID()).atMs() == ra.atMs(), "a is not written again until the next 5 minutes");
            // a moves and the next 5 minutes come
            a.setPos(a.getX() + 40, a.getY(), a.getZ());
            now.addAndGet(LastSeen.REFRESH_MS);
            LastSeen.tick(server);
            s.drain();
            StorageBackend.Seen ra2 = db.loadSeen(a.getUUID());
            h.assertTrue(ra2.x() == ra.x() + 40 && ra2.atMs() == now.get(), "the new place: " + ra2);
        });
    }

    @GameTest(template = EMPTY)
    public void a_newer_row_always_wins_whichever_is_written_last(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer p = playerAt(h, 2, 2, 2);
            UUID id = p.getUUID();
            long t = now.get();
            // the server they moved to wrote first (a later time); the logout from the server they left arrives late
            db.saveSeen(List.of(new StorageBackend.Seen(id, "Wren", t + 5000, "beta", "minecraft:the_nether", 10, 64, 10, StorageBackend.SEEN_REFRESH, "")));
            LastSeen.onLogout(p); // at t
            s.drain();
            StorageBackend.Seen r = db.loadSeen(id);
            h.assertTrue(r.server().equals("beta") && r.atMs() == t + 5000, "the older logout never replaces the newer row: " + r);
            now.addAndGet(6000);
            LastSeen.onLogout(p);
            s.drain();
            r = db.loadSeen(id);
            h.assertTrue(r.server().equals("alpha") && r.kind().equals(StorageBackend.SEEN_LOGOUT) && r.atMs() == t + 6000, "a newer one does: " + r);
        });
    }

    @GameTest(template = EMPTY)
    public void a_server_stop_writes_everyone_there_as_logged_out(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer a = playerAt(h, 1, 2, 3), b = playerAt(h, 4, 2, 1);
            LastSeen.onLogin(a);
            LastSeen.onServerStopping(List.of(a, b));
            s.drain();
            StorageBackend.Seen ra = db.loadSeen(a.getUUID()), rb = db.loadSeen(b.getUUID());
            h.assertTrue(ra != null && rb != null && ra.kind().equals(StorageBackend.SEEN_LOGOUT) && rb.kind().equals(StorageBackend.SEEN_LOGOUT), "both logged out: " + ra + " " + rb);
            now.addAndGet(LastSeen.ARRIVAL_MS);
            online.add(a);
            LastSeen.tick(server);
            s.drain();
            h.assertTrue(db.loadSeen(a.getUUID()).kind().equals(StorageBackend.SEEN_LOGOUT), "the arrival they had is forgotten: no row after the stop's");
        });
    }

    @GameTest(template = EMPTY)
    public void a_failed_write_waits_with_the_newest_row_and_is_tried_again_within_a_minute(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer p = playerAt(h, 2, 2, 6);
            db.setFailing(true);
            LastSeen.onLogout(p);
            s.drain();
            h.assertTrue(LastSeen.waiting() == 1, "it waits: " + LastSeen.waiting());
            now.addAndGet(2000);
            p.setPos(p.getX() + 7, p.getY(), p.getZ());
            LastSeen.record(p, StorageBackend.SEEN_REFRESH); // a newer row for the same player while the database is away
            s.drain();
            h.assertTrue(LastSeen.waiting() == 1, "one row per player waits");
            db.setFailing(false);
            now.addAndGet(LastSeen.RETRY_EVERY_MS - 3000); // a minute after the failure, less a second
            LastSeen.tick(server);
            s.drain();
            h.assertTrue(db.loadSeen(p.getUUID()) == null, "not tried before the minute");
            now.addAndGet(1000);
            LastSeen.tick(server);
            s.drain();
            StorageBackend.Seen r = db.loadSeen(p.getUUID());
            h.assertTrue(r != null && r.kind().equals(StorageBackend.SEEN_REFRESH) && r.x() == p.blockPosition().getX(), "the newest row written: " + r);
            h.assertTrue(LastSeen.waiting() == 0, "nothing left waiting");
        });
    }

    @GameTest(template = EMPTY)
    public void without_shared_storage_nothing_is_kept_and_without_a_key_the_row_is_unsigned(GameTestHelper h) {
        ServerPlayer p = playerAt(h, 1, 2, 1);
        Storage before = Storage.active();
        Storage.useForTests(null);
        try {
            LastSeen.resetForTests();
            h.assertTrue(!LastSeen.record(p, StorageBackend.SEEN_LOGOUT), "no shared storage: not kept");
            LastSeen.onLogout(p);
            h.assertTrue(LastSeen.waiting() == 0, "nothing waits");
        } finally {
            Storage.useForTests(before);
        }
        withStorage(h, (server, db, s, now, online) -> {
            WebActions.useForTests(null, null, null);
            ServerPlayer q = playerAt(h, 3, 2, 3);
            LastSeen.onLogout(q);
            s.drain();
            StorageBackend.Seen r = db.loadSeen(q.getUUID());
            h.assertTrue(r != null && r.line().isEmpty(), "written without a signed copy (the panel shows the place as not known): " + r);
        });
    }

    // ------------------------------------------------------------------ the audit's fixes (SU-118)

    @GameTest(template = EMPTY)
    public void an_arrival_keeps_its_own_row_after_a_five_minute_write_in_its_first_seconds(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer p = playerAt(h, 2, 2, 2);
            online.add(p);
            now.addAndGet(LastSeen.REFRESH_MS - 1000);
            LastSeen.onLogin(p); // they arrive a second before the 5-minute write
            now.addAndGet(1000);
            LastSeen.tick(server);
            s.drain();
            long first = db.loadSeen(p.getUUID()).atMs();
            // the server they left logs them out late, after this server's 5-minute row
            db.saveSeen(List.of(new StorageBackend.Seen(p.getUUID(), "x", first + 300, "beta", "minecraft:overworld", 0, 0, 0, StorageBackend.SEEN_LOGOUT, "")));
            now.addAndGet(LastSeen.ARRIVAL_MS);
            LastSeen.tick(server);
            s.drain();
            StorageBackend.Seen r = db.loadSeen(p.getUUID());
            h.assertTrue(r.server().equals("alpha") && r.kind().equals(StorageBackend.SEEN_REFRESH) && r.atMs() == now.get(),
                    "their arrival row still comes 10 s after arriving, over the late logout: " + r);
        });
    }

    @GameTest(template = EMPTY)
    public void a_stop_while_writes_fail_writes_everyone_once_the_database_answers(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer gone = playerAt(h, 1, 2, 1), here = playerAt(h, 3, 2, 3);
            db.setFailing(true);
            LastSeen.onLogout(gone);
            s.drain();
            h.assertTrue(LastSeen.waiting() == 1, "the failed row waits");
            db.setFailing(false);
            LastSeen.onServerStopping(List.of(here)); // the stop's own try, not the minute's
            s.drain();
            h.assertTrue(db.loadSeen(gone.getUUID()) != null && db.loadSeen(here.getUUID()) != null, "both written at the stop");
            h.assertTrue(LastSeen.waiting() == 0, "nothing left");
            Storage.useForTests(null); // the storage closed: the logout events that follow do nothing
            LastSeen.onLogout(here);
            h.assertTrue(LastSeen.waiting() == 0, "a logout after the storage closed is not kept");
            Storage.useForTests(s);
        });
    }

    @GameTest(template = EMPTY)
    public void one_row_the_database_refuses_never_holds_up_the_others_and_is_dropped_after_five_tries(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer good = playerAt(h, 1, 2, 1), bad = playerAt(h, 3, 2, 3);
            db.setFailing(true);
            LastSeen.onLogout(good);
            LastSeen.onLogout(bad);
            s.drain();
            db.setFailing(false);
            db.refuseSeen.add(bad.getUUID());
            now.addAndGet(LastSeen.RETRY_EVERY_MS);
            LastSeen.tick(server);
            s.drain();
            h.assertTrue(db.loadSeen(good.getUUID()) != null, "the good row written though the batch was refused");
            h.assertTrue(LastSeen.waiting() == 1, "the refused one waits: " + LastSeen.waiting());
            for (int i = 0; i < LastSeen.MAX_ROW_FAILS; i++) {
                now.addAndGet(LastSeen.RETRY_EVERY_MS);
                LastSeen.tick(server);
                s.drain();
            }
            h.assertTrue(LastSeen.waiting() == 0 && db.loadSeen(bad.getUUID()) == null, "dropped after " + LastSeen.MAX_ROW_FAILS + " refusals");
            db.refuseSeen.clear();
            now.addAndGet(1000);
            LastSeen.onLogout(good);
            s.drain();
            h.assertTrue(db.loadSeen(good.getUUID()).atMs() == now.get(), "writes go straight through again");
        });
    }

    @GameTest(template = EMPTY)
    public void a_machines_fake_player_never_writes_over_its_owners_place(GameTestHelper h) {
        withStorage(h, (server, db, s, now, online) -> {
            ServerPlayer owner = playerAt(h, 2, 2, 2);
            LastSeen.onLogout(owner);
            s.drain();
            StorageBackend.Seen before = db.loadSeen(owner.getUUID());
            net.minecraftforge.common.util.FakePlayer machine = net.minecraftforge.common.util.FakePlayerFactory.get(h.getLevel(),
                    new com.mojang.authlib.GameProfile(owner.getUUID(), "[Deployer]"));
            machine.setPos(machine.getX() + 500, machine.getY(), machine.getZ());
            now.addAndGet(5000);
            LastSeen.onLogin(machine);
            LastSeen.onLogout(machine);
            h.assertTrue(!LastSeen.record(machine, StorageBackend.SEEN_REFRESH), "never recorded");
            s.drain();
            h.assertTrue(db.loadSeen(owner.getUUID()).equals(before), "the owner's place stays: " + db.loadSeen(owner.getUUID()));
        });
    }
}
