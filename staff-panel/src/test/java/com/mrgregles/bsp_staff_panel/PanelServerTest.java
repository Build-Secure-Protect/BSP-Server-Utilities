package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.Base32;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.crypto.Totp;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.web.PanelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's web server (SU-33b): the Cloudflare check on every request, POSTs only from the panel's own pages, the
 * security headers, setting up and logging in over HTTP with the cookie's flags and length, Owner-only pages, a login
 * refused under another Cloudflare identity, and the live feed reaching an open page.
 */
class PanelServerTest {
    private static final String URL = "https://staff.test", PW = "frost-wolf-lantern-9";
    private MemoryStore store;
    private Auth auth;
    private Feed feed;
    private PanelServer server;
    private AtomicLong clock;
    private com.mrgregles.bsp_staff_panel.act.Actions actions;
    private final java.util.List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void start() throws Exception {
        store = new MemoryStore();
        clock = new AtomicLong(System.currentTimeMillis());
        auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 1000);
        feed = new Feed(clock::get);
        PanelServer.Gate gate = header -> {
            if (header == null || !header.startsWith("good:")) {
                throw new AccessJwt.Invalid("no");
            }
            return new AccessJwt.Identity(header.substring(5), Long.MAX_VALUE);
        };
        actions = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            sent.add(payload);
            new Thread(() -> actions.acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer",
                    com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"done\"}")))).start();
        }, clock::get, 2);
        server = new PanelServer("127.0.0.1", 0, URL, gate, auth, store, feed, clock::get).withActions(actions).start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<String> get(String path, String who, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (who != null) b.header("Cf-Access-Jwt-Assertion", "good:" + who);
        if (cookie != null) b.header("Cookie", PanelServer.COOKIE + "=" + cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String who, String cookie, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("X-BSP", "1").header("Origin", URL);
        if (who != null) b.header("Cf-Access-Jwt-Assertion", "good:" + who);
        if (cookie != null) b.header("Cookie", PanelServer.COOKIE + "=" + cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String cookieOf(HttpResponse<String> r) {
        String c = r.headers().firstValue("Set-Cookie").orElse("");
        return c.substring(c.indexOf('=') + 1, c.indexOf(';'));
    }

    private String code(String secret) {
        return Totp.format(Totp.code(Base32.decode(secret), Totp.step(clock.get())));
    }

    /** An Owner set up over HTTP; returns their session cookie. */
    private String owner() throws Exception {
        Auth.NewLink link = auth.createOwner("owner@bsp.test", "Mrgregles");
        JsonObject info = json(post("/api/setup/info", "owner@bsp.test", null, "{\"token\":\"" + link.token() + "\"}"));
        String secret = info.get("secret").getAsString();
        HttpResponse<String> done = post("/api/setup", "owner@bsp.test", null,
                "{\"token\":\"" + link.token() + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code(secret) + "\",\"password\":\"" + PW + "\"}");
        assertTrue(json(done).get("ok").getAsBoolean(), done.body());
        clock.addAndGet(31_000);
        return cookieOf(done);
    }

    @Test
    void nothing_is_served_without_cloudflare_s_token() throws Exception {
        for (String p : new String[]{"/", "/login", "/setup", "/static/panel.css", "/api/whoami", "/api/live"}) {
            HttpResponse<String> r = get(p, null, null);
            assertEquals(403, r.statusCode(), p);
            assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"), "headers even on a refusal");
        }
        HttpResponse<String> who = get("/api/whoami", "ana@bsp.test", null);
        assertEquals("ana@bsp.test", json(who).get("email").getAsString());
    }

    @Test
    void every_page_has_the_security_headers_and_needs_a_login() throws Exception {
        HttpResponse<String> root = get("/", "ana@bsp.test", null);
        assertEquals(303, root.statusCode());
        assertEquals("/login", root.headers().firstValue("Location").orElse(""));
        HttpResponse<String> login = get("/login", "ana@bsp.test", null);
        assertEquals(200, login.statusCode());
        assertTrue(login.body().contains("Log in"));
        assertEquals("DENY", login.headers().firstValue("X-Frame-Options").orElse(""));
        assertEquals("nosniff", login.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("no-store", login.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(login.headers().firstValue("Strict-Transport-Security").isPresent());
        String csp = login.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'") && !csp.contains("unsafe"), csp);
        assertFalse(login.body().contains("style=\"") || login.body().contains("<script>"), "no inline styles or scripts, which the policy would block");
        assertEquals(200, get("/static/app.js", "ana@bsp.test", null).statusCode());
        assertEquals(404, get("/static/../panel.properties", "ana@bsp.test", null).statusCode());
        assertEquals(401, get("/api/me", "ana@bsp.test", null).statusCode());
        assertEquals(401, get("/api/live", "ana@bsp.test", null).statusCode());
    }

    @Test
    void a_post_must_come_from_the_panel_s_own_page() throws Exception {
        String body = "{\"password\":\"x\",\"code\":\"1\"}";
        HttpRequest.Builder base = HttpRequest.newBuilder(uri("/api/login")).POST(HttpRequest.BodyPublishers.ofString(body)).header("Cf-Access-Jwt-Assertion", "good:a@b.test");
        assertEquals(403, http.send(base.copy().header("Content-Type", "application/json").header("Origin", URL).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no X-BSP");
        assertEquals(403, http.send(base.copy().header("Content-Type", "application/json").header("X-BSP", "1").header("Origin", "https://evil.test").build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "another site");
        assertEquals(403, http.send(base.copy().header("Content-Type", "application/json").header("X-BSP", "1").build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "no Origin");
        assertEquals(403, http.send(base.copy().header("Content-Type", "text/plain").header("X-BSP", "1").header("Origin", URL).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "not JSON");
        assertEquals(400, post("/api/login", "a@b.test", null, "{\"password\":\"" + "x".repeat(PanelServer.MAX_BODY) + "\"}").statusCode(), "valid JSON, but too big");
        assertEquals(200, post("/api/login", "a@b.test", null, "{\"password\":\"x\"}").statusCode(), "the same, small, is read");
    }

    @Test
    void setting_up_and_logging_in_over_http() throws Exception {
        Auth.NewLink link = auth.createOwner("owner@bsp.test", "Mrgregles");
        JsonObject wrong = json(post("/api/setup/info", "thief@bsp.test", null, "{\"token\":\"" + link.token() + "\"}"));
        assertFalse(wrong.get("ok").getAsBoolean(), "the link is only for its own Cloudflare email");
        JsonObject info = json(post("/api/setup/info", "owner@bsp.test", null, "{\"token\":\"" + link.token() + "\"}"));
        assertTrue(info.get("otpauth").getAsString().startsWith("otpauth://totp/"));
        String svg = new String(Base64.getDecoder().decode(info.get("qr").getAsString().substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8);
        assertTrue(svg.startsWith("<svg") && svg.contains("<path"), "the QR code is a picture made here");
        String secret = info.get("secret").getAsString();
        HttpResponse<String> done = post("/api/setup", "owner@bsp.test", null,
                "{\"token\":\"" + link.token() + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code(secret) + "\",\"password\":\"" + PW + "\"}");
        assertEquals(10, json(done).getAsJsonArray("codes").size());
        String setCookie = done.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict") && setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("Max-Age=" + Rank.OWNER.session().toSeconds()), "48 hours for an Owner (D281): " + setCookie);
        String cookie = cookieOf(done);
        JsonObject me = json(get("/api/me", "owner@bsp.test", cookie));
        assertEquals("OWNER", me.get("rank").getAsString());
        assertEquals(303, get("/login", "owner@bsp.test", cookie).statusCode(), "logged in: the login page sends you on");
        assertEquals(200, get("/", "owner@bsp.test", cookie).statusCode());
        assertEquals(401, get("/api/me", "thief@bsp.test", cookie).statusCode(), "the cookie under another Cloudflare identity is refused");

        clock.addAndGet(31_000);
        JsonObject bad = json(post("/api/login", "owner@bsp.test", null, "{\"password\":\"nope nope nope\",\"code\":\"000000\"}"));
        assertTrue(bad.get("message").getAsString().contains("4 tries left"), bad.toString());
        HttpResponse<String> good = post("/api/login", "owner@bsp.test", null, "{\"password\":\"" + PW + "\",\"code\":\"" + code(secret) + "\"}");
        assertTrue(json(good).get("ok").getAsBoolean());
        String again = cookieOf(good);
        assertEquals(200, post("/api/logout", "owner@bsp.test", again, "{}").statusCode());
        assertEquals(401, get("/api/me", "owner@bsp.test", again).statusCode(), "logged out");
    }

    @Test
    void only_an_owner_manages_staff() throws Exception {
        String o = owner();
        JsonObject added = json(post("/api/staff/add", "owner@bsp.test", o, "{\"email\":\"ana@bsp.test\",\"mcName\":\"Ana_Mod\",\"rank\":\"MODERATOR\"}"));
        assertTrue(added.get("link").getAsString().startsWith(URL + "/setup#"), "the link carries its token after #, never in an address: " + added);
        String token = added.get("link").getAsString().substring((URL + "/setup#").length());
        JsonObject info = json(post("/api/setup/info", "ana@bsp.test", null, "{\"token\":\"" + token + "\"}"));
        String secret = info.get("secret").getAsString();
        HttpResponse<String> done = post("/api/setup", "ana@bsp.test", null,
                "{\"token\":\"" + token + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code(secret) + "\",\"password\":\"" + PW + "\"}");
        String ana = cookieOf(done);
        assertTrue(done.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=" + Rank.MODERATOR.session().toSeconds()), "7 days for a Moderator");
        assertEquals(403, get("/api/staff", "ana@bsp.test", ana).statusCode());
        assertEquals(403, post("/api/staff/add", "ana@bsp.test", ana, "{\"email\":\"x@bsp.test\",\"mcName\":\"Someone\",\"rank\":\"ADMIN\"}").statusCode());
        JsonObject list = json(get("/api/staff", "owner@bsp.test", o));
        assertEquals(2, list.getAsJsonArray("staff").size());
        long id = list.getAsJsonArray("staff").get(1).getAsJsonObject().get("id").getAsLong();
        assertTrue(json(post("/api/staff/action", "owner@bsp.test", o, "{\"id\":" + id + ",\"action\":\"rank\",\"rank\":\"ADMIN\"}")).get("ok").getAsBoolean());
        assertEquals(401, get("/api/me", "ana@bsp.test", ana).statusCode(), "a rank change ends their login");
        assertFalse(json(post("/api/staff/action", "owner@bsp.test", o, "{\"id\":" + id + ",\"action\":\"explode\"}")).get("ok").getAsBoolean());
    }

    @Test
    void the_live_feed_reaches_an_open_page() throws Exception {
        String o = owner();
        feed.accept("BSP-Spawn", clock.get(), "{\"v\":1,\"server\":\"BSP-Spawn\",\"at\":1,\"mspt\":20.5,\"max\":100,\"players\":[{\"name\":\"Vexa\",\"role\":null}]}");
        HttpRequest req = HttpRequest.newBuilder(uri("/api/live")).header("Cf-Access-Jwt-Assertion", "good:owner@bsp.test").header("Cookie", PanelServer.COOKIE + "=" + o).GET().build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals("text/event-stream; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8));
        String first = nextData(in);
        JsonObject j = JsonParser.parseString(first).getAsJsonObject();
        assertEquals("BSP-Spawn", j.getAsJsonArray("servers").get(0).getAsJsonObject().get("server").getAsString());
        assertEquals("BSP-Spawn", j.getAsJsonArray("players").get(0).getAsJsonObject().get("server").getAsString(), "each player carries their server");
        assertEquals(1, server.liveCount());
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":2,\"mspt\":30,\"max\":100,\"players\":[]}");
        String next = nextData(in);
        assertTrue(next.contains("Frostwarf") && next.contains("\"onPanel\":[\"Mrgregles\"]"), "a change is pushed, with who is on the panel (D293): " + next);
        r.body().close();
    }

    @Test
    void heads_need_a_login_and_come_from_the_panel_itself() throws Exception {
        String o = owner();
        String id = "11111111-2222-3333-4444-555555555555";
        assertEquals(401, get("/api/head/" + id, "owner@bsp.test", null).statusCode());
        assertEquals(404, get("/api/head/" + id, "owner@bsp.test", o).statusCode(), "nobody by that id: the page draws a plain face");
        assertEquals(404, get("/api/head/not-an-id", "owner@bsp.test", o).statusCode());
    }

    private static JsonObject unsign(String payload) {
        String body = payload.substring(0, payload.indexOf('.'));
        return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void a_kick_goes_to_the_player_s_server_and_announcements_are_for_admins() throws Exception {
        String o = owner();
        JsonObject added = json(post("/api/staff/add", "owner@bsp.test", o, "{\"email\":\"ana@bsp.test\",\"mcName\":\"Ana_Mod\",\"rank\":\"MODERATOR\"}"));
        String token = added.get("link").getAsString().substring((URL + "/setup#").length());
        String secret = json(post("/api/setup/info", "ana@bsp.test", null, "{\"token\":\"" + token + "\"}")).get("secret").getAsString();
        String ana = cookieOf(post("/api/setup", "ana@bsp.test", null,
                "{\"token\":\"" + token + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code(secret) + "\",\"password\":\"" + PW + "\"}"));
        String vexa = "11111111-2222-3333-4444-555555555555";
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"players\":[{\"id\":\"" + vexa + "\",\"name\":\"Vexa\"}]}");

        JsonObject k = json(post("/api/act", "ana@bsp.test", ana, "{\"action\":\"kick\",\"player\":\"" + vexa + "\",\"reason\":\"spam\"}"));
        assertTrue(k.get("ok").getAsBoolean(), "a Moderator may kick (D250): " + k);
        JsonObject a = unsign(sent.get(0));
        assertEquals("Moongrove", a.get("server").getAsString(), "to the server the player is on");
        assertEquals("Ana_Mod", a.get("by").getAsString());
        assertEquals("MODERATOR", a.get("rank").getAsString());
        assertEquals("spam", a.get("reason").getAsString());
        assertFalse(json(post("/api/act", "ana@bsp.test", ana, "{\"action\":\"kick\",\"player\":\"nobody\"}")).get("ok").getAsBoolean(), "someone not online: nothing sent");
        assertEquals(1, sent.size());

        assertEquals(403, post("/api/act", "ana@bsp.test", ana, "{\"action\":\"broadcast\",\"colour\":\"gold\",\"network\":true,\"message\":\"hi\"}").statusCode(),
                "announcements are for Admins and Owners");
        JsonObject b = json(post("/api/act", "owner@bsp.test", o, "{\"action\":\"broadcast\",\"title\":true,\"colour\":\"gold\",\"target\":\"\",\"network\":true,\"message\":\"Restart | soon\"}"));
        assertTrue(b.get("ok").getAsBoolean());
        JsonObject ba = unsign(sent.get(1));
        assertTrue(ba.get("network").getAsBoolean() && ba.get("title").getAsBoolean() && ba.get("target").getAsString().equals("all"), ba.toString());
        assertEquals("Moongrove", ba.get("server").getAsString(), "the network: sent through a live server");
        assertFalse(json(post("/api/act", "owner@bsp.test", o, "{\"action\":\"broadcast\",\"colour\":\"gold\",\"network\":false,\"server\":\"Nowhere\",\"message\":\"hi\"}")).get("ok").getAsBoolean(),
                "a server that is not sending: refused");
        int before = sent.size();
        assertFalse(json(post("/api/act", "owner@bsp.test", o, "{\"action\":\"broadcast\",\"colour\":\"plaid\",\"network\":true,\"message\":\"hi\"}")).get("ok").getAsBoolean(), "an unknown colour");
        assertFalse(json(post("/api/act", "owner@bsp.test", o, "{\"action\":\"broadcast\",\"colour\":\"gold\",\"target\":\"two words\",\"network\":true,\"message\":\"hi\"}")).get("ok").getAsBoolean(), "not a name");
        assertTrue(json(post("/api/act", "owner@bsp.test", o, "{\"action\":\"broadcast\",\"colour\":\"gold\",\"title\":{\"x\":1},\"network\":\"yes\",\"server\":\"Moongrove\",\"message\":\"hi\"}")).get("ok").getAsBoolean(),
                "odd values read as false, not an error");
        assertEquals(before + 1, sent.size(), "nothing unchecked is signed (audit)");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.who().equals("ana@bsp.test") && l.what().startsWith("kicked Vexa on Moongrove")), "in the panel's log");
        assertEquals(403, post("/api/grave/return", "ana@bsp.test", ana, "{\"player\":\"Vexa\",\"grave\":\"3f9a2c1b\"}").statusCode(), "graves are returned by Admins and Owners (D296)");
    }

    @Test
    void graves_are_looked_up_by_name_and_returned_by_admins_where_the_player_is() throws Exception {
        String o = owner();
        java.util.List<String[]> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.mrgregles.bsp_staff_panel.act.Actions[] g = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        g[0] = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            JsonObject a = unsign(payload);
            asked.add(new String[]{a.get("server").getAsString(), a.get("action").getAsString()});
            String reply;
            if (a.get("action").getAsString().equals("graves")) {
                reply = "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"1 grave\",\"data\":[{\"shortId\":\"3f9a2c1b\",\"stacks\":14,\"returnable\":true}]}";
            } else if (a.get("server").getAsString().equals("Moongrove")) {
                reply = "{\"id\":\"" + uid + "\",\"ok\":false,\"message\":\"online elsewhere\",\"data\":{\"outcome\":\"ONLINE\",\"serverId\":\"Frostwarf\"}}";
            } else {
                reply = "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Returned.\"}";
            }
            new Thread(() -> g[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, reply)))).start();
        }, clock::get, 2);
        server.withActions(g[0]);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"graves\":true,\"players\":[]}");
        feed.accept("BSP-Spawn", clock.get(), "{\"v\":1,\"server\":\"BSP-Spawn\",\"at\":1,\"graves\":false,\"players\":[]}");
        store.players.put("22222222-3333-4444-5555-666666666666", "Old_Steve");

        JsonObject r = json(post("/api/graves", "owner@bsp.test", o, "{\"player\":\"old_steve\"}"));
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertEquals("Old_Steve", r.getAsJsonObject("player").get("name").getAsString(), "an offline player found by name, any case");
        assertEquals("3f9a2c1b", r.getAsJsonArray("graves").get(0).getAsJsonObject().get("shortId").getAsString());
        assertEquals("Moongrove", asked.get(0)[0], "offline: asked of a server whose BSP-InventorySync can answer, not BSP-Spawn (audit)");
        assertFalse(json(post("/api/graves", "owner@bsp.test", o, "{\"player\":\"Nobody_Here\"}")).get("ok").getAsBoolean(), "never played: said so");
        assertFalse(json(post("/api/graves", "owner@bsp.test", o, "{\"player\":\"no spaces\"}")).get("ok").getAsBoolean());

        JsonObject notLive = json(post("/api/grave/return", "owner@bsp.test", o, "{\"player\":\"22222222-3333-4444-5555-666666666666\",\"grave\":\"3f9a2c1b\"}"));
        assertFalse(notLive.get("ok").getAsBoolean(), "ONLINE on a server not sending to the panel: no second wait (audit)");
        assertTrue(notLive.get("message").getAsString().contains("Frostwarf") && notLive.get("message").getAsString().contains("in game"), notLive.toString());
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":1,\"graves\":true,\"players\":[]}");
        JsonObject ret = json(post("/api/grave/return", "owner@bsp.test", o, "{\"player\":\"22222222-3333-4444-5555-666666666666\",\"grave\":\"3f9a2c1b\"}"));
        assertTrue(ret.get("ok").getAsBoolean(), "an ONLINE answer is sent on once to the live server named: " + ret);
        assertEquals("Frostwarf", asked.get(asked.size() - 1)[0]);
        assertEquals(200, post("/api/grave/return", "owner@bsp.test", o, "{\"player\":\"Old_Steve\",\"grave\":\"../x\"}").statusCode());
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().startsWith("returned grave 3f9a2c1b of Old_Steve")), "in the panel's log");
    }

    private static String nextData(BufferedReader in) throws Exception {
        String line;
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && (line = in.readLine()) != null) {
            if (line.startsWith("data: ")) {
                return line.substring(6);
            }
        }
        throw new AssertionError("no event");
    }
}
