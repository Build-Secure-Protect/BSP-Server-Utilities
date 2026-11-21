package com.mrgregles.bsp_server_utilities.steals;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Totem steals for the staff web panel (owner decisions D613 to D622): every steal of a BSP-Core Shatter Totem (or a
 * Totem Forge's pour) that ends on this server, completed or failed, becomes one row in {@code <prefix>steals}, written
 * by this server alone, so no steal is counted twice across the network (D616). The row says where (server, dimension,
 * block), who stole from whom, the tier and its points, when the steal started and ended, and how long BSP-Core said it
 * would take, so the panel can show how fast a player steals and flag a steal that finished sooner than it could (D619).
 * Kept 90 days (D617): every server deletes older rows once an hour (the delete is the same everywhere).
 *
 * <p>Only with shared storage: the panel reads the shared table, so a server keeping its records to itself keeps none of
 * these. A write that fails waits (at most {@link #MAX_WAITING} rows) and is tried again each minute. Server thread only,
 * except the writes, which run on the storage worker.
 */
public final class StealRecorder {
    public static final long KEEP_MS = 90L * 86_400_000L;
    public static final int MAX_WAITING = 2000;
    static final long PRUNE_EVERY_MS = 3_600_000L, RETRY_EVERY_MS = 60_000L;
    /** A start never followed by an end (BSP-Core stopped mid-steal, say) is forgotten after this; BSP-Core's longest steal is far shorter. */
    static final long START_FORGET_MS = 6 * 3_600_000L;
    public static final String SOURCE_TOTEM = "totem", SOURCE_FORGE = "forge";

    /** A steal seen starting here: who, when (ms and the server's tick count), and how many seconds BSP-Core gave it. */
    record Start(UUID thief, long atMs, long tick, int seconds) {
    }

    /**
     * Failed attempts kept from flooding the table (audit of 0.16.0): BSP-Core has no limit on starting steals, and a
     * scripted start-and-leave fails at once. Another failed attempt by the same thief at the same totem within
     * {@link #FOLD_MS} of the last one recorded is not recorded again, and each thief has at most {@link #FAILS_PER_HOUR}
     * failed rows an hour; completed steals are always recorded.
     */
    static final long FOLD_MS = 60_000L;
    public static final int FAILS_PER_HOUR = 60;
    private static final Map<String, Long> LAST_FAIL = new HashMap<>();
    private static final Map<UUID, int[]> FAILS = new HashMap<>(); // thief -> [hour index, failed rows this hour]
    private static long folded;

    private static final Map<GlobalPos, Start> STARTS = new HashMap<>();
    private static final ConcurrentLinkedQueue<StorageBackend.Steal> WAITING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger WAITING_COUNT = new AtomicInteger();
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static LongSupplier clock = System::currentTimeMillis;
    /** The server's tick count, which is what BSP-Core counts a steal in (a lag catch-up runs ticks back to back; the clock does not). */
    private static java.util.function.ToLongFunction<MinecraftServer> ticks = MinecraftServer::getTickCount;
    private static long lastPrune, lastRetry;
    private static volatile boolean writeFailing;

    private StealRecorder() {
    }

    private static final BspCore.StealListener LISTENER = new BspCore.StealListener() {
        @Override
        public void started(MinecraftServer server, GlobalPos where, @Nullable UUID owner, UUID thief, String thiefName, int seconds) {
            try {
                StealRecorder.started(server, where, thief, seconds);
                com.mrgregles.bsp_server_utilities.web.WebTotems.announceStart(server, where, owner, thief, thiefName, seconds, clock.getAsLong());
            } catch (RuntimeException | LinkageError e) {
                // Forge's bus hands a listener's throw back to BSP-Core, which posts this before warning the owner: never break the steal (audit)
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: a steal start by {} was not noted: {}", thiefName, e.toString());
            }
        }

        @Override
        public void ended(MinecraftServer server, GlobalPos where, @Nullable UUID owner, UUID thief, String thiefName, boolean success, String reasonKey) {
            try {
                StealRecorder.ended(server, where, owner, thief, thiefName, success, reasonKey);
            } catch (RuntimeException | LinkageError e) {
                // BSP-Core posts the end before giving the thief the totem: a throw here would lose them a steal they made (audit)
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: a steal by {} was not recorded: {}", thiefName, e.toString());
            }
        }
    };

    /** At mod start: listens to BSP-Core's steal events (nothing without BSP-Core). */
    public static void register() {
        BspCore.onSteals(LISTENER);
    }

    public static void onServerStarted() {
        STARTS.clear();
        LAST_FAIL.clear();
        FAILS.clear();
        lastPrune = 0; // the first tick prunes
        lastRetry = clock.getAsLong();
    }

    /** A steal began at {@code where}. */
    public static void started(MinecraftServer server, GlobalPos where, UUID thief, int seconds) {
        STARTS.put(where, new Start(thief, clock.getAsLong(), ticks.applyAsLong(server), Math.max(0, seconds)));
    }

    /** A steal at {@code where} ended; the row is built now, while the totem still stands with its old owner. */
    public static void ended(MinecraftServer server, GlobalPos where, @Nullable UUID owner, UUID thief, String thiefName, boolean success, String reasonKey) {
        long now = clock.getAsLong();
        Start start = STARTS.remove(where);
        if (start != null && !start.thief().equals(thief)) {
            start = null; // another thief's start: never mixed with this end
        }
        if (!success && folded(thief, where, now)) {
            return;
        }
        BspCore.TotemInfo info = BspCore.totemInfo(server, where);
        // the server ticks it took, at least 1 once its start was seen (a finish in the same tick is the fastest of all)
        int took = start == null ? 0 : (int) Math.max(1, Math.min(Integer.MAX_VALUE, ticks.applyAsLong(server) - start.tick()));
        String ownerName = owner == null ? "" : !info.ownerName().isEmpty() ? info.ownerName() : nameOf(server, owner); // BSP-Core's own name first (audit)
        StorageBackend.Steal row = new StorageBackend.Steal(UUID.randomUUID().toString(), Storage.currentServerId(), clip(where.dimension().location().toString(), 160),
                where.pos().getX(), where.pos().getY(), where.pos().getZ(), thief, clip(thiefName, 32), owner, clip(ownerName, 32),
                info.source(), info.tier(), info.points(), start == null ? 0 : start.atMs(), now, start == null ? 0 : start.seconds(),
                success, success ? "" : clip(reasonKey, 128), took, "");
        row = row.withLine(com.mrgregles.bsp_server_utilities.web.WebTotems.signedEnd(row)); // the panel trusts only this signed copy (audit)
        if (record(row)) {
            com.mrgregles.bsp_server_utilities.web.WebTotems.announceEnd(row); // the panel's TOTEMS page, live (D620): only for a row that will be in the table
        }
    }

    /** Whether this failed attempt is left out: another by the same thief at the same totem within a minute, or past the hour's cap. */
    private static boolean folded(UUID thief, GlobalPos where, long now) {
        String key = thief + "|" + where;
        Long last = LAST_FAIL.get(key);
        if (last != null && now - last < FOLD_MS) {
            folded++;
            return true;
        }
        int hour = (int) (now / 3_600_000L);
        int[] f = FAILS.computeIfAbsent(thief, k -> new int[]{hour, 0});
        if (f[0] != hour) {
            f[0] = hour;
            f[1] = 0;
        }
        if (f[1] >= FAILS_PER_HOUR) {
            if (folded++ % 100 == 0) {
                BSPServerUtilities.LOGGER.info("BSP-Server Utilities: {} has failed over {} steals this hour; the rest of this hour's failures are not recorded", thief, FAILS_PER_HOUR);
            }
            return true;
        }
        f[1]++;
        LAST_FAIL.put(key, now);
        return false;
    }

    /** Failed attempts left out so far (tests, status). */
    public static long folded() {
        return folded;
    }

    /** Writes a steal to the shared table; false when it is not kept (no shared storage, or too many already waiting). */
    public static boolean record(StorageBackend.Steal row) {
        Storage s = Storage.active();
        if (s == null) {
            return false;
        }
        if (WAITING_COUNT.incrementAndGet() > MAX_WAITING) {
            WAITING_COUNT.decrementAndGet();
            DROPPED.incrementAndGet();
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: a steal by {} was not recorded: {} steals already wait for the database", row.thiefName(), MAX_WAITING);
            return false;
        }
        WAITING.add(row);
        if (!writeFailing) {
            flush(s); // while writes fail, only the minute's retry tries again, so an outage never holds up the other features' work (audit)
        }
        return true;
    }

    /** One write of every waiting row on the worker; on a failure they wait for the next try. */
    private static void flush(Storage s) {
        if (!QUEUED.compareAndSet(false, true)) {
            return;
        }
        writeFailing = true; // until this write lands: a database not reached at all (ready() failing) counts as failing too (audit of 0.16.0)
        s.submit(QUEUED, () -> { // cleared even when the database is not reached, so the retry always gets through (audit)
            List<StorageBackend.Steal> rows = new ArrayList<>();
            StorageBackend.Steal r;
            while ((r = WAITING.poll()) != null) {
                rows.add(r);
            }
            try {
                s.backend().insertSteals(rows);
                WAITING_COUNT.addAndGet(-rows.size());
                writeFailing = false;
                if (!WAITING.isEmpty()) {
                    flush(s); // rows that came while this one was written
                }
            } catch (Exception e) {
                WAITING.addAll(rows); // still counted: tried again within a minute
                writeFailing = true;
                throw e;
            }
        });
    }

    /** Every server tick: a failed write tried again each minute, old rows deleted once an hour, forgotten starts dropped. */
    public static void tick(MinecraftServer server) {
        Storage s = Storage.active();
        long now = clock.getAsLong();
        if (s != null && !WAITING.isEmpty() && now - lastRetry >= RETRY_EVERY_MS) {
            lastRetry = now;
            flush(s);
        }
        if (now - lastPrune < PRUNE_EVERY_MS) {
            return;
        }
        lastPrune = now;
        STARTS.values().removeIf(st -> now - st.atMs() > START_FORGET_MS);
        LAST_FAIL.values().removeIf(t -> now - t > FOLD_MS);
        int hour = (int) (now / 3_600_000L);
        FAILS.values().removeIf(f -> f[0] != hour);
        if (s != null) {
            long before = now - KEEP_MS;
            s.submit(() -> {
                int n = s.backend().pruneSteals(before);
                if (n > 0) {
                    BSPServerUtilities.LOGGER.debug("BSP-Server Utilities: {} steals older than 90 days deleted", n);
                }
            });
        }
    }

    /**
     * As the server stops (before the storage closes, which sends what is queued): the waiting rows get one last try, and how
     * many are left is said rather than lost without a word (audit of 0.16.0).
     */
    public static void onServerStopping() {
        Storage s = Storage.active();
        int n = WAITING_COUNT.get();
        if (s == null || n == 0) {
            return;
        }
        QUEUED.set(false);
        flush(s);
        BSPServerUtilities.LOGGER.info("BSP-Server Utilities: {} steals waited for the database at the stop; tried once more as the storage closes", n);
    }

    /** A player's name as this server knows it (online, else its profile cache); empty for nobody or when unknown. */
    public static String nameOf(MinecraftServer server, @Nullable UUID player) {
        if (player == null) {
            return "";
        }
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        try {
            var cache = server.getProfileCache();
            if (cache != null) {
                return cache.get(player).map(com.mojang.authlib.GameProfile::getName).orElse("");
            }
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("profile cache: {}", e.toString());
        }
        return "";
    }

    public static String clip(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Rows waiting for the database (0 normally). */
    public static int waiting() {
        return WAITING_COUNT.get();
    }

    /** Rows dropped because the database was too far behind (0 normally). */
    public static long dropped() {
        return DROPPED.get();
    }

    /** Tests: the server's tick count (reset by {@link #resetForTests}). */
    public static void useTicksForTests(java.util.function.ToLongFunction<MinecraftServer> t) {
        ticks = t;
    }

    /** Tests: the clock (null: the real one), and a clean slate. */
    public static void useClockForTests(@Nullable LongSupplier c) {
        clock = c == null ? System::currentTimeMillis : c;
    }

    public static void resetForTests() {
        STARTS.clear();
        WAITING.clear();
        WAITING_COUNT.set(0);
        QUEUED.set(false);
        DROPPED.set(0);
        writeFailing = false;
        LAST_FAIL.clear();
        FAILS.clear();
        folded = 0;
        ticks = MinecraftServer::getTickCount;
        lastPrune = clock.getAsLong();
        lastRetry = clock.getAsLong();
    }

    /** How many steals seen starting here wait for their end. */
    public static int startsWaiting() {
        return STARTS.size();
    }
}
