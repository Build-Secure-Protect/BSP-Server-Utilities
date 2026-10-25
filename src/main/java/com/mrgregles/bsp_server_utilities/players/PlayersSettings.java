package com.mrgregles.bsp_server_utilities.players;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * The player counter's settings, one set for the network (owner decisions D169, D170, D177 to D179): the adjustment
 * added to the real count (the number shown is the real list plus it, never below 0), the title of the PLAYERS board
 * and of Player count panels that have none of their own (up to 32 characters), the welcome line's words with
 * {@value #NAME} and {@value #NUMBER} filled in (up to 160 characters), and a revision that marks every save (the newest
 * wins).
 */
public record PlayersSettings(int offset, String title, String welcome, long revision) {
    public static final int MAX_TITLE = 32, MAX_WELCOME = 160;
    public static final String NAME = "{name}", NUMBER = "{number}";
    public static final String DEFAULT_TITLE = "Players of BSP";
    public static final String DEFAULT_WELCOME = "Welcome {name}, player number {number} on BSP!";
    public static final PlayersSettings DEFAULTS = new PlayersSettings(0, DEFAULT_TITLE, DEFAULT_WELCOME, 0);
    private static final String SEP = "\u001f";

    /** The same settings within the limits; an empty title or welcome is the default one. */
    public PlayersSettings sanitised() {
        return new PlayersSettings(offset, clean(title, MAX_TITLE, DEFAULT_TITLE), clean(welcome, MAX_WELCOME, DEFAULT_WELCOME), revision);
    }

    /** Typed text without formatting codes or control characters, cut to the limit; empty is the default. */
    public static String clean(@Nullable String s, int max, String fallback) {
        String t = s == null ? "" : s.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").strip();
        if (t.length() > max) {
            t = t.substring(0, max).strip();
        }
        return t.isEmpty() ? fallback : t;
    }

    public PlayersSettings withOffset(int o) {
        return new PlayersSettings(o, title, welcome, revision);
    }

    public PlayersSettings withTitle(String t) {
        return new PlayersSettings(offset, t, welcome, revision);
    }

    public PlayersSettings withWelcome(String w) {
        return new PlayersSettings(offset, title, w, revision);
    }

    public PlayersSettings withRevision(long r) {
        return new PlayersSettings(offset, title, welcome, r);
    }

    /** The number shown for a real count: the real count plus the adjustment, never below 0 (D170, D181). */
    public int shown(int real) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) real + offset));
    }

    public String encode() {
        return String.join(SEP, Integer.toString(offset), title, welcome, Long.toString(revision));
    }

    @Nullable
    public static PlayersSettings decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 4) {
            return null;
        }
        try {
            return new PlayersSettings(Integer.parseInt(f[0]), f[1], f[2], Long.parseLong(f[3])).sanitised();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("offset", offset);
        t.putString("title", title);
        t.putString("welcome", welcome);
        t.putLong("revision", revision);
        return t;
    }

    public static PlayersSettings load(CompoundTag t) {
        if (t.isEmpty()) {
            return DEFAULTS;
        }
        return new PlayersSettings(t.getInt("offset"), t.getString("title"), t.getString("welcome"), t.getLong("revision")).sanitised();
    }
}
