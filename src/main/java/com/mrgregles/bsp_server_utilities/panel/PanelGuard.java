package com.mrgregles.bsp_server_utilities.panel;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Perms;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** A player who may not edit never breaks a Display Panel, in creative or survival (owner decision D52). */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID)
public final class PanelGuard {
    private PanelGuard() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getState().getBlock() instanceof DisplayPanelBlock && event.getPlayer() instanceof ServerPlayer sp && !Perms.canEdit(sp)) {
            event.setCanceled(true);
        }
    }
}
