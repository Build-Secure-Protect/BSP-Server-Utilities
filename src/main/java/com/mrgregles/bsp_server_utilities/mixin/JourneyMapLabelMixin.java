package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.StreamerMode;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Streamer Mode's JourneyMap adapter for the lines under (and over) the minimap (owner's ask, D715): JourneyMap 6.0.7 draws
 * each of them through {@code LabelVars.draw(GuiGraphics, MultiBufferSource, Component)}, called from
 * {@code DisplayVars.drawInfoLabels} (checked with {@code javap}). In the stream picture, with
 * {@code journeymap:minimap_coords} hidden, a line holding coordinates ({@link StreamerRules#hasCoordinates}: the location
 * line in every form, the region line) is not drawn; the map and the other lines (biome, time, light...) stay. JourneyMap is
 * optional: without it, or if a later version renames the method, this does nothing. Client only.
 */
@Pseudo
@Mixin(targets = "journeymap.client.ui.minimap.LabelVars", remap = false)
public abstract class JourneyMapLabelMixin {
    @Inject(method = "draw(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void bspSu$hideCoordinates(GuiGraphics graphics, MultiBufferSource buffers, Component text, CallbackInfo ci) {
        if (text != null && StreamerMode.hidingNow("journeymap:minimap_coords") && StreamerRules.hasCoordinates(text.getString())) {
            ci.cancel();
        }
    }
}
