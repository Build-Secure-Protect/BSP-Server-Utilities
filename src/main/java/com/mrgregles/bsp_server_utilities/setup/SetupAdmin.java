package com.mrgregles.bsp_server_utilities.setup;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.SetupEditPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The setup card's and guide's server side: only players who may edit; every guide action answered with fresh data. */
public final class SetupAdmin {
    private SetupAdmin() {
    }

    public static boolean apply(ServerPlayer player, SetupEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        MinecraftServer server = player.server;
        Feature f = p.feature();
        switch (p.action()) {
            case OPEN -> {
                SetupService.openGuide(player);
                return true;
            }
            case CARD_LATER -> {
                player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.setup.later_done"));
                return true;
            }
            case IGNORE -> {
                SetupService.ignore(server, player.getUUID());
                player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.setup.ignored"));
                return true;
            }
            case NEXT -> SetupService.next(server, f);
            case LATER -> SetupService.later(server, f);
            case OFF -> SetupService.turnOff(server, f);
            case ON -> SetupService.turnOn(server, f);
            case CLOSE -> SetupService.closedAt(server, f);
            case NAME -> {
                if (SetupService.setServerName(p.arg())) {
                    player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.setup.name_saved", p.arg().strip()));
                }
            }
            case REFRESH -> {
            }
        }
        ServerUtilsNetwork.sendTo(player, SetupService.view(server, false, f));
        return true;
    }
}
