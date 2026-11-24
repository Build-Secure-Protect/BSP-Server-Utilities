package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.tutorial.Tutorial;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The popup editor (SU-143; owner decisions D723 to D728, D739 to D741; look AW A of
 * {@code tools/preview/tutorial_refined.html}): the popup's settings across the top (full or small, freezes or keeps
 * walking and for how long, background); on the left the layout grid (12 x 8, or 6 x 4 when small), where a box is made
 * by dragging over empty cells (+ TEXT or + IMAGE says which), moved by dragging it and sized by its corner, and under
 * it the picked box's editor (the text editor, or the picture's file, address and fit); on the right the preview as a
 * player sees it (for a small popup, the whole screen, where the popup is dragged anywhere and sized by its corner),
 * and under it the buttons, each with its label, colour and job. ‹ TREE takes the popup back to the builder.
 */
public class TutorialEditorScreen extends Screen {
    private static final int M = 6, BAR = 20;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int[] BACKGROUNDS = {0xFF10151C, 0xFF0C0E12, 0xFF1D2B44, 0xFF2B1D2F, 0xFF1F2B1F, 0};
    private static final int[] COLOURS = {0xE6EAF2, 0xFFD23A, 0x19D3B0, 0xFF6B5C, 0x9AA3B5, 0xB58CFF};
    private static final int[] KEY_COLOURS = {0x19D3B0, 0xFFD23A, 0xB58CFF, 0xFF6B5C, 0x2A2F3A};
    private final TutorialBuilderScreen builder;
    private final Tutorial tutorial;
    private final Tutorial.Point point;
    private TutorialPopup.Size size;
    private boolean freezes;
    private int seconds, background;
    private TutorialPopup.Placement place;
    private final List<TutorialPopup.Box> boxes = new ArrayList<>();
    private final List<TutorialPopup.Button> buttons = new ArrayList<>();
    private int picked = -1, pickedButton = -1, loadedBox = -1;
    private TutorialPopup.Kind addKind = TutorialPopup.Kind.TEXT;
    private final TutorialTextEditor text = new TutorialTextEditor();
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private Ui.Hit pressed;
    // dragging on the grid: making a box (from a cell), moving one, sizing one; on the preview: moving or sizing the small popup
    private int dragMode, dragBox, dragCol, dragRow, fileScroll;
    private double dragX, dragY;
    private int gx, gy, cell, rowH, px, py, pw, ph, half, lw;
    @Nullable
    private EditBox urlBox, labelBox, linkBox, hexBox;

    TutorialEditorScreen(TutorialBuilderScreen builder, Tutorial tutorial, int pointId) {
        super(Component.literal(Tr.t("tutorial.e.title")));
        this.builder = builder;
        this.tutorial = tutorial;
        this.point = tutorial.point(pointId);
        TutorialPopup p = point == null ? TutorialPopup.defaults() : point.popup();
        size = p.size();
        freezes = p.freezes();
        seconds = p.seconds();
        background = p.background();
        place = p.place();
        boxes.addAll(p.boxes());
        buttons.addAll(p.buttons());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private TutorialPopup popup() {
        return new TutorialPopup(size, freezes, seconds, background, place, boxes, buttons);
    }

    private boolean left;
    private int urlTicks;

    /** The popup to the builder, which saves it; the server then loads its pictures for the preview (audit of SU-146). */
    private void push() {
        commitText();
        if (point != null) {
            builder.popupEdited(point.id(), popup().sanitised(new HashSet<>(tutorial.children(point.id()))));
        }
    }

    @Override
    public void tick() {
        if (urlTicks > 0 && --urlTicks == 0) {
            push();
        }
        for (EditBox b : new EditBox[]{urlBox, labelBox, linkBox, hexBox}) {
            if (b != null) {
                b.tick();
            }
        }
    }

    /** Another screen in its place (SHOW AS PLAYER, a death screen): the edits are kept in the builder (audit of SU-146). */
    @Override
    public void removed() {
        if (!left) {
            push();
        }
        super.removed();
    }

    private void back() {
        left = true;
        commitText();
        if (point != null) {
            builder.popupEdited(point.id(), popup().sanitised(new HashSet<>(tutorial.children(point.id()))));
        }
        minecraft.setScreen(builder);
    }

    @Override
    public void onClose() {
        back();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        half = (width - 2 * M) / 2;
        gx = M + 8;
        // under the settings keys with room for the labels over the grid and the preview, and a row lower when the
        // settings take two rows (audit of SU-146)
        gy = M + BAR + 28 + (settingsWraps() ? 14 : 0);
        cell = Math.max(8, (half - 20) / size.cols);
        rowH = Math.max(8, Math.min(cell, (height / 2 - gy + 20) / size.rows));
        px = M + half + 6;
        py = gy;
        lw = half - 16;
        widgets();
    }

    /** The preview's size: 16 by 9 across the column, smaller while a button is picked so its editor fits under it (audit of SU-146). */
    private void sizePreview() {
        pw = lw;
        ph = Math.round(pw * 9f / 16);
        if (freezes && pickedButton >= 0 && pickedButton < buttons.size()) {
            int room = height - M - 3 - py - 12 - buttonEditorHeight();
            if (ph > room) {
                ph = Math.max(36, room);
                pw = Math.min(lw, Math.round(ph * 16f / 9));
                ph = Math.round(pw * 9f / 16);
            }
        }
    }

    /** How many rows the job keys take in the button editor, wrapping as it draws them. */
    private int jobRows() {
        int kx = px + labelW(Tr.t("tutorial.e.job")), rows = 1;
        for (TutorialPopup.Job j : TutorialPopup.Job.values()) {
            int kw = keyW(TutorialDraw.word(j));
            if (kx + kw > px + lw) {
                kx = px + 30;
                rows++;
            }
            kx += kw + 2;
        }
        return rows;
    }

    /** From the BUTTONS line: the colour row, the job row, the branch or link row, and DONE (audit of SU-146). */
    private int[] buttonRowsY(int top) {
        int colour = top + 25, job = top + 39, extra = job + 13 * (jobRows() - 1) + 15, done = extra + 17;
        return new int[]{colour, job, extra, done};
    }

    private int buttonEditorHeight() {
        return buttonRowsY(0)[3] + 12;
    }

    /** Whether BACKGROUND goes on a second settings row, as {@link #settingsRow} draws it. */
    private boolean settingsWraps() {
        int x = M + 8;
        return x + settingsMainW() + backgroundW() > width - M - 8;
    }

    private int settingsMainW() {
        int w = labelW(Tr.t("tutorial.e.size")) + keyW(Tr.t("tutorial.b.full")) + keyW(Tr.t("tutorial.b.small")) + 8
                + labelW(Tr.t("tutorial.e.player")) + keyW(Tr.t("tutorial.b.freezes")) + keyW(Tr.t("tutorial.e.walks")) + 8;
        if (!freezes) {
            w += labelW(Tr.t("tutorial.e.on_screen")) + keyW("−") + 22 + keyW("+") + 8;
        }
        return w;
    }

    private int backgroundW() {
        return labelW(Tr.t("tutorial.e.background")) + BACKGROUNDS.length * 12;
    }

    private void widgets() {
        setFocused(null); // a field made again must not keep taking keys (audit of SU-146)
        clearWidgets();
        urlBox = labelBox = linkBox = hexBox = null;
        text.focused = false;
        sizePreview();
        int ey = gy + rowH * size.rows + 16;
        int ew = cell * size.cols;
        TutorialPopup.Box b = picked >= 0 && picked < boxes.size() ? boxes.get(picked) : null;
        if (b != null && b.kind() == TutorialPopup.Kind.TEXT) {
            if (loadedBox != picked) {
                text.load(b.text(), this::commitText); // the preview follows the typing (audit of SU-146)
                loadedBox = picked;
            }
            // the toolbar takes two rows (styles and colours; sizes, alignment and the hex box), the text under them (audit of SU-146)
            text.place(gx, ey + 30, ew, Math.max(30, height - M - 14 - (ey + 30)));
            hexBox = new EditBox(font, gx + ew - 46, ey + 15, 46, 11, Component.literal("#"));
            hexBox.setMaxLength(7);
            hexBox.setValue(String.format(Locale.ROOT, "#%06X", text.colourNow()));
            hexBox.setResponder(s -> {
                String h = s.startsWith("#") ? s.substring(1) : s;
                if (h.length() == 6) {
                    try {
                        text.colour(Integer.parseInt(h, 16));
                    } catch (NumberFormatException ignored) {
                        // not a colour yet
                    }
                }
            });
            addRenderableWidget(hexBox);
        } else if (b != null) {
            urlBox = new EditBox(font, gx, ey + 30, ew, 12, Component.literal(Tr.t("tutorial.e.url")));
            urlBox.setMaxLength(TutorialPopup.MAX_URL);
            urlBox.setValue(b.image().url());
            int i = picked;
            urlBox.setResponder(s -> {
                if (i >= boxes.size()) {
                    return;
                }
                urlTicks = 20; // the server loads it once the typing stops
                TutorialPopup.Box now = boxes.get(i);
                setBox(i, new TutorialPopup.Box(now.col(), now.row(), now.w(), now.h(), now.kind(), now.text(),
                        new TutorialPopup.Image(TutorialPopup.Source.WEB, now.image().file(), s.strip(), now.image().fit()), now.fill()));
            });
            urlBox.visible = b.image().source() == TutorialPopup.Source.WEB;
            addRenderableWidget(urlBox);
        }
        if (freezes && pickedButton >= 0 && pickedButton < buttons.size()) {
            TutorialPopup.Button k = buttons.get(pickedButton);
            int bx = px, by = py + ph + 22;
            labelBox = new EditBox(font, bx + 34, by, lw - 34, 12, Component.literal(Tr.t("tutorial.e.label")));
            labelBox.setMaxLength(TutorialPopup.MAX_LABEL);
            labelBox.setValue(k.label());
            int i = pickedButton;
            labelBox.setResponder(s -> {
                if (i >= buttons.size()) {
                    return;
                }
                TutorialPopup.Button now = buttons.get(i);
                buttons.set(i, new TutorialPopup.Button(s, now.colour(), now.job(), now.target(), now.url()));
            });
            addRenderableWidget(labelBox);
            if (k.job() == TutorialPopup.Job.LINK) {
                // in the row under the job keys, however many rows they take (audit of SU-146)
                linkBox = new EditBox(font, bx + 34, buttonRowsY(py + ph + 12)[2], lw - 34, 12, Component.literal(Tr.t("tutorial.e.link")));
                linkBox.setMaxLength(512);
                linkBox.setValue(k.url());
                linkBox.setResponder(s -> {
                    if (i >= buttons.size()) {
                        return;
                    }
                    TutorialPopup.Button now = buttons.get(i);
                    buttons.set(i, new TutorialPopup.Button(now.label(), now.colour(), now.job(), now.target(), s.strip()));
                });
                addRenderableWidget(linkBox);
            }
        }
    }

    private void setBox(int i, TutorialPopup.Box b) {
        boxes.set(i, b);
    }

    /** The text editor's words into its box. */
    private void commitText() {
        if (picked >= 0 && picked == loadedBox && picked < boxes.size() && boxes.get(picked).kind() == TutorialPopup.Kind.TEXT) {
            TutorialPopup.Box b = boxes.get(picked);
            boxes.set(picked, new TutorialPopup.Box(b.col(), b.row(), b.w(), b.h(), b.kind(), text.text(), b.image(), b.fill()));
        }
    }

    private void pick(int box) {
        commitText();
        picked = box;
        widgets();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        hits.clear();
        int w = width - 2 * M, h = height - 2 * M;
        g.fill(M, M, M + w, M + h, Ui.PANEL);
        Ui.frame(g, M, M, w, h, Ui.VIOLET);
        // the bar
        g.fill(M + 1, M + BAR - 1, M + w - 1, M + BAR, Ui.DIM);
        int kx = M + 6;
        kx += key(g, kx, M + 4, Tr.t("tutorial.e.tree"), Ui.DIM, mx, my, this::back) + 6;
        String title = Tr.t("tutorial.e.popup", point == null ? "?" : point.title());
        Ui.text(g, font, Component.literal(Ui.fitBold(font, title.toUpperCase(Locale.ROOT), w / 2, 0.8f)).withStyle(ChatFormatting.BOLD), kx, M + 7, Ui.VIOLET, 0.8f);
        String show = Tr.t("tutorial.e.show");
        key(g, M + w - 8 - keyW(show), M + 4, show, Ui.DIM, mx, my, () -> {
            commitText();
            minecraft.setScreen(new Preview(this, tutorial.name(), popup(), i -> {
                String hash = imageHash(i);
                return hash.isEmpty() ? null : ClientPanelImages.texture(hash);
            }));
        });
        settingsRow(g, M + 8, M + BAR + 6, mx, my);
        grid(g, mx, my);
        boxEditor(g, mx, my);
        preview(g, mx, my);
        buttonList(g, mx, my);
        if (picked >= 0 && picked < boxes.size() && boxes.get(picked).kind() == TutorialPopup.Kind.TEXT) {
            text.render(g, font);
        }
        super.render(g, mx, my, partial);
    }

    private void settingsRow(GuiGraphics g, int x, int y, int mx, int my) {
        int kx = x;
        kx = label(g, kx, y, Tr.t("tutorial.e.size"));
        kx += key(g, kx, y, Tr.t("tutorial.b.full"), size == TutorialPopup.Size.FULL ? Ui.VIOLET : Ui.DIM, mx, my, () -> resize(TutorialPopup.Size.FULL));
        kx += key(g, kx, y, Tr.t("tutorial.b.small"), size == TutorialPopup.Size.SMALL ? Ui.VIOLET : Ui.DIM, mx, my, () -> resize(TutorialPopup.Size.SMALL)) + 8;
        kx = label(g, kx, y, Tr.t("tutorial.e.player"));
        kx += key(g, kx, y, Tr.t("tutorial.b.freezes"), freezes ? Ui.VIOLET : Ui.DIM, mx, my, () -> {
            freezes = true;
            init(); // the settings row may now take one row or two (audit of SU-146)
        });
        kx += key(g, kx, y, Tr.t("tutorial.e.walks"), !freezes ? Ui.VIOLET : Ui.DIM, mx, my, () -> {
            freezes = false;
            pickedButton = -1;
            init();
        }) + 8;
        if (!freezes) {
            kx = label(g, kx, y, Tr.t("tutorial.e.on_screen"));
            kx += key(g, kx, y, "−", Ui.DIM, mx, my, () -> seconds = Math.max(TutorialPopup.MIN_SECONDS, seconds - 1));
            Ui.text(g, font, seconds + " s", kx + 3, y + 3, Ui.TEXT, SMALL);
            kx += 22;
            kx += key(g, kx, y, "+", Ui.DIM, mx, my, () -> seconds = Math.min(TutorialPopup.MAX_SECONDS, seconds + 1)) + 8;
        }
        if (settingsWraps()) {
            kx = x; // BACKGROUND on a second row when it does not fit (audit of SU-146)
            y += 14;
        }
        kx = label(g, kx, y, Tr.t("tutorial.e.background"));
        for (int bg : BACKGROUNDS) {
            if (bg == 0) {
                g.fill(kx, y + 2, kx + 4, y + 6, 0xFF555555);
                g.fill(kx + 4, y + 6, kx + 8, y + 10, 0xFF555555);
                g.fill(kx + 4, y + 2, kx + 8, y + 6, 0xFF999999);
                g.fill(kx, y + 6, kx + 4, y + 10, 0xFF999999);
            } else {
                g.fill(kx, y + 2, kx + 8, y + 10, bg);
            }
            Ui.frame(g, kx - 1, y + 1, 10, 10, background == bg ? Ui.VIOLET : Ui.DIM);
            int b = bg;
            hits.add(new Ui.Hit(kx - 1, y + 1, 10, 10, () -> background = b));
            kx += 12;
        }
    }

    private int label(GuiGraphics g, int x, int y, String s) {
        String t = s.toUpperCase(Locale.ROOT);
        Ui.text(g, font, t, x, y + 4, 0xFF6B7180, TINY);
        return x + labelW(s);
    }

    private int labelW(String s) {
        return Ui.width(font, s.toUpperCase(Locale.ROOT), TINY) + 4;
    }

    private void resize(TutorialPopup.Size next) {
        if (next == size) {
            return;
        }
        commitText();
        size = next;
        // boxes cut to the new grid; any that fall off or overlap go
        TutorialPopup cut = popup().sanitised(new HashSet<>(tutorial.children(point == null ? -1 : point.id())));
        boxes.clear();
        boxes.addAll(cut.boxes());
        picked = loadedBox = -1;
        init();
    }

    private void grid(GuiGraphics g, int mx, int my) {
        Ui.text(g, font, Tr.t("tutorial.e.layout", size.cols, size.rows).toUpperCase(Locale.ROOT), gx, gy - 9, 0xFF6B7180, TINY);
        int w = cell * size.cols, h = rowH * size.rows;
        g.fill(gx, gy, gx + w, gy + h, Ui.SLOT);
        for (int c = 0; c <= size.cols; c++) {
            g.fill(gx + c * cell, gy, gx + c * cell + 1, gy + h, 0x1A9AA3B5);
        }
        for (int r = 0; r <= size.rows; r++) {
            g.fill(gx, gy + r * rowH, gx + w, gy + r * rowH + 1, 0x1A9AA3B5);
        }
        Ui.frame(g, gx, gy, w, h, Ui.DIM);
        for (int i = 0; i < boxes.size(); i++) {
            TutorialPopup.Box b = boxes.get(i);
            int bx = gx + b.col() * cell + 1, by = gy + b.row() * rowH + 1, bw = b.w() * cell - 2, bh = b.h() * rowH - 2;
            boolean img = b.kind() == TutorialPopup.Kind.IMAGE, sel = i == picked;
            g.fill(bx, by, bx + bw, by + bh, sel ? 0x1FB58CFF : img ? 0x1A19D3B0 : 0x8C2A2F3A);
            Ui.frame(g, bx, by, bw, bh, sel ? Ui.VIOLET : img ? 0x8019D3B0 : 0xFF3A4150);
            String s = img ? "▣ " + (b.image().isSet() ? (b.image().source() == TutorialPopup.Source.FILE ? b.image().file() : Tr.t("tutorial.e.web")) : Tr.t("tutorial.e.image"))
                    : "T · " + (b.text().isEmpty() ? Tr.t("tutorial.e.empty") : b.text().plain().replace('\n', ' '));
            Ui.text(g, font, Ui.fit(font, s, bw - 4, TINY), bx + 3, by + 3, sel ? Ui.VIOLET : img ? Ui.TEAL : Ui.MUTED, TINY);
            if (sel) {
                g.fill(bx + bw - 3, by + bh - 3, bx + bw + 1, by + bh + 1, Ui.VIOLET);
            }
        }
        if (dragMode == 1) {
            int[] r = dragRect(mx, my);
            Ui.frame(g, gx + r[0] * cell, gy + r[1] * rowH, r[2] * cell, r[3] * rowH, Ui.VIOLET);
        }
        int ky = gy + h + 3, kx = gx;
        kx += key(g, kx, ky, Tr.t("tutorial.e.add_text"), addKind == TutorialPopup.Kind.TEXT ? Ui.VIOLET : Ui.DIM, mx, my, () -> addBox(TutorialPopup.Kind.TEXT)) + 3;
        kx += key(g, kx, ky, Tr.t("tutorial.e.add_image"), addKind == TutorialPopup.Kind.IMAGE ? Ui.VIOLET : Ui.DIM, mx, my, () -> addBox(TutorialPopup.Kind.IMAGE)) + 3;
        if (picked >= 0) {
            key(g, kx, ky, Tr.t("tutorial.e.delete_box"), Ui.BAD, mx, my, () -> {
                boxes.remove(picked);
                picked = loadedBox = -1;
                widgets();
            });
        }
    }

    /** + TEXT or + IMAGE: what a drag over empty cells makes; and a box at once in the first free place. */
    private void addBox(TutorialPopup.Kind kind) {
        addKind = kind;
        if (boxes.size() >= TutorialPopup.MAX_BOXES) {
            return;
        }
        for (int r = 0; r < size.rows; r++) {
            for (int c = 0; c < size.cols; c++) {
                int bw = Math.min(kind == TutorialPopup.Kind.TEXT ? 4 : 3, size.cols - c), bh = Math.min(2, size.rows - r);
                TutorialPopup.Box b = new TutorialPopup.Box(c, r, bw, bh, kind, TutorialText.EMPTY, TutorialPopup.Image.NONE, 0);
                if (boxes.stream().noneMatch(b::overlaps)) {
                    commitText();
                    boxes.add(b);
                    picked = boxes.size() - 1;
                    widgets();
                    return;
                }
            }
        }
    }

    private int[] dragRect(double mx, double my) {
        int c = clamp((int) ((mx - gx) / cell), 0, size.cols - 1), r = clamp((int) ((my - gy) / rowH), 0, size.rows - 1);
        int c0 = Math.min(c, dragCol), r0 = Math.min(r, dragRow);
        return new int[]{c0, r0, Math.abs(c - dragCol) + 1, Math.abs(r - dragRow) + 1};
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void boxEditor(GuiGraphics g, int mx, int my) {
        int ey = gy + rowH * size.rows + 16, ew = cell * size.cols;
        if (picked < 0 || picked >= boxes.size()) {
            int ty = ey + 4;
            for (String line : Ui.wrap(font, Tr.t("tutorial.e.grid_help"), ew, SMALL)) {
                Ui.text(g, font, line, gx, ty, Ui.MUTED, SMALL);
                ty += 9;
            }
            return;
        }
        TutorialPopup.Box b = boxes.get(picked);
        if (b.kind() == TutorialPopup.Kind.TEXT) {
            int kx = gx;
            kx += tool(g, kx, ey, "B", text.has(TutorialText.BOLD), mx, my, () -> text.toggle(TutorialText.BOLD));
            kx += tool(g, kx, ey, "I", text.has(TutorialText.ITALIC), mx, my, () -> text.toggle(TutorialText.ITALIC));
            kx += tool(g, kx, ey, "U", text.has(TutorialText.UNDERLINE), mx, my, () -> text.toggle(TutorialText.UNDERLINE));
            kx += tool(g, kx, ey, "S", text.has(TutorialText.STRIKE), mx, my, () -> text.toggle(TutorialText.STRIKE));
            kx += tool(g, kx, ey, "▒", text.has(TutorialText.OBFUSCATED), mx, my, () -> text.toggle(TutorialText.OBFUSCATED)) + 3;
            for (int c : COLOURS) {
                g.fill(kx, ey + 2, kx + 8, ey + 10, 0xFF000000 | c);
                Ui.frame(g, kx - 1, ey + 1, 10, 10, text.colourNow() == c ? Ui.VIOLET : Ui.DIM);
                hits.add(new Ui.Hit(kx - 1, ey + 1, 10, 10, () -> {
                    text.colour(c);
                    if (hexBox != null) {
                        hexBox.setValue(String.format(Locale.ROOT, "#%06X", c));
                    }
                }));
                kx += 11;
            }
            // the second row: sizes and alignment, the hex box at its right (audit of SU-146)
            int ry = ey + 14;
            kx = gx;
            for (TutorialText.Size s : TutorialText.Size.values()) {
                kx += tool(g, kx, ry, s.name(), text.sizeNow() == s, mx, my, () -> text.size(s));
            }
            kx += 3;
            String[] al = {"⇤", "≡", "⇥"};
            for (TutorialText.Align a : TutorialText.Align.values()) {
                kx += tool(g, kx, ry, al[a.ordinal()], text.alignNow() == a, mx, my, () -> text.align(a));
            }
            return;
        }
        // a picture: from the folder or the web, and its fit
        int kx = gx;
        kx += key(g, kx, ey, Tr.t("tutorial.e.server_file"), b.image().source() == TutorialPopup.Source.FILE ? Ui.VIOLET : Ui.DIM, mx, my, () -> source(TutorialPopup.Source.FILE));
        key(g, kx, ey, Tr.t("tutorial.e.web_address"), b.image().source() == TutorialPopup.Source.WEB ? Ui.VIOLET : Ui.DIM, mx, my, () -> source(TutorialPopup.Source.WEB));
        int fy = ey + 15;
        kx = gx;
        kx = label(g, kx, fy, Tr.t("tutorial.e.fit"));
        for (TutorialPopup.Fit f : TutorialPopup.Fit.values()) {
            kx += key(g, kx, fy, TutorialDraw.word(f), b.image().fit() == f ? Ui.VIOLET : Ui.DIM, mx, my, () -> {
                TutorialPopup.Box now = boxes.get(picked);
                setBox(picked, new TutorialPopup.Box(now.col(), now.row(), now.w(), now.h(), now.kind(), now.text(),
                        new TutorialPopup.Image(now.image().source(), now.image().file(), now.image().url(), f), now.fill()));
                push();
            });
        }
        String err = imageError(picked);
        int ly = ey + 30, lh = Math.max(20, height - M - 20 - ly);
        if (!err.isEmpty()) {
            // under the server-file list (and its folder line), or under the address box (audit of SU-146)
            int ery = b.image().source() == TutorialPopup.Source.FILE ? ly + lh + 9 : ey + 46;
            Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.e.image_failed", err), ew, TINY), gx, ery, Ui.BAD, TINY);
        }
        if (b.image().source() == TutorialPopup.Source.FILE) {
            List<String> files = TutorialBuilderScreen.files(ClientTutorialAdmin.view());
            Ui.inset(g, gx, ly, ew, lh);
            if (files.isEmpty()) {
                int ty = ly + 3;
                for (String line : Ui.wrap(font, Tr.t("tutorial.e.no_files"), ew - 8, TINY)) {
                    Ui.text(g, font, line, gx + 4, ty, Ui.MUTED, TINY);
                    ty += 7;
                }
                return;
            }
            int rows = (lh - 2) / 9;
            fileScroll = clamp(fileScroll, 0, Math.max(0, files.size() - rows));
            for (int i = 0; i < rows && i + fileScroll < files.size(); i++) {
                String f = files.get(i + fileScroll);
                int ry = ly + 1 + i * 9;
                boolean on = f.equals(b.image().file());
                if (on) {
                    g.fill(gx + 1, ry, gx + ew - 1, ry + 9, 0xFF1A1530);
                }
                Ui.text(g, font, Ui.fit(font, f, ew - 8, SMALL), gx + 4, ry + 2, on ? Ui.VIOLET : Ui.TEXT, SMALL);
                hits.add(new Ui.Hit(gx + 1, ry, ew - 2, 9, () -> {
                    TutorialPopup.Box now = boxes.get(picked);
                    setBox(picked, new TutorialPopup.Box(now.col(), now.row(), now.w(), now.h(), now.kind(), now.text(),
                            new TutorialPopup.Image(TutorialPopup.Source.FILE, f, now.image().url(), now.image().fit()), now.fill()));
                    push();
                }));
            }
            Ui.text(g, font, Ui.fit(font, "config/bsp_server_utilities/tutorial_images/", ew, 0.5f), gx, ly + lh + 2, 0xFF6B7180, 0.5f);
        }
    }

    private void source(TutorialPopup.Source s) {
        TutorialPopup.Box now = boxes.get(picked);
        setBox(picked, new TutorialPopup.Box(now.col(), now.row(), now.w(), now.h(), now.kind(), now.text(),
                new TutorialPopup.Image(s, now.image().file(), now.image().url(), now.image().fit()), now.fill()));
        widgets();
        push();
    }

    /** The server's word on a box's picture: its hash when loaded, or why it failed. */
    private String imageHash(int box) {
        return imageField(box, "hash");
    }

    private String imageError(int box) {
        return imageField(box, "error");
    }

    /** Matched by the picture and the box's size, not its place in the list, so moved and new boxes find theirs (audit of SU-146). */
    private String imageField(int box, String field) {
        CompoundTag v = ClientTutorialAdmin.view();
        if (v == null || box < 0 || box >= boxes.size() || boxes.get(box).kind() != TutorialPopup.Kind.IMAGE) {
            return "";
        }
        String key = imageKey(boxes.get(box));
        for (Tag t : v.getList("images", Tag.TAG_COMPOUND)) {
            CompoundTag im = (CompoundTag) t;
            if (im.getString("key").equals(key)) {
                return im.getString(field);
            }
        }
        return "";
    }

    static String imageKey(TutorialPopup.Box b) {
        TutorialPopup.Image i = b.image();
        return i.source() + "|" + (i.source() == TutorialPopup.Source.WEB ? i.url() : i.file()) + "|" + b.w() + "x" + b.h();
    }

    private void preview(GuiGraphics g, int mx, int my) {
        Ui.text(g, font, Tr.t(size == TutorialPopup.Size.SMALL ? "tutorial.e.place_hint" : "tutorial.e.preview").toUpperCase(Locale.ROOT), px, py - 9, 0xFF6B7180, TINY);
        g.fill(px, py, px + pw, py + ph, 0xFF2A3F27);
        g.fill(px, py, px + pw, py + ph / 3, 0xFF4A6F9C);
        g.fill(px + pw / 2 - pw * 182 / 960, py + ph - ph * 26 / 270, px + pw / 2 + pw * 182 / 960, py + ph - 2, 0x73000000);
        Ui.scissor(g, px, py, px + pw, py + ph);
        g.pose().pushPose();
        g.pose().translate(px, py, 0);
        float s = pw / 480f;
        g.pose().scale(s, s, 1);
        TutorialPopup pop = popup();
        TutorialPopupScreen.drawPreview(g, font, tutorial.name(), pop, i -> {
            String hash = imageHash(i);
            return hash.isEmpty() ? null : ClientPanelImages.texture(hash);
        }, 480, 270);
        g.pose().popPose();
        if (size == TutorialPopup.Size.SMALL) {
            int[] r = smallRect(pop);
            Ui.frame(g, r[0], r[1], r[2], r[3], Ui.VIOLET);
            g.fill(r[0] + r[2] - 3, r[1] + r[3] - 3, r[0] + r[2] + 1, r[1] + r[3] + 1, Ui.VIOLET);
        }
        g.disableScissor();
        Ui.frame(g, px, py, pw, ph, Ui.DIM);
        if (size == TutorialPopup.Size.SMALL) {
            String at = Tr.t("tutorial.e.placed", place.x() / 10, place.y() / 10, place.w() * 480 / 1000, place.h() * 270 / 1000);
            Ui.text(g, font, at, px, py + ph + 3, Ui.MUTED, TINY);
        }
    }

    /** The small popup's rectangle on the preview, in screen units. */
    private int[] smallRect(TutorialPopup pop) {
        int[] r = TutorialPopupScreen.smallRect(pop, 480, 270);
        float s = pw / 480f;
        return new int[]{Math.round(px + r[0] * s), Math.round(py + r[1] * s), Math.round(r[2] * s), Math.round(r[3] * s)};
    }

    private void buttonList(GuiGraphics g, int mx, int my) {
        int x = px, y = py + ph + 12, w = lw;
        if (!freezes) {
            int ty = y + 2;
            for (String line : Ui.wrap(font, Tr.t("tutorial.e.walking_note"), w, SMALL)) {
                Ui.text(g, font, line, x, ty, Ui.MUTED, SMALL);
                ty += 9;
            }
            return;
        }
        Ui.text(g, font, Tr.t("tutorial.e.buttons", buttons.size(), TutorialPopup.MAX_BUTTONS).toUpperCase(Locale.ROOT), x, y, 0xFF6B7180, TINY);
        if (buttons.size() < TutorialPopup.MAX_BUTTONS) {
            String add = Tr.t("tutorial.e.add_button");
            key(g, x + w - keyW(add), y - 3, add, Ui.DIM, mx, my, () -> {
                buttons.add(TutorialPopup.Button.of(Tr.t("tutorial.job.next"), TutorialPopup.Job.NEXT));
                pickedButton = buttons.size() - 1;
                widgets();
            });
        }
        if (pickedButton >= 0 && pickedButton < buttons.size()) {
            buttonEditor(g, x, y + 10, w, mx, my);
            return;
        }
        int ry = y + 10;
        // rows tighter when the panel is short, so the last stays inside it (audit of SU-146)
        int step = buttons.isEmpty() ? 15 : Math.max(11, Math.min(15, (height - M - 3 - ry) / buttons.size()));
        for (int i = 0; i < buttons.size(); i++) {
            TutorialPopup.Button b = buttons.get(i);
            g.fill(x, ry, x + w, ry + step - 1, Ui.SLOT);
            TutorialDraw.button(g, font, x + 2, ry + 1, 60, step - 3, b.label(), b.colour(), false, true);
            String job = TutorialDraw.word(b.job()) + (b.job() == TutorialPopup.Job.BRANCH && tutorial.point(b.target()) != null
                    ? " → " + tutorial.point(b.target()).title() : b.job() == TutorialPopup.Job.LINK ? " " + b.url() : "");
            Ui.text(g, font, Ui.fit(font, job, w - 70, TINY), x + 66, ry + (step - 1 - 5) / 2, Ui.MUTED, TINY);
            int idx = i;
            hits.add(new Ui.Hit(x, ry, w, step - 1, () -> {
                pickedButton = idx;
                widgets();
            }));
            ry += step;
        }
    }

    private void buttonEditor(GuiGraphics g, int x, int y, int w, int mx, int my) {
        TutorialPopup.Button b = buttons.get(pickedButton);
        int i = pickedButton;
        Ui.text(g, font, Tr.t("tutorial.e.label").toUpperCase(Locale.ROOT), x, y + 3, 0xFF6B7180, TINY);
        int[] rows = buttonRowsY(y - 10); // as widgets() places the link box (audit of SU-146)
        int cy = rows[0];
        int kx = label(g, x, cy, Tr.t("tutorial.e.colour"));
        for (int c : KEY_COLOURS) {
            g.fill(kx, cy + 2, kx + 8, cy + 10, 0xFF000000 | c);
            Ui.frame(g, kx - 1, cy + 1, 10, 10, b.colour() == c ? Ui.VIOLET : Ui.DIM);
            hits.add(new Ui.Hit(kx - 1, cy + 1, 10, 10, () -> buttons.set(i, new TutorialPopup.Button(buttons.get(i).label(), c, buttons.get(i).job(), buttons.get(i).target(), buttons.get(i).url()))));
            kx += 12;
        }
        cy = rows[1];
        kx = label(g, x, cy, Tr.t("tutorial.e.job"));
        for (TutorialPopup.Job j : TutorialPopup.Job.values()) {
            boolean can = j != TutorialPopup.Job.BRANCH || tutorial.children(point.id()).size() > 1;
            String l = TutorialDraw.word(j);
            if (kx + keyW(l) > x + w) {
                kx = x + 30;
                cy += 13;
            }
            kx += key(g, kx, cy, l, b.job() == j ? Ui.VIOLET : can ? Ui.DIM : Ui.SLOT, mx, my, () -> {
                if (can) {
                    List<Integer> kids = tutorial.children(point.id());
                    buttons.set(i, new TutorialPopup.Button(buttons.get(i).label(), buttons.get(i).colour(), j, j == TutorialPopup.Job.BRANCH && !kids.isEmpty() ? kids.get(0) : -1, buttons.get(i).url()));
                    widgets();
                }
            }) + 2;
        }
        cy = rows[2];
        if (b.job() == TutorialPopup.Job.BRANCH) {
            kx = label(g, x, cy, Tr.t("tutorial.e.branch"));
            List<Tutorial.Point> kids = new ArrayList<>();
            for (int k : tutorial.children(point.id())) {
                if (tutorial.point(k) != null) {
                    kids.add(tutorial.point(k));
                }
            }
            // each branch key at most an equal share of the row, its title cut to fit (audit of SU-146)
            int share = kids.isEmpty() ? 0 : (x + w - kx - 2 * (kids.size() - 1)) / kids.size();
            for (Tutorial.Point kp : kids) {
                int k = kp.id();
                kx += key(g, kx, cy, Math.min(keyW(kp.title()), share), kp.title(), b.target() == k ? Ui.VIOLET : Ui.DIM, mx, my, () -> buttons.set(i, new TutorialPopup.Button(buttons.get(i).label(), buttons.get(i).colour(),
                        TutorialPopup.Job.BRANCH, k, ""))) + 2;
            }
        } else if (b.job() == TutorialPopup.Job.LINK) {
            label(g, x, cy, Tr.t("tutorial.e.link"));
        }
        cy = rows[3];
        kx = x;
        kx += key(g, kx, cy, Tr.t("tutorial.e.done"), Ui.VIOLET, mx, my, () -> {
            pickedButton = -1;
            widgets();
        }) + 3;
        if (i > 0) {
            kx += key(g, kx, cy, "←", Ui.DIM, mx, my, () -> {
                buttons.add(i - 1, buttons.remove(i));
                pickedButton = i - 1;
                widgets();
            }) + 3;
        }
        if (i < buttons.size() - 1) {
            kx += key(g, kx, cy, "→", Ui.DIM, mx, my, () -> {
                buttons.add(i + 1, buttons.remove(i));
                pickedButton = i + 1;
                widgets();
            }) + 3;
        }
        key(g, kx, cy, Tr.t("tutorial.e.remove"), Ui.BAD, mx, my, () -> {
            buttons.remove(i);
            pickedButton = -1;
            widgets();
        });
    }

    private int tool(GuiGraphics g, int x, int y, String s, boolean on, int mx, int my, Runnable action) {
        int w = Math.max(11, Ui.widthBold(font, s, SMALL) + 5); // measured bold (audit of SU-146)
        g.fill(x, y, x + w, y + 12, Ui.SLOT);
        Ui.frame(g, x, y, w, 12, on ? Ui.VIOLET : Ui.DIM);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + (w - Ui.widthBold(font, s, SMALL)) / 2, y + 3, on ? Ui.VIOLET : Ui.TEXT, SMALL);
        hits.add(new Ui.Hit(x, y, w, 12, action));
        return w + 1;
    }

    private int keyW(String label) {
        return Math.max(14, Ui.widthBold(font, label.toUpperCase(Locale.ROOT), TINY) + 10); // measured bold (audit of SU-146)
    }

    private int key(GuiGraphics g, int x, int y, String label, int colour, int mx, int my, Runnable action) {
        return key(g, x, y, keyW(label), label, colour, mx, my, action);
    }

    /** A key of a given width, its label cut to fit. */
    private int key(GuiGraphics g, int x, int y, int w, String label, int colour, int mx, int my, Runnable action) {
        int h = 12;
        boolean hot = mx >= x && mx < x + w && my >= y && my < y + h;
        boolean filled = colour == Ui.VIOLET || colour == Ui.BAD || colour == Ui.GOLD;
        g.fill(x, y, x + w, y + h, filled ? colour : Ui.SLOT);
        Ui.frame(g, x, y, w, h, hot ? 0xFFFFFFFF : filled ? colour : Ui.DIM);
        String t = Ui.fitBold(font, label.toUpperCase(Locale.ROOT), w - 4, TINY);
        Ui.text(g, font, Component.literal(t).withStyle(ChatFormatting.BOLD), x + (w - Ui.widthBold(font, t, TINY)) / 2, y + 3, filled ? Ui.INK : colour == Ui.SLOT ? 0xFF3A4150 : Ui.MUTED, TINY);
        hits.add(new Ui.Hit(x, y, w, h, action));
        return w;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            text.focused = false;
            return true;
        }
        pressed = null;
        if (button != 0) {
            return false;
        }
        for (Ui.Hit h : hits) {
            if (h.contains(mx, my)) {
                pressed = h;
                return true;
            }
        }
        if (text.contains(mx, my) && picked >= 0 && boxes.get(picked).kind() == TutorialPopup.Kind.TEXT) {
            text.focused = true;
            setFocused(null);
            text.click(mx, my, hasShiftDown());
            dragMode = 5;
            return true;
        }
        text.focused = false;
        int w = cell * size.cols, h = rowH * size.rows;
        if (mx >= gx && mx < gx + w && my >= gy && my < gy + h) {
            int c = (int) ((mx - gx) / cell), r = (int) ((my - gy) / rowH);
            for (int i = boxes.size() - 1; i >= 0; i--) {
                TutorialPopup.Box b = boxes.get(i);
                int bx = gx + b.col() * cell, by = gy + b.row() * rowH, bw = b.w() * cell, bh = b.h() * rowH;
                if (mx >= bx && mx < bx + bw && my >= by && my < by + bh) {
                    if (i != picked) {
                        pick(i);
                    }
                    boolean corner = mx >= bx + bw - 5 && my >= by + bh - 5;
                    dragMode = corner ? 3 : 2;
                    dragBox = i;
                    dragCol = c - b.col();
                    dragRow = r - b.row();
                    return true;
                }
            }
            if (boxes.size() < TutorialPopup.MAX_BOXES) {
                dragMode = 1;
                dragCol = c;
                dragRow = r;
            }
            return true;
        }
        if (size == TutorialPopup.Size.SMALL) {
            int[] rr = smallRect(popup());
            if (mx >= rr[0] && mx < rr[0] + rr[2] + 2 && my >= rr[1] && my < rr[1] + rr[3] + 2) {
                dragMode = mx >= rr[0] + rr[2] - 5 && my >= rr[1] + rr[3] - 5 ? 7 : 6;
                dragX = mx;
                dragY = my;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double ddx, double ddy) {
        if (dragMode == 5) {
            text.click(mx, my, true);
            return true;
        }
        if (dragMode == 2 || dragMode == 3) {
            TutorialPopup.Box b = boxes.get(dragBox);
            int c = clamp((int) ((mx - gx) / cell), 0, size.cols - 1), r = clamp((int) ((my - gy) / rowH), 0, size.rows - 1);
            TutorialPopup.Box next = dragMode == 2
                    ? new TutorialPopup.Box(clamp(c - dragCol, 0, size.cols - b.w()), clamp(r - dragRow, 0, size.rows - b.h()), b.w(), b.h(), b.kind(), b.text(), b.image(), b.fill())
                    : new TutorialPopup.Box(b.col(), b.row(), clamp(c - b.col() + 1, 1, size.cols - b.col()), clamp(r - b.row() + 1, 1, size.rows - b.row()), b.kind(), b.text(), b.image(), b.fill());
            boolean free = true;
            for (int i = 0; i < boxes.size(); i++) {
                if (i != dragBox && boxes.get(i).overlaps(next)) {
                    free = false;
                }
            }
            if (free) {
                boxes.set(dragBox, next);
            }
            return true;
        }
        if (dragMode == 6 || dragMode == 7) {
            float s = 1000f / pw, t = 1000f / ph;
            int dx = Math.round((float) (mx - dragX) * s), dy = Math.round((float) (my - dragY) * t);
            if (dx != 0 || dy != 0) {
                place = (dragMode == 6 ? new TutorialPopup.Placement(place.x() + dx, place.y() + dy, place.w(), place.h())
                        : new TutorialPopup.Placement(place.x(), place.y(), place.w() + dx, place.h() + dy)).sanitised();
                place = snap(place);
                dragX = mx;
                dragY = my;
            }
            return true;
        }
        return dragMode == 1 || super.mouseDragged(mx, my, button, ddx, ddy);
    }

    /** Snapped to the screen's edges and centre lines when within a few thousandths (D741). */
    private static TutorialPopup.Placement snap(TutorialPopup.Placement p) {
        int x = p.x(), y = p.y();
        if (Math.abs(x) < 12) {
            x = 0;
        } else if (Math.abs(x + p.w() - 1000) < 12) {
            x = 1000 - p.w();
        } else if (Math.abs(x + p.w() / 2 - 500) < 12) {
            x = 500 - p.w() / 2;
        }
        if (Math.abs(y) < 12) {
            y = 0;
        } else if (Math.abs(y + p.h() - 1000) < 12) {
            y = 1000 - p.h();
        } else if (Math.abs(y + p.h() / 2 - 500) < 12) {
            y = 500 - p.h() / 2;
        }
        return new TutorialPopup.Placement(x, y, p.w(), p.h());
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
        if (dragMode == 1) {
            int[] r = dragRect(mx, my);
            TutorialPopup.Box b = new TutorialPopup.Box(r[0], r[1], r[2], r[3], addKind, TutorialText.EMPTY, TutorialPopup.Image.NONE, 0);
            if (boxes.stream().noneMatch(b::overlaps)) {
                commitText();
                boxes.add(b);
                picked = boxes.size() - 1;
                widgets();
            }
        }
        dragMode = 0;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        fileScroll -= (int) Math.signum(delta);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (text.focused && key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && text.keyPressed(key, mods)) {
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (text.charTyped(c)) {
            return true;
        }
        return super.charTyped(c, mods);
    }

    /** SHOW AS PLAYER: the popup on the whole screen as a player sees it; any key or click goes back. */
    static final class Preview extends Screen {
        private final Screen back;
        private final String name;
        private final TutorialPopup popup;
        private final java.util.function.Function<Integer, ClientPanelImages.Texture> pictures;

        Preview(Screen back, String name, TutorialPopup popup, java.util.function.Function<Integer, ClientPanelImages.Texture> pictures) {
            super(Component.literal(name));
            this.back = back;
            this.name = name;
            this.popup = popup;
            this.pictures = pictures;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float partial) {
            TutorialPopupScreen.drawPreview(g, font, name, popup, pictures, width, height);
            String s = Tr.t("tutorial.e.preview_back");
            // at the top when a small popup sits at the foot, so the line does not cover its keys (audit of SU-146)
            int hy = height - 10;
            if (popup.size() == TutorialPopup.Size.SMALL) {
                int[] r = TutorialPopupScreen.smallRect(popup, width, height);
                if (r[1] + r[3] > height - 14) {
                    hy = 4;
                }
            }
            Ui.text(g, font, Ui.fit(font, s, width - 8, 0.7f), (width - Math.min(width - 8, Ui.width(font, s, 0.7f))) / 2, hy, Ui.MUTED, 0.7f);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            minecraft.setScreen(back);
            return true;
        }

        @Override
        public void onClose() {
            minecraft.setScreen(back);
        }
    }
}
