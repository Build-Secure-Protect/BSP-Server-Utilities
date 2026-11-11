package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.chat.Chat;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.feed.RedisListener;
import com.mrgregles.bsp_staff_panel.items.ItemPictures;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.web.PanelServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit of the whole staff panel (D420 to D424): what was found between the parts, as fixed on the panel's side. */
class WholeAuditTest {
    /**
     * The signature test vector, worked out apart from either side's code (Python's hmac): the same key, text and expected
     * results are checked by the mod's {@code WebAuditGameTests}, so a change to one side alone fails a test (audit).
     */
    static final byte[] GOLDEN_KEY = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    static final String GOLDEN_ACTION_JSON = "{\"v\":1,\"id\":\"golden-1\",\"at\":1700000000000,\"until\":1700000006000,\"by\":\"Ana\",\"rank\":\"ADMIN\",\"server\":\"Frostwarf\",\"action\":\"kick\",\"player\":\"11111111-2222-3333-4444-555555555555\",\"reason\":\"é ok\"}";
    static final String GOLDEN_ACTION = "eyJ2IjoxLCJpZCI6ImdvbGRlbi0xIiwiYXQiOjE3MDAwMDAwMDAwMDAsInVudGlsIjoxNzAwMDAwMDA2MDAwLCJieSI6IkFuYSIsInJhbmsiOiJBRE1JTiIsInNlcnZlciI6IkZyb3N0d2FyZiIsImFjdGlvbiI6ImtpY2siLCJwbGF5ZXIiOiIxMTExMTExMS0yMjIyLTMzMzMtNDQ0NC01NTU1NTU1NTU1NTUiLCJyZWFzb24iOiLDqSBvayJ9.a1f6e8f125af2d8d2853d00decfb7924338a661c587753628284af132279a5e0";
    static final String GOLDEN_ANSWER_JSON = "{\"id\":\"golden-1\",\"ok\":true,\"message\":\"Vexa was kicked from the network.\",\"server\":\"Frostwarf\"}";
    static final String GOLDEN_ANSWER = "403b073d2e98b5457f8025d3641f217095aa41415b8dc70800beb35cd93b9bdb." + GOLDEN_ANSWER_JSON;

    /** RFC 8032's first Ed25519 test key (section 7.1), as the panel keeps it and as a game server's panelKey. */
    static final String RFC_PRIVATE = "MC4CAQAwBQYDK2VwBCIEIJ1hsZ3v/VpguoRK9JLsLMREScVpezJpGXA7rAMcrn9g";
    static final String RFC_PUBLIC = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=";
    /** The golden action signed with that key (Ed25519 signatures are the same every time); the mod's test checks it with RFC_PUBLIC. */
    static final String GOLDEN_PANEL_ACTION = "eyJ2IjoxLCJpZCI6ImdvbGRlbi0xIiwiYXQiOjE3MDAwMDAwMDAwMDAsInVudGlsIjoxNzAwMDAwMDA2MDAwLCJieSI6IkFuYSIsInJhbmsiOiJBRE1JTiIsInNlcnZlciI6IkZyb3N0d2FyZiIsImFjdGlvbiI6ImtpY2siLCJwbGF5ZXIiOiIxMTExMTExMS0yMjIyLTMzMzMtNDQ0NC01NTU1NTU1NTU1NTUiLCJyZWFzb24iOiLDqSBvayJ9.gL6Ij3f_AF4tOyws13_mLVjqM5RWgTetrAG464O0Fb1ykBTsnkc8aKtnyyFmbTSX-jv5VHVwaMk2F9WYlhMUDQ";

    @Test
    void the_panels_own_signature_matches_the_shared_test_vector() throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("Ed25519");
        s.initSign(java.security.KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(RFC_PRIVATE))));
        assertEquals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
                java.util.HexFormat.of().formatHex(s.sign()), "RFC 8032's own signature of the empty message: the key is read as the RFC means");
        com.mrgregles.bsp_staff_panel.crypto.PanelSigner p = new com.mrgregles.bsp_staff_panel.crypto.PanelSigner(Base64.getDecoder().decode(RFC_PRIVATE));
        assertEquals(GOLDEN_PANEL_ACTION, p.sign(GOLDEN_ACTION_JSON));
        String[] pair = com.mrgregles.bsp_staff_panel.crypto.PanelSigner.newPair();
        assertEquals(44, pair[1].length(), "the public key as its 32 raw bytes in Base64, as the game servers read it");
    }

    @Test
    void actions_are_signed_with_the_panels_own_key_when_set() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        Actions a = new Actions(GOLDEN_KEY, (uid, at, kind, payload) -> sent.add(payload), () -> 1L, 1)
                .signWith(new com.mrgregles.bsp_staff_panel.crypto.PanelSigner(Base64.getDecoder().decode(RFC_PRIVATE)));
        JsonObject f = new JsonObject();
        f.addProperty("action", "kick");
        a.ask("Frostwarf", "Ana", "ADMIN", f);
        a.notice("web_notes", new JsonObject());
        assertEquals(2, sent.size());
        for (String p : sent) {
            String sig = p.substring(p.indexOf('.') + 1);
            assertEquals(86, sig.length(), "an Ed25519 signature (64 bytes), never the shared key's HMAC: " + p);
        }
    }

    @Test
    void the_signatures_match_the_shared_test_vector() {
        assertEquals(GOLDEN_ACTION, Actions.sign(GOLDEN_KEY, GOLDEN_ACTION_JSON), "an action signed as the game servers check it");
        assertEquals(GOLDEN_ANSWER, Signed.seal(GOLDEN_KEY, GOLDEN_ANSWER_JSON), "an answer sealed as the game servers seal it");
        assertEquals(GOLDEN_ANSWER_JSON, Signed.open(GOLDEN_KEY, GOLDEN_ANSWER));
    }

    @Test
    void an_action_keeps_its_envelope_carries_its_deadline_and_takes_only_the_asked_servers_answer() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        List<JsonObject> sent = new CopyOnWriteArrayList<>();
        Actions[] a = new Actions[1];
        AtomicReference<String> answerFrom = new AtomicReference<>("Frostwarf");
        a[0] = new Actions(GOLDEN_KEY, (uid, at, kind, payload) -> {
            JsonObject j = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(payload.substring(0, payload.indexOf('.'))), StandardCharsets.UTF_8)).getAsJsonObject();
            sent.add(j);
            String id = j.get("id").getAsString();
            new Thread(() -> {
                a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(GOLDEN_KEY, "{\"id\":\"" + id + "\",\"ok\":true,\"message\":\"forged\",\"server\":\"Moongrove\"}")));
                a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(GOLDEN_KEY, "{\"id\":\"" + id + "\",\"ok\":true,\"message\":\"done\",\"server\":\"" + answerFrom.get() + "\"}")));
            }).start();
        }, clock::get, 2);
        JsonObject fields = new JsonObject();
        fields.addProperty("action", "inv_take");
        fields.addProperty("rank", "OWNER");
        fields.addProperty("by", "Someone_Else");
        fields.addProperty("server", "Moongrove");
        fields.addProperty("until", Long.MAX_VALUE);
        Actions.Answer ans = a[0].ask("Frostwarf", "Ana", "MODERATOR", fields, 5);
        assertTrue(ans.ok() && ans.message().equals("done"), "only the asked server's answer is taken, not another's first: " + ans);
        JsonObject j = sent.get(0);
        assertEquals("MODERATOR", j.get("rank").getAsString(), "an action's fields never replace who asked and their rank (audit)");
        assertEquals("Ana", j.get("by").getAsString());
        assertEquals("Frostwarf", j.get("server").getAsString());
        assertEquals(1_005_000L, j.get("until").getAsLong(), "the deadline is the time plus the wait: past it the game server refuses");

        a[0].refuseWhen(server -> server.equals("Frostwarf") ? "Frostwarf cannot answer the panel." : null);
        Actions.Answer refused = a[0].ask("Frostwarf", "Ana", "MODERATOR", fields);
        assertFalse(refused.ok());
        assertEquals("Frostwarf cannot answer the panel.", refused.message());
        assertEquals(1, sent.size(), "nothing sent to a server that cannot answer");
    }

    @Test
    void an_undo_from_an_answer_is_only_ever_an_items_change() {
        JsonObject bad = JsonParser.parseString("{\"action\":\"ban\",\"rank\":\"OWNER\",\"player\":\"x\"}").getAsJsonObject();
        assertNull(PanelServer.undoFields(bad), "an answer cannot turn UNDO into a ban");
        JsonObject take = JsonParser.parseString("{\"action\":\"inv_take\",\"area\":\"inventory\",\"hash\":\"0123456789ab\",\"count\":3,\"rank\":\"OWNER\",\"server\":\"x\",\"extra\":{}}").getAsJsonObject();
        JsonObject kept = PanelServer.undoFields(take);
        assertEquals(java.util.Set.of("action", "area", "hash", "count"), kept.keySet(), "only the change's own fields: " + kept);
    }

    @Test
    void a_server_is_held_by_its_own_interval_dropped_when_it_stops_and_named_when_it_cannot_answer() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        Feed f = new Feed(clock::get);
        f.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":1,\"every\":10,\"answers\":true,\"players\":[]}");
        f.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":1,\"answers\":false,\"players\":[]}");
        clock.addAndGet(25_000);
        assertEquals(List.of("Frostwarf"), f.live(), "every 10 s: still there after 25 s; the usual 5 s one gone after 20 s");
        assertNull(f.whyNoAnswers("Frostwarf"));
        f.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":2,\"answers\":false,\"players\":[]}");
        assertTrue(f.whyNoAnswers("Moongrove").contains("Redis is not connected"), "a server whose answers cannot come back is named");
        f.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":3,\"every\":10,\"answers\":false,\"stopping\":true,\"players\":[]}");
        assertEquals(List.of("Moongrove"), f.live(), "its last snapshot before a stop: gone at once");
    }

    @Test
    void chat_rows_are_tied_to_their_signed_uid_old_lines_are_not_news_and_hidden_characters_are_spaces() throws Exception {
        AtomicLong clock = new AtomicLong(10_000_000L);
        MemoryStore store = new MemoryStore();
        Chat chat = new Chat(store, GOLDEN_KEY, clock::get);
        List<String> shown = new ArrayList<>();
        chat.onLine(o -> shown.add(o.get("text").getAsString()));
        chat.poll(); // notes where the table ends
        String line = Signed.seal(GOLDEN_KEY, "{\"type\":\"chat\",\"v\":1,\"uid\":\"u-1\",\"at\":" + clock.get() + ",\"server\":\"Frostwarf\",\"kind\":\"player\",\"name\":\"Vexa\",\"text\":\"hello\"}");
        store.insertChat("u-1", clock.get(), "Frostwarf", "Vexa", "hello", line);
        store.insertChat("u-copy", clock.get(), "Frostwarf", "Vexa", "hello", line);
        chat.poll();
        assertEquals(List.of("hello"), shown, "a copy stored under another uid is not shown again");
        assertEquals(1, chat.beforeLines(clock.get() + 1).size(), "nor in the history");

        String old = Signed.seal(GOLDEN_KEY, "{\"type\":\"chat\",\"v\":1,\"uid\":\"u-2\",\"at\":" + (clock.get() - Chat.LIVE_MAX_AGE_MS - 1) + ",\"server\":\"Frostwarf\",\"kind\":\"player\",\"name\":\"Vexa\",\"text\":\"old news\"}");
        assertTrue(chat.acceptMessage("u-2\n1\nFrostwarf\nchat\n" + old));
        assertEquals(List.of("hello"), shown, "an old signed line sent again live is not shown live");

        assertEquals("abc def", Chat.clean("abc‮def"), "a right-to-left override becomes a space, as in game");
    }

    @Test
    void typed_item_data_is_taken_only_as_the_pictures_list_it() throws Exception {
        MemoryStore m = new MemoryStore();
        ItemPictures p = new ItemPictures(m);
        String items = "{\"cell\":32,\"perRow\":64,\"perSheet\":4096,\"items\":[{\"id\":\"minecraft:potion\",\"nbt\":\"{Potion:\\\"minecraft:swiftness\\\"}\",\"name\":\"Potion of Swiftness\"}],\"tabs\":[]}";
        byte[] z = ItemPicturesTest.zip(items, ItemPicturesTest.NAMES, true, null);
        ItemPicturesTest.put(m, 7, z, ItemPictures.sha256(z));
        p.refresh();
        assertTrue(p.knowsVariant("minecraft:potion", "{Potion:\"minecraft:swiftness\"}"));
        assertTrue(p.knowsVariant("minecraft:diamond", ""), "no data: always");
        assertFalse(p.knowsVariant("minecraft:potion", "{AttributeModifiers:[{Amount:1000}]}"), "made-up data: refused");
        assertFalse(p.knowsVariant("minecraft:diamond", "{Potion:\"minecraft:swiftness\"}"), "another item's data: refused");
    }

    @Test
    void the_panels_own_tables_live_in_its_own_database_and_the_shared_ones_are_named_through_the_game_servers() {
        com.mrgregles.bsp_staff_panel.store.JdbcStore own = new com.mrgregles.bsp_staff_panel.store.JdbcStore("db", 3306, "bsp", "bsp_panel", "bsp_panel", "pw", false, "bsp_su_");
        assertEquals("`bsp`.bsp_su_", own.sharedTables(), "players, bans, notices and chat in the game servers' database (D425)");
        com.mrgregles.bsp_staff_panel.store.JdbcStore one = new com.mrgregles.bsp_staff_panel.store.JdbcStore("db", 3306, "bsp", "bsp", "pw", false, "bsp_su_");
        assertEquals("bsp_su_", one.sharedTables(), "one database: as before");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new com.mrgregles.bsp_staff_panel.store.JdbcStore("db", 3306, "bsp`; DROP", "bsp_panel", "u", "pw", false, "bsp_su_"));
    }

    @Test
    void the_cookie_is_host_only_over_https() {
        assertEquals("__Host-bsp_panel", PanelServer.cookieName(true));
        assertEquals("bsp_panel", PanelServer.cookieName(false));
    }

    @Test
    void the_redis_listener_never_counts_a_refused_subscription_as_listening() throws Exception {
        try (ServerSocket fake = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = fake.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    in.read(new byte[256]);
                    out.write("-NOAUTH Authentication required.\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                    // closing
                }
            });
            t.setDaemon(true);
            t.start();
            List<String> said = new CopyOnWriteArrayList<>();
            RedisListener l = new RedisListener("127.0.0.1", fake.getLocalPort(), "", "", "bsp_su:network:web", m -> { }, said::add).start();
            long until = System.currentTimeMillis() + 3000;
            while (said.isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            assertFalse(l.subscribed(), "a refused SUBSCRIBE is not listening");
            assertTrue(!said.isEmpty() && said.get(0).contains("NOAUTH") && said.get(0).contains("redis.password"), "and the console says why: " + said);
            l.close();
        }
    }
}
