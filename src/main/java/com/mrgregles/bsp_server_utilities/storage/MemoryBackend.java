package com.mrgregles.bsp_server_utilities.storage;

import com.mrgregles.bsp_server_utilities.access.StaffData;
import com.mrgregles.bsp_server_utilities.access.StaffGroups;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.rules.Agreement;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The backend in memory, for tests: one instance shared by two storages plays one database shared by two servers. */
public final class MemoryBackend implements StorageBackend {
    private final List<Notice> notices = new ArrayList<>();
    private long nextId = 1;
    private boolean failing;
    @Nullable
    private RuleSet rules;
    private final Map<UUID, Agreement> agreements = new HashMap<>();
    @Nullable
    private StaffGroups staffGroups;
    private com.mrgregles.bsp_server_utilities.afk.AfkSettings afkSettings;
    private final Map<UUID, StaffData.Seen> seen = new HashMap<>();
    private final List<LogEntry> log = new ArrayList<>();
    private final Map<String, Boolean> switches = new HashMap<>();

    /** When true every call throws, playing a database outage. */
    public synchronized void setFailing(boolean failing) {
        this.failing = failing;
    }

    private void check() throws Exception {
        if (failing) {
            throw new Exception("database down (test)");
        }
    }

    @Override
    public synchronized void init() throws Exception {
        check();
    }

    @Override
    public synchronized long publish(Notice n) throws Exception {
        check();
        long id = nextId++;
        notices.add(new Notice(id, n.uid(), n.createdAt(), n.source(), n.kind(), n.payload()));
        return id;
    }

    @Override
    public synchronized List<Notice> fetchNoticesAfter(long id, int limit) throws Exception {
        check();
        List<Notice> out = new ArrayList<>();
        for (Notice n : notices) {
            if (n.id() > id && out.size() < limit) {
                out.add(n);
            }
        }
        return out;
    }

    @Override
    public synchronized long latestNoticeId() throws Exception {
        check();
        return nextId - 1;
    }

    @Override
    public synchronized int pruneNotices(long beforeMs) throws Exception {
        check();
        int before = notices.size();
        notices.removeIf(n -> n.createdAt() < beforeMs);
        return before - notices.size();
    }

    /** How many notices the table holds (tests). */
    public synchronized int noticeCount() {
        return notices.size();
    }

    @Override
    @Nullable
    public synchronized RuleSet loadRules() throws Exception {
        check();
        return rules;
    }

    @Override
    public synchronized void saveRules(RuleSet r) throws Exception {
        check();
        rules = r;
    }

    @Override
    @Nullable
    public synchronized Agreement loadAgreement(UUID player) throws Exception {
        check();
        return agreements.get(player);
    }

    @Override
    public synchronized void saveAgreement(UUID player, @Nullable Agreement a) throws Exception {
        check();
        if (a == null) {
            agreements.remove(player);
        } else {
            agreements.put(player, a);
        }
    }

    @Override
    public synchronized int countAgreed(int version) throws Exception {
        check();
        return (int) agreements.values().stream().filter(a -> a.version() >= version).count();
    }

    @Override
    @Nullable
    public synchronized StaffGroups loadStaffGroups() throws Exception {
        check();
        return staffGroups;
    }

    @Override
    public synchronized void saveStaffGroups(StaffGroups groups) throws Exception {
        check();
        staffGroups = groups;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.afk.AfkSettings loadAfkSettings() throws Exception {
        check();
        return afkSettings;
    }

    @Override
    public synchronized void saveAfkSettings(com.mrgregles.bsp_server_utilities.afk.AfkSettings settings) throws Exception {
        check();
        afkSettings = settings;
    }

    @Override
    public synchronized void saveSeen(UUID player, StaffData.Seen s) throws Exception {
        check();
        StaffData.Seen old = seen.get(player);
        if (old == null || s.atMs() >= old.atMs()) {
            seen.put(player, s);
        }
    }

    @Override
    public synchronized Map<UUID, StaffData.Seen> loadSeen() throws Exception {
        check();
        return Map.copyOf(seen);
    }

    @Override
    public synchronized void insertLog(LogEntry e) throws Exception {
        check();
        log.add(e);
    }

    @Override
    public synchronized List<LogEntry> loadLog(long sinceMs, int limit) throws Exception {
        check();
        return log.stream().filter(e -> e.atMs() >= sinceMs).sorted((a, b) -> Long.compare(b.atMs(), a.atMs())).limit(limit).toList();
    }

    @Override
    public synchronized int pruneLog(long beforeMs) throws Exception {
        check();
        int n = log.size();
        log.removeIf(e -> e.atMs() < beforeMs);
        return n - log.size();
    }

    @Override
    public synchronized void saveLogSwitch(String root, boolean on) throws Exception {
        check();
        switches.put(root, on);
    }

    @Override
    public synchronized Map<String, Boolean> loadLogSwitches() throws Exception {
        check();
        return Map.copyOf(switches);
    }

    /** How many entries the table holds (tests). */
    public synchronized int logCount() {
        return log.size();
    }

    @Override
    public String describe() {
        return "memory";
    }

    @Override
    public void close() {
    }
}
