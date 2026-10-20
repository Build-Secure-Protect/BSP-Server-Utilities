package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.RulesOpenPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * The client's side of the rules: opens the popup or the read-only copy when the server sends them. While the player
 * still has to answer, the popup comes back by itself if anything else closes it (another mod's screen, a lost
 * window); it stops once AGREE or LEAVE SERVER is sent, or the player logs out.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class ClientRules {
    /** The popup still waiting for an answer, or null. */
    @Nullable
    private static RulesOpenPacket pending;

    private ClientRules() {
    }

    public static void open(RulesOpenPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (packet.agree()) {
            pending = packet;
        }
        RulesScreen next = new RulesScreen(packet);
        if (mc.screen instanceof RulesScreen current) {
            next.copyTicksFrom(current);
        }
        mc.setScreen(next);
    }

    /** AGREE or LEAVE SERVER was sent: the popup is not brought back (the server sends it again if the answer is refused). */
    static void answered() {
        pending = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null) {
            mc.setScreen(new RulesScreen(pending));
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pending = null;
    }
}
