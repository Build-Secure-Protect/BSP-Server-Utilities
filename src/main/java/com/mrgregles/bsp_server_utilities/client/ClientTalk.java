package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.TalkPopupPacket;
import com.mrgregles.bsp_server_utilities.network.TalkStatePacket;

/**
 * The client's copy of its conversation with staff (owner decisions D633 to D636): whether one is open (the MESSAGE STAFF
 * key shows), whether the newest line is from staff (the key flashes), and the popup waiting to be shown, with the staff
 * lines that arrived since folded into it (D652 R3: one popup per batch, naming the writer only when there was one).
 * Reset on leaving the server; the next server sends the state at login.
 */
public final class ClientTalk {
    private static TalkStatePacket state = TalkStatePacket.NONE;
    private static int popupCount;
    private static String popupWriter = "";
    private static boolean popupAway;

    private ClientTalk() {
    }

    public static void acceptState(TalkStatePacket p) {
        state = p;
        if (!p.open()) {
            clearPopup(); // ended: nothing left to open
        }
    }

    public static void acceptPopup(TalkPopupPacket p) {
        popupWriter = popupCount == 0 ? p.writer() : popupWriter.equals(p.writer()) ? popupWriter : "";
        popupCount += Math.max(1, p.count());
        popupAway = popupAway || p.away();
    }

    public static boolean open() {
        return state.open();
    }

    public static boolean waiting() {
        return state.open() && state.waiting();
    }

    /** Staff lines waiting for the popup (0: none). */
    public static int popupCount() {
        return popupCount;
    }

    public static String popupWriter() {
        return popupWriter;
    }

    public static boolean popupAway() {
        return popupAway;
    }

    /** The popup was shown (or the window opened): the batch is done. */
    public static void clearPopup() {
        popupCount = 0;
        popupWriter = "";
        popupAway = false;
    }

    // ------------------------------------------------------------------ the private window's data (drawn by SU-108)

    @org.jetbrains.annotations.Nullable
    private static com.mrgregles.bsp_server_utilities.network.TalkViewPacket view;
    private static final java.util.List<com.mrgregles.bsp_server_utilities.network.TalkViewPacket.Entry> LINES = new java.util.ArrayList<>();
    private static String refused = "";

    /** The server opened the window with its thread: an open window shows it in place, else the window opens over what is open. */
    public static void acceptView(com.mrgregles.bsp_server_utilities.network.TalkViewPacket p) {
        view = p;
        LINES.clear();
        LINES.addAll(p.lines());
        refused = "";
        if (!p.staff()) {
            clearPopup(); // reading it is what the popup asked for
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (!(mc.screen instanceof TalkScreen)) {
            net.minecraft.client.gui.screens.Screen under = mc.screen instanceof TalkPopupScreen || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen ? null : mc.screen;
            mc.setScreen(new TalkScreen(under));
        }
    }

    // ------------------------------------------------------------------ the popup at a quiet moment (D635)

    private static final long QUIET_MS = 10_000L;
    private static long lastHurt;

    /** Every client tick: notes when the player was hurt, and shows the popup when lines wait and nothing else is open. */
    static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent e) {
        if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        long now = net.minecraft.Util.getMillis();
        if (mc.player.hurtTime > 0) {
            lastHurt = now;
        }
        if (popupCount == 0) {
            return;
        }
        if (mc.screen instanceof TalkScreen && view != null && !view.staff()) {
            clearPopup(); // the window is open: the line is in it
        } else if (mc.screen == null && now - lastHurt >= QUIET_MS && !mc.player.isDeadOrDying()) {
            mc.setScreen(new TalkPopupScreen());
        }
    }

    /** A line arrived for the conversation the window shows. */
    public static void acceptLine(com.mrgregles.bsp_server_utilities.network.TalkLinePacket p) {
        if (view != null && view.player().equals(p.player())) {
            LINES.add(p.line());
            refused = "";
        }
    }

    public static void acceptAnswer(com.mrgregles.bsp_server_utilities.network.TalkAnswerPacket p) {
        refused = p.outcome();
    }

    @org.jetbrains.annotations.Nullable
    public static com.mrgregles.bsp_server_utilities.network.TalkViewPacket view() {
        return view;
    }

    public static java.util.List<com.mrgregles.bsp_server_utilities.network.TalkViewPacket.Entry> lines() {
        return java.util.Collections.unmodifiableList(LINES);
    }

    /** Why the last line or end was refused ({@code TalkService.Outcome} name), empty when nothing was. */
    public static String refused() {
        return refused;
    }

    /** The window closed: the server stops sending its lines. */
    public static void closeView() {
        if (view != null) {
            com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork.sendToServer(
                    new com.mrgregles.bsp_server_utilities.network.TalkActionPacket(com.mrgregles.bsp_server_utilities.network.TalkActionPacket.CLOSE, view.player(), ""));
        }
        view = null;
        LINES.clear();
        refused = "";
    }

    /** Once, at client setup. */
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> reset());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ClientTalk::tick);
    }

    public static void reset() {
        state = TalkStatePacket.NONE;
        clearPopup();
        view = null;
        LINES.clear();
        refused = "";
    }
}
