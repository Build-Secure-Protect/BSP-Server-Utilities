package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.shops.ShopDesk;
import com.mrgregles.bsp_staff_panel.store.Account;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SHOPS tab's desk (SU-161; D788 to D814): every rank reads; the shops are asked of one server and kept a minute for
 * everyone; every server is asked for its own stalls, counted on the shop at the top of their section; a server that stops
 * keeps its stalls, greyed; a change heard ({@code shopsAt}) drops what was kept; a shop's page carries its tree, its path
 * and the stalls of the tree. The made-up game server is {@link DemoShops}, as {@code --demo} answers.
 */
class ShopsTest {
    static final byte[] KEY = new byte[32];

    /** A desk over a feed with BSP-Spawn and Frostwarf (shops answering) and Moongrove (not), answered by the demo's shops. */
    private static final class Rig {
        final AtomicLong clock = new AtomicLong(60_000_000L);
        final Feed feed = new Feed(clock::get);
        final List<JsonObject> sent = new CopyOnWriteArrayList<>();
        final Actions actions;
        final ShopDesk desk;
        long shopsAt = 0;

        Rig() {
            DemoShops.reset();
            Actions[] a = new Actions[1];
            a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
                JsonObject act = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(payload.substring(0, payload.indexOf('.'))), StandardCharsets.UTF_8)).getAsJsonObject();
                sent.add(act);
                JsonObject sh = DemoShops.answer(act.get("action").getAsString(), act, act.get("server").getAsString());
                JsonObject ans = new JsonObject();
                ans.addProperty("id", uid);
                ans.add("ok", sh.get("ok"));
                ans.add("message", sh.get("message"));
                if (!sh.get("data").isJsonNull()) {
                    ans.add("data", sh.get("data"));
                }
                new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(KEY, ans.toString())))).start();
            }, clock::get, 3);
            actions = a[0];
            desk = new ShopDesk(feed, () -> actions, clock::get);
            snapshots();
        }

        long stallsAt = 0;

        void snapshots() {
            feed.accept("BSP-Spawn", clock.get(), "{\"v\":1,\"server\":\"BSP-Spawn\",\"at\":" + clock.get() + ",\"answers\":true,\"shops\":true,\"shopsAt\":" + shopsAt
                    + ",\"stallsAt\":" + stallsAt + ",\"lotteryUp\":true,\"players\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Glimmer\"}]}");
            feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":" + clock.get() + ",\"answers\":true,\"shops\":true,\"shopsAt\":" + shopsAt + ",\"players\":[]}");
            feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"answers\":true,\"players\":[]}");
        }

        long count(String action) {
            return sent.stream().filter(s -> s.get("action").getAsString().equals(action)).count();
        }

        List<String> servers(String action) {
            List<String> out = new ArrayList<>();
            sent.stream().filter(s -> s.get("action").getAsString().equals(action)).forEach(s -> out.add(s.get("server").getAsString()));
            return out;
        }
    }

    private static Account account(Rank rank) {
        Account a = new Account();
        a.email = rank.name().toLowerCase() + "@test";
        a.mcName = "Staff_" + rank.name();
        a.rank = rank;
        return a;
    }

    private static JsonObject tile(JsonObject grid, String id) {
        for (JsonElement e : grid.getAsJsonArray("shops")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError(id);
    }

    private static JsonObject venue(JsonObject tile, String server) {
        for (JsonElement e : tile.getAsJsonArray("venues")) {
            if (e.getAsJsonObject().get("server").getAsString().equals(server)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    @Test
    void the_grid_lists_top_shops_a_to_z_with_their_stalls_by_server_for_every_rank() {
        Rig r = new Rig();
        JsonObject g = r.desk.grid(account(Rank.MODERATOR), false);
        assertTrue(g.get("ok").getAsBoolean(), g.toString());
        List<String> names = new ArrayList<>();
        g.getAsJsonArray("shops").forEach(e -> names.add(e.getAsJsonObject().get("name").getAsString()));
        assertEquals(List.of("Builder's Yard", "Challenge", "Farmers' Market", "General Store", "Jams", "Miners' Guild", "Night Market", "Spring Fair", "Winter Sale"), names,
                "top-level shops only (sections live inside, except one whose shop is gone, D853), A to Z (D792)");
        JsonObject general = tile(g, "spawn_general");
        List<String> secs = new ArrayList<>();
        general.getAsJsonArray("sections").forEach(e -> secs.add(e.getAsJsonObject().get("id").getAsString()));
        assertEquals(List.of("spawn_general_food", "spawn_general_redstone", "spawn_general_tools", "spawn_general_pickaxes"), secs, "every section under it, depth first, for the search");
        JsonObject spawn = venue(general, "BSP-Spawn");
        assertEquals(3, spawn.get("stall").getAsInt(), "its own stall and its sections' stalls count on the shop at the top (D791)");
        assertEquals(1, spawn.get("post").getAsInt());
        assertEquals(2, spawn.get("display").getAsInt());
        assertEquals(4, spawn.get("forSections").getAsInt());
        assertEquals(1, venue(general, "Frostwarf").get("stall").getAsInt(), "every server with the shops is asked for its own stalls");
        assertTrue(r.servers("shop_list").equals(List.of("BSP-Spawn")), "the shops are asked of one server, its database connected first (D789): " + r.servers("shop_list"));
        assertFalse(r.servers("shop_venues").contains("Moongrove"), "a server without the shops is not asked");
        assertEquals(4, tile(g, "spawn_general").getAsJsonArray("peek").size(), "the first four cards for the tile");
    }

    @Test
    void reads_are_kept_a_minute_for_everyone_and_dropped_when_a_server_says_shops_changed() {
        Rig r = new Rig();
        r.desk.grid(account(Rank.OWNER), false);
        r.desk.grid(account(Rank.MODERATOR), false);
        assertEquals(1, r.count("shop_list"), "a second page within the minute is given the same read");
        assertEquals(2, r.count("shop_venues"));
        r.clock.addAndGet(ShopDesk.FRESH_MS + 1);
        r.snapshots();
        r.desk.grid(account(Rank.OWNER), false);
        assertEquals(2, r.count("shop_list"), "after a minute it is read again (D811)");
        r.desk.grid(account(Rank.OWNER), false);
        assertEquals(2, r.count("shop_list"));
        r.clock.addAndGet(1000); // a newer snapshot (the feed keeps only newer ones)
        r.shopsAt = r.clock.get();
        r.snapshots();
        r.desk.grid(account(Rank.OWNER), false);
        assertEquals(3, r.count("shop_list"), "a shop changed in game (shopsAt moved): read again at once (D811)");
        r.desk.grid(account(Rank.OWNER), true);
        assertEquals(4, r.count("shop_list"), "REFRESH reads again");
    }

    @Test
    void a_section_page_carries_its_path_its_tree_and_the_stalls_of_the_tree() {
        Rig r = new Rig();
        JsonObject p = r.desk.shop(account(Rank.MODERATOR), "spawn_general_pickaxes", false);
        assertTrue(p.get("ok").getAsBoolean(), p.toString());
        assertEquals("spawn_general", p.get("top").getAsString());
        List<String> path = new ArrayList<>();
        p.getAsJsonArray("path").forEach(e -> path.add(e.getAsJsonObject().get("name").getAsString()));
        assertEquals(List.of("General Store", "Tools", "Pickaxes"), path);
        List<String> tree = new ArrayList<>();
        p.getAsJsonArray("tree").forEach(e -> tree.add(e.getAsJsonObject().get("id").getAsString() + ":" + e.getAsJsonObject().get("depth").getAsInt()));
        assertEquals(List.of("spawn_general:0", "spawn_general_food:1", "spawn_general_redstone:1", "spawn_general_tools:1", "spawn_general_pickaxes:2"), tree);
        assertEquals("spawn_general_pickaxes", p.getAsJsonObject("shop").get("id").getAsString());
        assertEquals(3, p.getAsJsonObject("shop").getAsJsonArray("cards").size());
        JsonArray v = p.getAsJsonArray("venues");
        assertEquals(7, v.size(), "BSP-Spawn's six and Frostwarf's one, for the shop and every section");
        assertTrue(v.get(0).getAsJsonObject().has("shopName"));
    }

    @Test
    void ids_are_checked_before_anything_is_sent_and_a_missing_shop_is_said() {
        Rig r = new Rig();
        JsonObject bad = r.desk.shop(account(Rank.OWNER), "../etc", false);
        assertFalse(bad.get("ok").getAsBoolean());
        assertEquals(0, r.sent.size(), "nothing is signed for an id that is not one");
        JsonObject gone = r.desk.shop(account(Rank.OWNER), "no_such_shop", false);
        assertFalse(gone.get("ok").getAsBoolean());
        assertTrue(gone.get("message").getAsString().contains("no shop no_such_shop"), gone.toString());
        assertEquals(0, r.count("shop_read"), "the list says it is not there: the shop itself is not asked for");
    }

    @Test
    void sales_take_one_seven_or_thirty_days_and_are_kept() {
        Rig r = new Rig();
        JsonObject s = r.desk.sales(account(Rank.MODERATOR), "spawn_general", 5);
        assertTrue(s.get("ok").getAsBoolean(), s.toString());
        assertEquals(7, s.get("days").getAsInt(), "another span is read as a week");
        assertTrue(s.getAsJsonArray("rows").size() > 0);
        r.desk.sales(account(Rank.MODERATOR), "spawn_general", 7);
        assertEquals(1, r.count("shop_report"), "kept for the minute");
        JsonObject month = r.desk.sales(account(Rank.MODERATOR), "spawn_general", 30);
        assertEquals(14, month.get("days").getAsInt(), "BSP-Core's own span when its bank log keeps fewer days");
        assertTrue(month.get("capped").getAsBoolean());
        assertTrue(month.get("piecesIn").getAsLong() > s.get("piecesIn").getAsLong());
    }

    @Test
    void a_server_that_stops_keeps_its_stalls_greyed_and_no_server_is_said() {
        Rig r = new Rig();
        r.desk.remember("SafeRoom", r.clock.get() - 3_600_000L, JsonParser.parseString("{\"venues\":[{\"kind\":\"stall\",\"dim\":\"minecraft:overworld\",\"x\":0,\"y\":70,\"z\":0,\"shop\":\"spawn_general\"}]}").getAsJsonObject());
        JsonObject g = r.desk.grid(account(Rank.OWNER), false);
        JsonObject safe = venue(tile(g, "spawn_general"), "SafeRoom");
        assertFalse(safe.get("live").getAsBoolean(), "shown, greyed, with when it was last heard from");
        assertEquals(r.clock.get() - 3_600_000L, safe.get("heardAt").getAsLong());
        Rig none = new Rig();
        Feed empty = new Feed(none.clock::get);
        ShopDesk d = new ShopDesk(empty, () -> none.actions, none.clock::get);
        JsonObject n = d.grid(account(Rank.OWNER), false);
        assertFalse(n.get("ok").getAsBoolean());
        assertEquals(ShopDesk.NO_SERVER, n.get("message").getAsString());
    }

    @Test
    void a_parent_loop_never_loses_a_shop_from_the_grid() {
        JsonObject list = JsonParser.parseString("{\"shops\":[{\"id\":\"a\",\"parent\":\"b\"},{\"id\":\"b\",\"parent\":\"a\"},{\"id\":\"c\",\"parent\":\"b\"},{\"id\":\"d\",\"parent\":\"gone\"}]}").getAsJsonObject();
        var byId = ShopDesk.byId(list);
        assertEquals("a", ShopDesk.root(byId, "a"));
        assertEquals("a", ShopDesk.root(byId, "b"), "every shop in a loop has the same top");
        assertEquals("a", ShopDesk.root(byId, "c"));
        assertEquals("d", ShopDesk.root(byId, "d"), "a missing parent makes it a top shop");
    }

    // ------------------------------------------------------------------ SU-162: making and changing shops

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static long edited(Rig r, String id) {
        return r.desk.shop(account(Rank.OWNER), id, true).getAsJsonObject("shop").get("edited").getAsLong();
    }

    @Test
    void changes_are_for_admins_and_owners_and_a_moderator_signs_nothing() {
        Rig r = new Rig();
        r.desk.grid(account(Rank.OWNER), false);
        long before = r.sent.size();
        JsonObject o = r.desk.enable(account(Rank.MODERATOR), obj("{\"shop\":\"spawn_general\",\"on\":false,\"edited\":1}"));
        assertFalse(o.get("ok").getAsBoolean());
        assertEquals(ShopDesk.ADMINS, o.get("message").getAsString());
        assertTrue(o.get("panelLog").getAsString().startsWith("tried to turn spawn_general off"), "the refusal is in the panel's log too");
        for (JsonObject x : List.of(r.desk.create(account(Rank.MODERATOR), obj("{\"name\":\"X\",\"id\":\"x\",\"kind\":\"shop\"}")),
                r.desk.set(account(Rank.MODERATOR), obj("{\"shop\":\"spawn_general\",\"field\":\"name\",\"value\":\"Y\",\"edited\":1}")),
                r.desk.copy(account(Rank.MODERATOR), obj("{\"shop\":\"spawn_general\",\"newId\":\"z\",\"name\":\"Z\"}")),
                r.desk.delete(account(Rank.MODERATOR), obj("{\"shop\":\"spawn_general\",\"edited\":1}")))) {
            assertFalse(x.get("ok").getAsBoolean());
        }
        assertEquals(before, r.sent.size(), "nothing is signed for a Moderator (D788)");
    }

    @Test
    void a_new_shop_is_made_off_and_a_new_section_lands_at_the_end_of_its_shop() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        assertFalse(r.desk.create(owner, obj("{\"name\":\"Fish\",\"id\":\"Bad Id\",\"kind\":\"shop\"}")).get("ok").getAsBoolean());
        assertFalse(r.desk.create(owner, obj("{\"name\":\"\",\"id\":\"fish\",\"kind\":\"shop\"}")).get("ok").getAsBoolean());
        assertFalse(r.desk.create(owner, obj("{\"name\":\"Fish\",\"id\":\"fish\",\"kind\":\"sub\"}")).get("ok").getAsBoolean(), "a section is made inside a shop");
        JsonObject taken = r.desk.create(owner, obj("{\"name\":\"Fish\",\"id\":\"spawn_general\",\"kind\":\"shop\"}"));
        assertTrue(taken.get("message").getAsString().contains("already"), taken.toString());
        assertEquals(0, r.count("shop_create"), "every refusal so far is the panel's own");
        JsonObject made = r.desk.create(owner, obj("{\"name\":\"Harbour Fish\",\"id\":\"harbour_fish\",\"kind\":\"rotating\"}"));
        assertTrue(made.get("ok").getAsBoolean(), made.toString());
        assertEquals("made shop harbour_fish (rotating), off", made.get("panelLog").getAsString());
        JsonObject t = tile(r.desk.grid(owner, false), "harbour_fish");
        assertFalse(t.get("enabled").getAsBoolean(), "made OFF (D795)");
        long ed = edited(r, "spawn_general_tools");
        JsonObject noPic = r.desk.create(owner, obj("{\"name\":\"Lights\",\"id\":\"spawn_general_lights\",\"parent\":\"spawn_general_tools\",\"edited\":" + ed + "}"));
        assertFalse(noPic.get("ok").getAsBoolean(), "a section needs the picture its card shows");
        JsonObject sec = r.desk.create(owner, obj("{\"name\":\"Lights\",\"id\":\"spawn_general_lights\",\"parent\":\"spawn_general_tools\",\"edited\":" + ed
                + ",\"picture\":\"minecraft:lantern\"}"));
        assertTrue(sec.get("ok").getAsBoolean(), sec.toString());
        JsonArray cards = r.desk.shop(owner, "spawn_general_tools", false).getAsJsonObject("shop").getAsJsonArray("cards");
        JsonObject last = cards.get(cards.size() - 1).getAsJsonObject();
        assertEquals("spawn_general_lights", last.get("section").getAsString(), "its card at the end of the page (D796)");
        assertEquals("minecraft:lantern", last.get("item").getAsString());
        JsonObject stale = r.desk.create(owner, obj("{\"name\":\"More\",\"id\":\"spawn_general_more\",\"parent\":\"spawn_general_tools\",\"edited\":" + ed
                + ",\"picture\":\"minecraft:torch\"}"));
        assertFalse(stale.get("ok").getAsBoolean());
        assertTrue(stale.get("stale").getAsBoolean(), "the shop changed since it was read: refused (D794)");
    }

    @Test
    void every_setting_is_checked_before_it_is_signed_and_a_good_one_moves_the_stamp() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        long ed = edited(r, "spawn_general");
        long sentBefore = r.count("shop_set");
        String[] bad = {
                "\"field\":\"columns\",\"value\":3", "\"field\":\"rows\",\"value\":4", "\"field\":\"style\",\"value\":\"neon\"",
                "\"field\":\"hours\",\"value\":{\"mode\":\"GAME\",\"open\":0,\"close\":24000}", "\"field\":\"hours\",\"value\":{\"mode\":\"REAL\",\"open\":-1,\"close\":60}",
                "\"field\":\"rotateAt\",\"value\":\"monday 25:00\"", "\"field\":\"offerPercent\",\"value\":100", "\"field\":\"remote\",\"value\":\"yes\"",
                "\"field\":\"requires\",\"value\":{\"permission\":\"bad node!\"}", "\"field\":\"name\",\"value\":\"" + "x".repeat(65) + "\"",
                "\"field\":\"parent\",\"value\":\"spawn_general_pickaxes\"", "\"field\":\"parent\",\"value\":\"spawn_general\"",
                "\"field\":\"kind\",\"value\":\"sub\"", "\"field\":\"colour\",\"value\":1", "\"field\":\"offerEnd\",\"value\":5"};
        for (String b : bad) {
            JsonObject o = r.desk.set(owner, obj("{\"shop\":\"spawn_general\",\"edited\":" + ed + "," + b + "}"));
            assertFalse(o.get("ok").getAsBoolean(), b);
        }
        assertEquals(sentBefore, r.count("shop_set"), "none of those was signed (a loop, a bad value, an unknown setting)");
        JsonObject ok = r.desk.set(owner, obj("{\"shop\":\"spawn_general\",\"edited\":" + ed + ",\"field\":\"hours\",\"value\":{\"mode\":\"REAL\",\"open\":540,\"close\":1260}}"));
        assertTrue(ok.get("ok").getAsBoolean(), ok.toString());
        assertTrue(ok.get("edited").getAsLong() > ed, "the answer carries the new stamp");
        assertTrue(ok.get("panelLog").getAsString().startsWith("changed the opening hours of spawn_general to "), ok.get("panelLog").getAsString());
        JsonObject s = r.desk.shop(owner, "spawn_general", false).getAsJsonObject("shop");
        assertEquals(540, s.getAsJsonObject("hours").get("open").getAsInt(), "read again after a change (the copy kept was dropped)");
        JsonObject stale = r.desk.set(owner, obj("{\"shop\":\"spawn_general\",\"edited\":" + ed + ",\"field\":\"columns\",\"value\":5}"));
        assertFalse(stale.get("ok").getAsBoolean());
        assertTrue(stale.get("stale").getAsBoolean(), "an old stamp is refused by the game server (D794)");
        JsonObject needs = r.desk.set(owner, obj("{\"shop\":\"spawn_general\",\"edited\":" + ok.get("edited").getAsLong()
                + ",\"field\":\"requires\",\"value\":{\"permission\":\"bsp.rank.gold\",\"playtimeHours\":10,\"hide\":true,\"reason\":\" Gold\\u0007rank \"}}"));
        assertTrue(needs.get("ok").getAsBoolean(), needs.toString());
        JsonObject rq = r.desk.shop(owner, "spawn_general", false).getAsJsonObject("shop").getAsJsonObject("requires");
        assertEquals("Gold rank", rq.get("reason").getAsString(), "a control character is a space, the ends trimmed");
        assertTrue(rq.get("hide").getAsBoolean());
    }

    @Test
    void turning_a_shop_off_takes_its_sections_with_it() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        JsonObject off = r.desk.enable(admin, obj("{\"shop\":\"spawn_general\",\"on\":false,\"edited\":" + edited(r, "spawn_general") + "}"));
        assertTrue(off.get("ok").getAsBoolean(), off.toString());
        assertEquals("turned shop spawn_general off", off.get("panelLog").getAsString());
        assertFalse(tile(r.desk.grid(admin, false), "spawn_general").get("enabled").getAsBoolean());
        assertFalse(r.desk.shop(admin, "spawn_general_pickaxes", false).getAsJsonObject("shop").get("enabled").getAsBoolean(), "its sections go off with it (SU-BC8)");
        JsonObject on = r.desk.enable(admin, obj("{\"shop\":\"spawn_general\",\"on\":true,\"edited\":" + edited(r, "spawn_general") + "}"));
        assertTrue(on.get("ok").getAsBoolean());
        assertTrue(tile(r.desk.grid(admin, false), "spawn_general").get("enabled").getAsBoolean());
    }

    @Test
    void a_section_whose_shop_is_gone_is_marked_and_may_go_to_top_level_then_on() { // BSP-Core 1.27.0, D853
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        JsonObject jam = tile(r.desk.grid(admin, false), "autumn_fair_jams");
        assertTrue(jam.get("parentGone").getAsBoolean(), "the grid marks it: " + jam);
        assertFalse(tile(r.desk.grid(admin, false), "spawn_general").get("parentGone").getAsBoolean());
        assertTrue(r.desk.shop(admin, "autumn_fair_jams", false).getAsJsonObject("shop").get("parentGone").getAsBoolean(), "and its page");
        assertFalse(r.desk.shop(admin, "spawn_general_food", false).getAsJsonObject("shop").get("parentGone").getAsBoolean(), "a section with its shop is not");
        JsonObject on = r.desk.enable(admin, obj("{\"shop\":\"autumn_fair_jams\",\"on\":true,\"edited\":" + edited(r, "autumn_fair_jams") + "}"));
        assertFalse(on.get("ok").getAsBoolean(), "no switch while its shop is gone: " + on);
        JsonObject food = r.desk.set(admin, obj("{\"shop\":\"spawn_general_food\",\"field\":\"parent\",\"value\":\"\",\"edited\":" + edited(r, "spawn_general_food") + "}"));
        assertFalse(food.get("ok").getAsBoolean(), "a section with its shop stays inside one: " + food);
        JsonObject top = r.desk.set(admin, obj("{\"shop\":\"autumn_fair_jams\",\"field\":\"parent\",\"value\":\"\",\"edited\":" + edited(r, "autumn_fair_jams") + "}"));
        assertTrue(top.get("ok").getAsBoolean(), "Top level: " + top);
        JsonObject moved = tile(r.desk.grid(admin, false), "autumn_fair_jams");
        assertFalse(moved.get("parentGone").getAsBoolean());
        assertEquals("", moved.get("parent").getAsString());
        JsonObject kind = r.desk.set(admin, obj("{\"shop\":\"autumn_fair_jams\",\"field\":\"kind\",\"value\":\"shop\",\"edited\":" + edited(r, "autumn_fair_jams") + "}"));
        assertTrue(kind.get("ok").getAsBoolean(), "it chooses a kind: " + kind);
        JsonObject turned = r.desk.enable(admin, obj("{\"shop\":\"autumn_fair_jams\",\"on\":true,\"edited\":" + edited(r, "autumn_fair_jams") + "}"));
        assertTrue(turned.get("ok").getAsBoolean(), "then its switch works: " + turned);
    }

    @Test
    void a_copy_takes_every_section_under_new_ids_and_is_made_off() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        assertFalse(r.desk.copy(owner, obj("{\"shop\":\"winter_sale\",\"newId\":\"spawn_general\",\"name\":\"X\"}")).get("ok").getAsBoolean(), "a taken id");
        JsonObject c = r.desk.copy(owner, obj("{\"shop\":\"winter_sale\",\"newId\":\"summer_sale\",\"name\":\"Summer Sale\"}"));
        assertTrue(c.get("ok").getAsBoolean(), c.toString());
        assertEquals("summer_sale", c.get("shop").getAsString());
        JsonObject g = r.desk.grid(owner, false);
        JsonObject t = tile(g, "summer_sale");
        assertEquals("Summer Sale", t.get("name").getAsString());
        assertFalse(t.get("enabled").getAsBoolean(), "made OFF (D797)");
        assertEquals(2, t.getAsJsonArray("sections").size());
        JsonObject page = r.desk.shop(owner, "summer_sale", false);
        boolean pointsAtCopy = false;
        for (JsonElement e : page.getAsJsonObject("shop").getAsJsonArray("cards")) {
            String sec = e.getAsJsonObject().get("section").getAsString();
            assertFalse(sec.startsWith("winter_sale"), "no section card points back at the first shop");
            pointsAtCopy |= sec.startsWith("summer_sale_");
        }
        assertTrue(pointsAtCopy);
        assertEquals(0, venue(t, "BSP-Spawn") == null ? 0 : 1, "stalls stay with the first shop");
        assertTrue(tile(g, "winter_sale").getAsJsonArray("sections").size() == 2, "the first shop is untouched");
    }

    @Test
    void deleting_a_section_takes_its_card_and_the_sections_under_it() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        JsonObject d = r.desk.delete(owner, obj("{\"shop\":\"spawn_general_tools\",\"edited\":" + edited(r, "spawn_general_tools") + "}"));
        assertTrue(d.get("ok").getAsBoolean(), d.toString());
        assertEquals("deleted shop spawn_general_tools with its 1 section", d.get("panelLog").getAsString());
        JsonObject g = r.desk.grid(owner, false);
        List<String> secs = new ArrayList<>();
        tile(g, "spawn_general").getAsJsonArray("sections").forEach(e -> secs.add(e.getAsJsonObject().get("id").getAsString()));
        assertEquals(List.of("spawn_general_food", "spawn_general_redstone"), secs);
        for (JsonElement e : r.desk.shop(owner, "spawn_general", false).getAsJsonObject("shop").getAsJsonArray("cards")) {
            assertFalse(e.getAsJsonObject().get("section").getAsString().equals("spawn_general_tools"), "its section card went with it");
        }
        assertFalse(r.desk.delete(owner, obj("{\"shop\":\"spawn_general_food\",\"edited\":1}")).get("ok").getAsBoolean(), "an old stamp is refused");
    }

    @Test
    void a_change_is_read_once_and_a_shop_change_leaves_the_stalls_kept() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        long ed = edited(r, "spawn_blocks"); // a REFRESH read
        r.desk.grid(owner, false);
        long lists = r.count("shop_list"), stalls = r.count("shop_venues");
        r.clock.addAndGet(1000);
        JsonObject ok = r.desk.set(owner, obj("{\"shop\":\"spawn_blocks\",\"field\":\"rows\",\"value\":2,\"edited\":" + ed + "}"));
        assertTrue(ok.get("ok").getAsBoolean(), ok.toString());
        long changedAt = r.clock.get();
        r.clock.addAndGet(500);
        r.desk.grid(owner, false); // the page reads again after its change
        long afterChange = r.count("shop_list");
        assertTrue(afterChange > lists, "the change's own page read the shops again");
        assertEquals(stalls, r.count("shop_venues"), "a shop's change leaves every server's stalls kept");
        r.clock.addAndGet(1000);
        r.shopsAt = changedAt; // the servers say a shop changed: the change already read
        r.snapshots();
        r.desk.grid(owner, false);
        assertEquals(afterChange, r.count("shop_list"), "a shopsAt older than the read kept is not read twice (the reads allowance)");
        r.clock.addAndGet(1000);
        r.stallsAt = r.clock.get();
        r.snapshots();
        r.desk.grid(owner, false);
        assertEquals(List.of("BSP-Spawn"), r.servers("shop_venues").subList(r.servers("shop_venues").size() - 1, r.servers("shop_venues").size()),
                "stallsAt reads that server's stalls again, and only them");
        assertEquals(afterChange, r.count("shop_list"));
    }

    // ------------------------------------------------------------------ SU-163: the cards

    private static JsonArray cardsOf(Rig r, String id) {
        return r.desk.shop(account(Rank.OWNER), id, false).getAsJsonObject("shop").getAsJsonArray("cards");
    }

    @Test
    void an_item_goes_where_it_is_dropped_and_a_card_moves_and_goes() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        long ed = edited(r, "spawn_general_food");
        JsonObject bad = r.desk.addCard(owner, obj("{\"shop\":\"spawn_general_food\",\"item\":\"not an id\",\"at\":0,\"edited\":" + ed + "}"));
        assertFalse(bad.get("ok").getAsBoolean());
        assertEquals(0, r.count("shop_item_add"));
        JsonObject add = r.desk.addCard(owner, obj("{\"shop\":\"spawn_general_food\",\"item\":\"minecraft:potion\",\"v\":\"minecraft:healing\",\"count\":1,\"at\":1,\"edited\":" + ed + "}"));
        assertTrue(add.get("ok").getAsBoolean(), add.toString());
        assertEquals(1, add.get("index").getAsInt(), "the answer says where it went, for the drawer");
        assertEquals("added minecraft:potion (minecraft:healing) to spawn_general_food at card 2", add.get("panelLog").getAsString());
        JsonArray c = cardsOf(r, "spawn_general_food");
        assertEquals("minecraft:potion", c.get(1).getAsJsonObject().get("item").getAsString(), "dropped on card 2: it is card 2 and the rest move along (D799)");
        assertEquals("minecraft:cooked_beef", c.get(2).getAsJsonObject().get("item").getAsString());
        JsonObject far = r.desk.addCard(owner, obj("{\"shop\":\"spawn_general_food\",\"item\":\"minecraft:apple\",\"at\":999,\"edited\":" + add.get("edited").getAsLong() + "}"));
        assertEquals(c.size(), far.get("index").getAsInt(), "past the end is the end");
        JsonObject mv = r.desk.moveCard(owner, obj("{\"shop\":\"spawn_general_food\",\"from\":0,\"to\":3,\"edited\":" + far.get("edited").getAsLong() + "}"));
        assertTrue(mv.get("ok").getAsBoolean(), mv.toString());
        assertEquals("minecraft:bread", cardsOf(r, "spawn_general_food").get(3).getAsJsonObject().get("item").getAsString());
        assertFalse(r.desk.moveCard(owner, obj("{\"shop\":\"spawn_general_food\",\"from\":0,\"to\":99,\"edited\":" + mv.get("edited").getAsLong() + "}")).get("ok").getAsBoolean());
        int before = cardsOf(r, "spawn_general_food").size();
        JsonObject rm = r.desk.removeCard(owner, obj("{\"shop\":\"spawn_general_food\",\"index\":3,\"edited\":" + mv.get("edited").getAsLong() + "}"));
        assertTrue(rm.get("ok").getAsBoolean(), rm.toString());
        assertTrue(rm.get("panelLog").getAsString().startsWith("removed card 4 (minecraft:bread)"), rm.get("panelLog").getAsString());
        assertEquals(before - 1, cardsOf(r, "spawn_general_food").size());
        JsonObject stale = r.desk.removeCard(owner, obj("{\"shop\":\"spawn_general_food\",\"index\":0,\"edited\":" + mv.get("edited").getAsLong() + "}"));
        assertTrue(stale.has("stale") && stale.get("stale").getAsBoolean(), "an old stamp is refused (D794)");
        assertFalse(r.desk.removeCard(account(Rank.MODERATOR), obj("{\"shop\":\"spawn_general_food\",\"index\":0,\"edited\":1}")).get("ok").getAsBoolean());
    }

    @Test
    void every_card_field_is_checked_and_the_counting_ones_say_the_counts_restart() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        long ed = edited(r, "spawn_general");
        String[] bad = {"\"field\":\"count\",\"value\":0", "\"field\":\"count\",\"value\":65", "\"field\":\"buy\",\"value\":-2", "\"field\":\"currency\",\"value\":5",
                "\"field\":\"weight\",\"value\":101", "\"field\":\"stockGlobal\",\"value\":1.5", "\"field\":\"match\",\"value\":\"any\"",
                "\"field\":\"note\",\"value\":[\"a\",\"b\",\"c\",\"d\"]", "\"field\":\"note\",\"value\":[\"" + "x".repeat(61) + "\"]",
                "\"field\":\"stockRefill\",\"value\":\"same\"", "\"field\":\"sellRefill\",\"value\":\"rotation\"", "\"field\":\"stockRefill\",\"value\":\"0h\"",
                "\"field\":\"stockRefill\",\"value\":\"someday 06:00\"", "\"field\":\"price\",\"value\":5", "\"field\":\"instant\",\"value\":1"};
        for (String b : bad) {
            assertFalse(r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":4,\"edited\":" + ed + "," + b + "}")).get("ok").getAsBoolean(), b);
        }
        assertFalse(r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":0,\"edited\":" + ed + ",\"field\":\"buy\",\"value\":5}")).get("ok").getAsBoolean(),
                "a section's card has no price");
        assertEquals(0, r.count("shop_item_set"), "none of those was signed");
        JsonObject price = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":4,\"edited\":" + ed + ",\"field\":\"buy\",\"value\":175}"));
        assertTrue(price.get("ok").getAsBoolean(), price.toString());
        assertFalse(price.has("countsRestart"), "a price leaves the counts");
        JsonObject lim = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":4,\"edited\":" + price.get("edited").getAsLong() + ",\"field\":\"stockGlobal\",\"value\":200}"));
        assertTrue(lim.get("countsRestart").getAsBoolean(), "a limit starts the card's counts again, and the page says so");
        JsonObject rule = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":4,\"edited\":" + lim.get("edited").getAsLong() + ",\"field\":\"stockRefill\",\"value\":\"Monday 06:00\"}"));
        assertTrue(rule.get("ok").getAsBoolean(), rule.toString());
        JsonObject note = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":4,\"edited\":" + rule.get("edited").getAsLong() + ",\"field\":\"note\",\"value\":[\" Smelted | here \",\"\"]}"));
        assertTrue(note.get("ok").getAsBoolean(), note.toString());
        JsonObject c = cardsOf(r, "spawn_general").get(4).getAsJsonObject();
        assertEquals(175, c.get("buy").getAsInt());
        assertEquals("monday 06:00", c.get("stockRefill").getAsString());
        assertEquals(200, c.get("stockLeft").getAsInt(), "the count started again: full");
        assertEquals(1, c.getAsJsonArray("note").size(), "an empty line is dropped");
        assertEquals("Smelted   here", c.getAsJsonArray("note").get(0).getAsString(), "a | would split a line in game: it is a space");
        long rotEd = edited(r, "spawn_challenge");
        assertTrue(r.desk.setCard(owner, obj("{\"shop\":\"spawn_challenge\",\"index\":0,\"edited\":" + rotEd + ",\"field\":\"sellRefill\",\"value\":\"rotation\"}")).get("ok").getAsBoolean(),
                "each rotation on a rotating shop");
        JsonObject refill = r.desk.refillCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":4,\"edited\":" + note.get("edited").getAsLong() + "}"));
        assertTrue(refill.get("ok").getAsBoolean(), refill.toString());
        assertTrue(refill.get("panelLog").getAsString().startsWith("refilled card 5"), refill.get("panelLog").getAsString());
    }

    @Test
    void an_export_is_the_shop_and_its_sections_as_the_import_file_for_every_rank() {
        Rig r = new Rig();
        JsonObject e = r.desk.export(account(Rank.MODERATOR), "spawn_general");
        assertTrue(e.get("ok").getAsBoolean(), e.toString());
        assertEquals(1, e.getAsJsonObject("json").get("format").getAsInt());
        assertEquals(5, e.get("shops").getAsInt(), "the shop and its four sections");
        assertTrue(e.get("file").getAsString().startsWith("spawn_general-"), e.get("file").getAsString());
        assertFalse(e.has("panelLog"), "a read: nothing logged");
        assertFalse(r.desk.export(account(Rank.OWNER), "../x").get("ok").getAsBoolean());
    }

    // ------------------------------------------------------------------ the audit of SU-161 to SU-163 (SU-164)

    @Test
    void on_must_be_true_or_false_and_a_coin_price_is_capped() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        long ed = edited(r, "spawn_general");
        for (String on : List.of("\"true\"", "1", "null")) {
            assertFalse(r.desk.enable(owner, obj("{\"shop\":\"spawn_general\",\"on\":" + on + ",\"edited\":" + ed + "}")).get("ok").getAsBoolean(), on);
        }
        assertEquals(0, r.count("shop_enable"), "a text \"true\" or a 1 never turns a shop off");
        assertFalse(r.desk.set(owner, obj("{\"shop\":\"spawn_general\",\"field\":\"requires\",\"edited\":" + ed + ",\"value\":{\"hide\":\"yes\"}}")).get("ok").getAsBoolean());
        // card 9 (index 8) is paid in gold
        JsonObject big = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":8,\"field\":\"buy\",\"value\":10001,\"edited\":" + ed + "}"));
        assertFalse(big.get("ok").getAsBoolean(), "a price in coins is at most " + ShopDesk.MAX_COINS);
        JsonObject toCoins = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":4,\"field\":\"currency\",\"value\":4,\"edited\":" + ed + "}"));
        assertTrue(toCoins.get("ok").getAsBoolean(), "180 pieces may become 180 illyrium: " + toCoins);
        long ed2 = toCoins.get("edited").getAsLong();
        JsonObject pieces = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":5,\"field\":\"buy\",\"value\":20000,\"edited\":" + ed2 + "}"));
        assertTrue(pieces.get("ok").getAsBoolean(), "pieces keep their own limit");
        JsonObject toCoins2 = r.desk.setCard(owner, obj("{\"shop\":\"spawn_general\",\"index\":5,\"field\":\"currency\",\"value\":1,\"edited\":" + pieces.get("edited").getAsLong() + "}"));
        assertFalse(toCoins2.get("ok").getAsBoolean(), "20,000 pieces never become 20,000 gold coins");
    }

    @Test
    void a_game_servers_answer_never_writes_the_panels_log_and_its_clock_does_not_matter() {
        Rig r = new Rig();
        Account mod = account(Rank.MODERATOR);
        JsonObject sales = r.desk.sales(mod, "spawn_general", 7);
        assertFalse(sales.has("panelLog"), "a read carries no log line, whatever the server sends");
        r.desk.grid(mod, false);
        long lists = r.count("shop_list");
        r.clock.addAndGet(1000);
        r.shopsAt = 9_000_000_000_000L; // a game clock far ahead of the panel's
        r.snapshots();
        r.desk.grid(mod, false);
        assertEquals(lists + 1, r.count("shop_list"), "a move read once");
        r.clock.addAndGet(1000);
        r.snapshots(); // the same value again
        r.desk.grid(mod, false);
        assertEquals(lists + 1, r.count("shop_list"), "and not again while it stays (no comparison with the panel's clock)");
        r.clock.addAndGet(1000);
        r.shopsAt = 5; // a game clock far behind
        r.snapshots();
        r.desk.grid(mod, false);
        assertEquals(lists + 2, r.count("shop_list"), "a move backwards is a move too");
    }

    @Test
    void the_panels_own_refusals_are_not_kept_for_everyone() {
        assertTrue(ShopDesk.ownRefusal("That is 30 reads in a minute: wait a moment."));
        assertTrue(ShopDesk.ownRefusal("The panel is waiting on other actions; try again in a moment."));
        assertFalse(ShopDesk.ownRefusal("No answer from BSP-Spawn in 15 seconds."));
        assertTrue(ShopDesk.plain('a') && !ShopDesk.plain(0x85) && !ShopDesk.plain(0x2028) && !ShopDesk.plain('§'), "C1 controls, line separators and colour codes are not plain");
    }

    @Test
    void a_section_is_copied_with_its_shop_and_the_sales_carry_coins_per_tier() {
        Rig r = new Rig();
        JsonObject c = r.desk.copy(account(Rank.OWNER), obj("{\"shop\":\"spawn_general_tools\",\"newId\":\"tools_copy\",\"name\":\"Tools copy\"}"));
        assertFalse(c.get("ok").getAsBoolean());
        assertTrue(c.get("message").getAsString().contains("copy spawn_general"), "BSP-Core copies top shops only (D823): " + c);
        assertEquals(0, r.count("shop_copy"));
        JsonObject s = r.desk.sales(account(Rank.MODERATOR), "spawn_general", 7);
        assertEquals(5, s.getAsJsonArray("coinsIn").size(), "five counts, copper to illyrium (D820)");
        assertTrue(s.getAsJsonArray("sellers").size() > 0, "the players paid most (D821)");
    }

    @Test
    void a_shop_inside_another_never_goes_to_the_top_level_and_a_new_section_follows_its_shop() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        JsonObject top = r.desk.set(owner, obj("{\"shop\":\"spawn_general_tools\",\"field\":\"parent\",\"value\":\"\",\"edited\":" + edited(r, "spawn_general_tools") + "}"));
        assertFalse(top.get("ok").getAsBoolean(), "BSP-Core refuses the top level for any shop with a parent: " + top);
        assertEquals(0, r.count("shop_set"));
        JsonObject sec = r.desk.create(owner, obj("{\"name\":\"Lights\",\"id\":\"spawn_general_lights\",\"parent\":\"spawn_general\",\"edited\":" + edited(r, "spawn_general")
                + ",\"picture\":\"minecraft:lantern\"}"));
        assertTrue(sec.get("ok").getAsBoolean(), sec.toString());
        assertTrue(r.desk.shop(owner, "spawn_general_lights", false).getAsJsonObject("shop").get("enabled").getAsBoolean(), "on with its shop, as BSP-Core reports it");
    }
}
