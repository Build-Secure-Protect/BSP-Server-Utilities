package com.mrgregles.bsp_staff_panel.crypto;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Staff passwords (owner decision D248): PBKDF2 with HMAC-SHA256 from the JDK, a random salt per password, stored as
 * {@code pbkdf2$<iterations>$<salt>$<hash>} so the cost can rise later without breaking old hashes. Checked in constant
 * time.
 */
public final class Passwords {
    public static final int ITERATIONS = 310_000, MIN_LENGTH = 12, MAX_LENGTH = 200;
    private static final int SALT_BYTES = 16, HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Passwords() {
    }

    public static String hash(String password) {
        return hash(password, ITERATIONS);
    }

    /** With a given cost; tests use a small one to stay quick. */
    public static String hash(String password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] h = derive(password, salt, iterations);
        Base64.Encoder b = Base64.getEncoder().withoutPadding();
        return "pbkdf2$" + iterations + "$" + b.encodeToString(salt) + "$" + b.encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] want = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(derive(password, salt, iterations), want);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Why a new password is refused, or null when it is fine. */
    public static String problem(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "at least " + MIN_LENGTH + " characters";
        }
        if (password.length() > MAX_LENGTH) {
            return "at most " + MAX_LENGTH + " characters";
        }
        if (password.chars().distinct().count() < 5) {
            return "more different characters";
        }
        return null;
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is missing from this Java", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
