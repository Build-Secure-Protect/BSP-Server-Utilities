package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.network.StaffViewPacket;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Staff online face (owner decisions D53 to D55, D59, D62), as on the confirmed page
 * {@code tools/preview/staff_log_refined.html}: STAFF ONLINE and the count at the top, a column per role in its colour,
 * and in each a dot (green online, amber away, grey offline), the name, and the server, AWAY, or how long ago.
 */
final class StaffFace {
    private static final int TEAL = 0xFF19D3B0, GREEN = 0xFF8FE04A, AMBER = 0xFFF2B544, GREY = 0xFF3A3F4A, MUTED = 0xFF9AA3B5, TEXT = 0xFFE6EAF2, DIM = 0xFF6B7280, CHIP = 0xFF1B2230;

    private StaffFace() {
    }

    /** "now", "12 min", "3 h", "2 d". */
    static String ago(long ms) {
        long min = ms / 60_000L;
        if (min < 1) {
            return Tr.t("ago.now");
        }
        if (min < 60) {
            return Tr.t("ago.minutes", min);
        }
        long h = min / 60;
        return h < 48 ? Tr.t("ago.hours", h) : Tr.t("ago.days", h / 24);
    }

    static void draw(FaceCanvas c, int wBlocks, int hBlocks, PanelSettings.StaffView view) {
        float w = wBlocks * PanelLayout.PX, h = hBlocks * PanelLayout.PX;
        float x0 = PanelLayout.INSET + PanelLayout.MARGIN + 2, x1 = w - x0, y0 = x0, y1 = h - x0;
        List<StaffViewPacket.Row> rows = new ArrayList<>();
        int online = 0;
        for (StaffViewPacket.Row r : ClientStaff.rows()) {
            if (!view.roles().isEmpty() && !view.roles().contains(r.group().isEmpty() ? "operator" : r.group())) {
                continue;
            }
            if (r.state() != StaffDirectory.State.OFFLINE) {
                online++;
            } else if (!view.offline()) {
                continue;
            }
            rows.add(r);
        }
        float head = Math.max(7, Math.min(20, h * 0.11f));
        c.text(Tr.t("staff.title"), x0, y0, head, TEAL);
        String count = Tr.t("staff.count", online, rows.size());
        c.text(count, x1 - c.width(count, head * 0.85f), y0 + head * 0.1f, head * 0.85f, GREEN);
        Map<String, List<StaffViewPacket.Row>> byRole = new LinkedHashMap<>();
        for (StaffViewPacket.Row r : rows) {
            byRole.computeIfAbsent(r.label(), k -> new ArrayList<>()).add(r);
        }
        if (byRole.isEmpty()) {
            c.text(Tr.t("staff.none"), x0, y0 + head * 1.8f, head * 0.6f, MUTED);
            return;
        }
        float px = Math.max(5, Math.min(10, h * 0.06f)), line = px * 1.45f;
        float colW = (x1 - x0) / byRole.size(), top = y0 + head * 1.6f;
        int col = 0;
        for (Map.Entry<String, List<StaffViewPacket.Row>> e : byRole.entrySet()) {
            float cx = x0 + col * colW, cw = colW - px;
            int colour = 0xFF000000 | e.getValue().get(0).colour();
            c.text(fit(c, e.getKey().toUpperCase(), cw, px), cx, top, px, colour);
            c.rect(cx, top + px * 1.25f, cx + cw, top + px * 1.25f + Math.max(0.6f, px * 0.1f), (colour & 0xFFFFFF) | 0x55000000);
            float ry = top + px * 1.8f;
            for (StaffViewPacket.Row r : e.getValue()) {
                if (ry + px > y1) {
                    break;
                }
                int dot = switch (r.state()) {
                    case ONLINE -> GREEN;
                    case AWAY -> AMBER;
                    case OFFLINE -> GREY;
                };
                float d = px * 0.55f;
                c.rect(cx, ry + (px - d) / 2, cx + d, ry + (px - d) / 2 + d, dot);
                String tag = switch (r.state()) {
                    case ONLINE -> r.server();
                    case AWAY -> Tr.t("staff.away");
                    case OFFLINE -> ClientStaff.agoMs(r) > 0 ? ago(ClientStaff.agoMs(r)) : "";
                };
                float tagPx = px * 0.8f, tagW = tag.isEmpty() ? 0 : c.width(tag, tagPx) + px * 0.6f;
                String name = fit(c, r.name(), cw - d - px * 0.5f - tagW, px);
                float nx = cx + d + px * 0.5f;
                c.text(name, nx, ry, px, r.state() == StaffDirectory.State.OFFLINE ? DIM : TEXT);
                if (!tag.isEmpty()) {
                    float tx = nx + c.width(name, px) + px * 0.4f;
                    if (r.state() == StaffDirectory.State.ONLINE) {
                        c.rect(tx - px * 0.15f, ry, tx + c.width(tag, tagPx) + px * 0.15f, ry + px, CHIP);
                    }
                    c.text(tag, tx, ry + (px - tagPx) / 2, tagPx, r.state() == StaffDirectory.State.AWAY ? AMBER : MUTED);
                }
                ry += line;
            }
            col++;
        }
        float foot = px * 0.75f;
        if (y1 - foot > top + line * 4) {
            c.text(fit(c, Tr.t("staff.footer"), x1 - x0, foot), x0, y1 - foot, foot, MUTED);
        }
    }

    static String fit(FaceCanvas c, String s, float maxW, float px) {
        if (c.width(s, px) <= maxW) {
            return s;
        }
        String out = s;
        while (!out.isEmpty() && c.width(out + "…", px) > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
