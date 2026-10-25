package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.ClientSetupGuide;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the setup card (D155) when Forge's HUD has finished, after every {@code RenderGuiEvent.Post} listener: minimaps
 * such as JourneyMap's draw there at the lowest priority and would otherwise cover the card. RETURN only, client only.
 */
@Mixin(ForgeGui.class)
public abstract class ForgeGuiMixin {
    @Inject(method = "render", at = @At("RETURN"), remap = true)
    private void bspSu$setupCard(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        ClientSetupGuide.drawHud(graphics);
    }
}
