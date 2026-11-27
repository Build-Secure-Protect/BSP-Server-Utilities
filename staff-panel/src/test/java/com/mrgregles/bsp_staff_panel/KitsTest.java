package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.kits.KitDesk;
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
 * The KITS tab's desk (SU-172 to SU-174; D838 to D867): every rank reads; the kits are asked of one server with kits on and
 * kept a minute for everyone; a change heard ({@code kitsAt}) drops them; every change is checked before it is signed; a gift
 * goes through the player's own server when kits are on there; a kit with items the server lacks is only deleted; the
 * waiting wording when servers are still reading. The made-up game server is {@link DemoKits}, as {@code --demo} answers.
 */
class KitsTest {
    static final byte[] KEY = new byte[32];
    static final String KES = DemoKits.uuidOf("Kestrel_88"), PIXL = DemoKits.uuidOf("Pixl");

    /** A desk over BSP-Spawn (kits on, database), Frostwarf (kits on, Kestrel_88) and Stormfall (kits off, busiest, Pixl). */
    private static final class Rig {
        final AtomicLong clock = new AtomicLong(60_000_000L);
        final Feed feed = new Feed(clock::get);
        final List<JsonObject> sent = new CopyOnWriteArrayList<>();
        final Actions actions;
        final KitDesk desk;
        long kitsAt = 0;

        Rig() {
            DemoKits.reset();
            Actions[] a = new Actions[1];
            a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
                JsonObject act = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(payload.substring(0, payload.indexOf('.'))), StandardCharsets.UTF_8)).getAsJsonObject();
                sent.add(act);
                JsonObject k = DemoKits.answer(act.get("action").getAsString(), act, act.get("server").getAsString());
                JsonObject ans = new JsonObject();
                ans.addProperty("id", uid);
                ans.add("ok", k.get("ok"));
                ans.add("message", k.get("message"));
                if (!k.get("data").isJsonNull()) {
                    ans.add("data", k.get("data"));
                }
                new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(KEY, ans.toString())))).start();
            }, clock::get, 3);
            actions = a[0];
            desk = new KitDesk(feed, () -> actions, clock::get);
            snapshots();
        }

        void snapshots() {
            feed.accept("BSP-Spawn", clock.get(), "{\"v\":1,\"server\":\"BSP-Spawn\",\"at\":" + clock.get() + ",\"answers\":true,\"kits\":true,\"kitsOn\":true,\"kitsAt\":" + kitsAt
                    + ",\"lotteryUp\":true,\"players\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Glimmer\"}]}");
            feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":" + clock.get() + ",\"answers\":true,\"kits\":true,\"kitsOn\":true,\"kitsAt\":" + kitsAt
                    + ",\"players\":[{\"id\":\"" + KES + "\",\"name\":\"Kestrel_88\"}]}");
            feed.accept("Stormfall", clock.get(), "{\"v\":1,\"server\":\"Stormfall\",\"at\":" + clock.get() + ",\"answers\":true,\"kits\":true,\"kitsOn\":false,\"kitsAt\":" + kitsAt
                    + ",\"lotteryUp\":true,\"players\":[{\"id\":\"" + PIXL + "\",\"name\":\"Pixl\"},{\"id\":\"00000000-0000-0000-0000-000000000002\",\"name\":\"A\"},"
                    + "{\"id\":\"00000000-0000-0000-0000-000000000003\",\"name\":\"B\"}]}");
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

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static boolean ok(JsonObject o) {
        return o.get("ok").getAsBoolean();
    }

    private static long edited(Rig r, String id) {
        return r.desk.kit(account(Rank.OWNER), id, false).getAsJsonObject("kit").get("edited").getAsLong(); // a change drops what was kept: this reads afresh
    }

    @Test
    void the_grid_lists_every_kit_for_every_rank_from_a_server_with_kits_on_and_names_those_off() {
        Rig r = new Rig();
        JsonObject g = r.desk.grid(account(Rank.MODERATOR), false);
        assertTrue(ok(g), g.toString());
        List<String> ids = new ArrayList<>();
        g.getAsJsonArray("kits").forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsString()));
        assertEquals(List.of("starter", "daily", "miner", "builder", "raider", "spawn_welcome", "vip_monthly", "winter_event"), ids, "the kit browser's order (D841)");
        assertEquals("BSP-Spawn", g.get("server").getAsString(), "a server with kits on, its database connected, before the busier one with kits off (D863)");
        assertEquals("[\"Stormfall\"]", g.getAsJsonArray("off").toString(), "the strip names the servers with kits off (D863)");
        assertEquals(36, g.getAsJsonObject("choices").get("maxItems").getAsInt(), "the pickers come with it");
        JsonObject miner = r.desk.kit(account(Rank.MODERATOR), "miner", false);
        assertTrue(ok(miner) && miner.getAsJsonObject("kit").get("owed").getAsInt() == 3 && !miner.has("kits"), "a kit's page: " + miner);
        assertFalse(ok(r.desk.kit(account(Rank.MODERATOR), "nope", false)), "no such kit");
        assertFalse(ok(r.desk.kit(account(Rank.MODERATOR), "Bad Id", false)), "not an id");
        assertEquals(1, r.count("kit_list"), "kept a minute for everyone");
        r.kitsAt = 5;
        r.clock.addAndGet(1_000);
        r.snapshots();
        r.desk.grid(account(Rank.ADMIN), false);
        r.kitsAt = 6;
        r.clock.addAndGet(1_000);
        r.snapshots();
        r.desk.grid(account(Rank.ADMIN), false);
        assertEquals(3, r.count("kit_list"), "each kitsAt that moves drops the kits kept (D845)");
    }

    @Test
    void changes_are_for_admins_and_owners_and_checked_before_they_are_signed() {
        Rig r = new Rig();
        Account mod = account(Rank.MODERATOR), admin = account(Rank.ADMIN);
        JsonObject no = r.desk.create(mod, obj("{\"id\":\"nether_run\",\"name\":\"Nether Run\"}"));
        assertFalse(ok(no));
        assertEquals(KitDesk.ADMINS, no.get("message").getAsString());
        assertEquals(0, r.count("kit_create"), "a Moderator signs nothing");
        assertTrue(no.get("panelLog").getAsString().startsWith("tried to make kit"), "the try is logged");
        assertFalse(ok(r.desk.create(admin, obj("{\"id\":\"N\",\"name\":\"Nether Run\"}"))), "a bad id");
        assertFalse(ok(r.desk.create(admin, obj("{\"id\":\"miner\",\"name\":\"Again\"}"))), "taken");
        assertFalse(ok(r.desk.create(admin, obj("{\"id\":\"nether_run\",\"name\":\"" + "x".repeat(25) + "\"}"))), "a long name");
        assertEquals(0, r.count("kit_create"));
        JsonObject made = r.desk.create(admin, obj("{\"id\":\"nether_run\",\"name\":\"Nether Run\"}"));
        assertTrue(ok(made), made.toString());
        assertEquals("made kit nether_run, off", made.get("panelLog").getAsString());
        long ed = edited(r, "nether_run");
        JsonObject named = r.desk.set(admin, obj("{\"id\":\"nether_run\",\"field\":\"name\",\"value\":\"Nether Dash\",\"edited\":" + ed + "}"));
        assertTrue(ok(named) && named.get("edited").getAsLong() > ed, named.toString());
        JsonObject stale = r.desk.set(admin, obj("{\"id\":\"nether_run\",\"field\":\"name\",\"value\":\"Old\",\"edited\":" + ed + "}"));
        assertTrue(!ok(stale) && stale.get("stale").getAsBoolean(), "the old stamp is refused by the game server: " + stale);
        long sets = r.count("kit_set");
        String[][] bad = {{"cooldownSeconds", "-1"}, {"price", "1000000000001"}, {"ranks", "[\"vip!\"]"}, {"conditions", "[\"no_wings\"]"}, {"grant", "\"SOMETIMES\""},
                {"inBrowser", "\"yes\""}, {"description", "\"" + "d".repeat(81) + "\""}, {"taken", "3"}};
        for (String[] b : bad) {
            JsonObject o = r.desk.set(admin, obj("{\"id\":\"nether_run\",\"field\":\"" + b[0] + "\",\"value\":" + b[1] + ",\"edited\":" + edited(r, "nether_run") + "}"));
            assertFalse(ok(o), b[0] + " " + b[1]);
        }
        assertEquals(sets, r.count("kit_set"), "none of them signed");
        JsonObject ranks = r.desk.set(admin, obj("{\"id\":\"nether_run\",\"field\":\"ranks\",\"value\":\"miner, builder ,miner\",\"edited\":" + edited(r, "nether_run") + "}"));
        assertTrue(ok(ranks), ranks.toString());
        assertEquals("[\"miner\",\"builder\"]", r.sent.get(r.sent.size() - 1).get("value").toString(), "text joined by commas goes as a list, each once");
        JsonObject cond = r.desk.set(admin, obj("{\"id\":\"nether_run\",\"field\":\"conditions\",\"value\":[\"no_totem\"],\"edited\":" + edited(r, "nether_run") + "}"));
        assertTrue(ok(cond), "a condition BSP-Core offers");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 37; i++) {
            many.append(i == 0 ? "" : ",").append("{\"item\":\"minecraft:stone\",\"count\":1,\"nbt\":\"\"}");
        }
        JsonObject tooMany = r.desk.items(admin, obj("{\"id\":\"nether_run\",\"items\":[" + many + "],\"edited\":" + edited(r, "nether_run") + "}"));
        assertEquals("A kit holds at most 36 stacks.", tooMany.get("message").getAsString(), "the limit from BSP-Core's pickers");
        assertFalse(ok(r.desk.items(admin, obj("{\"id\":\"nether_run\",\"items\":[{\"item\":\"Stone\",\"count\":1}],\"edited\":" + edited(r, "nether_run") + "}"))), "not an item id");
        assertFalse(ok(r.desk.items(admin, obj("{\"id\":\"nether_run\",\"items\":[{\"item\":\"minecraft:stone\",\"count\":0}],\"edited\":" + edited(r, "nether_run") + "}"))), "a count of 0");
        assertEquals(0, r.count("kit_items"));
        JsonObject items = r.desk.items(admin, obj("{\"id\":\"nether_run\",\"one\":true,\"items\":[{\"item\":\"minecraft:golden_apple\",\"count\":2,\"nbt\":\"\"}],\"edited\":" + edited(r, "nether_run") + "}"));
        assertTrue(ok(items) && r.sent.get(r.sent.size() - 1).get("one").getAsBoolean(), items.toString());
        assertTrue(ok(r.desk.icon(admin, obj("{\"id\":\"nether_run\",\"icon\":{\"item\":\"minecraft:golden_apple\",\"nbt\":\"\"},\"edited\":" + edited(r, "nether_run") + "}"))));
        assertTrue(ok(r.desk.icon(admin, obj("{\"id\":\"nether_run\",\"icon\":null,\"edited\":" + edited(r, "nether_run") + "}"))), "the first item");
        assertTrue(r.sent.get(r.sent.size() - 1).get("icon").isJsonNull());
        r.clock.addAndGet(61_000); // ten changes a minute per staff member (the panel's own allowance)
        r.snapshots();
        assertFalse(ok(r.desk.enable(admin, obj("{\"id\":\"nether_run\",\"on\":\"true\",\"edited\":" + edited(r, "nether_run") + "}"))), "a text true is not on");
        JsonObject on = r.desk.enable(admin, obj("{\"id\":\"nether_run\",\"on\":true,\"edited\":" + edited(r, "nether_run") + "}"));
        assertTrue(ok(on) && on.get("panelLog").getAsString().equals("turned kit nether_run on"));
        JsonObject copy = r.desk.copy(admin, obj("{\"id\":\"nether_run\",\"newId\":\"nether_two\",\"name\":\"Nether Two\"}"));
        assertTrue(ok(copy), copy.toString());
        assertFalse(r.desk.kit(admin, "nether_two", false).getAsJsonObject("kit").get("enabled").getAsBoolean(), "a copy is made off (K6)");
        JsonObject del = r.desk.delete(admin, obj("{\"id\":\"nether_two\",\"edited\":" + edited(r, "nether_two") + "}"));
        assertTrue(ok(del) && del.get("panelLog").getAsString().equals("deleted kit nether_two"), del.toString());
    }

    @Test
    void a_kit_with_items_the_server_lacks_is_only_deleted() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        JsonObject o = r.desk.enable(admin, obj("{\"id\":\"winter_event\",\"on\":true,\"edited\":" + edited(r, "winter_event") + "}"));
        assertEquals(KitDesk.missingWords("BSP-Spawn"), o.get("message").getAsString(), "D867");
        assertFalse(ok(r.desk.copy(admin, obj("{\"id\":\"winter_event\",\"newId\":\"winter_two\",\"name\":\"Winter Two\"}"))));
        assertEquals(0, r.count("kit_enable") + r.count("kit_copy"));
        assertTrue(ok(r.desk.delete(admin, obj("{\"id\":\"winter_event\",\"edited\":" + edited(r, "winter_event") + "}"))), "deleting works");
    }

    @Test
    void a_gift_goes_through_the_players_own_server_when_kits_are_on_there_and_records_reset() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        JsonObject give = r.desk.give(admin, obj("{\"id\":\"daily\",\"player\":\"" + KES + "\",\"name\":\"Kestrel_88\"}"));
        assertTrue(ok(give), give.toString());
        assertEquals("gave kit daily to Kestrel_88", give.get("panelLog").getAsString());
        assertTrue(ok(r.desk.give(admin, obj("{\"id\":\"daily\",\"player\":\"" + PIXL + "\",\"name\":\"Pixl\"}"))));
        String offline = DemoKits.uuidOf("Offline_Olly");
        assertTrue(ok(r.desk.give(admin, obj("{\"id\":\"daily\",\"player\":\"" + offline + "\",\"name\":\"Offline_Olly\"}"))));
        assertEquals(List.of("Frostwarf", "BSP-Spawn", "BSP-Spawn"), r.servers("kit_give"), "the player's server with kits on; kits off there or offline: the answering server (D864)");
        assertFalse(ok(r.desk.give(account(Rank.MODERATOR), obj("{\"id\":\"daily\",\"player\":\"" + KES + "\"}"))), "a Moderator gives nothing");
        assertFalse(ok(r.desk.give(admin, obj("{\"id\":\"daily\",\"player\":\"Kestrel_88\"}"))), "a name is not a player id");
        JsonObject rec = r.desk.records(account(Rank.MODERATOR), KES);
        assertTrue(ok(rec), rec.toString());
        boolean deleted = false, daily = false;
        for (JsonElement e : rec.getAsJsonArray("rows")) {
            JsonObject row = e.getAsJsonObject();
            deleted |= row.get("kit").getAsString().equals("autumn_event") && !row.get("here").getAsBoolean();
            daily |= row.get("kit").getAsString().equals("daily") && row.get("owed").getAsInt() == 1;
        }
        assertTrue(deleted && daily, "a deleted kit by its id, the gift owed (D866): " + rec);
        JsonObject vague = r.desk.reset(admin, obj("{\"player\":\"" + KES + "\"}"));
        assertFalse(ok(vague), "every kit only when asked for");
        assertEquals(0, r.count("kit_reset"));
        JsonObject one = r.desk.reset(admin, obj("{\"player\":\"" + KES + "\",\"id\":\"autumn_event\",\"name\":\"Kestrel_88\"}"));
        assertTrue(ok(one) && one.get("panelLog").getAsString().equals("reset kit autumn_event for Kestrel_88"), one.toString());
        JsonObject all = r.desk.reset(admin, obj("{\"player\":\"" + KES + "\",\"all\":true,\"name\":\"Kestrel_88\"}"));
        assertTrue(ok(all) && r.sent.get(r.sent.size() - 1).get("all").getAsBoolean() && !r.sent.get(r.sent.size() - 1).has("kit"), all.toString());
        assertEquals(0, r.desk.records(admin, KES).getAsJsonArray("rows").size());
    }

    @Test
    void a_refused_change_never_hides_the_next_change_in_game() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        r.desk.grid(admin, false);
        JsonObject stale = r.desk.set(admin, obj("{\"id\":\"daily\",\"field\":\"price\",\"value\":5,\"edited\":1}"));
        assertFalse(ok(stale), "an old stamp");
        r.desk.grid(admin, false);
        long lists = r.count("kit_list");
        r.kitsAt = 77;
        r.clock.addAndGet(1_000);
        r.snapshots();
        r.desk.grid(admin, false);
        assertEquals(lists + 1, r.count("kit_list"), "a kit changed in game right after a refusal is read again (audit: the refusal held it back)");
    }

    @Test
    void servers_still_reading_are_said_apart_from_no_server() {
        AtomicLong clock = new AtomicLong(60_000_000L);
        Feed feed = new Feed(clock::get);
        KitDesk kits = new KitDesk(feed, () -> null, clock::get);
        ShopDesk shops = new ShopDesk(feed, () -> null, clock::get);
        Account mod = account(Rank.MODERATOR);
        assertEquals(KitDesk.NO_SERVER, kits.grid(mod, false).get("message").getAsString());
        assertEquals(ShopDesk.NO_SERVER, shops.grid(mod, false).get("message").getAsString());
        clock.addAndGet(25_000); // past the failure kept
        feed.accept("BSP-Spawn", clock.get(), "{\"v\":1,\"server\":\"BSP-Spawn\",\"at\":" + clock.get() + ",\"answers\":true,\"kits\":false,\"kitsWait\":true,\"shops\":false,\"shopsWait\":true,\"players\":[]}");
        assertEquals(KitDesk.WAITING, kits.grid(mod, true).get("message").getAsString(), "D862");
        assertEquals(ShopDesk.WAITING, shops.grid(mod, true).get("message").getAsString(), "D862");
    }
}
