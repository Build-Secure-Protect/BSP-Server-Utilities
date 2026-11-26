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
    /** A draw counts 5 s and BSP-Core pays it within a minute: one said to run for longer than this is stuck (audit of phase 5). */
    public static final long STUCK_DRAW_MS = 120_000L;
    public static final int VERSION = 1;

    private record Entry(String server, long heardAt, JsonObject snapshot) {
    }

    private final Map<String, Entry> servers = new ConcurrentHashMap<>();
    /** The skin last seen for each player, so an offline player's head can still be drawn (SU-115); the newest 5,000, this run only. */
    private final Map<String, String> lastSkins = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 5000;
        }
    });
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final LongSupplier clock;
    private volatile long lastMessageAt;
    private final Map<String, Long> unsignedSaid = new ConcurrentHashMap<>();

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
            Long said = unsignedSaid.get(server);
            if (said == null || clock.getAsLong() - said > 3_600_000L) { // named once an hour, so the one server with another key is found (audit)
                unsignedSaid.put(server, clock.getAsLong());
                System.err.println("[panel] a snapshot said to be from " + server.replaceAll("[^A-Za-z0-9_.-]", "?")
                        + " is not signed with this panel's action key: put actionKey= from panel.properties in that server's webPanel.actionKey");
            }
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
        if (o.has("stopping") && o.get("stopping").isJsonPrimitive() && o.get("stopping").getAsBoolean()) {
            servers.remove(server); // its last word before it stops: gone at once, not 20 s later (audit)
            lastMessageAt = clock.getAsLong();
            listeners.forEach(Runnable::run);
            return true;
        }
        servers.put(server, new Entry(server, heardAt, o));
        rememberSkins(o);
        lastMessageAt = clock.getAsLong();
        listeners.forEach(Runnable::run);
        return true;
    }

    /** The servers heard from lately, by name. */
    public List<String> live() {
        long now = clock.getAsLong();
        servers.values().removeIf(e -> e.heardAt < now - staleAfter(e));
        return servers.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * How long a server may stay quiet before it counts as gone: {@link #STALE_MS}, or about three of its own snapshots
     * when it sends less often (its snapshot's {@code every}; audit: a server sending every 25 s flickered).
     */
    private static long staleAfter(Entry e) {
        long every = 0;
        try {
            every = e.snapshot.has("every") ? e.snapshot.get("every").getAsLong() : 0;
        } catch (RuntimeException notANumber) {
            // the usual
        }
        return Math.max(STALE_MS, Math.min(30, Math.max(0, every)) * 3000L + 5000L);
    }

    /**
     * Why a server cannot be asked anything now, or null: its snapshot says no answer can reach the panel from it (its
     * Redis is not connected; answers travel on Redis only), so it carries nothing out (audit).
     */
    public String whyNoAnswers(String server) {
        Entry e = servers.get(server);
        if (e == null || !e.snapshot.has("answers") || !e.snapshot.get("answers").isJsonPrimitive() || e.snapshot.get("answers").getAsBoolean()) {
            return null;
        }
        return server + " cannot answer the panel: its Redis is not connected, so it carries out nothing from here until it is (its /su status says why). Nothing was sent.";
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
    private void rememberSkins(JsonObject snapshot) {
        for (JsonElement p : snapshot.getAsJsonArray("players")) {
            if (p.isJsonObject() && p.getAsJsonObject().has("id") && p.getAsJsonObject().get("skin") != null && p.getAsJsonObject().get("skin").isJsonPrimitive()) {
                lastSkins.put(p.getAsJsonObject().get("id").getAsString(), p.getAsJsonObject().get("skin").getAsString());
            }
        }
    }

    /** A player's skin address: from the feed while online, else the last one seen this run (an offline player's head); null when unknown. */
    public String skinOf(String playerId) {
        String now = liveSkinOf(playerId);
        return now != null ? now : lastSkins.get(playerId);
    }

    private String liveSkinOf(String playerId) {
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
        return liveWith("graves");
    }

    /** Live servers whose BSP-InventorySync reads a player's items (SU-39; their snapshot says so). */
    public List<String> liveWithItems() {
        return liveWith("items");
    }

    /** Live servers where BSP-Teleport moves players (SU-43). */
    public List<String> liveWithTp() {
        return liveWith("tp");
    }

    /** The name BSP-Teleport and the proxy know a server by (its snapshot's {@code tpName}), or the server's own id. */
    public String tpName(String server) {
        Entry e = servers.get(server);
        JsonElement n = e == null ? null : e.snapshot.get("tpName");
        return n != null && n.isJsonPrimitive() && !n.getAsString().isBlank() ? n.getAsString() : server;
    }

    /** A player online on a live server as the feed has them (with {@code server}), or null. */
    public JsonObject player(String id) {
        for (String name : live()) {
            Entry e = servers.get(name);
            if (e == null) {
                continue;
            }
            for (JsonElement p : e.snapshot.getAsJsonArray("players")) {
                if (p.isJsonObject() && p.getAsJsonObject().has("id") && p.getAsJsonObject().get("id").getAsString().equalsIgnoreCase(id)) {
                    JsonObject c = p.getAsJsonObject().deepCopy();
                    c.addProperty("server", name);
                    return c;
                }
            }
        }
        return null;
    }

    /** Live servers where BSP-Core 1.19 runs the Bounty Board (their snapshot's {@code bounties}), database connected first, then the busiest. */
    public List<String> liveWithBounties() {
        return liveWith("bounties").stream().sorted(java.util.Comparator.comparing((String n) -> !flag(n, "lotteryUp"))
                .thenComparing(n -> -players(n)).thenComparing(String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Live servers that read BSP-Core's board for the TOTEMS page (their snapshot's {@code totems}, SU-96), database connected first, then the busiest. */
    public List<String> liveWithTotems() {
        return liveWith("totems").stream().sorted(java.util.Comparator.comparing((String n) -> !flag(n, "lotteryUp"))
                .thenComparing(n -> -players(n)).thenComparing(String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Live servers whose BSP Region Guard answers for their regions (their snapshot's {@code regions}, SU-155), by name. */
    public List<String> liveWithRegions() {
        return liveWith("regions");
    }

    /**
     * When a live server's regions last changed (its snapshot's {@code regionsAt}, SU-158, D781), or -1 when it is not
     * live or does not say.
     */
    public long regionsAt(String server) {
        Entry e = servers.get(server);
        if (e == null || !live().contains(server)) {
            return -1;
        }
        JsonElement at = e.snapshot.get("regionsAt");
        return at != null && at.isJsonPrimitive() && at.getAsJsonPrimitive().isNumber() ? at.getAsLong() : -1;
    }

    /** A live server's numbers for the WORLDS tab: {@code mspt} and {@code count} (players), or null when it is not live. */
    public JsonObject numbers(String server) {
        Entry e = servers.get(server);
        if (e == null || !live().contains(server)) {
            return null;
        }
        JsonObject o = new JsonObject();
        JsonElement ms = e.snapshot.get("mspt");
        o.addProperty("mspt", ms != null && ms.isJsonPrimitive() && ms.getAsJsonPrimitive().isNumber() ? ms.getAsDouble() : 0); // never one odd snapshot breaking the grid (audit)
        o.addProperty("count", players(server));
        o.addProperty("heardAt", e.heardAt);
        return o;
    }

    /** Live servers that take private staff messages from the panel (their snapshot's {@code talk}, SU-109), the busiest first. */
    public List<String> liveWithTalk() {
        return liveWith("talk").stream().sorted(java.util.Comparator.comparing((String n) -> -players(n)).thenComparing(String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** Live servers where BSP-Core 1.18 reads a player's bank and market (their snapshot's {@code bank}), database connected first, then the busiest. */
    public List<String> liveWithBank() {
        return liveWith("bank").stream().sorted(java.util.Comparator.comparing((String n) -> !flag(n, "lotteryUp"))
                .thenComparing(n -> -players(n)).thenComparing(String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /**
     * Live servers with BSP-Core 1.16's market and lottery (SU-51), those whose database is connected first, then the
     * busiest first (D410: the busiest draws).
     */
    public List<String> liveWithMarket() {
        return liveWith("market").stream().sorted(java.util.Comparator.comparing((String n) -> !flag(n, "lotteryUp"))
                .thenComparing(n -> -players(n)).thenComparing(String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /**
     * Where the network's lottery draw stands (D411): {@code {phase, since, server}} of a live server whose snapshot says a
     * draw is counting (else starting), the earliest {@code since} among them; null when every server says IDLE.
     */
    public JsonObject lottery() {
        JsonObject best = null;
        for (String n : liveWith("market")) {
            Entry e = servers.get(n);
            if (e == null || !e.snapshot.has("lottery") || !e.snapshot.get("lottery").isJsonPrimitive()) {
                continue;
            }
            String phase = e.snapshot.get("lottery").getAsString();
            if (!phase.equals("COUNTING") && !phase.equals("DRAWING")) {
                continue;
            }
            long since;
            try {
                since = e.snapshot.has("lotterySince") && e.snapshot.get("lotterySince").isJsonPrimitive() ? e.snapshot.get("lotterySince").getAsLong() : 0;
            } catch (RuntimeException notANumber) {
                since = 0;
            }
            if (since > 0 && clock.getAsLong() - since > STUCK_DRAW_MS) {
                continue; // a server still saying a draw runs long after it began missed its end: it must not block DRAW (audit)
            }
            boolean better = best == null || (phase.equals("COUNTING") && !best.get("phase").getAsString().equals("COUNTING"))
                    || (phase.equals(best.get("phase").getAsString()) && since > 0 && (best.get("since").getAsLong() == 0 || since < best.get("since").getAsLong()));
            if (better) {
                best = new JsonObject();
                best.addProperty("phase", phase);
                best.addProperty("since", since);
                best.addProperty("server", n);
            }
        }
        return best;
    }

    private boolean flag(String server, String flag) {
        Entry e = servers.get(server);
        return e != null && e.snapshot.has(flag) && e.snapshot.get(flag).isJsonPrimitive() && e.snapshot.get(flag).getAsBoolean();
    }

    private int players(String server) {
        Entry e = servers.get(server);
        return e == null || !e.snapshot.has("players") || !e.snapshot.get("players").isJsonArray() ? 0 : e.snapshot.getAsJsonArray("players").size();
    }

    private List<String> liveWith(String flag) {
        return live().stream().filter(n -> {
            Entry e = servers.get(n);
            return e != null && e.snapshot.has(flag) && e.snapshot.get(flag).isJsonPrimitive() && e.snapshot.get(flag).getAsBoolean();
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
            s.addProperty("tp", e.snapshot.has("tp") && e.snapshot.get("tp").isJsonPrimitive() && e.snapshot.get("tp").getAsBoolean()); // SU-43
            s.addProperty("tpName", tpName(name));
            s.addProperty("market", flag(name, "market")); // SU-51
            long rat = regionsAt(name);
            if (rat >= 0) {
                s.addProperty("regionsAt", rat); // its regions changed: the WORLDS tab and the map read them again (D781)
            }
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
        JsonObject draw = lottery();
        if (draw != null) {
            out.add("lottery", draw); // a draw counting somewhere (D411)
        }
        out.add("players", pa);
        return out;
    }
}
