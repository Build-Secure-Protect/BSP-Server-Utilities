package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.AdminStaffPacket;
import com.mrgregles.bsp_server_utilities.network.AdminViewPacket;
import net.minecraft.client.Minecraft;

/** The client's side of the admin panel: opens it on {@code /su} and passes fresh views to it while it is open. */
public final class ClientAdmin {
    private ClientAdmin() {
    }

    /** A setup guide that arrived before its panel opened, and when (it is used only within a few seconds). */
    @org.jetbrains.annotations.Nullable
    private static com.mrgregles.bsp_server_utilities.network.SetupViewPacket earlyGuide;
    private static long earlyGuideAt;

    public static void view(AdminViewPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof AdminScreen screen) {
            screen.accept(packet);
        } else if (packet.open()) {
            AdminScreen screen = new AdminScreen(packet);
            mc.setScreen(screen);
            if (earlyGuide != null && System.currentTimeMillis() - earlyGuideAt < 10_000) {
                screen.acceptSetup(earlyGuide);
            }
            earlyGuide = null;
        }
    }

    /** The setup guide's data: opens the guide in the panel, or refreshes it. */
    public static void setup(com.mrgregles.bsp_server_utilities.network.SetupViewPacket packet) {
        if (Minecraft.getInstance().screen instanceof AdminScreen screen) {
            screen.acceptSetup(packet);
        } else if (packet.open()) {
            earlyGuide = packet; // the panel is still on its way: open the guide when it arrives
            earlyGuideAt = System.currentTimeMillis();
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
