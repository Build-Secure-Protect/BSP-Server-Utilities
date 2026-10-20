package com.mrgregles.bsp_server_utilities.access;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Staff records kept in the world (SavedData {@code bsp_server_utilities_staff}): the staff groups (the record without
 * shared storage, the local copy with it), whether they were seeded from the config yet, and when each staff member was
 * last seen and where.
 */
public final class StaffData extends SavedData {
    private static final String NAME = "bsp_server_utilities_staff";

    /** When a staff member was last seen online, by name, wall clock and server. */
    public record Seen(String name, long atMs, String server) {
    }

    private StaffGroups groups = StaffGroups.empty();
    private boolean seeded;
    private final Map<UUID, Seen> seen = new HashMap<>();

    public static StaffData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(StaffData::load, StaffData::new, NAME);
    }

    public StaffGroups groups() {
        return groups;
    }

    public void setGroups(StaffGroups g) {
        groups = g;
        setDirty();
    }

    public boolean seeded() {
        return seeded;
    }

    public void markSeeded() {
        seeded = true;
        setDirty();
    }

    public Map<UUID, Seen> seen() {
        return seen;
    }

    /** Records a sighting when it is newer than the one kept. */
    public void putSeen(UUID player, Seen s) {
        Seen old = seen.get(player);
        if (old == null || s.atMs() >= old.atMs()) {
            seen.put(player, s);
            setDirty();
        }
    }

    private static StaffData load(CompoundTag tag) {
        StaffData d = new StaffData();
        d.groups = StaffGroups.load(tag.getCompound("groups"));
        d.seeded = tag.getBoolean("seeded");
        CompoundTag seen = tag.getCompound("seen");
        for (String key : seen.getAllKeys()) {
            try {
                CompoundTag s = seen.getCompound(key);
                d.seen.put(UUID.fromString(key), new Seen(s.getString("name"), s.getLong("at"), s.getString("server")));
            } catch (IllegalArgumentException ignored) {
                // not a player id
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("groups", groups.save());
        tag.putBoolean("seeded", seeded);
        CompoundTag s = new CompoundTag();
        seen.forEach((id, v) -> {
            CompoundTag st = new CompoundTag();
            st.putString("name", v.name());
            st.putLong("at", v.atMs());
            st.putString("server", v.server());
            s.put(id.toString(), st);
        });
        tag.put("seen", s);
        return tag;
    }
}
