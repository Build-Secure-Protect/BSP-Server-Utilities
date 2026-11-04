package com.mrgregles.bsp_server_utilities.afk;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * AFK detection's settings, one set for the network (owner decisions D71, D74, D76, D81, D82, D221): whether idle players
 * are marked AFK and after how many minutes (1 to 60), whether players AFK too long are checked and, not answering,
 * kicked, and after how many minutes AFK (5 to 240), the kick message (up to 160 characters), whether repeated input
 * (a macro pattern) does not count as activity, and a revision that marks every save (the newest wins). Encodings and
 * saves from before the macros switch read with it on.
 */
public record AfkSettings(boolean enabled, int afkMinutes, boolean kick, int kickMinutes, String message, boolean macros, long revision) {
    public static final int MIN_AFK = 1, MAX_AFK = 60, MIN_KICK = 5, MAX_KICK = 240, MAX_MESSAGE = 160;
    public static final String DEFAULT_MESSAGE = "You were idle for too long. Come back any time!";
    public static final AfkSettings DEFAULTS = new AfkSettings(true, 5, false, 30, DEFAULT_MESSAGE, true, 0);
    private static final String SEP = "\u001f";

    /** The same settings within the limits; an empty message is the default one. */
    public AfkSettings sanitised() {
        String m = message == null ? "" : message.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").strip();
        if (m.length() > MAX_MESSAGE) {
            m = m.substring(0, MAX_MESSAGE);
        }
        return new AfkSettings(enabled, clamp(afkMinutes, MIN_AFK, MAX_AFK), kick, clamp(kickMinutes, MIN_KICK, MAX_KICK), m.isEmpty() ? DEFAULT_MESSAGE : m, macros, revision);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public AfkSettings withRevision(long r) {
        return new AfkSettings(enabled, afkMinutes, kick, kickMinutes, message, macros, r);
    }

    public AfkSettings withEnabled(boolean on) {
        return new AfkSettings(on, afkMinutes, kick, kickMinutes, message, macros, revision);
    }

    public AfkSettings withAfkMinutes(int minutes) {
        return new AfkSettings(enabled, minutes, kick, kickMinutes, message, macros, revision);
    }

    public AfkSettings withKick(boolean on) {
        return new AfkSettings(enabled, afkMinutes, on, kickMinutes, message, macros, revision);
    }

    public AfkSettings withKickMinutes(int minutes) {
        return new AfkSettings(enabled, afkMinutes, kick, minutes, message, macros, revision);
    }

    public AfkSettings withMessage(String m) {
        return new AfkSettings(enabled, afkMinutes, kick, kickMinutes, m, macros, revision);
    }

    public AfkSettings withMacros(boolean on) {
        return new AfkSettings(enabled, afkMinutes, kick, kickMinutes, message, on, revision);
    }

    public long afkMs() {
        return afkMinutes * 60_000L;
    }

    public long kickMs() {
        return kickMinutes * 60_000L;
    }

    public String encode() {
        return String.join(SEP, Boolean.toString(enabled), Integer.toString(afkMinutes), Boolean.toString(kick), Integer.toString(kickMinutes), message,
                Long.toString(revision), Boolean.toString(macros));
    }

    /** Reads an encoding; one of six fields (before the macros switch) has it on. */
    @Nullable
    public static AfkSettings decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 6 && f.length != 7) {
            return null;
        }
        try {
            boolean macros = f.length == 6 || Boolean.parseBoolean(f[6]);
            return new AfkSettings(Boolean.parseBoolean(f[0]), Integer.parseInt(f[1]), Boolean.parseBoolean(f[2]), Integer.parseInt(f[3]), f[4], macros, Long.parseLong(f[5])).sanitised();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putBoolean("enabled", enabled);
        t.putInt("afk", afkMinutes);
        t.putBoolean("kick", kick);
        t.putInt("kickAfter", kickMinutes);
        t.putString("message", message);
        t.putBoolean("macros", macros);
        t.putLong("revision", revision);
        return t;
    }

    public static AfkSettings load(CompoundTag t) {
        if (t.isEmpty()) {
            return DEFAULTS;
        }
        boolean macros = !t.contains("macros") || t.getBoolean("macros");
        return new AfkSettings(t.getBoolean("enabled"), t.getInt("afk"), t.getBoolean("kick"), t.getInt("kickAfter"), t.getString("message"), macros, t.getLong("revision")).sanitised();
    }
}
