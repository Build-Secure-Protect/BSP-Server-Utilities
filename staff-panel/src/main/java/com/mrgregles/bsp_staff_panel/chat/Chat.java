package com.mrgregles.bsp_staff_panel.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The network's public chat on the panel (SU-36, D261 to D263, D304 to D313). BSP-Server Utilities sends each line said
 * on a game server signed with the action key, live on Redis's web channel (kind {@code chat}) and into
 * {@code <prefix>web_chat}; the panel adds its own lines ([Staff] and [Server]) to the same table. Lines arrive live from
 * Redis, and the table is read every few seconds for anything Redis missed; each line reaches the pages once (by its
 * {@code uid}). Only lines signed with this panel's key are shown. Kept 30 days (D263).
 */
public final class Chat {
    /** A line arriving live older than this is not shown live (it is in the history). */
    public static final long LIVE_MAX_AGE_MS = 600_000L;
    private static final int OVERLAP = 50;
    private long startId;
    public static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
    public static final int MAX_TEXT = 256, PAGE = 100, SEEN = 5000;

    private final PanelStore store;
    private final byte[] key;
    private final LongSupplier clock;
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>(256, .75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > SEEN;
        }
    };
    private volatile long lastId = -1;

    /** {@code key} null only for the demo and tests: lines are then taken unsigned. */
    public Chat(PanelStore store, byte[] key, LongSupplier clock) {
        this.store = store;
        this.key = key == null ? null : key.clone();
        this.clock = clock;
    }

    /** Hears every new line once, on whichever thread brought it. */
    public void onLine(Consumer<JsonObject> l) {
        listeners.add(l);
    }

    /**
     * A line as the pages get it, from what was stored or sent: {uid, at, server, kind (player, staff, server), name, text,
     * player when a player said it}; null when it is not a chat line signed with this panel's key.
     */
    public JsonObject read(String signed) {
        String json = key == null ? signed : Signed.open(key, signed);
        if (json == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject()) {
                return null;
            }
            JsonObject o = e.getAsJsonObject();
            if (!"chat".equals(str(o, "type")) || !o.has("v") || o.get("v").getAsInt() != 1) {
                return null; // a snapshot or an answer signed with the same key is never read as chat
            }
            String kind = str(o, "kind"), uid = str(o, "uid"), name = str(o, "name"), text = str(o, "text"), server = str(o, "server");
            if (!(kind.equals("player") || kind.equals("staff") || kind.equals("server")) || uid.isEmpty() || uid.length() > 36 || server.isEmpty()
                    || server.length() > 64 || name.length() > 32 || text.isEmpty() || text.length() > MAX_TEXT || !o.has("at")) {
                return null;
            }
            JsonObject out = new JsonObject();
            out.addProperty("uid", uid);
            out.addProperty("at", o.get("at").getAsLong());
            out.addProperty("server", server);
            out.addProperty("kind", kind);
            out.addProperty("name", name);
            out.addProperty("text", text);
            if (kind.equals("player")) {
                String p = str(o, "player");
                out.addProperty("player", p.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ? p : "");
            }
            return out;
        } catch (RuntimeException bad) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** A message from the web channel; true when it was a chat line (shown or dropped), false for anything else. */
    public boolean acceptMessage(String message) {
        String[] parts = message.split("\n", 5);
        if (parts.length < 5 || !parts[3].equals("chat")) {
            return false;
        }
        JsonObject o = read(parts[4]);
        if (o != null && o.get("at").getAsLong() >= clock.getAsLong() - LIVE_MAX_AGE_MS) { // an old signed line sent again is not news (audit)
            deliver(o); // the signature is the proof: every game server holds the same key, so which one carried it proves nothing (audit)
        }
        return true;
    }

    private void deliver(JsonObject o) {
        synchronized (seen) {
            if (seen.put(o.get("uid").getAsString(), Boolean.TRUE) != null) {
                return; // from Redis and from the table both: once
            }
        }
        listeners.forEach(l -> l.accept(o));
    }

    /** Reads the table for lines Redis did not bring (every few seconds). The first call only notes where the table ends. */
    public void poll() throws Exception {
        if (lastId < 0) {
            lastId = store.chatLastId();
            startId = lastId;
            return;
        }
        long from = Math.max(startId, lastId);
        // a little behind where it was: a line given a lower id but written after a higher one is still found (audit); uids drop repeats
        for (PanelStore.ChatRow r : store.chatAfter(Math.max(startId, from - OVERLAP), 500)) {
            lastId = Math.max(lastId, r.id());
            JsonObject o = row(r);
            if (o != null) {
                deliver(o);
            }
        }
    }

    /** A stored row's line, only when the row's own uid is the signed line's (a copy stored under another uid is dropped; audit). */
    private JsonObject row(PanelStore.ChatRow r) {
        JsonObject o = read(r.line());
        return o != null && (r.uid() == null || r.uid().equals(o.get("uid").getAsString())) ? o : null;
    }

    private List<JsonObject> lines(List<PanelStore.ChatRow> rows) {
        List<JsonObject> out = new ArrayList<>();
        for (PanelStore.ChatRow r : rows) {
            JsonObject o = row(r);
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }

    /** A page of older lines, oldest first, and whether there may be more (counted before signatures are checked, audit). */
    public record Page(List<JsonObject> lines, boolean more) {
    }

    /**
     * Up to {@link #PAGE} lines at or before a time, oldest first (scrolling up, D309, D311). "At or before": lines sharing
     * that millisecond are not skipped; the page drops the ones it has already by uid.
     */
    public Page before(long atMs) throws Exception {
        List<PanelStore.ChatRow> rows = store.chatBefore(atMs, PAGE);
        List<JsonObject> l = lines(rows);
        java.util.Collections.reverse(l);
        return new Page(l, rows.size() >= PAGE);
    }

    /** The same, as a plain list (tests). */
    public List<JsonObject> beforeLines(long atMs) throws Exception {
        return before(atMs).lines();
    }

    /** The lines around a time (a search result tapped, D312): 20 before it and 20 from it, oldest first. */
    public List<JsonObject> around(long atMs) throws Exception {
        List<JsonObject> l = lines(store.chatBefore(atMs - 1, 20));
        java.util.Collections.reverse(l);
        l.addAll(lines(store.chatFrom(atMs, 20)));
        return l;
    }

    /** Search (D312): words, a player's name, a server, the last {@code days} days; newest first, at most 100. */
    public List<JsonObject> search(String words, String name, String server, int days) throws Exception {
        long since = clock.getAsLong() - Math.max(1, Math.min(30, days)) * 24L * 60 * 60 * 1000;
        return lines(store.searchChat(words.strip(), name.strip(), server.strip(), since, PAGE));
    }

    /**
     * A line said from the panel, stored and shown once: {@code voice} "staff" or "server", {@code to} "network" or a
     * server's name, {@code by} the staff member's Minecraft name.
     */
    public JsonObject record(String voice, String to, String by, String text) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("v", 1);
        o.addProperty("uid", UUID.randomUUID().toString());
        o.addProperty("at", clock.getAsLong());
        o.addProperty("server", to);
        o.addProperty("kind", voice);
        o.addProperty("name", by);
        o.addProperty("text", text);
        String json = o.toString(), line = key == null ? json : Signed.seal(key, json);
        JsonObject shown = read(line);
        if (shown == null) {
            throw new IllegalArgumentException("a line the panel could not read back"); // never stored unreadable (audit)
        }
        store.insertChat(str(o, "uid"), o.get("at").getAsLong(), to, by, text, line);
        deliver(shown);
        return shown;
    }

    /** Drops lines older than 30 days (D263); how many went. */
    public int prune() throws Exception {
        return store.pruneChat(clock.getAsLong() - KEEP_MS);
    }

    /**
     * The text a staff member typed, as it may be sent (D313): trimmed, at most {@link #MAX_TEXT} characters; null when it
     * is empty, too long or holds a section sign or a control character.
     */
    public static String clean(String raw) {
        String t = raw == null ? "" : raw.strip();
        // invisible format characters (a right-to-left override) become spaces, as the game shows them (audit)
        StringBuilder b = new StringBuilder(t.length());
        t.codePoints().forEach(c -> b.appendCodePoint(Character.getType(c) == Character.FORMAT ? ' ' : c));
        t = b.toString().strip();
        if (t.isEmpty() || t.length() > MAX_TEXT || t.indexOf('§') >= 0 || t.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return t;
    }
}
