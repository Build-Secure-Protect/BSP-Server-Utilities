package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TutorialBlobPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialEditPacket;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tutorial builder (SU-142; owner decisions D719, D720, D728, D734, D737; look AV A of
 * {@code tools/preview/tutorial_refined.html}): tabs for the tutorials (★ the first-join one) and + for a new one; the
 * draft's state, TEST and PUBLISH; a canvas of the points as nodes (dragged to move, right-dragged onto another to link,
 * the canvas dragged to pan and scrolled to zoom) with the links as stepped lines (gold: a walked path; grey dashes: no
 * path, the next popup opens at once; violet dots: another server or world; red dashes: a path still to draw); a drawer
 * for the picked point (its title, popup, place, radius, links, buttons), the picked link, or the tutorial's settings
 * (name, start, who the first-join one is for, the start area, the offer's text, the counts). A red dot marks what is
 * missing; PUBLISH asks again while any are left. Every change is saved to the draft a second after the last one.
 */
public class TutorialBuilderScreen extends Screen {
    private static final int M = 6, BAR = 20, STRIP = 14, DRAWER = 132, FOOT = 15, NODE = 20, SAVE_TICKS = 20;
    private static final long SURE_MS = 3_000L;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    @Nullable
    private final Screen parent;
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private Ui.Hit pressed;
    private int seenView = -1;
    private final List<CompoundTag> list = new ArrayList<>();
    private String selected = "", server = "";
    @Nullable
    private Tutorial draft;
    private CompoundTag counts = new CompoundTag();
    private boolean dirty;
    private int dirtyTicks;
    private int pickedPoint = -1, linkFrom = -1, linkTo = -1;
    private boolean settings;
    private float panX = 40, panY = 100, zoom = 1;
    private int dragNode = -1, linkingFrom = -1;
    private boolean panning;
    private double dragX, dragY, downX, downY;
    private long publishSureUntil, deleteSureUntil;
    private String note = "";
    private long noteUntil;
    @Nullable
    private EditBox titleBox, nameBox;
    @Nullable
    private MultiLineEditBox offerBox;

    public TutorialBuilderScreen(@Nullable Screen parent) {
        super(Component.literal(Tr.t("tutorial.builder")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (seenView < 0) {
            ServerUtilsNetwork.CHANNEL.sendToServer(TutorialEditPacket.of(TutorialEditPacket.Action.OPEN, selected));
        }
        widgets();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ the data

    private void readView() {
        CompoundTag v = ClientTutorialAdmin.view();
        if (v == null || ClientTutorialAdmin.viewVersion() == seenView) {
            return;
        }
        String sel = v.getString("selected");
        boolean other = !sel.equals(selected);
        // a view sent before the last save reached the server is not taken over a newer local draft; it is left unread
        // and read again once the save has settled, so a view arriving just after a save is never lost (audit of SU-146)
        boolean settling = Util.getMillis() - savedAt < 1500;
        if (!other && settling) {
            return;
        }
        seenView = ClientTutorialAdmin.viewVersion();
        list.clear();
        for (Tag t : v.getList("list", Tag.TAG_COMPOUND)) {
            list.add((CompoundTag) t);
        }
        server = v.getString("server");
        counts = v.getCompound("counts");
        publishedMax = v.contains("publishedMax") ? v.getInt("publishedMax") : -1;
        if (!other && dirty) {
            // unsaved local changes: keep them, but take what only the server sets (places, paths, the area)
            Tutorial from = v.contains("draft") ? Tutorial.load(v.getCompound("draft")) : null;
            if (draft != null && from != null && from.id().equals(draft.id())) {
                draft = serverParts(draft, from);
                String me = minecraft != null && minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
                if (from.editedBy().equals(me)) {
                    base = from.revision(); // our own save came back; another admin's does not move the base
                }
            }
            return;
        }
        selected = sel;
        Tutorial was = draft;
        draft = v.contains("draft") ? Tutorial.load(v.getCompound("draft")) : null;
        if (draft != null) {
            base = draft.revision();
        }
        if (other || was == null || draft == null) {
            pickedPoint = linkFrom = linkTo = -1;
            settings = false;
            widgets();
        } else {
            if (pickedPoint >= 0 && draft.point(pickedPoint) == null) {
                pickedPoint = -1;
                widgets();
            }
            syncFields();
        }
    }

    /**
     * The local draft with what only the server sets taken from its copy: each point's place (the local radius kept),
     * each link's path and the start area. SET TO HERE, a drawn path or a set area reach the builder this way while it
     * has unsaved changes, so the next save does not wipe them (audit of SU-146).
     */
    private static Tutorial serverParts(Tutorial local, Tutorial server) {
        List<Tutorial.Point> ps = new ArrayList<>();
        for (Tutorial.Point p : local.points()) {
            Tutorial.Point sp = server.point(p.id());
            if (sp != null) {
                Tutorial.Place a = sp.place();
                ps.add(p.withPlace(new Tutorial.Place(a.server(), a.dimension(), a.x(), a.y(), a.z(), p.place().radius())));
            } else {
                ps.add(p);
            }
        }
        List<Tutorial.Link> ls = new ArrayList<>();
        for (Tutorial.Link l : local.links()) {
            Tutorial.Link found = null;
            for (Tutorial.Link sl : server.links()) {
                if (sl.from() == l.from() && sl.to() == l.to()) {
                    found = sl;
                }
            }
            ls.add(found != null ? l.withPath(found.path()) : l);
        }
        return local.withPoints(ps).withLinks(ls).withArea(server.area());
    }

    /** The boxes show the draft's values without being made again (typing in one is left alone). */
    private void syncFields() {
        if (draft == null) {
            return;
        }
        if (nameBox != null && !nameBox.isFocused() && !nameBox.getValue().equals(draft.name())) {
            nameBox.setValue(draft.name());
        }
        if (offerBox != null && !offerBox.isFocused() && !offerBox.getValue().equals(draft.offer())) {
            offerBox.setValue(draft.offer());
        }
        Tutorial.Point p = pickedPoint < 0 ? null : draft.point(pickedPoint);
        if (titleBox != null && p != null && !titleBox.isFocused() && !titleBox.getValue().equals(p.title())) {
            titleBox.setValue(p.title());
        }
    }

    private long savedAt;
    /**
     * The revision of the draft last taken from a view, sent with each save: the server refuses a save made on an older
     * copy than another admin's (audit of SU-146).
     */
    private long base;
    /** The highest point id the published copy ever used (-1 for none): new points go above it (audit of SU-146). */
    private int publishedMax = -1;

    private void changed(Tutorial next) {
        draft = next;
        dirty = true;
        dirtyTicks = 0;
    }

    /** Sends the draft now when it has unsaved changes (before any action the server does on it). */
    void flush() {
        if (dirty && draft != null) {
            CompoundTag tag = new CompoundTag();
            tag.put("tutorial", draft.save());
            tag.putLong("base", base);
            for (TutorialBlobPacket p : TutorialBlobPacket.split(TutorialBlobPacket.Kind.SAVE, (int) (Util.getMillis() & 0x7FFFFFFF), tag)) {
                ServerUtilsNetwork.CHANNEL.sendToServer(p);
            }
            dirty = false;
            savedAt = Util.getMillis();
        }
    }

    private void action(TutorialEditPacket.Action a, int x, int y) {
        flush();
        ServerUtilsNetwork.CHANNEL.sendToServer(new TutorialEditPacket(a, selected, x, y, ""));
    }

    @Override
    public void tick() {
        readView();
        if (dirty && ++dirtyTicks >= SAVE_TICKS) {
            flush();
        }
        for (EditBox b : new EditBox[]{titleBox, nameBox}) {
            if (b != null) {
                b.tick();
            }
        }
        if (offerBox != null) {
            offerBox.tick();
        }
    }

    /** Another screen in its place (the editor, a death screen, path mode): unsaved changes go first (audit of SU-146). */
    @Override
    public void removed() {
        flush();
        super.removed();
    }

    @Override
    public void onClose() {
        flush();
        ServerUtilsNetwork.CHANNEL.sendToServer(TutorialEditPacket.of(TutorialEditPacket.Action.CLOSE, selected));
        minecraft.setScreen(parent);
    }

    /** From the popup editor: the point with its new popup. */
    void popupEdited(int point, TutorialPopup popup) {
        if (draft == null) {
            return;
        }
        List<Tutorial.Point> ps = new ArrayList<>();
        for (Tutorial.Point p : draft.points()) {
            ps.add(p.id() == point ? p.withPopup(popup) : p);
        }
        changed(draft.withPoints(ps).sanitised());
        flush();
    }

    @Nullable
    Tutorial draft() {
        return draft;
    }

    // ------------------------------------------------------------------ layout

    private int left() {
        return M;
    }

    private int top() {
        return M;
    }

    private int canvasX() {
        return left() + 2;
    }

    private int canvasY() {
        return top() + BAR + STRIP + 4;
    }

    private int canvasW() {
        return width - 2 * M - DRAWER - 8;
    }

    private int canvasH() {
        return height - 2 * M - BAR - STRIP - FOOT - 8;
    }

    private int drawerX() {
        return width - M - DRAWER - 2;
    }

    private int nodeX(Tutorial.Point p) {
        return Math.round(canvasX() + panX + p.cx() * zoom);
    }

    private int nodeY(Tutorial.Point p) {
        return Math.round(canvasY() + panY + p.cy() * zoom);
    }

    /** The drawer's boxes, made again whenever what it shows changes. */
    private void widgets() {
        setFocused(null); // a box made again must not keep taking keys (audit of SU-146)
        clearWidgets();
        titleBox = nameBox = null;
        offerBox = null;
        if (draft == null || width == 0) {
            return;
        }
        int dx = drawerX() + 5, dw = DRAWER - 10;
        if (settings) {
            nameBox = new EditBox(font, dx, canvasY() + 22, dw, 12, Component.literal(Tr.t("tutorial.b.name")));
            nameBox.setMaxLength(Tutorial.MAX_NAME);
            nameBox.setValue(draft.name());
            nameBox.setResponder(s -> {
                if (draft != null && !s.equals(draft.name())) {
                    changed(draft.withName(s));
                }
            });
            addRenderableWidget(nameBox);
            // placed under the start rows (which may wrap) and kept off the counts and DELETE TUTORIAL at the foot; 9
            // narrower, as the box draws its scroll bar outside its right edge (audit of SU-146)
            int[] offer = offerRect();
            offerBox = new MultiLineEditBox(font, dx, offer[0], dw - 9, offer[1], Component.literal(Tr.t("tutorial.b.offer_hint")), Component.literal(Tr.t("tutorial.b.offer")));
            offerBox.setCharacterLimit(Tutorial.MAX_OFFER);
            offerBox.setValue(draft.offer());
            offerBox.setValueListener(s -> {
                if (draft != null && !s.equals(draft.offer())) {
                    changed(draft.withOffer(s));
                }
            });
            addRenderableWidget(offerBox);
        } else if (pickedPoint >= 0 && draft.point(pickedPoint) != null) {
            Tutorial.Point p = draft.point(pickedPoint);
            titleBox = new EditBox(font, dx, canvasY() + 6, dw - 22, 12, Component.literal(Tr.t("tutorial.b.title")));
            titleBox.setMaxLength(Tutorial.MAX_TITLE);
            titleBox.setValue(p.title());
            int id = p.id();
            titleBox.setResponder(s -> {
                Tutorial.Point now = draft == null ? null : draft.point(id);
                if (now != null && !s.equals(now.title()) && !s.isBlank()) {
                    replace(new Tutorial.Point(id, s, now.icon(), now.cx(), now.cy(), now.place(), now.popup()));
                }
            });
            addRenderableWidget(titleBox);
        }
    }

    /** How many rows of keys the labels take in a width, a new row when the next key would pass it (audit of SU-146). */
    private int keyRows(List<String> labels, int w, int gap) {
        int rows = 1, used = 0;
        for (String l : labels) {
            int kw = keyW(l);
            if (used > 0 && used + kw > w) {
                rows++;
                used = 0;
            }
            used += kw + gap;
        }
        return rows;
    }

    /** Draws keys left to right, wrapping as {@link #keyRows} counts; returns the y under the last row. */
    private int keyFlow(GuiGraphics g, int dx, int cy, int dw, int gap, List<String> labels, java.util.function.IntUnaryOperator colour,
                        List<Runnable> actions, int mx, int my) {
        int kx = dx;
        for (int i = 0; i < labels.size(); i++) {
            int kw = keyW(labels.get(i));
            if (kx > dx && kx + kw > dx + dw) {
                kx = dx;
                cy += 14;
            }
            kx += key(g, kx, cy, labels.get(i), colour.applyAsInt(i), mx, my, actions.get(i)) + gap;
        }
        return cy + 12;
    }

    private List<String> startLabels() {
        List<String> out = new ArrayList<>();
        for (Tutorial.Start s : Tutorial.Start.values()) {
            out.add(TutorialDraw.word(s));
        }
        return out;
    }

    private List<String> audienceLabels() {
        List<String> out = new ArrayList<>();
        for (Tutorial.Audience a : Tutorial.Audience.values()) {
            out.add(TutorialDraw.word(a));
        }
        return out;
    }

    /** The settings drawer's y under the start keys, where the second section (offered to, or the start area) begins. */
    private int settingsSectionY() {
        return canvasY() + 46 + 14 * keyRows(startLabels(), DRAWER - 10, 2) + 3;
    }

    /**
     * The offer box's top and height: under the second section, at most 34 tall, leaving the counts, one ended-at line
     * and DELETE TUTORIAL at the drawer's foot (audit of SU-146).
     */
    private int[] offerRect() {
        int y = canvasY(), h = canvasH();
        int rows = draft != null && draft.start() == Tutorial.Start.FIRST_JOIN ? keyRows(audienceLabels(), DRAWER - 10, 2) : 1;
        int top = settingsSectionY() + 8 + 14 * rows + 2 + 8;
        int height = Math.max(12, Math.min(34, y + h - 36 - top));
        return new int[]{top, height};
    }

    private void replace(Tutorial.Point point) {
        List<Tutorial.Point> ps = new ArrayList<>();
        for (Tutorial.Point p : draft.points()) {
            ps.add(p.id() == point.id() ? point : p);
        }
        changed(draft.withPoints(ps));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        readView();
        renderBackground(g);
        hits.clear();
        int x = left(), y = top(), w = width - 2 * M, h = height - 2 * M;
        g.fill(x, y, x + w, y + h, Ui.PANEL);
        Ui.frame(g, x, y, w, h, Ui.VIOLET);
        bar(g, x, y, w, mx, my);
        if (draft == null) {
            String s = list.isEmpty() ? Tr.t("tutorial.b.empty") : Tr.t("tutorial.b.loading");
            Ui.text(g, font, s, x + 12, y + BAR + 14, Ui.MUTED, SMALL);
            if (list.isEmpty()) {
                key(g, x + 12, y + BAR + 28, Tr.t("tutorial.b.new"), Ui.VIOLET, mx, my, this::create);
            }
            super.render(g, mx, my, partial);
            return;
        }
        strip(g, x, y + BAR, w, mx, my);
        canvas(g, mx, my);
        drawer(g, mx, my);
        // the foot
        int fy = y + h - FOOT;
        g.fill(x + 1, fy, x + w - 1, y + h - 1, Ui.SLOT);
        // the hint and the legend on two lines of their own, each the panel's width (audit of SU-146)
        String hint = Util.getMillis() < noteUntil ? note : Tr.t("tutorial.b.hint");
        Ui.text(g, font, Ui.fit(font, hint, w - 12, TINY), x + 6, fy + 2, Util.getMillis() < noteUntil ? Ui.GOLD : 0xFF6B7180, TINY);
        Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.b.legend"), w - 12, TINY), x + 6, fy + 8, 0xFF6B7180, TINY);
        super.render(g, mx, my, partial);
    }

    private void bar(GuiGraphics g, int x, int y, int w, int mx, int my) {
        g.fill(x + 1, y + BAR - 1, x + w - 1, y + BAR, Ui.DIM);
        Ui.text(g, font, Component.literal(Tr.t("tutorial.b.title_bar")).withStyle(ChatFormatting.BOLD), x + 8, y + 7, Ui.VIOLET, 0.8f);
        int tx = x + 8 + Ui.widthBold(font, Tr.t("tutorial.b.title_bar"), 0.8f) + 8;
        // the right side first, so the tabs know their room
        int rx = x + w - 6;
        rx -= 10;
        Ui.text(g, font, "✕", rx, y + 6, Ui.MUTED, 1f);
        hits.add(new Ui.Hit(rx - 2, y + 3, 12, 14, this::onClose));
        if (draft != null) {
            boolean sure = Util.getMillis() < publishSureUntil;
            int issues = draft.issues().size();
            String pub = sure ? Tr.t("tutorial.b.publish_sure", issues) : Tr.t("tutorial.b.publish");
            // the slot is as wide as the SURE label whenever it could show, so the tabs do not move when it does (audit of SU-146)
            int slot = issues > 0 ? Math.max(keyW(Tr.t("tutorial.b.publish")), keyW(Tr.t("tutorial.b.publish_sure", issues))) : keyW(pub);
            rx -= 6 + slot;
            key(g, rx + slot - keyW(pub), y + 3, pub, sure ? Ui.BAD : Ui.VIOLET, mx, my, this::publish);
            String test = Tr.t("tutorial.b.test");
            rx -= 4 + keyW(test);
            key(g, rx, y + 3, test, Ui.DIM, mx, my, () -> action(TutorialEditPacket.Action.TEST, -1, -1));
            CompoundTag me = entry(selected);
            String state = me == null || !me.getBoolean("published") ? Tr.t("tutorial.b.state_draft") : me.getBoolean("changed") || dirty ? Tr.t("tutorial.b.state_changed") : Tr.t("tutorial.b.state_live");
            int sw = Ui.width(font, state, TINY);
            rx -= 6 + sw;
            Ui.text(g, font, state, rx, y + 8, me != null && me.getBoolean("published") && !me.getBoolean("changed") && !dirty ? Ui.TEAL : Ui.GOLD, TINY);
        }
        // the tabs; when they do not all fit, the row starts late enough that the picked one shows (audit of SU-146)
        int[] tws = new int[list.size()];
        int picked = -1;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.get(i);
            String label = (e.getBoolean("firstJoin") ? "★ " : "") + e.getString("name");
            tws[i] = Math.min(90, Ui.width(font, label.toUpperCase(Locale.ROOT), TINY) + 12);
            if (e.getString("id").equals(selected)) {
                picked = i;
            }
        }
        int first = 0;
        while (first < picked) {
            int end = tx;
            for (int i = first; i <= picked; i++) {
                end += tws[i] + 1;
            }
            if (end - 1 <= rx - 30) {
                break;
            }
            first++;
        }
        for (int i = first; i < list.size(); i++) {
            CompoundTag e = list.get(i);
            String label = (e.getBoolean("firstJoin") ? "★ " : "") + e.getString("name");
            int tw = tws[i];
            if (tx + tw > rx - 30) {
                break;
            }
            boolean on = e.getString("id").equals(selected);
            g.fill(tx, y + 1, tx + tw, y + BAR - 1, on ? Ui.SLOT : Ui.PANEL);
            if (on) {
                g.fill(tx, y + BAR - 3, tx + tw, y + BAR - 1, Ui.VIOLET);
            }
            Ui.text(g, font, Ui.fit(font, label.toUpperCase(Locale.ROOT), tw - 8, TINY), tx + 5, y + 8, on ? Ui.VIOLET : 0xFF6B7180, TINY);
            String id = e.getString("id");
            hits.add(new Ui.Hit(tx, y + 1, tw, BAR - 2, () -> pick(id)));
            tx += tw + 1;
        }
        if (tx + 14 < rx - 20) {
            Ui.text(g, font, "+", tx + 4, y + 6, Ui.MUTED, 1f);
            hits.add(new Ui.Hit(tx, y + 1, 14, BAR - 2, this::create));
        }
    }

    @Nullable
    private CompoundTag entry(String id) {
        for (CompoundTag e : list) {
            if (e.getString("id").equals(id)) {
                return e;
            }
        }
        return null;
    }

    private void strip(GuiGraphics g, int x, int y, int w, int mx, int my) {
        int cx = x + 8, cy = y + 3;
        Ui.text(g, font, Tr.t("tutorial.b.starts").toUpperCase(Locale.ROOT), cx, cy + 2, 0xFF6B7180, TINY);
        cx += Ui.width(font, Tr.t("tutorial.b.starts").toUpperCase(Locale.ROOT), TINY) + 4;
        cx += chip(g, cx, cy, TutorialDraw.word(draft.start()), Ui.TEAL) + 4;
        if (draft.start() == Tutorial.Start.FIRST_JOIN) {
            cx += chip(g, cx, cy, TutorialDraw.word(draft.audience()), Ui.MUTED) + 4;
        }
        int forks = 0;
        for (Tutorial.Point p : draft.points()) {
            if (draft.children(p.id()).size() > 1) {
                forks++;
            }
        }
        String info = Tr.t("tutorial.b.info", draft.points().size(), forks);
        Ui.text(g, font, info, cx + 2, cy + 2, 0xFF6B7180, TINY);
        int issues = draft.issues().size();
        if (issues > 0) {
            String is = Tr.t("tutorial.b.issues", issues);
            Ui.text(g, font, is, cx + 8 + Ui.width(font, info, TINY), cy + 2, Ui.BAD, TINY);
        }
        String set = Tr.t("tutorial.b.settings");
        int kw = keyW(set);
        key(g, x + w - 6 - kw, cy - 1, set, settings ? Ui.VIOLET : Ui.DIM, mx, my, () -> {
            settings = !settings;
            pickedPoint = linkFrom = linkTo = -1;
            widgets();
        });
        String add = Tr.t("tutorial.b.add_point");
        key(g, x + w - 10 - kw - keyW(add), cy - 1, add, Ui.DIM, mx, my, this::addPoint);
    }

    private int chip(GuiGraphics g, int x, int y, String s, int colour) {
        String t = s.toUpperCase(Locale.ROOT);
        int w = Ui.width(font, t, TINY) + 6;
        Ui.frame(g, x, y, w, 9, colour & 0x80FFFFFF);
        Ui.text(g, font, t, x + 3, y + 2, colour, TINY);
        return w;
    }

    // ------------------------------------------------------------------ the canvas

    private void canvas(GuiGraphics g, int mx, int my) {
        int x0 = canvasX(), y0 = canvasY(), w = canvasW(), h = canvasH();
        Ui.inset(g, x0, y0, w, h);
        Ui.scissor(g, x0 + 1, y0 + 1, x0 + w - 1, y0 + h - 1);
        // the dots
        int step = Math.max(6, Math.round(10 * zoom));
        int ox = Math.floorMod(Math.round(panX), step), oy = Math.floorMod(Math.round(panY), step);
        for (int gx = x0 + ox; gx < x0 + w; gx += step) {
            for (int gy = y0 + oy; gy < y0 + h; gy += step) {
                g.fill(gx, gy, gx + 1, gy + 1, 0x289AA3B5);
            }
        }
        // the links
        for (Tutorial.Link l : draft.links()) {
            Tutorial.Point a = draft.point(l.from()), b = draft.point(l.to());
            if (a == null || b == null) {
                continue;
            }
            int ax = nodeX(a) + NODE / 2, ay = nodeY(a), bx = nodeX(b) - NODE / 2, by = nodeY(b);
            int colour;
            int dash;
            if (!a.place().isSet() || !b.place().isSet()) {
                colour = 0xFF6B7180;
                dash = 3;
            } else if (!a.place().sameWorld(b.place())) {
                colour = Ui.VIOLET;
                dash = 1;
            } else if (l.path().isEmpty()) {
                colour = Ui.BAD;
                dash = 3;
            } else {
                colour = Ui.GOLD;
                dash = 0;
            }
            boolean picked = l.from() == linkFrom && l.to() == linkTo;
            int mid = bx > ax ? (ax + bx) / 2 : ax + 8;
            hLine(g, ax, mid, ay, colour, dash, picked);
            vLine(g, mid, ay, by, colour, dash, picked);
            hLine(g, mid, bx, by, colour, dash, picked);
            int from = l.from(), to = l.to();
            int hy = (ay + by) / 2;
            if (mid < x0 || mid >= x0 + w || hy < y0 || hy >= y0 + h) {
                continue; // a link's handle off the canvas takes no clicks (audit of SU-146)
            }
            hits.add(new Ui.Hit(mid - 4, hy - 4, 9, 9, () -> {
                linkFrom = from;
                linkTo = to;
                pickedPoint = -1;
                settings = false;
                widgets();
            }));
        }
        if (linkingFrom >= 0 && draft.point(linkingFrom) != null) {
            Tutorial.Point a = draft.point(linkingFrom);
            hLine(g, nodeX(a), mx, nodeY(a), Ui.VIOLET, 2, false);
            vLine(g, mx, nodeY(a), my, Ui.VIOLET, 2, false);
        }
        // the nodes
        Set<Integer> marked = new HashSet<>();
        for (Tutorial.Issue i : draft.issues()) {
            if (i.point() >= 0) {
                marked.add(i.point());
            }
            if (i.from() >= 0) {
                marked.add(i.from());
            }
        }
        List<Integer> starts = draft.starts();
        for (Tutorial.Point p : draft.points()) {
            int nx = nodeX(p) - NODE / 2, ny = nodeY(p) - NODE / 2;
            boolean fork = draft.children(p.id()).size() > 1, sel = p.id() == pickedPoint;
            g.fill(nx, ny, nx + NODE, ny + NODE, Ui.PANEL);
            int edge = sel ? Ui.VIOLET : fork ? Ui.GOLD : starts.contains(p.id()) ? Ui.TEAL : 0xFF3A4150;
            Ui.frame(g, nx, ny, NODE, NODE, edge);
            if (sel) {
                Ui.frame(g, nx - 2, ny - 2, NODE + 4, NODE + 4, 0x59B58CFF);
            }
            g.renderItem(icon(p.icon()), nx + 2, ny + 2);
            String t = Ui.fit(font, p.title(), 70, TINY);
            Ui.text(g, font, t, nx + NODE / 2 - Ui.width(font, t, TINY) / 2, ny + NODE + 2, sel ? Ui.VIOLET : Ui.MUTED, TINY);
            if (marked.contains(p.id())) {
                g.fill(nx + NODE - 3, ny - 2, nx + NODE + 2, ny + 3, Ui.BAD);
            }
        }
        g.disableScissor();
        // zoom keys, drawn over the node icons (items draw at depth 150; audit of SU-146)
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        int zx = x0 + w - 62, zy = y0 + h - 15;
        key(g, zx, zy, "−", Ui.DIM, mx, my, () -> zoomBy(1 / 1.25f, x0 + w / 2.0, y0 + h / 2.0));
        String pct = Math.round(zoom * 100) + "%";
        Ui.text(g, font, pct, zx + 20, zy + 4, Ui.MUTED, TINY);
        key(g, zx + 44, zy, "+", Ui.DIM, mx, my, () -> zoomBy(1.25f, x0 + w / 2.0, y0 + h / 2.0));
        g.pose().popPose();
    }

    private static ItemStack icon(String id) {
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(id));
        return new ItemStack(item == Items.AIR ? Items.COMPASS : item);
    }

    private void hLine(GuiGraphics g, int x1, int x2, int y, int c, int dash, boolean thick) {
        int a = Math.min(x1, x2), b = Math.max(x1, x2), t = thick ? 2 : 1;
        for (int x = a; x < b; x++) {
            if (dash == 0 || (x / (dash + 1)) % 2 == 0) {
                g.fill(x, y, x + 1, y + t, c);
            }
        }
    }

    private void vLine(GuiGraphics g, int x, int y1, int y2, int c, int dash, boolean thick) {
        int a = Math.min(y1, y2), b = Math.max(y1, y2), t = thick ? 2 : 1;
        for (int y = a; y < b; y++) {
            if (dash == 0 || (y / (dash + 1)) % 2 == 0) {
                g.fill(x, y, x + t, y + 1, c);
            }
        }
    }

    private void zoomBy(float f, double cx, double cy) {
        float next = Math.max(0.4f, Math.min(2.5f, zoom * f));
        double wx = (cx - canvasX() - panX) / zoom, wy = (cy - canvasY() - panY) / zoom;
        zoom = next;
        panX = (float) (cx - canvasX() - wx * zoom);
        panY = (float) (cy - canvasY() - wy * zoom);
    }

    @Nullable
    private Tutorial.Point nodeAt(double mx, double my) {
        if (draft == null) {
            return null;
        }
        for (int i = draft.points().size() - 1; i >= 0; i--) {
            Tutorial.Point p = draft.points().get(i);
            if (Math.abs(mx - nodeX(p)) <= NODE / 2.0 && Math.abs(my - nodeY(p)) <= NODE / 2.0) {
                return p;
            }
        }
        return null;
    }

    private boolean inCanvas(double mx, double my) {
        return mx >= canvasX() && mx < canvasX() + canvasW() && my >= canvasY() && my < canvasY() + canvasH();
    }

    // ------------------------------------------------------------------ the drawer

    private void drawer(GuiGraphics g, int mx, int my) {
        int x = drawerX(), y = canvasY(), w = DRAWER, h = canvasH();
        Ui.inset(g, x, y, w, h);
        int dx = x + 5, dw = w - 10;
        if (settings) {
            settingsDrawer(g, dx, y, dw, h, mx, my);
            return;
        }
        if (linkFrom >= 0) {
            linkDrawer(g, dx, y, dw, mx, my);
            return;
        }
        Tutorial.Point p = pickedPoint < 0 ? null : draft.point(pickedPoint);
        if (p == null) {
            int ty = y + 8;
            for (String line : Ui.wrap(font, Tr.t("tutorial.b.pick"), dw, SMALL)) {
                Ui.text(g, font, line, dx, ty, Ui.MUTED, SMALL);
                ty += 9;
            }
            return;
        }
        // the icon from the held item
        g.renderItem(icon(p.icon()), x + w - 21, y + 4);
        hits.add(new Ui.Hit(x + w - 22, y + 3, 18, 18, () -> {
            ItemStack held = minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getMainHandItem();
            if (!held.isEmpty()) {
                replace(new Tutorial.Point(p.id(), p.title(), BuiltInRegistries.ITEM.getKey(held.getItem()).toString(), p.cx(), p.cy(), p.place(), p.popup()));
                flash(Tr.t("tutorial.b.icon_set"));
            } else {
                flash(Tr.t("tutorial.b.icon_hint"));
            }
        }));
        int cy = y + 22;
        TutorialPopup pop = p.popup();
        cy = row(g, dx, cy, dw, Tr.t("tutorial.b.popup"), Tr.t(pop.size() == TutorialPopup.Size.FULL ? "tutorial.b.full" : "tutorial.b.small")
                + " · " + (pop.freezes() ? Tr.t("tutorial.b.freezes") : Tr.t("tutorial.b.walks", pop.seconds())));
        Tutorial.Place pl = p.place();
        cy = row(g, dx, cy, dw, Tr.t("tutorial.b.place"), pl.isSet() ? pl.server() : Tr.t("tutorial.b.no_place"));
        if (pl.isSet()) {
            String where = pl.dimension().replace("minecraft:", "") + " " + pl.x() + " " + pl.y() + " " + pl.z();
            Ui.text(g, font, Ui.fit(font, where, dw, TINY), dx + dw - Math.min(dw, Ui.width(font, where, TINY)), cy, Ui.MUTED, TINY);
            cy += 8;
        }
        // the radius
        Ui.text(g, font, Tr.t("tutorial.b.radius"), dx, cy + 3, Ui.MUTED, TINY);
        int rx = dx + dw - 40;
        key(g, rx, cy, "−", Ui.DIM, mx, my, () -> radius(p, -1));
        Ui.text(g, font, Integer.toString(pl.radius()), rx + 17, cy + 3, Ui.TEXT, SMALL);
        key(g, rx + 26, cy, "+", Ui.DIM, mx, my, () -> radius(p, 1));
        cy += 15;
        // the keys wrap to a second row when they do not fit the drawer (audit of SU-146)
        List<String> labels = new ArrayList<>(List.of(Tr.t("tutorial.b.edit_popup"), Tr.t("tutorial.b.set_here")));
        List<Runnable> actions = new ArrayList<>(List.of(() -> {
            flush();
            minecraft.setScreen(new TutorialEditorScreen(this, draft, p.id()));
        }, () -> action(TutorialEditPacket.Action.PLACE_HERE, p.id(), -1)));
        if (pl.isSet()) {
            labels.add(Tr.t("tutorial.b.go"));
            actions.add(() -> action(TutorialEditPacket.Action.GO_THERE, p.id(), -1));
        }
        cy = keyFlow(g, dx, cy, dw, 3, labels, i -> i == 0 ? Ui.VIOLET : Ui.DIM, actions, mx, my) + 4;
        // the links out of this point, then the buttons, both kept above DELETE POINT (audit of SU-146)
        int limit = y + canvasH() - 20;
        int buttonsMin = 11 + 8 * Math.min(2, pop.buttons().size());
        Ui.text(g, font, Tr.t("tutorial.b.links").toUpperCase(Locale.ROOT), dx, cy, 0xFF6B7180, TINY);
        cy += 9;
        List<Tutorial.Link> mine = new ArrayList<>();
        for (Tutorial.Link l : draft.links()) {
            if ((l.from() == p.id() || l.to() == p.id()) && draft.point(l.from() == p.id() ? l.to() : l.from()) != null) {
                mine.add(l);
            }
        }
        for (int li = 0; li < mine.size(); li++) {
            Tutorial.Link l = mine.get(li);
            if (cy + 10 > limit - buttonsMin) {
                Ui.text(g, font, "+" + (mine.size() - li), dx, cy + 1, Ui.MUTED, TINY);
                cy += 8;
                break;
            }
            boolean out = l.from() == p.id();
            Tutorial.Point other = draft.point(out ? l.to() : l.from());
            String state = linkState(l);
            String line = (out ? "→ " : "← ") + other.title();
            Ui.text(g, font, Ui.fit(font, line, dw - 50, TINY), dx, cy + 2, Ui.TEXT, TINY);
            Ui.text(g, font, Ui.fit(font, state, 48, TINY), dx + dw - Math.min(48, Ui.width(font, state, TINY)), cy + 2, linkColour(l), TINY);
            int f = l.from(), t = l.to();
            hits.add(new Ui.Hit(dx, cy, dw, 9, () -> {
                linkFrom = f;
                linkTo = t;
                pickedPoint = -1;
                widgets();
            }));
            cy += 10;
        }
        // the buttons
        cy += 2;
        Ui.text(g, font, Tr.t("tutorial.b.buttons").toUpperCase(Locale.ROOT), dx, cy, 0xFF6B7180, TINY);
        cy += 9;
        for (int bi = 0; bi < pop.buttons().size(); bi++) {
            TutorialPopup.Button b = pop.buttons().get(bi);
            if (cy + 8 > limit || bi < pop.buttons().size() - 1 && cy + 16 > limit) {
                Ui.text(g, font, "+" + (pop.buttons().size() - bi), dx, cy, Ui.MUTED, TINY);
                break;
            }
            String target = b.job() == TutorialPopup.Job.BRANCH && draft.point(b.target()) != null ? " → " + draft.point(b.target()).title() : "";
            Ui.text(g, font, Ui.fit(font, b.label().toUpperCase(Locale.ROOT) + "  " + TutorialDraw.word(b.job()) + target, dw, TINY), dx, cy, Ui.TEXT, TINY);
            cy += 8;
        }
        boolean sure = Util.getMillis() < deleteSureUntil;
        String del = Tr.t(sure ? "rules.sure" : "tutorial.b.delete_point");
        key(g, dx + dw - keyW(del), y + canvasH() - 18, del, sure ? Ui.BAD : Ui.DIM, mx, my, () -> {
            if (Util.getMillis() < deleteSureUntil) {
                deletePoint(p.id());
            } else {
                deleteSureUntil = Util.getMillis() + SURE_MS;
            }
        });
    }

    private String linkState(Tutorial.Link l) {
        Tutorial.Point a = draft.point(l.from()), b = draft.point(l.to());
        if (a == null || b == null || !a.place().isSet() || !b.place().isSet()) {
            return Tr.t("tutorial.b.at_once");
        }
        if (!a.place().sameWorld(b.place())) {
            return Tr.t("tutorial.b.teleport");
        }
        return l.path().isEmpty() ? Tr.t("tutorial.b.no_path") : Tr.t("tutorial.b.path_n", l.path().size());
    }

    private int linkColour(Tutorial.Link l) {
        Tutorial.Point a = draft.point(l.from()), b = draft.point(l.to());
        if (a == null || b == null || !a.place().isSet() || !b.place().isSet()) {
            return Ui.MUTED;
        }
        if (!a.place().sameWorld(b.place())) {
            return Ui.VIOLET;
        }
        return l.path().isEmpty() ? Ui.BAD : Ui.GOLD;
    }

    private void linkDrawer(GuiGraphics g, int dx, int y, int dw, int mx, int my) {
        Tutorial.Point a = draft.point(linkFrom), b = draft.point(linkTo);
        Tutorial.Link link = null;
        for (Tutorial.Link l : draft.links()) {
            if (l.from() == linkFrom && l.to() == linkTo) {
                link = l;
            }
        }
        if (a == null || b == null || link == null) {
            linkFrom = linkTo = -1;
            return;
        }
        Ui.text(g, font, Component.literal(Ui.fitBold(font, a.title() + " → " + b.title(), dw, SMALL)).withStyle(ChatFormatting.BOLD), dx, y + 6, Ui.VIOLET, SMALL);
        int cy = y + 20;
        Ui.text(g, font, linkState(link), dx, cy, linkColour(link), SMALL);
        cy += 12;
        int ty = cy;
        for (String line : Ui.wrap(font, Tr.t("tutorial.b.link_help"), dw, TINY)) {
            Ui.text(g, font, line, dx, ty, Ui.MUTED, TINY);
            ty += 7;
        }
        cy = ty + 6;
        int f = linkFrom, t = linkTo;
        if (a.place().sameWorld(b.place())) {
            String k = Tr.t(link.path().isEmpty() ? "tutorial.b.draw_path" : "tutorial.b.redraw");
            key(g, dx, cy, k, Ui.GOLD, mx, my, () -> action(TutorialEditPacket.Action.DRAW_PATH, f, t));
            cy += 18;
        }
        key(g, dx, cy, Tr.t("tutorial.b.remove_link"), Ui.DIM, mx, my, () -> {
            List<Tutorial.Link> ls = new ArrayList<>(draft.links());
            ls.removeIf(l -> l.from() == f && l.to() == t);
            changed(draft.withLinks(ls).sanitised());
            linkFrom = linkTo = -1;
        });
    }

    private void settingsDrawer(GuiGraphics g, int dx, int y, int dw, int h, int mx, int my) {
        Ui.text(g, font, Component.literal(Tr.t("tutorial.b.settings").toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD), dx, y + 5, Ui.VIOLET, SMALL);
        Ui.text(g, font, Tr.t("tutorial.b.name").toUpperCase(Locale.ROOT), dx, y + 15, 0xFF6B7180, TINY);
        int cy = y + 38;
        Ui.text(g, font, Tr.t("tutorial.b.starts").toUpperCase(Locale.ROOT), dx, cy, 0xFF6B7180, TINY);
        cy += 8;
        // the start and audience keys wrap to a second row when they do not fit (audit of SU-146)
        List<Runnable> startActs = new ArrayList<>();
        for (Tutorial.Start s : Tutorial.Start.values()) {
            startActs.add(() -> {
                if (draft.start() != s) {
                    changed(draft.withStart(s));
                    widgets(); // the offer box sits under the section, whose rows may differ
                }
            });
        }
        Tutorial.Start[] starts = Tutorial.Start.values();
        keyFlow(g, dx, cy, dw, 2, startLabels(), i -> draft.start() == starts[i] ? Ui.VIOLET : Ui.DIM, startActs, mx, my);
        cy = settingsSectionY();
        if (draft.start() == Tutorial.Start.FIRST_JOIN) {
            Ui.text(g, font, Tr.t("tutorial.b.offered_to").toUpperCase(Locale.ROOT), dx, cy, 0xFF6B7180, TINY);
            cy += 8;
            List<Runnable> audActs = new ArrayList<>();
            for (Tutorial.Audience a : Tutorial.Audience.values()) {
                audActs.add(() -> changed(draft.withAudience(a)));
            }
            Tutorial.Audience[] auds = Tutorial.Audience.values();
            keyFlow(g, dx, cy, dw, 2, audienceLabels(), i -> draft.audience() == auds[i] ? Ui.VIOLET : Ui.DIM, audActs, mx, my);
        } else {
            Ui.text(g, font, Tr.t("tutorial.b.start_area").toUpperCase(Locale.ROOT), dx, cy, 0xFF6B7180, TINY);
            cy += 8;
            Tutorial.Area a = draft.area();
            String where = a.isSet() ? a.server() + " · " + (a.x1() - a.x0() + 1) + "×" + (a.y1() - a.y0() + 1) + "×" + (a.z1() - a.z0() + 1) : Tr.t("tutorial.b.no_area");
            int setW = keyW(Tr.t("tutorial.b.set_area")), clearW = a.isSet() ? keyW("✕") + 2 : 0;
            int kx = dx + dw - setW - clearW;
            Ui.text(g, font, Ui.fit(font, where, kx - dx - 3, TINY), dx, cy + 3, a.isSet() ? Ui.TEXT : Ui.BAD, TINY);
            kx += key(g, kx, cy, Tr.t("tutorial.b.set_area"), Ui.VIOLET, mx, my, () -> action(TutorialEditPacket.Action.SET_AREA, -1, -1)) + 2;
            if (a.isSet()) {
                key(g, kx, cy, "✕", Ui.DIM, mx, my, () -> changed(draft.withArea(Tutorial.Area.NONE)));
            }
        }
        // the offer box, then the counts and the ended-at lines, all above DELETE TUTORIAL at the foot (audit of SU-146)
        int[] offer = offerRect();
        Ui.text(g, font, Tr.t("tutorial.b.offer").toUpperCase(Locale.ROOT), dx, offer[0] - 8, 0xFF6B7180, TINY);
        cy = offer[0] + offer[1] + 3;
        Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.b.counts", counts.getInt("started"), counts.getInt("finished"), counts.getInt("ended")), dw, TINY), dx, cy, Ui.MUTED, TINY);
        cy += 8;
        CompoundTag at = counts.getCompound("endedAt");
        for (String k : at.getAllKeys()) {
            if (cy + 5 > y + h - 20) {
                break;
            }
            Tutorial.Point p = draft.point(Integer.parseInt(k));
            Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.b.ended_at", p == null ? "?" : p.title(), at.getInt(k)), dw, TINY), dx, cy, 0xFF6B7180, TINY);
            cy += 7;
        }
        boolean sure = Util.getMillis() < deleteSureUntil;
        String del = Tr.t(sure ? "rules.sure" : "tutorial.b.delete_tutorial");
        key(g, dx + dw - keyW(del), y + h - 18, del, sure ? Ui.BAD : Ui.DIM, mx, my, () -> {
            if (Util.getMillis() < deleteSureUntil) {
                deleteSureUntil = 0;
                action(TutorialEditPacket.Action.DELETE, -1, -1);
                selected = "";
                settings = false;
            } else {
                deleteSureUntil = Util.getMillis() + SURE_MS;
            }
        });
    }

    private int row(GuiGraphics g, int x, int y, int w, String k, String v) {
        Ui.text(g, font, k, x, y, Ui.MUTED, TINY);
        String s = Ui.fit(font, v, w - Ui.width(font, k, TINY) - 6, TINY);
        Ui.text(g, font, s, x + w - Ui.width(font, s, TINY), y, Ui.TEXT, TINY);
        return y + 9;
    }

    private int keyW(String label) {
        return Math.max(14, Ui.widthBold(font, label.toUpperCase(Locale.ROOT), TINY) + 10); // measured bold (audit of SU-146)
    }

    private int key(GuiGraphics g, int x, int y, String label, int colour, int mx, int my, Runnable action) {
        int w = keyW(label), h = 12;
        boolean hot = mx >= x && mx < x + w && my >= y && my < y + h;
        boolean filled = colour == Ui.VIOLET || colour == Ui.GOLD || colour == Ui.BAD;
        if (filled) {
            g.fill(x, y, x + w, y + h, colour);
        } else {
            g.fill(x, y, x + w, y + h, Ui.SLOT);
            Ui.frame(g, x, y, w, h, hot ? Ui.VIOLET : Ui.DIM);
        }
        if (hot && filled) {
            Ui.frame(g, x, y, w, h, 0xFFFFFFFF);
        }
        String t = label.toUpperCase(Locale.ROOT);
        Ui.text(g, font, Component.literal(t).withStyle(ChatFormatting.BOLD), x + (w - Ui.widthBold(font, t, TINY)) / 2, y + 3, filled ? Ui.INK : Ui.MUTED, TINY);
        hits.add(new Ui.Hit(x, y, w, h, action));
        return w;
    }

    private void flash(String s) {
        note = s;
        noteUntil = Util.getMillis() + 3000;
    }

    // ------------------------------------------------------------------ actions

    private void pick(String id) {
        if (id.equals(selected)) {
            return;
        }
        flush();
        ServerUtilsNetwork.CHANNEL.sendToServer(TutorialEditPacket.of(TutorialEditPacket.Action.OPEN, id));
    }

    private void create() {
        flush();
        ServerUtilsNetwork.CHANNEL.sendToServer(new TutorialEditPacket(TutorialEditPacket.Action.CREATE, "", -1, -1, Tr.t("tutorial.b.new_name")));
    }

    private void publish() {
        if (draft == null) {
            return;
        }
        if (!draft.issues().isEmpty() && Util.getMillis() >= publishSureUntil) {
            publishSureUntil = Util.getMillis() + SURE_MS;
            return;
        }
        publishSureUntil = 0;
        action(TutorialEditPacket.Action.PUBLISH, -1, -1);
        flash(Tr.t("tutorial.b.published"));
    }

    private void addPoint() {
        int id = Math.max(draft.nextPointId(), publishedMax + 1); // never an id the published copy used (audit of SU-146)
        int cx = Math.round((canvasW() / 2f - panX) / zoom), cy = Math.round((canvasH() / 2f - panY) / zoom);
        List<Tutorial.Point> ps = new ArrayList<>(draft.points());
        ps.add(new Tutorial.Point(id, Tr.t("tutorial.b.point_n", id + 1), Tutorial.DEFAULT_ICON, cx + (id % 3) * 6, cy + (id % 3) * 6, Tutorial.Place.NONE, TutorialPopup.defaults()));
        changed(draft.withPoints(ps).sanitised());
        pickedPoint = id;
        settings = false;
        linkFrom = linkTo = -1;
        widgets();
    }

    private void deletePoint(int id) {
        List<Tutorial.Point> ps = new ArrayList<>(draft.points());
        ps.removeIf(p -> p.id() == id);
        List<Tutorial.Link> ls = new ArrayList<>(draft.links());
        ls.removeIf(l -> l.from() == id || l.to() == id);
        changed(draft.withPoints(ps).withLinks(ls).sanitised());
        pickedPoint = -1;
        deleteSureUntil = 0;
        widgets();
    }

    private void radius(Tutorial.Point p, int d) {
        Tutorial.Place pl = p.place();
        int r = Math.max(Tutorial.MIN_RADIUS, Math.min(Tutorial.MAX_RADIUS, pl.radius() + d));
        replace(p.withPlace(new Tutorial.Place(pl.server(), pl.dimension(), pl.x(), pl.y(), pl.z(), r)));
    }

    /**
     * Links one point to another. A point that forks gets a GO TO BRANCH button for each branch it has none for (named
     * after the branch's point, while there is room), and loses its NEXT; a link that would loop back is refused.
     */
    private void link(int from, int to) {
        if (from == to || draft.children(from).contains(to)) {
            return;
        }
        List<Tutorial.Link> ls = new ArrayList<>(draft.links());
        ls.add(new Tutorial.Link(from, to, List.of()));
        Tutorial next = draft.withLinks(ls).sanitised();
        if (next.links().size() == draft.links().size()) {
            flash(Tr.t("tutorial.b.loop"));
            return;
        }
        List<Integer> kids = next.children(from);
        Tutorial.Point p = next.point(from);
        if (kids.size() > 1 && p != null && p.popup().freezes()) {
            List<TutorialPopup.Button> bs = new ArrayList<>();
            for (TutorialPopup.Button b : p.popup().buttons()) {
                if (b.job() != TutorialPopup.Job.NEXT) {
                    bs.add(b);
                }
            }
            for (int k : kids) {
                boolean has = bs.stream().anyMatch(b -> b.job() == TutorialPopup.Job.BRANCH && b.target() == k);
                Tutorial.Point kp = next.point(k);
                if (!has && kp != null && bs.size() < TutorialPopup.MAX_BUTTONS) {
                    bs.add(new TutorialPopup.Button(kp.title(), 0x19D3B0, TutorialPopup.Job.BRANCH, k, ""));
                }
            }
            TutorialPopup pop = p.popup();
            List<Tutorial.Point> ps = new ArrayList<>();
            for (Tutorial.Point q : next.points()) {
                ps.add(q.id() == from ? q.withPopup(new TutorialPopup(pop.size(), pop.freezes(), pop.seconds(), pop.background(), pop.place(), pop.boxes(), bs)) : q);
            }
            next = next.withPoints(ps).sanitised();
        }
        changed(next);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        for (EditBox b : new EditBox[]{titleBox, nameBox}) {
            if (b != null) {
                b.setFocused(false);
            }
        }
        pressed = null;
        if (button == 0) {
            for (Ui.Hit h : hits) {
                if (h.contains(mx, my)) {
                    pressed = h;
                    return true;
                }
            }
        }
        if (draft == null || !inCanvas(mx, my)) {
            return false;
        }
        Tutorial.Point p = nodeAt(mx, my);
        downX = mx;
        downY = my;
        if (p != null && button == 1) {
            linkingFrom = p.id();
            return true;
        }
        if (p != null && button == 0) {
            dragNode = p.id();
            dragX = mx - nodeX(p);
            dragY = my - nodeY(p);
            if (pickedPoint != p.id()) {
                pickedPoint = p.id();
                settings = false;
                linkFrom = linkTo = -1;
                widgets();
            }
            return true;
        }
        if (button == 0) {
            panning = true;
            dragX = mx;
            dragY = my;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double ddx, double ddy) {
        if (draft != null && dragNode >= 0 && button == 0) {
            Tutorial.Point p = draft.point(dragNode);
            if (p != null) {
                int cx = (int) Math.round((mx - dragX - canvasX() - panX) / zoom), cy = (int) Math.round((my - dragY - canvasY() - panY) / zoom);
                if (cx != p.cx() || cy != p.cy()) {
                    replace(new Tutorial.Point(p.id(), p.title(), p.icon(), cx, cy, p.place(), p.popup()));
                }
            }
            return true;
        }
        if (panning) {
            panX += (float) (mx - dragX);
            panY += (float) (my - dragY);
            dragX = mx;
            dragY = my;
            return true;
        }
        return super.mouseDragged(mx, my, button, ddx, ddy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && pressed != null) {
            Ui.Hit was = pressed;
            pressed = null;
            if (was.contains(mx, my)) {
                was.action().run();
            }
            return true;
        }
        if (linkingFrom >= 0 && button == 1) {
            Tutorial.Point to = nodeAt(mx, my);
            if (to != null) {
                link(linkingFrom, to.id());
            }
            linkingFrom = -1;
            return true;
        }
        if (dragNode >= 0 && Math.abs(mx - downX) + Math.abs(my - downY) < 2 && Util.getMillis() - lastClick < 350 && lastClickNode == dragNode) {
            int id = dragNode;
            dragNode = -1;
            flush();
            minecraft.setScreen(new TutorialEditorScreen(this, draft, id)); // a double click opens its popup
            return true;
        }
        if (dragNode >= 0) {
            lastClick = Util.getMillis();
            lastClickNode = dragNode;
        }
        dragNode = -1;
        panning = false;
        return super.mouseReleased(mx, my, button);
    }

    private long lastClick;
    private int lastClickNode = -1;

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inCanvas(mx, my)) {
            zoomBy(delta > 0 ? 1.15f : 1 / 1.15f, mx, my);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = getFocused() instanceof EditBox || getFocused() instanceof MultiLineEditBox;
        if (!typing && key == GLFW.GLFW_KEY_DELETE && pickedPoint >= 0) {
            if (Util.getMillis() < deleteSureUntil) {
                deletePoint(pickedPoint);
            } else {
                deleteSureUntil = Util.getMillis() + SURE_MS;
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    /** The tag of a list (tests of the view read it; kept for symmetry with the editor). */
    static List<String> files(@Nullable CompoundTag view) {
        List<String> out = new ArrayList<>();
        if (view != null) {
            ListTag l = view.getList("files", Tag.TAG_STRING);
            for (int i = 0; i < l.size(); i++) {
                out.add(l.getString(i));
            }
        }
        return out;
    }
}
