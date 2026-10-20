package com.mrgregles.bsp_server_utilities.afk;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * AFK detection's settings, one set for the network (owner decisions D71, D74, D76, D81, D82): whether idle players are
 * marked AFK and after how many minutes (1 to 60), whether players AFK too long are kicked and after how many minutes
 * AFK (5 to 240), the kick message (up to 160 characters), and a revision that marks every save (the newest wins).
 */
public record AfkSettings(boolean enabled, int afkMinutes, boolean kick, int kickMinutes, String message, long revision) {
    public static final int MIN_AFK = 1, MAX_AFK = 60, MIN_KICK = 5, MAX_KICK = 240, MAX_MESSAGE = 160;
    public static final String DEFAULT_MESSAGE = "You were idle for too long. Come back any time!";
    public static final AfkSettings DEFAULTS = new AfkSettings(true, 5, false, 30, DEFAULT_MESSAGE, 0);
    private static final String SEP = "\u001f";

    /** The same settings within the limits; an empty message is the default one. */
    public AfkSettings sanitised() {
        String m = message == null ? "" : message.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").strip();
        if (m.length() > MAX_MESSAGE) {
            m = m.substring(0, MAX_MESSAGE);
        }
        return new AfkSettings(enabled, clamp(afkMinutes, MIN_AFK, MAX_AFK), kick, clamp(kickMinutes, MIN_KICK, MAX_KICK), m.isEmpty() ? DEFAULT_MESSAGE : m, revision);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public AfkSettings withRevision(long r) {
        return new AfkSettings(enabled, afkMinutes, kick, kickMinutes, message, r);
    }

    public long afkMs() {
        return afkMinutes * 60_000L;
    }

    public long kickMs() {
        return kickMinutes * 60_000L;
    }

    public String encode() {
        return String.join(SEP, Boolean.toString(enabled), Integer.toString(afkMinutes), Boolean.toString(kick), Integer.toString(kickMinutes), message, Long.toString(revision));
    }

    @Nullable
    public static AfkSettings decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 6) {
            return null;
        }
        try {
            return new AfkSettings(Boolean.parseBoolean(f[0]), Integer.parseInt(f[1]), Boolean.parseBoolean(f[2]), Integer.parseInt(f[3]), f[4], Long.parseLong(f[5])).sanitised();
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
        t.putLong("revision", revision);
        return t;
    }

    public static AfkSettings load(CompoundTag t) {
        if (t.isEmpty()) {
            return DEFAULTS;
        }
        return new AfkSettings(t.getBoolean("enabled"), t.getInt("afk"), t.getBoolean("kick"), t.getInt("kickAfter"), t.getString("message"), t.getLong("revision")).sanitised();
    }
}
