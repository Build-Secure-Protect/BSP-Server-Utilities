package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.menus.ServerGridLayout;
import com.mrgregles.bsp_server_utilities.menus.WorldGridLayout;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Multiplayer's server grid, the arithmetic: the featured server's card spans the row on top, the other servers are square
 * cards in the Select World grid's columns, the scanning line comes last, the scroll is clamped, the arrows go between
 * the featured card and the first row, and which addresses count as the featured server's. The drawing, the vanilla list
 * it reads, the pinging and every action need a client (docs/TESTING.md).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class ServerGridGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void server_grid_puts_the_featured_server_on_top_and_squares_below(GameTestHelper h) {
        for (int w : new int[]{480, 576, 640, 960}) {
            ServerGridLayout.Layout l = ServerGridLayout.of(w, 32, 270 - 64, true, 7, true);
            WorldGridLayout.Layout worlds = WorldGridLayout.of(w, 32, 206, 8);
            h.assertTrue(l.cols() == worlds.cols() && l.cardW() == worlds.cardW(), w + ": the same columns and card width as the Select World grid: " + l);
            h.assertTrue(l.isHero(0) && l.cardX(0) == l.left() && l.cardW(0) == l.rowW() && l.cardH(0) == ServerGridLayout.HERO_H,
                    w + ": card 0 is the featured server, as wide as a row of squares");
            int lastRight = l.cardX(l.cols()) + l.cardW(l.cols());
            h.assertTrue(lastRight == l.left() + l.rowW(), w + ": the squares' row ends where the featured card ends");
            h.assertTrue(l.cardW(1) == l.cardH(1), w + ": the other servers are squares");
            h.assertTrue(l.cardY(1, 0) == l.cardY(0, 0) + ServerGridLayout.HERO_H + WorldGridLayout.GAP, w + ": the squares start a gap below the featured card");
            h.assertTrue(l.cardX(0) >= WorldGridLayout.MARGIN && l.left() + l.rowW() <= l.barX() - WorldGridLayout.BAR_GAP, w + ": inside the margins, clear of the scrollbar");
        }
        ServerGridLayout.Layout none = ServerGridLayout.of(480, 32, 206, false, 3, true);
        h.assertTrue(!none.isHero(0) && none.cardW(0) == none.cardH(0) && none.cardY(0, 0) == 32 + WorldGridLayout.PAD,
                "without the featured server every server is a square, from the top");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void server_grid_scrolls_to_the_scanning_line(GameTestHelper h) {
        ServerGridLayout.Layout l = ServerGridLayout.of(480, 32, 206, true, 12, true); // 3 rows of 4 squares
        h.assertTrue(l.rows() == 3, "12 squares in 4 columns are 3 rows");
        int bottomOfLine = l.lineY(0) + ServerGridLayout.LINE_H + WorldGridLayout.PAD;
        h.assertTrue(l.contentH() == bottomOfLine - 32, "the content ends after the scanning line: " + l.contentH());
        h.assertTrue(l.maxScroll() == l.contentH() - l.viewH(), "it scrolls to the end of the content");
        h.assertTrue(l.lineY(0) == l.cardY(12, 0) + l.cardW() + WorldGridLayout.GAP, "the line sits a gap under the last row");
        int last = l.scrollToShow(0, 12);
        h.assertTrue(l.cardY(12, last) + l.cardH(12) <= l.bottom(), "the last square scrolls into view");
        h.assertTrue(l.scrollToShow(l.maxScroll(), 0) == 0, "the featured card scrolls back to the top");
        h.assertTrue(l.cardAt(l.cardX(0) + 2, l.cardY(0, 0) + 2, 0) == 0 && l.cardAt(l.cardX(0) + l.rowW() - 2, l.cardY(0, 0) + 2, 0) == 0,
                "the whole width of the featured card is card 0");
        h.assertTrue(l.cardAt(l.left() + 2, l.lineY(l.maxScroll()) + 5, l.maxScroll()) == -1, "the scanning line is no card");
        ServerGridLayout.Layout few = ServerGridLayout.of(960, 32, 476, true, 2, true);
        h.assertTrue(few.maxScroll() == 0 && few.thumb(0) == null, "a few servers on a big screen do not scroll");
        ServerGridLayout.Layout empty = ServerGridLayout.of(480, 32, 206, false, 0, true);
        h.assertTrue(empty.cards() == 0 && empty.lineY(0) == 32 + WorldGridLayout.PAD, "no server at all: only the scanning line");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void server_grid_arrows_reach_the_featured_card(GameTestHelper h) {
        ServerGridLayout.Layout l = ServerGridLayout.of(480, 32, 206, true, 6, true); // featured, then rows of 4 and 2
        h.assertTrue(l.move(0, 0, 1) == 1, "down from the featured card goes to the first square");
        h.assertTrue(l.move(0, 1, 0) == -1 && l.move(0, -1, 0) == -1 && l.move(0, 0, -1) == -1, "left, right and up leave the grid from the featured card");
        h.assertTrue(l.move(3, 0, -1) == 0 && l.move(1, 0, -1) == 0, "up from any square of the first row goes to the featured card");
        h.assertTrue(l.move(1, 1, 0) == 2 && l.move(4, 1, 0) == -1, "right steps along a row and stops at its end");
        h.assertTrue(l.move(2, 0, 1) == 6 && l.move(4, 0, 1) == 6, "down from the first row: straight below, else the last square");
        h.assertTrue(l.move(6, 0, -1) == 2 && l.move(6, 0, 1) == -1, "up from the second row; down from the last leaves the grid");
        h.assertTrue(l.move(-1, 0, 1) == 0, "with nothing chosen an arrow starts at the featured card");
        ServerGridLayout.Layout plain = ServerGridLayout.of(480, 32, 206, false, 6, true);
        h.assertTrue(plain.move(1, 0, -1) == -1 && plain.move(-1, 1, 0) == 0, "without one, up from the first row leaves the grid");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void server_grid_knows_the_featured_server_by_its_address(GameTestHelper h) {
        String bsp = "buildsecureprotect.com";
        h.assertTrue(ServerGridLayout.isFeatured("minecraft.buildsecureprotect.com", bsp), "the network's address");
        h.assertTrue(ServerGridLayout.isFeatured("Minecraft.BuildSecureProtect.com:25565", bsp), "any case, with a port");
        h.assertTrue(ServerGridLayout.isFeatured("buildsecureprotect.com.", bsp) && ServerGridLayout.isFeatured(" buildsecureprotect.com ", bsp), "the domain itself, a trailing dot, spaces");
        h.assertTrue(!ServerGridLayout.isFeatured("notbuildsecureprotect.com", bsp), "a name that only ends with the same letters is not it");
        h.assertTrue(!ServerGridLayout.isFeatured("buildsecureprotect.com.evil.net", bsp), "nor one with the domain in the middle");
        h.assertTrue(!ServerGridLayout.isFeatured("mc.hypixel.net", bsp), "another server is not it");
        h.assertTrue(!ServerGridLayout.isFeatured("minecraft.buildsecureprotect.com", "") && !ServerGridLayout.isFeatured("x", null), "an empty setting features nothing");
        h.assertTrue(ServerGridLayout.isFeatured("[2001:db8::1]:25565", "2001:db8::1") && !ServerGridLayout.isFeatured("2001:db8::1", bsp), "an IPv6 address in brackets");
        h.succeed();
    }
}
