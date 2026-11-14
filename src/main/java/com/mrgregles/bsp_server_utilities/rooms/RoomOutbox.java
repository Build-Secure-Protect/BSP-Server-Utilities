package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Notices about another server's room ({@value RoomLosses#KIND_LOST}: its machine was lost here; {@value RoomNotices#KIND_TOLD}:
 * its owner was told here), sent again until that server's shared rows show it was acted on (the audit before 0.11.0). A
 * server that was down or starting when a notice was sent never reads it (a starting server skips what came before it),
 * so each one is kept here: once the room's server has written its rows well after the notice and they still do not show
 * it (a lost room not in grace with no placed machine; a told room not marked told), it is sent again, at most
 * {@value #MAX_TRIES} times within {@value #MAX_AGE_MS} ms. Kept in memory: a restart of this server forgets them.
 * Server thread only.
 */
public final class RoomOutbox {
    /** How long after sending the room's server must have written its rows before the notice counts as missed (ms). */
    static final long MISSED_AFTER_MS = 30_000L;
    static final int MAX_TRIES = 10;
    static final long MAX_AGE_MS = 24 * 3_600_000L;
    static final int CHECK_TICKS = 1200;

    /** What to do with a notice kept here. */
    public enum Verdict { DONE, WAIT, AGAIN }

    /** One notice: its kind, the room's server and chunk, the payload, when first and last sent, and how often. */
    public record Item(String kind, String server, int x, int z, String payload, long firstAt, long lastAt, int tries) {
    }

    private static final List<Item> ITEMS = new ArrayList<>();
    private static int ticks;

    private RoomOutbox() {
    }

    /** Sends a notice about {@code server}'s room now and keeps it until that server's rows show it was acted on. */
    public static void send(String kind, String server, int x, int z, String payload, long now) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        s.publish(kind, payload);
        ITEMS.removeIf(i -> i.kind().equals(kind) && i.server().equalsIgnoreCase(server) && i.x() == x && i.z() == z);
        ITEMS.add(new Item(kind, server, x, z, payload, now, now, 1));
    }

    public static void tick(MinecraftServer server) {
        if (ITEMS.isEmpty() || ++ticks < CHECK_TICKS) {
            return;
        }
        ticks = 0;
        check(RoomList.table(), RoomList.read(), RoomLosses.now());
    }

    /** Looks at every notice kept against the table as last read; sends again those missed. */
    static void check(RoomTable table, boolean read, long now) {
        Storage s = Storage.active();
        Iterator<Item> it = ITEMS.iterator();
        List<Item> again = new ArrayList<>();
        while (it.hasNext()) {
            Item i = it.next();
            Verdict v = verdict(i, row(table, i), heard(table, i.server()), read, now);
            if (v == Verdict.DONE) {
                it.remove();
            } else if (v == Verdict.AGAIN && s != null) {
                it.remove();
                s.publish(i.kind(), i.payload());
                again.add(new Item(i.kind(), i.server(), i.x(), i.z(), i.payload(), i.firstAt(), now, i.tries() + 1));
            }
        }
        ITEMS.addAll(again);
    }

    /**
     * The rule: done when the room's row shows the notice acted on (lost: in grace, or a placed machine, which the room's
     * server refuses on; told: marked told, or no longer in grace), when the row is gone from a table read since the
     * start, or after {@value #MAX_TRIES} sends or {@value #MAX_AGE_MS} ms; sent again when the room's server wrote its
     * rows {@value #MISSED_AFTER_MS} ms or more after the last send without showing it; else wait.
     */
    public static Verdict verdict(Item i, Optional<RoomRow> row, @Nullable Long heardAt, boolean read, long now) {
        boolean givenUp = i.tries() >= MAX_TRIES || now - i.firstAt() >= MAX_AGE_MS;
        if (row.isEmpty()) {
            return read || givenUp ? Verdict.DONE : Verdict.WAIT;
        }
        if (givenUp || shown(i, row.get())) {
            return Verdict.DONE;
        }
        return heardAt != null && heardAt - i.lastAt() >= MISSED_AFTER_MS ? Verdict.AGAIN : Verdict.WAIT;
    }

    private static boolean shown(Item i, RoomRow r) {
        boolean grace = RoomRow.GRACE.equals(r.state());
        return RoomLosses.KIND_LOST.equals(i.kind()) ? grace || r.machines() > 0 : !grace || r.told();
    }

    private static Optional<RoomRow> row(RoomTable t, Item i) {
        for (RoomRow r : t.rows()) {
            if (r.server().equalsIgnoreCase(i.server()) && r.x() == i.x() && r.z() == i.z()) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    @Nullable
    private static Long heard(RoomTable t, String server) {
        for (Map.Entry<String, Long> e : t.heard().entrySet()) {
            if (e.getKey().equalsIgnoreCase(server)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ tests

    public static List<Item> itemsForTests() {
        return List.copyOf(ITEMS);
    }

    public static void resetForTests() {
        ITEMS.clear();
        ticks = 0;
    }
}
