package com.mrgregles.bsp_server_utilities.client;

import net.minecraft.client.resources.language.I18n;

/**
 * Screen text from the language file ({@code assets/bsp_server_utilities/lang/en_us.json}, written by
 * {@code tools/gen_lang.py}). Keys are {@code gui.bsp_server_utilities.<key>}; values use {@code %s} for the parts filled
 * in. {@code tools/check_lang.py} checks that every key used here exists.
 */
final class Tr {
    private static final String PREFIX = "gui.bsp_server_utilities.";

    private Tr() {
    }

    static String t(String key, Object... args) {
        return I18n.get(PREFIX + key, args);
    }
}
