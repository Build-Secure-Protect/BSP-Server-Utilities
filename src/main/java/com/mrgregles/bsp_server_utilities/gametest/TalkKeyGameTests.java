package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.support.CornerPlace;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.List;

/**
 * Where the MESSAGE STAFF key stands (SU-108; owner decision D652 R5): two pixels right of SUPPORT BSP on the corner row,
 * where SUPPORT BSP would be when there is no support link, a 66 px key stepping past what is in the way and down a row
 * when the left half of the window is used up. The key itself is drawn in the player's game (not testable here).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TalkKeyGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final int W = 66;

    @GameTest(template = EMPTY)
    public void the_message_staff_key_stands_after_support_bsp(GameTestHelper h) {
        int[] team = {2, 2, 40, 14}, kits = {44, 2, 40, 14}, support = {86, 2, 62, 14};
        int[] panel = {152, 52 - 32, 176, 166 + 64};
        int[] at = CornerPlace.place(List.of(team, kits, support, panel), new int[]{150, 2}, 480, 270, W);
        h.assertTrue(Arrays.equals(at, new int[]{150, 2}), "two pixels right of SUPPORT BSP on a 480 x 270 window: " + Arrays.toString(at));
        at = CornerPlace.place(List.of(team, kits, panel), new int[]{86, 2}, 480, 270, W);
        h.assertTrue(Arrays.equals(at, new int[]{86, 2}), "where SUPPORT BSP would be when it is hidden: " + Arrays.toString(at));
        at = CornerPlace.place(List.of(team, kits, support), new int[]{150, 2}, 400, 270, W);
        h.assertTrue(Arrays.equals(at, new int[]{2, 18}), "66 px past the left half of a 400 px window: down a row: " + Arrays.toString(at));
        at = CornerPlace.place(List.of(team, kits, support), new int[]{150, 2}, 432, 270, W);
        h.assertTrue(Arrays.equals(at, new int[]{150, 2}), "a key that just fits stays on the row: " + Arrays.toString(at));
        int[] other = {160, 2, 20, 20};
        at = CornerPlace.place(List.of(team, kits, support, other), new int[]{150, 2}, 600, 270, W);
        h.assertTrue(Arrays.equals(at, new int[]{182, 2}), "another mod's button in the way is stepped past: " + Arrays.toString(at));
        at = CornerPlace.place(List.of(team, kits, support), new int[]{150, 2}, 480, 270);
        h.assertTrue(Arrays.equals(at, new int[]{150, 2}), "SUPPORT BSP's own 62 px placing is unchanged");
        h.succeed();
    }
}
