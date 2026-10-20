package com.mrgregles.bsp_server_utilities.log;

import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The action log (owner decisions D25 to D27, D29, D30): what staff, operators, the console and RCON do, kept for the
 * network. An entry goes into the world's copy at once, into the shared table, and to the other servers as a
 * {@value #KIND_ENTRY} notice. A command whose switch is off is not logged; private messages never are. Turning a switch
 * off or on is itself an entry. Entries older than {@code log.keepDays} are deleted every hour on every server.
 */
public final class ActionLog {
    public static final String KIND_ENTRY = "log", KIND_SWITCH = "log_switch";
    public static final int CONSOLE_COLOUR = 0x9AA3B5;
    private static final long PRUNE_EVERY_MS = 3_600_000L;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static long lastPrune;

    private ActionLog() {
    }

    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    public static long keepMs() {
        return ServerUtilsConfig.getOr(ServerUtilsConfig.LOG_KEEP_DAYS, 7) * 86_400_000L;
    }

    /** Who a player is in the log: their staff role, or Operator for an operator who is not staff; empty for everyone else. */
    public static Optional<Staff.Role> tracked(ServerPlayer player) {
        Optional<Staff.Role> role = Staff.roleOf(player);
        if (role.isPresent()) {
            return role;
        }
        return player.hasPermissions(Perms.OPERATOR_LEVEL) ? Optional.of(Staff.OPERATOR) : Optional.empty();
    }

    /** Whether a command is logged: never a private message, otherwise unless its switch is off (D30). */
    public static boolean logged(MinecraftServer server, String root) {
        return !LogText.PRIVATE.contains(root) && LogData.get(server).switches().getOrDefault(root, true);
    }

    public static LogEntry entryFor(ServerPlayer player, Staff.Role role, String kind, String text) {
        return new LogEntry(0, UUID.randomUUID().toString(), System.currentTimeMillis(), Storage.currentServerId(), player.getUUID().toString(),
                player.getGameProfile().getName(), role.group(), role.label(), role.colour(), kind, text);
    }

    public static LogEntry entryForServer(String who, String kind, String text) {
        return new LogEntry(0, UUID.randomUUID().toString(), System.currentTimeMillis(), Storage.currentServerId(), "", who, "console", who, CONSOLE_COLOUR, kind, text);
    }

    /** Records an entry here, in the shared table and on the other servers. */
    public static void record(MinecraftServer server, LogEntry e) {
        if (LogData.get(server).add(e)) {
            Storage s = Storage.active();
            if (s != null) {
                s.submit(() -> s.backend().insertLog(e));
                s.publish(KIND_ENTRY, e.encode());
            }
            changed();
        }
    }

    /** Turns a command's logging on or off (the LOG tab); the change itself is an entry (D29). */
    public static void setSwitch(MinecraftServer server, @Nullable ServerPlayer by, String root, boolean on) {
        String r = LogText.root(root);
        if (r.isEmpty() || LogText.PRIVATE.contains(r) || LogData.get(server).switches().getOrDefault(r, true) == on) {
            return;
        }
        LogData.get(server).setSwitch(r, on);
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> s.backend().saveLogSwitch(r, on));
            s.publish(KIND_SWITCH, r + "|" + on);
        }
        String text = (on ? "started logging /" : "stopped logging /") + r;
        Optional<Staff.Role> role = by == null ? Optional.empty() : tracked(by);
        record(server, by != null && role.isPresent() ? entryFor(by, role.get(), LogEntry.SWITCH, text) : entryForServer("Console", LogEntry.SWITCH, text));
    }

    /** The newest entries, at most {@code limit}, from this server only when {@code allServers} is false, of the given kinds (empty for all). */
    public static List<LogEntry> recent(MinecraftServer server, int limit, boolean allServers, List<String> kinds) {
        String here = Storage.currentServerId();
        return LogData.get(server).entries().stream()
                .filter(e -> allServers || e.server().equals(here))
                .filter(e -> kinds.isEmpty() || kinds.contains(e.kind()))
                .limit(limit).toList();
    }

    public static void registerNotices() {
        Storage.on(KIND_ENTRY, (srv, n) -> {
            LogEntry e = LogEntry.decode(n.payload());
            if (e != null && LogData.get(srv).add(e)) {
                changed();
            }
        });
        Storage.on(KIND_SWITCH, (srv, n) -> {
            String[] f = n.payload().split("\\|");
            if (f.length == 2) {
                LogData.get(srv).setSwitch(f[0].toLowerCase(Locale.ROOT), Boolean.parseBoolean(f[1]));
                changed();
            }
        });
    }

    /** At server start: the shared switches and the recent entries replace the world's copy where they are newer. */
    public static void onServerStarted(MinecraftServer server) {
        lastPrune = 0;
        Storage s = Storage.active();
        if (s != null) {
            long since = System.currentTimeMillis() - keepMs();
            s.submit(() -> {
                var switches = s.backend().loadLogSwitches();
                var entries = s.backend().loadLog(since, LogData.MAX);
                server.execute(() -> {
                    switches.forEach((k, v) -> LogData.get(server).setSwitch(k, v));
                    entries.forEach(e -> LogData.get(server).add(e));
                    changed();
                });
            });
        }
    }

    /** Every tick: old entries go once an hour; creative items are counted up. */
    public static void tick(MinecraftServer server) {
        CreativeTracker.tick(server);
        long now = System.currentTimeMillis();
        if (now - lastPrune < PRUNE_EVERY_MS) {
            return;
        }
        lastPrune = now;
        long before = now - keepMs();
        if (LogData.get(server).prune(before) > 0) {
            changed();
        }
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> s.backend().pruneLog(before));
        }
    }
}
