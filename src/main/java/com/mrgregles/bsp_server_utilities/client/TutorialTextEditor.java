package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.tutorial.TutorialText;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The text box editor of the popup editor (SU-143; owner decision D740): letters keep their own style (bold, italic,
 * underline, strikethrough, obfuscated) and colour, so a style can change in the middle of a line; size and alignment
 * belong to a paragraph (a line ended by Enter). Select with the mouse or Shift and the arrows, then press a style, a
 * colour, a size or an alignment: only the selection changes (with nothing selected, the letters typed next take the
 * style). Ctrl (Cmd) with B, I, U, A, C, X, V; Home and End; up and down keep the column.
 */
final class TutorialTextEditor {
    private final StringBuilder chars = new StringBuilder();
    private final List<Integer> styles = new ArrayList<>(), colours = new ArrayList<>();
    private final List<TutorialText.Size> sizes = new ArrayList<>();
    private final List<TutorialText.Align> aligns = new ArrayList<>();
    int cursor, anchor;
    private int typeStyle, typeColour = TutorialText.DEFAULT_COLOUR;
    boolean focused;
    private int x, y, w, h, scroll;
    private final List<Line> lines = new ArrayList<>();
    private Runnable onChange = () -> {
    };

    /** A drawn line: the paragraph, the letters [start, end), its top, its scale and its left. */
    private record Line(int para, int start, int end, int top, float scale, int left) {
    }

    void load(TutorialText text, Runnable changed) {
        chars.setLength(0);
        styles.clear();
        colours.clear();
        sizes.clear();
        aligns.clear();
        List<TutorialText.Para> ps = text.paras().isEmpty() ? List.of(new TutorialText.Para(TutorialText.Size.M, TutorialText.Align.LEFT, List.of())) : text.paras();
        for (int i = 0; i < ps.size(); i++) {
            TutorialText.Para p = ps.get(i);
            if (i > 0) {
                chars.append('\n');
                styles.add(0);
                colours.add(TutorialText.DEFAULT_COLOUR);
            }
            sizes.add(p.size());
            aligns.add(p.align());
            for (TutorialText.Run r : p.runs()) {
                for (int k = 0; k < r.text().length(); k++) {
                    chars.append(r.text().charAt(k));
                    styles.add(r.style());
                    colours.add(r.colour());
                }
            }
        }
        cursor = anchor = 0;
        onChange = changed;
    }

    TutorialText text() {
        List<TutorialText.Para> out = new ArrayList<>();
        int para = 0;
        List<TutorialText.Run> runs = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int rs = -1, rc = -1;
        for (int i = 0; i <= chars.length(); i++) {
            boolean end = i == chars.length() || chars.charAt(i) == '\n';
            if (end || styles.get(i) != rs || colours.get(i) != rc) {
                if (run.length() > 0) {
                    runs.add(new TutorialText.Run(run.toString(), rs, rc));
                }
                run.setLength(0);
                if (!end) {
                    rs = styles.get(i);
                    rc = colours.get(i);
                }
            }
            if (end) {
                out.add(new TutorialText.Para(sizes.get(para), aligns.get(para), runs));
                runs = new ArrayList<>();
                rs = rc = -1;
                para++;
            } else {
                run.append(chars.charAt(i));
            }
        }
        return new TutorialText(out).sanitised();
    }

    int length() {
        return chars.length();
    }

    private int selStart() {
        return Math.min(cursor, anchor);
    }

    private int selEnd() {
        return Math.max(cursor, anchor);
    }

    private int paraOf(int index) {
        int p = 0;
        for (int i = 0; i < index && i < chars.length(); i++) {
            if (chars.charAt(i) == '\n') {
                p++;
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ editing

    void insert(String s) {
        deleteSelection();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '§' || Character.isISOControl(c) && c != '\n') {
                continue;
            }
            if (c != '\n' && chars.length() - countNewlines() >= TutorialText.MAX_CHARS) {
                break;
            }
            if (c == '\n') {
                if (sizes.size() >= TutorialText.MAX_PARAS) {
                    continue;
                }
                int p = paraOf(cursor);
                sizes.add(p + 1, sizes.get(p));
                aligns.add(p + 1, aligns.get(p));
            }
            chars.insert(cursor, c);
            styles.add(cursor, typeStyle);
            colours.add(cursor, typeColour);
            cursor++;
        }
        anchor = cursor;
        onChange.run();
    }

    private int countNewlines() {
        int n = 0;
        for (int i = 0; i < chars.length(); i++) {
            if (chars.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private boolean deleteSelection() {
        int a = selStart(), b = selEnd();
        if (a == b) {
            return false;
        }
        for (int i = b - 1; i >= a; i--) {
            remove(i);
        }
        cursor = anchor = a;
        return true;
    }

    private void remove(int i) {
        if (chars.charAt(i) == '\n') {
            int p = paraOf(i);
            sizes.remove(p + 1);
            aligns.remove(p + 1);
        }
        chars.deleteCharAt(i);
        styles.remove(i);
        colours.remove(i);
    }

    /** A style bit for the selection: set on all of it unless all of it has it already; with no selection, for typing. */
    void toggle(int bit) {
        int a = selStart(), b = selEnd();
        if (a == b) {
            typeStyle ^= bit;
            return;
        }
        boolean all = true;
        for (int i = a; i < b; i++) {
            if (chars.charAt(i) != '\n' && (styles.get(i) & bit) == 0) {
                all = false;
            }
        }
        for (int i = a; i < b; i++) {
            styles.set(i, all ? styles.get(i) & ~bit : styles.get(i) | bit);
        }
        onChange.run();
    }

    boolean has(int bit) {
        int a = selStart(), b = selEnd();
        if (a == b) {
            return (typeStyle & bit) != 0;
        }
        for (int i = a; i < b; i++) {
            if (chars.charAt(i) != '\n' && (styles.get(i) & bit) == 0) {
                return false;
            }
        }
        return true;
    }

    void colour(int rgb) {
        int a = selStart(), b = selEnd();
        typeColour = rgb & 0xFFFFFF;
        for (int i = a; i < b; i++) {
            colours.set(i, typeColour);
        }
        if (a != b) {
            onChange.run();
        }
    }

    int colourNow() {
        return selStart() != selEnd() ? colours.get(selStart()) : typeColour;
    }

    void size(TutorialText.Size s) {
        for (int p = paraOf(selStart()); p <= paraOf(selEnd()); p++) {
            sizes.set(p, s);
        }
        onChange.run();
    }

    void align(TutorialText.Align a) {
        for (int p = paraOf(selStart()); p <= paraOf(selEnd()); p++) {
            aligns.set(p, a);
        }
        onChange.run();
    }

    TutorialText.Size sizeNow() {
        return sizes.get(paraOf(cursor));
    }

    TutorialText.Align alignNow() {
        return aligns.get(paraOf(cursor));
    }

    private void moveTo(int index, boolean select) {
        cursor = Math.max(0, Math.min(chars.length(), index));
        if (!select) {
            anchor = cursor;
        }
        if (cursor > 0 && cursor <= chars.length() && selStart() == selEnd()) {
            typeStyle = styles.get(cursor - 1);
            typeColour = colours.get(cursor - 1);
        }
    }

    boolean keyPressed(int key, int mods) {
        if (!focused) {
            return false;
        }
        layout(Minecraft.getInstance().font); // the lines as the text is now (audit of SU-146)
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0, ctrl = Screen.hasControlDown();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> moveTo(!shift && selStart() != selEnd() ? selStart() : cursor - 1, shift);
            case GLFW.GLFW_KEY_RIGHT -> moveTo(!shift && selStart() != selEnd() ? selEnd() : cursor + 1, shift);
            case GLFW.GLFW_KEY_UP -> moveTo(vertical(-1), shift);
            case GLFW.GLFW_KEY_DOWN -> moveTo(vertical(1), shift);
            case GLFW.GLFW_KEY_HOME -> moveTo(lineOf(cursor).start(), shift);
            case GLFW.GLFW_KEY_END -> moveTo(lineOf(cursor).end(), shift);
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!deleteSelection() && cursor > 0) {
                    remove(--cursor);
                    anchor = cursor;
                }
                onChange.run();
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (!deleteSelection() && cursor < chars.length()) {
                    remove(cursor);
                }
                onChange.run();
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> insert("\n");
            default -> {
                if (!ctrl) {
                    return false;
                }
                switch (key) {
                    case GLFW.GLFW_KEY_A -> {
                        anchor = 0;
                        cursor = chars.length();
                    }
                    case GLFW.GLFW_KEY_B -> toggle(TutorialText.BOLD);
                    case GLFW.GLFW_KEY_I -> toggle(TutorialText.ITALIC);
                    case GLFW.GLFW_KEY_U -> toggle(TutorialText.UNDERLINE);
                    case GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_X -> {
                        Minecraft.getInstance().keyboardHandler.setClipboard(chars.substring(selStart(), selEnd()));
                        if (key == GLFW.GLFW_KEY_X) {
                            deleteSelection();
                            onChange.run();
                        }
                    }
                    case GLFW.GLFW_KEY_V -> insert(Minecraft.getInstance().keyboardHandler.getClipboard());
                    default -> {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    boolean charTyped(char c) {
        if (!focused || c < ' ' || c == '§') {
            return false;
        }
        insert(String.valueOf(c));
        return true;
    }

    // ------------------------------------------------------------------ layout and drawing

    private float charWidth(Font font, int i, float scale) {
        return font.width(Component.literal(String.valueOf(chars.charAt(i))).withStyle(style(i))) * scale;
    }

    private Style style(int i) {
        int s = styles.get(i);
        return Style.EMPTY.withColor(TextColor.fromRgb(colours.get(i))).withBold((s & TutorialText.BOLD) != 0).withItalic((s & TutorialText.ITALIC) != 0)
                .withUnderlined((s & TutorialText.UNDERLINE) != 0).withStrikethrough((s & TutorialText.STRIKE) != 0).withObfuscated((s & TutorialText.OBFUSCATED) != 0);
    }

    /** Lines wrapped at word ends where it can, as {@link TutorialDraw} draws them. */
    private void layout(Font font) {
        lines.clear();
        int top = 0, start = 0, para = 0;
        for (int i = 0; i <= chars.length(); i++) {
            if (i == chars.length() || chars.charAt(i) == '\n') {
                float scale = TutorialDraw.SIZES[sizes.get(para).ordinal()];
                int lineH = Math.round(10 * scale);
                int s = start;
                do {
                    float lw = 0;
                    int e = s, lastSpace = -1;
                    while (e < i) {
                        float cw = charWidth(font, e, scale);
                        if (lw + cw > w - 4 && e > s) {
                            break;
                        }
                        if (chars.charAt(e) == ' ') {
                            lastSpace = e;
                        }
                        lw += cw;
                        e++;
                    }
                    if (e < i && lastSpace > s) {
                        e = lastSpace + 1;
                    }
                    float width = 0;
                    for (int k = s; k < e; k++) {
                        width += charWidth(font, k, scale);
                    }
                    int left = switch (aligns.get(para)) {
                        case LEFT -> 0;
                        case CENTRE -> Math.round((w - 4 - width) / 2);
                        case RIGHT -> Math.round(w - 4 - width);
                    };
                    lines.add(new Line(para, s, e, top, scale, left));
                    top += lineH;
                    s = e;
                } while (s < i);
                start = i + 1;
                para++;
            }
        }
    }

    private Line lineOf(int index) {
        Line best = lines.isEmpty() ? new Line(0, 0, 0, 0, 1, 0) : lines.get(0);
        for (Line l : lines) {
            if (index >= l.start() && index <= l.end()) {
                best = l;
                if (index < l.end()) {
                    break;
                }
            }
        }
        return best;
    }

    private float xOf(Font font, int index) {
        Line l = lineOf(index);
        float lx = l.left();
        for (int k = l.start(); k < index && k < l.end(); k++) {
            lx += charWidth(font, k, l.scale());
        }
        return lx;
    }

    private int vertical(int dir) {
        Font font = Minecraft.getInstance().font;
        int at = lines.indexOf(lineOf(cursor));
        int to = at + dir;
        if (to < 0) {
            return 0;
        }
        if (to >= lines.size()) {
            return chars.length();
        }
        return indexAt(font, lines.get(to), xOf(font, cursor));
    }

    private int indexAt(Font font, Line l, float px) {
        float lx = l.left();
        for (int k = l.start(); k < l.end(); k++) {
            float cw = charWidth(font, k, l.scale());
            if (px < lx + cw / 2) {
                return k;
            }
            lx += cw;
        }
        return l.end();
    }

    void place(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    void click(double mx, double my, boolean select) {
        Font font = Minecraft.getInstance().font;
        layout(font);
        Line hit = null;
        for (Line l : lines) {
            if (my - y - 2 + scroll >= l.top()) {
                hit = l;
            }
        }
        if (hit != null) {
            moveTo(indexAt(font, hit, (float) (mx - x - 2)), select);
        }
    }

    void render(GuiGraphics g, Font font) {
        layout(font);
        g.fill(x, y, x + w, y + h, Ui.SLOT);
        Ui.frame(g, x, y, w, h, focused ? Ui.VIOLET : Ui.DIM);
        // keep the cursor's line in view
        Line cl = lineOf(cursor);
        int lineH = Math.round(10 * cl.scale());
        if (cl.top() - scroll + lineH > h - 4) {
            scroll = cl.top() + lineH - (h - 4);
        } else if (cl.top() < scroll) {
            scroll = cl.top();
        }
        Ui.scissor(g, x + 1, y + 1, x + w - 1, y + h - 1);
        int a = selStart(), b = selEnd();
        for (Line l : lines) {
            int ly = y + 2 + l.top() - scroll;
            if (ly > y + h || ly + 20 < y) {
                continue;
            }
            float lx = x + 2 + l.left();
            for (int k = l.start(); k < l.end(); k++) {
                float cw = charWidth(font, k, l.scale());
                if (k >= a && k < b) {
                    g.fill(Math.round(lx), ly, Math.round(lx + cw), ly + Math.round(9 * l.scale()), 0x59B58CFF);
                }
                g.pose().pushPose();
                g.pose().translate(lx, ly, 0);
                g.pose().scale(l.scale(), l.scale(), 1);
                g.drawString(font, Component.literal(String.valueOf(chars.charAt(k))).withStyle(style(k)), 0, 0, 0xFFFFFFFF, false);
                g.pose().popPose();
                lx += cw;
            }
        }
        if (focused && (Util.getMillis() / 500) % 2 == 0) {
            int cx = Math.round(x + 2 + xOf(font, cursor));
            int cy = y + 2 + cl.top() - scroll;
            g.fill(cx, cy - 1, cx + 1, cy + Math.round(9 * cl.scale()), Ui.VIOLET);
        }
        g.disableScissor();
        if (chars.length() == 0 && !focused) {
            // wrapped to the box and cut at its foot (audit of SU-146)
            int hy = y + 4;
            for (String line : Ui.wrap(font, Tr.t("tutorial.e.text_hint"), w - 8, 0.7f)) {
                if (hy + 7 > y + h - 2) {
                    break;
                }
                Ui.text(g, font, line, x + 4, hy, 0xFF6B7180, 0.7f);
                hy += 8;
            }
        }
        String count = (chars.length() - countNewlines()) + " / " + TutorialText.MAX_CHARS;
        Ui.text(g, font, count, x + w - Ui.width(font, count, 0.55f) - 2, y + h + 2, 0xFF6B7180, 0.55f);
    }
}
