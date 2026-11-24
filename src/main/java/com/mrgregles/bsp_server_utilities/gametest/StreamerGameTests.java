package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.streamer.StreamerCatalog;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Streamer Mode's rules (D700 to D714), the arithmetic: what the stream picture leaves out by default, which menus count
 * as hidden, where the eye key stands, when a stream frame is due and how the picture fits the stream window. The stream
 * picture, the stream window and OBS need a client (docs/TESTING.md SU-131).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class StreamerGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void streamer_hides_what_shows_a_place_by_default(GameTestHelper h) {
        Set<String> d = StreamerRules.DEFAULT_HIDDEN;
        for (String id : List.of("minecraft:debug_text", "journeymap:minimap", "journeymap:minimap_coords", "journeymap:waypoint_icons", "bsp_core:sentinel_strip", "bsp_core:recall_offer")) {
            h.assertTrue(StreamerRules.hides(d, id), id + " is hidden at first switch-on (D707)");
        }
        for (String id : List.of("minecraft:hotbar", "minecraft:chat_panel", "minecraft:player_list", "minecraft:boss_event_progress", "bsp_core:steal_timer", "mekanism:hud")) {
            h.assertTrue(!StreamerRules.hides(d, id), id + " is shown at first switch-on (D707)");
        }
        h.assertTrue(!StreamerRules.hides(d, null), "no id is never hidden");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_hides_menus_by_class_or_package(GameTestHelper h) {
        Set<String> d = StreamerRules.DEFAULT_HIDDEN;
        h.assertTrue(StreamerRules.screenHidden(d, "journeymap.client.ui.fullscreen.Fullscreen"), "JourneyMap's full-screen map is in its package");
        h.assertTrue(StreamerRules.screenHidden(d, "journeymap.client.ui.waypointmanager.WaypointManager"), "and its waypoint manager");
        Set<String> rg = Set.of(StreamerRules.SCREEN + "com.mrgregles.bsp_region_guard.client.OfficePlotsScreen");
        h.assertTrue(StreamerRules.screenHidden(rg, "com.mrgregles.bsp_region_guard.client.OfficePlotsScreen$Row"), "a nested class of a hidden menu");
        h.assertTrue(!StreamerRules.screenHidden(rg, "com.mrgregles.bsp_region_guard.client.OfficePlotsScreenOther"), "a class whose name only starts the same is not");
        h.assertTrue(!StreamerRules.screenHidden(d, "journeymap.client.uiextra.Thing"), "a package whose name only starts the same is not");
        h.assertTrue(!StreamerRules.screenHidden(d, "net.minecraft.client.gui.screens.inventory.InventoryScreen"), "the inventory is shown");
        h.assertTrue(!StreamerRules.screenHidden(d, null) && !StreamerRules.screenHidden(d, ""), "no class is never hidden");
        h.assertTrue(!StreamerRules.screenHidden(Set.of("minecraft:debug_text"), "journeymap.client.ui.fullscreen.Fullscreen"), "a HUD id hides no menu");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_eye_key_stands_top_right_and_steps_left(GameTestHelper h) {
        int[] free = StreamerRules.eyeKey(List.of(), 480);
        h.assertTrue(Arrays.equals(free, new int[]{480 - 2 - 14, 2}), "in the top-right corner, 2 from each edge: " + Arrays.toString(free));
        int[] past = StreamerRules.eyeKey(List.of(new int[]{440, 0, 40, 20}), 480);
        h.assertTrue(past != null && past[0] + 14 + 2 <= 440 && past[1] == 2, "left of something in the corner, a gap away: " + Arrays.toString(past));
        int[] two = StreamerRules.eyeKey(List.of(new int[]{460, 0, 20, 20}, new int[]{420, 2, 30, 14}), 480);
        h.assertTrue(two != null && two[0] + 14 + 2 <= 420, "past two in a row: " + Arrays.toString(two));
        int[] below = StreamerRules.eyeKey(List.of(new int[]{0, 40, 480, 20}), 480);
        h.assertTrue(Arrays.equals(below, free), "something lower down is not in the way");
        h.assertTrue(StreamerRules.eyeKey(List.of(new int[]{200, 0, 280, 18}), 480) == null, "no key rather than past the middle");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_frames_come_at_the_chosen_rate(GameTestHelper h) {
        long s = 1_000_000_000L;
        h.assertTrue(StreamerRules.due(5, 4, 0), "Match: every frame");
        h.assertTrue(StreamerRules.due(5, 0, 30), "the first frame is always due");
        h.assertTrue(!StreamerRules.due(s + s / 60, s, 30), "30: not after a sixtieth of a second");
        h.assertTrue(StreamerRules.due(s + s / 30, s, 30), "30: after a thirtieth");
        h.assertTrue(StreamerRules.due(s + s / 61, s, 60), "60: a frame a little early on a 60 Hz game is in time");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_picture_fits_the_stream_window_whole(GameTestHelper h) {
        h.assertTrue(Arrays.equals(StreamerRules.fit(1920, 1080, 1920, 1080), new int[]{0, 0, 1920, 1080}), "the same size fills it");
        h.assertTrue(Arrays.equals(StreamerRules.fit(1920, 1080, 960, 540), new int[]{0, 0, 960, 540}), "half the size, the same shape");
        h.assertTrue(Arrays.equals(StreamerRules.fit(1920, 1080, 1000, 1000), new int[]{0, 218, 1000, 563}), "a square window: bars above and below");
        h.assertTrue(Arrays.equals(StreamerRules.fit(1920, 1080, 2400, 1080), new int[]{240, 0, 1920, 1080}), "a wide window: bars at the sides");
        h.assertTrue(Arrays.equals(StreamerRules.fit(1920, 1080, 0, 0), new int[]{0, 0, 0, 0}), "a minimised window: nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_menu_hides_its_own_menu_and_other_mods_menus_when_asked(GameTestHelper h) {
        Set<String> d = StreamerRules.DEFAULT_HIDDEN;
        h.assertTrue(StreamerRules.menuHidden(Set.of(), StreamerCatalog.OWN_MENU), "Streamer Mode's own menu is always a card in the stream (it shows the stream)");
        h.assertTrue(StreamerRules.menuHidden(d, "journeymap.client.ui.fullscreen.Fullscreen"), "a menu hidden by its package");
        h.assertTrue(StreamerRules.menuHidden(d, "com.mrgregles.bsp_core.client.TeamScreen") && StreamerRules.menuHidden(d, "com.mrgregles.bsp_region_guard.client.OfficePlotsScreen"),
                "BSP-Core's Team screen and Region Guard's Land Office screen are hidden at first switch-on (D707)");
        h.assertTrue(!StreamerRules.menuHidden(d, "mekanism.client.gui.GuiMekanism"), "another mod's menu shows while Other mods' menus is off");
        Set<String> other = Set.of(StreamerCatalog.OTHER_MENUS);
        h.assertTrue(StreamerRules.menuHidden(other, "mekanism.client.gui.GuiMekanism"), "and is a card with it on");
        for (String vanilla : List.of("net.minecraft.client.gui.screens.inventory.InventoryScreen", "net.minecraftforge.client.gui.ModListScreen", "com.mojang.realmsclient.RealmsMainScreen")) {
            h.assertTrue(!StreamerRules.menuHidden(other, vanilla), vanilla + " is Minecraft's own: never hidden by Other mods' menus");
        }
        h.assertTrue(!StreamerRules.menuHidden(other, null), "no menu is never hidden");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_catalog_is_whole_and_every_word_is_in_the_language_file(GameTestHelper h) {
        Set<String> ids = new HashSet<>();
        Set<String> lang;
        try (var in = StreamerGameTests.class.getResourceAsStream("/assets/bsp_server_utilities/lang/en_us.json")) {
            h.assertTrue(in != null, "the language file is in the jar");
            lang = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().keySet();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        String p = "gui.bsp_server_utilities.";
        h.assertTrue(StreamerCatalog.MODS.get(0).modId() == null && StreamerCatalog.MODS.get(0).namespace().equals("minecraft"), "Minecraft is first and always shown");
        for (StreamerCatalog.Mod m : StreamerCatalog.MODS) {
            h.assertTrue(lang.contains(p + StreamerCatalog.modKey(m.key())), "the tile's name: " + m.key());
            h.assertTrue(StreamerCatalog.modOf(m.namespace()) == m, m.namespace() + "'s HUD parts go under its own tile");
            for (StreamerCatalog.Item i : m.items()) {
                h.assertTrue(ids.add(i.id()), "listed once: " + i.id());
                h.assertTrue(StreamerCatalog.known(i.id()), i.id() + " is known");
                h.assertTrue(lang.contains(p + StreamerCatalog.itemKey(i)) && lang.contains(p + StreamerCatalog.descKey(i)), "words for " + i.id());
                h.assertTrue(i.note() == null || lang.contains(p + StreamerCatalog.noteKey(i)), "the gold line for " + i.id());
            }
        }
        h.assertTrue(lang.contains(p + StreamerCatalog.modKey("other")), "the Other mods tile's name");
        for (String id : StreamerCatalog.DEFAULT_HIDDEN) {
            h.assertTrue(ids.contains(id), "a default is one of the menu's items, so it can be switched back on: " + id);
        }
        h.assertTrue(StreamerCatalog.modOf("mekanism") == null, "a mod not known goes under Other mods");
        h.assertTrue(StreamerCatalog.readable("food_level").equals("Food level") && StreamerCatalog.readable("hud/meka_suit").equals("Hud meka suit")
                && StreamerCatalog.readable("").equals("?"), "a part with no words is named from its path");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_finds_coordinates_in_journeymaps_minimap_lines(GameTestHelper h) {
        // JourneyMap 6.0.7's location line in each of its forms (jm.common.location_*), and its region line
        for (String yes : List.of("120, 64, -340", "x: 120, y: 64, z: -340", "x: 1, y: 70 (15), z: -2", "17, -15", "17, 71 (12), -15",
                "x: 1, z: -2, y: 3", "Region: x:0 z:-1", "Region: x:-12 z:5")) {
            h.assertTrue(StreamerRules.hasCoordinates(yes), "coordinates: " + yes);
        }
        // its other lines stay in the stream
        for (String no : List.of("12:34", "Day: 5", "Light: 12", "Speed: 4.3 blocks/sec", "Cherry Grove", "60 fps", "Moon: Full",
                "Weather: Raining", "Dimension: Overworld", "")) {
            h.assertTrue(!StreamerRules.hasCoordinates(no), "not coordinates: " + no);
        }
        h.assertTrue(!StreamerRules.hasCoordinates(null), "no line holds none");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_hides_the_teleport_band_whole_until_bsp_teleport_hides_its_coordinates(GameTestHelper h) {
        Set<String> d = StreamerRules.DEFAULT_HIDDEN;
        String band = "com.mrgregles.bsp_teleport.client.BandScreen", portal = "com.mrgregles.bsp_teleport.client.PortalScreen";
        h.assertTrue(d.contains("bsp_teleport:band_coordinates") && d.contains("bsp_teleport:portal_coordinates"), "BSP-Teleport's coordinates are hidden at first (D707)");
        h.assertTrue(StreamerRules.menuHidden(d, Set.of(), band) && StreamerRules.menuHidden(d, Set.of(), band + "$Tab") && StreamerRules.menuHidden(d, Set.of(), portal),
                "before BSP-Teleport asks, its band and portal screen are hidden whole (the fallback)");
        h.assertTrue(!StreamerRules.menuHidden(d, Set.of("bsp_teleport:band_coordinates"), band), "once it asks about the band, the band shows and hides its own numbers");
        h.assertTrue(StreamerRules.menuHidden(d, Set.of("bsp_teleport:band_coordinates"), portal), "the portal screen stays whole until it asks about that too");
        Set<String> shown = new HashSet<>(d);
        shown.remove("bsp_teleport:band_coordinates");
        h.assertTrue(!StreamerRules.menuHidden(shown, Set.of(), band), "with the band's coordinates shown, no fallback");
        h.assertTrue(!StreamerRules.menuHidden(d, Set.of(), band + "Other"), "a look-alike class is not the band");
        Set<String> old = StreamerCatalog.migrate(List.of(StreamerRules.SCREEN + band, StreamerRules.SCREEN + portal, "minecraft:debug_text"));
        h.assertTrue(old.equals(Set.of("bsp_teleport:band_coordinates", "bsp_teleport:portal_coordinates", "minecraft:debug_text")),
                "an earlier build's saved whole-menu entries become the coordinates items: " + old);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_leaves_chat_messages_with_coordinates_out_whole(GameTestHelper h) {
        for (String yes : List.of("Teleported BSPAdmin to 100.5, 64.0, -200.5", "/tp 100 64 -200", "my base is at 100 64 -200", "<Wren_88> come to 1200, -340")) {
            h.assertTrue(StreamerRules.hasCoordinates(yes), "coordinates: " + yes);
        }
        for (String no : List.of("I have 3 apples", "back at 12:30", "2 3", "<Steve_42> gg", "Day 4 of 30")) {
            h.assertTrue(!StreamerRules.hasCoordinates(no), "not coordinates: " + no);
        }
        // chat's lines newest first, as Minecraft keeps them: each message's last line (marked as its end) comes first, then
        // its earlier lines. Here: [0] "lovely"; [1..3] "<Wren_88> my base is at come to 1200, -340 tonight" wrapped over
        // three lines; [4] "<Steve_42> hi"
        boolean[] wrapped = StreamerRules.keptChatLines(List.of("lovely", "-340 tonight", "come to 1200,", "<Wren_88> my base is at", "<Steve_42> hi"),
                List.of(true, true, false, false, true));
        h.assertTrue(wrapped[0] && !wrapped[1] && !wrapped[2] && !wrapped[3] && wrapped[4],
                "a position split over the lines of one message leaves the whole message out: " + Arrays.toString(wrapped));
        h.assertTrue(StreamerRules.keptChatLines(List.of(), List.of()).length == 0, "no chat, nothing to keep");
        boolean[] first = StreamerRules.keptChatLines(List.of("100 64", "-200 here"), List.of(false, true));
        h.assertTrue(first.length == 2 && first[0] && first[1], "an unmarked first line is a message of its own, and each part alone holds no position");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_spout2_runs_on_64_bit_windows_only_with_the_shipped_library(GameTestHelper h) {
        h.assertTrue(StreamerRules.spoutAvailable("Windows 11", "amd64") && StreamerRules.spoutAvailable("Windows 10", "x86_64"), "64-bit Windows on Intel or AMD");
        for (String[] no : new String[][]{{"Mac OS X", "aarch64"}, {"Mac OS X", "x86_64"}, {"Linux", "amd64"}, {"Windows 11", "aarch64"}, {"Windows 10", "x86"}}) {
            h.assertTrue(!StreamerRules.spoutAvailable(no[0], no[1]), "not on " + no[0] + " " + no[1]);
        }
        h.assertTrue(!StreamerRules.spoutAvailable(null, "amd64") && !StreamerRules.spoutAvailable("Windows 11", null), "nothing known, no Spout2");
        byte[] dll;
        try (var in = StreamerGameTests.class.getResourceAsStream(StreamerRules.SPOUT_RESOURCE);
             var lic = StreamerGameTests.class.getResourceAsStream("/natives/windows-x64/SpoutLibrary-LICENSE.txt")) {
            h.assertTrue(in != null, "SpoutLibrary.dll is in the jar");
            h.assertTrue(lic != null, "its licence is in the jar beside it");
            dll = in.readAllBytes();
            String licence = new String(lic.readAllBytes(), StandardCharsets.UTF_8);
            h.assertTrue(licence.contains("Copyright (c) 2016-2026, Lynn Jarvis") && licence.contains("Redistribution and use in source and binary forms")
                    && licence.contains(StreamerRules.SPOUT_VERSION), "the BSD-2-Clause notice Spout's licence asks for, naming the version");
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(dll));
            h.assertTrue(sha.equals(StreamerRules.SPOUT_SHA256), "the shipped file is Spout " + StreamerRules.SPOUT_VERSION + "'s, unchanged: " + sha);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int pe = (dll[0x3c] & 0xFF) | (dll[0x3d] & 0xFF) << 8 | (dll[0x3e] & 0xFF) << 16 | (dll[0x3f] & 0xFF) << 24;
        int machine = (dll[pe + 4] & 0xFF) | (dll[pe + 5] & 0xFF) << 8;
        h.assertTrue(dll[0] == 'M' && dll[1] == 'Z' && dll[pe] == 'P' && dll[pe + 1] == 'E' && machine == 0x8664, "a 64-bit (x64) Windows library");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_audit_catches_every_way_a_position_is_written(GameTestHelper h) {
        for (String yes : List.of("The nearest minecraft:village_plains is at [1234, ~, -560] (12 blocks away)", "x=100, y=64, z=-200",
                "X 100 Y 64 Z -200", "100/64/-200", "XYZ: 100.500 / 64.000 / -200.500", "/tp @s 100 ~ -200", "1200,-340", "100,64,-200")) {
            h.assertTrue(StreamerRules.hasCoordinates(yes), "coordinates: " + yes);
        }
        for (String no : List.of("I paid 1,000 coins", "12,345 points", "1,000,000 blocks", "v1.20.1", "max 5 and y")) {
            h.assertTrue(!StreamerRules.hasCoordinates(no), "not coordinates: " + no);
        }
        // what the player types: always hidden with chat hidden; with chat's coordinates hidden, any command and any position
        h.assertTrue(StreamerRules.hideTyping(true, false, "hello"), "chat hidden: the box is empty");
        h.assertTrue(StreamerRules.hideTyping(false, true, "/tp 100 64") && StreamerRules.hideTyping(false, true, "/home"), "a command, even half typed");
        h.assertTrue(StreamerRules.hideTyping(false, true, "meet at 100 64 -200"), "a position in words");
        h.assertTrue(!StreamerRules.hideTyping(false, true, "gg everyone") && !StreamerRules.hideTyping(false, false, "/tp 100 64 -200"),
                "plain words show; with nothing hidden everything shows");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void streamer_audit_lists_every_bsp_menu_that_shows_positions_and_applies_new_defaults_once(GameTestHelper h) {
        Set<String> d = StreamerRules.DEFAULT_HIDDEN;
        for (String c : List.of("com.mrgregles.bsp_server_utilities.client.AdminScreen", "com.mrgregles.bsp_teleport.client.AdminScreen",
                "com.mrgregles.bsp_core.client.AdminScreen", "com.mrgregles.bsp_core.client.AcceleratorScreen", "com.mrgregles.bsp_region_guard.client.RegionScreen")) {
            h.assertTrue(StreamerRules.menuHidden(d, c), c + " draws positions: hidden at first");
        }
        // a player whose settings were written by an earlier version: only the defaults not yet applied are added
        Set<String> oldDefaults = Set.of("minecraft:debug_text", "journeymap:minimap");
        List<Set<String>> first = StreamerCatalog.applyDefaults(Set.of("minecraft:debug_text"), Set.of());
        h.assertTrue(first.get(0).containsAll(d) && first.get(1).containsAll(d), "nothing applied yet: every default is added once");
        List<Set<String>> later = StreamerCatalog.applyDefaults(Set.of("minecraft:debug_text"), d);
        h.assertTrue(later.get(0).equals(Set.of("minecraft:debug_text")), "a default the player switched off stays off: " + later.get(0));
        Set<String> partly = new HashSet<>(d);
        partly.remove(StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.AcceleratorScreen");
        List<Set<String>> upgrade = StreamerCatalog.applyDefaults(oldDefaults, partly);
        h.assertTrue(upgrade.get(0).contains(StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.AcceleratorScreen") && !upgrade.get(0).contains("bsp_core:recall_offer"),
                "a later version's new default is added; an older one already applied is not: " + upgrade.get(0));
        h.succeed();
    }
}
