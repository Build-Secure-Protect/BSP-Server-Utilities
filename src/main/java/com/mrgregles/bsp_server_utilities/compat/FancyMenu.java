package com.mrgregles.bsp_server_utilities.compat;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The only class that touches FancyMenu (the pack's menu designer, optional), by name: where its elements stand on a
 * screen, so Streamer Mode's eye key steps past them as it steps past widgets (the first test: on the pack's title screen
 * the key covered the corner of the Discord box, a FancyMenu element that is not one of the screen's widgets). Read from
 * FancyMenu 3.9.14's {@code ScreenCustomizationLayerHandler.getLayerOfScreen(screen).allElements} and each element's
 * {@code getAbsoluteX/Y/Width/Height} while it {@code shouldRender}. Elements as big as a quarter of the screen or more
 * (backgrounds) are left out, or nothing would ever fit. Absent, or another version that answers differently, counts as no
 * elements. Client use only.
 */
public final class FancyMenu {
    public static final String MODID = "fancymenu";
    private static boolean looked;
    /** After a read fails, FancyMenu is not asked again until this time (ms): a passing error does not end it for the session. */
    private static long brokenUntil;
    private static Method layerOf, shouldRender, ax, ay, aw, ah;
    private static Field all;

    private FancyMenu() {
    }

    private static boolean ready() {
        if (!looked) {
            looked = true;
            try {
                if (ModList.get() != null && ModList.get().isLoaded(MODID)) {
                    Class<?> handler = Class.forName("de.keksuccino.fancymenu.customization.layer.ScreenCustomizationLayerHandler");
                    Class<?> layer = Class.forName("de.keksuccino.fancymenu.customization.layer.ScreenCustomizationLayer");
                    Class<?> element = Class.forName("de.keksuccino.fancymenu.customization.element.AbstractElement");
                    layerOf = handler.getMethod("getLayerOfScreen", net.minecraft.client.gui.screens.Screen.class);
                    all = layer.getField("allElements");
                    shouldRender = element.getMethod("shouldRender");
                    ax = element.getMethod("getAbsoluteX");
                    ay = element.getMethod("getAbsoluteY");
                    aw = element.getMethod("getAbsoluteWidth");
                    ah = element.getMethod("getAbsoluteHeight");
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                layerOf = null;
                BSPServerUtilities.LOGGER.info("BSP-Server Utilities: FancyMenu's elements cannot be read ({}); the eye key steps past widgets only", e.toString());
            }
        }
        return layerOf != null && System.currentTimeMillis() >= brokenUntil;
    }

    /** FancyMenu's elements drawn on this screen now, as {x, y, width, height}; empty without FancyMenu. */
    public static List<int[]> areas(net.minecraft.client.gui.screens.Screen screen) {
        if (!ready()) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        try {
            Object layer = layerOf.invoke(null, screen);
            if (layer == null || !(all.get(layer) instanceof List<?> elements)) {
                return List.of();
            }
            long quarter = (long) screen.width * screen.height / 4;
            for (Object e : elements) {
                // FancyMenu's stand-ins for the screen's own widgets (the eye key's among them) are left out: the widgets
                // themselves are already stepped past, and the key must not step past its own stand-in
                if (e == null || e.getClass().getName().endsWith("VanillaWidgetElement") || !Boolean.TRUE.equals(shouldRender.invoke(e))) {
                    continue;
                }
                int x = (int) ax.invoke(e), y = (int) ay.invoke(e), w = (int) aw.invoke(e), h = (int) ah.invoke(e);
                if (w > 0 && h > 0 && (long) w * h < quarter) {
                    out.add(new int[]{x, y, w, h});
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            brokenUntil = System.currentTimeMillis() + 30_000L; // tried again in 30 s (audit, SU-135)
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities: FancyMenu's elements could not be read ({}); the eye key steps past widgets only", e.toString());
            return List.of();
        }
        return out;
    }
}
