package com.mrgregles.bsp_staff_panel.feed;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A small Redis subscriber (RESP2 over a plain socket, no library), as BSP-Server Utilities has on the game servers:
 * AUTH, SUBSCRIBE to one channel, PING when quiet, and a reconnect after a drop (half a second, doubling, at most 30 s).
 * Each message goes to {@code onMessage} on this listener's own thread.
 */
public final class RedisListener {
    private static final int CONNECT_TIMEOUT_MS = 3000, READ_TIMEOUT_MS = 15_000;
    private static final long FIRST_RETRY_MS = 500, MAX_RETRY_MS = 30_000;
    private final String host, user, password, channel;
    private final int port;
    private final Consumer<String> onMessage;
    private final Thread thread;
    private volatile boolean running = true, subscribed;
    private volatile Socket socket;
    private volatile String lastError = "";

    public RedisListener(String host, int port, String user, String password, String channel, Consumer<String> onMessage) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.channel = channel;
        this.onMessage = onMessage;
        thread = new Thread(this::loop, "panel-redis");
        thread.setDaemon(true);
    }

    public RedisListener start() {
        thread.start();
        return this;
    }

    public boolean subscribed() {
        return subscribed;
    }

    public String status() {
        return "Redis " + host + ":" + port + " " + channel + ": " + (subscribed ? "listening" : "not connected") + (lastError.isEmpty() ? "" : " (" + lastError + ")");
    }

    public void close() {
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        thread.interrupt();
    }

    private void loop() {
        long retry = FIRST_RETRY_MS;
        while (running) {
            try (Socket s = new Socket()) {
                socket = s;
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(READ_TIMEOUT_MS);
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = new BufferedOutputStream(s.getOutputStream());
                if (!password.isEmpty()) {
                    command(out, user.isEmpty() ? new String[]{"AUTH", password} : new String[]{"AUTH", user, password});
                    if (read(in) instanceof Error e) {
                        throw new IOException("Redis refused the login: " + e.text);
                    }
                }
                command(out, "SUBSCRIBE", channel);
                read(in);
                subscribed = true;
                lastError = "";
                retry = FIRST_RETRY_MS;
                boolean pinged = false;
                while (running) {
                    Object frame;
                    try {
                        frame = read(in);
                    } catch (SocketTimeoutException quiet) {
                        if (pinged) {
                            throw new IOException("no answer to a ping");
                        }
                        command(out, "PING");
                        pinged = true;
                        continue;
                    }
                    pinged = false;
                    if (frame instanceof List<?> l && l.size() == 3 && "message".equals(l.get(0)) && l.get(2) instanceof String m) {
                        try {
                            onMessage.accept(m);
                        } catch (RuntimeException e) {
                            lastError = "a message could not be read: " + e;
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (running) {
                    lastError = String.valueOf(e.getMessage());
                }
            } finally {
                subscribed = false;
                socket = null;
            }
            try {
                Thread.sleep(retry);
            } catch (InterruptedException e) {
                return;
            }
            retry = Math.min(retry * 2, MAX_RETRY_MS);
        }
    }

    private record Error(String text) {
    }

    private static void command(OutputStream out, String... args) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String a : args) {
            byte[] bytes = a.getBytes(StandardCharsets.UTF_8);
            b.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            b.write(bytes);
            b.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write(b.toByteArray());
        out.flush();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read();
                return b.toString(StandardCharsets.UTF_8);
            }
            b.write(c);
        }
        throw new IOException("Redis closed the connection");
    }

    private static Object read(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new IOException("Redis closed the connection");
        }
        String l = line(in);
        switch (type) {
            case '+':
                return l;
            case '-':
                return new Error(l);
            case ':':
                return Long.parseLong(l);
            case '$': {
                int n = Integer.parseInt(l);
                if (n < 0) {
                    return null;
                }
                byte[] data = in.readNBytes(n);
                in.read();
                in.read();
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(l);
                List<Object> list = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    list.add(read(in));
                }
                return list;
            }
            default:
                throw new IOException("not a Redis reply");
        }
    }
}
