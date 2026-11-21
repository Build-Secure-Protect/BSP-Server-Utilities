package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import com.mrgregles.bsp_staff_panel.totems.TotemDesk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TOTEMS page's desk (SU-97 and the audit's fixes, SU-98; owner decisions D613 to D622): the board read from a server
 * that can, the same one while it answers, the next when it cannot or has only just started; each change of points
 * recorded once (the first sighting marked, a player gone from a whole board at 0, never from an empty or cut board, nothing
 * again after a restart or a failed save); live steals taken only when signed; a missed end forgotten; rows shown only
 * through their signed copies; a steal faster than BSP-Core allows, a burst of three in ten minutes (also across the
 * window's start) and the time between steals; the database's own errors kept off the page; one player's steals and points.
 */
class TotemsTest {
    private static final byte[] KEY = ActionsTest.KEY;
    private static final String VEXA = "11111111-2222-3333-4444-555555555555", WREN = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private MemoryStore store;
    private AtomicLong clock;
    private Feed feed;
    private Actions actions;
    private final List<JsonObject> asked = new CopyOnWriteArrayList<>();
    /** Each server's answer to totem_board (its data), by server. */
    private final Map<String, String> boards = new ConcurrentHashMap<>();

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
            String data = boards.get(action.get("server").getAsString());
            String reply = data == null ? "{\"id\":\"" + uid + "\",\"ok\":false,\"message\":\"BSP-Core answered with an error\"}"
                    : "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"read\",\"data\":" + data + "}";
            new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(KEY, reply)))).start();
        }, clock::get, 2);
        actions = a[0];
    }

    private void snapshot(String server, boolean totems, int players) {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < players; i++) {
            p.append(i == 0 ? "" : ",").append("{\"id\":\"p").append(i).append("\",\"name\":\"P").append(i).append("\"}");
        }
        feed.accept(server, clock.get(), "{\"v\":1,\"server\":\"" + server + "\",\"at\":" + clock.get() + ",\"totems\":" + totems + ",\"lotteryUp\":true,\"players\":[" + p + "]}");
    }

    private static String line(String id, String name, int rank, int points, int totems, int tier) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"rank\":" + rank + ",\"points\":" + points + ",\"totems\":" + totems + ",\"tier\":" + tier + "}";
    }

    private static String board(String... lines) {
        return "{\"v\":1,\"lines\":" + lines.length + ",\"ready\":true,\"board\":[" + String.join(",", lines) + "],\"stealing\":[]}";
    }

    private TotemDesk desk(byte[] key) {
        return new TotemDesk(store, feed, () -> actions, clock::get, key);
    }

    private void tick() {
        clock.addAndGet(30_000);
    }

    @Test
    void the_board_is_read_from_an_able_server_and_each_change_of_points_recorded_once() throws Exception {
        TotemDesk d = desk(KEY);
        snapshot("Sanctuary", false, 0);
        d.poll();
        assertTrue(d.problem().contains("No game server") && asked.isEmpty(), "no server with the board: said, nothing asked");

        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 10, 3, 3), line(WREN, "Wren_88", 2, 4, 1, 2)));
        d.poll();
        assertEquals("totem_board", asked.get(0).get("action").getAsString(), "the read asked for");
        assertEquals("Frostwarf", asked.get(0).get("server").getAsString(), "of the able server");
        List<PanelStore.PointsChange> first = store.points(0, "", 50);
        assertEquals(2, first.size());
        assertTrue(first.stream().allMatch(p -> p.first() && p.before() == 0), "the first sighting is where the line starts, never a gain: " + first);

        tick();
        snapshot("Frostwarf", true, 0);
        d.poll();
        assertEquals(2, store.points(0, "", 50).size(), "nothing changed: nothing recorded");

        tick();
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 14, 4, 3)));
        d.poll();
        List<PanelStore.PointsChange> now = store.points(0, "", 50);
        assertEquals(4, now.size(), "Vexa up, Wren gone: " + now);
        assertTrue(now.stream().anyMatch(p -> p.player().equals(VEXA) && p.before() == 10 && p.after() == 14 && !p.first()), "Vexa's gain");
        assertTrue(now.stream().anyMatch(p -> p.player().equals(WREN) && p.before() == 4 && p.after() == 0), "Wren off a whole board: at 0");

        TotemDesk restarted = desk(KEY); // the panel started again: the table says where everyone stood
        tick();
        snapshot("Frostwarf", true, 0);
        restarted.poll();
        assertEquals(4, store.points(0, "", 50).size(), "no second first sighting after a restart");

        JsonObject page = restarted.page(7);
        JsonObject vexa = page.getAsJsonArray("board").get(0).getAsJsonObject();
        assertTrue(vexa.get("gained").getAsInt() == 4 && vexa.get("lost").getAsInt() == 0 && page.get("boardServer").getAsString().equals("Frostwarf"), "points gained in the window: " + vexa);
        assertTrue(page.getAsJsonArray("board").size() == 1 && page.get("boardAt").getAsLong() > 0);
    }

    @Test
    void the_open_pages_hear_of_a_board_read_only_when_it_changed() {
        TotemDesk d = desk(KEY);
        List<JsonObject> events = new ArrayList<>();
        d.onEvent(events::add);
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 10, 3, 3)));
        d.poll();
        assertEquals(1, events.size(), "the first board");
        tick();
        snapshot("Frostwarf", true, 0);
        d.poll();
        assertEquals(1, events.size(), "the same board again: no event, so no page reloads every 30 s (audit of SU-99)");
        tick();
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 12, 3, 3)));
        d.poll();
        assertEquals(2, events.size(), "a change: one event");
        d.acceptMessage(message("Frostwarf", "steal", Signed.seal(KEY, steal("start", "s-start", "Frostwarf", clock.get(), ",\"source\":\"forge\",\"tier\":1"))));
        JsonObject running = d.page(7).getAsJsonArray("stealing").get(0).getAsJsonObject();
        assertTrue(running.get("tier").getAsInt() == 1 && running.get("source").getAsString().equals("forge"), "a running steal keeps what the start said it is: " + running);
    }

    @Test
    void a_players_line_starts_where_their_points_stood_before_the_window() throws Exception {
        store.insertPoints(List.of(new PanelStore.PointsChange(clock.get() - 20 * TotemDesk.DAY, VEXA, "Vexa", 0, 17, 3, 3, true)));
        JsonObject p = desk(KEY).player(VEXA, 7);
        assertEquals(17, p.get("start").getAsInt(), "no change in the window: the newest one before it");
        assertEquals("Vexa", p.get("name").getAsString());
    }

    @Test
    void an_empty_a_cut_or_an_unready_board_never_sets_anyone_to_zero() throws Exception {
        TotemDesk d = desk(KEY);
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 10, 3, 3), line(WREN, "Wren_88", 2, 4, 1, 2)));
        d.poll();
        tick();
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", "{\"v\":1,\"lines\":1500,\"ready\":true,\"board\":[" + line(VEXA, "Vexa", 1, 10, 3, 3) + "],\"stealing\":[]}");
        d.poll();
        assertEquals(2, store.points(0, "", 50).size(), "Wren is past the board's 1,000 lines, not gone");
        tick();
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board());
        d.poll();
        assertEquals(2, store.points(0, "", 50).size(), "an empty board while points were held: a server that has not read the network yet, nobody zeroed (audit)");
        tick();
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", "{\"v\":1,\"lines\":0,\"ready\":false,\"board\":[],\"stealing\":[]}");
        d.poll();
        assertTrue(d.problem().contains("only just started"), "a server whose BSP-Core has not built its board: passed over, said: " + d.problem());
        assertEquals(2, store.points(0, "", 50).size());
    }

    @Test
    void the_same_server_is_kept_while_it_answers_and_the_next_one_tried_when_it_cannot() {
        TotemDesk d = desk(KEY);
        snapshot("Frostwarf", true, 3);
        snapshot("Moongrove", true, 1);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 10, 3, 3)));
        boards.put("Moongrove", board(line(VEXA, "Vexa", 1, 10, 3, 3)));
        d.poll();
        assertEquals("Frostwarf", d.boardServer(), "the busiest first");
        tick();
        snapshot("Frostwarf", true, 1);
        snapshot("Moongrove", true, 5);
        d.poll();
        assertEquals("Frostwarf", d.boardServer(), "kept while it answers, though Moongrove is busier now (audit: switching records changes that never were)");
        tick();
        snapshot("Frostwarf", true, 1);
        snapshot("Moongrove", true, 5);
        boards.remove("Frostwarf"); // it answers with an error now
        d.poll();
        assertEquals("Moongrove", d.boardServer(), "the next able server when it cannot (audit)");
        tick();
        snapshot("Frostwarf", true, 1);
        snapshot("Moongrove", true, 5);
        boards.put("Moongrove", "{\"v\":1,\"lines\":0,\"ready\":false,\"board\":[],\"stealing\":[]}");
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 10, 3, 3)));
        d.poll();
        assertEquals("Frostwarf", d.boardServer(), "a server that has only just started is passed over for the next");
    }

    @Test
    void a_failed_save_is_never_counted_twice() throws Exception {
        TotemDesk d = desk(KEY);
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 10, 3, 3)));
        d.poll();
        tick();
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 12, 3, 3)));
        store.failPointSaves = true;
        d.poll();
        assertTrue(d.problem().startsWith("The points could not be saved") && !d.problem().contains("test's database"), "said in plain words, the database's own on the console: " + d.problem());
        assertEquals(1, d.page(7).getAsJsonArray("board").size(), "the board is still shown");
        store.failPointSaves = false;
        tick();
        snapshot("Frostwarf", true, 0);
        d.poll();
        List<PanelStore.PointsChange> rows = store.points(0, VEXA, 50);
        assertEquals(2, rows.size(), "the first sighting, then the gain once: " + rows);
        assertEquals(12, rows.get(0).after());
    }

    private String steal(String event, String uid, String server, long at, String extra) {
        return "{\"type\":\"steal\",\"v\":1,\"event\":\"" + event + "\",\"uid\":\"" + uid + "\",\"at\":" + at + ",\"server\":\"" + server
                + "\",\"dim\":\"minecraft:the_nether\",\"x\":120,\"y\":64,\"z\":-880,\"thief\":\"" + VEXA + "\",\"thiefName\":\"Vexa\",\"owner\":\"" + WREN
                + "\",\"ownerName\":\"Wren_88\",\"seconds\":40" + extra + "}";
    }

    private static String message(String server, String kind, String payload) {
        return UUID.randomUUID() + "\n" + System.currentTimeMillis() + "\n" + server + "\n" + kind + "\n" + payload;
    }

    @Test
    void live_steals_are_taken_only_signed_and_move_stealing_now() {
        TotemDesk d = desk(KEY);
        List<JsonObject> events = new ArrayList<>();
        d.onEvent(events::add);
        long at = clock.get();
        assertFalse(d.acceptMessage(message("Frostwarf", "snapshot", "x")), "another kind is not ours");
        assertTrue(d.acceptMessage(message("Frostwarf", "steal", steal("start", "s1", "Frostwarf", at, ""))) && events.isEmpty(), "unsigned: dropped");
        assertTrue(d.acceptMessage(message("Frostwarf", "steal", Signed.seal(KEY, steal("start", "s1", "Frostwarf", at - 3_600_000L, "")))) && events.isEmpty(),
                "an old one sent again: dropped");
        String start = message("Frostwarf", "steal", Signed.seal(KEY, steal("start", "s1", "Frostwarf", at, "")));
        assertTrue(d.acceptMessage(start) && d.acceptMessage(start));
        assertEquals(1, events.size(), "once, however often it comes");
        clock.addAndGet(10_000);
        JsonArray stealing = d.page(7).getAsJsonArray("stealing");
        assertEquals(1, stealing.size());
        JsonObject s = stealing.get(0).getAsJsonObject();
        assertTrue(s.get("thiefName").getAsString().equals("Vexa") && s.get("x").getAsInt() == 120 && Math.abs(s.get("left").getAsDouble() - 0.75) < 0.01,
                "stealing now, a quarter done after 10 of 40 s: " + s);

        d.acceptMessage(message("Frostwarf", "steal", Signed.seal(KEY, steal("end", "row-1", "Frostwarf", clock.get(),
                ",\"source\":\"totem\",\"tier\":3,\"points\":4,\"started\":" + at + ",\"ended\":" + clock.get() + ",\"success\":true,\"reason\":\"\",\"ticks\":200"))));
        assertEquals(2, events.size());
        JsonObject end = events.get(1);
        assertTrue(end.get("event").getAsString().equals("end") && end.getAsJsonObject("steal").get("fast").getAsBoolean(),
                "the end, 10 s for a 40 s steal: fast: " + end);
        assertEquals(0, d.page(7).getAsJsonArray("stealing").size(), "no longer stealing (the page made again after the end, not the cached one)");
    }

    @Test
    void a_start_whose_end_was_missed_is_forgotten() {
        TotemDesk d = desk(KEY);
        d.acceptMessage(message("Frostwarf", "steal", Signed.seal(KEY, steal("start", "s9", "Frostwarf", clock.get(), ""))));
        clock.addAndGet(40_000 + 9 * 60_000);
        assertEquals(1, d.page(7).getAsJsonArray("stealing").size(), "still within BSP-Core's time and ten minutes");
        clock.addAndGet(2 * 60_000);
        assertEquals(0, d.page(7).getAsJsonArray("stealing").size(), "past it: the end was missed, not running for an hour (audit)");

        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", board(line(VEXA, "Vexa", 1, 10, 3, 3)));
        d.acceptMessage(message("Frostwarf", "steal", Signed.seal(KEY, steal("start", "s10", "Frostwarf", clock.get(), ""))));
        clock.addAndGet(61_000);
        snapshot("Frostwarf", true, 0);
        d.poll();
        assertEquals(0, d.page(7).getAsJsonArray("stealing").size(), "a board read a minute after the start does not list it: gone");
    }

    /** A steal as a game server writes it: its fields, and its signed copy as the row's line (the mod's WebTotems.signedEnd). */
    private static PanelStore.Steal row(String uid, String thief, long started, long ended, int seconds, boolean ok) {
        return row(uid, thief, started, ended, seconds, ok, started > 0 ? (int) Math.max(1, (ended - started) / 50) : 0);
    }

    /** As above with the server ticks it took given (a server catching up after lag runs more ticks than the clock shows). */
    private static PanelStore.Steal row(String uid, String thief, long started, long ended, int seconds, boolean ok, int ticks) {
        String name = thief.equals(VEXA) ? "Vexa" : "Wren_88", reason = ok ? "" : "message.bsp_core.steal.failed_left";
        JsonObject o = new JsonObject();
        o.addProperty("type", "steal");
        o.addProperty("v", 1);
        o.addProperty("event", "end");
        o.addProperty("uid", uid);
        o.addProperty("at", ended);
        o.addProperty("server", "Frostwarf");
        o.addProperty("dim", "minecraft:overworld");
        o.addProperty("x", 10);
        o.addProperty("y", 64);
        o.addProperty("z", 10);
        o.addProperty("thief", thief);
        o.addProperty("thiefName", name);
        o.addProperty("owner", "");
        o.addProperty("ownerName", "");
        o.addProperty("seconds", seconds);
        o.addProperty("source", "totem");
        o.addProperty("tier", 2);
        o.addProperty("points", 2);
        o.addProperty("started", started);
        o.addProperty("ended", ended);
        o.addProperty("success", ok);
        o.addProperty("reason", reason);
        o.addProperty("ticks", ticks);
        return new PanelStore.Steal(uid, "Frostwarf", "minecraft:overworld", 10, 64, 10, thief, name, "", "", "totem", 2, 2, started, ended, seconds, ok, reason,
                ticks, Signed.seal(KEY, o.toString()));
    }

    @Test
    void a_steal_faster_than_bsp_core_allows_and_a_burst_are_flagged_with_the_time_between() {
        long t = clock.get() - 3_600_000L, M = 60_000L;
        store.addSteal(row("a", VEXA, t, t + 40_000, 40, true));                 // on time
        store.addSteal(row("b", VEXA, t + 2 * M, t + 2 * M + 39_000, 40, true)); // a second short: within the margin
        store.addSteal(row("c", VEXA, t + 4 * M, t + 4 * M + 20_000, 40, true)); // half the time: fast
        store.addSteal(row("d", WREN, t, t + 45_000, 45, true));
        store.addSteal(row("e", WREN, t + 30 * M, t + 30 * M + 10_000, 45, false)); // failed: no gap, no flag
        store.addSteal(row("f", WREN, 0, t + 60 * M, 0, true));                   // start not seen: never fast
        JsonObject page = desk(KEY).page(1);
        JsonArray steals = page.getAsJsonArray("steals");
        assertEquals(6, steals.size());
        assertEquals(0, page.get("unverified").getAsInt());
        JsonObject c = find(steals, "c"), b = find(steals, "b"), a = find(steals, "a"), f = find(steals, "f"), e = find(steals, "e");
        assertTrue(c.get("fast").getAsBoolean() && !b.get("fast").getAsBoolean() && !a.get("fast").getAsBoolean() && !f.get("fast").getAsBoolean(), "only c is too fast");
        assertTrue(a.get("burst").getAsBoolean() && b.get("burst").getAsBoolean() && c.get("burst").getAsBoolean(), "three within 10 minutes: a burst");
        assertFalse(find(steals, "d").get("burst").getAsBoolean(), "Wren's are spread out");
        assertEquals(2 * M - 1_000, b.get("gapMs").getAsLong(), "the time since Vexa's previous steal ended");
        assertEquals(0, a.get("gapMs").getAsLong(), "the first steal there is has no gap");
        assertTrue(!e.get("success").getAsBoolean() && e.get("why").getAsString().equals("Left the totem for too long") && e.get("gapMs").getAsLong() == 0, "a failed one in words");
        assertEquals(20_000, c.get("tookMs").getAsLong());
        JsonObject vexa = page.getAsJsonArray("thieves").get(0).getAsJsonObject();
        assertTrue(vexa.get("id").getAsString().equals(VEXA) && vexa.get("steals").getAsInt() == 3 && vexa.get("fast").getAsInt() == 1 && vexa.get("burst").getAsBoolean()
                && vexa.get("points").getAsInt() == 6 && vexa.get("fastestMs").getAsLong() == 20_000 && vexa.get("shortestGapMs").getAsLong() == 2 * M - 19_000,
                "the flagged thief first, with counts: " + vexa);
        JsonObject wren = page.getAsJsonArray("thieves").get(1).getAsJsonObject();
        assertTrue(wren.get("steals").getAsInt() == 2 && wren.get("failed").getAsInt() == 1, "Wren: " + wren);
    }

    @Test
    void a_burst_across_the_windows_start_is_still_a_burst() {
        long since = clock.get() - TotemDesk.DAY, M = 60_000L;
        store.addSteal(row("x1", VEXA, since - 6 * M, since - 6 * M + 40_000, 40, true));
        store.addSteal(row("x2", VEXA, since - 3 * M, since - 3 * M + 40_000, 40, true));
        store.addSteal(row("x3", VEXA, since + M, since + M + 40_000, 40, true));
        JsonArray steals = desk(KEY).page(1).getAsJsonArray("steals");
        assertEquals(1, steals.size(), "only the window's own steal is shown");
        JsonObject x3 = steals.get(0).getAsJsonObject();
        assertTrue(x3.get("burst").getAsBoolean() && x3.get("gapMs").getAsLong() == 4 * M, "judged with the two before the window (audit): " + x3);
    }

    @Test
    void a_row_is_shown_only_through_its_signed_copy() {
        long t = clock.get() - 60_000L;
        PanelStore.Steal real = row("r1", VEXA, t - 40_000, t, 40, true);
        store.addSteal(real);
        // someone with only the database: a row with no signature, and one whose columns say other than its copy
        store.addSteal(new PanelStore.Steal("forged", "Frostwarf", "minecraft:overworld", 1, 2, 3, WREN, "Wren_88", "", "", "totem", 5, 10, t - 1000, t, 60, true, "", 20, ""));
        store.addSteal(new PanelStore.Steal("r2", "Frostwarf", "minecraft:overworld", 1, 2, 3, WREN, "Wren_88", "", "", "totem", 5, 10, t - 1000, t, 60, true, "", 20, real.line()));
        PanelStore.Steal changed = new PanelStore.Steal("r3", "Frostwarf", "minecraft:overworld", 10, 64, 10, VEXA, "Changed", "", "", "totem", 5, 10, t - 1000, t, 60, true, "", 20,
                row("r3", VEXA, t - 40_000, t, 40, true).line());
        store.addSteal(changed);
        JsonObject page = desk(KEY).page(7);
        JsonArray steals = page.getAsJsonArray("steals");
        assertEquals(2, steals.size(), "the real one and r3: " + steals);
        assertEquals(2, page.get("unverified").getAsInt(), "the unsigned row and the one carrying another steal's copy, counted");
        JsonObject r3 = find(steals, "r3");
        assertTrue(r3.get("thiefName").getAsString().equals("Vexa") && r3.get("tier").getAsInt() == 2 && !r3.get("fast").getAsBoolean(),
                "the signed copy's fields, never the changed columns: " + r3);
        assertEquals(4, desk(null).page(7).getAsJsonArray("steals").size(), "without a key (the demo) the rows are taken as they are");
    }

    @Test
    void speed_is_judged_on_ticks_and_rows_must_match_their_copies() {
        long t = clock.get() - 600_000L;
        // 40 s of ticks in 30 s of clock: a server catching up after lag; an honest steal (audit of 0.16.0)
        store.addSteal(row("lag", VEXA, t - 30_000, t, 40, true, 800));
        store.addSteal(row("quick", VEXA, t + 60_000 - 39_000, t + 60_000, 40, true, 300)); // 15 s of ticks for a 40 s steal: fast
        // Wren's signed steal put under Vexa's id in the column the rows are picked by
        PanelStore.Steal wrens = row("w9", WREN, t - 40_000, t - 1000, 40, true);
        store.addSteal(new PanelStore.Steal("w9", wrens.server(), wrens.dimension(), wrens.x(), wrens.y(), wrens.z(), VEXA, "Vexa", "", "", "totem", 2, 2,
                wrens.startedMs(), wrens.endedMs(), 40, true, "", wrens.ticks(), wrens.line()));
        store.addSteal(row("future", VEXA, t, clock.get() + 5 * TotemDesk.DAY, 40, true)); // dated days ahead: never read
        JsonObject p = desk(KEY).player(VEXA, 7);
        JsonArray steals = p.getAsJsonArray("steals");
        assertEquals(2, steals.size(), "only Vexa's own, signed as hers, and nothing from the future: " + steals);
        assertEquals(1, p.get("unverified").getAsInt(), "Wren's copy under Vexa's id is counted, not shown");
        assertTrue(!find(steals, "lag").get("fast").getAsBoolean() && find(steals, "lag").get("tookMs").getAsLong() == 40_000, "judged on ticks: not fast, took 0:40");
        assertTrue(find(steals, "quick").get("fast").getAsBoolean(), "fewer ticks than BSP-Core gives: fast, whatever the clock said");
    }

    @Test
    void a_forge_pour_or_another_servers_steal_stays_in_stealing_now_while_it_runs() {
        TotemDesk d = desk(KEY);
        long at = clock.get();
        // a forge pour (never on the board) and a steal the board names by BSP-Core's own server name
        d.acceptMessage(message("Frostwarf", "steal", Signed.seal(KEY, steal("start", "f1", "Frostwarf", at, ",\"source\":\"forge\",\"tier\":1").replace("\"x\":120", "\"x\":7"))));
        d.acceptMessage(message("Moongrove", "steal", Signed.seal(KEY, steal("start", "m1", "Moongrove", at, ",\"source\":\"totem\",\"tier\":2"))));
        clock.addAndGet(61_000);
        snapshot("Frostwarf", true, 0);
        boards.put("Frostwarf", "{\"v\":1,\"lines\":1,\"ready\":true,\"board\":[" + line(VEXA, "Vexa", 1, 10, 3, 3) + "],\"stealing\":[{\"server\":\"moongrove-core\","
                + "\"dim\":\"minecraft:the_nether\",\"x\":120,\"y\":64,\"z\":-880,\"thief\":\"" + VEXA + "\",\"thiefName\":\"Vexa\",\"left\":0.2}]}");
        d.poll();
        JsonArray now = d.page(7).getAsJsonArray("stealing");
        assertEquals(2, now.size(), "both still running a minute on, and the other server's once, not twice: " + now);
    }

    private static JsonObject find(JsonArray a, String uid) {
        for (var e : a) {
            if (e.getAsJsonObject().get("uid").getAsString().equals(uid)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no steal " + uid);
    }

    @Test
    void a_missing_grant_is_said_plainly_and_one_players_row_reads_their_steals_and_points() throws Exception {
        store.failStealReads = true;
        JsonObject page = desk(KEY).page(7);
        String why = page.get("stealsProblem").getAsString();
        assertTrue(page.get("ok").getAsBoolean() && why.contains("SELECT") && why.contains("STAFF_PANEL_SETUP") && !why.contains("denied to user"),
                "the page still answers, with why in plain words and not the database's own (audit): " + why);
        store.failStealReads = false;

        long t = clock.get() - 600_000L;
        store.addSteal(row("v1", VEXA, t, t + 40_000, 40, true));
        store.addSteal(row("w1", WREN, t, t + 40_000, 40, true));
        store.insertPoints(List.of(new PanelStore.PointsChange(t - 60_000, VEXA, "Vexa", 0, 8, 2, 2, true),
                new PanelStore.PointsChange(t + 40_000, VEXA, "Vexa", 8, 10, 3, 2, false)));
        assertFalse(desk(KEY).player("not-a-uuid", 7).get("ok").getAsBoolean());
        JsonObject p = desk(KEY).player(VEXA.toUpperCase(), 7);
        assertTrue(p.get("ok").getAsBoolean() && p.get("name").getAsString().equals("Vexa") && p.getAsJsonArray("steals").size() == 1, "only Vexa's steals: " + p);
        JsonArray pts = p.getAsJsonArray("points");
        assertTrue(pts.size() == 2 && pts.get(0).getAsJsonObject().get("first").getAsBoolean() && pts.get(1).getAsJsonObject().get("after").getAsInt() == 10, "oldest first, for the line");
        assertFalse(p.get("pointsCut").getAsBoolean());
        assertEquals(1, p.getAsJsonObject("summary").get("steals").getAsInt());
        assertEquals(7, desk(KEY).page(5).get("days").getAsInt(), "a window the page does not offer is a week");
        assertEquals(2, store.pointsTotals(0).get(VEXA)[0], "the totals summed by the store, first sightings left out");
    }
}
