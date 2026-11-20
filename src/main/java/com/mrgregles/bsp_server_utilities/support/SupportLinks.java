package com.mrgregles.bsp_server_utilities.support;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Whether a typed address may be a support link (owner decisions D603, D612): https only, a host name with a dot, no
 * spaces, no name and password, at most {@value #MAX} characters. Plain checks, the same on the server (which decides)
 * and in the SUPPORT tab (which shows the reason as it is typed). An empty address is allowed: it means no link.
 */
public final class SupportLinks {
    public static final int MAX = 256;

    private SupportLinks() {
    }

    /** Why an address is refused; {@link #OK} when it may be used. Each has a lang line {@code support.link.<key>}. */
    public enum Problem {
        OK("ok"), TOO_LONG("too_long"), SPACES("spaces"), HTTPS("https"), USER("user"), NOT_ADDRESS("not_address");

        private final String key;

        Problem(String key) {
            this.key = key;
        }

        /** The lang key of the line shown under the box: "A link players can open." or the reason. */
        public String langKey() {
            return "support.link." + key;
        }
    }

    /** The address as stored: surrounding spaces taken off, null as empty. */
    public static String tidy(@Nullable String typed) {
        return typed == null ? "" : typed.strip();
    }

    /** Checks a tidied address; empty is {@link Problem#OK} (no link). */
    public static Problem check(@Nullable String typed) {
        String s = tidy(typed);
        if (s.isEmpty()) {
            return Problem.OK;
        }
        if (s.length() > MAX) {
            return Problem.TOO_LONG;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i)) || Character.isSpaceChar(s.charAt(i))) {
                return Problem.SPACES;
            }
        }
        if (!s.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return Problem.HTTPS;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c == 0x7f || c == '§' || c == '\\' || c == '"' || c == '<' || c == '>' || c == '`' || Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT) { // zero-width and direction marks could disguise the address
                return Problem.NOT_ADDRESS;
            }
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return Problem.NOT_ADDRESS;
        }
        String authority = uri.getRawAuthority();
        if (authority != null && authority.contains("@")) {
            return Problem.USER;
        }
        String host = uri.getHost();
        if (host == null || !"https".equalsIgnoreCase(uri.getScheme()) || !validHost(host)) {
            return Problem.NOT_ADDRESS;
        }
        return Problem.OK;
    }

    /** Whether an address is allowed and not empty. */
    public static boolean usable(@Nullable String typed) {
        String s = tidy(typed);
        return !s.isEmpty() && check(s) == Problem.OK;
    }

    /** Labels of letters, digits and hyphens, separated by dots, at least two labels, none empty or edged with a hyphen. */
    private static boolean validHost(String host) {
        if (host.length() > 253 || host.startsWith(".") || host.endsWith(".") || !host.contains(".")) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-')) {
                    return false;
                }
            }
        }
        return true;
    }
}
