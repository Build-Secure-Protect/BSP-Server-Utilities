package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.lag.LagResult;
import com.mrgregles.bsp_server_utilities.lag.LagSnapshot;
import com.mrgregles.bsp_server_utilities.network.LagAnswerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LAG tab's answers as they arrive (SU-149), kept for the screen (SU-150): each server's world summaries with when
 * they came, the last map asked for and the last few chunk details (a TP's and a pick's can cross), and each server's
 * latest PROFILE answer (the profiler's
 * running and finished samples, SU-151) for the PROFILER half (SU-152). Cleared when the client leaves a server. The LAG tab
 * ({@link LagTab}) reads it as it draws.
 */
public final class ClientLag {
    /** One server's summaries and when they came. */
    public record Summaries(List<LagSnapshot.Summary> worlds, long atMs) {
    }

    /** One world's map rows: the server and the world they are of (the answer names both, audit of SU-153). */
    public record Map(String server, String dimension, List<LagSnapshot.MapRow> rows, long atMs) {
    }

    /** One server's profiler answer and when it came. */
    public record Profile(LagResult.Profile profile, long atMs) {
    }

    /** A chunk's detail, the server that gave it and when it came. */
    private record DetailAnswer(LagSnapshot.Detail detail, String server, long atMs) {
    }

    /** How many detail answers are kept, the newest first. */
    private static final int DETAILS_KEPT = 4;

    private static final java.util.Map<String, Summaries> SUMMARIES = new LinkedHashMap<>();
    private static final java.util.Map<String, Profile> PROFILES = new LinkedHashMap<>();
    @Nullable
    private static Map map;
    private static final java.util.ArrayDeque<DetailAnswer> DETAILS = new java.util.ArrayDeque<>();
    /** Counts the summaries' changes, so the screen sorts its list again only when they change. */
    private static long summariesVersion;

    private ClientLag() {
    }

    public static void accept(LagAnswerPacket p) {
        long now = System.currentTimeMillis();
        switch (p.kind()) {
            case SUMMARY -> {
                SUMMARIES.put(p.server(), new Summaries(LagSnapshot.decodeSummaries(p.payload()), now));
                summariesVersion++;
            }
            case MAP -> {
                LagSnapshot.MapAnswer a = LagSnapshot.decodeMap(p.payload());
                map = new Map(p.server(), a.dimension(), a.rows(), now);
            }
            case DETAIL -> {
                LagSnapshot.Detail d = LagSnapshot.decodeDetail(p.payload());
                if (d != null) {
                    DETAILS.addFirst(new DetailAnswer(d, p.server(), now));
                    while (DETAILS.size() > DETAILS_KEPT) {
                        DETAILS.removeLast();
                    }
                }
            }
            case PROFILE -> PROFILES.put(p.server(), new Profile(LagResult.decodeProfile(p.payload()), now));
        }
    }

    public static java.util.Map<String, Summaries> summaries() {
        return java.util.Collections.unmodifiableMap(SUMMARIES);
    }

    @Nullable
    public static Map map() {
        return map;
    }

    /** How many times the summaries changed (an answer came, or they were cleared). */
    public static long summariesVersion() {
        return summariesVersion;
    }

    /**
     * The newest detail of a chunk that came at or after {@code sinceMs} from a server {@code from} accepts; null when none
     * has.
     */
    @Nullable
    public static LagSnapshot.Detail detail(java.util.function.Predicate<String> from, String dimension, int x, int z, long sinceMs) {
        for (DetailAnswer a : DETAILS) {
            LagSnapshot.Detail d = a.detail();
            if (a.atMs() >= sinceMs && d.x() == x && d.z() == z && d.dimension().equals(dimension) && from.test(a.server())) {
                return d;
            }
        }
        return null;
    }

    /** Each server's latest profiler answer, by server. */
    public static java.util.Map<String, Profile> profiles() {
        return java.util.Collections.unmodifiableMap(PROFILES);
    }

    /** One server's latest profiler answer; null when it has not answered. */
    @Nullable
    public static Profile profile(String server) {
        return PROFILES.get(server);
    }

    /** The LAG tab opens: the last map and detail are of an earlier look, and are dropped (the tab asks again). */
    public static void clearMapAndDetail() {
        map = null;
        DETAILS.clear();
    }

    public static void clear() {
        SUMMARIES.clear();
        summariesVersion++;
        PROFILES.clear();
        map = null;
        DETAILS.clear();
    }
}
