package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraftforge.client.event.ScreenEvent;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * The vanilla selection lists a grid has taken over (the Select World and the Multiplayer screens): such a list draws its
 * background but no rows and no scrollbar ({@code mixin/GriddedListMixin}). Weak, so a closed screen's list goes with it;
 * used on the render thread only.
 */
public final class GriddedLists {
    private static final Set<Object> LISTS = Collections.newSetFromMap(new WeakHashMap<>());
    /** A grid's scroll position per list, so a list the screen keeps across re-inits keeps it too (as vanilla's does). */
    private static final java.util.Map<Object, Double> SCROLL = new WeakHashMap<>();

    private GriddedLists() {
    }

    public static boolean contains(Object list) {
        return !LISTS.isEmpty() && LISTS.contains(list);
    }

    static double scrollOf(Object list) {
        return SCROLL.getOrDefault(list, 0.0);
    }

    static void rememberScroll(Object list, double scroll) {
        SCROLL.put(list, scroll);
    }

    /**
     * The screen's lists of {@code type} are its own again: on an init with the grid turned off, a list the screen kept
     * from when it was on draws its rows again.
     */
    static void release(net.minecraft.client.gui.screens.Screen screen, Class<?> type) {
        if (LISTS.isEmpty()) {
            return;
        }
        for (GuiEventListener c : screen.children()) {
            if (type.isInstance(c)) {
                LISTS.remove(c);
            }
        }
    }

    /**
     * On a screen's {@code ScreenEvent.Init.Post}: takes the screen's first list of {@code type} out of its input and
     * narration, puts the grid {@code make} builds for it in the list's place (the same place in the Tab order), and marks
     * the list. The screen keeps the list as its model. Returns false when there was no such list or something failed;
     * a failure leaves the screen as vanilla's and is logged once by the caller's {@code what}.
     */
    static <L extends AbstractSelectionList<?>> boolean takeOver(ScreenEvent.Init.Post event, Class<L> type,
                                                                Function<L, AbstractWidget> make, String what) {
        // the screen's own list: Forge's event.getListenersList() is read-only (Collections.unmodifiableList), and
        // reordering it throws (SU-126)
        @SuppressWarnings("unchecked")
        List<GuiEventListener> children = (List<GuiEventListener>) event.getScreen().children();
        int at = -1;
        L list = null;
        for (int i = 0; i < children.size(); i++) {
            if (type.isInstance(children.get(i))) {
                list = type.cast(children.get(i));
                at = i;
                break;
            }
        }
        if (list == null) {
            return false;
        }
        AbstractWidget grid = null;
        try {
            grid = make.apply(list);
            // the list was added as a widget only (vanilla's render draws it by hand): out of the input and narration
            event.removeListener(list);
            event.addListener(grid);
            children.remove(grid);
            children.add(Math.min(at, children.size()), grid);
            LISTS.add(list);
            return true;
        } catch (RuntimeException | LinkageError e) {
            undo(event, children, list, grid, at);
            BSPServerUtilities.LOGGER.warn("{}: could not take over the list, so it shows as vanilla's: {}", what, e.toString());
            return false;
        }
    }

    /**
     * Puts the vanilla list back as it was (in the screen's input, at its place) and the grid out, and never throws: the
     * screen then works as vanilla's. Only the screen's own list is changed here, never the event's read-only copy.
     */
    private static void undo(ScreenEvent.Init.Post event, List<GuiEventListener> children, Object list, AbstractWidget grid, int at) {
        try {
            LISTS.remove(list);
            if (grid != null) {
                event.removeListener(grid);
            }
            if (list instanceof GuiEventListener l && !children.contains(l)) {
                // back into the input and the narration, as vanilla's addWidget put it, but not the renderables: the
                // screen draws the list by hand
                event.addListener(l);
                event.getScreen().renderables.remove(l);
                children.remove(l);
                children.add(Math.min(at, children.size()), l);
            }
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("Could not put a vanilla list back: {}", e.toString());
        }
    }
}
