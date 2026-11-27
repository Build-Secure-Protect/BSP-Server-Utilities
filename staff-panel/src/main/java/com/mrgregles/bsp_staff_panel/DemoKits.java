package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Made-up kits for {@code --demo} (SU-172), answering the {@code kit_*} actions as a game server with BSP-Server
 * Utilities 0.28 and BSP-Core 1.27.2 would ({@code docs/API.md} "Kits"): free and paid kits, once-only and waiting ones,
 * ranks from a ladder, a first-join kit, one hidden from the browser, one off holding items from a mod the server lacks,
 * and players' records with kits owed. Kits are switched off on Stormfall (D863). Item ids are ones the demo's item
 * pictures hold. Nothing reaches a game server.
 */
final class DemoKits {
    private static Map<String, JsonObject> kits;
    private static final Map<String, List<JsonObject>> RECORDS = new LinkedHashMap<>();
    static final String OFF_SERVER = "Stormfall";
    private static volatile long changedAt;

    private DemoKits() {
    }

    /** Tests: the demo's kits and records as at the start. */
    static synchronized void reset() {
        kits = null;
        RECORDS.clear();
        changedAt = 0;
    }

    /** The snapshot's {@code kitsAt} (D845): when a demo kit last changed, 0 when none has. */
    static long changedAt() {
        return changedAt;
    }

    /** The answer to a kit action: {ok, message, data}; null when {@code what} is not one. */
    static synchronized JsonObject answer(String what, JsonObject act, String server) {
        if (!what.startsWith("kit_")) {
            return null;
        }
        if (kits == null) {
            kits = make();
        }
        String id = str(act, "kit");
        return switch (what) {
            case "kit_list" -> {
                JsonObject d = new JsonObject();
                JsonArray a = new JsonArray();
                kits.values().forEach(k -> a.add(k.deepCopy()));
                d.add("kits", a);
                d.addProperty("version", 3);
                d.addProperty("kitsOn", !OFF_SERVER.equals(server));
                yield done(true, "kits", d);
            }
            case "kit_read" -> {
                JsonObject k = kits.get(id);
                if (k == null) {
                    yield done(false, "There is no such kit now: it may have been deleted.", null);
                }
                JsonObject d = new JsonObject();
                d.add("kit", k.deepCopy());
                d.addProperty("version", 3);
                yield done(true, "kit", d);
            }
            case "kit_choices" -> done(true, "choices", choices(server));
            case "kit_records" -> records(str(act, "player"));
            case "kit_create" -> changed(create(id, str(act, "name")));
            case "kit_copy" -> changed(copy(id, str(act, "newId"), str(act, "name")));
            case "kit_set" -> changed(set(act, id));
            case "kit_items" -> changed(items(act, id));
            case "kit_icon" -> changed(icon(act, id));
            case "kit_enable" -> changed(enable(act, id));
            case "kit_delete" -> changed(delete(act, id));
            case "kit_give" -> give(id, str(act, "player"));
            case "kit_reset" -> reset(act, id, str(act, "player"));
            default -> done(false, "This server does not know that kit action; is it on the same version as the panel?", null);
        };
    }

    private static JsonObject changed(JsonObject answer) {
        if (answer.get("ok").getAsBoolean()) {
            changedAt = Math.max(changedAt + 1, System.currentTimeMillis());
        }
        return answer;
    }

    // ------------------------------------------------------------------ the pickers

    private static JsonObject choices(String server) {
        JsonObject c = new JsonObject();
        c.add("ranks", array("settler", "miner", "builder", "veteran", "legend"));
        c.addProperty("ranksFromLadder", true);
        c.add("servers", array("BSP-Spawn", "Frostwarf", "Moongrove", "Boulderbrook", "Stormfall"));
        c.addProperty("thisServer", server.isEmpty() ? "BSP-Spawn" : server);
        JsonArray conds = new JsonArray();
        JsonObject nt = new JsonObject();
        nt.addProperty("key", "no_totem");
        nt.addProperty("label", "Owns no Shatter Totem anywhere on the network");
        conds.add(nt);
        c.add("conditions", conds);
        c.add("grants", array("NONE", "NETWORK", "SERVERS"));
        c.addProperty("maxItems", 36);
        c.addProperty("kitsOn", !OFF_SERVER.equals(server));
        JsonObject d = new JsonObject();
        d.add("choices", c);
        d.addProperty("version", 3);
        return d;
    }

    // ------------------------------------------------------------------ changes, as BSP-Core would make them

    private static JsonObject refused(String key, JsonObject k) {
        JsonObject d = new JsonObject();
        d.addProperty("kit", k == null ? "" : k.get("id").getAsString());
        d.addProperty("edited", k == null ? 0 : k.get("edited").getAsLong());
        if (key.equals("stale")) {
            d.addProperty("stale", true);
        }
        String why = switch (key) {
            case "stale" -> "It was changed in game since you read it: here it is as it is now.";
            case "no_kit" -> "There is no such kit now: it may have been deleted.";
            case "bad_id" -> "An id is 2 to 24 lower-case letters, digits and _.";
            case "taken" -> "There is already a kit with that id: choose another.";
            case "bad_value" -> "That value does not fit: a name is 1 to 24 characters, a description up to 80, a price or wait a whole number up to a trillion, and ranks or servers up to 512 characters together.";
            case "missing_items" -> "This kit holds items from a mod this server does not have: change it on a server that has the mod (it can still be deleted here).";
            case "too_many_items" -> "A kit holds at most 36 stacks.";
            default -> "BSP-Core refused it (" + key + ").";
        };
        return done(false, why, d);
    }

    private static JsonObject saved(JsonObject k, String message, int owed) {
        k.addProperty("edited", Math.max(k.get("edited").getAsLong() + 1, System.currentTimeMillis()));
        JsonObject d = new JsonObject();
        d.addProperty("kit", k.get("id").getAsString());
        d.addProperty("edited", k.get("edited").getAsLong());
        d.addProperty("owed", owed);
        return done(true, message, d);
    }

    /** The kit if it exists, carries {@code edited} and holds no item this server lacks; otherwise the refusal. */
    private static JsonObject check(JsonObject act, String id, boolean anyItems) {
        JsonObject k = kits.get(id);
        if (k == null) {
            return refused("no_kit", null);
        }
        if (!act.has("edited") || act.get("edited").getAsLong() != k.get("edited").getAsLong()) {
            return refused("stale", k);
        }
        if (!anyItems && k.get("missing").getAsInt() > 0) {
            return refused("missing_items", k);
        }
        return null;
    }

    private static JsonObject create(String id, String name) {
        if (!id.matches("[a-z0-9_]{2,24}")) {
            return refused("bad_id", null);
        }
        if (kits.containsKey(id)) {
            return refused("taken", null);
        }
        if (name.isBlank() || name.length() > 24) {
            return refused("bad_value", null);
        }
        JsonObject k = kit(id, name, "", false, true, false, 0, 0, List.of(), List.of(), List.of(), "NONE", List.of(), 0, "", 0, 0, 0);
        kits.put(id, k);
        return saved(k, "Made, off and empty.", 0);
    }

    private static JsonObject copy(String id, String to, String name) {
        JsonObject from = kits.get(id);
        if (from == null) {
            return refused("no_kit", null);
        }
        if (!to.matches("[a-z0-9_]{2,24}")) {
            return refused("bad_id", null);
        }
        if (kits.containsKey(to)) {
            return refused("taken", null);
        }
        if (from.get("missing").getAsInt() > 0) {
            return refused("missing_items", from);
        }
        JsonObject k = from.deepCopy();
        k.addProperty("id", to);
        k.addProperty("name", name);
        k.addProperty("enabled", false);
        k.addProperty("created", System.currentTimeMillis());
        k.addProperty("taken", 0);
        k.addProperty("lastTakenBy", "");
        k.addProperty("lastTakenMs", 0);
        k.addProperty("owed", 0);
        kits.put(to, k);
        return saved(k, "Copied, off.", 0);
    }

    private static JsonObject set(JsonObject act, String id) {
        JsonObject why = check(act, id, false);
        if (why != null) {
            return why;
        }
        JsonObject k = kits.get(id);
        String field = str(act, "field");
        JsonElement v = act.get("value");
        switch (field) {
            case "name", "description", "grant" -> k.addProperty(field, v.getAsString());
            case "inBrowser", "once" -> k.addProperty(field, v.getAsBoolean());
            case "cooldownSeconds", "price" -> k.addProperty(field, v.getAsLong());
            case "ranks", "servers", "grantServers", "conditions" -> {
                JsonArray a = new JsonArray();
                (v.isJsonArray() ? v.getAsJsonArray() : new JsonArray()).forEach(e -> a.add(field.equals("ranks") ? e.getAsString().toLowerCase() : e.getAsString()));
                k.add(field, a);
            }
            default -> {
                return done(false, "That cannot be changed on a kit.", null);
            }
        }
        return saved(k, "Saved.", 0);
    }

    private static JsonObject items(JsonObject act, String id) {
        JsonObject why = check(act, id, false);
        if (why != null) {
            return why;
        }
        JsonArray rows = act.getAsJsonArray("items");
        if (rows.size() > 36) {
            return refused("too_many_items", kits.get(id));
        }
        JsonObject k = kits.get(id);
        JsonArray out = new JsonArray();
        rows.forEach(r -> out.add(r.deepCopy()));
        k.add("items", out);
        return saved(k, "Saved.", 0);
    }

    private static JsonObject icon(JsonObject act, String id) {
        JsonObject why = check(act, id, false);
        if (why != null) {
            return why;
        }
        JsonObject k = kits.get(id);
        JsonElement i = act.get("icon");
        k.add("icon", row(i != null && i.isJsonObject() ? str(i.getAsJsonObject(), "item") : "", i != null && i.isJsonObject() ? 1 : 0));
        return saved(k, "Saved.", 0);
    }

    private static JsonObject enable(JsonObject act, String id) {
        JsonObject why = check(act, id, false);
        if (why != null) {
            return why;
        }
        JsonObject k = kits.get(id);
        k.addProperty("enabled", act.get("on").getAsBoolean());
        return saved(k, "Saved.", 0);
    }

    private static JsonObject delete(JsonObject act, String id) {
        JsonObject why = check(act, id, true);
        if (why != null) {
            return why;
        }
        int owed = kits.get(id).get("owed").getAsInt();
        kits.remove(id);
        RECORDS.values().forEach(l -> l.removeIf(r -> r.get("kit").getAsString().equals(id)));
        JsonObject d = new JsonObject();
        d.addProperty("kit", id);
        d.addProperty("edited", 0);
        d.addProperty("owed", owed);
        return done(true, owed > 0 ? "Deleted. " + owed + (owed == 1 ? " player was" : " players were") + " still owed it." : "Deleted.", d);
    }

    // ------------------------------------------------------------------ a player's kits

    private static List<JsonObject> recordsOf(String player) {
        return RECORDS.computeIfAbsent(player, p -> new ArrayList<>());
    }

    private static JsonObject record(String kit, String name, String scope, int count, long lastMs, int owed) {
        JsonObject r = new JsonObject();
        r.addProperty("kit", kit);
        r.addProperty("kitName", name);
        r.addProperty("scope", scope);
        r.addProperty("count", count);
        r.addProperty("lastMs", lastMs);
        r.addProperty("owed", owed);
        return r;
    }

    private static JsonObject records(String player) {
        if (uuid(player) == null) {
            return done(false, "There is no such player.", null);
        }
        long now = System.currentTimeMillis();
        JsonArray rows = new JsonArray();
        for (JsonObject r : recordsOf(player)) {
            JsonObject o = r.deepCopy();
            JsonObject k = kits.get(r.get("kit").getAsString());
            o.addProperty("kitName", k == null ? "" : k.get("name").getAsString());
            o.addProperty("here", k != null);
            if (k != null && r.get("scope").getAsString().isEmpty()) {
                boolean once = k.get("once").getAsBoolean();
                long cd = k.get("cooldownSeconds").getAsLong(), last = r.get("lastMs").getAsLong();
                int count = r.get("count").getAsInt();
                long again = once && count > 0 ? -1 : cd <= 0 || count <= 0 || last <= 0 ? 0 : Math.max(0, last + cd * 1000 > now ? last + cd * 1000 : 0);
                o.addProperty("again", again);
            }
            rows.add(o);
        }
        JsonObject d = new JsonObject();
        d.addProperty("player", player);
        d.add("rows", rows);
        d.addProperty("fresh", true);
        return done(true, "records", d);
    }

    private static JsonObject give(String id, String player) {
        JsonObject k = kits.get(id);
        if (k == null) {
            return refused("no_kit", null);
        }
        if (uuid(player) == null) {
            return done(false, "There is no such player.", null);
        }
        JsonObject r = recordsOf(player).stream().filter(x -> x.get("kit").getAsString().equals(id) && x.get("scope").getAsString().isEmpty()).findFirst().orElse(null);
        if (r == null) {
            r = record(id, k.get("name").getAsString(), "", 0, 0, 0);
            recordsOf(player).add(r);
        }
        if (r.get("owed").getAsInt() == 0) {
            k.addProperty("owed", k.get("owed").getAsInt() + 1); // players waiting, as BSP-Core counts them
        }
        r.addProperty("owed", r.get("owed").getAsInt() + 1);
        changedAt = Math.max(changedAt + 1, System.currentTimeMillis());
        JsonObject d = new JsonObject();
        d.addProperty("kit", id);
        d.addProperty("edited", k.get("edited").getAsLong());
        d.addProperty("owed", r.get("owed").getAsInt());
        return done(true, "Given: it goes to them the next time they join a server with kits on, or from their kit browser.", d);
    }

    private static JsonObject reset(JsonObject act, String id, String player) {
        if (uuid(player) == null) {
            return done(false, "There is no such player.", null);
        }
        boolean all = act.has("all") && act.get("all").getAsBoolean();
        List<JsonObject> rs = recordsOf(player);
        List<JsonObject> gone = rs.stream().filter(r -> all || r.get("kit").getAsString().equals(id)).toList();
        if (!all && gone.isEmpty() && !kits.containsKey(id)) {
            return refused("no_kit", null);
        }
        for (JsonObject r : gone) {
            JsonObject k = kits.get(r.get("kit").getAsString());
            if (k != null) {
                k.addProperty("owed", Math.max(0, k.get("owed").getAsInt() - r.get("owed").getAsInt()));
            }
        }
        rs.removeAll(gone);
        changedAt = Math.max(changedAt + 1, System.currentTimeMillis());
        JsonObject d = new JsonObject();
        d.addProperty("kit", all ? "" : id);
        d.addProperty("edited", 0);
        d.addProperty("owed", gone.size());
        return done(true, gone.size() == 1 ? "Reset: 1 record forgotten." : "Reset: " + gone.size() + " records forgotten.", d);
    }

    // ------------------------------------------------------------------ the demo's kits

    private static Map<String, JsonObject> make() {
        long now = System.currentTimeMillis(), day = 86_400_000L;
        Map<String, JsonObject> m = new LinkedHashMap<>();
        m.put("starter", kit("starter", "Starter Kit", "Everything for a first night: tools, food, light and a bed.", true, true, true, 0, 0,
                List.of(), List.of(), List.of(), "NETWORK", List.of(), 1204, "Kestrel_88", now - 180_000, 0, 0,
                "minecraft:iron_pickaxe", 1, "minecraft:diamond_sword", 1, "minecraft:bread", 16, "minecraft:torch", 32, "minecraft:oak_planks", 32,
                "minecraft:compass", 1, "minecraft:clock", 1, "minecraft:bucket", 1));
        m.put("daily", kit("daily", "Daily Bread", "A day's food, every day.", true, true, false, day / 1000, 0,
                List.of(), List.of(), List.of(), "NONE", List.of(), 8911, "Mossy_Fen", now - 60_000, 0, 0,
                "minecraft:bread", 8, "minecraft:cooked_beef", 4, "minecraft:apple", 4));
        m.put("miner", kit("miner", "Miner's Pack", "Everything a deep dig needs: tools, light, food and a way out.", true, true, false, 7 * day / 1000, 250,
                List.of("miner", "builder", "veteran", "legend"), List.of(), List.of(), "NONE", List.of(), 342, "Vexa", now - 2 * 3_600_000, 3, 0,
                "minecraft:diamond_pickaxe", 1, "minecraft:iron_pickaxe", 1, "minecraft:torch", 64, "minecraft:torch", 64, "minecraft:coal", 32,
                "minecraft:ladder", 32, "minecraft:cooked_beef", 16, "minecraft:bucket", 1, "minecraft:iron_helmet", 1, "minecraft:shield", 1,
                "minecraft:ender_pearl", 4, "minecraft:redstone", 16));
        m.put("builder", kit("builder", "Builder Box", "A stack of each builder's basic.", true, true, false, 3 * day / 1000, 500,
                List.of("builder", "veteran", "legend"), List.of("BSP-Spawn", "Boulderbrook"), List.of(), "NONE", List.of(), 97, "TallyHo", now - day, 0, 0,
                "minecraft:glass", 64, "minecraft:oak_planks", 64, "minecraft:cobblestone", 64, "minecraft:stone", 64, "minecraft:bricks", 64,
                "minecraft:sandstone", 64, "minecraft:lantern", 16, "minecraft:ladder", 32, "minecraft:oak_log", 64));
        m.put("raider", kit("raider", "Raider's Edge", "For a first steal. Only for players who own no totem.", true, true, true, 0, 1000,
                List.of(), List.of(), List.of("no_totem"), "NONE", List.of(), 58, "Pixl", now - 5 * day, 0, 0,
                "minecraft:netherite_sword", 1, "minecraft:shield", 1, "minecraft:bow", 1, "minecraft:arrow", 64, "minecraft:golden_apple", 2,
                "minecraft:ender_pearl", 4, "minecraft:tnt", 4, "minecraft:iron_helmet", 1, "minecraft:netherite_chestplate", 1,
                "minecraft:netherite_boots", 1, "minecraft:cooked_beef", 16));
        m.put("spawn_welcome", kit("spawn_welcome", "Spawn Welcome", "A map of spawn and something to eat.", true, false, true, 0, 0,
                List.of(), List.of(), List.of(), "SERVERS", List.of("BSP-Spawn"), 2377, "Newbie_Sam", now - 12 * 60_000, 0, 0,
                "minecraft:compass", 1, "minecraft:bread", 8));
        m.put("vip_monthly", kit("vip_monthly", "VIP Monthly", "A thank-you for VIPs, once a month.", true, true, false, 30 * day / 1000, 0,
                List.of("vip"), List.of(), List.of(), "NONE", List.of(), 41, "RuneSmith", now - 9 * day, 1, 0,
                "minecraft:golden_apple", 4, "minecraft:totem_of_undying", 1, "minecraft:ender_pearl", 16, "minecraft:diamond", 8, "minecraft:emerald", 16,
                "minecraft:cake", 1));
        m.put("winter_event", kit("winter_event", "Winter Event", "Last winter's event kit.", false, false, true, 0, 0,
                List.of(), List.of(), List.of(), "NONE", List.of(), 0, "", 0, 0, 2,
                "minecraft:lantern", 8, "minecraft:cake", 1));
        String sam = uuidOf("Newbie_Sam"), kes = uuidOf("Kestrel_88"), vexa = uuidOf("Vexa");
        recordsOf(sam).add(record("starter", "Starter Kit", "", 1, now - 3 * day, 0));
        recordsOf(sam).add(record("spawn_welcome", "Spawn Welcome", "BSP-Spawn", 1, now - 3 * day, 0));
        recordsOf(sam).add(record("daily", "Daily Bread", "", 3, now - 5 * 3_600_000, 0));
        recordsOf(kes).add(record("starter", "Starter Kit", "", 1, now - 40 * day, 0));
        recordsOf(kes).add(record("miner", "Miner's Pack", "", 4, now - 2 * day, 1));
        recordsOf(kes).add(record("autumn_event", "", "", 1, now - 60 * day, 0));
        recordsOf(vexa).add(record("miner", "Miner's Pack", "", 2, now - 2 * 3_600_000, 2));
        recordsOf(vexa).add(record("vip_monthly", "VIP Monthly", "", 1, now - 9 * day, 1));
        return m;
    }

    static String uuidOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes()).toString();
    }

    private static JsonObject kit(String id, String name, String description, boolean enabled, boolean inBrowser, boolean once, long cooldown, long price,
                                  List<String> ranks, List<String> servers, List<String> conditions, String grant, List<String> grantServers,
                                  int taken, String lastBy, long lastMs, int owed, int missing, Object... items) {
        JsonObject k = new JsonObject();
        long now = System.currentTimeMillis();
        k.addProperty("id", id);
        k.addProperty("name", name);
        k.addProperty("description", description);
        k.add("icon", row("", 0));
        JsonArray it = new JsonArray();
        for (int i = 0; i + 1 < items.length; i += 2) {
            JsonObject r = row((String) items[i], (Integer) items[i + 1]);
            if (((String) items[i]).endsWith("_sword") && id.equals("raider")) {
                r.addProperty("nbt", "{Enchantments:[{id:\"minecraft:sharpness\",lvl:2s}],Damage:0}");
            }
            it.add(r);
        }
        k.add("items", it);
        k.addProperty("enabled", enabled);
        k.addProperty("inBrowser", inBrowser);
        k.addProperty("once", once);
        k.addProperty("cooldownSeconds", cooldown);
        k.addProperty("price", price);
        k.add("ranks", array(ranks.toArray(String[]::new)));
        k.add("servers", array(servers.toArray(String[]::new)));
        k.add("conditions", array(conditions.toArray(String[]::new)));
        k.addProperty("grant", grant);
        k.add("grantServers", array(grantServers.toArray(String[]::new)));
        k.addProperty("created", now - 40L * 86_400_000L);
        k.addProperty("edited", now - 3_600_000L);
        k.addProperty("taken", taken);
        k.addProperty("lastTakenBy", lastBy);
        k.addProperty("lastTakenMs", lastMs);
        k.addProperty("missing", missing);
        k.addProperty("owed", owed);
        return k;
    }

    private static JsonObject row(String item, int count) {
        JsonObject r = new JsonObject();
        r.addProperty("item", item);
        r.addProperty("count", count);
        r.addProperty("nbt", "");
        return r;
    }

    private static JsonArray array(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) {
            a.add(v);
        }
        return a;
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonObject done(boolean ok, String message, JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("message", message);
        o.add("data", data == null ? com.google.gson.JsonNull.INSTANCE : data);
        return o;
    }
}
