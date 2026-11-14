package com.mrgregles.bsp_server_utilities.rooms;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * This server's Compact Machines rooms in their grace period (owner decisions D446, D447): a room whose machine was lost,
 * given back or freed stops counting at once and waits here until it is cleared (SU-57) or revived by a placed machine
 * bound to it. Compact Machines' own record of the room is not touched while it waits. Kept in the overworld's saved
 * data ({@value #NAME}). Server thread only.
 */
public final class RoomGrace extends SavedData {
    public static final String NAME = "bsp_server_utilities_room_grace";

    /**
     * A room in grace: its chunk, its owner and inside size when it went in, when it went in and when it is cleared (ms),
     * why (burnt, exploded, cactus, crushed, destroyed, despawned, cleared, void, given back, freed), and whether its owner
     * has been told.
     */
    public record Entry(ChunkPos room, UUID owner, int size, long since, long until, String reason, boolean told) {
        Entry withTold() {
            return new Entry(room, owner, size, since, until, reason, true);
        }
    }

    private final Map<Long, Entry> entries = new LinkedHashMap<>();

    public static RoomGrace get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RoomGrace::load, RoomGrace::new, NAME);
    }

    public static RoomGrace load(CompoundTag tag) {
        RoomGrace g = new RoomGrace();
        ListTag list = tag.getList("rooms", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            if (!e.hasUUID("owner")) {
                continue;
            }
            ChunkPos room = new ChunkPos(e.getInt("x"), e.getInt("z"));
            g.entries.put(room.toLong(), new Entry(room, e.getUUID("owner"), e.getInt("size"), e.getLong("since"), e.getLong("until"), e.getString("reason"), e.getBoolean("told")));
        }
        return g;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("x", e.room().x);
            t.putInt("z", e.room().z);
            t.putUUID("owner", e.owner());
            t.putInt("size", e.size());
            t.putLong("since", e.since());
            t.putLong("until", e.until());
            t.putString("reason", e.reason());
            t.putBoolean("told", e.told());
            list.add(t);
        }
        tag.put("rooms", list);
        return tag;
    }

    public Optional<Entry> get(ChunkPos room) {
        return Optional.ofNullable(entries.get(room.toLong()));
    }

    public boolean contains(ChunkPos room) {
        return entries.containsKey(room.toLong());
    }

    public void put(Entry e) {
        entries.put(e.room().toLong(), e);
        setDirty();
    }

    public Optional<Entry> remove(ChunkPos room) {
        Entry e = entries.remove(room.toLong());
        if (e != null) {
            setDirty();
        }
        return Optional.ofNullable(e);
    }

    /** Marks the owner as told; false when the room is not in grace. */
    public boolean markTold(ChunkPos room) {
        Entry e = entries.get(room.toLong());
        if (e == null || e.told()) {
            return e != null;
        }
        entries.put(room.toLong(), e.withTold());
        setDirty();
        return true;
    }

    /** How many of the player's rooms are in grace here. */
    public int countFor(UUID owner) {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.owner().equals(owner)) {
                n++;
            }
        }
        return n;
    }

    public List<Entry> all() {
        return new ArrayList<>(entries.values());
    }
}
