package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.AdminStaffPacket;
import com.mrgregles.bsp_server_utilities.network.AdminViewPacket;
import net.minecraft.client.Minecraft;

/** The client's side of the admin panel: opens it on {@code /su} and passes fresh views to it while it is open. */
public final class ClientAdmin {
    private ClientAdmin() {
    }

    public static void view(AdminViewPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AdminScreen screen) {
            screen.accept(packet);
        } else if (packet.open()) {
            mc.setScreen(new AdminScreen(packet));
        }
    }

    /** The setup guide's data: opens the guide in the panel, or refreshes it. */
    public static void setup(com.mrgregles.bsp_server_utilities.network.SetupViewPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptSetup(packet);
        }
    }

    public static void clients(com.mrgregles.bsp_server_utilities.network.AdminClientsPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptClients(packet);
        }
    }

    public static void drops(com.mrgregles.bsp_server_utilities.network.AdminDropsPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptDrops(packet);
        }
    }

    public static void chunks(com.mrgregles.bsp_server_utilities.network.AdminChunksPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptChunks(packet);
        }
    }

    public static void afk(com.mrgregles.bsp_server_utilities.network.AdminAfkPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptAfk(packet);
        }
    }

    public static void log(com.mrgregles.bsp_server_utilities.network.AdminLogPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptLog(packet);
        }
    }

    public static void staff(AdminStaffPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptStaff(packet);
        }
    }
}
