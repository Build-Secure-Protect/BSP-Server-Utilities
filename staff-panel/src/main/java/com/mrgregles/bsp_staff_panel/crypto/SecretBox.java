package com.mrgregles.bsp_staff_panel.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The panel's own key (32 random bytes in its config file, never in the database): seals what the database must not
 * hold in the clear, so a copy of the database alone gives nothing away.
 * <ul>
 *     <li>{@link #seal} / {@link #open}: AES-256-GCM, for the authenticator secrets, which the panel must read back.</li>
 *     <li>{@link #fingerprint}: HMAC-SHA256, for things only ever compared (backup codes, session and link tokens).</li>
 * </ul>
 */
public final class SecretBox {
    public static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12, TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec aes, hmac;

    public SecretBox(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("the panel's key must be " + KEY_BYTES + " bytes");
        }
        aes = new SecretKeySpec(key, "AES");
        hmac = new SecretKeySpec(key, "HmacSHA256");
    }

    public static byte[] newKey() {
        byte[] k = new byte[KEY_BYTES];
        RANDOM.nextBytes(k);
        return k;
    }

    public String seal(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM failed", e);
        }
    }

    /** The sealed text, or an exception when it was changed or sealed with another key. */
    public String open(String sealed) throws GeneralSecurityException {
        byte[] all;
        try {
            all = Base64.getDecoder().decode(sealed);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("not sealed text");
        }
        if (all.length <= IV_BYTES) {
            throw new GeneralSecurityException("too short");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
        return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    }

    /** A keyed fingerprint in hex (64 characters). */
    public String fingerprint(String value) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(hmac);
            return HexFormat.of().formatHex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 failed", e);
        }
    }
}
