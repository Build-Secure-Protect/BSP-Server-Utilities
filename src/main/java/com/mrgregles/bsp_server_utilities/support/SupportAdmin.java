package com.mrgregles.bsp_server_utilities.support;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.network.AdminSupportEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminSupportPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * The SUPPORT tab's requests, on the server (owner decisions D602, D605, D612): refused outright (nothing answered) for
 * a player who may not edit; SAVE and CLEAR go through {@link SupportService}, which checks the links again, logs and
 * shares; the answer is the settings as saved and what became of the request.
 */
public final class SupportAdmin {
    private static BiConsumer<ServerPlayer, AdminSupportPacket> answer = ServerUtilsNetwork::sendTo;

    private SupportAdmin() {
    }

    /** False when the player may not edit (nothing is done or answered). */
    public static boolean apply(ServerPlayer player, AdminSupportEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        String note = "", problem = "";
        if (p.action() != AdminSupportEditPacket.Action.REFRESH) {
            SupportService.Result r = p.action() == AdminSupportEditPacket.Action.CLEAR
                    ? SupportService.clear(player.server, player, "")
                    : SupportService.edit(player.server, player, "", p.link(), p.sentence(), p.chatLink());
            note = switch (r.outcome()) {
                case SAVED -> r.settings().link().isEmpty() && r.settings().sentence().isEmpty() && r.settings().chatLink().isEmpty() ? "cleared" : "saved";
                case UNCHANGED -> "unchanged";
                case BAD_LINK -> "bad_link";
                case BAD_CHAT_LINK -> "bad_chat_link";
                case NOT_ALLOWED -> "not_allowed";
            };
            if (r.outcome() == SupportService.Outcome.BAD_LINK || r.outcome() == SupportService.Outcome.BAD_CHAT_LINK) {
                problem = r.problem().name();
            }
        }
        answer.accept(player, new AdminSupportPacket(SupportService.settings(player.server), note, problem));
        return true;
    }

    /** Where the answers go (tests); null: to the player. */
    public static void useForTests(@Nullable BiConsumer<ServerPlayer, AdminSupportPacket> a) {
        answer = a != null ? a : ServerUtilsNetwork::sendTo;
    }
}
