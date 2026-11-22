package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.menus.WorldGridLayout;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/**
 * Singleplayer's Select World grid (artboard W1), the arithmetic: columns from the width (2 to 6, cards of at least 96
 * GUI px with 6 between, stretched to the row), 16:9 pictures under a 22 px text block, the cards inside the 12 px
 * margins and clear of the scrollbar, the scroll clamped, the selected card scrolled into view, the thumb dragged, the
 * arrows across the grid, and the picture's "cover" crop. The drawing, the vanilla list it reads and every action need a
 * client (docs/TESTING.md).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WorldGridGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void world_grid_columns_follow_the_width(GameTestHelper h) {
        // GUI widths: 1920 x 1080 at scale 4 is 480, the owner's Mac at scale 5 about 576, then 640 and 960
        int[][] cases = {{480, 4}, {576, 5}, {640, 6}, {960, 6}, {300, 2}, {200, 2}};
        for (int[] c : cases) {
            WorldGridLayout.Layout l = WorldGridLayout.of(c[0], 48, 270 - 64, 8);
            h.assertTrue(l.cols() == c[1], "a " + c[0] + " px wide screen has " + c[1] + " columns: " + l.cols());
            h.assertTrue(c[0] < 300 || l.cardW() >= WorldGridLayout.MIN_CARD, "cards are at least 96 wide when the screen allows: " + l);
        }
        WorldGridLayout.Layout wide = WorldGridLayout.of(1920, 48, 1016, 8);
        h.assertTrue(wide.cols() == 6 && wide.cardW() > 96, "never more than 6 columns; the cards stretch instead: " + wide);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void world_grid_cards_fit_inside_the_margins(GameTestHelper h) {
        for (int w : new int[]{480, 576, 640, 960, 1280}) {
            WorldGridLayout.Layout l = WorldGridLayout.of(w, 48, 300, 30);
            int lastRight = l.cardX(l.cols() - 1) + l.cardW();
            h.assertTrue(l.cardX(0) >= WorldGridLayout.MARGIN, w + ": the first column starts inside the 12 px margin: " + l);
            h.assertTrue(lastRight <= l.barX() - WorldGridLayout.BAR_GAP, w + ": the last column ends clear of the scrollbar: " + lastRight + " / " + l.barX());
            h.assertTrue(l.barX() + WorldGridLayout.BAR == w - WorldGridLayout.MARGIN, w + ": the 4 px track sits inside the right margin");
            h.assertTrue(l.barX() - WorldGridLayout.BAR_GAP - lastRight < l.cols(), w + ": the cards fill the row (less than a pixel a column left over)");
            h.assertTrue(l.imageH() == Math.round(l.cardW() * 9f / 16f) && l.cardH() == l.imageH() + 22, w + ": 16:9 pictures over a 22 px text block: " + l);
            h.assertTrue(l.cardX(1) - (l.cardX(0) + l.cardW()) == WorldGridLayout.GAP, w + ": 6 px between cards");
        }
        // 480 x 270 (GUI scale 4 at 1920 x 1080): a whole row and more than half the next show between the title and the buttons
        WorldGridLayout.Layout small = WorldGridLayout.of(480, 48, 270 - 64, 8);
        h.assertTrue(small.viewH() >= WorldGridLayout.PAD + small.rowH() + small.cardH() / 2, "a row and a half show at 480 x 270: " + small);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void world_grid_scrolls_within_its_content(GameTestHelper h) {
        WorldGridLayout.Layout one = WorldGridLayout.of(480, 48, 206, 1);
        h.assertTrue(one.maxScroll() == 0 && one.thumb(0) == null && one.rows() == 1, "no worlds: only the create card, nothing to scroll: " + one);
        h.assertTrue(one.cardAt(one.cardX(0) + 5, one.cardY(0, 0) + 5, 0) == 0, "the create card is card 0");
        h.assertTrue(one.cardAt(one.cardX(1 % one.cols()) + 5, one.cardY(0, 0) + 5, 0) == -1, "no card where there is none");

        WorldGridLayout.Layout l = WorldGridLayout.of(480, 48, 206, 31); // 30 worlds and the create card
        h.assertTrue(l.rows() == 8 && l.maxScroll() == l.contentH() - l.viewH(), "30 worlds scroll to their last row: " + l);
        h.assertTrue(l.clamp(-50) == 0 && l.clamp(1e6) == l.maxScroll(), "the position is clamped");
        int last = l.scrollToShow(0, 30);
        h.assertTrue(l.cardY(30, last) + l.cardH() <= l.bottom() && l.cardY(30, last) >= l.top(), "the last card scrolled into view: " + last);
        h.assertTrue(l.scrollToShow(last, 30) == last, "a card in view does not move the grid");
        int back = l.scrollToShow(last, 1);
        h.assertTrue(l.cardY(1, back) >= l.top() && back == 0, "back to the first row: " + back);
        int[] t = l.thumb(0), end = l.thumb(l.maxScroll());
        h.assertTrue(t != null && t[0] == l.top() && t[1] >= WorldGridLayout.MIN_THUMB, "the thumb starts at the top: " + Arrays.toString(t));
        h.assertTrue(end[0] + end[1] == l.bottom(), "and ends at the bottom: " + Arrays.toString(end));
        h.assertTrue(l.scrollForThumb(end[0], end[1]) == l.maxScroll() && l.scrollForThumb(l.top() - 40, t[1]) == 0, "dragging the thumb maps back to the position");
        int gapX = l.cardX(0) + l.cardW() + 2;
        h.assertTrue(l.cardAt(gapX, l.cardY(0, 0) + 5, 0) == -1, "a click in the gap between cards is on none");
        // at the bottom, card 2 (first row) sits above the area: a click just above the area's top lands on nothing,
        // and one inside it lands on the row that is really there
        int bottomRowY = l.cardY(28, l.maxScroll());
        h.assertTrue(l.cardY(2, l.maxScroll()) + l.cardH() < l.top(), "the first row is scrolled out of the area at the bottom");
        h.assertTrue(l.cardAt(l.cardX(2) + 3, l.top() - 1, l.maxScroll()) == -1, "above the area is no card, even where a scrolled-out card would be");
        h.assertTrue(l.cardAt(l.cardX(0) + 3, bottomRowY + 3, l.maxScroll()) == 28, "a click on the last row at the bottom finds its card: " + l.cardAt(l.cardX(0) + 3, bottomRowY + 3, l.maxScroll()));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void world_grid_arrows_move_across_the_grid(GameTestHelper h) {
        WorldGridLayout.Layout l = WorldGridLayout.of(480, 48, 206, 8); // 4 columns: create + 7 worlds, rows of 4 and 4
        h.assertTrue(l.cols() == 4, "4 columns at 480");
        h.assertTrue(l.move(1, 1, 0) == 2 && l.move(2, -1, 0) == 1, "left and right step one card");
        h.assertTrue(l.move(3, 1, 0) == -1 && l.move(4, -1, 0) == -1, "never round the end of a row: the focus moves on instead");
        h.assertTrue(l.move(1, 0, 1) == 5 && l.move(5, 0, -1) == 1, "up and down step a row");
        h.assertTrue(l.move(1, 0, -1) == -1, "up from the first row leaves the grid (to the search box)");
        h.assertTrue(l.move(5, 0, 1) == -1, "down from the last row leaves the grid (to the buttons)");
        WorldGridLayout.Layout ragged = WorldGridLayout.of(480, 48, 206, 6); // rows of 4 and 2
        h.assertTrue(ragged.move(3, 0, 1) == 5, "down with nothing straight below goes to the last card");
        h.assertTrue(ragged.move(0, 0, 1) == 4, "down from the create card goes to the card below it");
        h.assertTrue(ragged.move(-1, 0, 1) == 1 && ragged.move(-1, -1, 0) == 1, "with nothing chosen any arrow starts at the first world");
        WorldGridLayout.Layout none = WorldGridLayout.of(480, 48, 206, 1);
        h.assertTrue(none.move(-1, 0, 1) == 0, "with no world it starts at the create card");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void world_grid_pictures_cover_their_box(GameTestHelper h) {
        float[] r = WorldGridLayout.coverRows(160, 90);
        h.assertTrue(Math.abs(r[0] - 0.21875f) < 1e-5 && Math.abs(r[1] - 0.5625f) < 1e-5, "a 16:9 box shows the middle 9/16 of the square icon: " + Arrays.toString(r));
        h.assertTrue(Math.abs(r[0] * 2 + r[1] - 1f) < 1e-5, "cut equally top and bottom");
        float[] sq = WorldGridLayout.coverRows(64, 64);
        h.assertTrue(sq[0] == 0f && sq[1] == 1f, "a square box shows the whole icon");
        h.succeed();
    }
}
