package com.mrgregles.bsp_server_utilities.log;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The action log kept in the world (SavedData {@code bsp_server_utilities_log}): the newest entries (all of them up to
 * {@value #MAX} without shared storage; the recent copy with it) and the logging switches (a command missing from the
 * map is logged, D30).
 */
public final class LogData extends SavedData {
    private static final String NAME = "bsp_server_utilities_log";
    public static final int MAX = 5000;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private final Map<String, Boolean> switches = new HashMap<>();

    public static LogData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(LogData::load, LogData::new, NAME);
    }

    /** Adds an entry unless one with its uid is already here; the oldest go past {@value #MAX}. */
    public boolean add(LogEntry e) {
        for (LogEntry x : entries) {
            if (x.uid().equals(e.uid())) {
                return false;
            }
        }
        entries.addFirst(e);
        while (entries.size() > MAX) {
            entries.removeLast();
        }
        setDirty();
        return true;
    }

    /** Newest first. */
    public List<LogEntry> entries() {
        List<LogEntry> out = new ArrayList<>(entries);
        out.sort((a, b) -> Long.compare(b.atMs(), a.atMs()));
        return out;
    }

    public int prune(long beforeMs) {
        int n = entries.size();
        entries.removeIf(e -> e.atMs() < beforeMs);
        if (entries.size() != n) {
            setDirty();
        }
        return n - entries.size();
    }

    public Map<String, Boolean> switches() {
        return switches;
    }

    public void setSwitch(String root, boolean on) {
        switches.put(root, on);
        setDirty();
    }

    private static LogData load(CompoundTag tag) {
        LogData d = new LogData();
        for (Tag t : tag.getList("entries", Tag.TAG_STRING)) {
            LogEntry e = LogEntry.decode(t.getAsString());
            if (e != null) {
                d.entries.addLast(e);
            }
        }
        CompoundTag s = tag.getCompound("switches");
        for (String k : s.getAllKeys()) {
            d.switches.put(k, s.getBoolean(k));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (LogEntry e : entries) {
            list.add(net.minecraft.nbt.StringTag.valueOf(e.encode()));
        }
        tag.put("entries", list);
        CompoundTag s = new CompoundTag();
        switches.forEach(s::putBoolean);
        tag.put("switches", s);
        return tag;
    }
}
