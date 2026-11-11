package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.notes.PlayerNotes;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import com.mrgregles.bsp_staff_panel.web.PanelServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player notes and tags (SU-38, D266, D267, D320 to D326, D330 to D332): notes seen by all, changed or deleted by their
 * author or an Admin with the earlier words in the panel's log; counts for the game servers; tags put on and taken off by
 * any staff member, a typed tag joining the list grey, the list kept by admins; the counts notice signed as actions are;
 * and the routes over HTTP.
 */
class NotesTest {
    private static final String VEXA = "5f0c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b", PIXL = "6a1b2c3d-4e5f-4a6b-9c7d-8e9f0a1b2c3d";

    private static Account account(MemoryStore store, String name, Rank rank) throws Exception {
        Account a = new Account();
        a.email = name.toLowerCase() + "@bsp.test";
        a.mcName = name;
        a.rank = rank;
        a.id = store.insert(a);
        return a;
    }

    @Test
    void notes_seen_by_all_changed_by_their_author_or_an_admin() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(1_000_000);
        PlayerNotes n = new PlayerNotes(store, clock::get);
        Account rook = account(store, "Rook", Rank.MODERATOR), kip = account(store, "Kip", Rank.MODERATOR), saffi = account(store, "Saffi", Rank.ADMIN);
        List<String> heard = new ArrayList<>();
        n.onNotes(heard::add);
        JsonObject note = n.add(rook, VEXA, "Vexa", "  Found 12 diamonds in 5 minutes.\nWatching her. ");
        assertEquals("Found 12 diamonds in 5 minutes.\nWatching her.", note.get("text").getAsString(), "line breaks kept, ends trimmed");
        assertEquals(List.of(VEXA), heard);
        clock.addAndGet(1000);
        n.add(kip, VEXA, "Vexa", "Asked if x-ray is allowed.");
        JsonArray all = n.notes(VEXA);
        assertEquals(2, all.size(), "every staff member sees both, oldest first");
        assertEquals("Rook", all.get(0).getAsJsonObject().get("author").getAsString());

        long id = note.get("id").getAsLong();
        assertThrows(PlayerNotes.Refused.class, () -> n.edit(kip, id, "changed"), "a Moderator does not change another's note (D330)");
        assertThrows(PlayerNotes.Refused.class, () -> n.delete(kip, id));
        clock.addAndGet(1000);
        JsonObject edited = n.edit(rook, id, "Found 12 diamonds in 5 minutes at y -58.");
        assertEquals(clock.get(), edited.get("editedAt").getAsLong(), "the \"edited\" mark (D324)");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().contains("edited a note about Vexa") && l.what().endsWith("it said: Found 12 diamonds in 5 minutes.\nWatching her.")),
                "the earlier words kept in the panel's log");
        n.delete(saffi, id); // an Admin may
        assertEquals(1, n.notes(VEXA).size());
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().startsWith("deleted a note about Vexa")));

        assertThrows(PlayerNotes.Refused.class, () -> n.add(rook, "Vexa", "Vexa", "not an id"));
        assertThrows(PlayerNotes.Refused.class, () -> n.add(rook, VEXA, "Vexa", "x".repeat(PlayerNotes.MAX_NOTE + 1)));
        assertThrows(PlayerNotes.Refused.class, () -> n.add(rook, VEXA, "Vexa", "bell\u0007"));
        assertNull(PlayerNotes.cleanNote("   "));
    }

    @Test
    void counts_for_the_game_servers() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(1_000_000);
        PlayerNotes n = new PlayerNotes(store, clock::get);
        Account rook = account(store, "Rook", Rank.MODERATOR), kip = account(store, "Kip", Rank.MODERATOR);
        n.add(rook, VEXA, "Vexa", "one");
        clock.addAndGet(5000);
        n.add(kip, VEXA, "Vexa", "two");
        JsonObject c = n.counts(List.of(VEXA, PIXL));
        assertFalse(c.has(PIXL), "no notes: left out, and the hover says nothing (D332)");
        JsonObject v = c.getAsJsonObject(VEXA);
        assertEquals(2, v.get("n").getAsInt());
        assertEquals("Kip", v.get("by").getAsString(), "the newest note's author");
        assertEquals(clock.get(), v.get("at").getAsLong());
    }

    @Test
    void tags_put_on_and_taken_off_by_any_staff_and_kept_by_admins() throws Exception {
        MemoryStore store = new MemoryStore();
        PlayerNotes n = new PlayerNotes(store, () -> 1000);
        Account kip = account(store, "Kip", Rank.MODERATOR), saffi = account(store, "Saffi", Rank.ADMIN);
        int[] changes = {0};
        n.onTags(() -> changes[0]++);
        n.put(kip, PIXL, "Pixl", 0, " x-ray?  ");
        JsonObject t = n.tags();
        JsonObject typed = t.getAsJsonArray("tags").get(0).getAsJsonObject();
        assertEquals("x-ray?", typed.get("name").getAsString());
        assertTrue(typed.get("typed").getAsBoolean(), "a tag typed on the spot joins the list (D322)");
        assertEquals(PlayerNotes.GREY, typed.get("colour").getAsString(), "grey until an admin colours it");
        assertEquals(1, typed.get("count").getAsInt());
        long id = typed.get("id").getAsLong();
        n.put(kip, VEXA, "Vexa", 0, "X-RAY?");
        assertEquals(1, n.tags().getAsJsonArray("tags").size(), "the same name in another case is the same tag");
        assertEquals(id, n.tags().getAsJsonObject("players").getAsJsonArray(VEXA).get(0).getAsLong());

        assertThrows(PlayerNotes.Refused.class, () -> n.editTag(kip, id, "", "#ff6b5c"), "the list is kept by admins (D267)");
        n.editTag(saffi, id, "Watch for x-ray", "#FF6B5C");
        JsonObject kept = n.tags().getAsJsonArray("tags").get(0).getAsJsonObject();
        assertFalse(kept.get("typed").getAsBoolean(), "coloured: in the list for good");
        assertEquals("#ff6b5c", kept.get("colour").getAsString());
        assertEquals("Watch for x-ray", kept.get("name").getAsString());
        n.put(kip, PIXL, "Pixl", 0, "Helpful");
        long helpful = n.tags().getAsJsonArray("tags").get(0).getAsJsonObject().get("id").getAsLong();
        assertThrows(PlayerNotes.Refused.class, () -> n.editTag(saffi, helpful, "watch for X-RAY", ""), "two tags cannot share a name");
        assertThrows(PlayerNotes.Refused.class, () -> n.editTag(saffi, helpful, "", "#123456"), "only the palette's colours");
        assertThrows(PlayerNotes.Refused.class, () -> n.put(kip, PIXL, "Pixl", 0, "<script>"), "letters, digits and a little punctuation");

        n.take(kip, VEXA, "Vexa", id); // any staff member (D325)
        assertFalse(n.tags().getAsJsonObject("players").has(VEXA));
        n.removeTag(saffi, id);
        assertEquals(1, n.tags().getAsJsonArray("tags").size(), "removed from the list");
        assertEquals(1, n.tags().getAsJsonObject("players").getAsJsonArray(PIXL).size(), "and from every player");
        assertTrue(changes[0] >= 6, "every change heard by the pages");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().equals("tagged Vexa (" + VEXA + ") x-ray?")), "in the panel's log (D326)");
    }

    @Test
    void the_counts_notice_is_signed_as_actions_are() throws Exception {
        List<String[]> sent = new ArrayList<>();
        Actions a = new Actions(ActionsTest.KEY, (uid, at, kind, payload) -> sent.add(new String[]{kind, payload}), () -> 42_000L);
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.add("counts", new JsonObject());
        a.notice("web_notes", o);
        assertEquals("web_notes", sent.get(0)[0]);
        String payload = sent.get(0)[1], body = payload.substring(0, payload.indexOf('.'));
        String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
        assertEquals(Actions.sign(ActionsTest.KEY, json), payload, "what the game servers' WebActions.verify checks");
        assertEquals(42_000L, JsonParser.parseString(json).getAsJsonObject().get("at").getAsLong());
    }

    @Test
    void notes_and_tags_over_http() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        Auth auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 1000);
        PanelServer.Gate gate = header -> new AccessJwt.Identity(header.substring(5), Long.MAX_VALUE);
        PanelServer server = new PanelServer("127.0.0.1", 0, ChatTest.URL, gate, auth, store, new Feed(clock::get), clock::get)
                .withNotes(new PlayerNotes(store, clock::get)).start();
        HttpClient http = HttpClient.newHttpClient();
        try {
            ChatTest.setUp(http, server, auth, clock, auth.createOwner("owner@bsp.test", "Mrgregles"), "owner@bsp.test");
            Account me = store.accountByEmail("owner@bsp.test").orElseThrow();
            String kip = ChatTest.setUp(http, server, auth, clock, auth.addStaff(me, "kip@bsp.test", "Kip", Rank.MODERATOR), "kip@bsp.test");
            JsonObject added = json(ChatTest.postRaw(http, server, "/api/notes/add", "kip@bsp.test", kip, "{\"player\":\"" + VEXA + "\",\"name\":\"Vexa\",\"text\":\"watch her\"}"));
            assertTrue(added.get("ok").getAsBoolean(), added.toString());
            JsonObject got = json(get(http, server, "/api/notes?player=" + VEXA, "kip@bsp.test", kip));
            assertEquals("watch her", got.getAsJsonArray("notes").get(0).getAsJsonObject().get("text").getAsString());
            assertTrue(json(ChatTest.postRaw(http, server, "/api/tags/put", "kip@bsp.test", kip, "{\"player\":\"" + VEXA + "\",\"name\":\"Vexa\",\"typed\":\"Watch\"}")).get("ok").getAsBoolean());
            JsonObject tags = json(get(http, server, "/api/tags", "kip@bsp.test", kip));
            assertEquals("Watch", tags.getAsJsonArray("tags").get(0).getAsJsonObject().get("name").getAsString());
            long tagId = tags.getAsJsonArray("tags").get(0).getAsJsonObject().get("id").getAsLong();
            JsonObject refused = json(ChatTest.postRaw(http, server, "/api/tags/remove", "kip@bsp.test", kip, "{\"id\":" + tagId + "}"));
            assertFalse(refused.get("ok").getAsBoolean(), "a Moderator does not remove tags from the list");
            assertEquals(401, get(http, server, "/api/tags", "kip@bsp.test", null).statusCode());
            assertEquals(1, store.playerTags().size());
            assertTrue(store.notesOf(VEXA).stream().map(PanelStore.Note::authorName).anyMatch("Kip"::equals));
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
