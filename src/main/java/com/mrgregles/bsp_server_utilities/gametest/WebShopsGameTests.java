package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebShops;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The staff panel's shops on the game server (SU-165; D788 to D825), through the real BSP-Core jar (1.27.0) loaded in the
 * dev run: every action as the panel signs it, BSP-Core's answers put in the panel's shape, refusals as sentences, the
 * stamp check, and the snapshot's {@code shops}, {@code shopsAt}; and without BSP-Core's shops, every action says so. The
 * test world persists, so every shop made here has an id of its own and is deleted afterwards, also when a check fails.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebShopsGameTests {
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 5 + 3);
        }
    }

    private static WebActions.Answer act(MinecraftServer server, String rank, String action, String fields) {
        String json = "{\"v\":1,\"id\":\"shop-" + action + "-" + System.nanoTime() + "\",\"at\":" + System.currentTimeMillis() + ",\"by\":\"Ana_Admin\",\"rank\":\"" + rank
                + "\",\"server\":\"" + Storage.currentServerId() + "\",\"action\":\"" + action + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}";
        return WebActions.handle(server, WebActions.sign(KEY, json));
    }

    private static JsonObject data(WebActions.Answer a) {
        return a.data() != null && a.data().isJsonObject() ? a.data().getAsJsonObject() : new JsonObject();
    }

    private static long stamp(MinecraftServer server, String shop) {
        WebActions.Answer r = act(server, "MODERATOR", "shop_read", "\"shop\":\"" + shop + "\"");
        return r.ok() ? data(r).getAsJsonObject("shop").get("edited").getAsLong() : -1;
    }

    /** Every shop made by a test, deleted afterwards whatever happened (a top shop takes its sections with it). */
    private static final java.util.List<String> MADE = new java.util.ArrayList<>();

    private static String fresh() {
        String id = "gt" + Long.toString(System.nanoTime(), 36).toLowerCase();
        MADE.add(id);
        return id;
    }

    private interface Body {
        void run(MinecraftServer server);
    }

    private static void with(GameTestHelper h, Body body) {
        MinecraftServer server = h.getLevel().getServer();
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        try {
            if (!WebShops.available()) {
                h.fail("BSP-Core 1.26.0 with BspShops is not loaded in this run");
            }
            body.run(server);
        } finally {
            for (String id : List.copyOf(MADE)) { // left over by a failed check: never kept in the persisted world (audit)
                long ed = stamp(server, id);
                if (ed >= 0) {
                    act(server, "OWNER", "shop_delete", "\"shop\":\"" + id + "\",\"edited\":" + ed);
                }
                long copy = stamp(server, id + "c");
                if (copy >= 0) {
                    act(server, "OWNER", "shop_delete", "\"shop\":\"" + id + "c\",\"edited\":" + copy);
                }
            }
            MADE.clear();
            WebShops.availableForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void shops_are_made_off_listed_read_and_the_snapshot_moves(GameTestHelper h) {
        with(h, server -> {
            String id = fresh();
            long before = WebShops.shopsAt();
            WebActions.Answer made = act(server, "OWNER", "shop_create", "\"shop\":\"" + id + "\",\"name\":\"Test Stall\",\"kind\":\"shop\"");
            h.assertTrue(made.ok(), "made: " + made.message());
            h.assertTrue(WebShops.shopsAt() > before, "BSP-Core's ShopEvent.Changed moved shopsAt (D817)");
            JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
            h.assertTrue(snap.get("shops").getAsBoolean() && snap.get("shopsAt").getAsLong() == WebShops.shopsAt() && snap.has("stallsAt"), "the snapshot says shops: " + snap.get("shops"));
            WebActions.Answer list = act(server, "MODERATOR", "shop_list", "");
            h.assertTrue(list.ok(), "listed for every rank: " + list.message());
            JsonObject mine = null;
            for (JsonElement e : data(list).getAsJsonArray("shops")) {
                if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
                    mine = e.getAsJsonObject();
                }
            }
            h.assertTrue(mine != null && !mine.get("enabled").getAsBoolean(), "listed, made off (D795): " + mine);
            h.assertTrue(mine.getAsJsonObject("hours").get("mode").getAsString().equals("ALWAYS") && mine.has("peek") && mine.has("requires"), "the panel's shape: " + mine);
            h.assertTrue(data(list).has("timeZone"), "the time zone too");
            WebActions.Answer read = act(server, "MODERATOR", "shop_read", "\"shop\":\"" + id + "\"");
            h.assertTrue(read.ok() && data(read).getAsJsonObject("shop").getAsJsonArray("cards").size() == 0 && data(read).getAsJsonObject("shop").has("draws"), "read: " + read.data());
            WebActions.Answer missing = act(server, "MODERATOR", "shop_read", "\"shop\":\"" + id + "x\"");
            h.assertTrue(!missing.ok() && missing.message().contains("no such shop"), "a missing shop is said: " + missing.message());
            WebActions.Answer del = act(server, "OWNER", "shop_delete", "\"shop\":\"" + id + "\",\"edited\":" + stamp(server, id));
            h.assertTrue(del.ok(), "deleted: " + del.message());
        });
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void a_moderator_changes_nothing_and_a_stale_stamp_is_refused(GameTestHelper h) {
        with(h, server -> {
            String id = fresh();
            WebActions.Answer mod = act(server, "MODERATOR", "shop_create", "\"shop\":\"" + id + "\",\"name\":\"Nope\",\"kind\":\"shop\"");
            h.assertTrue(!mod.ok() && mod.message().startsWith("Refused: only Admins and Owners"), "a Moderator: " + mod.message());
            h.assertTrue(act(server, "OWNER", "shop_create", "\"shop\":\"" + id + "\",\"name\":\"Stamp\",\"kind\":\"shop\"").ok(), "made");
            long ed = stamp(server, id);
            WebActions.Answer saved = act(server, "ADMIN", "shop_set", "\"shop\":\"" + id + "\",\"field\":\"name\",\"value\":\"Stamp Two\",\"edited\":" + ed);
            h.assertTrue(saved.ok() && data(saved).get("edited").getAsLong() > ed, "saved with the new stamp: " + saved.message());
            WebActions.Answer stale = act(server, "ADMIN", "shop_set", "\"shop\":\"" + id + "\",\"field\":\"name\",\"value\":\"Stamp Three\",\"edited\":" + ed);
            h.assertTrue(!stale.ok() && data(stale).get("stale").getAsBoolean() && stale.message().contains("changed in game"), "the old stamp is refused (D794): " + stale.message());
            WebActions.Answer hours = act(server, "ADMIN", "shop_set", "\"shop\":\"" + id + "\",\"field\":\"hours\",\"value\":{\"mode\":\"REAL\",\"open\":540,\"close\":1260},\"edited\":" + stamp(server, id));
            h.assertTrue(hours.ok(), "opening hours through setHours: " + hours.message());
            WebActions.Answer needs = act(server, "ADMIN", "shop_set", "\"shop\":\"" + id + "\",\"field\":\"requires\",\"value\":{\"permission\":\"bsp.rank.gold\",\"playtimeHours\":10,\"hide\":true,\"reason\":\"Gold\"},\"edited\":" + stamp(server, id));
            h.assertTrue(needs.ok(), "needs through setRequires: " + needs.message());
            JsonObject s = data(act(server, "MODERATOR", "shop_read", "\"shop\":\"" + id + "\"")).getAsJsonObject("shop");
            h.assertTrue(s.getAsJsonObject("hours").get("open").getAsInt() == 540 && s.getAsJsonObject("requires").get("hide").getAsBoolean(), "both read back: " + s);
            WebActions.Answer on = act(server, "ADMIN", "shop_enable", "\"shop\":\"" + id + "\",\"on\":\"true\",\"edited\":" + stamp(server, id));
            h.assertTrue(!on.ok() && on.message().contains("On or off"), "a text true is not on: " + on.message());
            WebActions.Answer turned = act(server, "ADMIN", "shop_enable", "\"shop\":\"" + id + "\",\"on\":true,\"edited\":" + stamp(server, id));
            h.assertTrue(turned.ok(), "turned on: " + turned.message());
            h.assertTrue(act(server, "OWNER", "shop_delete", "\"shop\":\"" + id + "\",\"edited\":" + stamp(server, id)).ok(), "deleted");
        });
    }

    /** BSP-Core 1.27.0's changed answers reach the panel as sentences (D849 to D852). */
    @GameTest(template = SetupGameTests.EMPTY)
    public void bsp_core_1_27_refusals_are_said_in_words(GameTestHelper h) {
        with(h, server -> {
            WebActions.Answer all = act(server, "OWNER", "shop_create", "\"shop\":\"all\",\"name\":\"All\",\"kind\":\"shop\"");
            h.assertTrue(!all.ok() && all.message().contains("cannot be all"), "all is no shop id (D850): " + all.message());
            String id = fresh();
            h.assertTrue(act(server, "OWNER", "shop_create", "\"shop\":\"" + id + "\",\"name\":\"Words\",\"kind\":\"shop\"").ok(), "made");
            WebActions.Answer blank = act(server, "ADMIN", "shop_set", "\"shop\":\"" + id + "\",\"field\":\"name\",\"value\":\"\\u200b\\u202e\\u200b\",\"edited\":" + stamp(server, id));
            h.assertTrue(!blank.ok() && blank.message().contains("a letter or digit you can see"), "an invisible-only name (D851): " + blank.message());
            WebActions.Answer under = act(server, "ADMIN", "shop_item_remove", "\"shop\":\"" + id + "\",\"index\":-1,\"edited\":" + stamp(server, id));
            h.assertTrue(!under.ok() && under.message().contains("no such card"), "a negative place is no card: " + under.message());
            String deep = "{a:".repeat(70) + "1" + "}".repeat(70);
            WebActions.Answer nested = act(server, "ADMIN", "shop_item_add", "\"shop\":\"" + id + "\",\"item\":\"minecraft:stone\",\"nbt\":\"" + deep + "\",\"count\":1,\"place\":0,\"edited\":" + stamp(server, id));
            h.assertTrue(!nested.ok() && nested.message().contains("nested too deep"), "item data 70 deep (D852): " + nested.message());
            h.assertTrue(act(server, "OWNER", "shop_delete", "\"shop\":\"" + id + "\",\"edited\":" + stamp(server, id)).ok(), "deleted");
        });
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void sections_cards_notes_and_the_coin_cap_go_through_bsp_core(GameTestHelper h) {
        with(h, server -> {
            String id = fresh(), sec = id + "_s";
            h.assertTrue(act(server, "OWNER", "shop_create", "\"shop\":\"" + id + "\",\"name\":\"Cards\",\"kind\":\"shop\"").ok(), "made");
            WebActions.Answer s = act(server, "OWNER", "shop_create", "\"shop\":\"" + sec + "\",\"name\":\"Potions\",\"parent\":\"" + id
                    + "\",\"picture\":\"minecraft:potion\",\"v\":\"minecraft:swiftness\",\"edited\":" + stamp(server, id));
            h.assertTrue(s.ok() && data(s).get("parent").getAsString().equals(id), "a section, its card on the parent's page: " + s.message() + " " + s.data());
            JsonArray cards = data(act(server, "MODERATOR", "shop_read", "\"shop\":\"" + id + "\"")).getAsJsonObject("shop").getAsJsonArray("cards");
            JsonObject sc = cards.get(0).getAsJsonObject();
            h.assertTrue(sc.get("section").getAsString().equals(sec) && sc.get("v").getAsString().equals("minecraft:swiftness"), "the potion's v went in as SNBT: " + sc);
            WebActions.Answer add = act(server, "ADMIN", "shop_item_add", "\"shop\":\"" + id + "\",\"item\":\"minecraft:iron_ingot\",\"count\":1,\"place\":0,\"edited\":" + stamp(server, id));
            h.assertTrue(add.ok() && data(add).get("index").getAsInt() == 0, "added at place 0: " + add.message() + " " + add.data());
            WebActions.Answer note = act(server, "ADMIN", "shop_item_set", "\"shop\":\"" + id + "\",\"index\":0,\"field\":\"note\",\"value\":[\"Smelted here\",\"Fresh\"],\"edited\":" + stamp(server, id));
            h.assertTrue(note.ok(), "a note of two lines: " + note.message());
            JsonObject c = data(act(server, "MODERATOR", "shop_read", "\"shop\":\"" + id + "\"")).getAsJsonObject("shop").getAsJsonArray("cards").get(0).getAsJsonObject();
            h.assertTrue(c.getAsJsonArray("note").size() == 2 && c.getAsJsonArray("note").get(1).getAsString().equals("Fresh"), "joined by a new line, read back as lines: " + c);
            WebActions.Answer gold = act(server, "ADMIN", "shop_item_set", "\"shop\":\"" + id + "\",\"index\":0,\"field\":\"currency\",\"value\":1,\"edited\":" + stamp(server, id));
            h.assertTrue(gold.ok(), "paid in gold: " + gold.message());
            WebActions.Answer big = act(server, "ADMIN", "shop_item_set", "\"shop\":\"" + id + "\",\"index\":0,\"field\":\"buy\",\"value\":10001,\"edited\":" + stamp(server, id));
            h.assertTrue(!big.ok() && big.message().contains("10,000 coins"), "the coin cap as a sentence (D819): " + big.message());
            WebActions.Answer cardNeeds = act(server, "ADMIN", "shop_item_set", "\"shop\":\"" + id + "\",\"index\":0,\"field\":\"requires\",\"value\":{\"permission\":\"\",\"playtimeHours\":5,\"hide\":false,\"reason\":\"\"},\"edited\":" + stamp(server, id));
            h.assertTrue(cardNeeds.ok(), "a card's needs through setItemRequires: " + cardNeeds.message());
            WebActions.Answer mv = act(server, "ADMIN", "shop_item_move", "\"shop\":\"" + id + "\",\"from\":0,\"to\":1,\"edited\":" + stamp(server, id));
            h.assertTrue(mv.ok() && data(mv).get("index").getAsInt() == 1, "moved: " + mv.message());
            WebActions.Answer refill = act(server, "ADMIN", "shop_item_refill", "\"shop\":\"" + id + "\",\"index\":1,\"edited\":" + stamp(server, id));
            h.assertTrue(refill.ok(), "refilled: " + refill.message());
            WebActions.Answer rm = act(server, "ADMIN", "shop_item_remove", "\"shop\":\"" + id + "\",\"index\":1,\"edited\":" + stamp(server, id));
            h.assertTrue(rm.ok(), "removed: " + rm.message());
            WebActions.Answer copySec = act(server, "OWNER", "shop_copy", "\"shop\":\"" + sec + "\",\"newId\":\"" + id + "c\",\"name\":\"Copy\"");
            h.assertTrue(!copySec.ok() && copySec.message().contains("top shop"), "a section is not copied (D823): " + copySec.message());
            WebActions.Answer copy = act(server, "OWNER", "shop_copy", "\"shop\":\"" + id + "\",\"newId\":\"" + id + "c\",\"name\":\"Copy\"");
            h.assertTrue(copy.ok() && data(copy).getAsJsonObject("ids").get(sec).getAsString().equals(id + "c_1") && data(copy).get("shop").getAsString().equals(id + "c"),
                    "copied with its section under the ids the panel showed: " + copy.data());
            WebActions.Answer export = act(server, "MODERATOR", "shop_export", "\"shop\":\"" + id + "\"");
            h.assertTrue(export.ok() && data(export).getAsJsonObject("json").get("format").getAsInt() == 1 && data(export).get("shops").getAsInt() == 2, "exported: " + export.message());
            WebActions.Answer report = act(server, "MODERATOR", "shop_report", "\"shop\":\"" + id + "\",\"days\":7");
            WebActions.Answer sales = report.later() != null ? report.later().getNow(null) : null;
            if (sales != null) { // at once on a server alone; on a network BSP-Core answers a moment later from the database
                h.assertTrue(sales.ok() && data(sales).has("coinsIn") && data(sales).has("sellers") && data(sales).get("days").getAsInt() <= 7, "the report: " + sales.message());
            } else {
                h.assertTrue(report.ok() && report.later() != null, "the report is on its way");
            }
            WebActions.Answer del = act(server, "OWNER", "shop_delete", "\"shop\":\"" + id + "\",\"edited\":" + stamp(server, id));
            h.assertTrue(del.ok() && data(del).getAsJsonArray("deleted").size() == 2, "deleted with its section: " + del.data());
            h.assertTrue(act(server, "OWNER", "shop_delete", "\"shop\":\"" + id + "c\",\"edited\":" + stamp(server, id + "c")).ok(), "the copy deleted");
            WebActions.Answer venues = act(server, "MODERATOR", "shop_venues", "");
            h.assertTrue(venues.ok() && data(venues).has("venues"), "this server's stalls: " + venues.message());
        });
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void without_bsp_core_shops_every_shop_action_says_so(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        WebShops.availableForTests(false);
        try {
            for (String action : List.of("shop_list", "shop_read", "shop_create", "shop_set", "shop_item_add", "shop_report")) {
                WebActions.Answer a = act(server, "OWNER", action, "\"shop\":\"none\",\"edited\":1");
                h.assertTrue(a != null && !a.ok() && a.message().contains("BSP-Core 1.26.0"), action + " says what it needs: " + (a == null ? "none" : a.message()));
            }
            JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
            h.assertTrue(!snap.get("shops").getAsBoolean() && !snap.has("shopsAt"), "the snapshot says no shops here (D818)");
        } finally {
            WebShops.availableForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }
}
