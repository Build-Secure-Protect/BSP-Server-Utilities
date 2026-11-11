package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
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
import com.mrgregles.bsp_staff_panel.store.PanelStore;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        if (cookie != null) b.header("Cookie", PanelServer.SECURE_COOKIE + "=" + cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String who, String cookie, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("X-BSP", "1").header("Origin", URL);
        if (who != null) b.header("Cf-Access-Jwt-Assertion", "good:" + who);
        if (cookie != null) b.header("Cookie", PanelServer.SECURE_COOKIE + "=" + cookie);
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
        assertTrue(setCookie.startsWith("__Host-bsp_panel=") && setCookie.contains("Path=/;") && !setCookie.contains("Domain"),
                "a __Host- cookie over https: no other *.domain page can set one in its place (audit): " + setCookie);
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
        HttpRequest req = HttpRequest.newBuilder(uri("/api/live")).header("Cf-Access-Jwt-Assertion", "good:owner@bsp.test").header("Cookie", PanelServer.SECURE_COOKIE + "=" + o).GET().build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals("text/event-stream; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8));
        String first = nextData(in);
        JsonObject j = JsonParser.parseString(first).getAsJsonObject();
        assertEquals("BSP-Spawn", j.getAsJsonArray("servers").get(0).getAsJsonObject().get("server").getAsString());
        assertEquals("BSP-Spawn", j.getAsJsonArray("players").get(0).getAsJsonObject().get("server").getAsString(), "each player carries their server");
        assertEquals(1, server.liveCount());
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":2,\"mspt\":30,\"max\":100,\"players\":[]}");
        clock.addAndGet(PanelServer.LIVE_EVERY_MS); // changes go out at most every 5 s (D251; audit: every snapshot redrew every page)
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

    @Test
    void items_are_read_where_the_player_is_or_from_any_able_server_and_players_are_found_by_name() throws Exception {
        String o = owner();
        java.util.List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.mrgregles.bsp_staff_panel.act.Actions[] g = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        String vexa = "11111111-2222-3333-4444-555555555555", steve = "22222222-3333-4444-5555-666666666666";
        g[0] = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            JsonObject a = unsign(payload);
            String srv = a.get("server").getAsString();
            asked.add(srv + " " + a.get("action").getAsString() + " " + a.get("player").getAsString());
            // Moongrove answers for Vexa from her snapshot: she moved to Frostwarf a moment ago
            boolean moved = srv.equals("Moongrove") && a.get("player").getAsString().equals(vexa);
            String data = "{\"player\":\"" + a.get("player").getAsString() + "\",\"online\":" + !a.get("player").getAsString().equals(steve) + ",\"server\":\""
                    + (moved ? "Frostwarf" : srv) + "\",\"live\":" + (!moved && !a.get("player").getAsString().equals(steve)) + ",\"version\":7,\"savedAt\":1,\"areas\":[]}";
            String reply = "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"\",\"data\":" + data + "}";
            new Thread(() -> g[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, reply)))).start();
        }, clock::get, 2);
        server.withActions(g[0]);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"items\":true,\"players\":[{\"id\":\"" + vexa + "\",\"name\":\"Vexa\"}]}");
        feed.accept("BSP-Spawn", clock.get(), "{\"v\":1,\"server\":\"BSP-Spawn\",\"at\":1,\"items\":false,\"players\":[]}");
        store.players.put(steve, "Old_Steve");
        store.players.put("33333333-4444-5555-6666-777777777777", "Velvet_Hare");
        store.players.put(vexa, "Vexa");

        JsonObject off = json(post("/api/items", "owner@bsp.test", o, "{\"player\":\"Old_Steve\"}"));
        assertTrue(off.get("ok").getAsBoolean() && off.getAsJsonObject("items").get("version").getAsLong() == 7, off.toString());
        assertEquals("Moongrove inv_read " + steve, asked.get(0), "offline: asked of a server whose BSP-InventorySync reads items, not BSP-Spawn");
        asked.clear();
        JsonObject on = json(post("/api/items", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\"}"));
        assertEquals(java.util.List.of("Moongrove inv_read " + vexa), asked, "online: asked of her own server; Frostwarf is not sending, so no second ask");
        assertFalse(on.getAsJsonObject("items").get("live").getAsBoolean(), "her snapshot, as answered");
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":1,\"items\":true,\"players\":[]}");
        asked.clear();
        JsonObject moved = json(post("/api/items", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\"}"));
        assertEquals(java.util.List.of("Moongrove inv_read " + vexa, "Frostwarf inv_read " + vexa), asked, "online on another live server: asked once more there");
        assertTrue(moved.getAsJsonObject("items").get("live").getAsBoolean(), "and her live items come back: " + moved);
        assertFalse(json(post("/api/items", "owner@bsp.test", o, "{\"player\":\"Nobody_Here\"}")).get("ok").getAsBoolean(), "never played: said so");
        assertEquals(401, post("/api/items", "owner@bsp.test", null, "{\"player\":\"Vexa\"}").statusCode(), "a login is needed");

        JsonObject found = json(post("/api/players/find", "owner@bsp.test", o, "{\"q\":\"v\"}"));
        com.google.gson.JsonArray ps = found.getAsJsonArray("players");
        assertEquals(2, ps.size(), "Vexa once (online, not again from the table) and Velvet_Hare: " + ps);
        assertEquals("Vexa", ps.get(0).getAsJsonObject().get("name").getAsString(), "online first");
        assertEquals("Moongrove", ps.get(0).getAsJsonObject().get("server").getAsString());
        assertFalse(ps.get(1).getAsJsonObject().has("server"), "an offline player carries no server");
        assertEquals(0, json(post("/api/players/find", "owner@bsp.test", o, "{\"q\":\"%\"}")).getAsJsonArray("players").size(), "not a name: nothing");
    }

    @Test
    void items_are_changed_by_admins_and_moderators_with_the_power_and_undone_within_five_minutes() throws Exception {
        String o = owner();
        JsonObject added = json(post("/api/staff/add", "owner@bsp.test", o, "{\"email\":\"ana@bsp.test\",\"mcName\":\"Ana_Mod\",\"rank\":\"MODERATOR\"}"));
        String token = added.get("link").getAsString().substring((URL + "/setup#").length());
        String secret = json(post("/api/setup/info", "ana@bsp.test", null, "{\"token\":\"" + token + "\"}")).get("secret").getAsString();
        String anaFirst = cookieOf(post("/api/setup", "ana@bsp.test", null,
                "{\"token\":\"" + token + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code(secret) + "\",\"password\":\"" + PW + "\"}"));
        long anaId = store.accountByEmail("ana@bsp.test").orElseThrow().id;
        java.util.concurrent.atomic.AtomicBoolean busyOnce = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.List<JsonObject> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.mrgregles.bsp_staff_panel.act.Actions[] g = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        g[0] = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            JsonObject a = unsign(payload);
            asked.add(a);
            String reply;
            if (a.get("server").getAsString().equals("Moongrove") && a.get("action").getAsString().equals("inv_remove")) {
                reply = "{\"id\":\"" + uid + "\",\"ok\":false,\"message\":\"online elsewhere\",\"data\":{\"outcome\":\"ONLINE\",\"serverId\":\"frostwarf\"}}";
            } else if (a.has("undo") && busyOnce.getAndSet(false)) {
                reply = "{\"id\":\"" + uid + "\",\"ok\":false,\"message\":\"Try again in a moment.\",\"data\":{\"outcome\":\"BUSY\"}}";
            } else if (a.has("undo")) {
                reply = "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Took back all 64.\"}";
            } else {
                reply = "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Added.\",\"data\":{\"outcome\":\"OK\",\"hash\":\"0123456789ab\",\"undo\":{\"action\":\"inv_take\",\"area\":\"inventory\",\"hash\":\"0123456789ab\",\"count\":64}}}";
            }
            new Thread(() -> g[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, reply)))).start();
        }, clock::get, 2);
        server.withActions(g[0]);
        String vexa = "11111111-2222-3333-4444-555555555555";
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"items\":true,\"players\":[{\"id\":\"" + vexa + "\",\"name\":\"Vexa\"}]}");
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":1,\"items\":true,\"players\":[]}");
        String add = "{\"player\":\"Vexa\",\"area\":\"inventory\",\"item\":\"Diamond\",\"count\":64,\"version\":0}";

        assertEquals(403, post("/api/items/add", "ana@bsp.test", anaFirst, add).statusCode(), "a Moderator without the items power");
        assertFalse(json(me("ana@bsp.test", anaFirst)).get("itemsPower").getAsBoolean());
        assertTrue(json(post("/api/staff/action", "owner@bsp.test", o, "{\"id\":" + anaId + ",\"action\":\"items\",\"on\":true}")).get("ok").getAsBoolean());
        assertEquals(401, me("ana@bsp.test", anaFirst).statusCode(), "giving the power ends her open logins (D428)");
        clock.addAndGet(30_000); // a code is taken once per 30-second step
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":2,\"items\":true,\"players\":[{\"id\":\"" + vexa + "\",\"name\":\"Vexa\"}]}");
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":2,\"items\":true,\"players\":[]}");
        HttpResponse<String> relogin = post("/api/login", "ana@bsp.test", null, "{\"password\":\"" + PW + "\",\"code\":\"" + code(secret) + "\"}");
        assertTrue(relogin.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=" + Rank.ADMIN.session().toSeconds()),
                "and she logs in again for 48 hours, as Admins do (D428): " + relogin.headers().firstValue("Set-Cookie").orElse(""));
        String ana = cookieOf(relogin);
        assertTrue(json(me("ana@bsp.test", ana)).get("itemsPower").getAsBoolean(), "the Owner gave it");
        assertTrue(json(get("/api/staff", "owner@bsp.test", o)).getAsJsonArray("staff").asList().stream().anyMatch(x -> x.getAsJsonObject().get("itemsPower").getAsBoolean()), "shown on the staff page");

        JsonObject r = json(post("/api/items/add", "ana@bsp.test", ana, add));
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        JsonObject sent = asked.get(0);
        assertEquals("inv_add", sent.get("action").getAsString());
        assertEquals("minecraft:diamond", sent.get("item").getAsString(), "an id without its mod is Minecraft's, any case");
        assertTrue(sent.get("items").getAsBoolean(), "the power goes with a Moderator's action");
        assertEquals("Moongrove", sent.get("server").getAsString());
        assertTrue(r.has("undo") && r.get("until").getAsLong() == clock.get() + PanelServer.UNDO_MS, "an UNDO token for 5 minutes");

        assertFalse(json(post("/api/items/undo", "owner@bsp.test", o, "{\"token\":\"" + r.get("undo").getAsString() + "\"}")).get("ok").getAsBoolean(), "only who made the change undoes it");
        JsonObject u = json(post("/api/items/undo", "ana@bsp.test", ana, "{\"token\":\"" + r.get("undo").getAsString() + "\"}"));
        assertTrue(u.get("ok").getAsBoolean(), u.toString());
        JsonObject undo = asked.get(asked.size() - 1);
        assertEquals("inv_take", undo.get("action").getAsString());
        assertTrue(undo.get("undo").getAsBoolean() && undo.get("count").getAsInt() == 64 && undo.get("player").getAsString().equals(vexa), "the kept change, for the same player: " + undo);
        assertFalse(json(post("/api/items/undo", "ana@bsp.test", ana, "{\"token\":\"" + r.get("undo").getAsString() + "\"}")).get("ok").getAsBoolean(), "once only");
        JsonObject late = json(post("/api/items/add", "ana@bsp.test", ana, add));
        clock.addAndGet(PanelServer.UNDO_MS + 1);
        assertFalse(json(post("/api/items/undo", "ana@bsp.test", ana, "{\"token\":\"" + late.get("undo").getAsString() + "\"}")).get("ok").getAsBoolean(), "after 5 minutes: too late");
        clock.addAndGet(-PanelServer.UNDO_MS - 1);

        // audit: an UNDO the game server turned away with nothing changed (BUSY) may be tried again; then it is spent
        busyOnce.set(true);
        JsonObject again = json(post("/api/items/add", "ana@bsp.test", ana, add));
        String t = again.get("undo").getAsString();
        JsonObject busy = json(post("/api/items/undo", "ana@bsp.test", ana, "{\"token\":\"" + t + "\"}"));
        assertFalse(busy.get("ok").getAsBoolean());
        assertEquals(t, busy.get("undo").getAsString(), "BUSY: the UNDO is kept");
        assertTrue(json(post("/api/items/undo", "ana@bsp.test", ana, "{\"token\":\"" + t + "\"}")).get("ok").getAsBoolean(), "tried again");
        assertFalse(json(post("/api/items/undo", "ana@bsp.test", ana, "{\"token\":\"" + t + "\"}")).get("ok").getAsBoolean(), "then spent");
        // audit: two UNDOs of one change at the same moment: one goes to the game server
        String t2 = json(post("/api/items/add", "ana@bsp.test", ana, add)).get("undo").getAsString();
        int before = asked.size();
        java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<JsonObject>> both = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            both.add(two.submit(() -> {
                go.await();
                return json(post("/api/items/undo", "ana@bsp.test", ana, "{\"token\":\"" + t2 + "\"}"));
            }));
        }
        go.countDown();
        int oks = 0;
        for (java.util.concurrent.Future<JsonObject> f : both) {
            oks += f.get().get("ok").getAsBoolean() ? 1 : 0;
        }
        two.shutdown();
        assertEquals(1, oks, "one of the two undoes it");
        assertEquals(before + 1, asked.size(), "and the game server is asked once");

        asked.clear();
        JsonObject rem = json(post("/api/items/remove", "owner@bsp.test", o, "{\"player\":\"Vexa\",\"area\":\"inventory\",\"slot\":5,\"hash\":\"0123456789ab\",\"count\":16}"));
        assertEquals(2, asked.size(), "ONLINE elsewhere: asked once more on the live server named (any case): " + asked);
        assertEquals("Frostwarf", asked.get(1).get("server").getAsString());
        assertTrue(rem.get("ok").getAsBoolean() && !asked.get(1).has("items"), "an Owner's action needs no power mark");
        assertFalse(json(post("/api/items/add", "owner@bsp.test", o, "{\"player\":\"Vexa\",\"area\":\"inventory\",\"item\":\"no spaces here\",\"count\":1}")).get("ok").getAsBoolean(), "not an item id");
        assertFalse(json(post("/api/items/add", "owner@bsp.test", o, "{\"player\":\"Vexa\",\"area\":\"../x\",\"item\":\"diamond\",\"count\":1}")).get("ok").getAsBoolean(), "not an area");
        assertFalse(json(post("/api/items/remove", "owner@bsp.test", o, "{\"player\":\"Vexa\",\"area\":\"inventory\",\"slot\":5,\"hash\":\"zz\",\"count\":1}")).get("ok").getAsBoolean(), "not a hash");

        assertTrue(json(post("/api/staff/action", "owner@bsp.test", o, "{\"id\":" + anaId + ",\"action\":\"items\",\"on\":false}")).get("ok").getAsBoolean());
        assertEquals(401, post("/api/items/add", "ana@bsp.test", ana, add).statusCode(), "taken back: her logins end too (D428), so nothing is added");
        // audit: a rank change ends the power, so an Admin made a Moderator again starts without it
        assertTrue(json(post("/api/staff/action", "owner@bsp.test", o, "{\"id\":" + anaId + ",\"action\":\"items\",\"on\":true}")).get("ok").getAsBoolean());
        assertTrue(json(post("/api/staff/action", "owner@bsp.test", o, "{\"id\":" + anaId + ",\"action\":\"rank\",\"rank\":\"ADMIN\"}")).get("ok").getAsBoolean());
        assertTrue(json(post("/api/staff/action", "owner@bsp.test", o, "{\"id\":" + anaId + ",\"action\":\"rank\",\"rank\":\"MODERATOR\"}")).get("ok").getAsBoolean());
        assertFalse(store.accountByEmail("ana@bsp.test").orElseThrow().itemsPower, "the power ended with the rank change");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().startsWith("gave ana@bsp.test the items power")), "in the panel's log");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().startsWith("added 64 minecraft:diamond to Vexa's inventory")), "and the change too");
    }

    @Test
    void item_pictures_are_served_to_a_login_only_for_the_export_in_use() throws Exception {
        String o = owner();
        assertFalse(json(get("/api/items/catalog", "owner@bsp.test", o)).get("ok").getAsBoolean(), "none yet: said so");
        byte[] z = ItemPicturesTest.zip(ItemPicturesTest.ITEMS, ItemPicturesTest.NAMES, true, null);
        ItemPicturesTest.put(store, 500, z, com.mrgregles.bsp_staff_panel.items.ItemPictures.sha256(z));
        com.mrgregles.bsp_staff_panel.items.ItemPictures pics = new com.mrgregles.bsp_staff_panel.items.ItemPictures(store);
        pics.refresh();
        server.withPictures(pics);
        JsonObject ex = json(get("/api/items/exports", "owner@bsp.test", o));
        assertEquals(500, ex.getAsJsonObject("current").get("id").getAsLong());
        assertEquals("Juniper_K", ex.getAsJsonArray("list").get(0).getAsJsonObject().get("by").getAsString());
        HttpResponse<String> cat = get("/api/items/catalog?v=500", "owner@bsp.test", o);
        assertTrue(json(cat).getAsJsonObject("catalog").getAsJsonArray("items").size() == 2);
        assertTrue(cat.headers().firstValue("Cache-Control").orElse("").contains("max-age"), "the browser keeps it");
        HttpResponse<byte[]> sheet = http.send(java.net.http.HttpRequest.newBuilder(uri("/api/items/sheet/500/0.png")).header("Cf-Access-Jwt-Assertion", "good:owner@bsp.test")
                .header("Cookie", PanelServer.SECURE_COOKIE + "=" + o).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, sheet.statusCode());
        assertEquals("image/png", sheet.headers().firstValue("Content-Type").orElse(""));
        assertEquals(ItemPicturesTest.PNG.length, sheet.body().length);
        assertEquals(404, get("/api/items/sheet/499/0.png", "owner@bsp.test", o).statusCode(), "another export's sheet: not found");
        assertEquals(404, get("/api/items/sheet/500/7.png", "owner@bsp.test", o).statusCode());
        assertEquals(401, get("/api/items/sheet/500/0.png", "owner@bsp.test", null).statusCode(), "a login is needed");
        assertEquals(401, get("/api/items/catalog", "owner@bsp.test", null).statusCode());
    }

    @Test
    void a_graves_items_are_read_for_any_rank_where_the_graves_are() throws Exception {
        String o = owner();
        java.util.List<JsonObject> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.mrgregles.bsp_staff_panel.act.Actions[] g = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        g[0] = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            asked.add(unsign(payload));
            String reply = "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"\",\"data\":{\"xp\":23,\"areas\":[{\"id\":\"grave\",\"size\":1,\"slots\":[{\"i\":0,\"id\":\"minecraft:diamond\",\"n\":5}]}]}}";
            new Thread(() -> g[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, reply)))).start();
        }, clock::get, 2);
        server.withActions(g[0]);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"graves\":true,\"players\":[]}");
        store.players.put("22222222-3333-4444-5555-666666666666", "Old_Steve");
        JsonObject r = json(post("/api/grave/items", "owner@bsp.test", o, "{\"player\":\"Old_Steve\",\"grave\":\"3f9a2c1b\"}"));
        assertTrue(r.get("ok").getAsBoolean() && r.getAsJsonObject("items").get("xp").getAsInt() == 23, r.toString());
        assertEquals("grave_items", asked.get(0).get("action").getAsString());
        assertEquals("Moongrove", asked.get(0).get("server").getAsString(), "asked of a server whose BSP-InventorySync answers about graves");
        assertFalse(json(post("/api/grave/items", "owner@bsp.test", o, "{\"player\":\"Old_Steve\",\"grave\":\"../x\"}")).get("ok").getAsBoolean(), "not a grave id");
        assertEquals(1, asked.size());
        assertEquals(401, post("/api/grave/items", "owner@bsp.test", null, "{\"player\":\"Old_Steve\",\"grave\":\"3f9a2c1b\"}").statusCode());
    }

    @Test
    void players_are_moved_from_where_they_are_to_spawns_warps_players_and_coordinates() throws Exception {
        String o = owner();
        java.util.List<JsonObject> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.mrgregles.bsp_staff_panel.act.Actions[] g = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        g[0] = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            JsonObject a = unsign(payload);
            asked.add(a);
            String reply = a.get("action").getAsString().equals("tp_places")
                    ? "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"\",\"data\":{\"warps\":[{\"name\":\"mine\",\"server\":\"FROSTWARF\",\"world\":\"minecraft:overworld\",\"x\":1500,\"y\":40,\"z\":-600}],"
                    + "\"spawns\":[{\"server\":\"FROSTWARF\",\"world\":\"minecraft:overworld\",\"x\":0.5,\"y\":70,\"z\":0.5}]}}"
                    : "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Moved.\"}";
            new Thread(() -> g[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, reply)))).start();
        }, clock::get, 2);
        server.withActions(g[0]);
        String vexa = "11111111-2222-3333-4444-555555555555", kes = "33333333-2222-3333-4444-555555555555", mossy = "44444444-2222-3333-4444-555555555555";
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"tp\":true,\"tpName\":\"Moongrove\",\"players\":[{\"id\":\"" + vexa
                + "\",\"name\":\"Vexa\",\"dim\":\"minecraft:overworld\",\"x\":-2210,\"y\":12,\"z\":905},{\"id\":\"" + mossy + "\",\"name\":\"Mossy_Fen\",\"arriving\":true,\"dim\":\"minecraft:overworld\",\"x\":0,\"y\":0,\"z\":0}]}");
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":1,\"tp\":true,\"tpName\":\"FROSTWARF\",\"players\":[{\"id\":\"" + kes
                + "\",\"name\":\"Kestrel_88\",\"dim\":\"minecraft:the_nether\",\"x\":176,\"y\":33,\"z\":-49},{\"id\":\"55555555-2222-3333-4444-555555555555\",\"name\":\"Mrgregles\",\"dim\":\"minecraft:overworld\",\"x\":5,\"y\":64,\"z\":5}]}");

        JsonObject places = json(post("/api/tp/places", "owner@bsp.test", o, "{}"));
        assertTrue(places.get("ok").getAsBoolean(), places.toString());
        assertEquals("Frostwarf", places.getAsJsonArray("spawns").get(0).getAsJsonObject().get("server").getAsString(), "spawns by the panel's server ids");
        json(post("/api/tp/places", "owner@bsp.test", o, "{}"));
        assertEquals(1, asked.stream().filter(x -> x.get("action").getAsString().equals("tp_places")).count(), "kept 30 seconds");

        asked.clear();
        assertTrue(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"warp\",\"warp\":\"mine\"}")).get("ok").getAsBoolean());
        JsonObject w = asked.get(0);
        assertEquals("Moongrove", w.get("server").getAsString(), "sent to the server of the one who moves");
        assertEquals("FROSTWARF", w.getAsJsonObject("to").get("server").getAsString(), "the warp's server by BSP-Teleport's name");
        assertEquals("warp mine", w.getAsJsonObject("to").get("what").getAsString());
        assertTrue(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"spawn\",\"server\":\"frostwarf\"}")).get("ok").getAsBoolean(), "a spawn, any case");
        assertEquals(70, asked.get(1).getAsJsonObject("to").get("y").getAsInt());
        assertTrue(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"player\",\"target\":\"" + kes + "\"}")).get("ok").getAsBoolean());
        JsonObject t = asked.get(2);
        assertEquals(kes, t.get("target").getAsString(), "a player to go to: named, for their own server to read where they stand");
        assertEquals("minecraft:the_nether", t.getAsJsonObject("to").get("world").getAsString());
        assertEquals("FROSTWARF", t.getAsJsonObject("to").get("server").getAsString());
        assertTrue(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"coords\",\"server\":\"Moongrove\",\"world\":\"minecraft:overworld\",\"x\":\"-2240\",\"z\":\"930\",\"ground\":true}")).get("ok").getAsBoolean());
        assertEquals(-2239.5, asked.get(3).getAsJsonObject("to").get("x").getAsDouble(), "the middle of the block");
        assertTrue(asked.get(3).getAsJsonObject("to").get("ground").getAsBoolean());
        assertFalse(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"coords\",\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":\"1\",\"z\":\"1\",\"ground\":true}")).get("ok").getAsBoolean(),
                "on the ground on another server: refused (D349)");
        assertFalse(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"coords\",\"server\":\"Moongrove\",\"world\":\"minecraft:overworld\",\"x\":\"a\",\"y\":\"64\",\"z\":\"1\"}")).get("ok").getAsBoolean(),
                "not numbers");
        assertFalse(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + mossy + "\",\"kind\":\"warp\",\"warp\":\"mine\"}")).get("ok").getAsBoolean(), "arriving: refused (D366)");
        int before = asked.size();
        assertTrue(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"goto\"}")).get("ok").getAsBoolean(), "GO TO THEM: the Owner's own account is in game");
        JsonObject go = asked.get(before);
        assertEquals("Frostwarf", go.get("server").getAsString(), "the Owner (on Frostwarf) moves");
        assertEquals(vexa, go.get("target").getAsString());
        assertTrue(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"" + vexa + "\",\"kind\":\"bring\"}")).get("ok").getAsBoolean());
        assertEquals("Moongrove", asked.get(before + 1).get("server").getAsString(), "BRING THEM TO ME: Vexa moves, to the Owner");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().startsWith("moved Vexa to warp mine")), "in the panel's log");
        assertFalse(json(post("/api/tp", "owner@bsp.test", o, "{\"player\":\"nobody\",\"kind\":\"warp\",\"warp\":\"mine\"}")).get("ok").getAsBoolean());
    }

    @Test
    void the_market_and_lottery_are_read_by_everyone_drawn_by_admins_and_every_page_sees_a_draw() throws Exception {
        String o = owner();
        JsonObject kipLink = json(post("/api/staff/add", "owner@bsp.test", o, "{\"email\":\"kip@bsp.test\",\"mcName\":\"Kip\",\"rank\":\"MODERATOR\"}"));
        String kt = kipLink.get("link").getAsString().substring((URL + "/setup#").length());
        String ks = json(post("/api/setup/info", "kip@bsp.test", null, "{\"token\":\"" + kt + "\"}")).get("secret").getAsString();
        String kip = cookieOf(post("/api/setup", "kip@bsp.test", null,
                "{\"token\":\"" + kt + "\",\"secret\":\"" + ks + "\",\"code\":\"" + code(ks) + "\",\"password\":\"" + PW + "\"}"));
        java.util.List<JsonObject> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> marketReply = new java.util.concurrent.atomic.AtomicReference<>(
                "\"ok\":true,\"message\":\"2 lots, 1 orders\",\"data\":{\"lotsReadMs\":5,\"ordersReadMs\":5,\"down\":false,\"cut\":false,\"lots\":[{\"id\":\"lot-1\",\"sellerName\":\"Vexa\",\"price\":12500,\"items\":[]}],\"orders\":[]}");
        java.util.concurrent.atomic.AtomicReference<String> wins = new java.util.concurrent.atomic.AtomicReference<>("[{\"name\":\"Mossy_Fen\",\"pieces\":11800,\"at\":1}]");
        com.mrgregles.bsp_staff_panel.act.Actions[] g = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        g[0] = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            JsonObject a = unsign(payload);
            asked.add(a);
            String what = a.get("action").getAsString();
            String reply = "{\"id\":\"" + uid + "\"," + switch (what) {
                case "market_read" -> marketReply.get();
                case "lottery_read" -> "\"ok\":true,\"message\":\"3 in\",\"data\":{\"pot\":12400,\"entrants\":3,\"phase\":\"IDLE\",\"wins\":" + wins.get() + ",\"names\":[\"Glimmer\",\"Kip\",\"Vexa\"]}";
                default -> "\"ok\":true,\"message\":\"Drawn on " + a.get("server").getAsString() + ".\",\"data\":{\"result\":\"ok\",\"server\":\"" + a.get("server").getAsString() + "\"}";
            } + "}";
            new Thread(() -> g[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, reply)))).start();
        }, clock::get, 2);
        server.withActions(g[0]);
        com.mrgregles.bsp_staff_panel.market.MarketDesk desk = new com.mrgregles.bsp_staff_panel.market.MarketDesk(feed, () -> g[0], clock::get).runInline();
        server.withMarket(desk);
        java.util.List<JsonObject> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        desk.onEvent(events::add);

        JsonObject none = json(post("/api/market", "kip@bsp.test", kip, "{}"));
        assertFalse(none.get("ok").getAsBoolean(), "no server with BSP-Core 1.16: said so");
        clock.addAndGet(com.mrgregles.bsp_staff_panel.market.MarketDesk.FAILED_MS + 1000); // that failure is remembered for a few seconds (audit)
        java.util.function.Supplier<String> busy = () -> "{\"v\":1,\"server\":\"Frostwarf\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":false,\"lottery\":\"IDLE\",\"players\":[{\"id\":\"a1\",\"name\":\"A\"},{\"id\":\"a2\",\"name\":\"B\"},{\"id\":\"a3\",\"name\":\"C\"}]}";
        feed.accept("Frostwarf", clock.get(), busy.get());
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"IDLE\",\"players\":[{\"id\":\"b1\",\"name\":\"D\"}]}");
        feed.accept("Stormfall", clock.get(), "{\"v\":1,\"server\":\"Stormfall\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"IDLE\",\"players\":[{\"id\":\"c1\",\"name\":\"E\"},{\"id\":\"c2\",\"name\":\"F\"}]}");
        feed.accept("Sanctuary", clock.get(), "{\"v\":1,\"server\":\"Sanctuary\",\"at\":1,\"players\":[{\"id\":\"d1\",\"name\":\"G\"},{\"id\":\"d2\",\"name\":\"H\"},{\"id\":\"d3\",\"name\":\"I\"},{\"id\":\"d4\",\"name\":\"J\"}]}");
        assertEquals(java.util.List.of("Stormfall", "Moongrove", "Frostwarf"), feed.liveWithMarket(), "the market's servers: database connected first, then the busiest; none without BSP-Core 1.16");

        JsonObject m = json(post("/api/market", "kip@bsp.test", kip, "{}"));
        assertTrue(m.get("ok").getAsBoolean() && m.getAsJsonArray("lots").size() == 1, "a Moderator reads the market: " + m);
        assertEquals("Stormfall", m.get("server").getAsString());
        json(post("/api/market", "owner@bsp.test", o, "{}"));
        assertEquals(1, asked.stream().filter(x -> x.get("action").getAsString().equals("market_read")).count(), "kept 30 seconds for every page (D401)");
        json(post("/api/market", "kip@bsp.test", kip, "{\"refresh\":true}"));
        JsonObject again = asked.get(asked.size() - 1);
        assertTrue(again.get("action").getAsString().equals("market_read") && again.get("refresh").getAsBoolean(), "REFRESH asks again at once, BSP-Core reading first");
        clock.addAndGet(31_000);
        feed.accept("Stormfall", clock.get(), "{\"v\":1,\"server\":\"Stormfall\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"IDLE\",\"players\":[]}");
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"IDLE\",\"players\":[]}");
        feed.accept("Frostwarf", clock.get(), busy.get());
        marketReply.set("\"ok\":false,\"message\":\"The market cannot be read: BSP-Core's database is not connected on Moongrove.\",\"data\":{\"lotsReadMs\":0,\"ordersReadMs\":0,\"down\":true,\"cut\":false,\"lots\":[],\"orders\":[]}");
        JsonObject down = json(post("/api/market", "kip@bsp.test", kip, "{}"));
        assertTrue(!down.get("ok").getAsBoolean() && down.get("down").getAsBoolean() && down.get("message").getAsString().contains("cannot be read"),
                "the database down: said so, never an empty market (D414): " + down);

        JsonObject lot = json(post("/api/lottery", "kip@bsp.test", kip, "{}"));
        assertTrue(lot.get("ok").getAsBoolean() && lot.get("pot").getAsLong() == 12400 && lot.getAsJsonArray("names").size() == 3 && !lot.has("draw"), "a Moderator reads the lottery: " + lot);

        int before = asked.size();
        HttpResponse<String> modDraw = post("/api/lottery/draw", "kip@bsp.test", kip, "{}");
        assertEquals(403, modDraw.statusCode(), "a Moderator cannot draw (D409)");
        assertEquals(before, asked.size(), "nothing sent");
        JsonObject drawn = json(post("/api/lottery/draw", "owner@bsp.test", o, "{}"));
        assertTrue(drawn.get("ok").getAsBoolean() && drawn.get("result").getAsString().equals("ok"), drawn.toString());
        JsonObject d = asked.get(asked.size() - 1);
        assertEquals("lottery_draw", d.get("action").getAsString());
        assertEquals("Moongrove", d.get("server").getAsString(), "the busiest server whose database is connected draws (D410)");
        assertEquals("OWNER", d.get("rank").getAsString());
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().equals("drew the lottery on Moongrove")), "in the panel's log");

        clock.addAndGet(1000); // a newer snapshot (the feed keeps only newer ones)
        long since = clock.get();
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"COUNTING\",\"lotterySince\":" + since + ",\"players\":[]}");
        assertEquals("COUNTING", feed.json().getAsJsonObject("lottery").get("phase").getAsString(), "every page's feed says a draw is counting (D411)");
        desk.tick();
        desk.tick();
        assertEquals(1, events.size(), "a draw beginning is told once");
        assertEquals("counting", events.get(0).get("kind").getAsString());
        assertEquals("Mrgregles", events.get(0).get("by").getAsString(), "drawn from the panel: by whom");
        assertEquals(since, events.get(0).get("since").getAsLong());
        assertTrue(json(post("/api/lottery", "kip@bsp.test", kip, "{}")).has("draw"), "the Lottery page hears of the draw too");
        wins.set("[{\"name\":\"Kestrel_88\",\"pieces\":12400,\"at\":" + (since + 6000) + "},{\"name\":\"Mossy_Fen\",\"pieces\":11800,\"at\":1}]");
        clock.addAndGet(7000);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"IDLE\",\"players\":[]}");
        desk.tick();
        assertEquals(2, events.size(), events.toString());
        JsonObject won = events.get(1);
        assertTrue(won.get("kind").getAsString().equals("won") && won.get("name").getAsString().equals("Kestrel_88") && won.get("pieces").getAsLong() == 12400
                && won.get("by").getAsString().equals("Mrgregles"), "the winner, to every page, with who drew: " + won);

        clock.addAndGet(200_000);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"COUNTING\",\"lotterySince\":" + clock.get() + ",\"players\":[]}");
        desk.tick();
        assertFalse(events.get(2).has("by"), "a draw started in game: no panel name");
        clock.addAndGet(7000);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"IDLE\",\"players\":[]}");
        desk.tick();
        assertEquals("ended", events.get(3).get("kind").getAsString(), "no newer win: it ended without one (a failed payout)");

        clock.addAndGet(1000);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"lottery\":\"COUNTING\",\"lotterySince\":"
                + (clock.get() - 200_000) + ",\"players\":[]}");
        assertFalse(feed.json().has("lottery"), "a server still counting long after its draw began missed the end: it never blocks DRAW (audit)");
        desk.tick();
        assertEquals(4, events.size(), "and no event for it");
    }

    @Test
    void a_game_server_that_does_not_answer_is_asked_once_not_by_every_page_and_the_busiest_draws() throws Exception {
        String o = owner();
        java.util.List<JsonObject> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.mrgregles.bsp_staff_panel.act.Actions silent = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> asked.add(unsign(payload)), clock::get, 1);
        server.withActions(silent);
        com.mrgregles.bsp_staff_panel.market.MarketDesk desk = new com.mrgregles.bsp_staff_panel.market.MarketDesk(feed, () -> silent, clock::get).runInline();
        server.withMarket(desk);
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"players\":[{\"id\":\"b1\",\"name\":\"D\"}]}");
        feed.accept("Stormfall", clock.get(), "{\"v\":1,\"server\":\"Stormfall\",\"at\":" + clock.get() + ",\"market\":true,\"lotteryUp\":true,\"players\":[{\"id\":\"c1\",\"name\":\"E\"},{\"id\":\"c2\",\"name\":\"F\"}]}");
        JsonObject first = json(post("/api/lottery", "owner@bsp.test", o, "{}"));
        assertFalse(first.get("ok").getAsBoolean(), "no answer: said so");
        long t0 = System.nanoTime();
        JsonObject second = json(post("/api/lottery", "owner@bsp.test", o, "{}"));
        assertTrue(System.nanoTime() - t0 < 500_000_000L, "the failure is remembered: the next page does not wait again");
        assertEquals(first.get("message").getAsString(), second.get("message").getAsString());
        assertEquals(1, asked.stream().filter(x -> x.get("action").getAsString().equals("lottery_read")).count(), "asked once");
        json(post("/api/market", "owner@bsp.test", o, "{}"));
        json(post("/api/market", "owner@bsp.test", o, "{}"));
        assertEquals(1, asked.stream().filter(x -> x.get("action").getAsString().equals("market_read")).count(), "the market too");
        json(post("/api/lottery/draw", "owner@bsp.test", o, "{}"));
        JsonObject d = asked.get(asked.size() - 1);
        assertEquals("lottery_draw", d.get("action").getAsString());
        assertEquals("Stormfall", d.get("server").getAsString(), "the busiest server whose database is connected draws (D410)");
    }

    @Test
    void bans_are_for_admins_need_the_name_typed_and_staff_are_banned_by_the_owner_alone() throws Exception {
        String o = owner();
        JsonObject added = json(post("/api/staff/add", "owner@bsp.test", o, "{\"email\":\"saffi@bsp.test\",\"mcName\":\"Saffi\",\"rank\":\"ADMIN\"}"));
        String token = added.get("link").getAsString().substring((URL + "/setup#").length());
        String secret = json(post("/api/setup/info", "saffi@bsp.test", null, "{\"token\":\"" + token + "\"}")).get("secret").getAsString();
        String saffi = cookieOf(post("/api/setup", "saffi@bsp.test", null,
                "{\"token\":\"" + token + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code(secret) + "\",\"password\":\"" + PW + "\"}"));
        JsonObject kipLink = json(post("/api/staff/add", "owner@bsp.test", o, "{\"email\":\"kip@bsp.test\",\"mcName\":\"Kip\",\"rank\":\"MODERATOR\"}"));
        String kt = kipLink.get("link").getAsString().substring((URL + "/setup#").length());
        String ks = json(post("/api/setup/info", "kip@bsp.test", null, "{\"token\":\"" + kt + "\"}")).get("secret").getAsString();
        String kip = cookieOf(post("/api/setup", "kip@bsp.test", null,
                "{\"token\":\"" + kt + "\",\"secret\":\"" + ks + "\",\"code\":\"" + code(ks) + "\",\"password\":\"" + PW + "\"}"));
        java.util.List<JsonObject> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.mrgregles.bsp_staff_panel.act.Actions[] g = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        g[0] = new com.mrgregles.bsp_staff_panel.act.Actions(ActionsTest.KEY, (uid, at, kind, payload) -> {
            asked.add(unsign(payload));
            String reply = "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Done.\"}";
            new Thread(() -> g[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(ActionsTest.KEY, reply)))).start();
        }, clock::get, 2);
        server.withActions(g[0]);
        String vexa = "11111111-2222-3333-4444-555555555555";
        feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"players\":[{\"id\":\"" + vexa + "\",\"name\":\"Vexa\"},{\"id\":\"77777777-2222-3333-4444-555555555555\",\"name\":\"Helper_Bo\",\"role\":\"Helper\"}]}");
        feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":1,\"players\":[]}");
        store.players.put("88888888-2222-3333-4444-555555555555", "Kip");
        store.players.put("99999999-2222-3333-4444-555555555555", "Mrgregles");

        String ban = "{\"player\":\"Vexa\",\"length\":\"7d\",\"reason\":\"X-ray\",\"note\":\"two reports\",\"confirm\":\"vexa\"}";
        assertEquals(403, post("/api/ban", "kip@bsp.test", kip, ban).statusCode(), "a Moderator cannot ban (D370)");
        assertEquals(403, post("/api/ban/lift", "kip@bsp.test", kip, "{\"ban\":1}").statusCode(), "nor lift");
        JsonObject wrong = json(post("/api/ban", "saffi@bsp.test", saffi, ban.replace("\"confirm\":\"vexa\"", "\"confirm\":\"Vex\"")));
        assertFalse(wrong.get("ok").getAsBoolean(), "the name typed wrong: refused (D368)");
        assertTrue(asked.isEmpty());
        JsonObject r = json(post("/api/ban", "saffi@bsp.test", saffi, ban));
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        JsonObject sent = asked.get(0);
        assertEquals("ban", sent.get("action").getAsString());
        assertEquals("Moongrove", sent.get("server").getAsString(), "to the server the player is on");
        assertEquals(7 * 86_400_000L, sent.get("lengthMs").getAsLong());
        assertEquals("two reports", sent.get("note").getAsString());
        assertFalse(json(post("/api/ban", "saffi@bsp.test", saffi, "{\"player\":\"Kip\",\"length\":\"1h\",\"reason\":\"x\",\"confirm\":\"Kip\"}")).get("ok").getAsBoolean(),
                "an Admin cannot ban a staff member with a panel account (D355)");
        assertFalse(json(post("/api/ban", "saffi@bsp.test", saffi, "{\"player\":\"Helper_Bo\",\"length\":\"1h\",\"reason\":\"x\",\"confirm\":\"Helper_Bo\"}")).get("ok").getAsBoolean(),
                "nor one with a staff role in game");
        assertTrue(json(post("/api/ban", "owner@bsp.test", o, "{\"player\":\"Kip\",\"length\":\"custom\",\"days\":\"2\",\"hours\":\"6\",\"reason\":\"x\",\"confirm\":\"Kip\"}")).get("ok").getAsBoolean(),
                "the Owner can");
        assertEquals(2 * 86_400_000L + 6 * 3_600_000L, asked.get(asked.size() - 1).get("lengthMs").getAsLong(), "custom: days and hours");
        assertEquals("Frostwarf", asked.get(asked.size() - 1).get("server").getAsString(), "an offline player: any server sending to the panel");
        assertFalse(json(post("/api/ban", "owner@bsp.test", o, "{\"player\":\"Mrgregles\",\"length\":\"perm\",\"reason\":\"x\",\"confirm\":\"Mrgregles\"}")).get("ok").getAsBoolean(), "nobody bans the Owner");
        assertFalse(json(post("/api/ban", "owner@bsp.test", o, "{\"player\":\"Vexa\",\"length\":\"custom\",\"days\":\"0\",\"hours\":\"0\",\"reason\":\"x\",\"confirm\":\"Vexa\"}")).get("ok").getAsBoolean(), "custom under an hour");

        long now = clock.get();
        store.bans.add(new PanelStore.Ban(1, vexa, "Vexa", "X-ray", "two reports", "Saffi", now - 1000, now + 86_400_000L, 0, "", 0));
        store.bans.add(new PanelStore.Ban(2, vexa, "Vexa", "Griefing", "", "Saffi", now - 90_000_000L, now - 1000, 0, "", 0));
        store.bans.add(new PanelStore.Ban(3, "aaaaaaaa-2222-3333-4444-555555555555", "Spamz", "Ads", "", "Saffi", now - 5000, 0, now - 10, "Saffi", 0));
        assertEquals(1, json(post("/api/bans", "owner@bsp.test", o, "{\"which\":\"active\",\"q\":\"\"}")).getAsJsonArray("bans").size());
        assertEquals(2, json(post("/api/bans", "owner@bsp.test", o, "{\"which\":\"past\",\"q\":\"\"}")).getAsJsonArray("bans").size(), "ended and lifted");
        assertEquals(1, json(post("/api/bans", "owner@bsp.test", o, "{\"which\":\"past\",\"q\":\"sp\"}")).getAsJsonArray("bans").size(), "searched by the start of the name");
        assertEquals(2, json(post("/api/ban/history", "owner@bsp.test", o, "{\"player\":\"Vexa\"}")).getAsJsonArray("bans").size(), "a player's bans");
        assertTrue(json(post("/api/ban/edit", "saffi@bsp.test", saffi, "{\"ban\":1,\"length\":\"keep\",\"reason\":\"X-ray, twice\",\"note\":\"\"}")).get("ok").getAsBoolean());
        assertEquals(-1, asked.get(asked.size() - 1).get("lengthMs").getAsLong(), "keep the end");
        assertEquals(vexa, asked.get(asked.size() - 1).get("player").getAsString(), "the ban's player, which the game server checks");
        assertTrue(json(post("/api/ban/lift", "saffi@bsp.test", saffi, "{\"ban\":1}")).get("ok").getAsBoolean());
        assertEquals("unban", asked.get(asked.size() - 1).get("action").getAsString());
        // audit: a ban on staff is changed and lifted by the Owner alone, as it was given (D355, D427)
        String kipId = "88888888-2222-3333-4444-555555555555";
        store.bans.add(new PanelStore.Ban(4, kipId, "Kip", "Abuse", "", "Mrgregles", now - 1000, now + 3_600_000L, 0, "", 0));
        int n = asked.size();
        assertFalse(json(post("/api/ban/edit", "saffi@bsp.test", saffi, "{\"ban\":4,\"length\":\"perm\",\"reason\":\"Abuse\",\"note\":\"\"}")).get("ok").getAsBoolean(),
                "an Admin cannot make a staff member's ban longer");
        assertEquals(n, asked.size(), "nothing sent");
        assertTrue(json(post("/api/ban/edit", "owner@bsp.test", o, "{\"ban\":4,\"length\":\"perm\",\"reason\":\"Abuse\",\"note\":\"\"}")).get("ok").getAsBoolean(), "the Owner can");
        int m = asked.size();
        JsonObject adminLift = json(post("/api/ban/lift", "saffi@bsp.test", saffi, "{\"ban\":4}"));
        assertFalse(adminLift.get("ok").getAsBoolean(), "an Admin does not lift a staff member's ban (D427)");
        assertTrue(adminLift.get("message").getAsString().contains("only the Owner lifts"), adminLift.toString());
        assertEquals(m, asked.size(), "nothing sent");
        assertTrue(json(post("/api/ban/lift", "owner@bsp.test", o, "{\"ban\":4}")).get("ok").getAsBoolean(), "the Owner does");
        assertEquals(1, json(post("/api/ban/history", "saffi@bsp.test", saffi, "{\"player\":\"Kip\"}")).get("staff").getAsInt(), "the form knows Kip is staff");
        assertEquals(2, json(post("/api/ban/history", "saffi@bsp.test", saffi, "{\"player\":\"Mrgregles\"}")).get("staff").getAsInt(), "and the Owner");
        assertEquals(0, json(post("/api/ban/history", "saffi@bsp.test", saffi, "{\"player\":\"Vexa\"}")).get("staff").getAsInt(), "and a player");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().startsWith("banned Vexa (7d): X-ray")), "in the panel's log");
    }

    private HttpResponse<String> me(String who, String cookie) throws Exception {
        return get("/api/me", who, cookie);
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

    @Test
    void the_map_is_served_from_bluemaps_folder_behind_the_login_with_its_own_rules() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bluemap-web");
        java.nio.file.Path outside = java.nio.file.Files.createTempFile("secret", ".txt");
        try {
            java.nio.file.Files.writeString(root.resolve("index.html"), "<!doctype html><title>BlueMap</title>");
            java.nio.file.Path tiles = java.nio.file.Files.createDirectories(root.resolve("maps/frostwarf__overworld/tiles/1/x0"));
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
            java.nio.file.Files.write(tiles.resolve("z0.png"), png);
            java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(gz)) {
                z.write("{\"textures\":[]}".getBytes(StandardCharsets.UTF_8));
            }
            java.nio.file.Files.write(root.resolve("maps/frostwarf__overworld/textures.json.gz"), gz.toByteArray());
            java.nio.file.Files.writeString(outside, "panel secrets");
            java.nio.file.Files.createSymbolicLink(root.resolve("maps/leak.txt"), outside);
            String o = owner();
            assertEquals(401, get("/map/", "owner@bsp.test", null).statusCode(), "the panel's login is needed");
            assertEquals(404, get("/map/", "owner@bsp.test", o).statusCode(), "no map set up: said so");
            server.withMap(root);
            assertEquals(303, get("/map", "owner@bsp.test", o).statusCode(), "/map goes to /map/ (BlueMap's paths are relative)");
            HttpResponse<String> page = get("/map/", "owner@bsp.test", o);
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("BlueMap"));
            assertEquals(PanelServer.MAP_CSP, page.headers().firstValue("Content-Security-Policy").orElse(""), "the looser rules on /map/ only (D382)");
            assertEquals("SAMEORIGIN", page.headers().firstValue("X-Frame-Options").orElse(""), "the panel's page may frame it");
            HttpResponse<String> app = get("/", "owner@bsp.test", o);
            assertFalse(app.headers().firstValue("Content-Security-Policy").orElse("").contains("unsafe"), "the panel's own pages keep the strict rules");
            assertEquals("DENY", app.headers().firstValue("X-Frame-Options").orElse(""));
            assertTrue(app.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-src 'self'"), "the Map tab frames /map/ from this panel only (SU-47)");

            HttpResponse<byte[]> tex = http.send(HttpRequest.newBuilder(uri("/map/maps/frostwarf__overworld/textures.json")).header("Cf-Access-Jwt-Assertion", "good:owner@bsp.test")
                    .header("Cookie", PanelServer.SECURE_COOKIE + "=" + o).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, tex.statusCode());
            assertEquals("gzip", tex.headers().firstValue("Content-Encoding").orElse(""), "BlueMap's .gz files go as gzip");
            assertTrue(tex.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            assertArrayEquals(gz.toByteArray(), tex.body());
            HttpResponse<String> tile = get("/map/maps/frostwarf__overworld/tiles/1/x0/z0.png", "owner@bsp.test", o);
            assertEquals(200, tile.statusCode());
            assertEquals("image/png", tile.headers().firstValue("Content-Type").orElse(""));
            String modified = tile.headers().firstValue("Last-Modified").orElse("");
            HttpResponse<String> again = http.send(HttpRequest.newBuilder(uri("/map/maps/frostwarf__overworld/tiles/1/x0/z0.png")).header("Cf-Access-Jwt-Assertion", "good:owner@bsp.test")
                    .header("Cookie", PanelServer.SECURE_COOKIE + "=" + o).header("If-Modified-Since", modified).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(304, again.statusCode(), "unchanged since: not sent again");
            assertEquals(204, get("/map/maps/frostwarf__overworld/tiles/1/x5/z5.png", "owner@bsp.test", o).statusCode(), "a tile not drawn yet: nothing, not an error");
            assertEquals(404, get("/map/maps/frostwarf__overworld/nothing.json", "owner@bsp.test", o).statusCode());
            assertEquals(404, get("/map/maps/leak.txt", "owner@bsp.test", o).statusCode(), "a link out of BlueMap's folder is refused");
            assertEquals(404, get("/map/%2e%2e/" + outside.getFileName(), "owner@bsp.test", o).statusCode(), "no way up out of the folder (aimed at a real file beside it, audit)");
            java.nio.file.Files.writeString(root.resolve("maps/evil.html"), "<script src=x></script>");
            java.nio.file.Files.writeString(root.resolve("maps/evil.js"), "alert(1)");
            assertEquals(404, get("/map/maps/evil.html", "owner@bsp.test", o).statusCode(), "only BlueMap's own page runs at /map/ (audit)");
            assertEquals(404, get("/map/maps/evil.js", "owner@bsp.test", o).statusCode(), "and scripts only from its assets");
            HttpResponse<Void> head = http.send(HttpRequest.newBuilder(uri("/map/maps/frostwarf__overworld/tiles/1/x0/z0.png")).method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("Cf-Access-Jwt-Assertion", "good:owner@bsp.test").header("Cookie", PanelServer.SECURE_COOKIE + "=" + o).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(200, head.statusCode(), "HEAD answers too");
            assertEquals(404, get("/map/maps/frostwarf__overworld/live/sse", "owner@bsp.test", o).statusCode(), "no live stream: BlueMap's page reads players.json instead");
            // SU-46: the live data is the panel's own, from its feed
            java.nio.file.Files.createDirectories(root.resolve("maps/frostwarf__overworld/live"));
            java.nio.file.Files.writeString(root.resolve("maps/frostwarf__overworld/live/players.json"), "{\"players\":[{\"name\":\"stale\"}]}");
            feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":1,\"players\":[{\"id\":\"11111111-2222-3333-4444-555555555555\",\"name\":\"Kestrel_88\",\"dim\":\"minecraft:overworld\",\"x\":1412,\"y\":71,\"z\":-388,\"yaw\":30},"
                    + "{\"id\":\"21111111-2222-3333-4444-555555555555\",\"name\":\"Mossy_Fen\",\"dim\":\"minecraft:the_nether\",\"x\":1,\"y\":2,\"z\":3,\"yaw\":0}]}");
            HttpResponse<String> ps = get("/map/maps/frostwarf__overworld/live/players.json", "owner@bsp.test", o);
            assertEquals(200, ps.statusCode());
            assertEquals("no-store", ps.headers().firstValue("Cache-Control").orElse(""));
            JsonArray list = json(ps).getAsJsonArray("players");
            assertEquals(2, list.size(), "from the feed, not BlueMap's file: " + ps.body());
            assertFalse(list.get(0).getAsJsonObject().get("foreign").getAsBoolean());
            assertTrue(list.get(1).getAsJsonObject().get("foreign").getAsBoolean(), "in the Nether: not on the overworld's map");
            HttpResponse<String> marks = get("/map/maps/frostwarf__overworld/live/markers.json", "owner@bsp.test", o);
            assertEquals(200, marks.statusCode());
            assertTrue(json(marks).has("bsp-warps") && json(marks).has("bsp-spawns"), marks.body());
            assertEquals(404, get("/map/maps/frostwarf__overworld/assets/playerheads/31111111-2222-3333-4444-555555555555.png", "owner@bsp.test", o).statusCode(),
                    "no skin known: BlueMap's page draws its own head");

            // logged out: the remembered login is forgotten at once
            post("/api/logout", "owner@bsp.test", o, "{}");
            assertEquals(401, get("/map/", "owner@bsp.test", o).statusCode());
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(root)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
            }
            java.nio.file.Files.deleteIfExists(outside);
        }
    }

    @Test
    void a_map_login_is_believed_only_under_its_own_cloudflare_identity() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bluemap-web");
        try {
            java.nio.file.Files.writeString(root.resolve("index.html"), "<!doctype html><title>BlueMap</title>");
            server.withMap(root);
            String o = owner();
            assertEquals(200, get("/map/", "owner@bsp.test", o).statusCode(), "seen once: remembered for 30 s");
            assertEquals(401, get("/map/", "someone@else.test", o).statusCode(), "the same cookie under another Cloudflare identity: not believed (audit)");
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(root)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
            }
        }
    }
}
