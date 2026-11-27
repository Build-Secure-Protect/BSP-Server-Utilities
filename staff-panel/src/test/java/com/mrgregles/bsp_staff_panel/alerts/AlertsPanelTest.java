package com.mrgregles.bsp_staff_panel.alerts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.crypto.Base32;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.crypto.Totp;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.web.PanelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The alerts over HTTP (SU-167, R4): every logged-in staff member reads them, only Owners see the email line and send a test. */
class AlertsPanelTest {
    private static final String URL = "https://staff.test", PW = "frost-wolf-lantern-9";
    private MemoryStore store;
    private Auth auth;
    private PanelServer server;
    private AtomicLong clock;
    private ServerWatch watch;
    private final List<String> mailed = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void start() throws Exception {
        store = new MemoryStore();
        clock = new AtomicLong(System.currentTimeMillis());
        auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 1000);
        Feed feed = new Feed(clock::get);
        PanelServer.Gate gate = header -> {
            if (header == null || !header.startsWith("good:")) {
                throw new AccessJwt.Invalid("no");
            }
            return new AccessJwt.Identity(header.substring(5), Long.MAX_VALUE);
        };
        AlertSettings s = AlertSettings.from(Map.of("SMTP_HOST", "h.test", "SMTP_USER", "alerts@bsp.test", "SMTP_PASSWORD", "app-pw", "ALERT_EMAIL_TO", "owner@bsp.test"));
        MailQueue mail = new MailQueue(s, (subject, body, date) -> mailed.add(subject), Executors.newSingleThreadScheduledExecutor(), new long[0], clock::get);
        Letters letters = new Letters(URL);
        watch = new ServerWatch(clock::get, s, a -> ServerWatch.Port.OPEN, mail, store::saveAlert, letters);
        feed.onSnapshot(watch::heard);
        server = new PanelServer("127.0.0.1", 0, URL, gate, auth, store, feed, clock::get).withAlerts(Alerts.of(s, watch, mail, letters)).start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> post(String path, String who, String cookie, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("X-BSP", "1").header("Origin", URL).header("Cf-Access-Jwt-Assertion", "good:" + who);
        if (cookie != null) {
            b.header("Cookie", PanelServer.SECURE_COOKIE + "=" + cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private String setUp(String email, String token) throws Exception {
        JsonObject info = json(post("/api/setup/info", email, null, "{\"token\":\"" + token + "\"}"));
        String secret = info.get("secret").getAsString();
        String code = Totp.format(Totp.code(Base32.decode(secret), Totp.step(clock.get())));
        HttpResponse<String> done = post("/api/setup", email, null, "{\"token\":\"" + token + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code + "\",\"password\":\"" + PW + "\"}");
        assertTrue(json(done).get("ok").getAsBoolean(), done.body());
        clock.addAndGet(31_000);
        String c = done.headers().firstValue("Set-Cookie").orElse("");
        return c.substring(c.indexOf('=') + 1, c.indexOf(';'));
    }

    @Test
    void staff_read_the_alerts_and_only_an_owner_sends_a_test_email() throws Exception {
        String owner = setUp("owner@bsp.test", auth.createOwner("owner@bsp.test", "Mrgregles").token());
        JsonObject added = json(post("/api/staff/add", "owner@bsp.test", owner, "{\"email\":\"ana@bsp.test\",\"mcName\":\"Ana_Mod\",\"rank\":\"MODERATOR\"}"));
        String mod = setUp("ana@bsp.test", added.get("link").getAsString().substring((URL + "/setup#").length()));

        JsonObject seen = json(post("/api/alerts", "ana@bsp.test", mod, "{}"));
        assertTrue(seen.get("ok").getAsBoolean(), seen.toString());
        assertTrue(seen.has("open") && seen.has("recent") && seen.has("servers"));
        assertNull(seen.get("email"), "the email line is for Owners");
        JsonObject asOwner = json(post("/api/alerts", "owner@bsp.test", owner, "{}"));
        assertTrue(asOwner.getAsJsonObject("email").get("on").getAsBoolean());
        assertEquals(1, asOwner.getAsJsonObject("email").get("to").getAsInt());
        assertFalse(asOwner.toString().contains("app-pw") || asOwner.toString().contains("h.test") || asOwner.toString().contains("alerts@bsp.test"),
                "no setting's value reaches a page: " + asOwner);

        assertEquals(403, post("/api/alerts/test", "ana@bsp.test", mod, "{}").statusCode(), "a Moderator cannot send a test");
        assertTrue(mailed.isEmpty());
        JsonObject sent = json(post("/api/alerts/test", "owner@bsp.test", owner, "{}"));
        assertTrue(sent.get("ok").getAsBoolean(), sent.toString());
        assertEquals(List.of("Test email from the BSP staff panel"), mailed);
        JsonObject again = json(post("/api/alerts/test", "owner@bsp.test", owner, "{}"));
        assertFalse(again.get("ok").getAsBoolean(), "once every 30 seconds at most");
        assertEquals(1, mailed.size());
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().contains("sent a test alert email")), "logged");
    }

    @Test
    void every_open_page_is_told_when_the_alerts_change() throws Exception {
        String owner = setUp("owner@bsp.test", auth.createOwner("owner@bsp.test", "Mrgregles").token());
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/live")).header("Cf-Access-Jwt-Assertion", "good:owner@bsp.test")
                .header("Cookie", PanelServer.SECURE_COOKIE + "=" + owner).GET().build();
        HttpResponse<java.io.InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(r.body(), java.nio.charset.StandardCharsets.UTF_8));
        JsonObject snap = AlertsTest.snap("BSP-Spawn", 2, 20, "127.0.0.1:1", false, clock.get());
        snap.addProperty("stopping", true);
        watch.heard("BSP-Spawn", clock.get(), snap); // a planned restart begins: the pages show it (R4's blue line)
        String line;
        boolean heard = false;
        long until = System.currentTimeMillis() + 5000;
        while (!heard && System.currentTimeMillis() < until && (line = in.readLine()) != null) {
            heard = line.equals("event: alerts");
        }
        assertTrue(heard, "the page is told within a second and reads /api/alerts again");
        r.body().close();
        assertEquals("RESTARTING", json(post("/api/alerts", "owner@bsp.test", owner, "{}")).getAsJsonObject("servers").getAsJsonObject("BSP-Spawn").get("state").getAsString());
    }
}
