package com.mrgregles.bsp_staff_panel.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Which maps the Map tab shows (SU-83; D570 to D586): an Owner hides or shows each map BlueMap has drawn, for every staff
 * member alike. A hidden map is left out of the map list the panel serves ({@link #filterSettings}) and its files are
 * refused ({@link #hiddenPath}), so it is not reachable by its address either. A map nobody chose is shown (D573). Every
 * change is one line in the panel's log with who and when (D586); the pages hear of it through the live feed (D583).
 */
public final class MapShown {
    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    /** A map id as {@link MapLive#mapId} makes it. */
    static final Pattern MAP_ID = Pattern.compile("[a-z0-9_]{1,80}__[a-z0-9_]{1,80}");
    private static final Pattern MAP_PATH = Pattern.compile("maps/([^/]+)(/.*)?");
    /** The picker's order (its {@code localeCompare}): case and punctuation set aside, so "Boulderbrook" comes before "BSP-Spawn". */
    private static final java.text.Collator PICKER_ORDER = java.text.Collator.getInstance(java.util.Locale.ENGLISH);
    /** At most this many maps in one change (a server's ALL or NONE; the whole network has far fewer). */
    static final int MAX_CHANGE = 64;

    private final PanelStore store;
    private final LongSupplier clock;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, PanelStore.MapChoice> choices; // null until read: read again on the next ask

    public MapShown(PanelStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Told after every saved change (the panel sends a {@code maps} event to every open page). */
    public void onChange(Runnable l) {
        listeners.add(l);
    }

    /** A database that did not answer is asked again after this long, not on every tile (audit). */
    public static final long RETRY_READ_MS = 30_000L;
    private long readFailedAt = Long.MIN_VALUE;

    /** The choices as kept; read from the database the first time. A failed read: every map is shown until it answers. */
    private Map<String, PanelStore.MapChoice> choices() {
        Map<String, PanelStore.MapChoice> c = choices;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            try {
                return loaded();
            } catch (Exception e) {
                return Map.of(); // staff only, as before 0.4.2
            }
        }
    }

    /** Under the lock: the choices, read now if not yet; throws when the database does not answer (or did not, lately). */
    private Map<String, PanelStore.MapChoice> loaded() throws Exception {
        if (choices != null) {
            return choices;
        }
        long now = clock.getAsLong();
        if (readFailedAt != Long.MIN_VALUE && now - readFailedAt < RETRY_READ_MS) {
            throw new IllegalStateException("the panel's database did not answer lately");
        }
        try {
            Map<String, PanelStore.MapChoice> read = new HashMap<>();
            store.mapChoices().forEach(m -> read.put(m.map(), m));
            choices = read;
            readFailedAt = Long.MIN_VALUE;
            return read;
        } catch (Exception e) {
            readFailedAt = now;
            throw e;
        }
    }

    /** The maps hidden now. */
    public Set<String> hidden() {
        Set<String> out = new LinkedHashSet<>();
        choices().values().forEach(m -> {
            if (m.hidden()) {
                out.add(m.map());
            }
        });
        return out;
    }

    /** BlueMap's {@code settings.json} with the hidden maps taken off its {@code maps} list and their number as {@code bspHidden}; everything else as it is. */
    public static JsonObject filterSettings(JsonObject settings, Set<String> hidden) {
        JsonObject out = settings.deepCopy();
        int left = 0;
        if (out.has("maps") && out.get("maps").isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (JsonElement e : out.getAsJsonArray("maps")) {
                if (e.isJsonPrimitive() && hidden.contains(e.getAsString())) {
                    left++;
                } else {
                    kept.add(e);
                }
            }
            out.add("maps", kept);
        }
        out.addProperty("bspHidden", left); // the Map tab tells "every map hidden" from "none drawn yet" (D585); BlueMap's page ignores it
        return out;
    }

    /** Whether a path under {@code /map/} is one of a hidden map's files ({@code maps/<id>/...}): refused like a missing file. */
    public static boolean hiddenPath(String path, Set<String> hidden) {
        java.util.regex.Matcher m = MAP_PATH.matcher(path.toLowerCase(java.util.Locale.ROOT)); // a host whose files ignore case (audit)
        return m.matches() && hidden.contains(m.group(1));
    }

    // ------------------------------------------------------------------ names, as the Map tab shows them

    static String serverOf(String id) {
        return id.substring(0, id.indexOf("__"));
    }

    static String dimOf(String id) {
        return id.substring(id.indexOf("__") + 2);
    }

    /** As the page's {@code dimLabel}: Overworld, Nether, End, else the path without its namespace ("allthemodium_mining": "Mining"). */
    static String dimLabel(String slug) {
        return switch (slug) {
            case "overworld" -> "Overworld";
            case "nether" -> "Nether";
            case "end" -> "End";
            default -> {
                String w = slug.replaceFirst("^[a-z0-9]+_", "").replace('_', ' ');
                yield w.isEmpty() ? slug : Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
        };
    }

    /** A server's id made readable when no server by it is sending now ("bsp_spawn": "Bsp Spawn"), as the page does. */
    public static String readable(String serverSlug) {
        StringBuilder b = new StringBuilder();
        for (String w : serverSlug.split("_")) {
            if (!w.isEmpty()) {
                b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return b.toString();
    }

    private static String rank(String dim) {
        return dim.equals("overworld") ? "0" : dim.equals("nether") ? "1" : dim.equals("end") ? "2" : "3" + dim;
    }

    /** "BSP-Sanctuary's End". */
    static String label(String id, Function<String, String> serverName) {
        return serverName.apply(serverOf(id)) + "'s " + dimLabel(dimOf(id));
    }

    // ------------------------------------------------------------------ the Owner's sheet

    private static void owner(Account me) throws Refused {
        if (me.rank != Rank.OWNER) {
            throw new Refused("Only an Owner chooses which maps are shown.");
        }
    }

    /**
     * The MAPS sheet (D581): {@code servers} in the picker's order, each {@code {id, name, maps: [{id, label, shown}]}} with
     * its maps in the keys' order; {@code last} the newest change {@code {name, at}} or null.
     */
    public JsonObject sheet(Account me, List<String> drawn, Function<String, String> serverName) throws Refused {
        owner(me);
        Set<String> hid = hidden();
        Map<String, List<String>> byServer = new LinkedHashMap<>();
        drawn.stream().filter(m -> MAP_ID.matcher(m).matches()).distinct()
                .sorted(Comparator.comparing((String m) -> serverName.apply(serverOf(m)), PICKER_ORDER).thenComparing(m -> rank(dimOf(m))))
                .forEach(m -> byServer.computeIfAbsent(serverOf(m), k -> new ArrayList<>()).add(m));
        JsonArray servers = new JsonArray();
        byServer.forEach((s, ms) -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", s);
            o.addProperty("name", serverName.apply(s));
            JsonArray maps = new JsonArray();
            for (String m : ms) {
                JsonObject x = new JsonObject();
                x.addProperty("id", m);
                x.addProperty("label", dimLabel(dimOf(m)));
                x.addProperty("shown", !hid.contains(m));
                maps.add(x);
            }
            o.add("maps", maps);
            servers.add(o);
        });
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.add("servers", servers);
        out.add("last", last());
        return out;
    }

    private JsonObject last() {
        return choices().values().stream().max(Comparator.comparingLong(PanelStore.MapChoice::at)).map(m -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", m.byName());
            o.addProperty("at", m.at());
            return o;
        }).orElse(null);
    }

    /**
     * Hides ({@code show} false) or shows these maps, all saved together (D582). {@code how} is "tap", "all", "none" or
     * "undo", for the log. Each map must be one BlueMap has drawn ({@code drawn}). The answer carries {@code saved} (the
     * change in words, for "Saved: …"), {@code changed} (the maps whose state changed) and {@code last}. A refusal or a
     * failed save leaves everything as it was and is in the log too.
     */
    public synchronized JsonObject set(Account me, List<String> drawn, List<String> ids, boolean show, String how, Function<String, String> serverName) throws Refused {
        String who = me.email;
        try {
            owner(me);
        } catch (Refused r) {
            log(who, "tried to change the maps shown on the Map tab; refused: not an Owner");
            throw r;
        }
        if (ids == null || ids.isEmpty()) {
            throw new Refused("Choose a map.");
        }
        if (ids.size() > MAX_CHANGE) {
            throw new Refused("Too many maps in one change.");
        }
        Set<String> want = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || !MAP_ID.matcher(id).matches() || !drawn.contains(id)) {
                throw new Refused("No such map: BlueMap has not drawn it (it may have been removed). Open MAPS again.");
            }
            want.add(id);
        }
        String kind = switch (how == null ? "" : how) {
            case "all" -> " (ALL)";
            case "none" -> " (NONE)";
            case "undo" -> " (UNDO)";
            default -> "";
        };
        String what = describe(new ArrayList<>(want), drawn, serverName);
        Map<String, PanelStore.MapChoice> base;
        try {
            base = loaded(); // under the lock: two saves at once never lose one another (audit)
        } catch (Exception e) {
            log(who, "tried to " + (show ? "show " : "hide ") + what + " on the Map tab" + kind + "; not saved: " + e.getMessage());
            throw new Refused("Not saved: the panel's database did not answer. Try again.");
        }
        List<String> changed = want.stream().filter(m -> (base.containsKey(m) && base.get(m).hidden()) == show).toList(); // hidden now, to show; shown now, to hide
        long now = clock.getAsLong();
        String name = me.mcName == null || me.mcName.isBlank() ? me.email : me.mcName;
        String byName = name.length() > 32 ? name.substring(0, 32) : name;
        // only the maps whose state changes: a tap on a map already so changes nothing, not even "Last change" (audit)
        List<PanelStore.MapChoice> rows = changed.stream().map(m -> new PanelStore.MapChoice(m, !show, byName, now)).toList();
        if (!rows.isEmpty()) {
            try {
                store.putMapChoices(rows);
            } catch (Exception e) {
                log(who, "tried to " + (show ? "show " : "hide ") + what + " on the Map tab" + kind + "; not saved: " + e.getMessage());
                throw new Refused("Not saved: the panel's database did not answer. Try again.");
            }
            Map<String, PanelStore.MapChoice> next = new HashMap<>(base);
            rows.forEach(r -> next.put(r.map(), r));
            choices = next;
        }
        if (!changed.isEmpty()) {
            log(who, (show ? "showed " : "hid ") + what + " on the Map tab" + kind);
            listeners.forEach(Runnable::run);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("saved", changed.isEmpty() ? "nothing to change" : what + (show ? " shown" : " hidden"));
        JsonArray ch = new JsonArray();
        changed.forEach(ch::add);
        out.add("changed", ch);
        out.add("last", last());
        return out;
    }

    /** "BSP-Spawn's Nether"; "every map of BSP-SafeRoom" (a whole server); else the maps' names joined. */
    static String describe(List<String> ids, List<String> drawn, Function<String, String> serverName) {
        if (ids.size() == 1) {
            return label(ids.get(0), serverName);
        }
        Set<String> servers = new LinkedHashSet<>();
        ids.forEach(m -> servers.add(serverOf(m)));
        if (servers.size() == 1) {
            String s = servers.iterator().next();
            if (drawn.stream().filter(m -> MAP_ID.matcher(m).matches() && serverOf(m).equals(s)).allMatch(ids::contains)) {
                return "every map of " + serverName.apply(s);
            }
        }
        List<String> names = ids.stream().map(m -> label(m, serverName)).toList();
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private void log(String who, String what) {
        try {
            store.log(new PanelStore.LogLine(clock.getAsLong(), who, what));
        } catch (Exception ignored) {
            // the log is in the same database: when it does not answer, the panel's console has the error from the save
        }
    }
}
