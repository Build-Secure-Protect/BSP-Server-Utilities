package com.mrgregles.bsp_staff_panel.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Random tokens for sessions and one-time links, and the backup codes staff can write down. */
public final class Tokens {
    /** Backup codes avoid letters and digits that look alike (0 and O, 1 and I). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** 32 random bytes as URL-safe Base64 (43 characters). */
    public static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** {@code n} backup codes like {@code K7QPM-3XWRT} (50 random bits each). */
    public static List<String> backupCodes(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder s = new StringBuilder(11);
            for (int j = 0; j < 10; j++) {
                if (j == 5) {
                    s.append('-');
                }
                s.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            out.add(s.toString());
        }
        return out;
    }

    /** A typed backup code in the form it was issued: capitals, the dash in the middle, spaces ignored. */
    public static String normalBackupCode(String typed) {
        String s = typed == null ? "" : typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return s.length() == 10 ? s.substring(0, 5) + "-" + s.substring(5) : s;
    }
}
