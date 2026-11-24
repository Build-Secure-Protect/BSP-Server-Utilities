package com.mrgregles.bsp_server_utilities.tutorial;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Players' places in the tutorials kept in the world (SavedData {@code bsp_server_utilities_tutorial_progress}): the
 * record without shared storage; with it, the copy read at login for the players seen here.
 */
public final class ProgressData extends SavedData {
    private static final String NAME = "bsp_server_utilities_tutorial_progress";
    private final Map<UUID, Map<String, TutorialProgress>> rows = new HashMap<>();
    /** Rows the network's table has not taken yet ("player|tutorial"), kept across restarts (audit of SU-146). */
    private final java.util.Set<String> unwritten = new java.util.LinkedHashSet<>();

    void markUnwritten(String key) {
        if (unwritten.add(key)) {
            setDirty();
        }
    }

    /** The table took the row written at {@code atMs}: the mark goes unless a newer row has replaced it since. */
    void written(UUID player, String tutorial, long atMs) {
        TutorialProgress now = get(player, tutorial);
        if ((now == null || now.atMs() == atMs) && unwritten.remove(player + "|" + tutorial)) {
            setDirty();
        }
    }

    java.util.List<TutorialProgress> unwrittenRows() {
        java.util.List<TutorialProgress> out = new ArrayList<>();
        for (String k : unwritten) {
            int bar = k.indexOf('|');
            try {
                TutorialProgress p = get(UUID.fromString(k.substring(0, bar)), k.substring(bar + 1));
                if (p != null) {
                    out.add(p);
                }
            } catch (IllegalArgumentException | StringIndexOutOfBoundsException ignored) {
                // a damaged mark is dropped below
            }
        }
        return out;
    }

    public static ProgressData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ProgressData::load, ProgressData::new, NAME);
    }

    @Nullable
    public TutorialProgress get(UUID player, String tutorial) {
        Map<String, TutorialProgress> m = rows.get(player);
        return m == null ? null : m.get(tutorial);
    }

    public List<TutorialProgress> of(UUID player) {
        Map<String, TutorialProgress> m = rows.get(player);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    /** Every row (the TUTORIAL tab's counts without shared storage). */
    public List<TutorialProgress> all() {
        List<TutorialProgress> out = new ArrayList<>();
        rows.values().forEach(m -> out.addAll(m.values()));
        return out;
    }

    void put(TutorialProgress p) {
        rows.computeIfAbsent(p.player(), k -> new LinkedHashMap<>()).put(p.tutorial(), p);
        setDirty();
    }

    void remove(UUID player, String tutorial) {
        Map<String, TutorialProgress> m = rows.get(player);
        if (m != null && m.remove(tutorial) != null) {
            setDirty();
        }
    }

    /** Replaces one player's rows (read from the network's table at login). */
    void replace(UUID player, List<TutorialProgress> fresh) {
        Map<String, TutorialProgress> m = new LinkedHashMap<>();
        fresh.forEach(p -> m.put(p.tutorial(), p));
        rows.put(player, m);
        setDirty();
    }

    void clear() {
        rows.clear();
        unwritten.clear();
        setDirty();
    }

    private static ProgressData load(CompoundTag tag) {
        ProgressData d = new ProgressData();
        for (Tag x : tag.getList("rows", Tag.TAG_COMPOUND)) {
            TutorialProgress p = TutorialProgress.load((CompoundTag) x);
            if (p != null) {
                d.rows.computeIfAbsent(p.player(), k -> new LinkedHashMap<>()).put(p.tutorial(), p);
            }
        }
        for (Tag x : tag.getList("unwritten", Tag.TAG_STRING)) {
            d.unwritten.add(x.getAsString());
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        rows.values().forEach(m -> m.values().forEach(p -> list.add(p.save())));
        tag.put("rows", list);
        ListTag marks = new ListTag();
        unwritten.forEach(k -> marks.add(net.minecraft.nbt.StringTag.valueOf(k)));
        tag.put("unwritten", marks);
        return tag;
    }
}
