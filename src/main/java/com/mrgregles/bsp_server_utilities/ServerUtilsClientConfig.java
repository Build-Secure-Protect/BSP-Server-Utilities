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

    /** The featured server's address; "buildsecureprotect.com" before the config is loaded. */
    public static String featuredServer() {
        try {
            return FEATURED_SERVER.get();
        } catch (IllegalStateException e) {
            return "buildsecureprotect.com";
        }
    }
}
