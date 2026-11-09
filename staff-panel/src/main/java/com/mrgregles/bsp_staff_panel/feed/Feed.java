package com.mrgregles.bsp_staff_panel.feed;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Every game server's newest snapshot (BSP-Server Utilities' feed, its {@code docs/API.md}), as the panel shows them.
 * A server not heard from for {@link #STALE_MS} is gone (about three missed snapshots at the usual 5 seconds).
 * Listeners hear of each change; the live page gets {@link #json()}.
 */
public final class Feed {
    public static final long STALE_MS = 20_000L;
    public static final int VERSION = 1;

    private record Entry(String server, long heardAt, JsonObject snapshot) {
    }

    private final Map<String, Entry> servers = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final LongSupplier clock;
    private volatile long lastMessageAt;

    /** Null only for the demo and tests: the game servers' snapshots are then taken unsigned. */
    private final byte[] key;

    public Feed(LongSupplier clock) {
        this(clock, null);
    }

    /** Taking only snapshots signed with the action key (what the game servers send). */
    public Feed(LongSupplier clock, byte[] key) {
        this.clock = clock;
        this.key = key == null ? null : key.clone();
    }

    public void onChange(Runnable r) {
        listeners.add(r);
    }

    /** One message as it travels on the web channel: id, time, server, kind, payload on their own lines. */
    public boolean acceptMessage(String message) {
        String[] parts = message.split("\n", 5);
        if (parts.length < 5 || !parts[3].equals("snapshot")) {
            return false;
        }
        return accept(parts[2], clock.getAsLong(), parts[4]);
    }

    /** A snapshot heard at a time (now for Redis, the row's time for the table). Older than what we have: ignored. */
    public boolean accept(String server, long heardAt, String payload) {
        String json = key == null ? payload : com.mrgregles.bsp_staff_panel.crypto.Signed.open(key, payload);
        if (json == null) {
            return false; // not signed with this panel's key: dropped (audit)
        }
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(json);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("players") || e.getAsJsonObject().get("v").getAsInt() != VERSION) {
                return false;
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException bad) {
            return false;
        }
        long at = o.has("at") ? o.get("at").getAsLong() : 0;
        // the server's name and the time come from the signed text, never from what carried it (audit 2026-11-09)
        String named = o.has("server") && o.get("server").isJsonPrimitive() ? o.get("server").getAsString() : null;
        if (named != null ? !named.equalsIgnoreCase(server) : key != null) {
            return false;
        }
        if (key != null && at < clock.getAsLong() - 3 * STALE_MS) {
            return false; // an old signed snapshot sent again
        }
        Entry old = servers.get(server);
        if (old != null && old.snapshot.has("at") && old.snapshot.get("at").getAsLong() >= at) {
            return false;
        }
        servers.put(server, new Entry(server, heardAt, o));
        lastMessageAt = clock.getAsLong();
        listeners.forEach(Runnable::run);
        return true;
    }

    /** The servers heard from lately, by name. */
    public List<String> live() {
        long cut = clock.getAsLong() - STALE_MS;
        servers.values().removeIf(e -> e.heardAt < cut);
        return servers.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** Where a player is: {server, name}, by id or (any case) by name; null when they are not online on a live server. */
    public String[] find(String idOrName) {
        for (String name : live()) {
            Entry e = servers.get(name);
            if (e == null) {
                continue;
            }
            for (JsonElement p : e.snapshot.getAsJsonArray("players")) {
                if (!p.isJsonObject()) {
                    continue;
                }
                JsonObject o = p.getAsJsonObject();
                String id = o.has("id") ? o.get("id").getAsString() : "", n = o.has("name") ? o.get("name").getAsString() : "";
                if (id.equals(idOrName) || n.equalsIgnoreCase(idOrName)) {
                    return new String[]{name, n, id};
                }
            }
        }
        return null;
    }

    /** A player's skin address as their server last sent it, or null. */
    public String skinOf(String playerId) {
        for (Entry e : servers.values()) {
            for (JsonElement p : e.snapshot.getAsJsonArray("players")) {
                if (p.isJsonObject() && p.getAsJsonObject().has("id") && playerId.equals(p.getAsJsonObject().get("id").getAsString())) {
                    JsonElement s = p.getAsJsonObject().get("skin");
                    return s != null && s.isJsonPrimitive() ? s.getAsString() : null;
                }
            }
        }
        return null;
    }

    /** Live servers whose BSP-InventorySync answers about graves (their snapshot says so). */
    public List<String> liveWithGraves() {
        return live().stream().filter(n -> {
            Entry e = servers.get(n);
            return e != null && e.snapshot.has("graves") && e.snapshot.get("graves").isJsonPrimitive() && e.snapshot.get("graves").getAsBoolean();
        }).toList();
    }

    public long lastMessageAt() {
        return lastMessageAt;
    }

    /**
     * What the live page gets: {@code {"at", "servers": [{server, at, mspt, max, count}], "players": [... each player
     * with its "server"]}}, players by name.
     */
    public JsonObject json() {
        long now = clock.getAsLong();
        JsonObject out = new JsonObject();
        out.addProperty("at", now);
        JsonArray list = new JsonArray();
        List<JsonObject> players = new ArrayList<>();
        for (String name : live()) {
            Entry e = servers.get(name);
            if (e == null) {
                continue;
            }
            JsonObject s = new JsonObject();
            s.addProperty("server", name);
            s.addProperty("at", e.snapshot.has("at") ? e.snapshot.get("at").getAsLong() : 0);
            s.addProperty("mspt", e.snapshot.has("mspt") ? e.snapshot.get("mspt").getAsDouble() : 0);
            s.addProperty("max", e.snapshot.has("max") ? e.snapshot.get("max").getAsInt() : 0);
            JsonArray ps = e.snapshot.getAsJsonArray("players");
            s.addProperty("count", ps.size());
            list.add(s);
            for (JsonElement p : ps) {
                if (p.isJsonObject()) {
                    JsonObject c = p.getAsJsonObject().deepCopy();
                    c.addProperty("server", name);
                    players.add(c);
                }
            }
        }
        players.sort(Comparator.comparing(p -> p.has("name") ? p.get("name").getAsString().toLowerCase() : ""));
        JsonArray pa = new JsonArray();
        players.forEach(pa::add);
        out.add("servers", list);
        out.add("players", pa);
        return out;
    }
}
