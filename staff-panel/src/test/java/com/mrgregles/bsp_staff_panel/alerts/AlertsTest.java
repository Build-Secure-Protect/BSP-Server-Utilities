package com.mrgregles.bsp_staff_panel.alerts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore.AlertRow;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server alerts (SU-167, D826 to D834): the triple check, frozen and crashed, the planned restart's grace, one email
 * per outage and the back email, shared emails, the panel hearing nothing, the start, the slow servers, addresses, the
 * settings that are never printed, the mail sender against a stand-in mail server, and the port check.
 */
class AlertsTest {
    static final long T0 = 1_795_700_000_000L; // Thursday 26 November 2026, 13:33:20 UK time
    static final String ADDR = "192.168.1.27:25565";

    /** A watch on a hand-turned clock, with made-up ports and an outbox that keeps its letters. */
    static final class Rig {
        final AtomicLong clock = new AtomicLong(T0);
        final Map<String, ServerWatch.Port> ports = new HashMap<>();
        final List<String> knocked = new ArrayList<>();
        final List<Letters.Letter> letters = new ArrayList<>();
        final MemoryStore store = new MemoryStore();
        boolean ready = true;
        String failWith;
        final AlertSettings settings;
        final ServerWatch watch;

        Rig() {
            this(Map.of());
        }

        Rig(Map<String, String> env) {
            settings = AlertSettings.from(env);
            ServerWatch.Outbox out = new ServerWatch.Outbox() {
                @Override
                public boolean ready() {
                    return ready;
                }

                @Override
                public String why() {
                    return "SMTP_HOST is empty";
                }

                @Override
                public void send(Letters.Letter letter, java.util.function.Consumer<String> done) {
                    letters.add(letter);
                    done.accept(failWith);
                }
            };
            watch = new ServerWatch(clock::get, settings, a -> {
                knocked.add(a);
                return ports.getOrDefault(a, ServerWatch.Port.CLOSED);
            }, out, store::saveAlert, new Letters("https://staff.test"));
        }

        void heard(String server) {
            heard(server, 4, 31.5, ADDR, false);
        }

        void heard(String server, int players, double mspt, String addr, boolean stopping) {
            watch.heard(server, clock.get(), snap(server, players, mspt, addr, stopping, clock.get()));
        }

        /** The clock moves on in 2 s steps (the panel's tick), the named servers sending at every step. */
        void run(long ms, String... alive) {
            run(ms, Alerts.TICK_MS, alive);
        }

        void run(long ms, long step, String... alive) {
            for (long done = 0; done < ms; done += step) {
                clock.addAndGet(step);
                for (String s : alive) {
                    heard(s);
                }
                watch.tick();
            }
        }

        List<ServerWatch.Alert> alerts() {
            return watch.alerts();
        }

        String subjects() {
            return String.join(" | ", letters.stream().map(Letters.Letter::subject).toList());
        }
    }

    static JsonObject snap(String server, int players, double mspt, String addr, boolean stopping, long at) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("server", server);
        o.addProperty("at", at);
        o.addProperty("mspt", mspt);
        JsonArray ps = new JsonArray();
        for (int i = 0; i < players; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("id", "00000000-0000-0000-0000-00000000000" + i);
            p.addProperty("name", "P" + i);
            ps.add(p);
        }
        o.add("players", ps);
        if (addr != null) {
            o.addProperty("addr", addr);
        }
        if (stopping) {
            o.addProperty("stopping", true);
        }
        return o;
    }

    // ------------------------------------------------------------------ the triple check (D826)

    @Test
    void a_silent_server_is_checked_three_times_before_it_is_called_frozen() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.OPEN);
        r.heard("BSP-Frostwarf", 11, 48.2, ADDR, false);
        r.run(18_000);
        assertTrue(r.watch.json(true).getAsJsonObject("servers").entrySet().isEmpty(), "under 20 s silent: nothing");
        r.run(2_000);
        assertEquals("CHECKING", r.watch.json(true).getAsJsonObject("servers").getAsJsonObject("BSP-Frostwarf").get("state").getAsString(), "a suspect at 20 s");
        r.run(58_000);
        assertEquals(2, r.knocked.size(), "two checks by 78 s, 20 s apart");
        assertTrue(r.letters.isEmpty(), "no alert before the third check");
        assertTrue(r.alerts().isEmpty());
        r.run(2_000);
        assertEquals(3, r.knocked.size(), "the third check at 80 s");
        assertEquals(1, r.letters.size());
        Letters.Letter l = r.letters.get(0);
        assertEquals("BSP-Frostwarf is frozen", l.subject());
        assertTrue(l.body().contains("Its port still answers"), l.body());
        assertTrue(l.body().contains("11 players online, 48.2 ms a tick"), l.body());
        assertTrue(l.body().contains("UK time"), l.body());
        assertTrue(l.body().contains("https://staff.test/#worlds"), l.body());
        assertTrue(l.body().contains("This is the only email for this outage"), l.body());
        assertEquals(3, l.body().split("Checked")[1].split("\n")[0].split(",").length, "the three checks' times: " + l.body());
        ServerWatch.Alert a = r.alerts().get(0);
        assertEquals(ServerWatch.Kind.FROZEN, a.kind);
        assertEquals(T0, a.since, "since it was last heard, not since it was noticed");
        assertTrue(a.emailedMs > 0);
        assertEquals(1, r.store.alerts(0).size(), "kept in the panel's database");
    }

    @Test
    void a_snapshot_during_the_checks_ends_them_quietly() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.OPEN);
        r.heard("BSP-Frostwarf");
        r.run(62_000); // two checks done
        assertEquals(2, r.knocked.size());
        r.heard("BSP-Frostwarf");
        r.run(300_000, "BSP-Frostwarf");
        assertTrue(r.letters.isEmpty());
        assertTrue(r.alerts().isEmpty(), "a pause, not an outage");
    }

    @Test
    void the_last_check_tells_frozen_from_crashed_and_no_answer_and_no_address() {
        Map<ServerWatch.Port, String> want = Map.of(ServerWatch.Port.CLOSED, "BSP-A has crashed", ServerWatch.Port.NO_ANSWER, "BSP-A is not answering");
        for (Map.Entry<ServerWatch.Port, String> e : want.entrySet()) {
            Rig r = new Rig();
            r.ports.put(ADDR, e.getKey());
            r.heard("BSP-A");
            r.run(80_000);
            assertEquals(e.getValue(), r.subjects());
        }
        Rig r = new Rig();
        r.heard("BSP-A", 2, 20, null, false);
        r.run(80_000);
        assertEquals("BSP-A is not sending", r.subjects());
        assertTrue(r.knocked.isEmpty(), "no address: nothing to knock on");
        assertTrue(r.letters.get(0).body().contains("cannot tell frozen from crashed"));
        Rig z = new Rig();
        z.heard("BSP-A", 2, 20, "0.0.0.0:25565", false);
        z.run(80_000);
        assertTrue(z.knocked.isEmpty(), "0.0.0.0 says nothing about where the server is");
        assertEquals("BSP-A is not sending", z.subjects());
    }

    // ------------------------------------------------------------------ planned restarts (D827)

    @Test
    void a_planned_stop_raises_nothing_when_it_is_back_within_five_minutes() {
        Rig r = new Rig();
        r.heard("BSP-Spawn");
        r.heard("BSP-Spawn", 6, 20, ADDR, true);
        r.run(120_000);
        assertEquals("RESTARTING", r.watch.json(true).getAsJsonObject("servers").getAsJsonObject("BSP-Spawn").get("state").getAsString());
        r.run(178_000);
        assertTrue(r.knocked.isEmpty(), "nothing is checked while it restarts");
        r.heard("BSP-Spawn");
        r.run(600_000, "BSP-Spawn");
        assertTrue(r.letters.isEmpty());
        assertTrue(r.alerts().isEmpty());
    }

    @Test
    void a_planned_stop_not_back_after_five_minutes_raises_not_back_and_then_back() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.CLOSED);
        r.heard("BSP-Spawn", 6, 20, ADDR, false);
        r.heard("BSP-Spawn", 0, 0, ADDR, true);
        long stoppedAt = r.clock.get();
        r.run(298_000);
        assertTrue(r.letters.isEmpty(), "inside the grace time");
        r.run(2_000);
        assertEquals("BSP-Spawn is not back after its restart", r.subjects());
        assertTrue(r.letters.get(0).body().contains("Its port is closed: it is not running."), r.letters.get(0).body());
        assertTrue(r.letters.get(0).body().contains("6 players online"), "players as before it stopped: " + r.letters.get(0).body());
        assertEquals(stoppedAt, r.alerts().get(0).since);
        r.run(100_000);
        assertEquals(1, r.letters.size(), "one email per outage");
        r.heard("BSP-Spawn");
        r.run(2_000, "BSP-Spawn");
        assertEquals(2, r.letters.size());
        assertEquals("BSP-Spawn is back after 6 minutes", r.letters.get(1).subject());
        assertTrue(r.letters.get(1).body().contains("not back after a planned restart"));
    }

    // ------------------------------------------------------------------ emails: back, once per outage, shared (D828, D830)

    @Test
    void the_back_email_says_how_long_it_was_out() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.OPEN);
        r.heard("BSP-Frostwarf");
        r.run(444_000);
        r.clock.addAndGet(1_000);
        assertEquals(1, r.letters.size());
        r.heard("BSP-Frostwarf", 0, 41.0, ADDR, false);
        assertEquals(2, r.letters.size());
        Letters.Letter back = r.letters.get(1);
        assertEquals("BSP-Frostwarf is back after 7 minutes", back.subject());
        assertTrue(back.body().contains("7 minutes 25 seconds"), back.body());
        assertTrue(back.body().contains("frozen (its port answered)"), back.body());
        assertTrue(back.body().contains("0 players online, 41.0 ms a tick"), back.body());
        ServerWatch.Alert a = r.alerts().get(0);
        assertFalse(a.open());
        assertTrue(a.backEmailedMs > 0);
    }

    @Test
    void one_email_per_outage_with_no_hourly_limit() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.CLOSED);
        r.heard("BSP-Stormfall");
        r.run(3_600_000, 2_000, "BSP-Boulderbrook");
        assertEquals("BSP-Stormfall has crashed", r.subjects(), "an hour out: still one email");
        r.heard("BSP-Stormfall");
        r.run(4_000, "BSP-Stormfall", "BSP-Boulderbrook");
        r.run(80_000, "BSP-Boulderbrook");
        assertEquals("BSP-Stormfall has crashed | BSP-Stormfall is back after 1 hour | BSP-Stormfall has crashed", r.subjects(),
                "a second outage within the hour has its own email (D830)");
    }

    @Test
    void servers_silent_within_a_minute_share_one_email() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.CLOSED);
        r.heard("BSP-Moongrove");
        r.heard("BSP-Stormfall");
        r.heard("BSP-Spawn");
        r.run(30_000, "BSP-Stormfall", "BSP-Spawn"); // Stormfall goes quiet 30 s after Moongrove
        r.run(50_000, "BSP-Spawn");
        assertTrue(r.letters.isEmpty(), "Moongrove is decided at 80 s, but waits for Stormfall's checks");
        r.run(30_000, "BSP-Spawn");
        assertEquals(1, r.letters.size());
        Letters.Letter l = r.letters.get(0);
        assertEquals("2 game servers are out: BSP-Moongrove, BSP-Stormfall", l.subject());
        assertTrue(l.body().contains("Both are at 192.168.1.27: the machine itself may be down."), l.body());
        r.heard("BSP-Moongrove");
        r.heard("BSP-Stormfall");
        assertEquals(3, r.letters.size(), "each back on its own when they come back apart");
    }

    @Test
    void servers_silent_more_than_a_minute_apart_get_their_own_emails() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.CLOSED);
        r.heard("BSP-Moongrove");
        r.heard("BSP-Stormfall");
        r.heard("BSP-Spawn");
        r.run(70_000, "BSP-Stormfall", "BSP-Spawn");
        r.run(10_000, "BSP-Spawn");
        assertEquals("BSP-Moongrove has crashed", r.subjects());
        r.run(70_000, "BSP-Spawn");
        assertEquals("BSP-Moongrove has crashed | BSP-Stormfall has crashed", r.subjects());
    }

    @Test
    void back_before_its_email_went_means_no_email_at_all() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.OPEN);
        r.heard("BSP-A");
        r.heard("BSP-B");
        r.heard("BSP-C");
        r.run(30_000, "BSP-B", "BSP-C");
        r.run(56_000, "BSP-C"); // A decided at 80 s, waiting for B
        r.heard("BSP-A");
        r.run(30_000, "BSP-A", "BSP-C");
        assertEquals("BSP-B is frozen", r.subjects());
        ServerWatch.Alert a = r.alerts().stream().filter(x -> x.server.equals("BSP-A")).findFirst().orElseThrow();
        assertEquals("not emailed: back before the email went", a.emailNote);
        assertFalse(a.open());
    }

    @Test
    void a_failed_email_is_kept_and_no_back_email_follows() {
        Rig r = new Rig();
        r.failWith = "the mail server refused the sign-in (535 nope): check SMTP_USER and SMTP_PASSWORD";
        r.heard("BSP-A");
        r.run(80_000);
        assertEquals(1, r.letters.size(), "tried");
        ServerWatch.Alert a = r.alerts().get(0);
        assertTrue(a.emailNote.startsWith("email failed: the mail server refused"), a.emailNote);
        r.heard("BSP-A");
        assertEquals(1, r.letters.size(), "no back email after an alert email that did not go");
        assertEquals("email failed", r.watch.json(false).getAsJsonArray("recent").get(0).getAsJsonObject().get("emailNote").getAsString(),
                "the mail server's words are for Owners");
        assertTrue(r.watch.json(true).getAsJsonArray("recent").get(0).getAsJsonObject().get("emailNote").getAsString().contains("535"));
    }

    @Test
    void without_email_the_alert_is_still_kept_and_shown() {
        Rig r = new Rig();
        r.ready = false;
        r.heard("BSP-A");
        r.run(80_000);
        assertTrue(r.letters.isEmpty());
        assertEquals("not emailed: email is not set up", r.alerts().get(0).emailNote);
        assertEquals(1, r.watch.json(false).getAsJsonArray("open").size());
        JsonObject email = r.watch.json(true).getAsJsonObject("email");
        assertFalse(email.get("on").getAsBoolean());
        assertEquals("SMTP_HOST is empty", email.get("why").getAsString());
        assertNull(r.watch.json(false).get("email"), "only Owners see the email line");
        r.heard("BSP-A");
        assertTrue(r.letters.isEmpty());
    }

    // ------------------------------------------------------------------ the panel hearing nothing (D832 c)

    @Test
    void when_no_server_is_heard_the_panel_says_so_once_instead_of_an_alert_per_server() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.OPEN);
        r.heard("BSP-A");
        r.heard("BSP-B");
        r.heard("BSP-C");
        r.run(200_000);
        assertEquals("The staff panel cannot hear the game servers", r.subjects());
        assertTrue(r.knocked.isEmpty(), "no server is checked while the panel hears none");
        assertTrue(r.letters.get(0).body().contains("3 servers"), r.letters.get(0).body());
        r.heard("BSP-A");
        assertEquals("The staff panel hears the game servers again", r.letters.get(1).subject());
        r.run(80_000, "BSP-A");
        assertEquals("2 game servers are out: BSP-B, BSP-C", r.letters.get(2).subject(), "then the others are checked as usual");
    }

    @Test
    void a_server_already_in_an_alert_does_not_count_toward_the_panel_hearing_nothing() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.CLOSED);
        r.heard("BSP-A");
        r.heard("BSP-B");
        r.run(80_000, "BSP-B");
        assertEquals("BSP-A has crashed", r.subjects());
        r.run(100_000); // now B goes quiet too: A is already out, so this is B's own outage, not the panel's deafness
        assertEquals("BSP-A has crashed | BSP-B has crashed", r.subjects());
    }

    @Test
    void a_network_wide_planned_restart_is_not_the_panel_hearing_nothing() {
        Rig r = new Rig();
        for (String s : List.of("BSP-A", "BSP-B", "BSP-C")) {
            r.heard(s);
            r.heard(s, 0, 0, ADDR, true);
        }
        r.run(120_000);
        for (String s : List.of("BSP-A", "BSP-B", "BSP-C")) {
            r.heard(s);
        }
        r.run(60_000, "BSP-A", "BSP-B", "BSP-C");
        assertTrue(r.letters.isEmpty(), r.subjects());
    }

    // ------------------------------------------------------------------ the panel's start, and forgetting (D832 b)

    @Test
    void on_start_the_servers_of_the_last_day_are_watched() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.CLOSED);
        r.watch.seed("BSP-A", T0 - 3_600_000L, snap("BSP-A", 3, 20, ADDR, false, T0 - 3_600_000L));
        r.watch.seed("BSP-Old", T0 - 25 * 3_600_000L, snap("BSP-Old", 3, 20, ADDR, false, T0 - 25 * 3_600_000L));
        assertEquals(1, r.watch.json(true).get("watched").getAsInt(), "a server not heard for a day is not watched");
        r.run(62_000);
        assertEquals("BSP-A has crashed", r.subjects(), "a server that died while the panel was down still alerts once");
        assertEquals(T0 - 3_600_000L, r.alerts().get(0).since);
    }

    @Test
    void alerts_kept_from_before_a_restart_go_back_to_their_servers() {
        Rig r = new Rig();
        r.watch.seed("BSP-A", T0 - 10_000L, snap("BSP-A", 3, 20, ADDR, false, T0 - 10_000L));
        r.watch.restore(List.of(
                new AlertRow(1, "BSP-A", "FROZEN", T0 - 600_000, T0 - 520_000, T0 - 600_000, "OPEN", ADDR, 3, 20, "", T0 - 519_000, "", 0, 0, 0, ""),
                new AlertRow(2, "BSP-Gone", "CRASHED", T0 - 600_000, T0 - 520_000, T0 - 600_000, "CLOSED", ADDR, 1, 20, "", T0 - 519_000, "", 0, 0, 0, "")));
        r.run(120_000);
        assertTrue(r.knocked.isEmpty(), "BSP-A already has its alert: no second one");
        ServerWatch.Alert gone = r.alerts().stream().filter(a -> a.server.equals("BSP-Gone")).findFirst().orElseThrow();
        assertFalse(gone.open());
        assertTrue(gone.note.contains("no longer watched"));
        r.heard("BSP-A");
        assertEquals("BSP-A is back after 12 minutes", r.subjects());
    }

    @Test
    void a_server_not_heard_for_a_day_is_no_longer_watched() {
        Rig r = new Rig();
        r.heard("BSP-A");
        r.run(24 * 3_600_000L + 60_000, 60_000);
        assertEquals(0, r.watch.json(true).get("watched").getAsInt());
        ServerWatch.Alert a = r.alerts().get(0);
        assertFalse(a.open());
        assertTrue(a.note.contains("no longer watched"));
        assertEquals(1, r.letters.size(), "its alert, and no back email");
    }

    // ------------------------------------------------------------------ slow servers (D831)

    @Test
    void slow_alerts_are_off_unless_alert_slow_mspt_is_set() {
        Rig r = new Rig();
        for (int i = 0; i < 300; i++) {
            r.clock.addAndGet(2_000);
            r.heard("BSP-A", 5, 300, ADDR, false);
            r.watch.tick();
        }
        assertTrue(r.letters.isEmpty());
    }

    @Test
    void a_server_slow_for_five_minutes_raises_slow_and_is_over_after_two_calm_minutes() {
        Rig r = new Rig(Map.of("ALERT_SLOW_MSPT", "100"));
        for (int i = 0; i < 150; i++) {
            r.heard("BSP-A", 5, 150, ADDR, false);
            r.clock.addAndGet(2_000);
            r.watch.tick();
        }
        assertTrue(r.letters.isEmpty(), "under 5 minutes");
        r.heard("BSP-A", 5, 150, ADDR, false);
        assertEquals("BSP-A is very slow", r.subjects());
        assertTrue(r.letters.get(0).body().contains("100.0 ms a tick (under 10 TPS)"), r.letters.get(0).body());
        for (int i = 0; i < 61; i++) {
            r.clock.addAndGet(2_000);
            r.heard("BSP-A", 5, 40, ADDR, false);
            r.watch.tick();
        }
        assertEquals(2, r.letters.size(), r.subjects());
        assertEquals("BSP-A is no longer slow", r.letters.get(1).subject());
    }

    // ------------------------------------------------------------------ addresses (D832 a)

    @Test
    void the_panel_s_alert_servers_variable_wins_over_the_snapshot_s_address() {
        Rig r = new Rig(Map.of("ALERT_SERVERS", "BSP-A=10.0.0.9:25565, BSP-B = 10.0.0.10:25570"));
        r.heard("BSP-A");
        r.run(80_000);
        assertEquals(List.of("10.0.0.9:25565", "10.0.0.9:25565", "10.0.0.9:25565"), r.knocked);
        assertEquals("10.0.0.10:25570", r.settings.addressOf("bsp-b"));
    }

    // ------------------------------------------------------------------ the settings, never printed (D829)

    @Test
    void the_settings_come_from_the_environment_and_are_never_printed() {
        Map<String, String> env = new HashMap<>(Map.of("SMTP_HOST", "smtp.secret-host.test", "SMTP_USER", "alerts.secret@gmail.test",
                "SMTP_PASSWORD", "abcd efgh ijkl mnop", "ALERT_EMAIL_TO", "one@bsp.test, two@bsp.test"));
        AlertSettings s = AlertSettings.from(env);
        assertTrue(s.ready(), String.valueOf(s.whyNot()));
        assertEquals(587, s.port());
        assertEquals("alerts.secret@gmail.test", s.from(), "the sender is the account when ALERT_EMAIL_FROM is empty");
        assertEquals("email alerts on, to 2 addresses", s.toString());
        for (String v : env.values()) {
            assertFalse(s.toString().contains(v.split(",")[0]), "never a value: " + s);
        }
        env.remove("SMTP_PASSWORD");
        env.put("SMTP_PORT", "five");
        AlertSettings bad = AlertSettings.from(env);
        assertFalse(bad.ready());
        assertTrue(bad.whyNot().contains("SMTP_PASSWORD is empty") && bad.whyNot().contains("SMTP_PORT is not a number"), bad.whyNot());
        assertFalse(bad.toString().contains("secret"), bad.toString());
        env.put("SMTP_PASSWORD", "x");
        env.put("SMTP_PORT", "465");
        env.put("ALERT_EMAIL_TO", "not an address");
        assertTrue(AlertSettings.from(env).whyNot().contains("ALERT_EMAIL_TO holds something that is not an email address"));
        assertEquals("SMTP_HOST is empty; SMTP_USER is empty; SMTP_PASSWORD is empty; ALERT_EMAIL_TO is empty", AlertSettings.from(Map.of()).whyNot());
    }

    // ------------------------------------------------------------------ the mail sender (D832 d)

    /** A stand-in mail server for one conversation: it answers by the script and keeps every line it heard. */
    static final class FakeSmtp implements AutoCloseable {
        final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final List<String> heard = new CopyOnWriteArrayList<>();
        final StringBuilder data = new StringBuilder();
        final Thread t;

        FakeSmtp(boolean offersStartTls, boolean acceptAuth) throws Exception {
            t = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    out.write("220 fake ready\r\n".getBytes());
                    String l;
                    boolean inData = false;
                    while ((l = in.readLine()) != null) {
                        if (inData) {
                            if (l.equals(".")) {
                                inData = false;
                                out.write("250 queued\r\n".getBytes());
                            } else {
                                data.append(l).append("\n");
                            }
                            continue;
                        }
                        heard.add(l);
                        String u = l.toUpperCase();
                        if (u.startsWith("EHLO")) {
                            out.write(("250-fake\r\n" + (offersStartTls ? "250-STARTTLS\r\n" : "") + "250 AUTH PLAIN LOGIN\r\n").getBytes());
                        } else if (u.startsWith("AUTH")) {
                            out.write((acceptAuth ? "235 ok\r\n" : "535 5.7.8 Username and Password not accepted\r\n").getBytes());
                        } else if (u.startsWith("MAIL") || u.startsWith("RCPT")) {
                            out.write("250 ok\r\n".getBytes());
                        } else if (u.equals("DATA")) {
                            inData = true;
                            out.write("354 go\r\n".getBytes());
                        } else if (u.equals("QUIT")) {
                            out.write("221 bye\r\n".getBytes());
                            break;
                        } else {
                            out.write("502 no\r\n".getBytes());
                        }
                    }
                } catch (Exception ignored) {
                    // the test reads what was heard
                }
            });
            t.start();
        }

        Map<String, String> env() {
            return Map.of("SMTP_HOST", "127.0.0.1", "SMTP_PORT", String.valueOf(ss.getLocalPort()), "SMTP_USER", "alerts@bsp.test",
                    "SMTP_PASSWORD", "app-password-1234", "ALERT_EMAIL_TO", "one@bsp.test,two@bsp.test");
        }

        @Override
        public void close() throws Exception {
            ss.close();
            t.join(5_000);
        }
    }

    @Test
    void the_mail_sender_signs_in_and_sends_one_plain_text_message() throws Exception {
        try (FakeSmtp f = new FakeSmtp(false, true)) {
            AlertSettings s = AlertSettings.from(f.env());
            Smtp smtp = new Smtp(s, javax.net.SocketFactory.getDefault(), null, true, 5_000);
            smtp.send("BSP-A is frozen\r\nBcc: someone@else.test", "Line one\n.\nLine three ünïcode", ZonedDateTime.now(Letters.UK));
            f.t.join(5_000);
            assertTrue(f.heard.get(0).startsWith("EHLO"), f.heard.toString());
            String auth = f.heard.stream().filter(x -> x.startsWith("AUTH PLAIN ")).findFirst().orElseThrow();
            assertEquals("\0alerts@bsp.test\0app-password-1234", new String(Base64.getDecoder().decode(auth.substring(11)), StandardCharsets.UTF_8));
            assertTrue(f.heard.contains("MAIL FROM:<alerts@bsp.test>"));
            assertTrue(f.heard.contains("RCPT TO:<one@bsp.test>") && f.heard.contains("RCPT TO:<two@bsp.test>"));
            String msg = f.data.toString();
            assertTrue(msg.contains("Subject: BSP-A is frozen Bcc: someone@else.test\n"), "one header line: nothing can add a header: " + msg);
            assertFalse(msg.contains("\nBcc:"));
            assertTrue(msg.contains("Content-Type: text/plain; charset=UTF-8"));
            String b64 = msg.substring(msg.indexOf("\n\n") + 2).replace("\n", "");
            assertEquals("Line one\r\n.\r\nLine three ünïcode", new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
            assertEquals("QUIT", f.heard.get(f.heard.size() - 1));
        }
    }

    @Test
    void a_refused_sign_in_names_the_variables_never_their_values() throws Exception {
        try (FakeSmtp f = new FakeSmtp(false, false)) {
            Smtp smtp = new Smtp(AlertSettings.from(f.env()), javax.net.SocketFactory.getDefault(), null, true, 5_000);
            Smtp.Failed e = assertThrows(Smtp.Failed.class, () -> smtp.send("x", "y", ZonedDateTime.now(Letters.UK)));
            assertTrue(e.getMessage().contains("check SMTP_USER and SMTP_PASSWORD"), e.getMessage());
            assertFalse(e.getMessage().contains("app-password-1234") || e.getMessage().contains("alerts@bsp.test") || e.getMessage().contains("127.0.0.1"), e.getMessage());
            assertFalse(f.heard.stream().anyMatch(x -> x.startsWith("MAIL")), "nothing sent after a refused sign-in");
        }
    }

    @Test
    void the_password_is_never_sent_over_an_unencrypted_connection() throws Exception {
        try (FakeSmtp f = new FakeSmtp(false, true)) {
            Smtp smtp = new Smtp(AlertSettings.from(f.env()), javax.net.SocketFactory.getDefault(), (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(), false, 5_000);
            Smtp.Failed e = assertThrows(Smtp.Failed.class, () -> smtp.send("x", "y", ZonedDateTime.now(Letters.UK)));
            assertTrue(e.getMessage().contains("offers no encryption"), e.getMessage());
            f.close();
            assertFalse(f.heard.stream().anyMatch(x -> x.startsWith("AUTH")), f.heard.toString());
        }
    }

    @Test
    void the_mail_queue_tries_again_then_gives_up_with_the_reason() throws Exception {
        AlertSettings s = AlertSettings.from(Map.of("SMTP_HOST", "h.test", "SMTP_USER", "a@bsp.test", "SMTP_PASSWORD", "p", "ALERT_EMAIL_TO", "o@bsp.test"));
        AtomicInteger tries = new AtomicInteger();
        MailQueue q = new MailQueue(s, (subject, body, date) -> {
            if (tries.incrementAndGet() < 3) {
                throw new Smtp.Failed("the mail server did not answer in time");
            }
        }, Executors.newSingleThreadScheduledExecutor(), new long[]{1, 1, 1}, System::currentTimeMillis);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> said = new AtomicReference<>("unset");
        q.send(new Letters.Letter("s", "b"), e -> {
            said.set(e);
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(said.get(), "went on the third try");
        assertEquals(3, tries.get());
        MailQueue never = new MailQueue(s, (subject, body, date) -> {
            throw new Smtp.Failed("the mail server did not answer in time");
        }, Executors.newSingleThreadScheduledExecutor(), new long[]{1, 1, 1}, System::currentTimeMillis);
        CountDownLatch gave = new CountDownLatch(1);
        never.send(new Letters.Letter("s", "b"), e -> {
            said.set(e);
            gave.countDown();
        });
        assertTrue(gave.await(5, TimeUnit.SECONDS));
        assertEquals("the mail server did not answer in time", said.get(), "after four tries");
        q.close();
        never.close();
    }

    // ------------------------------------------------------------------ the port check and the feed's hook

    @Test
    void the_port_check_is_a_plain_connection() throws Exception {
        int closedPort;
        try (ServerSocket open = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(ServerWatch.Port.OPEN, PortKnock.knock("127.0.0.1:" + open.getLocalPort()));
            closedPort = open.getLocalPort();
        }
        assertEquals(ServerWatch.Port.CLOSED, PortKnock.knock("127.0.0.1:" + closedPort));
        assertEquals(ServerWatch.Port.UNKNOWN, PortKnock.knock("0.0.0.0:25565"));
        assertEquals(ServerWatch.Port.UNKNOWN, PortKnock.knock("no port"));
        assertEquals(ServerWatch.Port.UNKNOWN, PortKnock.knock("host:99999"));
        assertTrue(PortKnock.usable("[::1]:25565"));
    }

    @Test
    void the_feed_tells_the_watch_of_every_snapshot_a_planned_stop_included() {
        AtomicLong clock = new AtomicLong(T0);
        Feed feed = new Feed(clock::get);
        List<String> heard = new ArrayList<>();
        feed.onSnapshot((server, at, o) -> heard.add(server + (o.has("stopping") ? " stopping" : "")));
        assertTrue(feed.acceptMessage("1\n" + T0 + "\nBSP-A\nsnapshot\n" + snap("BSP-A", 1, 20, ADDR, false, T0)));
        assertTrue(feed.acceptMessage("2\n" + T0 + "\nBSP-A\nsnapshot\n" + snap("BSP-A", 0, 20, ADDR, true, T0 + 1)));
        assertEquals(List.of("BSP-A", "BSP-A stopping"), heard);
        assertTrue(feed.live().isEmpty(), "the feed drops it; the watch keeps it");
    }

    @Test
    void lengths_read_as_words() {
        assertEquals("45 seconds", Letters.length(45_000));
        assertEquals("1 minute", Letters.length(60_000));
        assertEquals("7 minutes 25 seconds", Letters.length(445_000));
        assertEquals("1 hour 3 minutes", Letters.length(3_780_000));
        assertEquals("7 minutes", Letters.shortLength(445_000));
        assertNotNull(JsonParser.parseString(new Rig().watch.json(true).toString()));
    }

    @Test
    void the_pages_signature_moves_with_every_change_the_pages_show() {
        Rig r = new Rig();
        r.ports.put(ADDR, ServerWatch.Port.OPEN);
        List<String> seen = new ArrayList<>();
        r.heard("BSP-A");
        seen.add(r.watch.signature());
        r.run(20_000);
        seen.add(r.watch.signature()); // CHECKING
        r.run(20_000);
        seen.add(r.watch.signature()); // one check
        r.run(40_000);
        seen.add(r.watch.signature()); // FROZEN, emailed
        r.heard("BSP-A");
        seen.add(r.watch.signature()); // back
        assertEquals(seen.size(), new java.util.HashSet<>(seen).size(), String.join(" | ", seen));
        String still = r.watch.signature();
        r.run(10_000, "BSP-A");
        assertEquals(still, r.watch.signature(), "snapshots that change nothing shown do not move it");
    }
}
