package com.mrgregles.bsp_server_utilities.tutorial;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.image.ImageException;
import com.mrgregles.bsp_server_utilities.image.ImageService;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Pictures in tutorial popups (SU-140; owner decision D726): a file from {@code config/bsp_server_utilities/tutorial_images/}
 * or a web address, read and scaled on a background thread with the Display Panels' rules, kept in the same cache under
 * its hash and sent to clients the same way (they ask by hash). A popup is sent with the hashes known so far; when a
 * picture finishes loading, the popups showing it are sent again.
 */
public final class TutorialImages {
    public static final String FOLDER = "bsp_server_utilities/tutorial_images";
    /** Pixels per grid cell a picture is kept at (a full popup's cell is about 40 GUI px; enough for GUI scale 4). */
    static final int PX_PER_CELL = 160;
    private static final Set<String> FILE_TYPES = Set.of("png", "jpg", "jpeg", "gif");
    private static final Map<String, String> HASH_OF = new HashMap<>();
    private static final Map<String, String> KEY_OF = new HashMap<>();
    private static final Map<String, TutorialPopup.Image> IMAGE_OF = new HashMap<>();
    private static final Map<String, int[]> CELLS_OF = new HashMap<>();
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Map<String, String> FAILED = new HashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bsp-su-tutorial-images");
        t.setDaemon(true);
        return t;
    });
    private static Runnable onLoaded = () -> {
    };

    private TutorialImages() {
    }

    /** Hooks into the picture service (requests for an evicted tutorial picture load it again) and sets who to tell when one loads. */
    public static void init(Runnable whenLoaded) {
        onLoaded = whenLoaded;
        ImageService.otherSource = hash -> {
            String key;
            synchronized (HASH_OF) {
                key = KEY_OF.get(hash);
            }
            if (key == null) {
                return false;
            }
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                synchronized (HASH_OF) {
                    HASH_OF.remove(key);
                }
                load(server, key);
            }
            return true;
        };
    }

    public static Path folder() {
        Path p = FMLPaths.CONFIGDIR.get().resolve(FOLDER);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            BSPServerUtilities.LOGGER.warn("Could not make {}: {}", p, e.toString());
        }
        return p;
    }

    /** The picture files in the folder, by name (at most 200), for the editor's list. */
    public static List<String> files() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder())) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (out.size() < 200 && dot > 0 && FILE_TYPES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)) && Files.isRegularFile(p)) {
                    out.add(name);
                }
            }
        } catch (IOException e) {
            BSPServerUtilities.LOGGER.warn("Could not list {}: {}", FOLDER, e.toString());
        }
        return out;
    }

    private static String key(TutorialPopup.Image image, int w, int h) {
        return image.source() + "|" + (image.source() == TutorialPopup.Source.WEB ? image.url() : image.file()) + "|" + w + "x" + h;
    }

    /** The hash of a box's picture when it is ready ("" while it loads or when it failed; loading starts here). */
    public static String hash(MinecraftServer server, TutorialPopup.Image image, int wCells, int hCells) {
        if (!image.isSet()) {
            return "";
        }
        String key = key(image, wCells, hCells);
        synchronized (HASH_OF) {
            String h = HASH_OF.get(key);
            if (h != null && ImageService.cached(h) != null) {
                return h;
            }
            IMAGE_OF.put(key, image);
            CELLS_OF.put(key, new int[]{wCells, hCells});
        }
        load(server, key);
        return "";
    }

    /** Why a picture failed to load, or "" (the editor's note under an image box). */
    public static String failure(TutorialPopup.Image image, int wCells, int hCells) {
        synchronized (HASH_OF) {
            return FAILED.getOrDefault(key(image, wCells, hCells), "");
        }
    }

    private static void load(MinecraftServer server, String key) {
        TutorialPopup.Image image;
        int[] cells;
        synchronized (HASH_OF) {
            image = IMAGE_OF.get(key);
            cells = CELLS_OF.get(key);
            if (image == null || cells == null || !IN_FLIGHT.add(key)) {
                return;
            }
        }
        WORKER.execute(() -> {
            ImageService.Picture picture = null;
            String error = "";
            try {
                picture = loadNow(image, cells[0], cells[1]);
            } catch (ImageException e) {
                error = e.key() + (e.detail().isEmpty() ? "" : ": " + e.detail());
            } catch (RuntimeException | LinkageError e) {
                error = "not_an_image";
            }
            ImageService.Picture done = picture;
            String why = error;
            server.execute(() -> {
                synchronized (HASH_OF) {
                    IN_FLIGHT.remove(key);
                    if (done != null) {
                        HASH_OF.put(key, done.loaded().hash());
                        KEY_OF.put(done.loaded().hash(), key);
                        FAILED.remove(key);
                    } else {
                        FAILED.put(key, why);
                    }
                }
                if (done != null) {
                    ImageService.delivered(server, done.loaded().hash(), done.png());
                    onLoaded.run();
                }
            });
        });
    }

    /** Reads and scales a picture at once on the calling thread (the worker, or a test). */
    public static ImageService.Picture loadNow(TutorialPopup.Image image, int wCells, int hCells) throws ImageException {
        int max = ServerUtilsConfig.getOr(ServerUtilsConfig.IMAGES_MAX_DOWNLOAD_KB, 4096) * 1024;
        byte[] bytes = image.source() == TutorialPopup.Source.WEB
                ? ImageService.fetch(image.url(), max, ServerUtilsConfig.getOr(ServerUtilsConfig.IMAGES_TIMEOUT_SECONDS, 10))
                : ImageService.readFileIn(folder(), image.file(), max);
        return ImageService.process(bytes, Math.max(1, wCells), Math.max(1, hCells), PX_PER_CELL);
    }
}
