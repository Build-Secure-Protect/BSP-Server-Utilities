package com.mrgregles.bsp_server_utilities.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing helpers for this mod's screens, in the BSP panel palette: scaled text, text fitted to a width with an ellipsis,
 * text wrapped to a width, frames, inset boxes, and clipping that survives the screen's own scale. Every string on a
 * screen goes through {@link #fit} or {@link #wrap}.
 */
final class Ui {
    static final int PANEL = 0xF010151C, SLOT = 0xFF0C0E12, DIM = 0xFF2A2F3A, MUTED = 0xFF9AA3B5, TEXT = 0xFFE6EAF2,
            TEAL = 0xFF19D3B0, VIOLET = 0xFFB58CFF, GOLD = 0xFFFFD23A, BAD = 0xFFFF6B5C, ROW_LINE = 0xFF1C222C, INK = 0xFF10151C;

    private Ui() {
    }

    static void text(GuiGraphics g, Font font, String s, int x, int y, int colour, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    static void text(GuiGraphics g, Font font, Component c, int x, int y, int colour, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, c, 0, 0, colour, false);
        g.pose().popPose();
    }

    static int width(Font font, String s, float scale) {
        return (int) Math.ceil(font.width(s) * scale);
    }

    /** The text cut to fit {@code maxW} (in drawn units at {@code scale}), with an ellipsis when cut. */
    static String fit(Font font, String s, int maxW, float scale) {
        int room = (int) (maxW / scale);
        if (font.width(s) <= room) {
            return s;
        }
        String dots = "…";
        return font.plainSubstrByWidth(s, Math.max(0, room - font.width(dots))) + dots;
    }

    /** The text broken into lines no wider than {@code maxW} at {@code scale}; line breaks in the text are kept. */
    static List<String> wrap(Font font, String s, int maxW, float scale) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) {
            return out;
        }
        for (FormattedText line : font.getSplitter().splitLines(s, (int) (maxW / scale), Style.EMPTY)) {
            out.add(line.getString());
        }
        return out;
    }

    static void frame(GuiGraphics g, int x, int y, int w, int h, int colour) {
        g.fill(x, y, x + w, y + 1, colour);
        g.fill(x, y + h - 1, x + w, y + h, colour);
        g.fill(x, y, x + 1, y + h, colour);
        g.fill(x + w - 1, y, x + w, y + h, colour);
    }

    static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SLOT);
        frame(g, x, y, w, h, DIM);
    }

    /**
     * Clips drawing to a box given in the current drawing units. Minecraft's scissor ignores the drawing scale, so the
     * corners are carried through it here (as in BSP RankUp and BSP Region Guard).
     */
    static void scissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        var m = g.pose().last().pose();
        Vector4f a = m.transform(new Vector4f(x1, y1, 0, 1)), b = m.transform(new Vector4f(x2, y2, 0, 1));
        g.enableScissor((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.ceil(b.x()), (int) Math.ceil(b.y()));
    }

    /** A clickable box collected while drawing. */
    record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
