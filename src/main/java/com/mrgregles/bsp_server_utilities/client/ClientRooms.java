package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import net.minecraft.client.Minecraft;

/** The client's side of {@code /su rooms} (SU-58): the page opens, or refreshes in place when it is already open. */
public final class ClientRooms {
    private ClientRooms() {
    }

    public static void accept(RoomsViewPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RoomsScreen open) {
            open.update(packet);
        } else {
            mc.setScreen(new RoomsScreen(packet));
        }
    }
}
