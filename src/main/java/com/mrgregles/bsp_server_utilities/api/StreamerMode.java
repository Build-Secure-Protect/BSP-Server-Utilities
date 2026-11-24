package com.mrgregles.bsp_server_utilities.api;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Streamer Mode for other mods (docs/API.md, "Streamer Mode"; request SU-TP2): while Server Utilities draws the clean
 * picture OBS captures, a mod that draws something the streamer may hide asks here and draws a placeholder instead. The
 * player's own window is drawn separately and always gets {@code false}.
 *
 * <p>Ask every time the thing is drawn, never cache the answer: it is true only during the stream picture. The first call
 * with an id also tells Server Utilities that the mod hides that item itself, so it stops hiding the mod's whole menu in
 * its place (the fallback until the mod has this call). Safe from any side: on a server it is always false. Call it by
 * class name when Server Utilities is optional (reflection or a {@code MethodHandle} looked up once), treating a missing
 * class or any error as false.
 */
public final class StreamerMode {
    private StreamerMode() {
    }

    /**
     * Whether the stream picture is being drawn now and the streamer hid this item. Ids so far:
     * {@code bsp_teleport:band_coordinates}, {@code bsp_teleport:portal_coordinates}.
     */
    public static boolean hidesNow(String id) {
        if (id == null || FMLEnvironment.dist != Dist.CLIENT) {
            return false;
        }
        return com.mrgregles.bsp_server_utilities.client.StreamerMode.askedHidingNow(id);
    }
}
