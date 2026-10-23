package com.mrgregles.bsp_server_utilities.check;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * The client check's world data on this server (SavedData {@code bsp_server_utilities_check}): this server's copy of the
 * network's rules and trusted setup, whether this server follows its own rules instead (D134), and its own rules and
 * setup (they start as a copy of the network's).
 */
public final class CheckData extends SavedData {
    private static final String NAME = "bsp_server_utilities_check";
    private CheckRules networkRules = CheckRules.DEFAULTS, ownRules = CheckRules.DEFAULTS;
    @Nullable
    private TrustedSetup networkSetup, ownSetup;
    private boolean useOwn;

    public static CheckData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CheckData::load, CheckData::new, NAME);
    }

    public CheckRules networkRules() {
        return networkRules;
    }

    public void setNetworkRules(CheckRules r) {
        networkRules = r;
        setDirty();
    }

    public CheckRules ownRules() {
        return ownRules;
    }

    public void setOwnRules(CheckRules r) {
        ownRules = r;
        setDirty();
    }

    @Nullable
    public TrustedSetup networkSetup() {
        return networkSetup;
    }

    public void setNetworkSetup(@Nullable TrustedSetup s) {
        networkSetup = s;
        setDirty();
    }

    @Nullable
    public TrustedSetup ownSetup() {
        return ownSetup;
    }

    public void setOwnSetup(@Nullable TrustedSetup s) {
        ownSetup = s;
        setDirty();
    }

    public boolean useOwn() {
        return useOwn;
    }

    public void setUseOwn(boolean own) {
        useOwn = own;
        setDirty();
    }

    private static CheckData load(CompoundTag tag) {
        CheckData d = new CheckData();
        d.networkRules = CheckRules.load(tag.getCompound("networkRules"));
        d.ownRules = CheckRules.load(tag.getCompound("ownRules"));
        d.networkSetup = setup(tag, "networkSetup");
        d.ownSetup = setup(tag, "ownSetup");
        d.useOwn = tag.getBoolean("useOwn");
        return d;
    }

    @Nullable
    private static TrustedSetup setup(CompoundTag tag, String key) {
        return tag.contains(key) ? TrustedSetup.fromJson(new String(tag.getByteArray(key), StandardCharsets.UTF_8)) : null;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("networkRules", networkRules.save());
        tag.put("ownRules", ownRules.save());
        // as bytes: a setup's JSON can pass the 64 KB a string tag may hold
        if (networkSetup != null) {
            tag.putByteArray("networkSetup", networkSetup.toJson().getBytes(StandardCharsets.UTF_8));
        }
        if (ownSetup != null) {
            tag.putByteArray("ownSetup", ownSetup.toJson().getBytes(StandardCharsets.UTF_8));
        }
        tag.putBoolean("useOwn", useOwn);
        return tag;
    }
}
