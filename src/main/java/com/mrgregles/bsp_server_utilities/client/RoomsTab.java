package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.AdminRoomsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminRoomsPacket;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
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
 * The ROOMS tab of the admin panel (SU-59; owner decisions D443, D448, D452 look A, D453 R4 to R7), as on
 * {@code tools/preview/rooms_refined.html}: on the left every owner of Compact Machine rooms with a search, their rooms
 * against the limit, how many over (or exempt) and on how many servers, most over first; on the right the picked owner's
 * rooms (size, name, server, machine placed or not, grace with days left) with FREE and SURE? for this server's rooms, and
 * the hovered room's position, machine and grace at the foot. The footer has the limit, the scope, the owner and grace
 * counts, and any server not heard from for five minutes in amber (R5). The server checks FREE again.
 */
final class RoomsTab {
    private static final int LEFT_X = 8, TOP = 22, LEFT_W = 166, BOX_H = 158, RIGHT_X = 180, RIGHT_W = 156, ROW = 10;
    private static final int REFRESH_TICKS = 100;
    private static final long SURE_MS = 3000, NOTE_MS = 4000;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int RED = 0xFFFF6B5C, AMBER = 0xFFF2B544, OFF = 0xFF5A6170, PICKED = 0xFF1B2230;

    private final AdminScreen host;
    @Nullable
    private AdminRoomsPacket data;
    private UUID picked = AdminRoomsPacket.NONE;
    private String query = "";
    private int ownerScroll, roomScroll, refreshIn;
    /** The room whose FREE was clicked once ("server|x|z") and until when SURE? shows. */
    private String sureKey = "";
    private long sureUntil;
    private String note = "";
    private long noteUntil;
    /** Where the tab was last drawn (for the wheel). */
    private int drawnX;
    @Nullable
    private EditBox searchBox;

    RoomsTab(AdminScreen host) {
        this.host = host;
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        send(AdminRoomsEditPacket.of(AdminRoomsEditPacket.Action.OPEN, picked));
    }

    private void refresh() {
        refreshIn = REFRESH_TICKS;
        send(AdminRoomsEditPacket.of(AdminRoomsEditPacket.Action.REFRESH, picked));
    }

    private static void send(AdminRoomsEditPacket p) {
        ServerUtilsNetwork.CHANNEL.sendToServer(p);
    }

    void accept(AdminRoomsPacket p) {
        data = p;
        if (!p.picked().equals(picked)) {
            return; // an answer for an owner picked before; the next one follows
        }
        String text = switch (p.note()) {
            case "freed" -> Tr.t("rooms_admin.note_freed", p.graceDays());
            case "placed" -> Tr.t("rooms_admin.note_placed");
            case "grace" -> Tr.t("rooms_admin.note_grace");
            case "gone" -> Tr.t("rooms_admin.note_gone");
            default -> "";
        };
        if (!text.isEmpty()) {
            note = text;
            noteUntil = System.currentTimeMillis() + NOTE_MS;
        }
    }

    void tick() {
        if (searchBox != null) {
            searchBox.tick();
        }
        if (--refreshIn <= 0) {
            refresh();
        }
    }

    void addWidgets(int x, int y) {
        searchBox = new EditBox(host.font(), x + LEFT_X + 3, y + TOP + 3, LEFT_W - 6, 11, Component.literal(Tr.t("rooms_admin.find")));
        searchBox.setMaxLength(16);
        searchBox.setHint(Component.literal(Tr.t("rooms_admin.find")).withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(query);
        searchBox.setResponder(s -> {
            query = s.strip().toLowerCase(Locale.ROOT);
            ownerScroll = 0;
        });
        host.add(searchBox);
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        drawnX = x;
        drawOwners(g, x, y, mx, my, hits);
        drawRooms(g, x, y, mx, my, hits);
        drawFooter(g, x, y, hits);
    }

    private static String ownerName(AdminRoomsPacket.Owner o) {
        return o.name().isEmpty() ? Tr.t("rooms_admin.unknown") : o.name();
    }

    private List<AdminRoomsPacket.Owner> shown() {
        if (data == null) {
            return List.of();
        }
        if (query.isEmpty()) {
            return data.owners();
        }
        List<AdminRoomsPacket.Owner> out = new ArrayList<>();
        for (AdminRoomsPacket.Owner o : data.owners()) {
            if (o.name().toLowerCase(Locale.ROOT).contains(query) || o.id().toString().startsWith(query)) {
                out.add(o);
            }
        }
        return out;
    }

    private void drawOwners(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + LEFT_X, ly = y + TOP;
        Ui.inset(g, lx, ly, LEFT_W, BOX_H);
        int hy = ly + 18;
        int cRooms = lx + 84, cOver = lx + 116, cServers = lx + 142;
        Ui.text(g, font, Tr.t("rooms_admin.owner").toUpperCase(Locale.ROOT), lx + 5, hy, Ui.MUTED, TINY);
        Ui.text(g, font, Tr.t("rooms_admin.rooms").toUpperCase(Locale.ROOT), cRooms, hy, Ui.MUTED, TINY);
        Ui.text(g, font, Tr.t("rooms_admin.over").toUpperCase(Locale.ROOT), cOver, hy, Ui.MUTED, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("rooms_admin.servers").toUpperCase(Locale.ROOT), LEFT_W - (cServers - lx) - 3, TINY), cServers, hy, Ui.MUTED, TINY);
        if (data == null) {
            return;
        }
        List<AdminRoomsPacket.Owner> owners = shown();
        int top = ly + 27, visible = (BOX_H - 29) / ROW;
        if (owners.isEmpty()) {
            String empty = data.owners().isEmpty() ? Tr.t("rooms_admin.none") : Tr.t("rooms_admin.no_match");
            Ui.text(g, font, Ui.fit(font, empty, LEFT_W - 10, TINY), lx + 5, top + 2, Ui.MUTED, TINY);
            return;
        }
        ownerScroll = Math.max(0, Math.min(ownerScroll, Math.max(0, owners.size() - visible)));
        int limit = data.limit();
        for (int i = 0; i < visible && ownerScroll + i < owners.size(); i++) {
            AdminRoomsPacket.Owner o = owners.get(ownerScroll + i);
            int ry = top + i * ROW;
            boolean on = o.id().equals(picked);
            if (on) {
                g.fill(lx + 1, ry, lx + LEFT_W - 1, ry + ROW, PICKED);
                g.fill(lx + 1, ry, lx + 3, ry + ROW, Ui.VIOLET);
            } else if (mx >= lx && mx < lx + LEFT_W && my >= ry && my < ry + ROW) {
                g.fill(lx + 1, ry, lx + LEFT_W - 1, ry + ROW, 0x22B58CFF);
            }
            String name = Ui.fit(font, ownerName(o), cRooms - lx - 9, SMALL);
            Ui.text(g, font, Component.literal(name).withStyle(o.name().isEmpty() ? ChatFormatting.ITALIC : ChatFormatting.BOLD), lx + 5, ry + 1, o.name().isEmpty() ? Ui.MUTED : Ui.TEXT, SMALL);
            String rooms = limit > 0 && !o.exempt() ? o.count() + " / " + limit : Integer.toString(o.count());
            Ui.text(g, font, rooms, cRooms, ry + 2, Ui.TEXT, TINY);
            int over = o.over(limit);
            if (o.exempt()) {
                Ui.text(g, font, Tr.t("rooms_admin.exempt"), cOver, ry + 2, Ui.VIOLET, TINY);
            } else if (over > 0) {
                Ui.text(g, font, Component.literal("+" + over).withStyle(ChatFormatting.BOLD), cOver, ry + 2, RED, TINY);
            } else {
                Ui.text(g, font, "·", cOver, ry + 2, Ui.MUTED, TINY);
            }
            Ui.text(g, font, Integer.toString(o.servers()), cServers, ry + 2, Ui.MUTED, TINY);
            UUID id = o.id();
            hits.add(new Ui.Hit(lx, ry, LEFT_W, ROW, () -> pick(id)));
        }
    }

    private void pick(UUID id) {
        if (id.equals(picked)) {
            return;
        }
        picked = id;
        roomScroll = 0;
        sureKey = "";
        refresh();
    }

    @Nullable
    private AdminRoomsPacket.Owner pickedOwner() {
        if (data == null) {
            return null;
        }
        for (AdminRoomsPacket.Owner o : data.owners()) {
            if (o.id().equals(picked)) {
                return o;
            }
        }
        return null;
    }

    private void drawRooms(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + TOP;
        Ui.inset(g, px, py, RIGHT_W, BOX_H);
        AdminRoomsPacket.Owner owner = pickedOwner();
        boolean current = data != null && data.picked().equals(picked) && !AdminRoomsPacket.NONE.equals(picked);
        if (owner == null || !current) {
            Ui.text(g, font, Ui.fit(font, Tr.t("rooms_admin.pick"), RIGHT_W - 10, TINY), px + 5, py + 5, Ui.MUTED, TINY);
            drawFoot(g, px, py, null);
            return;
        }
        int limit = data.limit();
        String head = Tr.t("rooms_admin.picked", ownerName(owner), data.rooms().size()).toUpperCase(Locale.ROOT);
        int over = owner.over(limit);
        String overText = over > 0 ? Tr.t("rooms_admin.over_by", over) : owner.exempt() ? Tr.t("rooms_admin.exempt") : "";
        int ow = Ui.width(font, overText, TINY);
        Ui.text(g, font, Component.literal(Ui.fit(font, head, RIGHT_W - 14 - ow, TINY)).withStyle(ChatFormatting.BOLD), px + 5, py + 5, Ui.VIOLET, TINY);
        if (!overText.isEmpty()) {
            Ui.text(g, font, overText, px + RIGHT_W - 5 - ow, py + 5, over > 0 ? RED : Ui.VIOLET, TINY);
        }
        List<RoomsViewPacket.Room> rooms = data.rooms();
        int top = py + 15, visible = (BOX_H - 15 - 22) / ROW;
        roomScroll = Math.max(0, Math.min(roomScroll, Math.max(0, rooms.size() - visible)));
        int cName = px + 18, cServer = px + 60, cState = px + 98, cKey = px + RIGHT_W - 25;
        RoomsViewPacket.Room hovered = null;
        long now = System.currentTimeMillis();
        for (int i = 0; i < visible && roomScroll + i < rooms.size(); i++) {
            RoomsViewPacket.Room r = rooms.get(roomScroll + i);
            int ry = top + i * ROW;
            String key = r.server() + "|" + r.x() + "|" + r.z();
            boolean sure = key.equals(sureKey) && now < sureUntil;
            boolean hover = mx >= px && mx < px + RIGHT_W && my >= ry && my < ry + ROW;
            if (hover || sure) {
                g.fill(px + 1, ry, px + RIGHT_W - 1, ry + ROW, PICKED);
            }
            if (hover) {
                hovered = r;
            }
            Ui.text(g, font, Integer.toString(r.size()), px + 5, ry + 2, Ui.TEXT, TINY);
            boolean named = !r.name().isEmpty();
            Ui.text(g, font, Component.literal(Ui.fit(font, named ? r.name() : "·", cServer - cName - 3, TINY)).withStyle(named ? ChatFormatting.BOLD : ChatFormatting.RESET),
                    cName, ry + 2, named ? Ui.TEXT : Ui.MUTED, TINY);
            boolean here = r.here(data.server());
            Ui.text(g, font, Ui.fit(font, r.server(), cState - cServer - 3, TINY), cServer, ry + 2, here ? Ui.VIOLET : Ui.MUTED, TINY);
            String state;
            int stateColour = Ui.MUTED;
            if (r.grace()) {
                state = Tr.t("rooms_admin.grace", Math.max(1, (r.graceLeftMs() + 86_399_999L) / 86_400_000L));
                stateColour = AMBER;
            } else if (r.machines() > 1) {
                state = Tr.t("rooms_admin.placed_n", r.machines());
            } else if (r.machines() == 1) {
                state = Tr.t("rooms_admin.placed");
            } else {
                state = Tr.t("rooms_admin.not_placed");
            }
            Ui.text(g, font, Ui.fit(font, state, cKey - cState - 3, TINY), cState, ry + 2, stateColour, TINY);
            switch (r.give()) {
                case RoomsViewPacket.GIVE_OK -> {
                    if (sure) {
                        g.fill(cKey, ry + 1, cKey + 22, ry + ROW - 1, 0xFFD63B2F);
                        String s = Tr.t("rooms_admin.sure");
                        Ui.text(g, font, Component.literal(Ui.fit(font, s, 20, TINY)).withStyle(ChatFormatting.BOLD), cKey + (22 - Ui.width(font, s, TINY)) / 2, ry + 2, 0xFFFFFFFF, TINY);
                    } else {
                        Ui.text(g, font, Component.literal(Tr.t("rooms_admin.free")).withStyle(ChatFormatting.BOLD), cKey + 2, ry + 2, RED, TINY);
                    }
                    hits.add(new Ui.Hit(cKey, ry, 22, ROW, () -> free(r, key)));
                }
                case RoomsViewPacket.GIVE_PLACED -> Ui.text(g, font, Tr.t("rooms_admin.free"), cKey + 2, ry + 2, OFF, TINY);
                case RoomsViewPacket.GIVE_ELSEWHERE -> Ui.text(g, font, Ui.fit(font, Tr.t("rooms_admin.there"), 22, TINY), cKey + 2, ry + 2, Ui.MUTED, TINY);
                default -> Ui.text(g, font, "·", cKey + 2, ry + 2, Ui.MUTED, TINY);
            }
        }
        drawFoot(g, px, py, hovered);
    }

    /** The two lines at the foot of the rooms box: a note after FREE, else the hovered room's details, else the hint. */
    private void drawFoot(GuiGraphics g, int px, int py, @Nullable RoomsViewPacket.Room r) {
        Font font = host.font();
        int fy = py + BOX_H - 20, w = RIGHT_W - 10;
        List<String> lines = new ArrayList<>();
        int colour = Ui.MUTED;
        if (System.currentTimeMillis() < noteUntil) {
            lines.addAll(Ui.wrap(font, note, w, TINY));
            colour = Ui.TEXT;
        } else if (r != null && data != null) {
            lines.add(Tr.t("rooms_admin.detail", r.x(), r.z(), r.server()));
            if (r.grace()) {
                String whyKey = "rooms.reason." + reasonKey(r.reason()); // built first: tools/check_lang.py reads a joined literal as a missing key
                String why = Tr.t(whyKey);
                lines.add(Tr.t("rooms_admin.detail_grace", why, Math.max(1, (r.graceLeftMs() + 86_399_999L) / 86_400_000L)));
            } else if (r.give() == RoomsViewPacket.GIVE_PLACED || r.machines() > 0) {
                lines.add(r.machines() > 1 ? Tr.t("rooms_admin.detail_machines", r.machines(), r.machine()) : Tr.t("rooms_admin.detail_machine", r.machine()));
            } else if (r.give() == RoomsViewPacket.GIVE_ELSEWHERE) {
                lines.add(Tr.t("rooms_admin.why_elsewhere", r.server()));
            }
            if (r.give() == RoomsViewPacket.GIVE_PLACED) {
                lines.add(Tr.t("rooms_admin.why_placed"));
            }
        } else {
            lines.addAll(Ui.wrap(font, Tr.t("rooms_admin.hover"), w, TINY));
        }
        for (int i = 0; i < lines.size() && i < 3; i++) {
            Ui.text(g, font, Ui.fit(font, lines.get(i), w, TINY), px + 5, fy + i * 6, colour, TINY);
        }
    }

    /** A reason the lang file knows ("destroyed" otherwise, so an unknown one never shows as a raw key). */
    private static String reasonKey(String reason) {
        return switch (reason) {
            case "burnt", "exploded", "cactus", "crushed", "destroyed", "despawned", "cleared", "void", "inside", "given_back", "freed" -> reason;
            default -> "destroyed";
        };
    }

    private void free(RoomsViewPacket.Room r, String key) {
        long now = System.currentTimeMillis();
        if (!key.equals(sureKey) || now >= sureUntil) {
            sureKey = key;
            sureUntil = now + SURE_MS;
            return;
        }
        sureKey = "";
        sureUntil = 0;
        refreshIn = REFRESH_TICKS;
        send(new AdminRoomsEditPacket(AdminRoomsEditPacket.Action.FREE, picked, r.x(), r.z()));
    }

    private void drawFooter(GuiGraphics g, int x, int y, List<Ui.Hit> hits) {
        Font font = host.font();
        int fy = y + AdminScreen.H - 8 - 14;
        String key = Tr.t("rooms_admin.refresh");
        int kw = Ui.width(font, key, TINY) + 16, kx = x + AdminScreen.W - 8 - kw;
        Ui.frame(g, kx, fy, kw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(key).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, kw, 14, this::open));
        if (data == null) {
            return;
        }
        int w = kx - x - 16;
        String limit = data.limit() > 0 ? Integer.toString(data.limit()) : Tr.t("rooms_admin.no_limit");
        String scope = Tr.t(data.network() ? "rooms_admin.scope_network" : "rooms_admin.scope_server");
        Ui.text(g, font, Ui.fit(font, Tr.t("rooms_admin.foot", limit, scope, data.owners().size(), data.inGrace()), w, TINY), x + 8, fy + 1, Ui.MUTED, TINY);
        String second;
        int colour;
        if (data.unread()) {
            second = Tr.t("rooms_admin.unread");
            colour = AMBER;
        } else if (data.stale().isEmpty()) {
            second = data.network() ? Tr.t("rooms_admin.heard") : "";
            colour = Ui.MUTED;
        } else {
            List<String> parts = new ArrayList<>();
            for (AdminRoomsPacket.Stale s : data.stale()) {
                parts.add(Tr.t("rooms_admin.stale", s.server(), Math.max(5, s.ageMs() / 60_000L), s.server()));
            }
            second = String.join(" · ", parts);
            colour = AMBER;
        }
        if (!second.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, second, w, TINY), x + 8, fy + 8, colour, TINY);
        }
    }

    boolean scroll(double mx, double delta) {
        int step = (int) Math.signum(delta);
        if (mx < drawnX + RIGHT_X) {
            ownerScroll -= step;
        } else {
            roomScroll -= step;
        }
        return true;
    }
}
