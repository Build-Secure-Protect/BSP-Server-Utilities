package com.mrgregles.bsp_server_utilities.despawn;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Despawn's world data on this server (SavedData {@code bsp_server_utilities_despawn}): the settings' copy, and the
 * running time of the last clear, so items on the ground before it go when their chunk next loads (D114).
 */
public final class DespawnData extends SavedData {
    private static final String NAME = "bsp_server_utilities_despawn";
    private DespawnSettings settings = DespawnSettings.DEFAULTS;
    private long clearedBefore;

    public static DespawnData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DespawnData::load, DespawnData::new, NAME);
    }

    public DespawnSettings settings() {
        return settings;
    }

    public void setSettings(DespawnSettings s) {
        settings = s;
        setDirty();
    }

    /** The server's running time (ticks) of the last clear; 0 when there has been none. */
    public long clearedBefore() {
        return clearedBefore;
    }

    public void setClearedBefore(long ticks) {
        clearedBefore = ticks;
        setDirty();
    }

    private static DespawnData load(CompoundTag tag) {
        DespawnData d = new DespawnData();
        d.settings = DespawnSettings.load(tag.getCompound("settings"));
        d.clearedBefore = tag.getLong("clearedBefore");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("settings", settings.save());
        tag.putLong("clearedBefore", clearedBefore);
        return tag;
    }
}
