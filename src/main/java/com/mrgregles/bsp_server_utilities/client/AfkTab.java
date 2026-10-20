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
 * The AFK tab of the admin panel (owner decisions D76, D79 to D85), as on {@code tools/preview/afk_refined.html}: on the
 * left AFK DETECTION (the switch, AFK AFTER with − and +, what counts as active) and KICKING (the switch, KICK AFTER, the
 * kick message); on the right everyone AFK now on any server with how long and where, a red countdown beside anyone
 * near a kick, and staff away by {@code /su away}. A switch or a step goes to the server at once, the message a second
 * after typing stops; the list is asked for again every few seconds while the tab is open.
 */
final class AfkTab {
    private static final int LEFT_X = 8, TOP = 22, LEFT_W = 150, BOX_H = 158, RIGHT_X = 166, RIGHT_W = 170, ROW = 10;
    private static final int REFRESH_TICKS = 100, TYPE_TICKS = 20;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int AMBER = 0xFFF2B544, RED = 0xFFFF6B5C;

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

    void addWidgets(int x, int y) {
        messageBox = new EditBox(host.font(), x + LEFT_X + 4, y + TOP + 126, LEFT_W - 8, 12, Component.literal(Tr.t("afk.message")));
        messageBox.setMaxLength(AfkSettings.MAX_MESSAGE);
        messageBox.setValue(settings.message());
        messageBox.setResponder(s -> {
            if (!s.equals(settings.message())) {
                settings = new AfkSettings(settings.enabled(), settings.afkMinutes(), settings.kick(), settings.kickMinutes(), s, settings.revision());
                typeTicks = TYPE_TICKS;
            }
        });
        host.add(messageBox);
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        drawSettings(g, x, y, mx, my, hits);
        drawList(g, x, y);
        Font font = host.font();
        int fy = y + AdminScreen.H - 8 - 14;
        String key = Tr.t("afk.refresh");
        int kw = Ui.width(font, key, TINY) + 16, kx = x + AdminScreen.W - 8 - kw;
        Ui.text(g, font, Ui.fit(font, Tr.t("afk.footer"), kx - x - 16, TINY), x + 8, fy + 5, Ui.MUTED, TINY);
        Ui.frame(g, kx, fy, kw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(key).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, kw, 14, this::open));
    }

    private void drawSettings(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LEFT_X, ly = y + TOP, ix = lx + 6, iw = LEFT_W - 12;
        Ui.inset(g, lx, ly, LEFT_W, BOX_H);
        Ui.text(g, font, Component.literal(Tr.t("afk.detection")).withStyle(ChatFormatting.BOLD), ix, ly + 5, Ui.VIOLET, TINY);
        toggle(g, ix, ly + 15, iw, Tr.t("afk.mark"), settings.enabled(), hits,
                () -> set(new AfkSettings(!settings.enabled(), settings.afkMinutes(), settings.kick(), settings.kickMinutes(), settings.message(), settings.revision())));
        stepper(g, ix, ly + 29, iw, Tr.t("afk.after"), settings.afkMinutes(), Tr.t("afk.min"), mx, my, hits,
                () -> set(withAfk(settings.afkMinutes() - 1)), () -> set(withAfk(settings.afkMinutes() + 1)),
                settings.afkMinutes() > AfkSettings.MIN_AFK, settings.afkMinutes() < AfkSettings.MAX_AFK);
        int ty = ly + 44;
        List<String> active = Ui.wrap(font, Tr.t("afk.active"), iw, TINY);
        for (String line : active.subList(0, Math.min(4, active.size()))) {
            Ui.text(g, font, line, ix, ty, Ui.MUTED, TINY);
            ty += 6;
        }
        Ui.text(g, font, Component.literal(Tr.t("afk.kicking")).withStyle(ChatFormatting.BOLD), ix, ly + 74, Ui.VIOLET, TINY);
        toggle(g, ix, ly + 84, iw, Tr.t("afk.kick"), settings.kick(), hits,
                () -> set(new AfkSettings(settings.enabled(), settings.afkMinutes(), !settings.kick(), settings.kickMinutes(), settings.message(), settings.revision())));
        stepper(g, ix, ly + 98, iw, Tr.t("afk.kick_after"), settings.kickMinutes(), Tr.t("afk.min_afk"), mx, my, hits,
                () -> set(withKick(settings.kickMinutes() - 5)), () -> set(withKick(settings.kickMinutes() + 5)),
                settings.kickMinutes() > AfkSettings.MIN_KICK, settings.kickMinutes() < AfkSettings.MAX_KICK);
        Ui.text(g, font, Tr.t("afk.message").toUpperCase(Locale.ROOT), ix, ly + 117, Ui.MUTED, TINY);
        String count = Tr.t("afk.count", settings.message().length(), AfkSettings.MAX_MESSAGE);
        Ui.text(g, font, count, ix + iw - Ui.width(font, count, TINY), ly + 117, settings.message().length() >= AfkSettings.MAX_MESSAGE ? Ui.GOLD : Ui.MUTED, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("afk.never_staff"), iw, TINY), ix, ly + 144, Ui.MUTED, TINY);
    }

    private AfkSettings withAfk(int minutes) {
        return new AfkSettings(settings.enabled(), minutes, settings.kick(), settings.kickMinutes(), settings.message(), settings.revision());
    }

    private AfkSettings withKick(int minutes) {
        return new AfkSettings(settings.enabled(), settings.afkMinutes(), settings.kick(), minutes, settings.message(), settings.revision());
    }

    private void toggle(GuiGraphics g, int x, int y, int w, String label, boolean on, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        g.fill(x, y + 2, x + 14, y + 8, on ? 0xFF3D2F5C : Ui.DIM);
        g.fill(on ? x + 8 : x + 1, y + 3, on ? x + 13 : x + 6, y + 7, on ? Ui.VIOLET : Ui.MUTED);
        Ui.text(g, font, Ui.fit(font, label, w - 20, SMALL), x + 19, y + 2, on ? Ui.TEXT : Ui.MUTED, SMALL);
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

    private void drawList(GuiGraphics g, int x, int y) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP;
        Ui.inset(g, px, py, RIGHT_W, BOX_H);
        List<AdminAfkPacket.Row> rows = data == null ? List.of() : data.rows();
        long afkCount = rows.stream().filter(r -> !r.typedAway()).count();
        Ui.text(g, font, Component.literal(Tr.t("afk.now", afkCount)).withStyle(ChatFormatting.BOLD), px + 5, py + 5, Ui.VIOLET, TINY);
        String all = Tr.t("afk.all_servers");
        Ui.text(g, font, all, px + RIGHT_W - 5 - Ui.width(font, all, TINY), py + 5, Ui.MUTED, TINY);
        if (rows.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t(data == null ? "afk.waiting" : "afk.nobody"), RIGHT_W - 10, TINY), px + 5, py + 18, Ui.MUTED, TINY);
            return;
        }
        int top = py + 15, visible = (BOX_H - 17) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        long since = System.currentTimeMillis() - receivedAt;
        int srvW = 0;
        for (AdminAfkPacket.Row r : rows) {
            srvW = Math.max(srvW, Ui.width(font, r.server(), TINY));
        }
        srvW = Math.min(srvW, 44) + 6;
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            AdminAfkPacket.Row r = rows.get(scroll + i);
            int ry = top + i * ROW;
            g.fill(px + 5, ry + 2, px + 9, ry + 6, AMBER);
            Ui.text(g, font, Component.literal(Ui.fit(font, r.name(), 48, SMALL)).withStyle(ChatFormatting.BOLD), px + 13, ry + 1, 0xFF000000 | r.colour(), SMALL);
            int wx = px + 66, room = RIGHT_W - (wx - px) - srvW - 8;
            String when;
            String warn = "";
            if (r.typedAway()) {
                when = Tr.t("afk.typed_away");
            } else {
                long afkFor = r.afkForMs() + since;
                when = StaffFace.ago(afkFor);
                long left = settings.kickMs() - afkFor;
                if (settings.enabled() && settings.kick() && !r.exempt() && left <= 5 * 60_000L) {
                    warn = Tr.t("afk.kicked_in", StaffFace.ago(Math.max(0, left)));
                }
            }
            Ui.text(g, font, Ui.fit(font, when, room, TINY), wx, ry + 2, Ui.MUTED, TINY);
            if (!warn.isEmpty()) {
                int ww = Ui.width(font, when, TINY) + 4;
                Ui.text(g, font, Ui.fit(font, warn, Math.max(0, room - ww), TINY), wx + ww, ry + 2, RED, TINY);
            }
            int sx = px + RIGHT_W - 4 - srvW;
            g.fill(sx, ry, sx + srvW, ry + 8, 0xFF1B2230);
            Ui.text(g, font, Ui.fit(font, r.server(), srvW - 4, TINY), sx + 3, ry + 2, Ui.MUTED, TINY);
        }
    }

    boolean scroll(double delta) {
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
