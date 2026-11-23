package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Singleplayer's Select World screen as a grid of world cards (artboard W1). The screen stays vanilla's
 * {@link SelectWorldScreen} (FancyMenu and other mods know it by its class) and its list stays the model: it still loads
 * the worlds, filters them on search, holds the selection and drives the buttons. The hook only puts a
 * {@link WorldGridWidget} in the list's place ({@link GriddedLists#takeOver}). The client setting {@code menus.worldGrid}
 * off leaves the screen wholly vanilla.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class WorldGridHook {
    private static boolean failed;

    private WorldGridHook() {
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SelectWorldScreen screen)) {
            return;
        }
        if (failed || !ServerUtilsClientConfig.worldGrid()) {
            GriddedLists.release(screen, WorldSelectionList.class);
            return;
        }
        boolean hadList = screen.children().stream().anyMatch(c -> c instanceof WorldSelectionList);
        // the vanilla list's bounds: top 48, bottom height - 64
        boolean done = GriddedLists.takeOver(event, WorldSelectionList.class,
                list -> new WorldGridWidget(screen, list, 0, 48, screen.width, Math.max(0, screen.height - 64 - 48)),
                "Select World grid");
        // a failure turns the grid off until the game restarts
        failed = hadList && !done;
    }
}
