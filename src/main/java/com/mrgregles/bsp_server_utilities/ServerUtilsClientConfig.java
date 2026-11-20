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

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("inventory");
        SUPPORT_BUTTON = b.comment("Show the SUPPORT BSP key in the top left corner of the inventory (when the server has a support page).")
                .define("supportButton", true);
        b.pop();
        SPEC = b.build();
    }

    private ServerUtilsClientConfig() {
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
