package com.mrgregles.bsp_server_utilities.support;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Where the SUPPORT BSP key stands in the inventory's top left corner (owner decisions D587, D592, D610): plain
 * arithmetic, so it is checked without a client. Rectangles are {x, y, width, height}.
 */
public final class CornerPlace {
    public static final int W = 62, H = 14, GAP = 2, MARGIN = 2;

    private CornerPlace() {
    }

    /**
     * The key's place ({x, y}), or null when there is none: from {@code after} (right of BSP-Core's TEAM and KITS) or the
     * window's corner, stepping right past every blocked rectangle and down a row when the left half of the window is
     * used up.
     */
    @Nullable
    public static int[] place(List<int[]> blocked, @Nullable int[] after, int screenW, int screenH) {
        int x = MARGIN, y = MARGIN;
        if (after != null) {
            x = after[0];
            y = after[1];
        }
        int limit = Math.max(MARGIN + W, screenW / 2);
        for (int tries = 0; tries < 256; tries++) {
            if (x + W > limit) {
                x = MARGIN;
                y += H + GAP;
            }
            if (y + H > screenH) {
                return null;
            }
            int[] hit = overlap(blocked, x, y);
            if (hit == null) {
                return new int[]{x, y};
            }
            x = Math.max(x + 1, hit[0] + hit[2] + GAP);
        }
        return null;
    }

    @Nullable
    private static int[] overlap(List<int[]> blocked, int x, int y) {
        for (int[] b : blocked) {
            if (x < b[0] + b[2] && x + W > b[0] && y < b[1] + b[3] && y + H > b[1]) {
                return b;
            }
        }
        return null;
    }
}
