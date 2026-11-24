package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.StreamerMode;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Streamer Mode's two places in the frame (D710): the stream picture right after the GUI's projection is set up (the
 * world and its effects are drawn, nothing of the HUD yet), and the stream window when the frame is drawn. Both do
 * nothing while Streamer Mode is off. Client only.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererStreamMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Lighting;setupFor3DItems()V", shift = At.Shift.AFTER))
    private void bspSu$streamPicture(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        StreamerMode.streamPass(partialTick, renderLevel);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void bspSu$streamWindow(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        StreamerMode.present();
    }
}
