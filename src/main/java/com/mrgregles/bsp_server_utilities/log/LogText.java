package com.mrgregles.bsp_server_utilities.log;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Command text helpers for the action log: the command's root, private messages, and coordinates hidden for players (D26). */
public final class LogText {
    /** Private messages: never logged and given no switch (D25, D30). */
    public static final Set<String> PRIVATE = Set.of("msg", "tell", "w", "r", "teammsg", "tm", "reply", "whisper", "m", "pm", "dm");
    public static final Set<String> MODE_COMMANDS = Set.of("gamemode", "defaultgamemode", "gm", "gmc", "gms", "gma", "gmsp");
    public static final Set<String> GIVE_COMMANDS = Set.of("give", "item", "i");
    /** A word that can be a coordinate: a number, or ~ / ^ with or without one. */
    private static final Pattern COORD = Pattern.compile("[~^]-?(?:\\d+(?:\\.\\d+)?)?|-?\\d+(?:\\.\\d+)?");

    private LogText() {
    }

    /** The command's first word, lower-cased, without a leading slash or a namespace ("minecraft:tp" is "tp"). */
    public static String root(String command) {
        String s = command.strip();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        int space = s.indexOf(' ');
        String first = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(colon + 1) : first;
    }

    /**
     * The text as players read it (D26): two or more coordinate-like words in a row (x y z, or x z) shown as "\u00b7" each.
     * A number on its own (a count, a level) stays.
     */
    public static String masked(String text) {
        String[] words = text.split(" ", -1);
        boolean[] coord = new boolean[words.length];
        for (int i = 0; i < words.length; i++) {
            coord[i] = !words[i].isEmpty() && COORD.matcher(words[i]).matches();
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            boolean inRun = coord[i] && ((i > 0 && coord[i - 1]) || (i + 1 < words.length && coord[i + 1]));
            if (i > 0) {
                b.append(' ');
            }
            b.append(inRun ? "\u00b7" : words[i]);
        }
        return b.toString();
    }
}
