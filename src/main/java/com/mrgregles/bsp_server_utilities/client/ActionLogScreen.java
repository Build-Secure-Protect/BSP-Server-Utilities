package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.LogViewPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /actionlog} for every player (owner decisions D26, D58), as on {@code tools/preview/staff_log_refined.html}:
 * read only, teal like the rules popup, the newest 100 entries with coordinates hidden (how long ago, who in their
 * role's colour, what, the server; hover a cut line for all of it), the wheel to scroll, CLOSE or Escape. It follows the
 * log while it is open.
 */
public class ActionLogScreen extends Screen {
    static final int W = 300, H = 220;
    private static final int PAD = 8, FOOTER_H = 16, ROW = 10, SCROLL_W = 3, SHOWN = 100;
    private static final float SMALL = 0.7f, TINY = 0.6f;

    private final List<Ui.Hit> hits = new ArrayList<>();
    private float scale = 1;
    private int scroll;

    public ActionLogScreen() {
        super(Component.literal(Tr.t("log.title")));
    }

    public static void open(LogViewPacket view) {
        ClientLog.accept(view);
        Minecraft.getInstance().setScreen(new ActionLogScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
    }

    private int left() {
        return (int) ((width / scale - W) / 2);
    }

    private int top() {
        return (int) ((height / scale - H) / 2);
    }

    private static List<LogViewPacket.Row> rows() {
        List<LogViewPacket.Row> all = ClientLog.rows();
        return all.subList(0, Math.min(SHOWN, all.size()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        String tip = draw(g, (int) (mouseX / scale), (int) (mouseY / scale));
        g.pose().popPose();
        if (tip != null) {
            g.renderTooltip(font, font.split(Component.literal(tip), 220), mouseX, mouseY);
        }
    }

    private String draw(GuiGraphics g, int mx, int my) {
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, Ui.PANEL);
        Ui.frame(g, x, y, W, H, Ui.TEAL);
        String sub = Tr.t("log.screen_sub", ClientLog.keepDays());
        int subW = Ui.width(font, sub, TINY);
        Ui.text(g, font, sub, x + W - PAD - subW, y + 9, Ui.MUTED, TINY);
        String title = Ui.fit(font, Tr.t("log.title"), W - 3 * PAD - subW, 1f);
        Ui.text(g, font, Component.literal(title).withStyle(ChatFormatting.BOLD), x + PAD, y + 7, Ui.TEAL, 1f);
        int iy = y + 20;
        List<String> intro = Ui.wrap(font, Tr.t("log.intro"), W - 2 * PAD, SMALL);
        for (int i = 0; i < Math.min(2, intro.size()); i++) {
            Ui.text(g, font, intro.get(i), x + PAD, iy, Ui.MUTED, SMALL);
            iy += 7;
        }

        int lx = x + PAD, ly = iy + 4, lw = W - 2 * PAD - SCROLL_W - 2, lh = y + H - PAD - FOOTER_H - 4 - ly;
        Ui.inset(g, lx, ly, lw, lh);
        String tip = drawRows(g, lx, ly, lw, lh, mx, my);

        int fy = y + H - PAD - FOOTER_H;
        String close = Tr.t("log.close");
        int closeW = Math.max(52, Ui.width(font, close, TINY) + 20);
        Ui.text(g, font, Ui.fit(font, Tr.t("log.screen_hint"), W - 3 * PAD - closeW, TINY), x + PAD, fy + 5, Ui.MUTED, TINY);
        int kx = x + W - PAD - closeW;
        if (mx >= kx && mx < kx + closeW && my >= fy && my < fy + FOOTER_H) {
            g.fill(kx, fy, kx + closeW, fy + FOOTER_H, 0x222A2F3A);
        }
        Ui.frame(g, kx, fy, closeW, FOOTER_H, Ui.DIM);
        Ui.text(g, font, Component.literal(close).withStyle(ChatFormatting.BOLD), kx + (closeW - Ui.width(font, close, TINY)) / 2, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, closeW, FOOTER_H, this::onClose));
        return tip;
    }

    /** The rows; answers the whole text of a cut line under the mouse. */
    private String drawRows(GuiGraphics g, int lx, int ly, int lw, int lh, int mx, int my) {
        List<LogViewPacket.Row> rows = rows();
        int visible = (lh - 4) / ROW;
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visible));
        drawScrollBar(g, lx + lw + 2, ly, lh, rows.size(), visible);
        if (rows.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("log.none"), lw - 10, SMALL), lx + 5, ly + 5, Ui.MUTED, SMALL);
            return null;
        }
        int srvW = 0;
        for (LogViewPacket.Row r : rows) {
            srvW = Math.max(srvW, Ui.width(font, r.server(), TINY));
        }
        srvW = Math.min(srvW, 56) + 6;
        int agoX = lx + 4, whoX = lx + 30, whatX = lx + 86, whatW = lw - (whatX - lx) - srvW - 8;
        String tip = null;
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            LogViewPacket.Row r = rows.get(scroll + i);
            int ry = ly + 3 + i * ROW;
            if (i > 0) {
                g.fill(lx + 1, ry - 1, lx + lw - 1, ry, Ui.ROW_LINE);
            }
            Ui.text(g, font, StaffFace.ago(ClientLog.agoMs(r)), agoX, ry + 2, Ui.MUTED, TINY);
            Ui.text(g, font, Component.literal(Ui.fit(font, r.actor(), whatX - whoX - 4, SMALL)).withStyle(ChatFormatting.BOLD), whoX, ry + 1, 0xFF000000 | r.colour(), SMALL);
            String what = Ui.fit(font, r.text(), whatW, SMALL);
            Ui.text(g, font, what, whatX, ry + 1, Ui.TEXT, SMALL);
            int sx = lx + lw - 4 - srvW;
            g.fill(sx, ry, sx + srvW, ry + 8, 0xFF1B2230);
            Ui.text(g, font, Ui.fit(font, r.server(), srvW - 4, TINY), sx + 3, ry + 2, Ui.MUTED, TINY);
            if (!what.equals(r.text()) && mx >= lx && mx < lx + lw && my >= ry && my < ry + ROW) {
                tip = r.text();
            }
        }
        return tip;
    }

    private void drawScrollBar(GuiGraphics g, int x, int y, int h, int total, int visible) {
        g.fill(x, y, x + SCROLL_W, y + h, Ui.DIM);
        if (total <= visible) {
            g.fill(x, y, x + SCROLL_W, y + h, Ui.MUTED);
            return;
        }
        int thumb = Math.max(10, h * visible / total);
        int ty = y + (h - thumb) * scroll / Math.max(1, total - visible);
        g.fill(x, ty, x + SCROLL_W, ty + thumb, Ui.MUTED);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double mx = mouseX / scale, my = mouseY / scale;
        if (button == 0) {
            for (Ui.Hit hit : List.copyOf(hits)) {
                if (hit.contains(mx, my)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll -= (int) Math.signum(delta) * 3;
        return true;
    }
}
