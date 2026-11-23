package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.GriddedLists;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Select World and Multiplayer grids: the vanilla list a grid replaces keeps rendering its background, so the
 * resource pack's and FancyMenu's backgrounds stay as they are, but draws none of its rows and no scrollbar of its own.
 * Only for a list a grid has taken over ({@link GriddedLists#contains}); every other list is untouched. HEAD injects
 * only, client only.
 */
@Mixin(AbstractSelectionList.class)
public abstract class GriddedListMixin {
    @Inject(method = "renderList", at = @At("HEAD"), cancellable = true)
    private void bspSu$noRows(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (GriddedLists.contains(this)) {
            ci.cancel();
        }
    }

    /** Nothing to scroll: the list's own scrollbar is drawn only when this is above 0. */
    @Inject(method = "getMaxScroll", at = @At("HEAD"), cancellable = true)
    private void bspSu$noScroll(CallbackInfoReturnable<Integer> cir) {
        if (GriddedLists.contains(this)) {
            cir.setReturnValue(0);
        }
    }
}
