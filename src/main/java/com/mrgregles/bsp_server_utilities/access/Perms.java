package com.mrgregles.bsp_server_utilities.access;

import com.mrgregles.bsp_server_utilities.compat.BspCore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who may edit what this mod shows (the rules, the panels, the logging switches): operators at permission level 2 and
 * anyone BSP-Core counts as an admin (owner decision D3).
 */
public final class Perms {
    public static final int OPERATOR_LEVEL = 2;

    private Perms() {
    }

    public static boolean canEdit(ServerPlayer player) {
        return player.hasPermissions(OPERATOR_LEVEL) || BspCore.isAdmin(player);
    }

    /** The console and command blocks pass by permission level; a player as {@link #canEdit(ServerPlayer)}. */
    public static boolean canEdit(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? canEdit(player) : source.hasPermission(OPERATOR_LEVEL);
    }
}
