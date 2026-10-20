package com.mrgregles.bsp_server_utilities.image;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.network.ImageChunkPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlock;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlockEntity;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import com.mrgregles.bsp_server_utilities.panel.PanelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Pictures for Display Panels, on the server (owner decisions D18 to D21, D49). The server fetches a web address (http
 * or https, within the size and time limits) or reads a file from {@code config/bsp_server_utilities/images/}, reads it
 * (PNG, JPEG, the first frame of a GIF), scales it down to what the panel can show, and keeps it as a PNG under its hash.
 * The panel's settings carry the hash; players' clients ask for the bytes by hash and get them in pieces, so players
 * never contact the address. Loading runs on one background thread; the result is written to the panel on the server
 * thread, and only if the panel still shows the same source.
 */
public final class ImageService {
    public static final String FOLDER = "bsp_server_utilities/images";
    /** A picture wider or taller than this is refused before it is decoded. */
    public static final int MAX_SOURCE_SIDE = 8192;
    public static final int CHUNK = 32 * 1024;
    private static final Set<String> FILE_TYPES = Set.of("png", "jpg", "jpeg", "gif");

    /** A picture ready to send: the PNG bytes and what the panel records about it. */
    public record Picture(byte[] png, PanelSettings.Loaded loaded) {
    }

    /** A file in the images folder, for the settings screen's list. */
    public record FolderFile(String name, long bytes, int width, int height) {
    }

    private static final LinkedHashMap<String, byte[]> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static long cachedBytes;
    private static final Map<String, Set<UUID>> WAITING = new HashMap<>();
    /** Where a picture with a given hash is shown, so a player's request after a restart can load it again. */
    private static final Map<String, GlobalPos> SHOWN_AT = new HashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    /** Panels with a refresh timer: a block of the panel, and when it is next due (wall clock). */
    private static final Map<GlobalPos, Long> REFRESH = new HashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bsp-su-images");
        t.setDaemon(true);
        return t;
    });
    private static long lastTimerCheck;

    private ImageService() {
    }

    // ------------------------------------------------------------------ reading a picture

    /**
     * Reads a picture and scales it down so it is no bigger than a panel of {@code wBlocks} by {@code hBlocks} needs at
     * {@code pxPerBlock} pixels a block (enough to cover the panel, never more than the panel's longer side), then makes
     * it a PNG. Never scales up.
     */
    public static Picture process(byte[] bytes, int wBlocks, int hBlocks, int pxPerBlock) throws ImageException {
        BufferedImage img;
        String format;
        int sw, sh;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new ImageException("not_an_image", "");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                format = reader.getFormatName().toUpperCase(Locale.ROOT);
                sw = reader.getWidth(0);
                sh = reader.getHeight(0);
                if (sw <= 0 || sh <= 0) {
                    throw new ImageException("not_an_image", "");
                }
                if (sw > MAX_SOURCE_SIDE || sh > MAX_SOURCE_SIDE) {
                    throw new ImageException("too_many_pixels", sw + " x " + sh);
                }
                img = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new ImageException("not_an_image", e.getMessage() == null ? "" : e.getMessage());
        }
        double boundW = (double) wBlocks * pxPerBlock, boundH = (double) hBlocks * pxPerBlock;
        double s = Math.min(1.0, Math.min(Math.max(boundW / sw, boundH / sh), Math.max(boundW, boundH) / Math.max(sw, sh)));
        int nw = Math.max(1, (int) Math.round(sw * s)), nh = Math.max(1, (int) Math.round(sh * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(out, "png", png);
        } catch (IOException e) {
            throw new ImageException("not_an_image", e.getMessage() == null ? "" : e.getMessage());
        }
        byte[] data = png.toByteArray();
        return new Picture(data, new PanelSettings.Loaded(hash(data), format, sw, sh, bytes.length, nw, nh, System.currentTimeMillis(), "", ""));
    }

    public static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fetches a web address, refusing more than {@code maxBytes} and anything slower than {@code timeoutSeconds}. */
    public static byte[] fetch(String url, int maxBytes, int timeoutSeconds) throws ImageException {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new ImageException("bad_address", url);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ImageException("bad_address", url);
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds)).header("User-Agent", "BSP-Server-Utilities").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ImageException("http", Integer.toString(response.statusCode()));
                }
                return readLimited(body, maxBytes);
            }
        } catch (HttpTimeoutException e) {
            throw new ImageException("timed_out", Integer.toString(timeoutSeconds));
        } catch (IOException e) {
            throw new ImageException("unreachable", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageException("unreachable", "interrupted");
        }
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException, ImageException {
        byte[] data = in.readNBytes(maxBytes + 1);
        if (data.length > maxBytes) {
            throw new ImageException("too_big", Integer.toString(maxBytes / 1024));
        }
        return data;
    }

    /** The images folder ({@code config/bsp_server_utilities/images/}), made when missing. */
    public static Path folder() {
        Path p = FMLPaths.CONFIGDIR.get().resolve(FOLDER);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            BSPServerUtilities.LOGGER.warn("Could not make {}: {}", p, e.toString());
        }
        return p;
    }

    /** A file of the images folder by its name; empty for a name with a path in it or outside the folder. */
    public static Optional<Path> resolve(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return Optional.empty();
        }
        Path folder = folder().toAbsolutePath().normalize();
        Path p = folder.resolve(name).normalize();
        return p.startsWith(folder) && p.getParent().equals(folder) ? Optional.of(p) : Optional.empty();
    }

    public static byte[] readFile(String name, int maxBytes) throws ImageException {
        Path p = resolve(name).orElseThrow(() -> new ImageException("no_file", name == null ? "" : name));
        try {
            if (!Files.isRegularFile(p)) {
                throw new ImageException("no_file", name);
            }
            if (Files.size(p) > maxBytes) {
                throw new ImageException("too_big", Integer.toString(maxBytes / 1024));
            }
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ImageException("no_file", name);
        }
    }

    /** The PNG, JPEG and GIF files in the images folder with their size and pixel size, by name (at most 200). */
    public static List<FolderFile> folderFiles() {
        List<FolderFile> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder())) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (out.size() >= 200 || dot < 0 || !FILE_TYPES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)) || !Files.isRegularFile(p) || resolve(name).isEmpty()) {
                    continue;
                }
                int w = 0, h = 0;
                try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
                    Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
                    if (readers != null && readers.hasNext()) {
                        ImageReader r = readers.next();
                        r.setInput(in, true, true);
                        w = r.getWidth(0);
                        h = r.getHeight(0);
                        r.dispose();
                    }
                } catch (IOException | RuntimeException ignored) {
                    // listed without its size
                }
                out.add(new FolderFile(name, Files.size(p), w, h));
            }
        } catch (IOException e) {
            BSPServerUtilities.LOGGER.warn("Could not list {}: {}", FOLDER, e.toString());
        }
        return out;
    }

    /** Loads the picture a panel's settings ask for, at once on the calling thread (the worker, or a test). */
    public static Picture loadNow(PanelSettings.Image image, int wBlocks, int hBlocks) throws ImageException {
        int max = ServerUtilsConfig.getOr(ServerUtilsConfig.IMAGES_MAX_DOWNLOAD_KB, 4096) * 1024;
        byte[] bytes = switch (image.source()) {
            case WEB -> {
                if (image.url().isEmpty()) {
                    throw new ImageException("no_source", "");
                }
                yield fetch(image.url(), max, ServerUtilsConfig.getOr(ServerUtilsConfig.IMAGES_TIMEOUT_SECONDS, 10));
            }
            case FOLDER -> {
                if (image.file().isEmpty()) {
                    throw new ImageException("no_source", "");
                }
                yield readFile(image.file(), max);
            }
        };
        return process(bytes, wBlocks, hBlocks, ServerUtilsConfig.getOr(ServerUtilsConfig.IMAGES_PIXELS_PER_BLOCK, 128));
    }

    // ------------------------------------------------------------------ the cache

    public static synchronized void cache(String hash, byte[] png) {
        if (CACHE.containsKey(hash)) {
            CACHE.get(hash);
            return;
        }
        CACHE.put(hash, png);
        cachedBytes += png.length;
        long limit = ServerUtilsConfig.getOr(ServerUtilsConfig.IMAGES_CACHE_MB, 64) * 1024L * 1024L;
        Iterator<Map.Entry<String, byte[]>> it = CACHE.entrySet().iterator();
        while (cachedBytes > limit && it.hasNext()) {
            Map.Entry<String, byte[]> eldest = it.next();
            if (eldest.getKey().equals(hash)) {
                continue;
            }
            cachedBytes -= eldest.getValue().length;
            it.remove();
        }
    }

    @Nullable
    public static synchronized byte[] cached(String hash) {
        return CACHE.get(hash);
    }

    // ------------------------------------------------------------------ panels

    private static String sourceKey(PanelSettings.Image image, int w, int h) {
        return image.source() + "|" + (image.source() == PanelSettings.Source.WEB ? image.url() : image.file()) + "|" + w + "x" + h;
    }

    /**
     * Loads the picture of the panel at {@code pos} in the background (unless the same source is loading already), then
     * writes what came of it to every block of the panel, if the panel still asks for the same source.
     */
    public static void load(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DisplayPanelBlock)) {
            return;
        }
        Direction facing = state.getValue(DisplayPanelBlock.FACING);
        PanelShape.Rect rect = PanelShape.rectOf(level, pos, facing);
        PanelSettings settings = PanelShape.sync(level, pos, facing);
        if (settings.mode() != PanelSettings.Mode.IMAGE) {
            return;
        }
        PanelSettings.Image image = settings.image();
        String key = sourceKey(image, rect.width(), rect.height());
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(key)) {
                return;
            }
        }
        MinecraftServer server = level.getServer();
        WORKER.execute(() -> {
            Picture picture = null;
            PanelSettings.Loaded loaded;
            try {
                picture = loadNow(image, rect.width(), rect.height());
                loaded = picture.loaded();
            } catch (ImageException e) {
                loaded = PanelSettings.Loaded.failed(e.key(), e.detail());
            } catch (RuntimeException | LinkageError e) {
                loaded = PanelSettings.Loaded.failed("not_an_image", e.toString());
            }
            Picture done = picture;
            PanelSettings.Loaded result = loaded;
            server.execute(() -> {
                synchronized (IN_FLIGHT) {
                    IN_FLIGHT.remove(key);
                }
                apply(level, pos, image, done, result);
            });
        });
    }

    /** Writes a load's result to the panel (server thread); public for tests that load with {@link #loadNow}. */
    public static void apply(ServerLevel level, BlockPos pos, PanelSettings.Image image, @Nullable Picture picture, PanelSettings.Loaded loaded) {
        if (picture != null) {
            cache(loaded.hash(), picture.png());
            SHOWN_AT.put(loaded.hash(), GlobalPos.of(level.dimension(), pos));
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DisplayPanelBlock)) {
            return;
        }
        Direction facing = state.getValue(DisplayPanelBlock.FACING);
        PanelSettings now = PanelShape.sync(level, pos, facing);
        if (!now.image().equals(image)) {
            return; // the admin chose something else meanwhile
        }
        PanelSettings.Loaded old = now.loaded();
        if (!old.hash().equals(loaded.hash()) || !old.error().equals(loaded.error()) || !old.detail().equals(loaded.detail()) || old.width() != loaded.width()) {
            PanelShape.save(level, pos, facing, now.withLoaded(loaded));
        }
        if (picture != null) {
            Set<UUID> waiting = WAITING.remove(loaded.hash());
            if (waiting != null) {
                for (UUID id : waiting) {
                    ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                    if (p != null) {
                        send(p, loaded.hash(), picture.png());
                    }
                }
            }
        }
    }

    /**
     * A panel block came into the world (its chunk loaded, or it was saved): remember where its picture shows, load it
     * again when the server no longer has its bytes (after a restart), and keep its refresh timer.
     */
    public static void track(ServerLevel level, BlockPos pos, PanelSettings settings) {
        GlobalPos at = GlobalPos.of(level.dimension(), pos);
        if (settings.mode() != PanelSettings.Mode.IMAGE) {
            REFRESH.remove(at);
            return;
        }
        if (settings.loaded().ok()) {
            SHOWN_AT.putIfAbsent(settings.loaded().hash(), at);
        }
        if (settings.image().source() == PanelSettings.Source.WEB && settings.image().refreshMinutes() > 0) {
            REFRESH.putIfAbsent(at, System.currentTimeMillis() + settings.image().refreshMinutes() * 60_000L);
        } else {
            REFRESH.remove(at);
        }
    }

    public static void untrack(ServerLevel level, BlockPos pos) {
        REFRESH.remove(GlobalPos.of(level.dimension(), pos));
    }

    /** Once a second: panels whose refresh is due load their picture again (one block of each panel is enough). */
    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastTimerCheck < 1000 || REFRESH.isEmpty()) {
            return;
        }
        lastTimerCheck = now;
        Set<String> started = new HashSet<>();
        for (Map.Entry<GlobalPos, Long> e : new ArrayList<>(REFRESH.entrySet())) {
            if (e.getValue() > now) {
                continue;
            }
            ServerLevel level = server.getLevel(e.getKey().dimension());
            BlockPos pos = e.getKey().pos();
            if (level == null || !level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity be)) {
                REFRESH.remove(e.getKey());
                continue;
            }
            PanelSettings s = be.settings();
            REFRESH.put(e.getKey(), now + Math.max(1, s.image().refreshMinutes()) * 60_000L);
            if (started.add(s.image().url())) {
                load(level, pos);
            }
        }
    }

    /** A player's client asks for a picture's bytes: sent now when the server has them, else once they are loaded again. */
    public static void request(ServerPlayer player, String hash) {
        byte[] png = cached(hash);
        if (png != null) {
            send(player, hash, png);
            return;
        }
        WAITING.computeIfAbsent(hash, k -> new HashSet<>()).add(player.getUUID());
        GlobalPos at = SHOWN_AT.get(hash);
        if (at != null) {
            ServerLevel level = player.server.getLevel(at.dimension());
            if (level != null && level.isLoaded(at.pos())) {
                load(level, at.pos());
            }
        }
    }

    /** Sends a picture in pieces of {@value #CHUNK} bytes. */
    public static void send(ServerPlayer player, String hash, byte[] png) {
        for (ImageChunkPacket p : ImageChunkPacket.split(hash, png)) {
            ServerUtilsNetwork.sendTo(player, p);
        }
    }

    /** Tests: when the panel block at {@code pos} next refreshes its picture (wall clock), or null when it has no timer. */
    @Nullable
    public static Long nextRefresh(ServerLevel level, BlockPos pos) {
        return REFRESH.get(GlobalPos.of(level.dimension(), pos));
    }

    public static void forget(UUID player) {
        WAITING.values().forEach(s -> s.remove(player));
    }
}
