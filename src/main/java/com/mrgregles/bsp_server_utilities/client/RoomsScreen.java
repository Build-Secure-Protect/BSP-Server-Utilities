package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.RoomGiveBackPacket;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /su rooms} (SU-58), drawn as the confirmed page {@code tools/preview/rooms_refined.html} (look B, D452, D453): one
 * teal panel; on the left the count against the limit; on the right one tile per room with a square drawn to its size,
 * this server's rooms first, other servers' with a dimmer square, rooms in grace striped amber; the picked tile's line at
 * the foot, with GIVE BACK (SURE? within three seconds) only where it would work, and why not otherwise. The server
 * checks a give-back again and sends the page back.
 */
public class RoomsScreen extends Screen {
    static final int W = 300, H = 200;
    private static final int PAD = 8, FOOTER_H = 16, LINE_H = 24, LEFT_W = 76, GAP = 4, TILE_H = 44, SCROLL_W = 3, WHEEL_STEP = 16;
    private static final float SMALL = 0.7f, TINY = 0.6f;
    private static final long SURE_MS = 3000, DAY_MS = 86_400_000L;
    private static final int HERE = 0xFF19D3B0, ELSEWHERE = 0xFF4F7F78, AMBER = 0xFFF2B544, AMBER_DIM = 0xFF6B5524, STRIPE = 0xFF12100B, CHIP = 0xFF1B2230;

    private RoomsViewPacket page;
    @Nullable
    private String selected;
    @Nullable
    private String sureFor;
    private long sureUntil;
    private final List<Ui.Hit> hits = new ArrayList<>();
    private float scale = 1;
    private int scroll, contentH;

    public RoomsScreen(RoomsViewPacket page) {
        super(Component.literal("Compact Machine rooms"));
        this.page = page;
    }

    /** The page sent again (after a give-back): the picked room stays picked while it is there. */
    void update(RoomsViewPacket next) {
        page = next;
        sureFor = null;
        if (selected != null && next.rooms().stream().noneMatch(r -> key(r).equals(selected))) {
            selected = null;
        }
    }

    private static String key(RoomsViewPacket.Room r) {
        return r.server() + "|" + r.x() + "|" + r.z();
    }

    @Nullable
    private RoomsViewPacket.Room selectedRoom() {
        for (RoomsViewPacket.Room r : page.rooms()) {
            if (key(r).equals(selected)) {
                return r;
            }
        }
        return null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
    }

    private int left() {
        return (int) ((width / scale - W) / 2);
    }

    private int top() {
        return (int) ((height / scale - H) / 2);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        hits.clear();
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1);
        draw(g, (int) (mouseX / scale), (int) (mouseY / scale));
        g.pose().popPose();
    }

    private void draw(GuiGraphics g, int mx, int my) {
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, Ui.PANEL);
        Ui.frame(g, x, y, W, H, Ui.TEAL);

        String scope = Tr.t(page.network() ? "rooms.scope_network" : "rooms.scope_server");
        int scopeW = Ui.width(font, scope, TINY);
        Ui.text(g, font, scope, x + W - PAD - scopeW, y + 9, Ui.MUTED, TINY);
        String title = Ui.fit(font, Tr.t("rooms.title"), W - 3 * PAD - scopeW, SMALL);
        Ui.text(g, font, Component.literal(title).withStyle(ChatFormatting.BOLD), x + PAD, y + 8, Ui.TEAL, SMALL);

        int fy = y + H - PAD - FOOTER_H;
        int lineY = fy - 4 - LINE_H;
        int bodyY = y + 20, bodyH = lineY - 4 - bodyY;
        drawCount(g, x + PAD, bodyY, LEFT_W, bodyH);
        int tx = x + PAD + LEFT_W + 6, tw = x + W - PAD - SCROLL_W - 2 - tx;
        drawTiles(g, tx, bodyY, tw, bodyH, mx, my);
        drawScrollBar(g, tx + tw + 2, bodyY, bodyH);
        drawLine(g, x + PAD, lineY, W - 2 * PAD, mx, my);

        String close = Tr.t("rooms.close");
        int closeW = Math.max(52, Ui.width(font, close, TINY) + 20);
        key(g, x + W - PAD - closeW, fy, closeW, close, KeyStyle.GHOST, mx, my, this::onClose);
    }

    // ------------------------------------------------------------------ the count

    private void drawCount(GuiGraphics g, int x, int y, int w, int h) {
        Ui.inset(g, x, y, w, h);
        int ix = x + 6, iw = w - 12, cy = y + 6;
        boolean limited = page.limit() > 0 && !page.exempt();
        boolean over = limited && page.count() > page.limit();
        String n = Integer.toString(page.count());
        Ui.text(g, font, Component.literal(n).withStyle(ChatFormatting.BOLD), ix, cy, over ? Ui.BAD : page.count() == 0 ? Ui.MUTED : Ui.TEAL, 2f);
        int nW = Ui.width(font, n, 2f);
        String of = limited ? " / " + page.limit() : "";
        Ui.text(g, font, of, ix + nW, cy + 6, Ui.MUTED, 1f);
        cy += 20;
        if (!limited) {
            Ui.text(g, font, Ui.fit(font, Tr.t(page.exempt() ? "rooms.not_limited" : "rooms.no_limit"), iw, TINY), ix, cy, Ui.MUTED, TINY);
            cy += 8;
        }
        Ui.text(g, font, Ui.fit(font, Tr.t("rooms.counted"), iw, TINY), ix, cy, Ui.MUTED, TINY);
        cy += 8;
        if (limited) {
            int seg = Math.min(page.limit(), 12);
            int sw = Math.max(2, (iw - (seg - 1) * 2) / seg);
            int filled = page.limit() <= 12 ? Math.min(page.count(), seg) : Math.min(seg, page.count() * seg / page.limit());
            for (int i = 0; i < seg; i++) {
                int sx = ix + i * (sw + 2);
                g.fill(sx, cy, sx + sw, cy + 4, i < filled ? (over ? Ui.BAD : Ui.TEAL) : Ui.DIM);
            }
            cy += 9;
            List<String> lines;
            if (over) {
                String pill = Tr.t("rooms.over_pill", page.count() - page.limit());
                int pw = Math.min(iw, Ui.width(font, pill, TINY) + 6);
                g.fill(ix, cy, ix + pw, cy + 9, 0xFF3A2A2A);
                Ui.frame(g, ix, cy, pw, 9, 0xFFD63B2F);
                Ui.text(g, font, Ui.fit(font, pill, pw - 4, TINY), ix + 3, cy + 2, 0xFFFF8F86, TINY);
                cy += 12;
                lines = Ui.wrap(font, Tr.t("rooms.over_hint", page.count() - page.limit()), iw, TINY);
            } else if (page.count() == page.limit()) {
                lines = Ui.wrap(font, Tr.t("rooms.at_limit"), iw, TINY);
            } else {
                lines = Ui.wrap(font, Tr.t("rooms.more", page.limit() - page.count()), iw, TINY);
            }
            for (String l : lines) {
                if (cy + 6 > y + h - 30) {
                    break;
                }
                Ui.text(g, font, l, ix, cy, Ui.MUTED, TINY);
                cy += 6;
            }
        }
        List<String> note = Ui.wrap(font, Tr.t("rooms.lost_note", page.graceDays()), iw, TINY);
        int ny = y + h - 4 - note.size() * 6;
        for (String l : note) {
            if (ny >= cy) {
                Ui.text(g, font, l, ix, ny, Ui.MUTED, TINY);
            }
            ny += 6;
        }
    }

    // ------------------------------------------------------------------ the tiles

    private void drawTiles(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        List<RoomsViewPacket.Room> rooms = page.rooms();
        if (rooms.isEmpty()) {
            Ui.inset(g, x, y, w, h);
            List<String> lines = Ui.wrap(font, Tr.t("rooms.empty"), w - 24, SMALL);
            int ly = y + (h - lines.size() * 8) / 2;
            for (String l : lines) {
                Ui.text(g, font, l, x + (w - Ui.width(font, l, SMALL)) / 2, ly, Ui.MUTED, SMALL);
                ly += 8;
            }
            contentH = 0;
            return;
        }
        int cols = 3, tw = (w - (cols - 1) * GAP) / cols;
        int rows = (rooms.size() + cols - 1) / cols;
        contentH = rows * (TILE_H + GAP) - GAP;
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentH - h));
        Ui.scissor(g, x, y, x + w, y + h);
        for (int i = 0; i < rooms.size(); i++) {
            RoomsViewPacket.Room r = rooms.get(i);
            int cx = x + (i % cols) * (tw + GAP), cy = y + (i / cols) * (TILE_H + GAP) - scroll;
            if (cy + TILE_H < y || cy > y + h) {
                continue;
            }
            drawTile(g, r, cx, cy, tw, mx, my);
            int top = Math.max(cy, y), bottom = Math.min(cy + TILE_H, y + h);
            String k = key(r);
            hits.add(new Ui.Hit(cx, top, tw, bottom - top, () -> {
                selected = k;
                sureFor = null;
            }));
        }
        g.disableScissor();
    }

    private void drawTile(GuiGraphics g, RoomsViewPacket.Room r, int x, int y, int w, int mx, int my) {
        boolean here = r.here(page.server());
        if (r.grace()) {
            g.fill(x, y, x + w, y + TILE_H, Ui.SLOT);
            for (int d = 0; d < TILE_H; d++) { // diagonal stripes, 6 wide every 12
                for (int s = d % 12; s < w; s += 12) {
                    g.fill(x + s, y + d, Math.min(x + s + 6, x + w), y + d + 1, STRIPE);
                }
            }
            Ui.frame(g, x, y, w, TILE_H, AMBER_DIM);
        } else {
            Ui.inset(g, x, y, w, TILE_H);
        }
        if (key(r).equals(selected)) {
            Ui.frame(g, x, y, w, TILE_H, Ui.TEAL);
        } else if (mx >= x && mx < x + w && my >= y && my < y + TILE_H) {
            Ui.frame(g, x, y, w, TILE_H, Ui.MUTED);
        }
        int sq = Math.max(4, Math.min(26, r.size() * 2));
        if (r.grace()) {
            Ui.frame(g, x + 5, y + 5, sq, sq, AMBER);
        } else {
            g.fill(x + 5, y + 5, x + 5 + sq, y + 5 + sq, here ? HERE : ELSEWHERE);
        }
        String size = Integer.toString(r.size());
        Ui.text(g, font, size, x + w - 4 - Ui.width(font, size, TINY), y + 5, Ui.MUTED, TINY);
        int ty = y + TILE_H - 17;
        if (r.grace()) {
            String what = switch (r.reason()) {
                case "given_back" -> Tr.t("rooms.given_back");
                case "freed" -> Tr.t("rooms.freed");
                default -> Tr.t("rooms.lost", Tr.t(reasonKey(r.reason())));
            };
            Ui.text(g, font, Component.literal(Ui.fit(font, what, w - 8, TINY)).withStyle(ChatFormatting.BOLD), x + 4, ty, AMBER, TINY);
            Ui.text(g, font, Ui.fit(font, Tr.t("rooms.cleared_in", daysLeft(r)), w - 8, TINY), x + 4, ty + 8, AMBER, TINY);
            return;
        }
        String name = r.name().isEmpty() ? Tr.t("rooms.no_name") : r.name();
        Ui.text(g, font, r.name().isEmpty() ? Component.literal(Ui.fit(font, name, w - 8, SMALL)) : Component.literal(Ui.fit(font, name, w - 8, SMALL)).withStyle(ChatFormatting.BOLD),
                x + 4, ty - 1, r.name().isEmpty() ? Ui.MUTED : Ui.TEXT, SMALL);
        String chip = Ui.fit(font, r.server(), w - 12, TINY);
        int cw = Ui.width(font, chip, TINY) + 4;
        g.fill(x + 4, ty + 7, x + 4 + cw, ty + 15, CHIP);
        Ui.text(g, font, chip, x + 6, ty + 9, Ui.MUTED, TINY);
    }

    /** The size word's key (tiny to maximum); built in a variable, as the lang check reads only whole keys. */
    private static String sizeKey(int size) {
        String key = "rooms.size." + switch (size) {
            case 3, 5, 7, 9, 11, 13 -> size;
            default -> 7; // never a raw key for a size the lang file does not name
        };
        return key;
    }

    /** A reason the lang file knows ("destroyed" otherwise, so an unknown one from a newer server never shows as a raw key). */
    private static String reasonKey(String reason) {
        String key = "rooms.reason." + com.mrgregles.bsp_server_utilities.rooms.RoomLosses.known(reason);
        return key;
    }

    private static long daysLeft(RoomsViewPacket.Room r) {
        return Math.max(1, (r.graceLeftMs() + DAY_MS - 1) / DAY_MS);
    }

    private void drawScrollBar(GuiGraphics g, int x, int y, int h) {
        if (contentH <= h) {
            return;
        }
        g.fill(x, y, x + SCROLL_W, y + h, Ui.DIM);
        int thumb = Math.max(10, h * h / contentH);
        int ty = y + (h - thumb) * scroll / Math.max(1, contentH - h);
        g.fill(x, ty, x + SCROLL_W, ty + thumb, Ui.MUTED);
    }

    // ------------------------------------------------------------------ the picked room's line

    private void drawLine(GuiGraphics g, int x, int y, int w, int mx, int my) {
        Ui.inset(g, x, y, w, LINE_H);
        RoomsViewPacket.Room r = selectedRoom();
        if (r == null) {
            Ui.text(g, font, Ui.fit(font, Tr.t("rooms.pick"), w - 12, TINY), x + 6, y + 9, Ui.MUTED, TINY);
            return;
        }
        boolean here = r.here(page.server());
        boolean sure = key(r).equals(sureFor) && System.currentTimeMillis() < sureUntil;
        int keyW = 0;
        String right = null;
        if (here && !r.grace()) {
            String label = Tr.t(sure ? "rooms.sure" : "rooms.give_back");
            keyW = Math.max(60, Ui.width(font, label, TINY) + 16);
            boolean can = r.give() == RoomsViewPacket.GIVE_OK;
            key(g, x + w - 4 - keyW, y + 4, keyW, label, can ? KeyStyle.WARN : KeyStyle.LOCKED, mx, my, can ? () -> giveBack(r) : null);
        } else if (!here && !r.grace()) {
            right = Tr.t("rooms.elsewhere", r.server());
        }
        if (right != null) {
            keyW = Ui.width(font, right, TINY) + 4;
            Ui.text(g, font, right, x + w - 4 - keyW, y + 9, Ui.MUTED, TINY);
        }
        int textW = w - 12 - keyW - 6;
        String name = r.name().isEmpty() ? Tr.t("rooms.no_name") : r.name();
        String nameFit = Ui.fit(font, name, Math.min(textW / 2, 90), SMALL);
        Ui.text(g, font, Component.literal(nameFit).withStyle(ChatFormatting.BOLD), x + 6, y + 4, r.grace() ? AMBER : Ui.TEXT, SMALL);
        int nameW = Ui.width(font, nameFit, SMALL) + 6;
        Ui.text(g, font, Ui.fit(font, detail(r, here), textW - nameW, TINY), x + 6 + nameW, y + 5, Ui.MUTED, TINY);
        String note = note(r, here, sure);
        if (!note.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, note, textW, TINY), x + 6, y + 15, r.give() == RoomsViewPacket.GIVE_OK || sure ? Ui.MUTED : r.grace() ? AMBER : Ui.MUTED, TINY);
        }
    }

    private String detail(RoomsViewPacket.Room r, boolean here) {
        String size = Tr.t(sizeKey(r.size()));
        String s = Tr.t("rooms.detail_size", size, r.size(), r.size(), r.size());
        String server = here ? Tr.t("rooms.this_server", r.server()) : r.server();
        String machine;
        if (r.grace()) {
            machine = Tr.t("rooms.cleared_in", daysLeft(r));
        } else if (r.machine().isEmpty()) {
            machine = Tr.t("rooms.machine_none");
        } else {
            machine = (r.machine().indexOf(' ') < 0 ? Tr.t("rooms.machine_in", dimension(r.machine())) : Tr.t("rooms.machine_at", where(r.machine())))
                    + (r.machines() > 1 ? " · " + Tr.t("rooms.machines_more", r.machines()) : "");
        }
        return s + " · " + server + " · " + machine;
    }

    /** "minecraft:the_nether" as "the nether" (players get the dimension only, D457). */
    static String dimension(String id) {
        return (id.contains(":") ? id.substring(id.indexOf(':') + 1) : id).replace('_', ' ');
    }

    /** "minecraft:overworld 1204 64 -388" as "1204 64 −388 (overworld)". */
    static String where(String machine) {
        String[] p = machine.split(" ");
        if (p.length < 4) {
            return machine;
        }
        String dim = p[0].contains(":") ? p[0].substring(p[0].indexOf(':') + 1) : p[0];
        return p[1] + " " + p[2] + " " + p[3].replace('-', '−') + " (" + dim.replace('_', ' ') + ")";
    }

    private String note(RoomsViewPacket.Room r, boolean here, boolean sure) {
        if (r.grace()) {
            return Tr.t("rooms.note_grace", daysLeft(r));
        }
        if (!here) {
            return "";
        }
        if (sure) {
            return Tr.t("rooms.note_sure");
        }
        return switch (r.give()) {
            case RoomsViewPacket.GIVE_OK -> Tr.t("rooms.note_ok", page.graceDays());
            case RoomsViewPacket.GIVE_PLACED -> Tr.t("rooms.note_placed");
            case RoomsViewPacket.GIVE_INSIDE -> Tr.t("rooms.note_inside");
            default -> "";
        };
    }

    private void giveBack(RoomsViewPacket.Room r) {
        long now = System.currentTimeMillis();
        if (key(r).equals(sureFor) && now < sureUntil) {
            sureFor = null;
            ServerUtilsNetwork.sendToServer(new RoomGiveBackPacket(r.x(), r.z()));
            return;
        }
        sureFor = key(r);
        sureUntil = now + SURE_MS;
    }

    // ------------------------------------------------------------------ keys and input

    private enum KeyStyle { WARN, LOCKED, GHOST }

    private void key(GuiGraphics g, int x, int y, int w, String label, KeyStyle style, int mx, int my, @Nullable Runnable action) {
        boolean hover = action != null && mx >= x && mx < x + w && my >= y && my < y + FOOTER_H;
        int text;
        switch (style) {
            case WARN -> {
                g.fill(x, y, x + w, y + FOOTER_H, hover ? 0xFF4A3030 : 0xFF3A2A2A);
                Ui.frame(g, x, y, w, FOOTER_H, 0xFFD63B2F);
                text = 0xFFFF8F86;
            }
            case LOCKED -> {
                Ui.frame(g, x, y, w, FOOTER_H, Ui.DIM);
                text = 0x669AA3B5;
            }
            default -> {
                if (hover) {
                    g.fill(x, y, x + w, y + FOOTER_H, 0x222A2F3A);
                }
                Ui.frame(g, x, y, w, FOOTER_H, Ui.DIM);
                text = Ui.MUTED;
            }
        }
        String s = Ui.fit(font, label.toUpperCase(Locale.ROOT), w - 6, TINY);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + (w - Ui.width(font, s, TINY)) / 2, y + 5, text, TINY);
        if (action != null) {
            hits.add(new Ui.Hit(x, y, w, FOOTER_H, action));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double mx = mouseX / scale, my = mouseY / scale;
        if (button == 0) {
            for (Ui.Hit hit : List.copyOf(hits)) {
                if (hit.contains(mx, my)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll -= (int) Math.signum(delta) * WHEEL_STEP;
        return true;
    }
}
