package com.mrgregles.bsp_staff_panel.alerts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.store.PanelStore.AlertRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Watches every game server's snapshots and says when one freezes or crashes (SU-167; the brief of 2026-11-27, D826 to
 * D834). The game servers send a snapshot every 5 seconds from their server tick, so a hung main thread sends nothing
 * while a lagging one keeps sending.
 *
 * <ol>
 * <li>A server silent for {@link #SUSPECT_MS} is a suspect (CHECKING). The panel then checks it {@link #CHECKS} times,
 * {@link #CHECK_EVERY_MS} apart, each a plain TCP connect to its game port ({@link PortKnock}). Any snapshot meanwhile ends
 * it quietly. After the third silent check the last knock decides: open is FROZEN, closed is CRASHED, no answer is
 * NO_ANSWER, no address is SILENT (D826).</li>
 * <li>A snapshot saying {@code stopping} (a planned stop) starts RESTARTING: no alert unless nothing comes within
 * {@link #GRACE_MS}, then NOT_BACK (D827).</li>
 * <li>One email per outage, none per hour (D830); alerts whose silences began within {@link #GROUP_MS} of each other share
 * one email (the panel waits for the others' checks). A back email when the server sends again, only after an alert
 * email went (D828).</li>
 * <li>When no watched server has been heard for {@link #SUSPECT_MS} (two or more watched, none restarting), the panel is
 * the likelier fault: one PANEL email and no server alerts until it hears one again (D832 c).</li>
 * <li>SLOW, only when {@code ALERT_SLOW_MSPT} is set: above it for {@link #SLOW_FOR_MS} running, over once under it for
 * {@link #SLOW_CLEAR_MS} (D831).</li>
 * <li>A server not heard for {@link #WATCH_FOR_MS} is no longer watched (D832 b); history is kept {@link #KEEP_MS} and
 * shown {@link #SHOWN_MS} (D832 e).</li>
 * </ol>
 * Port checks run outside the lock, so a snapshot is never held up by a slow knock.
 */
public final class ServerWatch {
    public static final long SUSPECT_MS = 20_000L;
    public static final long CHECK_EVERY_MS = 20_000L;
    public static final int CHECKS = 3;
    public static final long GRACE_MS = 5 * 60_000L;
    public static final long GROUP_MS = 60_000L;
    public static final long WATCH_FOR_MS = 24 * 3_600_000L;
    public static final long KEEP_MS = 30L * 24 * 3_600_000L;
    public static final long SHOWN_MS = 7L * 24 * 3_600_000L;
    public static final long SLOW_FOR_MS = 5 * 60_000L;
    public static final long SLOW_CLEAR_MS = 2 * 60_000L;

    public enum Port { OPEN, CLOSED, NO_ANSWER, UNKNOWN }

    public enum Kind { FROZEN, CRASHED, NO_ANSWER, SILENT, NOT_BACK, SLOW, PANEL }

    /** Knocks on a game port; a test gives its own. */
    public interface Knocker {
        Port knock(String address);
    }

    /** Sends a letter; {@code done} hears null when it went, else why not (words safe to show). */
    public interface Outbox {
        boolean ready();

        String why();

        void send(Letters.Letter letter, Consumer<String> done);
    }

    /** Keeps an alert row (the panel's database, off the watch's thread). */
    public interface Saver {
        void save(AlertRow row);
    }

    /** One alert, open or ended. */
    public static final class Alert {
        long id;
        String server;
        Kind kind;
        long since, decided, lastHeard;
        Port port = Port.UNKNOWN;
        String addr = "";
        int players;
        double mspt, threshold;
        final List<Long> checks = new ArrayList<>();
        long emailedMs, backMs, backEmailedMs, endedMs, calmSince;
        String emailNote = "", note = "", backServer = "";
        int backPlayers;
        double backMspt;
        boolean mailing, backAfterMail;

        AlertRow row() {
            StringBuilder c = new StringBuilder();
            checks.forEach(t -> c.append(c.length() == 0 ? "" : ",").append(t));
            return new AlertRow(id, server, kind.name(), since, decided, lastHeard, port.name(), addr, players, mspt, c.toString(), emailedMs, emailNote,
                    backMs, backEmailedMs, endedMs, note);
        }

        static Alert of(AlertRow r) {
            Alert a = new Alert();
            a.id = r.id();
            a.server = r.server();
            try {
                a.kind = Kind.valueOf(r.kind());
            } catch (IllegalArgumentException e) {
                a.kind = Kind.SILENT;
            }
            a.since = r.sinceMs();
            a.decided = r.decidedMs();
            a.lastHeard = r.lastHeardMs();
            try {
                a.port = Port.valueOf(r.port());
            } catch (IllegalArgumentException e) {
                a.port = Port.UNKNOWN;
            }
            a.addr = r.addr();
            a.players = r.players();
            a.mspt = r.mspt();
            for (String t : r.checks().split(",")) {
                try {
                    a.checks.add(Long.parseLong(t.trim()));
                } catch (NumberFormatException ignored) {
                    // an empty list
                }
            }
            a.emailedMs = r.emailedMs();
            a.emailNote = r.emailNote();
            a.backMs = r.backMs();
            a.backEmailedMs = r.backEmailedMs();
            a.endedMs = r.endedMs();
            a.note = r.note();
            return a;
        }

        public boolean open() {
            return endedMs == 0;
        }

        public String server() {
            return server;
        }

        public Kind kind() {
            return kind;
        }

        /** This alert as the panel shows it (an Owner's view). */
        public JsonObject json() {
            return ServerWatch.row(this, true);
        }
    }

    private static final class Watched {
        final String server;
        long heardAt;
        long stoppingAt;
        int players;
        double mspt;
        String addr = "";
        boolean checking;
        long nextCheckAt;
        final List<Long> checkTimes = new ArrayList<>();
        Port lastPort = Port.UNKNOWN;
        Alert alert;
        long slowSince, calmSince;

        Watched(String server) {
            this.server = server;
        }
    }

    private record Knock(String server, long heardAt, boolean notBack, String address) {
    }

    private final Map<String, Watched> servers = new LinkedHashMap<>();
    private final List<Alert> history = new ArrayList<>();
    private final List<Alert> pending = new ArrayList<>();
    private final List<Alert> pendingBack = new ArrayList<>();
    private final LongSupplier clock;
    private final Knocker knocker;
    private final Outbox outbox;
    private final Saver saver;
    private final Letters letters;
    private final AlertSettings settings;
    private final long startedAt;
    private long lastAnyHeard;
    private Alert deaf;
    private long seq;

    public ServerWatch(LongSupplier clock, AlertSettings settings, Knocker knocker, Outbox outbox, Saver saver, Letters letters) {
        this.clock = clock;
        this.settings = settings;
        this.knocker = knocker;
        this.outbox = outbox;
        this.saver = saver;
        this.letters = letters;
        this.startedAt = clock.getAsLong();
        this.lastAnyHeard = startedAt;
    }

    // ------------------------------------------------------------------ what the panel hears

    /** A snapshot accepted by the feed (from Redis or the servers' table), heard at {@code heardAt}. */
    public synchronized void heard(String server, long heardAt, JsonObject snapshot) {
        Watched w = servers.computeIfAbsent(key(server), k -> new Watched(server));
        if (heardAt < w.heardAt) {
            return; // older than what it said last (the same moment counts: a stop right after a snapshot)
        }
        lastAnyHeard = Math.max(lastAnyHeard, heardAt);
        if (deaf != null) {
            Alert d = deaf;
            deaf = null;
            d.backServer = server;
            end(d, heardAt, true);
            flush(clock.getAsLong());
        }
        boolean stopping = bool(snapshot, "stopping");
        w.heardAt = heardAt;
        w.checking = false;
        w.checkTimes.clear();
        String addr = text(snapshot, "addr");
        if (!addr.isEmpty()) {
            w.addr = addr;
        }
        if (stopping) {
            if (w.stoppingAt == 0) {
                w.stoppingAt = heardAt; // the planned stop's grace begins (D827); players and mspt stay as they were before
            }
            return;
        }
        w.stoppingAt = 0;
        w.players = snapshot.has("players") && snapshot.get("players").isJsonArray() ? snapshot.getAsJsonArray("players").size() : 0;
        w.mspt = number(snapshot, "mspt");
        if (w.alert != null && w.alert.kind != Kind.SLOW) {
            Alert a = w.alert;
            w.alert = null;
            a.backPlayers = w.players;
            a.backMspt = w.mspt;
            end(a, heardAt, true);
        }
        slow(w, heardAt);
        flush(clock.getAsLong()); // a back email (or a SLOW alert) goes now, not at the next tick
    }

    /** On the panel's start: a server's last row in the servers' table, watched when it is younger than a day (D832 b). */
    public synchronized void seed(String server, long atMs, JsonObject snapshot) {
        long now = clock.getAsLong();
        if (now - atMs > WATCH_FOR_MS || servers.containsKey(key(server))) {
            return;
        }
        Watched w = new Watched(server);
        w.heardAt = atMs;
        w.addr = text(snapshot, "addr");
        if (bool(snapshot, "stopping")) {
            w.stoppingAt = atMs;
        } else {
            w.players = snapshot.has("players") && snapshot.get("players").isJsonArray() ? snapshot.getAsJsonArray("players").size() : 0;
            w.mspt = number(snapshot, "mspt");
        }
        servers.put(key(server), w);
    }

    /**
     * On the panel's start, after {@link #seed}: the alerts kept in its database. An open one goes back to its server, so
     * its back email still goes and no second alert is raised; one for a server no longer watched is ended.
     */
    public synchronized void restore(List<AlertRow> rows) {
        long now = clock.getAsLong();
        for (AlertRow r : rows) {
            if (r.sinceMs() < now - KEEP_MS || history.stream().anyMatch(h -> h.id == r.id())) {
                continue;
            }
            Alert a = Alert.of(r);
            history.add(a);
            if (!a.open()) {
                continue;
            }
            Watched w = a.kind == Kind.PANEL ? null : servers.get(key(a.server));
            if (w != null && w.alert == null) {
                w.alert = a;
                if (a.kind == Kind.SLOW) {
                    w.slowSince = a.since;
                }
            } else {
                a.endedMs = now;
                a.note = a.kind == Kind.PANEL ? "the panel restarted" : "no longer watched (not heard for a day)";
                saver.save(a.row());
            }
        }
        history.sort(Comparator.comparingLong(x -> x.since));
    }

    // ------------------------------------------------------------------ the clock

    /** Every couple of seconds: moves each server on, knocks where a check is due, sends what is ready. */
    public void tick() {
        List<Knock> due = plan();
        Map<Knock, Port> got = new HashMap<>();
        for (Knock k : due) {
            got.put(k, k.address == null ? Port.UNKNOWN : knocker.knock(k.address));
        }
        apply(got);
    }

    private synchronized List<Knock> plan() {
        long now = clock.getAsLong();
        List<Knock> due = new ArrayList<>();
        servers.values().removeIf(w -> {
            if (now - w.heardAt < WATCH_FOR_MS) {
                return false;
            }
            if (w.alert != null) {
                w.alert.note = "no longer watched (not heard for a day)";
                end(w.alert, now, false);
            }
            return true;
        });
        // the servers that should be sending now: not in a planned stop, not already in an alert of their own
        List<Watched> counted = servers.values().stream().filter(w -> w.stoppingAt == 0 && (w.alert == null || w.alert.kind == Kind.SLOW)).toList();
        if (deaf == null && counted.size() >= 2 && now - lastAnyHeard >= SUSPECT_MS && now - startedAt >= SUSPECT_MS) {
            Alert d = newAlert("(panel)", Kind.PANEL, lastAnyHeard, now);
            d.lastHeard = lastAnyHeard;
            d.players = counted.size();
            deaf = d;
            pending.removeIf(p -> { // nothing decided in the same moment goes out on its own
                p.emailNote = "not emailed: the panel could not hear any server";
                saver.save(p.row());
                return true;
            });
            pending.add(d);
            saver.save(d.row());
        }
        if (deaf != null) {
            servers.values().forEach(w -> {
                w.checking = false;
                w.checkTimes.clear();
            });
            flush(now);
            return due;
        }
        for (Watched w : servers.values()) {
            if (w.stoppingAt > 0) {
                if (w.alert == null && now - w.stoppingAt >= GRACE_MS) {
                    due.add(new Knock(w.server, w.heardAt, true, address(w)));
                }
                continue;
            }
            if (w.alert != null && w.alert.kind != Kind.SLOW) {
                continue;
            }
            if (now - w.heardAt < SUSPECT_MS) {
                continue;
            }
            if (!w.checking) {
                w.checking = true;
                w.checkTimes.clear();
                w.nextCheckAt = now + CHECK_EVERY_MS;
            } else if (now >= w.nextCheckAt) {
                due.add(new Knock(w.server, w.heardAt, false, address(w)));
            }
        }
        flush(now);
        return due;
    }

    private synchronized void apply(Map<Knock, Port> got) {
        long now = clock.getAsLong();
        for (Map.Entry<Knock, Port> e : got.entrySet()) {
            Knock k = e.getKey();
            Watched w = servers.get(key(k.server));
            if (w == null || w.heardAt != k.heardAt || deaf != null) {
                continue; // it spoke meanwhile, or the panel went deaf: the knock says nothing now
            }
            if (k.notBack) {
                if (w.stoppingAt == 0 || w.alert != null) {
                    continue;
                }
                Alert a = newAlert(w.server, Kind.NOT_BACK, w.stoppingAt, now);
                fill(a, w, e.getValue(), k.address);
                a.checks.add(now);
                w.alert = a;
                pending.add(a);
                saver.save(a.row());
                continue;
            }
            if (!w.checking) {
                continue;
            }
            w.checkTimes.add(now);
            w.lastPort = e.getValue();
            w.nextCheckAt = now + CHECK_EVERY_MS;
            if (w.checkTimes.size() < CHECKS) {
                continue;
            }
            w.checking = false;
            Kind kind = switch (e.getValue()) {
                case OPEN -> Kind.FROZEN;
                case CLOSED -> Kind.CRASHED;
                case NO_ANSWER -> Kind.NO_ANSWER;
                case UNKNOWN -> Kind.SILENT;
            };
            if (w.alert != null) { // it was slow, then went silent: that one ends here and this one takes over
                w.alert.note = "then it went silent";
                end(w.alert, now, false);
                w.alert = null;
            }
            Alert a = newAlert(w.server, kind, w.heardAt, now);
            fill(a, w, e.getValue(), k.address);
            a.checks.addAll(w.checkTimes);
            w.checkTimes.clear();
            w.alert = a;
            pending.add(a);
            saver.save(a.row());
        }
        flush(now);
    }

    private void fill(Alert a, Watched w, Port port, String address) {
        a.lastHeard = w.heardAt;
        a.port = port;
        a.addr = address == null ? "" : address;
        a.players = w.players;
        a.mspt = w.mspt;
    }

    private Alert newAlert(String server, Kind kind, long since, long now) {
        Alert a = new Alert();
        a.id = now * 1000 + (seq++ % 1000);
        a.server = server;
        a.kind = kind;
        a.since = since;
        a.decided = now;
        history.add(a);
        return a;
    }

    /** Where a server's port is checked: the panel's ALERT_SERVERS first, then what its snapshot says, else null. */
    private String address(Watched w) {
        String set = settings.addressOf(w.server);
        if (PortKnock.usable(set)) {
            return set.trim();
        }
        return PortKnock.usable(w.addr) ? w.addr.trim() : null;
    }

    private void slow(Watched w, long heardAt) {
        double limit = settings.slowMspt();
        if (limit <= 0) {
            w.slowSince = 0;
            return;
        }
        if (w.mspt > limit) {
            w.calmSince = 0;
            if (w.slowSince == 0) {
                w.slowSince = heardAt;
            }
            if (w.alert == null && heardAt - w.slowSince >= SLOW_FOR_MS) {
                Alert a = newAlert(w.server, Kind.SLOW, w.slowSince, heardAt);
                a.lastHeard = heardAt;
                a.players = w.players;
                a.mspt = w.mspt;
                a.threshold = limit;
                w.alert = a;
                pending.add(a);
                saver.save(a.row());
            }
            return;
        }
        w.slowSince = 0;
        if (w.alert != null && w.alert.kind == Kind.SLOW) {
            if (w.calmSince == 0) {
                w.calmSince = heardAt;
            }
            if (heardAt - w.calmSince >= SLOW_CLEAR_MS) {
                Alert a = w.alert;
                w.alert = null;
                a.threshold = limit;
                a.calmSince = w.calmSince;
                a.backPlayers = w.players;
                a.backMspt = w.mspt;
                w.calmSince = 0;
                end(a, heardAt, true);
            }
        }
    }

    /** Ends an alert; {@code back} when the server (or, for PANEL, any server) is heard again. */
    private void end(Alert a, long at, boolean back) {
        a.endedMs = at;
        if (back) {
            a.backMs = at;
        }
        if (pending.remove(a)) {
            a.emailNote = back ? "not emailed: back before the email went" : "not emailed";
        } else if (back && a.emailedMs > 0) {
            pendingBack.add(a);
        } else if (back && a.mailing) {
            a.backAfterMail = true;
        }
        saver.save(a.row());
    }

    /** Sends what is ready: alerts once no server still being checked began its silence within a minute of theirs. */
    private void flush(long now) {
        if (!pending.isEmpty()) {
            boolean wait = deaf == null && pending.stream().anyMatch(p -> servers.values().stream()
                    .anyMatch(w -> w.checking && w.alert == null && Math.abs(w.heardAt - p.lastHeard) <= GROUP_MS));
            if (!wait) {
                List<Alert> batch = new ArrayList<>(pending);
                pending.clear();
                batch.sort(Comparator.comparingLong(x -> x.lastHeard));
                mail(batch, letters.alert(batch, now), false);
            }
        }
        if (!pendingBack.isEmpty()) {
            List<Alert> batch = new ArrayList<>(pendingBack);
            pendingBack.clear();
            mail(batch, letters.back(batch), true);
        }
    }

    private void mail(List<Alert> batch, Letters.Letter letter, boolean back) {
        if (!outbox.ready()) {
            if (!back) {
                batch.forEach(a -> {
                    a.emailNote = "not emailed: email is not set up";
                    saver.save(a.row());
                });
            }
            return;
        }
        if (!back) {
            batch.forEach(a -> a.mailing = true);
        }
        outbox.send(letter, err -> sent(batch, back, err));
    }

    private synchronized void sent(List<Alert> batch, boolean back, String err) {
        long now = clock.getAsLong();
        for (Alert a : batch) {
            if (back) {
                if (err == null) {
                    a.backEmailedMs = now;
                } else {
                    a.note = "back email failed: " + err;
                }
            } else {
                a.mailing = false;
                if (err == null) {
                    a.emailedMs = now;
                    a.emailNote = "";
                    if (a.backAfterMail) {
                        a.backAfterMail = false;
                        pendingBack.add(a);
                    }
                } else {
                    a.emailNote = "email failed: " + err;
                    a.backAfterMail = false;
                }
            }
            saver.save(a.row());
        }
        if (!pendingBack.isEmpty()) {
            flush(now);
        }
    }

    // ------------------------------------------------------------------ what the panel shows (R4, R5)

    /**
     * For the WORLDS tab and the banner: each server that is not plainly fine ({@code state}: CHECKING with its checks,
     * RESTARTING with when its grace ends, or the open alert's kind), the open alerts, the last 7 days, and (for Owners)
     * whether email is set up and why not.
     */
    public synchronized JsonObject json(boolean owner) {
        long now = clock.getAsLong();
        JsonObject o = new JsonObject();
        o.addProperty("at", now);
        JsonObject states = new JsonObject();
        for (Watched w : servers.values()) {
            JsonObject s = new JsonObject();
            if (w.alert != null) {
                s.addProperty("state", w.alert.kind.name());
                s.addProperty("since", w.alert.since);
                s.addProperty("port", w.alert.port.name());
            } else if (w.stoppingAt > 0) {
                s.addProperty("state", "RESTARTING");
                s.addProperty("since", w.stoppingAt);
                s.addProperty("until", w.stoppingAt + GRACE_MS);
            } else if (w.checking) {
                s.addProperty("state", "CHECKING");
                s.addProperty("since", w.heardAt);
                s.addProperty("checks", w.checkTimes.size());
                s.addProperty("of", CHECKS);
            } else {
                continue;
            }
            s.addProperty("heardAt", w.heardAt);
            s.addProperty("players", w.players);
            s.addProperty("mspt", w.mspt);
            states.add(w.server, s);
        }
        o.add("servers", states);
        JsonArray open = new JsonArray(), recent = new JsonArray();
        history.removeIf(a -> !a.open() && a.since < now - KEEP_MS);
        history.stream().sorted(Comparator.comparingLong((Alert a) -> a.since).reversed()).forEach(a -> {
            if (a.open()) {
                open.add(row(a, owner));
            }
            if (a.since >= now - SHOWN_MS && recent.size() < 50) {
                recent.add(row(a, owner));
            }
        });
        o.add("open", open);
        o.add("recent", recent);
        o.addProperty("watched", servers.size());
        if (owner) {
            JsonObject e = new JsonObject();
            e.addProperty("on", outbox.ready());
            if (!outbox.ready()) {
                e.addProperty("why", outbox.why());
            } else {
                e.addProperty("to", settings.to().size());
            }
            o.add("email", e);
        }
        if (settings.slowMspt() > 0) {
            o.addProperty("slowMspt", settings.slowMspt());
        }
        return o;
    }

    private static JsonObject row(Alert a, boolean owner) {
        JsonObject r = new JsonObject();
        r.addProperty("id", String.valueOf(a.id));
        r.addProperty("server", a.server);
        r.addProperty("kind", a.kind.name());
        r.addProperty("since", a.since);
        r.addProperty("decided", a.decided);
        r.addProperty("port", a.port.name());
        if (owner && !a.addr.isEmpty()) {
            r.addProperty("addr", a.addr);
        }
        r.addProperty("players", a.players);
        r.addProperty("mspt", a.mspt);
        r.addProperty("emailed", a.emailedMs);
        r.addProperty("mailing", a.mailing);
        String note = a.emailNote;
        if (!owner && note.startsWith("email failed")) {
            note = "email failed"; // the mail server's words are for Owners (R4)
        }
        r.addProperty("emailNote", note);
        r.addProperty("back", a.backMs);
        r.addProperty("ended", a.endedMs);
        r.addProperty("note", a.note);
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static String key(String server) {
        return server.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String text(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static double number(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }

    /** What the pages show, in short: when it changes, every open page reads {@code /api/alerts} again (SU-168). */
    public synchronized String signature() {
        StringBuilder b = new StringBuilder();
        for (Watched w : servers.values()) {
            b.append(w.server).append(':').append(w.alert == null ? "" : w.alert.id).append(':').append(w.stoppingAt > 0).append(':')
                    .append(w.checking ? w.checkTimes.size() : -1).append(';');
        }
        for (Alert a : history) {
            b.append(a.id).append('/').append(a.emailedMs).append('/').append(a.endedMs).append('/').append(a.mailing).append('/').append(a.emailNote.length()).append(',');
        }
        return b.append(deaf == null ? "" : "deaf").toString();
    }

    /** Tests and the console: the alerts as the watch holds them, oldest first. */
    public synchronized List<Alert> alerts() {
        return List.copyOf(history);
    }
}
