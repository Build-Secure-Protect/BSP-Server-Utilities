package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Multiplayer's server list as a grid: the featured server (BSP) full width on top, every other server a square below.
 * The screen stays vanilla's {@link JoinMultiplayerScreen} (FancyMenu, Builtin Servers and other mods know it by its
 * class) and its {@link ServerSelectionList} stays the model: the saved servers, the LAN games, the selection, the
 * buttons, the pinging and the icons are vanilla's. The hook only puts a {@link ServerGridWidget} in the list's place
 * ({@link GriddedLists#takeOver}). The client setting {@code menus.serverGrid} off leaves the screen wholly vanilla.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class ServerGridHook {
    private static boolean failed;

    private ServerGridHook() {
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }
        if (failed || !ServerUtilsClientConfig.serverGrid()) {
            // the screen keeps its list across re-inits: turned off meanwhile, it draws its rows again
            GriddedLists.release(screen, ServerSelectionList.class);
            return;
        }
        boolean hadList = screen.children().stream().anyMatch(c -> c instanceof ServerSelectionList);
        // the vanilla list's bounds: top 32, bottom height - 64
        boolean done = GriddedLists.takeOver(event, ServerSelectionList.class,
                list -> new ServerGridWidget(screen, list, 0, 32, screen.width, Math.max(0, screen.height - 64 - 32)),
                "Multiplayer grid");
        failed = hadList && !done;
    }
}
