package com.mrgregles.bsp_staff_panel;

import com.mrgregles.bsp_staff_panel.crypto.Base32;
import com.mrgregles.bsp_staff_panel.crypto.Passwords;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.crypto.Tokens;
import com.mrgregles.bsp_staff_panel.crypto.Totp;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Passwords, authenticator codes, the panel's key, tokens and backup codes (D248, D283). */
class CryptoTest {
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void authenticator_codes_match_rfc_6238() {
        // RFC 6238 appendix B, SHA-1, the last six of the eight digits
        assertEquals("287082", Totp.format(Totp.code(RFC_KEY, Totp.step(59_000L))));
        assertEquals("081804", Totp.format(Totp.code(RFC_KEY, Totp.step(1_111_111_109_000L))));
        assertEquals("050471", Totp.format(Totp.code(RFC_KEY, Totp.step(1_111_111_111_000L))));
        assertEquals("005924", Totp.format(Totp.code(RFC_KEY, Totp.step(1_234_567_890_000L))));
        assertEquals("279037", Totp.format(Totp.code(RFC_KEY, Totp.step(2_000_000_000_000L))));
    }

    @Test
    void a_code_works_thirty_seconds_either_side_and_never_twice() {
        String secret = Base32.encode(RFC_KEY);
        long now = 1_111_111_111_000L, step = Totp.step(now);
        String code = Totp.format(Totp.code(RFC_KEY, step));
        assertEquals(step, Totp.verify(secret, code, now, -1));
        assertEquals(step, Totp.verify(secret, "050 471", now, -1), "spaces are ignored");
        assertEquals(step, Totp.verify(secret, code, now + 30_000, -1), "the code before is still taken");
        assertEquals(-1, Totp.verify(secret, code, now + 61_000, -1), "but not two steps later");
        assertEquals(-1, Totp.verify(secret, code, now, step), "a code used once never works again");
        assertEquals(-1, Totp.verify(secret, "12345", now, -1));
        assertEquals(-1, Totp.verify(secret, "abcdef", now, -1));
        assertEquals(-1, Totp.verify("not base32!", code, now, -1));
    }

    @Test
    void base32_and_the_qr_address() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(RFC_KEY));
        assertTrue(java.util.Arrays.equals(RFC_KEY, Base32.decode("gezdgnbvgy3tqojqgezdgnbvgy3tqojq")));
        String s = Totp.newSecret();
        assertEquals(32, s.length());
        assertEquals(20, Base32.decode(s).length);
        String uri = Totp.uri("BSP Staff", "ana@example.com", s);
        assertTrue(uri.startsWith("otpauth://totp/BSP%20Staff:ana%40example.com?secret=" + s + "&issuer=BSP%20Staff"), uri);
    }

    @Test
    void passwords_are_hashed_with_a_salt_and_checked() {
        String h = Passwords.hash("correct horse battery", 1000);
        assertTrue(h.startsWith("pbkdf2$1000$"));
        assertTrue(Passwords.verify("correct horse battery", h));
        assertFalse(Passwords.verify("correct horse batterY", h));
        assertNotEquals(h, Passwords.hash("correct horse battery", 1000), "a new salt every time");
        assertFalse(Passwords.verify("x", "pbkdf2$1000$bad"));
        assertFalse(Passwords.verify("x", null));
        assertNotNull(Passwords.problem("short"));
        assertNotNull(Passwords.problem("aaaaaaaaaaaaaaaa"), "too few different characters");
        assertNull(Passwords.problem("frost-wolf-lantern-9"));
    }

    @Test
    void the_panel_key_seals_and_fingerprints() throws GeneralSecurityException {
        SecretBox box = new SecretBox(SecretBox.newKey()), other = new SecretBox(SecretBox.newKey());
        String sealed = box.seal("JBSWY3DPEHPK3PXP");
        assertNotEquals("JBSWY3DPEHPK3PXP", sealed);
        assertEquals("JBSWY3DPEHPK3PXP", box.open(sealed));
        assertThrows(GeneralSecurityException.class, () -> other.open(sealed), "another key cannot open it");
        String tampered = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThrows(GeneralSecurityException.class, () -> box.open(tampered), "a changed text is refused");
        assertEquals(box.fingerprint("abc"), box.fingerprint("abc"));
        assertNotEquals(box.fingerprint("abc"), other.fingerprint("abc"), "fingerprints depend on the key");
        assertEquals(64, box.fingerprint("abc").length());
        assertThrows(IllegalArgumentException.class, () -> new SecretBox(new byte[16]));
    }

    @Test
    void tokens_and_backup_codes() {
        assertEquals(43, Tokens.token().length());
        assertNotEquals(Tokens.token(), Tokens.token());
        List<String> codes = Tokens.backupCodes(10);
        assertEquals(10, new HashSet<>(codes).size());
        for (String c : codes) {
            assertTrue(c.matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}"), c);
        }
        assertEquals("K7QPM-3XWRT", Tokens.normalBackupCode(" k7qpm 3xwrt "));
        assertEquals("K7QPM-3XWRT", Tokens.normalBackupCode("K7QPM3XWRT"));
    }
}
