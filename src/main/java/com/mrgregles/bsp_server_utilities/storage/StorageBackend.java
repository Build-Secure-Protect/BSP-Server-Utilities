package com.mrgregles.bsp_server_utilities.storage;

import com.mrgregles.bsp_server_utilities.access.StaffData;
import com.mrgregles.bsp_server_utilities.access.StaffGroups;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.rules.Agreement;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the shared storage needs from a database: the notice table that carries messages between servers when Redis is
 * not there (or as its safety net), then each feature's own tables, added here as the feature is built. Private staff
 * messages (SU-105) have their methods in {@link com.mrgregles.bsp_server_utilities.talk.TalkStore}, which a world's save
 * also keeps on a server without shared storage.
 */
public interface StorageBackend extends com.mrgregles.bsp_server_utilities.talk.TalkStore {
    /** A notice between servers; {@code id} is 0 until the table assigns one. */
    record Notice(long id, String uid, long createdAt, String source, String kind, String payload) {
    }

    void init() throws Exception;

    long publish(Notice notice) throws Exception;

    List<Notice> fetchNoticesAfter(long id, int limit) throws Exception;

    long latestNoticeId() throws Exception;

    /** Deletes notices created before the given time; returns how many went. */
    int pruneNotices(long beforeMs) throws Exception;

    // ------------------------------------------------------------------ rules (SU-2)

    /** The shared rules, or null when none were ever saved. */
    @Nullable
    RuleSet loadRules() throws Exception;

    /** Replaces the shared rules (heading, texts, version, revision and every rule). */
    void saveRules(RuleSet rules) throws Exception;

    @Nullable
    Agreement loadAgreement(UUID player) throws Exception;

    /** Writes a player's agreement; null deletes it. */
    void saveAgreement(UUID player, @Nullable Agreement agreement) throws Exception;

    /** How many players agreed to the given version or a later one. */
    int countAgreed(int version) throws Exception;

    // ------------------------------------------------------------------ staff (SU-9)

    /** The network's staff groups, or null when none were ever saved. */
    @Nullable
    StaffGroups loadStaffGroups() throws Exception;

    void saveStaffGroups(StaffGroups groups) throws Exception;

    /** Records when a staff member was last seen (kept only when newer). */
    void saveSeen(UUID player, StaffData.Seen seen) throws Exception;

    Map<UUID, StaffData.Seen> loadSeen() throws Exception;

    // ------------------------------------------------------------------ the action log (SU-11)

    void insertLog(LogEntry entry) throws Exception;

    /** Entries at or after {@code sinceMs}, newest first, at most {@code limit}. */
    List<LogEntry> loadLog(long sinceMs, int limit) throws Exception;

    int pruneLog(long beforeMs) throws Exception;

    void saveLogSwitch(String root, boolean on) throws Exception;

    Map<String, Boolean> loadLogSwitches() throws Exception;

    // ------------------------------------------------------------------ AFK detection (SU-13)

    /** The network's AFK settings, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.afk.AfkSettings loadAfkSettings() throws Exception;

    void saveAfkSettings(com.mrgregles.bsp_server_utilities.afk.AfkSettings settings) throws Exception;

    // ------------------------------------------------------------------ chunk unloading (SU-15)

    /** The network's chunk unloading settings, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.chunks.ChunkSettings loadChunkSettings() throws Exception;

    void saveChunkSettings(com.mrgregles.bsp_server_utilities.chunks.ChunkSettings settings) throws Exception;

    // ------------------------------------------------------------------ despawn times (SU-17)

    /** The network's despawn settings, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.despawn.DespawnSettings loadDespawnSettings() throws Exception;

    void saveDespawnSettings(com.mrgregles.bsp_server_utilities.despawn.DespawnSettings settings) throws Exception;

    // ------------------------------------------------------------------ mob limits (SU-62)

    /** The network's mob limits, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.mobs.MobSettings loadMobSettings() throws Exception;

    void saveMobSettings(com.mrgregles.bsp_server_utilities.mobs.MobSettings settings) throws Exception;

    // ------------------------------------------------------------------ the client check (SU-20)

    /** The network's client check rules, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.check.CheckRules loadCheckRules() throws Exception;

    void saveCheckRules(com.mrgregles.bsp_server_utilities.check.CheckRules rules) throws Exception;

    /** The network's trusted setup, or null when none was ever taken. */
    @Nullable
    com.mrgregles.bsp_server_utilities.check.TrustedSetup loadTrustedSetup() throws Exception;

    void saveTrustedSetup(com.mrgregles.bsp_server_utilities.check.TrustedSetup setup) throws Exception;

    // ------------------------------------------------------------------ the setup guide (SU-22)

    /** The network's setup guide state, or null when none was ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.setup.SetupState loadSetupState() throws Exception;

    void saveSetupState(com.mrgregles.bsp_server_utilities.setup.SetupState state) throws Exception;

    // ------------------------------------------------------------------ the player counter (SU-24)

    /** Records a player's first join on the network; false when the player was already on the list (one row per UUID). */
    boolean insertPlayer(com.mrgregles.bsp_server_utilities.players.Joined joined) throws Exception;

    /** How many players the list holds, the first joins at or after {@code sinceMs} (newest first, at most {@code limit}) and the newest of all. */
    com.mrgregles.bsp_server_utilities.players.PlayerCounts playerCounts(long sinceMs, int limit) throws Exception;

    /** A player who ever joined the network, by name in any case (private staff messages to someone offline, SU-107), or null. */
    @Nullable
    com.mrgregles.bsp_server_utilities.players.Joined playerByName(String name) throws Exception;

    /** The network's player counter settings, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.players.PlayersSettings loadPlayersSettings() throws Exception;

    void savePlayersSettings(com.mrgregles.bsp_server_utilities.players.PlayersSettings settings) throws Exception;

    // ------------------------------------------------------------------ Support BSP (SU-89)

    /** The network's Support BSP settings, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.support.SupportSettings loadSupportSettings() throws Exception;

    void saveSupportSettings(com.mrgregles.bsp_server_utilities.support.SupportSettings settings) throws Exception;

    // ------------------------------------------------------------------ tutorials (SU-137)

    /** A tutorial's copy as the network's table keeps it: its stage ("draft" or "published") and the copy. */
    record StoredTutorial(String stage, com.mrgregles.bsp_server_utilities.tutorial.Tutorial tutorial) {
    }

    /** Every tutorial copy in the network's table, deleted markers included; rows that cannot be read are left out. */
    List<StoredTutorial> loadTutorials() throws Exception;

    /** One copy by stage and id, or null. */
    @Nullable
    com.mrgregles.bsp_server_utilities.tutorial.Tutorial loadTutorial(String stage, String id) throws Exception;

    /** Writes a copy; a row with the same or a newer revision is kept instead. */
    void saveTutorial(String stage, com.mrgregles.bsp_server_utilities.tutorial.Tutorial tutorial) throws Exception;

    /** One player's places in the tutorials (SU-138). */
    List<com.mrgregles.bsp_server_utilities.tutorial.TutorialProgress> loadTutorialProgress(UUID player) throws Exception;

    /** Writes one player's place in one tutorial (the newest wins by time). */
    void saveTutorialProgress(com.mrgregles.bsp_server_utilities.tutorial.TutorialProgress progress) throws Exception;

    /** Every place of every player in one tutorial, for the TUTORIAL tab's counts. */
    List<com.mrgregles.bsp_server_utilities.tutorial.TutorialProgress> tutorialProgressOf(String tutorial) throws Exception;

    /** A player's first join on the network by id, or null (SU-138: who counts as new for the first-join tutorial). */
    @Nullable
    com.mrgregles.bsp_server_utilities.players.Joined playerById(UUID id) throws Exception;

    // ------------------------------------------------------------------ server images (SU-26)

    /** The network's server images, or null when none were ever saved. */
    @Nullable
    com.mrgregles.bsp_server_utilities.serverimages.ServerImageSet loadServerImages() throws Exception;

    /** Saves the set with the bytes of its images (by id; images already stored are kept), and drops stored images it no longer uses. */
    void saveServerImages(com.mrgregles.bsp_server_utilities.serverimages.ServerImageSet set, Map<String, byte[]> bytes) throws Exception;

    /** An image's PNG bytes by id, or null. */
    @Nullable
    byte[] loadServerImage(String id) throws Exception;

    // ------------------------------------------------------------------ the staff web panel (SU-32)

    /** A server's newest snapshot for the staff web panel: the JSON documented in docs/API.md, and when it was sent. */
    record WebServer(String server, long atMs, String snapshot) {
    }

    /** Replaces this server's row (one row per server). */
    void saveWebServer(String server, long atMs, String snapshot) throws Exception;

    List<WebServer> loadWebServers() throws Exception;

    /**
     * A public chat line for the staff web panel (SU-36): {@code line} is the line as the panel reads it (signed JSON, see
     * docs/API.md); the plain columns are for searching. The panel adds its own lines to the same table and drops lines
     * older than 30 days (D263).
     */
    record WebChat(String uid, long atMs, String server, String name, String text, String line) {
    }

    /** Adds lines; a line whose {@code uid} is stored already is skipped. */
    void saveWebChat(List<WebChat> lines) throws Exception;

    /**
     * The item pictures for the staff web panel (SU-41, D340, D373): one export from an admin's game, a zip (pictures,
     * tabs, English names) stored in {@code parts} of at most {@link #WEB_ITEMS_PART} bytes. The newest export's parts are
     * kept; older exports keep their row (the panel's list of the last exports) but lose their parts.
     */
    record WebItemsExport(long id, long atMs, String by, String server, int items, int tabs, int mods, int size, int parts, String sha256) {
    }

    int WEB_ITEMS_PART = 512 * 1024;

    void saveWebItems(WebItemsExport export, List<byte[]> parts) throws Exception;

    /**
     * A network ban (SU-44, D255, D350 to D356): who, why (the player sees it), a note only staff see, who banned and when,
     * when it ends ({@code untilMs} 0: permanent), and when and by whom it was lifted ({@code liftedAt} 0: not lifted).
     * Rows are kept for good (D356).
     */
    record Ban(long id, java.util.UUID player, String name, String reason, String note, String by, long atMs, long untilMs, long liftedAt, String liftedBy, long editedAt) {
        public boolean activeAt(long now) {
            return liftedAt == 0 && (untilMs == 0 || untilMs > now);
        }
    }

    /** Bans neither lifted nor ended at {@code now}. */
    List<Ban> loadActiveBans(long now) throws Exception;

    /** Adds a ban; its id. */
    long insertBan(Ban b) throws Exception;

    /** Lifts a ban that is not lifted yet; false when there was none. */
    boolean liftBan(long id, String by, long at) throws Exception;

    /** Changes a ban's end, reason and note; false when there is no such ban still in force. */
    boolean editBan(long id, long untilMs, String reason, String note, long at) throws Exception;

    /** One ban by id, or null. */
    @org.jetbrains.annotations.Nullable
    Ban ban(long id) throws Exception;

    // ------------------------------------------------------------------ Compact Machines rooms (SU-54, R-2)

    /** Replaces every row of {@code server} in {@code <prefix>cm_rooms} with {@code rows}, and records that it wrote at {@code atMs}. */
    void saveRooms(String server, long atMs, List<com.mrgregles.bsp_server_utilities.rooms.RoomRow> rows) throws Exception;

    /** Records that {@code server} was heard from at {@code atMs}, its rows unchanged. */
    void touchRooms(String server, long atMs) throws Exception;

    /** Drops every row of {@code server} from both room tables (a server renamed or retired, D456); how many rooms were dropped. */
    int forgetRooms(String server) throws Exception;

    /** Every server's rooms and when each last wrote. */
    com.mrgregles.bsp_server_utilities.rooms.RoomTable loadRooms() throws Exception;

    // ------------------------------------------------------------------ totem steals for the staff panel (SU-95, D613 to D622)

    /**
     * One ended steal of a BSP-Core Shatter Totem, written by the game server where it ran (D616): where (server,
     * dimension, block), who stole from whom ({@code owner} null for an unclaimed totem), what it was ({@code source}
     * "totem" or "forge" for a Totem Forge's pour, else "other"; {@code tier} 1 to 5, 0 unknown; {@code points} what the
     * totem is worth on the board, 0 unknown), when it started and ended ({@code startedMs} 0 when this server did not see
     * the start), how long BSP-Core said it would take ({@code expectedSeconds}, 0 unknown), and whether it worked
     * ({@code reason}: BSP-Core's translation key for a failure, empty on success); {@code ticks} the server ticks between its
     * start and end as this server counted them (0 when the start was not seen), which is what BSP-Core counts a steal in;
     * {@code line} is all of it signed with the
     * action key, which is what the staff panel believes (a row without a good signature is not shown). Kept 90 days (D617).
     */
    record Steal(String uid, String server, String dimension, int x, int y, int z, UUID thief, String thiefName, @Nullable UUID owner, String ownerName,
                 String source, int tier, int points, long startedMs, long endedMs, int expectedSeconds, boolean success, String reason, int ticks, String line) {
        /** The same steal with {@code line}: its fields signed with the action key (the {@code steal} end message), empty without a key. */
        public Steal withLine(String signed) {
            return new Steal(uid, server, dimension, x, y, z, thief, thiefName, owner, ownerName, source, tier, points, startedMs, endedMs, expectedSeconds, success, reason,
                    ticks, signed == null ? "" : signed);
        }
    }

    /** Adds steals; a steal whose {@code uid} is stored already is skipped. */
    void insertSteals(List<Steal> steals) throws Exception;

    /** Steals that ended at or after {@code sinceMs}, newest first, at most {@code limit}. */
    List<Steal> loadSteals(long sinceMs, int limit) throws Exception;

    /** Deletes steals that ended before the given time; returns how many went. */
    int pruneSteals(long beforeMs) throws Exception;

    // ------------------------------------------------------------------ where each player was last seen, for the staff panel (SU-114, D656, D657)

    /**
     * Where a player was last seen on the network, one row per player in {@code <prefix>seen}: their name then, when
     * ({@code atMs}), on which server, in which dimension and at which block; {@code kind} {@link #SEEN_LOGOUT} when it is
     * their logout (a server stop counts) or {@link #SEEN_REFRESH} for the record written every 5 minutes while online
     * (still the newest after a crash); {@code line} is all of it signed with the action key, which is what the staff
     * panel believes (empty without a key).
     */
    record Seen(UUID player, String name, long atMs, String server, String dimension, int x, int y, int z, String kind, String line) {
    }

    String SEEN_LOGOUT = "logout", SEEN_REFRESH = "seen";

    /** Writes each row unless the table already holds a newer one for that player (a row as new as the stored one replaces it). */
    void saveSeen(List<Seen> rows) throws Exception;

    /** A player's row, or null when none was written. */
    @Nullable
    Seen loadSeen(UUID player) throws Exception;

    String describe();

    void close() throws Exception;
}
