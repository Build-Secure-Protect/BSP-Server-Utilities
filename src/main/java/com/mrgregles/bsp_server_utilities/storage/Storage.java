package com.mrgregles.bsp_server_utilities.storage;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The shared storage (owner decision D5), the same shape as BSP RankUp's: database work on one worker thread with the
 * results handed back on the server thread, and notices between servers that go through Redis when it is connected
 * and are always written to the notice table too, which every server reads on a timer; a notice acts once, by its uid,
 * and never on the server that sent it. Features register a handler per notice kind ({@link #on}) and add their own
 * tables to {@link StorageBackend} as they are built.
 *
 * <p>Without shared storage nothing here runs ({@link #active()} is null) and each feature keeps its record in the
 * world. Notices older than a day are deleted by every server once an hour (the delete is the same everywhere).
 */
public final class Storage {
    private static final int NOTICE_BATCH = 100, SEEN_LIMIT = 20_000;
    private static final long FLUSH_MS = 5000, PRUNE_EVERY_MS = 3_600_000L, NOTICE_KEEP_MS = 86_400_000L;
    private static final Runnable STOP = () -> {
    };
    /** Handlers per notice kind, registered once by each feature; called on the server thread. */
    private static final Map<String, BiConsumer<MinecraftServer, StorageBackend.Notice>> HANDLERS = new ConcurrentHashMap<>();

    /** A unit of database work. */
    public interface Task {
        void run() throws Exception;
    }

    @Nullable
    private static volatile Storage active;

    private final MinecraftServer server;
    private final StorageBackend backend;
    private final String serverId;
    private final int pollSeconds;
    private final LinkedBlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    @Nullable
    private final Thread worker;
    private volatile long lastNoticeId = -1;
    private volatile long lastPollMs, lastPruneMs;
    private volatile String lastError = "";
    private volatile int errors, delivered;
    @Nullable
    private volatile RedisLink redis;
    private volatile int safetyPollSeconds = 30;
    private final Set<String> seen = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    }));
    private final List<Consumer<StorageBackend.Notice>> noticeListeners = new CopyOnWriteArrayList<>();

    private Storage(MinecraftServer server, StorageBackend backend, String serverId, int pollSeconds, boolean threaded) {
        this.server = server;
        this.backend = backend;
        this.serverId = serverId;
        this.pollSeconds = pollSeconds;
        this.lastPruneMs = System.currentTimeMillis();
        if (threaded) {
            worker = new Thread(this::loop, "bsp-su-storage");
            worker.setDaemon(true);
            worker.start();
        } else {
            worker = null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the shared storage when the configs say it is shared (see {@link StorageSettings}). */
    public static void start(MinecraftServer server) {
        stop();
        StorageSettings.Resolved settings = StorageSettings.current();
        settings.notes().forEach(note -> BSPServerUtilities.LOGGER.info("BSP-Server Utilities storage: {}", note));
        if (!settings.shared()) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities keeps its records on this server only (server id '{}')", settings.serverId());
            return;
        }
        JdbcBackend backend = new JdbcBackend(settings.database());
        Storage s = new Storage(server, backend, settings.serverId(), settings.pollSeconds(), true);
        active = s;
        s.submit(() -> {
            backend.init();
            s.lastNoticeId = backend.latestNoticeId();
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities storage connected: {} (this server is '{}')", backend.describe(), s.serverId);
        });
        if (settings.redis() != null) {
            s.safetyPollSeconds = settings.safetyPollSeconds();
            s.connectRedis(settings.redis());
        }
    }

    /** A storage on a given backend without a worker thread: {@link #drain()} runs its work. For tests; never active. */
    public static Storage startWith(MinecraftServer server, StorageBackend backend, String serverId) {
        Storage s = new Storage(server, backend, serverId, 5, false);
        s.submit(() -> {
            backend.init();
            s.lastNoticeId = backend.latestNoticeId();
        });
        return s;
    }

    public static void stop() {
        Storage s = active;
        active = null;
        if (s != null) {
            s.close();
        }
    }

    /** Tests: makes a {@link #startWith} storage the active one (null to go back to none). Never used outside tests. */
    public static void useForTests(@Nullable Storage s) {
        active = s;
    }

    /** The running shared storage, or null when records are kept on this server only. */
    @Nullable
    public static Storage active() {
        return active;
    }

    /** This server's name on the network, whether or not anything is shared. */
    public static String currentServerId() {
        Storage s = active;
        return s != null ? s.serverId : StorageSettings.current().serverId();
    }

    /**
     * Registers what a feature does with a notice of the given kind from another server (on the server thread). One
     * handler per kind; a later registration replaces the earlier.
     */
    public static void on(String kind, BiConsumer<MinecraftServer, StorageBackend.Notice> handler) {
        HANDLERS.put(kind, handler);
    }

    public String serverId() {
        return serverId;
    }

    public StorageBackend backend() {
        return backend;
    }

    public void connectRedis(RedisLink.Settings settings) {
        RedisLink old = redis;
        if (old != null) {
            old.close();
        }
        redis = new RedisLink(settings, this::onRedisMessage);
    }

    @Nullable
    public RedisLink redis() {
        return redis;
    }

    /** Sends what is queued (up to a few seconds) and closes the database and Redis. */
    public void close() {
        RedisLink r = redis;
        if (r != null) {
            r.close();
            redis = null;
        }
        if (worker != null) {
            work.add(STOP);
            try {
                worker.join(FLUSH_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            drain();
        }
        running.set(false);
        try {
            backend.close();
        } catch (Exception e) {
            BSPServerUtilities.LOGGER.debug("closing the storage: {}", e.toString());
        }
    }

    public String status() {
        RedisLink r = redis;
        return backend.describe() + ", this server '" + serverId + "', " + (lastError.isEmpty() ? "no errors" : errors + " errors, last: " + lastError)
                + ", " + delivered + " notices handled" + (r == null ? ", no Redis" : "; " + r.status());
    }

    // ------------------------------------------------------------------ the worker

    public void submit(Task task) {
        work.add(() -> {
            try {
                task.run();
            } catch (Exception e) {
                errors++;
                String message = e.toString();
                if (!message.equals(lastError)) {
                    BSPServerUtilities.LOGGER.warn("BSP-Server Utilities storage: {}", message);
                }
                lastError = message;
            }
        });
    }

    /** Runs every queued task on the calling thread (tests, and the last flush of an unthreaded storage). */
    public void drain() {
        Runnable r;
        while ((r = work.poll()) != null) {
            if (r != STOP) {
                r.run();
            }
        }
    }

    private void loop() {
        while (running.get()) {
            try {
                Runnable r = work.poll(1, TimeUnit.SECONDS);
                if (r == STOP) {
                    return;
                }
                if (r != null) {
                    r.run();
                }
                long now = System.currentTimeMillis();
                long every = (redis != null && redis.subscribed() ? safetyPollSeconds : pollSeconds) * 1000L;
                if (now - lastPollMs >= every) {
                    lastPollMs = now;
                    pollNotices();
                }
                if (now - lastPruneMs >= PRUNE_EVERY_MS) {
                    lastPruneMs = now;
                    pruneNotices(now - NOTICE_KEEP_MS);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities storage worker: {}", e.toString());
            }
        }
    }

    /** Deletes notices created before the given time (every server does this once an hour). */
    public void pruneNotices(long beforeMs) {
        submit(() -> {
            int n = backend.pruneNotices(beforeMs);
            if (n > 0) {
                BSPServerUtilities.LOGGER.debug("BSP-Server Utilities: {} old notices deleted", n);
            }
        });
    }

    // ------------------------------------------------------------------ notices

    /** Sends a notice to every other server: through Redis at once when connected, and always into the table. */
    public void publish(String kind, String payload) {
        StorageBackend.Notice n = new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), serverId, kind, payload);
        seen.add(n.uid()); // our own copy never acts here
        RedisLink r = redis;
        if (r != null) {
            r.publish(encode(n));
        }
        submit(() -> backend.publish(n));
    }

    /** Listeners for tests: every notice from another server, after its handler ran. */
    public void onNotice(Consumer<StorageBackend.Notice> listener) {
        noticeListeners.add(listener);
    }

    static String encode(StorageBackend.Notice n) {
        return n.uid() + "\n" + n.createdAt() + "\n" + n.source() + "\n" + n.kind() + "\n" + n.payload();
    }

    @Nullable
    static StorageBackend.Notice decode(String message) {
        String[] parts = message.split("\n", 5);
        if (parts.length < 5) {
            return null;
        }
        try {
            return new StorageBackend.Notice(0, parts[0], Long.parseLong(parts[1]), parts[2], parts[3], parts[4]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void onRedisMessage(String message) {
        StorageBackend.Notice n = decode(message);
        if (n != null) {
            server.execute(() -> handle(n));
        }
    }

    /** Reads the notices the table has gained since the last read; on the worker thread (or a test's drain). */
    public void pollNotices() {
        submit(() -> {
            List<StorageBackend.Notice> rows = backend.fetchNoticesAfter(lastNoticeId, NOTICE_BATCH);
            for (StorageBackend.Notice n : rows) {
                lastNoticeId = Math.max(lastNoticeId, n.id());
                server.execute(() -> handle(n));
            }
        });
    }

    private void handle(StorageBackend.Notice n) {
        if (!seen.add(n.uid()) || n.source().equals(serverId)) {
            return;
        }
        delivered++;
        BiConsumer<MinecraftServer, StorageBackend.Notice> handler = HANDLERS.get(n.kind());
        if (handler != null) {
            try {
                handler.accept(server, n);
            } catch (RuntimeException e) {
                BSPServerUtilities.LOGGER.warn("Notice {} from {} could not be handled: {}", n.kind(), n.source(), e.toString());
            }
        }
        for (Consumer<StorageBackend.Notice> l : noticeListeners) {
            l.accept(n);
        }
    }

    /** Handles a notice at once, as if it had come from another server (tests, on the server thread). */
    public void deliverForTests(StorageBackend.Notice n) {
        handle(n);
    }

    public int delivered() {
        return delivered;
    }

    /** How many tasks wait for the worker (tests). */
    public int queued() {
        return work.size();
    }
}
