package com.mrgregles.bsp_server_utilities.serverimages;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Server images kept in the world (SavedData {@code bsp_server_utilities_server_images}): the set (the record without
 * shared storage, the copy with it) and the PNG bytes of every image it uses, so the folder's files may go afterwards
 * and a restart needs no database to send them.
 */
public final class ServerImagesData extends SavedData {
    private static final String NAME = "bsp_server_utilities_server_images";
    private ServerImageSet set = ServerImageSet.EMPTY;
    private final Map<String, byte[]> images = new HashMap<>();

    public static ServerImagesData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ServerImagesData::load, ServerImagesData::new, NAME);
    }

    public ServerImageSet set() {
        return set;
    }

    /** Takes a new set and drops the bytes it no longer uses. */
    public void setSet(ServerImageSet s) {
        set = s;
        keepOnly(s.ids());
        setDirty();
    }

    @Nullable
    public byte[] bytes(String id) {
        return images.get(id);
    }

    public boolean has(String id) {
        return images.containsKey(id);
    }

    public void put(String id, byte[] png) {
        if (!images.containsKey(id)) {
            images.put(id, png.clone());
            setDirty();
        }
    }

    private void keepOnly(Set<String> ids) {
        if (images.keySet().retainAll(ids)) {
            setDirty();
        }
    }

    private static ServerImagesData load(CompoundTag tag) {
        ServerImagesData d = new ServerImagesData();
        d.set = ServerImageSet.load(tag.getCompound("set"));
        CompoundTag list = tag.getCompound("images");
        for (String id : list.getAllKeys()) {
            if (ServerImages.validId(id)) {
                d.images.put(id, list.getByteArray(id));
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("set", set.save());
        CompoundTag list = new CompoundTag();
        images.forEach(list::putByteArray);
        tag.put("images", list);
        return tag;
    }
}
