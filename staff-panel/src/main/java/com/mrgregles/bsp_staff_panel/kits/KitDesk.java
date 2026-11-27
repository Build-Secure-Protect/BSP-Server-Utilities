package com.mrgregles.bsp_staff_panel.kits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The KITS tab (SU-172 to SU-174; D838 to D867, mock-up set BM), through the game servers' {@code kit_*} actions answered by
 * BSP-Core's {@code BspKits} (requests SU-BC11 to SU-BC13; the mod's side is SU-171, {@code docs/API.md} "Kits").
 * <ul>
 * <li>The kits are one record for the network, so the list ({@code kit_list}, every kit whole) and the pickers
 * ({@code kit_choices}) are asked of one live server that answers for them: kits switched on there first (D863), its
 * database connected, then the busiest; kept {@link #FRESH_MS} for every staff member. A server's {@code kitsAt} moving
 * drops them (D845), except once just after the panel's own change, which was read again at once.</li>
 * <li>Every rank reads (D839); every change is for Admins and Owners, checked here before anything is signed, with the
 * stamp the kit was read at, and is a line in the panel's log (D844).</li>
 * <li>A gift goes through the server the player is on when kits are on there, else the answering server (D864). A
 * player's records ({@code kit_records}) are asked fresh each time.</li>
 * </ul>
 */
public final class KitDesk {
    public static final long FRESH_MS = 60_000L;
    static final long RETRY_MS = 20_000L, OWN_MS = 15_000L;
    public static final String NO_SERVER = "No game server with BSP-Core's kits is sending to the panel right now.";
    /** Servers sending, but none has finished reading the network's kits (D862). */
    public static final String WAITING = "The game servers are still reading the network's kits after a restart, or cannot reach the database: the tab tries again within a minute.";
    public static final String ADMINS = "Changing kits is for Admins and Owners.";
    static final Pattern ID = Pattern.compile("[a-z0-9_]{2,24}");
    static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    static final Pattern LIST_ITEM = Pattern.compile("[A-Za-z0-9_.+-]{1,64}");
    static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    public static final int NAME_MAX = 24, DESCRIPTION_MAX = 80, LIST_MAX = 512, SNBT_MAX = 32_768, ICON_SNBT_MAX = 4_096, COUNT_MAX = 9_999;
    public static final long NUMBER_MAX = 1_000_000_000_000L;
    static final Set<String> GRANTS = Set.of("NONE", "NETWORK", "SERVERS");
    /** The words each setting has in the panel's log. */
    static final Map<String, String> FIELD_WORDS = Map.ofEntries(Map.entry("name", "the name"), Map.entry("description", "the description"),
            Map.entry("inBrowser", "shown in the browser"), Map.entry("once", "once only"), Map.entry("cooldownSeconds", "the wait"),
            Map.entry("price", "the price"), Map.entry("ranks", "the ranks"), Map.entry("servers", "the servers"),
            Map.entry("conditions", "what it needs"), Map.entry("grant", "first join"), Map.entry("grantServers", "the first-join servers"));

    private record Read(long at, String server, JsonObject data) {
    }

    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;
    private volatile Read list, listFailed, choices;
    private volatile long gen;
    private final Map<String, Long> kitsSeen = new ConcurrentHashMap<>(), ownUntil = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public KitDesk(Feed feed, Supplier<Actions> actions, LongSupplier clock) {
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
        feed.onChange(this::heard);
    }

    private void heard() {
        for (String s : feed.live()) {
            long at = feed.kitsAt(s);
            Long was = at < 0 ? null : kitsSeen.put(s, at);
            if (was != null && was != at) {
                Long own = ownUntil.remove(s);
                if (own == null || own < clock.getAsLong()) {
                    drop();
                }
            }
        }
    }

    /** The kits kept, and the failures kept, dropped; a read in flight that began before is not kept. The pickers stay (a kit change never moves them). */
    public void drop() {
        synchronized (lock) {
            gen++;
            list = null;
            listFailed = null;
        }
    }

    // ------------------------------------------------------------------ reads (every rank, D839)

    /**
     * The grid (K1): {@code ok}, {@code message}, {@code server} (which answered), {@code at}, {@code version}, {@code kits}
     * (every kit whole, in the kit browser's order), {@code choices} (the pickers, when they could be read), {@code kitsOn}
     * (on the answering server) and {@code off} (live servers answering for kits with kits switched off, D863).
     */
    public JsonObject grid(Account me, boolean refresh) {
        JsonObject l = list(me, refresh);
        if (!ok(l)) {
            return l;
        }
        JsonObject out = l.deepCopy();
        JsonObject c = choices(me, refresh);
        if (ok(c) && c.has("choices")) {
            out.add("choices", c.get("choices"));
        }
        JsonArray off = new JsonArray();
        for (String s : feed.liveWithKits()) {
            if (!feed.kitsOn(s)) {
                off.add(s);
            }
        }
        out.add("off", off);
        return out;
    }

    /** A kit's page (K2): the grid's answer with {@code kit}, the one asked for; refused when there is no such kit now. */
    public JsonObject kit(Account me, String id, boolean refresh) {
        if (id == null || !ID.matcher(id).matches()) {
            return refused("That is not a kit's id.");
        }
        JsonObject g = grid(me, refresh);
        if (!ok(g)) {
            return g;
        }
        JsonObject k = find(g, id);
        if (k == null) {
            return refused("There is no kit " + id + " now: it may have been deleted in game.");
        }
        g.add("kit", k);
        g.remove("kits");
        return g;
    }

    /** A player's kit records (D866), asked fresh: {@code rows} as the game server gives them, with {@code again} and {@code here}. */
    public JsonObject records(Account me, String player) {
        UUID u = uuid(player);
        if (u == null) {
            return refused("That is not a player.");
        }
        JsonObject f = action("kit_records");
        f.addProperty("player", u.toString());
        return askOne(me, f, Actions.LONG_WAIT_SECONDS); // on a network BSP-Core reads the database first
    }

    // ------------------------------------------------------------------ changes (Admins and Owners, D839)

    /** A new kit (K5): {@code id}, {@code name}; made off and empty by BSP-Core. */
    public JsonObject create(Account me, JsonObject b) {
        String id = str(b, "id");
        String tried = "tried to make kit " + (ID.matcher(id).matches() ? id : "?");
        JsonObject why = admins(me);
        if (why == null && !ID.matcher(id).matches()) {
            why = refused("An id is 2 to 24 lower-case letters, digits and _.");
        }
        String name = name(b.get("name"));
        if (why == null && name == null) {
            why = refused("A name of 1 to " + NAME_MAX + " characters, please.");
        }
        Map<String, JsonObject> byId = why == null ? known(me) : null;
        if (why == null && byId == null) {
            why = refused(noServer());
        }
        if (why == null && byId.containsKey(id)) {
            why = refused("There is already a kit " + id + ": choose another id.");
        }
        if (why != null) {
            return logged(why, tried);
        }
        JsonObject f = action("kit_create");
        f.addProperty("kit", id);
        f.addProperty("name", name);
        return logged(send(me, f, null), "made kit " + id + ", off");
    }

    /** One setting (K3, K4): {@code id}, {@code field}, {@code value}, {@code edited}. */
    public JsonObject set(Account me, JsonObject b) {
        String id = str(b, "id"), field = str(b, "field");
        String tried = "tried to change " + FIELD_WORDS.getOrDefault(field, "a setting") + " of kit " + (ID.matcher(id).matches() ? id : "?");
        Changing c = changing(me, b, tried);
        if (c.refusal != null) {
            return c.refusal;
        }
        if (!FIELD_WORDS.containsKey(field)) {
            return logged(refused("That is not a kit setting."), tried);
        }
        JsonElement v = b.get("value");
        JsonElement put = null;
        String why = null;
        switch (field) {
            case "name" -> {
                String n = name(v);
                if (n == null) {
                    why = "A name of 1 to " + NAME_MAX + " characters, please.";
                } else {
                    put = new com.google.gson.JsonPrimitive(n);
                }
            }
            case "description" -> {
                String d = v != null && v.isJsonPrimitive() ? clean(v.getAsString()) : null;
                if (d == null || d.length() > DESCRIPTION_MAX) {
                    why = "A description of up to " + DESCRIPTION_MAX + " characters, please.";
                } else {
                    put = new com.google.gson.JsonPrimitive(d);
                }
            }
            case "inBrowser", "once" -> {
                if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                    why = "On or off, please.";
                } else {
                    put = v.deepCopy();
                }
            }
            case "cooldownSeconds", "price" -> {
                long n = whole(v);
                if (n < 0 || n > NUMBER_MAX) {
                    why = "A whole number from 0 to a trillion, please.";
                } else {
                    put = new com.google.gson.JsonPrimitive(n);
                }
            }
            case "ranks", "servers", "grantServers" -> {
                List<String> l = names(v, LIST_ITEM);
                if (l == null || String.join(",", l).length() > LIST_MAX) {
                    why = "Names of letters, digits, _ . + and -, up to " + LIST_MAX + " characters together.";
                } else {
                    put = toArray(l);
                }
            }
            case "conditions" -> {
                List<String> l = names(v, Pattern.compile("[a-z0-9_]{1,32}"));
                Set<String> keys = conditionKeys(me);
                if (l == null || keys != null && !keys.containsAll(l)) {
                    why = "Only the conditions BSP-Core offers.";
                } else {
                    put = toArray(l);
                }
            }
            case "grant" -> {
                String g = v != null && v.isJsonPrimitive() ? v.getAsString() : "";
                if (!GRANTS.contains(g)) {
                    why = "Never, once on the network, or on each chosen server.";
                } else {
                    put = new com.google.gson.JsonPrimitive(g);
                }
            }
            default -> why = "That is not a kit setting.";
        }
        if (why != null) {
            return logged(refused(why), tried);
        }
        JsonObject f = stamped("kit_set", id, c.edited);
        f.addProperty("field", field);
        f.add("value", put);
        return logged(send(me, f, null), "changed " + FIELD_WORDS.get(field) + " of kit " + id + " to " + said(put));
    }

    /** The contents (K3, K4): {@code id}, {@code items} (every stack in slot order), {@code edited}, optionally {@code one}. */
    public JsonObject items(Account me, JsonObject b) {
        String id = str(b, "id");
        String tried = "tried to change the items of kit " + (ID.matcher(id).matches() ? id : "?");
        Changing c = changing(me, b, tried);
        if (c.refusal != null) {
            return c.refusal;
        }
        JsonElement items = b.get("items");
        int max = maxItems(me);
        if (items == null || !items.isJsonArray()) {
            return logged(refused("The items, please."), tried);
        }
        if (items.getAsJsonArray().size() > max) {
            return logged(refused("A kit holds at most " + max + " stacks."), tried);
        }
        JsonArray rows = new JsonArray();
        for (JsonElement e : items.getAsJsonArray()) {
            JsonObject row = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
            String item = row == null ? "" : str(row, "item"), nbt = row == null ? "" : str(row, "nbt");
            long count = row == null ? -1 : whole(row.get("count"));
            if (!ITEM.matcher(item).matches() || count < 1 || count > COUNT_MAX || nbt.length() > SNBT_MAX) {
                return logged(refused("Each stack is an item id, a count of at least 1 and its data (up to " + String.format(java.util.Locale.ROOT, "%,d", SNBT_MAX) + " characters)."), tried);
            }
            JsonObject r = new JsonObject();
            r.addProperty("item", item);
            r.addProperty("count", count);
            r.addProperty("nbt", nbt);
            rows.add(r);
        }
        JsonObject f = stamped("kit_items", id, c.edited);
        f.add("items", rows);
        f.addProperty("one", b.has("one") && b.get("one").isJsonPrimitive() && b.get("one").getAsJsonPrimitive().isBoolean() && b.get("one").getAsBoolean());
        return logged(send(me, f, null), "set the items of kit " + id + " (" + rows.size() + (rows.size() == 1 ? " stack)" : " stacks)"));
    }

    /** The icon (K3): {@code id}, {@code icon} ({@code item}, {@code nbt}; null or no item for the first item), {@code edited}. */
    public JsonObject icon(Account me, JsonObject b) {
        String id = str(b, "id");
        String tried = "tried to change the icon of kit " + (ID.matcher(id).matches() ? id : "?");
        Changing c = changing(me, b, tried);
        if (c.refusal != null) {
            return c.refusal;
        }
        JsonElement icon = b.get("icon");
        JsonObject f = stamped("kit_icon", id, c.edited);
        String item = icon != null && icon.isJsonObject() ? str(icon.getAsJsonObject(), "item") : "";
        if (item.isEmpty()) {
            f.add("icon", com.google.gson.JsonNull.INSTANCE);
            return logged(send(me, f, null), "set the icon of kit " + id + " to its first item");
        }
        String nbt = str(icon.getAsJsonObject(), "nbt");
        if (!ITEM.matcher(item).matches() || nbt.length() > ICON_SNBT_MAX) {
            return logged(refused("An icon is an item id and its data (up to " + String.format(java.util.Locale.ROOT, "%,d", ICON_SNBT_MAX) + " characters)."), tried);
        }
        JsonObject i = new JsonObject();
        i.addProperty("item", item);
        i.addProperty("nbt", nbt);
        f.add("icon", i);
        return logged(send(me, f, null), "set the icon of kit " + id + " to " + item);
    }

    /** The switch (K1, K2): {@code id}, {@code on}, {@code edited}. */
    public JsonObject enable(Account me, JsonObject b) {
        String id = str(b, "id");
        String tried = "tried to turn kit " + (ID.matcher(id).matches() ? id : "?") + " on or off";
        Changing c = changing(me, b, tried);
        if (c.refusal != null) {
            return c.refusal;
        }
        JsonElement on = b.get("on");
        if (on == null || !on.isJsonPrimitive() || !on.getAsJsonPrimitive().isBoolean()) {
            return logged(refused("On or off, please."), tried);
        }
        JsonObject f = stamped("kit_enable", id, c.edited);
        f.addProperty("on", on.getAsBoolean());
        return logged(send(me, f, null), "turned kit " + id + (on.getAsBoolean() ? " on" : " off"));
    }

    /** A copy (K5): {@code id}, {@code newId}, {@code name}; made off by BSP-Core, with no counts or records. */
    public JsonObject copy(Account me, JsonObject b) {
        String id = str(b, "id"), to = str(b, "newId");
        String tried = "tried to copy kit " + (ID.matcher(id).matches() ? id : "?");
        JsonObject why = admins(me);
        String name = name(b.get("name"));
        if (why == null && !ID.matcher(id).matches()) {
            why = refused("That is not a kit's id.");
        }
        if (why == null && !ID.matcher(to).matches()) {
            why = refused("An id is 2 to 24 lower-case letters, digits and _.");
        }
        if (why == null && name == null) {
            why = refused("A name of 1 to " + NAME_MAX + " characters, please.");
        }
        Map<String, JsonObject> byId = why == null ? known(me) : null;
        if (why == null && byId == null) {
            why = refused(noServer());
        }
        if (why == null && !byId.containsKey(id)) {
            why = refused("There is no kit " + id + " now: it may have been deleted in game.");
        }
        if (why == null && byId.containsKey(to)) {
            why = refused("There is already a kit " + to + ": choose another id.");
        }
        if (why == null && whole(byId.get(id).get("missing")) > 0) {
            why = refused(missingWords(str(listRead(), "server")));
        }
        if (why != null) {
            return logged(why, tried);
        }
        JsonObject f = action("kit_copy");
        f.addProperty("kit", id);
        f.addProperty("newId", to);
        f.addProperty("name", name);
        return logged(send(me, f, null), "copied kit " + id + " to " + to + ", off");
    }

    /** Deleting (K6): {@code id}, {@code edited}; the kit and every player's record of it (works on a kit with missing items, D867). */
    public JsonObject delete(Account me, JsonObject b) {
        String id = str(b, "id");
        String tried = "tried to delete kit " + (ID.matcher(id).matches() ? id : "?");
        JsonObject why = admins(me);
        long edited = whole(b.get("edited"));
        if (why == null && (!ID.matcher(id).matches() || edited < 0)) {
            why = refused("Read the kit again first.");
        }
        if (why != null) {
            return logged(why, tried);
        }
        JsonObject f = stamped("kit_delete", id, edited);
        return logged(send(me, f, null), "deleted kit " + id);
    }

    /** GIVE TO… (K6, D864): {@code id}, {@code player} (a UUID), {@code name}. Through the player's own server when kits are on there. */
    public JsonObject give(Account me, JsonObject b) {
        String id = str(b, "id"), name = str(b, "name");
        UUID player = uuid(str(b, "player"));
        String who = PLAYER_NAME.matcher(name).matches() ? name : player == null ? "?" : player.toString();
        String tried = "tried to give kit " + (ID.matcher(id).matches() ? id : "?") + " to " + who;
        JsonObject why = admins(me);
        if (why == null && !ID.matcher(id).matches()) {
            why = refused("That is not a kit's id.");
        }
        if (why == null && player == null) {
            why = refused("Choose a player.");
        }
        Map<String, JsonObject> byId = why == null ? known(me) : null;
        if (why == null && byId == null) {
            why = refused(noServer());
        }
        if (why == null && !byId.containsKey(id)) {
            why = refused("There is no kit " + id + " now: it may have been deleted in game.");
        }
        if (why != null) {
            return logged(why, tried);
        }
        JsonObject f = action("kit_give");
        f.addProperty("kit", id);
        f.addProperty("player", player.toString());
        if (PLAYER_NAME.matcher(name).matches()) {
            f.addProperty("name", name);
        }
        String[] at = feed.find(player.toString());
        String through = at != null && feed.liveWithKits().contains(at[0]) && feed.kitsOn(at[0]) ? at[0] : null;
        return logged(send(me, f, through), "gave kit " + id + " to " + who);
    }

    /** RESET (D866): {@code player}, {@code name}, and {@code id} (a kit, or a deleted kit's id from the records) or {@code all} true. */
    public JsonObject reset(Account me, JsonObject b) {
        String id = str(b, "id"), name = str(b, "name");
        boolean all = b.has("all") && b.get("all").isJsonPrimitive() && b.get("all").getAsJsonPrimitive().isBoolean() && b.get("all").getAsBoolean();
        UUID player = uuid(str(b, "player"));
        String who = PLAYER_NAME.matcher(name).matches() ? name : player == null ? "?" : player.toString();
        String what = all ? "every kit" : "kit " + (ID.matcher(id).matches() ? id : "?");
        String tried = "tried to reset " + what + " for " + who;
        JsonObject why = admins(me);
        if (why == null && player == null) {
            why = refused("That is not a player.");
        }
        if (why == null && !all && !ID.matcher(id).matches()) {
            why = refused("Name a kit to reset, or every kit.");
        }
        if (why != null) {
            return logged(why, tried);
        }
        JsonObject f = action("kit_reset");
        f.addProperty("player", player.toString());
        if (all) {
            f.addProperty("all", true);
        } else {
            f.addProperty("kit", id);
        }
        if (PLAYER_NAME.matcher(name).matches()) {
            f.addProperty("name", name);
        }
        return logged(send(me, f, null), "reset " + what + " for " + who);
    }

    // ------------------------------------------------------------------ the checks every change of a kit makes

    private record Changing(JsonObject refusal, long edited) {
    }

    /** Admin, a kit's id, its stamp, the kit known now and readable here (D867); or the refusal, logged. */
    private Changing changing(Account me, JsonObject b, String tried) {
        String id = str(b, "id");
        long edited = whole(b.get("edited"));
        JsonObject why = admins(me);
        if (why == null && (!ID.matcher(id).matches() || edited < 0)) {
            why = refused("Read the kit again first.");
        }
        Map<String, JsonObject> byId = why == null ? known(me) : null;
        if (why == null && byId == null) {
            why = refused(noServer());
        }
        if (why == null && !byId.containsKey(id)) {
            why = refused("There is no kit " + id + " now: it may have been deleted in game.");
        }
        if (why == null && whole(byId.get(id).get("missing")) > 0) {
            why = refused(missingWords(str(listRead(), "server")));
        }
        return new Changing(why == null ? null : logged(why, tried), edited);
    }

    /** D867: the words for a kit holding items the answering server lacks. */
    public static String missingWords(String server) {
        return "Holds items from a mod " + (server.isEmpty() ? "this server" : server) + " does not have: change it on a server that has the mod.";
    }

    private JsonObject admins(Account me) {
        return me.rank.atLeast(Rank.ADMIN) ? null : refused(ADMINS);
    }

    /** The kits as last read (or read now): null when no server answers. */
    private Map<String, JsonObject> known(Account me) {
        JsonObject l = list(me, false);
        if (!ok(l)) {
            return null;
        }
        Map<String, JsonObject> out = new java.util.LinkedHashMap<>();
        for (JsonElement e : l.getAsJsonArray("kits")) {
            if (e.isJsonObject()) {
                out.put(str(e.getAsJsonObject(), "id"), e.getAsJsonObject());
            }
        }
        return out;
    }

    private JsonObject listRead() {
        Read r = list;
        return r == null ? new JsonObject() : r.data;
    }

    private int maxItems(Account me) {
        JsonObject c = choices(me, false);
        if (ok(c) && c.has("choices") && c.get("choices").isJsonObject()) {
            long m = whole(c.getAsJsonObject("choices").get("maxItems"));
            if (m >= 1 && m <= 64) {
                return (int) m;
            }
        }
        return 36;
    }

    /** The condition keys BSP-Core offers, or null when they could not be read (the game server checks then). */
    private Set<String> conditionKeys(Account me) {
        JsonObject c = choices(me, false);
        if (!ok(c) || !c.has("choices") || !c.get("choices").isJsonObject() || !c.getAsJsonObject("choices").has("conditions")
                || !c.getAsJsonObject("choices").get("conditions").isJsonArray()) {
            return null; // a malformed answer: the game server checks the keys (audit)
        }
        Set<String> out = new HashSet<>();
        for (JsonElement e : c.getAsJsonObject("choices").getAsJsonArray("conditions")) {
            if (e.isJsonObject()) {
                out.add(str(e.getAsJsonObject(), "key"));
            }
        }
        return out;
    }

    private String noServer() {
        return feed.live().isEmpty() || !feed.anyLiveWith("kitsWait") ? NO_SERVER : WAITING;
    }

    // ------------------------------------------------------------------ asking the game servers

    private JsonObject list(Account me, boolean refresh) {
        long now = clock.getAsLong();
        heard();
        Read held = list;
        if (!refresh && held != null && now - held.at < FRESH_MS) {
            return held.data.deepCopy();
        }
        Read f = listFailed;
        if (!refresh && f != null && now - f.at < RETRY_MS) {
            return f.data.deepCopy();
        }
        long began = gen;
        JsonObject out = askOne(me, action("kit_list"), 0);
        if (ok(out) && out.has("kits") && out.get("kits").isJsonArray()) {
            listFailed = null;
            synchronized (lock) {
                if (began == gen) {
                    list = new Read(now, str(out, "server"), out.deepCopy());
                }
            }
        } else {
            if (ok(out)) {
                out = refused("The game server's answer had no kits in it.");
            }
            if (!com.mrgregles.bsp_staff_panel.shops.ShopDesk.ownRefusal(str(out, "message"))) {
                listFailed = new Read(now, "", out.deepCopy());
            }
        }
        return out;
    }

    private JsonObject choices(Account me, boolean refresh) {
        long now = clock.getAsLong();
        Read held = choices;
        if (!refresh && held != null && now - held.at < FRESH_MS) {
            return held.data.deepCopy();
        }
        long began = gen;
        JsonObject out = askOne(me, action("kit_choices"), 0);
        if (ok(out) && out.has("choices") && out.get("choices").isJsonObject()) {
            synchronized (lock) {
                if (began == gen) {
                    choices = new Read(now, str(out, "server"), out.deepCopy());
                }
            }
        }
        return out;
    }

    private JsonObject askOne(Account me, JsonObject f, int wait) {
        List<String> able = feed.liveWithKits();
        Actions act = actions.get();
        if (able.isEmpty() || act == null) {
            return refused(noServer());
        }
        String server = able.get(0);
        Actions.Answer a = wait > 0 ? act.read(server, me.mcName, me.rank.name(), f, wait) : act.read(server, me.mcName, me.rank.name(), f);
        return shaped(a, server);
    }

    /** A change, through {@code through} when given (D864), else the answering server; the kits kept are dropped whatever it answered. */
    private JsonObject send(Account me, JsonObject f, String through) {
        List<String> able = feed.liveWithKits();
        Actions act = actions.get();
        if (able.isEmpty() || act == null) {
            return refused(noServer());
        }
        String server = through != null && able.contains(through) ? through : able.get(0);
        Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f, Actions.LONG_WAIT_SECONDS);
        drop(); // whatever it answered, the next read is fresh
        if (ans.ok()) { // only a change that went through moves each server's kitsAt once: not read twice (audit)
            long until = clock.getAsLong() + OWN_MS;
            feed.liveWithKits().forEach(x -> ownUntil.put(x, until));
        }
        return shaped(ans, server);
    }

    private JsonObject shaped(Actions.Answer a, String server) {
        JsonObject out = new JsonObject();
        if (a.data() != null && a.data().isJsonObject()) {
            a.data().getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        }
        out.remove("panelLog"); // only the desk writes the panel's log, never a game server's answer
        out.addProperty("ok", a.ok());
        out.addProperty("message", a.message() == null ? "" : a.message());
        out.addProperty("server", server);
        out.addProperty("at", clock.getAsLong());
        return out;
    }

    // ------------------------------------------------------------------ small parts

    private static JsonObject find(JsonObject list, String id) {
        if (list.has("kits") && list.get("kits").isJsonArray()) {
            for (JsonElement e : list.getAsJsonArray("kits")) {
                if (e.isJsonObject() && str(e.getAsJsonObject(), "id").equals(id)) {
                    return e.getAsJsonObject().deepCopy();
                }
            }
        }
        return null;
    }

    private static JsonObject stamped(String action, String id, long edited) {
        JsonObject f = action(action);
        f.addProperty("kit", id);
        f.addProperty("edited", edited);
        return f;
    }

    /** Text as BSP-Core keeps it: control and format characters and colour codes become spaces, ends trimmed. */
    static String clean(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) || c == '§' || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR ? ' ' : c));
        return out.toString().strip();
    }

    private static String name(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String n = clean(e.getAsString());
        return n.isEmpty() || n.codePointCount(0, n.length()) > NAME_MAX ? null : n;
    }

    /** A list of names, as an array or one text joined by commas; null when one does not fit. */
    private static List<String> names(JsonElement v, Pattern each) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isJsonNull()) {
            return out;
        }
        List<String> raw = new ArrayList<>();
        if (v.isJsonArray()) {
            for (JsonElement e : v.getAsJsonArray()) {
                if (!e.isJsonPrimitive()) {
                    return null;
                }
                raw.add(e.getAsString());
            }
        } else if (v.isJsonPrimitive()) {
            for (String p : v.getAsString().split(",")) {
                raw.add(p);
            }
        } else {
            return null;
        }
        for (String r : raw) {
            String t = r.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (!each.matcher(t).matches()) {
                return null;
            }
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static JsonArray toArray(List<String> l) {
        JsonArray a = new JsonArray();
        l.forEach(a::add);
        return a;
    }

    private static String said(JsonElement v) {
        String s = v == null ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString();
        s = clean(s);
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    static UUID uuid(String s) {
        try {
            return s == null || s.length() != 36 ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static long whole(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return Long.MIN_VALUE;
        }
        double v = e.getAsDouble();
        return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : Long.MIN_VALUE;
    }

    private static JsonObject logged(JsonObject out, String line) {
        out.addProperty("panelLog", line);
        return out;
    }

    private static JsonObject action(String name) {
        JsonObject f = new JsonObject();
        f.addProperty("action", name);
        return f;
    }

    static JsonObject refused(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("message", message);
        return o;
    }

    private static boolean ok(JsonObject o) {
        return o.has("ok") && o.get("ok").isJsonPrimitive() && o.get("ok").getAsBoolean();
    }

    static String str(JsonObject o, String key) {
        if (o == null) {
            return "";
        }
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
