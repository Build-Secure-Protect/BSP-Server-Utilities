package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.TutorialAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialOfferPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The offer of a tutorial (owner decisions D722, D738; look AX A): a centred card with the tutorial's name and the
 * admin's text, YES, LATER and NO THANKS, and a line saying {@code /tutorial} starts it any time. Escape is LATER.
 */
public class TutorialOfferScreen extends Screen {
    private static final int W = 240, PAD = 12, KEY_H = 16;
    private final TutorialOfferPacket offer;
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private Ui.Hit pressed;
    private boolean answered;

    TutorialOfferScreen(TutorialOfferPacket offer) {
        super(Component.literal(offer.name()));
        this.offer = offer;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void answer(TutorialAnswerPacket.Action a) {
        if (!answered) {
            answered = true;
            ClientTutorial.send(new TutorialAnswerPacket(a, offer.tutorial(), -1, -1));
        }
        minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        answer(TutorialAnswerPacket.Action.LATER);
    }

    /** Replaced by another screen before an answer: taken as LATER, so the server is not left waiting. */
    @Override
    public void removed() {
        if (!answered) {
            answered = true;
            ClientTutorial.send(new TutorialAnswerPacket(TutorialAnswerPacket.Action.LATER, offer.tutorial(), -1, -1));
        }
        super.removed();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        hits.clear();
        String text = offer.text().isEmpty() ? Tr.t("tutorial.offer_default") : offer.text();
        List<String> lines = new ArrayList<>();
        for (String para : text.split("\n")) {
            lines.addAll(Ui.wrap(font, para, W - 2 * PAD, 0.85f));
        }
        int textH = Math.min(12, lines.size()) * 9;
        int h = PAD + 12 + 8 + textH + 12 + KEY_H + 6 + 8 + PAD;
        int x = (width - W) / 2, y = (height - h) / 2;
        g.fill(x, y, x + W, y + h, Ui.PANEL);
        Ui.frame(g, x, y, W, h, Ui.DIM);
        Ui.text(g, font, Component.literal(Ui.fitBold(font, offer.name().toUpperCase(Locale.ROOT), W - 2 * PAD, 1.1f)).withStyle(ChatFormatting.BOLD), x + PAD, y + PAD, Ui.TEAL, 1.1f);
        int cy = y + PAD + 20;
        for (int i = 0; i < Math.min(12, lines.size()); i++) {
            Ui.text(g, font, lines.get(i), x + PAD, cy, Ui.TEXT, 0.85f);
            cy += 9;
        }
        cy += 12;
        int kx = x + PAD;
        kx += key(g, kx, cy, Tr.t("tutorial.yes"), Ui.TEAL & 0xFFFFFF, mx, my, () -> answer(TutorialAnswerPacket.Action.YES)) + 4;
        key(g, kx, cy, Tr.t("tutorial.later"), 0x2A2F3A, mx, my, () -> answer(TutorialAnswerPacket.Action.LATER));
        String no = Tr.t("tutorial.no");
        int nw = Math.max(50, Ui.widthBold(font, no.toUpperCase(Locale.ROOT), 0.75f) + 18); // as key() measures it (audit of SU-146)
        key(g, x + W - PAD - nw, cy, no, 0x2A2F3A, mx, my, () -> answer(TutorialAnswerPacket.Action.NO));
        Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.any_time"), W - 2 * PAD, 0.7f), x + PAD, cy + KEY_H + 6, Ui.MUTED, 0.7f);
    }

    private int key(GuiGraphics g, int x, int y, String label, int colour, int mx, int my, Runnable action) {
        int w = Math.max(50, Ui.widthBold(font, label.toUpperCase(Locale.ROOT), 0.75f) + 18); // measured bold (audit of SU-146)
        boolean hot = mx >= x && mx < x + w && my >= y && my < y + KEY_H;
        TutorialDraw.button(g, font, x, y, w, KEY_H, label, colour, hot, true);
        hits.add(new Ui.Hit(x, y, w, KEY_H, action));
        return w;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        pressed = null;
        if (button == 0) {
            for (Ui.Hit h : hits) {
                if (h.contains(mouseX, mouseY)) {
                    pressed = h;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pressed != null) {
            Ui.Hit was = pressed;
            pressed = null;
            if (was.contains(mouseX, mouseY)) {
                was.action().run();
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
