package com.mrgregles.bsp_server_utilities.chunks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chunk unloading's world data on this server (SavedData {@code bsp_server_utilities_chunks}): the settings' copy, the
 * suspended tickets (taken from Forge, given back when the owner returns; they survive restarts), who placed each
 * machine, when each player last left this server, and the players an admin restored while they were away (D97).
 */
public final class ChunkData extends SavedData {
    private static final String NAME = "bsp_server_utilities_chunks";
    private static final String SEP = "\u001f";

    /** A ticket taken away: which, whose ({@code player} null for a machine with no owner an admin unloaded), and since when. */
    public record Suspended(ChunkTickets.Ticket ticket, @Nullable UUID player, String playerName, long sinceMs) {
        String encode() {
            return String.join(SEP, ticket.encode(), player == null ? "" : player.toString(), playerName, Long.toString(sinceMs));
        }

        @Nullable
        static Suspended decode(String s) {
            String[] f = s.split(SEP, -1);
            if (f.length != 4) {
                return null;
            }
            ChunkTickets.Ticket t = ChunkTickets.Ticket.decode(f[0]);
            try {
                return t == null ? null : new Suspended(t, f[1].isEmpty() ? null : UUID.fromString(f[1]), f[2], Long.parseLong(f[3]));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private ChunkSettings settings = ChunkSettings.DEFAULTS;
    private final Map<String, Suspended> suspended = new LinkedHashMap<>();
    private final Map<String, UUID> placers = new HashMap<>();
    private final Map<UUID, Long> lastLeft = new HashMap<>();
    private final Set<UUID> held = new HashSet<>();

    public static ChunkData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ChunkData::load, ChunkData::new, NAME);
    }

    public ChunkSettings settings() {
        return settings;
    }

    public void setSettings(ChunkSettings s) {
        settings = s;
        setDirty();
    }

    public Map<String, Suspended> suspended() {
        return suspended;
    }

    public void putSuspended(Suspended s) {
        suspended.put(s.ticket().key(), s);
        setDirty();
    }

    @Nullable
    public Suspended takeSuspended(String key) {
        Suspended s = suspended.remove(key);
        if (s != null) {
            setDirty();
        }
        return s;
    }

    /** Who placed the machine at a position ({@code dimension|x,y,z}), or null. */
    @Nullable
    public UUID placer(String posKey) {
        return placers.get(posKey);
    }

    public void putPlacer(String posKey, @Nullable UUID player) {
        if (player == null ? placers.remove(posKey) != null : !player.equals(placers.put(posKey, player))) {
            setDirty();
        }
    }

    @Nullable
    public Long lastLeft(UUID player) {
        return lastLeft.get(player);
    }

    public void setLastLeft(UUID player, long atMs) {
        lastLeft.put(player, atMs);
        setDirty();
    }

    public Set<UUID> held() {
        return held;
    }

    public void hold(UUID player, boolean on) {
        if (on ? held.add(player) : held.remove(player)) {
            setDirty();
        }
    }

    private static ChunkData load(CompoundTag tag) {
        ChunkData d = new ChunkData();
        d.settings = ChunkSettings.load(tag.getCompound("settings"));
        for (Tag t : tag.getList("suspended", Tag.TAG_STRING)) {
            Suspended s = Suspended.decode(t.getAsString());
            if (s != null) {
                d.suspended.put(s.ticket().key(), s);
            }
        }
        CompoundTag p = tag.getCompound("placers");
        for (String k : p.getAllKeys()) {
            d.placers.put(k, p.getUUID(k));
        }
        CompoundTag l = tag.getCompound("lastLeft");
        for (String k : l.getAllKeys()) {
            try {
                d.lastLeft.put(UUID.fromString(k), l.getLong(k));
            } catch (IllegalArgumentException ignored) {
                // a bad key
            }
        }
        for (Tag t : tag.getList("held", Tag.TAG_STRING)) {
            try {
                d.held.add(UUID.fromString(t.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // a bad entry
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("settings", settings.save());
        ListTag s = new ListTag();
        suspended.values().forEach(x -> s.add(StringTag.valueOf(x.encode())));
        tag.put("suspended", s);
        CompoundTag p = new CompoundTag();
        placers.forEach(p::putUUID);
        tag.put("placers", p);
        CompoundTag l = new CompoundTag();
        lastLeft.forEach((k, v) -> l.putLong(k.toString(), v));
        tag.put("lastLeft", l);
        ListTag h = new ListTag();
        held.forEach(u -> h.add(StringTag.valueOf(u.toString())));
        tag.put("held", h);
        return tag;
    }
}
