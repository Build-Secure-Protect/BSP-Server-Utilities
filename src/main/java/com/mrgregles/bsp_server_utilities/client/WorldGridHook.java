package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Singleplayer's Select World screen as a grid of world cards (artboard W1). The screen stays vanilla's
 * {@link SelectWorldScreen} (FancyMenu and other mods know it by its class) and its list stays the model: it still loads
 * the worlds, filters them on search, holds the selection and drives the buttons. The hook only takes the list out of the
 * screen's input and narration, puts a {@link WorldGridWidget} in its place (the same bounds, the same place in the Tab
 * order), and marks the list so it draws its background but no rows ({@code mixin/WorldListGridMixin}). The client
 * setting {@code menus.worldGrid} off leaves the screen wholly vanilla.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class WorldGridHook {
    /** The lists the grid has taken over; weak, so a closed screen's list goes with it. */
    private static final Set<Object> GRIDDED = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean failed;

    private WorldGridHook() {
    }

    /** Whether this selection list is one the grid replaced (it then draws no rows and no scrollbar). */
    public static boolean isGridded(Object list) {
        return !GRIDDED.isEmpty() && GRIDDED.contains(list);
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SelectWorldScreen screen) || failed || !ServerUtilsClientConfig.worldGrid()) {
            return;
        }
        // the screen's own list: Forge's event.getListenersList() is read-only (Collections.unmodifiableList), and
        // reordering it throws, which crashed the client before (SU-126)
        @SuppressWarnings("unchecked")
        List<GuiEventListener> children = (List<GuiEventListener>) screen.children();
        int at = -1;
        WorldSelectionList list = null;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i) instanceof WorldSelectionList l) {
                list = l;
                at = i;
                break;
            }
        }
        if (list == null) {
            return;
        }
        WorldGridWidget grid = null;
        try {
            // the vanilla list's bounds: top 48, bottom height - 64
            grid = new WorldGridWidget(screen, list, 0, 48, screen.width, Math.max(0, screen.height - 64 - 48));
            // the list was added as a widget only (vanilla's render draws it by hand): out of the input and narration
            event.removeListener(list);
            event.addListener(grid);
            // where the list was, so Tab still goes search box, worlds, then the buttons
            children.remove(grid);
            children.add(Math.min(at, children.size()), grid);
            GRIDDED.add(list);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            undo(event, children, list, grid, at);
            BSPServerUtilities.LOGGER.warn("Select World grid: could not take over the world list, so it shows as vanilla's: {}", e.toString());
        }
    }

    /**
     * Puts the vanilla list back as it was (in the screen's input, at its place) and the grid out, and never throws: the
     * screen then works as vanilla's, without the grid until the game restarts. Only the screen's own list is changed
     * here, never the event's read-only copy.
     */
    private static void undo(ScreenEvent.Init.Post event, List<GuiEventListener> children, WorldSelectionList list,
                             WorldGridWidget grid, int at) {
        try {
            GRIDDED.remove(list);
            if (grid != null) {
                event.removeListener(grid);
            }
            if (!children.contains(list)) {
                // into the input only, as vanilla's addWidget put it: the screen draws the list by hand
                children.add(Math.min(at, children.size()), list);
            }
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("Select World grid: could not put the world list back: {}", e.toString());
        }
    }
}
