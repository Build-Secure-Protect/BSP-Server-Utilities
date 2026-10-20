package com.mrgregles.bsp_server_utilities.afk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** The AFK settings kept in the world (SavedData {@code bsp_server_utilities_afk}): the record without shared storage, the copy with it. */
public final class AfkData extends SavedData {
    private static final String NAME = "bsp_server_utilities_afk";
    private AfkSettings settings = AfkSettings.DEFAULTS;

    public static AfkData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(AfkData::load, AfkData::new, NAME);
    }

    public AfkSettings settings() {
        return settings;
    }

    public void setSettings(AfkSettings s) {
        settings = s;
        setDirty();
    }

    private static AfkData load(CompoundTag tag) {
        AfkData d = new AfkData();
        d.settings = AfkSettings.load(tag.getCompound("settings"));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("settings", settings.save());
        return tag;
    }
}
