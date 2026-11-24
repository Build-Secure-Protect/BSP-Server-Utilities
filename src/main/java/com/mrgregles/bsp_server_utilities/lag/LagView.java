package com.mrgregles.bsp_server_utilities.lag;

import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sums behind the LAG tab's PROFILER (SU-152; D749, D751, D759, D760; frames R4 to R6 of
 * {@code tools/preview/lag_refined.html}), kept apart from the drawing so GameTests can check them: the colour of a
 * square from milliseconds per tick, the squares of one world at a zoom, a running sample's progress and time left, when
 * the screen asks again, the outline's clock and how long ago a sample ran.
 */
public final class LagView {
    /** Under 0.5, 0.5 to 2, 2 to 5, and 5 and more milliseconds per tick. */
    public static final int[] MS_RAMP = {0xFF26303C, 0xFF5E7F45, 0xFFF2B544, 0xFFFF6B5C};
    /** A chunk not loaded, or no data for it. */
    public static final int UNLOADED = 0xFF0E1117;
    /** How often the screen asks about a running sample. */
    public static final long POLL_MS = 2000;
    /** How long OUTLINE's boxes stay (D751). */
    public static final long OUTLINE_MS = 5 * 60_000L;
    /** A tick at 20 a second. */
    public static final long TICK_MS = 50;

    /** One square of the map at a zoom: the milliseconds of the timed chunks in it, and its slowest chunk. */
    public record Square(double ms, int worstX, int worstZ, double worstMs) {
    }

    private LagView() {
    }

    /** The ramp's colour for milliseconds per tick. */
    public static int msColour(double ms) {
        return ms < 0.5 ? MS_RAMP[0] : ms < 2 ? MS_RAMP[1] : ms < 5 ? MS_RAMP[2] : MS_RAMP[3];
    }

    /**
     * A square's colour: by its timed milliseconds when the sample timed a chunk in it; else the darkest loaded colour
     * when the map has it loaded; else not loaded.
     */
    public static int squareColour(@Nullable Square timed, boolean loaded) {
        if (timed != null) {
            return msColour(timed.ms());
        }
        return loaded ? MS_RAMP[0] : UNLOADED;
    }

    /** One world's timed chunks summed into squares of {@code 2^zoom} chunks a side, by {@link ChunkPos#asLong} of the square. */
    public static Map<Long, Square> squares(List<LagResult.Chunk> chunks, String dimension, int zoom) {
        Map<Long, Square> out = new HashMap<>();
        int s = 1 << Math.max(0, zoom);
        for (LagResult.Chunk c : chunks) {
            if (!c.dimension().equals(dimension)) {
                continue;
            }
            long key = ChunkPos.asLong(Math.floorDiv(c.x(), s), Math.floorDiv(c.z(), s));
            Square q = out.get(key);
            if (q == null) {
                out.put(key, new Square(c.ms(), c.x(), c.z(), c.ms()));
            } else {
                boolean worse = c.ms() > q.worstMs();
                out.put(key, new Square(q.ms() + c.ms(), worse ? c.x() : q.worstX(), worse ? c.z() : q.worstZ(), worse ? c.ms() : q.worstMs()));
            }
        }
        return out;
    }

    /** The slowest chunks of every world in a result, at most {@code n}, the slowest first. */
    public static List<LagResult.Chunk> worst(LagResult r, int n) {
        List<LagResult.Chunk> all = new ArrayList<>(r.chunks());
        all.sort(Comparator.comparingDouble(LagResult.Chunk::ms).reversed());
        return all.subList(0, Math.max(0, Math.min(n, all.size())));
    }

    /** OUTLINE's blocks in one world. */
    public static List<LagResult.Block> blocksIn(List<LagResult.Block> blocks, String dimension) {
        return blocks.stream().filter(b -> b.dimension().equals(dimension)).toList();
    }

    /**
     * What a running sample has left, in ms: the ticks it had to go when the answer came, less the time since
     * ({@code sinceMs}), never under 0. Worked out from ticks, so the server's clock is not needed.
     */
    public static long leftMs(int ticksAsked, int ticks, long sinceMs) {
        return Math.max(0, Math.max(0, ticksAsked - ticks) * TICK_MS - Math.max(0, sinceMs));
    }

    /** A running sample's progress, 0 to 1, as {@link #leftMs} has it. */
    public static double progress(int ticksAsked, int ticks, long sinceMs) {
        if (ticksAsked <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, 1 - leftMs(ticksAsked, ticks, sinceMs) / (double) (ticksAsked * TICK_MS)));
    }

    /** The whole seconds a running sample has left, rounded up. */
    public static long secondsLeft(int ticksAsked, int ticks, long sinceMs) {
        return (leftMs(ticksAsked, ticks, sinceMs) + 999) / 1000;
    }

    /**
     * Whether the screen asks about a running sample again: {@value #POLL_MS} ms after the last ask, or as soon as it
     * should have ended ({@code endAtMs}, on the screen's clock) when that has not been asked yet.
     */
    public static boolean askDue(long now, long lastAskMs, long endAtMs, boolean endAsked) {
        return now - lastAskMs >= POLL_MS || !endAsked && endAtMs > 0 && now >= endAtMs;
    }

    /** {@code m:ss}, the seconds rounded up. */
    public static String clock(long ms) {
        long s = (Math.max(0, ms) + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** How long ago as an amount and its unit: 0 seconds, 1 minutes, 2 hours, 3 days. */
    public static long[] ago(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) {
            return new long[]{s, 0};
        }
        long min = s / 60;
        if (min < 60) {
            return new long[]{min, 1};
        }
        long h = min / 60;
        return h < 48 ? new long[]{h, 2} : new long[]{h / 24, 3};
    }

    /** Milliseconds as the screen writes them: two decimals under 1, one from there. */
    public static String ms(double ms) {
        return String.format(Locale.ROOT, ms < 1 ? "%.2f" : "%.1f", ms);
    }

    /** The share of a 50 ms tick in whole percent. */
    public static int sharePercent(double ms) {
        return (int) Math.round(ms / LagResult.TICK_MS * 100);
    }
}
