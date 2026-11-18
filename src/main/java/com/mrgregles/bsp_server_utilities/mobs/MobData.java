package com.mrgregles.bsp_server_utilities.mobs;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Mob limits' world data on this server (SavedData {@code bsp_server_utilities_mobs}): this server's copy of the network's settings. */
public final class MobData extends SavedData {
    private static final String NAME = "bsp_server_utilities_mobs";
    private MobSettings settings = MobSettings.DEFAULTS;

    public static MobData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(MobData::load, MobData::new, NAME);
    }

    public MobSettings settings() {
        return settings;
    }

    public void setSettings(MobSettings s) {
        settings = s;
        setDirty();
    }

    private static MobData load(CompoundTag tag) {
        MobData d = new MobData();
        d.settings = MobSettings.load(tag.getCompound("settings"));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("settings", settings.save());
        return tag;
    }
}
