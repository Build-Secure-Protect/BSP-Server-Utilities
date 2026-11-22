package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.players.OfflineDesk;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline players (SU-115; owner decisions D653 to D671): everyone who ever joined and is not online, the most recently
 * seen first and then the rest by name, a page at a time, found by any part of the name; a place shown only through its
 * signed copy (another player's copy, a bad signature, an empty copy or one dated far ahead shown as not known); a panel
 * without the grant on the seen table still lists the names and says what is missing; one player for their card; an
 * offline player's head from the skin last seen.
 */
class OfflinePlayersTest {
    private static final byte[] KEY = ActionsTest.KEY;
    private static final String WREN = "aaaaaaaa-bbbb-cccc-dddd-000000000001", MOSS = "aaaaaaaa-bbbb-cccc-dddd-000000000002",
            JUNI = "aaaaaaaa-bbbb-cccc-dddd-000000000003", ASH = "aaaaaaaa-bbbb-cccc-dddd-000000000004", PIP = "aaaaaaaa-bbbb-cccc-dddd-000000000005";
    private MemoryStore store;
    private AtomicLong clock;
    private Feed feed;

    @BeforeEach
    void start() {
        store = new MemoryStore();
        clock = new AtomicLong(1_800_000_000_000L);
        feed = new Feed(clock::get);
        store.players.put(WREN, "Wren_88");
        store.players.put(MOSS, "Moss_Lad");
        store.players.put(JUNI, "Juniper7");
        store.players.put(ASH, "Ash_Vale");
        store.players.put(PIP, "Pip_Stone");
    }

    /** The copy the game server writes (BSP-Server Utilities' {@code LastSeen.text}), signed with the action key, under the player's own name. */
    private String copy(String player, long at, String server, String dim, int x, int y, int z, String kind) {
        return copyNamed(player, store.players.getOrDefault(player, "x"), at, server, dim, x, y, z, kind);
    }

    private static String copyNamed(String player, String name, long at, String server, String dim, int x, int y, int z, String kind) {
        return Signed.seal(KEY, "{\"type\":\"seen\",\"player\":\"" + player + "\",\"name\":\"" + name + "\",\"at\":" + at + ",\"server\":\"" + server + "\",\"dim\":\"" + dim
                + "\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"kind\":\"" + kind + "\"}");
    }

    private void seen(String player, long at, String server, String dim, int x, int y, int z, String kind, String line) {
        store.seen.put(player, new PanelStore.SeenPlayer(player, "", true, "x", at, server, dim, x, y, z, kind, line));
    }

    private void seenSigned(String player, long at, String server, String dim, int x, int y, int z, String kind) {
        seen(player, at, server, dim, x, y, z, kind, copy(player, at, server, dim, x, y, z, kind));
    }

    /** One server's snapshot with these players online, each with a skin. */
    private void online(String server, String... ids) {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            p.append(i == 0 ? "" : ",").append("{\"id\":\"").append(ids[i]).append("\",\"name\":\"N").append(i)
                    .append("\",\"skin\":\"http://textures.minecraft.net/texture/").append(i).append("\"}");
        }
        feed.accept(server, clock.get(), "{\"v\":1,\"server\":\"" + server + "\",\"at\":" + clock.get() + ",\"players\":[" + p + "]}");
    }

    private static String ids(JsonObject page) {
        StringBuilder s = new StringBuilder();
        for (var e : page.getAsJsonArray("players")) {
            s.append(e.getAsJsonObject().get("name").getAsString()).append(' ');
        }
        return s.toString().trim();
    }

    @Test
    void the_most_recently_seen_come_first_then_the_rest_by_name_and_online_players_are_left_out() {
        long t = clock.get();
        seenSigned(MOSS, t - 60_000, "BSP-Main", "minecraft:the_nether", 120, 64, -30, "logout");
        seenSigned(JUNI, t - 3 * 3_600_000L, "BSP-Mines", "minecraft:overworld", -412, 33, 1020, "logout");
        seenSigned(WREN, t - 1000, "BSP-Main", "minecraft:overworld", 1, 2, 3, "logout");
        online("BSP-Main", WREN);
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        JsonObject page = d.list("", 0, 50);
        assertTrue(page.get("ok").getAsBoolean(), page.toString());
        assertEquals("Moss_Lad Juniper7 Ash_Vale Pip_Stone", ids(page), "newest seen first, then the unseen by name; Wren is online: " + page);
        assertEquals(4, page.get("total").getAsInt(), "everyone who ever joined and is not online");
        JsonObject moss = page.getAsJsonArray("players").get(0).getAsJsonObject();
        JsonObject place = moss.getAsJsonObject("place");
        assertTrue(moss.get("state").getAsString().equals("ok") && place.get("server").getAsString().equals("BSP-Main") && place.get("dim").getAsString().equals("minecraft:the_nether")
                && place.get("x").getAsInt() == 120 && place.get("z").getAsInt() == -30 && place.get("at").getAsLong() == t - 60_000 && place.get("kind").getAsString().equals("logout"), "the place: " + moss);
        JsonObject ash = page.getAsJsonArray("players").get(2).getAsJsonObject();
        assertTrue(ash.get("state").getAsString().equals("none") && !ash.has("place"), "no row: not recorded: " + ash);
        JsonObject second = d.list("", 1, 2);
        assertEquals("Juniper7 Ash_Vale", ids(second), "a page from an offset");
        assertEquals(1, second.get("offset").getAsInt());
    }

    @Test
    void the_search_finds_any_part_of_a_name_in_any_case_and_odd_text_finds_nobody() {
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        JsonObject a = d.list("vAl", 0, 50);
        assertEquals("Ash_Vale", ids(a), "any part, any case: " + a);
        assertEquals(1, a.get("total").getAsInt(), "the count is of the matches");
        assertEquals("Juniper7", ids(d.list("per", 0, 50)), "inside a name");
        assertEquals(4, d.list("_", 0, 50).get("total").getAsInt(), "an underscore is a letter of the name, never a wildcard: Juniper7 has none");
        JsonObject odd = d.list("a%'; --", 0, 50);
        assertTrue(odd.get("ok").getAsBoolean() && odd.getAsJsonArray("players").isEmpty() && odd.get("total").getAsInt() == 0, "not a name: nobody: " + odd);
    }

    @Test
    void a_place_is_shown_only_through_a_signed_copy_of_that_very_row() {
        long t = clock.get();
        seenSigned(MOSS, t - 5000, "BSP-Main", "minecraft:the_end", 7, 70, 7, "seen"); // genuine: a crash's record
        seen(JUNI, t - 6000, "BSP-Main", "minecraft:overworld", 1, 1, 1, "logout", copy(WREN, t - 6000, "BSP-Main", "minecraft:overworld", 1, 1, 1, "logout")); // another player's copy
        seen(ASH, t - 7000, "BSP-Main", "minecraft:overworld", 1, 1, 1, "logout", Signed.seal("another-key-entirely-0123456789ab".getBytes(), "{\"type\":\"seen\",\"player\":\"" + ASH + "\",\"at\":1}"));
        seen(PIP, t - 8000, "BSP-Main", "minecraft:overworld", 1, 1, 1, "logout", "");
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        for (var e : d.list("", 0, 50).getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            String name = p.get("name").getAsString();
            if (name.equals("Moss_Lad")) {
                JsonObject place = p.getAsJsonObject("place");
                assertTrue(p.get("state").getAsString().equals("ok") && place.get("dim").getAsString().equals("minecraft:the_end") && place.get("kind").getAsString().equals("seen"),
                        "the signed place, and a crash's record said as such: " + p);
            } else if (!name.equals("Wren_88")) {
                assertTrue(!p.get("state").getAsString().equals("ok") && !p.has("place"), name + ": not shown (another's copy, a bad signature, none): " + p);
            }
        }
        // without a key (the demo) the rows are taken as they are
        JsonObject demo = new OfflineDesk(store, feed, clock::get, null).list("Pip", 0, 50).getAsJsonArray("players").get(0).getAsJsonObject();
        assertTrue(demo.get("state").getAsString().equals("ok") && demo.getAsJsonObject("place").get("server").getAsString().equals("BSP-Main"), "the demo: " + demo);
    }

    @Test
    void a_copy_must_match_its_rows_name_time_and_place() {
        long t = clock.get();
        // a renamed players row borrowing another's genuine copy: B's place under Wren_88's name
        seen(WREN, t - 5000, "BSP-Main", "minecraft:overworld", 4, 64, 4, "logout", copyNamed(WREN, "Someone_Else", t - 5000, "BSP-Main", "minecraft:overworld", 4, 64, 4, "logout"));
        // a time moved to keep the row on top, and a place moved, each under a genuine copy
        seen(MOSS, t - 1000, "BSP-Main", "minecraft:overworld", 4, 64, 4, "logout", copy(MOSS, t - 9_000_000, "BSP-Main", "minecraft:overworld", 4, 64, 4, "logout"));
        seen(JUNI, t - 7000, "BSP-Main", "minecraft:overworld", 900, 64, 4, "logout", copy(JUNI, t - 7000, "BSP-Main", "minecraft:overworld", 4, 64, 4, "logout"));
        seenSigned(ASH, t - 8000, "BSP-Mines", "minecraft:overworld", 4, 64, 4, "logout");
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        for (var e : d.list("", 0, 50).getAsJsonArray("players")) {
            JsonObject p = e.getAsJsonObject();
            String name = p.get("name").getAsString();
            if (name.equals("Ash_Vale")) {
                assertEquals("ok", p.get("state").getAsString(), "a row and its own copy: shown");
            } else if (!name.equals("Pip_Stone")) {
                assertEquals("bad", p.get("state").getAsString(), name + ": a copy not of this very row is not believed: " + p);
            }
        }
    }

    @Test
    void a_far_future_row_never_holds_the_top_and_a_bad_key_believes_nothing() {
        long t = clock.get();
        seenSigned(WREN, t + 2 * 86_400_000L, "BSP-Main", "minecraft:overworld", 1, 1, 1, "logout"); // two days ahead: made to stay on top
        seenSigned(MOSS, t - 1000, "BSP-Main", "minecraft:overworld", 1, 2, 3, "logout");
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        JsonArray ps = d.list("", 0, 50).getAsJsonArray("players");
        assertEquals("Moss_Lad", ps.get(0).getAsJsonObject().get("name").getAsString(), "the genuine newest first: " + ps);
        ps.forEach(e -> { if (e.getAsJsonObject().get("name").getAsString().equals("Wren_88")) assertEquals("bad", e.getAsJsonObject().get("state").getAsString()); });
        // an actionKey that could not be read becomes an empty key: nothing is believed, never "no key" (the demo)
        JsonArray none = new OfflineDesk(store, feed, clock::get, new byte[0]).list("", 0, 50).getAsJsonArray("players");
        none.forEach(e -> assertTrue(!e.getAsJsonObject().get("state").getAsString().equals("ok"), "an empty key shows no place: " + e));
    }

    @Test
    void at_most_a_page_and_never_a_row_that_is_not_a_player() {
        for (int i = 0; i < 60; i++) {
            store.players.put(String.format("bbbbbbbb-bbbb-cccc-dddd-%012d", i), "Extra_" + i);
        }
        store.players.put("not-a-uuid'><img src=x>", "Odd_Row");
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        JsonObject page = d.list("", 0, 9999);
        assertEquals(50, page.getAsJsonArray("players").size(), "at most 50 whatever is asked");
        JsonObject odd = d.list("Odd", 0, 50);
        assertTrue(odd.getAsJsonArray("players").isEmpty(), "a row whose id is not a UUID is never listed: " + odd);
    }

    @Test
    void without_the_grant_on_the_seen_table_the_names_are_listed_and_the_missing_grant_said() {
        seenSigned(MOSS, clock.get() - 1000, "BSP-Main", "minecraft:overworld", 1, 2, 3, "logout");
        store.seenRefused = true;
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        JsonObject page = d.list("", 0, 50);
        assertTrue(page.get("ok").getAsBoolean() && page.getAsJsonArray("players").size() == 5, "the names still: " + page);
        assertEquals("Ash_Vale Juniper7 Moss_Lad Pip_Stone Wren_88", ids(page), "by name, no place known");
        assertTrue(page.get("problem").getAsString().contains("bsp_su_seen") && page.get("problem").getAsString().contains("STAFF_PANEL_SETUP"), "what is missing said: " + page);
        store.seenRefused = false;
        store.seenMissing = true;
        JsonObject older = d.list("", 0, 50);
        assertTrue(older.get("problem").getAsString().contains("0.18.0") && !older.get("problem").getAsString().contains("SELECT"), "a table not made yet: the game servers need updating: " + older);
        JsonObject one = d.one(MOSS);
        assertTrue(one.get("ok").getAsBoolean() && one.getAsJsonObject("player").get("state").getAsString().equals("none") && one.has("problem"), "one player too: " + one);
    }

    @Test
    void one_player_for_their_card_with_whether_they_are_online_and_their_head_from_the_last_skin_seen() {
        seenSigned(MOSS, clock.get() - 1000, "BSP-Main", "minecraft:overworld", 1, 2, 3, "logout");
        OfflineDesk d = new OfflineDesk(store, feed, clock::get, KEY);
        assertFalse(d.one("not-a-uuid").get("ok").getAsBoolean(), "not a player");
        assertFalse(d.one("aaaaaaaa-bbbb-cccc-dddd-00000000ffff").get("ok").getAsBoolean(), "nobody who ever joined");
        JsonObject m = d.one(MOSS.toUpperCase());
        assertTrue(m.get("ok").getAsBoolean() && !m.getAsJsonObject("player").get("online").getAsBoolean() && m.getAsJsonObject("player").get("state").getAsString().equals("ok"),
                "found in any case, offline, with the place: " + m);
        assertFalse(m.getAsJsonObject("player").get("skin").getAsBoolean(), "no skin seen this run: a plain face");
        online("BSP-Main", MOSS);
        assertTrue(d.one(MOSS).getAsJsonObject("player").get("online").getAsBoolean(), "online now");
        clock.addAndGet(5000);
        online("BSP-Main"); // they left
        JsonObject after = d.one(MOSS).getAsJsonObject("player");
        assertTrue(!after.get("online").getAsBoolean() && after.get("skin").getAsBoolean(), "gone, and their head still drawn from the skin last seen: " + after);
        assertTrue(feed.skinOf(MOSS) != null, "the feed keeps it");
    }
}
