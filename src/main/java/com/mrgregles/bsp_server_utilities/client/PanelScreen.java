package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.image.ImageService;
import com.mrgregles.bsp_server_utilities.network.PanelOpenPacket;
import com.mrgregles.bsp_server_utilities.network.PanelRefreshPacket;
import com.mrgregles.bsp_server_utilities.network.PanelSavePacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Display Panel's settings (owner decisions D43 to D51), drawn as the confirmed page
 * {@code tools/preview/panel_refined.html}: the modes down the left with the background swatches under them, the face as
 * players will see it filling the top (click a line on it to pick it), and under it the inspector for the picked line:
 * its text with the count against 80, the size with − and +, the colour (a picker with the 16 Minecraft colours and a
 * # box), the alignment and bold; + ADD LINE, UP, DOWN and DELETE LINE (SURE? within three seconds). Nothing reaches the
 * panel in the world until SAVE; CLOSE and Escape leave it as it was. In Image mode (D49) the face shows the picture in
 * its fit, and under it the source (a web address, or a file from the server's images folder), the fit, REFRESH NOW
 * (the saved picture again) and the refresh timer; the footer says what the last load brought. In Staff online and
 * Action log modes (D59) the face shows the live data as players see it, and under it what the panel shows: for staff,
 * offline people too and which roles; for the log, every server or this one, which kinds, and 6, 8 or 12 lines.
 */
public class PanelScreen extends Screen {
    static final int W = 344, H = 230;
    private static final int PAD = 8, MODES_W = 52, MAIN_X = 66, MAIN_W = 270, PREVIEW_MAX_H = 104;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final long SURE_MS = 3000;
    /** The 16 Minecraft chat colours, in their code order (0 to f). */
    static final int[] CHAT_COLOURS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private final PanelOpenPacket opened;
    private final PanelSettings original;
    private final List<PanelSettings.Line> lines;
    private int background;
    private PanelSettings.Mode mode;
    private PanelSettings.Source source;
    private String url, file;
    private PanelSettings.Fit fit;
    private int refresh;
    private int fileScroll;
    private boolean staffOffline;
    private final List<String> staffRoles = new ArrayList<>();
    private boolean logAll;
    private final List<String> logKinds = new ArrayList<>();
    private int logLines;
    @Nullable
    private EditBox urlBox;
    private int selected = -1;
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private EditBox textBox, hexBox;
    private boolean pickerOpen;
    private int pickerX, pickerY;
    private long deleteSureUntil;

    public PanelScreen(PanelOpenPacket opened) {
        super(Component.literal(Tr.t("panel.title")));
        this.opened = opened;
        this.original = PanelSettings.load(opened.settings());
        this.lines = new ArrayList<>(original.lines());
        this.background = original.background();
        this.selected = lines.isEmpty() ? -1 : 0;
        this.mode = original.mode();
        this.staffOffline = original.staff().offline();
        this.staffRoles.addAll(original.staff().roles());
        this.logAll = original.log().allServers();
        this.logKinds.addAll(original.log().kinds());
        this.logLines = original.log().lines();
        PanelSettings.Image img = original.image();
        this.source = img.source();
        this.url = img.url();
        this.file = img.file();
        this.fit = img.fit();
        this.refresh = img.refreshMinutes();
    }

    private PanelSettings.Image image() {
        return new PanelSettings.Image(source, url, file, fit, source == PanelSettings.Source.WEB ? refresh : 0);
    }

    /** Whether the picture settings on screen are the saved ones (REFRESH NOW and the preview use the saved picture). */
    private boolean imageSaved() {
        return original.mode() == PanelSettings.Mode.IMAGE && original.image().equals(PanelSettings.load(current().save()).image());
    }

    private void setMode(PanelSettings.Mode m) {
        mode = m;
        pickerOpen = false;
        rebuild();
    }

    public static void open(PanelOpenPacket packet) {
        Minecraft.getInstance().setScreen(new PanelScreen(packet));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    // ------------------------------------------------------------------ editing

    @Nullable
    private PanelSettings.Line line() {
        return selected >= 0 && selected < lines.size() ? lines.get(selected) : null;
    }

    private void setLine(PanelSettings.Line l) {
        if (selected >= 0 && selected < lines.size()) {
            lines.set(selected, l);
        }
    }

    private void pick(int index) {
        selected = index;
        pickerOpen = false;
        deleteSureUntil = 0;
        rebuild();
    }

    private void addLine() {
        if (lines.size() >= PanelSettings.MAX_LINES) {
            return;
        }
        int at = selected < 0 ? lines.size() : selected + 1;
        lines.add(at, new PanelSettings.Line("", PanelSettings.Size.M, 0xFFFFFF, PanelSettings.Align.CENTRE, false));
        pick(at);
        if (textBox != null) {
            setFocused(textBox);
            textBox.setFocused(true);
        }
    }

    private void move(int delta) {
        int to = selected + delta;
        if (selected < 0 || to < 0 || to >= lines.size()) {
            return;
        }
        lines.add(to, lines.remove(selected));
        pick(to);
    }

    private void deleteLine() {
        long now = System.currentTimeMillis();
        if (now >= deleteSureUntil) {
            deleteSureUntil = now + SURE_MS;
            return;
        }
        deleteSureUntil = 0;
        if (line() != null) {
            lines.remove(selected);
            pick(lines.isEmpty() ? -1 : Math.min(selected, lines.size() - 1));
        }
    }

    private void size(int delta) {
        PanelSettings.Line l = line();
        if (l == null) {
            return;
        }
        PanelSettings.Size[] sizes = PanelSettings.Size.values();
        int i = Math.max(0, Math.min(sizes.length - 1, l.size().ordinal() + delta));
        setLine(new PanelSettings.Line(l.text(), sizes[i], l.colour(), l.align(), l.bold()));
    }

    private void colour(int rgb) {
        PanelSettings.Line l = line();
        if (l != null) {
            setLine(new PanelSettings.Line(l.text(), l.size(), rgb & 0xFFFFFF, l.align(), l.bold()));
        }
    }

    private void align(PanelSettings.Align a) {
        PanelSettings.Line l = line();
        if (l != null) {
            setLine(new PanelSettings.Line(l.text(), l.size(), l.colour(), a, l.bold()));
        }
    }

    private void bold() {
        PanelSettings.Line l = line();
        if (l != null) {
            setLine(new PanelSettings.Line(l.text(), l.size(), l.colour(), l.align(), !l.bold()));
        }
    }

    private PanelSettings current() {
        return new PanelSettings(mode, background, lines, image(), original.placedBy(), original.revision())
                .withViews(new PanelSettings.StaffView(staffOffline, staffRoles), new PanelSettings.LogView(logAll, logKinds, logLines)).sanitised();
    }

    private void save() {
        ServerUtilsNetwork.CHANNEL.sendToServer(new PanelSavePacket(opened.origin(), current().save()));
        super.onClose();
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        textBox = null;
        hexBox = null;
        urlBox = null;
        int x = left() + MAIN_X, y = top();
        if (mode == PanelSettings.Mode.IMAGE) {
            if (source == PanelSettings.Source.WEB) {
                urlBox = new EditBox(font, x + 1, y + 147, MAIN_W - 2, 12, Component.literal(Tr.t("panel.web_address")));
                urlBox.setMaxLength(PanelSettings.MAX_URL);
                urlBox.setValue(url);
                urlBox.setResponder(s -> url = s);
                addRenderableWidget(urlBox);
            }
            return;
        }
        PanelSettings.Line l = line();
        if (l != null) {
            textBox = new EditBox(font, x + 34, y + 131, MAIN_W - 34 - 34, 12, Component.literal(Tr.t("panel.line", selected + 1)));
            textBox.setMaxLength(PanelSettings.MAX_LINE);
            textBox.setValue(l.text());
            textBox.setResponder(s -> {
                PanelSettings.Line cur = line();
                if (cur != null && !cur.text().equals(s)) {
                    setLine(new PanelSettings.Line(s, cur.size(), cur.colour(), cur.align(), cur.bold()));
                }
            });
            addRenderableWidget(textBox);
        }
        if (pickerOpen && l != null) {
            hexBox = new EditBox(font, pickerX + 16, pickerY + 32, 48, 11, Component.literal("#"));
            hexBox.setMaxLength(6);
            hexBox.setValue(String.format(Locale.ROOT, "%06X", l.colour()));
            hexBox.setResponder(s -> {
                if (s.matches("[0-9a-fA-F]{6}")) {
                    colour(Integer.parseInt(s, 16));
                }
            });
            addRenderableWidget(hexBox);
        }
    }

    @Override
    public void tick() {
        if (textBox != null) {
            textBox.tick();
        }
        if (urlBox != null) {
            urlBox.tick();
        }
        if (hexBox != null) {
            hexBox.tick();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        hits.clear();
        pickerHits.clear();
        int x = left(), y = top();
        g.fill(x, y, x + W, y + H, Ui.PANEL);
        Ui.frame(g, x, y, W, H, Ui.VIOLET);
        Ui.text(g, font, Component.literal(Tr.t("panel.title")).withStyle(ChatFormatting.BOLD), x + PAD, y + 7, Ui.VIOLET, 1f);
        String hint = Tr.t(mode == PanelSettings.Mode.TEXT ? "panel.hint" : "panel.hint_image");
        Ui.text(g, font, hint, x + W - PAD - Ui.width(font, hint, TINY), y + 9, Ui.MUTED, TINY);

        drawModes(g, x, y);
        drawBackgrounds(g, x, y, mx, my);
        if (mode == PanelSettings.Mode.IMAGE) {
            drawImagePreview(g, x, y);
            drawImagePane(g, x, y, mx, my);
        } else if (mode == PanelSettings.Mode.STAFF) {
            drawLivePreview(g, x, y);
            drawStaffPane(g, x, y);
        } else if (mode == PanelSettings.Mode.LOG) {
            drawLivePreview(g, x, y);
            drawLogPane(g, x, y);
        } else {
            drawPreview(g, x, y, mx, my);
            drawInspector(g, x, y, mx, my);
        }
        drawFooter(g, x, y, mx, my);
        super.render(g, mx, my, partial);
        if (pickerOpen) {
            drawPicker(g, mx, my);
            if (hexBox != null) {
                hexBox.render(g, mx, my, partial); // over the picker's box
            }
        }
    }

    private void drawModes(GuiGraphics g, int x, int y) {
        String[] keys = {"panel.mode.text", "panel.mode.image", "panel.mode.staff", "panel.mode.log"};
        PanelSettings.Mode[] modes = {PanelSettings.Mode.TEXT, PanelSettings.Mode.IMAGE, PanelSettings.Mode.STAFF, PanelSettings.Mode.LOG};
        for (int i = 0; i < keys.length; i++) {
            int cx = x + PAD, cy = y + 22 + i * 14;
            boolean on = modes[i] == mode;
            g.fill(cx, cy, cx + MODES_W, cy + 11, Ui.SLOT);
            Ui.frame(g, cx, cy, MODES_W, 11, on ? Ui.VIOLET : Ui.DIM);
            String s = Ui.fit(font, Tr.t(keys[i]).toUpperCase(Locale.ROOT), MODES_W - 4, TINY);
            Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), cx + (MODES_W - Ui.width(font, s, TINY)) / 2, cy + 3, on ? Ui.VIOLET : Ui.MUTED, TINY);
            if (!on) {
                PanelSettings.Mode m = modes[i];
                hits.add(new Ui.Hit(cx, cy, MODES_W, 11, () -> setMode(m)));
            }
        }
    }

    private void drawBackgrounds(GuiGraphics g, int x, int y, int mx, int my) {
        int bx = x + PAD, by = y + 150;
        Ui.text(g, font, Tr.t("panel.background").toUpperCase(Locale.ROOT), bx, by, Ui.MUTED, TINY);
        int[] options = PanelSettings.BACKGROUNDS;
        for (int i = 0; i < options.length; i++) {
            int sx = bx + (i % 3) * 14, sy = by + 9 + (i / 3) * 14;
            int c = options[i];
            if (c == PanelSettings.CLEAR) {
                g.fill(sx, sy, sx + 10, sy + 10, 0xFF999999);
                g.fill(sx, sy, sx + 5, sy + 5, 0xFF555555);
                g.fill(sx + 5, sy + 5, sx + 10, sy + 10, 0xFF555555);
            } else {
                g.fill(sx, sy, sx + 10, sy + 10, c);
            }
            Ui.frame(g, sx - 1, sy - 1, 12, 12, background == c ? Ui.VIOLET : Ui.DIM);
            hits.add(new Ui.Hit(sx - 1, sy - 1, 12, 12, () -> background = c));
        }
        Ui.text(g, font, Ui.fit(font, Tr.t("panel.clear_hint"), MODES_W, TINY), bx, by + 39, Ui.MUTED, TINY);
    }

    private void drawPreview(GuiGraphics g, int x, int y, int mx, int my) {
        int bw = opened.width(), bh = opened.height();
        float aspect = (float) bw / bh;
        int pw = (int) Math.min(MAIN_W, PREVIEW_MAX_H * aspect), ph = (int) (pw / aspect);
        int px = x + MAIN_X + (MAIN_W - pw) / 2, py = y + 22 + (PREVIEW_MAX_H - ph) / 2;
        float k = (float) pw / (bw * PanelLayout.PX);
        // the bezel and the face as in the world
        g.fill(px, py, px + pw, py + ph, 0xFF2A2F3A);
        int inset = Math.max(1, Math.round(PanelLayout.INSET * k));
        if (background == PanelSettings.CLEAR) {
            for (int cy = py + inset; cy < py + ph - inset; cy += 6) {
                for (int cx = px + inset; cx < px + pw - inset; cx += 6) {
                    boolean dark = ((cx - px) / 6 + (cy - py) / 6) % 2 == 0;
                    g.fill(cx, cy, Math.min(cx + 6, px + pw - inset), Math.min(cy + 6, py + ph - inset), dark ? 0xFF6E6E6E : 0xFF7E7E7E);
                }
            }
        } else {
            g.fill(px + inset, py + inset, px + pw - inset, py + ph - inset, background);
        }
        g.fill(px + pw - inset - Math.max(2, (int) (6 * k)) - 2, py + ph - inset + Math.max(0, inset / 4), px + pw - inset - 2, py + ph - Math.max(0, inset / 4), Ui.TEAL);
        if (lines.isEmpty()) {
            String s = Ui.fit(font, Tr.t("panel.no_lines"), pw - 8, SMALL);
            Ui.text(g, font, s, px + (pw - Ui.width(font, s, SMALL)) / 2, py + ph / 2 - 3, Ui.MUTED, SMALL);
            return;
        }
        Ui.scissor(g, px, py, px + pw, py + ph);
        for (PanelLayout.Placed p : PanelLayout.layout(lines, bw, bh, font::width)) {
            g.pose().pushPose();
            g.pose().translate(px + p.left() * k, py + p.y() * k, 0);
            g.pose().scale(p.scale() * k, p.scale() * k, 1);
            g.drawString(font, p.text(), 0, 0, 0xFF000000 | p.colour(), false);
            g.pose().popPose();
            int sy = (int) (py + p.slotTop() * k), sh = Math.max(3, (int) (p.slotHeight() * k));
            int sx0 = px + inset + 1, sx1 = px + pw - inset - 1;
            if (p.index() == selected) {
                Ui.frame(g, sx0, sy - 1, sx1 - sx0, sh + 2, Ui.VIOLET);
            } else if (mx >= sx0 && mx < sx1 && my >= sy && my < sy + sh) {
                Ui.frame(g, sx0, sy - 1, sx1 - sx0, sh + 2, 0x66B58CFF);
            }
            int index = p.index();
            hits.add(new Ui.Hit(sx0, sy, sx1 - sx0, sh, () -> pick(index)));
        }
        g.disableScissor();
    }

    private void drawInspector(GuiGraphics g, int x, int y, int mx, int my) {
        int ix = x + MAIN_X;
        PanelSettings.Line l = line();
        int ky = y + 164;
        if (l != null) {
            Ui.text(g, font, Tr.t("panel.line", selected + 1).toUpperCase(Locale.ROOT), ix, y + 134, Ui.MUTED, TINY);
            String count = Tr.t("panel.count", l.text().length(), PanelSettings.MAX_LINE);
            Ui.text(g, font, count, ix + MAIN_W - Ui.width(font, count, TINY), y + 134, l.text().length() >= PanelSettings.MAX_LINE ? Ui.GOLD : Ui.MUTED, TINY);
            int ry = y + 148, cx = ix;
            Ui.text(g, font, Tr.t("panel.size").toUpperCase(Locale.ROOT), cx, ry + 3, Ui.MUTED, TINY);
            cx += 24;
            key(g, cx, ry, 11, "−", KeyStyle.PLAIN, l.size().ordinal() > 0, mx, my, () -> size(-1));
            String sz = l.size().name();
            Ui.text(g, font, Component.literal(sz).withStyle(ChatFormatting.BOLD), cx + 13 + (14 - Ui.width(font, sz, SMALL)) / 2, ry + 2, Ui.TEXT, SMALL);
            key(g, cx + 29, ry, 11, "+", KeyStyle.PLAIN, l.size().ordinal() < PanelSettings.Size.values().length - 1, mx, my, () -> size(1));
            cx += 52;
            Ui.text(g, font, Tr.t("panel.colour").toUpperCase(Locale.ROOT), cx, ry + 3, Ui.MUTED, TINY);
            cx += 30;
            g.fill(cx, ry, cx + 11, ry + 11, 0xFF000000 | l.colour());
            Ui.frame(g, cx - 1, ry - 1, 13, 13, pickerOpen ? Ui.VIOLET : Ui.DIM);
            int swatchX = cx;
            hits.add(new Ui.Hit(cx - 1, ry - 1, 13, 13, () -> {
                pickerOpen = !pickerOpen;
                pickerX = Math.max(left() + 2, swatchX - 40);
                pickerY = ry - 50;
                rebuild();
            }));
            cx += 20;
            Ui.text(g, font, Tr.t("panel.align").toUpperCase(Locale.ROOT), cx, ry + 3, Ui.MUTED, TINY);
            cx += 26;
            PanelSettings.Align[] aligns = PanelSettings.Align.values();
            String[] alignKeys = {"panel.align.left", "panel.align.centre", "panel.align.right"};
            for (int i = 0; i < aligns.length; i++) {
                PanelSettings.Align a = aligns[i];
                chip(g, cx + i * 13, ry, 11, Tr.t(alignKeys[i]), l.align() == a, () -> align(a));
            }
            cx += 44;
            chip(g, cx, ry, 11, Tr.t("panel.bold"), l.bold(), this::bold);
        }
        boolean full = lines.size() >= PanelSettings.MAX_LINES;
        key(g, ix, ky, 54, Tr.t("panel.add_line"), KeyStyle.VIOLET, !full, mx, my, this::addLine);
        String n = Tr.t("panel.count", lines.size(), PanelSettings.MAX_LINES);
        Ui.text(g, font, n, ix + 58, ky + 4, full ? Ui.GOLD : Ui.MUTED, TINY);
        key(g, ix + 80, ky, 24, Tr.t("panel.up"), KeyStyle.PLAIN, l != null && selected > 0, mx, my, () -> move(-1));
        key(g, ix + 108, ky, 32, Tr.t("panel.down"), KeyStyle.PLAIN, l != null && selected < lines.size() - 1, mx, my, () -> move(1));
        boolean sure = System.currentTimeMillis() < deleteSureUntil;
        key(g, ix + MAIN_W - 58, ky, 58, Tr.t(sure ? "panel.sure" : "panel.delete_line"), sure ? KeyStyle.SURE : KeyStyle.WARN, l != null, mx, my, this::deleteLine);
    }

    private void drawFooter(GuiGraphics g, int x, int y, int mx, int my) {
        int fy = y + H - PAD - 14;
        String where = mode == PanelSettings.Mode.IMAGE ? loadStatus()
                : Tr.t("panel.status", opened.width(), opened.height(), opened.server(), original.placedBy().isEmpty() ? "-" : original.placedBy());
        int colour = mode == PanelSettings.Mode.IMAGE && imageSaved() && !original.loaded().error().isEmpty() ? Ui.BAD : Ui.MUTED;
        Ui.text(g, font, Ui.fit(font, where, W - 3 * PAD - 100, TINY), x + PAD, fy + 5, colour, TINY);
        key(g, x + W - PAD - 46, fy, 46, Tr.t("panel.save"), KeyStyle.VIOLET, true, mx, my, this::save, 14);
        key(g, x + W - PAD - 96, fy, 46, Tr.t("panel.close"), KeyStyle.GHOST, true, mx, my, this::onClose, 14);
    }

    // ------------------------------------------------------------------ Image mode

    /** What the last load brought, or what SAVE will do. */
    private String loadStatus() {
        if (!imageSaved()) {
            return Tr.t("panel.image_changed");
        }
        PanelSettings.Loaded l = original.loaded();
        if (l.ok()) {
            return Tr.t("panel.loaded", l.sourceWidth(), l.sourceHeight(), l.format(), Math.max(1, l.sourceBytes() / 1024), l.width(), l.height());
        }
        String d = l.detail();
        return switch (l.error()) {
            case "" -> Tr.t("panel.not_loaded");
            case "not_an_image" -> Tr.t("panel.error.not_an_image");
            case "too_many_pixels" -> Tr.t("panel.error.too_many_pixels", d);
            case "bad_address" -> Tr.t("panel.error.bad_address");
            case "http" -> Tr.t("panel.error.http", d);
            case "timed_out" -> Tr.t("panel.error.timed_out", d);
            case "unreachable" -> Tr.t("panel.error.unreachable", d);
            case "too_big" -> Tr.t("panel.error.too_big", d);
            case "no_file" -> Tr.t("panel.error.no_file", d);
            default -> Tr.t("panel.error.no_source");
        };
    }

    private void drawImagePreview(GuiGraphics g, int x, int y) {
        int bw = opened.width(), bh = opened.height();
        float aspect = (float) bw / bh;
        int pw = (int) Math.min(MAIN_W, 100 * aspect), ph = (int) (pw / aspect);
        int px = x + MAIN_X + (MAIN_W - pw) / 2, py = y + 22 + (100 - ph) / 2;
        float k = (float) pw / (bw * PanelLayout.PX);
        int inset = Math.max(1, Math.round(PanelLayout.INSET * k));
        g.fill(px, py, px + pw, py + ph, 0xFF2A2F3A);
        int ax = px + inset, ay = py + inset, aw = pw - 2 * inset, ah = ph - 2 * inset;
        if (background == PanelSettings.CLEAR) {
            for (int cy = ay; cy < ay + ah; cy += 6) {
                for (int cx = ax; cx < ax + aw; cx += 6) {
                    boolean dark = ((cx - ax) / 6 + (cy - ay) / 6) % 2 == 0;
                    g.fill(cx, cy, Math.min(cx + 6, ax + aw), Math.min(cy + 6, ay + ah), dark ? 0xFF6E6E6E : 0xFF7E7E7E);
                }
            }
        } else {
            g.fill(ax, ay, ax + aw, ay + ah, background);
        }
        ClientPanelImages.Texture tex = imageSaved() && original.loaded().ok() ? ClientPanelImages.texture(original.loaded().hash()) : null;
        if (tex != null) {
            PanelLayout.Fitted f = PanelLayout.fit(fit, tex.width(), tex.height(), aw, ah);
            int dw = Math.round(f.w()), dh = Math.round(f.h());
            g.blit(tex.id(), ax + Math.round(f.x()), ay + Math.round(f.y()), dw, dh,
                    f.u0() * tex.width(), f.v0() * tex.height(), Math.round((f.u1() - f.u0()) * tex.width()), Math.round((f.v1() - f.v0()) * tex.height()),
                    tex.width(), tex.height());
        } else {
            String s = Ui.fit(font, Tr.t(imageSaved() && original.loaded().ok() ? "panel.picture_loading" : "panel.picture_after_save"), aw - 6, SMALL);
            Ui.text(g, font, s, ax + (aw - Ui.width(font, s, SMALL)) / 2, ay + ah / 2 - 3, Ui.MUTED, SMALL);
        }
    }

    private void drawImagePane(GuiGraphics g, int x, int y, int mx, int my) {
        int ix = x + MAIN_X, ry = y + 130;
        Ui.text(g, font, Tr.t("panel.source").toUpperCase(Locale.ROOT), ix, ry + 3, Ui.MUTED, TINY);
        wideChip(g, ix + 36, ry, 60, Tr.t("panel.web_address"), source == PanelSettings.Source.WEB, () -> {
            source = PanelSettings.Source.WEB;
            rebuild();
        });
        wideChip(g, ix + 100, ry, 64, Tr.t("panel.server_folder"), source == PanelSettings.Source.FOLDER, () -> {
            source = PanelSettings.Source.FOLDER;
            rebuild();
        });
        if (source == PanelSettings.Source.FOLDER) {
            drawFiles(g, ix, y + 145, mx, my);
        }
        int fy = y + 177;
        Ui.text(g, font, Tr.t("panel.fit").toUpperCase(Locale.ROOT), ix, fy + 3, Ui.MUTED, TINY);
        wideChip(g, ix + 36, fy, 44, Tr.t("panel.fit.inside"), fit == PanelSettings.Fit.INSIDE, () -> fit = PanelSettings.Fit.INSIDE);
        wideChip(g, ix + 84, fy, 56, Tr.t("panel.fit.fill"), fit == PanelSettings.Fit.FILL, () -> fit = PanelSettings.Fit.FILL);
        wideChip(g, ix + 144, fy, 40, Tr.t("panel.fit.stretch"), fit == PanelSettings.Fit.STRETCH, () -> fit = PanelSettings.Fit.STRETCH);
        int ry2 = y + 191;
        Ui.text(g, font, Tr.t("panel.refresh").toUpperCase(Locale.ROOT), ix, ry2 + 3, Ui.MUTED, TINY);
        boolean canRefresh = imageSaved();
        key(g, ix + 40, ry2, 56, Tr.t("panel.refresh_now"), KeyStyle.PLAIN, canRefresh, mx, my, () -> {
            ServerUtilsNetwork.CHANNEL.sendToServer(new PanelRefreshPacket(opened.origin()));
            super.onClose();
        }, 11);
        if (source == PanelSettings.Source.WEB) {
            String[] labels = {Tr.t("panel.refresh.off"), Tr.t("panel.refresh.five"), "15", "60"};
            for (int i = 0; i < PanelSettings.REFRESH_MINUTES.length; i++) {
                int minutes = PanelSettings.REFRESH_MINUTES[i];
                int w = i < 2 ? 30 : 18;
                int cx = ix + 100 + (i == 0 ? 0 : i == 1 ? 34 : i == 2 ? 68 : 90);
                wideChip(g, cx, ry2, w, labels[i], refresh == minutes, () -> refresh = minutes);
            }
        } else if (!canRefresh) {
            Ui.text(g, font, Ui.fit(font, Tr.t("panel.save_first"), 120, TINY), ix + 100, ry2 + 3, Ui.MUTED, TINY);
        }
    }

    // ------------------------------------------------------------------ Staff online and Action log modes

    /** The face with live data (the staff roster or the log), drawn as in the world through the same face code. */
    private void drawLivePreview(GuiGraphics g, int x, int y) {
        int bw = opened.width(), bh = opened.height();
        float aspect = (float) bw / bh;
        int pw = (int) Math.min(MAIN_W, 100 * aspect), ph = (int) (pw / aspect);
        int px = x + MAIN_X + (MAIN_W - pw) / 2, py = y + 22 + (100 - ph) / 2;
        float k = (float) pw / (bw * PanelLayout.PX);
        int inset = Math.max(1, Math.round(PanelLayout.INSET * k));
        g.fill(px, py, px + pw, py + ph, 0xFF2A2F3A);
        g.fill(px + inset, py + inset, px + pw - inset, py + ph - inset, background == PanelSettings.CLEAR ? 0xFF6E6E6E : background);
        Ui.scissor(g, px, py, px + pw, py + ph);
        FaceCanvas canvas = FaceCanvas.gui(g, font, px, py, k);
        if (mode == PanelSettings.Mode.LOG) {
            LogFace.draw(canvas, bw, bh, new PanelSettings.LogView(logAll, logKinds, logLines));
        } else {
            StaffFace.draw(canvas, bw, bh, new PanelSettings.StaffView(staffOffline, staffRoles));
        }
        g.disableScissor();
    }

    private void drawStaffPane(GuiGraphics g, int x, int y) {
        int ix = x + MAIN_X, ry = y + 132;
        Ui.text(g, font, Tr.t("panel.show").toUpperCase(Locale.ROOT), ix, ry + 3, Ui.MUTED, TINY);
        wideChip(g, ix + 36, ry, 76, Tr.t("panel.staff.all"), staffOffline, () -> staffOffline = true);
        wideChip(g, ix + 116, ry, 56, Tr.t("panel.staff.online_only"), !staffOffline, () -> staffOffline = false);
        int cy = ry + 16;
        Ui.text(g, font, Tr.t("panel.roles").toUpperCase(Locale.ROOT), ix, cy + 3, Ui.MUTED, TINY);
        int cx = ix + 36;
        wideChip(g, cx, cy, 50, Tr.t("panel.staff.every_role"), staffRoles.isEmpty(), staffRoles::clear);
        cx += 54;
        java.util.LinkedHashMap<String, String> roles = new java.util.LinkedHashMap<>();
        for (com.mrgregles.bsp_server_utilities.network.StaffViewPacket.Row r : ClientStaff.rows()) {
            roles.putIfAbsent(r.group().isEmpty() ? "operator" : r.group(), r.label());
        }
        for (java.util.Map.Entry<String, String> e : roles.entrySet()) {
            int w = Math.min(70, Ui.width(font, e.getValue().toUpperCase(Locale.ROOT), TINY) + 10);
            if (cx + w > ix + MAIN_W) {
                cx = ix + 36;
                cy += 14;
            }
            String key = e.getKey();
            wideChip(g, cx, cy, w, e.getValue(), staffRoles.contains(key), () -> {
                if (!staffRoles.remove(key)) {
                    staffRoles.add(key);
                }
            });
            cx += w + 4;
        }
    }

    private void drawLogPane(GuiGraphics g, int x, int y) {
        int ix = x + MAIN_X, ry = y + 132;
        Ui.text(g, font, Tr.t("panel.show").toUpperCase(Locale.ROOT), ix, ry + 3, Ui.MUTED, TINY);
        wideChip(g, ix + 36, ry, 60, Tr.t("panel.log.all"), logAll, () -> logAll = true);
        wideChip(g, ix + 100, ry, 60, Tr.t("panel.log.here"), !logAll, () -> logAll = false);
        int cy = ry + 16;
        Ui.text(g, font, Tr.t("panel.kinds").toUpperCase(Locale.ROOT), ix, cy + 3, Ui.MUTED, TINY);
        int cx = ix + 36;
        wideChip(g, cx, cy, 50, Tr.t("panel.log.every_kind"), logKinds.isEmpty(), logKinds::clear);
        cx += 54;
        for (int i = 0; i < LOG_KINDS.length; i++) {
            String kind = LOG_KINDS[i], label = Tr.t(LOG_KIND_KEYS[i]);
            int w = Math.min(70, Ui.width(font, label.toUpperCase(Locale.ROOT), TINY) + 10);
            if (cx + w > ix + MAIN_W) {
                cx = ix + 36;
                cy += 14;
            }
            wideChip(g, cx, cy, w, label, logKinds.contains(kind), () -> {
                if (!logKinds.remove(kind)) {
                    logKinds.add(kind);
                }
            });
            cx += w + 4;
        }
        int ly = cy + 16;
        Ui.text(g, font, Tr.t("panel.lines").toUpperCase(Locale.ROOT), ix, ly + 3, Ui.MUTED, TINY);
        for (int i = 0; i < PanelSettings.LogView.LINE_CHOICES.length; i++) {
            int n = PanelSettings.LogView.LINE_CHOICES[i];
            wideChip(g, ix + 36 + i * 24, ly, 20, Integer.toString(n), logLines == n, () -> logLines = n);
        }
    }

    private void drawFiles(GuiGraphics g, int ix, int ly, int mx, int my) {
        List<ImageService.FolderFile> files = opened.files();
        int lh = 29;
        Ui.inset(g, ix, ly, MAIN_W, lh);
        if (files.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("panel.no_files"), MAIN_W - 8, TINY), ix + 4, ly + 11, Ui.MUTED, TINY);
            return;
        }
        fileScroll = Math.max(0, Math.min(fileScroll, Math.max(0, files.size() - 3)));
        for (int i = 0; i < 3 && fileScroll + i < files.size(); i++) {
            ImageService.FolderFile f = files.get(fileScroll + i);
            int ry = ly + 1 + i * 9;
            boolean on = f.name().equals(file);
            if (on) {
                g.fill(ix + 1, ry, ix + MAIN_W - 1, ry + 9, 0xFF1B2230);
                g.fill(ix + 1, ry, ix + 3, ry + 9, Ui.VIOLET);
            } else if (mx >= ix && mx < ix + MAIN_W && my >= ry && my < ry + 9) {
                g.fill(ix + 1, ry, ix + MAIN_W - 1, ry + 9, 0x22B58CFF);
            }
            String size = Tr.t("panel.file_size", f.width(), f.height(), Math.max(1, f.bytes() / 1024));
            int sw = Ui.width(font, size, TINY);
            Ui.text(g, font, Ui.fit(font, f.name(), MAIN_W - sw - 14, TINY), ix + 6, ry + 2, on ? Ui.TEXT : Ui.MUTED, TINY);
            Ui.text(g, font, size, ix + MAIN_W - 4 - sw, ry + 2, Ui.MUTED, TINY);
            String name = f.name();
            hits.add(new Ui.Hit(ix, ry, MAIN_W, 9, () -> file = name));
        }
    }

    private void wideChip(GuiGraphics g, int x, int y, int w, String label, boolean on, Runnable action) {
        g.fill(x, y, x + w, y + 11, Ui.SLOT);
        Ui.frame(g, x, y, w, 11, on ? Ui.VIOLET : Ui.DIM);
        String s = Ui.fit(font, label.toUpperCase(Locale.ROOT), w - 4, TINY);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + (w - Ui.width(font, s, TINY)) / 2, y + 3, on ? Ui.VIOLET : Ui.MUTED, TINY);
        hits.add(new Ui.Hit(x, y, w, 11, action));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mode == PanelSettings.Mode.IMAGE && source == PanelSettings.Source.FOLDER) {
            fileScroll -= (int) Math.signum(delta);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void drawPicker(GuiGraphics g, int mx, int my) {
        int px = pickerX, py = pickerY, pw = 8 * 12 + 8, ph = 48;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, Ui.VIOLET);
        g.fill(px, py, px + pw, py + ph, Ui.PANEL);
        PanelSettings.Line l = line();
        for (int i = 0; i < CHAT_COLOURS.length; i++) {
            int sx = px + 4 + (i % 8) * 12, sy = py + 4 + (i / 8) * 12;
            int c = CHAT_COLOURS[i];
            g.fill(sx, sy, sx + 10, sy + 10, 0xFF000000 | c);
            Ui.frame(g, sx - 1, sy - 1, 12, 12, l != null && l.colour() == c ? Ui.VIOLET : Ui.DIM);
            pickerHits.add(new Ui.Hit(sx - 1, sy - 1, 12, 12, () -> {
                colour(c);
                pickerOpen = false;
                rebuild();
            }));
        }
        Ui.text(g, font, "#", px + 6, py + 34, Ui.MUTED, SMALL);
        g.pose().popPose();
    }

    private final List<Ui.Hit> pickerHits = new ArrayList<>();
    /** The kinds in {@code LogEntry.KINDS} order, with their labels. */
    private static final String[] LOG_KINDS = com.mrgregles.bsp_server_utilities.log.LogEntry.PUBLIC_KINDS.toArray(String[]::new);
    private static final String[] LOG_KIND_KEYS = {"panel.log.kind.cmd", "panel.log.kind.item", "panel.log.kind.mode", "panel.log.kind.give", "panel.log.kind.switch"};

    private enum KeyStyle { PLAIN, VIOLET, WARN, SURE, GHOST }

    private void key(GuiGraphics g, int x, int y, int w, String label, KeyStyle style, boolean enabled, int mx, int my, Runnable action) {
        key(g, x, y, w, label, style, enabled, mx, my, action, style == KeyStyle.PLAIN && w <= 12 ? 11 : 13);
    }

    private void key(GuiGraphics g, int x, int y, int w, String label, KeyStyle style, boolean enabled, int mx, int my, Runnable action, int h) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        int text;
        switch (style) {
            case VIOLET -> {
                g.fill(x, y, x + w, y + h, hover ? 0xFFC9A8FF : Ui.VIOLET);
                text = Ui.INK;
            }
            case WARN -> {
                g.fill(x, y, x + w, y + h, hover ? 0xFF4A3030 : 0xFF3A2A2A);
                Ui.frame(g, x, y, w, h, 0xFFD63B2F);
                text = 0xFFFF8F86;
            }
            case SURE -> {
                g.fill(x, y, x + w, y + h, 0xFFD63B2F);
                text = 0xFFFFFFFF;
            }
            case GHOST -> {
                if (hover) {
                    g.fill(x, y, x + w, y + h, 0x222A2F3A);
                }
                Ui.frame(g, x, y, w, h, Ui.DIM);
                text = Ui.MUTED;
            }
            default -> {
                g.fill(x, y, x + w, y + h, hover ? 0xFF3A404C : Ui.DIM);
                text = Ui.TEXT;
            }
        }
        if (!enabled) {
            text = 0xFF5A6170;
            if (style == KeyStyle.VIOLET) {
                g.fill(x, y, x + w, y + h, 0xFF4A3D66);
            }
        }
        String s = Ui.fit(font, label, w - 4, TINY);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + (w - Ui.width(font, s, TINY)) / 2, y + (h - 5) / 2, text, TINY);
        if (enabled) {
            hits.add(new Ui.Hit(x, y, w, h, action));
        }
    }

    private void chip(GuiGraphics g, int x, int y, int w, String label, boolean on, Runnable action) {
        g.fill(x, y, x + w, y + 11, Ui.SLOT);
        Ui.frame(g, x, y, w, 11, on ? Ui.VIOLET : Ui.DIM);
        Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + (w - Ui.width(font, label, TINY)) / 2, y + 3, on ? Ui.VIOLET : Ui.MUTED, TINY);
        hits.add(new Ui.Hit(x, y, w, 11, action));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && pickerOpen) {
            for (Ui.Hit hit : List.copyOf(pickerHits)) {
                if (hit.contains(mx, my)) {
                    hit.action().run();
                    pickerHits.clear();
                    return true;
                }
            }
            if (hexBox != null && hexBox.isMouseOver(mx, my)) {
                return super.mouseClicked(mx, my, button);
            }
            boolean inside = mx >= pickerX && mx < pickerX + 104 && my >= pickerY && my < pickerY + 48;
            pickerHits.clear();
            if (!inside) {
                pickerOpen = false; // a click elsewhere closes the picker (D51)
                rebuild();
                return true;
            }
            return true;
        }
        if (button == 0) {
            for (Ui.Hit hit : List.copyOf(hits)) {
                if (hit.contains(mx, my)) {
                    setFocused(null);
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
