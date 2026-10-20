package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.StaffViewPacket;

import java.util.List;

/** The client's copy of the staff roster, with the time it arrived so "ago" keeps counting. */
public final class ClientStaff {
    private static List<StaffViewPacket.Row> rows = List.of();
    private static long receivedAt;

    private ClientStaff() {
    }

    public static void accept(StaffViewPacket p) {
        rows = p.rows();
        receivedAt = System.currentTimeMillis();
    }

    public static List<StaffViewPacket.Row> rows() {
        return rows;
    }

    /** How long ago a row's player was seen, now. */
    public static long agoMs(StaffViewPacket.Row r) {
        return r.agoMs() <= 0 ? 0 : r.agoMs() + (System.currentTimeMillis() - receivedAt);
    }
}
