package com.mrgregles.bsp_staff_panel.alerts;

import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A small mail sender for the server alerts (SU-167, D832 d): SMTP with TLS from the start on port 465, or STARTTLS on any
 * other port (587 for Gmail), then AUTH PLAIN (or LOGIN), one plain-text UTF-8 message. It never signs in over an
 * unencrypted connection, checks the mail server's certificate and name, and never puts the host, the user or the
 * password in what it says: every failure is put in words that name the variable instead ({@link Failed}).
 */
public final class Smtp {
    /** Why a send failed, in words safe to show and log. */
    public static final class Failed extends IOException {
        public Failed(String message) {
            super(message);
        }
    }

    private final AlertSettings settings;
    private final SocketFactory plain;
    private final SSLSocketFactory tls;
    private final boolean plainForTests;
    private final int timeoutMs;

    public Smtp(AlertSettings settings) {
        this(settings, SocketFactory.getDefault(), (SSLSocketFactory) SSLSocketFactory.getDefault(), false, 20_000);
    }

    /** Tests: a fake mail server without TLS ({@code plainForTests}), which a real panel never allows. */
    Smtp(AlertSettings settings, SocketFactory plain, SSLSocketFactory tls, boolean plainForTests, int timeoutMs) {
        this.settings = settings;
        this.plain = plain;
        this.tls = tls;
        this.plainForTests = plainForTests;
        this.timeoutMs = timeoutMs;
    }

    /** Sends one message to every recipient in the settings. */
    public void send(String subject, String body, ZonedDateTime date) throws Failed {
        if (!settings.ready()) {
            throw new Failed("email is not set up: " + settings.whyNot());
        }
        try {
            talk(subject, body, date);
        } catch (Failed f) {
            throw new Failed(scrub(f.getMessage()));
        } catch (UnknownHostException e) {
            throw new Failed("the panel cannot find the mail server: check SMTP_HOST");
        } catch (ConnectException e) {
            throw new Failed("the mail server did not take the connection: check SMTP_HOST and SMTP_PORT");
        } catch (SocketTimeoutException e) {
            throw new Failed("the mail server did not answer in time");
        } catch (SSLException e) {
            throw new Failed(scrub("the secure connection to the mail server failed (" + e.getMessage() + ")"));
        } catch (IOException | RuntimeException e) {
            throw new Failed(scrub("sending failed (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")"));
        }
    }

    private Socket sock;
    private BufferedReader in;
    private OutputStream out;

    private void talk(String subject, String body, ZonedDateTime date) throws IOException {
        String host = settings.host();
        int port = settings.port();
        Socket raw = plain.createSocket();
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            sock = port == 465 && !plainForTests ? wrap(raw, host, port) : raw;
            streams();
            expect(reply(), 220, "the mail server did not greet the panel");
            List<String> caps = ehlo();
            boolean secure = sock instanceof SSLSocket;
            if (!secure) {
                if (caps.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).startsWith("STARTTLS")) && !plainForTests) {
                    line("STARTTLS");
                    expect(reply(), 220, "the mail server refused STARTTLS");
                    sock = wrap(sock, host, port);
                    streams();
                    caps = ehlo();
                    secure = true;
                } else if (!plainForTests) {
                    throw new Failed("the mail server offers no encryption, so the panel will not send the password: use port 465 or 587 (SMTP_PORT)");
                }
            }
            signIn(caps);
            line("MAIL FROM:<" + clean(settings.from()) + ">");
            expect(reply(), 250, "the mail server refused the sender address (ALERT_EMAIL_FROM or SMTP_USER)");
            for (String to : settings.to()) {
                line("RCPT TO:<" + clean(to) + ">");
                Reply r = reply();
                if (r.code != 250 && r.code != 251) {
                    throw new Failed("the mail server refused a recipient in ALERT_EMAIL_TO (" + r.text + ")");
                }
            }
            line("DATA");
            expect(reply(), 354, "the mail server refused the message");
            out.write(message(subject, body, date).getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(reply(), 250, "the mail server refused the message");
            try {
                line("QUIT");
            } catch (IOException ignored) {
                // sent already
            }
        } finally {
            try {
                (sock != null ? sock : raw).close();
            } catch (IOException ignored) {
                // closing anyway
            }
            sock = null;
        }
    }

    private SSLSocket wrap(Socket s, String host, int port) throws IOException {
        SSLSocket ssl = (SSLSocket) tls.createSocket(s, host, port, true);
        SSLParameters p = ssl.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS"); // the certificate must name the mail server
        ssl.setSSLParameters(p);
        ssl.setSoTimeout(timeoutMs);
        ssl.startHandshake();
        return ssl;
    }

    private void streams() throws IOException {
        in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        out = sock.getOutputStream();
    }

    private List<String> ehlo() throws IOException {
        line("EHLO bsp-staff-panel");
        Reply r = reply();
        expect(r, 250, "the mail server refused EHLO");
        return r.lines;
    }

    private void signIn(List<String> caps) throws IOException {
        String auth = caps.stream().filter(c -> c.toUpperCase(Locale.ROOT).startsWith("AUTH")).findFirst().orElse("").toUpperCase(Locale.ROOT);
        Base64.Encoder b64 = Base64.getEncoder();
        if (auth.contains("PLAIN") || !auth.contains("LOGIN")) {
            byte[] both = ("\0" + settings.user() + "\0" + settings.password()).getBytes(StandardCharsets.UTF_8);
            line("AUTH PLAIN " + b64.encodeToString(both));
            Reply r = reply();
            if (r.code != 235) {
                throw new Failed("the mail server refused the sign-in (" + r.code + " " + r.text + "): check SMTP_USER and SMTP_PASSWORD (Gmail needs an app password)");
            }
            return;
        }
        line("AUTH LOGIN");
        expect(reply(), 334, "the mail server refused the sign-in");
        line(b64.encodeToString(settings.user().getBytes(StandardCharsets.UTF_8)));
        expect(reply(), 334, "the mail server refused the sign-in: check SMTP_USER");
        line(b64.encodeToString(settings.password().getBytes(StandardCharsets.UTF_8)));
        Reply r = reply();
        if (r.code != 235) {
            throw new Failed("the mail server refused the sign-in (" + r.code + " " + r.text + "): check SMTP_USER and SMTP_PASSWORD (Gmail needs an app password)");
        }
    }

    /** The message as it goes after DATA, ending with the lone dot: plain text, base64, so no line can start with a dot. */
    String message(String subject, String body, ZonedDateTime date) {
        StringBuilder m = new StringBuilder();
        m.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(date)).append("\r\n");
        m.append("From: BSP staff panel <").append(clean(settings.from())).append(">\r\n");
        m.append("To: ").append(String.join(", ", settings.to().stream().map(Smtp::clean).toList())).append("\r\n");
        m.append("Subject: ").append(header(subject)).append("\r\n");
        m.append("Message-ID: <").append(UUID.randomUUID()).append("@bsp-staff-panel>\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: text/plain; charset=UTF-8\r\n");
        m.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String text = body.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
        m.append(Base64.getMimeEncoder(76, new byte[]{'\r', '\n'}).encodeToString(text.getBytes(StandardCharsets.UTF_8))).append("\r\n.\r\n");
        return m.toString();
    }

    /** One header line: no line breaks (so nothing can add a header), encoded when not plain ASCII. */
    static String header(String s) {
        String one = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        return one.chars().allMatch(c -> c >= 32 && c < 127) ? one
                : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(one.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String clean(String address) {
        return address.replaceAll("[\\s<>\\r\\n]", "");
    }

    private void line(String s) throws IOException {
        out.write((s + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private record Reply(int code, String text, List<String> lines) {
    }

    private Reply reply() throws IOException {
        List<String> lines = new ArrayList<>();
        int code = -1;
        StringBuilder text = new StringBuilder();
        while (true) {
            String l = in.readLine();
            if (l == null) {
                throw new Failed("the mail server closed the connection");
            }
            if (l.length() < 3) {
                throw new Failed("the mail server's answer could not be read");
            }
            try {
                code = Integer.parseInt(l.substring(0, 3));
            } catch (NumberFormatException e) {
                throw new Failed("the mail server's answer could not be read");
            }
            String rest = l.length() > 4 ? l.substring(4) : "";
            lines.add(rest);
            if (text.length() < 200) {
                text.append(text.length() == 0 ? "" : " ").append(rest);
            }
            if (l.length() == 3 || l.charAt(3) != '-') {
                break;
            }
        }
        String t = text.toString().replaceAll("[\\p{Cntrl}]", " ");
        return new Reply(code, t.length() > 160 ? t.substring(0, 160) + "…" : t, lines);
    }

    private static void expect(Reply r, int code, String words) throws Failed {
        if (r.code != code) {
            throw new Failed(words + " (" + r.code + " " + r.text + ")");
        }
    }

    /** Takes out any setting's value that a message might hold (a certificate error naming the host, a server echoing the user). */
    private String scrub(String m) {
        String out = m == null ? "" : m;
        out = swap(out, settings.password(), "SMTP_PASSWORD");
        out = swap(out, settings.user(), "SMTP_USER");
        out = swap(out, settings.host(), "SMTP_HOST");
        return out;
    }

    private static String swap(String in, String secret, String name) {
        if (secret == null || secret.length() < 3) {
            return in;
        }
        return in.replace(secret, name);
    }
}
