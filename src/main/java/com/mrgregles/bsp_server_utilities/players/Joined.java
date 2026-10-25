package com.mrgregles.bsp_server_utilities.players;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A player's first join on the network (owner decision D166): who, under which name, when, and on which server. */
public record Joined(UUID id, String name, long atMs, String server) {
    private static final String SEP = "\u001f";

    public String encode() {
        return String.join(SEP, id.toString(), name, Long.toString(atMs), server);
    }

    @Nullable
    public static Joined decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 4) {
            return null;
        }
        try {
            return new Joined(UUID.fromString(f[0]), f[1], Long.parseLong(f[2]), f[3]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putString("name", name);
        t.putLong("at", atMs);
        t.putString("server", server);
        return t;
    }

    @Nullable
    public static Joined load(CompoundTag t) {
        return t.hasUUID("id") ? new Joined(t.getUUID("id"), t.getString("name"), t.getLong("at"), t.getString("server")) : null;
    }
}
