package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.StreamerMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command suggestions in the stream (audit of SU-135): typing {@code /tp } while looking at a block makes Minecraft offer
 * that block's position, and usage and error lines repeat what was typed. While the stream picture is drawn with chat or
 * chat's coordinates hidden, the suggestion list and those lines are not drawn there. The player's window keeps them.
 * Client only.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsStreamMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void bspSu$hideSuggestions(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (StreamerMode.drawingStream() && StreamerMode.chatFiltered()) {
            ci.cancel();
        }
    }
}
