package com.mrgregles.bsp_server_utilities.rules;

import net.minecraft.nbt.CompoundTag;

/** A player's agreement: to which rules version, when (wall clock), on which server, under which name. */
public record Agreement(int version, long agreedAtMs, String server, String name) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", version);
        tag.putLong("at", agreedAtMs);
        tag.putString("server", server);
        tag.putString("name", name);
        return tag;
    }

    public static Agreement load(CompoundTag tag) {
        return new Agreement(tag.getInt("version"), tag.getLong("at"), tag.getString("server"), tag.getString("name"));
    }
}
