package com.mrgregles.bsp_staff_panel;

import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.AccessKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first lock (D248): Cloudflare Access's signed token, checked on every request. */
class AccessJwtTest {
    private static final String ISS = "https://bsp.cloudflareaccess.com", AUD = "aud-tag-1234";
    private static final long NOW = 1_800_000_000L;
    private static KeyPair team, stranger;
    private static Map<String, RSAPublicKey> keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        team = g.generateKeyPair();
        stranger = g.generateKeyPair();
        keys = AccessKeys.parse(jwks("k1", (RSAPublicKey) team.getPublic()));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String jwks(String kid, RSAPublicKey k) {
        byte[] n = k.getModulus().toByteArray();
        if (n[0] == 0) {
            n = java.util.Arrays.copyOfRange(n, 1, n.length);
        }
        return "{\"keys\":[{\"kid\":\"" + kid + "\",\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"e\":\"" + b64(k.getPublicExponent().toByteArray())
                + "\",\"n\":\"" + b64(n) + "\"},{\"kid\":\"ec\",\"kty\":\"EC\"}],\"public_cert\":{}}";
    }

    private static String token(String header, String body, KeyPair signer) throws Exception {
        String head = b64(header) + "." + b64(body);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signer.getPrivate());
        s.update(head.getBytes(StandardCharsets.US_ASCII));
        return head + "." + b64(s.sign());
    }

    private static String body(String iss, String aud, long exp, String email) {
        return "{\"aud\":[\"" + aud + "\"],\"email\":\"" + email + "\",\"exp\":" + exp + ",\"iat\":" + (NOW - 10) + ",\"nbf\":" + (NOW - 10)
                + ",\"iss\":\"" + iss + "\",\"type\":\"app\",\"sub\":\"abc\"}";
    }

    private static final String HEAD = "{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}";

    private static AccessJwt.Identity check(String jwt) throws AccessJwt.Invalid {
        return AccessJwt.verify(jwt, keys::get, ISS, AUD, NOW);
    }

    @Test
    void a_good_token_gives_the_email_in_lower_case() throws Exception {
        AccessJwt.Identity id = check(token(HEAD, body(ISS, AUD, NOW + 3600, "Ana@Example.com"), team));
        assertEquals("ana@example.com", id.email());
        assertEquals(NOW + 3600, id.expiresAt());
        String single = "{\"aud\":\"" + AUD + "\",\"email\":\"b@x.io\",\"exp\":" + (NOW + 60) + ",\"iss\":\"" + ISS + "\"}";
        assertEquals("b@x.io", check(token(HEAD, single, team)).email(), "aud may be a single string");
    }

    @Test
    void every_wrong_token_is_refused() throws Exception {
        assertThrows(AccessJwt.Invalid.class, () -> check(null), "no header");
        assertThrows(AccessJwt.Invalid.class, () -> check("a.b"), "not three parts");
        assertThrows(AccessJwt.Invalid.class, () -> check(token(HEAD, body(ISS, AUD, NOW + 3600, "a@b.c"), stranger)), "signed by another key");
        assertThrows(AccessJwt.Invalid.class, () -> check(token("{\"alg\":\"RS256\",\"kid\":\"k9\"}", body(ISS, AUD, NOW + 3600, "a@b.c"), team)), "unknown key id");
        assertThrows(AccessJwt.Invalid.class, () -> check(token(HEAD, body("https://evil.cloudflareaccess.com", AUD, NOW + 3600, "a@b.c"), team)), "another team");
        assertThrows(AccessJwt.Invalid.class, () -> check(token(HEAD, body(ISS, "other-app", NOW + 3600, "a@b.c"), team)), "another application");
        assertThrows(AccessJwt.Invalid.class, () -> check(token(HEAD, body(ISS, AUD, NOW - 120, "a@b.c"), team)), "expired");
        assertThrows(AccessJwt.Invalid.class, () -> check(token(HEAD, body(ISS, AUD, NOW + 3600, ""), team)), "no email");
        String good = token(HEAD, body(ISS, AUD, NOW + 3600, "a@b.c"), team);
        String[] p = good.split("\\.");
        assertThrows(AccessJwt.Invalid.class, () -> check(p[0] + "." + b64(body(ISS, AUD, NOW + 3600, "boss@b.c")) + "." + p[2]), "a changed body");
        assertThrows(AccessJwt.Invalid.class, () -> check(b64("{\"alg\":\"none\",\"kid\":\"k1\"}") + "." + p[1] + "."), "no signature (alg none)");
        assertThrows(AccessJwt.Invalid.class, () -> check(b64("{\"alg\":\"HS256\",\"kid\":\"k1\"}") + "." + p[1] + "." + p[2]), "another signing method");
        String future = "{\"aud\":\"" + AUD + "\",\"email\":\"a@b.c\",\"exp\":" + (NOW + 9999) + ",\"nbf\":" + (NOW + 600) + ",\"iss\":\"" + ISS + "\"}";
        assertThrows(AccessJwt.Invalid.class, () -> check(token(HEAD, future, team)), "not valid yet");
        assertNotNull(check(token(HEAD, body(ISS, AUD, NOW - 30, "a@b.c"), team)), "a minute's clock difference is allowed");
    }

    @Test
    void the_team_keys_are_fetched_again_for_an_unknown_key_at_most_once_a_minute() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000_000L);
        String[] served = {jwks("k1", (RSAPublicKey) team.getPublic())};
        AccessKeys k = new AccessKeys(() -> {
            fetches.incrementAndGet();
            return served[0];
        }, clock::get);
        assertNotNull(k.key("k1"));
        assertEquals(1, fetches.get());
        assertNotNull(k.key("k1"));
        assertEquals(1, fetches.get(), "known keys are kept");
        assertNull(k.key("k2"));
        assertEquals(1, fetches.get(), "an unknown key within a minute of the last fetch does not fetch again");
        served[0] = jwks("k2", (RSAPublicKey) stranger.getPublic());
        clock.addAndGet(AccessKeys.RETRY_MS);
        assertNotNull(k.key("k2"), "Cloudflare rotated its keys: fetched again");
        assertEquals(2, fetches.get());
        clock.addAndGet(AccessKeys.REFRESH_MS + 1);
        k.key("k2");
        assertEquals(3, fetches.get(), "and every hour");
        AccessKeys broken = new AccessKeys(() -> {
            throw new IllegalStateException("HTTP 500");
        }, clock::get);
        assertNull(broken.key("k1"));
        assertTrue(broken.lastError().contains("500"));
    }
}
