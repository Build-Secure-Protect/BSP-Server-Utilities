package com.mrgregles.bsp_staff_panel.totems;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The TOTEMS page (SU-97, owner decisions D613 to D622; audit fixes SU-98): BSP-Core's network board, when each player's
 * points changed, the steals running and every recorded steal with how fast it went, on one page for every staff rank (D614).
 * <ul>
 * <li>The board: every {@link #POLL_MS} the panel asks one live server whose snapshot says {@code totems} for
 * {@code totem_board} ({@link #poll()}), keeping to the same server while it answers (each server's board lags the others'
 * a little differently, so switching would record changes that never happened), the next able one when it cannot. A
 * server whose BSP-Core has not built its board since it started ({@code ready} false) is passed over. Each player's change
 * of points is kept in the panel's own {@code panel_points} (D618), 90 days; the first time it sees a player is marked
 * {@code first}, where their line starts, never a gain. Players missing from a whole board are recorded at 0, but never
 * from an empty board while anyone held points (a server that has not read the network yet), nor from a cut one.</li>
 * <li>Steals: read from the game servers' {@code <prefix>steals} (each writes its own, D616) and shown only through each
 * row's signed copy (its {@code line}), so someone with only the database cannot invent a steal; heard live from the web
 * channel (kind {@code steal}, D620), which keeps "stealing now" up to date between board reads.</li>
 * <li>Speed (D619): a completed steal that took more than {@link #FAST_MARGIN_MS} less than BSP-Core gave it is
 * {@code fast} (BSP-Core counts one tick per server tick, so only something ticking the totem faster, or an operator
 * finishing it on BSP-Core's admin screen, can do that); {@link #BURST_STEALS} or more completed steals by one player within
 * {@link #BURST_MS} are a {@code burst}; each completed steal carries the time since that player's previous one. Both look
 * back a day before the window, so a window's first steals are judged like the rest.</li>
 * </ul>
 * The page's answer is kept for {@link #CACHE_MS} and made again after any change, one at a time, so many open pages
 * reloading together cost one set of database reads.
 */
public final class TotemDesk {
    public static final long POLL_MS = 30_000L, DAY = 86_400_000L, KEEP_MS = 90 * DAY, BURST_MS = 10 * 60_000L, FAST_MARGIN_MS = 2_000L, CACHE_MS = 5_000L;
    public static final int BURST_STEALS = 3, MAX_STEALS = 2000, MAX_POINTS = 20_000, MAX_SERVERS_TRIED = 3;
    /** The windows the page offers, in days; anything else is 7. */
    public static final List<Integer> DAYS = List.of(1, 7, 30, 90);
    /** Who the board's read is by, for the game server and the read limits. */
    public static final String BY = "Panel board";
    static final String NO_SERVER = "No game server that reads BSP-Core's board is sending to the panel right now.";
    static final String NO_STEALS = "The steal history could not be read. The panel's database user needs SELECT on %s (docs/STAFF_PANEL_SETUP.md part 5); the panel's console says more.";
    static final String NO_POINTS = "The points history could not be read; the panel's console says more.";
    static final String NOT_SAVED = "The points could not be saved; the board is shown and its changes are tried again on the next read. The panel's console says more.";
    /** Looked back before a window for the time between steals and bursts. */
    static final long LOOK_BACK_MS = DAY;
    /** A steal heard starting is dropped this long after BSP-Core's time for it ran out, or when a board read this long after its start does not list it. */
    private static final long RUNNING_GRACE_MS = 10 * 60_000L, BOARD_LAG_MS = 60_000L, ENDED_MEMORY_MS = 120_000L, MAX_AGE_MS = 600_000L;
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** One board read: the game server's answer, when it was asked for (before the read), and from whom. */
    private record BoardRead(JsonObject data, long at, String server) {
    }

    private record Cached(JsonObject page, long at, long generation) {
    }

    private final PanelStore store;
    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;
    /** Null only for the demo and tests: steal messages and rows are then taken unsigned. */
    private final byte[] key;

    private volatile BoardRead read;
    private volatile String problem = "", lastSig = "";
    /** Each player's newest change as recorded; read from the table on the first board, and again after a failed save (guarded by this). */
    private Map<String, PanelStore.PointsChange> last;
    /** Steals heard starting, by place, until their end is heard. */
    private final Map<String, JsonObject> running = new ConcurrentHashMap<>();
    /** Places whose steal was heard ending lately, so a board read before the end does not show it running again. */
    private final Map<String, Long> endedAt = new ConcurrentHashMap<>();
    private final Set<String> seen = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 5000;
        }
    }));
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, String> logged = new ConcurrentHashMap<>();

    public TotemDesk(PanelStore store, Feed feed, Supplier<Actions> actions, LongSupplier clock, byte[] key) {
        this.store = store;
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
        this.key = key == null ? null : key.clone();
    }

    /** Hears each change the open pages should know of: {@code event} {@code board}, {@code start} or {@code end} (with {@code steal}). */
    public void onEvent(Consumer<JsonObject> l) {
        listeners.add(l);
    }

    // ------------------------------------------------------------------ the board, every 30 s

    /** Reads the board from one able server (the last one while it answers) and records each change of points; never throws. */
    public void poll() {
        long now = clock.getAsLong();
        forget(now);
        Actions act = actions.get();
        List<String> able = new ArrayList<>(feed.liveWithTotems().stream().filter(n -> feed.whyNoAnswers(n) == null).toList());
        BoardRead was = read;
        if (was != null) {
            for (int i = 0; i < able.size(); i++) {
                if (able.get(i).equalsIgnoreCase(was.server())) {
                    able.add(0, able.remove(i)); // the same server while it answers (audit)
                    break;
                }
            }
        }
        if (able.isEmpty() || act == null) {
            if (!NO_SERVER.equals(problem)) {
                problem = NO_SERVER;
                generation.incrementAndGet();
            }
            return;
        }
        List<String> why = new ArrayList<>();
        for (String server : able.subList(0, Math.min(MAX_SERVERS_TRIED, able.size()))) {
            JsonObject f = new JsonObject();
            f.addProperty("action", "totem_board");
            long asked = clock.getAsLong(); // the board is as of about now, not when the answer came (audit)
            Actions.Answer a;
            try {
                a = act.read(server, BY, "MODERATOR", f);
            } catch (RuntimeException e) {
                why.add(server + " could not be asked");
                continue;
            }
            if (a == null || !a.ok() || a.data() == null || !a.data().isJsonObject() || !a.data().getAsJsonObject().has("board")) {
                why.add(server + ": " + (a == null ? "no answer" : a.message()));
                continue;
            }
            JsonObject d = a.data().getAsJsonObject();
            if (d.has("ready") && !bool(d, "ready")) {
                why.add(server + " has only just started (its BSP-Core has not built the board yet)");
                continue;
            }
            String saved = "";
            try {
                record(d, asked);
            } catch (Exception e) {
                console("points", "saving the TOTEMS points: " + e.getMessage());
                saved = NOT_SAVED;
            }
            read = new BoardRead(d, asked, server); // the board and its time together (audit)
            boolean changed = !saved.equals(problem);
            problem = saved;
            String sig = String.valueOf(d.get("board")) + "|" + d.get("stealing") + "|" + server;
            if (changed || !sig.equals(lastSig)) { // the open pages read again only when something they show changed (audit of SU-99)
                lastSig = sig;
                generation.incrementAndGet();
                JsonObject e = new JsonObject();
                e.addProperty("event", "board");
                fire(e);
            }
            return;
        }
        String p = String.join("; ", why) + ".";
        if (!p.equals(problem)) {
            problem = p;
            generation.incrementAndGet();
            JsonObject e = new JsonObject();
            e.addProperty("event", "board");
            fire(e);
        }
    }

    /** Records each player whose points differ from the last record (and, on a whole board, those gone from it, at 0). */
    synchronized void record(JsonObject d, long now) throws Exception {
        JsonArray lines = d.has("board") && d.get("board").isJsonArray() ? d.getAsJsonArray("board") : new JsonArray();
        Map<String, PanelStore.PointsChange> before = last != null ? last : new HashMap<>(store.lastPoints());
        boolean anyHeld = before.values().stream().anyMatch(p -> p.after() > 0);
        // whole: every line sent, and not empty while points were held (a server that has not read the network yet; audit)
        boolean whole = (!d.has("lines") || num(d, "lines") <= lines.size()) && !(lines.isEmpty() && anyHeld);
        Map<String, PanelStore.PointsChange> next = new HashMap<>(before);
        List<PanelStore.PointsChange> changes = new ArrayList<>();
        Set<String> onBoard = new HashSet<>();
        for (JsonElement e : lines) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "id").toLowerCase(Locale.ROOT);
            if (!id.matches(UUID_RE)) {
                continue;
            }
            onBoard.add(id);
            int pts = (int) num(o, "points"), totems = (int) num(o, "totems"), tier = (int) num(o, "tier");
            String name = clip(str(o, "name"), 32);
            PanelStore.PointsChange prev = next.get(id);
            PanelStore.PointsChange c;
            if (prev == null) {
                c = new PanelStore.PointsChange(now, id, name, 0, pts, totems, tier, true);
            } else if (prev.after() != pts) {
                c = new PanelStore.PointsChange(now, id, name, prev.after(), pts, totems, tier, false);
            } else {
                continue;
            }
            changes.add(c);
            next.put(id, c);
        }
        if (whole) {
            for (PanelStore.PointsChange prev : before.values()) {
                if (prev.after() > 0 && !onBoard.contains(prev.player())) {
                    PanelStore.PointsChange c = new PanelStore.PointsChange(now, prev.player(), prev.name(), prev.after(), 0, 0, 0, false); // their last totem left the board
                    changes.add(c);
                    next.put(prev.player(), c);
                }
            }
        }
        try {
            store.insertPoints(changes); // all or none
        } catch (Exception e) {
            last = null; // read from the table again next time, whatever reached it (audit)
            throw e;
        }
        last = next;
        if (!changes.isEmpty()) {
            generation.incrementAndGet();
        }
    }

    /** Points changes older than 90 days go (every hour). */
    public int prune() throws Exception {
        return store.prunePoints(clock.getAsLong() - KEEP_MS);
    }

    // ------------------------------------------------------------------ live steals from the web channel

    /** A message from the web channel; true when it was a steal (taken or dropped). */
    public boolean acceptMessage(String message) {
        String[] parts = message.split("\n", 5);
        if (parts.length < 5 || !parts[3].equals("steal")) {
            return false;
        }
        JsonObject o = opened(parts[4]);
        if (o == null) {
            return true; // not signed with this panel's key: dropped
        }
        long now = clock.getAsLong(), at = num(o, "at");
        String event = str(o, "event"), uid = str(o, "uid"), server = str(o, "server");
        if (!str(o, "type").equals("steal") || num(o, "v") != 1 || uid.isEmpty() || (key != null && Math.abs(now - at) > MAX_AGE_MS)) {
            return true; // another kind, or an old one sent again
        }
        if (!seen.add(event + ":" + uid)) {
            return true;
        }
        String place = spot(str(o, "dim"), (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "thief"));
        JsonObject out = new JsonObject();
        out.addProperty("event", event);
        switch (event) {
            case "start" -> {
                JsonObject r = running(server, str(o, "dim"), (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "thief"), str(o, "thiefName"),
                        str(o, "owner"), str(o, "ownerName"), 1.0, at, (int) num(o, "seconds"));
                r.addProperty("source", str(o, "source"));
                r.addProperty("tier", (int) num(o, "tier"));
                running.put(place, r);
                endedAt.remove(place);
                out.add("steal", r);
            }
            case "end" -> {
                JsonObject was = running.get(place);
                if (was != null && str(was, "thief").equalsIgnoreCase(str(o, "thief"))) {
                    running.remove(place);
                }
                endedAt.put(place, now);
                out.add("steal", steal(fromSigned(o, uid), 0, false));
            }
            default -> {
                return true;
            }
        }
        generation.incrementAndGet();
        fire(out);
        return true;
    }

    /** The signed text's JSON, or null; unsigned text taken only without a key (the demo and tests). */
    private JsonObject opened(String signed) {
        String json = key == null ? signed : Signed.open(key, signed);
        if (json == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException bad) {
            return null;
        }
    }

    /** A steal from a signed end message (the live one, or a row's {@code line}): only its signed fields count. */
    private static PanelStore.Steal fromSigned(JsonObject o, String uid) {
        return new PanelStore.Steal(uid, str(o, "server"), str(o, "dim"), (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "thief"),
                str(o, "thiefName"), str(o, "owner"), str(o, "ownerName"), str(o, "source"), (int) num(o, "tier"), (int) num(o, "points"), num(o, "started"),
                num(o, "ended"), (int) num(o, "seconds"), bool(o, "success"), str(o, "reason"), (int) num(o, "ticks"), "");
    }

    /**
     * The rows as their signed copies say (audit of SU-97): a row without a good signature, or whose copy is of another
     * steal, is left out and counted. Without a key (the demo, tests) the rows are taken as they are.
     */
    private List<PanelStore.Steal> verified(List<PanelStore.Steal> rows, int[] unverified) {
        if (key == null) {
            return rows;
        }
        List<PanelStore.Steal> out = new ArrayList<>(rows.size());
        Set<String> uids = new HashSet<>();
        for (PanelStore.Steal r : rows) {
            JsonObject o = r.line() == null || r.line().isEmpty() ? null : opened(r.line());
            // the copy must be of this very row: the same uid, thief and end, as the columns the rows are picked by (audit of 0.16.0)
            if (o == null || !str(o, "uid").equals(r.uid()) || !str(o, "event").equals("end") || !str(o, "type").equals("steal")
                    || !str(o, "thief").equalsIgnoreCase(String.valueOf(r.thief())) || num(o, "ended") != r.endedMs()) {
                unverified[0]++;
                continue;
            }
            if (!uids.add(r.uid())) {
                unverified[0]++; // the same steal twice would make a burst of one (audit)
                continue;
            }
            out.add(fromSigned(o, r.uid()));
        }
        return out;
    }

    private void fire(JsonObject e) {
        for (Consumer<JsonObject> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // a page's trouble never stops the others
            }
        }
    }

    // ------------------------------------------------------------------ what the page reads

    /** The whole page for the last {@code days} (1, 7, 30 or 90; else 7), the same answer for {@link #CACHE_MS} unless something changed. */
    public JsonObject page(int days) {
        int d = DAYS.contains(days) ? days : 7;
        Cached c = cache.get(d);
        long now = clock.getAsLong();
        if (c != null && c.generation() == generation.get() && now - c.at() < CACHE_MS) {
            return c.page();
        }
        synchronized (cache) { // one page made at a time: the rest wait and take it (audit)
            c = cache.get(d);
            now = clock.getAsLong();
            if (c != null && c.generation() == generation.get() && now - c.at() < CACHE_MS) {
                return c.page();
            }
            long gen = generation.get();
            JsonObject page = makePage(d, now);
            cache.put(d, new Cached(page, now, gen));
            return page;
        }
    }

    private JsonObject makePage(int d, long now) {
        long since = now - d * DAY;
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("days", d);
        out.addProperty("at", now);
        List<PanelStore.Steal> rows = List.of();
        int[] unverified = new int[1];
        try {
            rows = store.steals(since - LOOK_BACK_MS, now + DAY, "", MAX_STEALS);
        } catch (Exception e) {
            console("steals", "reading the steals table: " + e.getMessage());
            out.addProperty("stealsProblem", NO_STEALS.formatted(store.stealsTable()));
        }
        out.addProperty("stealsCut", rows.size() >= MAX_STEALS);
        List<JsonObject> steals = inWindow(annotate(verified(rows, unverified)), since);
        out.addProperty("unverified", unverified[0]);
        Map<String, int[]> totals = Map.of();
        try {
            totals = store.pointsTotals(since);
        } catch (Exception e) {
            console("points-read", "reading the TOTEMS points: " + e.getMessage());
            out.addProperty("pointsProblem", NO_POINTS);
        }
        Map<String, JsonObject> thieves = thieves(steals);
        BoardRead b = read;
        JsonArray lines = new JsonArray();
        if (b != null && b.data().has("board") && b.data().get("board").isJsonArray()) {
            for (JsonElement e : b.data().getAsJsonArray("board")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject l = e.getAsJsonObject().deepCopy();
                String id = str(l, "id").toLowerCase(Locale.ROOT);
                int[] t = totals.getOrDefault(id, new int[2]);
                l.addProperty("gained", t[0]);
                l.addProperty("lost", t[1]);
                JsonObject th = thieves.get(id);
                l.addProperty("steals", th == null ? 0 : (int) num(th, "steals"));
                l.addProperty("flags", th == null ? 0 : (int) num(th, "fast") + (bool(th, "burst") ? 1 : 0));
                lines.add(l);
            }
        }
        out.add("board", lines);
        out.addProperty("boardLines", b == null ? 0 : num(b.data(), "lines"));
        out.addProperty("boardAt", b == null ? 0 : b.at());
        out.addProperty("boardServer", b == null ? "" : b.server());
        String p = problem;
        out.addProperty("problem", b == null && p.isEmpty() ? "The board has not been read yet (every 30 seconds)." : p);
        out.add("stealing", stealingNow(b, now));
        JsonArray list = new JsonArray();
        steals.forEach(list::add);
        out.add("steals", list);
        JsonArray who = new JsonArray();
        thieves.values().stream().sorted(Comparator.comparing((JsonObject t) -> -((int) num(t, "fast") + (bool(t, "burst") ? 1 : 0)))
                .thenComparing(t -> -num(t, "steals")).thenComparing(t -> -num(t, "lastAt"))).forEach(who::add);
        out.add("thieves", who);
        return out;
    }

    /** One player's steals (as thief) and points over the last {@code days}, for their row opened. */
    public JsonObject player(String id, int days) {
        String p = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
        if (!p.matches(UUID_RE)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("message", "No such player.");
            return o;
        }
        int d = DAYS.contains(days) ? days : 7;
        long now = clock.getAsLong(), since = now - d * DAY;
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("id", p);
        out.addProperty("days", d);
        out.addProperty("at", now);
        List<PanelStore.Steal> rows = List.of();
        int[] unverified = new int[1];
        try {
            rows = store.steals(since - LOOK_BACK_MS, now + DAY, p, MAX_STEALS);
        } catch (Exception e) {
            console("steals", "reading the steals table: " + e.getMessage());
            out.addProperty("stealsProblem", NO_STEALS.formatted(store.stealsTable()));
        }
        out.addProperty("stealsCut", rows.size() >= MAX_STEALS);
        List<JsonObject> steals = inWindow(annotate(verified(rows, unverified)), since);
        out.addProperty("unverified", unverified[0]);
        JsonArray list = new JsonArray();
        steals.forEach(list::add);
        out.add("steals", list);
        JsonObject t = thieves(steals).get(p);
        if (t != null) {
            out.add("summary", t);
        }
        JsonArray pts = new JsonArray();
        try {
            List<PanelStore.PointsChange> changes = new ArrayList<>(store.points(since, p, MAX_POINTS));
            out.addProperty("pointsCut", changes.size() >= MAX_POINTS);
            Collections.reverse(changes); // oldest first, for the line
            for (PanelStore.PointsChange c : changes) {
                JsonObject o = new JsonObject();
                o.addProperty("at", c.at());
                o.addProperty("before", c.before());
                o.addProperty("after", c.after());
                o.addProperty("totems", c.totems());
                o.addProperty("tier", c.tier());
                o.addProperty("first", c.first());
                pts.add(o);
                out.addProperty("name", c.name());
            }
        } catch (Exception e) {
            console("points-read", "reading the TOTEMS points: " + e.getMessage());
            out.addProperty("pointsProblem", NO_POINTS);
        }
        out.add("points", pts);
        try { // where their line starts when nothing changed in the window: their newest change before it (audits of SU-99 and 0.16.0)
            PanelStore.PointsChange newest = store.lastPointsBefore(p, since).orElse(null);
            if (newest != null) {
                out.addProperty("start", newest.after());
                if (!out.has("name")) {
                    out.addProperty("name", newest.name());
                }
            }
        } catch (Exception e) {
            console("points-read", "reading the TOTEMS points: " + e.getMessage());
        }
        if (!out.has("name") && !steals.isEmpty()) {
            out.addProperty("name", str(steals.get(0), "thiefName"));
        }
        BoardRead b = read;
        if (b != null && b.data().has("board") && b.data().get("board").isJsonArray()) {
            for (JsonElement e : b.data().getAsJsonArray("board")) {
                if (e.isJsonObject() && str(e.getAsJsonObject(), "id").equalsIgnoreCase(p)) {
                    out.add("now", e.getAsJsonObject().deepCopy());
                }
            }
        }
        return out;
    }

    private static List<JsonObject> inWindow(List<JsonObject> steals, long since) {
        return steals.stream().filter(s -> num(s, "ended") >= since).toList();
    }

    // ------------------------------------------------------------------ speed (D619)

    /** The steals as the page shows them, newest first, each with its speed: {@code tookMs}, {@code fast}, {@code burst}, {@code gapMs}. */
    static List<JsonObject> annotate(List<PanelStore.Steal> rows) {
        Map<String, List<PanelStore.Steal>> byThief = new HashMap<>();
        for (PanelStore.Steal s : rows) {
            if (s.success()) {
                byThief.computeIfAbsent(s.thief().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
            }
        }
        Map<String, Long> gap = new HashMap<>();
        Set<String> burst = new HashSet<>();
        for (List<PanelStore.Steal> l : byThief.values()) {
            l.sort(Comparator.comparingLong(PanelStore.Steal::endedMs));
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    gap.put(l.get(i).uid(), l.get(i).endedMs() - l.get(i - 1).endedMs());
                }
                if (i + BURST_STEALS - 1 < l.size() && l.get(i + BURST_STEALS - 1).endedMs() - l.get(i).endedMs() <= BURST_MS) {
                    for (int j = i; j < i + BURST_STEALS; j++) {
                        burst.add(l.get(j).uid());
                    }
                }
            }
        }
        List<PanelStore.Steal> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingLong(PanelStore.Steal::endedMs).reversed());
        List<JsonObject> out = new ArrayList<>(sorted.size());
        for (PanelStore.Steal s : sorted) {
            out.add(steal(s, gap.getOrDefault(s.uid(), 0L), burst.contains(s.uid())));
        }
        return out;
    }

    /**
     * Whether a completed steal finished sooner than BSP-Core's rules allow: in fewer server ticks than its seconds, less the
     * margin. Judged on ticks, which BSP-Core counts in, never on the clock: a server catching up after lag runs ticks back to
     * back, so an honest steal can end early by the clock (audit of 0.16.0). Without ticks (its start not seen): never.
     */
    public static boolean fast(PanelStore.Steal s) {
        return s.success() && s.ticks() > 0 && s.expectedSeconds() > 0 && s.ticks() < s.expectedSeconds() * 20L - FAST_MARGIN_MS / 50;
    }

    static JsonObject steal(PanelStore.Steal s, long gapMs, boolean burst) {
        JsonObject o = new JsonObject();
        o.addProperty("uid", s.uid());
        o.addProperty("server", s.server());
        o.addProperty("dim", s.dimension());
        o.addProperty("x", s.x());
        o.addProperty("y", s.y());
        o.addProperty("z", s.z());
        o.addProperty("thief", s.thief());
        o.addProperty("thiefName", s.thiefName());
        o.addProperty("owner", s.owner() == null ? "" : s.owner());
        o.addProperty("ownerName", s.ownerName() == null ? "" : s.ownerName());
        o.addProperty("source", s.source());
        o.addProperty("tier", s.tier());
        o.addProperty("points", s.points());
        o.addProperty("started", s.startedMs());
        o.addProperty("ended", s.endedMs());
        o.addProperty("seconds", s.expectedSeconds());
        o.addProperty("success", s.success());
        o.addProperty("reason", s.reason());
        o.addProperty("why", why(s.reason()));
        o.addProperty("ticks", s.ticks());
        // the time it took as BSP-Core counts it (its ticks); the clock's only when no ticks were kept
        o.addProperty("tookMs", s.ticks() > 0 ? s.ticks() * 50L : s.startedMs() > 0 ? Math.max(0, s.endedMs() - s.startedMs()) : 0);
        o.addProperty("fast", fast(s));
        o.addProperty("burst", burst);
        o.addProperty("gapMs", gapMs);
        return o;
    }

    /** Per thief over the steals shown: completed and failed, points taken, the quickest steal and gap, and the flags. */
    static Map<String, JsonObject> thieves(List<JsonObject> steals) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (JsonObject s : steals) {
            String id = str(s, "thief").toLowerCase(Locale.ROOT);
            JsonObject t = out.computeIfAbsent(id, k -> {
                JsonObject n = new JsonObject();
                n.addProperty("id", k);
                n.addProperty("name", str(s, "thiefName")); // the newest name (steals come newest first)
                n.addProperty("steals", 0);
                n.addProperty("failed", 0);
                n.addProperty("points", 0);
                n.addProperty("fastestMs", 0);
                n.addProperty("shortestGapMs", 0);
                n.addProperty("fast", 0);
                n.addProperty("burst", false);
                n.addProperty("lastAt", num(s, "ended"));
                return n;
            });
            if (!bool(s, "success")) {
                t.addProperty("failed", num(t, "failed") + 1);
                continue;
            }
            t.addProperty("steals", num(t, "steals") + 1);
            t.addProperty("points", num(t, "points") + num(s, "points"));
            long took = num(s, "tookMs"), gap = num(s, "gapMs");
            if (took > 0 && (num(t, "fastestMs") == 0 || took < num(t, "fastestMs"))) {
                t.addProperty("fastestMs", took);
            }
            if (gap > 0 && (num(t, "shortestGapMs") == 0 || gap < num(t, "shortestGapMs"))) {
                t.addProperty("shortestGapMs", gap);
            }
            if (bool(s, "fast")) {
                t.addProperty("fast", num(t, "fast") + 1);
            }
            if (bool(s, "burst")) {
                t.addProperty("burst", true);
            }
        }
        return out;
    }

    /** BSP-Core's failure key in words. */
    static String why(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "";
        }
        return switch (reason) {
            case "message.bsp_core.steal.failed_left" -> "Left the totem for too long";
            case "message.bsp_core.steal.failed_thief_gone" -> "Interrupted (the thief died, left or changed world)";
            case "message.bsp_core.steal.totem_gone" -> "The totem was no longer there";
            case "message.bsp_core.steal.cancelled_admin" -> "An operator cancelled it";
            case "message.bsp_core.totem_forge.steal.broken" -> "The Totem Forge was broken";
            case "message.bsp_core.totem_forge.steal.taken_back" -> "The owner took the totem out first";
            case "message.bsp_core.totem_forge.steal.failed" -> "Failed";
            default -> {
                String tail = reason.substring(reason.lastIndexOf('.') + 1).replace('_', ' ');
                yield tail.isEmpty() ? reason : Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
            }
        };
    }

    // ------------------------------------------------------------------ stealing now (D620)

    /**
     * Drops what is too old to be true: an end heard over two minutes ago, and a start whose steal BSP-Core's time ran out
     * on {@link #RUNNING_GRACE_MS} ago (its end was missed) or that a board read well after it does not list (audit).
     * Called by every board read and every page, so nothing grows while nobody looks.
     */
    private void forget(long now) {
        endedAt.values().removeIf(t -> now - t > ENDED_MEMORY_MS);
        BoardRead b = read;
        Set<String> listed = new HashSet<>();
        if (b != null && b.data().has("stealing") && b.data().get("stealing").isJsonArray()) {
            for (JsonElement e : b.data().getAsJsonArray("stealing")) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    listed.add(spot(str(o, "dim"), (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "thief")));
                }
            }
        }
        boolean changed = running.entrySet().removeIf(en -> {
            long since = num(en.getValue(), "since"), seconds = num(en.getValue(), "seconds");
            boolean ranOut = now - since > Math.max(0, seconds) * 1000L + RUNNING_GRACE_MS;
            // a Totem Forge's pour is never on the board (BSP-Core lists placed totems only): only its time running out drops it (audit)
            boolean unlisted = b != null && b.at() - since > BOARD_LAG_MS && !listed.contains(en.getKey()) && !"forge".equals(str(en.getValue(), "source"));
            return ranOut || unlisted;
        });
        if (changed) {
            generation.incrementAndGet();
        }
    }

    /** The board's steals running (as read) and the ones heard since, without those heard ending; each with {@code left} 0 to 1. */
    private JsonArray stealingNow(BoardRead b, long now) {
        forget(now);
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (b != null && b.data().has("stealing") && b.data().get("stealing").isJsonArray()) {
            for (JsonElement e : b.data().getAsJsonArray("stealing")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                String place = spot(str(o, "dim"), (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "thief"));
                Long ended = endedAt.get(place);
                if (ended != null && ended >= b.at() - BOARD_LAG_MS) { // a board a little behind never brings an ended steal back (audit)
                    continue;
                }
                double left = o.has("left") && o.get("left").isJsonPrimitive() ? o.get("left").getAsDouble() : 1.0;
                out.put(place, running(str(o, "server"), str(o, "dim"), (int) num(o, "x"), (int) num(o, "y"), (int) num(o, "z"), str(o, "thief"), str(o, "thiefName"),
                        str(o, "owner"), str(o, "ownerName"), left, 0, 0));
            }
        }
        for (Map.Entry<String, JsonObject> r : running.entrySet()) {
            JsonObject o = r.getValue().deepCopy();
            long since = num(o, "since"), seconds = num(o, "seconds");
            if (seconds > 0) {
                o.addProperty("left", Math.max(0, Math.min(1, 1 - (now - since) / (seconds * 1000.0))));
            }
            out.put(r.getKey(), o); // heard live: newer than the board's
        }
        JsonArray a = new JsonArray();
        out.values().forEach(a::add);
        return a;
    }

    private static JsonObject running(String server, String dim, int x, int y, int z, String thief, String thiefName, String owner, String ownerName,
                                      double left, long since, int seconds) {
        JsonObject o = new JsonObject();
        o.addProperty("server", server);
        o.addProperty("dim", dim);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("thief", thief);
        o.addProperty("thiefName", clip(thiefName, 32));
        o.addProperty("owner", owner);
        o.addProperty("ownerName", clip(ownerName, 32));
        o.addProperty("left", Math.round(Math.max(0, Math.min(1, left)) * 1000) / 1000.0);
        o.addProperty("since", since);
        o.addProperty("seconds", Math.max(0, seconds));
        return o;
    }

    /** A steal's place and thief: the server's name is left out, as BSP-Core and this mod may name a server differently (audit). */
    private static String spot(String dim, int x, int y, int z, String thief) {
        return dim + "|" + x + "|" + y + "|" + z + "|" + String.valueOf(thief).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ small helpers

    /** A database error on the console once per new message (the pages get plain words, never the database's own; audit). */
    private void console(String what, String message) {
        if (!String.valueOf(message).equals(logged.put(what, String.valueOf(message)))) {
            System.err.println("[panel] " + message);
        }
    }

    private static String clip(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0;
        } catch (NumberFormatException bad) {
            return 0;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /** Tests: when the board was last asked for (0: never). */
    public long boardAt() {
        BoardRead b = read;
        return b == null ? 0 : b.at();
    }

    /** Tests: the server the board was last read from. */
    public String boardServer() {
        BoardRead b = read;
        return b == null ? "" : b.server();
    }

    public String problem() {
        return problem;
    }
}
