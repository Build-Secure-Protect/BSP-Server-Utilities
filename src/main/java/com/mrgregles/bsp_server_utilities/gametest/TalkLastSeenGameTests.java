package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.TalkViewPacket;
import com.mrgregles.bsp_server_utilities.players.Joined;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.talk.SeenWords;
import com.mrgregles.bsp_server_utilities.talk.TalkCommands;
import com.mrgregles.bsp_server_utilities.talk.TalkDelivery;
import com.mrgregles.bsp_server_utilities.talk.TalkService;
import com.mrgregles.bsp_server_utilities.talk.TalkWindows;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Last seen" in the staff window (SU-120; owner decisions D672 to D683): {@code /su talk <player>} for a player online
 * nowhere carries where they were last seen (how long ago by the server's clock, the server, the world, the block, and
 * whether it was a logout), "not recorded" without a row, and nothing (plain "offline") when the read fails or there is no
 * shared storage; a player online is never looked up, and one who logs on during the read shows as online; the player's
 * own window never carries a place; the packet keeps the place and cuts it to its limits. The window's words (SU-121, D683
 * R1, R2): minutes, hours under a day, yesterday by the viewer's calendar, days to 13, then the date with the year when it
 * is not this year's; the three vanilla worlds and a modded one by its name.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TalkLastSeenGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long HOUR = 3_600_000L;

    private record Sent(ServerPlayer to, Object what) {
    }

    private static final class Quiet implements CommandSource {
        @Override
        public void sendSystemMessage(Component c) {
        }

        @Override
        public boolean acceptsSuccess() {
            return false;
        }

        @Override
        public boolean acceptsFailure() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static final class World {
        final MinecraftServer server;
        final MemoryBackend db = new MemoryBackend();
        final Storage s;
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final Set<UUID> staff = new HashSet<>();
        final List<ServerPlayer> here = new CopyOnWriteArrayList<>();
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        World(GameTestHelper h) {
            server = h.getLevel().getServer();
            s = Storage.startWith(server, db, "alpha");
            s.drain();
        }

        void run(ServerPlayer p, String command) throws CommandSyntaxException {
            CommandSourceStack src = p.createCommandSourceStack().withSource(new Quiet()).withPermission(0);
            server.getCommands().getDispatcher().execute(command, src);
            s.drain();
        }

        List<TalkViewPacket> views(ServerPlayer p) {
            return sent.stream().filter(x -> x.to() == p && x.what() instanceof TalkViewPacket).map(x -> (TalkViewPacket) x.what()).toList();
        }

        <T> T done(CompletableFuture<T> f) {
            s.drain();
            return f.join();
        }

        /** An offline player the network knows, with their row in the seen table (none when {@code kind} is null). */
        UUID offline(String name, long atMs, String server, String dim, int x, int y, int z, String kind) throws Exception {
            UUID id = UUID.randomUUID();
            db.insertPlayer(new Joined(id, name, atMs, server));
            if (kind != null) {
                db.saveSeen(List.of(new StorageBackend.Seen(id, name, atMs, server, dim, x, y, z, kind, "")));
            }
            return id;
        }
    }

    private interface Body {
        void run(World w, ServerPlayer staff) throws Exception;
    }

    private static void with(GameTestHelper h, boolean shared, Body body) {
        World w = new World(h);
        TalkService.useForTests(w.now::get, p -> w.staff.contains(p.getUUID()), (srv, id) -> w.staff.contains(id));
        TalkDelivery.useForTests(w.now::get, srv -> List.copyOf(w.here), p -> w.staff.contains(p.getUUID()), id -> false,
                (p, o) -> w.sent.add(new Sent(p, o)), (p, c) -> w.sent.add(new Sent(p, c)));
        TalkCommands.useClockForTests(w.now::get);
        TalkWindows.resetForTests();
        Storage before = Storage.active();
        Storage.useForTests(shared ? w.s : null);
        try {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.here.add(ava);
            body.run(w, ava);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Storage.useForTests(before);
            w.s.close();
            TalkService.useReal();
            TalkDelivery.useReal();
            TalkCommands.useClockForTests(null);
            TalkWindows.resetForTests();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void su_talk_for_an_offline_player_says_where_and_how_long_ago_they_logged_off(GameTestHelper h) {
        with(h, true, (w, ava) -> {
            w.offline("PebbleLs", w.now.get() - 3 * HOUR, "beta", "minecraft:the_nether", 120, 64, -30, StorageBackend.SEEN_LOGOUT);
            w.run(ava, "su talk PebbleLs");
            List<TalkViewPacket> v = w.views(ava);
            h.assertTrue(v.size() == 1 && v.get(0).staff() && v.get(0).where().isEmpty(), "the staff window, offline: " + v);
            TalkViewPacket.Place p = v.get(0).last();
            h.assertTrue(p.found() && p.ageMs() == 3 * HOUR, "found, 3 h ago by the server's clock (D677): " + p);
            h.assertTrue(p.server().equals("beta") && p.dimension().equals("minecraft:the_nether") && p.x() == 120 && p.y() == 64 && p.z() == -30,
                    "the server, the world and the block: " + p);
            h.assertTrue(p.logout(), "a logout");
        });
    }

    @GameTest(template = EMPTY)
    public void a_record_written_while_online_says_no_logout_was_recorded(GameTestHelper h) {
        with(h, true, (w, ava) -> {
            UUID id = w.offline("KestrelLs", w.now.get() - 26 * HOUR, "gamma", "minecraft:overworld", -812, 70, 2204, StorageBackend.SEEN_REFRESH);
            TalkViewPacket v = w.done(TalkWindows.openFor(ava, id, "KestrelLs"));
            h.assertTrue(v.last().found() && !v.last().logout() && v.last().ageMs() == 26 * HOUR, "found, without a logout (D674): " + v.last());
        });
    }

    @GameTest(template = EMPTY)
    public void a_player_without_a_row_is_not_recorded(GameTestHelper h) {
        with(h, true, (w, ava) -> {
            UUID id = w.offline("OldtimerLs", w.now.get() - 400 * HOUR, "beta", "", 0, 0, 0, null);
            TalkViewPacket v = w.done(TalkWindows.openFor(ava, id, "OldtimerLs"));
            h.assertTrue(v.last().state() == TalkViewPacket.Place.NOT_RECORDED, "not recorded (D675): " + v.last());
            h.assertTrue(w.db.seenReads.get() == 1, "one read of the row: " + w.db.seenReads.get());
        });
    }

    @GameTest(template = EMPTY)
    public void an_online_player_is_never_looked_up(GameTestHelper h) {
        with(h, true, (w, ava) -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            w.here.add(wren);
            w.db.saveSeen(List.of(new StorageBackend.Seen(wren.getUUID(), wren.getGameProfile().getName(), w.now.get() - HOUR, "beta", "minecraft:overworld", 1, 2, 3,
                    StorageBackend.SEEN_LOGOUT, "")));
            TalkViewPacket v = w.done(TalkWindows.openFor(ava, wren.getUUID(), wren.getGameProfile().getName()));
            h.assertTrue(v.where().equals("alpha") && v.last().state() == TalkViewPacket.Place.UNKNOWN, "online here, no place (R3): " + v.where() + " " + v.last());
            h.assertTrue(w.db.seenReads.get() == 0, "no read made for a player online");
        });
    }

    @GameTest(template = EMPTY)
    public void a_failed_read_still_opens_the_conversation_as_plain_offline(GameTestHelper h) {
        with(h, true, (w, ava) -> {
            UUID id = w.offline("FailLs", w.now.get() - HOUR, "beta", "minecraft:overworld", 1, 2, 3, StorageBackend.SEEN_LOGOUT);
            w.done(TalkService.staffSend(w.server, TalkService.By.panel("Mrgregles"), id, "FailLs", "Your claim is back."));
            w.db.failSeenReads = true;
            TalkViewPacket v = w.done(TalkWindows.openFor(ava, id, "FailLs"));
            h.assertTrue(v.last().state() == TalkViewPacket.Place.UNKNOWN, "not known: plain offline (D675): " + v.last());
            h.assertTrue(v.lines().size() == 1 && v.lines().get(0).text().equals("Your claim is back."), "the conversation is still there: " + v.lines());
            h.assertTrue(w.views(ava).size() == 1, "the window opened");
        });
    }

    @GameTest(template = EMPTY)
    public void without_shared_storage_the_window_says_plain_offline(GameTestHelper h) {
        with(h, false, (w, ava) -> {
            UUID id = UUID.randomUUID();
            TalkViewPacket v = w.done(TalkWindows.openFor(ava, id, "LocalLs"));
            h.assertTrue(v.where().isEmpty() && v.last().state() == TalkViewPacket.Place.UNKNOWN, "plain offline without shared storage (D676): " + v.last());
            h.assertTrue(w.db.seenReads.get() == 0, "the shared table not touched");
        });
    }

    @GameTest(template = EMPTY)
    public void a_player_who_logs_on_during_the_read_shows_as_online(GameTestHelper h) {
        with(h, true, (w, ava) -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            w.db.saveSeen(List.of(new StorageBackend.Seen(wren.getUUID(), wren.getGameProfile().getName(), w.now.get() - HOUR, "beta", "minecraft:overworld", 1, 2, 3,
                    StorageBackend.SEEN_LOGOUT, "")));
            CompletableFuture<TalkViewPacket> f = TalkWindows.openFor(ava, wren.getUUID(), wren.getGameProfile().getName()); // offline as it starts
            w.here.add(wren); // logs on before the database answers
            TalkViewPacket v = w.done(f);
            h.assertTrue(v.where().equals("alpha") && v.last().state() == TalkViewPacket.Place.UNKNOWN, "online, no place: " + v.where() + " " + v.last());
            h.assertTrue(w.db.seenReads.get() == 1, "it was read while they were offline");
        });
    }

    @GameTest(template = EMPTY)
    public void a_row_from_a_clock_ahead_is_never_in_the_future_and_the_own_window_has_no_place(GameTestHelper h) {
        with(h, true, (w, ava) -> {
            UUID id = w.offline("AheadLs", w.now.get() + 90_000, "beta", "minecraft:overworld", 1, 2, 3, StorageBackend.SEEN_LOGOUT);
            TalkViewPacket v = w.done(TalkWindows.openFor(ava, id, "AheadLs"));
            h.assertTrue(v.last().found() && v.last().ageMs() == 0, "a row ahead of this clock is 0 ago: " + v.last());
            // the player's own window: never a place
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            w.here.add(wren);
            w.done(TalkService.staffSend(w.server, TalkService.By.panel("Mrgregles"), wren.getUUID(), wren.getGameProfile().getName(), "Hello"));
            w.sent.clear();
            h.assertTrue(w.done(TalkWindows.openOwn(wren)), "their own window opened");
            List<TalkViewPacket> own = w.views(wren);
            h.assertTrue(own.size() == 1 && !own.get(0).staff() && own.get(0).last().state() == TalkViewPacket.Place.UNKNOWN, "no place in the player's own window: " + own);
        });
    }

    @GameTest(template = EMPTY)
    public void the_packet_keeps_the_place_and_cuts_it_to_its_limits(GameTestHelper h) {
        UUID id = UUID.randomUUID();
        List<TalkViewPacket.Entry> none = List.of();
        TalkViewPacket.Place found = TalkViewPacket.Place.found(3 * HOUR, "s".repeat(80), "d".repeat(200), 120, -64, -30_000_000, false);
        TalkViewPacket back = roundTrip(new TalkViewPacket(true, id, "Pebble", "", 0, "", true, none, found));
        TalkViewPacket.Place p = back.last();
        h.assertTrue(p.found() && p.ageMs() == 3 * HOUR && p.server().length() == 64 && p.dimension().length() == 160
                && p.x() == 120 && p.y() == -64 && p.z() == -30_000_000 && !p.logout(), "kept, the strings cut to 64 and 160: " + p);
        TalkViewPacket.Place negative = new TalkViewPacket.Place(TalkViewPacket.Place.FOUND, -5000, "beta", "minecraft:overworld", 0, 0, 0, true);
        h.assertTrue(roundTrip(new TalkViewPacket(true, id, "Pebble", "", 0, "", true, none, negative)).last().ageMs() == 0, "a negative age arrives as 0");
        h.assertTrue(roundTrip(new TalkViewPacket(true, id, "Pebble", "", 0, "", true, none, TalkViewPacket.Place.NO_RECORD)).last().state() == TalkViewPacket.Place.NOT_RECORDED,
                "not recorded kept");
        TalkViewPacket plain = roundTrip(new TalkViewPacket(true, id, "Pebble", "alpha", 0, "", true, none));
        h.assertTrue(plain.last().state() == TalkViewPacket.Place.UNKNOWN && plain.where().equals("alpha"), "no place kept as none");
        h.succeed();
    }

    private static long utc(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static void step(GameTestHelper h, long at, long now, ZoneId zone, SeenWords.Step step, long n, String what) {
        SeenWords.When w = SeenWords.when(at, now, zone);
        h.assertTrue(w.step() == step && w.n() == n, what + ": " + w);
    }

    @GameTest(template = EMPTY)
    public void the_window_says_minutes_hours_yesterday_days_then_the_date(GameTestHelper h) {
        ZoneId z = ZoneOffset.UTC;
        long now = utc(2026, 11, 22, 23, 30);
        step(h, now - 30_000, now, z, SeenWords.Step.MINUTES, 1, "under a minute is 1 min");
        step(h, now + 90_000, now, z, SeenWords.Step.MINUTES, 1, "ahead of now is 1 min");
        SeenWords.When m = SeenWords.when(now - 12 * 60_000L, now, z);
        h.assertTrue(m.step() == SeenWords.Step.MINUTES && m.n() == 12 && m.clock().equals("23:18"), "12 min ago (23:18): " + m);
        SeenWords.When hr = SeenWords.when(utc(2026, 11, 22, 21, 4), now, z);
        h.assertTrue(hr.step() == SeenWords.Step.HOURS && hr.n() == 2 && hr.clock().equals("21:04") && hr.full().equals("22 Nov 21:04"), "2 h ago (21:04): " + hr);
        step(h, utc(2026, 11, 21, 23, 40), now, z, SeenWords.Step.HOURS, 23, "the day before but under a day is hours");
        SeenWords.When y = SeenWords.when(utc(2026, 11, 21, 22, 41), now, z);
        h.assertTrue(y.step() == SeenWords.Step.YESTERDAY && y.clock().equals("22:41") && y.full().equals("21 Nov 22:41"), "yesterday (22:41): " + y);
        step(h, utc(2026, 11, 18, 22, 10), now, z, SeenWords.Step.DAYS, 4, "4 d ago");
        step(h, utc(2026, 11, 9, 1, 0), now, z, SeenWords.Step.DAYS, 13, "13 d ago");
        SeenWords.When d = SeenWords.when(utc(2026, 11, 8, 12, 0), now, z);
        h.assertTrue(d.step() == SeenWords.Step.DATE && d.date().equals("8 Nov"), "the date after 13 days: " + d);
        SeenWords.When old = SeenWords.when(utc(2025, 10, 18, 22, 10), now, z);
        h.assertTrue(old.step() == SeenWords.Step.DATE && old.date().equals("18 Oct 2025") && old.full().equals("18 Oct 2025 22:10"), "with the year: " + old);
        // the viewer's own calendar: 25 h 30 min ago is 2 days back in UTC, yesterday two hours west
        long now2 = utc(2026, 11, 22, 0, 30), at2 = utc(2026, 11, 20, 23, 0);
        step(h, at2, now2, z, SeenWords.Step.DAYS, 2, "UTC: two calendar days");
        step(h, at2, now2, ZoneOffset.ofHours(-2), SeenWords.Step.YESTERDAY, 1, "UTC-2: yesterday");
        // the night the clocks go back: 24 h 20 min on one calendar day is hours, never "yesterday" (audit of SU-121)
        ZoneId london = ZoneId.of("Europe/London");
        long at3 = ZonedDateTime.of(2026, 10, 25, 0, 10, 0, 0, london).toInstant().toEpochMilli(), now3 = ZonedDateTime.of(2026, 10, 25, 23, 30, 0, 0, london).toInstant().toEpochMilli();
        step(h, at3, now3, london, SeenWords.Step.HOURS, 24, "the same day, 24 h ago");
        step(h, now - 59 * 60_000L - 59_000, now, z, SeenWords.Step.MINUTES, 59, "59 min");
        step(h, now - 60 * 60_000L, now, z, SeenWords.Step.HOURS, 1, "60 min is 1 h");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_window_names_the_world(GameTestHelper h) {
        h.assertTrue(SeenWords.world("minecraft:overworld") == SeenWords.World.OVERWORLD && SeenWords.world("") == SeenWords.World.OVERWORLD, "the Overworld");
        h.assertTrue(SeenWords.world("minecraft:the_nether") == SeenWords.World.NETHER && SeenWords.world("minecraft:the_end") == SeenWords.World.END, "nether and end");
        h.assertTrue(SeenWords.world("twilightforest:twilight_forest") == SeenWords.World.OTHER
                && SeenWords.otherName("twilightforest:twilight_forest").equals("twilight forest"), "a modded world by its name");
        h.assertTrue(SeenWords.otherName("compactmachines:compact_world").equals("compact world") && SeenWords.otherName("odd").equals("odd"), "with or without a namespace");
        h.succeed();
    }

    private static TalkViewPacket roundTrip(TalkViewPacket v) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        v.encode(buf);
        TalkViewPacket back = TalkViewPacket.decode(buf);
        if (buf.readableBytes() != 0) {
            throw new IllegalStateException("bytes left after the packet: " + buf.readableBytes());
        }
        return back;
    }
}
