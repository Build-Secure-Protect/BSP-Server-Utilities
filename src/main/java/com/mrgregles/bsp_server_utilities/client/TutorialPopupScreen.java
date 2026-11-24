package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.TutorialPopupPacket;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A point's popup that freezes the player (SU-140; owner decisions D723 to D730, look AX A of
 * {@code tools/preview/tutorial_refined.html}). Full screen: a framed card over the darkened game with the tutorial's
 * name, the stop and a thin progress line, the admin's 12 x 8 layout, END TUTORIAL on the left and the other buttons on
 * the right. Small: the admin's 6 x 4 layout where they placed it, the game left bright, the buttons along its foot. END
 * TUTORIAL reads SURE? for three seconds; OPEN LINK goes through the game's confirm-link screen; a NEXT to another server
 * reads TAKE ME THERE (without BSP-Teleport it keeps its own words, with a gold line naming the server). Escape hides it.
 */
public class TutorialPopupScreen extends Screen {
    private static final int KEY_H = 16, SMALL_KEY_H = 13;
    private static final long SURE_MS = 3_000L;
    private TutorialPopupPacket packet;
    private TutorialPopup popup;
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private Ui.Hit pressed;
    private long sureUntil;
    /** Closed by a button or by the server (not hidden), or away at the confirm-link screen (coming back). */
    private boolean done, linking;

    TutorialPopupScreen(TutorialPopupPacket packet, TutorialPopup popup) {
        super(Component.literal(packet.name()));
        this.packet = packet;
        this.popup = popup;
    }

    void update(TutorialPopupPacket p, TutorialPopup pop) {
        packet = p;
        popup = pop;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        linking = false;
    }

    /** Closed because the server said so or the popup moved on: not a hide. */
    void closeQuietly() {
        done = true;
        minecraft.setScreen(null);
    }

    /** Being replaced by another popup: its leaving is not a hide, and the screen is left to the caller (audit of SU-146). */
    void markDone() {
        done = true;
    }

    /** Escape, or another screen taking its place: hidden until /tutorial resume (audit of SU-146). */
    @Override
    public void removed() {
        if (!done && !linking) {
            ClientTutorial.hidden(packet);
        }
        super.removed();
    }

    static int withAlpha(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 255) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Where a small popup sits on a screen of the given size (D727, D741). */
    static int[] smallRect(TutorialPopup pop, int sw, int sh) {
        TutorialPopup.Placement p = pop.place();
        // a popup that freezes with buttons is at least 60 tall, so its keys and the line over them leave room for the
        // boxes (audit of SU-146)
        int minH = pop.freezes() && !pop.buttons().isEmpty() ? 60 : 30;
        int w = Math.max(80, sw * p.w() / 1000), h = Math.max(minH, sh * p.h() / 1000);
        int x = Math.min(sw - w, sw * p.x() / 1000), y = Math.min(sh - h, sh * p.y() / 1000);
        return new int[]{Math.max(0, x), Math.max(0, y), Math.min(w, sw), Math.min(h, sh)};
    }

    private static int background(TutorialPopup pop) {
        int bg = pop.background();
        return (bg >>> 24) == 0 && bg != 0 ? 0xF0000000 | bg : bg;
    }

    /** A small popup's card and boxes (also the HUD's walking popup, with its alpha). */
    static void drawSmall(GuiGraphics g, Font font, TutorialPopupPacket p, TutorialPopup pop, int x, int y, int w, int h, float alpha) {
        g.fill(x, y, x + w, y + h, withAlpha(background(pop), alpha));
        Ui.frame(g, x, y, w, h, withAlpha(Ui.DIM, alpha));
        int keys = pop.freezes() && !pop.buttons().isEmpty() ? SMALL_KEY_H + 4 + (elsewhere(p, pop).isEmpty() ? 0 : LINE_H) : 0;
        TutorialDraw.boxes(g, font, pop, x + 4, y + 4, w - 8, h - 8 - keys, i -> picture(p, i));
    }

    /** The strip over the keys for the gold "continue on" line, so it never draws over the boxes (audit of SU-146). */
    private static final int LINE_H = 9;

    /** The server named by a button whose move this server cannot make (the gold "continue on" line), or empty. */
    static String elsewhere(TutorialPopupPacket p, TutorialPopup pop) {
        if (p.canMove()) {
            return "";
        }
        for (int i = 0; i < pop.buttons().size() && i < p.away().size(); i++) {
            if (pop.buttons().get(i).job() != TutorialPopup.Job.END && !p.away().get(i).isEmpty()) {
                return p.away().get(i);
            }
        }
        return "";
    }

    /**
     * Where each key goes along a row of width {@code w} from {@code x}: END TUTORIAL at the left, the rest at the right in
     * the admin's order. Widths are measured bold; when the row is too narrow the keys drop their minimum width, then
     * share the row in proportion, so keys never overlap or leave the card (audit of SU-146). Returns {x, width} per
     * button. The popup and the editor's preview both lay their keys out here.
     */
    static int[][] layoutKeys(Font font, TutorialPopup pop, List<String> labels, int x, int w) {
        int n = pop.buttons().size();
        int[] want = new int[n], bare = new int[n], kw = new int[n], kx = new int[n];
        int sumWant = 0, sumBare = 0;
        for (int i = 0; i < n; i++) {
            boolean end = pop.buttons().get(i).job() == TutorialPopup.Job.END;
            bare[i] = Ui.widthBold(font, labels.get(i).toUpperCase(Locale.ROOT), 0.75f) + 12;
            want[i] = Math.max(end ? 60 : 54, bare[i]);
            sumWant += want[i];
            sumBare += bare[i];
        }
        int room = w - 4 * Math.max(0, n - 1);
        for (int i = 0; i < n; i++) {
            if (sumWant <= room) {
                kw[i] = want[i];
            } else if (sumBare <= room) {
                kw[i] = bare[i];
            } else {
                kw[i] = Math.max(1, (int) ((long) bare[i] * room / Math.max(1, sumBare)));
            }
        }
        int right = x + w;
        for (int i = n - 1; i >= 0; i--) {
            if (pop.buttons().get(i).job() == TutorialPopup.Job.END) {
                continue;
            }
            right -= kw[i];
            kx[i] = right;
            right -= 4;
        }
        int left = x;
        for (int i = 0; i < n; i++) {
            if (pop.buttons().get(i).job() == TutorialPopup.Job.END) {
                kx[i] = left;
                left += kw[i] + 4;
            }
        }
        return new int[][]{kx, kw};
    }

    @Nullable
    private static ClientPanelImages.Texture picture(TutorialPopupPacket p, int i) {
        return i < p.hashes().size() && !p.hashes().get(i).isEmpty() ? ClientPanelImages.texture(p.hashes().get(i)) : null;
    }

    /**
     * The popup as a player would see it on a screen of {@code sw} by {@code sh}, drawn from (0, 0), with no keys to
     * press: the popup editor's preview and SHOW AS PLAYER (D724, D727).
     */
    static void drawPreview(GuiGraphics g, Font font, String name, TutorialPopup pop, java.util.function.Function<Integer, ClientPanelImages.Texture> pictures, int sw, int sh) {
        if (pop.size() == TutorialPopup.Size.FULL) {
            int x = Math.round(sw * 0.05f), y = Math.round(sh * 0.05f), w = sw - 2 * x, h = sh - 2 * y;
            g.fill(0, 0, sw, sh, 0x9005070A);
            g.fill(x, y, x + w, y + h, background(pop));
            Ui.frame(g, x, y, w, h, Ui.DIM);
            g.fill(x + 1, y + 21, x + w - 1, y + 22, Ui.DIM);
            Ui.text(g, font, Component.literal(Ui.fitBold(font, name.toUpperCase(Locale.ROOT), w / 2, 1f)).withStyle(ChatFormatting.BOLD), x + 8, y + 7, Ui.TEAL, 1f);
            int keysY = y + h - 8 - KEY_H;
            TutorialDraw.boxes(g, font, pop, x + 8, y + 30, w - 16, keysY - 8 - (y + 30), pictures);
            previewKeys(g, font, pop, x + 8, keysY, w - 16, KEY_H);
        } else {
            int[] r = smallRect(pop, sw, sh);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], background(pop));
            Ui.frame(g, r[0], r[1], r[2], r[3], Ui.DIM);
            int keys = pop.freezes() && !pop.buttons().isEmpty() ? SMALL_KEY_H + 4 : 0;
            TutorialDraw.boxes(g, font, pop, r[0] + 4, r[1] + 4, r[2] - 8, r[3] - 8 - keys, pictures);
            if (keys > 0) {
                previewKeys(g, font, pop, r[0] + 4, r[1] + r[3] - 4 - SMALL_KEY_H, r[2] - 8, SMALL_KEY_H);
            }
        }
    }

    private static void previewKeys(GuiGraphics g, Font font, TutorialPopup pop, int x, int y, int w, int h) {
        List<String> labels = new ArrayList<>();
        for (TutorialPopup.Button b : pop.buttons()) {
            labels.add(b.label());
        }
        int[][] at = layoutKeys(font, pop, labels, x, w); // as the real keys (audit of SU-146)
        for (int i = 0; i < pop.buttons().size(); i++) {
            TutorialPopup.Button b = pop.buttons().get(i);
            TutorialDraw.button(g, font, at[0][i], y, at[1][i], h, b.label(), b.colour(), false, true);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (popup.size() == TutorialPopup.Size.FULL) {
            super.renderBackground(g);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        if (popup.size() == TutorialPopup.Size.FULL) {
            drawFull(g, mouseX, mouseY);
        } else {
            int[] r = smallRect(popup, width, height);
            drawSmall(g, font, packet, popup, r[0], r[1], r[2], r[3], 1f);
            keys(g, r[0] + 4, r[1] + r[3] - 4 - SMALL_KEY_H, r[2] - 8, SMALL_KEY_H, mouseX, mouseY);
        }
    }

    private void drawFull(GuiGraphics g, int mx, int my) {
        int x = Math.round(width * 0.05f), y = Math.round(height * 0.05f), w = width - 2 * x, h = height - 2 * y;
        g.fill(x, y, x + w, y + h, background(popup));
        Ui.frame(g, x, y, w, h, Ui.DIM);
        // the head: the tutorial's name, the stop, the progress line
        g.fill(x + 1, y + 21, x + w - 1, y + 22, Ui.DIM);
        Ui.text(g, font, Component.literal(Ui.fitBold(font, packet.name().toUpperCase(Locale.ROOT), w / 2, 1f)).withStyle(ChatFormatting.BOLD), x + 8, y + 7, Ui.TEAL, 1f);
        String stop = packet.total() > 0 ? Tr.t("tutorial.stop_of", packet.stop(), packet.total()) : Tr.t("tutorial.stop", packet.stop());
        Ui.text(g, font, stop, x + w - 8 - Ui.width(font, stop, 0.75f), y + 9, Ui.MUTED, 0.75f);
        if (packet.total() > 0) {
            g.fill(x + 1, y + 21, x + 1 + (w - 2) * Math.min(packet.stop(), packet.total()) / packet.total(), y + 22, Ui.TEAL);
        }
        int keysY = y + h - 8 - KEY_H;
        int line = elsewhere(packet, popup).isEmpty() ? 0 : LINE_H; // the gold line's strip, off the boxes (audit of SU-146)
        TutorialDraw.boxes(g, font, popup, x + 8, y + 30, w - 16, keysY - 8 - line - (y + 30), i -> picture(packet, i));
        keys(g, x + 8, keysY, w - 16, KEY_H, mx, my);
    }

    /** The buttons: END TUTORIAL on the left, the rest on the right in the admin's order; the line naming another server above. */
    private void keys(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        List<TutorialPopup.Button> buttons = popup.buttons();
        boolean sure = Util.getMillis() < sureUntil;
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            TutorialPopup.Button b = buttons.get(i);
            String away = i < packet.away().size() ? packet.away().get(i) : "";
            if (b.job() == TutorialPopup.Job.END) {
                labels.add(sure ? Tr.t("rules.sure") : b.label());
            } else {
                // TAKE ME THERE when this server can move the player; else the button keeps its own words and stays on:
                // the server moves the tutorial on and says in chat where to go (audit of SU-146)
                labels.add(!away.isEmpty() && packet.canMove() ? Tr.t("tutorial.take_me", away) : b.label());
            }
        }
        int[][] at = layoutKeys(font, popup, labels, x, w);
        for (int i = 0; i < buttons.size(); i++) {
            TutorialPopup.Button b = buttons.get(i);
            if (b.job() == TutorialPopup.Job.END) {
                key(g, at[0][i], y, at[1][i], h, labels.get(i), sure ? 0xFF6B5C : b.colour(), true, mx, my, i);
            } else {
                boolean enabled = b.job() != TutorialPopup.Job.PREVIOUS || packet.canBack();
                key(g, at[0][i], y, at[1][i], h, labels.get(i), b.colour(), enabled, mx, my, i);
            }
        }
        String elsewhere = elsewhere(packet, popup);
        if (!elsewhere.isEmpty()) {
            String line = Tr.t("tutorial.continue_on", elsewhere);
            Ui.text(g, font, Ui.fit(font, line, w, 0.75f), x, y - LINE_H, Ui.GOLD, 0.75f);
        }
    }

    private void key(GuiGraphics g, int x, int y, int w, int h, String label, int colour, boolean enabled, int mx, int my, int index) {
        boolean hot = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        TutorialDraw.button(g, font, x, y, w, h, label, colour, hot, enabled);
        if (enabled) {
            hits.add(new Ui.Hit(x, y, w, h, () -> act(index)));
        }
    }

    private void act(int index) {
        TutorialPopup.Button b = popup.buttons().get(index);
        switch (b.job()) {
            case LINK -> {
                String url = b.url();
                linking = true;
                minecraft.setScreen(new ConfirmLinkScreen(open -> {
                    if (open) {
                        Util.getPlatform().openUri(url);
                    }
                    minecraft.setScreen(this);
                }, url, true));
            }
            case END -> {
                if (Util.getMillis() < sureUntil) {
                    ClientTutorial.pressed(packet, index);
                    done = true;
                    minecraft.setScreen(null);
                } else {
                    sureUntil = Util.getMillis() + SURE_MS;
                }
            }
            default -> {
                ClientTutorial.pressed(packet, index);
                done = true;
                minecraft.setScreen(null);
            }
        }
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
}
