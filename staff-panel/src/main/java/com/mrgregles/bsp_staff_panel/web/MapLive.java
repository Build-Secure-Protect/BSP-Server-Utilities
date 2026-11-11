package com.mrgregles.bsp_staff_panel.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.function.Function;

/**
 * The map's live data for BlueMap's page (SU-46; D259, D386): who is where ({@code players.json}) and the pins
 * ({@code markers.json}), made by the panel from its own feed and BSP-Teleport's places, so no game server runs BlueMap.
 *
 * <p>A map's id is {@code <server>__<dimension>} ({@link #mapId}): the server's id in small letters with every run of
 * other characters as {@code _}, two underscores, then the dimension as {@code overworld}, {@code nether}, {@code end} or
 * {@code <namespace>_<path>}. Only letters, digits and {@code _}: BlueMap turns anything else in a map file's name into
 * {@code _} (seen in SU-49). BlueMap's map files ({@code tools/gen_bluemap.py}) are named the same way. Every online player is listed on every
 * map, {@code foreign} unless the map is their server and dimension: BlueMap's page shows only the others, and can find
 * a followed player on another map.
 */
public final class MapLive {
    private MapLive() {
    }

    /** {@code frostwarf__overworld}, {@code bsp_spawn__nether}, {@code frostwarf__allthemodium_mining}. */
    public static String mapId(String server, String dimension) {
        return slug(server) + "__" + dimensionSlug(dimension);
    }

    static String dimensionSlug(String dimension) {
        String d = dimension == null ? "" : dimension.toLowerCase(Locale.ROOT);
        return switch (d) {
            case "minecraft:overworld", "overworld" -> "overworld";
            case "minecraft:the_nether", "the_nether" -> "nether";
            case "minecraft:the_end", "the_end" -> "end";
            default -> slug(d);
        };
    }

    private static String slug(String s) {
        String t = (s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return t.isEmpty() ? "unknown" : t;
    }

    /**
     * BlueMap's {@code players.json} for one map from the feed's players ({@code id}, {@code name}, {@code server},
     * {@code dim}, {@code x}, {@code y}, {@code z}, {@code yaw}): position at the feet, the game's own yaw.
     */
    public static JsonObject players(JsonArray feedPlayers, String mapId) {
        JsonArray out = new JsonArray();
        for (JsonElement e : feedPlayers) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject p = e.getAsJsonObject();
            String id = str(p, "id"), name = str(p, "name");
            if (!id.matches("[0-9a-fA-F-]{36}") || !name.matches("\\.?[A-Za-z0-9_]{1,16}")) {
                continue; // BlueMap's page puts the name in as markup: only names that are plain (Java, or Bedrock's "." first)
            }
            JsonObject o = new JsonObject();
            o.addProperty("uuid", id.toLowerCase(Locale.ROOT));
            o.addProperty("name", name);
            o.addProperty("foreign", !mapId(str(p, "server"), str(p, "dim")).equals(mapId));
            JsonObject pos = new JsonObject();
            pos.addProperty("x", num(p, "x"));
            pos.addProperty("y", num(p, "y"));
            pos.addProperty("z", num(p, "z"));
            o.add("position", pos);
            JsonObject rot = new JsonObject();
            rot.addProperty("pitch", 0);
            rot.addProperty("yaw", num(p, "yaw"));
            rot.addProperty("roll", 0);
            o.add("rotation", rot);
            out.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("players", out);
        return root;
    }

    /**
     * BlueMap's {@code markers.json} for one map: the warps (portal warps in their own set, hidden at first) and the
     * servers' spawns there, from the panel's places ({@code tp_places}: warps by BSP-Teleport's server names, spawns by
     * the panel's). {@code toPanel} turns a BSP-Teleport name into the panel's server id. Labels are escaped: BlueMap's
     * page puts them in as markup.
     */
    public static JsonObject markers(JsonObject places, Function<String, String> toPanel, String mapId) {
        JsonObject warps = set("Warps", 10, false), portals = set("Portal warps", 11, true), spawns = set("Spawns", 12, false);
        if (places != null) {
            if (places.has("warps") && places.get("warps").isJsonArray()) {
                int i = 0;
                for (JsonElement e : places.getAsJsonArray("warps")) {
                    i++;
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject w = e.getAsJsonObject();
                    String server = toPanel.apply(str(w, "server"));
                    if (!mapId(server, str(w, "world")).equals(mapId)) {
                        continue;
                    }
                    boolean portal = bool(w, "portal"), restricted = bool(w, "restricted"); // a null or odd field is false, never every pin lost (audit)
                    String name = str(w, "name");
                    (portal ? portals : warps).getAsJsonObject("markers").add("warp-" + i + "-" + slug(name), poi(w, name, // one key each (audit)
                            "<b>" + escape(name) + "</b><br>" + escape(server) + (portal ? " · portal warp" : "") + (restricted ? " · restricted" : ""), "/static/map-warp.svg"));
                }
            }
            if (places.has("spawns") && places.get("spawns").isJsonArray()) {
                for (JsonElement e : places.getAsJsonArray("spawns")) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject s = e.getAsJsonObject();
                    String server = str(s, "server");
                    if (mapId(server, str(s, "world")).equals(mapId)) {
                        spawns.getAsJsonObject("markers").add("spawn", poi(s, server + " spawn", "<b>" + escape(server) + " spawn</b><br>the /setspawn point", "/static/map-spawn.svg"));
                    }
                }
            }
        }
        JsonObject out = new JsonObject();
        out.add("bsp-warps", warps);
        out.add("bsp-portal-warps", portals);
        out.add("bsp-spawns", spawns);
        return out;
    }

    private static JsonObject set(String label, int sorting, boolean hidden) {
        JsonObject s = new JsonObject();
        s.addProperty("label", label);
        s.addProperty("toggleable", true);
        s.addProperty("defaultHidden", hidden);
        s.addProperty("sorting", sorting);
        s.add("markers", new JsonObject());
        return s;
    }

    private static JsonObject poi(JsonObject at, String label, String detail, String icon) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "poi");
        JsonObject pos = new JsonObject();
        pos.addProperty("x", num(at, "x"));
        pos.addProperty("y", num(at, "y"));
        pos.addProperty("z", num(at, "z"));
        m.add("position", pos);
        m.addProperty("label", label); // shown as text in BlueMap's list; only the detail goes in as markup, so only it is escaped (audit)
        m.addProperty("detail", detail);
        m.addProperty("icon", icon);
        JsonArray classes = new JsonArray(); // BlueMap's page needs the list, even empty (it spreads it)
        classes.add("bsp-pin");
        classes.add(icon.contains("spawn") ? "bsp-spawn" : "bsp-warp");
        m.add("classes", classes);
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", 12);
        anchor.addProperty("y", 30);
        m.add("anchor", anchor);
        return m;
    }

    /** Text made safe to be put in as markup. */
    public static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        try {
            return o.get(k).getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
