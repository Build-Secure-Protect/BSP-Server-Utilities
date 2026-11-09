package com.mrgregles.bsp_staff_panel.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The Cloudflare Access team's signing keys, from {@code https://<team>.cloudflareaccess.com/cdn-cgi/access/certs}
 * (a JSON Web Key Set). Fetched at the start, again every hour, and at once (at most once a minute) when a token names
 * a key not seen yet, as happens when Cloudflare rotates its keys.
 */
public final class AccessKeys {
    public static final long REFRESH_MS = 3_600_000L, RETRY_MS = 60_000L;
    private final Supplier<String> fetch;
    private final LongSupplier clock;
    private volatile Map<String, RSAPublicKey> keys = Map.of();
    private volatile long fetchedAt, triedAt = Long.MIN_VALUE / 2;
    private volatile String lastError = "";

    public AccessKeys(Supplier<String> fetch, LongSupplier clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    /** Keys read over HTTPS from the team's address. */
    public static AccessKeys forTeam(String teamDomain) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        URI uri = URI.create("https://" + teamDomain + "/cdn-cgi/access/certs");
        return new AccessKeys(() -> {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + r.statusCode());
                }
                return r.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }, System::currentTimeMillis);
    }

    /** The key by its {@code kid}, refreshing when it is unknown or the set is old; null when there is none. */
    public RSAPublicKey key(String kid) {
        long now = clock.getAsLong();
        RSAPublicKey k = keys.get(kid);
        if ((k == null || now - fetchedAt > REFRESH_MS) && now - triedAt >= RETRY_MS) {
            refresh();
            k = keys.get(kid);
        }
        return k;
    }

    public synchronized void refresh() {
        triedAt = clock.getAsLong();
        try {
            Map<String, RSAPublicKey> got = parse(fetch.get());
            if (!got.isEmpty()) {
                keys = got;
                fetchedAt = triedAt;
                lastError = "";
            }
        } catch (RuntimeException e) {
            lastError = String.valueOf(e.getMessage());
        }
    }

    public int count() {
        return keys.size();
    }

    public String lastError() {
        return lastError;
    }

    /** The RSA keys of a JSON Web Key Set by {@code kid}; other kinds of key are skipped. */
    public static Map<String, RSAPublicKey> parse(String json) {
        Map<String, RSAPublicKey> out = new HashMap<>();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("keys")) {
            return out;
        }
        for (JsonElement e : root.getAsJsonObject().getAsJsonArray("keys")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject k = e.getAsJsonObject();
            if (!k.has("kid") || !k.has("n") || !k.has("e") || !"RSA".equals(k.has("kty") ? k.get("kty").getAsString() : "")) {
                continue;
            }
            try {
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.get("n").getAsString()));
                BigInteger ex = new BigInteger(1, Base64.getUrlDecoder().decode(k.get("e").getAsString()));
                out.put(k.get("kid").getAsString(), (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, ex)));
            } catch (Exception bad) {
                // a key we cannot read is left out
            }
        }
        return Map.copyOf(out);
    }
}
