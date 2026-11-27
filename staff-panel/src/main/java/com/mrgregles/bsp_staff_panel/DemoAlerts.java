package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.alerts.ServerWatch;
import com.mrgregles.bsp_staff_panel.store.PanelStore.AlertRow;

import java.util.List;

/**
 * The demo's server alerts (SU-168): SafeRoom (off in the demo) last heard a minute ago with the demo panel's own port as
 * its address, so it is checked and called frozen soon after the start; Lumen said it was stopping a minute ago and
 * never comes back (a planned restart, then NOT BACK); a few ended alerts for RECENT ALERTS. Made up, nothing is emailed.
 */
final class DemoAlerts {
    private DemoAlerts() {
    }

    static void fill(ServerWatch watch, int panelPort) {
        long now = System.currentTimeMillis();
        watch.seed("SafeRoom", now - 50_000L, snapshot("SafeRoom", now - 50_000L, "127.0.0.1:" + panelPort, false, 3, 48.2));
        watch.heard("Lumen", now - 60_000L, snapshot("Lumen", now - 60_000L, "127.0.0.1:1", false, 6, 21.0));
        watch.heard("Lumen", now - 59_000L, snapshot("Lumen", now - 59_000L, "127.0.0.1:1", true, 0, 0));
        long day = 86_400_000L;
        watch.restore(List.of(
                row(1, "Lumen", "NOT_BACK", now - day + 1_000, "CLOSED", 6, 20.4, now - day + 301_000, "", now - day + 421_000),
                row(2, "Moongrove", "CRASHED", now - 3 * day, "CLOSED", 2, 18.4, now - 3 * day + 81_000, "", now - 3 * day + 180_000),
                row(3, "Moongrove", "CRASHED", now - 3 * day - 1_380_000, "CLOSED", 3, 19.1, 0,
                        "email failed: the mail server refused the sign-in (535 5.7.8 Username and Password not accepted): check SMTP_USER and SMTP_PASSWORD", now - 3 * day - 1_260_000)));
    }

    private static AlertRow row(long id, String server, String kind, long since, String port, int players, double mspt, long emailed, String note, long back) {
        return new AlertRow(id, server, kind, since, since + 80_000, since, port, "", players, mspt, "", emailed, note, back, emailed > 0 ? back : 0, back, "");
    }

    private static JsonObject snapshot(String server, long at, String addr, boolean stopping, int players, double mspt) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("server", server);
        o.addProperty("at", at);
        o.addProperty("mspt", mspt);
        o.addProperty("addr", addr);
        JsonArray ps = new JsonArray();
        for (int i = 0; i < players; i++) {
            ps.add(new JsonObject());
        }
        o.add("players", ps);
        if (stopping) {
            o.addProperty("stopping", true);
        }
        return o;
    }
}
