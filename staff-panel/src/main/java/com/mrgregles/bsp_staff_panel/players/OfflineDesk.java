package com.mrgregles.bsp_staff_panel.players;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Offline players for the Players tab and the Map tab (SU-115; owner decisions D653 to D671): everyone who ever joined
 * and is not online now, the most recently seen first, 50 at a time, found by any part of the name, each with where
 * they were last seen as BSP-Server Utilities recorded it ({@code <prefix>seen}).
 *
 * <p>A place is shown only through the row's signed copy (signed with the action key by the game server that wrote it):
 * a row without a good signature, whose copy names another player, or dated more than a day ahead is shown as "not
 * known" ({@code state} {@code bad}). Without a key (the demo, tests) the rows are taken as they are. A panel whose
 * database user lacks the grant on the seen table still lists the players, by name, with a line saying what is missing.
 */
public final class OfflineDesk {
    public static final int PAGE = 50, MAX_OFFSET = 100_000;
    /** A copy dated further ahead than this is not believed (a clock far off, or a row made to stay on top). */
    public static final long FUTURE_MS = 86_400_000L;
    static final String NO_GRANT = "Where offline players logged off could not be read. The panel's database user needs SELECT on %s"
            + " (docs/STAFF_PANEL_SETUP.md part 5); the panel's console says more.";
    static final String NO_PLAYERS = "The players who ever joined could not be read; the panel's console says more.";
    static final String NO_TABLE = "Where offline players logged off is not recorded yet: %s is made by BSP-Server Utilities 0.18.0 on the game servers.";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final PanelStore store;
    private final Feed feed;
    private final LongSupplier clock;
    /** null: no key at all (the demo, tests), rows taken as they are; an empty key (an actionKey that could not be read) believes nothing. */
    private final byte[] key;
    private final boolean noKey;
    private volatile String lastConsole = "";

    public OfflineDesk(PanelStore store, Feed feed, LongSupplier clock, byte[] key) {
        this.store = store;
        this.feed = feed;
        this.clock = clock;
        this.noKey = key == null;
        this.key = key == null ? null : key.clone(); // empty is not "none": a bad actionKey never shows unchecked rows (audit of SU-115)
    }

    /**
     * A page of offline players: {@code q} part of a name (letters, digits and _; anything else finds nobody), from
     * {@code offset}, at most {@code limit} (1 to 50). The answer: {@code total} (how many in all), {@code offset},
     * {@code players} (see {@link #player}) and {@code problem} when the places could not be read.
     */
    public JsonObject list(String q, int offset, int limit) {
        JsonObject out = new JsonObject();
        String part = q == null ? "" : q.strip();
        int from = Math.max(0, Math.min(MAX_OFFSET, offset));
        int count = Math.max(1, Math.min(PAGE, limit));
        JsonArray arr = new JsonArray();
        out.addProperty("ok", true);
        out.addProperty("q", part);
        out.addProperty("offset", from);
        if (!part.matches("[A-Za-z0-9_]{0,16}")) {
            out.addProperty("total", 0);
            out.add("players", arr);
            return out;
        }
        Set<String> online = online();
        List<PanelStore.SeenPlayer> rows;
        int total;
        try {
            total = store.countOffline(part, online);
        } catch (Exception e) {
            console("counting: " + e.getMessage());
            out.addProperty("ok", false);
            out.addProperty("message", NO_PLAYERS);
            return out;
        }
        long now = clock.getAsLong();
        try {
            rows = store.offlinePlayers(part, online, from, count, true, now + FUTURE_MS);
        } catch (Exception e) {
            console("reading where players were last seen: " + e.getMessage());
            out.addProperty("problem", problem(e));
            try {
                rows = store.offlinePlayers(part, online, from, count, false, now + FUTURE_MS); // the names still, without the places
            } catch (Exception again) {
                console("reading the players: " + again.getMessage());
                out.addProperty("ok", false);
                out.addProperty("message", NO_PLAYERS);
                return out;
            }
        }
        for (PanelStore.SeenPlayer r : rows) {
            if (r.id() == null || !r.id().toLowerCase(Locale.ROOT).matches(UUID_RE)) {
                continue; // not a row a game server wrote: never put in a page or a map's address (audit)
            }
            arr.add(player(r, now));
        }
        out.addProperty("total", total);
        out.add("players", arr);
        return out;
    }

    /**
     * One player who ever joined (a UUID), for their card: {@code player} as in {@link #list}, with {@code online} true
     * when the feed has them now.
     */
    public JsonObject one(String id) {
        JsonObject out = new JsonObject();
        String uid = id == null ? "" : id.toLowerCase(Locale.ROOT);
        if (!uid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            out.addProperty("ok", false);
            out.addProperty("message", "That is not a player.");
            return out;
        }
        Optional<PanelStore.SeenPlayer> r;
        try {
            r = store.seenPlayer(uid, true);
        } catch (Exception e) {
            console("reading where a player was last seen: " + e.getMessage()); // said once, not once per card (audit)
            out.addProperty("problem", problem(e));
            try {
                r = store.seenPlayer(uid, false);
            } catch (Exception again) {
                console("reading a player: " + again.getMessage());
                out.addProperty("ok", false);
                out.addProperty("message", NO_PLAYERS);
                return out;
            }
        }
        if (r.isEmpty()) {
            out.addProperty("ok", false);
            out.addProperty("message", "Nobody by that name has played on the network.");
            return out;
        }
        JsonObject p = player(r.get(), clock.getAsLong());
        p.addProperty("online", online().contains(uid));
        out.addProperty("ok", true);
        out.add("player", p);
        return out;
    }

    /**
     * {@code id}, {@code name}, {@code skin} (true when the panel has their head), {@code state} ({@code ok} with
     * {@code place}, {@code none} when no place was recorded, {@code bad} when its copy could not be checked) and
     * {@code place}: {@code at}, {@code server}, {@code dim}, {@code x}, {@code y}, {@code z}, {@code kind}
     * ({@code logout}, or {@code seen} when the server did not record a logout), all from the signed copy.
     */
    private JsonObject player(PanelStore.SeenPlayer r, long now) {
        JsonObject p = new JsonObject();
        p.addProperty("id", r.id().toLowerCase(Locale.ROOT));
        p.addProperty("name", r.name());
        p.addProperty("skin", feed.skinOf(r.id()) != null);
        if (!r.seen()) {
            p.addProperty("state", "none");
            return p;
        }
        JsonObject place = noKey ? unsigned(r) : verified(r, now);
        if (place == null) {
            p.addProperty("state", "bad");
            return p;
        }
        p.addProperty("state", "ok");
        p.add("place", place);
        return p;
    }

    /** The place from the row's signed copy, or null when the copy does not hold (see the class comment). */
    private JsonObject verified(PanelStore.SeenPlayer r, long now) {
        String json = key.length == 0 || r.line() == null || r.line().isEmpty() ? null : Signed.open(key, r.line());
        if (json == null) {
            return null;
        }
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return null;
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException bad) {
            return null;
        }
        if (!str(o, "type").equals("seen") || !str(o, "player").equalsIgnoreCase(r.id()) || num(o, "at") <= 0 || num(o, "at") > now + FUTURE_MS) {
            return null;
        }
        // the copy must be of this very row (audit of SU-115): the name shown beside it, and every column the list is sorted or
        // drawn by, so neither a renamed players row nor a changed time or place can borrow a genuine copy
        if (!str(o, "name").equalsIgnoreCase(r.name()) || num(o, "at") != r.atMs() || !str(o, "server").equals(r.server()) || !str(o, "dim").equals(r.dimension())
                || num(o, "x") != r.x() || num(o, "y") != r.y() || num(o, "z") != r.z() || !str(o, "kind").equals(r.kind())) {
            return null;
        }
        String kind = str(o, "kind");
        JsonObject place = new JsonObject();
        place.addProperty("at", num(o, "at"));
        place.addProperty("server", str(o, "server"));
        place.addProperty("dim", str(o, "dim"));
        place.addProperty("x", (int) num(o, "x"));
        place.addProperty("y", (int) num(o, "y"));
        place.addProperty("z", (int) num(o, "z"));
        place.addProperty("kind", kind.equals("logout") ? "logout" : "seen");
        return place;
    }

    private static JsonObject unsigned(PanelStore.SeenPlayer r) {
        JsonObject place = new JsonObject();
        place.addProperty("at", r.atMs());
        place.addProperty("server", r.server());
        place.addProperty("dim", r.dimension());
        place.addProperty("x", r.x());
        place.addProperty("y", r.y());
        place.addProperty("z", r.z());
        place.addProperty("kind", "logout".equals(r.kind()) ? "logout" : "seen");
        return place;
    }

    /** The words for a read of the seen table that failed: not made yet (game servers older than 0.18.0), or no grant. */
    private String problem(Exception e) {
        String m = String.valueOf(e.getMessage());
        boolean missing = e instanceof java.sql.SQLException q && "42S02".equals(q.getSQLState()) || m.contains("doesn't exist");
        return (missing ? NO_TABLE : NO_GRANT).formatted(store.seenTable());
    }

    /** Everyone online now, by the feed (UUIDs in lower case). */
    private Set<String> online() {
        Set<String> out = new LinkedHashSet<>();
        JsonObject snap = feed.json();
        if (snap.has("players") && snap.get("players").isJsonArray()) {
            for (JsonElement e : snap.getAsJsonArray("players")) {
                if (e.isJsonObject()) {
                    String id = str(e.getAsJsonObject(), "id");
                    if (!id.isEmpty()) {
                        out.add(id.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return out;
    }

    private void console(String what) {
        if (!what.equals(lastConsole)) {
            lastConsole = what;
            System.err.println("[panel] offline players: " + what);
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (NumberFormatException bad) {
            return 0;
        }
    }
}
