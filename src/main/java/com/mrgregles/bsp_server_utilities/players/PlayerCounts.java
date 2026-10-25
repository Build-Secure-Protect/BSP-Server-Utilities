package com.mrgregles.bsp_server_utilities.players;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * What the counter knows of the real list: how many players it holds, the first joins of the last {@value #RECENT_DAYS}
 * days (newest first, at most {@value #RECENT_MAX}) from which today's and this week's counts are taken as time passes,
 * and the newest player of all.
 */
public record PlayerCounts(int real, List<Joined> recent, @Nullable Joined newest) {
    public static final int RECENT_DAYS = 8, RECENT_MAX = 5000;
    public static final long DAY_MS = 86_400_000L;
    public static final PlayerCounts NONE = new PlayerCounts(0, List.of(), null);

    public PlayerCounts {
        recent = List.copyOf(recent);
    }

    /** First joins since the start of today (the server's own clock and time zone). */
    public int today(long now) {
        long since = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return since(since);
    }

    /** First joins in the last seven days. */
    public int week(long now) {
        return since(now - 7 * DAY_MS);
    }

    private int since(long ms) {
        int n = 0;
        for (Joined j : recent) {
            if (j.atMs() >= ms) {
                n++;
            }
        }
        return n;
    }

    /** How far back the recent list reaches from a moment. */
    public static long recentSince(long now) {
        return now - RECENT_DAYS * DAY_MS;
    }
}
