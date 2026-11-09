package com.mrgregles.bsp_staff_panel.web;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Players' heads for the panel's pages: the face (and the hat layer over it) cut from the player's skin, 64 pixels
 * square, served from the panel's own address so the pages load nothing from other sites. The skin's address comes from
 * the game servers' feed and must be Mojang's {@code textures.minecraft.net}; the panel fetches it once and keeps the
 * last 500 heads. A player without one gets nothing (the page draws a plain face).
 */
public final class Heads {
    public static final int SIZE = 64, KEEP = 500, MAX_BYTES = 64 * 1024;
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "panel-heads-watchdog");
        t.setDaemon(true);
        return t;
    });
    private final Function<String, byte[]> fetch;
    private final Map<String, byte[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > KEEP;
        }
    };

    /** Skins that could not be had are not fetched again for ten minutes; one fetch per skin at a time (audit). */
    public static final long RETRY_MS = 600_000L;
    private final Map<String, Long> failed = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<byte[]>> fetching = new java.util.concurrent.ConcurrentHashMap<>();

    public Heads(Function<String, byte[]> fetch) {
        this.fetch = fetch;
    }

    /** Fetching from Mojang over HTTPS, at most 64 KB, 5 seconds. */
    public static Heads fromMojang() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new Heads(url -> {
            try {
                HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                java.io.InputStream in = r.body();
                // the whole fetch within 10 s: a body trickled slowly is cut off (audit)
                java.util.concurrent.ScheduledFuture<?> cut = WATCHDOG.schedule(() -> {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // closing anyway
                    }
                }, 10, java.util.concurrent.TimeUnit.SECONDS);
                try (in) {
                    if (r.statusCode() != 200) {
                        return null;
                    }
                    byte[] b = in.readNBytes(MAX_BYTES + 1); // never more than 64 KB read (audit)
                    return b.length <= MAX_BYTES ? b : null;
                } finally {
                    cut.cancel(false);
                }
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
    }

    public static boolean allowed(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI u = URI.create(url);
            return "https".equals(u.getScheme()) && "textures.minecraft.net".equals(u.getHost()) && u.getPort() == -1
                    && u.getRawPath() != null && u.getRawPath().matches("/texture/[0-9a-fA-F]{20,80}") && u.getRawQuery() == null && u.getRawUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The head as PNG bytes for a skin address, or empty. */
    public Optional<byte[]> head(String skinUrl) {
        if (!allowed(skinUrl)) {
            return Optional.empty();
        }
        synchronized (cache) {
            byte[] have = cache.get(skinUrl);
            if (have != null) {
                return Optional.of(have);
            }
        }
        Long until = failed.get(skinUrl);
        if (until != null && until > System.currentTimeMillis()) {
            return Optional.empty();
        }
        java.util.concurrent.CompletableFuture<byte[]> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<byte[]> other = fetching.putIfAbsent(skinUrl, mine);
        if (other != null) {
            try {
                return Optional.ofNullable(other.get(12, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
        byte[] png = null;
        try {
            byte[] skin = fetch.apply(skinUrl);
            png = skin == null ? null : cut(skin);
            if (png != null) {
                synchronized (cache) {
                    cache.put(skinUrl, png);
                }
                failed.remove(skinUrl);
            } else {
                if (failed.size() > 5000) {
                    failed.clear();
                }
                failed.put(skinUrl, System.currentTimeMillis() + RETRY_MS);
            }
        } finally {
            mine.complete(png);
            fetching.remove(skinUrl);
        }
        return Optional.ofNullable(png);
    }

    /** The face (8x8 at 8,8) with the hat layer (8x8 at 40,8) over it, scaled to 64 without smoothing. */
    static byte[] cut(byte[] skinPng) {
        try {
            BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinPng));
            if (skin == null || skin.getWidth() != 64 || (skin.getHeight() != 64 && skin.getHeight() != 32)) {
                return null;
            }
            BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            int scale = SIZE / 8;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int face = skin.getRGB(8 + x, 8 + y) | 0xFF000000;
                    int hat = skin.getRGB(40 + x, 8 + y);
                    int c = (hat >>> 24) >= 128 ? hat | 0xFF000000 : face;
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            out.setRGB(x * scale + dx, y * scale + dy, c);
                        }
                    }
                }
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(out, "png", b);
            return b.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
