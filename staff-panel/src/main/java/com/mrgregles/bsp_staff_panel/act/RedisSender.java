package com.mrgregles.bsp_staff_panel.act;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** PUBLISH one message on Redis (a connection per message: actions are few), as RESP2 over a plain socket. */
public final class RedisSender {
    private final String host, user, password;
    private final int port;

    public RedisSender(String host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    public synchronized void publish(String channel, String message) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            if (!password.isEmpty()) {
                command(out, user.isEmpty() ? new String[]{"AUTH", password} : new String[]{"AUTH", user, password});
                check(in);
            }
            command(out, "PUBLISH", channel, message);
            check(in);
        }
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

    private static void check(InputStream in) throws IOException {
        int first = in.read();
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\r') {
            line.append((char) c);
        }
        in.read();
        if (first == '-') {
            throw new IOException("Redis: " + line);
        }
        if (first == -1) {
            throw new IOException("Redis closed the connection");
        }
    }
}
