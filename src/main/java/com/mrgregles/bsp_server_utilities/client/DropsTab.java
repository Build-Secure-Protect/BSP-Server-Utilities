package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.despawn.DespawnSettings;
import com.mrgregles.bsp_server_utilities.network.AdminDropsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminDropsPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The DROPS tab of the admin panel (owner decisions D108, D111 to D118), as on {@code tools/preview/drops_refined.html}:
 * on the left DESPAWN (counting while nobody is near, ITEMS and DEATH DROPS with − and +, orbs following items, the note
 * that mod-timed items are never removed) and CLEAR ITEMS NOW (this server, all servers or one by name; CLEAR ITEMS with
 * SURE?); on the right the exceptions (an item with its time or never, × to remove), or, after + ADD AN ITEM, a search
 * over every item with the picked item's time (− / + from 1 to 120, NEVER) and ADD. Changes go to the server at once.
 * The settings keep their 150 wide box at their content's height (pulled together a little on the shortest pages); the
 * list takes the rest of the page, more rows as it gets taller and the extra width to the item text (D756, D757).
 */
final class DropsTab {
    /** LEFT_H: the settings' content height; RIGHT_W: the list's width on the old 344 wide page, the item text's base. */
    private static final int LEFT_X = 8, TOP = 22, LEFT_W = 150, LEFT_H = 158, RIGHT_X = 166, RIGHT_W = 170, ROW = 10;
    /** CLEAR_Y: the CLEAR ITEMS key, the last settings line, pulled up in full on a short page. */
    private static final int REFRESH_TICKS = 100, CLEAR_Y = LEFT_H - 18;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int RED = 0xFFFF6B5C;

    private final AdminScreen host;
    @Nullable
    private AdminDropsPacket data;
    private DespawnSettings settings = DespawnSettings.DEFAULTS;
    private String clearTarget = "here";
    private long clearSureUntil;
    private int scroll, refreshIn;
    private boolean searching;
    private String query = "";
    @Nullable
    private Item picked;
    private int pickedMinutes = 30;
    private boolean pickedNever;
    @Nullable
    private EditBox searchBox;

    DropsTab(AdminScreen host) {
        this.host = host;
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        ServerUtilsNetwork.CHANNEL.sendToServer(AdminDropsEditPacket.of(AdminDropsEditPacket.Action.REFRESH, ""));
    }

    void accept(AdminDropsPacket p) {
        data = p;
        settings = p.settings();
    }

    private void set(DespawnSettings next) {
        settings = next.sanitised();
        ServerUtilsNetwork.CHANNEL.sendToServer(new AdminDropsEditPacket(AdminDropsEditPacket.Action.SETTINGS, "", settings));
    }

    private DespawnSettings with(boolean enabled, int items, int death, boolean orbs, List<DespawnSettings.ItemTime> ex) {
        return new DespawnSettings(enabled, items, death, orbs, ex, settings.revision());
    }

    void tick() {
        if (searchBox != null) {
            searchBox.tick();
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
        return base - (LEFT_H - leftH()) * base / CLEAR_Y;
    }

    /** Search results shown: 8 on the old page, more on a taller one, fewer on a shorter one. */
    private int resultRows() {
        return Math.max(1, (boxH() - 78) / ROW);
    }

    void addWidgets(int x, int y) {
        searchBox = null;
        if (searching) {
            searchBox = new EditBox(host.font(), x + RIGHT_X + 5, y + TOP + 14, rightW() - 10, 12, Component.literal(Tr.t("drops.search")));
            searchBox.setMaxLength(48);
            searchBox.setHint(Component.literal(Tr.t("drops.search")).withStyle(ChatFormatting.DARK_GRAY));
            searchBox.setValue(query);
            searchBox.setResponder(s -> {
                query = s.strip().toLowerCase(Locale.ROOT);
                scroll = 0;
            });
            host.add(searchBox);
        }
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        drawSettings(g, x, y, mx, my, hits);
        if (searching) {
            drawSearch(g, x, y, mx, my, hits);
        } else {
            drawExceptions(g, x, y, mx, my, hits);
        }
        Font font = host.font();
        int fy = y + host.pageH() - 8 - 14;
        String key = Tr.t("drops.refresh");
        int kw = Ui.widthBold(font, key, TINY) + 16, kx = x + host.pageW() - 8 - kw;
        String foot = data == null ? "" : Tr.t("drops.footer", data.items(), data.orbs());
        Ui.text(g, font, Ui.fit(font, foot, kx - x - 16, TINY), x + 8, fy + 5, Ui.MUTED, TINY);
        Ui.frame(g, kx, fy, kw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(key).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, kw, 14, this::open));
    }

    private void drawSettings(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LEFT_X, ly = y + TOP, ix = lx + 6, iw = LEFT_W - 12;
        Ui.inset(g, lx, ly, LEFT_W, leftH());
        Ui.text(g, font, Component.literal(Tr.t("drops.despawn")).withStyle(ChatFormatting.BOLD), ix, ly + at(5), Ui.VIOLET, TINY);
        toggle(g, ix, ly + at(14), iw, Tr.t("drops.enabled"), settings.enabled(), hits,
                () -> set(with(!settings.enabled(), settings.itemMinutes(), settings.deathMinutes(), settings.orbsFollow(), settings.exceptions())));
        stepper(g, ix, ly + at(27), iw, Tr.t("drops.items"), settings.itemMinutes(), mx, my, hits, DespawnSettings.MIN_MINUTES, DespawnSettings.MAX_MINUTES,
                m -> set(with(settings.enabled(), m, settings.deathMinutes(), settings.orbsFollow(), settings.exceptions())));
        stepper(g, ix, ly + at(41), iw, Tr.t("drops.death"), settings.deathMinutes(), mx, my, hits, DespawnSettings.MIN_MINUTES, DespawnSettings.MAX_MINUTES,
                m -> set(with(settings.enabled(), settings.itemMinutes(), m, settings.orbsFollow(), settings.exceptions())));
        toggle(g, ix, ly + at(55), iw, Tr.t("drops.orbs"), settings.orbsFollow(), hits,
                () -> set(with(settings.enabled(), settings.itemMinutes(), settings.deathMinutes(), !settings.orbsFollow(), settings.exceptions())));
        int ny = ly + at(68), noteEnd = ny + 12;
        for (String line : Ui.wrap(font, Tr.t("drops.never_note"), iw, TINY)) {
            if (ny > noteEnd) {
                break;
            }
            Ui.text(g, font, line, ix, ny, Ui.MUTED, TINY);
            ny += 6;
        }
        // clear items now
        Ui.text(g, font, Component.literal(Tr.t("drops.clear_now")).withStyle(ChatFormatting.BOLD), ix, ly + at(88), Ui.VIOLET, TINY);
        List<String[]> chips = new ArrayList<>();
        chips.add(new String[]{"here", Tr.t("drops.this_server")});
        chips.add(new String[]{"all", Tr.t("drops.all_servers")});
        if (data != null) {
            data.servers().forEach(s -> chips.add(new String[]{s, s}));
        }
        int cx = ix, cy = ly + at(97), ky = ly + at(CLEAR_Y);
        for (String[] c : chips) {
            String label = Ui.fit(font, c[1], 56, TINY);
            int cw = Ui.width(font, label, TINY) + 8;
            if (cx + cw > ix + iw) {
                cx = ix;
                cy += 13;
            }
            if (cy + 11 >= ky) {
                break;
            }
            boolean on = clearTarget.equals(c[0]);
            g.fill(cx, cy, cx + cw, cy + 11, Ui.SLOT);
            Ui.frame(g, cx, cy, cw, 11, on ? Ui.VIOLET : Ui.DIM);
            Ui.text(g, font, label, cx + 4, cy + 3, on ? Ui.VIOLET : Ui.TEXT, TINY);
            String target = c[0];
            hits.add(new Ui.Hit(cx, cy, cw, 11, () -> {
                clearTarget = target;
                clearSureUntil = 0;
            }));
            cx += cw + 3;
        }
        boolean sure = System.currentTimeMillis() < clearSureUntil;
        boolean counting = data != null && data.counting();
        String label;
        if (sure) {
            label = Tr.t("rules.sure");
        } else if (counting) {
            label = Tr.t("drops.counting");
        } else {
            label = Tr.t("drops.clear");
        }
        g.fill(ix, ky, ix + iw, ky + 13, sure ? 0xFFD63B2F : 0xFF3A2A2A);
        Ui.frame(g, ix, ky, iw, 13, 0xFFD63B2F);
        label = Ui.fitBold(font, label, iw - 6, TINY);
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), ix + (iw - Ui.widthBold(font, label, TINY)) / 2, ky + 4, sure ? 0xFFFFFFFF : 0xFFFF8F86, TINY);
        hits.add(new Ui.Hit(ix, ky, iw, 13, () -> {
            long now = System.currentTimeMillis();
            if (now >= clearSureUntil) {
                clearSureUntil = now + 3000;
                return;
            }
            clearSureUntil = 0;
            ServerUtilsNetwork.CHANNEL.sendToServer(AdminDropsEditPacket.of(AdminDropsEditPacket.Action.CLEAR, clearTarget));
        }));
    }

    private void toggle(GuiGraphics g, int x, int y, int w, String label, boolean on, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        g.fill(x, y + 2, x + 14, y + 8, on ? 0xFF3D2F5C : Ui.DIM);
        g.fill(on ? x + 8 : x + 1, y + 3, on ? x + 13 : x + 6, y + 7, on ? Ui.VIOLET : Ui.MUTED);
        Ui.text(g, font, Ui.fit(font, label, w - 20, SMALL), x + 19, y + 2, on ? Ui.TEXT : Ui.MUTED, SMALL);
        hits.add(new Ui.Hit(x, y, w, 10, action));
    }

    private void stepper(GuiGraphics g, int x, int y, int w, String label, int value, int mx, int my, List<Ui.Hit> hits, int min, int max, java.util.function.IntConsumer to) {
        Font font = host.font();
        Ui.text(g, font, label.toUpperCase(Locale.ROOT), x, y + 3, Ui.MUTED, TINY);
        String unit = Tr.t("drops.min");
        int uw = Ui.width(font, unit, TINY), sx = x + w - uw - 4 - 50;
        key(g, sx, y, "−", value > min, mx, my, hits, () -> to.accept(value - 1));
        g.fill(sx + 12, y, sx + 38, y + 11, Ui.SLOT);
        Ui.frame(g, sx + 12, y, 26, 11, Ui.DIM);
        String v = Integer.toString(value);
        Ui.text(g, font, Component.literal(v).withStyle(ChatFormatting.BOLD), sx + 12 + (26 - Ui.width(font, v, SMALL)) / 2, y + 2, Ui.TEXT, SMALL);
        key(g, sx + 38, y, "+", value < max, mx, my, hits, () -> to.accept(value + 1));
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

    private static String itemName(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : BuiltInRegistries.ITEM.get(rl);
        return item == null || item == Items.AIR ? id : item.getDescription().getString();
    }

    private void drawExceptions(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP, rw = rightW(), extra = rw - RIGHT_W, nameGrow = extra / 2, idGrow = extra - nameGrow;
        Ui.inset(g, px, py, rw, boxH());
        String add = Tr.t("drops.add_item");
        int aw = Ui.widthBold(font, add, TINY);
        String head = Ui.fitBold(font, Tr.t("drops.exceptions", settings.exceptions().size()), rw - 14 - aw, TINY);
        Ui.text(g, font, Component.literal(head).withStyle(ChatFormatting.BOLD), px + 5, py + 5, Ui.VIOLET, TINY);
        Ui.text(g, font, Component.literal(add).withStyle(ChatFormatting.BOLD), px + rw - 5 - aw, py + 5, Ui.VIOLET, TINY);
        hits.add(new Ui.Hit(px + rw - 7 - aw, py + 2, aw + 4, 10, () -> {
            searching = true;
            picked = null;
            query = "";
            scroll = 0;
            host.rebuildTab();
        }));
        List<DespawnSettings.ItemTime> ex = settings.exceptions();
        if (ex.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("drops.no_exceptions"), rw - 10, TINY), px + 5, py + 18, Ui.MUTED, TINY);
        }
        int top = py + 15, visible = Math.max(1, (boxH() - 17) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, ex.size() - visible)));
        for (int i = 0; i < visible && scroll + i < ex.size(); i++) {
            DespawnSettings.ItemTime e = ex.get(scroll + i);
            int ry = top + i * ROW;
            Ui.text(g, font, Component.literal(Ui.fitBold(font, itemName(e.itemId()), 62 + nameGrow, SMALL)).withStyle(ChatFormatting.BOLD), px + 5, ry + 1, Ui.TEXT, SMALL);
            Ui.text(g, font, Ui.fit(font, e.itemId(), 60 + idGrow, TINY), px + 70 + nameGrow, ry + 2, Ui.MUTED, TINY);
            boolean never = e.minutes() == DespawnSettings.NEVER;
            String t = never ? Tr.t("drops.never") : Tr.t("drops.minutes", e.minutes());
            Ui.text(g, font, Component.literal(t).withStyle(ChatFormatting.BOLD), px + rw - 13 - Ui.widthBold(font, t, TINY), ry + 2, never ? Ui.GOLD : Ui.TEXT, TINY); // clear of the × (audit of SU-154)
            Ui.text(g, font, "×", px + rw - 10, ry + 1, RED, SMALL);
            hits.add(new Ui.Hit(px + rw - 13, ry, 10, ROW, () -> {
                List<DespawnSettings.ItemTime> next = new ArrayList<>(settings.exceptions());
                next.removeIf(o -> o.itemId().equals(e.itemId()));
                set(with(settings.enabled(), settings.itemMinutes(), settings.deathMinutes(), settings.orbsFollow(), next));
            }));
        }
    }

    private String resultsFor = "";
    private List<Item> results = List.of();

    /** Every item whose name or id holds the text; worked out again only when the text changes. */
    private List<Item> results() {
        if (query.equals(resultsFor)) {
            return results;
        }
        resultsFor = query;
        List<Item> out = new ArrayList<>();
        results = out;
        if (query.isEmpty()) {
            return out;
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (id.contains(query) || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(query)) {
                out.add(item);
                if (out.size() >= 200) {
                    break;
                }
            }
        }
        return out;
    }

    private void drawSearch(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP, rw = rightW(), bh = boxH(), extra = rw - RIGHT_W;
        Ui.inset(g, px, py, rw, bh);
        String back = Tr.t("drops.back");
        int bw = Ui.width(font, back, TINY);
        String title = Ui.fitBold(font, Tr.t("drops.add_title"), rw - 14 - bw, TINY);
        Ui.text(g, font, Component.literal(title).withStyle(ChatFormatting.BOLD), px + 5, py + 4, Ui.VIOLET, TINY);
        Ui.text(g, font, back, px + rw - 5 - bw, py + 4, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(px + rw - 7 - bw, py + 1, bw + 4, 10, () -> {
            searching = false;
            host.rebuildTab();
        }));
        List<Item> found = results();
        int top = py + 30, shown = resultRows(), nameGrow = extra / 2;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, found.size() - shown)));
        for (int i = 0; i < shown && scroll + i < found.size(); i++) {
            Item item = found.get(scroll + i);
            int ry = top + i * ROW;
            boolean on = item == picked;
            if (on) {
                g.fill(px + 1, ry, px + rw - 1, ry + ROW, 0xFF1B2230);
                g.fill(px + 1, ry, px + 3, ry + ROW, Ui.VIOLET);
            } else if (mx >= px && mx < px + rw && my >= ry && my < ry + ROW) {
                g.fill(px + 1, ry, px + rw - 1, ry + ROW, 0x22B58CFF);
            }
            Ui.text(g, font, Ui.fit(font, item.getDescription().getString(), 70 + nameGrow, SMALL), px + 6, ry + 1, on ? Ui.TEXT : Ui.MUTED, SMALL);
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            String ids = Ui.fit(font, id, rw - 84 - nameGrow, TINY);
            Ui.text(g, font, ids, px + rw - 5 - Ui.width(font, ids, TINY), ry + 2, Ui.MUTED, TINY);
            hits.add(new Ui.Hit(px, ry, rw, ROW, () -> {
                picked = item;
                DespawnSettings.ItemTime had = settings.exception(id);
                pickedNever = had != null && had.minutes() == DespawnSettings.NEVER;
                pickedMinutes = had != null && had.minutes() > 0 ? had.minutes() : 30;
            }));
        }
        if (picked == null) {
            return;
        }
        // the picked item's time, NEVER, ADD
        int ky = py + bh - 16, kx = px + 5, lastsW = 54 + extra;
        String lasts = Ui.fit(font, Tr.t("drops.lasts", picked.getDescription().getString()), lastsW, TINY);
        Ui.text(g, font, lasts, kx, ky + 3, Ui.MUTED, TINY);
        // the steps follow the words: where they were on the old page, nearer on a narrower one, never far off on a wider one
        int sx = kx + 2 + (extra > 0 ? Math.max(54, Ui.width(font, lasts, TINY)) : lastsW);
        key(g, sx, ky, "−", !pickedNever && pickedMinutes > 1, mx, my, hits, () -> pickedMinutes--);
        g.fill(sx + 12, ky, sx + 34, ky + 11, Ui.SLOT);
        String v = pickedNever ? "–" : Integer.toString(pickedMinutes);
        Ui.text(g, font, Component.literal(v).withStyle(ChatFormatting.BOLD), sx + 12 + (22 - Ui.width(font, v, SMALL)) / 2, ky + 2, Ui.TEXT, SMALL);
        key(g, sx + 34, ky, "+", !pickedNever && pickedMinutes < DespawnSettings.MAX_EXCEPTION_MINUTES, mx, my, hits, () -> pickedMinutes++);
        int nx = sx + 49;
        String never = Tr.t("drops.never");
        int nw = Ui.width(font, never, TINY) + 8;
        g.fill(nx, ky, nx + nw, ky + 11, Ui.SLOT);
        Ui.frame(g, nx, ky, nw, 11, pickedNever ? Ui.VIOLET : Ui.DIM);
        Ui.text(g, font, never, nx + 4, ky + 3, pickedNever ? Ui.VIOLET : Ui.TEXT, TINY);
        hits.add(new Ui.Hit(nx, ky, nw, 11, () -> pickedNever = !pickedNever));
        int ax = px + rw - 5 - 26;
        g.fill(ax, ky, ax + 26, ky + 11, Ui.VIOLET);
        String a = Tr.t("drops.add");
        Ui.text(g, font, Component.literal(a).withStyle(ChatFormatting.BOLD), ax + (26 - Ui.widthBold(font, a, TINY)) / 2, ky + 3, Ui.INK, TINY);
        Item item = picked;
        hits.add(new Ui.Hit(ax, ky, 26, 11, () -> {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            List<DespawnSettings.ItemTime> next = new ArrayList<>(settings.exceptions());
            next.removeIf(o -> o.itemId().equals(id));
            next.add(new DespawnSettings.ItemTime(id, pickedNever ? DespawnSettings.NEVER : pickedMinutes));
            set(with(settings.enabled(), settings.itemMinutes(), settings.deathMinutes(), settings.orbsFollow(), next));
            searching = false;
            host.rebuildTab();
        }));
    }

    boolean scroll(double delta) {
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
