package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mrgregles.bsp_server_utilities.network.AfkAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.AfkCheckPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The presence check's card (owner decisions D220, D222, D223), as on {@code tools/preview/afk_check_refined.html}:
 * "STILL THERE?", the time left, the word to click and three keys in the server's order. It sits at a random place for
 * each check (at least 8 pixels from the edges, never over the hotbar or the crosshair) and is drawn over everything:
 * on the HUD after Forge's HUD has finished ({@code mixin/ForgeGuiMixin}, so JourneyMap's minimap is under it), and over
 * any open screen, where it is clicked directly; with no screen open, T opens chat and frees the mouse. The server
 * decides: a click only sends the key's word, and the card changes or goes when the server answers.
 */
public final class ClientAfkCheck {
    private static final int W = 160, PAD = 6, EDGE = 8, KEY_H = 12;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int KEY_HOVER = 0xFF3A404C;
    /** The eight woods, in the server's order (its word indexes). */
    private static final String[] WORD_KEYS = {"afk.card.word.oak", "afk.card.word.spruce", "afk.card.word.birch", "afk.card.word.jungle",
            "afk.card.word.acacia", "afk.card.word.dark_oak", "afk.card.word.mangrove", "afk.card.word.cherry"};
    private static final List<Ui.Hit> HITS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    @Nullable
    private static AfkCheckPacket card;
    private static long deadline;
    private static int placedFor, x, y, sentRound = -1;

    private ClientAfkCheck() {
    }

    public static void accept(AfkCheckPacket p) {
        if (p.hide()) {
            card = null;
            HITS.clear();
            return;
        }
        card = p;
        deadline = System.currentTimeMillis() + p.msLeft();
        sentRound = -1;
    }

    /** Once, at client set-up. */
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Render.Post e) -> {
            if (card != null) {
                drawOnTop(e.getGuiGraphics(), e.getMouseX(), e.getMouseY(), true);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.MouseButtonPressed.Pre e) -> {
            if (card != null && e.getButton() == 0) {
                for (Ui.Hit h : List.copyOf(HITS)) {
                    if (h.contains(e.getMouseX(), e.getMouseY())) {
                        h.action().run();
                        e.setCanceled(true);
                        return;
                    }
                }
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            card = null;
            HITS.clear();
        });
    }

    /** The card on the HUD while no screen is open (an open screen draws it over itself), last of all. */
    public static void drawHud(GuiGraphics g) {
        if (card == null || Minecraft.getInstance().screen != null) {
            return;
        }
        drawOnTop(g, -1, -1, false);
    }

    private static void drawOnTop(GuiGraphics g, int mx, int my, boolean clickable) {
        g.flush();
        RenderSystem.disableDepthTest(); // nothing drawn before can cover it
        draw(g, mx, my, clickable);
        g.flush();
        RenderSystem.enableDepthTest();
    }

    private static void answer(int word) {
        AfkCheckPacket c = card;
        if (c == null || sentRound == c.round()) {
            return; // one answer to each word; the server's reply changes the card
        }
        sentRound = c.round();
        ServerUtilsNetwork.CHANNEL.sendToServer(new AfkAnswerPacket(c.id(), c.round(), word));
    }

    private static String word(int i) {
        return Tr.t(WORD_KEYS[Math.floorMod(i, WORD_KEYS.length)]).toUpperCase(Locale.ROOT);
    }

    /** The instruction with the word in gold, wrapped to the card. */
    private static List<FormattedCharSequence> body(Font font, AfkCheckPacket c, boolean hint) {
        String mark = "\u0001";
        String[] parts = Tr.t(c.kick() ? "afk.card.click" : "afk.card.click_staff", mark).split(mark, -1);
        Component line = Component.literal(parts[0])
                .append(Component.literal(word(c.word())).withStyle(s -> s.withColor(Ui.GOLD & 0xFFFFFF).withBold(true)))
                .append(Component.literal(parts.length > 1 ? parts[1] : ""));
        if (hint) {
            line = line.copy().append(" " + Tr.t("afk.card.hint"));
        }
        return font.split(line, (int) ((W - 2 * PAD) / TINY));
    }

    private static int height(int bodyLines, int wrongLines) {
        return PAD + 10 + bodyLines * 7 + (wrongLines == 0 ? 0 : 2 + wrongLines * 7) + 4 + KEY_H + PAD;
    }

    /** A new random place for a new check, kept for that check (D222): clear of the edges, the hotbar and the crosshair. */
    private static void place(int sw, int sh, int h) {
        int hotbarLeft = sw / 2 - 91 - 30, hotbarRight = sw / 2 + 91 + 30, hotbarTop = sh - 40;
        int cx = sw / 2, cy = sh / 2;
        for (int i = 0; i < 60; i++) {
            int px = EDGE + RANDOM.nextInt(Math.max(1, sw - 2 * EDGE - W + 1)), py = EDGE + RANDOM.nextInt(Math.max(1, sh - 2 * EDGE - h + 1));
            boolean onHotbar = px < hotbarRight && px + W > hotbarLeft && py + h > hotbarTop;
            boolean onCrosshair = px < cx + 8 && px + W > cx - 8 && py < cy + 8 && py + h > cy - 8;
            if (!onHotbar && !onCrosshair) {
                x = px;
                y = py;
                return;
            }
        }
        x = EDGE;
        y = EDGE;
    }

    private static void draw(GuiGraphics g, int mx, int my, boolean clickable) {
        AfkCheckPacket c = card;
        HITS.clear();
        if (c == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        boolean hint = mc.screen == null;
        List<FormattedCharSequence> body = body(font, c, hint);
        List<String> wrong = c.wrong() ? Ui.wrap(font, Tr.t("afk.card.wrong"), W - 2 * PAD, TINY) : List.of();
        if (placedFor != c.id()) {
            placedFor = c.id();
            place(sw, sh, height(body(font, c, true).size(), 2)); // room for the hint and a wrong line
        }
        int h = height(body.size(), wrong.size());
        int cx = Math.max(EDGE, Math.min(x, sw - EDGE - W)), cy = Math.max(EDGE, Math.min(y, sh - EDGE - h));

        g.pose().pushPose();
        g.pose().translate(0, 0, 500); // over the chat, the hotbar, other mods' HUDs and any screen
        g.fill(cx, cy, cx + W, cy + h, Ui.PANEL);
        Ui.frame(g, cx, cy, W, h, Ui.TEAL);
        Ui.text(g, font, Component.literal(Ui.fit(font, Tr.t("afk.card.title"), W - 2 * PAD - 24, SMALL)).withStyle(ChatFormatting.BOLD), cx + PAD, cy + PAD, Ui.TEAL, SMALL);
        long left = Math.max(0, deadline - System.currentTimeMillis());
        String clock = (left / 60_000) + ":" + String.format(Locale.ROOT, "%02d", (left / 1000) % 60);
        Ui.text(g, font, clock, cx + W - PAD - Ui.width(font, clock, SMALL), cy + PAD, Ui.MUTED, SMALL);
        int ty = cy + PAD + 10;
        for (FormattedCharSequence line : body) {
            g.pose().pushPose();
            g.pose().translate(cx + PAD, ty, 0);
            g.pose().scale(TINY, TINY, 1);
            g.drawString(font, line, 0, 0, Ui.TEXT, false);
            g.pose().popPose();
            ty += 7;
        }
        if (!wrong.isEmpty()) {
            ty += 2;
            for (String line : wrong) {
                Ui.text(g, font, line, cx + PAD, ty, Ui.BAD, TINY);
                ty += 7;
            }
        }
        int kx = cx + PAD, ky = ty + 4, room = W - 2 * PAD - 2 * 4, each = room / c.keys().size();
        for (int k : c.keys()) {
            String label = Ui.fit(font, word(k), each - 6, TINY);
            int kw = Math.min(each, Ui.width(font, label, TINY) + 12);
            boolean hover = clickable && mx >= kx && mx < kx + kw && my >= ky && my < ky + KEY_H;
            g.fill(kx, ky, kx + kw, ky + KEY_H, hover ? KEY_HOVER : Ui.DIM);
            Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), kx + (kw - Ui.width(font, label, TINY)) / 2, ky + 4, Ui.TEXT, TINY);
            if (clickable) {
                HITS.add(new Ui.Hit(kx, ky, kw, KEY_H, () -> answer(k)));
            }
            kx += kw + 4;
        }
        g.pose().popPose();
    }
}
