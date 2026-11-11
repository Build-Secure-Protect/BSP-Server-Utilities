package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.chat.Chat;
import com.mrgregles.bsp_staff_panel.crypto.Base32;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.crypto.Totp;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import com.mrgregles.bsp_staff_panel.web.PanelServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chat on the panel (SU-36, D304 to D313): only lines signed with the panel's key, of the chat kind, are read; a player's
 * line only from its own server; each line reaches the pages once whether Redis or the table brought it; older lines,
 * the lines around a search result, search and the 30-day prune; the panel's own lines kept signed; sending to several
 * servers as one action; and over HTTP, [Server] for Admins and Owners only, the network reached server by server.
 */
class ChatTest {
    private static final byte[] KEY = ActionsTest.KEY;

    private static String line(String uid, long at, String server, String kind, String name, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("v", 1);
        o.addProperty("uid", uid);
        o.addProperty("at", at);
        o.addProperty("server", server);
        o.addProperty("kind", kind);
        o.addProperty("player", "00000000-0000-0000-0000-000000000009");
        o.addProperty("name", name);
        o.addProperty("text", text);
        return Signed.seal(KEY, o.toString());
    }

    @Test
    void only_signed_chat_lines_are_read() {
        Chat c = new Chat(new MemoryStore(), KEY, () -> 1000);
        JsonObject o = c.read(line("u1", 5, "Moongrove", "player", "Vexa", "anyone selling diamonds?"));
        assertNotNull(o);
        assertEquals("Vexa", o.get("name").getAsString());
        assertEquals("anyone selling diamonds?", o.get("text").getAsString());
        assertEquals("Moongrove", o.get("server").getAsString());
        byte[] other = KEY.clone();
        other[0] ^= 1;
        String foreign = Signed.seal(other, line("u2", 5, "Moongrove", "player", "Vexa", "hi").substring(65));
        assertNull(c.read(foreign), "another key: dropped");
        assertNull(c.read(Signed.seal(KEY, "{\"v\":1,\"server\":\"Moongrove\",\"at\":5,\"players\":[]}")), "a snapshot signed with the same key is not a chat line");
        assertNull(c.read(Signed.seal(KEY, "{\"id\":\"x\",\"ok\":true,\"message\":\"done\"}")), "nor an answer");
        assertNull(c.read(line("u3", 5, "Moongrove", "admin", "Vexa", "hi")), "an unknown kind");
        assertNull(c.read(line("u4", 5, "Moongrove", "player", "Vexa", "x".repeat(257))), "over 256 characters");
        assertNull(c.read(line("u5", 5, "Moongrove", "player", "Vexa", "")), "empty");
    }

    @Test
    void each_line_reaches_the_pages_once_from_redis_or_the_table() throws Exception {
        MemoryStore store = new MemoryStore();
        Chat c = new Chat(store, KEY, () -> 1000);
        List<String> heard = new CopyOnWriteArrayList<>();
        c.onLine(o -> heard.add(o.get("text").getAsString()));
        store.insertChat("old", 1, "Frostwarf", "Kestrel_88", "before the panel started", line("old", 1, "Frostwarf", "player", "Kestrel_88", "before the panel started"));
        c.poll();
        assertTrue(heard.isEmpty(), "the first read only notes where the table ends");
        String l = line("u1", 10, "Moongrove", "player", "Vexa", "hello");
        assertTrue(c.acceptMessage("m1\n10\nMoongrove\nchat\n" + l));
        assertEquals(List.of("hello"), heard, "live from Redis");
        store.insertChat("u1", 10, "Moongrove", "Vexa", "hello", l);
        store.insertChat("u2", 11, "Stormfall", "Pixl", "help", line("u2", 11, "Stormfall", "player", "Pixl", "help"));
        store.insertChat("u3", 12, "Stormfall", "Pixl", "forged", Signed.seal(new byte[32], "{}"));
        c.poll();
        assertEquals(List.of("hello", "help"), heard, "the table brings what Redis missed, never a line twice, never an unsigned one");
        assertTrue(c.acceptMessage("m2\n11\nStormfall\nchat\n" + line("u9", 11, "Moongrove", "player", "Vexa", "carried by another server")));
        assertEquals(3, heard.size(), "signed with the key is the proof, whichever server carried it (one key for all, audit)");
        assertFalse(c.acceptMessage("m3\n11\nStormfall\nsnapshot\n{}"), "not chat: left for the feed");
    }

    @Test
    void older_lines_around_a_time_search_and_the_thirty_day_prune() throws Exception {
        MemoryStore store = new MemoryStore();
        long day = 24L * 60 * 60 * 1000, now = 40 * day;
        Chat c = new Chat(store, KEY, () -> now);
        for (int i = 0; i < 150; i++) {
            String name = i % 2 == 0 ? "Vexa" : "Pixl", text = i % 10 == 0 ? "Diamonds " + i : "line " + i;
            store.insertChat("u" + i, now - (150 - i) * 60_000L, i % 3 == 0 ? "Moongrove" : "Stormfall", name, text, line("u" + i, now - (150 - i) * 60_000L, "Moongrove", "player", name, text));
        }
        store.insertChat("ancient", now - 31 * day, "Moongrove", "Vexa", "diamonds long ago", line("ancient", now - 31 * day, "Moongrove", "player", "Vexa", "diamonds long ago"));
        List<JsonObject> newest = c.beforeLines(Long.MAX_VALUE);
        assertTrue(c.before(Long.MAX_VALUE).more(), "a full page: there may be more");
        assertEquals(Chat.PAGE, newest.size());
        assertEquals("line 149", newest.get(newest.size() - 1).get("text").getAsString(), "oldest first, the newest at the bottom (D311)");
        List<JsonObject> older = c.beforeLines(newest.get(0).get("at").getAsLong() - 1);
        assertEquals("line 49", older.get(older.size() - 1).get("text").getAsString(), "scrolling up: the lines before");
        List<JsonObject> around = c.around(now - 100 * 60_000L);
        assertEquals(40, around.size());
        assertEquals("Diamonds 50", around.get(20).get("text").getAsString(), "the line found, with 20 before and 20 after (D312)");
        List<JsonObject> found = c.search("diamonds", "vexa", "", 7);
        assertEquals(15, found.size(), "any case, that player, the last 7 days only");
        assertEquals("Diamonds 140", found.get(0).get("text").getAsString(), "newest first");
        assertEquals(15, c.search("diamonds", "", "", 30).size(), "30 days back at most: the line 31 days old is not found");
        assertEquals(1, c.prune(), "lines over 30 days old dropped (D263)");
        assertTrue(store.searchChat("long ago", "", "", 0, 10).isEmpty());
    }

    @Test
    void the_panel_s_own_lines_are_kept_signed_and_shown_once() throws Exception {
        MemoryStore store = new MemoryStore();
        Chat c = new Chat(store, KEY, () -> 5000);
        List<JsonObject> heard = new ArrayList<>();
        c.onLine(heard::add);
        c.poll();
        JsonObject o = c.record("server", "network", "Ana_Mod", "Restart in 30 minutes");
        assertEquals("server", o.get("kind").getAsString());
        assertEquals("network", o.get("server").getAsString());
        assertEquals(1, heard.size());
        PanelStore.ChatRow row = store.chatBefore(Long.MAX_VALUE, 1).get(0);
        assertNotNull(Signed.open(KEY, row.line()), "stored signed with the panel's key");
        c.poll();
        assertEquals(1, heard.size(), "not shown again when the table is read");
        assertNull(Chat.clean("§kmagic"));
        assertNull(Chat.clean("bell\u0007"));
        assertNull(Chat.clean("   "));
        assertNull(Chat.clean("x".repeat(257)));
        assertEquals("hello", Chat.clean("  hello "));
    }

    @Test
    void one_line_to_several_servers_counts_as_one_action() {
        List<String> sent = new CopyOnWriteArrayList<>();
        Actions[] a = new Actions[1];
        a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
            String body = new String(java.util.Base64.getUrlDecoder().decode(payload.substring(0, payload.indexOf('.'))), java.nio.charset.StandardCharsets.UTF_8);
            String server = JsonParser.parseString(body).getAsJsonObject().get("server").getAsString();
            sent.add(server);
            if (!server.equals("Stormfall")) { // Stormfall never answers
                new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(KEY, "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Shown on " + server + "\"}")))).start();
            }
        }, System::currentTimeMillis, 1);
        JsonObject f = new JsonObject();
        f.addProperty("action", "say");
        Map<String, Actions.Answer> got = a[0].askAll(List.of("BSP-Spawn", "Moongrove", "Stormfall"), "Ana_Mod", "MODERATOR", f);
        assertEquals(List.of("BSP-Spawn", "Moongrove", "Stormfall"), List.copyOf(got.keySet()));
        assertTrue(got.get("Moongrove").ok());
        assertEquals("Shown on Moongrove", got.get("Moongrove").message());
        assertFalse(got.get("Stormfall").ok(), "no answer in time: named, to RETRY (D313)");
        for (int i = 0; i < Actions.PER_MINUTE - 1; i++) {
            a[0].askAll(List.of("BSP-Spawn"), "Ana_Mod", "MODERATOR", f);
        }
        assertFalse(a[0].ask("BSP-Spawn", "Ana_Mod", "MODERATOR", f).ok(), "the network line counted once: ten in a minute");
    }

    @Test
    void the_chat_table_is_the_same_on_both_sides() throws Exception {
        Path mod = Path.of("..", "src", "main", "java", "com", "mrgregles", "bsp_server_utilities", "storage", "JdbcBackend.java");
        if (!Files.exists(mod)) {
            return; // the panel built on its own
        }
        Matcher m = Pattern.compile("WEB_CHAT_TABLE = ((?:\\s*\\+?\\s*\"[^\"]*\")+);").matcher(Files.readString(mod));
        assertTrue(m.find(), "the game servers' table definition");
        StringBuilder theirs = new StringBuilder();
        Matcher part = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
        while (part.find()) {
            theirs.append(part.group(1));
        }
        assertEquals(PanelStore.WEB_CHAT_TABLE, theirs.toString(), "whichever starts first makes the same table");
    }

    // ------------------------------------------------------------------ over HTTP

    static final String URL = "https://staff.test", PW = "frost-wolf-lantern-9";

    @Test
    void saying_a_line_over_http() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        Auth auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 1000);
        Feed feed = new Feed(clock::get);
        List<String> to = new CopyOnWriteArrayList<>();
        Actions[] a = new Actions[1];
        a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
            String body = new String(java.util.Base64.getUrlDecoder().decode(payload.substring(0, payload.indexOf('.'))), java.nio.charset.StandardCharsets.UTF_8);
            to.add(JsonParser.parseString(body).getAsJsonObject().get("server").getAsString());
            new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(KEY, "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Shown\"}")))).start();
        }, clock::get, 2);
        Chat chat = new Chat(store, KEY, clock::get);
        PanelServer.Gate gate = header -> new AccessJwt.Identity(header.substring(5), Long.MAX_VALUE);
        PanelServer server = new PanelServer("127.0.0.1", 0, URL, gate, auth, store, feed, clock::get).withActions(a[0]).withChat(chat).start();
        HttpClient http = HttpClient.newHttpClient();
        try {
            String owner = setUp(http, server, auth, clock, auth.createOwner("owner@bsp.test", "Mrgregles"), "owner@bsp.test");
            Account me = store.accountByEmail("owner@bsp.test").orElseThrow();
            String mod = setUp(http, server, auth, clock, auth.addStaff(me, "ana@bsp.test", "Ana_Mod", Rank.MODERATOR), "ana@bsp.test");
            for (String s : new String[]{"BSP-Spawn", "Moongrove"}) { // two game servers sending to the panel now
                feed.accept(s, clock.get(), "{\"v\":1,\"server\":\"" + s + "\",\"at\":" + clock.get() + ",\"players\":[]}");
            }

            JsonObject refused = post(http, server, "ana@bsp.test", mod, "{\"voice\":\"server\",\"to\":\"network\",\"text\":\"Restart\"}");
            assertFalse(refused.get("ok").getAsBoolean(), "a Moderator does not speak as [Server] (D307)");
            assertTrue(to.isEmpty());

            JsonObject said = post(http, server, "ana@bsp.test", mod, "{\"voice\":\"staff\",\"to\":\"network\",\"text\":\"hello all\"}");
            assertTrue(said.get("ok").getAsBoolean(), said.toString());
            assertEquals(List.of("BSP-Spawn", "Moongrove"), to.stream().sorted().toList(), "one action to each live server (D313)");
            JsonArray results = said.getAsJsonArray("results");
            assertEquals(2, results.size());
            assertEquals("Ana_Mod", said.getAsJsonObject("line").get("name").getAsString());
            assertEquals(1, store.chatBefore(Long.MAX_VALUE, 10).size(), "kept once for the panel's chat");
        // a line that went nowhere it could be seen: the RETRY that reaches a server keeps it (audit)

            to.clear();
            JsonObject again = post(http, server, "owner@bsp.test", owner, "{\"voice\":\"server\",\"to\":\"moongrove\",\"text\":\"Restart\",\"retry\":true,\"kept\":true}");
            assertTrue(again.get("ok").getAsBoolean());
            assertEquals(List.of("Moongrove"), to, "one server, any case");
            assertEquals(1, store.chatBefore(Long.MAX_VALUE, 10).size(), "a RETRY is not kept again");
            post(http, server, "owner@bsp.test", owner, "{\"voice\":\"server\",\"to\":\"moongrove\",\"text\":\"Restart\",\"retry\":true,\"kept\":false}");
            assertEquals(2, store.chatBefore(Long.MAX_VALUE, 10).size(), "a RETRY of a line nothing kept keeps it once it reached a server (audit)");

            assertFalse(post(http, server, "owner@bsp.test", owner, "{\"voice\":\"staff\",\"to\":\"network\",\"text\":\"§ccolour\"}").get("ok").getAsBoolean(), "no § codes");
            assertFalse(post(http, server, "owner@bsp.test", owner, "{\"voice\":\"Vexa\",\"to\":\"network\",\"text\":\"hi\"}").get("ok").getAsBoolean(), "never another name");
            assertFalse(post(http, server, "owner@bsp.test", owner, "{\"voice\":\"staff\",\"to\":\"Frostwarf\",\"text\":\"hi\"}").get("ok").getAsBoolean(), "a server not sending to the panel");

            HttpResponse<String> lines = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/chat"))
                    .header("Cf-Access-Jwt-Assertion", "good:ana@bsp.test").header("Cookie", PanelServer.SECURE_COOKIE + "=" + mod).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("hello all", JsonParser.parseString(lines.body()).getAsJsonObject().getAsJsonArray("lines").get(0).getAsJsonObject().get("text").getAsString());
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/chat"))
                    .header("Cf-Access-Jwt-Assertion", "good:ana@bsp.test").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "only for a login");
        } finally {
            server.stop();
        }
    }

    static JsonObject post(HttpClient http, PanelServer server, String who, String cookie, String json) throws Exception {
        return JsonParser.parseString(postRaw(http, server, "/api/chat/say", who, cookie, json).body()).getAsJsonObject();
    }

    static HttpResponse<String> postRaw(HttpClient http, PanelServer server, String path, String who, String cookie, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json").header("X-BSP", "1").header("Origin", URL).header("Cf-Access-Jwt-Assertion", "good:" + who);
        if (cookie != null) {
            b.header("Cookie", PanelServer.SECURE_COOKIE + "=" + cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sets up an account through its link over HTTP; returns its session cookie. */
    static String setUp(HttpClient http, PanelServer server, Auth auth, AtomicLong clock, Auth.NewLink link, String email) throws Exception {
        JsonObject info = JsonParser.parseString(postRaw(http, server, "/api/setup/info", email, null, "{\"token\":\"" + link.token() + "\"}").body()).getAsJsonObject();
        String secret = info.get("secret").getAsString();
        String code = Totp.format(Totp.code(Base32.decode(secret), Totp.step(clock.get())));
        HttpResponse<String> done = postRaw(http, server, "/api/setup", email, null,
                "{\"token\":\"" + link.token() + "\",\"secret\":\"" + secret + "\",\"code\":\"" + code + "\",\"password\":\"" + PW + "\"}");
        assertTrue(JsonParser.parseString(done.body()).getAsJsonObject().get("ok").getAsBoolean(), done.body());
        clock.addAndGet(31_000);
        String c = done.headers().firstValue("Set-Cookie").orElse("");
        return c.substring(c.indexOf('=') + 1, c.indexOf(';'));
    }
}
