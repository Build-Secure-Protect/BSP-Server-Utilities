package com.mrgregles.bsp_staff_panel.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Authenticator codes (owner decision D248): RFC 6238 time-based codes, 6 digits every 30 seconds with HMAC-SHA1, which
 * every authenticator app reads from the usual {@code otpauth://} QR code. A code is accepted for the 30 seconds before
 * and after its own, and never twice: the step it matched is kept and only later steps pass.
 */
public final class Totp {
    public static final int DIGITS = 6, PERIOD_SECONDS = 30, SECRET_BYTES = 20, WINDOW = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new secret in Base32, as the apps want it. */
    public static String newSecret() {
        byte[] key = new byte[SECRET_BYTES];
        RANDOM.nextBytes(key);
        return Base32.encode(key);
    }

    public static long step(long nowMs) {
        return Math.floorDiv(nowMs / 1000, PERIOD_SECONDS);
    }

    /** The code for a step (RFC 4226 dynamic truncation), as a number. */
    public static int code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(new byte[]{(byte) (step >>> 56), (byte) (step >>> 48), (byte) (step >>> 40), (byte) (step >>> 32),
                    (byte) (step >>> 24), (byte) (step >>> 16), (byte) (step >>> 8), (byte) step});
            int o = h[h.length - 1] & 0x0F;
            int bin = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return bin % 1_000_000;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is missing from this Java", e);
        }
    }

    public static String format(int code) {
        return String.format("%06d", code);
    }

    /**
     * The step the code belongs to, or -1: it must be within {@link #WINDOW} steps of now and later than
     * {@code lastStep} (so a code that worked once never works again).
     */
    public static long verify(String secretBase32, String typed, long nowMs, long lastStep) {
        if (typed == null) {
            return -1;
        }
        String digits = typed.replaceAll("\\s", "");
        if (!digits.matches("\\d{6}")) {
            return -1;
        }
        byte[] key;
        try {
            key = Base32.decode(secretBase32);
        } catch (IllegalArgumentException e) {
            return -1;
        }
        if (key.length == 0) {
            return -1; // a secret that could not be opened: no code matches, backup codes still can (audit)
        }
        long now = step(nowMs), found = -1;
        byte[] want = digits.getBytes(StandardCharsets.US_ASCII);
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            boolean match = MessageDigest.isEqual(format(code(key, s)).getBytes(StandardCharsets.US_ASCII), want);
            if (match && s > lastStep && found < 0) {
                found = s; // keep looping: every step costs the same, whichever matched
            }
        }
        return found;
    }

    /** The {@code otpauth://} address the QR code holds. */
    public static String uri(String issuer, String account, String secretBase32) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer=" + enc(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
