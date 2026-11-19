package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.web.MapShown;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which maps the Map tab shows (SU-83; D570 to D586): Owners only, maps BlueMap has drawn only, every change saved
 * together and in the panel's log, a failed save leaving everything as it was, the map list filtered and a hidden map's
 * files refused. The routes over HTTP are in {@link PanelServerTest}.
 */
class MapShownTest {
    private static final List<String> DRAWN = List.of("bsp_spawn__overworld", "bsp_spawn__nether", "bsp_spawn__end", "frostwarf__overworld",
            "frostwarf__nether", "frostwarf__end", "frostwarf__allthemodium_mining", "saferoom__overworld", "saferoom__nether");
    private static final Function<String, String> NAMES = s -> Map.of("bsp_spawn", "BSP-Spawn", "frostwarf", "BSP-Frostwarf").getOrDefault(s, MapShown.readable(s));

    private static Account account(MemoryStore store, String name, Rank rank) throws Exception {
        Account a = new Account();
        a.email = name.toLowerCase() + "@bsp.test";
        a.mcName = name;
        a.rank = rank;
        a.id = store.insert(a);
        return a;
    }

    @Test
    void only_an_owner_chooses_and_a_refusal_is_in_the_log() throws Exception {
        MemoryStore store = new MemoryStore();
        MapShown m = new MapShown(store, () -> 5_000);
        Account saffi = account(store, "Saffi", Rank.ADMIN);
        MapShown.Refused r = assertThrows(MapShown.Refused.class, () -> m.set(saffi, DRAWN, List.of("bsp_spawn__nether"), false, "tap", NAMES), "an Admin may not (D570)");
        assertTrue(r.getMessage().contains("Only an Owner"));
        assertThrows(MapShown.Refused.class, () -> m.sheet(saffi, DRAWN, NAMES), "nor see the sheet");
        assertTrue(m.hidden().isEmpty(), "nothing hidden");
        assertTrue(store.log(0, 10).stream().anyMatch(l -> l.who().equals("saffi@bsp.test") && l.what().endsWith("refused: not an Owner")), "the try is in the log");
    }

    @Test
    void every_map_is_shown_until_an_owner_hides_it_and_each_tap_is_saved_and_logged() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(1_000_000);
        MapShown m = new MapShown(store, clock::get);
        Account owner = account(store, "Mrgregles", Rank.OWNER);
        AtomicInteger told = new AtomicInteger();
        m.onChange(told::incrementAndGet);

        JsonObject sheet = m.sheet(owner, DRAWN, NAMES);
        assertTrue(sheet.get("last").isJsonNull(), "no change yet");
        JsonArray servers = sheet.getAsJsonArray("servers");
        assertEquals(List.of("BSP-Frostwarf", "BSP-Spawn", "Saferoom"), names(servers), "in the picker's order, by name (D581)");
        assertEquals(List.of("Boulderbrook", "BSP-Spawn"), names(m.sheet(owner, List.of("bsp_spawn__overworld", "boulderbrook__overworld"), NAMES).getAsJsonArray("servers")),
                "as the picker's localeCompare: capitals and dashes set aside");
        JsonArray fw = servers.get(0).getAsJsonObject().getAsJsonArray("maps");
        assertEquals(List.of("Overworld", "Nether", "End", "Mining"), labels(fw), "in the keys' order, named as on them");
        assertTrue(fw.asList().stream().allMatch(x -> x.getAsJsonObject().get("shown").getAsBoolean()), "a map nobody chose is shown (D573)");

        clock.addAndGet(1000);
        JsonObject saved = m.set(owner, DRAWN, List.of("bsp_spawn__nether"), false, "tap", NAMES);
        assertEquals("BSP-Spawn's Nether hidden", saved.get("saved").getAsString(), "for \"Saved: …\" (D582)");
        assertEquals(List.of("bsp_spawn__nether"), saved.getAsJsonArray("changed").asList().stream().map(x -> x.getAsString()).toList());
        assertEquals("Mrgregles", saved.getAsJsonObject("last").get("name").getAsString(), "the sheet's foot (D586)");
        assertEquals(clock.get(), saved.getAsJsonObject("last").get("at").getAsLong());
        assertEquals(Set.of("bsp_spawn__nether"), m.hidden());
        assertEquals(1, told.get(), "the pages are told (D583)");
        assertEquals("hid BSP-Spawn's Nether on the Map tab", store.log(0, 10).get(0).what());
        assertEquals("mrgregles@bsp.test", store.log(0, 10).get(0).who());

        assertEquals("nothing to change", m.set(owner, DRAWN, List.of("bsp_spawn__nether"), false, "tap", NAMES).get("saved").getAsString(), "a second tap of the same");
        assertEquals(1, told.get(), "nobody told");
        assertEquals(1, store.log(0, 10).size(), "and no log line");

        clock.addAndGet(1000);
        m.set(owner, DRAWN, List.of("bsp_spawn__nether"), true, "undo", NAMES);
        assertTrue(m.hidden().isEmpty());
        assertEquals("showed BSP-Spawn's Nether on the Map tab (UNDO)", store.log(0, 10).get(0).what(), "UNDO is one line (D586)");

        MapShown fresh = new MapShown(store, clock::get);
        fresh.set(owner, DRAWN, List.of("frostwarf__end"), false, "tap", NAMES);
        assertEquals(Set.of("frostwarf__end"), new MapShown(store, clock::get).hidden(), "kept in the database: a restarted panel has it");
    }

    @Test
    void all_and_none_are_one_change_and_one_line() throws Exception {
        MemoryStore store = new MemoryStore();
        MapShown m = new MapShown(store, () -> 7_000);
        Account owner = account(store, "Mrgregles", Rank.OWNER);
        JsonObject none = m.set(owner, DRAWN, List.of("saferoom__overworld", "saferoom__nether"), false, "none", NAMES);
        assertEquals("every map of Saferoom hidden", none.get("saved").getAsString());
        assertEquals(Set.of("saferoom__overworld", "saferoom__nether"), m.hidden(), "the overworld too (D574)");
        assertEquals(1, store.log(0, 10).size());
        assertEquals("hid every map of Saferoom on the Map tab (NONE)", store.log(0, 10).get(0).what());
        JsonObject two = m.set(owner, DRAWN, List.of("frostwarf__nether", "frostwarf__end"), false, "undo", NAMES);
        assertEquals("BSP-Frostwarf's Nether and BSP-Frostwarf's End hidden", two.get("saved").getAsString(), "part of a server: named one by one");
        JsonArray saferoom = m.sheet(owner, DRAWN, NAMES).getAsJsonArray("servers").get(2).getAsJsonObject().getAsJsonArray("maps");
        assertTrue(saferoom.asList().stream().noneMatch(x -> x.getAsJsonObject().get("shown").getAsBoolean()), "the sheet shows them hidden");
    }

    @Test
    void only_maps_bluemap_has_drawn_and_a_failed_save_changes_nothing() throws Exception {
        MemoryStore store = new MemoryStore();
        MapShown m = new MapShown(store, () -> 9_000);
        Account owner = account(store, "Mrgregles", Rank.OWNER);
        AtomicInteger told = new AtomicInteger();
        m.onChange(told::incrementAndGet);
        assertThrows(MapShown.Refused.class, () -> m.set(owner, DRAWN, List.of("moongrove__overworld"), false, "tap", NAMES), "not drawn");
        assertThrows(MapShown.Refused.class, () -> m.set(owner, DRAWN, List.of("../settings"), false, "tap", NAMES), "not a map id");
        assertThrows(MapShown.Refused.class, () -> m.set(owner, DRAWN, List.of(), false, "tap", NAMES), "nothing chosen");
        java.util.ArrayList<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 65; i++) {
            many.add("bsp_spawn__overworld");
        }
        assertThrows(MapShown.Refused.class, () -> m.set(owner, DRAWN, many, false, "tap", NAMES), "too many at once");

        store.failMapSaves = true;
        MapShown.Refused r = assertThrows(MapShown.Refused.class, () -> m.set(owner, DRAWN, List.of("frostwarf__end"), false, "tap", NAMES));
        assertTrue(r.getMessage().startsWith("Not saved"), "the red line (D582): " + r.getMessage());
        assertTrue(m.hidden().isEmpty(), "nothing changed");
        assertEquals(0, told.get(), "nobody told");
        assertTrue(store.log(0, 10).get(0).what().startsWith("tried to hide BSP-Frostwarf's End on the Map tab; not saved:"), "the try is in the log");
        store.failMapSaves = false;
        m.set(owner, DRAWN, List.of("frostwarf__end"), false, "tap", NAMES);
        assertEquals(Set.of("frostwarf__end"), m.hidden(), "saved once the database answers");
    }

    @Test
    void a_tap_that_changes_nothing_keeps_the_last_change_and_two_saves_at_once_both_stand() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(50_000);
        MapShown m = new MapShown(store, clock::get);
        Account owner = account(store, "Mrgregles", Rank.OWNER), second = account(store, "Saffi", Rank.OWNER);
        m.set(owner, DRAWN, List.of("frostwarf__end"), false, "tap", NAMES);
        clock.addAndGet(60_000);
        JsonObject again = m.set(second, DRAWN, List.of("frostwarf__end"), false, "tap", NAMES); // an out-of-date sheet
        assertEquals("nothing to change", again.get("saved").getAsString());
        assertEquals("Mrgregles", again.getAsJsonObject("last").get("name").getAsString(), "the foot still names who changed it (audit)");
        assertEquals(50_000, again.getAsJsonObject("last").get("at").getAsLong());
        m.set(second, DRAWN, List.of("frostwarf__end", "frostwarf__nether"), false, "tap", NAMES);
        assertEquals("Mrgregles", store.mapChoices().stream().filter(x -> x.map().equals("frostwarf__end")).findFirst().orElseThrow().byName(),
                "a change of several saves only the maps it changes");

        for (int round = 0; round < 20; round++) {
            MapShown fresh = new MapShown(store, clock::get);
            String a = round % 2 == 0 ? "bsp_spawn__nether" : "bsp_spawn__end", b = round % 2 == 0 ? "saferoom__nether" : "saferoom__overworld";
            Thread t1 = new Thread(() -> { try { fresh.set(owner, DRAWN, List.of(a), false, "tap", NAMES); } catch (MapShown.Refused e) { throw new RuntimeException(e); } });
            Thread t2 = new Thread(() -> { try { fresh.set(second, DRAWN, List.of(b), false, "tap", NAMES); } catch (MapShown.Refused e) { throw new RuntimeException(e); } });
            t1.start(); t2.start(); t1.join(); t2.join();
            assertTrue(fresh.hidden().containsAll(List.of(a, b)), "two Owners at once: both kept (audit), round " + round);
            fresh.set(owner, DRAWN, List.of(a, b), true, "undo", NAMES);
        }
    }

    @Test
    void a_database_that_does_not_answer_shows_every_map_and_is_asked_again_later() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(100_000);
        Account owner = account(store, "Mrgregles", Rank.OWNER);
        new MapShown(store, clock::get).set(owner, DRAWN, List.of("frostwarf__end"), false, "tap", NAMES);
        MapShown m = new MapShown(store, clock::get); // a panel started while the database is down
        store.failMapReads = true;
        assertTrue(m.hidden().isEmpty(), "every map shown meanwhile");
        int reads = store.mapReads;
        for (int i = 0; i < 50; i++) {
            m.hidden(); // every tile asks
        }
        assertEquals(reads, store.mapReads, "not asked again for every tile (audit)");
        assertThrows(MapShown.Refused.class, () -> m.set(owner, DRAWN, List.of("frostwarf__nether"), false, "tap", NAMES), "no change saved over choices it could not read");
        store.failMapReads = false;
        clock.addAndGet(MapShown.RETRY_READ_MS + 1);
        assertEquals(Set.of("frostwarf__end"), m.hidden(), "read once it answers again");
    }

    @Test
    void the_map_list_leaves_hidden_maps_out_and_their_files_are_refused() {
        JsonObject settings = JsonParser.parseString("{\"version\":\"5.4\",\"useCookies\":true,\"maps\":[\"bsp_spawn__overworld\",\"bsp_spawn__nether\",\"frostwarf__end\"],"
                + "\"startLocation\":{\"map\":\"bsp_spawn__overworld\"}}").getAsJsonObject();
        JsonObject out = MapShown.filterSettings(settings, Set.of("bsp_spawn__nether", "frostwarf__end"));
        assertEquals(List.of("bsp_spawn__overworld"), out.getAsJsonArray("maps").asList().stream().map(x -> x.getAsString()).toList());
        assertEquals("5.4", out.get("version").getAsString(), "everything else as BlueMap wrote it");
        assertEquals(2, out.get("bspHidden").getAsInt(), "how many were left out: \"No maps are shown.\" when all were (D585)");
        assertTrue(out.has("startLocation"));
        assertEquals(3, settings.getAsJsonArray("maps").size(), "BlueMap's own copy untouched");

        Set<String> hidden = Set.of("bsp_spawn__nether");
        assertTrue(MapShown.hiddenPath("maps/bsp_spawn__nether/settings.json", hidden));
        assertTrue(MapShown.hiddenPath("maps/bsp_spawn__nether/tiles/1/x0/z0.png", hidden));
        assertTrue(MapShown.hiddenPath("maps/bsp_spawn__nether/live/players.json", hidden), "its live data too");
        assertTrue(MapShown.hiddenPath("maps/bsp_spawn__nether", hidden));
        assertFalse(MapShown.hiddenPath("maps/bsp_spawn__overworld/tiles/1/x0/z0.png", hidden));
        assertFalse(MapShown.hiddenPath("assets/bsp_spawn__nether.js", hidden), "only under maps/");
        assertTrue(MapShown.hiddenPath("maps/BSP_SPAWN__NETHER/tiles/1/x0/z0.png", hidden), "any case, for a host whose files ignore it (audit)");
        assertTrue(MapShown.hiddenPath("maps/bsp_spawn__nether/assets/playerheads/11111111-2222-3333-4444-555555555555.png", hidden), "its heads too");
        assertTrue(MapShown.hiddenPath("maps/bsp_spawn__nether/live/markers.json", hidden), "and its pins");
    }

    private static List<String> names(JsonArray servers) {
        return servers.asList().stream().map(x -> x.getAsJsonObject().get("name").getAsString()).toList();
    }

    private static List<String> labels(JsonArray maps) {
        return maps.asList().stream().map(x -> x.getAsJsonObject().get("label").getAsString()).toList();
    }
}
