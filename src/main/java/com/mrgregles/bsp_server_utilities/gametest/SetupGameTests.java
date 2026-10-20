package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Proves the GameTest wiring: the mod is loaded in the test server (with BSP-Core beside it, as dev runs load it) and a
 * mock player can be made. Every server-side rule added later gets its own test class next to this one.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class SetupGameTests {
    static final String EMPTY = "empty";

    @GameTest(template = EMPTY)
    public void mod_is_loaded_beside_bsp_core_and_a_mock_player_can_be_made(GameTestHelper h) {
        if (!ModList.get().isLoaded(BSPServerUtilities.MODID)) {
            h.fail("bsp_server_utilities is not loaded");
            return;
        }
        if (!ModList.get().isLoaded("bsp_core")) {
            h.fail("bsp_core is not loaded in the dev run");
            return;
        }
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        if (player.getGameProfile().getName().isEmpty()) {
            h.fail("mock player has no name");
            return;
        }
        h.succeed();
    }
}
