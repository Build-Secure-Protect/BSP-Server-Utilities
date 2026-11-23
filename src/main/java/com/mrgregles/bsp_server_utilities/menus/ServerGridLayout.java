package com.mrgregles.bsp_server_utilities.menus;

import static com.mrgregles.bsp_server_utilities.menus.WorldGridLayout.GAP;
import static com.mrgregles.bsp_server_utilities.menus.WorldGridLayout.MARGIN;
import static com.mrgregles.bsp_server_utilities.menus.WorldGridLayout.PAD;

/**
 * Where the cards of the Multiplayer grid stand: plain arithmetic on GUI pixels, so it is checked without a client. The
 * featured server (BSP) takes a full-width card on top when the list has it; every other server is a square card below,
 * in the same columns as the Select World grid; vanilla's "Scanning for games on your local network" line comes last.
 * Card 0 is the featured card when there is one; the squares follow in the list's own order.
 */
public final class ServerGridLayout {
    /** The featured card's height, the text block under a square's icon, and the scanning line's block. */
    public static final int HERO_H = 60, SQUARE_TEXT_H = 32, LINE_H = 20;

    /**
     * One layout.
     *
     * @param left    x of the first column (and of the featured card)
     * @param rowW    the width of a full row of squares (the featured card's width)
     * @param hero    whether there is a featured card
     * @param squares the number of square cards
     * @param line    whether the scanning line is shown after the cards
     */
    public record Layout(int left, int top, int bottom, int rowW, boolean hero, int cols, int cardW, int squares, int rows,
                         boolean line, int maxScroll, int barX) {
        public int viewH() {
            return bottom - top;
        }

        public int cards() {
            return (hero ? 1 : 0) + squares;
        }

        /** The squares' first row, from the top of the content. */
        public int squaresTop() {
            return PAD + (hero ? HERO_H + GAP : 0);
        }

        private int rowsH() {
            return rows == 0 ? 0 : rows * cardW + (rows - 1) * GAP;
        }

        public int contentH() {
            int h = squaresTop() + rowsH();
            if (line) {
                h += (rows > 0 ? GAP : 0) + LINE_H;
            } else if (rows == 0 && hero) {
                h -= GAP; // nothing below the featured card
            }
            return cards() == 0 && !line ? 0 : h + PAD;
        }

        /** The square index of card {@code i}, or -1 for the featured card. */
        public int square(int i) {
            return hero ? i - 1 : i;
        }

        public boolean isHero(int i) {
            return hero && i == 0;
        }

        public int cardX(int i) {
            return isHero(i) ? left : left + (square(i) % cols) * (cardW + GAP);
        }

        public int cardW(int i) {
            return isHero(i) ? rowW : cardW;
        }

        public int cardH(int i) {
            return isHero(i) ? HERO_H : cardW;
        }

        /** The card's top in content coordinates (0 = the grid area's top at scroll 0). */
        private int contentY(int i) {
            return isHero(i) ? PAD : squaresTop() + (square(i) / cols) * (cardW + GAP);
        }

        public int cardY(int i, int scroll) {
            return top + contentY(i) - scroll;
        }

        /** The scanning line's top. */
        public int lineY(int scroll) {
            return top + squaresTop() + rowsH() + (rows > 0 ? GAP : 0) - scroll;
        }

        /** The card under a point, or -1 (gaps, the margins and the scanning line hold none). */
        public int cardAt(double x, double y, int scroll) {
            if (y < top || y >= bottom) {
                return -1;
            }
            for (int i = 0; i < cards(); i++) {
                int cx = cardX(i), cy = cardY(i, scroll);
                if (x >= cx && x < cx + cardW(i) && y >= cy && y < cy + cardH(i)) {
                    return i;
                }
            }
            return -1;
        }

        public int clamp(double scroll) {
            return (int) Math.max(0, Math.min(maxScroll, Math.round(scroll)));
        }

        /** The scroll position that shows card {@code i} whole, moving as little as it can. */
        public int scrollToShow(int scroll, int i) {
            if (i < 0 || i >= cards()) {
                return clamp(scroll);
            }
            int y = contentY(i);
            if (y - PAD < scroll) {
                return clamp(y - PAD);
            }
            if (y + cardH(i) + PAD > scroll + viewH()) {
                return clamp(y + cardH(i) + PAD - viewH());
            }
            return clamp(scroll);
        }

        public int[] thumb(int scroll) {
            return WorldGridLayout.thumb(top, viewH(), contentH(), maxScroll, clamp(scroll));
        }

        public int scrollForThumb(double thumbY, int thumbH) {
            return clamp(WorldGridLayout.scrollForThumb(top, viewH(), maxScroll, thumbY, thumbH));
        }

        /**
         * The card an arrow key moves to ({@code dx} / {@code dy} -1 or +1), or -1 when there is none that way (the
         * key then moves the focus out of the grid). The featured card sits above the first row: up from the first row
         * goes to it, down from it to the first square. With nothing chosen ({@code from} -1) any arrow starts at card 0.
         */
        public int move(int from, int dx, int dy) {
            int cards = cards();
            if (cards == 0) {
                return -1;
            }
            if (from < 0 || from >= cards) {
                return 0;
            }
            if (isHero(from)) {
                return dy > 0 && squares > 0 ? 1 : -1;
            }
            int off = hero ? 1 : 0, j = from - off;
            if (dx != 0) {
                int col = j % cols;
                if (col + dx < 0 || col + dx >= cols || j + dx < 0 || j + dx >= squares) {
                    return -1;
                }
                return from + dx;
            }
            if (dy < 0) {
                return j - cols >= 0 ? from - cols : (hero ? 0 : -1);
            }
            if (j + cols >= squares) {
                return j / cols < (squares - 1) / cols ? off + squares - 1 : -1;
            }
            return from + cols;
        }
    }

    private ServerGridLayout() {
    }

    /**
     * The layout on a screen {@code screenW} wide between {@code top} and {@code bottom}: a featured card or not,
     * {@code squares} square cards, and the scanning line or not.
     */
    public static Layout of(int screenW, int top, int bottom, boolean hero, int squares, boolean line) {
        int areaW = WorldGridLayout.areaWidth(screenW);
        int cols = WorldGridLayout.columns(areaW);
        int cardW = Math.max(1, (areaW - (cols - 1) * GAP) / cols);
        int rowW = cols * cardW + (cols - 1) * GAP;
        int left = MARGIN + (areaW - rowW) / 2;
        int sq = Math.max(0, squares);
        int rows = sq == 0 ? 0 : (sq + cols - 1) / cols;
        Layout probe = new Layout(left, top, bottom, rowW, hero, cols, cardW, sq, rows, line, 0, 0);
        int maxScroll = Math.max(0, probe.contentH() - (bottom - top));
        int barX = screenW - MARGIN - WorldGridLayout.BAR;
        return new Layout(left, top, bottom, rowW, hero, cols, cardW, sq, rows, line, maxScroll, barX);
    }

    /**
     * Whether a server address is the featured server's: the host (without a port, any case) is {@code featured} or a
     * name under it ({@code play.buildsecureprotect.com} for {@code buildsecureprotect.com}). An empty setting features
     * nothing.
     */
    public static boolean isFeatured(String address, String featured) {
        if (address == null || featured == null) {
            return false;
        }
        String want = featured.trim().toLowerCase(java.util.Locale.ROOT);
        while (want.endsWith(".")) {
            want = want.substring(0, want.length() - 1);
        }
        if (want.isEmpty()) {
            return false;
        }
        String host = address.trim().toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end > 0 ? host.substring(1, end) : host;
        } else {
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && host.indexOf(':') == colon) {
                host = host.substring(0, colon);
            }
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.equals(want) || host.endsWith("." + want);
    }
}
