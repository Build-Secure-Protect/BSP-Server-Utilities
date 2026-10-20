package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Keeps key presses inside this mod's screens (BSP-Core's pattern): other mods' key handlers never see them while one of
 * our screens is open. Escape is left to the screen, which decides whether it closes.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class ScreenKeys {
    private static final String OURS = "com.mrgregles.bsp_server_utilities.";

    private ScreenKeys() {
    }

    public static boolean isOurs(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith(OURS);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKey(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isOurs(screen) || event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        screen.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyUp(ScreenEvent.KeyReleased.Pre event) {
        Screen screen = event.getScreen();
        if (!isOurs(screen) || event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            return;
        }
        screen.keyReleased(event.getKeyCode(), event.getScanCode(), event.getModifiers());
        event.setCanceled(true);
    }
}
