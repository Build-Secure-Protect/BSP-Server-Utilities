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
    private final Bus bus;
    private final LongSupplier clock;
    private final Map<String, CompletableFuture<Answer>> waiting = new ConcurrentHashMap<>();
    private final int waitSeconds;
    /** At most four actions waiting at once, and ten a minute for each staff member (audit). */
    private final java.util.concurrent.Semaphore busy = new java.util.concurrent.Semaphore(4);
    private final Map<String, java.util.ArrayDeque<Long>> recent = new ConcurrentHashMap<>();
    public static final int PER_MINUTE = 10;

    public Actions(byte[] key, Bus bus, LongSupplier clock) {
        this(key, bus, clock, WAIT_SECONDS);
    }

    /** With another wait (tests). */
    public Actions(byte[] key, Bus bus, LongSupplier clock, int waitSeconds) {
        this.key = key.clone();
        this.bus = bus;
        this.clock = clock;
        this.waitSeconds = waitSeconds;
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
        long now = clock.getAsLong();
        java.util.ArrayDeque<Long> mine = recent.computeIfAbsent(by, k -> new java.util.ArrayDeque<>());
        synchronized (mine) {
            while (!mine.isEmpty() && mine.peekFirst() < now - 60_000L) {
                mine.pollFirst();
            }
            if (mine.size() >= PER_MINUTE) {
                return new Answer(false, "That is " + PER_MINUTE + " actions in a minute: wait a moment.");
            }
            mine.addLast(now);
        }
        if (!busy.tryAcquire()) {
            return new Answer(false, "The panel is waiting on other actions; try again in a moment.");
        }
        try {
            return send(server, by, rank, fields, now);
        } finally {
            busy.release();
        }
    }

    private Answer send(String server, String by, String rank, JsonObject fields, long now) {
        String id = UUID.randomUUID().toString();
        JsonObject a = new JsonObject();
        a.addProperty("v", 1);
        a.addProperty("id", id);
        a.addProperty("at", now);
        a.addProperty("by", by);
        a.addProperty("rank", rank);
        a.addProperty("server", server);
        for (Map.Entry<String, JsonElement> e : fields.entrySet()) {
            a.add(e.getKey(), e.getValue());
        }
        CompletableFuture<Answer> f = new CompletableFuture<>();
        waiting.put(id, f);
        try {
            bus.send(id, now, KIND_ACTION, sign(key, a.toString()));
            return f.get(waitSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return new Answer(false, "No answer from " + server + " in " + waitSeconds + " seconds. It may still have happened (answers are lost while Redis is down): check the LOG tab in game before trying again.");
        } catch (Exception e) {
            return new Answer(false, "The request could not be sent: " + e.getMessage());
        } finally {
            waiting.remove(id);
        }
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
            CompletableFuture<Answer> f = waiting.get(o.get("id").getAsString());
            if (f == null) {
                return true; // too late, or another panel's
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
