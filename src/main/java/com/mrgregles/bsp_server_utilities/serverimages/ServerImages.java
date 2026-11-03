package com.mrgregles.bsp_server_utilities.serverimages;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.ServerImageListPacket;
import com.mrgregles.bsp_server_utilities.network.ServerImagePacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupService;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Server images in chat, on the server (owner decisions D189 to D204).
 *
 * <ul>
 *   <li>An admin puts one PNG per server in {@value #FOLDER} on any server, named as Velocity knows the server
 *   ({@code BSP-Spawn.png}), and presses REFRESH (or {@code /su serverimages refresh}): the folder's usable files become
 *   the network's whole set (D196), kept here and, with shared storage, in the network's tables with a notice to the
 *   other servers; a file left out removes that server's image. Without shared storage each server has its own (D204).</li>
 *   <li>Each file is checked (D192): a PNG, not animated, at most 512 x 64 pixels and 256 KB, and at most 96 pixels wide
 *   once drawn one chat line high. A file wider than {@value #MAX_SHEET} pixels is scaled down to that width: the game
 *   puts font glyphs on sheets of 256 x 256 pixels and draws a wider one as an empty box.</li>
 *   <li>A refresh that would replace or remove an image in use is only planned until it is confirmed (SURE?, D201, D203);
 *   one that only adds goes through. Each change is written to the Action Log.</li>
 *   <li>Every player is sent the list (server name to image id) and each image's bytes once per login, and again when the
 *   set changes; turned off in the setup guide (D198), the list is sent empty, so every tag reads as its text.</li>
 * </ul>
 */
public final class ServerImages {
    public static final String FOLDER = "bsp_server_utilities/server_images";
    public static final String KIND_SET = "server_images";
    /** The largest file taken (D192): 512 x 64 pixels and 256 KB. */
    public static final int MAX_WIDTH = 512, MAX_HEIGHT = 64, MAX_BYTES = 256 * 1024;
    /** Drawn one chat line high, at most 96 pixels wide (D192). */
    public static final int LINE = 9, MAX_DRAWN_WIDTH = 96;
    /** The most files read from the folder, and the most images in a set. */
    public static final int MAX_FILES = 64;
    /** The widest image kept: the size of the game's font sheets (a wider glyph does not fit and is drawn as a box). */
    public static final int MAX_SHEET = 256;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.+-]{1,64}");
    private static final Pattern ID = Pattern.compile("[0-9a-f]{40}");

    /**
     * A file in the folder: the server it names, the file's name, its size in pixels and bytes, its id (the hash) and what
     * is wrong with it (empty when it can be used).
     */
    public record FileInfo(String server, String file, int width, int height, int bytes, String id, String problem) {
        public boolean usable() {
            return problem.isEmpty();
        }
    }

    /** What a refresh would do, or did: the servers whose image is added, replaced or removed, and whether it was applied. */
    public record Plan(List<String> added, List<String> replaced, List<String> removed, boolean applied) {
        public boolean changes() {
            return !added.isEmpty() || !replaced.isEmpty() || !removed.isEmpty();
        }

        /** Whether it needs a second click (D201): it would replace or remove an image in use. */
        public boolean needsConfirm() {
            return !replaced.isEmpty() || !removed.isEmpty();
        }
    }

    /** The images each player has been sent since they logged in. */
    private static final Map<UUID, Set<String>> SENT = new HashMap<>();
    /** Images asked of the shared storage and not answered yet. */
    private static final Set<String> FETCHING = new HashSet<>();
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static Path testFolder;
    private static BiConsumer<ServerPlayer, Object> sender = ServerUtilsNetwork::sendTo;
    /** Each file as last read, by its path with its size and time: a file that has not changed is not read or decoded again. */
    private record Cached(long size, java.nio.file.attribute.FileTime modified, Read read) {
    }

    private static Map<String, Cached> cache = Map.of();

    private ServerImages() {
    }

    // ------------------------------------------------------------------ reading

    public static boolean off() {
        return Switches.off(Feature.SERVERS);
    }

    public static ServerImageSet set(MinecraftServer server) {
        return ServerImagesData.get(server).set();
    }

    /** An image's bytes as kept here, or null. */
    @Nullable
    public static byte[] bytes(MinecraftServer server, String id) {
        return ServerImagesData.get(server).bytes(id);
    }

    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    public static boolean validId(String id) {
        return ID.matcher(id).matches();
    }

    public static Path folder() {
        return testFolder != null ? testFolder : FMLPaths.CONFIGDIR.get().resolve(FOLDER);
    }

    // ------------------------------------------------------------------ the folder

    /** A folder file read with its bytes (when usable). */
    public record Read(FileInfo info, @Nullable byte[] png) {
    }

    /** Reads the folder (making it when missing): every PNG, sorted by name, checked; a second file for the same server is refused. */
    public static List<Read> scan() {
        Path dir = folder();
        List<Read> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            List<Path> paths = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{png,PNG}")) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p)) {
                        paths.add(p);
                    }
                }
            }
            paths.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
            Set<String> taken = new HashSet<>();
            Map<String, Cached> seen = new HashMap<>();
            for (Path p : paths.subList(0, Math.min(MAX_FILES, paths.size()))) {
                String file = p.getFileName().toString();
                String server = file.substring(0, file.length() - 4);
                Read r;
                try {
                    long size = Files.size(p);
                    java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(p);
                    String key = p.toAbsolutePath().toString();
                    Cached before = cache.get(key);
                    if (before != null && before.size() == size && before.modified().equals(modified)) {
                        r = before.read();
                    } else if (size > MAX_BYTES) {
                        r = new Read(new FileInfo(server, file, 0, 0, (int) Math.min(Integer.MAX_VALUE, size), "", "over 256 KB"), null);
                    } else {
                        byte[] png = Files.readAllBytes(p);
                        FileInfo info = check(server, file, png);
                        r = info.usable() ? fitSheet(new Read(info, png)) : new Read(info, null);
                    }
                    seen.put(key, new Cached(size, modified, r));
                } catch (IOException | RuntimeException e) {
                    r = new Read(new FileInfo(server, file, 0, 0, 0, "", "unreadable"), null);
                }
                if (r.info().usable() && !taken.add(server.toLowerCase(Locale.ROOT))) {
                    FileInfo i = r.info();
                    r = new Read(new FileInfo(i.server(), i.file(), i.width(), i.height(), i.bytes(), "", "a second file for " + i.server()), null);
                }
                out.add(r);
            }
            cache = Map.copyOf(seen);
        } catch (IOException e) {
            BSPServerUtilities.LOGGER.warn("Server Utilities could not read {}: {}", dir, e.toString());
        }
        return out;
    }

    /**
     * Checks a file against D192: a usable server name, a PNG, not animated, at most 512 x 64 pixels and 256 KB, at most
     * 96 pixels wide once drawn one line high, and readable. Returns its size and hash, or the problem in a few words.
     */
    public static FileInfo check(String server, String file, byte[] png) {
        if (!validName(server)) {
            return new FileInfo(server, file, 0, 0, png.length, "", "not a server name");
        }
        if (png.length > MAX_BYTES) {
            return new FileInfo(server, file, 0, 0, png.length, "", "over 256 KB");
        }
        if (png.length < 33 || !Arrays.equals(png, 0, 8, PNG, 0, 8)) {
            return new FileInfo(server, file, 0, 0, png.length, "", "not a PNG");
        }
        ByteBuffer buf = ByteBuffer.wrap(png);
        int width = buf.getInt(16), height = buf.getInt(20);
        // an animated PNG has an acTL chunk before its image data
        int at = 8;
        while (at + 8 <= png.length) {
            int length = buf.getInt(at);
            String type = new String(png, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("acTL")) {
                return new FileInfo(server, file, width, height, png.length, "", "animated");
            }
            if (length < 0 || length > png.length - at - 12) {
                return new FileInfo(server, file, width, height, png.length, "", "broken");
            }
            if (type.equals("IDAT")) {
                break;
            }
            at += 12 + length;
        }
        if (width <= 0 || height <= 0) {
            return new FileInfo(server, file, width, height, png.length, "", "broken");
        }
        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            return new FileInfo(server, file, width, height, png.length, "", width + " x " + height + " is over 512 x 64");
        }
        if (drawnWidth(width, height) > MAX_DRAWN_WIDTH) {
            return new FileInfo(server, file, width, height, png.length, "", "too wide: " + drawnWidth(width, height) + " px in chat, over 96");
        }
        try {
            if (ImageIO.read(new ByteArrayInputStream(png)) == null) {
                return new FileInfo(server, file, width, height, png.length, "", "broken");
            }
        } catch (IOException | RuntimeException e) {
            return new FileInfo(server, file, width, height, png.length, "", "broken");
        }
        return new FileInfo(server, file, width, height, png.length, idOf(png), "");
    }

    /**
     * A usable file wider than {@value #MAX_SHEET} pixels scaled down to that width (its height in proportion) and kept as
     * a new PNG under its own hash; any other file as it is.
     */
    static Read fitSheet(Read r) {
        FileInfo i = r.info();
        if (r.png() == null || i.width() <= MAX_SHEET) {
            return r;
        }
        try {
            java.awt.image.BufferedImage src = ImageIO.read(new ByteArrayInputStream(r.png()));
            int w = MAX_SHEET, h = Math.max(1, Math.round((float) i.height() * MAX_SHEET / i.width()));
            java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = dst.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ImageIO.write(dst, "png", out);
            byte[] png = out.toByteArray();
            return new Read(new FileInfo(i.server(), i.file(), w, h, png.length, idOf(png), ""), png);
        } catch (IOException | RuntimeException e) {
            return new Read(new FileInfo(i.server(), i.file(), i.width(), i.height(), i.bytes(), "", "broken"), null);
        }
    }

    /** How wide an image is drawn one chat line high. */
    public static int drawnWidth(int width, int height) {
        return height <= 0 ? 0 : (int) Math.ceil((double) width * LINE / height);
    }

    /** An image's id: the SHA-1 of its bytes, 40 hex digits. */
    public static String idOf(byte[] png) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(png));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ refreshing (D196, D201, D203)

    /** What a refresh from these files would change in the set. */
    public static Plan plan(ServerImageSet now, List<Read> files) {
        List<String> added = new ArrayList<>(), replaced = new ArrayList<>(), removed = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        for (Read r : files) {
            if (!r.info().usable()) {
                continue;
            }
            kept.add(r.info().server().toLowerCase(Locale.ROOT));
            ServerImageSet.Entry e = now.of(r.info().server());
            if (e == null) {
                added.add(r.info().server());
            } else if (!e.id().equals(r.info().id())) {
                replaced.add(e.name());
            }
        }
        for (ServerImageSet.Entry e : now.entries()) {
            if (!kept.contains(e.name().toLowerCase(Locale.ROOT))) {
                removed.add(e.name());
            }
        }
        return new Plan(List.copyOf(added), List.copyOf(replaced), List.copyOf(removed), false);
    }

    /**
     * REFRESH: reads the folder and makes it the set. When that would replace or remove an image in use and is not
     * confirmed, nothing changes and the plan says what would (D201). {@code by} is null for the console.
     */
    public static Plan refresh(MinecraftServer server, @Nullable ServerPlayer by, String byName, boolean confirmed) {
        List<Read> files = scan();
        ServerImageSet now = set(server);
        Plan plan = plan(now, files);
        if (!plan.changes() || (plan.needsConfirm() && !confirmed)) {
            return plan;
        }
        SetupService.touched(server, Feature.SERVERS);
        ServerImagesData data = ServerImagesData.get(server);
        List<ServerImageSet.Entry> entries = new ArrayList<>();
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (Read r : files) {
            if (r.info().usable() && r.png() != null) {
                FileInfo i = r.info();
                entries.add(new ServerImageSet.Entry(i.server(), i.id(), i.file(), i.width(), i.height(), i.bytes()));
                bytes.put(i.id(), r.png());
                data.put(i.id(), r.png());
            }
        }
        ServerImageSet next = new ServerImageSet(entries, Math.max(clock.getAsLong(), now.revision() + 1));
        data.setSet(next);
        Storage st = Storage.active();
        if (st != null) {
            // the notice only after the bytes are saved: through Redis it would otherwise reach the others first, and they
            // would ask the table for images not in it yet
            st.submit(() -> {
                st.backend().saveServerImages(next, bytes);
                st.publish(KIND_SET, next.encode());
            });
        }
        sendToAll(server);
        log(server, by, byName, describe(plan));
        return new Plan(plan.added(), plan.replaced(), plan.removed(), true);
    }

    /** The Action Log's words for a refresh: "refreshed the server images: added BSP-Spawn; removed BSP-Test". */
    public static String describe(Plan p) {
        List<String> parts = new ArrayList<>();
        if (!p.added().isEmpty()) {
            parts.add("added " + String.join(", ", p.added()));
        }
        if (!p.replaced().isEmpty()) {
            parts.add("replaced " + String.join(", ", p.replaced()));
        }
        if (!p.removed().isEmpty()) {
            parts.add("removed " + String.join(", ", p.removed()));
        }
        return "refreshed the server images: " + (parts.isEmpty() ? "nothing changed" : String.join("; ", parts));
    }

    private static void log(MinecraftServer server, @Nullable ServerPlayer by, String byName, String text) {
        LogEntry e;
        if (by != null) {
            Optional<Staff.Role> role = ActionLog.tracked(by);
            e = ActionLog.entryFor(by, role.orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.CMD, text);
        } else {
            e = ActionLog.entryForServer(byName, LogEntry.CMD, text);
        }
        ActionLog.record(server, e);
    }

    /** Takes a shared set when it is newer, fetches the images it lacks, and tells every player. */
    public static void apply(MinecraftServer server, ServerImageSet shared) {
        ServerImagesData data = ServerImagesData.get(server);
        if (shared.revision() <= data.set().revision()) {
            return;
        }
        data.setSet(shared);
        fetchMissing(server);
        sendToAll(server);
    }

    /** Asks the shared storage for every image in the set this server lacks; each goes to everyone on arrival. */
    public static void fetchMissing(MinecraftServer server) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        for (String id : set(server).ids()) {
            if (ServerImagesData.get(server).has(id) || !FETCHING.add(id)) {
                continue;
            }
            s.submit(() -> {
                byte[] png = s.backend().loadServerImage(id);
                server.execute(() -> {
                    FETCHING.remove(id);
                    if (png == null) {
                        BSPServerUtilities.LOGGER.warn("Server image {} is in the set but not in the shared storage; its server reads as text until the next REFRESH", id);
                    } else if (!idOf(png).equals(id)) {
                        BSPServerUtilities.LOGGER.warn("Server image {} came back changed from the shared storage; not used", id);
                    } else if (set(server).ids().contains(id)) {
                        ServerImagesData.get(server).put(id, png);
                        sendToAll(server);
                    }
                });
            });
        }
    }

    // ------------------------------------------------------------------ sending to players

    /** The list every player gets: each server with an image here, or nothing when the feature is off (D198). */
    public static ServerImageListPacket list(MinecraftServer server) {
        if (off()) {
            return new ServerImageListPacket(false, List.of());
        }
        List<ServerImageListPacket.Item> items = new ArrayList<>();
        for (ServerImageSet.Entry e : set(server).entries()) {
            items.add(new ServerImageListPacket.Item(e.name(), e.id()));
        }
        return new ServerImageListPacket(true, items);
    }

    /** At login: the list, then each image not sent this login. */
    public static void sendTo(ServerPlayer player) {
        sender.accept(player, list(player.server));
        if (!off()) {
            for (String id : set(player.server).ids()) {
                sendImage(player, id);
            }
        }
    }

    /** Sends an image to a player once per login; false when this server does not have it. */
    public static boolean sendImage(ServerPlayer player, String id) {
        if (SENT.getOrDefault(player.getUUID(), Set.of()).contains(id)) {
            return true;
        }
        byte[] png = bytes(player.server, id);
        if (png == null) {
            return false;
        }
        SENT.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(id);
        sender.accept(player, new ServerImagePacket(id, png));
        return true;
    }

    /** Sends an admin the folder's usable files, for the SERVERS tab's previews before a refresh. */
    public static void sendFolderImages(ServerPlayer admin, List<Read> files) {
        for (Read r : files) {
            if (r.info().usable() && r.png() != null && SENT.computeIfAbsent(admin.getUUID(), k -> new HashSet<>()).add(r.info().id())) {
                sender.accept(admin, new ServerImagePacket(r.info().id(), r.png()));
            }
        }
    }

    private static void sendToAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendTo(p);
        }
    }

    public static void onLogin(ServerPlayer player) {
        SENT.remove(player.getUUID());
        fetchMissing(player.server); // a fetch that came back empty is tried again
        sendTo(player);
    }

    public static void onLogout(ServerPlayer player) {
        SENT.remove(player.getUUID());
    }

    /** The setup guide turned the feature off or on: everyone's list follows. */
    public static void switched(MinecraftServer server) {
        sendToAll(server);
    }

    /** Whether a player was sent an image this login (tests). */
    public static boolean wasSent(UUID player, String id) {
        return SENT.getOrDefault(player, Set.of()).contains(id);
    }

    // ------------------------------------------------------------------ lifecycle

    public static void registerNotices() {
        Storage.on(KIND_SET, (srv, n) -> {
            ServerImageSet s = ServerImageSet.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
    }

    public static void onServerStarted(MinecraftServer server) {
        SENT.clear();
        FETCHING.clear();
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        s.submit(() -> {
            ServerImageSet shared = s.backend().loadServerImages();
            if (shared == null) {
                ServerImageSet ours = set(server);
                if (!ours.entries().isEmpty()) {
                    Map<String, byte[]> bytes = new LinkedHashMap<>();
                    ours.ids().forEach(id -> {
                        byte[] png = bytes(server, id);
                        if (png != null) {
                            bytes.put(id, png);
                        }
                    });
                    s.backend().saveServerImages(ours, bytes);
                }
            } else {
                server.execute(() -> {
                    apply(server, shared);
                    fetchMissing(server);
                });
            }
        });
    }

    // ------------------------------------------------------------------ tests

    /** A test clock, folder (null: the config folder) and where packets go (null: to the player). */
    public static void useForTests(LongSupplier testClock, @Nullable Path folder, @Nullable BiConsumer<ServerPlayer, Object> packets) {
        clock = testClock;
        testFolder = folder;
        sender = packets != null ? packets : ServerUtilsNetwork::sendTo;
    }

    public static void useReal() {
        useForTests(System::currentTimeMillis, null, null);
    }
}
