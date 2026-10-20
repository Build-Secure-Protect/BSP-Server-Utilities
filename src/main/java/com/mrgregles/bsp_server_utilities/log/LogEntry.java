package com.mrgregles.bsp_server_utilities.log;

import org.jetbrains.annotations.Nullable;

/**
 * One action in the log (owner decisions D25 to D27): when, on which server, who (their name, role and its colour;
 * "Console" or "RCON" for the server itself), what kind of action, and the full text (players see it with coordinates
 * hidden, {@link LogText#masked}). {@code id} is 0 until the database numbers it; {@code uid} names it across servers.
 */
public record LogEntry(long id, String uid, long atMs, String server, String actorId, String actor, String roleGroup, String roleLabel, int colour, String kind, String text) {
    public static final String CMD = "cmd", ITEM = "item", MODE = "mode", GIVE = "give", SWITCH = "switch";
    /** Every kind, in the order the Action log pane offers them. */
    public static final java.util.List<String> KINDS = java.util.List.of(CMD, ITEM, MODE, GIVE, SWITCH);
    private static final String SEP = "\u001f";

    public String encode() {
        return String.join(SEP, uid, Long.toString(atMs), server, actorId, actor, roleGroup, roleLabel, Integer.toString(colour), kind, text);
    }

    @Nullable
    public static LogEntry decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 10) {
            return null;
        }
        try {
            return new LogEntry(0, f[0], Long.parseLong(f[1]), f[2], f[3], f[4], f[5], f[6], Integer.parseInt(f[7]), f[8], f[9]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
