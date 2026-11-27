package com.mrgregles.bsp_staff_panel.shops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The SHOPS tab (SU-161; D788 to D814, sets BG A, BH B, BI A and BJ A refined, R1 to R5), through the game servers'
 * {@code shop_*} actions answered by BSP-Core's shops (requests SU-BC5 to SU-BC10). Every rank reads (D788).
 * <ul>
 * <li>The shops are one record for the whole network, so the list ({@code shop_list}) and a shop ({@code shop_read}) are
 * asked of one live server with the shops, its database connected first, then the busiest (D789), and kept
 * {@link #FRESH_MS} for every staff member.</li>
 * <li>Stalls are blocks: every live server with the shops is asked for its own ({@code shop_venues}) at once and its
 * answer kept the same time; a server that stops sending keeps its last answer, shown as such (R3).</li>
 * <li>A server's {@code shopsAt} (when it last heard a shop change, anywhere) newer than a read kept drops it; its
 * {@code stallsAt} (when its own stalls last changed) does the same for its stalls (D811). Until BSP-Core sends them,
 * pages read again every minute.</li>
 * <li>Sales ({@code shop_report}) for 1, 7 or 30 days, kept {@link #FRESH_MS} per shop and span (D808).</li>
 * </ul>
 * <p>What a game server answers (built in SU-165 through BSP-Core 1.26.0's {@code BspShops}; the full list is
 * {@code docs/API.md}, "Shops"): {@code shop_list} gives {@code {shops: [...], timeZone}}, each shop with its settings,
 * {@code cards} (a count), {@code hours}, {@code openNow}, {@code opensAt}, {@code requires}, {@code edited} and {@code peek};
 * {@code shop_read} ({@code shop}) gives {@code {shop}}: the same with {@code cards} as a list, {@code onShow},
 * {@code cycleLeft} (-1 without no repeats) and {@code draws} (the draw on show only); {@code shop_venues} gives
 * {@code {venues: [{kind, dim, x, y, z, shop, hours, pin}]}} ({@code hours} left out when the shop's count);
 * {@code shop_report} ({@code shop}, {@code days}) gives {@code days} (BSP-Core's), {@code capped}, {@code piecesIn},
 * {@code piecesOut}, {@code coinsIn} and {@code coinsOut} (five counts), {@code rows} (one per shop and item) and
 * {@code sellers}. The changes (SU-162): {@code shop_create} ({@code shop, name, kind}; a section also {@code parent, edited,
 * picture, v}), {@code shop_set} ({@code shop, field, value, edited}), {@code shop_enable} ({@code shop, on, edited}),
 * {@code shop_copy} ({@code shop, newId, name}) and {@code shop_delete} ({@code shop, edited}) answer {@code shop} and the
 * new {@code edited}, or a refusal with {@code stale} when the stamp moved. The shop's own id travels as {@code shop} and a card's
 * place as {@code place}: the envelope's own {@code id} and {@code at} are the action's. Snapshots carry {@code shops}, {@code shopsAt} and {@code stallsAt}. The cards
 * (SU-163): {@code shop_item_add} ({@code shop, item, v, nbt, count, place, edited}), {@code shop_item_set} ({@code shop,
 * index, field, value, edited}), {@code shop_item_move} ({@code shop, from, to, edited}), {@code shop_item_remove} and
 * {@code shop_item_refill} ({@code shop, index, edited}), answered the same way. SU-164: {@code shop_export} ({@code shop})
 * gives {@code json}, the shop and its sections as BSP-Core's import file (format 1), with {@code shops} and {@code cards}.
 */
public final class ShopDesk {
    public static final long FRESH_MS = 60_000L;
    /** A failed read is said again for this long before the server is asked again (as the WORLDS tab, audit). */
    static final long RETRY_MS = 20_000L;
    public static final String NO_SERVER = "No game server with BSP-Core's shops is sending to the panel right now.";
    static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    /** BSP-Core's deepest shop tree ({@code Shops.MAX_DEPTH}): a walk up or down never goes further, whatever a server sends. */
    public static final int MAX_DEPTH = 16;
    static final Set<Integer> SPANS = Set.of(1, 7, 30);

    private record Read(long at, String server, JsonObject data) {
    }

    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;
    private volatile Read list, listFailed;
    private final Map<String, Read> shops = new ConcurrentHashMap<>(), shopFailed = new ConcurrentHashMap<>(), sales = new ConcurrentHashMap<>();
    private final Map<String, Read> venues = new ConcurrentHashMap<>(), venuesFailed = new ConcurrentHashMap<>();
    /** When each server was last heard from, this run: a server that stops keeps its stalls, greyed. */
    private final Map<String, Long> lastLive = new ConcurrentHashMap<>();
    /** Bumped when shops change: a read that began before is not kept (as the WORLDS tab, audit). */
    private volatile long gen;
    /** Each server's {@code shopsAt} and {@code stallsAt} as last heard: a change of value, never a comparison of clocks (audit). */
    private final Map<String, Long> shopsSeen = new ConcurrentHashMap<>(), stallsSeen = new ConcurrentHashMap<>();
    /** Until when each server's next {@code shopsAt} move is the panel's own change, already read again (the reads allowance). */
    private final Map<String, Long> ownUntil = new ConcurrentHashMap<>();
    static final long OWN_MS = 15_000L;
    private final Object lock = new Object();

    public ShopDesk(Feed feed, Supplier<Actions> actions, LongSupplier clock) {
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
        feed.onChange(this::heard);
    }

    private void heard() {
        for (String s : feed.live()) {
            JsonObject n = feed.numbers(s);
            if (n != null && n.has("heardAt")) {
                lastLive.put(s, n.get("heardAt").getAsLong());
            }
            // D811: a server's shopsAt moving drops the shops kept, except once in OWN_MS after the panel's own change, which
            // was read again at once (the reads allowance, SU-162); its stallsAt moving drops its stalls only. Values are
            // compared with what was last heard, never with the panel's clock (the game server's may differ; audit).
            long at = feed.shopsAt(s);
            Long was = at < 0 ? null : shopsSeen.put(s, at);
            if (was != null && was != at) {
                Long own = ownUntil.remove(s);
                if (own == null || own < clock.getAsLong()) {
                    drop();
                }
            }
            long stalls = feed.stallsAt(s);
            Long wasStalls = stalls < 0 ? null : stallsSeen.put(s, stalls);
            if (wasStalls != null && wasStalls != stalls) {
                venues.remove(s);
            }
        }
    }

    /** Drops what was kept after a change heard from {@code server}: the shops (one record for the network) and its stalls. */
    public void forget(String server) {
        drop();
        if (server != null) {
            venues.remove(server);
        }
    }

    /** The shops kept, and the failures kept, dropped; a read in flight that began before is not kept. */
    private void drop() {
        synchronized (lock) {
            gen++;
            list = null;
            listFailed = null;
            shops.clear();
            shopFailed.clear();
        }
    }

    /** Tests and the demo: a server that is off, remembered with the stalls it last sent. */
    public void remember(String server, long heardAt, JsonObject venuesAnswer) {
        venues.put(server, new Read(heardAt, server, venuesAnswer.deepCopy()));
        lastLive.put(server, heardAt);
    }

    // ------------------------------------------------------------------ the grid (R1)

    /**
     * The grid: {@code ok}, {@code message}, {@code server} (which answered), {@code at}, {@code timeZone}, {@code shops}:
     * each top-level shop with its fields, {@code sections} (every shop under it, {@code id} and {@code name}, for the
     * search) and {@code venues} (one per server with stalls for it or its sections: {@code server}, {@code live},
     * {@code heardAt}, {@code stall}, {@code post}, {@code display}, {@code forSections}).
     */
    public JsonObject grid(Account me, boolean refresh) {
        JsonObject l = list(me, refresh);
        if (!ok(l)) {
            return l;
        }
        Map<String, JsonObject> byId = byId(l);
        Map<String, List<String>> kids = children(byId);
        Map<String, JsonArray> perShop = new HashMap<>();
        JsonObject vs = venuesAll(me, refresh);
        for (Map.Entry<String, JsonElement> e : vs.getAsJsonObject("servers").entrySet()) {
            JsonObject sv = e.getValue().getAsJsonObject();
            Map<String, int[]> counts = new LinkedHashMap<>(); // top shop -> stall, post, display, for its sections
            for (JsonElement v : sv.getAsJsonArray("venues")) {
                if (!v.isJsonObject()) {
                    continue;
                }
                String shop = str(v.getAsJsonObject(), "shop");
                String top = root(byId, shop), kind = str(v.getAsJsonObject(), "kind");
                int slot = kind.equals("stall") ? 0 : kind.equals("post") ? 1 : kind.equals("display") ? 2 : -1;
                if (top == null || slot < 0) {
                    continue; // a stall for a shop that is gone, or a kind this panel does not know
                }
                int[] c = counts.computeIfAbsent(top, k -> new int[4]);
                c[slot]++;
                if (!shop.equals(top)) {
                    c[3]++;
                }
            }
            for (Map.Entry<String, int[]> c : counts.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("server", e.getKey());
                o.add("live", sv.get("live"));
                o.add("heardAt", sv.get("heardAt"));
                o.addProperty("stall", c.getValue()[0]);
                o.addProperty("post", c.getValue()[1]);
                o.addProperty("display", c.getValue()[2]);
                o.addProperty("forSections", c.getValue()[3]);
                perShop.computeIfAbsent(c.getKey(), k -> new JsonArray()).add(o);
            }
        }
        JsonArray out = new JsonArray();
        List<JsonObject> tops = new ArrayList<>();
        for (JsonObject s : byId.values()) {
            if (root(byId, str(s, "id")).equals(str(s, "id"))) {
                tops.add(s);
            }
        }
        tops.sort(Comparator.comparing((JsonObject s) -> str(s, "name").toLowerCase(Locale.ROOT)).thenComparing(s -> str(s, "id"))); // A to Z (D792)
        for (JsonObject s : tops) {
            JsonObject o = s.deepCopy();
            JsonArray secs = new JsonArray();
            for (String id : under(kids, str(s, "id"))) {
                JsonObject sec = new JsonObject();
                sec.addProperty("id", id);
                sec.addProperty("name", str(byId.get(id), "name"));
                secs.add(sec);
            }
            o.add("sections", secs);
            JsonArray vv = perShop.getOrDefault(str(s, "id"), new JsonArray());
            o.add("venues", vv);
            out.add(o);
        }
        JsonObject g = new JsonObject();
        g.addProperty("ok", true);
        g.addProperty("message", "");
        g.add("server", l.get("server"));
        g.add("at", l.get("at"));
        g.addProperty("timeZone", str(l, "timeZone"));
        g.add("shops", out);
        g.add("venueErrors", vs.get("errors"));
        return g;
    }

    // ------------------------------------------------------------------ a shop's page (R2 to R5)

    /**
     * A shop or section for its page: {@code shop} (as the game server read it), {@code top} (the top-level shop's id),
     * {@code tree} (the top shop and every section under it, depth first: {@code id, name, kind, cards, depth}),
     * {@code path} (the shops from the top down to this one), {@code venues} (every stall for the tree, by server, with
     * {@code server}, {@code live}, {@code heardAt}) and {@code timeZone}.
     */
    public JsonObject shop(Account me, String id, boolean refresh) {
        if (id == null || !ID.matcher(id).matches()) {
            return refused("That is not a shop's id.");
        }
        JsonObject l = list(me, refresh);
        if (!ok(l)) {
            return l;
        }
        Map<String, JsonObject> byId = byId(l);
        if (!byId.containsKey(id)) {
            return refused("There is no shop " + id + " now: it may have been deleted in game.");
        }
        JsonObject s = read(me, id, refresh);
        if (!ok(s)) {
            return s;
        }
        String top = root(byId, id);
        Map<String, List<String>> kids = children(byId);
        JsonArray tree = new JsonArray();
        walk(byId, kids, top, 0, new HashSet<>(), tree);
        JsonArray path = new JsonArray();
        List<String> up = new ArrayList<>();
        String at = id;
        for (int i = 0; at != null && !at.isEmpty() && i <= MAX_DEPTH; i++) {
            up.add(0, at);
            if (at.equals(top)) {
                break;
            }
            at = str(byId.get(at), "parent");
        }
        for (String p : up) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p);
            o.addProperty("name", str(byId.get(p), "name"));
            path.add(o);
        }
        Set<String> inTree = new HashSet<>();
        tree.forEach(t -> inTree.add(str(t.getAsJsonObject(), "id")));
        JsonArray vv = new JsonArray();
        JsonObject vs = venuesAll(me, refresh);
        for (Map.Entry<String, JsonElement> e : vs.getAsJsonObject("servers").entrySet()) {
            JsonObject sv = e.getValue().getAsJsonObject();
            for (JsonElement v : sv.getAsJsonArray("venues")) {
                if (v.isJsonObject() && inTree.contains(str(v.getAsJsonObject(), "shop"))) {
                    JsonObject o = v.getAsJsonObject().deepCopy();
                    o.addProperty("server", e.getKey());
                    o.add("live", sv.get("live"));
                    o.add("heardAt", sv.get("heardAt"));
                    o.addProperty("shopName", str(byId.get(str(o, "shop")), "name"));
                    vv.add(o);
                }
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("message", "");
        out.add("server", s.get("server"));
        out.add("at", s.get("at"));
        out.add("shop", s.get("shop"));
        out.addProperty("top", top);
        out.add("tree", tree);
        out.add("path", path);
        out.add("venues", vv);
        out.add("venueErrors", vs.get("errors"));
        out.addProperty("timeZone", str(l, "timeZone"));
        return out;
    }

    /** Sales for a shop and its sections over 1, 7 or 30 days (another span is read as 7): the game server's totals and rows. */
    public JsonObject sales(Account me, String id, int days) {
        if (id == null || !ID.matcher(id).matches()) {
            return refused("That is not a shop's id.");
        }
        int span = SPANS.contains(days) ? days : 7;
        String key = id + "|" + span;
        long now = clock.getAsLong();
        Read held = sales.get(key);
        if (held != null && now - held.at < FRESH_MS) {
            return held.data.deepCopy();
        }
        JsonObject f = action("shop_report");
        f.addProperty("shop", id);
        f.addProperty("days", span);
        JsonObject out = askOne(me, f, Actions.LONG_WAIT_SECONDS); // on a network BSP-Core reads the bank log first (audit)
        if (!out.has("days")) {
            out.addProperty("days", span); // else BSP-Core's own: fewer when the bank log keeps fewer days (capped)
        }
        if (ok(out)) {
            if (sales.size() >= 200) {
                sales.clear(); // a read kept a minute: never more than this many shops and spans at once
            }
            sales.put(key, new Read(now, str(out, "server"), out.deepCopy()));
        }
        return out;
    }

    // ------------------------------------------------------------------ changes (SU-162)

    public static final String ADMINS = "Changing shops is for Admins and Owners.";
    static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    static final Pattern NODE = Pattern.compile("[A-Za-z0-9_.*-]{1,128}");
    static final Pattern ROTATE_AT = Pattern.compile("(day|monday|tuesday|wednesday|thursday|friday|saturday|sunday) ([01][0-9]|2[0-3]):[0-5][0-9]");
    static final Set<String> KINDS = Set.of("shop", "rotating", "offer");
    static final Set<String> STYLES = Set.of("cash_machine", "passbook", "market_board");
    static final Set<String> FLAGS = Set.of("remote", "buyOnly", "noRepeat", "broadcast");
    /** The longest a name and a requirement's reason may be (BSP-Core's {@code Shop.NAME_MAX} and {@code Requirement.REASON_MAX}). */
    public static final int NAME_MAX = 64, REASON_MAX = 64;
    static final long TEN_YEARS_MS = 3_650L * 86_400_000L;

    /**
     * A new shop (D795), or a section of {@code parent} (D796): {@code name}, {@code id}, {@code kind} ({@code shop},
     * {@code rotating}, {@code offer}); for a section {@code parent}, its {@code edited} and the {@code picture} item
     * ({@code v} for a potion) for the section card BSP-Core puts at the end of the parent's page. Made OFF.
     */
    public JsonObject create(Account me, JsonObject b) {
        String id = str(b, "id"), parent = str(b, "parent");
        boolean section = !parent.isEmpty();
        String tried = "tried to make " + (section ? "section " : "shop ") + (ID.matcher(id).matches() ? id : "?");
        if (!me.rank.atLeast(com.mrgregles.bsp_staff_panel.auth.Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        String name = name(b.get("name"));
        if (name == null) {
            return logged(refused("A name of 1 to " + NAME_MAX + " characters, please."), tried);
        }
        if (!ID.matcher(id).matches()) {
            return logged(refused("An id is 1 to 32 lower-case letters, digits and _."), tried);
        }
        Map<String, JsonObject> byId = known(me);
        if (byId == null) {
            return logged(refused(NO_SERVER), tried);
        }
        if (byId.containsKey(id)) {
            return logged(refused("There is already a shop " + id + ": choose another id."), tried);
        }
        JsonObject f = action("shop_create");
        f.addProperty("shop", id);
        f.addProperty("name", name);
        if (section) {
            if (!byId.containsKey(parent)) {
                return logged(refused("There is no shop " + parent + " now: read it again."), tried);
            }
            if (depth(byId, parent) + 1 > MAX_DEPTH) {
                return logged(refused("Sections go at most " + MAX_DEPTH + " deep."), tried);
            }
            String picture = str(b, "picture"), v = str(b, "v");
            long edited = whole(b, "edited");
            if (!ITEM.matcher(picture).matches() || !v.isEmpty() && !ITEM.matcher(v).matches()) {
                return logged(refused("Choose the item the section's card shows."), tried);
            }
            if (edited < 0) {
                return logged(refused("Read the shop again first."), tried);
            }
            f.addProperty("kind", "sub");
            f.addProperty("parent", parent);
            f.addProperty("picture", picture);
            if (!v.isEmpty()) {
                f.addProperty("v", v);
            }
            f.addProperty("edited", edited);
        } else {
            String kind = str(b, "kind");
            if (!KINDS.contains(kind)) {
                return logged(refused("A shop is a Shop, a Rotating shop or a Special offer."), tried);
            }
            f.addProperty("kind", kind);
        }
        JsonObject out = send(me, f);
        return logged(out, (section ? "made section " + id + " in " + parent : "made shop " + id + " (" + str(f, "kind") + ")") + ", off");
    }

    /**
     * One setting of a shop or section (D794, D803, D804): {@code shop}, {@code field}, {@code value}, {@code edited} (the
     * stamp it was read at: a shop changed in game since is refused, {@code stale}). Each field is checked and put in one
     * form here, so nothing malformed is signed.
     */
    public JsonObject set(Account me, JsonObject b) {
        String id = str(b, "shop"), field = str(b, "field");
        String tried = "tried to change " + (FIELD_WORDS.containsKey(field) ? FIELD_WORDS.get(field) : "a setting") + " of " + (ID.matcher(id).matches() ? id : "?");
        if (!me.rank.atLeast(com.mrgregles.bsp_staff_panel.auth.Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        long edited = whole(b, "edited");
        if (!ID.matcher(id).matches() || edited < 0) {
            return logged(refused("Read the shop again first."), tried);
        }
        if (!FIELD_WORDS.containsKey(field)) {
            return logged(refused("That is not a shop setting."), tried);
        }
        Map<String, JsonObject> byId = known(me);
        if (byId == null) {
            return logged(refused(NO_SERVER), tried);
        }
        JsonObject shop = byId.get(id);
        if (shop == null) {
            return logged(refused("There is no shop " + id + " now: it may have been deleted in game."), tried);
        }
        JsonElement value = b.get("value");
        String why = null;
        JsonElement put = null;
        switch (field) {
            case "name" -> {
                String n = name(value);
                if (n == null) {
                    why = "A name of 1 to " + NAME_MAX + " characters, please.";
                } else {
                    put = new JsonPrimitive(n);
                }
            }
            case "kind" -> {
                String k = prim(value);
                if (str(shop, "kind").equals("sub")) {
                    why = "A section stays a section.";
                } else if (!KINDS.contains(k)) {
                    why = "A shop is a Shop, a Rotating shop or a Special offer.";
                } else {
                    put = new JsonPrimitive(k);
                }
            }
            case "parent" -> {
                String p = prim(value);
                if (p.equals(id)) {
                    why = "A shop cannot be inside itself.";
                } else if (!p.isEmpty() && !byId.containsKey(p)) {
                    why = "There is no shop " + (ID.matcher(p).matches() ? p : "by that id") + ".";
                } else if (!p.isEmpty() && under(children(byId), id).contains(p)) {
                    why = "That shop is inside this one: it would go round in a loop.";
                } else if (!p.isEmpty() && depth(byId, p) + 1 + height(byId, id) > MAX_DEPTH) {
                    why = "Sections go at most " + MAX_DEPTH + " deep.";
                } else if (p.isEmpty() && !str(shop, "parent").isEmpty()) {
                    why = "A shop inside another stays inside a shop: move it to another one."; // BSP-Core's bad_value, for any kind (audit)
                } else {
                    put = new JsonPrimitive(p);
                }
            }
            case "remote", "buyOnly", "noRepeat", "broadcast" -> {
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    why = "On or off, please.";
                } else {
                    put = value.deepCopy();
                }
            }
            case "style" -> {
                if (!STYLES.contains(prim(value))) {
                    why = "Cash Machine, Passbook or Market Board.";
                } else {
                    put = new JsonPrimitive(prim(value));
                }
            }
            case "columns" -> put = between(value, 0, 7, n -> n == 0 || n >= 4);
            case "rows" -> put = between(value, 2, 3, n -> true);
            case "displaySlots" -> put = between(value, 1, 54, n -> true);
            case "intervalHours" -> put = between(value, 1, 8_760, n -> true);
            case "offerPercent" -> put = between(value, 0, 99, n -> true);
            case "offerEnd" -> {
                long t = whole(value);
                if (t != 0 && (t < clock.getAsLong() || t > clock.getAsLong() + TEN_YEARS_MS)) {
                    why = "An end in the next ten years, or none.";
                } else {
                    put = new JsonPrimitive(t);
                }
            }
            case "rotateAt" -> {
                String r = prim(value).toLowerCase(Locale.ROOT).strip();
                if (!r.equals("none") && !ROTATE_AT.matcher(r).matches()) {
                    why = "Every day or weekday at a time (monday 00:00), or none.";
                } else {
                    put = new JsonPrimitive(r);
                }
            }
            case "hours" -> {
                put = hours(value);
                if (put == null) {
                    why = "Always, or open and close times on the real clock (00:00 to 23:59) or the Minecraft day (0 to 23999).";
                }
            }
            default -> { // requires
                put = requires(value);
                if (put == null) {
                    why = "A LuckPerms node (letters, digits, . _ - *), 0 to 100,000 hours played, and a reason of up to " + REASON_MAX + " characters.";
                }
            }
        }
        if (put == null) {
            return logged(refused(why != null ? why : "That value does not fit " + FIELD_WORDS.get(field) + "."), tried);
        }
        JsonObject f = action("shop_set");
        f.addProperty("shop", id);
        f.addProperty("field", field);
        f.add("value", put);
        f.addProperty("edited", edited);
        JsonObject out = send(me, f);
        return logged(out, "changed " + FIELD_WORDS.get(field) + " of " + id + " to " + said(put));
    }

    /** A shop turned on or off (D805): {@code shop}, {@code on}, {@code edited}; its sections go with it (BSP-Core SU-BC8). */
    public JsonObject enable(Account me, JsonObject b) {
        String id = str(b, "shop");
        boolean isBool = b.has("on") && b.get("on").isJsonPrimitive() && b.get("on").getAsJsonPrimitive().isBoolean();
        boolean on = isBool && b.get("on").getAsBoolean();
        String tried = "tried to turn " + (ID.matcher(id).matches() ? id : "?") + (on ? " on" : " off");
        if (!me.rank.atLeast(com.mrgregles.bsp_staff_panel.auth.Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        long edited = whole(b, "edited");
        if (!ID.matcher(id).matches() || edited < 0 || !isBool) {
            return logged(refused("Read the shop again first."), tried);
        }
        JsonObject f = action("shop_enable");
        f.addProperty("shop", id);
        f.addProperty("on", on);
        f.addProperty("edited", edited);
        return logged(send(me, f), "turned shop " + id + (on ? " on" : " off"));
    }

    /** A copy of a shop with all its sections under new ids (D797): {@code shop}, {@code newId}, {@code name}. Made OFF. */
    public JsonObject copy(Account me, JsonObject b) {
        String id = str(b, "shop"), to = str(b, "newId");
        String tried = "tried to copy " + (ID.matcher(id).matches() ? id : "?") + " to " + (ID.matcher(to).matches() ? to : "?");
        if (!me.rank.atLeast(com.mrgregles.bsp_staff_panel.auth.Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        String name = name(b.get("name"));
        if (name == null) {
            return logged(refused("A name of 1 to " + NAME_MAX + " characters, please."), tried);
        }
        if (!ID.matcher(id).matches() || !ID.matcher(to).matches()) {
            return logged(refused("An id is 1 to 32 lower-case letters, digits and _."), tried);
        }
        Map<String, JsonObject> byId = known(me);
        if (byId == null) {
            return logged(refused(NO_SERVER), tried);
        }
        if (!byId.containsKey(id)) {
            return logged(refused("There is no shop " + id + " now: read it again."), tried);
        }
        if (!root(byId, id).equals(id)) {
            return logged(refused("A section is copied with its shop: copy " + root(byId, id) + "."), tried); // BSP-Core copies top shops only (D823)
        }
        if (byId.containsKey(to)) {
            return logged(refused("There is already a shop " + to + ": choose another id."), tried);
        }
        JsonObject f = action("shop_copy");
        f.addProperty("shop", id);
        f.addProperty("newId", to);
        f.addProperty("name", name);
        return logged(send(me, f), "copied shop " + id + " with its sections to " + to + ", off");
    }

    /** A shop deleted with every section under it (D807): {@code shop}, {@code edited}. */
    public JsonObject delete(Account me, JsonObject b) {
        String id = str(b, "shop");
        String tried = "tried to delete shop " + (ID.matcher(id).matches() ? id : "?");
        if (!me.rank.atLeast(com.mrgregles.bsp_staff_panel.auth.Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        long edited = whole(b, "edited");
        if (!ID.matcher(id).matches() || edited < 0) {
            return logged(refused("Read the shop again first."), tried);
        }
        Map<String, JsonObject> byId = known(me);
        int sections = byId == null || !byId.containsKey(id) ? 0 : under(children(byId), id).size();
        JsonObject f = action("shop_delete");
        f.addProperty("shop", id);
        f.addProperty("edited", edited);
        return logged(send(me, f), "deleted shop " + id + (sections > 0 ? " with its " + sections + (sections == 1 ? " section" : " sections") : ""));
    }

    /** The words each setting has in the panel's log. */
    static final Map<String, String> FIELD_WORDS = Map.ofEntries(Map.entry("name", "the name"), Map.entry("kind", "the kind"), Map.entry("parent", "where it sits"),
            Map.entry("remote", "the Shopping Network"), Map.entry("buyOnly", "buy only"), Map.entry("style", "the style"), Map.entry("columns", "the cards a row"),
            Map.entry("rows", "the rows"), Map.entry("displaySlots", "the cards on show"), Map.entry("intervalHours", "the draw's interval"),
            Map.entry("rotateAt", "the change time"), Map.entry("noRepeat", "no repeats"), Map.entry("broadcast", "telling every player"),
            Map.entry("offerPercent", "the discount"), Map.entry("offerEnd", "the offer's end"), Map.entry("hours", "the opening hours"),
            Map.entry("requires", "what it needs"));

    /** The shops as last read (or read now): null when no server answers. */
    private Map<String, JsonObject> known(Account me) {
        JsonObject l = list(me, false);
        return ok(l) ? byId(l) : null;
    }

    private JsonObject send(Account me, JsonObject f) {
        List<String> able = feed.liveWithShops();
        Actions act = actions.get();
        if (able.isEmpty() || act == null) {
            return refused(NO_SERVER);
        }
        String server = able.get(0);
        Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f, Actions.LONG_WAIT_SECONDS);
        forget(null); // whatever it answered, the next read is fresh
        long until = clock.getAsLong() + OWN_MS;
        feed.liveWithShops().forEach(x -> ownUntil.put(x, until)); // each server's shopsAt will move once for this change: not read twice
        JsonObject out = new JsonObject();
        if (ans.data() != null && ans.data().isJsonObject()) {
            ans.data().getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        }
        out.remove("panelLog");
        out.addProperty("ok", ans.ok());
        out.addProperty("message", ans.message() == null ? "" : ans.message());
        out.addProperty("server", server);
        return out;
    }

    /** How deep a shop sits: 0 for a top shop. */
    static int depth(Map<String, JsonObject> byId, String id) {
        int d = 0;
        Set<String> seen = new HashSet<>();
        for (String at = id; at != null && seen.add(at) && d <= MAX_DEPTH; d++) {
            String p = str(byId.get(at), "parent");
            if (p.isEmpty() || !byId.containsKey(p)) {
                return d;
            }
            at = p;
        }
        return d;
    }

    /** How many levels of sections are under a shop: 0 for none. */
    static int height(Map<String, JsonObject> byId, String id) {
        Map<String, List<String>> kids = children(byId);
        int best = 0;
        for (String k : under(kids, id)) {
            best = Math.max(best, depth(byId, k) - depth(byId, id));
        }
        return best;
    }

    private static String name(JsonElement e) {
        String n = prim(e);
        StringBuilder out = new StringBuilder(n.length());
        n.codePoints().forEach(c -> out.appendCodePoint(plain(c) ? c : ' '));
        String t = out.toString().strip();
        return t.isEmpty() || t.length() > NAME_MAX ? null : t;
    }

    private static JsonElement between(JsonElement e, long lo, long hi, java.util.function.LongPredicate also) {
        long n = whole(e);
        return n >= lo && n <= hi && also.test(n) ? new JsonPrimitive(n) : null;
    }

    private static JsonElement hours(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject h = e.getAsJsonObject();
        String mode = str(h, "mode");
        long open = whole(h, "open"), close = whole(h, "close");
        long max = mode.equals("REAL") ? 1_439 : mode.equals("GAME") ? 23_999 : 0;
        if (!Set.of("ALWAYS", "REAL", "GAME").contains(mode)) {
            return null;
        }
        if (mode.equals("ALWAYS")) {
            open = 0;
            close = 0;
        } else if (open < 0 || open > max || close < 0 || close > max) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("mode", mode);
        out.addProperty("open", open);
        out.addProperty("close", close);
        return out;
    }

    private static JsonElement requires(JsonElement e) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonObject r = e.getAsJsonObject();
        String node = str(r, "permission").strip();
        long hours = r.has("playtimeHours") ? whole(r, "playtimeHours") : 0;
        String reason = name(r.get("reason"));
        String rawReason = str(r, "reason").strip();
        if (!node.isEmpty() && !NODE.matcher(node).matches() || hours < 0 || hours > 100_000 || !rawReason.isEmpty() && reason == null) {
            return null;
        }
        if (reason != null && reason.length() > REASON_MAX) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("permission", node);
        out.addProperty("playtimeHours", hours);
        if (r.has("hide") && !(r.get("hide").isJsonPrimitive() && r.get("hide").getAsJsonPrimitive().isBoolean())) {
            return null;
        }
        out.addProperty("hide", r.has("hide") && r.get("hide").getAsBoolean());
        out.addProperty("reason", reason == null ? "" : reason);
        return out;
    }

    /** A character a name, a note or a log line may hold: no control (C0 or C1), format or line-separating character, no § colour code (audit). */
    public static boolean plain(int c) {
        int t = Character.getType(c);
        return c != '§' && t != Character.CONTROL && t != Character.FORMAT && t != Character.LINE_SEPARATOR && t != Character.PARAGRAPH_SEPARATOR;
    }

    /** Text from a game server's answer as it may go into the panel's log: ids only, else "?". */
    static String safe(String s) {
        return s != null && s.matches("[A-Za-z0-9_:./-]{1,128}") ? s : "?";
    }

    /** A value as one short line for the panel's log. */
    private static String said(JsonElement v) {
        String s = v.isJsonPrimitive() ? v.getAsString() : v.toString();
        s = s.codePoints().map(c -> plain(c) ? c : ' ').collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static String prim(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    static long whole(JsonObject o, String k) {
        return whole(o.get(k));
    }

    static long whole(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return Long.MIN_VALUE;
        }
        double v = e.getAsDouble();
        return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : Long.MIN_VALUE;
    }

    /** The panel's own refusals (a staff member's reads allowance, the panel busy): never kept for everyone (audit). */
    public static boolean ownRefusal(String message) {
        return message != null && (message.startsWith("That is ") && message.contains(" in a minute") || message.startsWith("The panel is waiting"));
    }

    private static boolean own(JsonObject out) {
        return ownRefusal(str(out, "message"));
    }

    private static String num(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static JsonObject logged(JsonObject out, String line) {
        out.addProperty("panelLog", line);
        return out;
    }

    // ------------------------------------------------------------------ the cards (SU-163)

    public static final int MAX_CARDS = 2_000, NOTE_LINES = 3, NOTE_CHARS = 60, NBT_MAX = 4_096;
    public static final long MAX_PRICE = 1_000_000_000_000L, MAX_LIMIT = 1_000_000_000L;
    /** The most coins a card may cost or pay (BSP-Core pays in copper up to Integer.MAX_VALUE; audit): a typo never pays out billions. */
    public static final long MAX_COINS = 10_000L;
    static final Pattern RULE = Pattern.compile("never|same|rotation|[1-9][0-9]{0,5}[mhd]|(day|monday|tuesday|wednesday|thursday|friday|saturday|sunday) ([01][0-9]|2[0-3]):[0-5][0-9]");
    static final Set<String> COUNTING = Set.of("count", "match", "stockGlobal", "stockPlayer", "sellGlobal", "sellPlayer", "stockRefill", "stockPlayerRefill", "sellRefill", "sellPlayerRefill");
    static final Map<String, String> CARD_WORDS = Map.ofEntries(Map.entry("count", "the lot size"), Map.entry("buy", "the buy price"), Map.entry("sell", "the sell price"),
            Map.entry("currency", "what it is paid in"), Map.entry("instant", "instant"), Map.entry("hidden", "hidden"), Map.entry("match", "what selling takes"),
            Map.entry("weight", "the weight"), Map.entry("note", "the note"), Map.entry("stockGlobal", "everyone's stock"), Map.entry("stockPlayer", "each player's stock"),
            Map.entry("sellGlobal", "everyone's sell room"), Map.entry("sellPlayer", "each player's sell room"), Map.entry("stockRefill", "when everyone's stock fills"),
            Map.entry("stockPlayerRefill", "when each player's stock fills"), Map.entry("sellRefill", "when everyone's sell room fills"),
            Map.entry("sellPlayerRefill", "when each player's sell room fills"), Map.entry("requires", "what it needs"));

    /** An item added to a shop's page (D798, D799): {@code shop}, {@code item}, {@code v}, {@code nbt}, {@code count}, {@code at} (its place, sent as {@code place}; past the end for the end), {@code edited}. */
    public JsonObject addCard(Account me, JsonObject b) {
        String id = str(b, "shop"), item = str(b, "item"), v = str(b, "v"), nbt = str(b, "nbt");
        String tried = "tried to add " + (ITEM.matcher(item).matches() ? item : "an item") + " to " + (ID.matcher(id).matches() ? id : "?");
        CardShop got = cardShop(me, b, tried);
        if (got.refusal() != null) {
            return got.refusal();
        }
        JsonObject shop = got.shop();
        int cards = shop.getAsJsonArray("cards").size();
        long at = whole(b, "at"), count = whole(b, "count");
        if (!ITEM.matcher(item).matches() || !v.isEmpty() && !ITEM.matcher(v).matches()) {
            return logged(refused("That is not an item id."), tried);
        }
        if (nbt.length() > NBT_MAX || nbt.chars().anyMatch(c -> c < 32 && c != '\t')) {
            return logged(refused("That item's data is too long to put on a card."), tried);
        }
        if (cards >= MAX_CARDS) {
            return logged(refused("A shop holds at most " + MAX_CARDS + " cards."), tried);
        }
        JsonObject f = action("shop_item_add");
        f.addProperty("shop", id);
        f.addProperty("item", item);
        if (!v.isEmpty()) {
            f.addProperty("v", v);
        }
        if (!nbt.isEmpty()) {
            f.addProperty("nbt", nbt);
        }
        if (b.has("count") && (count < 1 || count > 64)) {
            return logged(refused("A lot is 1 to 64 items."), tried);
        }
        f.addProperty("count", b.has("count") ? count : 1);
        f.addProperty("place", at < 0 || at > cards ? cards : at); // not "at": the envelope's time is that
        f.addProperty("edited", whole(b, "edited"));
        return logged(send(me, f), "added " + item + (v.isEmpty() ? "" : " (" + v + ")") + " to " + id + " at card " + ((at < 0 || at > cards ? cards : at) + 1));
    }

    /** One field of a card (D800, D801, D802): {@code shop}, {@code index}, {@code field}, {@code value}, {@code edited}. */
    public JsonObject setCard(Account me, JsonObject b) {
        String id = str(b, "shop"), field = str(b, "field");
        long index = whole(b, "index");
        String tried = "tried to change " + (CARD_WORDS.containsKey(field) ? CARD_WORDS.get(field) : "a card") + " of card " + (index >= 0 ? index + 1 : "?") + " in " + (ID.matcher(id).matches() ? id : "?");
        CardShop got = cardShop(me, b, tried);
        if (got.refusal() != null) {
            return got.refusal();
        }
        JsonObject shop = got.shop();
        JsonObject card = card(shop, index);
        if (card == null) {
            return logged(refused("There is no such card now: read the shop again."), tried);
        }
        if (!CARD_WORDS.containsKey(field)) {
            return logged(refused("That is not a card setting."), tried);
        }
        boolean rotating = str(shop, "kind").equals("rotating");
        JsonElement value = b.get("value"), put = null;
        String why = null;
        switch (field) {
            case "count" -> put = between(value, 1, 64, n -> true);
            case "buy", "sell" -> {
                boolean coins = card.has("currency") && whole(card, "currency") >= 0;
                put = between(value, -1, coins ? MAX_COINS : MAX_PRICE, n -> true);
                why = put == null && coins ? "A price in coins is at most " + num(MAX_COINS) + " coins; empty for off." : null;
            }
            case "currency" -> {
                put = between(value, -1, 4, n -> true);
                if (put != null && put.getAsLong() >= 0 && (whole(card, "buy") > MAX_COINS || whole(card, "sell") > MAX_COINS)) {
                    put = null;
                    why = "A price in coins is at most " + num(MAX_COINS) + " coins: lower the prices first.";
                }
            }
            case "weight" -> put = between(value, 0, 100, n -> true);
            case "stockGlobal", "stockPlayer", "sellGlobal", "sellPlayer" -> put = between(value, -1, MAX_LIMIT, n -> true);
            case "instant", "hidden" -> put = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.deepCopy() : null;
            case "match" -> put = Set.of("exact", "item").contains(prim(value)) ? new JsonPrimitive(prim(value)) : null;
            case "note" -> {
                put = note(value);
                why = put == null ? "Up to " + NOTE_LINES + " lines of up to " + NOTE_CHARS + " characters." : null;
            }
            case "requires" -> {
                put = requires(value);
                why = put == null ? "A LuckPerms node (letters, digits, . _ - *), 0 to 100,000 hours played, and a reason of up to " + REASON_MAX + " characters." : null;
            }
            default -> { // the four refill rules
                String r = prim(value).toLowerCase(Locale.ROOT).strip();
                boolean perPlayer = field.equals("stockPlayerRefill") || field.equals("sellPlayerRefill");
                if (!RULE.matcher(r).matches()) {
                    why = "Never, after a time (90m, 12h, 2d), every day or weekday at a time (monday 06:00)" + (rotating ? ", each rotation" : "") + (perPlayer ? ", or as everyone's" : "") + ".";
                } else if (r.equals("same") && !perPlayer) {
                    why = "Only a player's count can follow everyone's.";
                } else if (r.equals("rotation") && !rotating) {
                    why = "Only a rotating shop's cards fill at each draw.";
                } else {
                    put = new JsonPrimitive(r);
                }
            }
        }
        if (put == null) {
            return logged(refused(why != null ? why : "That value does not fit " + CARD_WORDS.get(field) + "."), tried);
        }
        if (!str(card, "section").isEmpty() && !Set.of("hidden", "requires").contains(field)) {
            return logged(refused("A section's card only opens the section: hide it, or set what it needs."), tried);
        }
        JsonObject f = action("shop_item_set");
        f.addProperty("shop", id);
        f.addProperty("index", index);
        f.addProperty("field", field);
        f.add("value", put);
        f.addProperty("edited", whole(b, "edited"));
        JsonObject out = send(me, f);
        if (COUNTING.contains(field)) {
            out.addProperty("countsRestart", true); // what is counted, how much or when it fills: the card's counts start again, as in game
        }
        return logged(out, "changed " + CARD_WORDS.get(field) + " of card " + (index + 1) + " (" + safe(str(card, "item")) + ") in " + id + " to " + said(put));
    }

    /** A card moved (D799): {@code shop}, {@code from}, {@code to}, {@code edited}; the cards between start their counts again, as in game. */
    public JsonObject moveCard(Account me, JsonObject b) {
        String id = str(b, "shop");
        long from = whole(b, "from"), to = whole(b, "to");
        String tried = "tried to move a card in " + (ID.matcher(id).matches() ? id : "?");
        CardShop got = cardShop(me, b, tried);
        if (got.refusal() != null) {
            return got.refusal();
        }
        JsonObject shop = got.shop();
        int cards = shop.getAsJsonArray("cards").size();
        if (from < 0 || from >= cards || to < 0 || to >= cards || from == to) {
            return logged(refused("There is no such card now: read the shop again."), tried);
        }
        JsonObject f = action("shop_item_move");
        f.addProperty("shop", id);
        f.addProperty("from", from);
        f.addProperty("to", to);
        f.addProperty("edited", whole(b, "edited"));
        return logged(send(me, f), "moved card " + (from + 1) + " (" + safe(str(card(shop, from), "item")) + ") to " + (to + 1) + " in " + id);
    }

    /** A card removed (D799): {@code shop}, {@code index}, {@code edited}. A section's card goes; the section stays. */
    public JsonObject removeCard(Account me, JsonObject b) {
        return oneCard(me, b, "shop_item_remove", "removed");
    }

    /** Every count of a card full again now (REFILL NOW): {@code shop}, {@code index}, {@code edited}. */
    public JsonObject refillCard(Account me, JsonObject b) {
        return oneCard(me, b, "shop_item_refill", "refilled");
    }

    private JsonObject oneCard(Account me, JsonObject b, String action, String verb) {
        String id = str(b, "shop");
        long index = whole(b, "index");
        String tried = "tried to " + (verb.equals("removed") ? "remove" : "refill") + " card " + (index >= 0 ? index + 1 : "?") + " in " + (ID.matcher(id).matches() ? id : "?");
        CardShop got = cardShop(me, b, tried);
        if (got.refusal() != null) {
            return got.refusal();
        }
        JsonObject shop = got.shop();
        JsonObject card = card(shop, index);
        if (card == null) {
            return logged(refused("There is no such card now: read the shop again."), tried);
        }
        JsonObject f = action(action);
        f.addProperty("shop", id);
        f.addProperty("index", index);
        f.addProperty("edited", whole(b, "edited"));
        return logged(send(me, f), verb + " card " + (index + 1) + " (" + safe(str(card, "item")) + (str(card, "section").isEmpty() ? "" : ", the card of section " + safe(str(card, "section"))) + ") in " + id);
    }

    /** The checks every card change makes first: the rank, the id, the stamp, and the shop as read; a refusal (it has {@code ok}) or the shop. */
    /** A refusal, or the shop as read (never told apart by a key a game server could send; audit). */
    private record CardShop(JsonObject refusal, JsonObject shop) {
    }

    private CardShop cardShop(Account me, JsonObject b, String tried) {
        if (!me.rank.atLeast(com.mrgregles.bsp_staff_panel.auth.Rank.ADMIN)) {
            return new CardShop(logged(refused(ADMINS), tried), null);
        }
        String id = str(b, "shop");
        if (!ID.matcher(id).matches() || whole(b, "edited") < 0) {
            return new CardShop(logged(refused("Read the shop again first."), tried), null);
        }
        JsonObject r = read(me, id, false);
        if (!ok(r) || !r.get("shop").isJsonObject() || !r.getAsJsonObject("shop").has("cards") || !r.getAsJsonObject("shop").get("cards").isJsonArray()) {
            return new CardShop(logged(refused(ok(r) ? "The shop could not be read: read it again." : str(r, "message")), tried), null);
        }
        return new CardShop(null, r.getAsJsonObject("shop"));
    }

    private static JsonObject card(JsonObject shop, long index) {
        JsonArray cards = shop.getAsJsonArray("cards");
        return index >= 0 && index < cards.size() && cards.get((int) index).isJsonObject() ? cards.get((int) index).getAsJsonObject() : null;
    }

    private static JsonElement note(JsonElement e) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() > NOTE_LINES) {
            return null;
        }
        JsonArray out = new JsonArray();
        for (JsonElement l : e.getAsJsonArray()) {
            if (!l.isJsonPrimitive()) {
                return null;
            }
            String t = l.getAsString().codePoints().map(c -> !plain(c) || c == '|' ? ' ' : c)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
            if (t.length() > NOTE_CHARS) {
                return null;
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ export (SU-164)

    /**
     * A shop with every section under it as BSP-Core's import file (format 1, D809): {@code json} (the file's content),
     * {@code shops}, {@code cards}, and {@code file} (a name for it). Every rank; a read, never kept, nothing logged.
     */
    public JsonObject export(Account me, String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return refused("That is not a shop's id.");
        }
        JsonObject f = action("shop_export");
        f.addProperty("shop", id);
        JsonObject out = askOne(me, f);
        if (ok(out) && (!out.has("json") || !out.get("json").isJsonObject())) {
            return refused("The game server's answer had no file in it.");
        }
        if (ok(out)) {
            out.addProperty("file", id + "-" + java.time.Instant.ofEpochMilli(clock.getAsLong()).atZone(java.time.ZoneOffset.UTC).toLocalDate() + ".json");
        }
        return out;
    }

    // ------------------------------------------------------------------ reads kept

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
        JsonObject out = askOne(me, action("shop_list"));
        if (ok(out) && out.has("shops") && out.get("shops").isJsonArray()) {
            listFailed = null;
            synchronized (lock) {
                if (began == gen) {
                    list = new Read(now, str(out, "server"), out.deepCopy());
                }
            }
        } else {
            if (ok(out)) {
                out = refused("The game server's answer had no shops in it.");
            }
            if (!own(out)) {
                listFailed = new Read(now, "", out.deepCopy());
            }
        }
        return out;
    }

    private JsonObject read(Account me, String id, boolean refresh) {
        long now = clock.getAsLong();
        Read held = shops.get(id);
        if (!refresh && held != null && now - held.at < FRESH_MS) {
            return held.data.deepCopy();
        }
        Read f = shopFailed.get(id);
        if (!refresh && f != null && now - f.at < RETRY_MS) {
            return f.data.deepCopy();
        }
        long began = gen;
        JsonObject a = action("shop_read");
        a.addProperty("shop", id); // not "id": the action's own id is that
        JsonObject out = askOne(me, a);
        if (ok(out) && out.has("shop") && out.get("shop").isJsonObject()) {
            shopFailed.remove(id);
            synchronized (lock) {
                if (began == gen) {
                    shops.put(id, new Read(now, str(out, "server"), out.deepCopy()));
                }
            }
        } else {
            if (ok(out)) {
                out = refused("The game server's answer had no shop in it.");
            }
            if (!own(out) && shopFailed.size() < 500) {
                shopFailed.put(id, new Read(now, "", out.deepCopy()));
            }
        }
        return out;
    }

    /**
     * Every server's stalls: {@code servers} by name, each {@code {live, heardAt, venues}}, live ones read now when their
     * copy is old, servers that stopped with what they last sent; {@code errors}, servers that did not answer, with why.
     */
    private JsonObject venuesAll(Account me, boolean refresh) {
        long now = clock.getAsLong();
        List<String> able = feed.liveWithShops();
        List<String> stale = able.stream().filter(s -> {
            Read r = venues.get(s), f = venuesFailed.get(s);
            return (refresh || r == null || now - r.at >= FRESH_MS) && (f == null || now - f.at >= RETRY_MS);
        }).toList();
        JsonObject errors = new JsonObject();
        Actions act = actions.get();
        if (!stale.isEmpty() && act != null) {
            long began = gen;
            Map<String, Actions.Answer> answers = act.readAll(stale, me.mcName, me.rank.name(), action("shop_venues"));
            for (Map.Entry<String, Actions.Answer> e : answers.entrySet()) {
                Actions.Answer a = e.getValue();
                if (a.ok() && a.data() != null && a.data().isJsonObject() && a.data().getAsJsonObject().has("venues")
                        && a.data().getAsJsonObject().get("venues").isJsonArray()) {
                    venuesFailed.remove(e.getKey());
                    if (began == gen || !venues.containsKey(e.getKey())) {
                        venues.put(e.getKey(), new Read(now, e.getKey(), a.data().getAsJsonObject().deepCopy()));
                    }
                } else {
                    JsonObject why = new JsonObject();
                    why.addProperty("message", a.ok() ? "Its answer had no stalls in it." : a.message());
                    if (!ownRefusal(why.get("message").getAsString())) {
                        venuesFailed.put(e.getKey(), new Read(now, e.getKey(), why));
                    } else {
                        errors.addProperty(e.getKey(), why.get("message").getAsString());
                    }
                }
            }
        }
        for (String s : able) {
            Read f = venuesFailed.get(s);
            if (f != null && now - f.at < RETRY_MS) {
                errors.addProperty(s, str(f.data, "message"));
            }
        }
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(venues.keySet());
        JsonObject servers = new JsonObject();
        for (String s : names) {
            Read r = venues.get(s);
            if (r == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("live", able.contains(s));
            o.addProperty("heardAt", lastLive.getOrDefault(s, r.at));
            JsonElement vs = r.data.get("venues");
            o.add("venues", vs != null && vs.isJsonArray() ? vs.deepCopy() : new JsonArray());
            servers.add(s, o);
        }
        JsonObject out = new JsonObject();
        out.add("servers", servers);
        out.add("errors", errors);
        return out;
    }

    private JsonObject askOne(Account me, JsonObject f) {
        return askOne(me, f, 0);
    }

    private JsonObject askOne(Account me, JsonObject f, int wait) {
        List<String> able = feed.liveWithShops();
        Actions act = actions.get();
        if (able.isEmpty() || act == null) {
            return refused(NO_SERVER);
        }
        String server = able.get(0);
        Actions.Answer a = wait > 0 ? act.read(server, me.mcName, me.rank.name(), f, wait) : act.read(server, me.mcName, me.rank.name(), f);
        JsonObject out = new JsonObject();
        if (a.data() != null && a.data().isJsonObject()) {
            a.data().getAsJsonObject().entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        }
        out.remove("panelLog"); // only the desk writes the panel's log, never a game server's answer (audit)
        out.addProperty("ok", a.ok());
        out.addProperty("message", a.message() == null ? "" : a.message());
        out.addProperty("server", server);
        out.addProperty("at", clock.getAsLong());
        return out;
    }

    // ------------------------------------------------------------------ the tree

    /** Every shop in a list answer by id (a malformed entry is left out). */
    public static Map<String, JsonObject> byId(JsonObject list) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (JsonElement e : list.getAsJsonArray("shops")) {
            if (e.isJsonObject() && ID.matcher(str(e.getAsJsonObject(), "id")).matches()) {
                out.put(str(e.getAsJsonObject(), "id"), e.getAsJsonObject());
            }
        }
        return out;
    }

    /**
     * The top-level shop above {@code id}: up its parents until one has no parent or a missing one. BSP-Core refuses loops,
     * but should one arrive, every shop in it gets the same top (the loop's first id A to Z), so none is lost from the grid;
     * a chain deeper than {@link #MAX_DEPTH} stops there. Null when {@code id} is not a shop.
     */
    public static String root(Map<String, JsonObject> byId, String id) {
        if (!byId.containsKey(id)) {
            return null;
        }
        List<String> path = new ArrayList<>();
        String at = id;
        for (int i = 0; i <= MAX_DEPTH; i++) {
            path.add(at);
            String p = str(byId.get(at), "parent");
            if (p.isEmpty() || !byId.containsKey(p)) {
                return at;
            }
            int loop = path.indexOf(p);
            if (loop >= 0) {
                return path.subList(loop, path.size()).stream().min(Comparator.naturalOrder()).orElse(at);
            }
            at = p;
        }
        return at;
    }

    /** Each shop's children by name, A to Z (a shop under a loop or a missing parent is its own top). */
    static Map<String, List<String>> children(Map<String, JsonObject> byId) {
        Map<String, List<String>> out = new HashMap<>();
        for (JsonObject s : byId.values()) {
            String id = str(s, "id"), p = str(s, "parent");
            if (!p.isEmpty() && byId.containsKey(p) && !root(byId, id).equals(id)) {
                out.computeIfAbsent(p, k -> new ArrayList<>()).add(id);
            }
        }
        out.values().forEach(l -> l.sort(Comparator.comparing((String k) -> str(byId.get(k), "name").toLowerCase(Locale.ROOT)).thenComparing(k -> k)));
        return out;
    }

    /** Every shop under {@code top}, depth first, never deeper than {@link #MAX_DEPTH}. */
    static List<String> under(Map<String, List<String>> kids, String top) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(top);
        under(kids, top, 1, seen, out);
        return out;
    }

    private static void under(Map<String, List<String>> kids, String at, int depth, Set<String> seen, List<String> out) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (String k : kids.getOrDefault(at, List.of())) {
            if (seen.add(k)) {
                out.add(k);
                under(kids, k, depth + 1, seen, out);
            }
        }
    }

    private static void walk(Map<String, JsonObject> byId, Map<String, List<String>> kids, String id, int depth, Set<String> seen, JsonArray out) {
        if (depth > MAX_DEPTH || !seen.add(id)) {
            return;
        }
        JsonObject s = byId.get(id);
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", str(s, "name"));
        o.addProperty("kind", str(s, "kind"));
        o.add("cards", s.has("cards") ? s.get("cards") : null);
        o.addProperty("depth", depth);
        out.add(o);
        for (String k : kids.getOrDefault(id, List.of())) {
            walk(byId, kids, k, depth + 1, seen, out);
        }
    }

    // ------------------------------------------------------------------ small parts

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
