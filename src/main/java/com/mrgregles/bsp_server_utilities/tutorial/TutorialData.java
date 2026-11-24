package com.mrgregles.bsp_server_utilities.tutorial;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tutorials kept in the world (SavedData {@code bsp_server_utilities_tutorials}): each tutorial's draft and
 * published copy, deleted ones as their empty marker copies. Without shared storage this is the record; with it, the
 * local copy of the network's table.
 */
public final class TutorialData extends SavedData {
    private static final String NAME = "bsp_server_utilities_tutorials";
    private final Map<TutorialStage, Map<String, Tutorial>> copies = new EnumMap<>(TutorialStage.class);

    public TutorialData() {
        for (TutorialStage s : TutorialStage.values()) {
            copies.put(s, new LinkedHashMap<>());
        }
    }

    public static TutorialData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TutorialData::load, TutorialData::new, NAME);
    }

    /** A copy by id, deleted markers included, or null. */
    @Nullable
    public Tutorial get(TutorialStage stage, String id) {
        return copies.get(stage).get(id);
    }

    /** Every copy of a stage, deleted markers included, in the order they were first seen. */
    public List<Tutorial> all(TutorialStage stage) {
        return new ArrayList<>(copies.get(stage).values());
    }

    void put(TutorialStage stage, Tutorial t) {
        copies.get(stage).put(t.id(), t);
        setDirty();
    }

    /** Tests: forgets every copy. */
    void clear() {
        copies.values().forEach(Map::clear);
        setDirty();
    }

    private static TutorialData load(CompoundTag tag) {
        TutorialData d = new TutorialData();
        for (TutorialStage s : TutorialStage.values()) {
            for (Tag x : tag.getList(s.key, Tag.TAG_COMPOUND)) {
                Tutorial t = Tutorial.load((CompoundTag) x);
                if (!t.id().isEmpty()) {
                    d.copies.get(s).put(t.id(), t);
                }
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (TutorialStage s : TutorialStage.values()) {
            ListTag list = new ListTag();
            copies.get(s).values().forEach(t -> list.add(t.save()));
            tag.put(s.key, list);
        }
        return tag;
    }
}
