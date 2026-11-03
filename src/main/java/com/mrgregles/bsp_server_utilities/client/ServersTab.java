package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.AdminServersEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminServersPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.serverimages.ServerImages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The SERVERS tab of the admin panel (owner decisions D199 to D204), as on {@code tools/preview/server_images_refined.html}:
 * on the left every name in one alphabetical list (this server marked "here", a server not heard from lately "off", a file
 * named after a server never heard from "?"), each with its image as chat shows it, or its grey text, and its state; on
 * the right the picked server's file, the image at chat size and twice that, a sample chat line, and what is wrong with a
 * refused file. REFRESH makes the folder the network's set; when that would replace or remove an image in use the
 * footer names them in red and the key asks SURE? for three seconds. In the setup guide the key and the line sit in the
 * right box, since the guide's own keys take the footer.
 */
final class ServersTab {
    private static final int LIST_X = 8, TOP = 22, LIST_W = 170, BOX_H = 158, PANE_X = 184, PANE_W = 152, ROW = 13, HEAD = 12;
    private static final int REFRESH_TICKS = 100;
    private static final long SURE_MS = 3000;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int GREY_TEXT = 0xFFAAAAAA, GREEN = 0xFF8FE04A, RED = 0xFFFF6B5C;

    private final AdminScreen host;
    @Nullable
    private AdminServersPacket data;
    @Nullable
    private String picked;
    private int scroll, refreshIn;
    private long sureUntil;

    ServersTab(AdminScreen host) {
        this.host = host;
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        ServerUtilsNetwork.CHANNEL.sendToServer(new AdminServersEditPacket(AdminServersEditPacket.Action.VIEW, false));
    }

    void accept(AdminServersPacket p) {
        data = p;
        if (p.outcome() == AdminServersPacket.Outcome.PENDING) {
            sureUntil = System.currentTimeMillis() + SURE_MS; // D203: named in red, the key asks SURE?
        } else if (p.outcome() != AdminServersPacket.Outcome.NONE) {
            sureUntil = 0;
        }
        if (picked == null || row(picked) == null) {
            picked = p.rows().stream().filter(r -> r.mark() == AdminServersPacket.Mark.HERE).map(AdminServersPacket.Row::name).findFirst()
                    .orElse(p.rows().isEmpty() ? null : p.rows().get(0).name()); // D202: it opens on this server
        }
    }

    void tick() {
        if (--refreshIn <= 0) {
            open();
        }
    }

    boolean scroll(double delta) {
        int rows = data == null ? 0 : data.rows().size();
        scroll = Math.max(0, Math.min(Math.max(0, rows - visible()), scroll - (int) Math.signum(delta)));
        return true;
    }

    private static int visible() {
        return (BOX_H - HEAD - 2) / ROW;
    }

    @Nullable
    private AdminServersPacket.Row row(String name) {
        if (data == null) {
            return null;
        }
        for (AdminServersPacket.Row r : data.rows()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        boolean confirmed = now < sureUntil;
        sureUntil = 0;
        refreshIn = REFRESH_TICKS; // the tab's own refresh waits, so the answer and a SURE? line stay up
        ServerUtilsNetwork.CHANNEL.sendToServer(new AdminServersEditPacket(AdminServersEditPacket.Action.REFRESH, confirmed));
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        drawList(g, x, y, mx, my, hits);
        drawPane(g, x, y, mx, my, hits);
        if (!host.inGuide()) {
            drawFooter(g, x, y, mx, my, hits);
        }
    }

    private void drawList(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LIST_X, ly = y + TOP;
        Ui.inset(g, lx, ly, LIST_W, BOX_H);
        Ui.text(g, font, Tr.t("servers.server").toUpperCase(Locale.ROOT), lx + 5, ly + 4, Ui.MUTED, TINY);
        Ui.text(g, font, Tr.t("servers.chat").toUpperCase(Locale.ROOT), lx + 72, ly + 4, Ui.MUTED, TINY);
        String st = Tr.t("servers.state").toUpperCase(Locale.ROOT);
        Ui.text(g, font, st, lx + LIST_W - 5 - Ui.width(font, st, TINY), ly + 4, Ui.MUTED, TINY);
        if (data == null) {
            Ui.text(g, font, Tr.t("servers.loading"), lx + 5, ly + HEAD + 4, Ui.MUTED, TINY);
            return;
        }
        List<AdminServersPacket.Row> rows = data.rows();
        int ry = ly + HEAD;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible()); i++) {
            AdminServersPacket.Row r = rows.get(i);
            boolean sel = r.name().equals(picked);
            if (i > scroll) {
                g.fill(lx + 1, ry, lx + LIST_W - 1, ry + 1, Ui.ROW_LINE);
            }
            if (sel) {
                g.fill(lx + 1, ry, lx + LIST_W - 1, ry + ROW, 0xFF1B2230);
                g.fill(lx + 1, ry, lx + 3, ry + ROW, Ui.VIOLET);
            }
            String mark = switch (r.mark()) {
                case HERE -> Tr.t("servers.mark.here");
                case OFF -> Tr.t("servers.mark.off");
                case UNKNOWN -> "?";
                case NONE -> "";
            };
            int mw = mark.isEmpty() ? 0 : Ui.width(font, mark, 0.5f) + 2;
            String name = Ui.fit(font, r.name(), 62 - mw, SMALL);
            Ui.text(g, font, name, lx + 5, ry + 3, Ui.TEXT, SMALL);
            if (!mark.isEmpty()) {
                Ui.text(g, font, mark, lx + 5 + Ui.width(font, name, SMALL) + 2, ry + 4, Ui.MUTED, 0.5f);
            }
            drawTag(g, r, lx + 72, ry + 2, 7, 64);
            pill(g, r, lx + LIST_W - 5, ry + 3);
            String name0 = r.name();
            hits.add(new Ui.Hit(lx, ry, LIST_W, ROW, () -> picked = name0));
            ry += ROW;
        }
    }

    /** The server's tag as chat shows it: its image in use, else its grey text, {@code height} high and at most {@code maxW} wide. */
    private void drawTag(GuiGraphics g, AdminServersPacket.Row r, int x, int y, int height, int maxW) {
        if (r.inUse() && image(g, r.id(), x, y, height, maxW) > 0) {
            return;
        }
        Font font = host.font();
        float scale = height / 9f;
        Ui.text(g, font, Ui.fit(font, "[" + r.name() + "]", maxW, scale), x, y + 1, GREY_TEXT, scale);
    }

    /** Draws an image {@code height} high (width in proportion, at most {@code maxW}); returns the width drawn, 0 when it is not here yet. */
    private static int image(GuiGraphics g, String id, int x, int y, int height, int maxW) {
        ServerImageFont.Texture t = ServerImageFont.texture(id);
        if (t == null) {
            return 0;
        }
        int w = Math.max(1, Math.round((float) t.width() * height / t.height()));
        int h = height;
        if (w > maxW) {
            h = Math.max(1, Math.round((float) h * maxW / w));
            w = maxW;
        }
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(t.id(), x, y + (height - h) / 2, w, h, 0, 0, t.width(), t.height(), t.width(), t.height());
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        return w;
    }

    private void pill(GuiGraphics g, AdminServersPacket.Row r, int right, int y) {
        Font font = host.font();
        String label;
        int bg, fg;
        if (r.refused()) {
            label = Tr.t("servers.state.refused");
            bg = 0xFF3A2020;
            fg = RED;
        } else if (r.inUse()) {
            label = Tr.t("servers.state.image");
            bg = 0xFF1A2A16;
            fg = GREEN;
        } else if (!r.folderFile().isEmpty()) {
            label = Tr.t("servers.state.ready");
            bg = 0xFF1B2230;
            fg = Ui.VIOLET;
        } else {
            label = Tr.t("servers.state.text");
            bg = Ui.DIM;
            fg = Ui.MUTED;
        }
        int w = Ui.width(font, label, 0.5f) + 6;
        g.fill(right - w, y, right, y + 7, bg);
        Ui.text(g, font, label, right - w + 3, y + 1, fg, 0.5f);
    }

    private void drawPane(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + PANE_X, py = y + TOP, ix = px + 5, iw = PANE_W - 10;
        Ui.inset(g, px, py, PANE_W, BOX_H);
        AdminServersPacket.Row r = picked == null ? null : row(picked);
        if (r == null) {
            return;
        }
        String where = switch (r.mark()) {
            case HERE -> Tr.t("servers.where.here");
            case OFF, UNKNOWN -> Tr.t("servers.where.off");
            case NONE -> "";
        };
        String head = where.isEmpty() ? r.name() : r.name() + " · " + where;
        Ui.text(g, font, Component.literal(Ui.fit(font, head.toUpperCase(Locale.ROOT), iw, TINY)).withStyle(ChatFormatting.BOLD), ix, py + 5, Ui.VIOLET, TINY);
        int ty = py + 15;
        // the image in use, or the folder's file when it differs (D202)
        String id = r.inUse() ? r.id() : r.folderId();
        if (r.inUse()) {
            line(g, Tr.t("servers.file", r.file(), r.width(), r.height(), kb(r.bytes()), ServerImages.drawnWidth(r.width(), r.height())), ix, ty, iw, Ui.TEXT);
            ty += 8;
        }
        if (!r.folderFile().isEmpty() && (!r.inUse() || !r.folderId().equals(r.id()))) {
            if (r.refused()) {
                line(g, Tr.t("servers.file_refused", r.folderFile(), r.problem()), ix, ty, iw, RED);
            } else {
                line(g, Tr.t(r.inUse() ? "servers.file_new" : "servers.file_ready", r.folderFile(), r.folderWidth(), r.folderHeight()), ix, ty, iw, Ui.VIOLET);
            }
            ty += 8;
        } else if (r.folderFile().isEmpty() && !r.inUse()) {
            line(g, Tr.t("servers.no_file", r.name()), ix, ty, iw, Ui.MUTED);
            ty += 8;
        }
        if (r.mark() == AdminServersPacket.Mark.UNKNOWN) {
            for (String l : Ui.wrap(font, Tr.t("servers.unknown", r.name()), iw, TINY)) {
                Ui.text(g, font, l, ix, ty, Ui.GOLD, TINY);
                ty += 7;
            }
        }
        ty += 3;
        if (!id.isEmpty()) {
            Ui.text(g, font, Tr.t("servers.in_chat").toUpperCase(Locale.ROOT), ix, ty, Ui.MUTED, TINY);
            ty += 8;
            int w1 = image(g, id, ix, ty, 9, 96);
            image(g, id, ix + w1 + 6, ty - 4, 18, iw - w1 - 6);
            ty += 18;
        }
        Ui.text(g, font, Tr.t("servers.as_staff").toUpperCase(Locale.ROOT), ix, ty, Ui.MUTED, TINY);
        ty += 8;
        // the sample line on chat's dark backing
        g.fill(ix, ty - 1, ix + iw, ty + 10, 0x80000000);
        int sx = ix + 2;
        int drawn = id.isEmpty() ? 0 : image(g, id, sx, ty, 9, 96);
        if (drawn == 0) {
            String text = "[" + r.name() + "]";
            Ui.text(g, font, text, sx, ty + 1, GREY_TEXT, 1f);
            drawn = font.width(text);
        }
        Ui.text(g, font, Ui.fit(font, Tr.t("servers.sample"), iw - drawn - 8, 1f), sx + drawn + 4, ty + 1, 0xFFFFFFFF, 1f);
        ty += 14;
        if (host.inGuide()) {
            drawGuideKey(g, ix, py + BOX_H - 30, iw, mx, my, hits);
        } else {
            for (String l : Ui.wrap(font, Tr.t("servers.hover_note", r.name()), iw, TINY)) {
                if (ty > py + BOX_H - 9) {
                    break;
                }
                Ui.text(g, font, l, ix, ty, Ui.MUTED, TINY);
                ty += 7;
            }
        }
    }

    private void line(GuiGraphics g, String s, int x, int y, int w, int colour) {
        Ui.text(g, host.font(), Ui.fit(host.font(), s, w, TINY), x, y, colour, TINY);
    }

    private static String kb(int bytes) {
        return bytes < 1024 ? bytes + " B" : Math.round(bytes / 1024f) + " KB";
    }

    /** The line under the list: what REFRESH did or would do, or where the set lives (D203, D204). */
    private String status() {
        if (data == null) {
            return "";
        }
        if (System.currentTimeMillis() < sureUntil && data.outcome() == AdminServersPacket.Outcome.PENDING) {
            String replaces = data.replaced().isEmpty() ? "" : Tr.t("servers.replaces", String.join(", ", data.replaced()));
            String removes = data.removed().isEmpty() ? "" : Tr.t("servers.removes", String.join(", ", data.removed()));
            String both = replaces.isEmpty() ? removes : removes.isEmpty() ? replaces : replaces + ", " + removes.substring(0, 1).toLowerCase(Locale.ROOT) + removes.substring(1);
            return both + ". " + Tr.t("servers.click_again");
        }
        if (data.outcome() == AdminServersPacket.Outcome.DONE) {
            int n = data.added().size() + data.replaced().size() + data.removed().size();
            if (data.replaced().isEmpty() && data.removed().isEmpty()) {
                return Tr.t("servers.added", data.added().size());
            }
            return Tr.t("servers.changed", n);
        }
        if (data.outcome() == AdminServersPacket.Outcome.NOTHING) {
            return Tr.t("servers.nothing");
        }
        if (!data.shared()) {
            return Tr.t("servers.local");
        }
        return Tr.t("servers.folder", ServerImages.FOLDER, data.files());
    }

    private int statusColour() {
        if (data == null) {
            return Ui.MUTED;
        }
        if (System.currentTimeMillis() < sureUntil && data.outcome() == AdminServersPacket.Outcome.PENDING) {
            return RED;
        }
        if (data.outcome() == AdminServersPacket.Outcome.DONE) {
            return GREEN;
        }
        return !data.shared() && data.outcome() != AdminServersPacket.Outcome.NOTHING ? Ui.GOLD : Ui.MUTED;
    }

    private void drawFooter(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int fy = y + AdminScreen.H - 8 - 14;
        int kw = refreshKey(g, x + AdminScreen.W - 8, fy, mx, my, hits);
        String guideKey = Tr.t("admin.setup.guide_key");
        int gw = Ui.width(font, guideKey, TINY) + 16, gx = x + AdminScreen.W - 8 - kw - 4 - gw;
        Ui.frame(g, gx, fy, gw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(guideKey).withStyle(ChatFormatting.BOLD), gx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(gx, fy, gw, 14, () -> ServerUtilsNetwork.CHANNEL.sendToServer(
                com.mrgregles.bsp_server_utilities.network.SetupEditPacket.of(com.mrgregles.bsp_server_utilities.network.SetupEditPacket.Action.OPEN))));
        Ui.text(g, font, Ui.fit(font, status(), gx - x - 16, TINY), x + 8, fy + 5, statusColour(), TINY);
    }

    /** In the guide: the status line and REFRESH at the bottom of the right box. */
    private void drawGuideKey(GuiGraphics g, int x, int y, int w, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int ty = y;
        for (String l : Ui.wrap(font, status(), w, TINY)) {
            if (ty > y + 8) {
                break;
            }
            Ui.text(g, font, l, x, ty, statusColour(), TINY);
            ty += 7;
        }
        String label = Tr.t("servers.refresh");
        refreshKey(g, x + Ui.width(font, label, TINY) + 16, y + 15, mx, my, hits);
    }

    /** REFRESH, or SURE? while a plan waits; drawn right-aligned at {@code right}. Returns its width. */
    private int refreshKey(GuiGraphics g, int right, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        boolean sure = System.currentTimeMillis() < sureUntil;
        String label = Tr.t(sure ? "rules.sure" : "servers.refresh");
        int w = Math.max(Ui.width(font, Tr.t("servers.refresh"), TINY), Ui.width(font, Tr.t("rules.sure"), TINY)) + 16, x = right - w;
        boolean hover = mx >= x && mx < right && my >= y && my < y + 14;
        if (sure) {
            g.fill(x, y, right, y + 14, 0xFF3A2A2A);
            Ui.frame(g, x, y, w, 14, 0xFFD63B2F);
        } else {
            g.fill(x, y, right, y + 14, hover ? 0xFFC9A8FF : Ui.VIOLET);
        }
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + (w - Ui.width(font, label, TINY)) / 2, y + 5, sure ? 0xFFFF8F86 : Ui.INK, TINY);
        hits.add(new Ui.Hit(x, y, w, 14, this::refresh));
        return w;
    }
}
