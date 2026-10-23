package com.mrgregles.bsp_server_utilities.setup;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;

/**
 * The setup guide's world data on this server (SavedData {@code bsp_server_utilities_setup}): this server's copy of the
 * network's state, and how far this server's own page (WELCOME) got.
 */
public final class SetupData extends SavedData {
    private static final String NAME = "bsp_server_utilities_setup";
    private SetupState state = SetupState.EMPTY;
    private SetupState.Status welcome = SetupState.Status.NOT_YET;

    public static SetupData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SetupData::load, SetupData::new, NAME);
    }

    public SetupState state() {
        return state;
    }

    public void setState(SetupState s) {
        state = s;
        setDirty();
    }

    public SetupState.Status welcome() {
        return welcome;
    }

    public void setWelcome(SetupState.Status s) {
        welcome = s;
        setDirty();
    }

    private static SetupData load(CompoundTag tag) {
        SetupData d = new SetupData();
        SetupState s = tag.contains("state") ? SetupState.decode(new String(tag.getByteArray("state"), StandardCharsets.UTF_8)) : null;
        d.state = s == null ? SetupState.EMPTY : s;
        try {
            d.welcome = tag.contains("welcome") ? SetupState.Status.valueOf(tag.getString("welcome")) : SetupState.Status.NOT_YET;
        } catch (IllegalArgumentException e) {
            d.welcome = SetupState.Status.NOT_YET;
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putByteArray("state", state.encode().getBytes(StandardCharsets.UTF_8)); // many ignoring admins can pass a string tag's 64 KB
        tag.putString("welcome", welcome.name());
        return tag;
    }
}
