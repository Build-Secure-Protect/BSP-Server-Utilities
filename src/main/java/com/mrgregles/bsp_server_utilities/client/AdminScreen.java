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
 * The admin panel ({@code /su}, owner decisions D6, D34 to D39), almost full screen with a list of tabs down the left
 * (D754 to D757, {@code tools/preview/staff_fullscreen_refined.html}): the RULES page ({@code tools/preview/rules_refined.html}),
 * STAFF ({@link StaffTab}), LOG ({@link LogTab}), AFK ({@link AfkTab}), CHUNKS ({@link ChunksTab}), DROPS ({@link DropsTab}), CLIENTS ({@link ClientsTab}), SERVERS ({@link ServersTab}), ROOMS ({@link RoomsTab}), MOBS ({@link MobsTab}), SUPPORT ({@link SupportTab}) and TUTORIAL ({@link TutorialTab}).
 * On the left POPUP TEXT, the rules in order and + ADD RULE; on the right the picked row's boxes with counters against
 * the limits, UP, DOWN and DELETE (SURE? within three seconds). The footer has the version and how many players agreed,
 * PREVIEW (the popup as a player sees it) and ASK EVERYONE AGAIN (SURE?). Edits are sent a second after typing stops,
 * and when another row is picked or the panel closes; the server answers with a fresh view.
 */
public class AdminScreen extends Screen {
    /**
     * The screen fills the window less {@link #MARGIN} on each side and is never smaller than the panel it once was, unless
     * the window is (D756). The tab list runs down the left ({@link #SIDE_W}) under the title bar ({@link #TITLE_H}); each
     * tab draws in the page beside it: {@link #left()}, {@link #top()}, {@link #pageW()}, {@link #pageH()} (D754).
     */
    static final int MIN_W = 344, MIN_H = 210, MARGIN = 8, SIDE_W = 74, TITLE_H = 18;
    /** Below this panel height the tab list packs tighter so every tab fits. */
    private static final int COMPACT_BELOW = 254;
    private static final int PAD = 8, LIST_X = 8, LIST_Y = 22, LIST_W = 110, ROW_H = 12;
    private static final int PANE_X = 126, PANE_MAX = 300, KEY_H = 13, FOOTER_H = 14;
    /** How often the tab list's counts are asked for while the screen is open. */
    private static final int COUNTS_TICKS = 200;
    private static final float SMALL = 0.7f, TINY = 0.6f, TAB_SCALE = 0.55f;
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
    private static final int RULES = 0, STAFF = 1, LOG = 2, AFK = 3, CHUNKS = 4, DROPS = 5, CLIENTS = 6, SERVERS = 7, ROOMS = 8, MOBS = 9, SUPPORT = 10, TUTORIAL = 11;
    /** The tab list's groups (D755), each a heading key and its tabs in order. */
    private static final String[] GROUP_KEYS = {"admin.group.players", "admin.group.staff", "admin.group.world", "admin.group.network"};
    private static final int[][] GROUPS = {{RULES, CLIENTS, AFK}, {STAFF, LOG}, {CHUNKS, MOBS, DROPS, ROOMS}, {SERVERS, SUPPORT, TUTORIAL}};
    /** Each tab's name and its page line, written out in full so the language check sees every key. */
    private static final String[] TAB_KEYS = {"admin.tab.rules", "admin.tab.staff", "admin.tab.log", "admin.tab.afk", "admin.tab.chunks", "admin.tab.drops", "admin.tab.clients", "admin.tab.servers", "admin.tab.rooms", "admin.tab.mobs", "admin.tab.support", "admin.tab.tutorial"};
    private static final String[] PAGE_KEYS = {"admin.page.rules", "admin.page.staff", "admin.page.log", "admin.page.afk", "admin.page.chunks", "admin.page.drops", "admin.page.clients", "admin.page.servers", "admin.page.rooms", "admin.page.mobs", "admin.page.support", "admin.page.tutorial"};
    private int tab = RULES;
    @Nullable
    private com.mrgregles.bsp_server_utilities.network.AdminCountsPacket counts;
    private int countsIn;
    /** When the arrow keys last moved the tab list: held keys repeat, and each tab asks the server for its data. */
    private long arrowAt;
    private final StaffTab staff = new StaffTab(this);
    private final LogTab log = new LogTab(this);
    private final AfkTab afk = new AfkTab(this);
    private final ChunksTab chunks = new ChunksTab(this);
    private final DropsTab drops = new DropsTab(this);
    private final ClientsTab clients = new ClientsTab(this);
    private final ServersTab servers = new ServersTab(this);
    private final RoomsTab rooms = new RoomsTab(this);
    private final MobsTab mobs = new MobsTab(this);
    private final SupportTab support = new SupportTab(this);
    private final TutorialTab tutorial = new TutorialTab(this);
    /** The setup guide, while the panel shows it (D156). */
    @Nullable
    private SetupGuide guide;

    public AdminScreen(AdminViewPacket view) {
        super(Component.literal(Tr.t("admin.title")));
        this.view = view;
        loadFields();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    int panelW() {
        return Math.max(width - 2 * MARGIN, Math.min(width, MIN_W));
    }

    int panelH() {
        return Math.max(height - 2 * MARGIN, Math.min(height, MIN_H));
    }

    int panelLeft() {
        return (width - panelW()) / 2;
    }

    int panelTop() {
        return (height - panelH()) / 2;
    }

    /** The height the setup guide's strip takes under the title bar. */
    private int guideOff() {
        return guide != null ? SetupGuide.OFFSET : 0;
    }

    /** The page's left edge: tabs draw from here, 8 in from it. */
    int left() {
        return panelLeft() + SIDE_W;
    }

    /** The page's top: a tab's heading line takes the first 22, its body starts below that. */
    int top() {
        return panelTop() + TITLE_H + guideOff();
    }

    /** The page's width, what each tab lays out in (the fixed panel's 344 once). */
    int pageW() {
        return panelW() - SIDE_W;
    }

    /** The page's height, down to the panel's bottom edge (the fixed panel's 210 once). */
    int pageH() {
        return panelH() - TITLE_H - guideOff();
    }

    /** The tab list's open-tab counts arrived: refused today, and chunks over a limit. */
    void acceptCounts(com.mrgregles.bsp_server_utilities.network.AdminCountsPacket p) {
        counts = p;
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
        if (counts == null) {
            askCounts(); // a resize runs init again: the timer asks after that (review of SU-154)
        }
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

    void acceptClients(com.mrgregles.bsp_server_utilities.network.AdminClientsPacket p) {
        clients.accept(p);
    }

    void acceptServers(com.mrgregles.bsp_server_utilities.network.AdminServersPacket p) {
        servers.accept(p);
    }

    void acceptRooms(com.mrgregles.bsp_server_utilities.network.AdminRoomsPacket p) {
        rooms.accept(p);
    }

    void acceptMobs(com.mrgregles.bsp_server_utilities.network.AdminMobsPacket p) {
        mobs.accept(p);
    }

    void acceptSupport(com.mrgregles.bsp_server_utilities.network.AdminSupportPacket p) {
        support.accept(p);
    }

    /** Whether the panel shows the setup guide (a tab drawn in it leaves the footer to the guide's keys). */
    boolean inGuide() {
        return guide != null;
    }

    /** The setup guide's data: opens the guide at its page, or refreshes it. */
    void acceptSetup(com.mrgregles.bsp_server_utilities.network.SetupViewPacket p) {
        if (p.open() || guide == null) {
            if (!p.open()) {
                return;
            }
            save();
            staff.flush();
            afk.flush();
            clients.flush();
            guide = new SetupGuide(this, p);
            guidePageChanged();
        } else {
            guide.accept(p);
        }
    }

    /** The guide moved to another page: show that page's tab (or its own body). */
    void guidePageChanged() {
        if (guide == null) {
            return;
        }
        com.mrgregles.bsp_server_utilities.setup.Feature f = guide.feature();
        int next = tab;
        if (f != null) {
            switch (f) {
                case STAFF -> next = STAFF;
                case RULES -> next = RULES;
                case LOG -> next = LOG;
                case AFK -> next = AFK;
                case CHUNKS -> next = CHUNKS;
                case DESPAWN -> next = DROPS;
                case SERVERS -> next = SERVERS;
                case TUTORIAL -> next = TUTORIAL;
                case CHECK -> {
                    next = CLIENTS;
                    clients.showRules();
                }
                default -> {
                }
            }
        }
        save();
        staff.flush();
        afk.flush();
        clients.flush();
        tab = next;
        rebuild();
        if (!guide.custom()) {
            openTab();
        }
    }

    /** The tab lit in the row: the page's tab, none for the guide's own pages. */
    private int litTab() {
        if (guide != null && guide.custom()) {
            return -1;
        }
        return tab;
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
        } else if (tab == CLIENTS) {
            clients.open();
        } else if (tab == SERVERS) {
            servers.open();
        } else if (tab == ROOMS) {
            rooms.open();
        } else if (tab == MOBS) {
            mobs.open();
        } else if (tab == SUPPORT) {
            support.open();
        } else if (tab == TUTORIAL) {
            tutorial.open();
        }
    }

    private void showTab(int next) {
        if (tab == next) {
            return;
        }
        log.closeMenu();
        if (tab == TUTORIAL) {
            tutorial.close();
        }
        save();
        staff.flush();
        afk.flush();
        clients.flush();
        tab = next;
        rebuild();
        openTab();
    }

    private void rebuild() {
        buildWidgets();
        if (getFocused() != null && !children().contains(getFocused())) {
            setFocused(null); // a box taken away keeps the focus otherwise, and swallows keys (review of SU-154)
        }
    }

    private void buildWidgets() {
        clearWidgets();
        titleBox = headingBox = null;
        textBox = introBox = leaveBox = null;
        if (guide != null && guide.custom()) {
            guide.addWidgets(left(), top());
            return;
        }
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
        if (tab == CLIENTS) {
            clients.addWidgets(left(), top());
            return;
        }
        if (tab == SERVERS) {
            return; // no boxes: the list, the previews and REFRESH are drawn
        }
        if (tab == ROOMS) {
            rooms.addWidgets(left(), top());
            return;
        }
        if (tab == MOBS) {
            mobs.addWidgets(left(), top());
            return;
        }
        if (tab == SUPPORT) {
            support.addWidgets(left(), top());
            return;
        }
        int x = left() + PANE_X, y = top(), paneW = paneW(), room = textRoom();
        loading = true;
        if (selected == POPUP) {
            headingBox = new EditBox(font, x + 1, y + 30, paneW - 2, 12, Component.literal(Tr.t("rules.heading")));
            headingBox.setMaxLength(RuleSet.MAX_HEADING);
            headingBox.setValue(fHeading);
            headingBox.setResponder(s -> {
                fHeading = s;
                changed();
            });
            addRenderableWidget(headingBox);
            int introH = introH();
            introBox = multi(x, y + 54, introH, RuleSet.MAX_INTRO, fIntro, Tr.t("rules.intro"), s -> fIntro = s);
            leaveBox = multi(x, y + 54 + introH + 11, room - introH - 11, RuleSet.MAX_LEAVE, fLeave, Tr.t("rules.leave_message"), s -> fLeave = s);
        } else {
            titleBox = new EditBox(font, x + 1, y + 30, paneW - 2, 12, Component.literal(Tr.t("rules.title")));
            titleBox.setMaxLength(RuleSet.MAX_TITLE);
            titleBox.setValue(fTitle);
            titleBox.setResponder(s -> {
                fTitle = s;
                changed();
            });
            addRenderableWidget(titleBox);
            textBox = multi(x, y + 54, room, RuleSet.MAX_TEXT, fText, Tr.t("rules.text"), s -> fText = s);
        }
        loading = false;
    }

    /** The RULES page's right side: as wide as the page allows, up to {@link #PANE_MAX} (D757). */
    private int paneW() {
        return Math.min(PANE_MAX, pageW() - PANE_X - PAD);
    }

    /** The rules list's height: down to the footer. */
    private int listH() {
        return pageH() - LIST_Y - 30;
    }

    /** The height under the first box for the text boxes: down to UP, DOWN and DELETE (106 on the fixed panel). */
    private int textRoom() {
        return pageH() - 104;
    }

    /** The popup's intro box: half the room, the leave message the rest. */
    private int introH() {
        return (textRoom() - 11) / 2;
    }

    private MultiLineEditBox multi(int x, int y, int h, int limit, String value, String label, java.util.function.Consumer<String> into) {
        MultiLineEditBox box = new MultiLineEditBox(font, x, y, paneW(), h, Component.empty(), Component.literal(label));
        box.setCharacterLimit(limit);
        box.setValue(value);
        box.setValueListener(s -> {
            into.accept(s);
            changed();
        });
        addRenderableWidget(box);
        return box;
    }

    private void askCounts() {
        countsIn = COUNTS_TICKS;
        ServerUtilsNetwork.CHANNEL.sendToServer(new com.mrgregles.bsp_server_utilities.network.AdminCountsAskPacket());
    }

    @Override
    public void tick() {
        if (--countsIn <= 0) {
            askCounts();
        }
        if (guide != null) {
            guide.tick();
        }
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
        if (tab == CLIENTS) {
            clients.tick();
        }
        if (tab == SERVERS) {
            servers.tick();
        }
        if (tab == ROOMS) {
            rooms.tick();
        }
        if (tab == MOBS) {
            mobs.tick();
        }
        if (tab == SUPPORT) {
            support.tick();
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
        clients.flush();
        if (guide != null) {
            guide.closing();
        }
        if (tab == TUTORIAL) {
            tutorial.close();
        }
        super.removed();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        hits.clear();
        int px = panelLeft(), py = panelTop(), pw = panelW(), ph = panelH();
        g.fill(px, py, px + pw, py + ph, Ui.PANEL);
        Ui.frame(g, px, py, pw, ph, Ui.VIOLET);
        drawTitleBar(g, px, py, pw, mx, my);
        if (guide != null) {
            guide.renderStrip(g, px + 1, py + TITLE_H, pw - 2);
        }
        drawSide(g, px, py + TITLE_H + guideOff(), py + ph, mx, my);
        int x = left(), y = top();
        drawPageHead(g, x, y);
        if (guide != null) {
            renderGuide(g, x, y, mx, my, partial);
            return;
        }
        renderTab(g, x, y, mx, my, partial);
    }

    /** The title, this server and how many are on it (from the counts), and the close key. */
    private void drawTitleBar(GuiGraphics g, int px, int py, int pw, int mx, int my) {
        g.fill(px + 1, py + TITLE_H - 1, px + pw - 1, py + TITLE_H, Ui.DIM);
        int cx = px + pw - 15;
        boolean hover = mx >= cx && mx < cx + 12 && my >= py + 3 && my < py + 15;
        if (hover) {
            g.fill(cx, py + 3, cx + 12, py + 15, 0x22B58CFF);
        }
        Ui.text(g, font, Component.literal("×").withStyle(ChatFormatting.BOLD), cx + 3, py + 5, hover ? Ui.VIOLET : Ui.MUTED, 1f);
        hits.add(new Ui.Hit(cx, py + 3, 12, 12, this::onClose));
        int room = cx - 6 - (px + PAD);
        Component title = Component.literal(Tr.t("admin.title")).withStyle(ChatFormatting.BOLD);
        if (font.width(title) > room) {
            title = Component.literal(Tr.t("admin.title_short")).withStyle(ChatFormatting.BOLD);
        }
        Ui.text(g, font, title, px + PAD, py + 5, Ui.VIOLET, 1f);
        int after = px + PAD + font.width(title) + 8;
        if (counts != null && !counts.here().isEmpty() && cx - 6 > after) {
            String where = Tr.t("admin.here", counts.here(), counts.online());
            Ui.text(g, font, Ui.fit(font, where, cx - 6 - after, TINY), after, py + 7, Ui.MUTED, TINY);
        }
    }

    /** The tab list (D754, D755): group headings, the open tab in violet, gold counts; in the guide, where it is only. */
    private void drawSide(GuiGraphics g, int px, int sy, int bottom, int mx, int my) {
        g.fill(px + 1, sy, px + SIDE_W, bottom - 1, Ui.SLOT);
        g.fill(px + SIDE_W - 1, sy, px + SIDE_W, bottom - 1, Ui.DIM);
        boolean compact = panelH() < COMPACT_BELOW;
        int itemH = compact ? 11 : 12, headH = compact ? 9 : 12, y = sy + 3, lit = litTab();
        for (int gi = 0; gi < GROUPS.length; gi++) {
            String head = Ui.fitBold(font, Tr.t(GROUP_KEYS[gi]).toUpperCase(Locale.ROOT), SIDE_W - 12, TINY);
            Ui.text(g, font, Component.literal(head).withStyle(ChatFormatting.BOLD), px + 7, y + headH - 7, 0xFF6B7180, TINY);
            y += headH;
            for (int t : GROUPS[gi]) {
                boolean on = t == lit, done = guide != null && t != lit && guide.done(featureOf(t));
                boolean hover = guide == null && !on && mx >= px + 1 && mx < px + SIDE_W - 1 && my >= y && my < y + itemH;
                if (on) {
                    g.fill(px + 1, y, px + SIDE_W - 1, y + itemH, 0xFF1A1530);
                    g.fill(px + 1, y, px + 3, y + itemH, Ui.VIOLET);
                } else if (hover) {
                    g.fill(px + 1, y, px + SIDE_W - 1, y + itemH, 0x22B58CFF);
                }
                int colour = on ? Ui.VIOLET : done ? Ui.TEAL : guide != null ? 0xFF6B7180 : Ui.MUTED;
                int dy = y + (itemH - 4) / 2;
                g.fill(px + 7, dy, px + 11, dy + 4, on ? Ui.VIOLET : done ? Ui.TEAL : 0xFF3A4150);
                String count = guide == null ? count(t) : "";
                int cw = count.isEmpty() ? 0 : Ui.width(font, count, TINY) + 3;
                String label = Ui.fitBold(font, Tr.t(TAB_KEYS[t]), SIDE_W - 22 - cw, SMALL);
                Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), px + 15, y + (itemH - 6) / 2, colour, SMALL);
                if (!count.isEmpty()) {
                    Ui.text(g, font, count, px + SIDE_W - 5 - Ui.width(font, count, TINY), y + (itemH - 4) / 2, Ui.GOLD, TINY);
                }
                int which = t;
                if (guide == null) {
                    hits.add(new Ui.Hit(px + 1, y, SIDE_W - 2, itemH, () -> showTab(which))); // in the guide the list only shows where you are (D161)
                }
                y += itemH;
            }
        }
    }

    /** A tab's gold count, or empty: refusals today on CLIENTS, chunks over a limit on ENTITIES. */
    private String count(int t) {
        if (counts == null) {
            return "";
        }
        int n = t == CLIENTS ? counts.refused() : t == MOBS ? counts.crowded() : 0;
        return n <= 0 ? "" : n > 99 ? "99+" : Integer.toString(n);
    }

    /** The page's heading line: the open tab's name and what it is for (the guide's own pages have none). */
    private void drawPageHead(GuiGraphics g, int x, int y) {
        int lit = litTab();
        if (lit < 0) {
            return;
        }
        String name = Tr.t(TAB_KEYS[lit]);
        Ui.text(g, font, Component.literal(name).withStyle(ChatFormatting.BOLD), x + PAD, y + 7, Ui.TEXT, 0.8f);
        int nw = Ui.widthBold(font, name, 0.8f) + 6;
        String about = Ui.fit(font, Tr.t(PAGE_KEYS[lit]), pageW() - 2 * PAD - nw, TINY);
        Ui.text(g, font, about, x + PAD + nw, y + 9, Ui.MUTED, TINY);
    }

    /** The guide's feature for a tab, for its done mark; null when the tab has no page of its own. */
    @Nullable
    private static com.mrgregles.bsp_server_utilities.setup.Feature featureOf(int t) {
        return switch (t) {
            case STAFF -> com.mrgregles.bsp_server_utilities.setup.Feature.STAFF;
            case RULES -> com.mrgregles.bsp_server_utilities.setup.Feature.RULES;
            case LOG -> com.mrgregles.bsp_server_utilities.setup.Feature.LOG;
            case AFK -> com.mrgregles.bsp_server_utilities.setup.Feature.AFK;
            case CHUNKS -> com.mrgregles.bsp_server_utilities.setup.Feature.CHUNKS;
            case DROPS -> com.mrgregles.bsp_server_utilities.setup.Feature.DESPAWN;
            case SERVERS -> com.mrgregles.bsp_server_utilities.setup.Feature.SERVERS;
            case TUTORIAL -> com.mrgregles.bsp_server_utilities.setup.Feature.TUTORIAL;
            case CLIENTS -> com.mrgregles.bsp_server_utilities.setup.Feature.CHECK;
            default -> null;
        };
    }

    /** The tabs in the list's order, for the arrow keys. */
    private static int[] order() {
        int[] out = new int[TAB_KEYS.length];
        int i = 0;
        for (int[] group : GROUPS) {
            for (int t : group) {
                out[i++] = t;
            }
        }
        return java.util.Arrays.copyOf(out, i);
    }

    /** Up and down move through the tab list while no box is being typed in (not in the guide). */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        boolean typing = getFocused() instanceof EditBox || getFocused() instanceof MultiLineEditBox;
        boolean menu = tab == LOG && log.menuOpen();
        if (guide == null && !typing && !menu && modifiers == 0 && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP || key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN)) {
            long now = System.currentTimeMillis();
            if (now - arrowAt < 150) {
                return true;
            }
            arrowAt = now;
            int[] order = order();
            int at = 0;
            for (int i = 0; i < order.length; i++) {
                if (order[i] == tab) {
                    at = i;
                }
            }
            int next = Mth.clamp(at + (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP ? -1 : 1), 0, order.length - 1);
            showTab(order[next]);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    /** A guide page: its own body, or the page's tab with the tab's footer hidden; then the strip and the footer keys. */
    private void renderGuide(GuiGraphics g, int x, int y, int mx, int my, float partial) {
        if (guide.custom()) {
            guide.renderCustom(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial);
        } else {
            int before = hits.size();
            Ui.scissor(g, x, y + 20, x + pageW(), y + pageH() - 24);
            renderTab(g, x, y, mx, my, partial);
            g.disableScissor();
            if (tab == LOG) {
                log.overlay(g, x, y, mx, my, hits);
            }
            int footer = y + pageH() - 24;
            for (int i = hits.size() - 1; i >= before; i--) {
                if (hits.get(i).y() >= footer) {
                    hits.remove(i); // the tab's own footer keys are under the guide's
                }
            }
        }
        guide.renderFrame(g, x, y, mx, my, hits);
    }

    private void renderTab(GuiGraphics g, int x, int y, int mx, int my, float partial) {
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
        if (tab == SERVERS) {
            servers.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial);
            return;
        }
        if (tab == ROOMS) {
            rooms.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the search box
            return;
        }
        if (tab == MOBS) {
            mobs.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the never-counted box
            return;
        }
        if (tab == SUPPORT) {
            support.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the link, sentence and chat link boxes
            return;
        }
        if (tab == TUTORIAL) {
            tutorial.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial);
            return;
        }
        if (tab == CLIENTS) {
            clients.render(g, x, y, mx, my, hits);
            super.render(g, mx, my, partial); // the reason, message and add boxes
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
            if (guide == null) {
                log.overlay(g, x, y, mx, my, hits); // in the guide it is drawn after the clip (audit of SU-154)
            }
            return;
        }
        drawList(g, x, y, mx, my);
        drawPane(g, x, y, mx, my);
        if (guide == null) {
            drawFooter(g, x, y, mx, my);
        }
        super.render(g, mx, my, partial);
    }

    private void drawList(GuiGraphics g, int x, int y, int mx, int my) {
        int lx = x + LIST_X, ly = y + LIST_Y;
        int listH = listH();
        Ui.inset(g, lx, ly, LIST_W, listH);
        int rowsH = listH - ROW_H - 4; // the + ADD RULE row stays at the bottom
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
        int ay = ly + listH - ROW_H - 1;
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
        int px = x + PANE_X, paneW = paneW();
        if (selected == POPUP) {
            label(g, px, y + 22, Tr.t("rules.heading"), fHeading.length(), RuleSet.MAX_HEADING);
            label(g, px, y + 46, Tr.t("rules.intro"), fIntro.length(), RuleSet.MAX_INTRO);
            label(g, px, y + 54 + introH() + 3, Tr.t("rules.leave_message"), fLeave.length(), RuleSet.MAX_LEAVE);
        } else {
            label(g, px, y + 22, Tr.t("rules.title"), fTitle.length(), RuleSet.MAX_TITLE);
            label(g, px, y + 46, Tr.t("rules.text"), fText.length(), RuleSet.MAX_TEXT);
            int ky = y + pageH() - 46;
            int n = view.rules().size();
            key(g, px, ky, 26, Tr.t("rules.up"), KeyStyle.PLAIN, selected > 0, mx, my, () -> move(-1));
            key(g, px + 30, ky, 34, Tr.t("rules.down"), KeyStyle.PLAIN, selected < n - 1, mx, my, () -> move(1));
            boolean sure = System.currentTimeMillis() < deleteSureUntil;
            key(g, px + paneW - 44, ky, 44, Tr.t(sure ? "rules.sure" : "rules.delete"), sure ? KeyStyle.SURE : KeyStyle.WARN, true, mx, my, this::delete);
        }
        if (System.currentTimeMillis() < noteUntil) {
            String s = Ui.fit(font, note, paneW, TINY);
            Ui.text(g, font, s, px + paneW - Ui.width(font, s, TINY), y + pageH() - 30, Ui.MUTED, TINY);
        }
    }

    private void label(GuiGraphics g, int px, int y, String label, int used, int max) {
        Ui.text(g, font, label.toUpperCase(Locale.ROOT), px, y, Ui.MUTED, TINY);
        String count = Tr.t("rules.count", used, max);
        Ui.text(g, font, count, px + paneW() - Ui.width(font, count, TINY), y, used >= max ? Ui.GOLD : Ui.MUTED, TINY);
    }

    private void drawFooter(GuiGraphics g, int x, int y, int mx, int my) {
        int fy = y + pageH() - PAD - FOOTER_H, W = pageW();
        String status = view.agreed() < 0 ? Tr.t("rules.version_counting", view.version()) : Tr.t("rules.version_agreed", view.version(), view.agreed());
        boolean sure = System.currentTimeMillis() < askSureUntil;
        String ask = Tr.t(sure ? "rules.sure" : "rules.ask_again");
        int askW = Ui.widthBold(font, Tr.t("rules.ask_again"), TINY) + 16;
        String preview = Tr.t("rules.preview");
        int previewW = Math.max(46, Ui.widthBold(font, preview, TINY) + 16);
        key(g, x + W - PAD - askW, fy, askW, ask, sure ? KeyStyle.SURE : KeyStyle.WARN, true, mx, my, this::askAgain, FOOTER_H);
        key(g, x + W - PAD - askW - 4 - previewW, fy, previewW, preview, KeyStyle.GHOST, !view.rules().isEmpty(), mx, my, this::preview, FOOTER_H);
        String setupKey = Tr.t("admin.setup.guide_key");
        int setupW = Ui.widthBold(font, setupKey, TINY) + 16;
        key(g, x + W - PAD - askW - 4 - previewW - 4 - setupW, fy, setupW, setupKey, KeyStyle.GHOST, true, mx, my,
                () -> ServerUtilsNetwork.CHANNEL.sendToServer(com.mrgregles.bsp_server_utilities.network.SetupEditPacket.of(com.mrgregles.bsp_server_utilities.network.SetupEditPacket.Action.OPEN)), FOOTER_H);
        Ui.text(g, font, Ui.fit(font, status, W - 4 * PAD - askW - previewW - setupW, TINY), x + PAD, fy + 5, Ui.MUTED, TINY);
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
        String s = Ui.fitBold(font, label, w - 6, TINY);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + (w - Ui.widthBold(font, s, TINY)) / 2, y + (h - 5) / 2, text, TINY);
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
        if (mx < left() || my < top()) {
            return false; // the tab list and the title bar do not scroll the page (review of SU-154)
        }
        if (tab == STAFF) {
            return staff.scroll(mx, delta);
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
        if (tab == CLIENTS) {
            return clients.scroll(mx, delta);
        }
        if (tab == SERVERS) {
            return servers.scroll(delta);
        }
        if (tab == ROOMS) {
            return rooms.scroll(mx, delta);
        }
        if (tab == MOBS) {
            return mobs.scroll(mx, my, delta);
        }
        if (tab == TUTORIAL) {
            return tutorial.scroll(delta); // the tutorials list (audit of SU-146)
        }
        if (tab == SUPPORT) {
            return super.mouseScrolled(mx, my, delta); // the sentence box, not the hidden rules list (audit of 0.15.0)
        }
        int lx = left() + LIST_X, ly = top() + LIST_Y;
        if (mx >= lx && mx < lx + LIST_W && my >= ly && my < ly + listH()) {
            listScroll -= (int) Math.signum(delta) * ROW_H;
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }
}
