package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.check.ClientReport;
import com.mrgregles.bsp_server_utilities.network.CheckReportPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The client's side of the client check (owner decisions D120 to D127): what this client tells a server about itself.
 *
 * <ul>
 *   <li>Mods: every loaded mod with its version, and the SHA-1 of its file, read once on a background thread at start-up
 *   (the pack's jars take a few seconds to read; nothing waits on the game's thread).</li>
 *   <li>After every resource reload, on the game's thread once the loading screen has gone: the resource packs switched
 *   on, the stone test, the shader Oculus has chosen and the brightness. The stone test looks at common stone-type
 *   blocks: each must still have all six faces, draw in the solid layer only, and have textures that are not largely
 *   see-through; the first that fails is reported with the top pack that changed it.</li>
 *   <li>Packs' fingerprints are read on the background thread (kept while the file is unchanged).</li>
 *   <li>Asked during login, the report is put together on the background thread and sent as the answer; in game, a
 *   reload, or the brightness crossing the slider's top, sends it again.</li>
 * </ul>
 */
public final class ClientCheck {
    /** The blocks of the stone test: what an xray pack makes see-through. */
    private static final List<Block> STONE = List.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF,
            Blocks.CALCITE, Blocks.DIRT, Blocks.GRAVEL, Blocks.SAND, Blocks.COBBLESTONE, Blocks.NETHERRACK, Blocks.BLACKSTONE, Blocks.END_STONE);
    /** A texture this share see-through or more fails the stone test. */
    private static final double SEE_THROUGH = 0.25;
    private static final int WAIT_SECONDS = 15;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BSP-Server Utilities client check");
        t.setDaemon(true);
        return t;
    });
    /** Fingerprints by file, kept while the file's size and time are unchanged. */
    private static final Map<Path, String[]> FINGERPRINTS = new ConcurrentHashMap<>();

    /** What the game's thread saw after the last reload. */
    private record Snapshot(List<PackFile> packs, boolean stonePassed, String stoneBlock, String stonePack, String shader, boolean shadersOn) {
    }

    private record PackFile(String id, String name, Path file) {
    }

    private static volatile CompletableFuture<List<ClientReport.Mod>> mods;
    private static volatile Snapshot snapshot;
    private static volatile boolean reloaded = true;
    private static boolean wasBright;
    private static int ticks;

    private ClientCheck() {
    }

    /** Client set-up: start reading the mods' files. */
    public static void start() {
        mods = CompletableFuture.supplyAsync(ClientCheck::readMods, WORKER);
    }

    /** A resource reload finished applying (a reload listener). */
    public static void onReload() {
        reloaded = true;
    }

    /** Every client tick: after a reload, once the loading screen has gone, look again and tell the server. */
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (reloaded && mc.getOverlay() == null) {
            reloaded = false;
            snapshot = look(mc);
            sendInGame(mc);
        }
        if (++ticks % 20 == 0 && mc.player != null) {
            boolean bright = mc.options.gamma().get() > 1.0001;
            if (bright != wasBright) {
                wasBright = bright;
                sendInGame(mc);
            }
        }
    }

    private static void sendInGame(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null || !ServerUtilsNetwork.CHANNEL.isRemotePresent(mc.getConnection().getConnection())) {
            return;
        }
        double brightness = mc.options.gamma().get();
        WORKER.execute(() -> {
            ClientReport r = build(brightness);
            mc.execute(() -> {
                if (mc.getConnection() != null) {
                    ServerUtilsNetwork.CHANNEL.sendToServer(new CheckReportPacket(r));
                }
            });
        });
    }

    /** The server asked during login (on the network thread): answer from the background thread. */
    public static void answer(Consumer<ClientReport> reply) {
        Minecraft mc = Minecraft.getInstance();
        double brightness = mc.options.gamma().get();
        WORKER.execute(() -> {
            ClientReport r;
            try {
                r = build(brightness);
            } catch (RuntimeException ex) {
                BSPServerUtilities.LOGGER.error("Client check report failed", ex);
                r = ClientReport.EMPTY;
            }
            reply.accept(r);
        });
    }

    /** Puts the report together; on the background thread. */
    private static ClientReport build(double brightness) {
        List<ClientReport.Mod> m;
        try {
            m = mods != null ? mods.get(WAIT_SECONDS, TimeUnit.SECONDS) : readMods();
        } catch (Exception ex) {
            m = readMods(false);
        }
        Snapshot s = snapshot;
        if (s == null) {
            try {
                s = Minecraft.getInstance().submit(() -> look(Minecraft.getInstance())).get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ex) {
                s = new Snapshot(List.of(), true, "", "", "", false);
            }
        }
        List<ClientReport.Pack> packs = new ArrayList<>();
        for (PackFile p : s.packs()) {
            packs.add(new ClientReport.Pack(p.id(), p.name(), p.file() == null ? "" : fingerprint(p.file())));
        }
        return new ClientReport(m, packs, s.shader(), s.shadersOn(), s.stonePassed(), s.stoneBlock(), s.stonePack(), brightness);
    }

    // ------------------------------------------------------------------ mods

    private static List<ClientReport.Mod> readMods() {
        return readMods(true);
    }

    private static List<ClientReport.Mod> readMods(boolean withFiles) {
        List<ClientReport.Mod> out = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            String sha1 = "";
            if (withFiles) {
                try {
                    sha1 = fingerprint(info.getOwningFile().getFile().getFilePath());
                } catch (RuntimeException ignored) {
                    // no file to read
                }
            }
            out.add(new ClientReport.Mod(info.getModId(), info.getDisplayName(), info.getVersion().toString(), sha1));
        }
        out.sort(Comparator.comparing(ClientReport.Mod::id));
        return out;
    }

    // ------------------------------------------------------------------ after a reload, on the game's thread

    private static Snapshot look(Minecraft mc) {
        List<PackFile> packs = new ArrayList<>();
        Path dir = mc.getResourcePackDirectory();
        for (Pack p : mc.getResourcePackRepository().getSelectedPacks()) {
            String id = p.getId();
            if (id.equals("vanilla") || id.equals("mod_resources")) {
                continue;
            }
            Path file = id.startsWith("file/") ? dir.resolve(id.substring(5)) : null;
            packs.add(new PackFile(id, p.getTitle().getString(), file));
        }
        String failed = "", culprit = "";
        try {
            for (Block b : STONE) {
                if (!solid(mc, b.defaultBlockState())) {
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
                    failed = id.getPath();
                    culprit = culprit(mc, id);
                    break;
                }
            }
        } catch (RuntimeException ex) {
            BSPServerUtilities.LOGGER.warn("Client check stone test could not run", ex);
        }
        String[] shader = shader();
        return new Snapshot(List.copyOf(packs), failed.isEmpty(), failed, culprit, shader[0], shader[1].equals("true"));
    }

    /** All six faces, the solid layer only, and textures not largely see-through. */
    private static boolean solid(Minecraft mc, BlockState state) {
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        RandomSource rand = RandomSource.create(42L);
        ChunkRenderTypeSet types = model.getRenderTypes(state, rand, ModelData.EMPTY);
        if (!types.contains(RenderType.solid()) || types.asList().size() != 1) {
            return false;
        }
        Set<Direction> faces = EnumSet.noneOf(Direction.class);
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction d : Direction.values()) {
            rand.setSeed(42L);
            quads.addAll(model.getQuads(state, d, rand, ModelData.EMPTY, RenderType.solid()));
        }
        rand.setSeed(42L);
        quads.addAll(model.getQuads(state, null, rand, ModelData.EMPTY, RenderType.solid()));
        for (BakedQuad q : quads) {
            faces.add(q.getDirection());
            if (seeThrough(q.getSprite().contents()) >= SEE_THROUGH) {
                return false;
            }
        }
        return faces.size() == 6;
    }

    private static double seeThrough(SpriteContents c) {
        int step = Math.max(1, Math.max(c.width(), c.height()) / 64), seen = 0, clear = 0;
        for (int y = 0; y < c.height(); y += step) {
            for (int x = 0; x < c.width(); x += step) {
                seen++;
                if (c.isTransparent(0, x, y)) {
                    clear++;
                }
            }
        }
        return seen == 0 ? 1.0 : (double) clear / seen;
    }

    /** The top pack, other than the game's own, that gives the block's state, model or texture; its title. */
    private static String culprit(Minecraft mc, ResourceLocation block) {
        String ns = block.getNamespace(), path = block.getPath();
        String best = "";
        for (ResourceLocation at : List.of(ResourceLocation.fromNamespaceAndPath(ns, "blockstates/" + path + ".json"),
                ResourceLocation.fromNamespaceAndPath(ns, "models/block/" + path + ".json"), ResourceLocation.fromNamespaceAndPath(ns, "textures/block/" + path + ".png"))) {
            List<Resource> stack = mc.getResourceManager().getResourceStack(at);
            if (!stack.isEmpty()) {
                String id = stack.get(stack.size() - 1).sourcePackId();
                if (!id.equals("vanilla") && !id.equals("mod_resources")) {
                    best = id;
                }
            }
        }
        if (best.isEmpty()) {
            return "";
        }
        Pack p = mc.getResourcePackRepository().getPack(best);
        return p != null ? p.getTitle().getString() : best;
    }

    /** Oculus's chosen shader pack and whether shaders are on, from its settings file; empty when Oculus is not here. */
    private static String[] shader() {
        if (!ModList.get().isLoaded("oculus")) {
            return new String[] {"", "false"};
        }
        for (String name : List.of("oculus.properties", "iris.properties")) {
            Path f = FMLPaths.CONFIGDIR.get().resolve(name);
            if (Files.isRegularFile(f)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(f)) {
                    p.load(in);
                    return new String[] {p.getProperty("shaderPack", ""), p.getProperty("enableShaders", "false").strip()};
                } catch (Exception ignored) {
                    // unreadable: report none
                }
            }
        }
        return new String[] {"", "false"};
    }

    // ------------------------------------------------------------------ fingerprints

    /** SHA-1 of a file, or of a folder's files in name order (paths and contents); empty when it cannot be read. */
    static String fingerprint(Path file) {
        try {
            long size = Files.isDirectory(file) ? -1 : Files.size(file);
            long time = Files.getLastModifiedTime(file).toMillis();
            String[] known = FINGERPRINTS.get(file);
            if (known != null && known[0].equals(size + ":" + time)) {
                return known[1];
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            if (Files.isDirectory(file)) {
                try (Stream<Path> all = Files.walk(file)) {
                    for (Path p : all.filter(Files::isRegularFile).sorted().toList()) {
                        sha.update(file.relativize(p).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest(sha, p, buf);
                    }
                }
            } else {
                digest(sha, file, buf);
            }
            String hex = HexFormat.of().formatHex(sha.digest());
            FINGERPRINTS.put(file, new String[] {size + ":" + time, hex});
            return hex;
        } catch (Exception e) {
            return "";
        }
    }

    private static void digest(MessageDigest sha, Path file, byte[] buf) throws java.io.IOException {
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                sha.update(buf, 0, n);
            }
        }
    }
}
