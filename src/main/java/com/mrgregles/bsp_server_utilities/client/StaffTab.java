package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.access.StaffGroups;
import com.mrgregles.bsp_server_utilities.network.AdminStaffEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminStaffPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.StaffViewPacket;
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
 * The STAFF tab of the admin panel (owner decisions D56, D57, D63), as on {@code tools/preview/staff_log_refined.html}:
 * the staff groups on the left (+ ADD A LUCKPERMS GROUP, the operators switch), everyone with their role and where
 * they are on the right; a picked group shows its editor instead (the LuckPerms group, the label, the colour, the
 * order, REMOVE with SURE?). Every change goes to the server at once (a label or colour typed, a second after typing).
 */
final class StaffTab {
    /** The groups column keeps its width; the people list takes the rest of the page, the editor at most {@code EDIT_W} of it. */
    private static final int LIST_X = 8, LIST_Y = 22, LIST_W = 110, PANE_X = 126, EDIT_W = 210;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int[] SWATCHES = {0xFFD23A, 0xB58CFF, 0x19D3B0, 0xFF6B5C, 0x8FE04A, 0x55FFFF, 0xFF55FF, 0xE6EAF2};

    private final AdminScreen host;
    @Nullable
    private AdminStaffPacket data;
    private final List<Staff.Role> roles = new ArrayList<>();
    private boolean operators = true;
    private int selected = -1, scroll, groupScroll, dirtyTicks;
    /** Set by UP and DOWN so the moved group is scrolled into view once. */
    private boolean revealSelected;
    private long removeSureUntil;
    @Nullable
    private EditBox labelBox, hexBox;

    StaffTab(AdminScreen host) {
        this.host = host;
    }

    /** Both columns' height, down to the footer (158 on a 210-high page). */
    private int listH() {
        return host.pageH() - LIST_Y - 30;
    }

    /** The right column's width: all the page's width the groups leave (210 on a 344-wide page). */
    private int paneW() {
        return host.pageW() - PANE_X - 8;
    }

    /** The group editor's width: its old 210, less on a narrower page. */
    private int editW() {
        return Math.min(EDIT_W, paneW());
    }

    void open() {
        ServerUtilsNetwork.CHANNEL.sendToServer(new AdminStaffEditPacket(true, List.of(), true));
    }

    void accept(AdminStaffPacket p) {
        data = p;
        if (dirtyTicks <= 0) {
            roles.clear();
            roles.addAll(p.roles());
            operators = p.operators();
            if (selected >= roles.size()) {
                selected = -1;
            }
            host.rebuildTab();
        }
    }

    private void send() {
        dirtyTicks = 0;
        ServerUtilsNetwork.CHANNEL.sendToServer(new AdminStaffEditPacket(false, List.copyOf(roles), operators));
    }

    void tick() {
        if (dirtyTicks > 0 && --dirtyTicks == 0) {
            send();
        }
    }

    void flush() {
        if (dirtyTicks > 0) {
            send();
        }
    }

    void addWidgets(int x, int y) {
        labelBox = hexBox = null;
        if (selected < 0 || selected >= roles.size()) {
            return;
        }
        Staff.Role r = roles.get(selected);
        Font font = host.font();
        labelBox = new EditBox(font, x + PANE_X + 1, y + 76, editW() - 2, 12, Component.literal(Tr.t("staff.label")));
        labelBox.setMaxLength(StaffGroups.MAX_LABEL);
        labelBox.setValue(r.label());
        labelBox.setResponder(s -> edit(new Staff.Role(roles.get(selected).group(), s, roles.get(selected).colour())));
        host.add(labelBox);
        hexBox = new EditBox(font, x + PANE_X + 8 * 14 + 12, y + 99, 48, 11, Component.literal("#"));
        hexBox.setMaxLength(6);
        hexBox.setValue(String.format(Locale.ROOT, "%06X", r.colour()));
        hexBox.setResponder(s -> {
            if (s.matches("[0-9a-fA-F]{6}")) {
                edit(new Staff.Role(roles.get(selected).group(), roles.get(selected).label(), Integer.parseInt(s, 16)));
            }
        });
        host.add(hexBox);
    }

    private void edit(Staff.Role r) {
        if (selected >= 0 && selected < roles.size() && !roles.get(selected).equals(r)) {
            roles.set(selected, r);
            dirtyTicks = 20;
        }
    }

    private void pick(int i) {
        flush();
        selected = i;
        removeSureUntil = 0;
        host.rebuildTab();
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LIST_X, ly = y + LIST_Y, lh = listH();
        Ui.inset(g, lx, ly, LIST_W, lh);
        Ui.text(g, font, Component.literal(Tr.t("staff.groups")).withStyle(ChatFormatting.BOLD), lx + 5, ly + 4, Ui.VIOLET, TINY);
        // the groups and + Add stop above the operators switch, scrolled by the wheel on a short page (audit of SU-154)
        int entries = roles.size() + (roles.size() < StaffGroups.MAX_GROUPS ? 1 : 0), fit = Math.max(1, (lh - 14 - 16) / 12);
        if (revealSelected && selected >= 0) {
            groupScroll = Math.min(selected, Math.max(groupScroll, selected - fit + 1));
            revealSelected = false;
        }
        groupScroll = Math.max(0, Math.min(groupScroll, Math.max(0, entries - fit)));
        for (int i = groupScroll; i < roles.size() && i < groupScroll + fit; i++) {
            Staff.Role r = roles.get(i);
            int ry = ly + 14 + (i - groupScroll) * 12;
            if (i == selected) {
                g.fill(lx + 1, ry, lx + LIST_W - 1, ry + 12, 0xFF1B2230);
                g.fill(lx + 1, ry, lx + 3, ry + 12, Ui.VIOLET);
            } else if (mx >= lx && mx < lx + LIST_W && my >= ry && my < ry + 12) {
                g.fill(lx + 1, ry, lx + LIST_W - 1, ry + 12, 0x22B58CFF);
            }
            g.fill(lx + 6, ry + 3, lx + 12, ry + 9, 0xFF000000 | r.colour());
            Ui.text(g, font, Component.literal(Ui.fitBold(font, r.group(), 46, SMALL)).withStyle(ChatFormatting.BOLD), lx + 16, ry + 3, Ui.TEXT, SMALL);
            String label = Ui.fit(font, r.label(), 40, TINY);
            Ui.text(g, font, label, lx + LIST_W - 5 - Ui.width(font, label, TINY), ry + 4, Ui.MUTED, TINY);
            int index = i;
            hits.add(new Ui.Hit(lx, ry, LIST_W, 12, () -> pick(index == selected ? -1 : index)));
        }
        if (roles.size() < StaffGroups.MAX_GROUPS && roles.size() < groupScroll + fit) {
            int ay = ly + 14 + (roles.size() - groupScroll) * 12 + 2;
            Ui.text(g, font, Component.literal(Ui.fitBold(font, Tr.t("staff.add_group"), LIST_W - 10, SMALL)).withStyle(ChatFormatting.BOLD), lx + 5, ay + 3, Ui.VIOLET, SMALL);
            hits.add(new Ui.Hit(lx, ay, LIST_W, 12, this::addGroup));
        }
        int oy = ly + lh - 14;
        g.fill(lx + 5, oy + 3, lx + 19, oy + 9, operators ? 0xFF3D2F5C : Ui.DIM);
        g.fill(operators ? lx + 13 : lx + 6, oy + 4, operators ? lx + 18 : lx + 11, oy + 8, operators ? Ui.VIOLET : Ui.MUTED);
        Ui.text(g, font, Ui.fit(font, Tr.t("staff.operators"), LIST_W - 28, TINY), lx + 23, oy + 4, Ui.TEXT, TINY);
        hits.add(new Ui.Hit(lx, oy, LIST_W, 12, () -> {
            operators = !operators;
            send();
        }));

        if (selected >= 0 && selected < roles.size()) {
            drawEditor(g, x, y, mx, my, hits);
        } else {
            drawPeople(g, x, y, mx, my);
        }
        // footer
        int fy = y + host.pageH() - 8 - 14;
        List<StaffViewPacket.Row> rows = data == null ? List.of() : data.roster().rows();
        long on = rows.stream().filter(r -> r.state() == StaffDirectory.State.ONLINE).count(), away = rows.stream().filter(r -> r.state() == StaffDirectory.State.AWAY).count();
        String key = Tr.t(selected >= 0 ? "staff.people" : "staff.refresh");
        int kw = Ui.widthBold(font, key, TINY) + 16, kx = x + host.pageW() - 8 - kw;
        Ui.text(g, font, Ui.fit(font, Tr.t("staff.summary", rows.size(), on, away), kx - 4 - (x + 8), TINY), x + 8, fy + 5, Ui.MUTED, TINY);
        Ui.frame(g, kx, fy, kw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(key).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, kw, 14, selected >= 0 ? () -> pick(-1) : this::open));
    }

    private void drawPeople(GuiGraphics g, int x, int y, int mx, int my) {
        Font font = host.font();
        int px = x + PANE_X, py = y + LIST_Y, pw = paneW(), lh = listH();
        Ui.inset(g, px, py, pw, lh);
        List<StaffViewPacket.Row> rows = data == null ? List.of() : data.roster().rows();
        if (rows.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t(data == null ? "staff.waiting" : "staff.no_people"), pw - 10, TINY), px + 5, py + 6, Ui.MUTED, TINY);
            return;
        }
        int visible = (lh - 4) / 10;
        // the width beyond the old 210 goes half to the name and half to where they are; the role keeps its 54
        int extra = pw - EDIT_W, nameExtra = extra / 2, labelX = px + 80 + nameExtra, whereX = px + 138 + nameExtra;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        StaffViewPacket.Row hovered = null;
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            StaffViewPacket.Row r = rows.get(scroll + i);
            int ry = py + 3 + i * 10;
            if (mx >= px && mx < px + pw && my >= ry && my < ry + 10) {
                hovered = r;
            }
            int dot = switch (r.state()) {
                case ONLINE -> 0xFF8FE04A;
                case AWAY -> 0xFFF2B544;
                case OFFLINE -> 0xFF3A3F4A;
            };
            g.fill(px + 5, ry + 2, px + 9, ry + 6, dot);
            Ui.text(g, font, Component.literal(Ui.fitBold(font, r.name(), 64 + nameExtra, SMALL)).withStyle(ChatFormatting.BOLD), px + 13, ry + 1, 0xFF000000 | r.colour(), SMALL);
            Ui.text(g, font, Ui.fit(font, r.label(), 54, TINY), labelX, ry + 2, Ui.MUTED, TINY);
            String where = switch (r.state()) {
                case ONLINE -> r.server();
                case AWAY -> Tr.t("staff.away");
                case OFFLINE -> ClientStaff.agoMs(r) > 0 ? Tr.t("staff.seen", StaffFace.ago(ClientStaff.agoMs(r))) : "";
            };
            Ui.text(g, font, Ui.fit(font, where, 70 + extra - nameExtra, TINY), whereX, ry + 2, r.state() == StaffDirectory.State.AWAY ? 0xFFF2B544 : Ui.MUTED, TINY);
        }
        if (hovered != null) {
            ClientNotes.hover(g, font, null, hovered.name(), mx, my); // the staff web panel's notes, by name (D321)
        }
    }

    private void drawEditor(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        Staff.Role r = roles.get(selected);
        int px = x + PANE_X, py = y + LIST_Y, ew = editW();
        Ui.text(g, font, Ui.fit(font, Tr.t("staff.lp_group").toUpperCase(Locale.ROOT), ew, TINY), px, py, Ui.MUTED, TINY);
        int cx = px, cy = py + 8;
        List<String> lp = data == null ? List.of() : data.luckPermsGroups();
        if (lp.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("staff.no_luckperms"), ew, TINY), px, cy + 3, Ui.MUTED, TINY);
        }
        for (String name : lp) {
            int w = Math.min(70, Ui.width(font, name, TINY) + 10);
            if (cx + w > px + ew) {
                cx = px;
                cy += 13;
            }
            if (cy > py + 34) {
                break;
            }
            boolean on = name.equalsIgnoreCase(r.group());
            boolean taken = !on && roles.stream().anyMatch(o -> o.group().equalsIgnoreCase(name));
            g.fill(cx, cy, cx + w, cy + 11, Ui.SLOT);
            Ui.frame(g, cx, cy, w, 11, on ? Ui.VIOLET : Ui.DIM);
            Ui.text(g, font, Ui.fit(font, name, w - 10, TINY), cx + 5, cy + 3, on ? Ui.VIOLET : taken ? 0xFF5A6170 : Ui.TEXT, TINY);
            if (!taken && !on) {
                hits.add(new Ui.Hit(cx, cy, w, 11, () -> {
                    roles.set(selected, new Staff.Role(name.toLowerCase(Locale.ROOT), roles.get(selected).label(), roles.get(selected).colour()));
                    send();
                }));
            }
            cx += w + 3;
        }
        Ui.text(g, font, Ui.fit(font, Tr.t("staff.label").toUpperCase(Locale.ROOT), ew, TINY), px, y + 68, Ui.MUTED, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("staff.colour").toUpperCase(Locale.ROOT), ew, TINY), px, y + 92, Ui.MUTED, TINY);
        for (int i = 0; i < SWATCHES.length; i++) {
            int sx = px + i * 14, sy = y + 99;
            int c = SWATCHES[i];
            g.fill(sx, sy, sx + 11, sy + 11, 0xFF000000 | c);
            Ui.frame(g, sx - 1, sy - 1, 13, 13, r.colour() == c ? Ui.VIOLET : Ui.DIM);
            hits.add(new Ui.Hit(sx - 1, sy - 1, 13, 13, () -> {
                roles.set(selected, new Staff.Role(roles.get(selected).group(), roles.get(selected).label(), c));
                send();
                host.rebuildTab();
            }));
        }
        Ui.text(g, font, "#", px + 8 * 14 + 4, y + 101, Ui.MUTED, SMALL);
        int ky = y + 118;
        Ui.text(g, font, Ui.fit(font, Tr.t("staff.order").toUpperCase(Locale.ROOT), ew, TINY), px, ky - 7, Ui.MUTED, TINY);
        key(g, px, ky + 2, 26, Tr.t("rules.up"), selected > 0, hits, () -> move(-1));
        key(g, px + 30, ky + 2, 34, Tr.t("rules.down"), selected < roles.size() - 1, hits, () -> move(1));
        boolean sure = System.currentTimeMillis() < removeSureUntil;
        int rw = 50, rx = px + ew - rw;
        g.fill(rx, ky + 2, rx + rw, ky + 15, sure ? 0xFFD63B2F : 0xFF3A2A2A);
        Ui.frame(g, rx, ky + 2, rw, 13, 0xFFD63B2F);
        String rl = Tr.t(sure ? "rules.sure" : "staff.remove");
        Ui.text(g, font, Component.literal(Ui.fitBold(font, rl, rw - 4, TINY)).withStyle(ChatFormatting.BOLD), rx + (rw - Math.min(rw - 4, Ui.widthBold(font, rl, TINY))) / 2, ky + 6, sure ? 0xFFFFFFFF : 0xFFFF8F86, TINY);
        hits.add(new Ui.Hit(rx, ky + 2, rw, 13, this::remove));
        List<String> members = new ArrayList<>();
        if (data != null) {
            for (StaffViewPacket.Row row : data.roster().rows()) {
                if (row.group().equalsIgnoreCase(r.group())) {
                    members.add(row.name());
                }
            }
        }
        Ui.text(g, font, Ui.fit(font, Tr.t("staff.members", members.size(), String.join(", ", members)), paneW(), TINY), px, ky + 22, Ui.MUTED, TINY);
    }

    private void key(GuiGraphics g, int x, int y, int w, String label, boolean enabled, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        g.fill(x, y, x + w, y + 13, Ui.DIM);
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + (w - Ui.widthBold(font, label, TINY)) / 2, y + 4, enabled ? Ui.TEXT : 0xFF5A6170, TINY);
        if (enabled) {
            hits.add(new Ui.Hit(x, y, w, 13, action));
        }
    }

    // ------------------------------------------------------------------ actions

    private void addGroup() {
        String group = "staff";
        if (data != null) {
            for (String g : data.luckPermsGroups()) {
                if (roles.stream().noneMatch(r -> r.group().equalsIgnoreCase(g)) && !g.equalsIgnoreCase("default")) {
                    group = g.toLowerCase(Locale.ROOT);
                    break;
                }
            }
        }
        String label = Character.toUpperCase(group.charAt(0)) + group.substring(1);
        roles.add(new Staff.Role(group, label, Staff.DEFAULT_COLOUR));
        selected = roles.size() - 1;
        send();
        host.rebuildTab();
    }

    private void move(int delta) {
        int to = selected + delta;
        if (to < 0 || to >= roles.size()) {
            return;
        }
        roles.add(to, roles.remove(selected));
        selected = to;
        revealSelected = true;
        send();
        host.rebuildTab();
    }

    private void remove() {
        long now = System.currentTimeMillis();
        if (now >= removeSureUntil) {
            removeSureUntil = now + 3000;
            return;
        }
        removeSureUntil = 0;
        roles.remove(selected);
        selected = -1;
        send();
        host.rebuildTab();
    }

    /** The wheel over the groups column scrolls the groups, elsewhere the people. */
    boolean scroll(double mx, double delta) {
        if (mx < host.left() + LIST_X + LIST_W) {
            groupScroll -= (int) Math.signum(delta);
            return true;
        }
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
