package com.mrgregles.bsp_server_utilities.log;

import org.jetbrains.annotations.Nullable;

/**
 * One action in the log (owner decisions D25 to D27): when, on which server, who (their name, role and its colour;
 * "Console" or "RCON" for the server itself), what kind of action, and the full text (players see it with coordinates
 * hidden, {@link LogText#masked}). {@code id} is 0 until the database numbers it; {@code uid} names it across servers.
 */
public record LogEntry(long id, String uid, long atMs, String server, String actorId, String actor, String roleGroup, String roleLabel, int colour, String kind, String text) {
    public static final String CMD = "cmd", ITEM = "item", MODE = "mode", GIVE = "give", SWITCH = "switch", CHECK = "check", AFK = "afk", ROOM = "room", MOB = "mob", DROPS = "drops", BANK = "bank", BOUNTY = "bounty", TUTORIAL = "tutorial", CHUNK_TP = "chunk_tp", LAG_SAMPLE = "lag_sample", REGION_EDIT = "region_edit", REGION_CREATE = "region_create", REGION_REMOVE = "region_remove",
            SHOP_CREATE = "shop_create", SHOP_EDIT = "shop_edit", SHOP_COPY = "shop_copy", SHOP_DELETE = "shop_delete", SHOP_ON = "shop_on", SHOP_OFF = "shop_off",
            KIT_CREATE = "kit_create", KIT_EDIT = "kit_edit", KIT_COPY = "kit_copy", KIT_DELETE = "kit_delete", KIT_ON = "kit_on", KIT_OFF = "kit_off", KIT_GIVE = "kit_give", KIT_RESET = "kit_reset";
    /** Every kind, in the order the Action log pane offers them. */
    public static final java.util.List<String> KINDS = java.util.List.of(CMD, ITEM, MODE, GIVE, SWITCH, CHECK, AFK, ROOM, MOB, DROPS, BANK, BOUNTY, TUTORIAL, CHUNK_TP, LAG_SAMPLE, REGION_EDIT, REGION_CREATE, REGION_REMOVE,
            SHOP_CREATE, SHOP_EDIT, SHOP_COPY, SHOP_DELETE, SHOP_ON, SHOP_OFF, KIT_CREATE, KIT_EDIT, KIT_COPY, KIT_DELETE, KIT_ON, KIT_OFF, KIT_GIVE, KIT_RESET);
    /** The kinds players read (on panels, {@code /actionlog}, the board): the client check's refusals and kicks, and failed AFK checks, are for staff only (D142, D213). */
    public static final java.util.List<String> PUBLIC_KINDS = java.util.List.of(CMD, ITEM, MODE, GIVE, SWITCH);
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
