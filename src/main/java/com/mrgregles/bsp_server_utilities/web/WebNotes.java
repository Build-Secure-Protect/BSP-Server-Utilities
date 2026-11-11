package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.network.NotesCountPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Player notes counts from the staff web panel (SU-38, D266, D321, D332). About every 30 seconds, and at once when a note
 * is added or deleted, the panel sends every game server a signed notice {@code web_notes}: for each online player with
 * notes, how many, who wrote the newest and when. This server keeps the newest one it was sent and passes it to the staff
 * in game who can open the admin panel, whose CLIENTS and STAFF tabs show it when they hover a player. Refused unless it
 * is signed with {@code webPanel.actionKey}, at most two minutes old and newer than the last one taken.
 */
public final class WebNotes {
    public static final String KIND = "web_notes";
    public static final long MAX_AGE_MS = 120_000L;
    public static final int MAX_PLAYERS = 1000;

    /** One player's notes: how many, the newest's author and time (ms). */
    public record Count(UUID id, String name, int n, String by, long at) {
    }

    private static volatile Map<UUID, Count> counts = Map.of();
    private static volatile long lastAt, takenAt;
    /** Counts older than this (three missed notices and more) are not shown any more (audit). */
    public static final long STALE_MS = 120_000L;
    private static volatile boolean dirty;
    private static int ticks;
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static BiConsumer<ServerPlayer, NotesCountPacket> sender;

    private WebNotes() {
    }

    public static void registerNotices() {
        Storage.on(KIND, (server, n) -> accept(n.payload()));
    }

    /** Takes a notice's payload; true when it was taken. */
    public static boolean accept(String payload) {
        if (!WebAgent.enabled()) {
            return false;
        }
        JsonObject o = WebActions.verify(WebActions.currentKey(), payload);
        long at;
        try {
            if (o == null || !"web_notes".equals(o.get("type").getAsString()) || o.get("v").getAsInt() != 1 || !o.get("counts").isJsonObject()) {
                return false; // only a notes notice: never an action signed with the same key (audit)
            }
            at = o.get("at").getAsLong();
        } catch (RuntimeException bad) {
            return false;
        }
        if (Math.abs(clock.getAsLong() - at) > MAX_AGE_MS || at <= lastAt) {
            return false; // old, or sent again
        }
        Map<UUID, Count> next = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("counts").entrySet()) {
            if (next.size() >= MAX_PLAYERS || !e.getValue().isJsonObject()) {
                continue;
            }
            try {
                JsonObject c = e.getValue().getAsJsonObject();
                UUID id = UUID.fromString(e.getKey());
                int n = c.get("n").getAsInt();
                String by = c.has("by") ? c.get("by").getAsString() : "", name = c.has("name") ? c.get("name").getAsString() : "";
                if (n > 0 && by.length() <= 32 && name.length() <= 32) {
                    next.put(id, new Count(id, name, Math.min(n, 9999), by, c.has("at") ? c.get("at").getAsLong() : 0));
                }
            } catch (RuntimeException bad) {
                // one odd entry is left out
            }
        }
        lastAt = at;
        takenAt = clock.getAsLong();
        boolean changed = !next.equals(counts);
        counts = Map.copyOf(next);
        if (changed) {
            dirty = true;
        }
        return true;
    }

    /** The counts while they are fresh and the panel is on here; none otherwise (audit). */
    private static Map<UUID, Count> current() {
        return WebAgent.enabled() && clock.getAsLong() - takenAt <= STALE_MS ? counts : Map.of();
    }

    @Nullable
    public static Count of(UUID player) {
        return current().get(player);
    }

    public static NotesCountPacket packet() {
        return new NotesCountPacket(new ArrayList<>(current().values()));
    }

    /** Every server tick: a change goes to the staff who can open the admin panel, at most once a second. */
    public static void tick(MinecraftServer server) {
        if (++ticks < 20) {
            return;
        }
        ticks = 0;
        if (!counts.isEmpty() && current().isEmpty()) {
            counts = Map.of(); // the panel stopped sending, or was switched off here: the hovers go once (audit)
            dirty = true;
        }
        if (!dirty) {
            return;
        }
        dirty = false;
        NotesCountPacket p = packet();
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            sendTo(player, p);
        }
    }

    /** At login, and on every change: only staff who can open the admin panel get it. */
    public static void sendTo(ServerPlayer player) {
        sendTo(player, packet());
    }

    private static void sendTo(ServerPlayer player, NotesCountPacket p) {
        if (!Perms.canEdit(player)) {
            return;
        }
        if (sender != null) {
            sender.accept(player, p);
        } else {
            ServerUtilsNetwork.sendTo(player, p);
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(@Nullable LongSupplier testClock, @Nullable BiConsumer<ServerPlayer, NotesCountPacket> send) {
        if (testClock != null) {
            clock = testClock;
        }
        sender = send;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        sender = null;
        counts = Map.of();
        lastAt = 0;
        takenAt = 0;
        dirty = false;
    }
}
