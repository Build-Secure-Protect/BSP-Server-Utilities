package com.mrgregles.bsp_staff_panel.act;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * What the panel asks a game server to do (SU-35; BSP-Server Utilities' {@code WebActions} on the other side). An action
 * is JSON with a one-time {@code id}, the time, who asked ({@code by}, their Minecraft name, and {@code rank}) and the
 * server it is for; it is signed with the action key (HMAC-SHA256 of its URL-safe Base64) and sent as a notice
 * {@code web_action} on the game servers' own channel, through Redis and the notices table both (the servers drop the
 * second copy). The server's answer arrives on the web channel (kind {@code answer}); none within {@link #WAIT_SECONDS}
 * is answered for it. The panel checks the staff member's rank before it signs anything.
 */
public final class Actions {
    public static final String KIND_ACTION = "web_action", KIND_ANSWER = "answer", SOURCE = "panel";
    public static final int WAIT_SECONDS = 6;
    /** For actions that take longer on the game server: an UNDO taking items back slot by slot, a market REFRESH, bans waiting on the database (audit). */
    public static final int LONG_WAIT_SECONDS = 15;
    /** The fields every action carries; an action's own fields never replace them (audit: an UNDO's fields came from a game server's answer). */
    private static final java.util.Set<String> ENVELOPE = java.util.Set.of("v", "id", "at", "until", "by", "rank", "server");

    /** Where actions go: the servers' channel on Redis, and the notices table. */
    public interface Bus {
        void send(String uid, long at, String kind, String payload) throws Exception;
    }

    public record Answer(boolean ok, String message, com.google.gson.JsonElement data) {
        public Answer(boolean ok, String message) {
            this(ok, message, null);
        }
    }

    private final byte[] key;
    /** Signs what the panel sends: the panel's Ed25519 key (D426) on the network; the shared key in the demo and tests. */
    private volatile java.util.function.UnaryOperator<String> signer;
    private final Bus bus;
    private final LongSupplier clock;
    private final Map<String, CompletableFuture<Answer>> waiting = new ConcurrentHashMap<>();
    /** Which server each waiting action was sent to: only that server's answer is taken (audit). */
    private final Map<String, String> askedOf = new ConcurrentHashMap<>();
    /** Why a server cannot be asked now (its snapshot says its answers cannot reach the panel), or null (audit). */
    private volatile java.util.function.Function<String, String> refuse = server -> null;
    private final int waitSeconds;
    /** At most four actions waiting at once, and ten a minute for each staff member (audit). */
    private final java.util.concurrent.Semaphore busy = new java.util.concurrent.Semaphore(4);
    private final Map<String, java.util.ArrayDeque<Long>> recent = new ConcurrentHashMap<>();
    public static final int PER_MINUTE = 10;
    /** Reads that change nothing (a player's items, D337: every 15 s while a sheet is open) have their own allowance. */
    public static final int READS_PER_MINUTE = 30;

    public Actions(byte[] key, Bus bus, LongSupplier clock) {
        this(key, bus, clock, WAIT_SECONDS);
    }

    /** With another wait (tests). */
    public Actions(byte[] key, Bus bus, LongSupplier clock, int waitSeconds) {
        this.key = key.clone();
        byte[] k = this.key;
        this.signer = json -> sign(k, json);
        this.bus = bus;
        this.clock = clock;
        this.waitSeconds = waitSeconds;
    }

    /** Signs every action and notice with the panel's own private key (D426): the game servers check it with webPanel.panelKey. */
    public Actions signWith(com.mrgregles.bsp_staff_panel.crypto.PanelSigner panel) {
        signer = panel::sign;
        return this;
    }

    /** Sets the check made before anything is sent to a server: a reason it cannot answer now, or null. */
    public void refuseWhen(java.util.function.Function<String, String> reason) {
        refuse = reason;
    }

    public static String sign(byte[] key, String json) {
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return body + "." + HexFormat.of().formatHex(m.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Signs and sends one action to one server and waits for its answer (at most {@link #WAIT_SECONDS}).
     *
     * @param fields the action's own fields: {@code action} and what it needs
     */
    public Answer ask(String server, String by, String rank, JsonObject fields) {
        return ask(server, by, rank, fields, waitSeconds);
    }

    /** The same with its own wait in seconds ({@link #LONG_WAIT_SECONDS} for the slow ones). */
    public Answer ask(String server, String by, String rank, JsonObject fields, int wait) {
        return askAll(java.util.List.of(server), by, rank, fields, by, PER_MINUTE, "actions", wait).get(server);
    }

    /**
     * The same action to several servers at once (a chat line to the network, D313), counted as one action for the limits;
     * each server's answer by its name, all within {@link #WAIT_SECONDS}.
     */
    public Map<String, Answer> askAll(java.util.List<String> servers, String by, String rank, JsonObject fields) {
        return askAll(servers, by, rank, fields, by, PER_MINUTE, "actions", waitSeconds);
    }

    /** Private staff messages to players (SU-110, D642): their own allowance, as in game. */
    public static final int MESSAGES_PER_MINUTE = 30;

    /** A private staff line or end (SU-110): counted apart from other actions, {@link #MESSAGES_PER_MINUTE} a minute, as in game (D642). */
    public Answer message(String server, String by, String rank, JsonObject fields) {
        return askAll(java.util.List.of(server), by, rank, fields, "talk:" + by, MESSAGES_PER_MINUTE, "messages", waitSeconds).get(server);
    }

    /** A read that changes nothing on the game server (SU-39), counted apart from actions, {@link #READS_PER_MINUTE} a minute. */
    public Answer read(String server, String by, String rank, JsonObject fields) {
        return read(server, by, rank, fields, waitSeconds);
    }

    /** A read with its own wait (a market REFRESH). */
    public Answer read(String server, String by, String rank, JsonObject fields, int wait) {
        return askAll(java.util.List.of(server), by, rank, fields, "read:" + by, READS_PER_MINUTE, "reads", wait).get(server);
    }

    private Map<String, Answer> askAll(java.util.List<String> servers, String by, String rank, JsonObject fields, String budget, int perMinute, String what, int wait) {
        long now = clock.getAsLong();
        Map<String, Answer> out = new java.util.LinkedHashMap<>();
        java.util.List<String> able = new java.util.ArrayList<>();
        for (String server : servers) {
            String why = refuse.apply(server);
            if (why != null) {
                out.put(server, new Answer(false, why));
            } else {
                able.add(server);
            }
        }
        if (able.isEmpty()) {
            return out;
        }
        servers = able;
        java.util.ArrayDeque<Long> mine = recent.computeIfAbsent(budget, k -> new java.util.ArrayDeque<>());
        synchronized (mine) {
            while (!mine.isEmpty() && mine.peekFirst() < now - 60_000L) {
                mine.pollFirst();
            }
            if (mine.size() >= perMinute) {
                servers.forEach(s -> out.put(s, new Answer(false, "That is " + perMinute + " " + what + " in a minute: wait a moment.")));
                return out;
            }
            mine.addLast(now);
        }
        if (!busy.tryAcquire()) {
            servers.forEach(s -> out.put(s, new Answer(false, "The panel is waiting on other actions; try again in a moment.")));
            return out;
        }
        Map<String, String> ids = new java.util.LinkedHashMap<>();
        try {
            for (String server : servers) {
                String id = UUID.randomUUID().toString();
                JsonObject a = new JsonObject();
                a.addProperty("v", 1);
                a.addProperty("id", id);
                a.addProperty("at", now);
                a.addProperty("by", by);
                a.addProperty("rank", rank);
                a.addProperty("server", server);
                a.addProperty("until", now + wait * 1000L); // past this the panel has stopped waiting: the game server refuses it then (audit)
                for (Map.Entry<String, JsonElement> e : fields.entrySet()) {
                    if (!ENVELOPE.contains(e.getKey())) {
                        a.add(e.getKey(), e.getValue());
                    }
                }
                waiting.put(id, new CompletableFuture<>());
                askedOf.put(id, server);
                ids.put(server, id);
                try {
                    bus.send(id, now, KIND_ACTION, signer.apply(a.toString()));
                } catch (Exception e) {
                    waiting.remove(id).complete(new Answer(false, "The request could not be sent: " + e.getMessage()));
                    out.put(server, new Answer(false, "The request could not be sent: " + e.getMessage()));
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(wait);
            for (Map.Entry<String, String> e : ids.entrySet()) {
                if (out.containsKey(e.getKey())) {
                    continue;
                }
                CompletableFuture<Answer> f = waiting.get(e.getValue());
                try {
                    out.put(e.getKey(), f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (java.util.concurrent.TimeoutException t) {
                    out.put(e.getKey(), new Answer(false, "No answer from " + e.getKey() + " in " + wait + " seconds. It may still have happened: check the LOG tab in game before trying again."));
                } catch (Exception x) {
                    out.put(e.getKey(), new Answer(false, "The request could not be sent: " + x.getMessage()));
                }
            }
            return out;
        } finally {
            ids.values().forEach(waiting::remove);
            ids.values().forEach(askedOf::remove);
            busy.release();
        }
    }

    /**
     * A signed notice for every game server that waits for no answer (the notes counts, D332): the same signature as an
     * action, sent through the same bus.
     */
    public void notice(String kind, JsonObject json) throws Exception {
        long now = clock.getAsLong();
        json.addProperty("at", now);
        bus.send(UUID.randomUUID().toString(), now, kind, signer.apply(json.toString()));
    }

    /** A message from the web channel; true when it was an answer to one of ours. */
    public boolean acceptMessage(String message) {
        String[] parts = message.split("\n", 5);
        if (parts.length < 5 || !parts[3].equals(KIND_ANSWER)) {
            return false;
        }
        try {
            String text = com.mrgregles.bsp_staff_panel.crypto.Signed.open(key, parts[4]);
            if (text == null) {
                return true; // an answer not signed with our key: dropped (audit)
            }
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            String id = o.get("id").getAsString();
            CompletableFuture<Answer> f = waiting.get(id);
            if (f == null) {
                return true; // too late, or another panel's
            }
            String asked = askedOf.get(id), from = o.has("server") ? o.get("server").getAsString() : "";
            if (asked == null || !from.isEmpty() && !asked.equalsIgnoreCase(from)) {
                return true; // another game server answering for the one asked: not taken (audit; every game server names itself)
            }
            f.complete(new Answer(o.get("ok").getAsBoolean(), o.has("message") ? o.get("message").getAsString() : "", o.get("data")));
            return true;
        } catch (RuntimeException bad) {
            return false;
        }
    }

    /** One line in the game servers' notice format: id, time, source, kind, payload. */
    public static String line(String uid, long at, String kind, String payload) {
        return uid + "\n" + at + "\n" + SOURCE + "\n" + kind + "\n" + payload;
    }
}
