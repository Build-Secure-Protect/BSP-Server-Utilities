package com.mrgregles.bsp_staff_panel.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * What the game servers send the panel is signed with the action key (BSP-Server Utilities' {@code WebActions.signText}):
 * the HMAC-SHA256 of the text's UTF-8 bytes in hex, a dot, then the text. Anything else is dropped, so someone with only
 * Redis or database access cannot show made-up players or a made-up answer (audit 2026-11-09).
 */
public final class Signed {
    private Signed() {
    }

    public static String seal(byte[] key, String text) {
        return HexFormat.of().formatHex(mac(key, text)) + "." + text;
    }

    /** The text when the signature holds, else null. */
    public static String open(byte[] key, String signed) {
        if (key == null || signed == null || signed.length() < 66 || signed.charAt(64) != '.') {
            return null;
        }
        byte[] sig;
        try {
            sig = HexFormat.of().parseHex(signed.substring(0, 64));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String text = signed.substring(65);
        return MessageDigest.isEqual(mac(key, text), sig) ? text : null;
    }

    private static byte[] mac(byte[] key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
