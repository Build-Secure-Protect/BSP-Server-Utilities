package com.mrgregles.bsp_staff_panel.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The panel's own signing key (D426): an Ed25519 private key only the panel holds ({@code signingKey} in
 * {@code panel.properties}), and its public key ({@code panelKey}), which every game server keeps as
 * {@code webPanel.panelKey}. Actions and the notes notice are signed with it, so nothing on a game server (its files,
 * another mod there) can order an action: a game server can only check.
 */
public final class PanelSigner {
    private final PrivateKey key;

    /** A new pair: {private key as PKCS#8 in Base64, public key as its 32 raw bytes in Base64}. */
    public static String[] newPair() {
        try {
            KeyPair p = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] spki = p.getPublic().getEncoded();
            return new String[]{Base64.getEncoder().encodeToString(p.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length))};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** From the private key as PKCS#8 bytes; IllegalArgumentException when they are not an Ed25519 key. */
    public PanelSigner(byte[] pkcs8) {
        try {
            key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an Ed25519 private key", e);
        }
    }

    /** {@code json} as the game servers read it: its URL-safe Base64, a dot, the Ed25519 signature of that text in URL-safe Base64. */
    public String sign(String json) {
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(body.getBytes(StandardCharsets.US_ASCII));
            return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
