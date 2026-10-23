package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.network.LogViewPacket;
import com.mrgregles.bsp_server_utilities.network.StaffViewPacket;
import com.mrgregles.bsp_server_utilities.registry.ModBlocks;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only registrations on the mod bus: the Display Panel's face renderer, the boards on BSP-Core's Score Screens and the client check. */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlocks.DISPLAY_PANEL_ENTITY.get(), PanelRenderer::new);
    }

    /** The STAFF ONLINE board on BSP-Core's Score Screens (D60), from the roster the server keeps sending. */
    /** The client check (D127): a resource reload means looking at the packs again. */
    @SubscribeEvent
    public static void onReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> ClientCheck.onReload());
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // the client check: read the mods' files in the background, and look again after reloads
        ClientCheck.start();
        MinecraftForge.EVENT_BUS.addListener(ClientCheck::onTick);
        // the setup card in the corner (D155)
        ClientSetupGuide.register();
        event.enqueueWork(() -> BspCore.registerBoard("staff_online", "gui.bsp_server_utilities.staff.title", () -> {
            List<BspCore.Line> out = new ArrayList<>();
            for (StaffViewPacket.Row r : ClientStaff.rows()) {
                String value = switch (r.state()) {
                    case ONLINE -> r.server();
                    case AWAY -> Tr.t("staff.away");
                    case OFFLINE -> ClientStaff.agoMs(r) > 0 ? StaffFace.ago(ClientStaff.agoMs(r)) : "";
                };
                int colour = switch (r.state()) {
                    case ONLINE -> 0x8FE04A;
                    case AWAY -> 0xF2B544;
                    case OFFLINE -> 0x9AA3B5;
                };
                out.add(new BspCore.Line(r.name() + " \u00b7 " + r.label(), value, colour));
            }
            return out;
        }));
        // ACTION LOG (D60): the newest entries as players read them, who in their role's colour, how long ago at the right
        event.enqueueWork(() -> BspCore.registerBoard("action_log", "gui.bsp_server_utilities.log.title", () -> {
            List<BspCore.Line> out = new ArrayList<>();
            List<LogViewPacket.Row> rows = ClientLog.rows();
            for (LogViewPacket.Row r : rows.subList(0, Math.min(BOARD_LINES, rows.size()))) {
                out.add(new BspCore.Line(r.actor(), r.colour(), r.text(), StaffFace.ago(ClientLog.agoMs(r)), 0x9AA3B5));
            }
            return out;
        }));
    }

    /** How many entries the ACTION LOG board offers (BSP-Core shows as many as its screen holds). */
    private static final int BOARD_LINES = 12;
}
