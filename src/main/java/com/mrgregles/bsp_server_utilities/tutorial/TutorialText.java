package com.mrgregles.bsp_server_utilities.tutorial;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * A text box's words (owner decision D740): paragraphs, each with a size and an alignment, made of runs of letters that
 * share a style (bold, italic, underline, strikethrough, obfuscated) and a colour, so the style can change in the
 * middle of a line. A paragraph never holds a line break; a new paragraph is a new line.
 */
public record TutorialText(List<Para> paras) {
    public static final int MAX_PARAS = 16, MAX_CHARS = 1000;
    public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8, OBFUSCATED = 16, ALL_STYLES = 31;
    public static final int DEFAULT_COLOUR = 0xE6EAF2;
    public static final TutorialText EMPTY = new TutorialText(List.of());

    public enum Size { S, M, L, XL }

    public enum Align { LEFT, CENTRE, RIGHT }

    /** Letters sharing one style (the bits above) and one colour (0xRRGGBB). */
    public record Run(String text, int style, int colour) {
        public boolean has(int bit) {
            return (style & bit) != 0;
        }
    }

    /** One line of the box, wrapped to its width when drawn. */
    public record Para(Size size, Align align, List<Run> runs) {
        public Para {
            runs = List.copyOf(runs);
        }

        public String plain() {
            StringBuilder b = new StringBuilder();
            runs.forEach(r -> b.append(r.text()));
            return b.toString();
        }
    }

    public TutorialText {
        paras = List.copyOf(paras);
    }

    /** Plain text, one paragraph a line, as the default (M, LEFT, no style, the default colour). */
    public static TutorialText of(String text) {
        List<Para> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            out.add(new Para(Size.M, Align.LEFT, List.of(new Run(line, 0, DEFAULT_COLOUR))));
        }
        return new TutorialText(out).sanitised();
    }

    public String plain() {
        List<String> lines = new ArrayList<>();
        paras.forEach(p -> lines.add(p.plain()));
        return String.join("\n", lines);
    }

    public int length() {
        int n = 0;
        for (Para p : paras) {
            n += p.plain().length();
        }
        return n;
    }

    public boolean isEmpty() {
        return length() == 0;
    }

    /**
     * Within the limits: control characters and section signs removed (a line break or tab inside a run becomes a
     * space), styles and colours masked to what they can be, empty runs dropped and touching runs of the same look
     * joined, at most {@value #MAX_PARAS} paragraphs and {@value #MAX_CHARS} letters (the rest cut), and no empty
     * paragraphs left at the end.
     */
    public TutorialText sanitised() {
        List<Para> out = new ArrayList<>();
        int left = MAX_CHARS;
        for (Para p : paras) {
            if (out.size() >= MAX_PARAS) {
                break;
            }
            List<Run> runs = new ArrayList<>();
            for (Run r : p.runs()) {
                if (left <= 0) {
                    break;
                }
                String t = clean(r.text());
                if (t.length() > left) {
                    t = t.substring(0, left);
                }
                if (t.isEmpty()) {
                    continue;
                }
                left -= t.length();
                Run next = new Run(t, r.style() & ALL_STYLES, r.colour() & 0xFFFFFF);
                if (!runs.isEmpty()) {
                    Run last = runs.get(runs.size() - 1);
                    if (last.style() == next.style() && last.colour() == next.colour()) {
                        runs.set(runs.size() - 1, new Run(last.text() + next.text(), last.style(), last.colour()));
                        continue;
                    }
                }
                runs.add(next);
            }
            out.add(new Para(p.size() == null ? Size.M : p.size(), p.align() == null ? Align.LEFT : p.align(), runs));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).runs().isEmpty()) {
            out.remove(out.size() - 1);
        }
        return new TutorialText(out);
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') {
                b.append(' ');
            } else if (!Character.isISOControl(c) && c != '§') {
                b.append(c);
            }
        }
        return b.toString();
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Para p : paras) {
            CompoundTag pt = new CompoundTag();
            pt.putString("size", p.size().name());
            pt.putString("align", p.align().name());
            ListTag runs = new ListTag();
            for (Run r : p.runs()) {
                CompoundTag rt = new CompoundTag();
                rt.putString("t", r.text());
                rt.putInt("s", r.style());
                rt.putInt("c", r.colour());
                runs.add(rt);
            }
            pt.put("runs", runs);
            list.add(pt);
        }
        return list;
    }

    public static TutorialText load(ListTag list) {
        List<Para> paras = new ArrayList<>();
        for (Tag t : list) {
            if (!(t instanceof CompoundTag pt)) {
                continue;
            }
            List<Run> runs = new ArrayList<>();
            for (Tag r : pt.getList("runs", Tag.TAG_COMPOUND)) {
                CompoundTag rt = (CompoundTag) r;
                runs.add(new Run(rt.getString("t"), rt.getInt("s"), rt.getInt("c")));
            }
            paras.add(new Para(Tutorial.named(Size.class, pt.getString("size"), Size.M), Tutorial.named(Align.class, pt.getString("align"), Align.LEFT), runs));
        }
        return new TutorialText(paras).sanitised();
    }
}
