package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.RegionGuard;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The staff panel's regions on a server without BSP Region Guard's api 10 (SU-158, D782), touching none of Region
 * Guard's types: run as part of every batch, and on its own meaning with {@code -PnoRg} (no Region Guard) and
 * {@code -PrgRun=0.7.3} (api 9). Whatever is installed, the answer matches what {@link RegionGuard#regionsReady} found,
 * and without api 10 every region action says so and the snapshot says no regions here.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebRegionsAbsentGameTests {
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 3 + 11);
        }
    }

    @GameTest(template = SetupGameTests.EMPTY)
    public void without_region_guard_api_10_every_region_action_says_so(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        try {
            boolean ready = RegionGuard.regionsReady();
            BSPServerUtilities.LOGGER.info("Region Guard loaded: {}; api 10 or newer: {}", RegionGuard.isLoaded(), ready);
            JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
            h.assertTrue(snap.get("regions").getAsBoolean() == ready && snap.has("regionsAt") == ready, "the snapshot says what is here: " + snap.get("regions"));
            for (String action : List.of("regions_list", "region_save", "region_remove", "region_mine_reset", "region_check", "region_create")) {
                String json = "{\"v\":1,\"id\":\"abs-" + action + "-" + System.nanoTime() + "\",\"at\":" + System.currentTimeMillis() + ",\"by\":\"Ana_Mod\",\"rank\":\"OWNER\",\"server\":\""
                        + Storage.currentServerId() + "\",\"action\":\"" + action + "\",\"dim\":\"minecraft:overworld\",\"region\":\"abs_none\",\"version\":0,\"set\":{\"name\":\"x\"}}";
                WebActions.Answer a = WebActions.handle(server, WebActions.sign(KEY, json));
                h.assertTrue(a != null, action + " is answered");
                if (!ready) {
                    h.assertTrue(!a.ok() && a.message().contains("Region Guard 0.8.0 or newer is not on this server"), action + " says Region Guard is missing: " + a.message());
                } else {
                    h.assertTrue(!a.message().contains("does not know that action"), action + " is known: " + a.message());
                }
            }
            RegionGuard.claimOwner(h.getLevel(), new net.minecraft.world.level.ChunkPos(h.absolutePos(net.minecraft.core.BlockPos.ZERO))); // chunk owners: never throws, with or without Region Guard
        } finally {
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }
}
