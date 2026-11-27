package com.mrgregles.bsp_staff_panel.alerts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The server alerts' settings (SU-167, D829, D831, D832): read only from the environment, which Pterodactyl fills from the
 * panel server's variables, never from a file. Nothing here is ever printed: {@link #toString()} and {@link #whyNot()} name
 * a variable, never its value.
 *
 * <ul>
 * <li>{@code SMTP_HOST}, {@code SMTP_PORT} (587 by default: STARTTLS; 465: TLS from the start), {@code SMTP_USER},
 * {@code SMTP_PASSWORD}: the account the emails are sent from.</li>
 * <li>{@code ALERT_EMAIL_TO}: who gets them, addresses separated by commas. {@code ALERT_EMAIL_FROM}: the sender address,
 * {@code SMTP_USER} when empty (Gmail sends only as the signed-in account).</li>
 * <li>{@code ALERT_SERVERS}: game servers' addresses for the port check, {@code Name=host:port} separated by commas; these
 * win over the address a server's snapshot gives.</li>
 * <li>{@code ALERT_SLOW_MSPT}: empty (the default) turns SLOW alerts off; a number turns them on at that many ms a tick.</li>
 * </ul>
 */
public final class AlertSettings {
    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final List<String> to;
    private final String from;
    private final Map<String, String> servers;
    private final double slowMspt;
    private final String why;

    private AlertSettings(String host, int port, String user, String password, List<String> to, String from, Map<String, String> servers, double slowMspt, String why) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.to = to;
        this.from = from;
        this.servers = servers;
        this.slowMspt = slowMspt;
        this.why = why;
    }

    public static AlertSettings fromEnv() {
        return from(System.getenv());
    }

    /** From these variables (the environment, or a test's map). */
    public static AlertSettings from(Map<String, String> env) {
        String host = val(env, "SMTP_HOST"), user = val(env, "SMTP_USER"), password = env.getOrDefault("SMTP_PASSWORD", "");
        String portText = val(env, "SMTP_PORT");
        List<String> problems = new ArrayList<>();
        int port = 587;
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
                if (port < 1 || port > 65535) {
                    problems.add("SMTP_PORT is not a port number");
                }
            } catch (NumberFormatException e) {
                problems.add("SMTP_PORT is not a number");
            }
        }
        List<String> to = new ArrayList<>();
        for (String a : val(env, "ALERT_EMAIL_TO").split("[,;\\s]+")) {
            if (a.isEmpty()) {
                continue;
            }
            if (!looksLikeAddress(a)) {
                problems.add("ALERT_EMAIL_TO holds something that is not an email address");
            } else if (!to.contains(a)) {
                to.add(a);
            }
        }
        String from = val(env, "ALERT_EMAIL_FROM");
        if (from.isEmpty()) {
            from = user;
        }
        if (host.isEmpty()) {
            problems.add(0, "SMTP_HOST is empty");
        }
        if (user.isEmpty()) {
            problems.add("SMTP_USER is empty");
        }
        if (password.isEmpty()) {
            problems.add("SMTP_PASSWORD is empty");
        }
        if (to.isEmpty() && val(env, "ALERT_EMAIL_TO").isEmpty()) {
            problems.add("ALERT_EMAIL_TO is empty");
        }
        if (!from.isEmpty() && !looksLikeAddress(from)) {
            problems.add(val(env, "ALERT_EMAIL_FROM").isEmpty() ? "SMTP_USER is not an email address (set ALERT_EMAIL_FROM)" : "ALERT_EMAIL_FROM is not an email address");
        }
        Map<String, String> servers = new LinkedHashMap<>();
        for (String pair : val(env, "ALERT_SERVERS").split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && !pair.substring(eq + 1).isBlank()) {
                servers.put(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT), pair.substring(eq + 1).trim());
            }
        }
        double slow = 0;
        String slowText = val(env, "ALERT_SLOW_MSPT");
        if (!slowText.isEmpty()) {
            try {
                slow = Math.max(0, Double.parseDouble(slowText));
            } catch (NumberFormatException e) {
                slow = 0; // not a number: off, as when empty
            }
        }
        return new AlertSettings(host, port, user, password, List.copyOf(to), from, Collections.unmodifiableMap(servers), slow,
                problems.isEmpty() ? null : String.join("; ", problems));
    }

    private static String val(Map<String, String> env, String k) {
        String v = env.get(k);
        return v == null ? "" : v.trim();
    }

    static boolean looksLikeAddress(String a) {
        return a.length() <= 254 && a.matches("[^\\s@<>,;\"\\\\]+@[^\\s@<>,;\"\\\\]+\\.[^\\s@<>,;\"\\\\]+");
    }

    /** Null when email can be sent; else what is missing, naming variables only. */
    public String whyNot() {
        return why;
    }

    public boolean ready() {
        return why == null;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String user() {
        return user;
    }

    /** Only for the sign-in to the mail server. */
    String password() {
        return password;
    }

    public List<String> to() {
        return to;
    }

    public String from() {
        return from;
    }

    /** The address a server's port is checked at by the panel's {@code ALERT_SERVERS}, or null. */
    public String addressOf(String server) {
        return servers.get(server.toLowerCase(Locale.ROOT));
    }

    /** SLOW alerts above this many ms a tick, 0 when off (D831). */
    public double slowMspt() {
        return slowMspt;
    }

    /** Words for the console: on or off and why, never a value. */
    @Override
    public String toString() {
        return (ready() ? "email alerts on, to " + to.size() + (to.size() == 1 ? " address" : " addresses") : "email alerts off (" + why + ")")
                + (servers.isEmpty() ? "" : ", " + servers.size() + " server addresses from ALERT_SERVERS")
                + (slowMspt > 0 ? ", SLOW alerts above " + slowMspt + " ms a tick" : "");
    }
}
