package com.mrgregles.bsp_server_utilities.talk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * The time steps and world words of "last seen" in the staff window (SU-121; owner decisions D672, D683 R1 and R2), kept
 * apart from the screen so the GameTests check them; the screen turns each step into its words.
 * <ul>
 * <li>Under an hour: {@link Step#MINUTES} (at least 1), "12 min ago (21:04)".</li>
 * <li>Under a day, or the same calendar day (24 h on the night the clocks go back): {@link Step#HOURS}, "3 h ago (21:04)".</li>
 * <li>The calendar day before: {@link Step#YESTERDAY}, "yesterday (22:41)".</li>
 * <li>To 13 calendar days: {@link Step#DAYS}, "4 d ago".</li>
 * <li>Then {@link Step#DATE}: "18 Oct", with the year when it is not this year's ("18 Oct 2025").</li>
 * </ul>
 * Days count in the viewer's own calendar, as "yesterday" does.
 */
public final class SeenWords {
    public enum Step { MINUTES, HOURS, YESTERDAY, DAYS, DATE }

    public enum World { OVERWORLD, NETHER, END, OTHER }

    /** One moment as the window says it: the step and its number, the clock ("21:04"), the date ("18 Oct") and both ("22 Nov 21:04"). */
    public record When(Step step, long n, String clock, String date, String full) {
    }

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT), DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ROOT),
            DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);

    private SeenWords() {
    }

    /** When {@code atMs} was, seen at {@code nowMs} in {@code zone}; a moment ahead of now counts as now. */
    public static When when(long atMs, long nowMs, ZoneId zone) {
        long at = Math.min(atMs, nowMs);
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone), now = Instant.ofEpochMilli(nowMs).atZone(zone);
        String clock = HM.format(t);
        String date = (t.getYear() == now.getYear() ? DAY : DAY_YEAR).format(t);
        String full = date + " " + clock;
        long min = (nowMs - at) / 60_000L;
        if (min < 60) {
            return new When(Step.MINUTES, Math.max(1, min), clock, date, full);
        }
        if (min < 1440) {
            return new When(Step.HOURS, min / 60, clock, date, full);
        }
        long days = ChronoUnit.DAYS.between(LocalDate.from(t), LocalDate.from(now));
        if (days <= 0) {
            return new When(Step.HOURS, min / 60, clock, date, full); // the same day for over 24 h: the night the clocks go back (audit of SU-121)
        }
        if (days == 1) {
            return new When(Step.YESTERDAY, 1, clock, date, full);
        }
        return new When(days <= 13 ? Step.DAYS : Step.DATE, days, clock, date, full);
    }

    /** The world of a dimension id: the three vanilla ones, else {@link World#OTHER} (named by {@link #otherName}). */
    public static World world(String dimension) {
        String d = dimension == null ? "" : dimension;
        return switch (d) {
            case "", "minecraft:overworld", "overworld" -> World.OVERWORLD;
            case "minecraft:the_nether", "the_nether" -> World.NETHER;
            case "minecraft:the_end", "the_end" -> World.END;
            default -> World.OTHER;
        };
    }

    /** A modded world's name: its path with underscores as spaces ("twilight_forest:twilight_forest" is "twilight forest"). */
    public static String otherName(String dimension) {
        String d = dimension == null ? "" : dimension;
        return d.substring(d.indexOf(':') + 1).replace('_', ' ');
    }
}
