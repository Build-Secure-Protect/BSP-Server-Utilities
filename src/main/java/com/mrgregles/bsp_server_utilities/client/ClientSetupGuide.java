package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.SetupCardPacket;
import com.mrgregles.bsp_server_utilities.network.SetupEditPacket;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The setup card in the top right corner (owner decisions D155, D159, D160), as on {@code tools/preview/setup_refined.html}:
 * drawn over everything else on the HUD (minimaps included); it does not stop the game; its keys (SETUP NOW or CONTINUE SETUP, LATER, IGNORE SETUP, and × for later) can be
 * clicked whenever chat is open; it stays until a choice is made and is hidden while any other screen is open.
 */
public final class ClientSetupGuide {
    private static final int W = 160, PAD = 6;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    @Nullable
    private static SetupCardPacket card;
    private static final List<Ui.Hit> HITS = new ArrayList<>();

    private ClientSetupGuide() {
    }

    public static void card(SetupCardPacket p) {
        card = p.show() ? p : null;
    }

    /** Once, at client set-up. */
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Render.Post e) -> {
            if (card != null && e.getScreen() instanceof ChatScreen) {
                draw(e.getGuiGraphics(), e.getMouseX(), e.getMouseY(), true);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (card != null && e.getScreen() instanceof ChatScreen && e.getButton() == 0) {
                for (Ui.Hit h : List.copyOf(HITS)) {
                    if (h.contains(e.getMouseX(), e.getMouseY())) {
                        h.action().run();
                        e.setCanceled(true);
                        return;
                    }
                }
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> card = null);
    }

    /**
     * The card on the HUD, while no screen is open; called when Forge's HUD has finished ({@code mixin/ForgeGuiMixin}),
     * so a minimap drawn at the end of the HUD (JourneyMap's) is under it. Depth testing is off while it draws, so
     * nothing drawn before can cover it.
     */
    public static void drawHud(GuiGraphics g) {
        if (card == null || Minecraft.getInstance().screen != null) {
            return;
        }
        g.flush();
        RenderSystem.disableDepthTest();
        draw(g, -1, -1, false);
        g.flush();
        RenderSystem.enableDepthTest();
    }

    private static void choose(SetupEditPacket.Action a) {
        card = null;
        HITS.clear();
        ServerUtilsNetwork.CHANNEL.sendToServer(SetupEditPacket.of(a));
    }

    private static void draw(GuiGraphics g, int mx, int my, boolean clickable) {
        SetupCardPacket c = card;
        if (c == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        HITS.clear();
        int x = mc.getWindow().getGuiScaledWidth() - W - 6, y = 6;
        List<String> body;
        String title;
        if (c.first()) {
            title = Tr.t("admin.setup.card.title");
            body = Ui.wrap(font, Tr.t("admin.setup.card.text"), W - 2 * PAD, TINY);
        } else {
            title = Tr.t("admin.setup.card.left_title", c.left().size());
            List<String> names = new ArrayList<>();
            for (Feature f : c.left()) {
                names.add(SetupGuide.featureName(f));
            }
            body = Ui.wrap(font, Tr.t("admin.setup.card.left_text", String.join(", ", names)), W - 2 * PAD, TINY);
        }
        List<String> hint = clickable ? List.of() : Ui.wrap(font, Tr.t("admin.setup.card.hint"), W - 2 * PAD, TINY);
        int h = PAD + 9 + body.size() * 7 + 4 + 13 + (hint.isEmpty() ? 0 : 3 + hint.size() * 7) + PAD;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400); // over the chat and the hotbar
        g.fill(x, y, x + W, y + h, Ui.PANEL);
        Ui.frame(g, x, y, W, h, Ui.VIOLET);
        Ui.text(g, font, Component.literal(Ui.fit(font, title, W - 2 * PAD - 10, SMALL)).withStyle(ChatFormatting.BOLD), x + PAD, y + PAD, Ui.VIOLET, SMALL);
        Ui.text(g, font, "×", x + W - PAD - 4, y + PAD - 1, Ui.MUTED, SMALL);
        HITS.add(new Ui.Hit(x + W - PAD - 7, y + 2, 10, 10, () -> choose(SetupEditPacket.Action.CARD_LATER)));
        int ty = y + PAD + 10;
        for (String line : body) {
            Ui.text(g, font, line, x + PAD, ty, c.first() ? Ui.TEXT : Ui.GOLD, TINY);
            ty += 7;
        }
        int ky = ty + 4, kx = x + PAD;
        kx += key(g, font, kx, ky, Tr.t(c.first() ? "admin.setup.card.now" : "admin.setup.card.continue"), 0, mx, my, () -> choose(SetupEditPacket.Action.OPEN)) + 3;
        kx += key(g, font, kx, ky, Tr.t("admin.setup.card.later"), 1, mx, my, () -> choose(SetupEditPacket.Action.CARD_LATER)) + 3;
        key(g, font, kx, ky, Tr.t("admin.setup.card.ignore"), 2, mx, my, () -> choose(SetupEditPacket.Action.IGNORE));
        int hy = ky + 16;
        for (String line : hint) {
            Ui.text(g, font, line, x + PAD, hy, Ui.MUTED, TINY);
            hy += 7;
        }
        g.pose().popPose();
        if (!clickable) {
            HITS.clear();
        }
    }

    /** style 0 violet, 1 plain, 2 ghost; returns the width. */
    private static int key(GuiGraphics g, Font font, int x, int y, String label, int style, int mx, int my, Runnable action) {
        int w = Ui.width(font, label, TINY) + 10, h = 12;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        if (style == 0) {
            g.fill(x, y, x + w, y + h, hover ? 0xFFC8A6FF : Ui.VIOLET);
        } else if (style == 1) {
            g.fill(x, y, x + w, y + h, hover ? 0xFF3A404C : Ui.DIM);
        } else {
            Ui.frame(g, x, y, w, h, Ui.DIM);
        }
        int colour = style == 0 ? Ui.INK : style == 1 ? Ui.TEXT : Ui.MUTED;
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + 5, y + 4, colour, TINY);
        HITS.add(new Ui.Hit(x, y, w, h, action));
        return w;
    }
}
