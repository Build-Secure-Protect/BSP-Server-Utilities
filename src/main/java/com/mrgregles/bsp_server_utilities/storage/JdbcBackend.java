package com.mrgregles.bsp_server_utilities.storage;

import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffData;
import com.mrgregles.bsp_server_utilities.access.StaffGroups;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.rules.Agreement;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MySQL through plain JDBC, as BSP RankUp and BSP Region Guard do it. No driver is bundled: BSP-Core's full jar
 * provides one on Forge servers and Mohist ships its own. One connection, reopened when it fails; used only from the
 * storage's worker thread. Tables: {@code <prefix>notices}; {@code <prefix>rules} (one row), {@code <prefix>rule_items},
 * {@code <prefix>rules_agreed}; {@code <prefix>staff_settings} (one row), {@code <prefix>staff_groups}, {@code <prefix>staff_seen}; {@code <prefix>log}, {@code <prefix>log_switches}; {@code <prefix>afk_settings} (one row); {@code <prefix>chunk_settings} (one row); {@code <prefix>despawn_settings} (one row).
 */
public final class JdbcBackend implements StorageBackend {
    private final StorageSettings.Database settings;
    private final String url, prefix;
    private Connection connection;

    public JdbcBackend(StorageSettings.Database settings) {
        this.settings = settings;
        this.url = "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?useSSL=" + settings.ssl() + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=5000&socketTimeout=15000";
        this.prefix = settings.tablePrefix();
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // reopening anyway
                }
            }
            connection = DriverManager.getConnection(url, settings.user(), settings.password());
        }
        return connection;
    }

    @Override
    public void init() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "notices (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uid VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL, source VARCHAR(64) NOT NULL, kind VARCHAR(32) NOT NULL, payload MEDIUMTEXT, KEY created (created_at))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "rules (id INT NOT NULL PRIMARY KEY, heading VARCHAR(64) NOT NULL, intro VARCHAR(320) NOT NULL, leave_message VARCHAR(320) NOT NULL, version INT NOT NULL, revision BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "rule_items (pos INT NOT NULL PRIMARY KEY, title VARCHAR(96) NOT NULL, text TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "rules_agreed (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, version INT NOT NULL, agreed_at BIGINT NOT NULL, server VARCHAR(64) NOT NULL, KEY version (version))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "staff_settings (id INT NOT NULL PRIMARY KEY, operators BOOLEAN NOT NULL, revision BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "staff_groups (pos INT NOT NULL PRIMARY KEY, grp VARCHAR(64) NOT NULL, label VARCHAR(48) NOT NULL, colour INT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "staff_seen (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, seen_at BIGINT NOT NULL, server VARCHAR(64) NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "log (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uid VARCHAR(36) NOT NULL, at_ms BIGINT NOT NULL, server VARCHAR(64) NOT NULL, actor_id VARCHAR(36) NOT NULL, actor VARCHAR(32) NOT NULL, role_group VARCHAR(64) NOT NULL, role_label VARCHAR(48) NOT NULL, colour INT NOT NULL, kind VARCHAR(16) NOT NULL, text TEXT NOT NULL, UNIQUE KEY uid (uid), KEY at (at_ms))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "log_switches (cmd VARCHAR(64) NOT NULL PRIMARY KEY, enabled BOOLEAN NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "despawn_settings (id INT NOT NULL PRIMARY KEY, settings MEDIUMTEXT NOT NULL, revision BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "chunk_settings (id INT NOT NULL PRIMARY KEY, settings TEXT NOT NULL, revision BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "afk_settings (id INT NOT NULL PRIMARY KEY, enabled BOOLEAN NOT NULL, afk_minutes INT NOT NULL, kick BOOLEAN NOT NULL, kick_minutes INT NOT NULL, message VARCHAR(320) NOT NULL, revision BIGINT NOT NULL)");
        }
    }

    @Override
    public long publish(Notice n) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "notices (uid, created_at, source, kind, payload) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, n.uid());
            ps.setLong(2, n.createdAt());
            ps.setString(3, n.source());
            ps.setString(4, n.kind());
            ps.setString(5, n.payload());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    @Override
    public List<Notice> fetchNoticesAfter(long id, int limit) throws SQLException {
        List<Notice> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, uid, created_at, source, kind, payload FROM " + prefix + "notices WHERE id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, id);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Notice(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)));
                }
            }
        }
        return out;
    }

    @Override
    public long latestNoticeId() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + prefix + "notices")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override
    public int pruneNotices(long beforeMs) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + "notices WHERE created_at < ?")) {
            ps.setLong(1, beforeMs);
            return ps.executeUpdate();
        }
    }

    @Override
    @Nullable
    public RuleSet loadRules() throws SQLException {
        String heading, intro, leave;
        int version;
        long revision;
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT heading, intro, leave_message, version, revision FROM " + prefix + "rules WHERE id = 1")) {
            if (!rs.next()) {
                return null;
            }
            heading = rs.getString(1);
            intro = rs.getString(2);
            leave = rs.getString(3);
            version = rs.getInt(4);
            revision = rs.getLong(5);
        }
        List<RuleSet.Rule> rules = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT title, text FROM " + prefix + "rule_items ORDER BY pos")) {
            while (rs.next()) {
                rules.add(new RuleSet.Rule(rs.getString(1), rs.getString(2)));
            }
        }
        return new RuleSet(heading, intro, leave, rules, version, revision).sanitised();
    }

    @Override
    public void saveRules(RuleSet r) throws SQLException {
        Connection c = conn();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + prefix + "rules (id, heading, intro, leave_message, version, revision) VALUES (1, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE heading = VALUES(heading), intro = VALUES(intro), leave_message = VALUES(leave_message), version = VALUES(version), revision = VALUES(revision)")) {
                ps.setString(1, r.heading());
                ps.setString(2, r.intro());
                ps.setString(3, r.leaveMessage());
                ps.setInt(4, r.version());
                ps.setLong(5, r.revision());
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM " + prefix + "rule_items");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + prefix + "rule_items (pos, title, text) VALUES (?, ?, ?)")) {
                for (int i = 0; i < r.rules().size(); i++) {
                    ps.setInt(1, i);
                    ps.setString(2, r.rules().get(i).title());
                    ps.setString(3, r.rules().get(i).text());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    @Override
    @Nullable
    public Agreement loadAgreement(UUID player) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT version, agreed_at, server, name FROM " + prefix + "rules_agreed WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Agreement(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4)) : null;
            }
        }
    }

    @Override
    public void saveAgreement(UUID player, @Nullable Agreement a) throws SQLException {
        if (a == null) {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + "rules_agreed WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "rules_agreed (uuid, name, version, agreed_at, server) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), version = VALUES(version), agreed_at = VALUES(agreed_at), server = VALUES(server)")) {
            ps.setString(1, player.toString());
            ps.setString(2, a.name());
            ps.setInt(3, a.version());
            ps.setLong(4, a.agreedAtMs());
            ps.setString(5, a.server());
            ps.executeUpdate();
        }
    }

    @Override
    public int countAgreed(int version) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT COUNT(*) FROM " + prefix + "rules_agreed WHERE version >= ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    @Nullable
    public com.mrgregles.bsp_server_utilities.afk.AfkSettings loadAfkSettings() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT enabled, afk_minutes, kick, kick_minutes, message, revision FROM " + prefix + "afk_settings WHERE id = 1")) {
            if (!rs.next()) {
                return null;
            }
            return new com.mrgregles.bsp_server_utilities.afk.AfkSettings(rs.getBoolean(1), rs.getInt(2), rs.getBoolean(3), rs.getInt(4), rs.getString(5), rs.getLong(6)).sanitised();
        }
    }

    @Override
    public void saveAfkSettings(com.mrgregles.bsp_server_utilities.afk.AfkSettings s) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "afk_settings (id, enabled, afk_minutes, kick, kick_minutes, message, revision) VALUES (1, ?, ?, ?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), afk_minutes = VALUES(afk_minutes), kick = VALUES(kick), kick_minutes = VALUES(kick_minutes), message = VALUES(message), revision = VALUES(revision)")) {
            ps.setBoolean(1, s.enabled());
            ps.setInt(2, s.afkMinutes());
            ps.setBoolean(3, s.kick());
            ps.setInt(4, s.kickMinutes());
            ps.setString(5, s.message());
            ps.setLong(6, s.revision());
            ps.executeUpdate();
        }
    }

    @Override
    @Nullable
    public com.mrgregles.bsp_server_utilities.despawn.DespawnSettings loadDespawnSettings() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT settings FROM " + prefix + "despawn_settings WHERE id = 1")) {
            return rs.next() ? com.mrgregles.bsp_server_utilities.despawn.DespawnSettings.decode(rs.getString(1)) : null;
        }
    }

    @Override
    public void saveDespawnSettings(com.mrgregles.bsp_server_utilities.despawn.DespawnSettings s) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "despawn_settings (id, settings, revision) VALUES (1, ?, ?)"
                + " ON DUPLICATE KEY UPDATE settings = VALUES(settings), revision = VALUES(revision)")) {
            ps.setString(1, s.encode());
            ps.setLong(2, s.revision());
            ps.executeUpdate();
        }
    }

    @Override
    @Nullable
    public com.mrgregles.bsp_server_utilities.chunks.ChunkSettings loadChunkSettings() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT settings FROM " + prefix + "chunk_settings WHERE id = 1")) {
            return rs.next() ? com.mrgregles.bsp_server_utilities.chunks.ChunkSettings.decode(rs.getString(1)) : null;
        }
    }

    @Override
    public void saveChunkSettings(com.mrgregles.bsp_server_utilities.chunks.ChunkSettings s) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "chunk_settings (id, settings, revision) VALUES (1, ?, ?)"
                + " ON DUPLICATE KEY UPDATE settings = VALUES(settings), revision = VALUES(revision)")) {
            ps.setString(1, s.encode());
            ps.setLong(2, s.revision());
            ps.executeUpdate();
        }
    }

    @Override
    @Nullable
    public StaffGroups loadStaffGroups() throws SQLException {
        boolean operators;
        long revision;
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT operators, revision FROM " + prefix + "staff_settings WHERE id = 1")) {
            if (!rs.next()) {
                return null;
            }
            operators = rs.getBoolean(1);
            revision = rs.getLong(2);
        }
        List<Staff.Role> roles = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT grp, label, colour FROM " + prefix + "staff_groups ORDER BY pos")) {
            while (rs.next()) {
                roles.add(new Staff.Role(rs.getString(1), rs.getString(2), rs.getInt(3)));
            }
        }
        return new StaffGroups(roles, operators, revision).sanitised();
    }

    @Override
    public void saveStaffGroups(StaffGroups g) throws SQLException {
        Connection c = conn();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + prefix + "staff_settings (id, operators, revision) VALUES (1, ?, ?) ON DUPLICATE KEY UPDATE operators = VALUES(operators), revision = VALUES(revision)")) {
                ps.setBoolean(1, g.operators());
                ps.setLong(2, g.revision());
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM " + prefix + "staff_groups");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + prefix + "staff_groups (pos, grp, label, colour) VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i < g.roles().size(); i++) {
                    ps.setInt(1, i);
                    ps.setString(2, g.roles().get(i).group());
                    ps.setString(3, g.roles().get(i).label());
                    ps.setInt(4, g.roles().get(i).colour());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    @Override
    public void saveSeen(UUID player, StaffData.Seen s) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "staff_seen (uuid, name, seen_at, server) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = IF(VALUES(seen_at) >= seen_at, VALUES(name), name), server = IF(VALUES(seen_at) >= seen_at, VALUES(server), server), seen_at = GREATEST(seen_at, VALUES(seen_at))")) {
            ps.setString(1, player.toString());
            ps.setString(2, s.name());
            ps.setLong(3, s.atMs());
            ps.setString(4, s.server());
            ps.executeUpdate();
        }
    }

    @Override
    public Map<UUID, StaffData.Seen> loadSeen() throws SQLException {
        Map<UUID, StaffData.Seen> out = new HashMap<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, name, seen_at, server FROM " + prefix + "staff_seen")) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), new StaffData.Seen(rs.getString(2), rs.getLong(3), rs.getString(4)));
                } catch (IllegalArgumentException ignored) {
                    // not a player id
                }
            }
        }
        return out;
    }

    @Override
    public void insertLog(LogEntry e) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT IGNORE INTO " + prefix + "log (uid, at_ms, server, actor_id, actor, role_group, role_label, colour, kind, text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, e.uid());
            ps.setLong(2, e.atMs());
            ps.setString(3, e.server());
            ps.setString(4, e.actorId());
            ps.setString(5, e.actor());
            ps.setString(6, e.roleGroup());
            ps.setString(7, e.roleLabel());
            ps.setInt(8, e.colour());
            ps.setString(9, e.kind());
            ps.setString(10, e.text());
            ps.executeUpdate();
        }
    }

    @Override
    public List<LogEntry> loadLog(long sinceMs, int limit) throws SQLException {
        List<LogEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, uid, at_ms, server, actor_id, actor, role_group, role_label, colour, kind, text FROM " + prefix + "log WHERE at_ms >= ? ORDER BY at_ms DESC LIMIT ?")) {
            ps.setLong(1, sinceMs);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LogEntry(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getString(10), rs.getString(11)));
                }
            }
        }
        return out;
    }

    @Override
    public int pruneLog(long beforeMs) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + "log WHERE at_ms < ?")) {
            ps.setLong(1, beforeMs);
            return ps.executeUpdate();
        }
    }

    @Override
    public void saveLogSwitch(String root, boolean on) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "log_switches (cmd, enabled) VALUES (?, ?) ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)")) {
            ps.setString(1, root);
            ps.setBoolean(2, on);
            ps.executeUpdate();
        }
    }

    @Override
    public Map<String, Boolean> loadLogSwitches() throws SQLException {
        Map<String, Boolean> out = new HashMap<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT cmd, enabled FROM " + prefix + "log_switches")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getBoolean(2));
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return settings.describe();
    }

    @Override
    public void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
