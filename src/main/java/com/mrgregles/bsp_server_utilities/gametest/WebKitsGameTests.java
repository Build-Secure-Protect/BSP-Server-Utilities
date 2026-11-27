package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebKits;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The staff panel's kits on the game server (SU-171; D838 to D867), through the real BSP-Core jar (1.27.2, {@code BspKits}
 * VERSION 3) loaded in the dev run: every action as the panel signs it, BSP-Core's answers in the panel's shape, refusals as
 * sentences (D860), the stamp check, a player's records with the next time worked out, the snapshot's {@code kits},
 * {@code kitsOn} and {@code kitsAt}; and on an older BSP-Core, what each version answers (D859). The test world persists, so
 * every kit made here has an id of its own and is deleted afterwards, also when a check fails.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebKitsGameTests {
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 7 + 1);
        }
    }

    private static WebActions.Answer act(MinecraftServer server, String rank, String action, String fields) {
        String json = "{\"v\":1,\"id\":\"kit-" + action + "-" + System.nanoTime() + "\",\"at\":" + System.currentTimeMillis() + ",\"by\":\"Ana_Admin\",\"rank\":\"" + rank
                + "\",\"server\":\"" + Storage.currentServerId() + "\",\"action\":\"" + action + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}";
        return WebActions.handle(server, WebActions.sign(KEY, json));
    }

    private static JsonObject data(WebActions.Answer a) {
        return a != null && a.data() != null && a.data().isJsonObject() ? a.data().getAsJsonObject() : new JsonObject();
    }

    private static JsonObject read(MinecraftServer server, String kit) {
        WebActions.Answer r = act(server, "MODERATOR", "kit_read", "\"kit\":\"" + kit + "\"");
        return r.ok() ? data(r).getAsJsonObject("kit") : null;
    }

    private static long stamp(MinecraftServer server, String kit) {
        JsonObject k = read(server, kit);
        return k == null ? -1 : k.get("edited").getAsLong();
    }

    /** Every kit made by a test, deleted afterwards whatever happened. */
    private static final List<String> MADE = new java.util.ArrayList<>();

    private static String fresh() {
        String id = "gk" + Long.toString(System.nanoTime(), 36).toLowerCase();
        id = id.length() > 20 ? id.substring(0, 20) : id;
        MADE.add(id);
        MADE.add(id + "c");
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
            if (WebKits.version() < 3) {
                h.fail("BSP-Core 1.27.2 with BspKits VERSION 3 is not loaded in this run (VERSION " + WebKits.version() + ")");
            }
            body.run(server);
        } finally {
            for (String id : List.copyOf(MADE)) { // left over by a failed check: never kept in the persisted world
                long ed = stamp(server, id);
                if (ed >= 0) {
                    act(server, "OWNER", "kit_delete", "\"kit\":\"" + id + "\",\"edited\":" + ed);
                }
            }
            MADE.clear();
            WebKits.versionForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void kits_are_made_off_listed_read_and_the_snapshot_moves(GameTestHelper h) {
        with(h, server -> {
            String id = fresh();
            long before = WebKits.kitsAt();
            WebActions.Answer made = act(server, "OWNER", "kit_create", "\"kit\":\"" + id + "\",\"name\":\"Test Kit\"");
            h.assertTrue(made.ok() && made.message().equals("Made, off and empty.") && data(made).get("edited").getAsLong() > 0, "made: " + made.message() + " " + made.data());
            h.assertTrue(WebKits.kitsAt() > before, "BSP-Core's KitEvent.Changed moved kitsAt (D845)");
            JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
            h.assertTrue(snap.get("kits").getAsBoolean() && snap.has("kitsOn") && snap.get("kitsAt").getAsLong() == WebKits.kitsAt(), "the snapshot says kits (D863): " + snap.get("kits"));
            boolean really = com.mrgregles.bsp_core.api.BspKits.choices(server).kitsOn();
            h.assertTrue(snap.get("kitsOn").getAsBoolean() == really, "kitsOn is BSP-Core's own (audit: it was never read): " + snap.get("kitsOn") + " vs " + really);
            WebActions.Answer list = act(server, "MODERATOR", "kit_list", "");
            h.assertTrue(list.ok() && data(list).get("version").getAsInt() >= 3 && data(list).get("kitsOn").getAsBoolean() == really, "listed for every rank: " + list.message());
            JsonObject mine = null;
            for (JsonElement e : data(list).getAsJsonArray("kits")) {
                if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
                    mine = e.getAsJsonObject();
                }
            }
            h.assertTrue(mine != null && !mine.get("enabled").getAsBoolean() && mine.getAsJsonArray("items").size() == 0, "listed, off and empty (K5): " + mine);
            h.assertTrue(mine.has("owed") && mine.has("missing") && mine.has("grantServers") && mine.getAsJsonObject("icon").has("item"), "the panel's shape: " + mine);
            JsonObject k = read(server, id);
            h.assertTrue(k != null && k.get("name").getAsString().equals("Test Kit"), "read: " + k);
            WebActions.Answer missing = act(server, "MODERATOR", "kit_read", "\"kit\":\"" + id + "x\"");
            h.assertTrue(!missing.ok() && missing.message().contains("no such kit"), "a missing kit is said: " + missing.message());
            WebActions.Answer choices = act(server, "MODERATOR", "kit_choices", "");
            JsonObject c = data(choices).getAsJsonObject("choices");
            h.assertTrue(choices.ok() && c.get("maxItems").getAsInt() >= 1 && c.has("kitsOn") && c.getAsJsonArray("grants").size() == 3 && c.getAsJsonArray("conditions").size() >= 1,
                    "the pickers: " + c);
            WebActions.Answer del = act(server, "OWNER", "kit_delete", "\"kit\":\"" + id + "\",\"edited\":" + stamp(server, id));
            h.assertTrue(del.ok() && del.message().startsWith("Deleted") && read(server, id) == null, "deleted: " + del.message());
        });
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void a_moderator_changes_nothing_every_field_saves_and_a_stale_stamp_is_refused(GameTestHelper h) {
        with(h, server -> {
            String id = fresh();
            WebActions.Answer mod = act(server, "MODERATOR", "kit_create", "\"kit\":\"" + id + "\",\"name\":\"Nope\"");
            h.assertTrue(!mod.ok() && mod.message().startsWith("Refused: only Admins and Owners"), "a Moderator (D839): " + mod.message());
            h.assertTrue(act(server, "OWNER", "kit_create", "\"kit\":\"" + id + "\",\"name\":\"Stamp\"").ok(), "made");
            long ed = stamp(server, id);
            WebActions.Answer saved = act(server, "ADMIN", "kit_set", "\"kit\":\"" + id + "\",\"field\":\"name\",\"value\":\"Stamp Two\",\"edited\":" + ed);
            h.assertTrue(saved.ok() && data(saved).get("edited").getAsLong() > ed, "saved with the new stamp: " + saved.message());
            WebActions.Answer stale = act(server, "ADMIN", "kit_set", "\"kit\":\"" + id + "\",\"field\":\"name\",\"value\":\"Stamp Three\",\"edited\":" + ed);
            h.assertTrue(!stale.ok() && data(stale).get("stale").getAsBoolean() && stale.message().contains("changed in game"), "the old stamp is refused: " + stale.message());
            String[][] fields = {{"description", "\"Tools for a deep dig\""}, {"inBrowser", "false"}, {"once", "true"}, {"cooldownSeconds", "604800"}, {"price", "250"},
                    {"ranks", "[\"Miner\",\"Builder\"]"}, {"servers", "[\"alpha\"]"}, {"conditions", "[\"no_totem\"]"}, {"grant", "\"SERVERS\""}, {"grantServers", "[\"alpha\",\"beta\"]"}};
            for (String[] f : fields) {
                WebActions.Answer s = act(server, "ADMIN", "kit_set", "\"kit\":\"" + id + "\",\"field\":\"" + f[0] + "\",\"value\":" + f[1] + ",\"edited\":" + stamp(server, id));
                h.assertTrue(s.ok(), f[0] + " saved: " + s.message());
            }
            JsonObject k = read(server, id);
            h.assertTrue(k.get("description").getAsString().equals("Tools for a deep dig") && !k.get("inBrowser").getAsBoolean() && k.get("once").getAsBoolean()
                    && k.get("cooldownSeconds").getAsLong() == 604800 && k.get("price").getAsLong() == 250, "read back: " + k);
            h.assertTrue(k.getAsJsonArray("ranks").toString().equals("[\"miner\",\"builder\"]") && k.getAsJsonArray("conditions").size() == 1
                    && k.get("grant").getAsString().equals("SERVERS") && k.getAsJsonArray("grantServers").size() == 2, "lists as lists, ranks lower-case: " + k);
            WebActions.Answer comma = act(server, "ADMIN", "kit_set", "\"kit\":\"" + id + "\",\"field\":\"ranks\",\"value\":[\"a,b\"],\"edited\":" + stamp(server, id));
            h.assertTrue(!comma.ok() && comma.message().startsWith("That value does not fit"), "a comma inside a rank is refused, never merged (audit): " + comma.message());
            WebActions.Answer listName = act(server, "ADMIN", "kit_set", "\"kit\":\"" + id + "\",\"field\":\"name\",\"value\":[\"A\"],\"edited\":" + stamp(server, id));
            h.assertTrue(!listName.ok(), "a list is no name (audit)");
            WebActions.Answer field = act(server, "ADMIN", "kit_set", "\"kit\":\"" + id + "\",\"field\":\"taken\",\"value\":5,\"edited\":" + stamp(server, id));
            h.assertTrue(!field.ok() && field.message().equals("That cannot be changed on a kit."), "bad_field (D860): " + field.message());
            WebActions.Answer value = act(server, "ADMIN", "kit_set", "\"kit\":\"" + id + "\",\"field\":\"name\",\"value\":\"" + "x".repeat(30) + "\",\"edited\":" + stamp(server, id));
            h.assertTrue(!value.ok() && value.message().startsWith("That value does not fit"), "bad_value (D860): " + value.message());
            WebActions.Answer text = act(server, "ADMIN", "kit_enable", "\"kit\":\"" + id + "\",\"on\":\"true\",\"edited\":" + stamp(server, id));
            h.assertTrue(!text.ok() && text.message().contains("On or off"), "a text true is not on: " + text.message());
            WebActions.Answer on = act(server, "ADMIN", "kit_enable", "\"kit\":\"" + id + "\",\"on\":true,\"edited\":" + stamp(server, id));
            h.assertTrue(on.ok() && read(server, id).get("enabled").getAsBoolean(), "turned on: " + on.message());
            WebActions.Answer noStamp = act(server, "ADMIN", "kit_enable", "\"kit\":\"" + id + "\",\"on\":false");
            h.assertTrue(!noStamp.ok() && noStamp.message().contains("Read the kit again"), "no stamp, no change: " + noStamp.message());
            h.assertTrue(act(server, "OWNER", "kit_delete", "\"kit\":\"" + id + "\",\"edited\":" + stamp(server, id)).ok(), "deleted");
        });
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void items_icon_copy_and_the_refusals_in_words(GameTestHelper h) {
        with(h, server -> {
            String id = fresh();
            h.assertTrue(act(server, "OWNER", "kit_create", "\"kit\":\"" + id + "\",\"name\":\"Items\"").ok(), "made");
            WebActions.Answer items = act(server, "ADMIN", "kit_items", "\"kit\":\"" + id + "\",\"items\":[{\"item\":\"minecraft:iron_ingot\",\"count\":3,\"nbt\":\"\"},"
                    + "{\"item\":\"minecraft:diamond_sword\",\"count\":1,\"nbt\":\"{display:{Name:'{\\\"text\\\":\\\"Edge\\\"}'}}\"}],\"edited\":" + stamp(server, id));
            h.assertTrue(items.ok(), "items saved: " + items.message());
            JsonArray got = read(server, id).getAsJsonArray("items");
            h.assertTrue(got.size() == 2 && got.get(0).getAsJsonObject().get("item").getAsString().equals("minecraft:iron_ingot") && got.get(0).getAsJsonObject().get("count").getAsInt() == 3
                    && got.get(1).getAsJsonObject().get("nbt").getAsString().contains("Edge"), "read back in slot order, data kept: " + got);
            WebActions.Answer icon = act(server, "ADMIN", "kit_icon", "\"kit\":\"" + id + "\",\"icon\":{\"item\":\"minecraft:diamond\",\"nbt\":\"\"},\"edited\":" + stamp(server, id));
            h.assertTrue(icon.ok() && read(server, id).getAsJsonObject("icon").get("item").getAsString().equals("minecraft:diamond"), "icon set: " + icon.message());
            WebActions.Answer noIcon = act(server, "ADMIN", "kit_icon", "\"kit\":\"" + id + "\",\"edited\":" + stamp(server, id));
            h.assertTrue(!noIcon.ok() && read(server, id).getAsJsonObject("icon").get("item").getAsString().equals("minecraft:diamond"), "no icon field clears nothing (audit): " + noIcon.message());
            WebActions.Answer objNbt = act(server, "ADMIN", "kit_items", "\"kit\":\"" + id + "\",\"items\":[{\"item\":\"minecraft:diamond_sword\",\"count\":1,\"nbt\":{\"Damage\":3}}],\"edited\":" + stamp(server, id));
            h.assertTrue(!objNbt.ok() && read(server, id).getAsJsonArray("items").size() == 2, "data that is not text is refused, never dropped (audit): " + objNbt.message());
            WebActions.Answer clear = act(server, "ADMIN", "kit_icon", "\"kit\":\"" + id + "\",\"icon\":null,\"edited\":" + stamp(server, id));
            h.assertTrue(clear.ok() && read(server, id).getAsJsonObject("icon").get("item").getAsString().isEmpty(), "icon cleared: the first item shows");
            WebActions.Answer unknown = act(server, "ADMIN", "kit_items", "\"kit\":\"" + id + "\",\"one\":true,\"items\":[{\"item\":\"minecraft:no_such_item_here\",\"count\":1,\"nbt\":\"\"}],\"edited\":" + stamp(server, id));
            h.assertTrue(!unknown.ok() && unknown.message().startsWith("An item was refused") && unknown.message().endsWith("(the one you just changed)"), "bad_item (D860): " + unknown.message());
            WebActions.Answer shape = act(server, "ADMIN", "kit_items", "\"kit\":\"" + id + "\",\"items\":[{\"item\":\"minecraft:dirt\",\"count\":0}],\"edited\":" + stamp(server, id));
            h.assertTrue(!shape.ok() && shape.message().startsWith("An item was refused"), "a count of 0 never reaches BSP-Core: " + shape.message());
            int max = data(act(server, "MODERATOR", "kit_choices", "")).getAsJsonObject("choices").get("maxItems").getAsInt();
            StringBuilder many = new StringBuilder();
            for (int i = 0; i <= max; i++) {
                many.append(i == 0 ? "" : ",").append("{\"item\":\"minecraft:dirt\",\"count\":1,\"nbt\":\"\"}");
            }
            WebActions.Answer tooMany = act(server, "ADMIN", "kit_items", "\"kit\":\"" + id + "\",\"items\":[" + many + "],\"edited\":" + stamp(server, id));
            h.assertTrue(!tooMany.ok() && tooMany.message().equals("A kit holds at most " + max + " stacks."), "too_many_items (D860): " + tooMany.message());
            WebActions.Answer badId = act(server, "OWNER", "kit_create", "\"kit\":\"A\",\"name\":\"Bad\"");
            h.assertTrue(!badId.ok() && badId.message().startsWith("An id is 2 to 24"), "bad_id (D860): " + badId.message());
            WebActions.Answer taken = act(server, "OWNER", "kit_create", "\"kit\":\"" + id + "\",\"name\":\"Again\"");
            h.assertTrue(!taken.ok() && taken.message().contains("already a kit"), "taken (D860): " + taken.message());
            WebActions.Answer copy = act(server, "OWNER", "kit_copy", "\"kit\":\"" + id + "\",\"newId\":\"" + id + "c\",\"name\":\"Items Copy\"");
            h.assertTrue(copy.ok() && copy.message().equals("Copied, off.") && data(copy).get("kit").getAsString().equals(id + "c"), "copied: " + copy.message() + " " + copy.data());
            JsonObject c = read(server, id + "c");
            h.assertTrue(c != null && !c.get("enabled").getAsBoolean() && c.getAsJsonArray("items").size() == 2 && c.get("taken").getAsInt() == 0, "the copy, off, never taken: " + c);
            h.assertTrue(act(server, "OWNER", "kit_delete", "\"kit\":\"" + id + "c\",\"edited\":" + stamp(server, id + "c")).ok(), "the copy deleted");
            h.assertTrue(act(server, "OWNER", "kit_delete", "\"kit\":\"" + id + "\",\"edited\":" + stamp(server, id)).ok(), "deleted");
        });
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void a_gift_is_owed_recorded_and_reset(GameTestHelper h) {
        with(h, server -> {
            String id = fresh();
            UUID player = UUID.randomUUID(); // nobody online: the gift waits (D864)
            h.assertTrue(act(server, "OWNER", "kit_create", "\"kit\":\"" + id + "\",\"name\":\"Gift\"").ok(), "made");
            h.assertTrue(act(server, "ADMIN", "kit_items", "\"kit\":\"" + id + "\",\"items\":[{\"item\":\"minecraft:bread\",\"count\":4,\"nbt\":\"\"}],\"edited\":" + stamp(server, id)).ok(), "items");
            WebActions.Answer modGive = act(server, "MODERATOR", "kit_give", "\"kit\":\"" + id + "\",\"player\":\"" + player + "\"");
            h.assertTrue(!modGive.ok(), "a Moderator gives nothing");
            WebActions.Answer badPlayer = act(server, "ADMIN", "kit_give", "\"kit\":\"" + id + "\",\"player\":\"nobody\"");
            h.assertTrue(!badPlayer.ok() && badPlayer.message().equals("There is no such player."), "not a player: " + badPlayer.message());
            WebActions.Answer give = act(server, "ADMIN", "kit_give", "\"kit\":\"" + id + "\",\"player\":\"" + player + "\",\"name\":\"Offline_Olly\"");
            h.assertTrue(give.ok() && data(give).get("owed").getAsInt() >= 1 && give.message().contains("next time they join"), "given, waiting (even though the kit is off): " + give.message() + " " + give.data());
            h.assertTrue(read(server, id).get("owed").getAsInt() >= 1, "the kit counts it waiting (D865)");
            WebActions.Answer rec = act(server, "MODERATOR", "kit_records", "\"player\":\"" + player + "\"");
            WebActions.Answer rows = rec.later() != null ? rec.later().getNow(null) : null;
            h.assertTrue(rec.ok() && rec.later() != null, "the records are on their way: " + rec.message());
            if (rows != null) { // at once on a server alone; on a network a moment later from the database
                JsonObject row = null;
                for (JsonElement e : data(rows).getAsJsonArray("rows")) {
                    if (e.getAsJsonObject().get("kit").getAsString().equals(id)) {
                        row = e.getAsJsonObject();
                    }
                }
                h.assertTrue(row != null && row.get("owed").getAsInt() >= 1 && row.get("here").getAsBoolean() && row.has("again") && data(rows).has("fresh"), "the record: " + rows.data());
            }
            WebActions.Answer both = act(server, "ADMIN", "kit_reset", "\"kit\":\"" + id + "\",\"all\":true,\"player\":\"" + player + "\"");
            h.assertTrue(!both.ok() && both.message().contains("not both"), "a kit and every kit: refused (audit): " + both.message());
            WebActions.Answer vague = act(server, "ADMIN", "kit_reset", "\"player\":\"" + player + "\"");
            h.assertTrue(!vague.ok() && vague.message().startsWith("Name a kit"), "every kit only when asked for: " + vague.message());
            WebActions.Answer reset = act(server, "ADMIN", "kit_reset", "\"kit\":\"" + id + "\",\"player\":\"" + player + "\",\"name\":\"Offline_Olly\"");
            h.assertTrue(reset.ok() && reset.message().startsWith("Reset:") && read(server, id).get("owed").getAsInt() == 0, "reset, owed no longer: " + reset.message());
            h.assertTrue(act(server, "ADMIN", "kit_give", "\"kit\":\"" + id + "\",\"player\":\"" + player + "\"").ok(), "given again");
            WebActions.Answer all = act(server, "ADMIN", "kit_reset", "\"all\":true,\"player\":\"" + player + "\"");
            h.assertTrue(all.ok() && read(server, id).get("owed").getAsInt() == 0, "every kit reset: " + all.message());
            WebActions.Answer del = act(server, "OWNER", "kit_delete", "\"kit\":\"" + id + "\",\"edited\":" + stamp(server, id));
            h.assertTrue(del.ok(), "deleted: " + del.message());
        });
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void the_next_time_is_worked_out_and_an_older_bsp_core_is_said(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        try {
            long now = 1_000_000_000L;
            h.assertTrue(WebKits.again(true, 0, 1, now - 5, now) == -1, "once and taken: never again");
            h.assertTrue(WebKits.again(true, 0, 0, 0, now) == 0, "once, not taken: any time");
            h.assertTrue(WebKits.again(false, 60, 2, now - 10_000, now) == now + 50_000, "a minute's wait from the last time");
            h.assertTrue(WebKits.again(false, 60, 2, now - 120_000, now) == 0, "the wait is over");
            h.assertTrue(WebKits.again(false, 0, 9, now, now) == 0, "no wait: any time");
            WebKits.versionForTests(1);
            for (String action : List.of("kit_list", "kit_read", "kit_create", "kit_give", "kit_records")) {
                WebActions.Answer a = act(server, "OWNER", action, "\"kit\":\"x1\"");
                h.assertTrue(a != null && !a.ok() && a.message().equals("Kits need BSP-Core 1.27.1 or newer on this server."), action + " on VERSION 1 (D859): " + (a == null ? null : a.message()));
            }
            JsonObject old = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
            h.assertTrue(!old.get("kits").getAsBoolean() && !old.has("kitsAt"), "VERSION 1: the panel does not ask this server");
            WebKits.versionForTests(2);
            JsonObject two = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
            h.assertTrue(two.get("kits").getAsBoolean() && two.get("kitsOn").getAsBoolean(), "VERSION 2: ready and on (D859)");
            WebActions.Answer c = act(server, "MODERATOR", "kit_choices", "");
            h.assertTrue(c.ok() && data(c).getAsJsonObject("choices").has("kitsOn"), "VERSION 2 choices carry kitsOn (filled in as on when BSP-Core has none)");
            WebActions.Answer other = act(server, "OWNER", "kit_sparkle", "");
            h.assertTrue(other != null && !other.ok() && other.message().contains("kit action"), "an unknown kit action: " + (other == null ? null : other.message()));
        } finally {
            WebKits.versionForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }
}
