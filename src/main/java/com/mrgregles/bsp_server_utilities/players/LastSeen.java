package com.mrgregles.bsp_server_utilities.players;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Where each player was last seen on the network, for the staff web panel's offline players (SU-114; owner decisions
 * D656, D657): one row per player in the shared table {@code <prefix>seen}, written by the server they are on at their
 * logout (a server stop counts as one), every 5 minutes while they are online (so a crash leaves a place at most 5
 * minutes old) and 10 seconds after they arrive (so a crash soon after a move between servers does not leave the server
 * they came from). The table keeps the newest row per player whichever server writes last. Each row carries its fields
 * signed with the action key ({@code webPanel.actionKey}), the only copy the panel believes.
 *
 * <p>Nothing is kept without shared storage: the panel reads the database. Writes wait (the newest row per player) while
 * the database is away and are tried again each minute.
 */
public final class LastSeen {
    public static final long REFRESH_MS = 5 * 60_000L, ARRIVAL_MS = 10_000L, RETRY_EVERY_MS = 60_000L;
    public static final int MAX_NAME = 32, MAX_SERVER = 64, MAX_DIMENSION = 160;

    /** The newest row per player not yet written. */
    private static final Map<UUID, StorageBackend.Seen> WAITING = new ConcurrentHashMap<>();
    /** Players who arrived here and have no row from this server yet, with when they arrived. */
    private static final Map<UUID, Long> ARRIVED = new ConcurrentHashMap<>();
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    /** Rows refused on their own while others were written, by player: a row refused {@link #MAX_ROW_FAILS} times is dropped (audit of SU-114). */
    private static final Map<UUID, Integer> ROW_FAILS = new ConcurrentHashMap<>();
    public static final int MAX_ROW_FAILS = 5;
    private static volatile boolean writeFailing;
    private static long lastRefresh;
    private static volatile long lastRetry;
    private static LongSupplier clock = System::currentTimeMillis;
    private static Function<MinecraftServer, Collection<ServerPlayer>> players = s -> s.getPlayerList().getPlayers();
    private static java.util.function.BiFunction<MinecraftServer, UUID, ServerPlayer> find = (s, id) -> s.getPlayerList().getPlayer(id);

    private LastSeen() {
    }

    // ------------------------------------------------------------------ events

    /** A new run of the server: nothing left over from an earlier one in the same game (audit of SU-114). */
    public static void onServerStarted() {
        WAITING.clear();
        ARRIVED.clear();
        ROW_FAILS.clear();
        QUEUED.set(false);
        writeFailing = false;
        lastRefresh = 0;
        lastRetry = 0;
    }

    public static void onLogin(ServerPlayer p) {
        if (p instanceof FakePlayer) {
            return;
        }
        if (Storage.active() != null) {
            ARRIVED.put(p.getUUID(), clock.getAsLong());
        }
    }

    /** Their logout: where they are now is where they left. */
    public static void onLogout(ServerPlayer p) {
        if (p instanceof FakePlayer) {
            return; // a machine's player may carry its owner's UUID: never their place (audit of SU-114)
        }
        ARRIVED.remove(p.getUUID());
        record(p, StorageBackend.SEEN_LOGOUT);
    }

    /**
     * As the server stops, before the storage closes: everyone still here is written as logged out now (their logout
     * events come after the storage has gone), with one last try.
     */
    public static void onServerStopping(Collection<ServerPlayer> here) {
        for (ServerPlayer p : here) {
            ARRIVED.remove(p.getUUID());
            record(p, StorageBackend.SEEN_LOGOUT);
        }
        Storage s = Storage.active();
        if (s != null && !WAITING.isEmpty()) {
            QUEUED.set(false);
            flush(s);
            // said, not lost without a word, should the database be too slow for the storage's last seconds (audit of SU-114)
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities: {} players' last places waited for the database at the stop; tried once more as the storage closes", WAITING.size());
        }
    }

    /** Every server tick: arrivals after 10 s, everyone every 5 minutes, a failed write again a minute after it failed. */
    public static void tick(MinecraftServer server) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        long now = clock.getAsLong();
        if (now - lastRefresh >= REFRESH_MS) {
            lastRefresh = now;
            ARRIVED.values().removeIf(at -> now - at >= ARRIVAL_MS); // an arrival younger than 10 s still gets its own row (audit of SU-114)
            for (ServerPlayer p : players.apply(server)) {
                record(p, StorageBackend.SEEN_REFRESH);
            }
        } else if (!ARRIVED.isEmpty()) {
            for (Map.Entry<UUID, Long> e : new ArrayList<>(ARRIVED.entrySet())) { // only the arrivals, each looked up (audit)
                if (now - e.getValue() < ARRIVAL_MS) {
                    continue;
                }
                ARRIVED.remove(e.getKey());
                ServerPlayer p = find.apply(server, e.getKey());
                if (p != null) {
                    record(p, StorageBackend.SEEN_REFRESH);
                } // gone meanwhile: their logout wrote a row
            }
        }
        if (!WAITING.isEmpty() && writeFailing && now - lastRetry >= RETRY_EVERY_MS) {
            lastRetry = now;
            flush(s);
        }
    }

    // ------------------------------------------------------------------ the rows

    /** Queues this player's row; false without shared storage. */
    public static boolean record(ServerPlayer p, String kind) {
        Storage s = Storage.active();
        if (s == null || p instanceof FakePlayer) {
            return false;
        }
        BlockPos at = p.blockPosition();
        StorageBackend.Seen row = signed(new StorageBackend.Seen(p.getUUID(), clip(p.getGameProfile().getName(), MAX_NAME), clock.getAsLong(),
                clip(s.serverId(), MAX_SERVER), clip(p.level().dimension().location().toString(), MAX_DIMENSION), at.getX(), at.getY(), at.getZ(), kind, ""));
        WAITING.merge(row.player(), row, (old, fresh) -> fresh.atMs() >= old.atMs() ? fresh : old);
        if (!writeFailing) {
            flush(s); // while writes fail, only the minute's retry tries again, so an outage never holds up the other features' work
        }
        return true;
    }

    /**
     * The row with {@code line}: {@code {"type":"seen","player","name","at","server","dim","x","y","z","kind"}} signed with
     * the action key; empty without a key (the panel then shows the place as not known).
     */
    public static StorageBackend.Seen signed(StorageBackend.Seen r) {
        byte[] key = WebActions.currentKey();
        if (key == null) {
            return r;
        }
        return new StorageBackend.Seen(r.player(), r.name(), r.atMs(), r.server(), r.dimension(), r.x(), r.y(), r.z(), r.kind(), WebActions.signText(key, text(r)));
    }

    /** The text that is signed (the panel reads the same fields back). */
    public static String text(StorageBackend.Seen r) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "seen");
        o.addProperty("player", r.player().toString());
        o.addProperty("name", r.name());
        o.addProperty("at", r.atMs());
        o.addProperty("server", r.server());
        o.addProperty("dim", r.dimension());
        o.addProperty("x", r.x());
        o.addProperty("y", r.y());
        o.addProperty("z", r.z());
        o.addProperty("kind", r.kind());
        return o.toString();
    }

    /** One write of every waiting row on the worker; on a failure each waits for the next try unless a newer one came meanwhile. */
    private static void flush(Storage s) {
        if (!QUEUED.compareAndSet(false, true)) {
            return;
        }
        writeFailing = true; // until this write lands: a database not reached at all counts as failing too
        s.submit(QUEUED, () -> {
            List<StorageBackend.Seen> rows = new ArrayList<>();
            for (UUID id : new ArrayList<>(WAITING.keySet())) {
                StorageBackend.Seen r = WAITING.remove(id);
                if (r != null) {
                    rows.add(r);
                }
            }
            try {
                s.backend().saveSeen(rows);
                rows.forEach(r -> ROW_FAILS.remove(r.player()));
                writeFailing = false;
                if (!WAITING.isEmpty()) {
                    flush(s); // rows that came while this one was written
                }
            } catch (Exception e) {
                // one row the database keeps refusing must not hold up everyone's (audit of SU-114): each tried on its own; a row
                // refused while others went through is marked, and a marked row refused MAX_ROW_FAILS times is dropped, said once;
                // when every unmarked row fails the database is away and they all wait
                List<StorageBackend.Seen> failed = new ArrayList<>();
                if (rows.size() > 1) {
                    for (StorageBackend.Seen r : rows) {
                        try {
                            s.backend().saveSeen(List.of(r));
                            ROW_FAILS.remove(r.player());
                        } catch (Exception one) {
                            failed.add(r);
                        }
                    }
                } else {
                    failed.addAll(rows);
                }
                boolean away = failed.size() == rows.size(); // every row refused: the database is away, unless a row is known bad
                boolean back = false;
                for (StorageBackend.Seen r : failed) {
                    boolean counts = !away || ROW_FAILS.containsKey(r.player()); // refused while others went through, now or before
                    if (counts && ROW_FAILS.merge(r.player(), 1, Integer::sum) >= MAX_ROW_FAILS) {
                        ROW_FAILS.remove(r.player());
                        BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: {}'s last place was refused by the database {} times and is dropped: {}", r.name(), MAX_ROW_FAILS, e.toString());
                        continue;
                    }
                    WAITING.merge(r.player(), r, (newer, old) -> newer.atMs() >= old.atMs() ? newer : old);
                    back = true;
                }
                writeFailing = back; // nothing put back: the next row goes straight through
                lastRetry = clock.getAsLong(); // the next try a minute after this one
                if (!back && !WAITING.isEmpty()) {
                    flush(s); // rows that came meanwhile
                }
                if (away && back) {
                    throw e;
                }
            }
        });
    }

    private static String clip(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ status and tests

    /** Players whose row waits for the database (0 normally). */
    public static int waiting() {
        return WAITING.size();
    }

    /** Tests: the clock (null: the real one). */
    public static void useClockForTests(@Nullable LongSupplier c) {
        clock = c == null ? System::currentTimeMillis : c;
    }

    /** Tests: who is online here (mock players are not in the player list; null: the real list). */
    public static void usePlayersForTests(@Nullable Function<MinecraftServer, Collection<ServerPlayer>> list) {
        players = list == null ? s -> s.getPlayerList().getPlayers() : list;
        find = list == null ? (s, id) -> s.getPlayerList().getPlayer(id) : (s, id) -> list.apply(s).stream().filter(p -> p.getUUID().equals(id)).findFirst().orElse(null);
    }

    /** Tests: a clean slate, the 5-minute write just done. */
    public static void resetForTests() {
        WAITING.clear();
        ARRIVED.clear();
        ROW_FAILS.clear();
        QUEUED.set(false);
        writeFailing = false;
        lastRefresh = clock.getAsLong();
        lastRetry = clock.getAsLong();
    }
}
