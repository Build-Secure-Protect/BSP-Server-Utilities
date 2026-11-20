package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.SupportLinkPacket;
import com.mrgregles.bsp_server_utilities.support.SupportLinks;

/** The client's copy of the network's support link (the SUPPORT BSP key and the popup); empty until the server sends one, and on leaving. */
public final class ClientSupport {
    private static String link = "";

    private ClientSupport() {
    }

    public static void acceptLink(SupportLinkPacket p) {
        link = SupportLinks.usable(p.link()) ? SupportLinks.tidy(p.link()) : "";
    }

    /** {@code /support}: the popup over whatever is open (the game when nothing is). */
    public static void open(String link) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (com.mrgregles.bsp_server_utilities.support.SupportLinks.usable(link)) {
            // a second /support while the popup is open replaces it, never stacks on it (SU-93 audit)
            net.minecraft.client.gui.screens.Screen under = mc.screen instanceof SupportScreen open ? open.parent() : mc.screen;
            mc.setScreen(new SupportScreen(link, under));
        }
    }

    /** The support link, or empty when there is none. */
    public static String link() {
        return link;
    }

    /** Once, at client setup. */
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> reset());
    }

    /** Leaving the server: nothing shown until the next one sends its link. */
    public static void reset() {
        link = "";
    }
}
