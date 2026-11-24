package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.client.StreamChatAccess;
import com.mrgregles.bsp_server_utilities.client.StreamerMode;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Streamer Mode's chat filter (D716): while the stream picture draws chat and the streamer hid
 * {@code bsp_server_utilities:chat_coordinates}, chat draws a copy of its lines without the messages that hold coordinates
 * ({@link StreamerRules#keptChatLines}, whole messages, however they wrap); its own lines are put back as soon as it has
 * drawn. The player's chat is never changed. Client only.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentStreamMixin implements StreamChatAccess {
    @Shadow
    @Final
    @Mutable
    private List<GuiMessage.Line> trimmedMessages;
    @Unique
    @Nullable
    private List<GuiMessage.Line> bspSu$own;

    @Inject(method = "render", at = @At("HEAD"))
    private void bspSu$streamLines(GuiGraphics graphics, int tick, int mouseX, int mouseY, CallbackInfo ci) {
        if (bspSu$own == null && StreamerMode.hidingNow("bsp_server_utilities:chat_coordinates")) {
            List<GuiMessage.Line> lines = trimmedMessages;
            List<String> texts = new ArrayList<>(lines.size());
            List<Boolean> ends = new ArrayList<>(lines.size());
            for (GuiMessage.Line l : lines) {
                StringBuilder b = new StringBuilder();
                l.content().accept((i, style, cp) -> {
                    b.appendCodePoint(cp);
                    return true;
                });
                texts.add(b.toString());
                ends.add(l.endOfEntry());
            }
            boolean[] keep = StreamerRules.keptChatLines(texts, ends);
            List<GuiMessage.Line> shown = new ArrayList<>(lines.size());
            for (int i = 0; i < keep.length; i++) {
                if (keep[i]) {
                    shown.add(lines.get(i));
                }
            }
            bspSu$own = lines;
            trimmedMessages = shown;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void bspSu$ownLines(GuiGraphics graphics, int tick, int mouseX, int mouseY, CallbackInfo ci) {
        bspSu$restoreLines();
    }

    /**
     * No hover text or message tag from chat while the stream picture is drawn with chat filtered (audit of SU-135): the chat
     * screen asks after chat has drawn, from the player's own lines, so it could show a hidden message's hover text or one
     * that does not match the line under the mouse in the stream.
     */
    @Inject(method = "getClickedComponentStyleAt", at = @At("HEAD"), cancellable = true)
    private void bspSu$noStyleInStream(double x, double y, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.network.chat.Style> cir) {
        if (StreamerMode.drawingStream() && StreamerMode.chatFiltered()) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getMessageTagAt", at = @At("HEAD"), cancellable = true)
    private void bspSu$noTagInStream(double x, double y, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.client.GuiMessageTag> cir) {
        if (StreamerMode.drawingStream() && StreamerMode.chatFiltered()) {
            cir.setReturnValue(null);
        }
    }

    @Override
    public void bspSu$restoreLines() {
        if (bspSu$own != null) {
            trimmedMessages = bspSu$own;
            bspSu$own = null;
        }
    }
}
