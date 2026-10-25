package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.SetupEditPacket;
import com.mrgregles.bsp_server_utilities.network.SetupViewPacket;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The setup guide inside the admin panel (owner decisions D148 to D161), look G-C of {@code tools/preview/setup_refined.html}:
 * the panel as always with its tab row showing where the page is (clicks on it do nothing, D161), a progress bar and a
 * violet strip naming the step and what the feature does, the page's own tab as the body, and BACK, TURN OFF FEATURE (or
 * TURN ON), SETUP LATER and NEXT in place of the tab's footer. Welcome, Display panels, Player counter and Finish are
 * drawn here. Closing part way leaves the rest for later (D158). The Player counter page (D170, D173, D177 to D179, as
 * {@code tools/preview/counter_refined.html}): the number shown and the real count, SET and ADD with a typed number,
 * RESET TO 0 (SURE? within three seconds) and REAL COUNT; the title and the welcome line's words, sent when the page is
 * left, with the line as it will read.
 */
final class SetupGuide {
    static final int OFFSET = 14;
    /** Pages: every feature, then Finish. */
    static final int FINISH = Feature.values().length, PAGES = FINISH + 1;
    private static final long SURE_MS = 3000;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int GREEN = 0xFF8FE04A;

    private final AdminScreen host;
    private SetupViewPacket view;
    private int page;
    private boolean finished;
    private long offSureUntil;
    private String name;
    @Nullable
    private EditBox nameBox, numberBox, titleBox, welcomeBox;
    private String number = "", title, welcome, sentTitle, sentWelcome;
    private long resetSureUntil;

    SetupGuide(AdminScreen host, SetupViewPacket view) {
        this.host = host;
        this.view = view;
        this.page = view.startAt().ordinal();
        this.name = view.named().isEmpty() ? view.here() : view.named();
        this.title = sentTitle = view.players().title();
        this.welcome = sentWelcome = view.players().welcome();
    }

    void accept(SetupViewPacket next) {
        view = next;
    }

    int page() {
        return page;
    }

    @Nullable
    Feature feature() {
        return page < FINISH ? Feature.values()[page] : null;
    }

    /** Whether this page is drawn here rather than by a tab. */
    boolean custom() {
        Feature f = feature();
        return f == null || f == Feature.WELCOME || f == Feature.PANELS || f == Feature.PLAYERS;
    }

    private SetupState.Status status(Feature f) {
        return view.statuses().getOrDefault(f, SetupState.Status.NOT_YET);
    }

    private static void send(SetupEditPacket.Action a, Feature f, String arg) {
        ServerUtilsNetwork.CHANNEL.sendToServer(new SetupEditPacket(a, f, arg));
    }

    // ------------------------------------------------------------------ moving

    private void go(int next) {
        sendPlayersText();
        page = Math.max(0, Math.min(PAGES - 1, next));
        offSureUntil = 0;
        host.guidePageChanged();
    }

    private void next() {
        Feature f = feature();
        if (f == Feature.WELCOME && !name.strip().isEmpty() && !name.strip().equals(view.named().isEmpty() ? view.here() : view.named())) {
            send(SetupEditPacket.Action.NAME, f, name.strip());
        }
        send(SetupEditPacket.Action.NEXT, f, "");
        go(page + 1);
    }

    private void later() {
        send(SetupEditPacket.Action.LATER, feature(), "");
        go(page + 1);
    }

    private void off() {
        Feature f = feature();
        if (f == Feature.LOG) {
            long now = System.currentTimeMillis();
            if (now >= offSureUntil) {
                offSureUntil = now + SURE_MS; // the action log: a second click (D151)
                return;
            }
        }
        send(SetupEditPacket.Action.OFF, f, "");
        go(page + 1);
    }

    private void on() {
        send(SetupEditPacket.Action.ON, feature(), "");
    }

    /** The panel closes with the guide unfinished: this page and the rest not reached are left for later (D158). */
    void closing() {
        Feature f = feature();
        sendPlayersText();
        if (!finished && f != null) {
            send(SetupEditPacket.Action.CLOSE, f, "");
        }
    }

    /** Leaving the Player counter page: the title and the welcome words, when they changed. */
    private void sendPlayersText() {
        if (feature() != Feature.PLAYERS) {
            return;
        }
        if (!title.strip().equals(sentTitle)) {
            sentTitle = title.strip();
            send(SetupEditPacket.Action.PLAYERS_TITLE, Feature.PLAYERS, sentTitle);
        }
        if (!welcome.strip().equals(sentWelcome)) {
            sentWelcome = welcome.strip();
            send(SetupEditPacket.Action.PLAYERS_WELCOME, Feature.PLAYERS, sentWelcome);
        }
    }

    /** SET or ADD with the number typed (commas allowed); nothing when it is not a number. */
    private void sendNumber(SetupEditPacket.Action a) {
        String n = number.strip().replace(",", "");
        if (n.matches("-?\\d{1,9}")) {
            send(a, Feature.PLAYERS, n);
            number = "";
            if (numberBox != null) {
                numberBox.setValue("");
            }
        }
    }

    private void reset() {
        long now = System.currentTimeMillis();
        if (now >= resetSureUntil) {
            resetSureUntil = now + SURE_MS; // a second click (SURE?)
            return;
        }
        resetSureUntil = 0;
        send(SetupEditPacket.Action.PLAYERS_RESET, Feature.PLAYERS, "");
    }

    // ------------------------------------------------------------------ widgets

    void addWidgets(int x, int y) {
        nameBox = null;
        if (feature() == Feature.WELCOME) {
            nameBox = new EditBox(host.font(), x + 14, y + 74, 120, 12, Component.literal(Tr.t("admin.setup.name")));
            nameBox.setMaxLength(32);
            nameBox.setValue(name);
            nameBox.setResponder(s -> name = s);
            host.add(nameBox);
        }
        numberBox = titleBox = welcomeBox = null;
        if (feature() == Feature.PLAYERS) {
            int bx = x + 8, by = y + 22, rx = bx + RIGHT, rw = AdminScreen.W - 16 - RIGHT - 6;
            numberBox = new EditBox(host.font(), bx + 7, by + 58, 58, 12, Component.literal(Tr.t("admin.setup.players.set_number")));
            numberBox.setMaxLength(12);
            numberBox.setValue(number);
            numberBox.setResponder(s -> number = s);
            host.add(numberBox);
            titleBox = new EditBox(host.font(), rx, by + 15, rw, 12, Component.literal(Tr.t("admin.setup.players.title")));
            titleBox.setMaxLength(com.mrgregles.bsp_server_utilities.players.PlayersSettings.MAX_TITLE);
            titleBox.setValue(title);
            titleBox.setResponder(s -> title = s);
            host.add(titleBox);
            welcomeBox = new EditBox(host.font(), rx, by + 45, rw, 12, Component.literal(Tr.t("admin.setup.players.welcome")));
            welcomeBox.setMaxLength(com.mrgregles.bsp_server_utilities.players.PlayersSettings.MAX_WELCOME);
            welcomeBox.setValue(welcome);
            welcomeBox.setResponder(s -> welcome = s);
            host.add(welcomeBox);
        }
    }

    void tick() {
        for (EditBox b : new EditBox[]{nameBox, numberBox, titleBox, welcomeBox}) {
            if (b != null) {
                b.tick();
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    /** The progress bar and the strip above the body, and the footer keys below it. */
    void renderFrame(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int top = y - OFFSET;
        g.fill(x + 8, top + 20, x + AdminScreen.W - 8, top + 22, Ui.DIM);
        g.fill(x + 8, top + 20, x + 8 + (AdminScreen.W - 16) * (page + 1) / PAGES, top + 22, Ui.VIOLET);
        int sy = top + 24;
        g.fill(x + 8, sy, x + AdminScreen.W - 8, sy + 11, 0xFF1A1530);
        Ui.frame(g, x + 8, sy, AdminScreen.W - 16, 11, 0xFF3D2F5C);
        String step = Tr.t("admin.setup.step", page + 1, PAGES);
        Ui.text(g, font, Component.literal(step).withStyle(ChatFormatting.BOLD), x + 12, sy + 3, Ui.VIOLET, TINY);
        int sw = Ui.width(font, step, TINY) + 8;
        Ui.text(g, font, Ui.fit(font, about(feature()), AdminScreen.W - 24 - sw, TINY), x + 12 + sw, sy + 3, Ui.TEXT, TINY);
        // the footer
        int fy = y + AdminScreen.H - 8 - 14;
        g.fill(x + 1, fy - 2, x + AdminScreen.W - 1, y + AdminScreen.H - 1, Ui.PANEL);
        Feature f = feature();
        if (page > 0) {
            key(g, x + 8, fy, Tr.t("admin.setup.back"), Style.GHOST, mx, my, hits, () -> go(page - 1));
        }
        int kx = x + AdminScreen.W - 8;
        if (f == null) {
            kx -= key(g, kx, fy, Tr.t("admin.setup.done"), Style.VIOLET, mx, my, hits, () -> {
                finished = true;
                host.onClose();
            }, true);
            return;
        }
        kx -= key(g, kx, fy, Tr.t("admin.setup.next"), Style.VIOLET, mx, my, hits, this::next, true) + 4;
        if (status(f) != SetupState.Status.OFF) {
            kx -= key(g, kx, fy, Tr.t("admin.setup.later"), Style.PLAIN, mx, my, hits, this::later, true) + 4;
        }
        if (f.canTurnOff()) {
            if (status(f) == SetupState.Status.OFF) {
                key(g, kx, fy, Tr.t("admin.setup.on"), Style.PLAIN, mx, my, hits, this::on, true);
            } else {
                boolean sure = System.currentTimeMillis() < offSureUntil;
                key(g, kx, fy, Tr.t(sure ? "rules.sure" : "admin.setup.off"), sure ? Style.SURE : Style.WARN, mx, my, hits, this::off, true);
                if (sure) {
                    g.fill(x + 8, fy - 14, x + AdminScreen.W - 8, fy - 3, 0xFF3A2020);
                    Ui.text(g, font, Ui.fit(font, Tr.t("admin.setup.log_warning"), AdminScreen.W - 24, TINY), x + 12, fy - 11, Ui.BAD, TINY);
                }
            }
        }
        if (status(f) == SetupState.Status.OFF) {
            String o = Tr.t("admin.setup.is_off");
            Ui.text(g, font, o, x + 8 + (page > 0 ? 40 : 0), fy + 5, Ui.MUTED, TINY);
        }
    }

    private String about(@Nullable Feature f) {
        if (f == null) {
            return Tr.t("admin.setup.about.finish");
        }
        return switch (f) {
            case WELCOME -> Tr.t("admin.setup.about.welcome");
            case STAFF -> Tr.t("admin.setup.about.staff");
            case RULES -> Tr.t("admin.setup.about.rules");
            case LOG -> Tr.t("admin.setup.about.log");
            case AFK -> Tr.t("admin.setup.about.afk");
            case CHUNKS -> Tr.t("admin.setup.about.chunks");
            case DESPAWN -> Tr.t("admin.setup.about.despawn");
            case CHECK -> Tr.t("admin.setup.about.check");
            case PANELS -> Tr.t("admin.setup.about.panels");
            case PLAYERS -> Tr.t("admin.setup.about.players");
        };
    }

    static String featureName(Feature f) {
        return switch (f) {
            case WELCOME -> Tr.t("admin.setup.feature.welcome");
            case STAFF -> Tr.t("admin.setup.feature.staff");
            case RULES -> Tr.t("admin.setup.feature.rules");
            case LOG -> Tr.t("admin.setup.feature.log");
            case AFK -> Tr.t("admin.setup.feature.afk");
            case CHUNKS -> Tr.t("admin.setup.feature.chunks");
            case DESPAWN -> Tr.t("admin.setup.feature.despawn");
            case CHECK -> Tr.t("admin.setup.feature.check");
            case PANELS -> Tr.t("admin.setup.feature.panels");
            case PLAYERS -> Tr.t("admin.setup.feature.players");
        };
    }

    /** Welcome, Display panels or Finish. */
    void renderCustom(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int bx = x + 8, by = y + 22, bw = AdminScreen.W - 16, bh = AdminScreen.H - 22 - 26;
        Ui.inset(g, bx, by, bw, bh);
        Feature f = feature();
        if (f == Feature.WELCOME) {
            int ty = para(g, Tr.t("admin.setup.welcome.intro"), bx + 6, by + 5, bw - 12, Ui.TEXT);
            Ui.text(g, font, Tr.t("admin.setup.name").toUpperCase(Locale.ROOT), bx + 6, by + 44, Ui.MUTED, TINY);
            Ui.text(g, font, Ui.fit(font, Tr.t("admin.setup.name_hint"), bw - 140, TINY), bx + 132, by + 56, Ui.MUTED, TINY);
            Ui.text(g, font, Tr.t("admin.setup.storage").toUpperCase(Locale.ROOT), bx + 6, by + 72, Ui.MUTED, TINY);
            String st = view.shared() ? Tr.t("admin.setup.shared", view.storage()) : Tr.t("admin.setup.local");
            int sy = by + 80;
            for (String line : Ui.wrap(font, st, bw - 20, TINY)) {
                if (sy > by + 100) {
                    break;
                }
                Ui.text(g, font, line, bx + 14, sy, Ui.TEXT, TINY);
                sy += 7;
            }
            g.fill(bx + 7, by + 81, bx + 11, by + 85, view.shared() ? GREEN : Ui.GOLD);
            String servers = view.servers().isEmpty() ? Tr.t("admin.setup.no_servers") : Tr.t("admin.setup.servers", String.join(", ", view.servers()));
            Ui.text(g, font, Ui.fit(font, servers, bw - 12, TINY), bx + 6, by + 108, Ui.MUTED, TINY);
            Ui.text(g, font, Ui.fit(font, Tr.t("admin.setup.storage_hint"), bw - 12, TINY), bx + 6, by + 118, Ui.MUTED, TINY);
            return;
        }
        if (f == Feature.PANELS) {
            para(g, Tr.t("admin.setup.panels.text"), bx + 6, by + 5, bw - 12, Ui.TEXT);
            Ui.text(g, font, Ui.fit(font, Tr.t("admin.setup.panels.give"), bw - 12, TINY), bx + 6, by + 60, Ui.MUTED, TINY);
            return;
        }
        if (f == Feature.PLAYERS) {
            renderPlayers(g, bx, by, bw, bh, mx, my, hits);
            return;
        }
        // Finish: every page and where it stands
        int ry = by + 4;
        int listW = bw - 150;
        for (Feature each : Feature.values()) {
            String label = featureName(each);
            Ui.text(g, font, Ui.fit(font, label, listW - 60, SMALL), bx + 6, ry + 2, Ui.TEXT, SMALL);
            SetupState.Status s = status(each);
            String pill;
            int bg, fg;
            switch (s) {
                case DONE -> {
                    pill = Tr.t("admin.setup.status.done");
                    bg = 0xFF1A2A16;
                    fg = GREEN;
                }
                case OFF -> {
                    pill = Tr.t("admin.setup.status.off");
                    bg = Ui.DIM;
                    fg = Ui.MUTED;
                }
                case LATER -> {
                    pill = Tr.t("admin.setup.status.later");
                    bg = 0xFF2A2414;
                    fg = Ui.GOLD;
                }
                default -> {
                    pill = Tr.t("admin.setup.status.not_yet");
                    bg = 0xFF1B2230;
                    fg = Ui.VIOLET;
                }
            }
            int pw = Ui.width(font, pill, TINY) + 8, px = bx + listW - pw;
            g.fill(px, ry + 1, px + pw, ry + 10, bg);
            Ui.text(g, font, pill, px + 4, ry + 3, fg, TINY);
            ry += 13;
            if (each.ordinal() < Feature.values().length - 1) {
                g.fill(bx + 3, ry - 2, bx + listW + 2, ry - 1, Ui.ROW_LINE);
            }
        }
        long left = view.statuses().values().stream().filter(s -> s == SetupState.Status.LATER || s == SetupState.Status.NOT_YET).count();
        String note = left == 0 ? Tr.t("admin.setup.finish.all") : Tr.t("admin.setup.finish.left", left);
        int ny = para(g, note, bx + listW + 14, by + 8, bw - listW - 20, Ui.TEXT);
        para(g, Tr.t("admin.setup.finish.later"), bx + listW + 14, ny + 6, bw - listW - 20, Ui.MUTED);
    }

    /** Where the Player counter page's right column starts, from the body's left edge. */
    private static final int RIGHT = 150;

    /** The Player counter page: the number and its keys at the left, the title and the welcome line at the right. */
    private void renderPlayers(GuiGraphics g, int bx, int by, int bw, int bh, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        SetupViewPacket.Players p = view.players();
        int lx = bx + 7, lw = RIGHT - 14;
        Ui.text(g, font, Tr.t("admin.setup.players.shown").toUpperCase(Locale.ROOT), lx, by + 6, Ui.MUTED, TINY);
        String shown = com.mrgregles.bsp_server_utilities.players.PlayerCounter.format(p.shown());
        float big = Math.min(2f, lw / (float) Math.max(1, Ui.width(font, shown, 1f)));
        Ui.text(g, font, Component.literal(shown).withStyle(ChatFormatting.BOLD), lx, by + 15, Ui.GOLD, big);
        Ui.text(g, font, Ui.fit(font, Tr.t("admin.setup.players.real", com.mrgregles.bsp_server_utilities.players.PlayerCounter.format(p.real())), lw, TINY), lx, by + 35, Ui.MUTED, TINY);
        Ui.text(g, font, Tr.t("admin.setup.players.set_number").toUpperCase(Locale.ROOT), lx, by + 49, Ui.MUTED, TINY);
        int kx = lx + 62;
        kx += key(g, kx, by + 57, Tr.t("admin.setup.players.set"), Style.VIOLET, mx, my, hits, () -> sendNumber(SetupEditPacket.Action.PLAYERS_SET)) + 3;
        key(g, kx, by + 57, Tr.t("admin.setup.players.add"), Style.PLAIN, mx, my, hits, () -> sendNumber(SetupEditPacket.Action.PLAYERS_ADD));
        boolean sure = System.currentTimeMillis() < resetSureUntil;
        int rw = key(g, lx, by + 77, Tr.t(sure ? "rules.sure" : "admin.setup.players.reset"), sure ? Style.SURE : Style.WARN, mx, my, hits, this::reset);
        key(g, lx + rw + 3, by + 77, Tr.t("admin.setup.players.real_key"), Style.PLAIN, mx, my, hits, () -> send(SetupEditPacket.Action.PLAYERS_REAL, Feature.PLAYERS, ""));
        para(g, Tr.t("admin.setup.players.logged"), lx, by + 97, lw, Ui.MUTED);
        g.fill(bx + RIGHT - 4, by + 4, bx + RIGHT - 3, by + bh - 4, Ui.ROW_LINE);
        // the title and the welcome line
        int rx = bx + RIGHT, rwid = bw - RIGHT - 6;
        Ui.text(g, font, Tr.t("admin.setup.players.title").toUpperCase(Locale.ROOT), rx, by + 6, Ui.MUTED, TINY);
        Ui.text(g, font, Tr.t("admin.setup.players.welcome").toUpperCase(Locale.ROOT), rx, by + 36, Ui.MUTED, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("admin.setup.players.count", welcome.length(), com.mrgregles.bsp_server_utilities.players.PlayersSettings.MAX_WELCOME), rwid, TINY), rx, by + 61, Ui.MUTED, TINY);
        String me = net.minecraft.client.Minecraft.getInstance().getUser().getName();
        String words = welcome.strip().isEmpty() ? com.mrgregles.bsp_server_utilities.players.PlayersSettings.DEFAULT_WELCOME : welcome.strip();
        String line = com.mrgregles.bsp_server_utilities.players.PlayerCounter.welcomeText(words, me, p.shown() + 1);
        g.fill(rx, by + 71, rx + rwid, by + 106, Ui.SLOT);
        int ly = by + 75;
        for (String l : Ui.wrap(font, line, rwid - 8, SMALL)) {
            if (ly > by + 98) {
                break;
            }
            Ui.text(g, font, l, rx + 4, ly, Ui.GOLD, SMALL);
            ly += 8;
        }
        para(g, Tr.t("admin.setup.players.everywhere"), rx, by + 111, rwid, Ui.MUTED);
    }

    private int para(GuiGraphics g, String text, int x, int y, int w, int colour) {
        Font font = host.font();
        for (String line : Ui.wrap(font, text, w, TINY)) {
            Ui.text(g, font, line, x, y, colour, TINY);
            y += 7;
        }
        return y;
    }

    private enum Style { PLAIN, WARN, SURE, GHOST, VIOLET }

    private int key(GuiGraphics g, int x, int y, String label, Style style, int mx, int my, List<Ui.Hit> hits, Runnable action) {
        return key(g, x, y, label, style, mx, my, hits, action, false);
    }

    /** Draws a footer key with its left edge at x (or its right edge, when {@code fromRight}); returns its width. */
    private int key(GuiGraphics g, int x, int y, String label, Style style, int mx, int my, List<Ui.Hit> hits, Runnable action, boolean fromRight) {
        Font font = host.font();
        int w = Math.max(36, Ui.width(font, label, TINY) + 14), h = 14;
        int kx = fromRight ? x - w : x;
        boolean hover = mx >= kx && mx < kx + w && my >= y && my < y + h;
        int text;
        switch (style) {
            case WARN -> {
                g.fill(kx, y, kx + w, y + h, hover ? 0xFF4A3030 : 0xFF3A2A2A);
                Ui.frame(g, kx, y, w, h, 0xFFD63B2F);
                text = 0xFFFF8F86;
            }
            case SURE -> {
                g.fill(kx, y, kx + w, y + h, 0xFFD63B2F);
                text = 0xFFFFFFFF;
            }
            case GHOST -> {
                Ui.frame(g, kx, y, w, h, Ui.DIM);
                text = Ui.MUTED;
            }
            case VIOLET -> {
                g.fill(kx, y, kx + w, y + h, hover ? 0xFFC8A6FF : Ui.VIOLET);
                text = Ui.INK;
            }
            default -> {
                g.fill(kx, y, kx + w, y + h, hover ? 0xFF3A404C : Ui.DIM);
                text = Ui.TEXT;
            }
        }
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), kx + (w - Ui.width(font, label, TINY)) / 2, y + 5, text, TINY);
        hits.add(new Ui.Hit(kx, y, w, h, action));
        return w;
    }
}
