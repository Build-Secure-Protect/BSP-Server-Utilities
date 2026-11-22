package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import com.mrgregles.bsp_staff_panel.talk.PlayerTalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Private staff messages to players on the panel (SU-110; owner decisions D630 to D652, R6 and R7): a line shown only
 * through its signed copy, and only a copy of that very line; a signed end line closes a conversation its row calls open;
 * the open list waiting on staff first, the longest wait first, with where the player is and "not seen it yet"; SEND and END
 * sent to the player's own server, else the busiest that takes messages, never when none does; an empty or long line refused
 * before anything is asked; live lines taken once, only when signed and new; the grant missing said with the table's name.
 */
class PlayerTalkTest {
    private static final byte[] KEY = ActionsTest.KEY, OTHER = new byte[32];
    private static final String WREN = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", PEBBLE = "11111111-2222-3333-4444-555555555555", KES = "99999999-8888-7777-6666-555555555555";
    private MemoryStore store;
    private AtomicLong clock;
    private Feed feed;
    private Actions actions;
    private final List<JsonObject> asked = new CopyOnWriteArrayList<>();
    /** What the asked server answers (its data), or null for a refusal. */
    private final AtomicReference<String> answer = new AtomicReference<>();
    /** True: the asked server never answers (a slow worker). */
    private final java.util.concurrent.atomic.AtomicBoolean silent = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<JsonObject> events = new CopyOnWriteArrayList<>();
    private Account ana;

    @BeforeEach
    void start() {
        store = new MemoryStore();
        clock = new AtomicLong(1_800_000_000_000L);
        feed = new Feed(clock::get);
        Actions[] a = new Actions[1];
        a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
            String body = payload.substring(0, payload.indexOf('.'));
            JsonObject action = JsonParser.parseString(new String(java.util.Base64.getUrlDecoder().decode(body), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            asked.add(action);
            if (silent.get()) {
                return;
            }
            String data = answer.get();
            String reply = data == null ? "{\"id\":\"" + uid + "\",\"ok\":false,\"message\":\"Wren_88 has no open conversation with staff.\",\"data\":{\"outcome\":\"NO_TALK\"}}"
                    : "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Sent to Wren_88.\",\"data\":" + data + "}";
            new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(KEY, reply)))).start();
        }, clock::get, 2);
        actions = a[0];
        ana = new Account();
        ana.id = 7;
        ana.mcName = "Ana_Mod";
        ana.rank = Rank.MODERATOR;
    }

    private PlayerTalk desk() {
        PlayerTalk d = new PlayerTalk(store, feed, () -> actions, clock::get, KEY).countAtOnce();
        d.onEvent(events::add);
        return d;
    }

    private void snapshot(String server, boolean talk, String... players) {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < players.length; i += 2) {
            p.append(i == 0 ? "" : ",").append("{\"id\":\"").append(players[i]).append("\",\"name\":\"").append(players[i + 1]).append("\"}");
        }
        feed.accept(server, clock.get(), "{\"v\":1,\"server\":\"" + server + "\",\"at\":" + clock.get() + ",\"talk\":" + talk + ",\"players\":[" + p + "]}");
    }

    private static String copy(byte[] key, String uid, long talk, String player, String name, long at, String kind, String staff, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "talk");
        o.addProperty("v", 1);
        o.addProperty("uid", uid);
        o.addProperty("talk", talk);
        o.addProperty("player", player);
        o.addProperty("name", name);
        o.addProperty("at", at);
        o.addProperty("kind", kind);
        o.addProperty("staff", staff);
        o.addProperty("text", text);
        o.addProperty("server", "Frostwarf");
        return Signed.seal(key, o.toString());
    }

    /** A line as a game server writes it: the row and its signed copy. */
    private void line(long talk, String player, String name, long at, String kind, String staff, String text) {
        String uid = UUID.randomUUID().toString();
        store.addTalkLine(new PanelStore.TalkLine(0, uid, talk, player, at, kind, staff, text, "Frostwarf", copy(KEY, uid, talk, player, name, at, kind, staff, text)));
    }

    private List<JsonObject> lines() {
        return events.stream().filter(e -> e.get("event").getAsString().equals("line")).toList();
    }

    private void talk(long id, String player, String name, long opened, String by, long lastStaff, long lastPlayer, long told) {
        store.putTalk(new PanelStore.Talk(id, player, name, opened, by, 0, "", lastStaff, lastPlayer, told));
    }

    @Test
    void lines_are_shown_only_through_their_own_signed_copies() {
        long t0 = clock.get() - 3_600_000;
        talk(1, WREN, "Wren_88", t0, "Ana_Mod", t0, t0 + 60_000, t0);
        line(1, WREN, "Wren_88", t0, "staff", "Ana_Mod", "Did someone take your things?");
        String uid = UUID.randomUUID().toString();
        // the row's text changed in the database: the copy says what was written
        store.addTalkLine(new PanelStore.TalkLine(0, uid, 1, WREN, t0 + 60_000, "player", "", "forged words", "Frostwarf",
                copy(KEY, uid, 1, WREN, "Wren_88", t0 + 60_000, "player", "", "yes, my diamonds")));
        String bad = UUID.randomUUID().toString();
        store.addTalkLine(new PanelStore.TalkLine(0, bad, 1, WREN, t0 + 70_000, "staff", "Greg", "made up", "Frostwarf",
                copy(OTHER, bad, 1, WREN, "Wren_88", t0 + 70_000, "staff", "Greg", "made up"))); // not this panel's key
        String moved = UUID.randomUUID().toString();
        store.addTalkLine(new PanelStore.TalkLine(0, moved, 1, WREN, t0 + 80_000, "staff", "Ana_Mod", "x", "Frostwarf",
                copy(KEY, UUID.randomUUID().toString(), 1, WREN, "Wren_88", t0 + 80_000, "staff", "Ana_Mod", "x"))); // another line's copy
        JsonObject h = desk().history(WREN, "");
        assertTrue(h.get("ok").getAsBoolean(), h.toString());
        JsonArray lines = h.getAsJsonArray("talks").get(0).getAsJsonObject().getAsJsonArray("lines");
        assertEquals(2, lines.size(), "only the two lines whose copies hold: " + lines);
        assertEquals("yes, my diamonds", lines.get(1).getAsJsonObject().get("text").getAsString(), "the copy's words, never the row's");
        assertEquals(2, h.get("unverified").getAsInt(), "the others counted");
        JsonObject open = h.getAsJsonObject("open");
        assertEquals("staff", open.get("waiting").getAsString(), "the player wrote last: staff are waiting");
        assertEquals(clock.get() - (t0 + 60_000), open.get("waitMs").getAsLong());
        assertEquals("Wren_88", h.get("name").getAsString());
        assertEquals("", h.get("where").getAsString(), "offline");
    }

    @Test
    void a_signed_end_line_closes_a_conversation_its_row_calls_open() {
        long t0 = clock.get() - 600_000;
        talk(2, WREN, "Wren_88", t0, "Ana_Mod", t0, 0, t0);
        line(2, WREN, "Wren_88", t0, "staff", "Ana_Mod", "Sorted now.");
        line(2, WREN, "Wren_88", t0 + 1000, "end", "Greg", "");
        PlayerTalk d = desk();
        JsonObject h = d.history(WREN, "");
        JsonObject t = h.getAsJsonArray("talks").get(0).getAsJsonObject();
        assertFalse(t.get("open").getAsBoolean(), "ended by the signed end line");
        assertEquals("Greg", t.get("endedBy").getAsString());
        assertFalse(h.has("open"));
        assertEquals(0, d.list().getAsJsonArray("open").size(), "not in the open list");
    }

    @Test
    void the_open_list_puts_waiting_replies_first_the_longest_wait_first() {
        long now = clock.get();
        talk(1, WREN, "Wren_88", now - 600_000, "Ana", now - 600_000, now - 120_000, now - 600_000);
        line(1, WREN, "Wren_88", now - 600_000, "staff", "Ana", "hi");
        line(1, WREN, "Wren_88", now - 120_000, "player", "", "answered later");
        talk(2, PEBBLE, "Pebble", now - 900_000, "Ana", now - 900_000, now - 540_000, now - 900_000);
        line(2, PEBBLE, "Pebble", now - 900_000, "staff", "Ana", "hi");
        line(2, PEBBLE, "Pebble", now - 540_000, "player", "", "answered first");
        talk(3, KES, "Kestrel_x", now - 7_200_000, "Mrgregles", now - 7_200_000, 0, 0);
        line(3, KES, "Kestrel_x", now - 7_200_000, "staff", "Mrgregles", "Please read the rules.");
        snapshot("Frostwarf", true, WREN, "Wren_88");
        JsonObject l = desk().list();
        JsonArray open = l.getAsJsonArray("open");
        assertEquals(3, open.size());
        assertEquals(PEBBLE, open.get(0).getAsJsonObject().get("player").getAsString(), "the longest wait first");
        assertEquals(WREN, open.get(1).getAsJsonObject().get("player").getAsString());
        assertEquals(KES, open.get(2).getAsJsonObject().get("player").getAsString(), "then the ones waiting on the player");
        assertEquals(2, l.get("waiting").getAsInt(), "the red count on More");
        assertEquals("Frostwarf", open.get(1).getAsJsonObject().get("where").getAsString(), "where an online player is");
        assertTrue(open.get(2).getAsJsonObject().get("notSeen").getAsBoolean(), "offline and not told: not seen it yet");
        assertFalse(open.get(0).getAsJsonObject().get("notSeen").getAsBoolean());
        assertEquals("Please read the rules.", open.get(2).getAsJsonObject().getAsJsonObject("last").get("text").getAsString());
        assertEquals(540_000, open.get(0).getAsJsonObject().get("sinceMs").getAsLong());
    }

    @Test
    void send_and_end_go_to_the_players_server_or_any_that_takes_messages() {
        PlayerTalk d = desk();
        JsonObject none = d.send(ana, WREN, "Wren_88", "hello");
        assertFalse(none.get("ok").getAsBoolean());
        assertTrue(none.get("message").getAsString().contains("No game server"), "no server takes messages: said, " + none);
        assertTrue(asked.isEmpty(), "nothing asked");
        snapshot("Spawn", true, "x1", "A", "x2", "B");
        snapshot("Frostwarf", true, WREN, "Wren_88");
        snapshot("Sanctuary", false, "x3", "C", "x4", "D", "x5", "E");
        String line = copy(KEY, "u-1", 4, WREN, "Wren_88", clock.get(), "staff", "Ana_Mod", "hello");
        answer.set("{\"outcome\":\"SENT\",\"line\":\"" + line.replace("\"", "\\\"") + "\",\"where\":\"Frostwarf\"}");
        JsonObject sent = d.send(ana, WREN, "Wren_88", "hello");
        assertTrue(sent.get("ok").getAsBoolean(), sent.toString());
        JsonObject a = asked.get(0);
        assertEquals("talk_send", a.get("action").getAsString());
        assertEquals("Frostwarf", a.get("server").getAsString(), "the player's own server");
        assertEquals("Ana_Mod", a.get("by").getAsString(), "by the staff account's Minecraft name");
        assertEquals("MODERATOR", a.get("rank").getAsString(), "every rank (D630)");
        assertEquals(WREN, a.get("player").getAsString());
        assertEquals("hello", a.get("text").getAsString());
        assertEquals("hello", sent.getAsJsonObject("line").get("text").getAsString(), "the line shown at once");
        assertEquals(1, lines().size(), "and told to every page");
        assertTrue(d.acceptMessage("u\n" + clock.get() + "\nFrostwarf\ntalk\n" + line), "the same line live");
        assertEquals(1, lines().size(), "once");
        d.send(ana, PEBBLE, "Pebble", "you there?");
        assertEquals("Spawn", asked.get(1).get("server").getAsString(), "offline: the busiest server that takes messages, never one that does not");
        answer.set(null);
        JsonObject end = d.end(ana, WREN, "Wren_88");
        assertEquals("talk_end", asked.get(2).get("action").getAsString());
        assertEquals("Wren_88", asked.get(2).get("name").getAsString());
        assertFalse(end.get("ok").getAsBoolean());
        assertEquals("NO_TALK", end.get("outcome").getAsString(), "the server's refusal passed on");
    }

    @Test
    void empty_long_and_bad_requests_are_refused_before_anything_is_asked() {
        snapshot("Frostwarf", true);
        PlayerTalk d = desk();
        assertFalse(d.send(ana, WREN, "Wren_88", "   ").get("ok").getAsBoolean());
        assertFalse(d.send(ana, WREN, "Wren_88", "x".repeat(257)).get("ok").getAsBoolean());
        assertFalse(d.send(ana, "not-a-uuid", "Wren_88", "hi").get("ok").getAsBoolean());
        assertFalse(d.end(ana, WREN, "not a name").get("ok").getAsBoolean());
        assertFalse(d.history("nope", "").get("ok").getAsBoolean());
        assertTrue(asked.isEmpty(), "nothing asked");
    }

    @Test
    void live_lines_are_taken_once_only_when_signed_and_new() {
        snapshot("Frostwarf", true);
        PlayerTalk d = desk();
        assertFalse(d.acceptMessage("u\n1\nFrostwarf\nsteal\n{}"), "another kind is not ours");
        assertTrue(d.acceptMessage("u\n1\nFrostwarf\ntalk\n" + copy(OTHER, "u-9", 1, WREN, "Wren_88", clock.get(), "player", "", "x")));
        assertTrue(events.isEmpty(), "not signed with this panel's key: dropped");
        assertTrue(d.acceptMessage("u\n1\nFrostwarf\ntalk\n" + copy(KEY, "u-8", 1, WREN, "Wren_88", clock.get() - 3_600_000, "player", "", "x")));
        assertTrue(events.isEmpty(), "an hour old: not news");
        talk(1, WREN, "Wren_88", clock.get() - 5000, "Ana", clock.get() - 5000, clock.get(), 0);
        line(1, WREN, "Wren_88", clock.get(), "player", "", "help please");
        String live = copy(KEY, "u-7", 1, WREN, "Wren_88", clock.get(), "player", "", "help please");
        assertTrue(d.acceptMessage("u\n1\nFrostwarf\ntalk\n" + live));
        assertTrue(d.acceptMessage("u\n1\nFrostwarf\ntalk\n" + live));
        assertEquals(1, lines().size(), "taken once: " + events);
        JsonObject e = lines().get(0);
        assertEquals("help please", e.getAsJsonObject("line").get("text").getAsString());
        JsonObject count = events.stream().filter(x -> x.get("event").getAsString().equals("count")).reduce((a, b) -> b).orElseThrow();
        assertEquals(1, count.get("waiting").getAsInt(), "then the waiting count, worked out apart from the Redis thread");
        assertEquals(1, d.waiting());
    }

    @Test
    void a_missing_grant_is_said_with_the_tables_name() {
        store.failTalkReads = true;
        PlayerTalk d = desk();
        JsonObject h = d.history(WREN, "");
        assertFalse(h.get("ok").getAsBoolean());
        assertTrue(h.get("message").getAsString().contains("bsp_su_talk_lines") && !h.get("message").getAsString().contains("denied"),
                "the grant named, the database's own error kept for the console: " + h);
        assertEquals(0, d.list().get("waiting").getAsInt());
    }

    @Test
    void a_conversation_row_moved_to_another_player_carries_none_of_its_lines() {
        long t0 = clock.get() - 60_000;
        talk(1, WREN, "Wren_88", t0, "Ana", t0, t0 + 1000, t0);
        line(1, WREN, "Wren_88", t0, "staff", "Ana", "about your chest");
        line(1, WREN, "Wren_88", t0 + 1000, "player", "", "my diamonds");
        talk(1, PEBBLE, "Pebble", t0, "Ana", t0, t0 + 1000, t0); // someone with the database moves the conversation to Pebble
        PlayerTalk d = desk();
        JsonObject h = d.history(PEBBLE, "");
        JsonArray shown = h.getAsJsonArray("talks").get(0).getAsJsonObject().getAsJsonArray("lines");
        assertEquals(0, shown.size(), "none of Wren's lines on Pebble's card: " + h);
        assertEquals(2, h.get("unverified").getAsInt());
        assertFalse(h.has("open"), "nothing to answer either");
        assertEquals(0, d.list().getAsJsonArray("open").size(), "nor in the open list");
    }

    @Test
    void who_started_or_ended_a_conversation_comes_only_from_signed_lines() {
        long t0 = clock.get() - 60_000;
        store.putTalk(new PanelStore.Talk(3, WREN, "Wren_88", t0, "Forged", 0, "", t0, 0, t0));
        line(3, WREN, "Wren_88", t0 + 10, "staff", "Ana_Mod", "hello");
        JsonObject h = desk().history(WREN, "");
        assertEquals("Ana_Mod", h.getAsJsonObject("open").get("by").getAsString(), "the first signed staff line, never the row's opened_by");
        assertEquals(t0 + 10, h.getAsJsonObject("open").get("since").getAsLong());
        store.putTalk(new PanelStore.Talk(3, WREN, "Wren_88", t0, "Forged", t0 + 20, "Forger", t0, 0, t0)); // the row says ended, with no signed end
        JsonObject again = desk().history(WREN, "");
        JsonObject t = again.getAsJsonArray("talks").get(0).getAsJsonObject();
        assertTrue(t.get("open").getAsBoolean() && t.get("endedBy").getAsString().isEmpty(), "nobody is named as ending it without a signed end line: " + t);
    }

    @Test
    void send_is_not_offered_when_no_server_can_answer_and_a_late_answer_never_invites_a_second_copy() {
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":" + clock.get() + ",\"talk\":true,\"answers\":false,\"players\":[]}");
        PlayerTalk d = desk();
        assertFalse(d.history(WREN, "Wren_88").get("canWrite").getAsBoolean(), "its Redis is down: SEND not offered");
        snapshot("Spawn", true);
        assertTrue(d.history(WREN, "Wren_88").get("canWrite").getAsBoolean());
        silent.set(true);
        JsonObject r = d.send(ana, WREN, "Wren_88", "hello");
        assertFalse(r.get("ok").getAsBoolean());
        assertTrue(r.get("late").getAsBoolean() && r.get("message").getAsString().contains("may still have been sent"), "said as perhaps sent: " + r);
    }
}
