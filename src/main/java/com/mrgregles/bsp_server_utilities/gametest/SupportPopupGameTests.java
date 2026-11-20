package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.SupportOpenPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.support.CornerPlace;
import com.mrgregles.bsp_server_utilities.support.SupportService;
import com.mrgregles.bsp_server_utilities.support.SupportSettings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /support} and where the SUPPORT BSP key stands (SU-91; owner decisions D587, D592, D593, D595, D610): the
 * command opens the popup at the support link for any player, and with no link says so instead; the key's place right
 * of TEAM and KITS, stepping past what is in the way, down a row when the left half is used up, and nowhere when no
 * place is free. The key's drawing, the popup, the browser, the clipboard, JEI and EMI need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class SupportPopupGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String SHOP = "https://store.example.net/bsp";

    @GameTest(template = EMPTY)
    public void support_opens_the_popup_or_says_there_is_no_page(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SupportSettings was = SupportService.settings(server);
        List<SupportOpenPacket> opened = new ArrayList<>();
        try {
            Storage.useForTests(null);
            SupportService.useOpenerForTests((p, packet) -> opened.add(packet));
            SupportService.restoreForTests(server, SupportSettings.NONE);
            ServerPlayer kat = TestPlayers.mockServerPlayer(h);
            int ran = server.getCommands().performPrefixedCommand(kat.createCommandSourceStack(), "support");
            h.assertTrue(ran == 1 && opened.isEmpty() && !SupportService.open(kat), "with no link /support opens nothing and answers in chat instead (D595)");
            SupportService.restoreForTests(server, new SupportSettings(SHOP, "", "", "Console", 1, 1));
            ran = server.getCommands().performPrefixedCommand(kat.createCommandSourceStack(), "support");
            h.assertTrue(ran == 1 && opened.size() == 1 && opened.get(0).link().equals(SHOP), "any player's /support opens the popup at the support link (D593): " + opened);
        } finally {
            SupportService.useReal();
            SupportService.restoreForTests(server, was);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_support_key_finds_a_free_place_in_the_corner(GameTestHelper h) {
        int[] team = {2, 2, 40, 14}, kits = {44, 2, 40, 14};
        int[] panel = {152, 52 - 32, 176, 166 + 64};
        int[] after = {86, 2};
        int[] at = CornerPlace.place(List.of(team, kits, panel), after, 480, 270);
        h.assertTrue(Arrays.equals(at, new int[]{86, 2}), "two pixels right of KITS on a 480 x 270 window (D610): " + Arrays.toString(at));
        at = CornerPlace.place(List.of(), null, 480, 270);
        h.assertTrue(Arrays.equals(at, new int[]{2, 2}), "the window's corner without BSP-Core: " + Arrays.toString(at));
        int[] other = {86, 2, 20, 20};
        at = CornerPlace.place(List.of(team, kits, other, panel), after, 480, 270);
        h.assertTrue(Arrays.equals(at, new int[]{108, 2}), "another mod's button in the way is stepped past: " + Arrays.toString(at));
        at = CornerPlace.place(List.of(team, kits), after, 294, 200);
        h.assertTrue(Arrays.equals(at, new int[]{2, 18}), "past the left half of a narrow window: down a row: " + Arrays.toString(at));
        at = CornerPlace.place(List.of(team, kits), after, 296, 200);
        h.assertTrue(Arrays.equals(at, new int[]{86, 2}), "a key that just fits stays on the row: " + Arrays.toString(at));
        int[] book = {0, 0, 300, 180};
        at = CornerPlace.place(List.of(team, kits, book), after, 480, 190);
        h.assertTrue(at == null, "nothing free (a recipe book over the whole left half of a short window): not drawn rather than drawn over something");
        at = CornerPlace.place(List.of(team, kits, new int[]{0, 0, 300, 100}), after, 480, 270);
        h.assertTrue(at != null && at[1] >= 100 && at[0] + CornerPlace.W <= 240, "under something tall it steps down until it is free: " + Arrays.toString(at));
        h.succeed();
    }
}
