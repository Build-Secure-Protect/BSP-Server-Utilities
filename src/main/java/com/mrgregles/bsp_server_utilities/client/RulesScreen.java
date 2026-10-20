package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.RulesAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.RulesOpenPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules popup (owner decisions D31 to D33, D38) and the read-only copy from {@code /rules} (D12), drawn as the
 * confirmed page {@code tools/preview/rules_refined.html}: one teal panel, the heading and intro, every rule in one
 * scrolling list with its number, bold title and text, and (to agree) a tick box on the right of each rule; a click
 * anywhere on a row ticks it. AGREE stays dim until every box is ticked; LEAVE SERVER sits at the bottom left. Escape
 * does nothing while the player must answer. The server checks the answer again; this screen only shows. Opened from the
 * admin panel's PREVIEW it works the same but sends nothing: AGREE, LEAVE SERVER and Escape go back to the panel.
 */
public class RulesScreen extends Screen {
    static final int W = 300, H = 220;
    private static final int PAD = 8, LIST_TOP_GAP = 4, FOOTER_H = 16, ROW_PAD = 4, TICK = 9, NUM_W = 11, SCROLL_W = 3, WHEEL_STEP = 14;
    private static final float SMALL = 0.7f, TINY = 0.6f;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final RulesOpenPacket rules;
    /** The admin panel when this is its PREVIEW, else null. */
    @Nullable
    private final Screen parent;
    private final boolean[] ticked;
    private final List<Ui.Hit> hits = new ArrayList<>();
    private float scale = 1;
    private int scroll, contentH;

    public RulesScreen(RulesOpenPacket rules) {
        this(rules, null);
    }

    public RulesScreen(RulesOpenPacket rules, @Nullable Screen parent) {
        super(Component.literal(rules.heading()));
        this.rules = rules;
        this.parent = parent;
        this.ticked = new boolean[rules.rules().size()];
    }

    RulesOpenPacket packet() {
        return rules;
    }

    private boolean allTicked() {
        for (boolean t : ticked) {
            if (!t) {
                return false;
            }
        }
        return true;
    }

    private int tickedCount() {
        int n = 0;
        for (boolean t : ticked) {
            if (t) {
                n++;
            }
        }
        return n;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !rules.agree() || parent != null;
    }

    @Override
    public void onClose() {
        if (parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        // Smaller than the GUI scale only when the window cannot hold the panel.
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
    }

    private int left() {
        return (int) ((width / scale - W) / 2);
    }

    private int top() {
        return (int) ((height / scale - H) / 2);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        draw(g, (int) (mouseX / scale), (int) (mouseY / scale));
        g.pose().popPose();
    }

    private void draw(GuiGraphics g, int mx, int my) {
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, Ui.PANEL);
        Ui.frame(g, x, y, W, H, Ui.TEAL);

        // heading and the count (or "read only")
        String right = rules.agree() ? Tr.t("rules.ticked", tickedCount(), ticked.length) : Tr.t("rules.read_only");
        int rightW = Ui.width(font, right, TINY);
        Ui.text(g, font, right, x + W - PAD - rightW, y + 9, rules.agree() && allTicked() ? Ui.TEAL : Ui.MUTED, TINY);
        String heading = Ui.fit(font, rules.heading().toUpperCase(Locale.ROOT), W - 3 * PAD - rightW, 1f);
        Ui.text(g, font, Component.literal(heading).withStyle(ChatFormatting.BOLD), x + PAD, y + 7, Ui.TEAL, 1f);

        // the intro, up to three lines
        int iy = y + 20;
        List<String> intro = Ui.wrap(font, rules.intro(), W - 2 * PAD, SMALL);
        for (int i = 0; i < Math.min(3, intro.size()); i++) {
            Ui.text(g, font, intro.get(i), x + PAD, iy, Ui.MUTED, SMALL);
            iy += 7;
        }

        // the list
        int lx = x + PAD, ly = iy + LIST_TOP_GAP, lw = W - 2 * PAD - SCROLL_W - 2, lh = y + H - PAD - FOOTER_H - 4 - ly;
        Ui.inset(g, lx, ly, lw, lh);
        drawRules(g, lx, ly, lw, lh, mx, my);
        drawScrollBar(g, lx + lw + 2, ly, lh);

        // the footer
        int fy = y + H - PAD - FOOTER_H;
        if (rules.agree()) {
            String leave = Tr.t("rules.leave_server");
            int leaveW = Ui.width(font, leave, TINY) + 16;
            key(g, x + PAD, fy, leaveW, leave, KeyStyle.LEAVE, mx, my, this::leave);
            String agree = Tr.t("rules.agree");
            int agreeW = Math.max(52, Ui.width(font, agree, TINY) + 20);
            key(g, x + W - PAD - agreeW, fy, agreeW, agree, allTicked() ? KeyStyle.TEAL : KeyStyle.LOCKED, mx, my, allTicked() ? this::agree : null);
            String hint = Ui.fit(font, Tr.t(allTicked() ? "rules.every_rule_ticked" : "rules.tick_every_rule"), W - 4 * PAD - leaveW - agreeW, TINY);
            int hintW = Ui.width(font, hint, TINY);
            Ui.text(g, font, hint, x + (W - hintW) / 2, fy + 5, allTicked() ? Ui.TEAL : Ui.MUTED, TINY);
        } else {
            if (rules.agreedAtMs() > 0) {
                String when = DAY.format(Instant.ofEpochMilli(rules.agreedAtMs()).atZone(ZoneId.systemDefault()));
                Ui.text(g, font, Ui.fit(font, Tr.t("rules.you_agreed_on", when), W - 3 * PAD - 60, TINY), x + PAD, fy + 5, Ui.MUTED, TINY);
            }
            String close = Tr.t("rules.close");
            int closeW = Math.max(52, Ui.width(font, close, TINY) + 20);
            key(g, x + W - PAD - closeW, fy, closeW, close, KeyStyle.GHOST, mx, my, this::onClose);
        }
    }

    private void drawRules(GuiGraphics g, int lx, int ly, int lw, int lh, int mx, int my) {
        List<RuleSet.Rule> list = rules.rules();
        if (list.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("rules.no_rules"), lw - 8, SMALL), lx + 5, ly + 5, Ui.MUTED, SMALL);
            contentH = 0;
            return;
        }
        int maxScroll = Math.max(0, contentH - lh);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        Ui.scissor(g, lx + 1, ly + 1, lx + lw - 1, ly + lh - 1);
        int textX = lx + ROW_PAD + NUM_W;
        int textW = lw - ROW_PAD * 2 - NUM_W - (rules.agree() ? TICK + 6 : 0);
        int ry = ly + 1 - scroll;
        int total = 0;
        for (int i = 0; i < list.size(); i++) {
            RuleSet.Rule r = list.get(i);
            List<String> body = Ui.wrap(font, r.text(), textW, SMALL);
            int rowH = ROW_PAD + 9 + body.size() * 7 + ROW_PAD;
            if (i > 0) {
                g.fill(lx + 1, ry, lx + lw - 1, ry + 1, Ui.ROW_LINE);
            }
            boolean visible = ry + rowH > ly && ry < ly + lh;
            if (visible) {
                boolean hover = rules.agree() && mx >= lx && mx < lx + lw && my >= Math.max(ry, ly) && my < Math.min(ry + rowH, ly + lh);
                if (hover) {
                    g.fill(lx + 1, ry + 1, lx + lw - 1, ry + rowH, 0x2219D3B0);
                }
                Ui.text(g, font, Integer.toString(i + 1), lx + ROW_PAD, ry + ROW_PAD + 1, Ui.TEAL, SMALL);
                Ui.text(g, font, Component.literal(Ui.fit(font, r.title(), textW, 1f)).withStyle(ChatFormatting.BOLD), textX, ry + ROW_PAD, Ui.TEXT, 1f);
                int by = ry + ROW_PAD + 10;
                for (String line : body) {
                    Ui.text(g, font, line, textX, by, Ui.MUTED, SMALL);
                    by += 7;
                }
                if (rules.agree()) {
                    int tx = lx + lw - ROW_PAD - TICK, ty = ry + ROW_PAD;
                    drawTick(g, tx, ty, ticked[i]);
                    int index = i;
                    int top = Math.max(ry, ly), bottom = Math.min(ry + rowH, ly + lh);
                    hits.add(new Ui.Hit(lx, top, lw, bottom - top, () -> ticked[index] = !ticked[index]));
                }
            }
            ry += rowH;
            total += rowH;
        }
        contentH = total + 2;
        g.disableScissor();
    }

    private static void drawTick(GuiGraphics g, int x, int y, boolean on) {
        if (on) {
            g.fill(x, y, x + TICK, y + TICK, Ui.TEAL);
            // the check mark, drawn in the panel's ink
            g.fill(x + 2, y + 4, x + 3, y + 6, Ui.INK);
            g.fill(x + 3, y + 5, x + 4, y + 7, Ui.INK);
            g.fill(x + 4, y + 4, x + 5, y + 6, Ui.INK);
            g.fill(x + 5, y + 3, x + 6, y + 5, Ui.INK);
            g.fill(x + 6, y + 2, x + 7, y + 4, Ui.INK);
        } else {
            Ui.frame(g, x, y, TICK, TICK, Ui.MUTED);
        }
    }

    private void drawScrollBar(GuiGraphics g, int x, int y, int h) {
        g.fill(x, y, x + SCROLL_W, y + h, Ui.DIM);
        if (contentH <= h) {
            g.fill(x, y, x + SCROLL_W, y + h, Ui.MUTED);
            return;
        }
        int thumb = Math.max(10, h * h / contentH);
        int ty = y + (h - thumb) * scroll / Math.max(1, contentH - h);
        g.fill(x, ty, x + SCROLL_W, ty + thumb, Ui.MUTED);
    }

    private enum KeyStyle { TEAL, LOCKED, LEAVE, GHOST }

    private void key(GuiGraphics g, int x, int y, int w, String label, KeyStyle style, int mx, int my, Runnable action) {
        boolean hover = action != null && mx >= x && mx < x + w && my >= y && my < y + FOOTER_H;
        int text;
        switch (style) {
            case TEAL -> {
                g.fill(x, y, x + w, y + FOOTER_H, hover ? 0xFF4FE3C6 : Ui.TEAL);
                text = Ui.INK;
            }
            case LOCKED -> {
                g.fill(x, y, x + w, y + FOOTER_H, 0x6619D3B0);
                text = 0x9910151C;
            }
            case LEAVE -> {
                if (hover) {
                    g.fill(x, y, x + w, y + FOOTER_H, 0x33FF6B5C);
                }
                Ui.frame(g, x, y, w, FOOTER_H, 0xFF5A2A2A);
                text = Ui.BAD;
            }
            default -> {
                if (hover) {
                    g.fill(x, y, x + w, y + FOOTER_H, 0x222A2F3A);
                }
                Ui.frame(g, x, y, w, FOOTER_H, Ui.DIM);
                text = Ui.MUTED;
            }
        }
        String s = Ui.fit(font, label, w - 6, TINY);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + (w - Ui.width(font, s, TINY)) / 2, y + 5, text, TINY);
        if (action != null) {
            hits.add(new Ui.Hit(x, y, w, FOOTER_H, action));
        }
    }

    // ------------------------------------------------------------------ input

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
        scroll -= (int) Math.signum(delta) * WHEEL_STEP;
        return true;
    }

    private void agree() {
        if (parent != null) {
            onClose();
            return;
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < ticked.length; i++) {
            if (ticked[i]) {
                indexes.add(i);
            }
        }
        ClientRules.answered();
        ServerUtilsNetwork.CHANNEL.sendToServer(new RulesAnswerPacket(RulesAnswerPacket.Action.AGREE, rules.version(), rules.revision(), indexes));
        super.onClose();
    }

    private void leave() {
        if (parent != null) {
            onClose();
            return;
        }
        ClientRules.answered();
        ServerUtilsNetwork.CHANNEL.sendToServer(new RulesAnswerPacket(RulesAnswerPacket.Action.LEAVE, rules.version(), rules.revision(), List.of()));
    }

    /** Ticks carried over when the same rules are sent again (a held player typing {@code /rules}). */
    void copyTicksFrom(RulesScreen other) {
        if (other.rules.revision() == rules.revision() && other.ticked.length == ticked.length) {
            System.arraycopy(other.ticked, 0, ticked, 0, ticked.length);
            scroll = other.scroll;
        }
    }
}
