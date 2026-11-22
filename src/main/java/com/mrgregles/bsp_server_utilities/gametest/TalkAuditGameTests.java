package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.TalkPopupPacket;
import com.mrgregles.bsp_server_utilities.network.TalkStatePacket;
import com.mrgregles.bsp_server_utilities.network.TalkViewPacket;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.talk.TalkBook;
import com.mrgregles.bsp_server_utilities.talk.TalkDelivery;
import com.mrgregles.bsp_server_utilities.talk.TalkService;
import com.mrgregles.bsp_server_utilities.talk.TalkStore;
import com.mrgregles.bsp_server_utilities.talk.TalkTable;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The audit of private staff messages (SU-112): every request is answered while the database has not answered since the
 * start, and the login read is tried again; a line never lands in a conversation ended a moment before; a late line of an
 * ended conversation never brings the key back; lines during the login read reach staff once; a quick relog keeps its own
 * buffer; the login popup is not shown when another server told the player meanwhile; long names are cut, never refused
 * or thrown; two prunes never lose an end line; who wrote last decides the flash, not two servers' clocks; an offline
 * operator is never written to; a conversation that ends itself is in the Action Log.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TalkAuditGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long DAY = 86_400_000L;

    private record Sent(ServerPlayer to, Object what) {
    }

    private static final class World {
        final MinecraftServer server;
        final MemoryBackend db = new MemoryBackend();
        Storage s;
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final Set<UUID> staff = new HashSet<>();
        final List<ServerPlayer> here = new CopyOnWriteArrayList<>();
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        World(GameTestHelper h) {
            server = h.getLevel().getServer();
        }

        <T> T done(CompletableFuture<T> f) {
            s.drain();
            if (!f.isDone()) {
                throw new IllegalStateException("not answered");
            }
            return f.join();
        }

        <T> List<T> to(ServerPlayer p, Class<T> kind) {
            return sent.stream().filter(x -> x.to() == p && kind.isInstance(x.what())).map(x -> kind.cast(x.what())).toList();
        }

        long chat(ServerPlayer p) {
            return sent.stream().filter(x -> x.to() == p && x.what() instanceof Component).count();
        }
    }

    private interface Body {
        void run(World w) throws Exception;
    }

    /** {@code downAtStart}: the database refuses from the very first task, as MySQL still starting when the server boots. */
    private static void with(GameTestHelper h, boolean downAtStart, Body body) {
        World w = new World(h);
        w.db.setFailing(downAtStart);
        w.s = Storage.startWith(w.server, w.db, "alpha");
        w.s.drain();
        TalkService.useForTests(w.now::get, p -> w.staff.contains(p.getUUID()), (srv, id) -> w.staff.contains(id));
        TalkDelivery.useForTests(w.now::get, srv -> List.copyOf(w.here), p -> w.staff.contains(p.getUUID()), id -> false,
                (p, o) -> w.sent.add(new Sent(p, o)), (p, c) -> w.sent.add(new Sent(p, c)));
        Storage before = Storage.active();
        Storage.useForTests(w.s);
        try {
            body.run(w);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Storage.useForTests(before);
            w.s.close();
            TalkService.useReal();
            TalkDelivery.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void with_the_database_down_since_the_start_every_request_is_answered_and_the_login_read_is_tried_again(GameTestHelper h) {
        with(h, true, w -> {
            UUID wren = UUID.randomUUID();
            TalkService.Result r = w.done(TalkService.staffSend(w.server, TalkService.By.console("Console"), wren, "Wren", "hello"));
            h.assertTrue(r.outcome() == TalkService.Outcome.FAILED, "answered, as not saved: " + r);
            CompletableFuture<?> found = TalkService.find(w.server, "Nobody_" + UUID.randomUUID().toString().substring(0, 6));
            w.s.drain();
            h.assertTrue(found.isCompletedExceptionally(), "a name looked up is answered too (as a failure, never as nobody)");
            w.db.setFailing(false); // the database answers
            ServerPlayer kat = TestPlayers.mockServerPlayer(h);
            w.done(TalkService.staffSend(w.server, TalkService.By.console("Console"), kat.getUUID(), "Kat", "are you there?")); // while Kat is away
            w.db.setFailing(true); // and is away again as Kat logs in
            w.here.add(kat);
            TalkDelivery.onLogin(kat);
            w.s.drain();
            h.assertTrue(TalkDelivery.retryDue(kat.getUUID()) && w.to(kat, TalkStatePacket.class).isEmpty(), "the login read failed: nothing yet, a retry waits");
            long at = w.now.get();
            TalkDelivery.deliver(w.server, new TalkDelivery.Event(77, kat.getUUID(), "Kat", TalkStore.KIND_STAFF, "Ava", "live", at, at, 0, 0, TalkStore.KIND_STAFF));
            h.assertTrue(w.to(kat, TalkPopupPacket.class).size() == 1, "a live line meanwhile still reaches them (no read is holding it)");
            w.sent.clear();
            w.db.setFailing(false);
            w.now.addAndGet(31_000);
            TalkDelivery.tick(w.server);
            w.s.drain();
            h.assertTrue(w.to(kat, TalkStatePacket.class).equals(List.of(new TalkStatePacket(true, true))), "tried again 30 s later: the key's state: " + w.to(kat, TalkStatePacket.class));
            TalkDelivery.tick(w.server);
            w.s.drain();
            h.assertTrue(w.to(kat, TalkPopupPacket.class).size() == 1, "and the popup for the line sent while away");
        });
    }

    /** A store whose first {@code openTalk} answers as it stood a moment before (another server ended it since). */
    private static TalkStore stale(TalkTable real, TalkStore.Talk was) {
        AtomicBoolean once = new AtomicBoolean(true);
        return (TalkStore) Proxy.newProxyInstance(TalkStore.class.getClassLoader(), new Class<?>[]{TalkStore.class}, (proxy, m, args) -> {
            if (m.getName().equals("openTalk") && once.getAndSet(false)) {
                return was;
            }
            try {
                return m.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @GameTest(template = EMPTY)
    public void a_line_never_lands_in_a_conversation_ended_a_moment_before(GameTestHelper h) throws Exception {
        TalkTable t = new TalkTable();
        UUID wren = UUID.randomUUID();
        long now = System.currentTimeMillis();
        TalkStore.Talk first = TalkBook.staffWrites(t, wren, "Wren", "Ava", "hi", now, "alpha", x -> "").talk();
        TalkStore.Talk seenOpen = t.talk(first.id());
        TalkBook.end(t, wren, "Greg", now + 1, "beta", x -> ""); // ended on another server
        TalkBook.Written w = TalkBook.staffWrites(stale(t, seenOpen), wren, "Wren", "Ava", "a late line", now + 2, "alpha", x -> "");
        h.assertTrue(w.talk().id() != first.id() && w.opened(), "the late staff line opens a new conversation: " + w.talk());
        List<TalkStore.Line> old = t.talkLines(List.of(first.id()));
        h.assertTrue(old.size() == 2 && old.get(1).kind().equals(TalkStore.KIND_END), "the ended one keeps its end last: " + old);
        TalkBook.end(t, wren, "Greg", now + 3, "beta", x -> "");
        TalkBook.Written answer = TalkBook.playerWrites(stale(t, t.talk(w.talk().id())), wren, "Wren", "too late", now + 4, "alpha", x -> "");
        h.assertTrue(answer == null && t.talkLines(List.of(w.talk().id())).stream().noneMatch(l -> l.text().equals("too late")), "never an answer into a closed conversation");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_late_line_of_an_ended_conversation_never_brings_the_key_back(GameTestHelper h) {
        with(h, false, w -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            w.here.add(wren);
            UUID id = wren.getUUID();
            long at = w.now.get();
            TalkDelivery.deliver(w.server, new TalkDelivery.Event(9, id, "Wren", TalkStore.KIND_END, "Greg", "", at, at - 5, 0, at, TalkStore.KIND_STAFF));
            w.sent.clear();
            // the staff line written just before the end, arriving late through the notices table
            TalkDelivery.deliver(w.server, new TalkDelivery.Event(9, id, "Wren", TalkStore.KIND_STAFF, "Ava", "hi", at - 5, at - 5, 0, 0, TalkStore.KIND_STAFF));
            h.assertTrue(w.to(wren, TalkStatePacket.class).isEmpty() && w.to(wren, TalkPopupPacket.class).isEmpty(), "no key, no popup: " + w.sent);
            h.assertTrue(TalkDelivery.openOf(id) == null, "nothing open here");
        });
    }

    @GameTest(template = EMPTY)
    public void lines_during_the_login_read_reach_staff_once_and_a_quick_relog_keeps_its_own(GameTestHelper h) {
        with(h, false, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.here.addAll(List.of(ava, wren));
            UUID id = wren.getUUID();
            w.done(TalkService.staffSend(w.server, TalkService.By.of(ava), id, wren.getGameProfile().getName(), "hello"));
            w.sent.clear();
            TalkDelivery.onLogin(wren); // first login's read queued
            TalkDelivery.onLogin(wren); // a quick relog: a second read queued
            long at = w.now.get() + 1000;
            TalkDelivery.deliver(w.server, new TalkDelivery.Event(1, id, wren.getGameProfile().getName(), TalkStore.KIND_PLAYER, "", "my answer", at, at - 1000, at, 0, TalkStore.KIND_PLAYER));
            h.assertTrue(w.chat(ava) == 1, "staff hear the answer at once");
            w.s.drain();
            h.assertTrue(w.chat(ava) == 1, "and only once after the read: " + w.chat(ava));
            List<TalkStatePacket> st = w.to(wren, TalkStatePacket.class);
            h.assertTrue(!st.isEmpty() && st.get(st.size() - 1).equals(new TalkStatePacket(true, false)), "the second login's buffer replayed after its read: the answer stops the flash: " + st);
        });
    }

    @GameTest(template = EMPTY)
    public void the_login_popup_waits_for_a_fresh_read_and_is_not_shown_when_told_meanwhile(GameTestHelper h) {
        with(h, false, w -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            UUID id = wren.getUUID();
            TalkStore.Talk t = w.done(TalkService.staffSend(w.server, TalkService.By.panel("Ava"), id, "Wren", "while you were away")).written().talk();
            w.here.add(wren);
            TalkDelivery.onLogin(wren);
            w.s.drain();
            h.assertTrue(TalkDelivery.popupDue(id), "due");
            w.db.talkTable().talkTold(t.id(), t.lastStaffAt()); // the server they just left showed it a moment ago
            w.now.addAndGet(TalkDelivery.LOGIN_DELAY_MS + 1);
            TalkDelivery.tick(w.server);
            w.s.drain();
            h.assertTrue(w.to(wren, TalkPopupPacket.class).isEmpty(), "told already: never twice (D636)");
        });
    }

    @GameTest(template = EMPTY)
    public void long_names_are_cut_never_refused_or_thrown(GameTestHelper h) {
        with(h, false, w -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            w.here.add(wren);
            String longName = "Command_block_with_a_very_long_custom_name";
            TalkService.Result r = w.done(TalkService.staffSend(w.server, TalkService.By.console(longName), wren.getUUID(), wren.getGameProfile().getName(), "hi"));
            h.assertTrue(r.ok() && r.written().line().staff().length() == 32 && r.written().opened(), "sent, the name cut to 32, and it opened the conversation: " + r);
            h.assertTrue(w.db.loadLog(0, 100).stream().map(LogEntry::text).anyMatch(x -> x.equals("started a private conversation with " + wren.getGameProfile().getName())),
                    "the start is in the Action Log");
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            new TalkViewPacket(true, wren.getUUID(), "x".repeat(40), "y".repeat(80), 1, "z".repeat(40), true,
                    List.of(new TalkViewPacket.Entry(1, 1, TalkViewPacket.STAFF, "s".repeat(40), "t".repeat(2000)))).encode(buf);
            TalkViewPacket back = TalkViewPacket.decode(buf);
            new TalkPopupPacket("w".repeat(40), 1, false).encode(new FriendlyByteBuf(Unpooled.buffer()));
            h.assertTrue(back.name().length() == 32 && back.where().length() == 64 && back.lines().get(0).staff().length() == 32, "the packets cut to their limits: " + back.name());
        });
    }

    @GameTest(template = EMPTY)
    public void two_prunes_never_lose_an_end_line_and_a_self_ended_conversation_is_logged(GameTestHelper h) throws Exception {
        TalkTable t = new TalkTable();
        long now = System.currentTimeMillis();
        TalkStore.Talk a = t.openOrCreateTalk(UUID.randomUUID(), "Ann", now - 91 * DAY, "Ava");
        t.endTalk(a.id(), now, "") ; // another server's prune ended it this moment and has not written its end line yet
        TalkBook.Pruned p = TalkBook.prune(t, now, "beta", x -> "");
        h.assertTrue(p.dropped() == 0 && t.talk(a.id()) != null, "ended a moment ago: left for its end line");
        p = TalkBook.prune(t, now + 2 * 60_000L, "beta", x -> "");
        h.assertTrue(p.dropped() == 1 && t.talk(a.id()) == null, "ended over a minute ago with no line: dropped");
        with(h, false, w -> {
            UUID quiet = UUID.randomUUID();
            w.done(TalkService.staffSend(w.server, TalkService.By.panel("Ava"), quiet, "QuietOne", "hello?"));
            w.now.addAndGet(91 * DAY);
            w.done(TalkService.prune(w.server));
            h.assertTrue(w.db.loadLog(0, 100).stream().map(LogEntry::text).anyMatch(x -> x.equals("ended the private conversation with QuietOne (no line for 90 days)")),
                    "the end is in the Action Log (D644)");
        });
    }

    @GameTest(template = EMPTY)
    public void who_wrote_last_decides_the_flash_not_two_servers_clocks(GameTestHelper h) throws Exception {
        TalkTable t = new TalkTable();
        UUID wren = UUID.randomUUID();
        TalkBook.staffWrites(t, wren, "Wren", "Ava", "hi", 10_000, "alpha", x -> "");
        TalkBook.Written w = TalkBook.playerWrites(t, wren, "Wren", "answered", 9_000, "beta", x -> ""); // beta's clock is a second behind
        h.assertTrue(!w.talk().waitingOnPlayer() && w.talk().waitingOnStaff(), "the answer stops the flash though its time is earlier: " + w.talk());
        List<TalkStore.Line> lines = t.talkLines(List.of(w.talk().id()));
        h.assertTrue(lines.get(1).text().equals("answered"), "lines in the order written");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void an_offline_operator_is_never_written_to(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        GameProfile op = new GameProfile(UUID.randomUUID(), "su_test_op");
        Staff.useForTests(List.of(), true);
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(op, 4, false)); // level 4, as a real server gives (the test server gives 0)
        try {
            TalkService.useForTests(null, p -> true, null); // the real check for whom it is to
            CompletableFuture<TalkService.Result> r = TalkService.staffSend(server, TalkService.By.console("Console"), op.getId(), "su_test_op", "hi");
            h.assertTrue(r.isDone() && r.join().outcome() == TalkService.Outcome.TO_STAFF, "an offline operator counts as staff when operators do (D641): " + r.join());
        } finally {
            server.getPlayerList().getOps().remove(op);
            Staff.useConfig();
            TalkService.useReal();
        }
        h.succeed();
    }
}
