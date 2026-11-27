package com.mrgregles.bsp_staff_panel.alerts;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The server alerts' emails (SU-167, look BK A confirmed in D834, R1 to R3 of {@code tools/preview/alerts_refined.html}):
 * plain text, times in UK time (D832 g), one email per outage and one when it is back (D828, D830).
 */
public final class Letters {
    public record Letter(String subject, String body) {
    }

    public static final ZoneId UK = ZoneId.of("Europe/London");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK);

    private final String publicUrl;

    public Letters(String publicUrl) {
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    static String time(long ms) {
        return TIME.format(Instant.ofEpochMilli(ms).atZone(UK));
    }

    static String day(long ms) {
        return DAY.format(Instant.ofEpochMilli(ms).atZone(UK));
    }

    public static ZonedDateTime date(long ms) {
        return Instant.ofEpochMilli(ms).atZone(UK);
    }

    /** "7 minutes 25 seconds", "45 seconds", "1 hour 3 minutes". */
    static String length(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return plural(s, "second");
        }
        if (s < 3600) {
            return plural(s / 60, "minute") + (s % 60 == 0 ? "" : " " + plural(s % 60, "second"));
        }
        return plural(s / 3600, "hour") + (s % 3600 / 60 == 0 ? "" : " " + plural(s % 3600 / 60, "minute"));
    }

    /** For a subject: "7 minutes", "45 seconds", "1 hour 3 minutes" (no seconds once it is a minute or more). */
    static String shortLength(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return plural(s, "second");
        }
        if (s < 3600) {
            return plural(s / 60, "minute");
        }
        return plural(s / 3600, "hour") + (s % 3600 / 60 == 0 ? "" : " " + plural(s % 3600 / 60, "minute"));
    }

    private static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static String ms(double mspt) {
        return String.format(Locale.UK, "%.1f ms a tick", mspt);
    }

    private static String players(int n) {
        return n + (n == 1 ? " player" : " players") + " online";
    }

    private String worlds() {
        return "WORLDS on the panel:\n" + (publicUrl.isEmpty() ? "(the panel's publicUrl)" : publicUrl) + "/#worlds";
    }

    /** What happened, as a subject's verb: "is frozen", "has crashed". */
    static String headline(ServerWatch.Alert a) {
        return switch (a.kind) {
            case FROZEN -> "is frozen";
            case CRASHED -> "has crashed";
            case NO_ANSWER -> "is not answering";
            case SILENT -> "is not sending";
            case NOT_BACK -> "is not back after its restart";
            case SLOW -> "is very slow";
            case PANEL -> "cannot hear the game servers";
        };
    }

    private static String explain(ServerWatch.Alert a) {
        return switch (a.kind) {
            case FROZEN -> "Its port still answers, so the game is stuck but the server process is still running.";
            case CRASHED -> "Its port is closed: the server crashed, or was stopped without saying so (a Kill in Pterodactyl).";
            case NO_ANSWER -> "Its address did not answer at all within 3 seconds: the server or the machine it runs on may be down, or the network between them.";
            case SILENT -> "The panel has no address for it, so it cannot tell frozen from crashed.";
            case NOT_BACK -> "It said it was stopping at " + time(a.since) + " and has sent nothing in the " + length(ServerWatch.GRACE_MS) + " since. " + switch (a.port) {
                case OPEN -> "Its port still answers: it is running but stuck starting or stopping.";
                case CLOSED -> "Its port is closed: it is not running.";
                case NO_ANSWER -> "Its address did not answer at all.";
                case UNKNOWN -> "The panel has no address to check whether it is running.";
            };
            case SLOW -> "It has taken more than " + ms(a.threshold) + " (under " + Math.round(1000 / Math.max(50, a.threshold)) + " TPS) since " + time(a.since) + ".";
            case PANEL -> "";
        };
    }

    private static String todo(ServerWatch.Alert a) {
        return switch (a.kind) {
            case FROZEN -> "A frozen server does not stop on its own: restart it in Pterodactyl (Console, Restart; Kill if it will not stop).";
            case CRASHED -> "Look at its console in Pterodactyl. If Pterodactyl starts it again, you get the back email.";
            case NO_ANSWER -> "Check the machine and the server in Pterodactyl.";
            case SILENT -> "Look at it in Pterodactyl. To let the panel tell frozen from crashed, put its address in the panel's ALERT_SERVERS variable.";
            case NOT_BACK -> "Look at its console in Pterodactyl, and start it if it is stopped.";
            case SLOW -> "Look at it in game or in Pterodactyl's console.";
            case PANEL -> "Look at the BSP-Staff-Panel console in Pterodactyl.";
        };
    }

    private static String portLine(ServerWatch.Alert a) {
        String at = a.checks.isEmpty() ? time(a.decided) : time(a.checks.get(a.checks.size() - 1));
        return switch (a.port) {
            case OPEN -> a.addr + " answered at " + at;
            case CLOSED -> a.addr + " is closed (checked " + at + ")";
            case NO_ANSWER -> a.addr + " did not answer (checked " + at + ")";
            case UNKNOWN -> "no address to check";
        };
    }

    private static String lastHeard(ServerWatch.Alert a) {
        return time(a.lastHeard) + ", " + players(a.players) + ", " + ms(a.mspt);
    }

    /** One email for the alerts that went out together (servers silent within a minute of each other share one; D830). */
    public Letter alert(List<ServerWatch.Alert> alerts, long now) {
        if (alerts.size() == 1) {
            return single(alerts.get(0), now);
        }
        String names = alerts.stream().map(a -> a.server).collect(Collectors.joining(", "));
        StringBuilder b = new StringBuilder();
        b.append(alerts.size()).append(" game servers stopped sending within a minute of each other (").append(day(alerts.get(0).lastHeard)).append(", UK time).");
        String host = sameHost(alerts);
        if (host != null) {
            b.append(alerts.size() == 2 ? " Both" : " All").append(" are at ").append(host).append(": the machine itself may be down.");
        }
        b.append("\n");
        for (ServerWatch.Alert a : alerts) {
            b.append("\n").append(a.server).append(" ").append(headline(a)).append("\n");
            b.append("  Last heard   ").append(lastHeard(a)).append("\n");
            b.append("  Its port     ").append(portLine(a)).append("\n");
        }
        b.append("\n").append(worlds()).append("\n\nThis is the only email for this outage. You get one more when each is back.\n");
        return new Letter(alerts.size() + " game servers are out: " + names, b.toString());
    }

    private static String sameHost(List<ServerWatch.Alert> alerts) {
        String host = null;
        for (ServerWatch.Alert a : alerts) {
            if (a.port == ServerWatch.Port.UNKNOWN || a.addr.isEmpty() || a.addr.lastIndexOf(':') <= 0) {
                return null;
            }
            String h = a.addr.substring(0, a.addr.lastIndexOf(':'));
            if (host != null && !host.equals(h)) {
                return null;
            }
            host = h;
        }
        return host;
    }

    private Letter single(ServerWatch.Alert a, long now) {
        StringBuilder b = new StringBuilder();
        if (a.kind == ServerWatch.Kind.PANEL) {
            b.append("The staff panel has heard no game server since ").append(time(a.since)).append(" (").append(day(a.since)).append(", UK time), ")
                    .append(length(now - a.since)).append(" before this email, while it watches ").append(a.players).append(" servers. ")
                    .append("The fault is most likely the panel's own link to Redis or the database, not every server at once. Server alerts wait until the panel hears a server again.\n\n")
                    .append(todo(a)).append("\n\nThis is the only email for this. You get one more when the panel hears the servers again.\n");
            return new Letter("The staff panel cannot hear the game servers", b.toString());
        }
        if (a.kind == ServerWatch.Kind.SLOW) {
            b.append(a.server).append(" is very slow. ").append(explain(a)).append("\n\n");
            b.append("Last heard     ").append(lastHeard(a)).append("\n\n");
        } else if (a.kind == ServerWatch.Kind.NOT_BACK) {
            b.append(a.server).append(" is not back after its restart (").append(day(a.since)).append(", UK time). ").append(explain(a)).append("\n\n");
            b.append("Before it stopped  ").append(players(a.players)).append(", ").append(ms(a.mspt)).append("\n");
            b.append("Its port           ").append(portLine(a)).append("\n\n");
        } else {
            b.append(a.server).append(" ").append(headline(a)).append(": it stopped sending at ").append(time(a.lastHeard)).append(" (").append(day(a.lastHeard))
                    .append(", UK time), ").append(length(now - a.lastHeard)).append(" before this email. ").append(explain(a)).append("\n\n");
            b.append("Last heard     ").append(lastHeard(a)).append("\n");
            b.append("Checked        ").append(a.checks.stream().map(Letters::time).collect(Collectors.joining(", "))).append(": silent each time\n");
            b.append("Its port       ").append(portLine(a)).append("\n\n");
        }
        b.append(todo(a)).append("\n\n").append(worlds()).append("\n\n");
        b.append("This is the only email for this outage. You get one more when ").append(a.server).append(a.kind == ServerWatch.Kind.SLOW ? " is no longer slow.\n" : " is back.\n");
        return new Letter(a.server + " " + headline(a), b.toString());
    }

    /** One email for the servers back at once. */
    public Letter back(List<ServerWatch.Alert> alerts) {
        if (alerts.size() == 1) {
            ServerWatch.Alert a = alerts.get(0);
            if (a.kind == ServerWatch.Kind.PANEL) {
                return new Letter("The staff panel hears the game servers again", "The staff panel heard " + a.backServer + " at " + time(a.backMs) + " (" + day(a.backMs)
                        + ", UK time), after " + length(a.backMs - a.since) + " without hearing any game server. Server alerts are on again.\n");
            }
            if (a.kind == ServerWatch.Kind.SLOW) {
                return new Letter(a.server + " is no longer slow", a.server + " has been under " + ms(a.threshold) + " since " + time(a.calmSince) + " (" + day(a.backMs)
                        + ", UK time), after " + length(a.calmSince - a.since) + " of being slow.\n\nNow           " + players(a.backPlayers) + ", " + ms(a.backMspt) + "\n");
            }
            return new Letter(a.server + " is back after " + shortLength(a.backMs - a.since), a.server + " is back: sending again since " + time(a.backMs) + " (" + day(a.backMs) + ", UK time).\n\n"
                    + backLines(a, ""));
        }
        StringBuilder b = new StringBuilder();
        b.append(alerts.size()).append(" game servers are back (").append(day(alerts.get(0).backMs)).append(", UK time).\n");
        for (ServerWatch.Alert a : alerts) {
            b.append("\n").append(a.server).append(" is back since ").append(time(a.backMs)).append("\n").append(backLines(a, "  "));
        }
        return new Letter(alerts.size() + " game servers are back: " + alerts.stream().map(x -> x.server).collect(Collectors.joining(", ")), b.toString());
    }

    private static String backLines(ServerWatch.Alert a, String indent) {
        String what = switch (a.kind) {
            case FROZEN -> "frozen (its port answered)";
            case CRASHED -> "crashed (its port was closed)";
            case NO_ANSWER -> "not answering (its address did not answer)";
            case SILENT -> "not sending (no address to check)";
            case NOT_BACK -> "not back after a planned restart";
            case SLOW -> "very slow";
            case PANEL -> "";
        };
        return indent + "Out for        " + length(a.backMs - a.since) + " (" + time(a.since) + " to " + time(a.backMs) + ")\n"
                + indent + "What it was    " + what + "\n"
                + indent + "Now            " + players(a.backPlayers) + ", " + ms(a.backMspt) + "\n";
    }

    /** The test email (D829): from the console's {@code test-email} or an Owner's SEND TEST EMAIL. */
    public Letter test(long now, String by) {
        return new Letter("Test email from the BSP staff panel", "This is a test email from the BSP staff panel, sent at " + time(now) + " (" + day(now) + ", UK time)"
                + (by == null || by.isBlank() ? "" : " by " + by) + ".\n\nServer alerts come to this address the same way. Nothing needs doing.\n");
    }
}
