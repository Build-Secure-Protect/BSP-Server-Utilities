package com.mrgregles.bsp_staff_panel.regions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;

import java.util.ArrayList;
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
 * The WORLDS tab and the region editor (SU-155; D762 to D777, set BC A and BD A refined), through the game servers'
 * {@code region_*} actions: each server answers for its own worlds through BSP Region Guard (D762; requests SU-RG3 to
 * SU-RG5).
 * <ul>
 * <li>Reads for every rank (D769): {@code regions_list} on each server whose snapshot says {@code regions}. An answer is
 * kept for {@link #FRESH_MS} and shared by every staff member (D775, until Region Guard's change event); a server that
 * stops sending keeps its last answer, shown as such, and its tile greyed with when it was last heard from.</li>
 * <li>Changes for Admins and Owners (D769), checked here before signing and again by the game server: a save of what
 * changed with the version it was read at (D773), a removal (any kind but the global region; a claim for Owners only,
 * D772) and a mine's reset. Every field is checked here so nothing malformed is signed; the answer forgets that
 * server's answer, so the next read is fresh. Each change carries {@code panelLog}, the line for the panel's own log
 * (D774), taken off before the page sees it.</li>
 * </ul>
 */
public final class RegionDesk {
    public static final String ADMINS = "Changing regions is for Admins and Owners.";
    public static final String CLAIM_OWNERS = "Removing a player's claim is for Owners.";
    static final String GLOBAL_STAYS = "The global region is the whole world: it cannot be removed.";
    static final String NOT_SENDING = "%s is not sending to the panel now, or its Region Guard does not answer for regions.";
    /** How long a server's answer is used before it is read again (D775: every 60 seconds until Region Guard says when a region changes). */
    public static final long FRESH_MS = 60_000L;
    public static final String GLOBAL = "__global__";
    static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    static final Pattern DIM = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    static final Pattern FLAG = Pattern.compile("[a-z0-9-]{1,48}");
    static final Pattern PLAYER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern BLOCK = DIM;
    /** The kinds a region can be switched between with the editor (Region Guard's {@code /rg type}); claims, mines and the global region stay what they are. */
    static final Set<String> SWITCHABLE = Set.of("protected", "plot_for_sale", "open_for_buying");
    /** The lists of player flags plots, open areas and claims keep beside their own flags (the visitors') . */
    static final Set<String> AUDIENCES = Set.of("members", "owners");
    public static final int MAX_TEXT = 256, MAX_SET = 64, MAX_FILL = 32, MAX_PEOPLE = 32, MAX_INDEX = 3000;
    static final long MAX_XZ = 30_000_000L;
    /** Minecraft's own limits for a world's height. */
    static final long MIN_Y = -2032, MAX_Y = 2031;

    private record Read(long at, long heardAt, JsonObject data) {
    }

    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;
    private final Map<String, Read> reads = new ConcurrentHashMap<>();
    /** Bumped by every change: a read that began before a change is not kept (audit: a slow read put old data back). */
    private final Map<String, Long> gens = new ConcurrentHashMap<>();
    /** A server whose read failed, with when and why: asked again only after {@link #RETRY_MS} (audit: every grid load waited on it). */
    private final Map<String, Read> failed = new ConcurrentHashMap<>();
    static final long RETRY_MS = 20_000L;
    /** When each server was last seen sending, this run: a server that stops keeps its tile, greyed (R1). */
    private final Map<String, Long> lastLive = new ConcurrentHashMap<>();

    public RegionDesk(Feed feed, Supplier<Actions> actions, LongSupplier clock) {
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
        feed.onChange(this::heard); // a server that stops is kept with when it was really last heard from (audit)
    }

    private void heard() {
        for (String s : feed.live()) {
            JsonObject n = feed.numbers(s);
            if (n != null) {
                lastLive.put(s, n.get("heardAt").getAsLong());
            }
        }
    }

    // ------------------------------------------------------------------ reading

    /** The WORLDS grid: every server live now or heard from this run, with its dimensions and its regions counted by kind. */
    public JsonObject servers(Account me) {
        long now = clock.getAsLong();
        List<String> live = feed.live(), able = feed.liveWithRegions();
        heard();
        List<String> stale = able.stream().filter(s -> !fresh(s, now) && !failedLately(s, now)).toList();
        Map<String, Actions.Answer> answers = new java.util.HashMap<>();
        Actions act = actions.get();
        if (!stale.isEmpty() && act != null) {
            Map<String, Long> began = new java.util.HashMap<>();
            stale.forEach(s -> began.put(s, gens.getOrDefault(s, 0L)));
            answers.putAll(act.readAll(stale, me.mcName, me.rank.name(), action("regions_list")));
            for (Map.Entry<String, Actions.Answer> e : answers.entrySet()) {
                keep(e.getKey(), e.getValue(), now, began.get(e.getKey()));
            }
        }
        for (String s : able) { // a failure still being waited out is said again, not asked again
            Read f = failed.get(s);
            if (!answers.containsKey(s) && f != null && now - f.at < RETRY_MS) {
                answers.put(s, new Actions.Answer(false, f.data.get("message").getAsString()));
            }
        }
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(live);
        names.addAll(reads.keySet());
        names.addAll(lastLive.keySet());
        JsonArray list = new JsonArray();
        for (String s : names) {
            JsonObject o = new JsonObject();
            o.addProperty("server", s);
            JsonObject n = feed.numbers(s);
            o.addProperty("live", n != null);
            o.addProperty("regions", able.contains(s));
            if (n != null) {
                o.add("mspt", n.get("mspt"));
                o.add("count", n.get("count"));
            }
            Long seen = lastLive.get(s);
            Read r = reads.get(s);
            o.addProperty("heardAt", seen != null ? seen : r != null ? r.heardAt : 0);
            Actions.Answer a = answers.get(s);
            if (a != null && !a.ok()) {
                o.addProperty("error", a.message());
            } else if (n != null && !able.contains(s)) {
                o.addProperty("error", "Its Region Guard does not answer for regions (it needs BSP Region Guard with the panel's api and this mod's region actions).");
            }
            if (r != null) {
                o.addProperty("readAt", r.at);
                summary(r.data, o);
            }
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("now", now);
        out.add("servers", list);
        return out;
    }

    /** One server's dimensions, regions and flag list; read again when older than {@link #FRESH_MS} or when {@code refresh}. */
    public JsonObject server(Account me, String server, boolean refresh) {
        long now = clock.getAsLong();
        String name = liveName(server);
        if (name != null && (refresh || !fresh(name, now))) {
            Actions act = actions.get();
            if (act != null) {
                long began = gens.getOrDefault(name, 0L);
                Actions.Answer a = act.read(name, me.mcName, me.rank.name(), action("regions_list"));
                keep(name, a, now, began);
                if (!a.ok() && !reads.containsKey(name)) {
                    return refused(a.message());
                }
                if (!a.ok()) {
                    return page(name, true, "Reading again failed: " + a.message() + " What is shown was read earlier.");
                }
            }
        }
        if (name == null) {
            String kept = reads.keySet().stream().filter(s -> s.equalsIgnoreCase(server)).findFirst().orElse(null);
            if (kept == null) {
                return refused(NOT_SENDING.formatted(safe(server)));
            }
            return page(kept, false, kept + " is not sending to the panel now: what is shown was read earlier, and nothing can be changed until it is back.");
        }
        return page(name, true, "");
    }

    private JsonObject page(String server, boolean live, String note) {
        Read r = reads.get(server);
        if (r == null) {
            return refused(NOT_SENDING.formatted(safe(server)));
        }
        JsonObject out = r.data.deepCopy();
        out.addProperty("ok", true);
        out.addProperty("server", server);
        out.addProperty("live", live);
        out.addProperty("readAt", r.at);
        out.addProperty("now", clock.getAsLong());
        if (!note.isEmpty()) {
            out.addProperty("note", note);
        }
        return out;
    }

    private boolean fresh(String server, long now) {
        Read r = reads.get(server);
        return r != null && now - r.at < FRESH_MS;
    }

    private void keep(String server, Actions.Answer a, long now, Long began) {
        if (a.ok() && a.data() != null && a.data().isJsonObject()) {
            failed.remove(server);
            if (began == null || gens.getOrDefault(server, 0L).equals(began)) { // not if a change was made meanwhile
                reads.put(server, new Read(now, now, a.data().getAsJsonObject().deepCopy()));
            }
        } else if (!a.ok()) {
            JsonObject why = new JsonObject();
            why.addProperty("message", a.message());
            failed.put(server, new Read(now, now, why));
        }
    }

    private boolean failedLately(String server, long now) {
        Read f = failed.get(server);
        return f != null && now - f.at < RETRY_MS;
    }

    /** A live server whose Region Guard answers, by the name the feed knows it by; null when there is none. */
    private String liveName(String server) {
        if (server == null) {
            return null;
        }
        for (String s : feed.liveWithRegions()) {
            if (s.equalsIgnoreCase(server)) {
                return s;
            }
        }
        return null;
    }

    /** The grid's counts: every dimension with its regions by kind (the global region and mines' shells not counted). */
    static void summary(JsonObject data, JsonObject into) {
        Map<String, JsonObject> dims = new java.util.LinkedHashMap<>();
        if (data.has("dims") && data.get("dims").isJsonArray()) {
            for (JsonElement d : data.getAsJsonArray("dims")) {
                if (d.isJsonObject() && !str(d.getAsJsonObject(), "id").isEmpty()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", str(d.getAsJsonObject(), "id"));
                    o.add("counts", new JsonObject());
                    dims.put(o.get("id").getAsString(), o);
                    if (bool(d.getAsJsonObject(), "pvp") && o.get("id").getAsString().equals("minecraft:overworld")) {
                        into.addProperty("pvp", true);
                    }
                }
            }
        }
        JsonObject all = new JsonObject();
        JsonArray index = new JsonArray(); // the grid's search: id, name, kind and dimension of each region (R1)
        if (data.has("regions") && data.get("regions").isJsonArray()) {
            for (JsonElement e : data.getAsJsonArray("regions")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject r = e.getAsJsonObject();
                String type = str(r, "type"), dim = str(r, "dim");
                if (type.equals("global") || r.has("shellOf")) {
                    continue;
                }
                JsonObject d = dims.computeIfAbsent(dim, k -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", k);
                    o.add("counts", new JsonObject());
                    return o;
                });
                bump(d.getAsJsonObject("counts"), type);
                bump(all, type);
                if (index.size() < MAX_INDEX) {
                    JsonArray one = new JsonArray();
                    one.add(str(r, "id"));
                    one.add(str(r, "name"));
                    one.add(type);
                    one.add(dim);
                    index.add(one);
                }
            }
        }
        JsonArray list = new JsonArray();
        dims.values().forEach(list::add);
        into.add("dims", list);
        into.add("counts", all);
        into.add("index", index);
    }

    private static void bump(JsonObject counts, String type) {
        counts.addProperty(type, (counts.has(type) ? counts.get(type).getAsInt() : 0) + 1);
    }

    /** A server's answer kept as if read now, with when it was last heard from (the demo's server that is off). */
    public void remember(String server, long heardAt, JsonObject data) {
        reads.put(server, new Read(heardAt, heardAt, data.deepCopy()));
        lastLive.put(server, heardAt);
    }

    /** After a change: that server's next read is fresh. */
    public void forget(String server) {
        gens.merge(server, 1L, Long::sum);
        reads.computeIfPresent(server, (k, r) -> new Read(0, r.heardAt, r.data));
    }

    /**
     * The region as the game server says it is now: from a read under {@link #FRESH_MS} old, else read again first
     * (audit: a rule that depends on a region's kind never passes for want of a read). Null when it is not there.
     */
    private JsonObject known(Account me, String server, String dim, String id) {
        if (!fresh(server, clock.getAsLong())) {
            Actions act = actions.get();
            if (act != null) {
                long began = gens.getOrDefault(server, 0L);
                keep(server, act.read(server, me.mcName, me.rank.name(), action("regions_list")), clock.getAsLong(), began);
            }
            if (!fresh(server, clock.getAsLong())) {
                return null;
            }
        }
        return cachedRegion(server, dim, id);
    }

    /** The region as the last read gave it, or null. */
    private JsonObject cachedRegion(String server, String dim, String id) {
        Read r = reads.get(server);
        if (r == null || !r.data.has("regions") || !r.data.get("regions").isJsonArray()) {
            return null;
        }
        for (JsonElement e : r.data.getAsJsonArray("regions")) {
            if (e.isJsonObject() && str(e.getAsJsonObject(), "id").equals(id) && str(e.getAsJsonObject(), "dim").equals(dim)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ changing

    /**
     * A save (D773): {@code server}, {@code dim}, {@code id}, {@code version} and {@code set}, what changed: {@code name},
     * {@code newId}, {@code priority}, {@code parent} ("" for none), {@code type}, {@code box} (six whole numbers),
     * {@code flags} (a flag's name to its text, a list of texts, or null to clear), {@code lists} ({@code members} and
     * {@code owners}, each a flag's name to allow, deny or null), {@code owners} and {@code members} ({@code add} names,
     * {@code remove} ids), {@code claim}, {@code mine} and {@code border}.
     */
    public JsonObject save(Account me, JsonObject b) {
        String id = str(b, "id"), server = str(b, "server"), dim = str(b, "dim");
        String tried = "tried to change region " + (validId(id) ? id : "?") + " on " + (server.matches("[A-Za-z0-9 _.-]{1,64}") ? server : "?");
        if (!me.rank.atLeast(Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        if (!validId(id) || !DIM.matcher(dim).matches() || whole(b, "version") < 0) {
            return logged(refused("No such region."), tried);
        }
        String to = liveName(server);
        if (to == null) {
            return logged(refused(NOT_SENDING.formatted(safe(server))), tried);
        }
        if (b.has("set") && !b.get("set").isJsonObject()) {
            return logged(refused("Not a change the panel understands."), tried);
        }
        JsonObject set = b.has("set") ? b.getAsJsonObject("set") : new JsonObject();
        JsonObject now = set.has("type") ? known(me, to, dim, id) : null;
        if (set.has("type") && (now == null || !SWITCHABLE.contains(str(now, "type")))) { // a claim or a mine stays one (audit: switched, then removed)
            return logged(refused(now == null ? "No such region, or its server did not answer: read it again." : "A claim, a mine or the global region keeps its kind."), tried);
        }
        JsonObject clean = new JsonObject();
        List<String> what = new ArrayList<>();
        String why = checkSet(set, clean, what, id.equals(GLOBAL));
        if (why != null) {
            return logged(refused(why), tried);
        }
        if (clean.size() == 0) {
            return refused("Nothing has changed.");
        }
        JsonObject f = action("region_save");
        f.addProperty("dim", dim);
        f.addProperty("region", id); // "id" is the action's own one-time id (Actions' envelope)
        f.addProperty("version", whole(b, "version"));
        f.add("set", clean);
        JsonObject out = send(me, to, f);
        forget(to);
        out.addProperty("panelLog", "changed region " + id + " on " + to + " (" + dim + "): " + String.join(", ", what));
        return out;
    }

    /** {@code server}, {@code dim}, {@code id}, {@code version}: any kind but the global region; a claim for Owners only (D772). */
    public JsonObject remove(Account me, JsonObject b) {
        String id = str(b, "id"), server = str(b, "server"), dim = str(b, "dim");
        String tried = "tried to remove region " + (validId(id) ? id : "?");
        if (!me.rank.atLeast(Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        if (id.equals(GLOBAL)) {
            return logged(refused(GLOBAL_STAYS), tried);
        }
        if (!ID.matcher(id).matches() || !DIM.matcher(dim).matches() || whole(b, "version") < 0) {
            return logged(refused("No such region."), tried);
        }
        String to = liveName(server);
        if (to == null) {
            return logged(refused(NOT_SENDING.formatted(safe(server))), tried);
        }
        JsonObject known = known(me, to, dim, id); // never decided without the region's kind (audit)
        if (known == null) {
            return logged(refused("No such region, or its server did not answer: read it again."), tried);
        }
        if (str(known, "type").equals("global")) {
            return logged(refused(GLOBAL_STAYS), tried);
        }
        if (known.has("shellOf")) {
            return logged(refused("A mine's shell goes with its mine: remove the mine."), tried);
        }
        if (str(known, "type").equals("claim") && me.rank != Rank.OWNER) {
            return logged(refused(CLAIM_OWNERS), tried);
        }
        JsonObject f = action("region_remove");
        f.addProperty("dim", dim);
        f.addProperty("region", id); // "id" is the action's own one-time id (Actions' envelope)
        f.addProperty("version", whole(b, "version"));
        JsonObject out = send(me, to, f);
        forget(to);
        out.addProperty("panelLog", "removed region " + id + " on " + to + " (" + dim + ")");
        return out;
    }

    /** A mine filled again now ({@code server}, {@code dim}, {@code id}): Admins and Owners. */
    public JsonObject resetMine(Account me, JsonObject b) {
        String id = str(b, "id"), server = str(b, "server"), dim = str(b, "dim");
        String tried = "tried to reset mine " + (ID.matcher(id).matches() ? id : "?");
        if (!me.rank.atLeast(Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        if (!ID.matcher(id).matches() || !DIM.matcher(dim).matches()) {
            return logged(refused("No such mine."), tried);
        }
        String to = liveName(server);
        if (to == null) {
            return logged(refused(NOT_SENDING.formatted(safe(server))), tried);
        }
        JsonObject f = action("region_mine_reset");
        f.addProperty("dim", dim);
        f.addProperty("region", id); // "id" is the action's own one-time id (Actions' envelope)
        JsonObject out = send(me, to, f);
        forget(to);
        out.addProperty("panelLog", "reset mine " + id + " on " + to + " (" + dim + ")");
        return out;
    }

    /** The kinds a region drawn on the map can be (D770): claims are bought by players, and each world has one global region. */
    static final Set<String> CREATABLE = Set.of("protected", "mine", "plot_for_sale", "open_for_buying");

    /**
     * Region Guard's checks for a region drawn on the map, without making it (SU-157; Region Guard's dry run, SU-RG5): the
     * same fields as {@link #create}. Admins and Owners; counted as a read, nothing logged.
     */
    public JsonObject check(Account me, JsonObject b) {
        if (!me.rank.atLeast(Rank.ADMIN)) {
            return refused(ADMINS);
        }
        JsonObject f = action("region_check");
        String why = newRegion(b, f);
        if (why != null) {
            JsonObject out = refused(why);
            out.addProperty("panel", true); // the panel's own check, before Region Guard is asked
            return out;
        }
        String to = liveName(str(b, "server"));
        if (to == null) {
            return refused(NOT_SENDING.formatted(safe(str(b, "server"))));
        }
        Actions act = actions.get();
        if (act == null) {
            return refused(NOT_SENDING.formatted(to));
        }
        Actions.Answer a = act.read(to, me.mcName, me.rank.name(), f);
        JsonObject out = new JsonObject();
        out.addProperty("ok", a.ok());
        out.addProperty("message", a.message());
        return out;
    }

    /**
     * A region drawn on the map (SU-157; D770, D771): {@code server}, {@code dim}, {@code id}, {@code type} (a region, a
     * mine, a plot for sale or an open area), {@code box} (six whole numbers), {@code priority}, {@code parent} ("" for none),
     * {@code name}, and for a plot {@code claim.price}, an open area {@code claim.costPerBlock}, {@code minSize},
     * {@code maxSize}, a mine {@code mine.intervalSeconds} and {@code mine.fill}. Admins and Owners; a line in the panel's log.
     */
    public JsonObject create(Account me, JsonObject b) {
        String id = str(b, "id").toLowerCase(Locale.ROOT), server = str(b, "server");
        String tried = "tried to create region " + (ID.matcher(id).matches() ? id : "?") + " on " + (server.matches("[A-Za-z0-9 _.-]{1,64}") ? server : "?");
        if (!me.rank.atLeast(Rank.ADMIN)) {
            return logged(refused(ADMINS), tried);
        }
        JsonObject f = action("region_create");
        String why = newRegion(b, f);
        if (why != null) {
            return logged(refused(why), tried);
        }
        String to = liveName(server);
        if (to == null) {
            return logged(refused(NOT_SENDING.formatted(safe(server))), tried);
        }
        JsonObject out = send(me, to, f);
        forget(to);
        JsonArray box = f.getAsJsonArray("box");
        out.addProperty("panelLog", "created region " + id + " (" + f.get("type").getAsString() + ") on " + to + " (" + f.get("dim").getAsString() + ") from "
                + box.get(0) + ", " + box.get(1) + ", " + box.get(2) + " to " + box.get(3) + ", " + box.get(4) + ", " + box.get(5));
        return out;
    }

    /** Checks a new region's fields and copies them into the action {@code f}; null when fine, else why not. */
    static String newRegion(JsonObject b, JsonObject f) {
        String id = str(b, "id").toLowerCase(Locale.ROOT), dim = str(b, "dim"), type = str(b, "type");
        if (!ID.matcher(id).matches() || id.equals(GLOBAL)) {
            return "An id is 1 to 32 letters, digits, _ or -.";
        }
        if (!DIM.matcher(dim).matches()) {
            return "No such world.";
        }
        if (!CREATABLE.contains(type)) {
            return "A region drawn on the map is a region, a mine, a plot for sale or an open area.";
        }
        JsonObject set = new JsonObject();
        set.add("box", b.get("box"));
        if (b.has("priority")) {
            set.add("priority", b.get("priority"));
        }
        if (b.has("parent")) {
            set.add("parent", b.get("parent"));
        }
        if (b.has("name")) {
            set.add("name", b.get("name"));
        }
        if (b.has("claim") && (type.equals("plot_for_sale") || type.equals("open_for_buying"))) {
            set.add("claim", b.get("claim"));
        }
        if (b.has("mine") && type.equals("mine")) {
            set.add("mine", b.get("mine"));
        }
        JsonObject clean = new JsonObject();
        String why = b.has("box") ? checkSet(set, clean, new ArrayList<>(), false) : "Set both corners on the map.";
        if (why != null) {
            return why;
        }
        f.addProperty("region", id); // "id" is the action's own one-time id
        f.addProperty("dim", dim);
        f.addProperty("type", type);
        for (String k : clean.keySet()) {
            f.add(k, clean.get(k));
        }
        if (!f.has("priority")) {
            f.addProperty("priority", 0);
        }
        if (!f.has("parent")) {
            f.addProperty("parent", "");
        }
        return null;
    }

    /** Checks {@code set} and copies what is allowed into {@code clean}; null when fine, else why not. {@code what}: words for the log. */
    static String checkSet(JsonObject set, JsonObject clean, List<String> what, boolean global) {
        for (String k : set.keySet()) {
            if (!Set.of("name", "newId", "priority", "parent", "type", "box", "flags", "lists", "owners", "members", "claim", "mine", "border").contains(k)) {
                return "Not a setting the panel knows: " + safe(k) + ".";
            }
        }
        if (set.has("name")) {
            String n = text(set, "name");
            if (n == null || n.length() > 64) {
                return "A name is at most 64 characters.";
            }
            clean.addProperty("name", n);
            what.add("name");
        }
        if (set.has("newId")) {
            String n = str(set, "newId").toLowerCase(Locale.ROOT);
            if (global || !ID.matcher(n).matches() || n.equals(GLOBAL)) {
                return global ? "The global region keeps its id." : "An id is 1 to 32 letters, digits, _ or -.";
            }
            clean.addProperty("newId", n);
            what.add("id to " + n);
        }
        if (set.has("priority")) {
            long p = whole(set, "priority");
            if (global || p < -1_000_000 || p > 1_000_000) {
                return global ? "The global region is always the lowest priority." : "A priority is a whole number between -1,000,000 and 1,000,000.";
            }
            clean.addProperty("priority", p);
            what.add("priority " + p);
        }
        if (set.has("parent")) {
            String p = str(set, "parent").toLowerCase(Locale.ROOT);
            if (global || !set.get("parent").isJsonPrimitive() || p.equals(GLOBAL) || !(p.isEmpty() || ID.matcher(p).matches())) {
                return global ? "The global region has no parent." : "Pick a parent from the list.";
            }
            clean.addProperty("parent", p);
            what.add(p.isEmpty() ? "no parent" : "parent " + p);
        }
        if (set.has("type")) {
            String t = str(set, "type");
            if (global || !SWITCHABLE.contains(t)) {
                return "A region can be switched between a region, a plot for sale and an open area only.";
            }
            clean.addProperty("type", t);
            what.add("kind " + t);
        }
        if (set.has("box")) {
            JsonArray box = set.get("box").isJsonArray() ? set.getAsJsonArray("box") : null;
            if (global || box == null || box.size() != 6) {
                return "Give both corners as six whole numbers.";
            }
            long[] v = new long[6];
            for (int i = 0; i < 6; i++) {
                v[i] = whole(box, i);
                boolean y = i == 1 || i == 4;
                if (v[i] == Long.MIN_VALUE || (y ? v[i] < MIN_Y || v[i] > MAX_Y : Math.abs(v[i]) > MAX_XZ)) {
                    return "The corners must be whole numbers inside the world.";
                }
            }
            JsonArray c = new JsonArray();
            for (int i = 0; i < 3; i++) {
                c.add(Math.min(v[i], v[i + 3]));
            }
            for (int i = 0; i < 3; i++) {
                c.add(Math.max(v[i], v[i + 3]));
            }
            clean.add("box", c);
            what.add("bounds");
        }
        if (set.has("flags")) {
            JsonObject fl = set.get("flags").isJsonObject() ? set.getAsJsonObject("flags") : null;
            if (fl == null) {
                return "Flags are sent by name.";
            }
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : fl.entrySet()) {
                String why = flagValue(e.getKey(), e.getValue(), out);
                if (why != null) {
                    return why;
                }
            }
            if (out.size() > 0) {
                clean.add("flags", out);
                what.add("flags " + names(out));
            }
        }
        if (set.has("lists")) {
            JsonObject ls = set.get("lists").isJsonObject() ? set.getAsJsonObject("lists") : null;
            if (ls == null || global) {
                return "Only plots, open areas and claims keep lists for members and owners.";
            }
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> l : ls.entrySet()) {
                if (!AUDIENCES.contains(l.getKey()) || !l.getValue().isJsonObject()) {
                    return "The lists are members and owners.";
                }
                JsonObject one = new JsonObject();
                for (Map.Entry<String, JsonElement> e : l.getValue().getAsJsonObject().entrySet()) {
                    String v = e.getValue().isJsonNull() ? null : e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : "?";
                    if (!FLAG.matcher(e.getKey()).matches() || !(v == null || v.equals("allow") || v.equals("deny"))) {
                        return "A member or owner flag is allow, deny or inherit.";
                    }
                    one.add(e.getKey(), v == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(v));
                }
                if (one.size() > 0) {
                    out.add(l.getKey(), one);
                    what.add(l.getKey() + "' flags " + names(one));
                }
            }
            if (out.size() > 0) {
                clean.add("lists", out);
            }
        }
        for (String who : List.of("owners", "members")) {
            if (!set.has(who)) {
                continue;
            }
            JsonObject p = set.get(who).isJsonObject() ? set.getAsJsonObject(who) : null;
            if (p == null || global) {
                return global ? "The global region has no owners or members." : "Add players by name.";
            }
            JsonArray add = new JsonArray(), rem = new JsonArray();
            for (JsonElement e : arr(p, "add")) {
                String n = e.isJsonPrimitive() ? e.getAsString().strip() : "";
                if (!PLAYER.matcher(n).matches()) {
                    return "A player's name is 1 to 16 letters, digits or _.";
                }
                add.add(n);
            }
            for (JsonElement e : arr(p, "remove")) {
                String u = e.isJsonPrimitive() ? e.getAsString() : "";
                if (!UUID.matcher(u).matches()) {
                    return "Pick the player to take off from the list.";
                }
                rem.add(u.toLowerCase(Locale.ROOT));
            }
            if (add.size() + rem.size() > MAX_PEOPLE) {
                return "At most " + MAX_PEOPLE + " players at a time.";
            }
            if (add.size() + rem.size() > 0) {
                JsonObject o = new JsonObject();
                o.add("add", add);
                o.add("remove", rem);
                clean.add(who, o);
                what.add(who);
            }
        }
        if (set.has("claim")) {
            JsonObject c = set.get("claim").isJsonObject() ? set.getAsJsonObject("claim") : null;
            if (c == null || global) {
                return "Only plots, open areas and claims have these settings.";
            }
            JsonObject out = new JsonObject();
            for (String k : c.keySet()) {
                long v = whole(c, k);
                boolean known = Set.of("price", "costPerBlock", "minSize", "maxSize", "plotLimit").contains(k) || k.equals("coinTier");
                if (!known) {
                    return "Not a plot setting the panel knows: " + safe(k) + ".";
                }
                if (k.equals("coinTier") ? v < -1 || v > 64 : v < 0 || v > 1_000_000_000_000L) {
                    return "Prices, sizes and limits are whole numbers of 0 or more.";
                }
                out.addProperty(k, v);
            }
            if (out.has("minSize") && out.has("maxSize") && out.get("minSize").getAsLong() > out.get("maxSize").getAsLong()) {
                return "The smallest size is above the largest.";
            }
            if (out.size() > 0) {
                clean.add("claim", out);
                what.add("plot settings " + names(out));
            }
        }
        if (set.has("mine")) {
            JsonObject m = set.get("mine").isJsonObject() ? set.getAsJsonObject("mine") : null;
            if (m == null || global) {
                return "Only mines have these settings.";
            }
            JsonObject out = new JsonObject();
            if (m.has("fill")) {
                JsonArray fill = m.get("fill").isJsonArray() ? m.getAsJsonArray("fill") : null;
                if (fill == null || fill.isEmpty() || fill.size() > MAX_FILL) {
                    return "A mine is filled with 1 to " + MAX_FILL + " blocks.";
                }
                JsonArray c = new JsonArray();
                long total = 0;
                Set<String> seen = new java.util.HashSet<>();
                for (JsonElement e : fill) {
                    JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
                    String block = str(o, "block").toLowerCase(Locale.ROOT);
                    long w = whole(o, "weight");
                    if (!BLOCK.matcher(block).matches() || w < 1 || w > 1000 || !seen.add(block)) {
                        return "Each block of the mix is named once, like minecraft:stone, with a share above 0.";
                    }
                    total += w;
                    JsonObject one = new JsonObject();
                    one.addProperty("block", block);
                    one.addProperty("weight", w);
                    c.add(one);
                }
                if (total != 1000) {
                    return "The mix must add up to 100% (it adds up to " + (total / 10.0) + "%).";
                }
                out.add("fill", c);
            }
            if (m.has("intervalSeconds")) {
                long s = whole(m, "intervalSeconds");
                if (s < 10 || s > 7 * 86_400) {
                    return "A mine resets every 10 seconds to 7 days.";
                }
                out.addProperty("intervalSeconds", s);
            }
            if (m.has("spawn")) {
                JsonObject p = m.get("spawn").isJsonObject() ? m.getAsJsonObject("spawn") : null;
                if (p == null && !m.get("spawn").isJsonNull()) {
                    return "Where players go is three whole numbers inside the world, or none.";
                } else if (p == null) {
                    out.add("spawn", com.google.gson.JsonNull.INSTANCE);
                } else {
                    long x = whole(p, "x"), y = whole(p, "y"), z = whole(p, "z");
                    if (!inXZ(x) || !inXZ(z) || y < MIN_Y || y > MAX_Y) {
                        return "Where players go is three whole numbers inside the world.";
                    }
                    JsonObject c = new JsonObject();
                    c.addProperty("x", x);
                    c.addProperty("y", y);
                    c.addProperty("z", z);
                    out.add("spawn", c);
                }
            }
            for (String k : m.keySet()) {
                if (!Set.of("fill", "intervalSeconds", "spawn").contains(k)) {
                    return "Not a mine setting the panel knows: " + safe(k) + ".";
                }
            }
            if (out.size() > 0) {
                clean.add("mine", out);
                what.add("mine " + names(out));
            }
        }
        if (set.has("border")) {
            JsonObject bd = set.get("border").isJsonObject() ? set.getAsJsonObject("border") : null;
            if (bd == null || !global) {
                return "Only the global region has the world border.";
            }
            JsonObject out = new JsonObject();
            for (String k : bd.keySet()) {
                switch (k) {
                    case "size" -> {
                        long v = whole(bd, k);
                        if (v < 0 || v > 2 * MAX_XZ) {
                            return "The border's size is 0 (none) or more, in blocks.";
                        }
                        out.addProperty(k, v);
                    }
                    case "x", "z" -> {
                        long v = whole(bd, k);
                        if (!inXZ(v)) {
                            return "The border's centre is inside the world.";
                        }
                        out.addProperty(k, v);
                    }
                    case "plotsInPvp" -> {
                        if (!bd.get(k).isJsonPrimitive() || !bd.get(k).getAsJsonPrimitive().isBoolean()) {
                            return "Plots in PvP worlds is on or off.";
                        }
                        out.addProperty(k, bool(bd, k));
                    }
                    default -> {
                        return "Not a border setting the panel knows: " + safe(k) + ".";
                    }
                }
            }
            if (out.size() > 0) {
                clean.add("border", out);
                what.add("border " + names(out));
            }
        }
        return null;
    }

    /** One flag's new value: null clears it; text, a whole or decimal number, true or false, or a list of texts. */
    private static String flagValue(String name, JsonElement v, JsonObject out) {
        if (!FLAG.matcher(name).matches()) {
            return "Not a flag: " + safe(name) + ".";
        }
        if (v == null || v.isJsonNull()) {
            out.add(name, com.google.gson.JsonNull.INSTANCE);
            return null;
        }
        if (v.isJsonArray()) {
            if (v.getAsJsonArray().size() > MAX_SET) {
                return name + " takes at most " + MAX_SET + " entries.";
            }
            JsonArray c = new JsonArray();
            for (JsonElement e : v.getAsJsonArray()) {
                String s = e.isJsonPrimitive() ? clean(e.getAsString()).strip() : "";
                if (s.isEmpty() || s.length() > 128) {
                    return "An entry of " + name + " is 1 to 128 characters.";
                }
                c.add(s);
            }
            out.add(name, c);
            return null;
        }
        if (!v.isJsonPrimitive()) {
            return "Not a value for " + name + ".";
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isBoolean()) {
            out.add(name, p);
            return null;
        }
        String s = clean(p.getAsString());
        if (s.length() > MAX_TEXT) {
            return name + " is at most " + MAX_TEXT + " characters.";
        }
        out.addProperty(name, s);
        return null;
    }

    // ------------------------------------------------------------------ sending

    private JsonObject send(Account me, String server, JsonObject f) {
        Actions act = actions.get();
        if (act == null) {
            return refused(NOT_SENDING.formatted(safe(server)));
        }
        Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f, Actions.LONG_WAIT_SECONDS);
        JsonObject out = new JsonObject();
        if (ans.data() != null && ans.data().isJsonObject()) {
            for (var e : ans.data().getAsJsonObject().entrySet()) {
                out.add(e.getKey(), e.getValue());
            }
        }
        out.addProperty("ok", ans.ok());
        out.addProperty("message", ans.message());
        out.addProperty("server", server);
        return out;
    }

    private static JsonObject action(String name) {
        JsonObject f = new JsonObject();
        f.addProperty("action", name);
        return f;
    }

    /** A name from the request as it may go into a message and the panel's log: plain, short, else "that". */
    static String safe(String s) {
        return s != null && s.matches("[A-Za-z0-9 _.:-]{1,64}") ? s : "that";
    }

    private static boolean inXZ(long v) {
        return v != Long.MIN_VALUE && Math.abs(v) <= MAX_XZ;
    }

    private static boolean validId(String id) {
        return id.equals(GLOBAL) || ID.matcher(id).matches();
    }

    private static String names(JsonObject o) {
        List<String> k = new ArrayList<>(o.keySet());
        return k.size() <= 8 ? String.join(" ", k) : String.join(" ", k.subList(0, 8)) + " and " + (k.size() - 8) + " more";
    }

    private static Iterable<JsonElement> arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static JsonObject logged(JsonObject out, String line) {
        out.addProperty("panelLog", line);
        return out;
    }

    static JsonObject refused(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("message", why);
        return o;
    }

    private static String text(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? clean(e.getAsString()).strip() : null;
    }

    private static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(c -> out.appendCodePoint(c < 32 || c == 127 ? ' ' : c));
        return out.toString();
    }

    /** A whole number, else {@link Long#MIN_VALUE} (callers compare against their own ranges). */
    static long whole(JsonObject o, String k) {
        return whole(o.get(k));
    }

    private static long whole(JsonArray a, int i) {
        return whole(a.get(i));
    }

    private static long whole(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return Long.MIN_VALUE;
        }
        double v = e.getAsDouble();
        return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : Long.MIN_VALUE;
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
