package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.StreamerMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the player types, in the stream (D716; audit of SU-135): while the stream picture draws the chat screen, its input
 * box (with the grey suggestion inside it) is not drawn when {@link StreamerMode#hideTyping} says so: chat hidden, or chat's
 * coordinates hidden and the text is a command or holds a position. Done by hiding the box for that one draw (plain
 * {@code @Inject}s, so no other chat mod's hook can clash with it). The player's own window always shows it. Client only.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenStreamMixin {
    @Shadow
    protected EditBox input;
    @Unique
    private boolean bspSu$wasVisible;
    @Unique
    private boolean bspSu$hid;

    @Inject(method = "render", at = @At("HEAD"))
    private void bspSu$hideTyping(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        bspSu$hid = false;
        if (input != null && StreamerMode.drawingStream() && StreamerMode.hideTyping(input.getValue())) {
            bspSu$wasVisible = input.visible;
            input.visible = false;
            bspSu$hid = true;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void bspSu$showTyping(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (bspSu$hid) {
            input.visible = bspSu$wasVisible;
            bspSu$hid = false;
        }
    }
}
