package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.talk.StaffTalk;
import com.mrgregles.bsp_staff_panel.web.PanelServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Staff tab (SU-37, D265, D314 to D319, D327 to D329): the room, deleting your own line for 5 minutes and an Owner
 * any, messages to one person or all staff with read marks, REPLY marking read, who hears what, the 30-day prune that
 * keeps unread messages, the limit, and the routes over HTTP.
 */
class StaffTalkTest {
    private static final long MIN = 60_000L, DAY = 24 * 60 * MIN;

    private static Account account(MemoryStore store, String name, Rank rank) throws Exception {
        Account a = new Account();
        a.email = name.toLowerCase() + "@bsp.test";
        a.mcName = name;
        a.rank = rank;
        a.id = store.insert(a);
        return a;
    }

    @Test
    void the_room_and_deleting_lines() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(100 * DAY);
        StaffTalk t = new StaffTalk(store, clock::get);
        Account ana = account(store, "Ana_Mod", Rank.MODERATOR), saffi = account(store, "Saffi", Rank.ADMIN), owner = account(store, "Mrgregles", Rank.OWNER);
        List<JsonObject> heard = new ArrayList<>();
        t.onRoom(heard::add);
        JsonObject line = t.say(ana, "  restart at 22:00? ");
        assertEquals("restart at 22:00?", line.get("text").getAsString());
        assertEquals("Moderator", line.get("rankLabel").getAsString());
        assertEquals(1, heard.size(), "every open page hears it");
        assertThrows(StaffTalk.Refused.class, () -> t.say(ana, "two\nlines"), "the room takes one line");
        assertThrows(StaffTalk.Refused.class, () -> t.say(ana, "x".repeat(StaffTalk.MAX_TEXT + 1)));
        assertThrows(StaffTalk.Refused.class, () -> t.say(ana, "   "));

        long id = line.get("id").getAsLong();
        assertThrows(StaffTalk.Refused.class, () -> t.deleteRoom(saffi, id), "an Admin does not delete other people's lines");
        clock.addAndGet(4 * MIN);
        JsonObject gone = t.deleteRoom(ana, id);
        assertTrue(gone.get("deleted").getAsBoolean());
        assertEquals("", gone.get("text").getAsString(), "\"deleted\" stays in its place, the words do not (D327)");
        assertEquals(2, heard.size(), "and the pages hear the deletion");
        JsonObject later = t.say(ana, "second");
        clock.addAndGet(6 * MIN);
        assertThrows(StaffTalk.Refused.class, () -> t.deleteRoom(ana, later.get("id").getAsLong()), "after 5 minutes, not even your own");
        t.deleteRoom(owner, later.get("id").getAsLong()); // an Owner deletes any line at any time

        for (int i = 0; i < 105; i++) {
            clock.addAndGet(5_000); // within the limit of 30 a minute each
            t.say(i % 2 == 0 ? saffi : owner, "line " + i);
        }
        List<JsonObject> newest = t.room(Long.MAX_VALUE);
        assertEquals(StaffTalk.PAGE, newest.size());
        assertEquals("line 104", newest.get(newest.size() - 1).get("text").getAsString(), "oldest first, the newest at the bottom");
        List<JsonObject> older = t.room(newest.get(0).get("id").getAsLong());
        assertEquals(7, older.size(), "scrolling up: the rest");
    }

    @Test
    void messages_to_one_person_or_all_staff_with_read_marks() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(100 * DAY);
        StaffTalk t = new StaffTalk(store, clock::get);
        Account ana = account(store, "Ana_Mod", Rank.MODERATOR), kip = account(store, "Kip", Rank.MODERATOR), owner = account(store, "Mrgregles", Rank.OWNER);
        Account gone = account(store, "Old_Mod", Rank.MODERATOR);
        gone.active = false;
        store.update(gone);
        List<Set<Long>> told = new ArrayList<>();
        t.onMessages(told::add);

        JsonObject m = t.send(owner, String.valueOf(ana.id), "Can you keep an eye on Moongrove tonight?\nTwo reports of x-ray.", 0);
        assertEquals("Ana_Mod", m.get("to").getAsString());
        assertEquals(Set.of(ana.id, owner.id), told.get(0), "only the two of them are told");
        assertEquals(1, t.messages(ana).get("unread").getAsInt(), "waiting for Ana when she opens the panel");
        assertEquals(0, t.messages(kip).getAsJsonArray("messages").size(), "Kip never sees it");
        assertThrows(StaffTalk.Refused.class, () -> t.read(kip, m.get("id").getAsLong()), "nor marks it read");

        clock.addAndGet(MIN);
        JsonObject reply = t.send(ana, String.valueOf(owner.id), "On it.", m.get("id").getAsLong());
        assertEquals(0, t.messages(ana).get("unread").getAsInt(), "REPLY marks the message read (D328)");
        JsonObject asOwner = t.messages(owner).getAsJsonArray("messages").get(1).getAsJsonObject();
        assertEquals(clock.get(), asOwner.get("theyReadAt").getAsLong(), "the sender sees \"Read 21:40\" (D315)");
        assertEquals(1, t.messages(owner).get("unread").getAsInt(), "and has Ana's reply waiting");
        assertTrue(reply.get("mine").getAsBoolean());

        JsonObject all = t.send(kip, "all", "Restart on Sunday at 10:00.", 0);
        assertNull(told.get(told.size() - 1), "everyone is told of a message to all staff");
        t.read(ana, all.get("id").getAsLong());
        JsonObject asKip = t.messages(kip).getAsJsonArray("messages").get(0).getAsJsonObject();
        JsonArray readers = asKip.getAsJsonArray("readers");
        assertEquals(1, readers.size());
        assertEquals("Ana_Mod", readers.get(0).getAsString());
        assertEquals(2, asKip.get("of").getAsInt(), "\"Read by 1 of 2\": the other active staff");

        assertThrows(StaffTalk.Refused.class, () -> t.send(kip, String.valueOf(owner.id), "sneaky reply", m.get("id").getAsLong()), "a reply to a message that is not Kip's");
        assertTrue(t.messages(owner).getAsJsonArray("messages").asList().stream().noneMatch(x -> x.getAsJsonObject().get("text").getAsString().equals("sneaky reply")),
                "is not stored either (audit)");
        assertThrows(StaffTalk.Refused.class, () -> t.send(ana, String.valueOf(ana.id), "me", 0), "not to yourself");
        assertThrows(StaffTalk.Refused.class, () -> t.send(ana, String.valueOf(gone.id), "hi", 0), "not to a removed account");
        assertThrows(StaffTalk.Refused.class, () -> t.send(ana, "Vexa", "hi", 0));
        assertThrows(StaffTalk.Refused.class, () -> t.deleteMessage(ana, all.get("id").getAsLong()), "not someone else's");
        t.deleteMessage(kip, all.get("id").getAsLong());
        assertTrue(t.messages(ana).getAsJsonArray("messages").get(0).getAsJsonObject().get("deleted").getAsBoolean());
    }

    @Test
    void thirty_days_kept_and_unread_messages_until_read() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(100 * DAY);
        StaffTalk t = new StaffTalk(store, clock::get);
        Account ana = account(store, "Ana_Mod", Rank.MODERATOR), kip = account(store, "Kip", Rank.MODERATOR), owner = account(store, "Mrgregles", Rank.OWNER);
        t.say(ana, "old line");
        long read = t.send(owner, String.valueOf(ana.id), "read one", 0).get("id").getAsLong();
        t.read(ana, read);
        t.send(owner, String.valueOf(kip.id), "never read", 0);
        long all = t.send(owner, "all", "to all", 0).get("id").getAsLong();
        t.read(ana, all);
        clock.addAndGet(31 * DAY);
        t.say(ana, "new line");
        int[] first = t.prune();
        assertEquals(1, first[0], "room lines over 30 days old");
        assertEquals(1, first[1], "only the read message went; Kip's unread one and the one to all (Kip has not read it) stay (D316)");
        t.read(kip, all);
        assertEquals(1, t.prune()[1], "now all have read it");
        Account k = store.account(kip.id).orElseThrow();
        k.active = false;
        store.update(k);
        assertEquals(1, t.prune()[1], "Kip removed: he will never read his, so it goes");
    }

    @Test
    void thirty_lines_a_minute() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(100 * DAY);
        StaffTalk t = new StaffTalk(store, clock::get);
        Account ana = account(store, "Ana_Mod", Rank.MODERATOR);
        for (int i = 0; i < StaffTalk.PER_MINUTE; i++) {
            t.say(ana, "line " + i);
        }
        assertThrows(StaffTalk.Refused.class, () -> t.say(ana, "one more"));
        clock.addAndGet(MIN + 1);
        t.say(ana, "a minute later");
    }

    @Test
    void the_staff_tab_over_http() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        Auth auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 1000);
        PanelServer.Gate gate = header -> new AccessJwt.Identity(header.substring(5), Long.MAX_VALUE);
        StaffTalk talk = new StaffTalk(store, clock::get);
        PanelServer server = new PanelServer("127.0.0.1", 0, ChatTest.URL, gate, auth, store, new Feed(clock::get), clock::get).withTalk(talk).start();
        HttpClient http = HttpClient.newHttpClient();
        try {
            String owner = ChatTest.setUp(http, server, auth, clock, auth.createOwner("owner@bsp.test", "Mrgregles"), "owner@bsp.test");
            Account me = store.accountByEmail("owner@bsp.test").orElseThrow();
            String ana = ChatTest.setUp(http, server, auth, clock, auth.addStaff(me, "ana@bsp.test", "Ana_Mod", Rank.MODERATOR), "ana@bsp.test");
            long anaId = store.accountByEmail("ana@bsp.test").orElseThrow().id;

            JsonObject said = json(ChatTest.postRaw(http, server, "/api/room/say", "ana@bsp.test", ana, "{\"text\":\"hello staff\"}"));
            assertTrue(said.get("ok").getAsBoolean(), said.toString());
            JsonObject room = json(get(http, server, "/api/room", "owner@bsp.test", owner));
            assertEquals("hello staff", room.getAsJsonArray("lines").get(0).getAsJsonObject().get("text").getAsString());

            JsonObject sent = json(ChatTest.postRaw(http, server, "/api/messages/send", "owner@bsp.test", owner, "{\"to\":\"" + anaId + "\",\"text\":\"Moongrove tonight?\"}"));
            assertTrue(sent.get("ok").getAsBoolean(), sent.toString());
            JsonObject box = json(get(http, server, "/api/messages", "ana@bsp.test", ana));
            assertEquals(1, box.get("unread").getAsInt());
            assertEquals("Mrgregles", box.getAsJsonArray("people").get(0).getAsJsonObject().get("name").getAsString(), "who Ana can write to");
            long mid = box.getAsJsonArray("messages").get(0).getAsJsonObject().get("id").getAsLong();
            assertTrue(json(ChatTest.postRaw(http, server, "/api/messages/read", "ana@bsp.test", ana, "{\"id\":" + mid + "}")).get("ok").getAsBoolean());
            assertEquals(0, json(get(http, server, "/api/messages", "ana@bsp.test", ana)).get("unread").getAsInt());
            JsonObject refused = json(ChatTest.postRaw(http, server, "/api/room/delete", "ana@bsp.test", ana, "{\"id\":999}"));
            assertFalse(refused.get("ok").getAsBoolean(), "a refusal comes back as words, not an error");
            assertEquals(401, get(http, server, "/api/messages", "ana@bsp.test", null).statusCode(), "only for a login");
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient http, PanelServer server, String path, String who, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).header("Cf-Access-Jwt-Assertion", "good:" + who).GET();
        if (cookie != null) {
            b.header("Cookie", PanelServer.COOKIE + "=" + cookie);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }
}
