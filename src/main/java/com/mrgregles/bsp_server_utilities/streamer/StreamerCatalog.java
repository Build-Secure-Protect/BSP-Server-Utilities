package com.mrgregles.bsp_server_utilities.streamer;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * What Streamer Mode's menu lists (owner decisions D706, D713, D714; refined page R1 and R3), as plain data so it is
 * checked without a client: the mods it knows, in the menu's order, each with its known items first. The client adds,
 * under each mod, every other HUD part that mod registers with Forge, and lists the HUD parts of every other mod under
 * "Other mods". An item's {@code key} names its words in the language file ({@code streamer.item.<key>} and
 * {@code streamer.item.<key>.desc}); {@code note} names a gold line where the mod's own update is needed.
 */
public final class StreamerCatalog {
    /** The switch that hides every menu not Minecraft's own (refined page R1, "Other mods' menus"). */
    public static final String OTHER_MENUS = "bsp_server_utilities:other_menus";
    /** Streamer Mode's own menu: always a "Menu hidden" card in the stream (it shows the stream itself). */
    public static final String OWN_MENU = "com.mrgregles.bsp_server_utilities.client.StreamerScreen";

    /**
     * An item. {@code fallbackScreen}: a menu hidden whole in its place until its mod hides the item itself through
     * {@code api/StreamerMode} (BSP-Teleport's coordinates, request SU-TP2).
     */
    public record Item(String id, String key, @Nullable String note, @Nullable String fallbackScreen) {
        public Item(String id, String key, @Nullable String note) {
            this(id, key, note, null);
        }
    }

    /** A mod's tile: {@code modId} (null for Minecraft, always shown), the HUD namespace its own parts use, its items. */
    public record Mod(@Nullable String modId, String namespace, String key, List<Item> items) {
    }

    public static final List<Mod> MODS = List.of(
            new Mod(null, "minecraft", "minecraft", List.of(
                    new Item("minecraft:debug_text", "f3", null),
                    new Item("minecraft:chat_panel", "chat", null),
                    new Item("bsp_server_utilities:chat_coordinates", "chat_coords", null),
                    new Item("minecraft:player_list", "player_list", null),
                    new Item("minecraft:boss_event_progress", "boss_bars", null),
                    new Item("minecraft:title_text", "titles", null),
                    new Item("minecraft:record_overlay", "action_bar", null),
                    new Item("minecraft:scoreboard", "scoreboard", null))),
            new Mod("journeymap", "journeymap", "journeymap", List.of(
                    new Item("journeymap:minimap", "jm_minimap", null),
                    new Item("journeymap:minimap_coords", "jm_coords", null),
                    new Item("journeymap:waypoint_icons", "jm_markers", null),
                    new Item(StreamerRules.SCREEN + "journeymap.client.ui.", "jm_menus", null))),
            new Mod("bsp_teleport", "bsp_teleport", "bsp_teleport", List.of(
                    new Item("bsp_teleport:band_coordinates", "tp_band", "tp_update", "com.mrgregles.bsp_teleport.client.BandScreen"),
                    new Item("bsp_teleport:portal_coordinates", "tp_portal", "tp_update", "com.mrgregles.bsp_teleport.client.PortalScreen"),
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_teleport.client.AdminScreen", "tp_admin", null))),
            new Mod("bsp_core", "bsp_core", "bsp_core", List.of(
                    new Item("bsp_core:sentinel_strip", "core_sentinel", null),
                    new Item("bsp_core:recall_offer", "core_recall", null),
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.TeamScreen", "core_team", null),
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.AdminScreen", "core_admin", null),
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.AcceleratorScreen", "core_accelerator", null))),
            new Mod("bsp_region_guard", "bsp_region_guard", "bsp_region_guard", List.of(
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_region_guard.client.OfficePlotsScreen", "rg_office", null),
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_region_guard.client.RegionScreen", "rg_region", null))),
            new Mod(null, "bsp_server_utilities", "bsp_server_utilities", List.of(
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_server_utilities.client.AdminScreen", "su_admin", null),
                    new Item(StreamerRules.SCREEN + "com.mrgregles.bsp_server_utilities.client.TutorialBuilderScreen", "su_tutorial", null))));

    /** Hidden when Streamer Mode is first switched on (D707): everything that shows a place; also the settings' default. */
    public static final List<String> DEFAULT_HIDDEN = List.of(
            "minecraft:debug_text",
            "bsp_server_utilities:chat_coordinates",
            "journeymap:minimap",
            "journeymap:minimap_coords",
            "journeymap:waypoint_icons",
            StreamerRules.SCREEN + "journeymap.client.ui.",
            "bsp_teleport:band_coordinates",
            "bsp_teleport:portal_coordinates",
            "bsp_core:sentinel_strip",
            "bsp_core:recall_offer",
            StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.TeamScreen",
            StreamerRules.SCREEN + "com.mrgregles.bsp_region_guard.client.OfficePlotsScreen",
            // added in the audit of SU-135: menus that draw positions
            StreamerRules.SCREEN + "com.mrgregles.bsp_teleport.client.AdminScreen",
            StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.AdminScreen",
            StreamerRules.SCREEN + "com.mrgregles.bsp_core.client.AcceleratorScreen",
            StreamerRules.SCREEN + "com.mrgregles.bsp_region_guard.client.RegionScreen",
            StreamerRules.SCREEN + "com.mrgregles.bsp_server_utilities.client.AdminScreen",
            StreamerRules.SCREEN + "com.mrgregles.bsp_server_utilities.client.TutorialBuilderScreen");

    private StreamerCatalog() {
    }

    /** The language keys under {@code gui.bsp_server_utilities.} for a tile's name and an item's words (a GameTest checks each exists). */
    public static String modKey(String key) {
        return "streamer.mod." + key;
    }

    public static String itemKey(Item i) {
        return "streamer.item." + i.key();
    }

    public static String descKey(Item i) {
        return "streamer.item." + i.key() + ".desc";
    }

    @Nullable
    public static String noteKey(Item i) {
        return i.note() == null ? null : "streamer.note." + i.note();
    }

    /** Settings saved by an earlier build, in today's ids: BSP-Teleport's whole menus became its coordinates (SU-133). */
    public static java.util.Set<String> migrate(java.util.Collection<String> saved) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String id : saved) {
            out.add(switch (id) {
                case StreamerRules.SCREEN + "com.mrgregles.bsp_teleport.client.BandScreen" -> "bsp_teleport:band_coordinates";
                case StreamerRules.SCREEN + "com.mrgregles.bsp_teleport.client.PortalScreen" -> "bsp_teleport:portal_coordinates";
                default -> id;
            });
        }
        return out;
    }

    /**
     * The hidden items after defaults not yet applied are added (audit of SU-135): Forge writes the settings' default list
     * once, at the first start, so a default added in a later version would otherwise never reach existing players.
     * {@code applied} lists the defaults already applied (and then perhaps switched off by the player): only the others are
     * added. Returns {hidden, applied}.
     */
    public static List<java.util.Set<String>> applyDefaults(java.util.Collection<String> hidden, java.util.Collection<String> applied) {
        java.util.Set<String> h = new java.util.HashSet<>(hidden), a = new java.util.HashSet<>(applied);
        for (String d : DEFAULT_HIDDEN) {
            if (a.add(d)) {
                h.add(d);
            }
        }
        return List.of(h, a);
    }

    /** The known items that stand in for a whole menu until their mod hides them itself. */
    public static List<Item> withFallback() {
        List<Item> out = new java.util.ArrayList<>();
        for (Mod m : MODS) {
            for (Item i : m.items()) {
                if (i.fallbackScreen() != null) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    /** The known mod whose tile lists HUD parts of this namespace, or null ("Other mods"). */
    @Nullable
    public static Mod modOf(String namespace) {
        for (Mod m : MODS) {
            if (m.namespace().equals(namespace)) {
                return m;
            }
        }
        return null;
    }

    /** Whether an item id is one of the known items (not one read from the game). */
    public static boolean known(String id) {
        for (Mod m : MODS) {
            for (Item i : m.items()) {
                if (i.id().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A HUD part's words when no language line names it: its path, spaced and capitalised ("food_level" is "Food level"). */
    public static String readable(String path) {
        String s = path == null ? "" : path.replace('_', ' ').replace('/', ' ').replace('.', ' ').trim().replaceAll(" +", " ");
        return s.isEmpty() ? "?" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Whether a menu of this class is one of Minecraft's (or Forge's) own: the "Other mods' menus" switch leaves those. */
    public static boolean vanillaMenu(String className) {
        return className.startsWith("net.minecraft.") || className.startsWith("net.minecraftforge.") || className.startsWith("com.mojang.");
    }
}
