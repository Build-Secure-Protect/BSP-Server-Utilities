package com.mrgregles.bsp_server_utilities.players;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player counter kept in the world (SavedData {@code bsp_server_utilities_players}): the settings (the record without
 * shared storage, the copy with it) and, without shared storage, this server's own list of first joins (D174).
 */
public final class PlayersData extends SavedData {
    private static final String NAME = "bsp_server_utilities_players";
    private PlayersSettings settings = PlayersSettings.DEFAULTS;
    private final Map<UUID, Joined> players = new LinkedHashMap<>();

    public static PlayersData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PlayersData::load, PlayersData::new, NAME);
    }

    public PlayersSettings settings() {
        return settings;
    }

    public void setSettings(PlayersSettings s) {
        settings = s;
        setDirty();
    }

    /** Adds a first join; false when the player is already on the list. */
    public boolean add(Joined j) {
        if (players.putIfAbsent(j.id(), j) != null) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean has(UUID id) {
        return players.containsKey(id);
    }

    /** Takes a player off the list (tests). */
    public void remove(UUID id) {
        if (players.remove(id) != null) {
            setDirty();
        }
    }

    /** The counts of this server's own list at a moment. */
    public PlayerCounts counts(long now) {
        long since = PlayerCounts.recentSince(now);
        List<Joined> recent = new ArrayList<>();
        Joined newest = null;
        for (Joined j : players.values()) {
            if (j.atMs() >= since) {
                recent.add(j);
            }
            if (newest == null || j.atMs() >= newest.atMs()) {
                newest = j;
            }
        }
        recent.sort(Comparator.comparingLong(Joined::atMs).reversed());
        return new PlayerCounts(players.size(), recent.size() > PlayerCounts.RECENT_MAX ? recent.subList(0, PlayerCounts.RECENT_MAX) : recent, newest);
    }

    private static PlayersData load(CompoundTag tag) {
        PlayersData d = new PlayersData();
        d.settings = PlayersSettings.load(tag.getCompound("settings"));
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Joined j = Joined.load(list.getCompound(i));
            if (j != null) {
                d.players.put(j.id(), j);
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("settings", settings.save());
        ListTag list = new ListTag();
        players.values().forEach(j -> list.add(j.save()));
        tag.put("players", list);
        return tag;
    }
}
