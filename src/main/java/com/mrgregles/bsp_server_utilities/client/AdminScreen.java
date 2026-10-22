package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.AdminRulesPacket;
import com.mrgregles.bsp_server_utilities.network.AdminViewPacket;
import com.mrgregles.bsp_server_utilities.network.RulesOpenPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The admin panel ({@code /su}, owner decisions D6, D34 to D39), drawn as the confirmed page
 * {@code tools/preview/rules_refined.html}: a violet panel with the RULES, STAFF ({@link StaffTab}), LOG ({@link LogTab}), AFK ({@link AfkTab}), CHUNKS ({@link ChunksTab}) and DROPS ({@link DropsTab}) tabs.
 * On the left POPUP TEXT, the rules in order and + ADD RULE; on the right the picked row's boxes with counters against
 * the limits, UP, DOWN and DELETE (SURE? within three seconds). The footer has the version and how many players agreed,
 * PREVIEW (the popup as a player sees it) and ASK EVERYONE AGAIN (SURE?). Edits are sent a second after typing stops,
 * and when another row is picked or the panel closes; the server answers with a fresh view.
 */
public class AdminScreen extends Screen {
    static final int W = 344, H = 210;
    private static final int PAD = 8, LIST_X = 8, LIST_Y = 22, LIST_W = 110, LIST_H = 158, ROW_H = 12;
    private static final int PANE_X = 126, PANE_W = 210, KEY_H = 13, FOOTER_H = 14;
    private static final float SMALL = 0.7f, TINY = 0.6f;
    private static final int SAVE_DELAY_TICKS = 20;
    private static final long SURE_MS = 3000;
    /** The row picked: {@link #POPUP} for the heading and texts, else a rule's index. */
    private static final int POPUP = -1;

    private AdminViewPacket view;
    private int selected = POPUP;
    private int listScroll;
    private final List<Ui.Hit> hits = new ArrayList<>();
    // the boxes' text, kept here because the boxes are made again on every resize
    private String fTitle = "", fText = "", fHeading = "", fIntro = "", fLeave = "";
    @Nullable
    private EditBox titleBox, headingBox;
    @Nullable
    private MultiLineEditBox textBox, introBox, leaveBox;
    private boolean dirty, loading;
    private int dirtyTicks;
    private boolean selectLastOnView;
    private int moveFollow;
    private long deleteSureUntil, askSureUntil;
    private String note = "";
    private long noteUntil;
    private static final int RULES = 0, STAFF = 1, LOG = 2, AFK = 3, CHUNKS = 4, DROPS = 5;
    private int tab = RULES;
    private final StaffTab staff = new StaffTab(this);
    private final LogTab log = new LogTab(this);
    private final AfkTab afk = new AfkTab(this);
    private final ChunksTab chunks = new ChunksTab(this);
    private final DropsTab drops = new DropsTab(this);

    public AdminScreen(AdminViewPacket view) {
        super(Component.literal(Tr.t("admin.title")));
        this.view = view;
        loadFields();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    // ------------------------------------------------------------------ data

    /** A fresh view from the server. */
    void accept(AdminViewPacket next) {
        view = next;
        int before = selected;
        int n = next.rules().size();
        if (selectLastOnView && n > 0) {
            selected = n - 1;
            selectLastOnView = false;
            dirty = false;
        } else if (moveFollow != 0) {
            selected = Mth.clamp(selected + moveFollow, 0, Math.max(0, n - 1));
            moveFollow = 0;
        }
        if (selected >= n) {
            selected = n == 0 ? POPUP : n - 1;
        }
        if (selected != before) {
            dirty = false;
            loadFields();
            rebuild();
        } else if (!dirty) {
            loadFields();
            refreshBoxes();
        }
        showNote(next.note());
    }

    /** Puts the fresh texts into the boxes nobody is typing in (a box with the focus keeps what it has). */
    private void refreshBoxes() {
        loading = true;
        if (titleBox != null && !titleBox.isFocused() && !titleBox.getValue().equals(fTitle)) {
            titleBox.setValue(fTitle);
        }
        if (headingBox != null && !headingBox.isFocused() && !headingBox.getValue().equals(fHeading)) {
            headingBox.setValue(fHeading);
        }
        set(textBox, fText);
        set(introBox, fIntro);
        set(leaveBox, fLeave);
        loading = false;
    }

    private static void set(@Nullable MultiLineEditBox box, String value) {
        if (box != null && !box.isFocused() && !box.getValue().equals(value)) {
            box.setValue(value);
        }
    }

    private void loadFields() {
        loading = true;
        fHeading = view.heading();
        fIntro = view.intro();
        fLeave = view.leaveMessage();
        if (selected >= 0 && selected < view.rules().size()) {
            fTitle = view.rules().get(selected).title();
            fText = view.rules().get(selected).text();
        } else {
            fTitle = "";
            fText = "";
        }
        loading = false;
    }

    private void changed() {
        if (!loading) {
            dirty = true;
            dirtyTicks = SAVE_DELAY_TICKS;
        }
    }

    /** Sends what was typed, if anything; a rule's empty title is not sent (it would remove the rule). */
    private void save() {
        if (!dirty) {
            return;
        }
        if (selected == POPUP) {
            send(new AdminRulesPacket(AdminRulesPacket.Action.TEXTS, -1, fHeading, fIntro, fLeave));
        } else if (selected < view.rules().size()) {
            if (fTitle.isBlank()) {
                showNote("no_title");
                return;
            }
            send(new AdminRulesPacket(AdminRulesPacket.Action.SET, selected, fTitle, fText, ""));
        }
        dirty = false;
    }

    private static void send(AdminRulesPacket packet) {
        ServerUtilsNetwork.CHANNEL.sendToServer(packet);
    }

    private void pick(int row) {
        if (row == selected) {
            return;
        }
        save();
        dirty = false;
        selected = row;
        deleteSureUntil = 0;
        loadFields();
        rebuild();
    }

    private void showNote(String key) {
        String text = switch (key) {
            case "saved" -> Tr.t("note.saved");
            case "added" -> Tr.t("note.added");
            case "deleted" -> Tr.t("note.deleted");
            case "full" -> Tr.t("note.full", RuleSet.MAX_RULES);
            case "asked_again" -> Tr.t("note.asked_again");
            case "refused" -> Tr.t("note.refused");
            case "no_title" -> Tr.t("note.no_title");
            default -> "";
        };
        if (!text.isEmpty()) {
            note = text;
            noteUntil = System.currentTimeMillis() + 3000;
        }
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        rebuild();
        send(AdminRulesPacket.of(AdminRulesPacket.Action.REFRESH)); // fresh on opening, and when back from PREVIEW
        openTab();
    }

    Font font() {
        return font;
    }

    <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
    }

    void rebuildTab() {
        rebuild();
    }

    void acceptStaff(com.mrgregles.bsp_server_utilities.network.AdminStaffPacket p) {
        staff.accept(p);
    }

    void acceptLog(com.mrgregles.bsp_server_utilities.network.AdminLogPacket p) {
        log.accept(p);
    }

    void acceptAfk(com.mrgregles.bsp_server_utilities.network.AdminAfkPacket p) {
        afk.accept(p);
    }

    void acceptChunks(com.mrgregles.bsp_server_utilities.network.AdminChunksPacket p) {
        chunks.accept(p);
    }

    void acceptDrops(com.mrgregles.bsp_server_utilities.network.AdminDropsPacket p) {
        drops.accept(p);
    }

    private void openTab() {
        if (tab == STAFF) {
            staff.open();
        } else if (tab == LOG) {
            log.open();
        } else if (tab == AFK) {
            afk.open();
        } else if (tab == CHUNKS) {
            chunks.open();
        } else if (tab == DROPS) {
            drops.open();
        }
    }

    private void showTab(int next) {
        if (tab == next) {
            return;
        }
        save();
        staff.flush();
        afk.flush();
        tab = next;
        rebuild();
        openTab();
    }

    private void rebuild() {
        clearWidgets();
        titleBox = headingBox = null;
        textBox = introBox = leaveBox = null;
        if (tab == STAFF) {
            staff.addWidgets(left(), top());
            return;
        }
        if (tab == LOG) {
            log.addWidgets(left(), top());
            return;
        }
        if (tab == AFK) {
            afk.addWidgets(left(), top());
            return;
        }
        if (tab == CHUNKS) {
            chunks.addWidgets(left(), top());
            return;
        }
        if (tab == DROPS) {
            drops.addWidgets(left(), top());
            return;
        }
        int x = left() + PANE_X, y = top();
        loading = true;
        if (selected == POPUP) {
            headingBox = new EditBox(font, x + 1, y + 30, PANE_W - 2, 12, Component.literal(Tr.t("rules.heading")));
            headingBox.setMaxLength(RuleSet.MAX_HEADING);
            headingBox.setValue(fHeading);
            headingBox.setResponder(s -> {
                fHeading = s;
                changed();
            });
            addRenderableWidget(headingBox);
            introBox = multi(x, y + 54, 48, RuleSet.MAX_INTRO, fIntro, Tr.t("rules.intro"), s -> fIntro = s);
            leaveBox = multi(x, y + 113, 47, RuleSet.MAX_LEAVE, fLeave, Tr.t("rules.leave_message"), s -> fLeave = s);
        } else {
            titleBox = new EditBox(font, x + 1, y + 30, PANE_W - 2, 12, Component.literal(Tr.t("rules.title")));
            titleBox.setMaxLength(RuleSet.MAX_TITLE);
            titleBox.setValue(fTitle);
            titleBox.setResponder(s -> {
                fTitle = s;
                changed();
            });
            addRenderableWidget(titleBox);
            textBox = multi(x, y + 54, 106, RuleSet.MAX_TEXT, fText, Tr.t("rules.text"), s -> fText = s);
        }
        loading = false;
    }

    private MultiLineEditBox multi(int x, int y, int h, int limit, String value, String label, java.util.function.Consumer<String> into) {
        MultiLineEditBox box = new MultiLineEditBox(font, x, y, PANE_W, h, Component.empty(), Component.literal(label));
        box.setCharacterLimit(limit);
        box.setValue(value);
        box.setValueListener(s -> {
            into.accept(s);
            changed();
        });
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void tick() {
        staff.tick();
        if (tab == LOG) {
            log.tick();
        }
        if (tab == AFK) {
            afk.tick();
        }
        if (tab == CHUNKS) {
            chunks.tick();
        }
        if (tab == DROPS) {
            drops.tick();
        }
        if (titleBox != null) {
            titleBox.tick();
        }
        if (headingBox != null) {
            headingBox.tick();
        }
        for (MultiLineEditBox b : new MultiLineEditBox[]{textBox, introBox, leaveBox}) {
            if (b != null) {
                b.tick();
            }
        }
        if (dirty && --dirtyTicks <= 0) {
            save();
        }
    }

    @Override
    public void removed() {
        save();
        staff.flush();
        afk.flush();
        super.removed();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        hits.clear();
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, Ui.PANEL);
        Ui.frame(g, x, y, W, H, Ui.VIOLET);
        String[] tabs = {Tr.t("admin.tab.rules"), Tr.t("admin.tab.staff"), Tr.t("admin.tab.log"), Tr.t("admin.tab.afk"), Tr.t("admin.tab.chunks"), Tr.t("admin.tab.drops")};
        int tx = x + W - PAD;
        for (int i = tabs.length - 1; i >= 0; i--) {
            int tabW = Math.max(36, Ui.width(font, tabs[i], TINY) + 12);
            tx -= tabW;
            boolean on = i == tab;
            g.fill(tx, y + 5, tx + tabW, y + 18, on ? Ui.VIOLET : Ui.DIM);
            Ui.text(g, font, Component.literal(tabs[i]).withStyle(ChatFormatting.BOLD), tx + (tabW - Ui.width(font, tabs[i], TINY)) / 2, y + 9, on ? Ui.INK : Ui.MUTED, TINY);
            int which = i;
            hits.add(new Ui.Hit(tx, y + 5, tabW, 13, () -> showTab(which)));
            tx -= 4;
        }
        // the title in the room the tabs leave, smaller when six tabs need it
        String title = Tr.t("admin.title");
        float titleScale = Math.min(1f, (tx - x - PAD - 2) / (float) Math.max(1, Ui.width(font, title, 1f)));
        Ui.text(g, font, Component.literal(title).withStyle(ChatFormatting.BOLD), x + PAD, y + 7 + Math.round((1f - titleScale) * 3), Ui.VIOLET, titleScale);
        if (tab == STAFF) {
            staff.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial);
            return;
        }
        if (tab == DROPS) {
            drops.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the search box
            return;
        }
        if (tab == CHUNKS) {
            chunks.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the name box
            return;
        }
        if (tab == AFK) {
            afk.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the message box
            return;
        }
        if (tab == LOG) {
            log.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the search box
            log.overlay(g, x, y, mx, my, hits);
            return;
        }
        drawList(g, x, y, mx, my);
        drawPane(g, x, y, mx, my);
        drawFooter(g, x, y, mx, my);
        super.render(g, mx, my, partial);
    }

    private void drawList(GuiGraphics g, int x, int y, int mx, int my) {
        int lx = x + LIST_X, ly = y + LIST_Y;
        Ui.inset(g, lx, ly, LIST_W, LIST_H);
        int rowsH = LIST_H - ROW_H - 4; // the + ADD RULE row stays at the bottom
        List<RuleSet.Rule> rules = view.rules();
        int contentH = (rules.size() + 1) * ROW_H;
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, contentH - rowsH));
        Ui.scissor(g, lx + 1, ly + 1, lx + LIST_W - 1, ly + 1 + rowsH);
        int ry = ly + 2 - listScroll;
        row(g, lx, ry, POPUP, Component.literal(Tr.t("rules.popup_text")).withStyle(ChatFormatting.BOLD), Ui.VIOLET, mx, my, ly, rowsH);
        for (int i = 0; i < rules.size(); i++) {
            ry += ROW_H;
            String title = Ui.fit(font, rules.get(i).title(), LIST_W - 22, SMALL);
            row(g, lx, ry, i, Component.literal(title), Ui.TEXT, mx, my, ly, rowsH);
            Ui.text(g, font, Integer.toString(i + 1), lx + 5, ry + 3, Ui.VIOLET, SMALL);
        }
        g.disableScissor();
        // + ADD RULE and the count
        int ay = ly + LIST_H - ROW_H - 1;
        boolean full = rules.size() >= RuleSet.MAX_RULES;
        boolean hover = !full && mx >= lx && mx < lx + LIST_W && my >= ay && my < ay + ROW_H;
        if (hover) {
            g.fill(lx + 1, ay, lx + LIST_W - 1, ay + ROW_H, 0x22B58CFF);
        }
        Ui.text(g, font, Component.literal(Tr.t("rules.add_rule")).withStyle(ChatFormatting.BOLD), lx + 5, ay + 3, full ? Ui.MUTED : Ui.VIOLET, SMALL);
        String count = Tr.t("rules.count", rules.size(), RuleSet.MAX_RULES);
        Ui.text(g, font, count, lx + LIST_W - 5 - Ui.width(font, count, TINY), ay + 4, Ui.MUTED, TINY);
        if (!full) {
            hits.add(new Ui.Hit(lx, ay, LIST_W, ROW_H, this::addRule));
        }
    }

    private void row(GuiGraphics g, int lx, int ry, int index, Component label, int colour, int mx, int my, int ly, int rowsH) {
        boolean sel = index == selected;
        if (sel) {
            g.fill(lx + 1, ry, lx + LIST_W - 1, ry + ROW_H, 0xFF1B2230);
            g.fill(lx + 1, ry, lx + 3, ry + ROW_H, Ui.VIOLET);
        } else if (mx >= lx && mx < lx + LIST_W && my >= ry && my < ry + ROW_H && my >= ly && my < ly + 1 + rowsH) {
            g.fill(lx + 1, ry, lx + LIST_W - 1, ry + ROW_H, 0x22B58CFF);
        }
        Ui.text(g, font, label, lx + (index == POPUP ? 5 : 15), ry + 3, colour, SMALL);
        int top = Math.max(ry, ly + 1), bottom = Math.min(ry + ROW_H, ly + 1 + rowsH);
        if (bottom > top) {
            hits.add(new Ui.Hit(lx, top, LIST_W, bottom - top, () -> pick(index)));
        }
    }

    private void drawPane(GuiGraphics g, int x, int y, int mx, int my) {
        int px = x + PANE_X;
        if (selected == POPUP) {
            label(g, px, y + 22, Tr.t("rules.heading"), fHeading.length(), RuleSet.MAX_HEADING);
            label(g, px, y + 46, Tr.t("rules.intro"), fIntro.length(), RuleSet.MAX_INTRO);
            label(g, px, y + 105, Tr.t("rules.leave_message"), fLeave.length(), RuleSet.MAX_LEAVE);
        } else {
            label(g, px, y + 22, Tr.t("rules.title"), fTitle.length(), RuleSet.MAX_TITLE);
            label(g, px, y + 46, Tr.t("rules.text"), fText.length(), RuleSet.MAX_TEXT);
            int ky = y + 164;
            int n = view.rules().size();
            key(g, px, ky, 26, Tr.t("rules.up"), KeyStyle.PLAIN, selected > 0, mx, my, () -> move(-1));
            key(g, px + 30, ky, 34, Tr.t("rules.down"), KeyStyle.PLAIN, selected < n - 1, mx, my, () -> move(1));
            boolean sure = System.currentTimeMillis() < deleteSureUntil;
            key(g, px + PANE_W - 44, ky, 44, Tr.t(sure ? "rules.sure" : "rules.delete"), sure ? KeyStyle.SURE : KeyStyle.WARN, true, mx, my, this::delete);
        }
        if (System.currentTimeMillis() < noteUntil) {
            String s = Ui.fit(font, note, PANE_W, TINY);
            Ui.text(g, font, s, px + PANE_W - Ui.width(font, s, TINY), y + 180, Ui.MUTED, TINY);
        }
    }

    private void label(GuiGraphics g, int px, int y, String label, int used, int max) {
        Ui.text(g, font, label.toUpperCase(Locale.ROOT), px, y, Ui.MUTED, TINY);
        String count = Tr.t("rules.count", used, max);
        Ui.text(g, font, count, px + PANE_W - Ui.width(font, count, TINY), y, used >= max ? Ui.GOLD : Ui.MUTED, TINY);
    }

    private void drawFooter(GuiGraphics g, int x, int y, int mx, int my) {
        int fy = y + H - PAD - FOOTER_H;
        String status = view.agreed() < 0 ? Tr.t("rules.version_counting", view.version()) : Tr.t("rules.version_agreed", view.version(), view.agreed());
        boolean sure = System.currentTimeMillis() < askSureUntil;
        String ask = Tr.t(sure ? "rules.sure" : "rules.ask_again");
        int askW = Ui.width(font, Tr.t("rules.ask_again"), TINY) + 16;
        String preview = Tr.t("rules.preview");
        int previewW = Math.max(46, Ui.width(font, preview, TINY) + 16);
        key(g, x + W - PAD - askW, fy, askW, ask, sure ? KeyStyle.SURE : KeyStyle.WARN, true, mx, my, this::askAgain, FOOTER_H);
        key(g, x + W - PAD - askW - 4 - previewW, fy, previewW, preview, KeyStyle.GHOST, !view.rules().isEmpty(), mx, my, this::preview, FOOTER_H);
        Ui.text(g, font, Ui.fit(font, status, W - 3 * PAD - askW - previewW, TINY), x + PAD, fy + 5, Ui.MUTED, TINY);
    }

    private enum KeyStyle { PLAIN, WARN, SURE, GHOST }

    private void key(GuiGraphics g, int x, int y, int w, String label, KeyStyle style, boolean enabled, int mx, int my, Runnable action) {
        key(g, x, y, w, label, style, enabled, mx, my, action, KEY_H);
    }

    private void key(GuiGraphics g, int x, int y, int w, String label, KeyStyle style, boolean enabled, int mx, int my, Runnable action, int h) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int text;
        switch (style) {
            case WARN -> {
                g.fill(x, y, x + w, y + h, hover ? 0xFF4A3030 : 0xFF3A2A2A);
                Ui.frame(g, x, y, w, h, 0xFFD63B2F);
                text = 0xFFFF8F86;
            }
            case SURE -> {
                g.fill(x, y, x + w, y + h, 0xFFD63B2F);
                text = 0xFFFFFFFF;
            }
            case GHOST -> {
                if (hover) {
                    g.fill(x, y, x + w, y + h, 0x222A2F3A);
                }
                Ui.frame(g, x, y, w, h, Ui.DIM);
                text = Ui.MUTED;
            }
            default -> {
                g.fill(x, y, x + w, y + h, hover ? 0xFF3A404C : Ui.DIM);
                text = Ui.TEXT;
            }
        }
        if (!enabled) {
            text = 0xFF5A6170;
        }
        String s = Ui.fit(font, label, w - 6, TINY);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + (w - Ui.width(font, s, TINY)) / 2, y + (h - 5) / 2, text, TINY);
        if (enabled) {
            hits.add(new Ui.Hit(x, y, w, h, action));
        }
    }

    // ------------------------------------------------------------------ actions

    private void addRule() {
        save();
        selectLastOnView = true;
        send(new AdminRulesPacket(AdminRulesPacket.Action.ADD, -1, Tr.t("rules.new_rule"), "", ""));
    }

    private void move(int delta) {
        save();
        moveFollow = delta;
        send(new AdminRulesPacket(AdminRulesPacket.Action.MOVE, selected, Integer.toString(delta), "", ""));
    }

    private void delete() {
        long now = System.currentTimeMillis();
        if (now >= deleteSureUntil) {
            deleteSureUntil = now + SURE_MS;
            return;
        }
        deleteSureUntil = 0;
        dirty = false;
        send(new AdminRulesPacket(AdminRulesPacket.Action.DELETE, selected, "", "", ""));
    }

    private void askAgain() {
        long now = System.currentTimeMillis();
        if (now >= askSureUntil) {
            askSureUntil = now + SURE_MS;
            return;
        }
        askSureUntil = 0;
        save();
        send(AdminRulesPacket.of(AdminRulesPacket.Action.ASK_AGAIN));
    }

    private void preview() {
        save();
        RuleSet shown = view.ruleSet();
        minecraft.setScreen(new RulesScreen(RulesOpenPacket.of(shown, true, 0), this));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Ui.Hit hit : List.copyOf(hits)) {
                if (hit.contains(mx, my)) {
                    setFocused(null);
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tab == STAFF) {
            return staff.scroll(delta);
        }
        if (tab == LOG) {
            return log.scroll(mx, delta);
        }
        if (tab == AFK) {
            return afk.scroll(delta);
        }
        if (tab == CHUNKS) {
            return chunks.scroll(delta);
        }
        if (tab == DROPS) {
            return drops.scroll(delta);
        }
        int lx = left() + LIST_X, ly = top() + LIST_Y;
        if (mx >= lx && mx < lx + LIST_W && my >= ly && my < ly + LIST_H) {
            listScroll -= (int) Math.signum(delta) * ROW_H;
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }
}
