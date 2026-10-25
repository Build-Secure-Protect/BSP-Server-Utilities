package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.PlayersViewPacket;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import com.mrgregles.bsp_server_utilities.players.PlayerCounter;

import java.util.Locale;

/**
 * The Player count face (owner decisions D172, D175, D178), look A of {@code tools/preview/counter_refined.html}: the
 * title in teal (the panel's own, else the counter's), the number large in gold, "players have joined the network" under
 * it, and the new counts small when the panel shows them; everything centred. Nothing while the counter is off (D173).
 */
final class PlayersFace {
    private static final int TEAL = 0xFF19D3B0, GOLD = 0xFFFFD23A, TEXT = 0xFFE6EAF2, MUTED = 0xFF9AA3B5;

    private PlayersFace() {
    }

    static void draw(FaceCanvas c, int wBlocks, int hBlocks, PanelSettings.CountView view) {
        PlayersViewPacket v = ClientPlayers.view();
        if (v.off()) {
            return;
        }
        float w = wBlocks * PanelLayout.PX, h = hBlocks * PanelLayout.PX;
        float x0 = PanelLayout.INSET + PanelLayout.MARGIN + 2, maxW = w - 2 * x0;
        String title = (view.title().isEmpty() ? v.title() : view.title()).toUpperCase(Locale.ROOT);
        String number = PlayerCounter.format(v.shown());
        String under = Tr.t("players.face.joined");
        String counts = Tr.t("players.face.new", PlayerCounter.format(v.today()), PlayerCounter.format(v.week()));
        float titlePx = size(c, title, h * 0.092f, maxW), numberPx = size(c, number, h * 0.27f, maxW);
        float underPx = size(c, under, h * 0.075f, maxW), countsPx = view.newCounts() ? size(c, counts, h * 0.067f, maxW) : 0;
        float gap = h * 0.045f;
        float total = titlePx + gap + numberPx + gap + underPx + (view.newCounts() ? gap + countsPx : 0);
        float y = (h - total) / 2;
        centred(c, StaffFace.fit(c, title, maxW, titlePx), w, y, titlePx, TEAL);
        y += titlePx + gap;
        centred(c, number, w, y, numberPx, GOLD);
        y += numberPx + gap;
        centred(c, StaffFace.fit(c, under, maxW, underPx), w, y, underPx, TEXT);
        if (view.newCounts()) {
            y += underPx + gap;
            centred(c, StaffFace.fit(c, counts, maxW, countsPx), w, y, countsPx, MUTED);
        }
    }

    /** The size wanted, smaller when the text would be wider than the face, never under 3 panel pixels. */
    private static float size(FaceCanvas c, String s, float want, float maxW) {
        float perPx = c.width(s, 1f);
        return Math.max(3f, perPx <= 0 ? want : Math.min(want, maxW / perPx));
    }

    private static void centred(FaceCanvas c, String s, float w, float y, float px, int colour) {
        c.text(s, (w - c.width(s, px)) / 2, y, px, colour);
    }
}
