package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.LogViewPacket;

import java.util.List;

/** The client's copy of the newest action log entries (coordinates hidden), with the time it arrived so "ago" keeps counting. */
public final class ClientLog {
    private static LogViewPacket view = new LogViewPacket("", 7, List.of());
    private static long receivedAt;

    private ClientLog() {
    }

    public static void accept(LogViewPacket p) {
        view = p;
        receivedAt = System.currentTimeMillis();
    }

    /** When the newest copy arrived (the LOG tab asks for fresh entries when it changes). */
    public static long receivedAt() {
        return receivedAt;
    }

    public static List<LogViewPacket.Row> rows() {
        return view.rows();
    }

    /** The server this client is on. */
    public static String here() {
        return view.here();
    }

    public static int keepDays() {
        return view.keepDays();
    }

    /** How long ago an entry happened, now. */
    public static long agoMs(LogViewPacket.Row r) {
        return r.agoMs() + (System.currentTimeMillis() - receivedAt);
    }
}
