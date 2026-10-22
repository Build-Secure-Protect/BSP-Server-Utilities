package com.mrgregles.bsp_server_utilities.despawn;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Despawn times for dropped items, one set for the network (owner decisions D101 to D105, D117): whether time counts
 * while nobody is near, how long items last (1 to 60 minutes), how long a player's death drops last (1 to 60), whether
 * experience orbs follow the items' time, and per-item exceptions (1 to 120 minutes, or 0 for never), with a revision.
 */
public record DespawnSettings(boolean enabled, int itemMinutes, int deathMinutes, boolean orbsFollow, List<ItemTime> exceptions, long revision) {
    public static final int MIN_MINUTES = 1, MAX_MINUTES = 60, MAX_EXCEPTION_MINUTES = 120, MAX_EXCEPTIONS = 200, NEVER = 0;
    public static final DespawnSettings DEFAULTS = new DespawnSettings(true, 5, 15, true, List.of(), 0);
    private static final String SEP = "\u001f";

    /** An item with its own time: minutes, or {@link #NEVER}. */
    public record ItemTime(String itemId, int minutes) {
    }

    public DespawnSettings {
        exceptions = List.copyOf(exceptions);
    }

    public DespawnSettings sanitised() {
        List<ItemTime> out = new ArrayList<>();
        for (ItemTime e : exceptions) {
            String id = e == null || e.itemId() == null ? "" : e.itemId().strip().toLowerCase(Locale.ROOT);
            if (id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && out.stream().noneMatch(x -> x.itemId().equals(id)) && out.size() < MAX_EXCEPTIONS) {
                out.add(new ItemTime(id, e.minutes() <= NEVER ? NEVER : Math.min(MAX_EXCEPTION_MINUTES, e.minutes())));
            }
        }
        return new DespawnSettings(enabled, clamp(itemMinutes), clamp(deathMinutes), orbsFollow, out, revision);
    }

    private static int clamp(int m) {
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, m));
    }

    public DespawnSettings withRevision(long r) {
        return new DespawnSettings(enabled, itemMinutes, deathMinutes, orbsFollow, exceptions, r);
    }

    /** The exception for an item, or null. */
    @Nullable
    public ItemTime exception(String itemId) {
        for (ItemTime e : exceptions) {
            if (e.itemId().equals(itemId)) {
                return e;
            }
        }
        return null;
    }

    public String encode() {
        StringBuilder ex = new StringBuilder();
        for (ItemTime e : exceptions) {
            if (ex.length() > 0) {
                ex.append(',');
            }
            ex.append(e.itemId()).append('=').append(e.minutes());
        }
        return String.join(SEP, Boolean.toString(enabled), Integer.toString(itemMinutes), Integer.toString(deathMinutes), Boolean.toString(orbsFollow), ex, Long.toString(revision));
    }

    @Nullable
    public static DespawnSettings decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 6) {
            return null;
        }
        try {
            List<ItemTime> ex = new ArrayList<>();
            for (String e : f[4].isEmpty() ? new String[0] : f[4].split(",")) {
                int eq = e.lastIndexOf('=');
                if (eq > 0) {
                    ex.add(new ItemTime(e.substring(0, eq), Integer.parseInt(e.substring(eq + 1))));
                }
            }
            return new DespawnSettings(Boolean.parseBoolean(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Boolean.parseBoolean(f[3]), ex, Long.parseLong(f[5])).sanitised();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("encoded", encode());
        return t;
    }

    public static DespawnSettings load(CompoundTag t) {
        DespawnSettings s = t.contains("encoded") ? decode(t.getString("encoded")) : null;
        return s == null ? DEFAULTS : s;
    }
}
