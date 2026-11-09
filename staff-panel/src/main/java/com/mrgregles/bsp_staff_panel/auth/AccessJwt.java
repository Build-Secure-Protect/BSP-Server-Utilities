package com.mrgregles.bsp_staff_panel.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Function;

/**
 * The first lock (owner decision D248): Cloudflare Access puts a signed token in the {@code Cf-Access-Jwt-Assertion}
 * header of every request it lets through. The panel checks it on every request: RS256 only, signed by one of the team's
 * keys ({@link AccessKeys}), issued by the team's own address, meant for this application (its AUD tag), and not
 * expired. Its email is who Cloudflare says this is; the panel's own login must then be that same person.
 */
public final class AccessJwt {
    /** Clock difference allowed between Cloudflare and this machine. */
    public static final long LEEWAY_SECONDS = 60;

    public record Identity(String email, long expiresAt) {
    }

    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    private AccessJwt() {
    }

    /**
     * @param keyById the team's key for a {@code kid}, or null when it has none by that name
     * @param issuer  {@code https://<team>.cloudflareaccess.com}
     * @param aud     the application's AUD tag
     */
    public static Identity verify(String jwt, Function<String, RSAPublicKey> keyById, String issuer, String aud, long nowSeconds) throws Invalid {
        if (jwt == null || jwt.isBlank()) {
            throw new Invalid("no Cloudflare Access token");
        }
        String[] parts = jwt.trim().split("\\.", -1);
        if (parts.length != 3) {
            throw new Invalid("not a token");
        }
        JsonObject header = json(parts[0]), body = json(parts[1]);
        if (!"RS256".equals(str(header, "alg"))) {
            throw new Invalid("unexpected signing method");
        }
        String kid = str(header, "kid");
        RSAPublicKey key = kid == null ? null : keyById.apply(kid);
        if (key == null) {
            throw new Invalid("signed with an unknown key");
        }
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new Invalid("bad signature");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Invalid("bad signature");
        }
        if (!issuer.equals(str(body, "iss"))) {
            throw new Invalid("issued by someone else");
        }
        if (!audienceHas(body.get("aud"), aud)) {
            throw new Invalid("meant for another application");
        }
        long exp = num(body, "exp");
        if (exp <= 0 || nowSeconds > exp + LEEWAY_SECONDS) {
            throw new Invalid("expired");
        }
        long nbf = num(body, "nbf");
        if (nbf > 0 && nowSeconds + LEEWAY_SECONDS < nbf) {
            throw new Invalid("not valid yet");
        }
        String email = str(body, "email");
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new Invalid("no email in the token");
        }
        return new Identity(email.trim().toLowerCase(Locale.ROOT), exp);
    }

    private static boolean audienceHas(JsonElement aud, String want) {
        if (aud == null || want == null || want.isEmpty()) {
            return false;
        }
        if (aud.isJsonPrimitive()) {
            return want.equals(aud.getAsString());
        }
        if (aud.isJsonArray()) {
            for (JsonElement e : aud.getAsJsonArray()) {
                if (e.isJsonPrimitive() && want.equals(e.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonObject json(String part) throws Invalid {
        try {
            JsonElement e = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                throw new Invalid("not a token");
            }
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Invalid("not a token");
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static long num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
