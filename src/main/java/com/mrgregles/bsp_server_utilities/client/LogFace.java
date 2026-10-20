package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.LogViewPacket;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;

import java.util.List;

/**
 * The Action log face (owner decisions D26, D53, D55, D59), as on the confirmed page
 * {@code tools/preview/staff_log_refined.html}: ACTION LOG with where the entries come from and how long they are kept,
 * then a table, newest first: how long ago, who in their role's colour, what (coordinates hidden), and the server when
 * the panel shows every server. As many rows as the panel's view asks for (6, 8 or 12), sized to fill the face.
 */
final class LogFace {
    private static final int VIOLET = 0xFFB58CFF, MUTED = 0xFF9AA3B5, TEXT = 0xFFE6EAF2, CHIP = 0xFF1B2230, RULE = 0xFF1B2230;

    private LogFace() {
    }

    static void draw(FaceCanvas c, int wBlocks, int hBlocks, PanelSettings.LogView view) {
        float w = wBlocks * PanelLayout.PX, h = hBlocks * PanelLayout.PX;
        float x0 = PanelLayout.INSET + PanelLayout.MARGIN + 2, x1 = w - x0, y0 = x0, y1 = h - x0;
        float head = Math.max(7, Math.min(20, h * 0.11f));
        c.text(Tr.t("log.title"), x0, y0, head, VIOLET);
        String where = view.allServers() ? Tr.t("log.all_servers", ClientLog.keepDays()) : Tr.t("log.this_server", ClientLog.here(), ClientLog.keepDays());
        float subPx = head * 0.6f;
        float subW = Math.min(c.width(where, subPx), (x1 - x0) * 0.55f);
        c.text(StaffFace.fit(c, where, subW + 0.01f, subPx), x1 - subW, y0 + (head - subPx) * 0.6f, subPx, MUTED);

        List<LogViewPacket.Row> rows = LogViewPacket.visible(ClientLog.rows(), ClientLog.here(), view);
        float top = y0 + head * 1.5f;
        if (rows.isEmpty()) {
            c.text(StaffFace.fit(c, Tr.t("log.none"), x1 - x0, head * 0.6f), x0, top + head * 0.3f, head * 0.6f, MUTED);
            return;
        }
        float rowH = (y1 - top) / view.lines();
        float px = Math.max(4, Math.min(10, rowH * 0.62f)), gap = px * 0.8f;
        // column widths from what this face holds: ago and who fitted to their widest, the server as a chip, what takes the rest
        float agoW = 0, whoW = 0, srvW = 0;
        for (LogViewPacket.Row r : rows) {
            agoW = Math.max(agoW, c.width(StaffFace.ago(ClientLog.agoMs(r)), px * 0.85f));
            whoW = Math.max(whoW, c.width(r.actor(), px));
            srvW = Math.max(srvW, c.width(r.server(), px * 0.8f));
        }
        float span = x1 - x0;
        whoW = Math.min(whoW, span * 0.24f);
        srvW = view.allServers() ? Math.min(srvW, span * 0.22f) + px * 0.4f : 0;
        float whoX = x0 + agoW + gap, whatX = whoX + whoW + gap, srvX = x1 - srvW, whatW = (srvW > 0 ? srvX - gap : x1) - whatX;
        float y = top;
        for (LogViewPacket.Row r : rows) {
            float ty = y + (rowH - px) / 2;
            String ago = StaffFace.ago(ClientLog.agoMs(r));
            c.text(ago, x0, ty + px * 0.08f, px * 0.85f, MUTED);
            c.text(StaffFace.fit(c, r.actor(), whoW, px), whoX, ty, px, 0xFF000000 | r.colour());
            c.text(StaffFace.fit(c, r.text(), whatW, px), whatX, ty, px, TEXT);
            if (srvW > 0) {
                float sp = px * 0.8f;
                c.rect(srvX, ty - px * 0.05f, x1, ty + px * 1.05f, CHIP);
                c.text(StaffFace.fit(c, r.server(), srvW - px * 0.4f, sp), srvX + px * 0.2f, ty + (px - sp) / 2, sp, MUTED);
            }
            y += rowH;
            if (y < y1 - rowH * 0.5f) {
                c.rect(x0, y - Math.max(0.4f, px * 0.06f), x1, y, RULE);
            }
        }
    }
}
