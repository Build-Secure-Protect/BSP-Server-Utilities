package com.mrgregles.bsp_server_utilities.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.ServerImageListPacket;
import com.mrgregles.bsp_server_utilities.serverimages.ServerImages;
import com.mrgregles.bsp_server_utilities.serverimages.ServerTag;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Unit;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server images on the client (owner decisions D189 to D198). Each image the server sends gets a character of Unicode's
 * private use area; {@link ServerImagePack} serves the font {@code bsp_server_utilities:server_tags} with one bitmap per
 * image (one chat line high) and then the default font. When images arrive the fonts alone are reloaded, and the key of
 * each server in the list ({@link ServerTag#KEY}{@code <name>}) reads as its image's character. Until then, and for a
 * server without an image, the key falls back to the grey {@code [name]}.
 *
 * <p>The keys are answered by a wrapper around the game's language. BSP-RankUp wraps the language too and wraps again
 * whenever its own wrapper is not on top; so this wrapper is put in only when it is nowhere in the chain (asked with a
 * probe key only it answers), and it reads the images from one shared map instead of being replaced on each change.
 * The two then settle after one wrap each instead of taking turns on top every tick.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class ServerImageFont {
    /** The first private-use character handed out (RankUp's font has its own; the fonts never mix). */
    private static final int FIRST = 0xEC00, LAST = 0xF8FF;
    /** Ticks without a new image before the fonts are reloaded, so a login's images load in one go. */
    private static final int QUIET_TICKS = 10;
    /** Drawn one chat line high, the top one pixel above capitals. */
    static final int HEIGHT = ServerImages.LINE, ASCENT = 8;
    /** A key only this mod's wrapper answers, and its answer: how the wrapper finds itself in the chain. */
    private static final String PROBE = ServerTag.KEY + "~probe";
    private static final String TOKEN = UUID.randomUUID().toString();

    private static final Map<String, byte[]> IMAGES = new LinkedHashMap<>();
    private static final Map<String, Integer> CHARS = new HashMap<>();
    private static int next = FIRST;
    /** Each server's image id (names in lower case), as the server last listed them. */
    private static Map<String, String> servers = Map.of();
    /** The images the font file listed when it was last read (a reload's preparation, any thread). */
    private static volatile Map<String, Integer> listed = Map.of();
    /** What the wrapper answers: each server's key to its image's character, for the images the font draws now. */
    private static volatile Map<String, String> glyphs = Map.of();
    private static boolean wanted, reloading;
    private static int quiet;
    private static final Map<String, Texture> TEXTURES = new HashMap<>();

    /** A preview texture and its size in pixels, for the SERVERS tab. */
    public record Texture(ResourceLocation id, int width, int height) {
    }

    private ServerImageFont() {
    }

    // ------------------------------------------------------------------ from the server

    /** The servers' images in use (main thread): which key reads as which image; an empty list (off) leaves every tag as text. */
    public static void accept(ServerImageListPacket p) {
        Map<String, String> next = new HashMap<>();
        if (p.on()) {
            for (ServerImageListPacket.Item i : p.items()) {
                next.put(i.server().toLowerCase(Locale.ROOT), i.id());
            }
        }
        servers = Map.copyOf(next);
        rebuild();
    }

    /** An image (main thread). Ignored when already known, malformed, or past the limits. */
    public static void receive(String id, byte[] png) {
        if (IMAGES.containsKey(id) || !ServerImages.validId(id) || png.length < 24 || next > LAST) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(png);
        int w = buf.getInt(16), h = buf.getInt(20);
        if (w <= 0 || h <= 0 || w > ServerImages.MAX_WIDTH || h > ServerImages.MAX_HEIGHT) {
            return;
        }
        synchronized (IMAGES) {
            IMAGES.put(id, png);
            if (w > ServerImages.MAX_SHEET) {
                return; // kept for previews only: the font's sheets are 256 pixels (the server scales wider files down)
            }
            CHARS.put(id, next++);
        }
        wanted = true;
        quiet = 0;
    }

    /** Whether the font draws a server's image now (the SERVERS tab's sample line). */
    public static boolean drawn(String server) {
        return glyphs.containsKey(ServerTag.key(server));
    }

    // ------------------------------------------------------------------ the font and the language

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!TOKEN.equals(Language.getInstance().getOrDefault(PROBE, ""))) {
            Language.inject(new Wrapped(Language.getInstance())); // a resource reload replaced the language, or first use
        }
        if (wanted && !reloading && ++quiet >= QUIET_TICKS) {
            reload();
        }
    }

    private static void reload() {
        wanted = false;
        reloading = true;
        Minecraft mc = Minecraft.getInstance();
        FontManager fonts;
        try {
            fonts = ObfuscationReflectionHelper.getPrivateValue(Minecraft.class, mc, "f_91045_");
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("Server images: could not reach the font manager; they show after the next resource reload (F3+T): {}", e.toString());
            reloading = false;
            return;
        }
        SimpleReloadInstance.create(mc.getResourceManager(), List.of(fonts), Util.backgroundExecutor(), mc, CompletableFuture.completedFuture(Unit.INSTANCE), false)
                .done().whenComplete((v, err) -> mc.execute(() -> {
                    reloading = false;
                    if (err != null) {
                        BSPServerUtilities.LOGGER.warn("Server images: the font reload failed: {}", err.toString());
                    }
                    rebuild();
                }));
    }

    /** What the wrapper answers: each listed server whose image the font draws now. */
    private static void rebuild() {
        Map<String, Integer> drawnNow = listed;
        Map<String, String> out = new HashMap<>();
        servers.forEach((name, id) -> {
            Integer c = drawnNow.get(id);
            if (c != null) {
                out.put(ServerTag.KEY + name, Character.toString(c));
            }
        });
        glyphs = Map.copyOf(out);
    }

    /** The font file: one bitmap per image, then the default font. Records what it listed. */
    static String fontJson() {
        Map<String, Integer> now;
        synchronized (IMAGES) {
            now = Map.copyOf(CHARS);
        }
        JsonArray providers = new JsonArray();
        now.forEach((id, c) -> {
            JsonObject bitmap = new JsonObject();
            bitmap.addProperty("type", "bitmap");
            bitmap.addProperty("file", BSPServerUtilities.MODID + ":" + ServerImagePack.TEXTURE_DIR + id + ".png");
            bitmap.addProperty("height", HEIGHT);
            bitmap.addProperty("ascent", ASCENT);
            JsonArray chars = new JsonArray();
            chars.add(Character.toString(c));
            bitmap.add("chars", chars);
            providers.add(bitmap);
        });
        JsonObject fallback = new JsonObject();
        fallback.addProperty("type", "reference");
        fallback.addProperty("id", "minecraft:default");
        providers.add(fallback);
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        listed = now;
        return font.toString();
    }

    @Nullable
    static byte[] png(String id) {
        synchronized (IMAGES) {
            return IMAGES.get(id);
        }
    }

    // ------------------------------------------------------------------ previews

    /** An image as a texture for the SERVERS tab, or null while its bytes have not arrived or could not be read. */
    @Nullable
    public static Texture texture(String id) {
        if (id.isEmpty()) {
            return null;
        }
        Texture t = TEXTURES.get(id);
        if (t != null) {
            return t;
        }
        byte[] png = png(id);
        if (png == null) {
            return null;
        }
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, "server_image_preview/" + id);
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            t = new Texture(rl, image.getWidth(), image.getHeight());
            TEXTURES.put(id, t);
            return t;
        } catch (IOException | RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("A server image could not be read: {}", e.toString());
            return null;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        servers = Map.of();
        glyphs = Map.of();
        Minecraft mc = Minecraft.getInstance();
        for (Texture t : TEXTURES.values()) {
            mc.getTextureManager().release(t.id());
        }
        TEXTURES.clear();
    }

    /** The game's language with the servers' keys answered by their images' characters; everything else goes to the language it wraps. */
    static final class Wrapped extends Language {
        private final Language base;

        Wrapped(Language base) {
            this.base = base;
        }

        @Override
        public String getOrDefault(String key, String fallback) {
            if (key.startsWith(ServerTag.KEY)) {
                if (key.equals(PROBE)) {
                    return TOKEN;
                }
                String glyph = glyphs.get(key);
                if (glyph != null) {
                    return glyph;
                }
            }
            return base.getOrDefault(key, fallback);
        }

        @Override
        public boolean has(String key) {
            return key.equals(PROBE) || glyphs.containsKey(key) || base.has(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return base.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return base.getVisualOrder(text);
        }

        @Override
        public Map<String, String> getLanguageData() {
            return base.getLanguageData();
        }
    }
}
