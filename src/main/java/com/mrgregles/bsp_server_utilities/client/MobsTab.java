package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.mobs.MobPlan;
import com.mrgregles.bsp_server_utilities.mobs.MobSettings;
import com.mrgregles.bsp_server_utilities.network.AdminMobsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminMobsPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The MOBS tab of the admin panel (owner decisions D478 to D486), look A of {@code tools/preview/mobs_refined.html}: on
 * the left the limits (EVERY CHUNK, DIMENSIONS with ADD, SINGLE CHUNKS with HERE, NEVER COUNTED with a box to add a mob),
 * each number with − and + (shift: 10) going down to OFF; on the right the fullest chunks on every server, worst first,
 * a server not heard from for 30 s dimmed, and under the list the picked chunk: what is in it, REMOVE EXTRAS with SURE?
 * on this server, LIMIT to give it its own number. Every change goes to the server at once; the list is asked for again
 * every 5 s while the tab is open.
 */
final class MobsTab {
    private static final int LEFT_X = 8, TOP = 34, LEFT_W = 150, RIGHT_X = 166, ROW = 10, LINE = 11, PICKED_H = 44;
    private static final int REFRESH_TICKS = 100, QUIET_TICKS = 10, SURE_MS = 3000, FLASH_MS = 1500, NOTE_MS = 4000;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int AMBER = 0xFFF2B544, RED = 0xFFFF6B5C, KEY_HOVER = 0xFF3A404C, ROW_SEL = 0xFF1B2230, FLASH = 0xFF122A26, WARN = 0xFF3A2A2A;

    private final AdminScreen host;
    @Nullable
    private AdminMobsPacket data;
    private MobSettings settings = MobSettings.DEFAULTS;
    private int refreshIn, quiet, dimScroll, chunkScroll, neverScroll, menuScroll, listScroll, picked = -1;
    private long receivedAt, sureUntil, flashUntil, noteUntil;
    private boolean dimMenu, dropsPage;
    private int dropPicked = -1;
    private String note = "";
    @Nullable
    private String sureKey, flashKey;
    @Nullable
    private EditBox neverBox;

    MobsTab(AdminScreen host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ layout, from the page's size

    /** Both boxes' height, down to the footer (146 on a 210-high page). */
    private int boxH() {
        return host.pageH() - TOP - 30;
    }

    /** The list box's width: all the page's width the limits column leaves (170 on a 344-wide page). */
    private int rightW() {
        return host.pageW() - RIGHT_X - 8;
    }

    /**
     * Rows DIMENSIONS and SINGLE CHUNKS show between them: as many as fit above NEVER COUNTED with 3 to spare at the
     * box's foot (6, 3 each, on a 146-high box), the odd one to DIMENSIONS.
     */
    private int limitRows() {
        return Math.max(2, (boxH() - 79) / LINE);
    }

    private int dimRows() {
        return (limitRows() + 1) / 2;
    }

    private int chunkRows() {
        return limitRows() / 2;
    }

    /** A section's heading and its rows (46 with 3 rows). */
    private static int sectionH(int rows) {
        return 13 + rows * LINE;
    }

    private int dimsY() {
        return TOP + 18;
    }

    private int chunksY() {
        return dimsY() + sectionH(dimRows());
    }

    /** NEVER COUNTED: its heading, a line of chips, then the box to add a mob 21 below the heading. */
    private int neverY() {
        return chunksY() + sectionH(chunkRows());
    }

    /** Rows of the list above the picked chunk's box (8 on a 146-high box). */
    private int listRows() {
        return Math.max(1, (boxH() - PICKED_H - 22) / ROW);
    }

    /** Lines of the dimension menu (10 on a 146-high box). */
    private int menuRows() {
        return Math.max(1, (boxH() - 42) / 10);
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        ServerUtilsNetwork.CHANNEL.sendToServer(AdminMobsEditPacket.of(AdminMobsEditPacket.Action.OPEN));
    }

    void accept(AdminMobsPacket p) {
        String was = picked >= 0 && data != null && picked < data.rows().size() ? key(data.rows().get(picked)) : null;
        String wasDrop = dropPicked >= 0 && data != null && dropPicked < data.drops().size() ? dropKey(data.drops().get(dropPicked)) : null;
        data = p;
        receivedAt = System.currentTimeMillis();
        if (quiet <= 0) {
            settings = p.settings();
        }
        picked = -1;
        for (int i = 0; was != null && i < p.rows().size(); i++) {
            if (key(p.rows().get(i)).equals(was)) {
                picked = i; // the same chunk stays picked as the list moves
            }
        }
        dropPicked = -1;
        for (int i = 0; wasDrop != null && i < p.drops().size(); i++) {
            if (dropKey(p.drops().get(i)).equals(wasDrop)) {
                dropPicked = i;
            }
        }
        if (p.cleared() != AdminMobsPacket.NO_NOTE) {
            note(p.cleared() > 0 ? Tr.t("crowd.note_cleared", p.cleared()) : Tr.t("crowd.note_none"));
        }
        if (p.removed() != AdminMobsPacket.NO_NOTE) {
            note(p.removed() > 0 ? Tr.t("mobs.note_removed", p.removed()) : Tr.t("mobs.note_none"));
        }
    }

    private void note(String s) {
        note = s;
        noteUntil = System.currentTimeMillis() + NOTE_MS;
    }

    /** One change: shown at once here, sent alone, so another admin's changes elsewhere are never undone (audit). */
    private void change(MobSettings shown, AdminMobsEditPacket.Action action, String server, String dim, int x, int z, int count) {
        settings = shown.sanitised();
        quiet = QUIET_TICKS; // our own change stands until the server's answer to it can have come
        ServerUtilsNetwork.CHANNEL.sendToServer(AdminMobsEditPacket.chunk(action, server, dim, x, z, count));
    }

    void tick() {
        if (neverBox != null) {
            neverBox.tick();
        }
        if (quiet > 0) {
            quiet--;
        }
        if (--refreshIn <= 0) {
            refreshIn = REFRESH_TICKS;
            ServerUtilsNetwork.CHANNEL.sendToServer(AdminMobsEditPacket.of(AdminMobsEditPacket.Action.REFRESH));
        }
    }

    void addWidgets(int x, int y) {
        neverBox = new EditBox(host.font(), x + LEFT_X + 6, y + neverY() + 21, LEFT_W - 47, 11, Component.literal(Tr.t("mobs.never_hint")));
        neverBox.setMaxLength(64);
        neverBox.setHint(Component.literal(Tr.t("mobs.never_hint")).withStyle(ChatFormatting.DARK_GRAY));
        host.add(neverBox);
    }

    private void addNever() {
        if (neverBox == null) {
            return;
        }
        String id = neverBox.getValue().strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (id.isEmpty()) {
            return;
        }
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        if (EntityType.byString(id).isEmpty()) {
            note(Tr.t("mobs.note_no_mob", id));
            return;
        }
        change(settings.withNever(id, true), AdminMobsEditPacket.Action.NEVER, "", id, 0, 0, 1);
        neverBox.setValue("");
    }

    // ------------------------------------------------------------------ names

    /** A dimension by name: the three of the game, the Compact Machines rooms, else its id in words. */
    static String dimName(String id) {
        switch (id) {
            case "minecraft:overworld":
                return Tr.t("mobs.dim.overworld");
            case "minecraft:the_nether":
                return Tr.t("mobs.dim.nether");
            case "minecraft:the_end":
                return Tr.t("mobs.dim.end");
            case "compactmachines:compact_world":
                return Tr.t("mobs.dim.rooms");
            default:
                String w = MobPlan.words(id);
                return w.isEmpty() ? id : Character.toUpperCase(w.charAt(0)) + w.substring(1);
        }
    }

    private static String mobName(String type) {
        return EntityType.byString(type).map(t -> t.getDescription().getString()).orElse(MobPlan.words(type));
    }

    private static String key(AdminMobsPacket.Row r) {
        return r.server() + "|" + r.dim() + "|" + r.x() + "|" + r.z();
    }

    private static String place(AdminMobsPacket.Row r) {
        if (!r.owner().isEmpty()) {
            return Tr.t("mobs.room", r.owner());
        }
        return Tr.t("mobs.place", dimName(r.dim()).toLowerCase(Locale.ROOT), r.x(), r.z());
    }

    private String from(MobSettings.From f) {
        if (f == MobSettings.From.CHUNK) {
            return Tr.t("mobs.from_chunk");
        }
        if (f == MobSettings.From.DIMENSION) {
            return Tr.t("mobs.from_dim");
        }
        return Tr.t("mobs.from_every");
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        int start = hits.size();
        pageKeys(g, x, y, mx, my, hits);
        if (neverBox != null) {
            neverBox.visible = !dropsPage;
        }
        String tip, tip2;
        if (dropsPage) {
            tip = drawDropNumbers(g, x, y, mx, my, hits);
            tip2 = drawDropList(g, x, y, mx, my, hits);
        } else {
            tip = drawLimits(g, x, y, mx, my, hits);
            tip2 = drawList(g, x, y, mx, my, hits);
        }
        Font font = host.font();
        int fy = y + host.pageH() - 8 - 14;
        String footer;
        if (System.currentTimeMillis() < noteUntil) {
            footer = note;
        } else if (dropsPage) {
            footer = Tr.t("crowd.footer");
        } else if (settings.anyOn()) {
            footer = Tr.t("mobs.footer");
        } else {
            footer = Tr.t("mobs.footer_off");
        }
        int footerColour = System.currentTimeMillis() < noteUntil ? Ui.GOLD : Ui.MUTED;
        List<String> lines = Ui.wrap(font, footer, host.pageW() - 16, TINY);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            Ui.text(g, font, lines.get(i), x + 8, fy + (lines.size() == 1 ? 5 : 2 + i * 6), footerColour, TINY);
        }
        if (dimMenu) {
            hits.subList(start, hits.size()).clear(); // while the menu is open only its lines (and closing it) take clicks
            drawDimMenu(g, x, y, mx, my, hits);
        }
        String t = tip2 != null ? tip2 : tip;
        if (t != null && !dimMenu) {
            g.renderTooltip(font, font.split(Component.literal(t), 200), mx, my);
        }
    }

    /** MOBS and DROPS &amp; ARROWS under the tab row (D493, D498). */
    private void pageKeys(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int kx = x + LEFT_X;
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == dropsPage;
            String label = i == 0 ? Tr.t("mobs.page_mobs") : Tr.t("mobs.page_drops");
            int w = Ui.widthBold(font, label, TINY) + 12;
            g.fill(kx, y + 21, kx + w, y + 31, Ui.SLOT);
            Ui.frame(g, kx, y + 21, w, 10, on ? Ui.VIOLET : Ui.DIM);
            Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), kx + 6, y + 24, on ? Ui.VIOLET : Ui.MUTED, TINY);
            boolean drops = i == 1;
            hits.add(new Ui.Hit(kx, y + 21, w, 10, () -> {
                dropsPage = drops;
                dimMenu = false;
                sureKey = null;
            }));
            kx += w + 3;
        }
    }

    private boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void smallKey(GuiGraphics g, int x, int y, int w, String label, int mx, int my, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        g.fill(x, y, x + w, y + 10, in(mx, my, x, y, w, 10) ? KEY_HOVER : Ui.DIM);
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + (w - Ui.widthBold(font, label, TINY)) / 2, y + 3, Ui.TEXT, TINY);
        hits.add(new Ui.Hit(x, y, w, 10, action));
    }

    private static int step() {
        return Screen.hasShiftDown() ? 10 : 1;
    }

    /** − value + for a number that goes down to OFF; returns its width. */
    private int stepper(GuiGraphics g, int x, int y, int value, int mx, int my, List<Ui.Hit> hits, java.util.function.IntConsumer to) {
        Font font = host.font();
        tinyKey(g, x, y, "−", value > MobSettings.OFF, mx, my, hits, () -> to.accept(Math.max(MobSettings.OFF, value - step())));
        g.fill(x + 11, y, x + 35, y + 10, Ui.SLOT);
        Ui.frame(g, x + 11, y, 24, 10, Ui.DIM);
        String v = value > MobSettings.OFF ? Integer.toString(value) : Tr.t("mobs.off");
        Ui.text(g, font, Component.literal(v).withStyle(ChatFormatting.BOLD), x + 11 + (24 - Ui.widthBold(font, v, TINY)) / 2, y + 3, value > MobSettings.OFF ? Ui.TEXT : Ui.MUTED, TINY);
        tinyKey(g, x + 35, y, "+", value < MobSettings.MAX_LIMIT, mx, my, hits, () -> to.accept(Math.min(MobSettings.MAX_LIMIT, value + step())));
        return 46;
    }

    private void tinyKey(GuiGraphics g, int x, int y, String label, boolean enabled, int mx, int my, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        boolean hover = enabled && in(mx, my, x, y, 11, 10);
        g.fill(x, y, x + 11, y + 10, hover ? KEY_HOVER : Ui.DIM);
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + (11 - Ui.widthBold(font, label, SMALL)) / 2, y + 1, enabled ? Ui.TEXT : 0xFF5A6170, SMALL);
        if (enabled) {
            hits.add(new Ui.Hit(x, y, 11, 10, action));
        }
    }

    private void cross(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        Ui.text(g, font, "×", x + 1, y + 2, in(mx, my, x, y, 8, 10) ? RED : Ui.MUTED, SMALL);
        hits.add(new Ui.Hit(x, y, 8, 10, action));
    }

    private void label(GuiGraphics g, int x, int y, String s) {
        Ui.text(g, host.font(), Component.literal(s).withStyle(ChatFormatting.BOLD), x, y, Ui.VIOLET, TINY);
    }

    private String drawLimits(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LEFT_X, ly = y + TOP, ix = lx + 6, iw = LEFT_W - 12, right = ix + iw, rows = dimRows();
        Ui.inset(g, lx, ly, LEFT_W, boxH());
        String tip = null;

        label(g, ix, ly + 7, Tr.t("mobs.every"));
        stepper(g, right - 46, ly + 4, settings.every(), mx, my, hits, n -> change(settings.withEvery(n), AdminMobsEditPacket.Action.EVERY, "", "", 0, 0, n));

        // dimensions
        int dy = y + dimsY();
        label(g, ix, dy + 3, Tr.t("mobs.dims"));
        smallKey(g, right - 24, dy, 24, Tr.t("mobs.add"), mx, my, hits, () -> dimMenu = !dimMenu);
        List<MobSettings.DimLimit> dims = settings.dims();
        dimScroll = Math.max(0, Math.min(dimScroll, Math.max(0, dims.size() - rows)));
        int ry = dy + 12;
        if (dims.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("mobs.dims_none"), iw, TINY), ix, ry + 3, Ui.MUTED, TINY);
        }
        for (int i = 0; i < rows && dimScroll + i < dims.size(); i++) {
            MobSettings.DimLimit d = dims.get(dimScroll + i);
            Ui.text(g, font, Ui.fit(font, dimName(d.dim()), iw - 60, TINY), ix, ry + 3, Ui.TEXT, TINY);
            stepper(g, right - 56, ry, d.limit(), mx, my, hits, n -> change(settings.withDim(d.dim(), n), AdminMobsEditPacket.Action.DIM, "", d.dim(), 0, 0, n));
            cross(g, right - 8, ry, mx, my, hits, () -> change(settings.withDim(d.dim(), null), AdminMobsEditPacket.Action.DIM, "", d.dim(), 0, 0, AdminMobsEditPacket.REMOVE_ROW));
            ry += LINE;
        }
        if (dims.size() > rows) {
            Ui.text(g, font, Tr.t("mobs.more", dims.size() - rows), ix + Ui.widthBold(font, Tr.t("mobs.dims"), TINY) + 4, dy + 3, Ui.MUTED, TINY);
        }

        // single chunks
        int cy = y + chunksY();
        label(g, ix, cy + 3, Tr.t("mobs.chunks"));
        smallKey(g, right - 28, cy, 28, Tr.t("mobs.here"), mx, my, hits,
                () -> ServerUtilsNetwork.CHANNEL.sendToServer(AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.HERE, "", "", 0, 0, 0)));
        List<MobSettings.ChunkLimit> chunks = settings.chunks();
        rows = chunkRows();
        chunkScroll = Math.max(0, Math.min(chunkScroll, Math.max(0, chunks.size() - rows)));
        ry = cy + 12;
        if (chunks.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("mobs.chunks_none"), iw, TINY), ix, ry + 3, Ui.MUTED, TINY);
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows && chunkScroll + i < chunks.size(); i++) {
            MobSettings.ChunkLimit c = chunks.get(chunkScroll + i);
            String k = c.server() + "|" + c.dim() + "|" + c.x() + "|" + c.z();
            if (k.equals(flashKey) && now < flashUntil) {
                g.fill(ix - 2, ry - 1, right + 1, ry + 11, FLASH);
            }
            int sw = Math.min(36, Ui.width(font, c.server(), TINY) + 4);
            g.fill(ix, ry + 1, ix + sw, ry + 9, ROW_SEL);
            Ui.text(g, font, Ui.fit(font, c.server(), sw - 4, TINY), ix + 2, ry + 3, Ui.MUTED, TINY);
            String where = c.x() + " " + c.z();
            int wx = ix + sw + 3;
            Ui.text(g, font, Ui.fit(font, where, right - 56 - wx - 2, TINY), wx, ry + 3, Ui.MUTED, TINY);
            if (in(mx, my, wx, ry, right - 56 - wx, 10)) {
                tip = Tr.t("mobs.chunk_tip", dimName(c.dim()), c.server(), c.x() * 16, c.z() * 16, c.x() * 16 + 15, c.z() * 16 + 15);
            }
            stepper(g, right - 56, ry, c.limit(), mx, my, hits, n -> change(settings.withChunk(c.server(), c.dim(), c.x(), c.z(), n), AdminMobsEditPacket.Action.CHUNK, c.server(), c.dim(), c.x(), c.z(), n));
            cross(g, right - 8, ry, mx, my, hits, () -> change(settings.withChunk(c.server(), c.dim(), c.x(), c.z(), null), AdminMobsEditPacket.Action.CHUNK, c.server(), c.dim(), c.x(), c.z(), AdminMobsEditPacket.REMOVE_ROW));
            ry += LINE;
        }
        if (chunks.size() > rows) {
            Ui.text(g, font, Tr.t("mobs.more", chunks.size() - rows), ix + Ui.widthBold(font, Tr.t("mobs.chunks"), TINY) + 4, cy + 3, Ui.MUTED, TINY);
        }

        // never counted
        int ny = y + neverY();
        label(g, ix, ny + 3, Tr.t("mobs.never"));
        List<String> never = settings.never();
        neverScroll = Math.max(0, Math.min(neverScroll, Math.max(0, never.size() - 1)));
        int chipX = ix, chipY = ny + 10;
        if (never.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("mobs.never_none"), iw, TINY), ix, chipY + 2, Ui.MUTED, TINY);
        }
        for (int i = neverScroll; i < never.size(); i++) {
            String type = never.get(i), name = mobName(type);
            int w = Ui.width(font, name, TINY) + 12;
            String more = Tr.t("mobs.more", never.size() - i);
            boolean last = i == never.size() - 1;
            if (chipX + w > right || (!last && chipX + w + 3 + Ui.width(font, more, TINY) > right)) {
                // always room left for "+N more" inside the column (audit of SU-154)
                Ui.text(g, font, Ui.fit(font, more, Math.max(0, right - chipX), TINY), chipX, chipY + 2, Ui.MUTED, TINY);
                break;
            }
            g.fill(chipX, chipY, chipX + w, chipY + 9, ROW_SEL);
            Ui.text(g, font, name, chipX + 2, chipY + 2, Ui.TEXT, TINY);
            cross(g, chipX + w - 8, chipY - 1, mx, my, hits, () -> change(settings.withNever(type, false), AdminMobsEditPacket.Action.NEVER, "", type, 0, 0, 0));
            chipX += w + 3;
        }
        smallKey(g, right - 34, ny + 21, 34, Tr.t("mobs.add"), mx, my, hits, this::addNever);
        return tip;
    }

    private void drawDimMenu(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        List<String> dims = data == null ? List.of() : data.dims();
        int lines = menuRows();
        int mxp = x + LEFT_X + 30, myp = y + TOP + 30, w = 170, h = Math.max(1, Math.min(dims.size(), lines)) * 10 + 4;
        menuScroll = Math.max(0, Math.min(menuScroll, Math.max(0, dims.size() - lines))); // the wheel moves a longer list (audit)
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(mxp - 1, myp - 1, mxp + w + 1, myp + h + 1, Ui.VIOLET);
        g.fill(mxp, myp, mxp + w, myp + h, Ui.PANEL);
        if (dims.isEmpty()) {
            Ui.text(g, font, Tr.t("mobs.waiting"), mxp + 4, myp + 4, Ui.MUTED, TINY);
        }
        for (int i = 0; i < Math.min(lines, dims.size() - menuScroll); i++) {
            String d = dims.get(menuScroll + i);
            int ry = myp + 2 + i * 10;
            MobSettings.DimLimit set = settings.dims().stream().filter(s -> s.dim().equals(d)).findFirst().orElse(null);
            boolean hover = set == null && in(mx, my, mxp, ry, w, 10);
            if (hover) {
                g.fill(mxp + 1, ry, mxp + w - 1, ry + 10, ROW_SEL);
            }
            Ui.text(g, font, Ui.fit(font, dimName(d), 100, TINY), mxp + 4, ry + 3, set != null ? 0xFF5F6676 : Ui.TEXT, TINY);
            String side = set != null ? Tr.t("mobs.dim_set", set.limit() > 0 ? Integer.toString(set.limit()) : Tr.t("mobs.off")) : d;
            Ui.text(g, font, Ui.fit(font, side, 62, TINY), mxp + w - 4 - Math.min(62, Ui.width(font, side, TINY)), ry + 3, Ui.MUTED, TINY);
            if (set == null) {
                hits.add(new Ui.Hit(mxp, ry, w, 10, () -> {
                    dimMenu = false;
                    change(settings.withDim(d, settings.every()), AdminMobsEditPacket.Action.DIM, "", d, 0, 0, settings.every());
                }));
            }
        }
        if (dims.size() > lines) {
            String more = Tr.t("mobs.menu_more", menuScroll + 1, Math.min(dims.size(), menuScroll + lines), dims.size());
            Ui.text(g, font, more, mxp + w - Ui.width(font, more, TINY) - 2, myp + h + 3, Ui.MUTED, TINY);
        }
        g.pose().popPose();
        hits.add(new Ui.Hit(x, y, host.pageW(), host.pageH(), () -> dimMenu = false)); // a click anywhere else closes it
    }

    /** The list box and its heading line: FULLEST CHUNKS on the left, cut to what the age on the right leaves. */
    private void listHead(GuiGraphics g, int px, int py, int rw) {
        Font font = host.font();
        Ui.inset(g, px, py, rw, boxH());
        String ago = data == null ? Tr.t("mobs.waiting") : Tr.t("mobs.refreshed", Math.max(0, (System.currentTimeMillis() - receivedAt) / 1000));
        ago = Ui.fit(font, ago, rw - 10, TINY);
        int agoW = Ui.width(font, ago, TINY);
        Ui.text(g, font, Component.literal(Ui.fitBold(font, Tr.t("mobs.fullest"), rw - 14 - agoW, TINY)).withStyle(ChatFormatting.BOLD), px + 5, py + 5, Ui.VIOLET, TINY);
        Ui.text(g, font, ago, px + rw - 5 - agoW, py + 5, Ui.MUTED, TINY);
    }

    private String drawList(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP, rw = rightW();
        listHead(g, px, py, rw);
        List<AdminMobsPacket.Row> rows = data == null ? List.of() : data.rows();
        if (data != null && rows.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("mobs.nothing"), rw - 10, TINY), px + 5, py + 18, Ui.MUTED, TINY);
        }
        String tip = null;
        int top = py + 15;
        // the wheel over the list shows the rest of the fullest chunks when the page is too short for them all (review of SU-154)
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - listRows())));
        for (int i = listScroll; i < Math.min(listScroll + listRows(), rows.size()); i++) {
            AdminMobsPacket.Row r = rows.get(i);
            int ry = top + (i - listScroll) * ROW;
            boolean stale = r.ageMs() > 30_000;
            if (i == picked) {
                g.fill(px + 2, ry - 1, px + rw - 2, ry + 9, ROW_SEL);
                g.fill(px + 2, ry - 1, px + 3, ry + 9, Ui.VIOLET);
            }
            int sw = Math.min(38, Ui.width(font, r.server(), TINY) + 4);
            g.fill(px + 5, ry, px + 5 + sw, ry + 8, ROW_SEL);
            Ui.text(g, font, Ui.fit(font, r.server(), sw - 4, TINY), px + 7, ry + 2, stale ? 0xFF5F6676 : Ui.MUTED, TINY);
            String n = r.limit() > 0 ? r.count() + " / " + r.limit() : Integer.toString(r.count());
            int nx = px + rw - 36 - Ui.widthBold(font, n, TINY);
            String where = place(r);
            // the place takes the width the page has beyond the old 170, never running into the number
            int wx = px + 5 + sw + 4, ww = Math.max(0, Math.min(62 + rw - 170, nx - 3 - wx));
            Ui.text(g, font, Ui.fit(font, where, ww, TINY), wx, ry + 2, stale ? 0xFF5F6676 : Ui.MUTED, TINY);
            if (in(mx, my, wx, ry, ww, 9)) {
                tip = Tr.t("mobs.chunk_tip", dimName(r.dim()), r.server(), r.x() * 16, r.z() * 16, r.x() * 16 + 15, r.z() * 16 + 15)
                        + (stale ? " " + Tr.t("mobs.stale", r.ageMs() / 1000) : "");
            }
            int colour = stale ? 0xFF5F6676 : r.limit() > 0 && r.count() > r.limit() ? RED : r.limit() > 0 && r.count() == r.limit() ? AMBER : Ui.TEXT;
            Ui.text(g, font, Component.literal(n).withStyle(ChatFormatting.BOLD), nx, ry + 2, colour, TINY);
            int bx = px + rw - 30;
            g.fill(bx, ry + 2, bx + 24, ry + 6, Ui.DIM);
            if (r.limit() > 0) {
                int fill = (int) Math.min(24, Math.round(24.0 * r.count() / r.limit()));
                g.fill(bx, ry + 2, bx + fill, ry + 6, stale ? 0xFF3A3F4A : r.count() > r.limit() ? RED : r.count() == r.limit() ? AMBER : Ui.TEAL);
            }
            int which = i;
            hits.add(new Ui.Hit(px + 2, ry - 1, rw - 4, 10, () -> {
                picked = which;
                sureKey = null;
            }));
        }
        if (picked >= 0 && picked < rows.size()) {
            drawPicked(g, px, py, rw, rows.get(picked), mx, my, hits);
        }
        return tip;
    }

    private void drawPicked(GuiGraphics g, int px, int py, int rw, AdminMobsPacket.Row r, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int cx = px + 4, cy = py + boxH() - PICKED_H - 4, cw = rw - 8, ch = PICKED_H;
        g.fill(cx, cy, cx + cw, cy + ch, Ui.SLOT);
        Ui.frame(g, cx, cy, cw, ch, Ui.DIM);
        boolean here = data != null && r.server().equals(data.here());
        String title = Tr.t("mobs.picked_title", place(r), r.server(), here ? Tr.t("mobs.this_server") : "");
        Ui.text(g, font, Component.literal(Ui.fitBold(font, title, cw - 8, TINY)).withStyle(ChatFormatting.BOLD), cx + 4, cy + 4, Ui.TEXT, TINY);
        StringBuilder what = new StringBuilder();
        for (int i = 0; i < Math.min(4, r.types().size()); i++) {
            MobPlan.TypeCount t = r.types().get(i);
            what.append(i == 0 ? "" : ", ").append(t.count()).append(' ').append(mobName(t.type()).toLowerCase(Locale.ROOT));
            if (t.kept() > 0) {
                what.append(' ').append(Tr.t("mobs.kept", t.kept()));
            }
        }
        String limitText = r.limit() > 0 ? Tr.t("mobs.picked_limit", r.count(), r.limit(), from(r.from())) : Tr.t("mobs.picked_nolimit", r.count());
        List<String> lines = Ui.wrap(font, limitText + " · " + what, cw - 8, TINY);
        int ty = cy + 12;
        for (String line : lines.subList(0, Math.min(3, lines.size()))) {
            Ui.text(g, font, line, cx + 4, ty, Ui.MUTED, TINY);
            ty += 6;
        }
        int ky = cy + ch - 14;
        String k = key(r);
        int kx = cx + 4;
        List<MobPlan.TypeCount> plan = r.limit() > 0 ? MobPlan.plan(r.types(), r.count(), r.limit()) : List.of();
        int n = MobPlan.total(plan);
        if (here && n > 0) {
            boolean sure = k.equals(sureKey) && System.currentTimeMillis() < sureUntil;
            String label = sure ? Tr.t("mobs.sure") : Tr.t("mobs.remove", n);
            int w = Ui.widthBold(font, label, TINY) + 10;
            g.fill(kx, ky, kx + w, ky + 10, WARN);
            Ui.frame(g, kx, ky, w, 10, 0xFFD63B2F);
            Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), kx + 5, ky + 3, 0xFFFF8F86, TINY);
            hits.add(new Ui.Hit(kx, ky, w, 10, () -> {
                if (k.equals(sureKey) && System.currentTimeMillis() < sureUntil) {
                    sureKey = null;
                    ServerUtilsNetwork.CHANNEL.sendToServer(AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.REMOVE, r.server(), r.dim(), r.x(), r.z(), r.count()));
                } else {
                    sureKey = k;
                    sureUntil = System.currentTimeMillis() + SURE_MS;
                }
            }));
            kx += w + 4;
        }
        boolean listed = settings.chunks().stream().anyMatch(c -> c.is(r.server(), r.dim(), r.x(), r.z()));
        if (!listed) {
            String label = Tr.t("mobs.limit");
            int w = Ui.widthBold(font, label, TINY) + 10;
            smallKey(g, kx, ky, w, label, mx, my, hits, () -> {
                flashKey = k;
                flashUntil = System.currentTimeMillis() + FLASH_MS;
                ServerUtilsNetwork.CHANNEL.sendToServer(AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.LIMIT, r.server(), r.dim(), r.x(), r.z(), r.count()));
            });
            kx += w + 4;
        }
        String side;
        if (k.equals(sureKey) && System.currentTimeMillis() < sureUntil) {
            side = Tr.t("mobs.sure_text", MobPlan.describeWords(plan, MobsTab::mobName), r.count() - n);
        } else if (!here && n > 0) {
            side = Tr.t("mobs.remove_there", r.server());
        } else if (listed) {
            side = Tr.t("mobs.own_limit", r.limit());
        } else {
            side = "";
        }
        List<String> sl = Ui.wrap(font, side, cx + cw - 4 - kx, TINY);
        int sideLines = Math.min(3, sl.size()), first = sideLines == 1 ? 3 : sideLines == 2 ? -1 : -4; // three lines on a narrow page (audit of SU-154)
        for (int i = 0; i < sideLines; i++) {
            Ui.text(g, font, sl.get(i), kx, ky + first + i * 6, k.equals(sureKey) ? RED : Ui.MUTED, TINY);
        }
    }

    // ------------------------------------------------------------------ the DROPS & ARROWS page (D487 to D501)

    private static String dropKey(AdminMobsPacket.DropRow r) {
        return "drop|" + r.server() + "|" + r.dim() + "|" + r.x() + "|" + r.z();
    }

    private static String dropPlace(AdminMobsPacket.DropRow r) {
        return !r.owner().isEmpty() ? Tr.t("mobs.room", r.owner()) : Tr.t("mobs.place", dimName(r.dim()).toLowerCase(Locale.ROOT), r.x(), r.z());
    }

    /** A number with − and + by {@code step} (shift: {@code bigStep}) between {@code min} and {@code max}, OFF at 0 when {@code min} is 0. */
    private void rangeStepper(GuiGraphics g, int x, int y, int value, int min, int max, int step, int bigStep, int mx, int my, List<Ui.Hit> hits, java.util.function.IntConsumer to) {
        Font font = host.font();
        int by = Screen.hasShiftDown() ? bigStep : step;
        tinyKey(g, x, y, "−", value > min, mx, my, hits, () -> to.accept(Math.max(min, value - by)));
        g.fill(x + 11, y, x + 35, y + 10, Ui.SLOT);
        Ui.frame(g, x + 11, y, 24, 10, Ui.DIM);
        String v = value > 0 ? Integer.toString(value) : Tr.t("mobs.off");
        Ui.text(g, font, Component.literal(v).withStyle(ChatFormatting.BOLD), x + 11 + (24 - Ui.widthBold(font, v, TINY)) / 2, y + 3, value > 0 ? Ui.TEXT : Ui.MUTED, TINY);
        tinyKey(g, x + 35, y, "+", value < max, mx, my, hits, () -> to.accept(Math.min(max, value + by)));
    }

    private String drawDropNumbers(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LEFT_X, ly = y + TOP, ix = lx + 6, iw = LEFT_W - 12, right = ix + iw;
        Ui.inset(g, lx, ly, LEFT_W, boxH());
        MobSettings.Drops d = settings.drops();
        label(g, ix, ly + 7, Tr.t("crowd.items"));
        rangeStepper(g, right - 46, ly + 4, d.items(), 0, MobSettings.Drops.MAX, 8, 64, mx, my, hits,
                n -> change(settings.withDrops(new MobSettings.Drops(n, d.arrows(), d.minutes())), AdminMobsEditPacket.Action.ITEMS, "", "", 0, 0, n));
        label(g, ix, ly + 22, Tr.t("crowd.arrows"));
        rangeStepper(g, right - 46, ly + 19, d.arrows(), 0, MobSettings.Drops.MAX, 8, 64, mx, my, hits,
                n -> change(settings.withDrops(new MobSettings.Drops(d.items(), n, d.minutes())), AdminMobsEditPacket.Action.ARROWS, "", "", 0, 0, n));
        label(g, ix, ly + 37, Tr.t("crowd.over"));
        Ui.text(g, font, Tr.t("crowd.min"), right - 60, ly + 37, Ui.MUTED, TINY);
        rangeStepper(g, right - 46, ly + 34, d.minutes(), MobSettings.Drops.MIN_MINUTES, MobSettings.Drops.MAX_MINUTES, 1, 5, mx, my, hits,
                n -> change(settings.withDrops(new MobSettings.Drops(d.items(), d.arrows(), n)), AdminMobsEditPacket.Action.MINUTES, "", "", 0, 0, n));
        int ty = ly + 52;
        for (String line : Ui.wrap(font, Tr.t("crowd.what"), iw, TINY)) {
            Ui.text(g, font, line, ix, ty, Ui.MUTED, TINY);
            ty += 6;
        }
        ty += 4;
        String hour = data == null || data.clears() == 0 ? Tr.t("crowd.hour_none") : Tr.t("crowd.hour", data.clears(), data.clearedItems(), data.clearedArrows());
        for (String line : Ui.wrap(font, hour, iw, TINY)) {
            Ui.text(g, font, line, ix, ty, Ui.TEXT, TINY);
            ty += 6;
        }
        return null;
    }

    /** How long a row has been over, counting on since its server reported it (another server's a little earlier, audit). */
    private long overMs(AdminMobsPacket.DropRow r) {
        long o = Math.max(r.itemsOverMs(), r.arrowsOverMs());
        return o == 0 ? 0 : o + r.ageMs() + (System.currentTimeMillis() - receivedAt);
    }

    private String drawDropList(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP, rw = rightW();
        listHead(g, px, py, rw);
        List<AdminMobsPacket.DropRow> rows = data == null ? List.of() : data.drops();
        if (data != null && rows.isEmpty()) {
            List<String> none = Ui.wrap(font, Tr.t("crowd.nothing"), rw - 10, TINY);
            for (int i = 0; i < none.size(); i++) {
                Ui.text(g, font, none.get(i), px + 5, py + 18 + i * 6, Ui.MUTED, TINY);
            }
        }
        MobSettings.Drops d = settings.drops();
        long wait = d.minutes() * 60_000L;
        String tip = null;
        int top = py + 15;
        // the wheel over the list shows the rest of the fullest chunks when the page is too short for them all (review of SU-154)
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - listRows())));
        for (int i = listScroll; i < Math.min(listScroll + listRows(), rows.size()); i++) {
            AdminMobsPacket.DropRow r = rows.get(i);
            int ry = top + (i - listScroll) * ROW;
            boolean stale = r.ageMs() > 30_000;
            if (i == dropPicked) {
                g.fill(px + 2, ry - 1, px + rw - 2, ry + 9, ROW_SEL);
                g.fill(px + 2, ry - 1, px + 3, ry + 9, Ui.VIOLET);
            }
            int dim = stale ? 0xFF5F6676 : Ui.MUTED;
            int sw = Math.min(38, Ui.width(font, r.server(), TINY) + 4);
            g.fill(px + 5, ry, px + 5 + sw, ry + 8, ROW_SEL);
            Ui.text(g, font, Ui.fit(font, r.server(), sw - 4, TINY), px + 7, ry + 2, dim, TINY);
            boolean itemsMore = (d.items() > 0 ? r.items() / (double) d.items() : 0) >= (d.arrows() > 0 ? r.arrows() / (double) d.arrows() : 0);
            String n = itemsMore ? Tr.t("crowd.items_short", r.items()) : Tr.t("crowd.arrows_short", r.arrows());
            int nx = px + rw - 44 - Ui.widthBold(font, n, TINY);
            // the place takes the width the page has beyond the old 170, never running into the number
            int wx = px + 5 + sw + 4, ww = Math.max(0, Math.min(56 + rw - 170, nx - 3 - wx));
            Ui.text(g, font, Ui.fit(font, dropPlace(r), ww, TINY), wx, ry + 2, dim, TINY);
            if (in(mx, my, wx, ry, ww, 9)) {
                tip = Tr.t("mobs.chunk_tip", dimName(r.dim()), r.server(), r.x() * 16, r.z() * 16, r.x() * 16 + 15, r.z() * 16 + 15)
                        + (stale ? " " + Tr.t("mobs.stale", r.ageMs() / 1000) : "");
            }
            boolean over = itemsMore ? d.items() > 0 && r.items() > d.items() : d.arrows() > 0 && r.arrows() > d.arrows();
            Ui.text(g, font, Component.literal(n).withStyle(ChatFormatting.BOLD), nx, ry + 2, stale ? 0xFF5F6676 : over ? RED : Ui.TEXT, TINY);
            long o = overMs(r);
            int bx = px + rw - 40;
            if (o > 0) {
                g.fill(bx, ry + 2, bx + 16, ry + 6, Ui.DIM);
                g.fill(bx, ry + 2, bx + (int) Math.min(16, 16 * o / Math.max(1, wait)), ry + 6, stale ? 0xFF3A3F4A : AMBER);
                String t = (o / 60_000) + ":" + String.format(Locale.ROOT, "%02d", (o / 1000) % 60);
                Ui.text(g, font, t, bx + 19, ry + 2, stale ? 0xFF5F6676 : AMBER, TINY);
            } else {
                String u = itemsMore && d.items() == 0 ? Tr.t("crowd.items_off") : !itemsMore && d.arrows() == 0 ? Tr.t("crowd.arrows_off") : Tr.t("crowd.under");
                Ui.text(g, font, Ui.fit(font, u, 36, TINY), bx, ry + 2, Ui.MUTED, TINY);
            }
            int which = i;
            hits.add(new Ui.Hit(px + 2, ry - 1, rw - 4, 10, () -> {
                dropPicked = which;
                sureKey = null;
            }));
        }
        if (dropPicked >= 0 && dropPicked < rows.size()) {
            drawDropPicked(g, px, py, rw, rows.get(dropPicked), mx, my, hits);
        }
        return tip;
    }

    private void drawDropPicked(GuiGraphics g, int px, int py, int rw, AdminMobsPacket.DropRow r, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int cx = px + 4, cy = py + boxH() - PICKED_H - 4, cw = rw - 8, ch = PICKED_H;
        g.fill(cx, cy, cx + cw, cy + ch, Ui.SLOT);
        Ui.frame(g, cx, cy, cw, ch, Ui.DIM);
        boolean here = data != null && r.server().equals(data.here());
        MobSettings.Drops d = settings.drops();
        String title = Tr.t("mobs.picked_title", dropPlace(r), r.server(), here ? Tr.t("mobs.this_server") : "");
        Ui.text(g, font, Component.literal(Ui.fitBold(font, title, cw - 8, TINY)).withStyle(ChatFormatting.BOLD), cx + 4, cy + 4, Ui.TEXT, TINY);
        String limits = Tr.t("crowd.picked", r.items(), d.items() > 0 ? Integer.toString(d.items()) : Tr.t("mobs.off"), r.arrows(), d.arrows() > 0 ? Integer.toString(d.arrows()) : Tr.t("mobs.off"));
        long o = overMs(r);
        if (o > 0) {
            limits += " · " + Tr.t("crowd.picked_over", (o / 60_000) + ":" + String.format(Locale.ROOT, "%02d", (o / 1000) % 60), d.minutes() + ":00");
        }
        if (r.kept() > 0) {
            limits += " · " + Tr.t("crowd.kept", r.kept());
        }
        List<String> lines = Ui.wrap(font, limits, cw - 8, TINY);
        int ty = cy + 12;
        for (String line : lines.subList(0, Math.min(3, lines.size()))) {
            Ui.text(g, font, line, cx + 4, ty, Ui.MUTED, TINY);
            ty += 6;
        }
        int items = d.items() > 0 && r.items() > d.items() ? Math.max(0, Math.min(r.items() - d.items(), r.items() - r.kept())) : 0;
        int arrows = d.arrows() > 0 && r.arrows() > d.arrows() ? r.arrows() - d.arrows() : 0;
        int ky = cy + ch - 14, kx = cx + 4;
        String k = dropKey(r);
        boolean sure = k.equals(sureKey) && System.currentTimeMillis() < sureUntil;
        String side = "";
        if (here && items + arrows > 0) {
            String label = sure ? Tr.t("mobs.sure") : Tr.t("crowd.clear");
            int w = Ui.widthBold(font, label, TINY) + 10;
            g.fill(kx, ky, kx + w, ky + 10, WARN);
            Ui.frame(g, kx, ky, w, 10, 0xFFD63B2F);
            Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), kx + 5, ky + 3, 0xFFFF8F86, TINY);
            hits.add(new Ui.Hit(kx, ky, w, 10, () -> {
                if (k.equals(sureKey) && System.currentTimeMillis() < sureUntil) {
                    sureKey = null;
                    ServerUtilsNetwork.CHANNEL.sendToServer(AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.CLEAR, r.server(), r.dim(), r.x(), r.z(), 0));
                } else {
                    sureKey = k;
                    sureUntil = System.currentTimeMillis() + SURE_MS;
                }
            }));
            kx += w + 4;
            String what = items > 0 && arrows > 0 ? Tr.t("crowd.what_both", items, arrows) : items > 0 ? Tr.t("crowd.what_items", items) : Tr.t("crowd.what_arrows", arrows);
            side = sure ? Tr.t("crowd.sure_text", what, r.kept()) : Tr.t("crowd.clear_text", what);
        } else if (!here && items + arrows > 0) {
            side = Tr.t("crowd.clear_there", r.server());
        }
        List<String> sl = Ui.wrap(font, side, cx + cw - 4 - kx, TINY);
        int sideLines = Math.min(3, sl.size()), first = sideLines == 1 ? 3 : sideLines == 2 ? -1 : -4;
        for (int i = 0; i < sideLines; i++) {
            Ui.text(g, font, sl.get(i), kx, ky + first + i * 6, sure ? RED : Ui.MUTED, TINY);
        }
    }

    boolean scroll(double mx, double my, double delta) {
        int x = host.left(), y = host.top();
        int d = -(int) Math.signum(delta);
        if (dimMenu) {
            menuScroll += d;
        } else if (mx >= x + RIGHT_X) {
            listScroll += d;
        } else if (dropsPage) {
            return true;
        } else if (in((int) mx, (int) my, x + LEFT_X, y + dimsY(), LEFT_W, sectionH(dimRows()))) {
            dimScroll += d;
        } else if (in((int) mx, (int) my, x + LEFT_X, y + chunksY(), LEFT_W, sectionH(chunkRows()))) {
            chunkScroll += d;
        } else if (in((int) mx, (int) my, x + LEFT_X, y + neverY(), LEFT_W, 20)) {
            neverScroll += d;
        }
        return true;
    }
}
