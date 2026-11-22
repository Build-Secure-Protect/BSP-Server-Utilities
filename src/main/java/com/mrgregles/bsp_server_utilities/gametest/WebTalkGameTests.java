package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.talk.TalkDelivery;
import com.mrgregles.bsp_server_utilities.talk.TalkService;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebTalk;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Private staff messages from the staff web panel, the game server's part (SU-109; owner decisions D630, D632, D641,
 * D652 R6): {@code talk_send} from any panel rank writes a staff line under the staff member's name and answers with the
 * line's signed copy and where the player is; {@code talk_end} ends the conversation; the same refusals as in game (staff,
 * an empty line, nothing open, not a player); every line written on this server, from the panel or the game, is told to
 * the panel live as its signed copy; nothing is told while the panel is off here; the snapshot says the server takes them.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebTalkGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 9 + 1);
        }
    }

    private static int counter;

    private static String action(long at, String rank, String action, UUID player, String name, @Nullable String text) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("id", "talk-" + (++counter) + "-" + UUID.randomUUID());
        o.addProperty("at", at);
        o.addProperty("by", "Ana_Mod");
        o.addProperty("rank", rank);
        o.addProperty("server", Storage.currentServerId());
        o.addProperty("action", action);
        o.addProperty("player", player.toString());
        o.addProperty("name", name);
        if (text != null) {
            o.addProperty("text", text);
        }
        return WebActions.sign(KEY, o.toString());
    }

    /** The JSON inside a line signed as the game servers sign for the panel, or null when the signature does not hold. */
    @Nullable
    private static JsonObject opened(String signed) {
        int dot = signed.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String json = signed.substring(dot + 1);
        return WebActions.signText(KEY, json).equals(signed) ? JsonParser.parseString(json).getAsJsonObject() : null;
    }

    private interface Body {
        void run(MinecraftServer server, MemoryBackend db, Storage s, long now, Set<UUID> staff, List<String> live) throws Exception;
    }

    private static void with(GameTestHelper h, Body body) {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        long now = System.currentTimeMillis();
        Set<UUID> staff = new HashSet<>();
        List<String> live = new CopyOnWriteArrayList<>();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        TalkService.useForTests(() -> now, p -> staff.contains(p.getUUID()), (srv, id) -> staff.contains(id));
        TalkDelivery.useForTests(() -> now, srv -> List.of(), p -> false, id -> false, (p, o) -> {
        }, (p, c) -> {
        });
        WebTalk.sentForTests(live::add);
        Storage before = Storage.active();
        Storage.useForTests(s);
        try {
            body.run(server, db, s, now, staff, live);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            Storage.useForTests(before);
            s.close();
            WebActions.useReal();
            WebAgent.useReal();
            TalkService.useReal();
            TalkDelivery.useReal();
            WebTalk.sentForTests(null);
        }
        h.succeed();
    }

    /** Handles an action and waits for its answer (the storage's work run). */
    private static WebActions.Answer ask(MinecraftServer server, Storage s, String payload) {
        WebActions.Answer a = WebActions.handle(server, payload);
        if (a == null) {
            throw new IllegalStateException("not handled");
        }
        if (a.later() == null) {
            return a;
        }
        s.drain();
        if (!a.later().isDone()) {
            throw new IllegalStateException("no answer");
        }
        return a.later().join();
    }

    @GameTest(template = EMPTY)
    public void a_line_from_the_panel_is_written_and_answered_with_its_signed_copy(GameTestHelper h) {
        with(h, (server, db, s, now, staff, live) -> {
            UUID wren = UUID.randomUUID();
            WebActions.Answer a = ask(server, s, action(now, "MODERATOR", "talk_send", wren, "Wren_88", "Did someone take your things?"));
            h.assertTrue(a.ok(), "every rank sends (D630): " + a);
            JsonObject data = a.data().getAsJsonObject();
            JsonObject line = opened(data.get("line").getAsString());
            h.assertTrue(line != null && line.get("type").getAsString().equals("talk") && line.get("kind").getAsString().equals("staff")
                    && line.get("staff").getAsString().equals("Ana_Mod") && line.get("text").getAsString().equals("Did someone take your things?")
                    && line.get("player").getAsString().equals(wren.toString()) && line.get("name").getAsString().equals("Wren_88"),
                    "the answer holds the line's signed copy, by the staff member's name: " + line);
            h.assertTrue(data.get("where").getAsString().isEmpty() && data.get("outcome").getAsString().equals("SENT"), "offline: where is empty: " + data);
            h.assertTrue(live.size() == 1 && live.get(0).equals(data.get("line").getAsString()), "told to the panel live, the same copy: " + live);
            h.assertTrue(db.talkTable().openTalk(wren) != null && db.talkTable().openTalk(wren).openedBy().equals("Ana_Mod"), "the conversation, opened by them");
            h.assertTrue(db.loadLog(0, 100).stream().anyMatch(e -> e.actor().equals("Ana_Mod (panel)") && e.text().equals("started a private conversation with Wren_88")),
                    "the Action Log's start, by \"Ana_Mod (panel)\" (D644)");
            JsonObject snap = new JsonObject();
            WebTalk.addTo(snap);
            h.assertTrue(snap.get("talk").getAsBoolean(), "the snapshot says this server takes them");
        });
    }

    @GameTest(template = EMPTY)
    public void the_panel_ends_a_conversation_and_is_refused_as_in_game(GameTestHelper h) {
        with(h, (server, db, s, now, staff, live) -> {
            UUID wren = UUID.randomUUID(), mod = UUID.randomUUID();
            staff.add(mod);
            ask(server, s, action(now, "ADMIN", "talk_send", wren, "Wren_88", "Hello"));
            live.clear();
            WebActions.Answer end = ask(server, s, action(now, "OWNER", "talk_end", wren, "Wren_88", null));
            JsonObject line = opened(end.data().getAsJsonObject().get("line").getAsString());
            h.assertTrue(end.ok() && line != null && line.get("kind").getAsString().equals("end") && line.get("staff").getAsString().equals("Ana_Mod"), "ended, by them: " + line);
            h.assertTrue(db.talkTable().openTalk(wren) == null && live.size() == 1, "closed, and told live");
            WebActions.Answer again = ask(server, s, action(now, "OWNER", "talk_end", wren, "Wren_88", null));
            h.assertTrue(!again.ok() && again.data().getAsJsonObject().get("outcome").getAsString().equals("NO_TALK"), "nothing open: " + again.message());
            WebActions.Answer toStaff = ask(server, s, action(now, "OWNER", "talk_send", mod, "Moddy", "hi"));
            h.assertTrue(!toStaff.ok() && toStaff.message().contains("staff room"), "never to staff (D641): " + toStaff.message());
            WebActions.Answer empty = ask(server, s, action(now, "OWNER", "talk_send", wren, "Wren_88", "   "));
            h.assertTrue(!empty.ok() && empty.data().getAsJsonObject().get("outcome").getAsString().equals("EMPTY"), "nothing to send: " + empty.message());
            WebActions.Answer bad = ask(server, s, action(now, "OWNER", "talk_send", wren, "not a name!", "hi"));
            h.assertTrue(!bad.ok() && bad.message().equals("That is not a player."), "a name that is not a name: " + bad.message());
        });
    }

    @GameTest(template = EMPTY)
    public void lines_written_in_game_are_told_to_the_panel_and_nothing_while_it_is_off(GameTestHelper h) {
        with(h, (server, db, s, now, staff, live) -> {
            ServerPlayer wren = TestPlayers.mockServerPlayer(h);
            s.drain();
            TalkService.staffSend(server, TalkService.By.console("Console"), wren.getUUID(), wren.getGameProfile().getName(), "From the game");
            s.drain();
            TalkService.playerSend(wren, "my answer");
            s.drain();
            h.assertTrue(live.size() == 2, "both lines told live: " + live);
            JsonObject answer = opened(live.get(1));
            h.assertTrue(answer != null && answer.get("kind").getAsString().equals("player") && answer.get("text").getAsString().equals("my answer"), "the answer, signed: " + answer);
            live.clear();
            WebAgent.useForTests(false, null, null);
            TalkService.staffSend(server, TalkService.By.console("Console"), wren.getUUID(), wren.getGameProfile().getName(), "While the panel is off");
            s.drain();
            h.assertTrue(live.isEmpty(), "nothing told while the panel is off here");
            h.assertTrue(db.talkTable().talkLines(List.of(db.talkTable().openTalk(wren.getUUID()).id())).size() == 3, "the line is still written");
            h.assertTrue(WebActions.handle(server, action(now, "OWNER", "talk_send", wren.getUUID(), "Wren", "x")) == null, "and no action is taken");
        });
    }
}
