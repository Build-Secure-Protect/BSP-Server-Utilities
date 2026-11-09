package com.mrgregles.bsp_staff_panel.store;

import com.mrgregles.bsp_staff_panel.auth.Rank;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The panel's records in the network's MySQL / MariaDB, beside the game servers' tables: {@code <prefix>panel_staff},
 * {@code <prefix>panel_sessions}, {@code <prefix>panel_links}, {@code <prefix>panel_log}. One connection, reopened when it
 * drops; every call is synchronized (a handful of staff never need more).
 */
public final class JdbcStore implements PanelStore {
    private final String url, user, password, prefix;
    private Connection conn;

    public JdbcStore(String host, int port, String database, String user, String password, boolean ssl, String prefix) {
        if (!prefix.matches("[A-Za-z0-9_]{0,32}")) {
            throw new IllegalArgumentException("the table prefix may hold letters, digits and underscores only");
        }
        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database + (ssl ? "?sslMode=verify-full" : "");
        this.user = user;
        this.password = password;
        this.prefix = prefix;
    }

    private Connection conn() throws SQLException {
        if (conn == null || !conn.isValid(2)) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // reopening anyway
                }
            }
            conn = DriverManager.getConnection(url, user, password);
        }
        return conn;
    }

    @Override
    public synchronized void init() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_staff (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, email VARCHAR(254) NOT NULL,"
                    + " mc_name VARCHAR(16) NOT NULL, rnk VARCHAR(16) NOT NULL, password VARCHAR(255) NULL, totp TEXT NULL, totp_step BIGINT NOT NULL,"
                    + " backup TEXT NOT NULL, failed INT NOT NULL, locked_until BIGINT NOT NULL, created_at BIGINT NOT NULL, created_by VARCHAR(254) NOT NULL,"
                    + " last_login BIGINT NOT NULL, previous_login BIGINT NOT NULL, active BOOLEAN NOT NULL, UNIQUE KEY email (email)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_sessions (token CHAR(64) NOT NULL PRIMARY KEY, staff BIGINT NOT NULL,"
                    + " created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, KEY staff (staff)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_links (token CHAR(64) NOT NULL PRIMARY KEY, staff BIGINT NOT NULL,"
                    + " kind VARCHAR(8) NOT NULL, expires_at BIGINT NOT NULL, KEY staff (staff)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_log (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, at_ms BIGINT NOT NULL,"
                    + " who VARCHAR(254) NOT NULL, what TEXT NOT NULL, KEY at (at_ms)) DEFAULT CHARSET=utf8mb4");
        }
    }

    private static final String COLS = "id, email, mc_name, rnk, password, totp, totp_step, backup, failed, locked_until, created_at, created_by, last_login, previous_login, active";

    private static Account read(ResultSet rs) throws SQLException {
        Account a = new Account();
        a.id = rs.getLong(1);
        a.email = rs.getString(2);
        a.mcName = rs.getString(3);
        a.rank = Rank.parse(rs.getString(4));
        a.password = rs.getString(5);
        a.totp = rs.getString(6);
        a.totpStep = rs.getLong(7);
        String b = rs.getString(8);
        a.backup = b.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(b.split(",")));
        a.failed = rs.getInt(9);
        a.lockedUntil = rs.getLong(10);
        a.createdAt = rs.getLong(11);
        a.createdBy = rs.getString(12);
        a.lastLoginAt = rs.getLong(13);
        a.previousLoginAt = rs.getLong(14);
        a.active = rs.getBoolean(15);
        return a;
    }

    @Override
    public synchronized Optional<Account> accountByEmail(String email) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT " + COLS + " FROM " + prefix + "panel_staff WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized Optional<Account> account(long id) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT " + COLS + " FROM " + prefix + "panel_staff WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized List<Account> accounts() throws SQLException {
        List<Account> out = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT " + COLS + " FROM " + prefix + "panel_staff ORDER BY id")) {
            while (rs.next()) {
                out.add(read(rs));
            }
        }
        return out;
    }

    private static void bind(PreparedStatement ps, Account a) throws SQLException {
        ps.setString(1, a.email);
        ps.setString(2, a.mcName);
        ps.setString(3, a.rank.name());
        ps.setString(4, a.password);
        ps.setString(5, a.totp);
        ps.setLong(6, a.totpStep);
        ps.setString(7, String.join(",", a.backup));
        ps.setInt(8, a.failed);
        ps.setLong(9, a.lockedUntil);
        ps.setLong(10, a.createdAt);
        ps.setString(11, a.createdBy);
        ps.setLong(12, a.lastLoginAt);
        ps.setLong(13, a.previousLoginAt);
        ps.setBoolean(14, a.active);
    }

    @Override
    public synchronized long insert(Account a) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "panel_staff (email, mc_name, rnk, password, totp, totp_step, backup, failed,"
                + " locked_until, created_at, created_by, last_login, previous_login, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, a);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("no id for the new account");
                }
                return keys.getLong(1);
            }
        }
    }

    @Override
    public synchronized void update(Account a) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("UPDATE " + prefix + "panel_staff SET email = ?, mc_name = ?, rnk = ?, password = ?, totp = ?, totp_step = ?,"
                + " backup = ?, failed = ?, locked_until = ?, created_at = ?, created_by = ?, last_login = ?, previous_login = ?, active = ? WHERE id = ?")) {
            bind(ps, a);
            ps.setLong(15, a.id);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void saveSession(Session s) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "panel_sessions (token, staff, created_at, expires_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, s.token());
            ps.setLong(2, s.accountId());
            ps.setLong(3, s.createdAt());
            ps.setLong(4, s.expiresAt());
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized Optional<Session> session(String token) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT token, staff, created_at, expires_at FROM " + prefix + "panel_sessions WHERE token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Session(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized void deleteSession(String token) throws SQLException {
        delete("panel_sessions", "token", token);
    }

    @Override
    public synchronized void deleteSessionsOf(long accountId) throws SQLException {
        delete("panel_sessions", "staff", accountId);
    }

    @Override
    public synchronized void saveLink(Link l) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "panel_links (token, staff, kind, expires_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, l.token());
            ps.setLong(2, l.accountId());
            ps.setString(3, l.kind());
            ps.setLong(4, l.expiresAt());
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized Optional<Link> takeLink(String token) throws SQLException {
        Optional<Link> l = peekLink(token);
        if (l.isPresent()) {
            try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + "panel_links WHERE token = ?")) {
                ps.setString(1, token);
                if (ps.executeUpdate() == 0) {
                    return Optional.empty(); // used at the same moment elsewhere
                }
            }
        }
        return l;
    }

    @Override
    public synchronized Optional<Link> peekLink(String token) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT token, staff, kind, expires_at FROM " + prefix + "panel_links WHERE token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new Link(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4))) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized void deleteLinksOf(long accountId) throws SQLException {
        delete("panel_links", "staff", accountId);
    }

    private void delete(String table, String column, Object value) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + table + " WHERE " + column + " = ?")) {
            ps.setObject(1, value);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized Optional<String[]> findPlayer(String name) throws SQLException {
        boolean byId = name.length() == 36;
        try (PreparedStatement ps = conn().prepareStatement("SELECT uuid, name FROM " + prefix + "players WHERE " + (byId ? "uuid" : "name") + " = ? LIMIT 1")) {
            ps.setString(1, byId ? name.toLowerCase(java.util.Locale.ROOT) : name); // names: the table's collation ignores case
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new String[]{rs.getString(1), rs.getString(2)}) : Optional.empty();
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public synchronized void insertNotice(String uid, long at, String source, String kind, String payload) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "notices (uid, created_at, source, kind, payload) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setLong(2, at);
            ps.setString(3, source);
            ps.setString(4, kind);
            ps.setString(5, payload);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized List<WebRow> webServers() throws SQLException {
        List<WebRow> out = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT server, at_ms, snapshot FROM " + prefix + "web_servers")) {
            while (rs.next()) {
                out.add(new WebRow(rs.getString(1), rs.getLong(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                return out; // no game server has sent anything yet
            }
            throw e;
        }
        return out;
    }

    @Override
    public synchronized void log(LogLine line) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "panel_log (at_ms, who, what) VALUES (?, ?, ?)")) {
            ps.setLong(1, line.at());
            ps.setString(2, line.who());
            ps.setString(3, line.what());
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized List<LogLine> logStarting(long sinceMs, String prefix, int limit) throws SQLException {
        List<LogLine> out = new ArrayList<>();
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (PreparedStatement ps = conn().prepareStatement("SELECT at_ms, who, what FROM " + prefix() + "panel_log WHERE at_ms >= ? AND what LIKE ? ORDER BY at_ms DESC, id DESC LIMIT ?")) {
            ps.setLong(1, sinceMs);
            ps.setString(2, like);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LogLine(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }

    private String prefix() {
        return prefix;
    }

    @Override
    public synchronized List<LogLine> log(long sinceMs, int limit) throws SQLException {
        List<LogLine> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT at_ms, who, what FROM " + prefix + "panel_log WHERE at_ms >= ? ORDER BY at_ms DESC, id DESC LIMIT ?")) {
            ps.setLong(1, sinceMs);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LogLine(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }
}
