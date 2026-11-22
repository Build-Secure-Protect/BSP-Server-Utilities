package com.mrgregles.bsp_staff_panel.talk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Private staff messages to players on the panel (SU-110; owner decisions D630 to D652, R6 and R7). BSP-Server Utilities
 * keeps them in the game servers' {@code <prefix>talks} and {@code <prefix>talk_lines} (docs/API.md), which the panel only
 * reads; it writes through the signed actions {@code talk_send} and {@code talk_end}, sent to the server holding the player
 * or else to any server that takes them (its snapshot's {@code talk}). A line is shown only through its signed copy (the
 * same uid, conversation, player, kind and time as its row), so nobody with the database alone can put words in a staff
 * member's or a player's mouth; a conversation counts as ended when its row says so or a signed end line does. New lines
 * arrive live from the web channel (kind {@code talk}) and go to every open page. Every rank reads, writes and ends (D630).
 */
public final class PlayerTalk {
    public static final int MAX_TEXT = 256, MAX_TALKS = 50;
    public static final long MAX_AGE_MS = 600_000L;
    static final String NO_SERVER = "No game server is answering: try again in a moment.";
    static final String LATE = "No answer yet: it may still have been sent. It shows here when it arrives; send again only if it does not.";
    static final String NO_TABLES = "The messages could not be read. The panel's database user needs SELECT on %s and its talks table (docs/STAFF_PANEL_SETUP.md part 5); the panel's console says more.";

    private final PanelStore store;
    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;
    private final byte[] key;
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>(256, .75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 5000;
        }
    };
    private volatile String lastConsole = "";
    /** The waiting count as last worked out (sent with each live line at once; worked out again in the background). */
    private volatile int waitingNow;
    private final java.util.concurrent.atomic.AtomicBoolean recountQueued = new java.util.concurrent.atomic.AtomicBoolean();
    /** Where the count is worked out after a live line: never on the Redis listener's thread (audit of SU-111). */
    private java.util.concurrent.Executor recounter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "panel-pmsg");
        t.setDaemon(true);
        return t;
    });
    static final long RECOUNT_DELAY_MS = 500L;

    /** {@code key} null only for the demo and tests: rows are then taken as they are. */
    public PlayerTalk(PanelStore store, Feed feed, Supplier<Actions> actions, LongSupplier clock, byte[] key) {
        this.store = store;
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
        this.key = key == null ? null : key.clone();
    }

    /** Hears each new line (event {@code line}) and each change of the waiting count, for every open page. */
    public void onEvent(Consumer<JsonObject> l) {
        listeners.add(l);
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

    private void console(String what) {
        if (!what.equals(lastConsole)) {
            lastConsole = what;
            System.err.println("[panel] player messages: " + what);
        }
    }

    // ------------------------------------------------------------------ signed copies

    private JsonObject opened(String signed) {
        if (signed == null || signed.isEmpty()) {
            return null;
        }
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

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        try {
            JsonElement e = o.get(k);
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (RuntimeException bad) {
            return 0;
        }
    }

    /** A line as the page gets it: {uid, talk, at, kind, staff, text, name}. */
    private static JsonObject lineJson(JsonObject signed) {
        JsonObject o = new JsonObject();
        o.addProperty("uid", str(signed, "uid"));
        o.addProperty("talk", num(signed, "talk"));
        o.addProperty("player", str(signed, "player").toLowerCase(Locale.ROOT));
        o.addProperty("name", str(signed, "name"));
        o.addProperty("at", num(signed, "at"));
        o.addProperty("kind", str(signed, "kind"));
        o.addProperty("staff", str(signed, "staff"));
        o.addProperty("text", str(signed, "text"));
        return o;
    }

    /**
     * The row's line as its signed copy says, or null when the copy is missing, wrong, of another line, or of another
     * player than the conversation it is shown under ({@code player}; audit of SU-111: a conversation row moved to
     * another player never carries its lines with it).
     */
    private JsonObject verified(PanelStore.TalkLine r, PanelStore.Talk t) {
        String player = t == null ? "" : t.player();
        if (key == null) {
            if (!r.player().equalsIgnoreCase(player)) {
                return null;
            }
            JsonObject o = new JsonObject();
            o.addProperty("uid", r.uid());
            o.addProperty("talk", r.talk());
            o.addProperty("player", r.player());
            o.addProperty("name", t.name()); // no key (the demo, tests): the rows as they are
            o.addProperty("at", r.at());
            o.addProperty("kind", r.kind());
            o.addProperty("staff", r.staff());
            o.addProperty("text", r.text());
            return lineJson(o);
        }
        JsonObject o = opened(r.line());
        if (o == null || !str(o, "type").equals("talk") || num(o, "v") != 1 || !str(o, "uid").equals(r.uid()) || num(o, "talk") != r.talk()
                || !str(o, "player").equalsIgnoreCase(r.player()) || !str(o, "player").equalsIgnoreCase(player) || !str(o, "kind").equals(r.kind()) || num(o, "at") != r.at()) {
            return null;
        }
        return lineJson(o);
    }

    /** The lines of these conversations by conversation, oldest first, only those that hold; {@code unverified[0]} counts the rest. */
    private Map<Long, List<JsonObject>> linesOf(List<PanelStore.Talk> talks, int[] unverified) throws Exception {
        Map<Long, List<JsonObject>> out = new HashMap<>();
        Map<Long, PanelStore.Talk> talkOf = new HashMap<>();
        talks.forEach(t -> talkOf.put(t.id(), t));
        java.util.Set<String> uids = new java.util.HashSet<>();
        for (PanelStore.TalkLine r : store.talkLines(talks.stream().map(PanelStore.Talk::id).toList())) {
            JsonObject l = verified(r, talkOf.get(r.talk()));
            if (l == null || !uids.add(r.uid())) {
                unverified[0]++;
                continue;
            }
            out.computeIfAbsent(r.talk(), k -> new ArrayList<>()).add(l);
        }
        return out;
    }

    /**
     * What a conversation's signed lines say, never its unsigned row (audit of SU-111): ended by a signed end line, who
     * wrote first and when, the newest staff and player lines, who wrote last (in the order the table took them, never by
     * two servers' clocks), and the newest line. The database alone can hide a conversation (as it can delete one) but
     * never name anyone as starting or ending it.
     */
    private record State(boolean open, String endedBy, long endedAt, String openedBy, long openedAt, long lastStaff, long lastPlayer, String lastKind, JsonObject last) {
        boolean staffWaits() {
            return lastKind.equals("player");
        }
    }

    private static State state(List<JsonObject> lines) {
        boolean ended = false;
        String endedBy = "", openedBy = "", lastKind = "";
        long endedAt = 0, openedAt = 0, staff = 0, player = 0;
        JsonObject last = null;
        for (JsonObject l : lines) {
            String kind = str(l, "kind");
            if (openedAt == 0 && kind.equals("staff")) {
                openedBy = str(l, "staff");
                openedAt = num(l, "at");
            }
            switch (kind) {
                case "end" -> {
                    ended = true;
                    endedBy = str(l, "staff");
                    endedAt = num(l, "at");
                }
                case "staff" -> {
                    staff = Math.max(staff, num(l, "at"));
                    lastKind = kind;
                }
                case "player" -> {
                    player = Math.max(player, num(l, "at"));
                    lastKind = kind;
                }
                default -> {
                }
            }
            last = l;
        }
        return new State(!ended, endedBy, endedAt, openedBy, openedAt, staff, player, lastKind, last);
    }

    // ------------------------------------------------------------------ what the page reads

    /**
     * One player's conversations of the last 90 days (R6): newest first, each with its lines oldest first; the open one
     * with who started it and when, who is waiting and since when, and whether the player has been told; where they are.
     */
    public JsonObject history(String player, String nameAsked) {
        JsonObject out = new JsonObject();
        String id = player.toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            out.addProperty("ok", false);
            out.addProperty("message", "That is not a player.");
            return out;
        }
        List<PanelStore.Talk> talks;
        Map<Long, List<JsonObject>> lines;
        int[] unverified = {0};
        try {
            talks = store.talksOf(id, MAX_TALKS);
            lines = linesOf(talks, unverified);
        } catch (Exception e) {
            console("reading " + id + ": " + e.getMessage());
            out.addProperty("ok", false);
            out.addProperty("message", NO_TABLES.formatted(store.talkLinesTable()));
            return out;
        }
        long now = clock.getAsLong();
        String name = nameAsked;
        JsonArray arr = new JsonArray();
        JsonObject open = null;
        for (PanelStore.Talk t : talks) {
            List<JsonObject> ls = lines.getOrDefault(t.id(), List.of());
            State st = state(ls);
            if (name.isEmpty()) {
                for (int i = ls.size() - 1; i >= 0 && name.isEmpty(); i--) {
                    name = str(ls.get(i), "name"); // the signed name, never the row's (audit)
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("open", st.open());
            o.addProperty("openedAt", st.openedAt());
            o.addProperty("openedBy", st.openedBy());
            o.addProperty("endedAt", st.open() ? 0 : st.endedAt());
            o.addProperty("endedBy", st.open() ? "" : st.endedBy());
            JsonArray la = new JsonArray();
            ls.forEach(la::add);
            o.add("lines", la);
            arr.add(o);
            if (st.open() && !ls.isEmpty() && open == null) {
                open = new JsonObject();
                open.addProperty("id", t.id());
                open.addProperty("since", st.openedAt());
                open.addProperty("by", st.openedBy());
                boolean staffWaits = st.staffWaits();
                open.addProperty("waiting", staffWaits ? "staff" : st.lastKind().equals("staff") ? "player" : "");
                open.addProperty("waitMs", staffWaits ? now - st.lastPlayer() : st.lastStaff() > 0 ? now - st.lastStaff() : 0);
                open.addProperty("seen", t.toldAt() >= st.lastStaff());
            }
        }
        String[] where = feed.find(id);
        out.addProperty("ok", true);
        out.addProperty("player", id);
        out.addProperty("name", where != null ? where[1] : name);
        out.addProperty("where", where != null ? where[0] : "");
        out.add("talks", arr);
        if (open != null) {
            out.add("open", open);
        }
        out.addProperty("unverified", unverified[0]);
        out.addProperty("canWrite", serverFor(id) != null); // a server that takes messages and can answer (audit)
        out.addProperty("now", now);
        return out;
    }

    /**
     * The open conversations (R7): those waiting on staff first, the longest wait first; then the rest, the newest staff
     * line first; each with its newest line, where the player is, and "not seen it yet". {@code waiting} is the red count
     * on More.
     */
    public JsonObject list() {
        JsonObject out = new JsonObject();
        List<PanelStore.Talk> talks;
        Map<Long, List<JsonObject>> lines;
        int[] unverified = {0};
        try {
            talks = store.openTalks();
            lines = linesOf(talks, unverified);
        } catch (Exception e) {
            console("reading the open conversations: " + e.getMessage());
            out.addProperty("ok", false);
            out.addProperty("message", NO_TABLES.formatted(store.talkLinesTable()));
            out.addProperty("waiting", 0);
            return out;
        }
        long now = clock.getAsLong();
        record Row(JsonObject json, boolean waiting, long sortAt) {
        }
        List<Row> rows = new ArrayList<>();
        for (PanelStore.Talk t : talks) {
            List<JsonObject> ls = lines.getOrDefault(t.id(), List.of());
            State st = state(ls);
            if (!st.open() || ls.isEmpty()) {
                continue; // a signed end line wins over the row; a conversation with no signed line is not shown
            }
            boolean staffWaits = st.staffWaits();
            JsonObject o = new JsonObject();
            String[] where = feed.find(t.player());
            o.addProperty("player", t.player());
            o.addProperty("name", where != null ? where[1] : str(ls.get(ls.size() - 1), "name"));
            o.addProperty("where", where != null ? where[0] : "");
            o.addProperty("waitingOnStaff", staffWaits);
            o.addProperty("sinceMs", now - (staffWaits ? st.lastPlayer() : Math.max(st.lastStaff(), st.openedAt())));
            o.addProperty("notSeen", where == null && t.toldAt() < st.lastStaff());
            if (st.last() != null) {
                o.add("last", st.last());
            }
            rows.add(new Row(o, staffWaits, staffWaits ? st.lastPlayer() : -st.lastStaff()));
        }
        rows.sort(Comparator.comparing((Row r) -> !r.waiting()).thenComparingLong(Row::sortAt));
        JsonArray arr = new JsonArray();
        rows.forEach(r -> arr.add(r.json()));
        out.addProperty("ok", true);
        out.add("open", arr);
        int count = (int) rows.stream().filter(Row::waiting).count();
        waitingNow = count;
        out.addProperty("waiting", count);
        out.addProperty("now", now);
        out.addProperty("unverified", unverified[0]);
        return out;
    }

    // ------------------------------------------------------------------ writing, through a game server

    /** The server to ask: the one holding the player when it takes messages, else the busiest that does; null when none. */
    String serverFor(String player) {
        List<String> able = feed.liveWithTalk().stream().filter(n -> feed.whyNoAnswers(n) == null).toList();
        String[] where = feed.find(player);
        if (where != null && able.stream().anyMatch(n -> n.equalsIgnoreCase(where[0]))) {
            return where[0];
        }
        return able.isEmpty() ? null : able.get(0);
    }

    private JsonObject act(Account by, String action, String player, String name, String text) {
        JsonObject out = new JsonObject();
        String id = player.toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || !name.matches("[A-Za-z0-9_]{1,16}")) {
            out.addProperty("ok", false);
            out.addProperty("message", "That is not a player.");
            return out;
        }
        Actions a = actions.get();
        String server = serverFor(id);
        if (a == null || server == null) {
            out.addProperty("ok", false);
            out.addProperty("message", NO_SERVER);
            return out;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", action);
        f.addProperty("player", id);
        f.addProperty("name", name);
        if (text != null) {
            f.addProperty("text", text);
        }
        Actions.Answer ans = a.message(server, by.mcName, by.rank.name(), f);
        out.addProperty("ok", ans != null && ans.ok());
        String said = ans == null ? NO_SERVER : ans.message();
        if (said.startsWith("No answer from")) {
            said = LATE; // taken, perhaps: never invite a second copy (audit)
            out.addProperty("late", true);
        }
        out.addProperty("message", said);
        if (ans != null && ans.data() != null && ans.data().isJsonObject()) {
            JsonObject d = ans.data().getAsJsonObject();
            out.addProperty("outcome", str(d, "outcome"));
            JsonObject l = d.has("line") ? opened(str(d, "line")) : null;
            if (l != null && str(l, "type").equals("talk")) {
                JsonObject shown = lineJson(l);
                out.add("line", shown);
                take(shown); // shown at once on every page, once (the live copy is the same uid)
            }
        }
        return out;
    }

    /** SEND (R6): 1 to {@value #MAX_TEXT} characters; the game server cleans and checks again. */
    public JsonObject send(Account by, String player, String name, String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty() || t.length() > MAX_TEXT) {
            JsonObject out = new JsonObject();
            out.addProperty("ok", false);
            out.addProperty("message", t.isEmpty() ? "There is nothing to send." : "At most " + MAX_TEXT + " characters.");
            return out;
        }
        return act(by, "talk_send", player, name, t);
    }

    /** END CONVERSATION (R6, after SURE? on the page). */
    public JsonObject end(Account by, String player, String name) {
        return act(by, "talk_end", player, name, null);
    }

    // ------------------------------------------------------------------ live lines

    /** A message from the web channel; true when it was a private staff line (shown or dropped). */
    public boolean acceptMessage(String message) {
        String[] parts = message.split("\n", 5);
        if (parts.length < 5 || !parts[3].equals("talk")) {
            return false;
        }
        JsonObject o = opened(parts[4]);
        if (o == null || !str(o, "type").equals("talk") || num(o, "v") != 1 || str(o, "uid").isEmpty()) {
            return true; // not signed with this panel's key, or not a line
        }
        if (key != null && Math.abs(clock.getAsLong() - num(o, "at")) > MAX_AGE_MS) {
            return true; // an old line sent again is not news
        }
        take(lineJson(o));
        return true;
    }

    private void take(JsonObject line) {
        synchronized (seen) {
            if (seen.put(str(line, "uid"), Boolean.TRUE) != null) {
                return;
            }
        }
        JsonObject e = new JsonObject();
        e.addProperty("event", "line");
        e.add("line", line);
        fire(e); // at once, with nothing read: the pages read only the player whose card is open (audit)
        recount();
    }

    /**
     * The waiting count worked out again once a burst of lines is over, off the Redis thread, and sent to every page as
     * event {@code count} with the open list's version ({@code at}), so a page never takes an older count over a newer one.
     */
    private void recount() {
        if (!recountQueued.compareAndSet(false, true)) {
            return;
        }
        recounter.execute(() -> {
            try {
                if (recounter instanceof java.util.concurrent.ExecutorService) {
                    Thread.sleep(RECOUNT_DELAY_MS); // the rest of a burst joins this count
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recountQueued.set(false);
            JsonObject l = list();
            JsonObject e = new JsonObject();
            e.addProperty("event", "count");
            e.addProperty("waiting", l.has("waiting") ? l.get("waiting").getAsInt() : waitingNow);
            e.addProperty("at", clock.getAsLong());
            fire(e);
        });
    }

    /** How many open conversations wait on staff, as last worked out (the red count on More). */
    public int waiting() {
        return waitingNow;
    }

    /** Tests: the count worked out at once on the calling thread. */
    public PlayerTalk countAtOnce() {
        recounter = Runnable::run;
        return this;
    }
}
