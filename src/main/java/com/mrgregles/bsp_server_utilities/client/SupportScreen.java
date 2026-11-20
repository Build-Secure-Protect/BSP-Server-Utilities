package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.support.SupportLinks;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Support BSP popup (owner decisions D594, D608, D611; {@code tools/preview/support_refined.html} R2), opened by the
 * SUPPORT BSP key in the inventory and by {@code /support}: a 220 GUI px card with a gold frame, a pink heart over the
 * gold title, one line of thanks, the address in a gold box (a long one cut in the middle, the whole of it as a tooltip),
 * and CLOSE, COPY LINK and a gold OPEN. OPEN opens the player's web browser at once and closes the popup (this popup is
 * the "are you sure"); COPY LINK puts the address on the clipboard and says "Copied." in green for three seconds;
 * CLOSE or Escape goes back to the inventory, or to the game after {@code /support}. Keys drawn by hand; key presses
 * stay inside ({@link ScreenKeys}); the game is not paused. The address is checked again here and never opened unless
 * it is an https link.
 */
public class SupportScreen extends Screen {
    static final int W = 220;
    private static final int PAD = 10, KEY_H = 14, KEY_GAP = 4, HEART = 0xFFFF6B8A, OK = 0xFF8FE04A;
    private static final float SMALL = 0.75f;
    private static final long COPIED_MS = 3_000L;

    private final String link;
    @Nullable
    private final Screen parent;
    private final List<Ui.Hit> hits = new ArrayList<>();
    private float scale = 1;
    private long copiedAt = -1;
    /** The key the left button went down on, until it comes up. */
    @Nullable
    private Ui.Hit pressed;
    private int h = 120;

    public SupportScreen(String link, @Nullable Screen parent) {
        super(Component.literal(Tr.t("support.title")));
        this.link = SupportLinks.usable(link) ? SupportLinks.tidy(link) : "";
        this.parent = parent;
    }

    /** What CLOSE goes back to. */
    @Nullable
    Screen parent() {
        return parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    protected void init() {
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / h));
    }

    private int left() {
        return (int) ((width / scale - W) / 2);
    }

    private int top() {
        return (int) ((height / scale - h) / 2);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        // the height (and so the scale) settled before anything is drawn, so one frame never mixes two scales (audit of 0.15.0)
        boolean copied = copiedAt >= 0 && Util.getMillis() - copiedAt < COPIED_MS;
        int textH = Math.max(1, Ui.wrap(font, Tr.t("support.text"), W - 2 * PAD, SMALL).size()) * 8;
        int wanted = PAD + 10 + 4 + 10 + 6 + textH + 6 + 16 + (copied ? 10 : 0) + 6 + KEY_H + PAD;
        if (wanted != h) {
            h = wanted;
            init();
        }
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        draw(g, (int) (mouseX / scale), (int) (mouseY / scale));
        g.pose().popPose();
    }

    private void draw(GuiGraphics g, int mx, int my) {
        Font f = font;
        List<String> lines = Ui.wrap(f, Tr.t("support.text"), W - 2 * PAD, SMALL);
        int textH = Math.max(1, lines.size()) * 8;
        boolean copied = copiedAt >= 0 && Util.getMillis() - copiedAt < COPIED_MS;
        int x = left(), y = top();
        g.fill(x, y, x + W, y + h, Ui.PANEL);
        Ui.frame(g, x, y, W, h, Ui.GOLD);
        int cy = y + PAD;
        // the heart and the title, centred
        String heart = "❤";
        Ui.text(g, f, heart, x + (W - Ui.width(f, heart, 1.25f)) / 2, cy, HEART, 1.25f);
        cy += 14;
        Component title = Component.literal(Ui.fit(f, Tr.t("support.title"), W - 2 * PAD, 1f)).withStyle(ChatFormatting.BOLD);
        Ui.text(g, f, title, x + (W - f.width(title)) / 2, cy, Ui.GOLD, 1f);
        cy += 16;
        for (String line : lines) {
            Ui.text(g, f, line, x + (W - Ui.width(f, line, SMALL)) / 2, cy, Ui.MUTED, SMALL);
            cy += 8;
        }
        cy += 6;
        // the address
        int boxX = x + PAD, boxW = W - 2 * PAD;
        Ui.inset(g, boxX, cy, boxW, 16);
        String shown = cutMiddle(f, link, boxW - 8, SMALL);
        Ui.text(g, f, shown, x + (W - Ui.width(f, shown, SMALL)) / 2, cy + 5, Ui.GOLD, SMALL);
        boolean overBox = mx >= boxX && mx < boxX + boxW && my >= cy && my < cy + 16;
        int boxY = cy;
        cy += 16;
        if (copied) {
            cy += 3;
            String c = Tr.t("support.copied");
            Ui.text(g, f, c, x + (W - Ui.width(f, c, SMALL)) / 2, cy, OK, SMALL);
            cy += 7;
        }
        cy += 6;
        // the keys, centred: CLOSE, COPY LINK, OPEN
        String close = Tr.t("support.close"), copy = Tr.t("support.copy"), open = Tr.t("support.open");
        int wClose = Ui.width(f, close, SMALL) + 14, wCopy = Ui.width(f, copy, SMALL) + 14, wOpen = Ui.width(f, open, SMALL) + 18;
        int kx = x + (W - (wClose + wCopy + wOpen + 2 * KEY_GAP)) / 2;
        key(g, f, kx, cy, wClose, close, mx, my, Kind.GHOST, this::onClose);
        kx += wClose + KEY_GAP;
        key(g, f, kx, cy, wCopy, copy, mx, my, copied ? Kind.DONE : Kind.PLAIN, this::copy);
        kx += wCopy + KEY_GAP;
        key(g, f, kx, cy, wOpen, open, mx, my, Kind.GOLD, this::open);
        if (overBox && !shown.equals(link)) {
            g.pose().pushPose();
            g.renderTooltip(f, f.split(Component.literal(link), 300), mx, boxY + 20);
            g.pose().popPose();
        }
    }

    private enum Kind { GHOST, PLAIN, DONE, GOLD }

    private void key(GuiGraphics g, Font f, int x, int y, int w, String label, int mx, int my, Kind kind, Runnable action) {
        boolean hot = mx >= x && mx < x + w && my >= y && my < y + KEY_H;
        int ink;
        switch (kind) {
            case GOLD -> {
                g.fill(x, y, x + w, y + KEY_H, hot ? 0xFFFFE27A : Ui.GOLD);
                ink = Ui.INK;
            }
            case GHOST -> {
                Ui.frame(g, x, y, w, KEY_H, hot ? Ui.GOLD : Ui.DIM);
                ink = hot ? Ui.GOLD : Ui.MUTED;
            }
            default -> {
                g.fill(x, y, x + w, y + KEY_H, Ui.DIM);
                if (hot || kind == Kind.DONE) {
                    Ui.frame(g, x, y, w, KEY_H, kind == Kind.DONE ? OK : Ui.GOLD);
                }
                ink = Ui.TEXT;
            }
        }
        Component c = Component.literal(label).withStyle(ChatFormatting.BOLD);
        Ui.text(g, f, c, x + (w - (int) Math.ceil(f.width(c) * SMALL)) / 2, y + 4, ink, SMALL);
        hits.add(new Ui.Hit(x, y, w, KEY_H, action));
    }

    private void copy() {
        if (!link.isEmpty()) {
            minecraft.keyboardHandler.setClipboard(link);
            copiedAt = Util.getMillis();
        }
    }

    private void open() {
        if (!link.isEmpty()) {
            Util.getPlatform().openUri(link);
        }
        onClose();
    }

    /**
     * A key acts when the button is let go over the key it was pressed on, never on the press: closing on the press would
     * hand the release to the inventory underneath, which puts down a stack held on the cursor (audit of 0.15.0).
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        pressed = null;
        if (button == 0) {
            pressed = hitAt(mouseX, mouseY);
            if (pressed != null) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pressed != null) {
            Ui.Hit was = pressed;
            pressed = null;
            Ui.Hit now = hitAt(mouseX, mouseY);
            if (now != null && now.x() == was.x() && now.y() == was.y()) {
                now.action().run();
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Nullable
    private Ui.Hit hitAt(double mouseX, double mouseY) {
        double mx = mouseX / scale, my = mouseY / scale;
        for (Ui.Hit hit : hits) {
            if (hit.contains(mx, my)) {
                return hit;
            }
        }
        return null;
    }

    /** From the keyboard: Enter or Space opens the page, C copies it (Esc closes, as on every screen). */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
            open();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_C) {
            copy();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** The text cut in the middle with "…" to fit, keeping its start and its end (D611). */
    static String cutMiddle(Font font, String s, int maxW, float scale) {
        int room = (int) (maxW / scale);
        if (font.width(s) <= room) {
            return s;
        }
        String dots = "…";
        int half = Math.max(0, (room - font.width(dots)) / 2);
        String start = font.plainSubstrByWidth(s, half);
        String end = font.plainSubstrByWidth(new StringBuilder(s).reverse().toString(), half);
        end = new StringBuilder(end).reverse().toString();
        return start + dots + end;
    }
}
