package com.mrgregles.bsp_server_utilities.streamer;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Streamer Mode's rules, as plain arithmetic so they are checked without a client (owner decisions D700 to D714): what
 * the stream picture leaves out, where the eye key stands, when a stream frame is due and how the picture fits the stream
 * window. An item is an id: a Forge HUD part's id ({@code minecraft:debug_text}), an adapter's id
 * ({@code journeymap:minimap}), or {@code screen:} and a class name or a package ending in a dot (every menu in it).
 */
public final class StreamerRules {
    public static final String SCREEN = "screen:";
    public static final int EYE = 14, MARGIN = 2, GAP = 2;
    /** Spout's library shipped in the jar for the Spout2 output (D717): its version, its SHA-256 and where it is. */
    public static final String SPOUT_VERSION = "2.007.018",
            SPOUT_SHA256 = "c76ac3382618cc6d71851a4bc6c8a41768d75d2cd386962fc2e3b901efd0d923",
            SPOUT_RESOURCE = "/natives/windows-x64/SpoutLibrary.dll";

    /** Hidden when Streamer Mode is first switched on (D707): {@link StreamerCatalog#DEFAULT_HIDDEN}. */
    public static final Set<String> DEFAULT_HIDDEN = Set.copyOf(StreamerCatalog.DEFAULT_HIDDEN);

    private StreamerRules() {
    }

    /** Whether this item is left out of the stream picture. */
    public static boolean hides(Set<String> hidden, String id) {
        return id != null && hidden.contains(id);
    }

    /** Whether a menu of this class is left out (shown as the "Menu hidden" card): by its class, or a package it is in. */
    public static boolean screenHidden(Set<String> hidden, @Nullable String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        for (String id : hidden) {
            if (!id.startsWith(SCREEN)) {
                continue;
            }
            String name = id.substring(SCREEN.length());
            if (name.endsWith(".") ? className.startsWith(name) : className.equals(name) || className.startsWith(name + "$")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the open menu is a "Menu hidden" card in the stream: Streamer Mode's own menu always (it shows the stream
     * itself), a menu hidden by its class or package, and with "Other mods' menus" on every menu not Minecraft's own.
     */
    public static boolean menuHidden(Set<String> hidden, @Nullable String className) {
        return menuHidden(hidden, Set.of(), className);
    }

    /**
     * The same, and an item with a fallback menu ({@link StreamerCatalog.Item#fallbackScreen}) that the streamer hid and
     * its mod has not yet asked about ({@code handled}, through {@code api/StreamerMode}) hides that whole menu in its place.
     */
    public static boolean menuHidden(Set<String> hidden, Set<String> handled, @Nullable String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        if (className.equals(StreamerCatalog.OWN_MENU) || screenHidden(hidden, className)
                || hidden.contains(StreamerCatalog.OTHER_MENUS) && !StreamerCatalog.vanillaMenu(className)) {
            return true;
        }
        for (StreamerCatalog.Item i : StreamerCatalog.withFallback()) {
            String f = i.fallbackScreen();
            if (hidden.contains(i.id()) && !handled.contains(i.id()) && (className.equals(f) || className.startsWith(f + "$"))) {
                return true;
            }
        }
        return false;
    }

    /** A number as positions are written: signed, maybe with decimals, or Minecraft's relative {@code ~} ({@code ~}, {@code ~5}). */
    private static final String NUM = "(?:-?\\d+(?:\\.\\d+)?|~-?\\d*(?:\\.\\d+)?)";
    /**
     * Two numbers with a comma between ("120, -340", "x: 1, y: 2 (3), z: 4", "[1234, ~, -560]"; an axis with ":" or "=" may
     * come before the second, a "(light)" between them). A comma straight before exactly three digits is a thousands
     * separator ("1,000 coins"), not a position.
     */
    private static final java.util.regex.Pattern LISTED = java.util.regex.Pattern.compile(
            "(?<![\\w.])" + NUM + "(?:\\s*\\(-?\\d+\\))?\\s*,(?!\\d{3}(?![\\d.]))\\s*(?:[xyz]\\s*[:=]?\\s*)?" + NUM,
            java.util.regex.Pattern.CASE_INSENSITIVE);
    /** Two numbers each after an axis, with ":", "=" or a space ("x:0 z:-1", "x=100, z=-200", "X 100 Y 64 Z -200"). */
    private static final java.util.regex.Pattern LABELLED = java.util.regex.Pattern.compile(
            "\\b[xyz]\\s*[:=]?\\s*" + NUM + "(?![\\d.]).*?\\b[xyz]\\s*[:=]?\\s*" + NUM + "(?![\\d.])", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** Three numbers with only spaces between ("/tp 100 64 -200", "/tp @s 100 ~ -200"). */
    private static final java.util.regex.Pattern SPACED = java.util.regex.Pattern.compile(
            "(?<![\\w.~-])" + NUM + "\\s+" + NUM + "\\s+" + NUM + "(?![\\w.])");
    /** Three numbers with "/" or ";" between ("100/64/-200", "XYZ: 100.5 / 64 / -200.5"). */
    private static final java.util.regex.Pattern SLASHED = java.util.regex.Pattern.compile(
            "(?<![\\w.])" + NUM + "\\s*[/;]\\s*" + NUM + "\\s*[/;]\\s*" + NUM + "(?![\\w.])");

    /**
     * Whether a line of text holds coordinates (D715, D716; widened in the audit of SU-135): two numbers with a comma between,
     * two numbers each after an axis, or three numbers with spaces, "/" or ";" between; "~" counts as a number. JourneyMap's
     * location line in every form ({@code jm.common.location_*}), its region line, Minecraft's "Teleported to 100.5, 64.0,
     * -200.5" and "/locate"'s "[1234, ~, -560]", a typed "/tp 100 64 -200", "x=100, z=-200" and "100/64/-200" match; times
     * ("12:34"), a day, a light level, a speed ("4.3 blocks/sec"), a biome, a frame rate or "1,000 coins" do not.
     */
    public static boolean hasCoordinates(@Nullable String text) {
        return text != null && !text.isEmpty() && (LISTED.matcher(text).find() || LABELLED.matcher(text).find()
                || SPACED.matcher(text).find() || SLASHED.matcher(text).find());
    }

    /**
     * Whether the chat box's text stays out of the stream (audit of SU-135): always with chat hidden; with chat's coordinates
     * hidden, while it is a command (its arguments may be a position typed in part, "/tp 100 64") or holds a position.
     */
    public static boolean hideTyping(boolean chatHidden, boolean coordsHidden, @Nullable String typed) {
        if (chatHidden) {
            return true;
        }
        return coordsHidden && typed != null && (typed.startsWith("/") || hasCoordinates(typed));
    }

    /**
     * Which of chat's lines stay in the stream when messages with coordinates are left out (D716). {@code texts} and
     * {@code ends} are chat's wrapped lines as Minecraft keeps them, newest first, each message's last line marked as its
     * end: a message is its marked line and the lines after it up to the next mark. A message is left out whole when its
     * lines, joined, hold coordinates, so wrapping never lets part of a position through.
     */
    public static boolean[] keptChatLines(List<String> texts, List<Boolean> ends) {
        int n = texts.size();
        boolean[] keep = new boolean[n];
        int start = 0;
        while (start < n) {
            int end = start + 1;
            while (end < n && !ends.get(end)) {
                end++;
            }
            StringBuilder message = new StringBuilder();
            for (int i = end - 1; i >= start; i--) { // oldest line first
                message.append(texts.get(i)).append(' ');
            }
            boolean show = !hasCoordinates(message.toString());
            for (int i = start; i < end; i++) {
                keep[i] = show;
            }
            start = end;
        }
        return keep;
    }

    /**
     * The eye key's place ({x, y}) in the top-right corner of a screen {@code screenW} wide, stepping left past anything in
     * the way ({x, y, width, height} each); null when it would have to go past the middle.
     */
    @Nullable
    public static int[] eyeKey(List<int[]> blocked, int screenW) {
        int x = screenW - MARGIN - EYE, y = MARGIN;
        for (int tries = 0; tries < 256; tries++) {
            if (x < screenW / 2) {
                return null;
            }
            int[] hit = null;
            for (int[] b : blocked) {
                if (x < b[0] + b[2] && x + EYE > b[0] && y < b[1] + b[3] && y + EYE > b[1]) {
                    hit = b;
                    break;
                }
            }
            if (hit == null) {
                return new int[]{x, y};
            }
            x = Math.min(x - 1, hit[0] - GAP - EYE);
        }
        return null;
    }

    /** Whether Spout2 can run here (D717): Windows on Intel or AMD, 64-bit (Spout's library is that build only). */
    public static boolean spoutAvailable(@Nullable String osName, @Nullable String arch) {
        if (osName == null || arch == null) {
            return false;
        }
        String a = arch.toLowerCase(java.util.Locale.ROOT);
        return osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows") && (a.equals("amd64") || a.equals("x86_64"));
    }

    /** Whether a stream frame is due at {@code now} (nanoseconds) after one at {@code last}: every frame at 0, else at most {@code fps} a second. */
    public static boolean due(long now, long last, int fps) {
        if (fps <= 0 || last == 0) {
            return true;
        }
        long step = 1_000_000_000L / fps;
        return now - last >= step - step / 20; // a twentieth early is in time, so 60 on a 60 Hz game does not drop frames
    }

    /** The picture {@code sw} x {@code sh} fitted into a window {@code dw} x {@code dh}, whole and centred: {x, y, width, height}. */
    public static int[] fit(int sw, int sh, int dw, int dh) {
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
            return new int[]{0, 0, 0, 0};
        }
        int w = dw, h = (int) Math.round((double) dw * sh / sw);
        if (h > dh) {
            h = dh;
            w = (int) Math.round((double) dh * sw / sh);
        }
        return new int[]{(dw - w) / 2, (dh - h) / 2, w, h};
    }
}
