package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.afk.AfkSettings;
import com.mrgregles.bsp_server_utilities.network.AdminAfkEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminAfkPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The AFK tab of the admin panel (owner decisions D76, D79 to D85, D221, D224), as on {@code tools/preview/afk_refined.html}
 * and look A of {@code tools/preview/afk_check_refined.html}: on the left AFK DETECTION (the switch, AFK AFTER with −
 * and +), KICKING (the check-then-kick switch, CHECK AFTER, the kick message) and MACROS (the repeated-input switch and
 * what counts); on the right everyone AFK now on any server with how long and where, "macro?" (hovered, what was seen),
 * the time left of a check under way, a red countdown to a check, and staff away by {@code /su away}. A switch or a step
 * goes to the server at once, the message a second after typing stops; the list is asked for again every few seconds
 * while the tab is open. The settings keep their 150 wide box at their content's height (pulled together a little on the
 * shortest pages); the list takes the rest of the page, more rows as it gets taller and the extra width to the names
 * (D756, D757).
 */
final class AfkTab {
    /** LEFT_H: the settings' content height; RIGHT_W: the list's width on the old 344 wide page, the names' base. */
    private static final int LEFT_X = 8, TOP = 22, LEFT_W = 150, LEFT_H = 158, RIGHT_X = 166, RIGHT_W = 170, ROW = 10;
    /** SQUEEZE_REF: the last settings line pulled up in full on a short page (the macros text). */
    private static final int REFRESH_TICKS = 100, TYPE_TICKS = 20, MESSAGE_Y = 94, SQUEEZE_REF = 134;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int AMBER = 0xFFF2B544, RED = 0xFFFF6B5C, PILL = 0xFF2A2414;

    private final AdminScreen host;
    @Nullable
    private AdminAfkPacket data;
    private AfkSettings settings = AfkSettings.DEFAULTS;
    private int scroll, refreshIn, typeTicks;
    private long receivedAt;
    @Nullable
    private EditBox messageBox;

    AfkTab(AdminScreen host) {
        this.host = host;
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        ServerUtilsNetwork.CHANNEL.sendToServer(AdminAfkEditPacket.ask());
    }

    void accept(AdminAfkPacket p) {
        data = p;
        receivedAt = System.currentTimeMillis();
        if (typeTicks <= 0) {
            settings = p.settings();
            if (messageBox != null && !messageBox.isFocused() && !messageBox.getValue().equals(settings.message())) {
                messageBox.setValue(settings.message());
            }
        }
    }

    private void send() {
        typeTicks = 0;
        ServerUtilsNetwork.CHANNEL.sendToServer(new AdminAfkEditPacket(false, settings));
    }

    private void set(AfkSettings next) {
        settings = next.sanitised();
        send();
    }

    void tick() {
        if (messageBox != null) {
            messageBox.tick();
        }
        if (typeTicks > 0 && --typeTicks == 0) {
            send();
        }
        if (--refreshIn <= 0) {
            open();
        }
    }

    void flush() {
        if (typeTicks > 0) {
            send();
        }
    }

    // ------------------------------------------------------------------ layout, from the page's size

    /** The list's height, all the page down to the footer (158 on the old 210 high page). */
    private int boxH() {
        return host.pageH() - TOP - 30;
    }

    /** The list's width, all the page beside the settings (170 on the old 344 wide page). */
    private int rightW() {
        return host.pageW() - RIGHT_X - 8;
    }

    /** The settings box: as tall as its content, never past the list's bottom. */
    private int leftH() {
        return Math.min(boxH(), LEFT_H);
    }

    /** A settings line's offset from the box's top, pulled up in proportion when the box is shorter than its content. */
    private int at(int base) {
        return base - (LEFT_H - leftH()) * base / SQUEEZE_REF;
    }

    /** What the names' column gains on a wider list, or gives up (half the loss, the rest from the times) on a narrower one. */
    private int grow() {
        int extra = rightW() - RIGHT_W;
        return extra >= 0 ? extra : extra / 2;
    }

    void addWidgets(int x, int y) {
        messageBox = new EditBox(host.font(), x + LEFT_X + 4, y + TOP + at(MESSAGE_Y), LEFT_W - 8, 12, Component.literal(Tr.t("afk.message")));
        messageBox.setMaxLength(AfkSettings.MAX_MESSAGE);
        messageBox.setValue(settings.message());
        messageBox.setResponder(s -> {
            if (!s.equals(settings.message())) {
                settings = settings.withMessage(s);
                typeTicks = TYPE_TICKS;
            }
        });
        host.add(messageBox);
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        drawSettings(g, x, y, mx, my, hits);
        String tip = drawList(g, x, y, mx, my);
        Font font = host.font();
        int fy = y + host.pageH() - 8 - 14;
        String key = Tr.t("afk.refresh");
        int kw = Ui.widthBold(font, key, TINY) + 16, kx = x + host.pageW() - 8 - kw;
        List<String> footer = Ui.wrap(font, Tr.t("afk.footer"), kx - x - 16, TINY);
        for (int i = 0; i < Math.min(2, footer.size()); i++) {
            Ui.text(g, font, footer.get(i), x + 8, fy + (footer.size() == 1 ? 5 : 2 + i * 6), Ui.MUTED, TINY);
        }
        Ui.frame(g, kx, fy, kw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(key).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, kw, 14, this::open));
        if (tip != null) {
            g.renderTooltip(font, font.split(Component.literal(tip), 200), mx, my);
        }
    }

    private void drawSettings(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LEFT_X, ly = y + TOP, ix = lx + 6, iw = LEFT_W - 12;
        Ui.inset(g, lx, ly, LEFT_W, leftH());
        Ui.text(g, font, Component.literal(Tr.t("afk.detection")).withStyle(ChatFormatting.BOLD), ix, ly + at(5), Ui.VIOLET, TINY);
        toggle(g, ix, ly + at(15), iw, Tr.t("afk.mark"), settings.enabled(), hits, () -> set(settings.withEnabled(!settings.enabled())));
        stepper(g, ix, ly + at(29), iw, Tr.t("afk.after"), settings.afkMinutes(), Tr.t("afk.min"), mx, my, hits,
                () -> set(settings.withAfkMinutes(settings.afkMinutes() - 1)), () -> set(settings.withAfkMinutes(settings.afkMinutes() + 1)),
                settings.afkMinutes() > AfkSettings.MIN_AFK, settings.afkMinutes() < AfkSettings.MAX_AFK);

        Ui.text(g, font, Component.literal(Tr.t("afk.kicking")).withStyle(ChatFormatting.BOLD), ix, ly + at(45), Ui.VIOLET, TINY);
        toggle(g, ix, ly + at(55), iw, Tr.t("afk.kick"), settings.kick(), hits, () -> set(settings.withKick(!settings.kick())));
        stepper(g, ix, ly + at(69), iw, Tr.t("afk.kick_after"), settings.kickMinutes(), Tr.t("afk.min_afk"), mx, my, hits,
                () -> set(settings.withKickMinutes(settings.kickMinutes() - 5)), () -> set(settings.withKickMinutes(settings.kickMinutes() + 5)),
                settings.kickMinutes() > AfkSettings.MIN_KICK, settings.kickMinutes() < AfkSettings.MAX_KICK);
        Ui.text(g, font, Tr.t("afk.message").toUpperCase(Locale.ROOT), ix, ly + at(86), Ui.MUTED, TINY);
        String count = Tr.t("afk.count", settings.message().length(), AfkSettings.MAX_MESSAGE);
        Ui.text(g, font, count, ix + iw - Ui.width(font, count, TINY), ly + at(86), settings.message().length() >= AfkSettings.MAX_MESSAGE ? Ui.GOLD : Ui.MUTED, TINY);

        Ui.text(g, font, Component.literal(Tr.t("afk.macros")).withStyle(ChatFormatting.BOLD), ix, ly + at(112), Ui.VIOLET, TINY);
        toggle(g, ix, ly + at(121), iw, Tr.t("afk.macros_switch"), settings.macros(), hits, () -> set(settings.withMacros(!settings.macros())));
        int ty = ly + at(134);
        List<String> what = Ui.wrap(font, Tr.t("afk.macros_text"), iw, TINY);
        for (String line : what.subList(0, Math.min(4, what.size()))) {
            if (ty + 6 > ly + leftH()) {
                break;
            }
            Ui.text(g, font, line, ix, ty, Ui.MUTED, TINY);
            ty += 6;
        }
    }

    /** A switch with its label, at the panel's size when it fits, else smaller. */
    private void toggle(GuiGraphics g, int x, int y, int w, String label, boolean on, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        g.fill(x, y + 2, x + 14, y + 8, on ? 0xFF3D2F5C : Ui.DIM);
        g.fill(on ? x + 8 : x + 1, y + 3, on ? x + 13 : x + 6, y + 7, on ? Ui.VIOLET : Ui.MUTED);
        float scale = Ui.width(font, label, SMALL) <= w - 20 ? SMALL : TINY;
        Ui.text(g, font, Ui.fit(font, label, w - 20, scale), x + 19, y + (scale == SMALL ? 2 : 3), on ? Ui.TEXT : Ui.MUTED, scale);
        hits.add(new Ui.Hit(x, y, w, 10, action));
    }

    private void stepper(GuiGraphics g, int x, int y, int w, String label, int value, String unit, int mx, int my, List<Ui.Hit> hits,
                         Runnable down, Runnable up, boolean canDown, boolean canUp) {
        Font font = host.font();
        Ui.text(g, font, label.toUpperCase(Locale.ROOT), x, y + 3, Ui.MUTED, TINY);
        int uw = Ui.width(font, unit, TINY), sx = x + w - uw - 4 - 50;
        key(g, sx, y, "−", canDown, mx, my, hits, down);
        g.fill(sx + 12, y, sx + 38, y + 11, Ui.SLOT);
        Ui.frame(g, sx + 12, y, 26, 11, Ui.DIM);
        String v = Integer.toString(value);
        Ui.text(g, font, Component.literal(v).withStyle(ChatFormatting.BOLD), sx + 12 + (26 - Ui.width(font, v, SMALL)) / 2, y + 2, Ui.TEXT, SMALL);
        key(g, sx + 38, y, "+", canUp, mx, my, hits, up);
        Ui.text(g, font, unit, x + w - uw, y + 3, Ui.MUTED, TINY);
    }

    private void key(GuiGraphics g, int x, int y, String label, boolean enabled, int mx, int my, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        boolean hover = enabled && mx >= x && mx < x + 12 && my >= y && my < y + 11;
        g.fill(x, y, x + 12, y + 11, hover ? 0xFF3A404C : Ui.DIM);
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + (12 - Ui.width(font, label, SMALL)) / 2, y + 2, enabled ? Ui.TEXT : 0xFF5A6170, SMALL);
        if (enabled) {
            hits.add(new Ui.Hit(x, y, 12, 11, action));
        }
    }

    private static String clock(long ms) {
        return (ms / 60_000) + ":" + String.format(Locale.ROOT, "%02d", (ms / 1000) % 60);
    }

    /** The list; returns the hovered "macro?" mark's words, or null. */
    @Nullable
    private String drawList(GuiGraphics g, int x, int y, int mx, int my) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP, rw = rightW(), grow = grow();
        Ui.inset(g, px, py, rw, boxH());
        List<AdminAfkPacket.Row> rows = data == null ? List.of() : data.rows();
        long afkCount = rows.stream().filter(r -> !r.typedAway()).count();
        String all = Tr.t("afk.all_servers");
        int allW = Ui.width(font, all, TINY);
        String now = Ui.fitBold(font, Tr.t("afk.now", afkCount), rw - 14 - allW, TINY);
        Ui.text(g, font, Component.literal(now).withStyle(ChatFormatting.BOLD), px + 5, py + 5, Ui.VIOLET, TINY);
        Ui.text(g, font, all, px + rw - 5 - allW, py + 5, Ui.MUTED, TINY);
        if (rows.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t(data == null ? "afk.waiting" : "afk.nobody"), rw - 10, TINY), px + 5, py + 18, Ui.MUTED, TINY);
            return null;
        }
        int top = py + 15, visible = Math.max(1, (boxH() - 17) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        long since = System.currentTimeMillis() - receivedAt;
        int srvW = 0;
        for (AdminAfkPacket.Row r : rows) {
            srvW = Math.max(srvW, Ui.width(font, r.server(), TINY));
        }
        srvW = Math.min(srvW, 44) + 6;
        String pill = Tr.t("afk.macro");
        int pillW = Ui.width(font, pill, TINY) + 6;
        String tip = null;
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            AdminAfkPacket.Row r = rows.get(scroll + i);
            int ry = top + i * ROW;
            g.fill(px + 5, ry + 2, px + 9, ry + 6, AMBER);
            String name = Ui.fitBold(font, r.name(), (r.macro() ? 34 : 48) + grow, SMALL);
            Ui.text(g, font, Component.literal(name).withStyle(ChatFormatting.BOLD), px + 13, ry + 1, 0xFF000000 | r.colour(), SMALL);
            int cursor = px + 13 + Ui.widthBold(font, name, SMALL) + 3;
            if (r.macro()) {
                g.fill(cursor, ry, cursor + pillW, ry + 8, PILL);
                Ui.text(g, font, pill, cursor + 3, ry + 2, Ui.GOLD, TINY);
                if (mx >= cursor && mx < cursor + pillW && my >= ry && my < ry + 8) {
                    if (r.checkLeftMs() > 0) {
                        tip = Tr.t("afk.macro_tip_checked", r.name(), r.seen(), settings.kickMinutes());
                    } else {
                        tip = Tr.t("afk.macro_tip", r.name(), r.seen());
                    }
                }
                cursor += pillW + 3;
            }
            int wx = Math.max(px + 66 + grow, cursor), room = rw - (wx - px) - srvW - 8;
            String when;
            String warn = "";
            int warnColour = RED;
            if (r.typedAway()) {
                when = Tr.t("afk.typed_away");
            } else {
                long afkFor = r.afkForMs() + since;
                when = StaffFace.ago(afkFor);
                long toCheck = settings.kickMs() - afkFor;
                if (r.checkLeftMs() > 0) {
                    warn = Tr.t("afk.checked_left", clock(Math.max(0, r.checkLeftMs() - since)));
                    if (r.exempt()) {
                        warnColour = Ui.GOLD; // staff are checked but never kicked: no red (D468)
                    }
                } else if (r.exempt()) {
                    warn = Tr.t("afk.never_kicked");
                    warnColour = Ui.MUTED;
                } else if (settings.enabled() && settings.kick() && toCheck <= 5 * 60_000L) {
                    warn = Tr.t("afk.checked_in", StaffFace.ago(Math.max(0, toCheck)));
                }
            }
            Ui.text(g, font, Ui.fit(font, when, room, TINY), wx, ry + 2, Ui.MUTED, TINY);
            int ww = Ui.width(font, when, TINY) + 4;
            if (!warn.isEmpty() && ww < room) {
                Ui.text(g, font, Ui.fit(font, warn, Math.max(0, room - ww), TINY), wx + ww, ry + 2, warnColour, TINY);
            }
            int sx = px + rw - 4 - srvW;
            g.fill(sx, ry, sx + srvW, ry + 8, 0xFF1B2230);
            Ui.text(g, font, Ui.fit(font, r.server(), srvW - 4, TINY), sx + 3, ry + 2, Ui.MUTED, TINY);
        }
        return tip;
    }

    boolean scroll(double delta) {
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
