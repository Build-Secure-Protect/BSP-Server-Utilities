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
 * not there (or as its safety net), then each feature's own tables, added here as the feature is built.
 */
public interface StorageBackend {
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

    String describe();

    void close() throws Exception;
}
