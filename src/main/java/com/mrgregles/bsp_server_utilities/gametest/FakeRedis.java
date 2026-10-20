package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.storage.Resp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny Redis stand-in for GameTests, on a real local socket: AUTH, PING, SUBSCRIBE and PUBLISH in RESP2, enough for
 * this mod's client. {@link #stop()} and {@link #restart()} play an outage on the same port.
 */
public final class FakeRedis implements AutoCloseable {
    private final String password;
    private final int port;
    private volatile ServerSocket listener;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<Client>> channels = new ConcurrentHashMap<>();

    private FakeRedis(String password, int port) throws IOException {
        this.password = password;
        this.port = port;
        listen();
    }

    /** Starts on a free local port; an empty password means no AUTH is needed. */
    public static FakeRedis start(String password) throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = probe.getLocalPort();
        }
        return new FakeRedis(password, port);
    }

    public int port() {
        return port;
    }

    public int subscribers(String channel) {
        Set<Client> set = channels.get(channel);
        return set == null ? 0 : set.size();
    }

    /** Drops every connection and stops listening, like Redis going down. */
    public void stop() {
        ServerSocket l = listener;
        listener = null;
        if (l != null) {
            try {
                l.close();
            } catch (IOException ignored) {
                // stopping anyway
            }
        }
        for (Client c : clients) {
            c.close();
        }
        clients.clear();
        channels.clear();
    }

    /** Listens again on the same port. */
    public void restart() throws IOException {
        if (listener == null) {
            listen();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void listen() throws IOException {
        ServerSocket l = new ServerSocket();
        l.setReuseAddress(true);
        l.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        listener = l;
        Thread t = new Thread(() -> {
            while (listener == l) {
                try {
                    Socket s = l.accept();
                    Client c = new Client(s);
                    clients.add(c);
                    Thread ct = new Thread(c::serve, "fake-redis-client");
                    ct.setDaemon(true);
                    ct.start();
                } catch (IOException e) {
                    return; // closed
                }
            }
        }, "fake-redis-accept");
        t.setDaemon(true);
        t.start();
    }

    private final class Client {
        private final Socket socket;
        private final OutputStream out;
        private boolean authed = password.isEmpty(), subscribed;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new BufferedOutputStream(socket.getOutputStream());
        }

        void serve() {
            try (socket) {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                while (true) {
                    if (!(Resp.read(in) instanceof List<?> args) || args.isEmpty()) {
                        reply("-ERR protocol\r\n");
                        continue;
                    }
                    String cmd = String.valueOf(args.get(0)).toUpperCase(java.util.Locale.ROOT);
                    if (cmd.equals("AUTH")) {
                        authed = String.valueOf(args.get(args.size() - 1)).equals(password);
                        reply(authed ? "+OK\r\n" : "-WRONGPASS invalid username-password pair or user is disabled.\r\n");
                    } else if (!authed) {
                        reply("-NOAUTH Authentication required.\r\n");
                    } else if (cmd.equals("PING")) {
                        if (subscribed) {
                            array("pong", "");
                        } else {
                            reply("+PONG\r\n");
                        }
                    } else if (cmd.equals("SUBSCRIBE") && args.size() == 2) {
                        String ch = String.valueOf(args.get(1));
                        channels.computeIfAbsent(ch, k -> ConcurrentHashMap.newKeySet()).add(this);
                        subscribed = true;
                        synchronized (this) {
                            out.write("*3\r\n".getBytes(StandardCharsets.US_ASCII));
                            Resp.bulk(out, "subscribe");
                            Resp.bulk(out, ch);
                            out.write(":1\r\n".getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                        }
                    } else if (cmd.equals("PUBLISH") && args.size() == 3) {
                        String ch = String.valueOf(args.get(1)), message = String.valueOf(args.get(2));
                        int n = 0;
                        for (Client c : channels.getOrDefault(ch, Set.of())) {
                            if (c.message(ch, message)) {
                                n++;
                            }
                        }
                        reply(":" + n + "\r\n");
                    } else {
                        reply("-ERR unknown command '" + cmd + "'\r\n");
                    }
                }
            } catch (IOException e) {
                // the client went away or we were stopped
            } finally {
                clients.remove(this);
                channels.values().forEach(set -> set.remove(this));
            }
        }

        private synchronized void reply(String raw) throws IOException {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private synchronized void array(String... parts) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String p : parts) {
                Resp.bulk(buf, p);
            }
            out.write(buf.toByteArray());
            out.flush();
        }

        boolean message(String channel, String message) {
            try {
                array("message", channel, message);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
