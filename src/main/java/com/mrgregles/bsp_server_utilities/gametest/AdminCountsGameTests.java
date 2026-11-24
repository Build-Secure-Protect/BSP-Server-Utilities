package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.admin.AdminCounts;
import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.mobs.MobData;
import com.mrgregles.bsp_server_utilities.mobs.MobLimits;
import com.mrgregles.bsp_server_utilities.mobs.MobSettings;
import com.mrgregles.bsp_server_utilities.network.AdminCountsPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * The staff screen's counts (SU-154): this server and its players and today's refusals as their sources say; every chunk
 * over its mob number counts, a quiet one under the fullest list's floor of 10 mobs too; the packet carries them unchanged.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class AdminCountsGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public static void countsNameThisServer(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AdminCountsPacket p = AdminCounts.of(server);
        h.assertTrue(p.here().equals(Storage.currentServerId()), "this server's name");
        h.assertTrue(p.online() == server.getPlayerCount(), "the players on it");
        h.assertTrue(p.refused() == ClientCheckService.refusedToday().size(), "today's refusals");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyChunkOverItsNumberCounts(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        BlockPos rel = new BlockPos(3, 2, 3);
        ChunkPos c = new ChunkPos(h.absolutePos(rel));
        for (int i = 0; i < 5; i++) {
            h.spawnWithNoFreeWill(EntityType.SILVERFISH, rel);
        }
        MobSettings before = MobLimits.settings(server);
        String here = Storage.currentServerId(), dim = h.getLevel().dimension().location().toString();
        try {
            // the same mobs read twice, the chunk's number the only change: it counts once at 1, not at a number it is under
            MobData.get(server).setSettings(before.withChunk(here, dim, c.x, c.z, 1).sanitised());
            int small = MobLimits.localOverCount(server);
            MobData.get(server).setSettings(before.withChunk(here, dim, c.x, c.z, 1000).sanitised());
            int big = MobLimits.localOverCount(server);
            h.assertTrue(small - big == 1, "a chunk of 5 mobs over a number of 1 counts, under the list's floor of 10: " + small + " against " + big);
        } finally {
            MobData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void countsPacketRoundTrip(GameTestHelper h) {
        AdminCountsPacket p = new AdminCountsPacket("survival", 14, 3, 120);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        p.encode(buf);
        h.assertTrue(AdminCountsPacket.decode(buf).equals(p), "the counts arrive as sent");
        h.succeed();
    }
}
