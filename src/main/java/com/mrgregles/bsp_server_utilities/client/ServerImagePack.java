package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * A built-in, always-on, hidden resource pack held in memory: the font {@code bsp_server_utilities:server_tags} and the
 * server images as its textures, read from {@link ServerImageFont} each time the fonts load. Added to the client's packs
 * at start-up; every other resource of the mod comes from its jar as usual.
 */
final class ServerImagePack implements PackResources {
    static final String ID = "bsp_server_utilities_server_images";
    /** Minecraft 1.20.1's resource pack format. */
    private static final int FORMAT = 15;
    private static final String FONT = "font/server_tags.json";
    static final String TEXTURE_DIR = "server_tags/";
    private static final String TEXTURES = "textures/" + TEXTURE_DIR;
    private static final ServerImagePack INSTANCE = new ServerImagePack();

    static Pack pack() {
        Component title = Component.literal("BSP-Server Utilities server images");
        return Pack.create(ID, title, true, id -> INSTANCE, new Pack.Info(title, FORMAT, FORMAT, FeatureFlagSet.of(), true),
                PackType.CLIENT_RESOURCES, Pack.Position.TOP, true, PackSource.BUILT_IN);
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type != PackType.CLIENT_RESOURCES || !location.getNamespace().equals(BSPServerUtilities.MODID)) {
            return null;
        }
        String path = location.getPath();
        if (path.equals(FONT)) {
            byte[] json = ServerImageFont.fontJson().getBytes(StandardCharsets.UTF_8);
            return () -> new ByteArrayInputStream(json);
        }
        if (path.startsWith(TEXTURES) && path.endsWith(".png")) {
            byte[] png = ServerImageFont.png(path.substring(TEXTURES.length(), path.length() - 4));
            return png == null ? null : () -> new ByteArrayInputStream(png);
        }
        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type == PackType.CLIENT_RESOURCES && namespace.equals(BSPServerUtilities.MODID) && FONT.startsWith(path + "/")) {
            ResourceLocation font = ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, FONT);
            IoSupplier<InputStream> json = getResource(type, font);
            if (json != null) {
                output.accept(font, json);
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(BSPServerUtilities.MODID) : Set.of();
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        return serializer == PackMetadataSection.TYPE ? (T) new PackMetadataSection(Component.literal("BSP-Server Utilities server images"), FORMAT) : null;
    }

    @Override
    public String packId() {
        return ID;
    }

    @Override
    public boolean isBuiltin() {
        return true;
    }

    @Override
    public boolean isHidden() {
        return true;
    }

    @Override
    public void close() {
    }
}
