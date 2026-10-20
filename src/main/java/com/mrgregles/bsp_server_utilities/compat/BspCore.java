package com.mrgregles.bsp_server_utilities.compat;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

import java.util.Optional;

/**
 * The only class that touches BSP-Core, which is optional (owner decision D2). Every method answers sensibly when
 * BSP-Core is not installed; a BSP-Core older or newer than expected (a missing class or method) counts as absent.
 */
public final class BspCore {
    public static final String MODID = "bsp_core";
    private static final String CONFIG_CLASS = "com.mrgregles.bsp_core.BSPConfig";

    private BspCore() {
    }

    public static boolean isLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MODID);
    }

    /**
     * A value of BSP-Core's server config by the name of its field on {@code BSPConfig} ({@code STORAGE_SERVER_ID},
     * {@code STORAGE_HOST}, {@code REDIS_ENABLED}, ...). Empty when BSP-Core is absent, the field is missing or the
     * config is not loaded. Reflection, because the config is not in BSP-Core's api (BSP-InventorySync reads it the same way).
     */
    public static <T> Optional<T> setting(String field, Class<T> type) {
        if (!isLoaded()) {
            return Optional.empty();
        }
        try {
            Object value = Class.forName(CONFIG_CLASS).getField(field).get(null);
            if (value instanceof ForgeConfigSpec.ConfigValue<?> cv) {
                Object got = cv.get();
                return type.isInstance(got) ? Optional.of(type.cast(got)) : Optional.empty();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core setting {} not readable: {}", field, e.toString());
        }
        return Optional.empty();
    }

    /** Whether BSP-Core counts the player as an admin ({@code admin.admins}, or operators when that list is empty). */
    public static boolean isAdmin(Player player) {
        if (!isLoaded()) {
            return false;
        }
        try {
            return com.mrgregles.bsp_core.api.BspPlayers.isAdmin(player);
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core admin check failed: {}", e.toString());
            return false;
        }
    }

    /**
     * A board's line for {@link #registerBoard}: the text at the left (after {@code lead} in {@code leadColour} when
     * {@code lead} is not empty), the value at the right, the value's colour (0xRRGGBB).
     */
    public record Line(String lead, int leadColour, String text, String value, int colour) {
        public Line(String text, String value, int colour) {
            this("", 0, text, value, colour);
        }

        net.minecraft.network.chat.Component left() {
            net.minecraft.network.chat.MutableComponent c = net.minecraft.network.chat.Component.empty();
            if (!lead.isEmpty()) {
                c.append(net.minecraft.network.chat.Component.literal(lead + " ").withStyle(s -> s.withColor(leadColour)));
            }
            return c.append(net.minecraft.network.chat.Component.literal(text));
        }
    }

    /**
     * Client side: shows a board on BSP-Core's Score Screens (owner decision D60), in BSP-Core's own board look; nothing
     * when BSP-Core is absent or has no boards.
     */
    public static void registerBoard(String path, String titleKey, java.util.function.Supplier<java.util.List<Line>> lines) {
        if (!isLoaded()) {
            return;
        }
        try {
            com.mrgregles.bsp_core.api.BspBoards.register(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, path),
                    net.minecraft.network.chat.Component.translatable(titleKey), () -> lines.get().stream()
                            .map(l -> new com.mrgregles.bsp_core.api.BspBoards.BoardLine(l.left(),
                                    l.value().isEmpty() ? null : net.minecraft.network.chat.Component.literal(l.value()), net.minecraft.world.item.ItemStack.EMPTY, l.colour()))
                            .toList());
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core boards not reachable: {}", e.toString());
        }
    }

    /** Calls {@code then(player, kitName, how)} when BSP-Core gives a kit (its {@code KitEvent.Given}); nothing without BSP-Core. */
    public static void onKitGiven(TriConsumer then) {
        if (!isLoaded() || kitHooked) {
            return;
        }
        try {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((com.mrgregles.bsp_core.api.event.KitEvent.Given e) -> {
                net.minecraft.server.level.ServerPlayer p = e.getServer().getPlayerList().getPlayer(e.getPlayer());
                if (p != null) {
                    then.accept(p, e.getKitName(), e.getHow().name());
                }
            });
            kitHooked = true;
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core kit events not reachable: {}", e.toString());
        }
    }

    private static boolean kitHooked;

    public interface TriConsumer {
        void accept(net.minecraft.server.level.ServerPlayer player, String kit, String how);
    }
}
