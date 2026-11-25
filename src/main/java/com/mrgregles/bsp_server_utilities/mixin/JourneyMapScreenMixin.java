package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.StreamerMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Streamer Mode's JourneyMap adapter for menus: besides the HUD ({@link JourneyMapHudMixin}), JourneyMap 6.0.7 draws its minimap
 * behind the menus it allows (the pause menu among them) from {@code ScreenEventHandler.onScreenPreRender}, called from the menu's
 * render event (checked with {@code javap}: it calls {@code MiniMap.drawMap}). The stream picture draws the open menu too, so a hidden
 * minimap showed behind the pause menu on stream. It is skipped in the stream picture when the streamer hid
 * {@code journeymap:minimap}; the player's own window keeps it. JourneyMap is optional: without it, or if a later version renames the
 * method, this does nothing. Client only.
 */
@Pseudo
@Mixin(targets = "journeymap.client.event.handlers.ScreenEventHandler", remap = false)
public abstract class JourneyMapScreenMixin {
    @Inject(method = "onScreenPreRender(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphics;II)V", at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    private void bspSu$hideMenuMinimap(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (StreamerMode.hidingNow("journeymap:minimap")) {
            ci.cancel();
        }
    }
}
