package com.mrgregles.bsp_server_utilities.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * Where a panel's text lines go (owner decisions D44, D45), the same for the face in the world and the preview on the
 * settings screen. The face is measured in canvas pixels, {@value #PX} per block. A line's size is a share of the
 * panel's height (S a twelfth, M a ninth, L a sixth, XL a quarter), so a line keeps its proportion on a bigger panel;
 * the lines are centred top to bottom with a gap between them, all scaled down together when they do not fit, and each
 * line is shrunk on its own when it is wider than the face. Inside the bezel ({@value #INSET} pixels) there is a small
 * margin. Pure: the text width comes from the caller (the client's font).
 */
public final class PanelLayout {
    public static final int PX = 64, INSET = 4, MARGIN = 3;
    /** The font's glyph height in pixels at scale 1. */
    public static final float GLYPH = 8f;
    private static final float GAP = 0.3f;

    /**
     * One line placed: the text with formatting codes, the anchor x (left edge, centre or right edge by {@code align}),
     * the top y of the glyphs, the scale, the colour (0xRRGGBB), the slot it sits in (top and height, for picking it),
     * and the drawn width.
     */
    public record Placed(int index, String text, float x, float y, float scale, int colour, PanelSettings.Align align,
                         float slotTop, float slotHeight, float width) {
        /** The left edge of the drawn text. */
        public float left() {
            return switch (align) {
                case LEFT -> x;
                case CENTRE -> x - width / 2f;
                case RIGHT -> x - width;
            };
        }
    }

    private PanelLayout() {
    }

    /** A line's height as a share of the panel's. */
    public static float share(PanelSettings.Size size) {
        return switch (size) {
            case S -> 1f / 12f;
            case M -> 1f / 9f;
            case L -> 1f / 6f;
            case XL -> 1f / 4f;
        };
    }

    /**
     * The text as the font draws it: {@code &} followed by a colour or style code (0 to 9, a to f, k to o, r) becomes a
     * formatting code (D45), and a bold line starts bold (a {@code &r} inside it ends the bold too).
     */
    public static String codes(String raw, boolean bold) {
        StringBuilder b = new StringBuilder(bold ? "§l" : "");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '&' && i + 1 < raw.length() && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(raw.charAt(i + 1))) >= 0) {
                b.append('§').append(Character.toLowerCase(raw.charAt(i + 1)));
                i++;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** The text without its {@code &} codes, for counting what a player reads. */
    public static String plain(String raw) {
        return codes(raw, false).replaceAll("§.", "").toLowerCase(Locale.ROOT);
    }

    /** Places the lines of a panel {@code wBlocks} by {@code hBlocks}; {@code width} measures a text at scale 1. */
    public static List<Placed> layout(List<PanelSettings.Line> lines, int wBlocks, int hBlocks, ToIntFunction<String> width) {
        List<Placed> out = new ArrayList<>();
        if (lines.isEmpty()) {
            return out;
        }
        float w = wBlocks * PX, h = hBlocks * PX;
        float x0 = INSET + MARGIN, x1 = w - INSET - MARGIN, y0 = INSET + MARGIN, areaW = x1 - x0, areaH = h - 2 * (INSET + MARGIN);
        float[] px = new float[lines.size()];
        float total = 0;
        for (int i = 0; i < lines.size(); i++) {
            px[i] = h * share(lines.get(i).size());
            total += px[i] + (i > 0 ? px[i] * GAP : 0);
        }
        float k = total > areaH ? areaH / total : 1f;
        float y = y0 + (areaH - total * k) / 2f;
        for (int i = 0; i < lines.size(); i++) {
            PanelSettings.Line line = lines.get(i);
            float slot = px[i] * k;
            if (i > 0) {
                y += slot * GAP;
            }
            String text = codes(line.text(), line.bold());
            int tw = Math.max(0, width.applyAsInt(text));
            float scale = slot / GLYPH;
            if (tw * scale > areaW && tw > 0) {
                scale = areaW / tw;
            }
            float glyphH = GLYPH * scale;
            float x = switch (line.align()) {
                case LEFT -> x0;
                case CENTRE -> (x0 + x1) / 2f;
                case RIGHT -> x1;
            };
            out.add(new Placed(i, text, x, y + (slot - glyphH) / 2f, scale, line.colour(), line.align(), y, slot, tw * scale));
            y += slot;
        }
        return out;
    }

    /**
     * Where a picture of {@code imgW} by {@code imgH} goes in an area of {@code areaW} by {@code areaH} (owner decision
     * D49): {@code x, y, w, h} of the drawn rectangle inside the area and {@code u0, v0, u1, v1} of the picture shown
     * (0 to 1). INSIDE shows all of it, centred, leaving bands of background; FILL covers the area and crops the middle;
     * STRETCH fills the area with all of it.
     */
    public record Fitted(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
    }

    public static Fitted fit(PanelSettings.Fit fit, int imgW, int imgH, float areaW, float areaH) {
        if (imgW <= 0 || imgH <= 0 || areaW <= 0 || areaH <= 0) {
            return new Fitted(0, 0, 0, 0, 0, 0, 1, 1);
        }
        return switch (fit) {
            case STRETCH -> new Fitted(0, 0, areaW, areaH, 0, 0, 1, 1);
            case INSIDE -> {
                float s = Math.min(areaW / imgW, areaH / imgH), w = imgW * s, h = imgH * s;
                yield new Fitted((areaW - w) / 2f, (areaH - h) / 2f, w, h, 0, 0, 1, 1);
            }
            case FILL -> {
                float s = Math.max(areaW / imgW, areaH / imgH);
                float uSpan = areaW / (imgW * s), vSpan = areaH / (imgH * s);
                yield new Fitted(0, 0, areaW, areaH, (1 - uSpan) / 2f, (1 - vSpan) / 2f, (1 + uSpan) / 2f, (1 + vSpan) / 2f);
            }
        };
    }
}
