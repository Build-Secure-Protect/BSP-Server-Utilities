package com.mrgregles.bsp_server_utilities.log;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * What goes into the action log (owner decision D25). Commands: one listener at LOWEST that a cancelled command never
 * reaches; on Mohist every typed command, plugin commands included, passes Forge's {@code CommandEvent} on its way through
 * the vanilla dispatcher. Who: staff, operators, the console ("Console") and RCON ("RCON"); command blocks and other
 * players are not logged. Creative items come from {@link CreativeTracker}; BSP-Core's kit gives from its kit event.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID)
public final class LogCapture {
    private LogCapture() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        String text = event.getParseResults().getReader().getString().strip();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        String root = LogText.root(text);
        MinecraftServer server = source.getServer();
        if (root.isEmpty() || server == null || !ActionLog.logged(server, root)) {
            return;
        }
        String kind = LogText.MODE_COMMANDS.contains(root) ? LogEntry.MODE : LogText.GIVE_COMMANDS.contains(root) ? LogEntry.GIVE : LogEntry.CMD;
        String shown = "/" + text;
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            Optional<Staff.Role> role = ActionLog.tracked(player);
            role.ifPresent(r -> ActionLog.record(server, ActionLog.entryFor(player, r, kind, shown)));
            return;
        }
        if (source.getEntity() == null) {
            String name = source.getTextName();
            if (name.equals("Server")) {
                ActionLog.record(server, ActionLog.entryForServer("Console", kind, shown));
            } else if (name.equals("Rcon")) {
                ActionLog.record(server, ActionLog.entryForServer("RCON", kind, shown));
            }
        }
    }

    /** BSP-Core's kit gives from its admin panel (ADMIN) and TEST ON ME (TEST). */
    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        BspCore.onKitGiven((player, kit, how) -> {
            if (how.equals("TEST")) {
                ActionLog.tracked(player).ifPresent(r -> ActionLog.record(player.server, ActionLog.entryFor(player, r, LogEntry.GIVE, "kit TEST ON ME: " + kit)));
            } else if (how.equals("ADMIN")) {
                ActionLog.record(player.server, ActionLog.entryForServer("Admin panel", LogEntry.GIVE, "gave the kit " + kit + " to " + player.getGameProfile().getName()));
            }
        });
    }
}
