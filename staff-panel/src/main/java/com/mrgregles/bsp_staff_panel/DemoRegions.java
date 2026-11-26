package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Made-up regions for {@code --demo} (SU-155), answering the {@code region_*} actions as a game server with BSP Region
 * Guard would: each demo server's worlds and regions of every kind, the flag list Region Guard declares
 * ({@code demo/region-flags.json}, from {@code tools/gen_demo_region_flags.py}), and saves, removals and mine resets
 * applied to the made-up regions with their version checked, so the editor can be tried end to end. Nothing reaches a
 * game server.
 */
final class DemoRegions {
    private static final Map<String, JsonObject> WORLDS = new ConcurrentHashMap<>();
    private static JsonArray flags;
    private static final String OW = "minecraft:overworld", NE = "minecraft:the_nether", EN = "minecraft:the_end";

    private DemoRegions() {
    }

    /** The answer to a region action: {ok, message, data}; null when {@code what} is not one. */
    static synchronized JsonObject answer(String what, JsonObject act) {
        if (!what.startsWith("region")) {
            return null;
        }
        String server = act.has("server") ? act.get("server").getAsString() : "";
        JsonObject world = world(server);
        JsonObject out = switch (what) {
            case "regions_list" -> done(true, "demo regions", world.deepCopy());
            case "region_save" -> save(world, act);
            case "region_remove" -> remove(world, act);
            case "region_mine_reset" -> reset(world, act);
            case "region_check" -> check(world, act, server);
            case "region_create" -> create(world, act, server);
            default -> done(false, "Not a region action the demo knows.", null);
        };
        if (!Set.of("regions_list", "region_check", "region_mine_reset").contains(what) && out.get("ok").getAsBoolean()) { // a mine's timer aside, as Region Guard
            CHANGED.merge(server.toLowerCase(java.util.Locale.ROOT), System.currentTimeMillis(), (a, b) -> Math.max(a + 1, b)); // the snapshot's regionsAt (D781)
        }
        return out;
    }

    private static final Map<String, Long> CHANGED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The snapshot's {@code regionsAt} for a demo server: when a region there last changed, 0 when none has. */
    static long changedAt(String server) {
        return CHANGED.getOrDefault(server.toLowerCase(java.util.Locale.ROOT), 0L);
    }

    /** The made-up worlds as they start (each test starts from them). */
    static synchronized void reset() {
        WORLDS.clear();
        CHANGED.clear();
    }

    /** A server that is off, remembered with what it last said (the WORLDS tab's greyed tile). */
    static void offline(com.mrgregles.bsp_staff_panel.regions.RegionDesk desk) {
        desk.remember("SafeRoom", System.currentTimeMillis() - 3 * 3_600_000L, world("SafeRoom"));
    }

    // ------------------------------------------------------------------ the made-up worlds

    static synchronized JsonObject world(String server) {
        return WORLDS.computeIfAbsent(server, DemoRegions::make);
    }

    private static JsonObject make(String server) {
        JsonObject w = new JsonObject();
        w.addProperty("v", 1);
        w.addProperty("rg", "demo");
        JsonArray dims = new JsonArray(), regs = new JsonArray();
        w.add("dims", dims);
        w.add("regions", regs);
        w.add("flags", flags());
        JsonArray tiers = new JsonArray();
        for (String t : new String[]{"copper", "gold", "diamond", "netherite", "illyrium"}) {
            tiers.add(t); // BSP Core's Shatter Coin tiers (D779)
        }
        w.add("coinTiers", tiers);
        boolean pvp = server.equals("Stormfall");
        dim(dims, OW, -64, 319, pvp);
        if (!server.equals("SafeRoom")) {
            dim(dims, NE, 0, 255, pvp);
            dim(dims, EN, 0, 255, pvp);
        }
        switch (server) {
            case "Frostwarf" -> {
                dim(dims, "bsp_core:mining", -64, 319, false);
                frostwarf(regs);
            }
            case "Moongrove" -> {
                dim(dims, "twilightforest:twilight_forest", -32, 255, false);
                town(regs, OW, "grove_town", "Moongrove Town", -2300, 760, 1);
                mine(regs, OW, "amethyst_mine", "Amethyst mine", -2120, 20, 1010, "minecraft:amethyst_block", 15);
                claims(regs, OW, "grove_wilds", -1800, 600, new String[]{"Vexa", "ObsidianOtter", "Wren_88", "Ash_Tree"});
                global(regs, NE, 0, 0, 0);
            }
            case "Boulderbrook" -> {
                town(regs, OW, "brook_town", "Boulderbrook", -700, -1560, 2);
                mine(regs, OW, "coal_mine", "Coal mine", -540, 10, -1400, "minecraft:coal_ore", 25);
                claims(regs, OW, "brook_wilds", -900, -1300, new String[]{"TallyHo", "Juniper_K", "Kestrel_88"});
            }
            case "BSP-Spawn" -> {
                regs.add(region("spawn", "protected", OW, "Spawn", new long[]{-120, -64, -120, 120, 319, 120}, 10, ""));
                regs.add(region("rules_hall", "protected", OW, "Rules hall", new long[]{-20, 60, -20, 20, 100, 20}, 20, "spawn"));
                regs.add(region("portal_plaza", "protected", OW, "Portal plaza", new long[]{60, 60, -30, 110, 110, 30}, 20, "spawn"));
                regs.add(region("nether_hub", "protected", NE, "Nether hub", new long[]{-40, 30, -40, 40, 120, 40}, 10, ""));
                set(regs, "spawn", "pvp", "deny");
                set(regs, "spawn", "build", "deny");
                set(regs, "spawn", "greeting", "Welcome to BSP!");
                set(regs, "spawn", "mob-spawning", "deny");
            }
            case "Stormfall" -> {
                regs.add(region("arena_north", "protected", OW, "North arena", new long[]{1400, 40, 100, 1600, 140, 300}, 10, ""));
                regs.add(region("safe_camp", "protected", OW, "Safe camp", new long[]{1480, 60, 180, 1520, 100, 220}, 20, "arena_north"));
                set(regs, "safe_camp", "pvp", "deny");
                set(regs, "arena_north", "totem-place", "deny");
            }
            case "Sanctuary" -> {
                town(regs, OW, "haven", "Haven", 40, 80, 3);
                claims(regs, OW, "haven_fields", 200, 200, new String[]{"Ash_Tree", "Glimmer"});
            }
            default -> regs.add(region("safe_room", "protected", OW, "Safe room", new long[]{-16, 60, -16, 16, 80, 16}, 10, ""));
        }
        global(regs, OW, pvp ? 4000 : 0, 0, 0);
        if (pvp) {
            set(regs, "__global__", "pvp", "allow");
        }
        plotRules(w);
        return w;
    }

    private static void frostwarf(JsonArray regs) {
        regs.add(region("spawn_town", "protected", OW, "Frostwarf Town", new long[]{-150, -64, -110, 120, 319, 100}, 10, ""));
        regs.add(region("market", "protected", OW, "Market square", new long[]{-40, -64, -35, 30, 319, 20}, 20, "spawn_town"));
        String[] price = {"4000", "4000", "5500", "6000"};
        long[][] plots = {{-140, 60, 52, -112, 120, 92}, {-106, 60, 52, -78, 120, 92}, {-72, 60, 52, -44, 120, 92}, {50, 60, 40, 90, 120, 90}};
        for (int i = 0; i < 4; i++) {
            JsonObject p = region("plot_" + (i + 1), "plot_for_sale", OW, "Plot " + (i + 1), plots[i], 30, "spawn_town");
            JsonObject c = new JsonObject();
            c.addProperty("price", Long.parseLong(price[i]));
            c.addProperty("coinTier", "gold");
            c.addProperty("plotLimit", 1);
            p.add("claim", c);
            lists(p);
            regs.add(p);
        }
        regs.add(region("arena", "protected", OW, "PvP arena", new long[]{150, 40, -205, 235, 140, -130}, 10, ""));
        mine(regs, OW, "iron_mine", "Iron mine", 175, 12, -90, "minecraft:iron_ore", 24);
        mine(regs, OW, "copper_mine", "Copper mine", -265, 20, 120, "minecraft:copper_ore", 30);
        mine(regs, "bsp_core:mining", "deep_mine", "Deep mine", 0, -50, 0, "minecraft:deepslate_diamond_ore", 4);
        claims(regs, OW, "wilds_east", 262, -40, new String[]{"Kestrel_88", "BrickLayerTom", "Mossy_Fen"});
        regs.add(region("fortress", "protected", NE, "Nether fortress", new long[]{100, 30, -80, 220, 110, 40}, 10, ""));
        regs.add(region("end_island", "protected", EN, "Main island", new long[]{-150, 0, -150, 150, 255, 150}, 10, ""));
        set(regs, "spawn_town", "build", "deny");
        set(regs, "spawn_town", "pvp", "deny");
        set(regs, "spawn_town", "greeting", "Welcome to Frostwarf Town");
        set(regs, "spawn_town", "creeper-explosion", "deny");
        set(regs, "spawn_town", "fire-spread", "deny");
        set(regs, "market", "chest-access", "allow");
        set(regs, "market", "interact", "allow");
        JsonArray deny = new JsonArray();
        deny.add("minecraft:creeper");
        deny.add("minecraft:phantom");
        find(regs, "spawn_town").getAsJsonObject("flags").add("deny-spawn", deny);
        set(regs, "arena", "pvp", "allow");
        set(regs, "arena", "totem-place", "deny");
    }

    private static void town(JsonArray regs, String dim, String id, String name, long x, long z, int plots) {
        regs.add(region(id, "protected", dim, name, new long[]{x - 120, -64, z - 100, x + 120, 319, z + 100}, 10, ""));
        set(regs, id, "build", "deny");
        set(regs, id, "pvp", "deny");
        set(regs, id, "greeting", "Welcome to " + name);
        for (int i = 1; i <= plots; i++) {
            long px = x - 110 + i * 34;
            JsonObject p = region(id + "_plot_" + i, "plot_for_sale", dim, "Plot " + i, new long[]{px, 60, z + 40, px + 28, 120, z + 80}, 30, id);
            JsonObject c = new JsonObject();
            c.addProperty("price", 3500 + 500L * i);
            c.addProperty("coinTier", "");
            c.addProperty("plotLimit", 1);
            p.add("claim", c);
            lists(p);
            regs.add(p);
        }
    }

    private static void mine(JsonArray regs, String dim, String id, String name, long x, long y, long z, String ore, int orePercent) {
        JsonObject m = region(id, "mine", dim, name, new long[]{x, y, z, x + 47, y + 50, z + 48}, 15, "");
        JsonObject d = new JsonObject();
        JsonArray fill = new JsonArray();
        fill(fill, "minecraft:stone", 1000 - orePercent * 10 - 100);
        fill(fill, ore, orePercent * 10);
        fill(fill, "minecraft:andesite", 100);
        d.add("fill", fill);
        d.addProperty("intervalSeconds", 1200);
        d.addProperty("nextResetAt", System.currentTimeMillis() + (Math.abs(id.hashCode()) % 1200) * 1000L);
        JsonObject spawn = new JsonObject();
        spawn.addProperty("x", x + 23);
        spawn.addProperty("y", y + 51);
        spawn.addProperty("z", z - 6);
        d.add("spawn", spawn);
        d.addProperty("shell", id + "_shell");
        m.add("mine", d);
        regs.add(m);
        set(regs, id, "block-break", "allow");
        set(regs, id, "block-place", "deny");
        set(regs, id, "mine-only-break", "allow");
        set(regs, id, "pvp", "deny");
        JsonObject shell = region(id + "_shell", "protected", dim, name + " shell", new long[]{x - 4, y - 4, z - 4, x + 51, y + 54, z + 52}, 14, id);
        shell.addProperty("shellOf", id);
        regs.add(shell);
        set(regs, id + "_shell", "block-break", "deny");
    }

    private static void claims(JsonArray regs, String dim, String areaId, long x, long z, String[] owners) {
        JsonObject area = region(areaId, "open_for_buying", dim, "Open land", new long[]{x, -64, z, x + 258, 319, z + 302}, 5, "");
        JsonObject c = new JsonObject();
        c.addProperty("costPerBlock", 12);
        c.addProperty("minSize", 5);
        c.addProperty("maxSize", 64);
        c.addProperty("coinTier", "");
        c.addProperty("plotLimit", 2);
        area.add("claim", c);
        lists(area);
        regs.add(area);
        long now = System.currentTimeMillis();
        for (int i = 0; i < owners.length; i++) {
            long cx = x + 20 + (i % 3) * 80, cz = z + 30 + (i / 3) * 90;
            String id = owners[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "") + "_claim";
            JsonObject cl = region(id, "claim", dim, owners[i] + "'s claim", new long[]{cx, -64, cz, cx + 40 + i * 3, 319, cz + 36}, 40, areaId);
            JsonArray own = new JsonArray();
            own.add(person(owners[i]));
            cl.add("owners", own);
            if (i + 1 < owners.length) {
                JsonArray mem = new JsonArray();
                mem.add(person(owners[i + 1]));
                cl.add("members", mem);
            }
            JsonObject cd = new JsonObject();
            cd.addProperty("boughtFor", (41L + i * 3) * 37 * 12);
            cd.addProperty("boughtCoins", 1);
            cd.addProperty("boughtAt", now - (i + 2) * 86_400_000L * 3);
            cd.addProperty("origin", "open");
            cl.add("claim", cd);
            lists(cl);
            cl.getAsJsonObject("flags").addProperty("build", "deny");
            cl.getAsJsonObject("flags").addProperty("chest-access", "deny");
            cl.getAsJsonObject("flags").addProperty("item-pickup", "deny");
            cl.getAsJsonObject("lists").getAsJsonObject("members").addProperty("chest-access", "deny");
            cl.getAsJsonObject("flags").addProperty("greeting", owners[i] + "'s claim");
            regs.add(cl);
        }
    }

    private static void global(JsonArray regs, String dim, long size, long x, long z) {
        JsonObject g = region("__global__", "global", dim, "", null, Integer.MIN_VALUE, "");
        JsonObject b = new JsonObject();
        b.addProperty("size", size);
        b.addProperty("x", x);
        b.addProperty("z", z);
        b.addProperty("plotsInPvp", "default");
        g.add("border", b);
        regs.add(g);
        g.getAsJsonObject("flags").addProperty("creeper-explosion", "deny");
    }

    private static JsonObject region(String id, String type, String dim, String name, long[] box, int priority, String parent) {
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("dim", dim);
        r.addProperty("name", name);
        r.addProperty("type", type);
        if (box != null) {
            JsonArray b = new JsonArray();
            for (long v : box) {
                b.add(v);
            }
            r.add("box", b);
        }
        r.addProperty("priority", priority);
        r.addProperty("parent", parent);
        r.add("owners", new JsonArray());
        r.add("members", new JsonArray());
        r.add("flags", new JsonObject());
        r.addProperty("version", 3 + Math.abs(id.hashCode()) % 20);
        r.addProperty("updatedAt", System.currentTimeMillis() - (Math.abs(id.hashCode()) % 40) * 86_400_000L);
        return r;
    }

    private static void lists(JsonObject r) {
        JsonObject l = new JsonObject();
        l.add("members", new JsonObject());
        l.add("owners", new JsonObject());
        r.add("lists", l);
    }

    /** Each dimension's plotsAllowed and plotRule from its PvP and its global region's plots-in-PvP setting, as Region Guard's WorldView. */
    private static void plotRules(JsonObject world) {
        for (JsonElement e : world.getAsJsonArray("dims")) {
            JsonObject d = e.getAsJsonObject();
            JsonObject g = find(world, d.get("id").getAsString(), "__global__");
            String pip = g != null && g.has("border") ? g.getAsJsonObject("border").get("plotsInPvp").getAsString() : "default";
            boolean pvp = d.get("pvp").getAsBoolean();
            String rule = !pvp ? "not_pvp" : pip.equals("allow") ? "world_allows" : pip.equals("deny") ? "world_refuses" : "pvp_denied";
            d.addProperty("plotsAllowed", !pvp || pip.equals("allow"));
            d.addProperty("plotRule", rule);
        }
    }

    private static void dim(JsonArray dims, String id, int minY, int maxY, boolean pvp) {
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("minY", minY);
        d.addProperty("maxY", maxY);
        d.addProperty("pvp", pvp);
        d.addProperty("plotsAllowed", !pvp); // as Region Guard's config says by default (D778)
        d.addProperty("plotRule", pvp ? "pvp_denied" : "not_pvp");
        dims.add(d);
    }

    private static void fill(JsonArray fill, String block, int weight) {
        JsonObject o = new JsonObject();
        o.addProperty("block", block);
        o.addProperty("weight", weight);
        fill.add(o);
    }

    private static JsonObject person(String name) {
        JsonObject p = new JsonObject();
        p.addProperty("id", UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString());
        p.addProperty("name", name);
        return p;
    }

    private static void set(JsonArray regs, String id, String flag, String value) {
        find(regs, id).getAsJsonObject("flags").addProperty(flag, value);
    }

    private static JsonObject find(JsonArray regs, String id) {
        for (JsonElement e : regs) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
                return e.getAsJsonObject();
            }
        }
        throw new IllegalArgumentException(id);
    }

    private static JsonObject find(JsonObject world, String dim, String id) {
        for (JsonElement e : world.getAsJsonArray("regions")) {
            JsonObject r = e.getAsJsonObject();
            if (r.get("id").getAsString().equals(id) && r.get("dim").getAsString().equals(dim)) {
                return r;
            }
        }
        return null;
    }

    static synchronized JsonArray flags() {
        if (flags == null) {
            try (InputStreamReader in = new InputStreamReader(DemoRegions.class.getResourceAsStream("/demo/region-flags.json"), StandardCharsets.UTF_8)) {
                flags = JsonParser.parseReader(in).getAsJsonArray();
            } catch (Exception e) {
                flags = new JsonArray();
            }
        }
        return flags;
    }

    // ------------------------------------------------------------------ changes, as Region Guard would make them

    private static JsonObject save(JsonObject world, JsonObject act) {
        String dim = act.get("dim").getAsString(), id = act.get("region").getAsString();
        JsonObject r = find(world, dim, id);
        if (r == null) {
            return done(false, "There is no region " + id + " in that world any more.", null);
        }
        if (r.get("version").getAsLong() != act.get("version").getAsLong()) {
            return done(false, id + " was changed in game since this page read it (now version " + r.get("version").getAsLong() + "). Nothing was saved: the page shows it as it is now.", stale());
        }
        JsonObject set = act.getAsJsonObject("set");
        if (set.has("newId")) {
            String n = set.get("newId").getAsString();
            if (!n.equals(id) && find(world, dim, n) != null) {
                return done(false, "There is already a region " + n + " in that world.", null);
            }
        }
        if (set.has("parent")) {
            String p = set.get("parent").getAsString();
            for (String up = p; !up.isEmpty(); ) {
                if (up.equals(id)) {
                    return done(false, "That would make " + id + " its own parent.", null);
                }
                JsonObject u = find(world, dim, up);
                if (u == null) {
                    return done(false, "There is no region " + up + " in that world.", null);
                }
                up = u.get("parent").getAsString();
            }
            r.addProperty("parent", p);
        }
        if (set.has("name")) {
            r.addProperty("name", set.get("name").getAsString());
        }
        if (set.has("priority")) {
            r.addProperty("priority", set.get("priority").getAsLong());
        }
        if (set.has("type")) {
            r.addProperty("type", set.get("type").getAsString());
            if (!r.has("lists") && !set.get("type").getAsString().equals("protected")) {
                lists(r);
            }
        }
        if (set.has("box")) {
            r.add("box", set.get("box"));
        }
        if (set.has("flags")) {
            for (Map.Entry<String, JsonElement> e : set.getAsJsonObject("flags").entrySet()) {
                if (e.getValue().isJsonNull()) {
                    r.getAsJsonObject("flags").remove(e.getKey());
                } else {
                    r.getAsJsonObject("flags").add(e.getKey(), e.getValue());
                }
            }
        }
        if (set.has("lists") && r.has("lists")) {
            for (Map.Entry<String, JsonElement> l : set.getAsJsonObject("lists").entrySet()) {
                JsonObject to = r.getAsJsonObject("lists").getAsJsonObject(l.getKey());
                for (Map.Entry<String, JsonElement> e : l.getValue().getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonNull()) {
                        to.remove(e.getKey());
                    } else {
                        to.add(e.getKey(), e.getValue());
                    }
                }
            }
        }
        for (String who : new String[]{"owners", "members"}) {
            if (!set.has(who)) {
                continue;
            }
            JsonArray now = r.getAsJsonArray(who), kept = new JsonArray();
            java.util.Set<String> gone = new java.util.HashSet<>();
            set.getAsJsonObject(who).getAsJsonArray("remove").forEach(e -> gone.add(e.getAsString()));
            now.forEach(e -> {
                if (!gone.contains(e.getAsJsonObject().get("id").getAsString())) {
                    kept.add(e);
                }
            });
            for (JsonElement e : set.getAsJsonObject(who).getAsJsonArray("add")) {
                JsonObject p = person(e.getAsString());
                boolean there = false;
                for (JsonElement k : kept) {
                    there |= k.getAsJsonObject().get("id").getAsString().equals(p.get("id").getAsString());
                }
                if (!there) {
                    kept.add(p);
                }
            }
            r.add(who, kept);
        }
        for (String part : new String[]{"claim", "mine", "border"}) {
            if (set.has(part)) {
                JsonObject to = r.has(part) ? r.getAsJsonObject(part) : new JsonObject();
                set.getAsJsonObject(part).entrySet().forEach(e -> to.add(e.getKey(), e.getKey().equals("coinTier") && e.getValue().getAsString().equals("default") ? new com.google.gson.JsonPrimitive("") : e.getValue()));
                r.add(part, to);
            }
        }
        if (set.has("newId")) {
            String n = set.get("newId").getAsString();
            world.getAsJsonArray("regions").forEach(e -> {
                JsonObject o = e.getAsJsonObject();
                if (o.get("dim").getAsString().equals(dim) && o.get("parent").getAsString().equals(id)) {
                    o.addProperty("parent", n);
                }
                if (o.get("dim").getAsString().equals(dim) && o.has("shellOf") && o.get("shellOf").getAsString().equals(id)) {
                    o.addProperty("shellOf", n); // a mine's shell follows its mine's new id
                }
            });
            r.addProperty("id", n);
        }
        r.addProperty("version", r.get("version").getAsLong() + 1);
        r.addProperty("updatedAt", System.currentTimeMillis());
        JsonObject data = new JsonObject();
        data.addProperty("region", r.get("id").getAsString());
        data.addProperty("version", r.get("version").getAsLong());
        JsonArray saved = new JsonArray();
        set.keySet().forEach(saved::add); // as the game servers say what was saved (D783); the demo saves all or nothing
        data.add("saved", saved);
        data.add("notSaved", new JsonArray());
        plotRules(world);
        return done(true, "Saved (demo: nothing was sent to a game server).", data);
    }

    private static JsonObject remove(JsonObject world, JsonObject act) {
        String dim = act.get("dim").getAsString(), id = act.get("region").getAsString();
        JsonObject r = find(world, dim, id);
        if (r == null) {
            return done(false, "There is no region " + id + " in that world any more.", null);
        }
        if (r.get("version").getAsLong() != act.get("version").getAsLong()) {
            return done(false, id + " was changed in game since this page read it. Nothing was removed: the page shows it as it is now.", stale());
        }
        if (r.get("type").getAsString().equals("claim") && !"OWNER".equals(act.get("rank").getAsString())) {
            return done(false, "Removing a player's claim is for Owners.", null);
        }
        JsonArray regs = world.getAsJsonArray("regions"), kept = new JsonArray();
        String shell = r.has("mine") && r.getAsJsonObject("mine").has("shell") ? r.getAsJsonObject("mine").get("shell").getAsString() : "";
        regs.forEach(e -> {
            JsonObject o = e.getAsJsonObject();
            boolean same = o.get("dim").getAsString().equals(dim);
            if (same && (o.get("id").getAsString().equals(id) || o.get("id").getAsString().equals(shell))) {
                return;
            }
            if (same && o.get("parent").getAsString().equals(id)) {
                o.addProperty("parent", "");
            }
            kept.add(o);
        });
        world.add("regions", kept);
        return done(true, "Removed " + id + (shell.isEmpty() ? "" : " and its shell") + " (demo: nothing was sent to a game server).", null);
    }

    private static JsonObject reset(JsonObject world, JsonObject act) {
        JsonObject r = find(world, act.get("dim").getAsString(), act.get("region").getAsString());
        if (r == null || !r.has("mine")) {
            return done(false, "There is no such mine.", null);
        }
        JsonObject m = r.getAsJsonObject("mine");
        m.addProperty("nextResetAt", System.currentTimeMillis() + m.get("intervalSeconds").getAsLong() * 1000L);
        return done(true, "Filled again; players inside were moved out (demo: nothing was sent to a game server).", null);
    }

    /** Region Guard's checks, as its SU-RG5 dry run would make them: the id, the world's heights, plots in PvP worlds, plots over plots. */
    private static JsonObject check(JsonObject world, JsonObject act, String server) {
        String dim = act.get("dim").getAsString(), id = act.get("region").getAsString(), type = act.get("type").getAsString();
        JsonArray box = act.getAsJsonArray("box");
        long x1 = box.get(0).getAsLong(), y1 = box.get(1).getAsLong(), z1 = box.get(2).getAsLong(), x2 = box.get(3).getAsLong(), y2 = box.get(4).getAsLong(), z2 = box.get(5).getAsLong();
        if (find(world, dim, id) != null) {
            return done(false, "There is already a region " + id + " in this world.", null);
        }
        for (JsonElement e : world.getAsJsonArray("dims")) {
            JsonObject d = e.getAsJsonObject();
            if (d.get("id").getAsString().equals(dim) && (y1 < d.get("minY").getAsLong() || y2 > d.get("maxY").getAsLong())) {
                return done(false, "It goes beyond the world's height (Y " + d.get("minY").getAsLong() + " to " + d.get("maxY").getAsLong() + ").", null);
            }
            if (d.get("id").getAsString().equals(dim) && !d.get("plotsAllowed").getAsBoolean() && (type.equals("plot_for_sale") || type.equals("open_for_buying"))) {
                return done(false, "Plots and open areas are not allowed in this world: PvP is on.", null);
            }
        }
        if (type.equals("plot_for_sale")) {
            for (JsonElement e : world.getAsJsonArray("regions")) {
                JsonObject r = e.getAsJsonObject();
                if (!r.get("dim").getAsString().equals(dim) || !r.get("type").getAsString().equals("plot_for_sale") || !r.has("box")) {
                    continue;
                }
                JsonArray b = r.getAsJsonArray("box");
                boolean overlap = x1 <= b.get(3).getAsLong() && b.get(0).getAsLong() <= x2 && z1 <= b.get(5).getAsLong() && b.get(2).getAsLong() <= z2;
                if (overlap) {
                    return done(false, "Part of it lies over " + r.get("id").getAsString() + " (" + r.get("name").getAsString() + ").", null);
                }
            }
        }
        return done(true, "Fine: nothing in the way.", null);
    }

    private static JsonObject create(JsonObject world, JsonObject act, String server) {
        JsonObject c = check(world, act, server);
        if (!c.get("ok").getAsBoolean()) {
            return c;
        }
        String dim = act.get("dim").getAsString(), id = act.get("region").getAsString(), type = act.get("type").getAsString();
        JsonArray box = act.getAsJsonArray("box");
        long[] b = new long[6];
        for (int i = 0; i < 6; i++) {
            b[i] = box.get(i).getAsLong();
        }
        JsonObject r = region(id, type, dim, act.has("name") ? act.get("name").getAsString() : id, b, act.get("priority").getAsInt(), act.get("parent").getAsString());
        r.addProperty("version", 1);
        r.addProperty("updatedAt", System.currentTimeMillis());
        if (act.has("claim")) {
            r.add("claim", act.get("claim"));
        }
        if (!type.equals("protected") && !type.equals("mine")) {
            lists(r);
        }
        if (type.equals("mine") && (id.length() > 26 || find(world, dim, id + "_shell") != null)) {
            return done(false, "Its shell's id, " + id + "_shell, is too long or taken: choose another id.", null);
        }
        if (type.equals("mine")) {
            JsonObject m = act.has("mine") ? act.getAsJsonObject("mine").deepCopy() : new JsonObject();
            if (!m.has("fill")) {
                JsonArray fill = new JsonArray();
                fill(fill, "minecraft:stone", 1000);
                m.add("fill", fill);
            }
            if (!m.has("intervalSeconds")) {
                m.addProperty("intervalSeconds", 1200);
            }
            m.addProperty("nextResetAt", System.currentTimeMillis() + m.get("intervalSeconds").getAsLong() * 1000L);
            m.addProperty("shell", id + "_shell");
            r.add("mine", m);
            JsonObject shell = region(id + "_shell", "protected", dim, r.get("name").getAsString() + " shell", new long[]{b[0] - 4, b[1] - 4, b[2] - 4, b[3] + 4, b[4] + 4, b[5] + 4},
                    act.get("priority").getAsInt() - 1, id);
            shell.addProperty("shellOf", id);
            world.getAsJsonArray("regions").add(shell);
        }
        world.getAsJsonArray("regions").add(r);
        JsonObject data = new JsonObject();
        data.addProperty("region", id);
        data.addProperty("version", 1);
        return done(true, "Created " + id + " (demo: nothing was sent to a game server).", data);
    }

    private static JsonObject stale() {
        JsonObject d = new JsonObject();
        d.addProperty("stale", true);
        return d;
    }

    private static JsonObject done(boolean ok, String message, JsonObject data) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("message", message);
        o.add("data", data == null ? JsonNull.INSTANCE : data);
        return o;
    }
}
