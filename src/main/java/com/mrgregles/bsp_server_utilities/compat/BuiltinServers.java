package com.mrgregles.bsp_server_utilities.compat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;

/**
 * The only class that touches Builtin Servers (the pack's mod that adds BSP Official to every player's server list),
 * which is optional. It marks its entries on vanilla's {@code ServerData} through its own interface; the Multiplayer grid
 * reads that mark here to show the mod's badge, as the mod shows it on vanilla's rows. Absent, or a version with a
 * different interface, counts as no badge. Client use only (it reads server-list entries).
 */
public final class BuiltinServers {
    public static final String MODID = "builtinservers";
    private static final String MIXED = "io.github.lucaargolo.builtinservers.mixed.MixedServerInfo";
    private static Class<?> mixed;
    private static Method isBuiltin;
    private static Method badgeVisible;
    private static Object config;
    private static boolean looked;

    private BuiltinServers() {
    }

    private static boolean ready() {
        if (!looked) {
            looked = true;
            try {
                if (ModList.get() != null && ModList.get().isLoaded(MODID)) {
                    mixed = Class.forName(MIXED);
                    isBuiltin = mixed.getMethod("builtinservers_isBuiltin");
                    try {
                        config = Class.forName("io.github.lucaargolo.builtinservers.BuiltinServers").getField("CONFIG").get(null);
                        badgeVisible = config == null ? null : config.getClass().getMethod("isBadgeVisible");
                    } catch (ReflectiveOperationException | LinkageError e) {
                        badgeVisible = null; // the badge then shows, as the mod's default
                    }
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                mixed = null;
                isBuiltin = null;
            }
        }
        return isBuiltin != null;
    }

    /** Whether Builtin Servers added this server-list entry (a {@code ServerData}), so it carries the mod's badge. */
    public static boolean isBuiltin(Object serverData) {
        if (serverData == null || !ready() || !mixed.isInstance(serverData)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isBuiltin.invoke(serverData));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Whether the mod's own setting shows its badge ({@code badgeVisible} in its config; true when it cannot be read). */
    public static boolean badgeVisible() {
        if (!ready() || badgeVisible == null) {
            return true;
        }
        try {
            return !Boolean.FALSE.equals(badgeVisible.invoke(config));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** The mod's badge in its own words and colour ("Builtin", gold). */
    public static Component badge() {
        return Component.translatable("builtinservers.badge").withStyle(ChatFormatting.GOLD);
    }

    /** The mod's own hover text for the badge. */
    public static Component description() {
        return Component.translatable("builtinservers.description");
    }
}
