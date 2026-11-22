package com.mrgregles.bsp_server_utilities.talk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Private staff messages kept in the world (SavedData {@code bsp_server_utilities_talks}): the record on a server without
 * shared storage (D645). With shared storage it stays empty; the network's tables are the record.
 */
public final class TalkData extends SavedData {
    private static final String NAME = "bsp_server_utilities_talks";
    private TalkTable table = new TalkTable();

    public TalkData() {
        table.onChange(this::setDirty);
    }

    public static TalkData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TalkData::load, TalkData::new, NAME);
    }

    public TalkTable table() {
        return table;
    }

    private static TalkData load(CompoundTag tag) {
        TalkData d = new TalkData();
        d.table = TalkTable.load(tag.getCompound("table")).onChange(d::setDirty);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("table", table.save());
        return tag;
    }
}
