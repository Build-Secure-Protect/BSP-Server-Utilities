package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.TalkActionPacket;
import com.mrgregles.bsp_server_utilities.network.TalkAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.TalkLinePacket;
import com.mrgregles.bsp_server_utilities.network.TalkViewPacket;
import com.mrgregles.bsp_server_utilities.players.Joined;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.talk.TalkCommands;
import com.mrgregles.bsp_server_utilities.talk.TalkDelivery;
import com.mrgregles.bsp_server_utilities.talk.TalkService;
import com.mrgregles.bsp_server_utilities.talk.TalkWindows;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The commands and windows of private staff messages (SU-107; owner decisions D637, D638, D641, D652 R1, R2, R4):
 * {@code /su msg} finds a player online, offline or unknown and answers where they are; players who are not staff cannot
 * use the staff commands; {@code /su talk} opens the player's own window with only the open conversation; {@code /su talk
 * <player>} opens the staff window with earlier conversations, where they are and who started it, and refuses staff;
 * lines arrive live in every open window, the window's SEND and END work and a refusal comes back with its reason; a
 * closed window gets nothing; {@code /su talks} lists the open conversations, waiting replies first.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TalkCommandGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private record Sent(ServerPlayer to, Object what) {
    }

    /** A command source that keeps what it is told. */
    private static final class Ear implements CommandSource {
        final List<Component> heard = new CopyOnWriteArrayList<>();

        @Override
        public void sendSystemMessage(Component c) {
            heard.add(c);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        /** Every lang key and literal text in what was heard, with the arguments, flattened. */
        String flat() {
            StringBuilder b = new StringBuilder();
            heard.forEach(c -> {
                flatten(c, b);
                b.append('\n');
            });
            return b.toString();
        }
    }

    private static void flatten(Component c, StringBuilder b) {
        if (c.getContents() instanceof TranslatableContents t) {
            b.append('<').append(t.getKey());
            for (Object a : t.getArgs()) {
                b.append('|');
                if (a instanceof Component ac) {
                    flatten(ac, b);
                } else {
                    b.append(a);
                }
            }
            b.append('>');
        } else if (c.getContents() instanceof LiteralContents l) {
            b.append(l.text());
        }
        c.getSiblings().forEach(x -> flatten(x, b));
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

        /** Runs a command as this player, its answers to {@code ear}; the storage's work run after. */
        void run(ServerPlayer p, Ear ear, String command) throws CommandSyntaxException {
            CommandSourceStack src = p.createCommandSourceStack().withSource(ear).withPermission(0);
            server.getCommands().getDispatcher().execute(command, src);
            s.drain();
        }

        <T> List<T> sentTo(ServerPlayer p, Class<T> kind) {
            return sent.stream().filter(x -> x.to() == p && kind.isInstance(x.what())).map(x -> kind.cast(x.what())).toList();
        }
    }

    private interface Body {
        void run(World w) throws Exception;
    }

    private static void with(GameTestHelper h, Body body) {
        World w = new World(h);
        TalkService.useForTests(w.now::get, p -> w.staff.contains(p.getUUID()), (srv, id) -> w.staff.contains(id));
        TalkDelivery.useForTests(w.now::get, srv -> List.copyOf(w.here), p -> w.staff.contains(p.getUUID()), id -> false,
                (p, o) -> w.sent.add(new Sent(p, o)), (p, c) -> w.sent.add(new Sent(p, c)));
        TalkCommands.useClockForTests(w.now::get);
        TalkWindows.resetForTests();
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
            TalkCommands.useClockForTests(null);
            TalkWindows.resetForTests();
        }
        h.succeed();
    }

    private static String name(ServerPlayer p) {
        return p.getGameProfile().getName();
    }

    @GameTest(template = EMPTY)
    public void su_msg_finds_the_player_online_offline_or_not_at_all(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.here.addAll(List.of(ava, wren));
            Ear ear = new Ear();
            w.run(ava, ear, "su msg " + name(wren) + " Did someone take your things?");
            h.assertTrue(ear.flat().contains("<message.bsp_server_utilities.talk.cmd.sent_online|" + name(wren) + "|alpha>"), "sent, online here: " + ear.flat());
            h.assertTrue(w.db.talkTable().openTalk(wren.getUUID()) != null, "the conversation is open");
            UUID pebble = UUID.randomUUID();
            w.db.insertPlayer(new Joined(pebble, "PebbleAway", w.now.get(), "beta"));
            ear.heard.clear();
            w.run(ava, ear, "su msg pebbleaway Your claim is back.");
            h.assertTrue(ear.flat().contains("<message.bsp_server_utilities.talk.cmd.sent_offline|PebbleAway>"), "found in the network's list, any case: " + ear.flat());
            h.assertTrue(w.db.talkTable().openTalk(pebble) != null, "written for when they log on");
            ear.heard.clear();
            w.run(ava, ear, "su msg Nobody_Here_123 hello");
            h.assertTrue(ear.flat().contains("<message.bsp_server_utilities.talk.cmd.unknown|Nobody_Here_123>"), "nobody of that name: " + ear.flat());
            ear.heard.clear();
            UUID mod = UUID.randomUUID();
            w.staff.add(mod);
            w.db.insertPlayer(new Joined(mod, "ModMolly", w.now.get(), "beta"));
            w.run(ava, ear, "su msg ModMolly hello");
            h.assertTrue(ear.flat().contains("talk.cmd.refused.to_staff|ModMolly"), "never to staff (D641): " + ear.flat());
        });
    }

    @GameTest(template = EMPTY)
    public void players_who_are_not_staff_have_only_su_talk(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer bob = TestPlayers.mockServerPlayer(h);
            w.here.add(bob);
            Ear ear = new Ear();
            for (String cmd : List.of("su msg someone hello", "su talks", "su talk someone")) {
                boolean refused = false;
                try {
                    w.run(bob, ear, cmd);
                } catch (CommandSyntaxException e) {
                    refused = true;
                }
                h.assertTrue(refused, "a player who is not staff cannot run " + cmd);
            }
            w.run(bob, ear, "su talk");
            h.assertTrue(ear.flat().contains("talk.cmd.none_open"), "/su talk works for them, and says when nothing is open: " + ear.flat());
            h.assertTrue(w.sentTo(bob, TalkViewPacket.class).isEmpty(), "no window without a conversation");
        });
    }

    @GameTest(template = EMPTY)
    public void su_talk_opens_the_players_window_with_the_open_conversation_only(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            w.here.add(wren);
            UUID id = wren.getUUID();
            w.s.drain();
            done(w, TalkService.staffSend(w.server, TalkService.By.panel("Ava"), id, name(wren), "Old one"));
            done(w, TalkService.end(w.server, TalkService.By.panel("Ava"), id, name(wren)));
            w.now.addAndGet(1000);
            done(w, TalkService.staffSend(w.server, TalkService.By.panel("Greg"), id, name(wren), "New one"));
            w.run(wren, new Ear(), "su talk");
            List<TalkViewPacket> v = w.sentTo(wren, TalkViewPacket.class);
            h.assertTrue(v.size() == 1 && !v.get(0).staff() && v.get(0).player().equals(id), "the player's window: " + v);
            List<TalkViewPacket.Entry> lines = v.get(0).lines();
            h.assertTrue(lines.size() == 1 && lines.get(0).text().equals("New one") && lines.get(0).staff().equals("Greg") && lines.get(0).kind() == TalkViewPacket.STAFF,
                    "only the open conversation, never the ended one (D633): " + lines);
            h.assertTrue(id.equals(TalkWindows.watching(id)), "its lines will arrive live");
        });
    }

    private static <T> T done(World w, java.util.concurrent.CompletableFuture<T> f) {
        w.s.drain();
        return f.join();
    }

    @GameTest(template = EMPTY)
    public void su_talk_player_opens_the_staff_window_with_90_days_and_where_they_are(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.here.addAll(List.of(ava, wren));
            UUID id = wren.getUUID();
            done(w, TalkService.staffSend(w.server, TalkService.By.panel("Mrgregles"), id, name(wren), "Your claim is too close to spawn."));
            done(w, TalkService.end(w.server, TalkService.By.panel("Mrgregles"), id, name(wren)));
            w.now.addAndGet(60_000);
            done(w, TalkService.staffSend(w.server, TalkService.By.panel("Ava_Builds"), id, name(wren), "We put your claim back."));
            w.run(ava, new Ear(), "su talk " + name(wren));
            List<TalkViewPacket> v = w.sentTo(ava, TalkViewPacket.class);
            h.assertTrue(v.size() == 1 && v.get(0).staff() && v.get(0).name().equals(name(wren)), "the staff window: " + v);
            TalkViewPacket p = v.get(0);
            h.assertTrue(p.where().equals("alpha") && p.openedBy().equals("Ava_Builds") && p.openSince() > 0, "where they are and who started it (R2): " + p);
            h.assertTrue(p.seen(), "they were told (online here)");
            List<Byte> kinds = p.lines().stream().map(TalkViewPacket.Entry::kind).toList();
            h.assertTrue(kinds.equals(List.of(TalkViewPacket.STAFF, TalkViewPacket.END, TalkViewPacket.STAFF)), "the earlier conversation with its end, then the open one: " + kinds);
            UUID mod = UUID.randomUUID();
            w.staff.add(mod);
            w.db.insertPlayer(new Joined(mod, "ModMolly", w.now.get(), "beta"));
            Ear ear = new Ear();
            w.run(ava, ear, "su talk ModMolly");
            h.assertTrue(ear.flat().contains("talk.cmd.refused.to_staff") && w.sentTo(ava, TalkViewPacket.class).size() == 1, "refused for staff (D641): " + ear.flat());
        });
    }

    @GameTest(template = EMPTY)
    public void lines_arrive_live_and_the_windows_keys_work(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), greg = TestPlayers.mockServerPlayer(h), wren = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.staff.add(greg.getUUID());
            w.here.addAll(List.of(ava, greg, wren));
            UUID id = wren.getUUID();
            w.run(ava, new Ear(), "su msg " + name(wren) + " Hello");
            w.run(wren, new Ear(), "su talk");
            w.run(ava, new Ear(), "su talk " + name(wren));
            w.sent.clear();
            TalkWindows.onAction(wren, new TalkActionPacket(TalkActionPacket.SEND, id, "hi, yes"));
            w.s.drain();
            for (ServerPlayer p : List.of(ava, wren)) {
                List<TalkLinePacket> l = w.sentTo(p, TalkLinePacket.class);
                h.assertTrue(l.size() == 1 && l.get(0).line().kind() == TalkViewPacket.PLAYER && l.get(0).line().text().equals("hi, yes"), "the answer arrives live in both windows: " + l);
            }
            h.assertTrue(w.sentTo(greg, TalkLinePacket.class).isEmpty(), "not to staff without the window open");
            w.sent.clear();
            TalkWindows.onAction(ava, new TalkActionPacket(TalkActionPacket.SEND, id, "   "));
            w.s.drain();
            h.assertTrue(w.sentTo(ava, TalkAnswerPacket.class).equals(List.of(new TalkAnswerPacket("EMPTY"))), "a refusal comes back with its reason: " + w.sentTo(ava, TalkAnswerPacket.class));
            TalkWindows.onAction(wren, new TalkActionPacket(TalkActionPacket.END, id, ""));
            w.s.drain();
            h.assertTrue(w.db.talkTable().openTalk(id) != null, "the player's window has no END");
            TalkWindows.onAction(ava, new TalkActionPacket(TalkActionPacket.SEND, UUID.randomUUID(), "to someone else"));
            w.s.drain();
            h.assertTrue(w.sentTo(ava, TalkLinePacket.class).isEmpty(), "a staff key for a window that is not open does nothing");
            TalkWindows.onAction(ava, new TalkActionPacket(TalkActionPacket.END, id, ""));
            w.s.drain();
            for (ServerPlayer p : List.of(ava, wren)) {
                h.assertTrue(w.sentTo(p, TalkLinePacket.class).stream().anyMatch(l -> l.line().kind() == TalkViewPacket.END), "the end arrives in both windows");
            }
            h.assertTrue(w.db.talkTable().openTalk(id) == null, "ended");
            TalkWindows.onAction(wren, new TalkActionPacket(TalkActionPacket.CLOSE, id, ""));
            w.sent.clear();
            w.run(ava, new Ear(), "su msg " + name(wren) + " One more thing");
            h.assertTrue(w.sentTo(wren, TalkLinePacket.class).isEmpty() && w.sentTo(ava, TalkLinePacket.class).size() == 1,
                    "a closed window gets nothing; the staff window still open gets the new conversation's line");
        });
    }

    @GameTest(template = EMPTY)
    public void su_talks_lists_waiting_replies_first(GameTestHelper h) {
        with(h, w -> {
            ServerPlayer ava = TestPlayers.mockServerPlayer(h), early = TestPlayers.mockServerPlayer(h), late = TestPlayers.mockServerPlayer(h);
            w.staff.add(ava.getUUID());
            w.here.addAll(List.of(ava, early, late));
            UUID quiet = UUID.randomUUID();
            done(w, TalkService.staffSend(w.server, TalkService.By.panel("Ava"), quiet, "QuietOne", "Hello?"));
            done(w, TalkService.staffSend(w.server, TalkService.By.panel("Ava"), early.getUUID(), name(early), "Hello?"));
            done(w, TalkService.staffSend(w.server, TalkService.By.panel("Ava"), late.getUUID(), name(late), "Hello?"));
            w.now.addAndGet(60_000);
            done(w, TalkService.playerSend(early, "answered first"));
            w.now.addAndGet(5 * 60_000);
            done(w, TalkService.playerSend(late, "answered later"));
            w.now.addAndGet(4 * 60_000);
            Ear ear = new Ear();
            w.run(ava, ear, "su talks");
            String f = ear.flat();
            int a = f.indexOf(name(early)), b = f.indexOf(name(late)), c = f.indexOf("QuietOne");
            h.assertTrue(f.contains("talk.cmd.open_header|3") && a >= 0 && a < b && b < c, "waiting longest first, then the rest: " + f);
            h.assertTrue(f.contains(name(early) + " <message.bsp_server_utilities.talk.cmd.waiting|9 min>") && f.contains(name(late) + " <message.bsp_server_utilities.talk.cmd.waiting|4 min>"),
                    "how long each waited: " + f);
            h.assertTrue(f.contains("QuietOne <message.bsp_server_utilities.talk.cmd.sent|10 min>") && f.contains("talk.cmd.offline_unseen"), "sent, offline, not seen yet: " + f);
            List<String> clicks = new ArrayList<>();
            ear.heard.forEach(line -> line.visit((style, text) -> {
                if (style.getClickEvent() != null) {
                    clicks.add(style.getClickEvent().getValue());
                }
                return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY));
            h.assertTrue(clicks.contains("/su talk " + name(early)) && clicks.contains("/su talk QuietOne"), "each name opens the staff window: " + clicks);
        });
    }
}
