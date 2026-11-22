package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.talk.TalkBook;
import com.mrgregles.bsp_server_utilities.talk.TalkData;
import com.mrgregles.bsp_server_utilities.talk.TalkService;
import com.mrgregles.bsp_server_utilities.talk.TalkStore;
import com.mrgregles.bsp_server_utilities.talk.TalkTable;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Private staff messages, the rules (SU-105; owner decisions D630 to D645): a staff line opens one conversation per player
 * that every staff member shares; the player answers only into an open one; any staff member ends it, once, and the
 * player cannot reopen it; only staff write, never to staff; lines of 1 to 256 plain characters, 30 a minute for staff and
 * 10 for a player; the Action Log gets the start and the end only; lines go after 90 days and a conversation left with no
 * line ends itself; two servers sharing the tables keep one conversation; a lone server keeps it all in its save; every
 * line carries a signed copy for the panel.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TalkGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long DAY = 86_400_000L;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 11 + 3);
        }
    }

    private interface Body {
        void run(MinecraftServer server, @Nullable MemoryBackend db, @Nullable Storage s, AtomicLong now, Set<UUID> staff) throws Exception;
    }

    /** A clock the test moves, who is staff by UUID, the action key, and shared storage on a memory table (or none); all put back after. */
    private static void with(GameTestHelper h, boolean shared, Body body) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = shared ? new MemoryBackend() : null;
        Storage s = shared ? Storage.startWith(server, db, "alpha") : null;
        if (s != null) {
            s.drain();
        }
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        Set<UUID> staff = new HashSet<>();
        TalkService.useForTests(now::get, p -> staff.contains(p.getUUID()), (srv, id) -> staff.contains(id));
        WebActions.useForTests(KEY, null, null);
        Storage before = Storage.active();
        Storage.useForTests(s);
        try {
            body.run(server, db, s, now, staff);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Storage.useForTests(before);
            if (s != null) {
                s.close();
            }
            TalkService.useReal();
            WebActions.useReal();
        }
        h.succeed();
    }

    /** The future's value once the storage's queued work has run (a lone server answers at once). */
    private static <T> T done(@Nullable Storage s, CompletableFuture<T> f) {
        if (s != null) {
            s.drain();
        }
        if (!f.isDone()) {
            throw new IllegalStateException("not answered");
        }
        return f.join();
    }

    private static String name() {
        return "p" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** The JSON inside a signed copy, when the signature is the test key's; null otherwise. */
    @Nullable
    private static JsonObject open(String signed) {
        if (signed.length() < 66) {
            return null;
        }
        String text = signed.substring(65);
        return WebActions.signText(KEY, text).equals(signed) ? JsonParser.parseString(text).getAsJsonObject() : null;
    }

    private static long logged(MemoryBackend db, String text) throws Exception {
        return db.loadLog(0, 1000).stream().map(LogEntry::text).filter(t -> t.equals(text)).count();
    }

    @GameTest(template = EMPTY)
    public void a_staff_line_opens_one_conversation_that_all_staff_share(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), greg = TestPlayers.mockServerPlayer(h);
            staff.add(ava.getUUID());
            staff.add(greg.getUUID());
            UUID wren = UUID.randomUUID();
            String n = name();
            TalkService.Result a = done(s, TalkService.staffSend(server, TalkService.By.of(ava), wren, n, "Hi, did someone take your things?"));
            now.addAndGet(1000);
            TalkService.Result b = done(s, TalkService.staffSend(server, TalkService.By.of(greg), wren, n, "We are checking the logs."));
            h.assertTrue(a.ok() && b.ok(), "both sent: " + a + " " + b);
            h.assertTrue(a.written().opened() && !b.written().opened(), "the first opened it, the second joined it");
            h.assertTrue(a.written().talk().id() == b.written().talk().id(), "one conversation for the player (D631)");
            TalkBook.History hist = done(s, TalkService.history(server, wren));
            h.assertTrue(hist.talks().size() == 1 && hist.lines().size() == 2, "one conversation, two lines: " + hist);
            h.assertTrue(hist.lines().get(0).staff().equals(ava.getGameProfile().getName()) && hist.lines().get(1).staff().equals(greg.getGameProfile().getName()),
                    "each staff line names its writer");
            TalkStore.Talk t = hist.open();
            h.assertTrue(t != null && t.openedBy().equals(ava.getGameProfile().getName()) && t.name().equals(n), "opened by the first writer, for the player: " + t);
            h.assertTrue(t.waitingOnPlayer() && !t.waitingOnStaff(), "the newest line is from staff: the key flashes (D634)");
            h.assertTrue(logged(db, "started a private conversation with " + n) == 1, "one Action Log entry for the start, none per line (D644)");
        });
    }

    @GameTest(template = EMPTY)
    public void the_player_answers_only_into_an_open_conversation(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            TalkService.Result first = done(s, TalkService.playerSend(wren, "hello?"));
            h.assertTrue(first.outcome() == TalkService.Outcome.NO_TALK && first.written() == null, "staff write first (D633): " + first);
            h.assertTrue(db.talkTable().talksOf(wren.getUUID()).isEmpty(), "and nothing was opened by it");
            done(s, TalkService.staffSend(server, TalkService.By.console("Console"), wren.getUUID(), wren.getGameProfile().getName(), "Are you there?"));
            now.addAndGet(5000);
            TalkService.Result r = done(s, TalkService.playerSend(wren, "yes, here"));
            h.assertTrue(r.ok() && r.written().line().kind().equals(TalkStore.KIND_PLAYER) && r.written().line().staff().isEmpty(), "their answer: " + r);
            h.assertTrue(r.written().talk().waitingOnStaff() && !r.written().talk().waitingOnPlayer(), "answered: the flash stops, staff are waiting (D634, D639)");
        });
    }

    @GameTest(template = EMPTY)
    public void any_staff_member_ends_it_once_and_the_next_line_opens_a_new_one(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), greg = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            staff.add(ava.getUUID());
            staff.add(greg.getUUID());
            String n = wren.getGameProfile().getName();
            long firstId = done(s, TalkService.staffSend(server, TalkService.By.of(ava), wren.getUUID(), n, "Your claim is back.")).written().talk().id();
            done(s, TalkService.playerSend(wren, "thanks!"));
            now.addAndGet(1000);
            TalkService.Result end = done(s, TalkService.end(server, TalkService.By.of(greg), wren.getUUID(), n));
            h.assertTrue(end.outcome() == TalkService.Outcome.ENDED, "a staff member who did not write last ends it (D632): " + end);
            TalkStore.Talk t = end.written().talk();
            h.assertTrue(!t.open() && t.endedBy().equals(greg.getGameProfile().getName()) && end.written().line().kind().equals(TalkStore.KIND_END), "ended, by them, with an end line");
            TalkService.Result again = done(s, TalkService.end(server, TalkService.By.of(ava), wren.getUUID(), n));
            h.assertTrue(again.outcome() == TalkService.Outcome.NO_TALK, "only once: " + again);
            TalkService.Result late = done(s, TalkService.playerSend(wren, "one more thing"));
            h.assertTrue(late.outcome() == TalkService.Outcome.NO_TALK, "the player cannot reopen it (D633): " + late);
            now.addAndGet(1000);
            TalkService.Result next = done(s, TalkService.staffSend(server, TalkService.By.of(ava), wren.getUUID(), n, "One more question."));
            h.assertTrue(next.written().opened() && next.written().talk().id() != firstId, "the next staff line starts a new conversation");
            TalkBook.History hist = done(s, TalkService.history(server, wren.getUUID()));
            h.assertTrue(hist.talks().size() == 2 && hist.talks().get(0).open() && !hist.talks().get(1).open(), "the new one first, the ended one kept: " + hist.talks());
            TalkBook.History mine = done(s, TalkService.playerView(server, wren.getUUID()));
            h.assertTrue(mine.talks().size() == 1 && mine.lines().size() == 1, "the player sees only the open conversation: " + mine.lines());
            h.assertTrue(logged(db, "ended the private conversation with " + n) == 1 && logged(db, "started a private conversation with " + n) == 2,
                    "the Action Log: each start and the end");
        });
    }

    @GameTest(template = EMPTY)
    public void only_staff_write_and_never_to_staff(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), bob = TestPlayers.mockServerPlayer(h);
            staff.add(ava.getUUID());
            UUID wren = UUID.randomUUID(), mod = UUID.randomUUID();
            staff.add(mod);
            TalkService.Result r = done(s, TalkService.staffSend(server, TalkService.By.of(bob), wren, name(), "hi"));
            h.assertTrue(r.outcome() == TalkService.Outcome.NOT_STAFF, "a player who is not staff: " + r);
            r = done(s, TalkService.end(server, TalkService.By.of(bob), wren, "Wren"));
            h.assertTrue(r.outcome() == TalkService.Outcome.NOT_STAFF, "nor ends one: " + r);
            r = done(s, TalkService.staffSend(server, TalkService.By.of(ava), mod, "Moddy", "hi"));
            h.assertTrue(r.outcome() == TalkService.Outcome.TO_STAFF && r.message().contains("staff room"), "never to another staff member (D641): " + r);
            r = done(s, TalkService.staffSend(server, TalkService.By.panel("Ava"), mod, "Moddy", "hi"));
            h.assertTrue(r.outcome() == TalkService.Outcome.TO_STAFF, "not from the panel either: " + r);
            r = done(s, TalkService.staffSend(server, TalkService.By.panel("Ava"), wren, name(), "From the panel"));
            h.assertTrue(r.ok(), "the panel (it checked the rank, D630): " + r);
            h.assertTrue(db.talkTable().talksOf(mod).isEmpty(), "nothing stored for the refused ones");
        });
    }

    @GameTest(template = EMPTY)
    public void lines_are_plain_and_limited_in_length_and_rate(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            staff.add(ava.getUUID());
            UUID id = wren.getUUID();
            String n = wren.getGameProfile().getName();
            TalkService.By by = TalkService.By.of(ava);
            h.assertTrue(done(s, TalkService.staffSend(server, by, id, n, "   ")).outcome() == TalkService.Outcome.EMPTY, "nothing to send");
            h.assertTrue(done(s, TalkService.staffSend(server, by, id, n, "x".repeat(257))).outcome() == TalkService.Outcome.TOO_LONG, "257 characters refused");
            TalkService.Result ok = done(s, TalkService.staffSend(server, by, id, n, "§cRed‮ and " + "y".repeat(240)));
            h.assertTrue(ok.ok() && !ok.written().line().text().contains("§") && !ok.written().line().text().contains("‮"),
                    "colour codes and invisible characters taken out (D642): " + ok.written().line().text());
            for (int i = 1; i < TalkService.STAFF_PER_MINUTE; i++) {
                h.assertTrue(done(s, TalkService.staffSend(server, by, id, n, "line " + i)).ok(), "line " + i + " of 30");
            }
            h.assertTrue(done(s, TalkService.staffSend(server, by, id, n, "too many")).outcome() == TalkService.Outcome.TOO_FAST, "the 31st in a minute");
            for (int i = 0; i < TalkService.PLAYER_PER_MINUTE; i++) {
                h.assertTrue(done(s, TalkService.playerSend(wren, "answer " + i)).ok(), "answer " + i + " of 10");
            }
            h.assertTrue(done(s, TalkService.playerSend(wren, "eleven")).outcome() == TalkService.Outcome.TOO_FAST, "the player's 11th in a minute");
            now.addAndGet(60_001);
            h.assertTrue(done(s, TalkService.staffSend(server, by, id, n, "a minute later")).ok() && done(s, TalkService.playerSend(wren, "me too")).ok(), "a minute later both write again");
        });
    }

    @GameTest(template = EMPTY)
    public void lines_go_after_90_days_and_a_conversation_left_empty_ends_itself(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            TalkTable t = db.talkTable();
            UUID quiet = UUID.randomUUID(), busy = UUID.randomUUID(), closed = UUID.randomUUID();
            long start = now.get();
            done(s, TalkService.staffSend(server, TalkService.By.console("Console"), quiet, "Quiet", "Hello?"));
            done(s, TalkService.staffSend(server, TalkService.By.console("Console"), busy, "Busy", "Hello?"));
            done(s, TalkService.staffSend(server, TalkService.By.console("Console"), closed, "Closed", "Hello?"));
            now.addAndGet(2000);
            done(s, TalkService.end(server, TalkService.By.console("Console"), closed, "Closed"));
            now.set(start + 80 * DAY);
            done(s, TalkService.staffSend(server, TalkService.By.console("Console"), busy, "Busy", "Still there?"));
            now.set(start + 91 * DAY);
            TalkStore.Talk opening = t.openOrCreateTalk(UUID.randomUUID(), "Opening", now.get() - 5000, "Ava"); // its first line not written yet
            List<TalkBook.Written> heard = new java.util.ArrayList<>();
            java.util.function.BiConsumer<MinecraftServer, TalkBook.Written> ear = (srv, w) -> heard.add(w);
            TalkService.onChange(ear);
            try {
                TalkBook.Pruned p = done(s, TalkService.prune(server));
                h.assertTrue(p.lines() == 4, "the four lines older than 90 days went: " + p);
                h.assertTrue(p.ended().size() == 1 && p.ended().get(0).talk().player().equals(quiet), "the conversation left with no line ended itself (D643): " + p.ended());
                TalkStore.Talk q = t.talksOf(quiet).get(0);
                h.assertTrue(!q.open() && q.endedBy().isEmpty() && t.talkLines(List.of(q.id())).size() == 1, "ended by nobody, with its end line: " + q);
                h.assertTrue(heard.size() == 1, "the listeners heard it (the key goes): " + heard.size());
                h.assertTrue(t.talksOf(closed).isEmpty(), "an ended conversation with no line left is dropped");
                h.assertTrue(t.openTalk(busy) != null && t.talkLines(List.of(t.openTalk(busy).id())).size() == 1, "a newer line keeps its conversation open");
                h.assertTrue(t.talk(opening.id()) != null && t.talk(opening.id()).open(), "one being opened this minute is left alone");
            } finally {
                TalkService.removeListenerForTests(ear);
            }
        });
    }

    @GameTest(template = EMPTY)
    public void two_servers_on_the_same_tables_keep_one_conversation(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            Storage beta = Storage.startWith(server, db, "beta");
            try {
                beta.drain();
                UUID wren = UUID.randomUUID();
                // both servers find no open conversation and open one at the same moment: the table keeps one
                TalkStore.Talk a = db.openOrCreateTalk(wren, "Wren", now.get(), "Ava");
                TalkStore.Talk b = db.openOrCreateTalk(wren, "Wren", now.get(), "Greg");
                h.assertTrue(a.id() == b.id() && b.openedBy().equals("Ava"), "one open conversation per player: " + a + " " + b);
                TalkBook.Written w = TalkBook.staffWrites(db, wren, "Wren", "Greg", "From beta", now.get() + 1, "beta", x -> "");
                h.assertTrue(w.talk().id() == a.id() && !w.opened() && w.line().server().equals("beta"), "beta's line joins alpha's conversation: " + w);
                TalkBook.Written e1 = TalkBook.end(db, wren, "Ava", now.get() + 2, "alpha", x -> "");
                TalkBook.Written e2 = TalkBook.end(db, wren, "Greg", now.get() + 2, "beta", x -> "");
                h.assertTrue(e1 != null && e2 == null, "ended once: the first end wins");
            } finally {
                beta.close();
            }
        });
    }

    @GameTest(template = EMPTY)
    public void a_lone_server_keeps_conversations_in_its_save(GameTestHelper h) {
        with(h, false, (server, db, s, now, staff) -> {
            UUID wren = UUID.randomUUID();
            TalkService.Result r = done(null, TalkService.staffSend(server, TalkService.By.console("Console"), wren, "Wren", "Kept in the world"));
            h.assertTrue(r.ok(), "answered at once without shared storage (D645): " + r);
            TalkTable saved = TalkData.get(server).table();
            TalkStore.Talk t = saved.openTalk(wren);
            h.assertTrue(t != null && saved.talkLines(List.of(t.id())).size() == 1, "in this world's save");
            TalkTable copy = TalkTable.load(saved.save());
            TalkStore.Talk c = copy.openTalk(wren);
            h.assertTrue(c != null && c.equals(t) && copy.talkLines(List.of(c.id())).equals(saved.talkLines(List.of(t.id()))), "and read back the same after a restart");
            h.assertTrue(copy.openOrCreateTalk(UUID.randomUUID(), "N", now.get(), "X").id() > t.id(), "new conversations never reuse an id");
            done(null, TalkService.end(server, TalkService.By.console("Console"), wren, "Wren"));
        });
    }

    @GameTest(template = EMPTY)
    public void every_line_carries_a_signed_copy_for_the_panel(GameTestHelper h) {
        with(h, true, (server, db, s, now, staff) -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            TalkService.Result r = done(s, TalkService.staffSend(server, TalkService.By.panel("Ava"), wren.getUUID(), wren.getGameProfile().getName(), "Signed"));
            TalkService.Result a = done(s, TalkService.playerSend(wren, "and answered"));
            TalkService.Result e = done(s, TalkService.end(server, TalkService.By.panel("Ava"), wren.getUUID(), "Wren"));
            for (TalkService.Result x : List.of(r, a, e)) {
                TalkStore.Line l = x.written().line();
                JsonObject o = open(l.line());
                h.assertTrue(o != null, "signed with the action key: " + l.line());
                h.assertTrue(o.get("type").getAsString().equals("talk") && o.get("v").getAsInt() == 1 && o.get("uid").getAsString().equals(l.uid())
                        && o.get("talk").getAsLong() == l.talk() && o.get("player").getAsString().equals(wren.getUUID().toString())
                        && o.get("at").getAsLong() == l.at() && o.get("kind").getAsString().equals(l.kind()) && o.get("staff").getAsString().equals(l.staff())
                        && o.get("text").getAsString().equals(l.text()) && o.get("server").getAsString().equals("alpha"), "the copy says what the row says: " + o);
            }
            h.assertTrue(r.written().line().staff().equals("Ava") && e.written().line().kind().equals(TalkStore.KIND_END), "the panel's name on its lines");
            WebActions.useForTests(null, null, null);
            TalkService.Result unsigned = done(s, TalkService.staffSend(server, TalkService.By.panel("Ava"), wren.getUUID(), "Wren", "No key here"));
            h.assertTrue(unsigned.ok() && unsigned.written().line().line().isEmpty(), "without a key the line is kept and its copy is empty (the panel will not show it)");
        });
    }
}
