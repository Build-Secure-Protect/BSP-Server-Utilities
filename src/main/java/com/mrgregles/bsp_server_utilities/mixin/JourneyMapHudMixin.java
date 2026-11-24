package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.StreamerMode;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Streamer Mode's JourneyMap adapter (D706): JourneyMap 6.0.7 draws its whole minimap, with its coordinate, biome and
 * time lines, from {@code HudOverlayHandler.onRenderOverlay} (checked with {@code javap}: it calls
 * {@code UIManager.drawMiniMap}), from whichever HUD event its placement setting uses. It is skipped in the stream picture
 * when the streamer hid {@code journeymap:minimap}. JourneyMap is optional: without it, or if a later version renames the
 * method, this does nothing. Client only.
 */
@Pseudo
@Mixin(targets = "journeymap.client.event.handlers.HudOverlayHandler", remap = false)
public abstract class JourneyMapHudMixin {
    @Inject(method = "onRenderOverlay(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void bspSu$hideMinimap(GuiGraphics graphics, CallbackInfo ci) {
        if (StreamerMode.hidingNow("journeymap:minimap")) {
            ci.cancel();
        }
    }
}
