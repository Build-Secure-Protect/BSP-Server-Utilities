package com.mrgregles.bsp_server_utilities.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The popup when staff write (owner decisions D635, D636, D649, D652 R3; {@code tools/preview/talk_refined.html}): a
 * 160 GUI px card with a violet frame, an envelope over "STAFF MESSAGE", one sentence saying who wrote and how many lines
 * (never the words), LATER and a violet OPEN. Lines arriving while it shows are counted into it. OPEN (or Enter) asks the
 * server for the window; LATER (or Esc) closes it and the key keeps flashing. Keys act when the mouse button comes up
 * over the key it went down on. Shown by {@link ClientTalk} only at a quiet moment: no other screen open, not hurt in the
 * last 10 seconds.
 */
public class TalkPopupScreen extends Screen {
    static final int W = 160;
    private static final int PAD = 10, KEY_H = 14, KEY_GAP = 4;
    private static final float SMALL = 0.75f;
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private Ui.Hit pressed;
    private int h = 90;

    public TalkPopupScreen() {
        super(Component.literal(Tr.t("talk.popup.title")));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** LATER, Esc: the batch is done; the key keeps flashing until they answer. */
    @Override
    public void onClose() {
        ClientTalk.clearPopup();
        minecraft.setScreen(null);
    }

    private void open() {
        ClientTalk.clearPopup();
        minecraft.setScreen(null);
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand("su talk");
        }
    }

    /** The sentence for what waits: who (when one staff member wrote all of it), how many, and whether while they were away. */
    static String sentence(int count, String writer, boolean away) {
        String key = (away ? "talk.popup.away_" : "talk.popup.") + (count <= 1 ? "one" : "many") + (writer.isEmpty() ? "" : "_by");
        return writer.isEmpty() ? Tr.t(key, count) : Tr.t(key, writer, count);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        Font f = font;
        List<String> lines = Ui.wrap(f, sentence(ClientTalk.popupCount(), ClientTalk.popupWriter(), ClientTalk.popupAway()), W - 2 * PAD, SMALL);
        h = PAD + 14 + 14 + Math.max(1, lines.size()) * 8 + 8 + KEY_H + PAD;
        int x = (width - W) / 2, y = (height - h) / 2;
        g.fill(x, y, x + W, y + h, Ui.PANEL);
        Ui.frame(g, x, y, W, h, Ui.VIOLET);
        int cy = y + PAD;
        String env = "✉";
        Ui.text(g, f, env, x + (W - Ui.width(f, env, 1.5f)) / 2, cy, Ui.VIOLET, 1.5f);
        cy += 15;
        Component title = Component.literal(Ui.fit(f, Tr.t("talk.popup.title"), W - 2 * PAD, 1f)).withStyle(ChatFormatting.BOLD);
        Ui.text(g, f, title, x + (W - f.width(title)) / 2, cy, Ui.VIOLET, 1f);
        cy += 13;
        for (String line : lines) {
            Ui.text(g, f, line, x + (W - Ui.width(f, line, SMALL)) / 2, cy, Ui.MUTED, SMALL);
            cy += 8;
        }
        cy += 8;
        String later = Tr.t("talk.popup.later"), open = Tr.t("talk.popup.open");
        int wl = Ui.width(f, later, SMALL) + 16, wo = Ui.width(f, open, SMALL) + 20;
        int kx = x + (W - (wl + KEY_GAP + wo)) / 2;
        int mx = mouseX, my = mouseY;
        boolean hotL = mx >= kx && mx < kx + wl && my >= cy && my < cy + KEY_H;
        Ui.frame(g, kx, cy, wl, KEY_H, hotL ? Ui.VIOLET : Ui.DIM);
        label(g, f, later, kx, cy, wl, hotL ? Ui.VIOLET : Ui.MUTED);
        hits.add(new Ui.Hit(kx, cy, wl, KEY_H, this::onClose));
        kx += wl + KEY_GAP;
        boolean hotO = mx >= kx && mx < kx + wo && my >= cy && my < cy + KEY_H;
        g.fill(kx, cy, kx + wo, cy + KEY_H, hotO ? 0xFFCBAEFF : Ui.VIOLET);
        label(g, f, open, kx, cy, wo, Ui.INK);
        hits.add(new Ui.Hit(kx, cy, wo, KEY_H, this::open));
    }

    private static void label(GuiGraphics g, Font f, String s, int x, int y, int w, int ink) {
        Component c = Component.literal(s).withStyle(ChatFormatting.BOLD);
        Ui.text(g, f, c, x + (w - (int) Math.ceil(f.width(c) * SMALL)) / 2, y + 4, ink, SMALL);
    }

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
    private Ui.Hit hitAt(double mx, double my) {
        for (Ui.Hit hit : hits) {
            if (hit.contains(mx, my)) {
                return hit;
            }
        }
        return null;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            open();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
