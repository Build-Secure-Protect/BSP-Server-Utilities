package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.UUID;

/**
 * Server players for tests, as in the other BSP mods. {@link GameTestHelper#makeMockServerPlayerInLevel()} gives its
 * connection no channel, and Forge's packet listener touches the channel in its constructor, so this builds the player
 * on an in-memory netty channel instead. Packets sent to the player go nowhere. A mock player is not in the server's
 * player list, so nothing broadcast reaches it and {@code getPlayer(uuid)} does not find it.
 *
 * <p>When LuckPerms is loaded (dev runs with the LuckPerms dev jar in {@code libs/}), the mock is also taken through
 * LuckPerms's login step, as BSP RankUp's helper does: its user is loaded and the load-from-file event is fired.
 */
final class TestPlayers {
    private static int counter;

    private TestPlayers() {
    }

    static ServerPlayer mockServerPlayer(GameTestHelper h) {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection); // registers and activates the handler, which gives the connection its channel
        String name = "su_test_" + (++counter); // letters, digits and underscores only: LuckPerms refuses other names
        UUID uuid = UUID.randomUUID();
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), new GameProfile(uuid, name));
        player.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, player);
        if (LuckPermsBridge.isPresent()) {
            net.luckperms.api.LuckPermsProvider.get().getUserManager().loadUser(uuid, name).join();
            java.io.File playerDir = h.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile();
            MinecraftForge.EVENT_BUS.post(new PlayerEvent.LoadFromFile(player, playerDir, uuid.toString()));
        }
        return player;
    }
}
