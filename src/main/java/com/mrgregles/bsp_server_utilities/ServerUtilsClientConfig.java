package com.mrgregles.bsp_server_utilities;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * This mod's settings in the player's own game ({@code config/bsp_server_utilities-client.toml}): never sent to or read by
 * a server.
 */
public final class ServerUtilsClientConfig {
    public static final ForgeConfigSpec SPEC;
    /** The SUPPORT BSP key in the inventory's top left corner (owner decision D591). */
    public static final ForgeConfigSpec.BooleanValue SUPPORT_BUTTON;
    /** The MESSAGE STAFF key in the inventory while staff have a conversation open with the player (D652 R5). */
    public static final ForgeConfigSpec.BooleanValue MESSAGE_BUTTON;
    /** Singleplayer's Select World screen as a grid of world cards (artboard W1). */
    public static final ForgeConfigSpec.BooleanValue WORLD_GRID;
    /** Multiplayer's server list as a grid: the featured server full width on top, the others as squares below. */
    public static final ForgeConfigSpec.BooleanValue SERVER_GRID;
    /** The server address (a host, or a domain its hosts end with) shown full width on top of the Multiplayer grid. */
    public static final ForgeConfigSpec.ConfigValue<String> FEATURED_SERVER;
    /** Streamer Mode's output: "window" (the stream window) or "spout2" (Windows only, D701). */
    public static final ForgeConfigSpec.ConfigValue<String> STREAM_OUTPUT;
    /** Streamer Mode's frame rate: 0 every frame (Match), or 60 or 30 a second (D710). */
    public static final ForgeConfigSpec.IntValue STREAM_FPS;
    /** What Streamer Mode leaves out of the stream (D706, D707): HUD part ids, adapter ids and screen: entries. */
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> STREAM_HIDDEN;
    /** The first switch-on defaults already applied to {@code hidden} (so a later version's new defaults are applied once). */
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> STREAM_APPLIED;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("inventory");
        SUPPORT_BUTTON = b.comment("Show the SUPPORT BSP key in the top left corner of the inventory (when the server has a support page).")
                .define("supportButton", true);
        MESSAGE_BUTTON = b.comment("Show the MESSAGE STAFF key in the inventory while staff have a conversation open with you (/su talk opens it too).")
                .define("messageButton", true);
        b.pop();
        b.push("menus");
        WORLD_GRID = b.comment("Show Singleplayer's Select World screen as a grid of world cards; false keeps Minecraft's own list.")
                .define("worldGrid", true);
        SERVER_GRID = b.comment("Show Multiplayer's server list as a grid: the featured server full width on top, the others as squares; false keeps Minecraft's own list.")
                .define("serverGrid", true);
        FEATURED_SERVER = b.comment("The server shown full width on top of the Multiplayer grid: a host, or a domain its hosts end with (empty: none).")
                .define("featuredServer", "buildsecureprotect.com");
        b.pop();
        b.push("streamer");
        STREAM_OUTPUT = b.comment("Streamer Mode's output for OBS: \"window\" (a window titled BSP Stream; OBS Window Capture) or \"spout2\" (Windows only; OBS's Spout2 plugin).")
                .define("output", "window", o -> "window".equals(o) || "spout2".equals(o));
        STREAM_FPS = b.comment("Streamer Mode's frame rate: 0 draws the stream every frame (Match); 60 or 30 draw it at most that many times a second, which costs less.")
                .defineInRange("frameRate", 0, 0, 240);
        STREAM_HIDDEN = b.comment("What Streamer Mode leaves out of the stream: HUD part ids (minecraft:debug_text), journeymap:minimap, and screen: followed by a menu's class or package. Set it in the Streamer Mode menu (the eye key on the title and pause screens).")
                .defineListAllowEmpty(java.util.List.of("hidden"), () -> com.mrgregles.bsp_server_utilities.streamer.StreamerCatalog.DEFAULT_HIDDEN, o -> o instanceof String);
        STREAM_APPLIED = b.comment("Kept by the game: which of Streamer Mode's hidden-at-first items have been applied to the list above, so a newer version adds only its new ones. Leave it alone.")
                .defineListAllowEmpty(java.util.List.of("appliedDefaults"), java.util.List::of, o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }

    private ServerUtilsClientConfig() {
    }

    /** Whether the MESSAGE STAFF key is shown; true before the config is loaded. */
    public static boolean messageButton() {
        try {
            return MESSAGE_BUTTON.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Whether the SUPPORT BSP key is shown; true before the config is loaded. */
    public static boolean supportButton() {
        try {
            return SUPPORT_BUTTON.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Whether the Select World screen shows the grid; true before the config is loaded. */
    public static boolean worldGrid() {
        try {
            return WORLD_GRID.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Whether the Multiplayer screen shows the grid; true before the config is loaded. */
    public static boolean serverGrid() {
        try {
            return SERVER_GRID.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Streamer Mode's output, "window" or "spout2"; "window" before the config is loaded. */
    public static String streamOutput() {
        try {
            return STREAM_OUTPUT.get();
        } catch (IllegalStateException e) {
            return "window";
        }
    }

    /** Streamer Mode's frame rate (0: every frame); 0 before the config is loaded. */
    public static int streamFps() {
        try {
            return STREAM_FPS.get();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    /** What Streamer Mode hides; the first switch-on defaults before the config is loaded. */
    public static java.util.Set<String> streamHidden() {
        try {
            return java.util.Set.copyOf(STREAM_HIDDEN.get());
        } catch (IllegalStateException e) {
            return com.mrgregles.bsp_server_utilities.streamer.StreamerRules.DEFAULT_HIDDEN;
        }
    }

    /** The defaults already applied; empty before the config is loaded. */
    public static java.util.Set<String> streamApplied() {
        try {
            return java.util.Set.copyOf(STREAM_APPLIED.get());
        } catch (IllegalStateException e) {
            return java.util.Set.of();
        }
    }

    /** Saves which defaults have been applied. */
    public static void setStreamApplied(java.util.Collection<String> applied) {
        try {
            STREAM_APPLIED.set(java.util.List.copyOf(new java.util.TreeSet<>(applied)));
            SPEC.save();
        } catch (IllegalStateException e) {
            // not loaded yet
        }
    }

    /** Saves a Streamer Mode setting at once (the menu saves each change, D714 R1). Does nothing before the config is loaded. */
    public static void setStream(String output, int fps, java.util.Collection<String> hidden) {
        try {
            STREAM_OUTPUT.set(output);
            STREAM_FPS.set(fps);
            STREAM_HIDDEN.set(java.util.List.copyOf(new java.util.TreeSet<>(hidden)));
            SPEC.save();
        } catch (IllegalStateException e) {
            // not loaded yet: kept for this session only
        }
    }

    /** The featured server's address; "buildsecureprotect.com" before the config is loaded. */
    public static String featuredServer() {
        try {
            return FEATURED_SERVER.get();
        } catch (IllegalStateException e) {
            return "buildsecureprotect.com";
        }
    }
}
