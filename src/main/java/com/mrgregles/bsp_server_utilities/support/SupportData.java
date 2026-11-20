package com.mrgregles.bsp_server_utilities.support;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Support BSP's settings kept in the world (SavedData {@code bsp_server_utilities_support}): the record without shared storage, the copy with it. */
public final class SupportData extends SavedData {
    private static final String NAME = "bsp_server_utilities_support";
    private SupportSettings settings = SupportSettings.NONE;

    public static SupportData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SupportData::load, SupportData::new, NAME);
    }

    public SupportSettings settings() {
        return settings;
    }

    public void setSettings(SupportSettings s) {
        settings = s;
        setDirty();
    }

    private static SupportData load(CompoundTag tag) {
        SupportData d = new SupportData();
        d.settings = SupportSettings.load(tag.getCompound("settings"));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("settings", settings.save());
        return tag;
    }
}
