package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.AdminLogEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminLogPacket;
import com.mrgregles.bsp_server_utilities.network.LogViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The LOG tab of the admin panel (owner decisions D25, D29, D30, D56), as on {@code tools/preview/staff_log_refined.html}:
 * on the left WHAT IS LOGGED, a search box and a switch per command (private messages listed as never logged, with no
 * switch); on the right the newest entries in full text (how long ago, who in their role's colour, what, the server;
 * hover a cut line for all of it). The footer has the server and kind filters. A switch goes to the server at once and
 * its change shows as an entry; the entries follow the log while the tab is open.
 */
final class LogTab {
    private static final int LIST_X = 8, LIST_Y = 22, LIST_W = 110, LIST_H = 158, PANE_X = 126, PANE_W = 210, ROW = 10;
    private static final int SW_TOP = 29, KEY_W = 66;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    /** The kinds in {@code LogEntry.KINDS} order, with their labels. */
    private static final String[] KIND_KEYS = {"panel.log.kind.cmd", "panel.log.kind.item", "panel.log.kind.mode", "panel.log.kind.give", "panel.log.kind.switch"};

    private enum Menu { NONE, SERVERS, KINDS }

    private final AdminScreen host;
    @Nullable
    private AdminLogPacket data;
    private String server = "", kind = "", query = "";
    private int switchScroll, entryScroll;
    private long seenLog;
    private Menu menu = Menu.NONE;
    @Nullable
    private EditBox searchBox;

    LogTab(AdminScreen host) {
        this.host = host;
    }

    void open() {
        seenLog = ClientLog.receivedAt();
        send(AdminLogEditPacket.refresh(server, kind));
    }

    private static void send(AdminLogEditPacket p) {
        ServerUtilsNetwork.CHANNEL.sendToServer(p);
    }

    void accept(AdminLogPacket p) {
        data = p;
    }

    /** Fresh entries whenever the log changes (the players' copy arrives at most once a second). */
    void tick() {
        if (searchBox != null) {
            searchBox.tick();
        }
        if (data != null && ClientLog.receivedAt() != seenLog) {
            seenLog = ClientLog.receivedAt();
            send(AdminLogEditPacket.refresh(server, kind));
        }
    }

    void addWidgets(int x, int y) {
        searchBox = new EditBox(host.font(), x + LIST_X + 4, y + LIST_Y + 13, LIST_W - 8, 12, Component.literal(Tr.t("log.find")));
        searchBox.setMaxLength(32);
        searchBox.setHint(Component.literal(Tr.t("log.find")).withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(query);
        searchBox.setResponder(s -> {
            query = s.strip().toLowerCase(Locale.ROOT);
            switchScroll = 0;
        });
        host.add(searchBox);
    }

    private List<AdminLogPacket.Switch> shownSwitches() {
        List<AdminLogPacket.Switch> out = new ArrayList<>();
        if (data != null) {
            String q = query.startsWith("/") ? query.substring(1) : query;
            for (AdminLogPacket.Switch s : data.switches()) {
                if (q.isEmpty() || s.root().contains(q)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private void toggle(AdminLogPacket.Switch s) {
        if (data != null) {
            List<AdminLogPacket.Switch> next = new ArrayList<>(data.switches());
            next.replaceAll(o -> o.root().equals(s.root()) ? new AdminLogPacket.Switch(o.root(), !o.on(), false) : o);
            data = new AdminLogPacket(next, data.servers(), data.server(), data.kind(), data.entries());
        }
        send(new AdminLogEditPacket(server, kind, s.root(), !s.on()));
    }

    private void filter(String onServer, String ofKind) {
        server = onServer;
        kind = ofKind;
        entryScroll = 0;
        menu = Menu.NONE;
        send(AdminLogEditPacket.refresh(server, kind));
    }

    // ------------------------------------------------------------------ drawing

    @Nullable
    private String tip;

    /** The lists and the footer, under the search box. */
    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        drawSwitches(g, x, y, mx, my, hits);
        tip = drawEntries(g, x, y, mx, my);
        drawFooter(g, x, y, mx, my, hits);
    }

    /** An open filter's choices, or the full text of a cut entry, over everything. */
    void overlay(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        if (menu != Menu.NONE) {
            drawMenu(g, x, y, mx, my, hits);
        } else if (tip != null) {
            Font font = host.font();
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(tip), 220);
            g.renderTooltip(font, lines, mx, my);
        }
    }

    private void drawSwitches(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LIST_X, ly = y + LIST_Y;
        Ui.inset(g, lx, ly, LIST_W, LIST_H);
        Ui.text(g, font, Component.literal(Tr.t("log.what")).withStyle(ChatFormatting.BOLD), lx + 5, ly + 4, Ui.VIOLET, TINY);
        List<AdminLogPacket.Switch> shown = shownSwitches();
        int top = ly + SW_TOP, rows = (LIST_H - SW_TOP - 2) / ROW;
        if (data != null && shown.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("log.no_match"), LIST_W - 10, TINY), lx + 5, top + 3, Ui.MUTED, TINY);
        }
        switchScroll = Math.max(0, Math.min(switchScroll, Math.max(0, shown.size() - rows)));
        for (int i = 0; i < rows && switchScroll + i < shown.size(); i++) {
            AdminLogPacket.Switch s = shown.get(switchScroll + i);
            int ry = top + i * ROW;
            boolean hover = !s.privateMessage() && mx >= lx && mx < lx + LIST_W && my >= ry && my < ry + ROW;
            if (hover) {
                g.fill(lx + 1, ry, lx + LIST_W - 1, ry + ROW, 0x22B58CFF);
            }
            String right = s.privateMessage() ? Tr.t("log.never") : "";
            int rightW = s.privateMessage() ? Ui.width(font, right, TINY) : 16;
            Ui.text(g, font, Ui.fit(font, "/" + s.root(), LIST_W - 14 - rightW, SMALL), lx + 5, ry + 2, s.privateMessage() || !s.on() ? Ui.MUTED : Ui.TEXT, SMALL);
            if (s.privateMessage()) {
                Ui.text(g, font, right, lx + LIST_W - 5 - rightW, ry + 3, Ui.MUTED, TINY);
            } else {
                int tx = lx + LIST_W - 19;
                g.fill(tx, ry + 2, tx + 14, ry + 8, s.on() ? 0xFF3D2F5C : Ui.DIM);
                g.fill(s.on() ? tx + 8 : tx + 1, ry + 3, s.on() ? tx + 13 : tx + 6, ry + 7, s.on() ? Ui.VIOLET : Ui.MUTED);
                hits.add(new Ui.Hit(lx, ry, LIST_W, ROW, () -> toggle(s)));
            }
        }
    }

    /** Draws the entries; answers the full text of a cut line under the mouse, for the tooltip. */
    @Nullable
    private String drawEntries(GuiGraphics g, int x, int y, int mx, int my) {
        Font font = host.font();
        int px = x + PANE_X, py = y + LIST_Y;
        Ui.inset(g, px, py, PANE_W, LIST_H);
        List<LogViewPacket.Row> rows = data == null ? List.of() : data.entries();
        if (rows.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t(data == null ? "log.waiting" : "log.none"), PANE_W - 10, TINY), px + 5, py + 6, Ui.MUTED, TINY);
            return null;
        }
        int visible = (LIST_H - 4) / ROW;
        entryScroll = Math.max(0, Math.min(entryScroll, Math.max(0, rows.size() - visible)));
        int srvW = 0;
        for (LogViewPacket.Row r : rows) {
            srvW = Math.max(srvW, Ui.width(font, r.server(), TINY));
        }
        srvW = Math.min(srvW, 46) + 6;
        int agoX = px + 4, whoX = px + 28, whatX = px + 76, whatW = PANE_W - (whatX - px) - srvW - 8;
        String tip = null;
        for (int i = 0; i < visible && entryScroll + i < rows.size(); i++) {
            LogViewPacket.Row r = rows.get(entryScroll + i);
            int ry = py + 3 + i * ROW;
            Ui.text(g, font, StaffFace.ago(ClientLog.agoMs(r)), agoX, ry + 2, Ui.MUTED, TINY);
            Ui.text(g, font, Component.literal(Ui.fit(font, r.actor(), whatX - whoX - 4, SMALL)).withStyle(ChatFormatting.BOLD), whoX, ry + 1, 0xFF000000 | r.colour(), SMALL);
            String what = Ui.fit(font, r.text(), whatW, SMALL);
            Ui.text(g, font, what, whatX, ry + 1, Ui.TEXT, SMALL);
            int sx = px + PANE_W - 4 - srvW;
            g.fill(sx, ry, sx + srvW, ry + 8, 0xFF1B2230);
            Ui.text(g, font, Ui.fit(font, r.server(), srvW - 4, TINY), sx + 3, ry + 2, Ui.MUTED, TINY);
            if (!what.equals(r.text()) && mx >= px && mx < px + PANE_W && my >= ry && my < ry + ROW) {
                tip = r.text();
            }
        }
        return tip;
    }

    private void drawFooter(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int fy = y + AdminScreen.H - 8 - 14;
        int kindX = x + AdminScreen.W - 8 - KEY_W, serverX = kindX - 4 - KEY_W;
        String footer = Tr.t("log.footer", ClientLog.keepDays());
        Ui.text(g, font, Ui.fit(font, footer, serverX - x - 12, TINY), x + 8, fy + 5, Ui.MUTED, TINY);
        dropKey(g, serverX, fy, server.isEmpty() ? Tr.t("log.server_all") : server, menu == Menu.SERVERS, mx, my, hits,
                () -> menu = menu == Menu.SERVERS ? Menu.NONE : Menu.SERVERS);
        dropKey(g, kindX, fy, kind.isEmpty() ? Tr.t("log.kind_all") : Tr.t(kindKey(kind)), menu == Menu.KINDS, mx, my, hits,
                () -> menu = menu == Menu.KINDS ? Menu.NONE : Menu.KINDS);
    }

    private static String kindKey(String k) {
        int i = LogEntry.KINDS.indexOf(k);
        return i < 0 ? KIND_KEYS[0] : KIND_KEYS[i];
    }

    private void dropKey(GuiGraphics g, int kx, int fy, String label, boolean open, int mx, int my, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        if (open || mx >= kx && mx < kx + KEY_W && my >= fy && my < fy + 14) {
            g.fill(kx, fy, kx + KEY_W, fy + 14, 0x222A2F3A);
        }
        Ui.frame(g, kx, fy, KEY_W, 14, open ? Ui.VIOLET : Ui.DIM);
        String s = Ui.fit(font, label.toUpperCase(Locale.ROOT), KEY_W - 14, TINY);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), kx + 5, fy + 5, open ? Ui.VIOLET : Ui.MUTED, TINY);
        Ui.text(g, font, "▾", kx + KEY_W - 9, fy + 4, open ? Ui.VIOLET : Ui.MUTED, SMALL);
        hits.add(new Ui.Hit(kx, fy, KEY_W, 14, action));
    }

    /** The open filter's choices, upwards from its key; a click anywhere else closes it. */
    private void drawMenu(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        List<String> values = new ArrayList<>(), labels = new ArrayList<>();
        values.add("");
        if (menu == Menu.SERVERS) {
            labels.add(Tr.t("log.server_all"));
            List<String> servers = data == null ? List.of() : data.servers();
            for (String s : servers.subList(0, Math.min(12, servers.size()))) {
                values.add(s);
                labels.add(s);
            }
        } else {
            labels.add(Tr.t("log.kind_all"));
            for (int i = 0; i < LogEntry.KINDS.size(); i++) {
                values.add(LogEntry.KINDS.get(i));
                labels.add(Tr.t(KIND_KEYS[i]));
            }
        }
        int fy = y + AdminScreen.H - 8 - 14, kindX = x + AdminScreen.W - 8 - KEY_W;
        int mxLeft = menu == Menu.KINDS ? kindX : kindX - 4 - KEY_W, h = values.size() * 11 + 2, top = fy - 2 - h;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(mxLeft - 1, top - 1, mxLeft + KEY_W + 1, top + h + 1, Ui.VIOLET);
        g.fill(mxLeft, top, mxLeft + KEY_W, top + h, Ui.PANEL);
        List<Ui.Hit> options = new ArrayList<>();
        String current = menu == Menu.SERVERS ? server : kind;
        for (int i = 0; i < values.size(); i++) {
            int oy = top + 1 + i * 11;
            String v = values.get(i);
            boolean on = v.equals(current);
            if (on || mx >= mxLeft && mx < mxLeft + KEY_W && my >= oy && my < oy + 11) {
                g.fill(mxLeft + 1, oy, mxLeft + KEY_W - 1, oy + 11, on ? 0xFF1B2230 : 0x22B58CFF);
            }
            Ui.text(g, font, Ui.fit(font, labels.get(i), KEY_W - 8, TINY), mxLeft + 4, oy + 3, on ? Ui.VIOLET : Ui.TEXT, TINY);
            Menu m = menu;
            options.add(new Ui.Hit(mxLeft, oy, KEY_W, 11, () -> {
                if (m == Menu.SERVERS) {
                    filter(v, kind);
                } else {
                    filter(server, v);
                }
            }));
        }
        g.pose().popPose();
        hits.addAll(0, options);
        hits.add(options.size(), new Ui.Hit(x - 4000, y - 4000, 8000, 8000, () -> menu = Menu.NONE));
    }

    boolean scroll(double mx, double delta) {
        if (mx < host.width / 2.0 - AdminScreen.W / 2.0 + PANE_X) {
            switchScroll -= (int) Math.signum(delta) * 2;
        } else {
            entryScroll -= (int) Math.signum(delta) * 2;
        }
        return true;
    }
}
