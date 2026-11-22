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

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("inventory");
        SUPPORT_BUTTON = b.comment("Show the SUPPORT BSP key in the top left corner of the inventory (when the server has a support page).")
                .define("supportButton", true);
        MESSAGE_BUTTON = b.comment("Show the MESSAGE STAFF key in the inventory while staff have a conversation open with you (/su talk opens it too).")
                .define("messageButton", true);
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
}
