package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.TalkPopupPacket;
import com.mrgregles.bsp_server_utilities.network.TalkStatePacket;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.talk.TalkDelivery;
import com.mrgregles.bsp_server_utilities.talk.TalkService;
import com.mrgregles.bsp_server_utilities.talk.TalkStore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Private staff messages reaching people (SU-106; owner decisions D633 to D639, D652 R3 and R4): a staff line gives the
 * player here the key's flashing state, the popup (the writer, never the words) and the [OPEN] chat line, and records
 * that they were told; their answer stops the flash and reaches every staff member here; the end takes the key away
 * and tells the other staff, not the one who ended it; a line written on another server reaches the player through the
 * notice; at login, staff lines never told bring one popup after the delay, later while the rules hold the player, and
 * never again on the next server; a line arriving during the login read is handled after it.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TalkDeliveryGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    /** What the delivery sent: packets and chat lines, by player. */
    private record Sent(ServerPlayer to, Object what) {
    }

    private static final class World {
        final MinecraftServer server;
        final MemoryBackend db = new MemoryBackend();
        final Storage s;
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final Set<UUID> staff = new HashSet<>();
        final List<ServerPlayer> here = new CopyOnWriteArrayList<>();
        final Set<UUID> atRules = new HashSet<>();
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        World(GameTestHelper h) {
            server = h.getLevel().getServer();
            s = Storage.startWith(server, db, "alpha");
            s.drain();
        }

        <T> T done(CompletableFuture<T> f) {
            s.drain();
            if (!f.isDone()) {
                throw new IllegalStateException("not answered");
            }
            return f.join();
        }

        List<Object> to(ServerPlayer p) {
            return sent.stream().filter(x -> x.to() == p).map(Sent::what).toList();
        }

        List<TalkStatePacket> states(ServerPlayer p) {
            return to(p).stream().filter(o -> o instanceof TalkStatePacket).map(o -> (TalkStatePacket) o).toList();
        }

        List<TalkPopupPacket> popups(ServerPlayer p) {
            return to(p).stream().filter(o -> o instanceof TalkPopupPacket).map(o -> (TalkPopupPacket) o).toList();
        }

        /** The lang keys of the chat lines a player got (the line's parts, in order). */
        List<String> keys(ServerPlayer p) {
            List<String> out = new ArrayList<>();
            to(p).stream().filter(o -> o instanceof Component).forEach(o -> collect((Component) o, out));
            return out;
        }

        String text(ServerPlayer p) {
            StringBuilder b = new StringBuilder();
            to(p).stream().filter(o -> o instanceof Component).forEach(o -> b.append(((Component) o).getString()).append('\n'));
            return b.toString();
        }

        void clear() {
            sent.clear();
        }
    }

    private static void collect(Component c, List<String> out) {
        if (c.getContents() instanceof TranslatableContents t) {
            out.add(t.getKey());
        }
        c.getSiblings().forEach(x -> collect(x, out));
    }

    private static List<String> commands(Component c) {
        List<String> out = new ArrayList<>();
        c.visit((style, text) -> {
            ClickEvent e = style.getClickEvent();
            if (e != null && e.getAction() == ClickEvent.Action.RUN_COMMAND && !out.contains(e.getValue())) {
                out.add(e.getValue());
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return out;
    }

    private interface Body {
        void run(World w) throws Exception;
    }

    private static void with(GameTestHelper h, Body body) {
        World w = new World(h);
        TalkService.useForTests(w.now::get, p -> w.staff.contains(p.getUUID()), (srv, id) -> w.staff.contains(id));
        TalkDelivery.useForTests(w.now::get, srv -> List.copyOf(w.here), p -> w.staff.contains(p.getUUID()), w.atRules::contains,
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

    private static final String NEW = "message.bsp_server_utilities.talk.new", AWAY = "message.bsp_server_utilities.talk.away",
            ENDED = "message.bsp_server_utilities.talk.ended", REPLIED = "message.bsp_server_utilities.talk.replied",
            STAFF_ENDED = "message.bsp_server_utilities.talk.staff_ended";

    @GameTest(template = EMPTY)
    public void a_staff_line_gives_the_player_here_the_flash_the_popup_and_the_line(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.here.add(ava);
            w.here.add(wren);
            TalkService.Result r = w.done(TalkService.staffSend(w.server, TalkService.By.of(ava), wren.getUUID(), wren.getGameProfile().getName(), "Did someone take your things?"));
            h.assertTrue(r.ok(), "sent: " + r);
            h.assertTrue(w.states(wren).equals(List.of(new TalkStatePacket(true, true))), "the key shows and flashes (D634): " + w.states(wren));
            List<TalkPopupPacket> pop = w.popups(wren);
            h.assertTrue(pop.size() == 1 && pop.get(0).writer().equals(ava.getGameProfile().getName()) && pop.get(0).count() == 1 && !pop.get(0).away(),
                    "one popup naming the writer: " + pop);
            h.assertTrue(w.keys(wren).contains(NEW) && !w.text(wren).contains("Did someone"), "the [OPEN] line, never the words (D649): " + w.text(wren));
            Component line = (Component) w.to(wren).stream().filter(o -> o instanceof Component).findFirst().orElseThrow();
            h.assertTrue(commands(line).equals(List.of("/su talk")), "[OPEN] runs /su talk (D637): " + commands(line));
            h.assertTrue(w.to(ava).isEmpty(), "staff are not told of staff lines");
            TalkStore.Talk t = w.db.talkTable().openTalk(wren.getUUID());
            h.assertTrue(t != null && t.toldAt() == t.lastStaffAt() && !t.untold(), "recorded as told, for every server (D636): " + t);
        });
    }

    @GameTest(template = EMPTY)
    public void the_answer_stops_the_flash_and_reaches_every_staff_member_here(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), greg = TestPlayers.mockServerPlayer(h), bob = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.staff.add(greg.getUUID());
            w.here.addAll(List.of(ava, greg, bob, wren));
            w.done(TalkService.staffSend(w.server, TalkService.By.of(ava), wren.getUUID(), wren.getGameProfile().getName(), "Hello"));
            w.clear();
            w.done(TalkService.playerSend(wren, "yes, my diamonds were gone this morning and also my iron and a whole lot of other things I had kept"));
            h.assertTrue(w.states(wren).equals(List.of(new TalkStatePacket(true, false))), "answered: the key stays, the flash stops: " + w.states(wren));
            for (ServerPlayer s : List.of(ava, greg)) {
                h.assertTrue(w.keys(s).contains(REPLIED), "every staff member here hears the answer (D639): " + w.text(s));
                h.assertTrue(w.text(s).contains("…") && !w.text(s).contains("whole lot of other things I had kept"), "cut to one line: " + w.text(s));
                Component line = (Component) w.to(s).get(0);
                h.assertTrue(commands(line).equals(List.of("/su talk " + wren.getGameProfile().getName())), "[OPEN] opens their conversation: " + commands(line));
            }
            h.assertTrue(w.to(bob).isEmpty(), "a player who is not staff hears nothing");
            h.assertTrue(w.popups(wren).isEmpty() && !w.keys(wren).contains(REPLIED), "the player gets no popup for their own line");
        });
    }

    @GameTest(template = EMPTY)
    public void the_end_takes_the_key_away_and_tells_the_other_staff(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), greg = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.staff.add(greg.getUUID());
            w.here.addAll(List.of(ava, greg, wren));
            String n = wren.getGameProfile().getName();
            w.done(TalkService.staffSend(w.server, TalkService.By.of(ava), wren.getUUID(), n, "Sorted."));
            w.clear();
            w.done(TalkService.end(w.server, TalkService.By.of(greg), wren.getUUID(), n));
            h.assertTrue(w.states(wren).equals(List.of(TalkStatePacket.NONE)), "the key goes (D633): " + w.states(wren));
            h.assertTrue(w.keys(wren).contains(ENDED) && w.popups(wren).isEmpty(), "the thank-you line, no popup: " + w.text(wren));
            h.assertTrue(w.keys(ava).contains(STAFF_ENDED) && w.text(ava).contains(greg.getGameProfile().getName()) && w.text(ava).contains(n),
                    "the other staff are told who ended it with whom: " + w.text(ava));
            h.assertTrue(w.to(greg).isEmpty(), "not the one who ended it");
            h.assertTrue(TalkDelivery.openOf(wren.getUUID()) == null, "nothing open here any more");
        });
    }

    @GameTest(template = EMPTY)
    public void a_line_written_on_another_server_reaches_the_player_here(GameTestHelper h) {
        with(h, w -> {
            Storage beta = Storage.startWith(w.server, w.db, "beta");
            try {
                beta.drain();
                ServerPlayer wren = TestPlayers.mockServerPlayer(h);
                // written on alpha, where the player is not
                w.done(TalkService.staffSend(w.server, TalkService.By.console("Console"), wren.getUUID(), wren.getGameProfile().getName(), "From alpha"));
                h.assertTrue(w.to(wren).isEmpty(), "nothing for them on alpha");
                h.assertTrue(w.db.talkTable().openTalk(wren.getUUID()).untold(), "not told yet");
                // the player is on beta: beta reads the notice from the table
                w.here.add(wren);
                beta.pollNotices();
                beta.drain();
                h.assertTrue(w.states(wren).equals(List.of(new TalkStatePacket(true, true))), "beta gives them the flashing key: " + w.states(wren));
                h.assertTrue(w.popups(wren).size() == 1 && w.popups(wren).get(0).writer().equals("Console") && w.keys(wren).contains(NEW), "and the popup and line");
                w.s.drain();
                beta.drain();
                h.assertTrue(!w.db.talkTable().openTalk(wren.getUUID()).untold(), "told, in the shared table");
            } finally {
                beta.close();
            }
        });
    }

    @GameTest(template = EMPTY)
    public void lines_sent_while_away_bring_one_popup_after_login_and_never_again(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            UUID id = wren.getUUID();
            String n = wren.getGameProfile().getName();
            w.done(TalkService.staffSend(w.server, TalkService.By.panel("Ava"), id, n, "First"));
            w.now.addAndGet(1000);
            w.done(TalkService.staffSend(w.server, TalkService.By.panel("Ava"), id, n, "Second"));
            w.here.add(wren);
            w.atRules.add(id); // a new player held by the rules popup
            TalkDelivery.onLogin(wren);
            w.s.drain();
            h.assertTrue(w.states(wren).equals(List.of(new TalkStatePacket(true, true))), "the key's state at login: " + w.states(wren));
            h.assertTrue(TalkDelivery.popupDue(id) && w.popups(wren).isEmpty(), "the popup waits");
            w.now.addAndGet(TalkDelivery.LOGIN_DELAY_MS + 1);
            TalkDelivery.tick(w.server);
            h.assertTrue(w.popups(wren).isEmpty(), "not while the rules hold them (D636)");
            w.atRules.remove(id);
            w.now.addAndGet(TalkDelivery.LOGIN_DELAY_MS - 10);
            TalkDelivery.tick(w.server);
            h.assertTrue(w.popups(wren).isEmpty(), "and only " + TalkDelivery.LOGIN_DELAY_MS + " ms after they were let through");
            w.now.addAndGet(20);
            TalkDelivery.tick(w.server);
            w.s.drain();
            List<TalkPopupPacket> pop = w.popups(wren);
            h.assertTrue(pop.equals(List.of(new TalkPopupPacket("Ava", 2, true))), "one popup for both lines, by their one writer, the away wording: " + pop);
            h.assertTrue(w.keys(wren).contains(AWAY) && !w.keys(wren).contains(NEW), "the away line: " + w.keys(wren));
            h.assertTrue(!w.db.talkTable().openTalk(id).untold(), "told, for every server");
            // the next server: no popup again for the same lines
            TalkDelivery.onLogout(wren);
            w.clear();
            TalkDelivery.onLogin(wren);
            w.s.drain();
            h.assertTrue(!TalkDelivery.popupDue(id) && w.states(wren).equals(List.of(new TalkStatePacket(true, true))), "the key still flashes, no second popup");
        });
    }

    @GameTest(template = EMPTY)
    public void lines_from_two_staff_while_away_name_nobody(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            UUID id = wren.getUUID();
            w.done(TalkService.staffSend(w.server, TalkService.By.panel("Ava"), id, "Wren", "One"));
            w.now.addAndGet(1000);
            w.done(TalkService.staffSend(w.server, TalkService.By.console("Console"), id, "Wren", "Two"));
            w.now.addAndGet(1000);
            w.done(TalkService.staffSend(w.server, TalkService.By.panel("Ava"), id, "Wren", "Three"));
            w.here.add(wren);
            TalkDelivery.onLogin(wren);
            w.s.drain();
            w.now.addAndGet(TalkDelivery.LOGIN_DELAY_MS + 1);
            TalkDelivery.tick(w.server);
            w.s.drain(); // the popup reads the conversation again first (audit of SU-111)
            h.assertTrue(w.popups(wren).equals(List.of(new TalkPopupPacket("", 3, true))), "three lines, two writers: nobody named (R3b): " + w.popups(wren));
        });
    }

    @GameTest(template = EMPTY)
    public void a_line_arriving_during_the_login_read_is_handled_after_it(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            UUID id = wren.getUUID();
            w.here.add(wren);
            TalkDelivery.onLogin(wren); // the read is queued, not run yet
            // meanwhile another server tells of a staff line (handed in as its notice would be)
            TalkDelivery.deliver(w.server, new TalkDelivery.Event(77, id, "Wren", TalkStore.KIND_STAFF, "Ava", "hi", w.now.get(), w.now.get(), 0, 0, TalkStore.KIND_STAFF));
            h.assertTrue(w.to(wren).isEmpty(), "held until the login read is done");
            w.s.drain();
            h.assertTrue(w.popups(wren).size() == 1, "then handled: " + w.to(wren));
            List<TalkStatePacket> st = w.states(wren);
            h.assertTrue(st.size() == 2 && st.get(0).equals(TalkStatePacket.NONE) && st.get(1).equals(new TalkStatePacket(true, true)),
                    "after the read's state, never under it: " + st);
        });
    }

    @GameTest(template = EMPTY)
    public void a_conversation_that_ends_itself_takes_the_key_away_and_tells_no_staff(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.here.addAll(List.of(ava, wren));
            w.done(TalkService.staffSend(w.server, TalkService.By.of(ava), wren.getUUID(), wren.getGameProfile().getName(), "Hello?"));
            w.clear();
            w.now.addAndGet(91L * 86_400_000L);
            w.done(TalkService.prune(w.server));
            h.assertTrue(w.states(wren).equals(List.of(TalkStatePacket.NONE)) && w.keys(wren).contains(ENDED), "the key goes after 90 days with no line (D643)");
            h.assertTrue(w.to(ava).isEmpty(), "nobody ended it: no staff line");
        });
    }
}
