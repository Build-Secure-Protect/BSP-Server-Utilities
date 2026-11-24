package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogData;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import net.minecraft.server.MinecraftServer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the action log gained since a point in a test, told apart by each entry's uid. Counting the entries before and
 * after is wrong once the test world's log is full ({@link LogData#MAX}, reached after a few days of runs, since the world
 * under {@code run/} persists): each new entry then pushes the oldest out, and when that oldest one is of the kind being
 * counted, the count stays the same although the entry was written.
 */
public final class LogWatch {
    private final Set<String> seen = new HashSet<>();

    private LogWatch() {
    }

    /** Remembers every entry in the log now. */
    public static LogWatch start(MinecraftServer server) {
        LogWatch w = new LogWatch();
        LogData.get(server).entries().forEach(e -> w.seen.add(e.uid()));
        return w;
    }

    /** Every entry, of any kind and from any server, added since {@link #start}, newest first. */
    public List<LogEntry> added(MinecraftServer server) {
        return LogData.get(server).entries().stream().filter(e -> !seen.contains(e.uid())).toList();
    }

    /** The entries of this kind, on this server, written since {@link #start}, newest first. */
    public List<LogEntry> added(MinecraftServer server, String kind) {
        return ActionLog.recent(server, LogData.MAX, false, List.of(kind)).stream().filter(e -> !seen.contains(e.uid())).toList();
    }
}
