package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.chunks.ChunkSettings;
import com.mrgregles.bsp_server_utilities.network.AdminChunksEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminChunksPacket;
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
import java.util.UUID;

/**
 * The CHUNKS tab of the admin panel (owner decisions D92 to D96), as on {@code tools/preview/chunks_refined.html}: on
 * the left UNLOADING (the switch, AFTER with − and +), the mods never unloaded as chips ({@code bsp_core} always) and
 * the players always loaded (+ add a player); on the right every player whose machines hold chunks on this server, with
 * where they are and how many chunks are loaded (green) and unloaded (red), and below them machines with no owner found
 * (UNLOAD / RESTORE). A picked player shows their machines with RESTORE NOW and UNLOAD NOW (SURE?). Changes go to the
 * server at once; the lists are asked for again every few seconds while the tab is open. The settings keep their 150 wide
 * box at their content's height (pulled together a little on the shortest pages); the lists take the rest of the page,
 * more rows as it gets taller and the extra width to the names (D756, D757).
 */
final class ChunksTab {
    /** LEFT_H: the settings' content height; RIGHT_W: the list's width on the old 344 wide page, the names' base. */
    private static final int LEFT_X = 8, TOP = 22, LEFT_W = 150, LEFT_H = 158, RIGHT_X = 166, RIGHT_W = 170, ROW = 10;
    /** ADD_Y: the add-a-player line, the last settings line, pulled up in full on a short page. */
    private static final int REFRESH_TICKS = 100, ADD_Y = 137, ORPHAN_ROWS = 5, PLAYER_ROWS = 8;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int GREEN = 0xFF8FE04A, AMBER = 0xFFF2B544, RED = 0xFFFF6B5C, GREY = 0xFF6B7280;

    private final AdminScreen host;
    @Nullable
    private AdminChunksPacket data;
    private ChunkSettings settings = ChunkSettings.DEFAULTS;
    @Nullable
    private UUID picked;
    private int scroll, refreshIn;
    private long unloadSureUntil;
    private boolean adding;
    @Nullable
    private EditBox nameBox;

    ChunksTab(AdminScreen host) {
        this.host = host;
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        send(AdminChunksEditPacket.of(AdminChunksEditPacket.Action.REFRESH, ""));
    }

    private static void send(AdminChunksEditPacket p) {
        ServerUtilsNetwork.CHANNEL.sendToServer(p);
    }

    void accept(AdminChunksPacket p) {
        data = p;
        settings = p.settings();
    }

    private void set(ChunkSettings next) {
        settings = next.sanitised();
        send(new AdminChunksEditPacket(AdminChunksEditPacket.Action.SETTINGS, "", settings));
    }

    void tick() {
        if (nameBox != null) {
            nameBox.tick();
        }
        if (--refreshIn <= 0) {
            open();
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
        return base - (LEFT_H - leftH()) * base / ADD_Y;
    }

    /** What the names' column gains on a wider list, or gives up (half the loss, the rest from WHERE) on a narrower one. */
    private int grow() {
        int extra = rightW() - RIGHT_W;
        return extra >= 0 ? extra : extra / 2;
    }

    /**
     * Rows for the machines with no owner: 5 on the old page, then half of every row more or fewer the list's height
     * makes (all of them 13 rows on the old page: 8 players and 5 machines).
     */
    private int orphanRows() {
        int total = (boxH() - 30) / ROW; // the last row's key stays inside the box (audit of SU-154)
        return Math.max(1, ORPHAN_ROWS + (total - PLAYER_ROWS - ORPHAN_ROWS) / 2);
    }

    /** Rows for the players above them. */
    private int playerRows() {
        return Math.max(1, (boxH() - 28) / ROW - orphanRows());
    }

    void addWidgets(int x, int y) {
        nameBox = null;
        if (adding) {
            nameBox = new EditBox(host.font(), x + LEFT_X + 6, y + TOP + at(ADD_Y) + 1, 92, 12, Component.literal(Tr.t("chunks.player_name")));
            nameBox.setMaxLength(16);
            host.add(nameBox);
        }
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        drawSettings(g, x, y, mx, my, hits);
        if (picked != null) {
            drawPlayer(g, x, y, mx, my, hits);
        } else {
            drawEveryone(g, x, y, mx, my, hits);
        }
        Font font = host.font();
        int fy = y + host.pageH() - 8 - 14;
        String key = Tr.t(picked != null ? "chunks.players" : "chunks.refresh");
        int kw = Ui.widthBold(font, key, TINY) + 16, kx = x + host.pageW() - 8 - kw;
        Ui.text(g, font, Ui.fit(font, Tr.t("chunks.footer"), kx - x - 16, TINY), x + 8, fy + 5, Ui.MUTED, TINY);
        Ui.frame(g, kx, fy, kw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(key).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, kw, 14, picked != null ? () -> pick(null) : this::open));
    }

    private void pick(@Nullable UUID player) {
        picked = player;
        unloadSureUntil = 0;
        scroll = 0;
    }

    private void drawSettings(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LEFT_X, ly = y + TOP, ix = lx + 6, iw = LEFT_W - 12;
        Ui.inset(g, lx, ly, LEFT_W, leftH());
        Ui.text(g, font, Component.literal(Tr.t("chunks.unloading")).withStyle(ChatFormatting.BOLD), ix, ly + at(5), Ui.VIOLET, TINY);
        boolean on = settings.enabled();
        int ty = ly + at(15);
        g.fill(ix, ty + 2, ix + 14, ty + 8, on ? 0xFF3D2F5C : Ui.DIM);
        g.fill(on ? ix + 8 : ix + 1, ty + 3, on ? ix + 13 : ix + 6, ty + 7, on ? Ui.VIOLET : Ui.MUTED);
        Ui.text(g, font, Ui.fit(font, Tr.t("chunks.enabled"), iw - 20, SMALL), ix + 19, ty + 2, on ? Ui.TEXT : Ui.MUTED, SMALL);
        hits.add(new Ui.Hit(ix, ty, iw, 10, () -> set(with(!settings.enabled(), settings.minutes(), settings.exemptMods(), settings.alwaysLoaded()))));
        // AFTER − n + min away
        int sy = ly + at(29);
        Ui.text(g, font, Tr.t("chunks.after").toUpperCase(Locale.ROOT), ix, sy + 3, Ui.MUTED, TINY);
        String unit = Tr.t("chunks.min_away");
        int uw = Ui.width(font, unit, TINY), sx = ix + iw - uw - 4 - 50;
        key(g, sx, sy, "−", settings.minutes() > ChunkSettings.MIN_MINUTES, mx, my, hits, () -> set(withMinutes(settings.minutes() - 1)));
        g.fill(sx + 12, sy, sx + 38, sy + 11, Ui.SLOT);
        Ui.frame(g, sx + 12, sy, 26, 11, Ui.DIM);
        String v = Integer.toString(settings.minutes());
        Ui.text(g, font, Component.literal(v).withStyle(ChatFormatting.BOLD), sx + 12 + (26 - Ui.width(font, v, SMALL)) / 2, sy + 2, Ui.TEXT, SMALL);
        key(g, sx + 38, sy, "+", settings.minutes() < ChunkSettings.MAX_MINUTES, mx, my, hits, () -> set(withMinutes(settings.minutes() + 1)));
        Ui.text(g, font, unit, ix + iw - uw, sy + 3, Ui.MUTED, TINY);
        // mods never unloaded
        Ui.text(g, font, Tr.t("chunks.never").toUpperCase(Locale.ROOT), ix, ly + at(46), Ui.MUTED, TINY);
        int cx = ix, cy = ly + at(54), lastRow = cy + 2 * 13;
        cx = chip(g, cx, cy, ix, iw, Tr.t("chunks.bsp_core_always"), false, true, hits, null);
        List<String> mods = data == null ? List.of() : data.mods();
        for (String mod : mods) {
            if (cx + Math.min(60, Ui.width(font, mod, TINY) + 8) > ix + iw) {
                cx = ix;
                cy += 13;
            }
            if (cy > lastRow) {
                break;
            }
            boolean exempt = settings.exemptMods().contains(mod);
            cx = chip(g, cx, cy, ix, iw, mod, exempt, false, hits, () -> {
                List<String> next = new ArrayList<>(settings.exemptMods());
                if (!next.remove(mod)) {
                    next.add(mod);
                }
                set(with(settings.enabled(), settings.minutes(), next, settings.alwaysLoaded()));
            });
        }
        // always loaded
        Ui.text(g, font, Tr.t("chunks.always").toUpperCase(Locale.ROOT), ix, ly + at(100), Ui.MUTED, TINY);
        cx = ix;
        cy = ly + at(108);
        lastRow = cy + 13;
        for (ChunkSettings.Player p : settings.alwaysLoaded()) {
            String label = p.name() + " ×";
            if (cx + Math.min(60, Ui.width(font, label, TINY) + 8) > ix + iw) {
                cx = ix;
                cy += 13;
            }
            if (cy > lastRow) {
                break;
            }
            cx = chip(g, cx, cy, ix, iw, label, true, false, hits, () -> {
                List<ChunkSettings.Player> next = new ArrayList<>(settings.alwaysLoaded());
                next.removeIf(o -> o.id().equals(p.id()));
                set(with(settings.enabled(), settings.minutes(), settings.exemptMods(), next));
            });
        }
        int ay = ly + at(ADD_Y);
        if (!adding) {
            String add = Tr.t("chunks.add_player");
            Ui.text(g, font, Component.literal(add).withStyle(ChatFormatting.BOLD), ix, ay + 3, Ui.VIOLET, TINY);
            hits.add(new Ui.Hit(ix, ay, Ui.widthBold(font, add, TINY) + 4, 10, () -> {
                adding = true;
                host.rebuildTab();
            }));
        } else {
            int kx = ix + 96;
            g.fill(kx, ay + 1, kx + 40, ay + 13, Ui.VIOLET);
            String a = Tr.t("chunks.add");
            Ui.text(g, font, Component.literal(a).withStyle(ChatFormatting.BOLD), kx + (40 - Ui.widthBold(font, a, TINY)) / 2, ay + 5, Ui.INK, TINY);
            hits.add(new Ui.Hit(kx, ay + 1, 40, 12, () -> {
                if (nameBox != null && !nameBox.getValue().isBlank()) {
                    send(AdminChunksEditPacket.of(AdminChunksEditPacket.Action.ADD_ALWAYS, nameBox.getValue().strip()));
                }
                adding = false;
                host.rebuildTab();
            }));
        }
    }

    private ChunkSettings with(boolean enabled, int minutes, List<String> mods, List<ChunkSettings.Player> always) {
        return new ChunkSettings(enabled, minutes, mods, always, settings.revision());
    }

    private ChunkSettings withMinutes(int minutes) {
        return with(settings.enabled(), minutes, settings.exemptMods(), settings.alwaysLoaded());
    }

    /** A chip; returns where the next one starts. */
    private int chip(GuiGraphics g, int x, int y, int left, int w, String label, boolean on, boolean locked, List<Ui.Hit> hits, @Nullable Runnable action) {
        Font font = host.font();
        String s = Ui.fit(font, label, 56, TINY);
        int cw = Ui.width(font, s, TINY) + 8;
        g.fill(x, y, x + cw, y + 11, Ui.SLOT);
        Ui.frame(g, x, y, cw, 11, on ? Ui.VIOLET : Ui.DIM);
        Ui.text(g, font, s, x + 4, y + 3, locked ? GREY : on ? Ui.VIOLET : Ui.TEXT, TINY);
        if (action != null) {
            hits.add(new Ui.Hit(x, y, cw, 11, action));
        }
        return x + cw + 3;
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

    private String where(AdminChunksPacket.PlayerRow p) {
        if (settings.alwaysLoaded(p.id())) {
            return Tr.t("chunks.always_loaded");
        }
        if (p.online()) {
            return p.awayMs() > 0 ? Tr.t("chunks.afk", StaffFace.ago(p.awayMs())) : Tr.t("chunks.here");
        }
        return Tr.t("chunks.away", StaffFace.ago(p.awayMs()));
    }

    private int whereColour(AdminChunksPacket.PlayerRow p) {
        if (settings.alwaysLoaded(p.id())) {
            return Ui.GOLD;
        }
        if (p.online() && p.awayMs() == 0) {
            return GREEN;
        }
        return p.awayMs() < settings.awayMs() ? AMBER : GREY;
    }

    private void drawEveryone(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP, rw = rightW(), wx = 58 + grow();
        Ui.inset(g, px, py, rw, boxH());
        Ui.text(g, font, Ui.fit(font, Tr.t("chunks.col_player").toUpperCase(Locale.ROOT), wx - 9, TINY), px + 5, py + 4, Ui.MUTED, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("chunks.col_where").toUpperCase(Locale.ROOT), rw - 36 - wx, TINY), px + wx, py + 4, Ui.MUTED, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("chunks.col_loaded"), 16, TINY), px + rw - 34, py + 4, GREEN, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("chunks.col_unloaded"), 14, TINY), px + rw - 16, py + 4, RED, TINY);
        List<AdminChunksPacket.PlayerRow> players = data == null ? List.of() : data.players();
        if (players.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t(data == null ? "chunks.waiting" : "chunks.none"), rw - 10, TINY), px + 5, py + 16, Ui.MUTED, TINY);
        }
        int top = py + 13, visible = playerRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, players.size() - visible)));
        for (int i = 0; i < visible && scroll + i < players.size(); i++) {
            AdminChunksPacket.PlayerRow p = players.get(scroll + i);
            int ry = top + i * ROW;
            if (mx >= px && mx < px + rw && my >= ry && my < ry + ROW) {
                g.fill(px + 1, ry, px + rw - 1, ry + ROW, 0x22B58CFF);
            }
            Ui.text(g, font, Component.literal(Ui.fitBold(font, p.name(), wx - 8, SMALL)).withStyle(ChatFormatting.BOLD), px + 5, ry + 1, Ui.TEXT, SMALL);
            Ui.text(g, font, Ui.fit(font, where(p), rw - wx - 40, TINY), px + wx, ry + 2, whereColour(p), TINY);
            Ui.text(g, font, Ui.fit(font, p.loaded() > 0 ? Integer.toString(p.loaded()) : "–", 16, TINY), px + rw - 32, ry + 2, GREEN, TINY);
            Ui.text(g, font, Ui.fit(font, p.unloaded() > 0 ? Integer.toString(p.unloaded()) : "–", 12, TINY), px + rw - 14, ry + 2, RED, TINY);
            UUID id = p.id();
            hits.add(new Ui.Hit(px, ry, rw, ROW, () -> pick(id)));
        }
        // machines with no owner found
        List<AdminChunksPacket.MachineRow> orphans = machinesOf(AdminChunksPacket.NOBODY);
        int oy = top + visible * ROW + 5;
        g.fill(px + 1, oy - 2, px + rw - 1, oy - 1, Ui.ROW_LINE);
        String none = Ui.fitBold(font, Tr.t("chunks.no_owner", orphans.size()), rw - 10, TINY);
        Ui.text(g, font, Component.literal(none).withStyle(ChatFormatting.BOLD), px + 5, oy + 2, Ui.GOLD, TINY);
        for (int i = 0; i < Math.min(orphanRows(), orphans.size()); i++) {
            AdminChunksPacket.MachineRow m = orphans.get(i);
            int ry = oy + 12 + i * ROW;
            boolean unloaded = m.unloaded() > 0;
            Ui.text(g, font, Ui.fit(font, m.modId() + " · " + m.where(), rw - 52, TINY), px + 5, ry + 2, unloaded ? RED : Ui.MUTED, TINY);
            String label = Tr.t(unloaded ? "chunks.restore" : "chunks.unload");
            int kw = 40, kx = px + rw - 4 - kw;
            g.fill(kx, ry, kx + kw, ry + 9, Ui.DIM);
            Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), kx + (kw - Ui.widthBold(font, label, TINY)) / 2, ry + 2, Ui.TEXT, TINY);
            String key = m.key();
            hits.add(new Ui.Hit(kx, ry, kw, 9, () -> send(AdminChunksEditPacket.of(unloaded ? AdminChunksEditPacket.Action.RESTORE_MACHINE : AdminChunksEditPacket.Action.UNLOAD_MACHINE, key))));
        }
    }

    private List<AdminChunksPacket.MachineRow> machinesOf(UUID player) {
        List<AdminChunksPacket.MachineRow> out = new ArrayList<>();
        if (data != null) {
            for (AdminChunksPacket.MachineRow m : data.machines()) {
                if (m.player().equals(player)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private void drawPlayer(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP, rw = rightW(), bh = boxH(), grow = grow();
        Ui.inset(g, px, py, rw, bh);
        AdminChunksPacket.PlayerRow p = data == null ? null : data.players().stream().filter(r -> r.id().equals(picked)).findFirst().orElse(null);
        if (p == null) {
            Ui.text(g, font, Ui.fit(font, Tr.t("chunks.waiting"), rw - 10, TINY), px + 5, py + 6, Ui.MUTED, TINY);
            return;
        }
        Ui.text(g, font, Component.literal(Ui.fitBold(font, p.name(), 70 + grow, 1f)).withStyle(ChatFormatting.BOLD), px + 5, py + 4, Ui.TEXT, 1f);
        String w = Ui.fit(font, where(p), rw - 84 - grow, TINY);
        Ui.text(g, font, w, px + rw - 5 - Ui.width(font, w, TINY), py + 6, whereColour(p), TINY);
        List<AdminChunksPacket.MachineRow> machines = machinesOf(p.id());
        int top = py + 18, visible = Math.max(1, (bh - 58) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, machines.size() - visible)));
        for (int i = 0; i < visible && scroll + i < machines.size(); i++) {
            AdminChunksPacket.MachineRow m = machines.get(scroll + i);
            int ry = top + i * ROW;
            Ui.text(g, font, Component.literal(Ui.fitBold(font, m.modId(), 46, TINY)).withStyle(ChatFormatting.BOLD), px + 5, ry + 2, Ui.TEXT, TINY);
            Ui.text(g, font, Ui.fit(font, m.where(), rw - 54 - 30, TINY), px + 54, ry + 2, Ui.MUTED, TINY);
            String n = m.unloaded() > 0 ? Integer.toString(m.unloaded()) : Integer.toString(m.loaded());
            Ui.text(g, font, Ui.fit(font, n, 11, TINY), px + rw - 12, ry + 2, m.unloaded() > 0 ? RED : GREEN, TINY);
        }
        // RESTORE NOW, UNLOAD NOW (SURE?)
        int ky = py + bh - 17;
        String r = Tr.t("chunks.restore_now");
        int restoreW = Ui.widthBold(font, r, TINY) + 12;
        g.fill(px + 5, ky, px + 5 + restoreW, ky + 13, Ui.DIM);
        Ui.text(g, font, Component.literal(r).withStyle(ChatFormatting.BOLD), px + 11, ky + 4, Ui.TEXT, TINY);
        UUID id = p.id();
        hits.add(new Ui.Hit(px + 5, ky, restoreW, 13, () -> send(AdminChunksEditPacket.of(AdminChunksEditPacket.Action.RESTORE_PLAYER, id.toString()))));
        boolean sure = System.currentTimeMillis() < unloadSureUntil;
        String u = Tr.t(sure ? "rules.sure" : "chunks.unload_now");
        int uw = Math.max(Ui.widthBold(font, Tr.t("chunks.unload_now"), TINY), Ui.widthBold(font, u, TINY)) + 12, ux = px + 9 + restoreW;
        g.fill(ux, ky, ux + uw, ky + 13, sure ? 0xFFD63B2F : 0xFF3A2A2A);
        Ui.frame(g, ux, ky, uw, 13, 0xFFD63B2F);
        Ui.text(g, font, Component.literal(u).withStyle(ChatFormatting.BOLD), ux + (uw - Ui.widthBold(font, u, TINY)) / 2, ky + 4, sure ? 0xFFFFFFFF : 0xFFFF8F86, TINY);
        hits.add(new Ui.Hit(ux, ky, uw, 13, () -> {
            long now = System.currentTimeMillis();
            if (now >= unloadSureUntil) {
                unloadSureUntil = now + 3000;
                return;
            }
            unloadSureUntil = 0;
            send(AdminChunksEditPacket.of(AdminChunksEditPacket.Action.UNLOAD_PLAYER, id.toString()));
        }));
    }

    boolean scroll(double delta) {
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
