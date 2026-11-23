package com.mrgregles.bsp_server_utilities.menus;

/**
 * Where the cards of the Select World grid stand (artboard W1): plain arithmetic on GUI pixels, so it is checked without a
 * client. Card 0 is "+ Create new world"; card {@code i + 1} is the list's world {@code i}, in the list's own order.
 */
public final class WorldGridLayout {
    /** Space left and right of the grid, the scrollbar's width and the gap between the cards and the scrollbar. */
    public static final int MARGIN = 12, BAR = 4, BAR_GAP = 4;
    /** The gap between cards, the narrowest card, and the fewest and most columns. */
    public static final int GAP = 6, MIN_CARD = 96, MIN_COLS = 2, MAX_COLS = 6;
    /** The text block under a card's picture, and the space above the first row and below the last. */
    public static final int TEXT_H = 22, PAD = 4;
    /** The shortest scrollbar thumb. */
    public static final int MIN_THUMB = 16;

    /**
     * One layout.
     *
     * @param left     x of the first column
     * @param top      y of the grid area (the vanilla list's top)
     * @param bottom   y of the grid area's bottom edge
     * @param cols     columns
     * @param cardW    a card's width
     * @param imageH   its picture's height (16:9)
     * @param cardH    the whole card's height
     * @param rows     rows of cards
     * @param maxScroll how far the grid scrolls
     * @param barX     x of the scrollbar's track
     */
    public record Layout(int left, int top, int bottom, int cols, int cardW, int imageH, int cardH, int cards, int rows,
                         int maxScroll, int barX) {
        public int viewH() {
            return bottom - top;
        }

        public int rowH() {
            return cardH + GAP;
        }

        public int contentH() {
            return rows == 0 ? 0 : PAD * 2 + rows * cardH + (rows - 1) * GAP;
        }

        public int cardX(int i) {
            return left + (i % cols) * (cardW + GAP);
        }

        /** The card's top at this scroll position. */
        public int cardY(int i, int scroll) {
            return top + PAD + (i / cols) * rowH() - scroll;
        }

        /** The card under a point, or -1 (gaps and the margins hold none). */
        public int cardAt(double x, double y, int scroll) {
            if (y < top || y >= bottom || x < left) {
                return -1;
            }
            int col = (int) ((x - left) / (cardW + GAP));
            int row = (int) ((y - top - PAD + scroll) / rowH());
            if (col >= cols || y - top - PAD + scroll < 0) {
                return -1;
            }
            int i = row * cols + col;
            if (i >= cards || x >= cardX(i) + cardW || y >= cardY(i, scroll) + cardH) {
                return -1;
            }
            return i;
        }

        public int clamp(double scroll) {
            return (int) Math.max(0, Math.min(maxScroll, Math.round(scroll)));
        }

        /** The scroll position that shows card {@code i} whole, moving as little as it can. */
        public int scrollToShow(int scroll, int i) {
            if (i < 0 || i >= cards) {
                return clamp(scroll);
            }
            int y = PAD + (i / cols) * rowH();
            if (y - PAD < scroll) {
                return clamp(y - PAD);
            }
            if (y + cardH + PAD > scroll + viewH()) {
                return clamp(y + cardH + PAD - viewH());
            }
            return clamp(scroll);
        }

        /** The scrollbar thumb's {y, height}, or null when everything fits. */
        public int[] thumb(int scroll) {
            return WorldGridLayout.thumb(top, viewH(), contentH(), maxScroll, clamp(scroll));
        }

        /** The scroll position for the thumb's top at {@code thumbY}. */
        public int scrollForThumb(double thumbY, int thumbH) {
            return clamp(WorldGridLayout.scrollForThumb(top, viewH(), maxScroll, thumbY, thumbH));
        }

        /**
         * The card an arrow key moves to: {@code dx} -1 / +1 for left and right, {@code dy} -1 / +1 for up and down;
         * -1 when there is no card that way (the key then moves the focus out of the grid, as vanilla's arrows do).
         * Down from a row with nothing straight below goes to the last card. With nothing chosen ({@code from} -1) any
         * arrow starts at the first world, or at the create card when there is no world.
         */
        public int move(int from, int dx, int dy) {
            if (cards == 0) {
                return -1;
            }
            if (from < 0 || from >= cards) {
                return cards > 1 ? 1 : 0;
            }
            if (dx != 0) {
                int col = from % cols, to = from + dx;
                if (col + dx < 0 || col + dx >= cols || to < 0 || to >= cards) {
                    return -1;
                }
                return to;
            }
            int to = from + dy * cols;
            if (to < 0) {
                return -1;
            }
            if (to >= cards) {
                return from / cols < (cards - 1) / cols ? cards - 1 : -1;
            }
            return to;
        }
    }

    private WorldGridLayout() {
    }

    /** A scrollbar thumb's {y, height} in a track from {@code top}, {@code view} tall, or null when nothing scrolls. */
    public static int[] thumb(int top, int view, int contentH, int maxScroll, int scroll) {
        if (maxScroll <= 0) {
            return null;
        }
        int h = Math.max(MIN_THUMB, Math.min(view, view * view / Math.max(1, contentH)));
        int y = top + (int) ((long) (view - h) * Math.max(0, Math.min(maxScroll, scroll)) / maxScroll);
        return new int[]{y, h};
    }

    /** The scroll position (unclamped) for a thumb {@code thumbH} tall whose top is at {@code thumbY}. */
    public static double scrollForThumb(int top, int view, int maxScroll, double thumbY, int thumbH) {
        int room = view - thumbH;
        if (room <= 0) {
            return 0;
        }
        return (thumbY - top) * maxScroll / room;
    }

    /** The number of columns for a grid area {@code areaW} wide: as many cards of at least 96 as fit, 2 to 6. */
    public static int columns(int areaW) {
        return Math.max(MIN_COLS, Math.min(MAX_COLS, (areaW + GAP) / (MIN_CARD + GAP)));
    }

    /** The cards' area width on a screen {@code screenW} wide: the margins and the scrollbar left out. */
    public static int areaWidth(int screenW) {
        return Math.max(0, screenW - 2 * MARGIN - BAR - BAR_GAP);
    }

    /**
     * The layout of {@code cards} cards (the create card included) between {@code top} and {@code bottom} on a screen
     * {@code screenW} wide.
     */
    public static Layout of(int screenW, int top, int bottom, int cards) {
        int areaW = areaWidth(screenW);
        int cols = columns(areaW);
        int cardW = Math.max(1, (areaW - (cols - 1) * GAP) / cols);
        int imageH = Math.round(cardW * 9f / 16f);
        int cardH = imageH + TEXT_H;
        int rows = cards <= 0 ? 0 : (cards + cols - 1) / cols;
        int contentH = rows == 0 ? 0 : PAD * 2 + rows * cardH + (rows - 1) * GAP;
        int maxScroll = Math.max(0, contentH - (bottom - top));
        // the cards stretch to fill the row; what integer widths leave over is split between the two sides
        int spare = areaW - (cols * cardW + (cols - 1) * GAP);
        int left = MARGIN + spare / 2;
        int barX = screenW - MARGIN - BAR;
        return new Layout(left, top, bottom, cols, cardW, imageH, cardH, Math.max(0, cards), rows, maxScroll, barX);
    }

    /**
     * The part of a square picture a {@code w} x {@code h} box shows "cover" style (centred, top and bottom cut): {v,
     * vHeight} as fractions of the picture's height.
     */
    public static float[] coverRows(int w, int h) {
        if (w <= 0 || h <= 0 || h >= w) {
            return new float[]{0f, 1f};
        }
        float shown = (float) h / w;
        return new float[]{(1f - shown) / 2f, shown};
    }
}
