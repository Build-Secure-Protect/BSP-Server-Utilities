package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TutorialAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialGuidePacket;
import com.mrgregles.bsp_server_utilities.network.TutorialOfferPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialPopupPacket;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * The player's side of a tutorial (SU-140, SU-141): the offer card, a point's popup (a screen when it freezes the
 * player, full or small; a card on the HUD that fades when it keeps them walking, D723, D739), and the guide the world
 * renderer draws. An offer or a freezing popup that arrives while another screen is open waits until it closes (a chat
 * box open does not count, so typing is not interrupted for long).
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class ClientTutorial {
    static final long FADE_MS = 1_000L;
    @Nullable
    private static TutorialOfferPacket pendingOffer;
    @Nullable
    private static TutorialPopupPacket pendingPopup;
    /** The popup on screen or on the HUD, and the time a walking one appeared. */
    @Nullable
    private static TutorialPopupPacket current;
    @Nullable
    private static TutorialPopup currentPopup;
    private static long walkingSince;
    private static boolean walkedSent;
    @Nullable
    private static TutorialGuidePacket guide;

    private ClientTutorial() {
    }

    @Nullable
    static TutorialGuidePacket guide() {
        return guide;
    }

    public static void offer(TutorialOfferPacket p) {
        pendingOffer = p;
        tryOpen();
    }

    public static void guide(TutorialGuidePacket p) {
        guide = p.tutorial().isEmpty() ? null : p;
    }

    public static void popup(TutorialPopupPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.tutorial().isEmpty()) {
            current = null;
            currentPopup = null;
            pendingPopup = null;
            if (mc.screen instanceof TutorialPopupScreen s) {
                s.closeQuietly();
            }
            return;
        }
        TutorialPopup pop = TutorialPopup.load(p.popup());
        boolean same = current != null && current.tutorial().equals(p.tutorial()) && current.point() == p.point();
        current = p;
        currentPopup = pop;
        if (!pop.freezes()) {
            if (!same) {
                walkingSince = Util.getMillis();
                walkedSent = false;
            }
            if (mc.screen instanceof TutorialPopupScreen s) {
                s.closeQuietly();
            }
            return;
        }
        if (same && mc.screen instanceof TutorialPopupScreen s) {
            s.update(p, pop);
            return;
        }
        pendingPopup = p;
        tryOpen();
    }

    private static boolean free(@Nullable Screen screen) {
        return screen == null || screen instanceof TutorialPopupScreen || screen instanceof TutorialOfferScreen;
    }

    private static void tryOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !free(mc.screen)) {
            return;
        }
        if (pendingOffer != null) {
            TutorialOfferPacket o = pendingOffer;
            pendingOffer = null;
            mc.setScreen(new TutorialOfferScreen(o));
            return;
        }
        if (pendingPopup != null && current == pendingPopup && currentPopup != null) {
            pendingPopup = null;
            if (mc.screen instanceof TutorialPopupScreen old) {
                old.markDone(); // replaced by the next popup: its leaving is not a hide (audit of SU-146)
            }
            mc.setScreen(new TutorialPopupScreen(current, currentPopup));
        }
    }

    static void send(TutorialAnswerPacket a) {
        ServerUtilsNetwork.CHANNEL.sendToServer(a);
    }

    /** A freezing popup closed with Escape (or replaced by another screen): shown again with /tutorial resume. */
    static void hidden(TutorialPopupPacket p) {
        pendingPopup = null;
        current = null;
        currentPopup = null;
        send(new TutorialAnswerPacket(TutorialAnswerPacket.Action.HIDDEN, p.tutorial(), p.point(), -1));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(Tr.t("tutorial.hidden")), false);
        }
    }

    /** A button pressed: the popup goes, the server answers with what comes next. */
    static void pressed(TutorialPopupPacket p, int index) {
        current = null;
        currentPopup = null;
        send(new TutorialAnswerPacket(TutorialAnswerPacket.Action.BUTTON, p.tutorial(), p.point(), index));
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if ((pendingOffer != null || pendingPopup != null) && !(mc.screen instanceof ChatScreen)) {
            tryOpen();
        }
        if (current != null && currentPopup != null && !currentPopup.freezes() && !walkedSent
                && Util.getMillis() - walkingSince >= currentPopup.seconds() * 1000L + FADE_MS) {
            walkedSent = true;
            TutorialPopupPacket p = current;
            current = null;
            currentPopup = null;
            send(new TutorialAnswerPacket(TutorialAnswerPacket.Action.WALKED, p.tutorial(), p.point(), -1));
        }
    }

    /** A popup that keeps the player walking: drawn on the HUD where the admin placed it, with a bar for the time left, then a fade. */
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        TutorialPopupPacket p = current;
        TutorialPopup pop = currentPopup;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || pop == null || pop.freezes() || mc.options.hideGui || mc.screen instanceof TutorialPopupScreen) {
            return;
        }
        long shown = Util.getMillis() - walkingSince, total = pop.seconds() * 1000L;
        float alpha = shown <= total ? 1f : Math.max(0f, 1f - (shown - total) / (float) FADE_MS);
        if (alpha <= 0.02f) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth(), sh = g.guiHeight();
        int[] r = TutorialPopupScreen.smallRect(pop, sw, sh);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.setColor(1f, 1f, 1f, alpha); // the fade, for fills, text and pictures alike (once: audit of SU-146)
        TutorialPopupScreen.drawSmall(g, mc.font, p, pop, r[0], r[1], r[2], r[3], 1f);
        int left = (int) (r[2] * Math.max(0f, 1f - shown / (float) total));
        g.fill(r[0], r[1] + r[3] - 2, r[0] + left, r[1] + r[3], Ui.TEAL);
        g.setColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingOffer = null;
        pendingPopup = null;
        current = null;
        currentPopup = null;
        guide = null;
    }
}
