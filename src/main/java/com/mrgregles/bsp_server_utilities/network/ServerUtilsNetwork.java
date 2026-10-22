package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** The mod's channel: {@code bsp_server_utilities:main}. The server decides, the client displays. */
public final class ServerUtilsNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ServerUtilsNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(RulesOpenPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RulesOpenPacket::encode).decoder(RulesOpenPacket::decode)
                .consumerMainThread(RulesOpenPacket::handle).add();
        CHANNEL.messageBuilder(RulesAnswerPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RulesAnswerPacket::encode).decoder(RulesAnswerPacket::decode)
                .consumerMainThread(RulesAnswerPacket::handle).add();
        CHANNEL.messageBuilder(AdminViewPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminViewPacket::encode).decoder(AdminViewPacket::decode)
                .consumerMainThread(AdminViewPacket::handle).add();
        CHANNEL.messageBuilder(AdminRulesPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdminRulesPacket::encode).decoder(AdminRulesPacket::decode)
                .consumerMainThread(AdminRulesPacket::handle).add();
        CHANNEL.messageBuilder(PanelOpenPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PanelOpenPacket::encode).decoder(PanelOpenPacket::decode)
                .consumerMainThread(PanelOpenPacket::handle).add();
        CHANNEL.messageBuilder(PanelSavePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PanelSavePacket::encode).decoder(PanelSavePacket::decode)
                .consumerMainThread(PanelSavePacket::handle).add();
        CHANNEL.messageBuilder(PanelRefreshPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PanelRefreshPacket::encode).decoder(PanelRefreshPacket::decode)
                .consumerMainThread(PanelRefreshPacket::handle).add();
        CHANNEL.messageBuilder(ImageRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImageRequestPacket::encode).decoder(ImageRequestPacket::decode)
                .consumerMainThread(ImageRequestPacket::handle).add();
        CHANNEL.messageBuilder(ImageChunkPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ImageChunkPacket::encode).decoder(ImageChunkPacket::decode)
                .consumerMainThread(ImageChunkPacket::handle).add();
        CHANNEL.messageBuilder(StaffViewPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StaffViewPacket::encode).decoder(StaffViewPacket::decode)
                .consumerMainThread(StaffViewPacket::handle).add();
        CHANNEL.messageBuilder(AdminStaffPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminStaffPacket::encode).decoder(AdminStaffPacket::decode)
                .consumerMainThread(AdminStaffPacket::handle).add();
        CHANNEL.messageBuilder(AdminStaffEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdminStaffEditPacket::encode).decoder(AdminStaffEditPacket::decode)
                .consumerMainThread(AdminStaffEditPacket::handle).add();
        CHANNEL.messageBuilder(LogViewPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LogViewPacket::encode).decoder(LogViewPacket::decode)
                .consumerMainThread(LogViewPacket::handle).add();
        CHANNEL.messageBuilder(AdminLogPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminLogPacket::encode).decoder(AdminLogPacket::decode)
                .consumerMainThread(AdminLogPacket::handle).add();
        CHANNEL.messageBuilder(AdminLogEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdminLogEditPacket::encode).decoder(AdminLogEditPacket::decode)
                .consumerMainThread(AdminLogEditPacket::handle).add();
        CHANNEL.messageBuilder(LogOpenPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LogOpenPacket::encode).decoder(LogOpenPacket::decode)
                .consumerMainThread(LogOpenPacket::handle).add();
        CHANNEL.messageBuilder(AdminAfkPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminAfkPacket::encode).decoder(AdminAfkPacket::decode)
                .consumerMainThread(AdminAfkPacket::handle).add();
        CHANNEL.messageBuilder(AdminAfkEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdminAfkEditPacket::encode).decoder(AdminAfkEditPacket::decode)
                .consumerMainThread(AdminAfkEditPacket::handle).add();
        CHANNEL.messageBuilder(AdminChunksPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminChunksPacket::encode).decoder(AdminChunksPacket::decode)
                .consumerMainThread(AdminChunksPacket::handle).add();
        CHANNEL.messageBuilder(AdminChunksEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdminChunksEditPacket::encode).decoder(AdminChunksEditPacket::decode)
                .consumerMainThread(AdminChunksEditPacket::handle).add();
        CHANNEL.messageBuilder(AdminDropsPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AdminDropsPacket::encode).decoder(AdminDropsPacket::decode)
                .consumerMainThread(AdminDropsPacket::handle).add();
        CHANNEL.messageBuilder(AdminDropsEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AdminDropsEditPacket::encode).decoder(AdminDropsEditPacket::decode)
                .consumerMainThread(AdminDropsEditPacket::handle).add();
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
