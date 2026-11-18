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
    private com.mrgregles.bsp_server_utilities.chunks.ChunkSettings chunkSettings;
    private com.mrgregles.bsp_server_utilities.despawn.DespawnSettings despawnSettings;
    private com.mrgregles.bsp_server_utilities.mobs.MobSettings mobSettings;
    private com.mrgregles.bsp_server_utilities.check.CheckRules checkRules;
    private com.mrgregles.bsp_server_utilities.check.TrustedSetup trustedSetup;
    private com.mrgregles.bsp_server_utilities.setup.SetupState setupState;
    private com.mrgregles.bsp_server_utilities.players.PlayersSettings playersSettings;
    private final Map<String, WebServer> webServers = new HashMap<>();
    private com.mrgregles.bsp_server_utilities.serverimages.ServerImageSet serverImages;
    private final Map<String, byte[]> serverImageBytes = new java.util.HashMap<>();
    private final Map<UUID, com.mrgregles.bsp_server_utilities.players.Joined> players = new java.util.LinkedHashMap<>();
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
    public synchronized com.mrgregles.bsp_server_utilities.setup.SetupState loadSetupState() throws Exception {
        check();
        return setupState;
    }

    @Override
    public synchronized void saveSetupState(com.mrgregles.bsp_server_utilities.setup.SetupState state) throws Exception {
        check();
        setupState = state;
    }

    @Override
    public synchronized boolean insertPlayer(com.mrgregles.bsp_server_utilities.players.Joined j) throws Exception {
        check();
        com.mrgregles.bsp_server_utilities.players.Joined old = players.putIfAbsent(j.id(), j);
        if (old != null && !old.name().equals(j.name())) {
            players.put(j.id(), new com.mrgregles.bsp_server_utilities.players.Joined(old.id(), j.name(), old.atMs(), old.server()));
        }
        return old == null;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.players.PlayerCounts playerCounts(long sinceMs, int limit) throws Exception {
        check();
        List<com.mrgregles.bsp_server_utilities.players.Joined> all = new ArrayList<>(players.values());
        all.sort((a, b) -> Long.compare(b.atMs(), a.atMs()));
        return new com.mrgregles.bsp_server_utilities.players.PlayerCounts(all.size(), all.stream().filter(j -> j.atMs() >= sinceMs).limit(limit).toList(), all.isEmpty() ? null : all.get(0));
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.serverimages.ServerImageSet loadServerImages() throws Exception {
        check();
        return serverImages;
    }

    @Override
    public synchronized void saveServerImages(com.mrgregles.bsp_server_utilities.serverimages.ServerImageSet set, Map<String, byte[]> bytes) throws Exception {
        check();
        bytes.forEach((id, png) -> serverImageBytes.putIfAbsent(id, png.clone()));
        serverImageBytes.keySet().retainAll(set.ids());
        serverImages = set;
    }

    @Override
    public synchronized void saveWebServer(String server, long atMs, String snapshot) throws Exception {
        check();
        webServers.put(server, new WebServer(server, atMs, snapshot));
    }

    private final java.util.LinkedHashMap<String, WebChat> webChat = new java.util.LinkedHashMap<>();

    public final List<Ban> bans = new ArrayList<>();

    @Override
    public synchronized List<Ban> loadActiveBans(long now) throws Exception {
        check();
        return bans.stream().filter(b -> b.activeAt(now)).toList();
    }

    @Override
    public synchronized long insertBan(Ban b) throws Exception {
        check();
        long id = bans.size() + 1L;
        bans.add(new Ban(id, b.player(), b.name(), b.reason(), b.note(), b.by(), b.atMs(), b.untilMs(), 0, "", 0));
        return id;
    }

    @Override
    public synchronized boolean liftBan(long id, String by, long at) throws Exception {
        check();
        for (int i = 0; i < bans.size(); i++) {
            Ban b = bans.get(i);
            if (b.id() == id && b.liftedAt() == 0) {
                bans.set(i, new Ban(b.id(), b.player(), b.name(), b.reason(), b.note(), b.by(), b.atMs(), b.untilMs(), at, by, b.editedAt()));
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean editBan(long id, long untilMs, String reason, String note, long at) throws Exception {
        check();
        for (int i = 0; i < bans.size(); i++) {
            Ban b = bans.get(i);
            if (b.id() == id && b.liftedAt() == 0) {
                bans.set(i, new Ban(b.id(), b.player(), b.name(), reason, note, b.by(), b.atMs(), untilMs, 0, "", at));
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized Ban ban(long id) {
        return bans.stream().filter(b -> b.id() == id).findFirst().orElse(null);
    }

    /** The item picture exports and the newest one's parts (tests read them). */
    public final List<WebItemsExport> webItems = new ArrayList<>();
    public final List<byte[]> webItemParts = new ArrayList<>();

    @Override
    public synchronized void saveWebItems(WebItemsExport export, List<byte[]> parts) throws Exception {
        check();
        webItems.add(export);
        webItemParts.clear();
        webItemParts.addAll(parts);
    }

    @Override
    public synchronized void saveWebChat(List<WebChat> lines) throws Exception {
        check();
        lines.forEach(l -> webChat.putIfAbsent(l.uid(), l));
    }

    /** Every chat line kept, oldest first (tests). */
    public synchronized List<WebChat> webChat() {
        return new ArrayList<>(webChat.values());
    }

    @Override
    public synchronized List<WebServer> loadWebServers() throws Exception {
        check();
        return new ArrayList<>(webServers.values());
    }

    @Override
    public synchronized byte[] loadServerImage(String id) throws Exception {
        check();
        byte[] png = serverImageBytes.get(id);
        return png == null ? null : png.clone();
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.players.PlayersSettings loadPlayersSettings() throws Exception {
        check();
        return playersSettings;
    }

    @Override
    public synchronized void savePlayersSettings(com.mrgregles.bsp_server_utilities.players.PlayersSettings settings) throws Exception {
        check();
        playersSettings = settings;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.check.CheckRules loadCheckRules() throws Exception {
        check();
        return checkRules;
    }

    @Override
    public synchronized void saveCheckRules(com.mrgregles.bsp_server_utilities.check.CheckRules rules) throws Exception {
        check();
        checkRules = rules;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.check.TrustedSetup loadTrustedSetup() throws Exception {
        check();
        return trustedSetup;
    }

    @Override
    public synchronized void saveTrustedSetup(com.mrgregles.bsp_server_utilities.check.TrustedSetup setup) throws Exception {
        check();
        trustedSetup = setup;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.despawn.DespawnSettings loadDespawnSettings() throws Exception {
        check();
        return despawnSettings;
    }

    @Override
    public synchronized void saveDespawnSettings(com.mrgregles.bsp_server_utilities.despawn.DespawnSettings settings) throws Exception {
        check();
        despawnSettings = settings;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.mobs.MobSettings loadMobSettings() throws Exception {
        check();
        return mobSettings;
    }

    @Override
    public synchronized void saveMobSettings(com.mrgregles.bsp_server_utilities.mobs.MobSettings settings) throws Exception {
        check();
        mobSettings = settings;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.chunks.ChunkSettings loadChunkSettings() throws Exception {
        check();
        return chunkSettings;
    }

    @Override
    public synchronized void saveChunkSettings(com.mrgregles.bsp_server_utilities.chunks.ChunkSettings settings) throws Exception {
        check();
        chunkSettings = settings;
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

    private final Map<String, List<com.mrgregles.bsp_server_utilities.rooms.RoomRow>> rooms = new java.util.LinkedHashMap<>();
    private final Map<String, Long> roomsHeard = new java.util.LinkedHashMap<>();

    @Override
    public synchronized void saveRooms(String server, long atMs, List<com.mrgregles.bsp_server_utilities.rooms.RoomRow> rows) throws Exception {
        check();
        rooms.put(server, List.copyOf(rows));
        roomsHeard.put(server, atMs);
    }

    @Override
    public synchronized void touchRooms(String server, long atMs) throws Exception {
        check();
        roomsHeard.put(server, atMs);
    }

    @Override
    public synchronized int forgetRooms(String server) throws Exception {
        check();
        int n = 0;
        for (java.util.Iterator<Map.Entry<String, List<com.mrgregles.bsp_server_utilities.rooms.RoomRow>>> it = rooms.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<com.mrgregles.bsp_server_utilities.rooms.RoomRow>> e = it.next();
            if (e.getKey().equalsIgnoreCase(server)) { // as MySQL compares the key
                n += e.getValue().size();
                it.remove();
            }
        }
        roomsHeard.keySet().removeIf(k -> k.equalsIgnoreCase(server));
        return n;
    }

    @Override
    public synchronized com.mrgregles.bsp_server_utilities.rooms.RoomTable loadRooms() throws Exception {
        check();
        List<com.mrgregles.bsp_server_utilities.rooms.RoomRow> all = new ArrayList<>();
        rooms.values().forEach(all::addAll);
        return new com.mrgregles.bsp_server_utilities.rooms.RoomTable(Map.copyOf(roomsHeard), List.copyOf(all));
    }

    @Override
    public String describe() {
        return "memory";
    }

    @Override
    public void close() {
    }
}
