package com.mrgregles.bsp_server_utilities.storage;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * This mod's own small Redis client, as BSP RankUp and BSP Region Guard have it (owner decision D5): PUBLISH on one connection, SUBSCRIBE on another, with
 * AUTH and PING, over a plain socket (Redis stays on the local network). No library is bundled, so nothing can clash
 * with Mohist's libraries or another mod's copy of a Redis client.
 *
 * <p>Each connection has its own daemon thread and reconnects by itself, waiting longer after each failure (half a
 * second, doubling, at most 30 s). While the publishing connection is down, messages are not queued for Redis: the
 * MySQL message table carries them, and receivers drop whichever copy arrives second.
 */
public final class RedisLink {
    public static final String CHANNEL_PREFIX = "bsp_su:";
    private static final int CONNECT_TIMEOUT_MS = 3000, READ_TIMEOUT_MS = 15_000, MAX_QUEUE = 10_000;
    private static final long FIRST_RETRY_MS = 500, MAX_RETRY_MS = 30_000, KEEPALIVE_MS = 30_000, CLOSE_WAIT_MS = 2000;

    /** Where Redis is. {@code channel} is the name from the config; {@link #fullChannel()} adds the prefix. */
    public record Settings(String host, int port, String username, String password, String channel) {
        public String fullChannel() {
            return CHANNEL_PREFIX + channel;
        }

        public String describe() {
            return "redis " + host + ":" + port + ", channel " + fullChannel();
        }
    }

    private final Settings settings;
    private final Consumer<String> onMessage;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread publisher, subscriber;
    private final AtomicLong sent = new AtomicLong(), received = new AtomicLong(), dropped = new AtomicLong();
    private volatile boolean publishing, subscribed, everSubscribed;
    private volatile Socket subscriberSocket;
    private volatile String lastError = "";

    /** Connects in the background; {@code onMessage} gets each message on the channel, on the subscriber's thread. */
    public RedisLink(Settings settings, Consumer<String> onMessage) {
        this.settings = settings;
        this.onMessage = onMessage;
        publisher = new Thread(this::publishLoop, "bsp-su-redis-pub");
        subscriber = new Thread(this::subscribeLoop, "bsp-su-redis-sub");
        publisher.setDaemon(true);
        subscriber.setDaemon(true);
        publisher.start();
        subscriber.start();
    }

    /** Sends a message to every server on the channel, unless the publishing connection is down (MySQL carries it then). */
    public void publish(String message) {
        if (!running.get() || !publishing || !outbox.offer(message)) {
            dropped.incrementAndGet();
        }
    }

    /** True while this server is subscribed, so messages from the others arrive through Redis. */
    public boolean subscribed() {
        return subscribed;
    }

    public boolean publishing() {
        return publishing;
    }

    public String status() {
        String state = subscribed && publishing ? "connected" : subscribed ? "receiving only" : publishing ? "sending only" : "down";
        return settings.describe() + ": " + state + "; sent " + sent.get() + ", received " + received.get() + ", not sent " + dropped.get()
                + (lastError.isEmpty() ? "" : " (last error: " + lastError + ")");
    }

    /** Sends what is still queued (up to two seconds), then closes both connections. */
    public void close() {
        running.set(false);
        try {
            publisher.join(CLOSE_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        publisher.interrupt();
        Socket s = subscriberSocket;
        if (s != null) {
            try {
                s.close(); // unblocks the subscriber's read
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        subscriber.interrupt();
    }

    // ------------------------------------------------------------------ connections

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(settings.host(), settings.port()), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            if (!settings.password().isEmpty()) {
                OutputStream out = socket.getOutputStream();
                if (settings.username().isEmpty()) {
                    Resp.command(out, "AUTH", settings.password());
                } else {
                    Resp.command(out, "AUTH", settings.username(), settings.password());
                }
                if (Resp.read(socket.getInputStream()) instanceof Resp.Error e) {
                    throw new IOException("Redis refused the login: " + e.message());
                }
            }
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private void failed(String what, Exception e) {
        String message = what + ": " + e.getMessage();
        if (!message.equals(lastError)) {
            BSPServerUtilities.LOGGER.warn("Redis {} ({}); retrying, MySQL carries messages meanwhile", message, settings.describe());
        }
        lastError = message;
    }

    /** Waits before reconnecting; false when the link is closing. */
    private boolean pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            return false;
        }
        return running.get();
    }

    private void publishLoop() {
        long retry = FIRST_RETRY_MS;
        while (running.get()) {
            try (Socket socket = connect()) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                publishing = true;
                retry = FIRST_RETRY_MS;
                long lastUse = System.currentTimeMillis();
                while (true) {
                    String message = outbox.poll(1, TimeUnit.SECONDS);
                    if (message == null) {
                        if (!running.get()) {
                            return; // closing and everything queued is sent
                        }
                        if (System.currentTimeMillis() - lastUse >= KEEPALIVE_MS) {
                            // an idle connection a router dropped shows up here, not on the next real message
                            Resp.command(out, "PING");
                            check(Resp.read(in));
                            lastUse = System.currentTimeMillis();
                        }
                        continue;
                    }
                    Resp.command(out, "PUBLISH", settings.fullChannel(), message);
                    check(Resp.read(in));
                    sent.incrementAndGet();
                    lastUse = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                failed("sending", e);
            } finally {
                publishing = false;
            }
            dropped.addAndGet(outbox.size());
            outbox.clear(); // MySQL carries these
            if (!pause(retry)) {
                return;
            }
            retry = Math.min(retry * 2, MAX_RETRY_MS);
        }
    }

    private void subscribeLoop() {
        long retry = FIRST_RETRY_MS;
        while (running.get()) {
            try (Socket socket = connect()) {
                subscriberSocket = socket;
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                Resp.command(out, "SUBSCRIBE", settings.fullChannel());
                check(Resp.read(in));
                subscribed = true;
                retry = FIRST_RETRY_MS;
                if (!lastError.isEmpty() || !everSubscribed) {
                    BSPServerUtilities.LOGGER.info("Redis {} ({})", everSubscribed ? "connected again" : "connected", settings.describe());
                    lastError = "";
                    everSubscribed = true;
                }
                boolean pinged = false;
                while (running.get()) {
                    int first;
                    try {
                        first = Resp.next(in);
                    } catch (SocketTimeoutException idle) {
                        // nothing for a while: ask, and give up on the connection if the answer does not come either
                        if (pinged) {
                            throw new IOException("no answer to a ping");
                        }
                        Resp.command(out, "PING");
                        pinged = true;
                        continue;
                    }
                    pinged = false;
                    Object frame = Resp.readAfter(in, first);
                    if (frame instanceof List<?> list && list.size() == 3 && "message".equals(list.get(0)) && list.get(2) instanceof String message) {
                        received.incrementAndGet();
                        try {
                            onMessage.accept(message);
                        } catch (RuntimeException e) {
                            BSPServerUtilities.LOGGER.warn("Redis message could not be handled: {}", e.toString());
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (running.get()) {
                    failed("receiving", e);
                }
            } finally {
                subscribed = false;
                subscriberSocket = null;
            }
            if (!pause(retry)) {
                return;
            }
            retry = Math.min(retry * 2, MAX_RETRY_MS);
        }
    }

    private static void check(Object reply) throws IOException {
        if (reply instanceof Resp.Error e) {
            throw new IOException(e.message());
        }
    }
}
