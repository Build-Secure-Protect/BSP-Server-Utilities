package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pictures as textures for drawing: a picture's PNG bytes (from {@link ClientImages}) become a texture named after its
 * hash, made once on the render thread and kept until the player leaves the server.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class ClientPanelImages {
    /** A texture and its size in pixels. */
    public record Texture(ResourceLocation id, int width, int height) {
    }

    private static final Map<String, Texture> TEXTURES = new HashMap<>();
    private static final Set<String> BROKEN = new HashSet<>();

    private ClientPanelImages() {
    }

    /** The picture's texture, or null while its bytes have not arrived (they are asked for) or could not be read. */
    @Nullable
    public static Texture texture(String hash) {
        if (hash.isEmpty() || BROKEN.contains(hash)) {
            return null;
        }
        Texture t = TEXTURES.get(hash);
        if (t != null) {
            return t;
        }
        byte[] png = ClientImages.get(hash);
        if (png == null) {
            return null;
        }
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, "panel_image/" + hash);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            t = new Texture(id, image.getWidth(), image.getHeight());
            TEXTURES.put(hash, t);
            return t;
        } catch (IOException | RuntimeException e) {
            BROKEN.add(hash);
            BSPServerUtilities.LOGGER.warn("A panel picture could not be read: {}", e.toString());
            return null;
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft mc = Minecraft.getInstance();
        for (Texture t : TEXTURES.values()) {
            mc.getTextureManager().release(t.id());
        }
        TEXTURES.clear();
        BROKEN.clear();
        ClientImages.clear();
    }
}
