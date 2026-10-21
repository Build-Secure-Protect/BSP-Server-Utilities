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
 * server at once; the lists are asked for again every few seconds while the tab is open.
 */
final class ChunksTab {
    private static final int LEFT_X = 8, TOP = 22, LEFT_W = 150, BOX_H = 158, RIGHT_X = 166, RIGHT_W = 170, ROW = 10;
    private static final int REFRESH_TICKS = 100;
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

    void addWidgets(int x, int y) {
        nameBox = null;
        if (adding) {
            nameBox = new EditBox(host.font(), x + LEFT_X + 6, y + TOP + 138, 92, 12, Component.literal(Tr.t("chunks.player_name")));
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
        int fy = y + AdminScreen.H - 8 - 14;
        String key = Tr.t(picked != null ? "chunks.players" : "chunks.refresh");
        int kw = Ui.width(font, key, TINY) + 16, kx = x + AdminScreen.W - 8 - kw;
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
        Ui.inset(g, lx, ly, LEFT_W, BOX_H);
        Ui.text(g, font, Component.literal(Tr.t("chunks.unloading")).withStyle(ChatFormatting.BOLD), ix, ly + 5, Ui.VIOLET, TINY);
        boolean on = settings.enabled();
        g.fill(ix, ly + 17, ix + 14, ly + 23, on ? 0xFF3D2F5C : Ui.DIM);
        g.fill(on ? ix + 8 : ix + 1, ly + 18, on ? ix + 13 : ix + 6, ly + 22, on ? Ui.VIOLET : Ui.MUTED);
        Ui.text(g, font, Ui.fit(font, Tr.t("chunks.enabled"), iw - 20, SMALL), ix + 19, ly + 17, on ? Ui.TEXT : Ui.MUTED, SMALL);
        hits.add(new Ui.Hit(ix, ly + 15, iw, 10, () -> set(with(!settings.enabled(), settings.minutes(), settings.exemptMods(), settings.alwaysLoaded()))));
        // AFTER − n + min away
        int sy = ly + 29;
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
        Ui.text(g, font, Tr.t("chunks.never").toUpperCase(Locale.ROOT), ix, ly + 46, Ui.MUTED, TINY);
        int cx = ix, cy = ly + 54;
        cx = chip(g, cx, cy, ix, iw, Tr.t("chunks.bsp_core_always"), false, true, hits, null);
        List<String> mods = data == null ? List.of() : data.mods();
        for (String mod : mods) {
            if (cx + Math.min(60, Ui.width(font, mod, TINY) + 8) > ix + iw) {
                cx = ix;
                cy += 13;
            }
            if (cy > ly + 84) {
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
        Ui.text(g, font, Tr.t("chunks.always").toUpperCase(Locale.ROOT), ix, ly + 100, Ui.MUTED, TINY);
        cx = ix;
        cy = ly + 108;
        for (ChunkSettings.Player p : settings.alwaysLoaded()) {
            String label = p.name() + " ×";
            if (cx + Math.min(60, Ui.width(font, label, TINY) + 8) > ix + iw) {
                cx = ix;
                cy += 13;
            }
            if (cy > ly + 121) {
                break;
            }
            cx = chip(g, cx, cy, ix, iw, label, true, false, hits, () -> {
                List<ChunkSettings.Player> next = new ArrayList<>(settings.alwaysLoaded());
                next.removeIf(o -> o.id().equals(p.id()));
                set(with(settings.enabled(), settings.minutes(), settings.exemptMods(), next));
            });
        }
        if (!adding) {
            String add = Tr.t("chunks.add_player");
            Ui.text(g, font, Component.literal(add).withStyle(ChatFormatting.BOLD), ix, ly + 140, Ui.VIOLET, TINY);
            hits.add(new Ui.Hit(ix, ly + 137, Ui.width(font, add, TINY) + 4, 10, () -> {
                adding = true;
                host.rebuildTab();
            }));
        } else {
            int kx = ix + 96;
            g.fill(kx, ly + 138, kx + 40, ly + 150, Ui.VIOLET);
            String a = Tr.t("chunks.add");
            Ui.text(g, font, Component.literal(a).withStyle(ChatFormatting.BOLD), kx + (40 - Ui.width(font, a, TINY)) / 2, ly + 142, Ui.INK, TINY);
            hits.add(new Ui.Hit(kx, ly + 138, 40, 12, () -> {
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
        return p.online() ? Tr.t("chunks.here") : Tr.t("chunks.away", StaffFace.ago(p.awayMs()));
    }

    private int whereColour(AdminChunksPacket.PlayerRow p) {
        if (settings.alwaysLoaded(p.id())) {
            return Ui.GOLD;
        }
        return p.online() ? GREEN : p.awayMs() < settings.awayMs() ? AMBER : GREY;
    }

    private void drawEveryone(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP;
        Ui.inset(g, px, py, RIGHT_W, BOX_H);
        Ui.text(g, font, Tr.t("chunks.col_player").toUpperCase(Locale.ROOT), px + 5, py + 4, Ui.MUTED, TINY);
        Ui.text(g, font, Tr.t("chunks.col_where").toUpperCase(Locale.ROOT), px + 58, py + 4, Ui.MUTED, TINY);
        Ui.text(g, font, Tr.t("chunks.col_loaded"), px + RIGHT_W - 34, py + 4, GREEN, TINY);
        Ui.text(g, font, Tr.t("chunks.col_unloaded"), px + RIGHT_W - 16, py + 4, RED, TINY);
        List<AdminChunksPacket.PlayerRow> players = data == null ? List.of() : data.players();
        if (players.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t(data == null ? "chunks.waiting" : "chunks.none"), RIGHT_W - 10, TINY), px + 5, py + 16, Ui.MUTED, TINY);
        }
        int top = py + 13, visible = 8;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, players.size() - visible)));
        for (int i = 0; i < visible && scroll + i < players.size(); i++) {
            AdminChunksPacket.PlayerRow p = players.get(scroll + i);
            int ry = top + i * ROW;
            if (mx >= px && mx < px + RIGHT_W && my >= ry && my < ry + ROW) {
                g.fill(px + 1, ry, px + RIGHT_W - 1, ry + ROW, 0x22B58CFF);
            }
            Ui.text(g, font, Component.literal(Ui.fit(font, p.name(), 50, SMALL)).withStyle(ChatFormatting.BOLD), px + 5, ry + 1, Ui.TEXT, SMALL);
            Ui.text(g, font, Ui.fit(font, where(p), RIGHT_W - 58 - 40, TINY), px + 58, ry + 2, whereColour(p), TINY);
            Ui.text(g, font, p.loaded() > 0 ? Integer.toString(p.loaded()) : "–", px + RIGHT_W - 32, ry + 2, GREEN, TINY);
            Ui.text(g, font, p.unloaded() > 0 ? Integer.toString(p.unloaded()) : "–", px + RIGHT_W - 14, ry + 2, RED, TINY);
            UUID id = p.id();
            hits.add(new Ui.Hit(px, ry, RIGHT_W, ROW, () -> pick(id)));
        }
        // machines with no owner found
        List<AdminChunksPacket.MachineRow> orphans = machinesOf(AdminChunksPacket.NOBODY);
        int oy = py + 98;
        g.fill(px + 1, oy - 2, px + RIGHT_W - 1, oy - 1, Ui.ROW_LINE);
        Ui.text(g, font, Component.literal(Tr.t("chunks.no_owner", orphans.size())).withStyle(ChatFormatting.BOLD), px + 5, oy + 2, Ui.GOLD, TINY);
        for (int i = 0; i < Math.min(5, orphans.size()); i++) {
            AdminChunksPacket.MachineRow m = orphans.get(i);
            int ry = oy + 12 + i * ROW;
            boolean unloaded = m.unloaded() > 0;
            Ui.text(g, font, Ui.fit(font, m.modId() + " · " + m.where(), RIGHT_W - 52, TINY), px + 5, ry + 2, unloaded ? RED : Ui.MUTED, TINY);
            String label = Tr.t(unloaded ? "chunks.restore" : "chunks.unload");
            int kw = 40, kx = px + RIGHT_W - 4 - kw;
            g.fill(kx, ry, kx + kw, ry + 9, Ui.DIM);
            Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), kx + (kw - Ui.width(font, label, TINY)) / 2, ry + 2, Ui.TEXT, TINY);
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
        int px = x + RIGHT_X, py = y + TOP;
        Ui.inset(g, px, py, RIGHT_W, BOX_H);
        AdminChunksPacket.PlayerRow p = data == null ? null : data.players().stream().filter(r -> r.id().equals(picked)).findFirst().orElse(null);
        if (p == null) {
            Ui.text(g, font, Ui.fit(font, Tr.t("chunks.waiting"), RIGHT_W - 10, TINY), px + 5, py + 6, Ui.MUTED, TINY);
            return;
        }
        Ui.text(g, font, Component.literal(Ui.fit(font, p.name(), 70, 1f)).withStyle(ChatFormatting.BOLD), px + 5, py + 4, Ui.TEXT, 1f);
        String w = Ui.fit(font, where(p), RIGHT_W - 84, TINY);
        Ui.text(g, font, w, px + RIGHT_W - 5 - Ui.width(font, w, TINY), py + 6, whereColour(p), TINY);
        List<AdminChunksPacket.MachineRow> machines = machinesOf(p.id());
        int top = py + 18, visible = 10;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, machines.size() - visible)));
        for (int i = 0; i < visible && scroll + i < machines.size(); i++) {
            AdminChunksPacket.MachineRow m = machines.get(scroll + i);
            int ry = top + i * ROW;
            Ui.text(g, font, Component.literal(Ui.fit(font, m.modId(), 46, TINY)).withStyle(ChatFormatting.BOLD), px + 5, ry + 2, Ui.TEXT, TINY);
            Ui.text(g, font, Ui.fit(font, m.where(), RIGHT_W - 54 - 30, TINY), px + 54, ry + 2, Ui.MUTED, TINY);
            String n = m.unloaded() > 0 ? Integer.toString(m.unloaded()) : Integer.toString(m.loaded());
            Ui.text(g, font, n, px + RIGHT_W - 12, ry + 2, m.unloaded() > 0 ? RED : GREEN, TINY);
        }
        // RESTORE NOW, UNLOAD NOW (SURE?)
        int ky = py + BOX_H - 17;
        String r = Tr.t("chunks.restore_now");
        int rw = Ui.width(font, r, TINY) + 12;
        g.fill(px + 5, ky, px + 5 + rw, ky + 13, Ui.DIM);
        Ui.text(g, font, Component.literal(r).withStyle(ChatFormatting.BOLD), px + 11, ky + 4, Ui.TEXT, TINY);
        UUID id = p.id();
        hits.add(new Ui.Hit(px + 5, ky, rw, 13, () -> send(AdminChunksEditPacket.of(AdminChunksEditPacket.Action.RESTORE_PLAYER, id.toString()))));
        boolean sure = System.currentTimeMillis() < unloadSureUntil;
        String u = Tr.t(sure ? "rules.sure" : "chunks.unload_now");
        int uw = Ui.width(font, Tr.t("chunks.unload_now"), TINY) + 12, ux = px + 9 + rw;
        g.fill(ux, ky, ux + uw, ky + 13, sure ? 0xFFD63B2F : 0xFF3A2A2A);
        Ui.frame(g, ux, ky, uw, 13, 0xFFD63B2F);
        Ui.text(g, font, Component.literal(u).withStyle(ChatFormatting.BOLD), ux + (uw - Ui.width(font, u, TINY)) / 2, ky + 4, sure ? 0xFFFFFFFF : 0xFFFF8F86, TINY);
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
