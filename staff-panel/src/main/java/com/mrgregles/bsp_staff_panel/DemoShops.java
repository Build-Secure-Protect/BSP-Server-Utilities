package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Made-up shops for {@code --demo} (SU-161), answering the {@code shop_*} actions as a game server with BSP-Core's shops
 * would (requests SU-BC5 to SU-BC10): shops of every kind with sections, rotating and special-offer shops, opening hours on
 * the real clock and the Minecraft day, shops turned off, stalls on several servers and sales. Item ids are ones the demo's
 * item pictures hold. Nothing reaches a game server.
 */
final class DemoShops {
    private static Map<String, JsonObject> shops;

    private DemoShops() {
    }

    /** The answer to a shop action: {ok, message, data}; null when {@code what} is not one. */
    static synchronized JsonObject answer(String what, JsonObject act, String server) {
        if (!what.startsWith("shop_")) {
            return null;
        }
        if (shops == null) {
            shops = make();
        }
        return switch (what) {
            case "shop_list" -> done(true, "demo shops", list());
            case "shop_read" -> read(act.has("shop") ? act.get("shop").getAsString() : "");
            case "shop_venues" -> done(true, "demo stalls", venues(server));
            case "shop_report" -> done(true, "demo sales", report(act.has("shop") ? act.get("shop").getAsString() : "", act.has("days") ? act.get("days").getAsInt() : 7));
            case "shop_create" -> changed(create(act));
            case "shop_set" -> changed(set(act));
            case "shop_enable" -> changed(enable(act));
            case "shop_copy" -> changed(copy(act));
            case "shop_delete" -> changed(delete(act));
            case "shop_item_add" -> changed(addCard(act));
            case "shop_item_set" -> changed(setCard(act));
            case "shop_item_move" -> changed(moveCard(act));
            case "shop_item_remove" -> changed(removeCard(act));
            case "shop_item_refill" -> changed(refillCard(act));
            case "shop_export" -> export(act.has("shop") ? act.get("shop").getAsString() : "");
            default -> done(false, "Not a shop action the demo knows.", null);
        };
    }

    private static volatile long changedAt;

    /** The snapshot's {@code shopsAt} (D811): when a demo shop last changed, 0 when none has. */
    static long changedAt() {
        return changedAt;
    }

    private static JsonObject changed(JsonObject answer) {
        if (answer.get("ok").getAsBoolean()) {
            changedAt = Math.max(changedAt + 1, System.currentTimeMillis());
            refresh();
        }
        return answer;
    }

    // ------------------------------------------------------------------ changes (SU-162), as BSP-Core would make them (SU-BC6 to SU-BC8)

    private static JsonObject stale(JsonObject act, JsonObject s) {
        if (s == null) {
            return done(false, "There is no shop " + (act.has("shop") ? act.get("shop").getAsString() : "") + ".", null);
        }
        if (!act.has("edited") || act.get("edited").getAsLong() != s.get("edited").getAsLong()) {
            JsonObject d = new JsonObject();
            d.addProperty("stale", true);
            return done(false, "It was changed in game since you read it: here it is as it is now.", d);
        }
        return null;
    }

    private static JsonObject stamped(JsonObject s, String message) {
        s.addProperty("edited", Math.max(System.currentTimeMillis(), s.get("edited").getAsLong() + 1));
        JsonObject d = new JsonObject();
        d.addProperty("shop", s.get("id").getAsString());
        d.add("edited", s.get("edited"));
        return done(true, message, d);
    }

    private static JsonObject create(JsonObject act) {
        String id = act.get("shop").getAsString();
        if (shops.containsKey(id)) {
            return done(false, "There is already a shop " + id + ".", null);
        }
        String parent = act.has("parent") ? act.get("parent").getAsString() : "";
        JsonObject p = parent.isEmpty() ? null : shops.get(parent);
        if (!parent.isEmpty()) {
            JsonObject why = stale(act, p);
            if (why != null) {
                return why;
            }
        }
        JsonObject s = shop(shops, id, act.get("name").getAsString(), act.get("kind").getAsString(), parent, p != null && p.get("enabled").getAsBoolean(), p == null || p.get("remote").getAsBoolean(),
                p == null ? "cash_machine" : p.get("style").getAsString(), 0, 3, always());
        s.addProperty("edited", System.currentTimeMillis());
        if (s.get("kind").getAsString().equals("rotating")) {
            rotating(s);
        }
        if (p != null) {
            Card c = section(id, act.get("picture").getAsString());
            if (act.has("v")) {
                c.potion(act.get("v").getAsString());
            }
            p.getAsJsonArray("cards").add(c.o);
            renumber(p);
            stamped(p, "");
            return stamped(s, "Section " + s.get("name").getAsString() + " made in " + p.get("name").getAsString() + ".");
        }
        return stamped(s, "Shop " + s.get("name").getAsString() + " made, off.");
    }

    private static JsonObject set(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        String field = act.get("field").getAsString();
        var v = act.get("value");
        switch (field) {
            case "kind" -> {
                String k = v.getAsString();
                if (!k.equals("rotating") && s.getAsJsonArray("cards").toString().contains("\"rotation\"")) {
                    return done(false, "Some cards fill again at each draw, which only a rotating shop makes: change them first.", null);
                }
                s.addProperty("kind", k);
                if (k.equals("rotating")) {
                    rotating(s);
                }
            }
            case "parent" -> { // BSP-Core moves the section card with it: off the old parent's page, onto the end of the new one's
                String old = s.get("parent").getAsString(), to = v.getAsString();
                JsonObject card = null;
                if (shops.containsKey(old)) {
                    JsonArray kept = new JsonArray();
                    for (var c : shops.get(old).getAsJsonArray("cards")) {
                        if (c.getAsJsonObject().get("section").getAsString().equals(s.get("id").getAsString()) && card == null) {
                            card = c.getAsJsonObject();
                        } else {
                            kept.add(c);
                        }
                    }
                    shops.get(old).add("cards", kept);
                    renumber(shops.get(old));
                    stamped(shops.get(old), "");
                }
                if (shops.containsKey(to)) {
                    shops.get(to).getAsJsonArray("cards").add(card != null ? card : section(s.get("id").getAsString(), "minecraft:chest").o);
                    renumber(shops.get(to));
                    stamped(shops.get(to), "");
                }
                s.addProperty("parent", to);
            }
            default -> s.add(field, v.deepCopy());
        }
        return stamped(s, "Saved.");
    }

    private static JsonObject enable(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        boolean on = act.get("on").getAsBoolean();
        for (String id : tree(s.get("id").getAsString())) {
            shops.get(id).addProperty("enabled", on);
        }
        return stamped(s, on ? "On: players can open it again." : "Off: closed for changes.");
    }

    private static JsonObject copy(JsonObject act) {
        String from = act.get("shop").getAsString(), to = act.get("newId").getAsString();
        if (!shops.containsKey(from)) {
            return done(false, "There is no shop " + from + ".", null);
        }
        if (!shops.get(from).get("parent").getAsString().isEmpty()) {
            return done(false, "Only a top shop can do that: a section follows its shop.", null); // BSP-Core's not_top
        }
        if (shops.containsKey(to)) {
            return done(false, "There is already a shop " + to + ".", null);
        }
        List<String> ids = tree(from);
        Map<String, String> map = new LinkedHashMap<>();
        map.put(from, to);
        int n = 1;
        for (String id : ids.subList(1, ids.size())) {
            String made;
            do {
                made = (to.length() > 29 ? to.substring(0, 29) : to) + "_" + n++;
            } while (shops.containsKey(made) || map.containsValue(made));
            map.put(id, made);
        }
        long now = System.currentTimeMillis();
        JsonObject idsOut = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            JsonObject c = shops.get(e.getKey()).deepCopy();
            c.addProperty("id", e.getValue());
            if (e.getKey().equals(from)) {
                c.addProperty("name", act.get("name").getAsString());
            } else {
                c.addProperty("parent", map.getOrDefault(c.get("parent").getAsString(), c.get("parent").getAsString()));
            }
            c.addProperty("enabled", false);
            c.addProperty("edited", now);
            for (var card : c.getAsJsonArray("cards")) {
                JsonObject cc = card.getAsJsonObject();
                String sec = cc.get("section").getAsString();
                if (map.containsKey(sec)) {
                    cc.addProperty("section", map.get(sec));
                }
                cc.addProperty("stockLeft", cc.get("stockGlobal").getAsInt());
                cc.addProperty("sellLeft", cc.get("sellGlobal").getAsInt());
            }
            shops.put(e.getValue(), c);
            idsOut.addProperty(e.getKey(), e.getValue());
        }
        JsonObject d = new JsonObject();
        d.addProperty("shop", to);
        d.add("ids", idsOut);
        return done(true, "Copied as " + act.get("name").getAsString() + ", off.", d);
    }

    private static JsonObject delete(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        List<String> ids = tree(s.get("id").getAsString());
        String parent = s.get("parent").getAsString();
        ids.forEach(shops::remove);
        JsonObject p = shops.get(parent);
        if (p != null) { // its section card goes with it
            JsonArray kept = new JsonArray();
            p.getAsJsonArray("cards").forEach(c -> {
                if (!ids.contains(c.getAsJsonObject().get("section").getAsString())) {
                    kept.add(c);
                }
            });
            p.add("cards", kept);
            renumber(p);
            stamped(p, "");
        }
        JsonObject d = new JsonObject();
        JsonArray gone = new JsonArray();
        ids.forEach(gone::add);
        d.add("deleted", gone);
        return done(true, "Deleted" + (ids.size() > 1 ? " with its " + (ids.size() - 1) + " sections." : "."), d);
    }

    // ------------------------------------------------------------------ the cards (SU-163), as BSP-Core's Workbench does

    private static JsonObject addCard(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        Card c = card(act.get("item").getAsString(), act.get("count").getAsInt(), -1, -1);
        if (act.has("v")) {
            c.potion(act.get("v").getAsString());
        }
        JsonArray cards = s.getAsJsonArray("cards");
        int at = Math.max(0, Math.min(act.get("place").getAsInt(), cards.size()));
        List<com.google.gson.JsonElement> all = new ArrayList<>();
        cards.forEach(all::add);
        all.add(at, c.o);
        JsonArray out = new JsonArray();
        all.forEach(out::add);
        s.add("cards", out);
        renumber(s);
        JsonObject a = stamped(s, "Added at card " + (at + 1) + ": set its price.");
        a.getAsJsonObject("data").addProperty("index", at);
        return a;
    }

    private static JsonObject setCard(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        int i = act.get("index").getAsInt();
        JsonArray cards = s.getAsJsonArray("cards");
        if (i < 0 || i >= cards.size()) {
            return done(false, "There is no such card.", null);
        }
        JsonObject c = cards.get(i).getAsJsonObject();
        String field = act.get("field").getAsString();
        c.add(field, act.get("value").deepCopy());
        if (field.startsWith("stock") || field.startsWith("sell") && !field.equals("sell") || field.equals("count") || field.equals("match")) {
            fill(c);
        }
        return stamped(s, "Saved.");
    }

    private static JsonObject moveCard(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        JsonArray cards = s.getAsJsonArray("cards");
        int from = act.get("from").getAsInt(), to = act.get("to").getAsInt();
        if (from < 0 || to < 0 || from >= cards.size() || to >= cards.size()) {
            return done(false, "There is no such card.", null);
        }
        List<com.google.gson.JsonElement> all = new ArrayList<>();
        cards.forEach(all::add);
        all.add(to, all.remove(from));
        JsonArray out = new JsonArray();
        all.forEach(out::add);
        s.add("cards", out);
        renumber(s);
        for (int k = Math.min(from, to); k <= Math.max(from, to); k++) {
            fill(out.get(k).getAsJsonObject()); // every card between moved: their counts start again
        }
        JsonObject a = stamped(s, "Moved to card " + (to + 1) + ".");
        a.getAsJsonObject("data").addProperty("index", to);
        return a;
    }

    private static JsonObject removeCard(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        JsonArray cards = s.getAsJsonArray("cards");
        int i = act.get("index").getAsInt();
        if (i < 0 || i >= cards.size()) {
            return done(false, "There is no such card.", null);
        }
        cards.remove(i);
        renumber(s);
        return stamped(s, "Removed.");
    }

    private static JsonObject refillCard(JsonObject act) {
        JsonObject s = shops.get(act.get("shop").getAsString());
        JsonObject why = stale(act, s);
        if (why != null) {
            return why;
        }
        int i = act.get("index").getAsInt();
        JsonArray cards = s.getAsJsonArray("cards");
        if (i < 0 || i >= cards.size()) {
            return done(false, "There is no such card.", null);
        }
        fill(cards.get(i).getAsJsonObject());
        return stamped(s, "Every count of the card is full again.");
    }

    private static void fill(JsonObject c) {
        c.addProperty("stockLeft", c.get("stockGlobal").getAsInt());
        c.addProperty("sellLeft", c.get("sellGlobal").getAsInt());
    }

    /** The shop and its sections in the shape of BSP-Core's import file (format 1, its docs/API.md), as {@code /bsp shop export} writes it. */
    private static JsonObject export(String id) {
        if (!shops.containsKey(id)) {
            return done(false, "There is no shop " + id + ".", null);
        }
        JsonArray list = new JsonArray();
        int cards = 0;
        String[] tiers = {"copper", "gold", "diamond", "netherite", "illyrium"};
        for (String sid : tree(id)) {
            JsonObject s = shops.get(sid), o = new JsonObject();
            for (String k : List.of("id", "name", "parent", "remote", "buyOnly", "style", "columns", "rows")) {
                o.add(k, s.get(k));
            }
            o.addProperty("kind", s.get("kind").getAsString());
            o.addProperty("enabled", s.get("enabled").getAsBoolean());
            JsonObject h = s.getAsJsonObject("hours"), hh = new JsonObject();
            hh.addProperty("mode", h.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT));
            if (h.get("mode").getAsString().equals("REAL")) {
                hh.addProperty("open", String.format("%02d:%02d", h.get("open").getAsInt() / 60, h.get("open").getAsInt() % 60));
                hh.addProperty("close", String.format("%02d:%02d", h.get("close").getAsInt() / 60, h.get("close").getAsInt() % 60));
            } else if (h.get("mode").getAsString().equals("GAME")) {
                hh.add("open", h.get("open"));
                hh.add("close", h.get("close"));
            }
            o.add("hours", hh);
            o.add("requires", s.get("requires"));
            JsonArray page = new JsonArray();
            for (var c : s.getAsJsonArray("cards")) {
                JsonObject cc = c.getAsJsonObject(), k = new JsonObject();
                k.add("item", cc.get("item"));
                k.add("count", cc.get("count"));
                k.add("buy", cc.get("buy"));
                k.add("sell", cc.get("sell"));
                int cur = cc.get("currency").getAsInt();
                k.addProperty("currency", cur < 0 ? "pieces" : tiers[cur]);
                for (String f : List.of("stockGlobal", "stockPlayer", "sellGlobal", "sellPlayer", "stockRefill", "stockPlayerRefill", "sellRefill", "sellPlayerRefill",
                        "instant", "hidden", "section", "match", "weight", "requires")) {
                    k.add(f, cc.get(f));
                }
                k.add("lore", cc.get("note"));
                page.add(k);
                cards++;
            }
            JsonArray pages = new JsonArray();
            pages.add(page);
            o.add("pages", pages);
            list.add(o);
        }
        JsonObject file = new JsonObject();
        file.addProperty("format", 1);
        file.addProperty("allowCoinPayouts", false);
        file.add("shops", list);
        JsonObject d = new JsonObject();
        d.add("json", file);
        d.addProperty("shops", list.size());
        d.addProperty("cards", cards);
        return done(true, "Exported.", d);
    }

    /** A shop and every shop under it, the shop first. */
    private static List<String> tree(String id) {
        List<String> out = new ArrayList<>();
        out.add(id);
        for (int pass = 0; pass < 16; pass++) {
            for (JsonObject s : shops.values()) {
                String sid = s.get("id").getAsString();
                if (!out.contains(sid) && out.contains(s.get("parent").getAsString())) {
                    out.add(sid);
                }
            }
        }
        return out;
    }

    private static void renumber(JsonObject s) {
        int i = 0;
        for (var c : s.getAsJsonArray("cards")) {
            c.getAsJsonObject().addProperty("i", i++);
        }
    }

    private static void rotating(JsonObject s) {
        if (!s.has("displaySlots")) {
            s.addProperty("displaySlots", 4);
            s.addProperty("intervalHours", 12);
            s.addProperty("rotateAt", "none");
            s.addProperty("noRepeat", false);
            s.addProperty("broadcast", false);
        }
        if (!s.has("onShow")) {
            s.add("onShow", new JsonArray());
            s.add("draws", new JsonArray());
        }
    }

    /** Open now and when it opens, after hours or on and off changed. */
    private static void refresh() {
        for (JsonObject s : shops.values()) {
            s.addProperty("openNow", s.get("enabled").getAsBoolean() && open(s.getAsJsonObject("hours")));
            s.addProperty("opensAt", opensAt(s.getAsJsonObject("hours")));
        }
    }

    /** The made-up shops as they start (each test starts from them). */
    static synchronized void reset() {
        shops = null;
        changedAt = 0;
    }

    /** A server that is off, remembered with its stalls (R3's greyed rows). */
    static void offline(com.mrgregles.bsp_staff_panel.shops.ShopDesk desk) {
        desk.remember("SafeRoom", System.currentTimeMillis() - 3 * 3_600_000L, venues("SafeRoom"));
    }

    // ------------------------------------------------------------------ the shops

    private static JsonObject list() {
        JsonArray a = new JsonArray();
        for (JsonObject s : shops.values()) {
            JsonObject o = s.deepCopy();
            JsonArray cards = o.getAsJsonArray("cards");
            o.addProperty("cards", cards.size());
            JsonArray peek = new JsonArray();
            for (var c : cards) {
                JsonObject cc = c.getAsJsonObject();
                if (cc.get("section").getAsString().isEmpty() && !cc.get("hidden").getAsBoolean() && peek.size() < 4) {
                    JsonObject p = new JsonObject();
                    p.add("item", cc.get("item"));
                    if (cc.has("v")) {
                        p.add("v", cc.get("v"));
                    }
                    peek.add(p);
                }
            }
            o.add("peek", peek);
            for (String k : List.of("onShow", "draws", "cycleLeft")) {
                o.remove(k);
            }
            a.add(o);
        }
        JsonObject d = new JsonObject();
        d.add("shops", a);
        d.addProperty("timeZone", "Europe/London");
        return d;
    }

    private static JsonObject read(String id) {
        JsonObject s = shops.get(id);
        if (s == null) {
            return done(false, "There is no shop " + id + ".", null);
        }
        JsonObject d = new JsonObject();
        d.add("shop", s.deepCopy());
        return done(true, "demo shop", d);
    }

    private static Map<String, JsonObject> make() {
        long now = System.currentTimeMillis();
        Map<String, JsonObject> m = new LinkedHashMap<>();
        JsonObject g = shop(m, "spawn_general", "General Store", "shop", "", true, true, "cash_machine", 7, 3, always());
        g.add("cards", cards(
                section("spawn_general_food", "minecraft:bread"), section("spawn_general_tools", "minecraft:iron_pickaxe"), section("spawn_general_redstone", "minecraft:redstone"),
                card("minecraft:torch", 16, 32, -1), card("minecraft:iron_ingot", 1, 180, 60).note("Smelted at the spawn forge."), card("minecraft:oak_log", 16, 96, 24),
                card("minecraft:coal", 8, 48, 12), card("minecraft:glass", 16, 64, -1), card("minecraft:ender_pearl", 1, 2, -1).coin(1), card("minecraft:emerald", 1, 1, -1).coin(2),
                card("minecraft:diamond", 1, -1, 400).sellLimits(-1, 16, "never", "day 06:00").left(-1, 9), card("minecraft:shulker_box", 1, 4500, -1).hide(),
                card("minecraft:totem_of_undying", 1, 4, -1).coin(3).needs("", 50, false, "Veterans only"), card("minecraft:lantern", 1, 55, -1),
                card("minecraft:ladder", 8, 40, -1), card("minecraft:bucket", 1, 120, 20), card("minecraft:string", 8, 30, 6), card("minecraft:bone_meal", 16, 24, -1),
                card("minecraft:gunpowder", 4, 80, 15).stock(500, 10, "12h", "same").left(312, -1), card("minecraft:lapis_lazuli", 8, 64, 16), card("minecraft:compass", 1, 150, -1),
                card("minecraft:clock", 1, 150, -1), card("minecraft:shears", 1, 90, -1), card("minecraft:fishing_rod", 1, 70, -1), card("minecraft:tnt", 1, 300, -1).needs("bsp.shop.tnt", 0, true, "Builders only"),
                card("minecraft:arrow", 16, 48, -1), card("minecraft:bow", 1, 160, -1)));
        shop(m, "spawn_general_food", "Food", "sub", "spawn_general", true, false, "cash_machine", 0, 2, always()).add("cards", cards(
                card("minecraft:bread", 1, 40, -1), card("minecraft:cooked_beef", 1, 75, 20), card("minecraft:apple", 4, 30, 6),
                card("minecraft:golden_apple", 1, 650, -1).needs("", 10, false, "10 hours played"), card("minecraft:cake", 1, 220, -1),
                card("minecraft:potion", 1, 140, -1).potion("minecraft:swiftness"), card("minecraft:potion", 1, 160, -1).potion("minecraft:healing")));
        shop(m, "spawn_general_tools", "Tools", "sub", "spawn_general", true, false, "cash_machine", 7, 2, always()).add("cards", cards(
                section("spawn_general_pickaxes", "minecraft:diamond_pickaxe"), card("minecraft:shears", 1, 90, -1), card("minecraft:fishing_rod", 1, 70, -1),
                card("minecraft:compass", 1, 150, -1), card("minecraft:clock", 1, 150, -1), card("minecraft:bucket", 1, 120, 20), card("minecraft:lantern", 1, 55, -1)));
        shop(m, "spawn_general_pickaxes", "Pickaxes", "sub", "spawn_general_tools", true, false, "cash_machine", 0, 2, always()).add("cards", cards(
                card("minecraft:iron_pickaxe", 1, 300, 40).match("item"), card("minecraft:diamond_pickaxe", 1, 3, -1).coin(1),
                card("minecraft:netherite_pickaxe", 1, 2, -1).coin(3).needs("bsp.rank.miner", 0, true, "Miner rank")));
        shop(m, "spawn_general_redstone", "Redstone", "sub", "spawn_general", true, false, "cash_machine", 0, 2, always()).add("cards", cards(
                card("minecraft:redstone", 32, 120, 30), card("minecraft:tnt", 1, 300, -1), card("create:electron_tube", 1, 260, -1), card("create:precision_mechanism", 1, 900, -1)));
        shop(m, "spawn_blocks", "Builder's Yard", "shop", "", true, true, "market_board", 0, 3, always()).add("cards", cards(
                card("minecraft:cobblestone", 64, 32, 8), card("minecraft:oak_planks", 64, 96, 16), card("minecraft:stone", 64, 64, 12), card("minecraft:bricks", 32, 160, -1),
                card("minecraft:sandstone", 64, 80, -1), card("minecraft:glass", 32, 128, -1), card("minecraft:ladder", 16, 64, -1), card("minecraft:lantern", 4, 200, -1),
                card("minecraft:torch", 64, 120, -1)));
        JsonObject ch = shop(m, "spawn_challenge", "Challenge", "rotating", "", true, true, "cash_machine", 0, 3, always());
        ch.add("cards", cards(
                card("create:brass_casing", 1, -1, 2500).weight(1).sellLimits(200, 4, "rotation", "same"), card("create:mechanical_press", 1, -1, 4000).weight(1),
                card("create:precision_mechanism", 1, -1, 3200).weight(2), card("create:brass_ingot", 8, -1, 900).weight(3),
                card("minecraft:iron_ingot", 16, -1, 600).weight(3), card("minecraft:diamond", 4, -1, 1500).weight(1), card("minecraft:redstone", 32, -1, 400).weight(2),
                card("create:cogwheel", 4, -1, 700).weight(1), card("create:shaft", 8, -1, 800).weight(1), card("minecraft:lantern", 8, -1, 350).weight(2),
                card("minecraft:glass", 32, -1, 300).weight(3), card("create:electron_tube", 1, -1, 2).coin(1).weight(0)));
        ch.addProperty("displaySlots", 4);
        ch.addProperty("intervalHours", 168);
        ch.addProperty("rotateAt", "monday 00:00");
        ch.addProperty("noRepeat", true);
        ch.addProperty("broadcast", true);
        ch.addProperty("nextChange", now + 4 * 86_400_000L + 9 * 3_600_000L);
        ch.add("onShow", ints(0, 1, 2, 3));
        ch.addProperty("cycleLeft", 4);
        JsonArray draws = new JsonArray();
        draws.add(draw(now - 3 * 86_400_000L, 0, 1, 2, 3)); // BSP-Core keeps only the draw on show
        ch.add("draws", draws);
        JsonObject ws = shop(m, "winter_sale", "Winter Sale", "offer", "", false, true, "passbook", 0, 3, always());
        ws.addProperty("offerPercent", 25);
        ws.addProperty("offerEnd", now + 35 * 86_400_000L);
        ws.add("cards", cards(section("winter_sale_books", "minecraft:compass"), section("winter_sale_potions", "minecraft:potion"),
                card("minecraft:ender_pearl", 4, 600, -1), card("minecraft:golden_apple", 1, 500, -1)));
        shop(m, "winter_sale_books", "Books", "sub", "winter_sale", false, true, "passbook", 0, 3, always()).add("cards", cards(card("minecraft:compass", 1, 120, -1), card("minecraft:clock", 1, 120, -1)));
        shop(m, "winter_sale_potions", "Potions", "sub", "winter_sale", false, true, "passbook", 0, 3, always()).add("cards", cards(
                card("minecraft:potion", 1, 100, -1).potion("minecraft:swiftness"), card("minecraft:potion", 1, 120, -1).potion("minecraft:healing")));
        shop(m, "farmers_market", "Farmers' Market", "shop", "", true, false, "market_board", 5, 2, hours("GAME", 0, 14000)).add("cards", cards(
                card("minecraft:apple", 4, 12, 3), card("minecraft:bread", 1, 10, -1), card("minecraft:cake", 1, 40, -1), card("minecraft:cooked_beef", 1, 9, 2), card("minecraft:bone_meal", 16, 8, -1)));
        shop(m, "miners_guild", "Miners' Guild", "shop", "", true, true, "cash_machine", 0, 3, always()).add("cards", cards(
                card("minecraft:iron_pickaxe", 1, 280, -1), card("minecraft:coal", 16, 90, 20), card("minecraft:iron_ingot", 8, 1300, 400), card("minecraft:diamond", 1, -1, 420)));
        m.get("miners_guild").add("requires", requires("bsp.rank.miner", 0, false, "Miner rank"));
        shop(m, "night_market", "Night Market", "shop", "", true, true, "passbook", 0, 3, hours("REAL", 20 * 60, 2 * 60)).add("cards", cards(
                card("minecraft:ender_pearl", 2, 400, -1), card("minecraft:lantern", 1, 50, -1), card("minecraft:potion", 1, 150, -1).potion("minecraft:swiftness"), card("minecraft:tnt", 1, 320, -1)));
        shop(m, "spring_fair", "Spring Fair", "shop", "", false, true, "passbook", 0, 3, always()).add("cards", cards(
                section("spring_fair_seeds", "minecraft:bone_meal"), card("minecraft:cake", 1, 200, -1), card("minecraft:apple", 8, 40, -1)));
        shop(m, "spring_fair_seeds", "Seeds", "sub", "spring_fair", false, true, "passbook", 0, 3, always()).add("cards", cards(card("minecraft:bone_meal", 16, 20, -1)));
        for (JsonObject s : m.values()) { // open now follows the hours; an OFF shop is never open
            s.addProperty("edited", now - 3_600_000L);
            s.addProperty("openNow", s.get("enabled").getAsBoolean() && open(s.getAsJsonObject("hours")));
            s.addProperty("opensAt", opensAt(s.getAsJsonObject("hours")));
        }
        return m;
    }

    // ------------------------------------------------------------------ stalls and sales

    private static JsonObject venues(String server) {
        JsonArray a = new JsonArray();
        switch (server) {
            case "BSP-Spawn" -> {
                a.add(venue("stall", 112, 64, -40, "spawn_general", null, null));
                a.add(venue("stall", 120, 64, -44, "spawn_general_tools", null, null));
                a.add(venue("stall", 126, 64, -44, "spawn_general_food", hours("REAL", 7 * 60, 19 * 60), null));
                a.add(venue("post", 98, 65, -12, "spawn_general", hours("REAL", 8 * 60, 22 * 60), null));
                a.add(venue("display", 104, 66, -38, "spawn_general_pickaxes", null, null));
                a.add(venue("display", 130, 66, -40, "spawn_general_food", null, "minecraft:bread"));
                a.add(venue("stall", 140, 64, -30, "spawn_blocks", null, null));
                a.add(venue("stall", 146, 64, -30, "spawn_blocks", null, null));
                a.add(venue("post", 88, 64, -20, "spawn_challenge", null, null));
                a.add(venue("stall", 150, 64, -50, "winter_sale", null, null));
                a.add(venue("stall", 160, 64, -52, "spring_fair", null, null));
                a.add(venue("stall", 164, 64, -52, "spring_fair_seeds", null, null));
            }
            case "Frostwarf" -> {
                a.add(venue("stall", -310, 71, 228, "spawn_general", null, null));
                a.add(venue("post", -280, 40, 300, "miners_guild", null, null));
                a.add(venue("post", -284, 40, 300, "miners_guild", null, null));
            }
            case "Moongrove" -> {
                for (int i = 0; i < 4; i++) {
                    a.add(venue("stall", 400 + 6 * i, 70, -120, "farmers_market", null, null));
                }
            }
            case "Boulderbrook" -> {
                a.add(venue("stall", 20, 66, 18, "spawn_blocks", null, null));
                a.add(venue("display", 24, 67, 18, "spawn_blocks", null, null));
                a.add(venue("stall", 30, 66, 18, "farmers_market", hours("GAME", 0, 12000), null));
                a.add(venue("stall", 36, 66, 18, "farmers_market", null, null));
            }
            case "Stormfall" -> {
                a.add(venue("stall", -12, 80, 40, "night_market", null, null));
                a.add(venue("stall", -18, 80, 40, "night_market", null, null));
            }
            case "SafeRoom" -> a.add(venue("stall", 0, 70, 0, "spawn_general", null, null));
            default -> {
            }
        }
        JsonObject d = new JsonObject();
        d.add("venues", a);
        return d;
    }

    private static JsonObject report(String shop, int days) {
        List<String> ids = new ArrayList<>();
        ids.add(shop);
        for (int pass = 0; pass < 4; pass++) {
            for (JsonObject s : shops.values()) {
                String id = s.get("id").getAsString();
                if (!ids.contains(id) && ids.contains(s.get("parent").getAsString())) {
                    ids.add(id);
                }
            }
        }
        JsonArray rows = new JsonArray();
        long in = 0, out = 0;
        long[] coinsIn = new long[5], coinsOut = new long[5];
        int n = 0;
        for (String id : ids) {
            JsonObject s = shops.get(id);
            if (s == null) {
                continue;
            }
            for (var c : s.getAsJsonArray("cards")) {
                JsonObject cc = c.getAsJsonObject();
                if (!cc.get("section").getAsString().isEmpty() || n++ % 3 == 2) {
                    continue;
                }
                long buy = Math.max(0, cc.get("buy").getAsLong()), sell = Math.max(0, cc.get("sell").getAsLong());
                int bought = buy > 0 ? (int) ((7 + (n * 13) % 40) * days / 7.0 + 1) : 0, sold = sell > 0 ? (int) ((3 + (n * 7) % 25) * days / 7.0 + 1) : 0;
                int cur = cc.get("currency").getAsInt();
                boolean coins = cur >= 0;
                JsonObject r = new JsonObject(); // BSP-Core's ReportRow: no v, coins as five counts (copper to illyrium)
                r.addProperty("shop", id);
                r.addProperty("shopName", s.get("name").getAsString());
                r.add("item", cc.get("item"));
                r.addProperty("bought", bought);
                r.addProperty("sold", sold);
                r.addProperty("piecesIn", coins ? 0 : bought * buy);
                r.addProperty("piecesOut", coins ? 0 : sold * sell);
                JsonArray cin = new JsonArray(), cout = new JsonArray();
                for (int t = 0; t < 5; t++) {
                    cin.add(t == cur ? bought * buy : 0);
                    cout.add(t == cur ? sold * sell : 0);
                    if (t == cur) {
                        coinsIn[t] += bought * buy;
                        coinsOut[t] += sold * sell;
                    }
                }
                r.add("coinsIn", cin);
                r.add("coinsOut", cout);
                in += coins ? 0 : bought * buy;
                out += coins ? 0 : sold * sell;
                rows.add(r);
            }
        }
        JsonObject d = new JsonObject();
        d.addProperty("shop", shop);
        d.addProperty("days", days);
        d.addProperty("piecesIn", in);
        d.addProperty("piecesOut", out);
        JsonArray ci = new JsonArray(), co = new JsonArray();
        for (int t = 0; t < 5; t++) {
            ci.add(coinsIn[t]);
            co.add(coinsOut[t]);
        }
        d.add("coinsIn", ci);
        d.add("coinsOut", co);
        d.addProperty("capped", days > 14); // as if the bank log kept 14 days
        d.addProperty("days", Math.min(days, 14));
        d.add("rows", rows);
        JsonArray sellers = new JsonArray();
        String[] names = {"Kestrel_88", "Vexa", "BrickLayerTom", "Juniper_K", "Glimmer"};
        for (int k = 0; k < names.length; k++) {
            JsonObject p = new JsonObject();
            p.addProperty("name", names[k]);
            p.addProperty("pieces", out / (k + 2) + 10);
            sellers.add(p);
        }
        d.add("sellers", sellers);
        return d;
    }

    // ------------------------------------------------------------------ parts

    /** A card under construction, with the small setters the made-up shops use. */
    private static final class Card {
        final JsonObject o = new JsonObject();

        Card coin(int tier) {
            o.addProperty("currency", tier);
            return this;
        }

        Card note(String line) {
            JsonArray a = new JsonArray();
            a.add(line);
            o.add("note", a);
            return this;
        }

        Card hide() {
            o.addProperty("hidden", true);
            return this;
        }

        Card match(String m) {
            o.addProperty("match", m);
            return this;
        }

        Card weight(int w) {
            o.addProperty("weight", w);
            return this;
        }

        Card potion(String v) {
            o.addProperty("v", v);
            return this;
        }

        Card needs(String perm, int hours, boolean hide, String reason) {
            o.add("requires", requires(perm, hours, hide, reason));
            return this;
        }

        Card stock(int all, int each, String rule, String eachRule) {
            o.addProperty("stockGlobal", all);
            o.addProperty("stockPlayer", each);
            o.addProperty("stockRefill", rule);
            o.addProperty("stockPlayerRefill", eachRule);
            return this;
        }

        Card sellLimits(int all, int each, String rule, String eachRule) {
            o.addProperty("sellGlobal", all);
            o.addProperty("sellPlayer", each);
            o.addProperty("sellRefill", rule);
            o.addProperty("sellPlayerRefill", eachRule);
            return this;
        }

        Card left(int stock, int sell) {
            o.addProperty("stockLeft", stock);
            o.addProperty("sellLeft", sell);
            return this;
        }
    }

    private static Card card(String item, int count, long buy, long sell) {
        Card c = new Card();
        c.o.addProperty("item", item);
        c.o.addProperty("count", count);
        c.o.addProperty("buy", buy);
        c.o.addProperty("sell", sell);
        c.o.addProperty("currency", -1);
        c.o.addProperty("stockGlobal", -1);
        c.o.addProperty("stockPlayer", -1);
        c.o.addProperty("sellGlobal", -1);
        c.o.addProperty("sellPlayer", -1);
        c.o.addProperty("stockRefill", "never");
        c.o.addProperty("stockPlayerRefill", "same");
        c.o.addProperty("sellRefill", "never");
        c.o.addProperty("sellPlayerRefill", "same");
        c.o.addProperty("instant", false);
        c.o.addProperty("hidden", false);
        c.o.addProperty("section", "");
        c.o.addProperty("match", "exact");
        c.o.addProperty("weight", 1);
        c.o.add("note", new JsonArray());
        c.o.add("requires", requires("", 0, false, ""));
        c.o.addProperty("stockLeft", -1);
        c.o.addProperty("sellLeft", -1);
        return c;
    }

    private static Card section(String id, String picture) {
        Card c = card(picture, 1, -1, -1);
        c.o.addProperty("section", id);
        return c;
    }

    private static JsonArray cards(Card... cs) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < cs.length; i++) {
            cs[i].o.addProperty("i", i);
            a.add(cs[i].o);
        }
        return a;
    }

    private static JsonObject shop(Map<String, JsonObject> m, String id, String name, String kind, String parent, boolean enabled, boolean remote, String style, int columns, int rows, JsonObject hours) {
        JsonObject s = new JsonObject();
        s.addProperty("id", id);
        s.addProperty("name", name);
        s.addProperty("kind", kind);
        s.addProperty("parent", parent);
        s.addProperty("enabled", enabled);
        s.addProperty("remote", remote);
        s.addProperty("buyOnly", false);
        s.addProperty("style", style);
        s.addProperty("columns", columns);
        s.addProperty("rows", rows);
        s.add("hours", hours);
        s.add("requires", requires("", 0, false, ""));
        s.addProperty("offerPercent", 0);
        s.addProperty("offerEnd", 0);
        s.add("cards", new JsonArray());
        m.put(id, s);
        return s;
    }

    private static JsonObject requires(String perm, int hours, boolean hide, String reason) {
        JsonObject r = new JsonObject();
        r.addProperty("permission", perm);
        r.addProperty("playtimeHours", hours);
        r.addProperty("hide", hide);
        r.addProperty("reason", reason);
        return r;
    }

    private static JsonObject always() {
        return hours("ALWAYS", 0, 0);
    }

    private static JsonObject hours(String mode, int open, int close) {
        JsonObject h = new JsonObject();
        h.addProperty("mode", mode);
        h.addProperty("open", open);
        h.addProperty("close", close);
        return h;
    }

    /** BSP-Core's rule (OpenHours.isOpenAt): the demo's Minecraft day is held at night so the market shows closed. */
    private static boolean open(JsonObject h) {
        String mode = h.get("mode").getAsString();
        int o = h.get("open").getAsInt(), c = h.get("close").getAsInt();
        if (mode.equals("ALWAYS") || o == c) {
            return true;
        }
        int at = mode.equals("GAME") ? 18000 : LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        return o < c ? at >= o && at < c : at >= o || at < c;
    }

    private static String opensAt(JsonObject h) {
        String mode = h.get("mode").getAsString();
        int o = h.get("open").getAsInt();
        if (mode.equals("ALWAYS")) {
            return "";
        }
        if (mode.equals("GAME")) {
            int hh = (o / 1000 + 6) % 24, mm = (o % 1000) * 60 / 1000;
            return String.format("%02d:%02d (game)", hh, mm);
        }
        return String.format("%02d:%02d", o / 60, o % 60);
    }

    private static JsonObject venue(String kind, int x, int y, int z, String shop, JsonObject hours, String pin) {
        JsonObject v = new JsonObject();
        v.addProperty("kind", kind);
        v.addProperty("dim", "minecraft:overworld");
        v.addProperty("x", x);
        v.addProperty("y", y);
        v.addProperty("z", z);
        v.addProperty("shop", shop);
        if (hours != null) {
            v.add("hours", hours);
        }
        if (pin != null) {
            v.addProperty("pin", pin);
        }
        return v;
    }

    private static JsonArray ints(int... is) {
        JsonArray a = new JsonArray();
        for (int i : is) {
            a.add(i);
        }
        return a;
    }

    private static JsonObject draw(long at, int... cards) {
        JsonObject d = new JsonObject();
        d.addProperty("at", at);
        d.add("cards", ints(cards));
        return d;
    }

    private static JsonObject done(boolean ok, String message, JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("message", message);
        o.add("data", data == null ? com.google.gson.JsonNull.INSTANCE : data);
        return o;
    }
}
