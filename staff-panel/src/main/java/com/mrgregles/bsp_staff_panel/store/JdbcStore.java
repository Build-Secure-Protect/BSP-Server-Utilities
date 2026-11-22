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
import java.util.Map;
import java.util.Optional;

/**
 * The panel's records in the network's MySQL / MariaDB, beside the game servers' tables: {@code <prefix>panel_staff},
 * {@code <prefix>panel_sessions}, {@code <prefix>panel_links}, {@code <prefix>panel_log}. One connection, reopened when it
 * drops; every call is synchronized (a handful of staff never need more).
 */
public final class JdbcStore implements PanelStore {
    private final String url, user, password, prefix;
    /** The game servers' shared tables (players, bans, notices, web_servers, web_chat, web_items): in their own database when the panel has one of its own (D425). */
    private final String shared;
    private final boolean ownDatabase;
    private Connection conn;

    public JdbcStore(String host, int port, String database, String user, String password, boolean ssl, String prefix) {
        this(host, port, database, database, user, password, ssl, prefix);
    }

    /**
     * {@code panelDatabase} holds the panel's own tables (accounts, sessions, notes, the staff room) with a user only the
     * panel has (D425: the game servers' database password must not reach the accounts); {@code database} is the game
     * servers' shared one, where the panel reads and adds only what it needs.
     */
    public JdbcStore(String host, int port, String database, String panelDatabase, String user, String password, boolean ssl, String prefix) {
        if (!prefix.matches("[A-Za-z0-9_]{0,32}")) {
            throw new IllegalArgumentException("the table prefix may hold letters, digits and underscores only");
        }
        if (!database.matches("[A-Za-z0-9_]{1,64}") || !panelDatabase.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("database names may hold letters, digits and underscores only");
        }
        this.ownDatabase = !panelDatabase.equals(database);
        this.shared = ownDatabase ? "`" + database + "`." + prefix : prefix;
        database = panelDatabase;
        // a database that stops answering fails a request after 15 s instead of holding every page's thread for good (audit)
        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?connectTimeout=5000&socketTimeout=15000" + (ssl ? "&sslMode=verify-full" : "");
        this.user = user;
        this.password = password;
        this.prefix = prefix;
    }

    /** How the shared tables are named from the panel's own database (tests). */
    public String sharedTables() {
        return shared;
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
                    + " last_login BIGINT NOT NULL, previous_login BIGINT NOT NULL, active BOOLEAN NOT NULL, items_power BOOLEAN NOT NULL DEFAULT FALSE,"
                    + " bank_power BOOLEAN NOT NULL DEFAULT FALSE, bounty_power BOOLEAN NOT NULL DEFAULT FALSE,"
                    + " UNIQUE KEY email (email)) DEFAULT CHARSET=utf8mb4");
            try (ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + prefix + "panel_staff LIKE 'items_power'")) {
                if (!rs.next()) { // a table from the panel's 0.1.0 (SU-40)
                    try (Statement add = conn().createStatement()) {
                        add.executeUpdate("ALTER TABLE " + prefix + "panel_staff ADD COLUMN items_power BOOLEAN NOT NULL DEFAULT FALSE");
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + prefix + "panel_staff LIKE 'bounty_power'")) {
                if (!rs.next()) { // a table from before the bounty power (D527)
                    try (Statement add = conn().createStatement()) {
                        add.executeUpdate("ALTER TABLE " + prefix + "panel_staff ADD COLUMN bounty_power BOOLEAN NOT NULL DEFAULT FALSE");
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("SHOW COLUMNS FROM " + prefix + "panel_staff LIKE 'bank_power'")) {
                if (!rs.next()) { // a table from before the bank power (D504)
                    try (Statement add = conn().createStatement()) {
                        add.executeUpdate("ALTER TABLE " + prefix + "panel_staff ADD COLUMN bank_power BOOLEAN NOT NULL DEFAULT FALSE");
                    }
                }
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_sessions (token CHAR(64) NOT NULL PRIMARY KEY, staff BIGINT NOT NULL,"
                    + " created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, KEY staff (staff)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_links (token CHAR(64) NOT NULL PRIMARY KEY, staff BIGINT NOT NULL,"
                    + " kind VARCHAR(8) NOT NULL, expires_at BIGINT NOT NULL, KEY staff (staff)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_log (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, at_ms BIGINT NOT NULL,"
                    + " who VARCHAR(254) NOT NULL, what TEXT NOT NULL, KEY at (at_ms)) DEFAULT CHARSET=utf8mb4");
            try (Statement chat = conn().createStatement()) {
                chat.executeUpdate(WEB_CHAT_TABLE.replace("<prefix>", shared)); // BSP-Server Utilities makes it too: whichever starts first
            } catch (SQLException noRight) {
                if (!ownDatabase) {
                    throw noRight;
                }
                // in the shared database the panel's own user may not make tables: the game servers make it (they start first)
            }
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_room (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, at_ms BIGINT NOT NULL, staff BIGINT NOT NULL,"
                    + " name VARCHAR(32) NOT NULL, rnk VARCHAR(16) NOT NULL, text VARCHAR(1000) NOT NULL, deleted BOOLEAN NOT NULL, KEY at (at_ms)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_messages (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, at_ms BIGINT NOT NULL, from_staff BIGINT NOT NULL,"
                    + " to_staff BIGINT NOT NULL, text TEXT NOT NULL, deleted BOOLEAN NOT NULL, KEY at (at_ms), KEY to_staff (to_staff), KEY from_staff (from_staff)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_reads (message BIGINT NOT NULL, staff BIGINT NOT NULL, at_ms BIGINT NOT NULL,"
                    + " PRIMARY KEY (message, staff)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_notes (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, player CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL,"
                    + " at_ms BIGINT NOT NULL, author BIGINT NOT NULL, author_name VARCHAR(32) NOT NULL, text TEXT NOT NULL, edited_at BIGINT NOT NULL, KEY player (player)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_tags (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(32) NOT NULL, colour CHAR(7) NOT NULL,"
                    + " typed BOOLEAN NOT NULL, by_staff BIGINT NOT NULL, by_name VARCHAR(32) NOT NULL, at_ms BIGINT NOT NULL, UNIQUE KEY name (name)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_player_tags (player CHAR(36) NOT NULL, tag BIGINT NOT NULL, by_staff BIGINT NOT NULL, at_ms BIGINT NOT NULL,"
                    + " PRIMARY KEY (player, tag), KEY tag (tag)) DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_maps (map VARCHAR(170) NOT NULL PRIMARY KEY, hidden BOOLEAN NOT NULL,"
                    + " by_name VARCHAR(32) NOT NULL, at_ms BIGINT NOT NULL) DEFAULT CHARSET=utf8mb4"); // SU-83
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "panel_points (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, at_ms BIGINT NOT NULL, player CHAR(36) NOT NULL,"
                    + " name VARCHAR(32) NOT NULL, before_pts INT NOT NULL, after_pts INT NOT NULL, totems INT NOT NULL, tier INT NOT NULL, first BOOLEAN NOT NULL,"
                    + " KEY player (player, at_ms), KEY at (at_ms)) DEFAULT CHARSET=utf8mb4"); // SU-97: the board's changes, 90 days
        }
    }

    private static final String COLS = "id, email, mc_name, rnk, password, totp, totp_step, backup, failed, locked_until, created_at, created_by, last_login, previous_login, active, items_power, bank_power, bounty_power";

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
        a.itemsPower = rs.getBoolean(16);
        a.bankPower = rs.getBoolean(17);
        a.bountyPower = rs.getBoolean(18);
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
        ps.setBoolean(15, a.itemsPower);
        ps.setBoolean(16, a.bankPower);
        ps.setBoolean(17, a.bountyPower);
    }

    @Override
    public synchronized long insert(Account a) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + prefix + "panel_staff (email, mc_name, rnk, password, totp, totp_step, backup, failed,"
                + " locked_until, created_at, created_by, last_login, previous_login, active, items_power, bank_power, bounty_power) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
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
                + " backup = ?, failed = ?, locked_until = ?, created_at = ?, created_by = ?, last_login = ?, previous_login = ?, active = ?, items_power = ?, bank_power = ?, bounty_power = ? WHERE id = ?")) {
            bind(ps, a);
            ps.setLong(18, a.id);
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
        try (PreparedStatement ps = conn().prepareStatement("SELECT uuid, name FROM " + shared + "players WHERE " + (byId ? "uuid" : "name") + " = ? LIMIT 1")) {
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

    private static final String BAN_COLS = "id, uuid, name, reason, note, by_name, at_ms, until_ms, lifted_at, lifted_by, edited_at";

    private List<Ban> bansWhere(String where, java.util.function.Consumer<PreparedStatement> bind) throws SQLException {
        List<Ban> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT " + BAN_COLS + " FROM " + shared + "bans " + where)) {
            bind.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Ban(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                            rs.getString(10), rs.getLong(11)));
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                return out; // no game server with SU-44 has started yet
            }
            throw e;
        }
        return out;
    }

    private static void set(PreparedStatement ps, Object... values) {
        try {
            for (int i = 0; i < values.length; i++) {
                ps.setObject(i + 1, values[i]);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized List<Ban> bans(boolean active, String q, long now, int limit) throws SQLException {
        String like = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return bansWhere((active ? "WHERE lifted_at = 0 AND (until_ms = 0 OR until_ms > ?)" : "WHERE NOT (lifted_at = 0 AND (until_ms = 0 OR until_ms > ?))")
                + " AND name LIKE ? ORDER BY at_ms DESC LIMIT ?", ps -> set(ps, now, like, limit));
    }

    @Override
    public synchronized List<Ban> bansOf(String player) throws SQLException {
        return bansWhere("WHERE uuid = ? ORDER BY at_ms DESC LIMIT 100", ps -> set(ps, player.toLowerCase(java.util.Locale.ROOT)));
    }

    @Override
    public synchronized Optional<Ban> ban(long id) throws SQLException {
        return bansWhere("WHERE id = ?", ps -> set(ps, id)).stream().findFirst();
    }

    @Override
    public synchronized List<ItemsExport> itemsExports(int limit) throws SQLException {
        List<ItemsExport> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, at_ms, made_by, server, items, tabs, mods, size, parts, sha256 FROM " + shared + "web_items ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ItemsExport(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getString(10)));
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                return out; // no game server with SU-41 has started yet
            }
            throw e;
        }
        return out;
    }

    @Override
    public synchronized byte[] itemsExportBytes(long id) throws SQLException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int parts = 0;
        try (PreparedStatement ps = conn().prepareStatement("SELECT data FROM " + shared + "web_item_parts WHERE export = ? ORDER BY part")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.writeBytes(rs.getBytes(1));
                    parts++;
                }
            }
        }
        return parts == 0 ? null : out.toByteArray();
    }

    @Override
    public synchronized List<String[]> searchPlayers(String start, int limit) throws SQLException {
        String like = start.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<String[]> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT uuid, name FROM " + shared + "players WHERE name LIKE ? ORDER BY name LIMIT ?")) {
            ps.setString(1, like); // the table's collation ignores case
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't exist")) {
                return out;
            }
            throw e;
        }
        return out;
    }

    @Override
    public synchronized void insertNotice(String uid, long at, String source, String kind, String payload) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT INTO " + shared + "notices (uid, created_at, source, kind, payload) VALUES (?, ?, ?, ?, ?)")) {
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
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT server, at_ms, snapshot FROM " + shared + "web_servers")) {
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

    // ------------------------------------------------------------------ the staff room and messages (SU-37)

    private long insertReturningId(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("no id for the new row");
                }
                return keys.getLong(1);
            }
        }
    }

    private int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    @Override
    public synchronized long roomInsert(long at, long staffId, String name, String rank, String text) throws SQLException {
        return insertReturningId("INSERT INTO " + prefix + "panel_room (at_ms, staff, name, rnk, text, deleted) VALUES (?, ?, ?, ?, ?, FALSE)", at, staffId, name, rank, text);
    }

    private List<RoomLine> roomRows(String where, Object... args) throws SQLException {
        List<RoomLine> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, at_ms, staff, name, rnk, text, deleted FROM " + prefix + "panel_room " + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RoomLine(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<RoomLine> roomBefore(long beforeId, int limit) throws SQLException {
        return roomRows("WHERE id < ? ORDER BY id DESC LIMIT ?", beforeId, limit);
    }

    @Override
    public synchronized Optional<RoomLine> roomLine(long id) throws SQLException {
        return roomRows("WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public synchronized void roomDelete(long id) throws SQLException {
        update("UPDATE " + prefix + "panel_room SET deleted = TRUE, text = '' WHERE id = ?", id);
    }

    @Override
    public synchronized int roomPrune(long beforeMs) throws SQLException {
        return update("DELETE FROM " + prefix + "panel_room WHERE at_ms < ?", beforeMs);
    }

    @Override
    public synchronized long messageInsert(long at, long fromId, long toId, String text) throws SQLException {
        return insertReturningId("INSERT INTO " + prefix + "panel_messages (at_ms, from_staff, to_staff, text, deleted) VALUES (?, ?, ?, ?, FALSE)", at, fromId, toId, text);
    }

    private List<Message> messageRows(String where, Object... args) throws SQLException {
        List<Message> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, at_ms, from_staff, to_staff, text, deleted FROM " + prefix + "panel_messages " + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Message(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getBoolean(6)));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<Message> messagesOf(long staffId) throws SQLException {
        return messageRows("WHERE to_staff = ? OR to_staff = 0 OR from_staff = ? ORDER BY id DESC LIMIT 500", staffId, staffId);
    }

    @Override
    public synchronized Optional<Message> message(long id) throws SQLException {
        return messageRows("WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public synchronized void messageDelete(long id) throws SQLException {
        update("UPDATE " + prefix + "panel_messages SET deleted = TRUE, text = '' WHERE id = ?", id);
    }

    @Override
    public synchronized void markRead(long messageId, long staffId, long at) throws SQLException {
        update("INSERT IGNORE INTO " + prefix + "panel_reads (message, staff, at_ms) VALUES (?, ?, ?)", messageId, staffId, at);
    }

    @Override
    public synchronized List<Read> reads(List<Long> messageIds) throws SQLException {
        List<Read> out = new ArrayList<>();
        if (messageIds.isEmpty()) {
            return out;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(messageIds.size(), "?"));
        try (PreparedStatement ps = conn().prepareStatement("SELECT message, staff, at_ms FROM " + prefix + "panel_reads WHERE message IN (" + marks + ")")) {
            for (int i = 0; i < messageIds.size(); i++) {
                ps.setLong(i + 1, messageIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Read(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<Message> messagesBefore(long beforeMs) throws SQLException {
        return messageRows("WHERE at_ms < ? ORDER BY id", beforeMs);
    }

    @Override
    public synchronized void messagesDelete(List<Long> ids) throws SQLException {
        for (long id : ids) {
            update("DELETE FROM " + prefix + "panel_reads WHERE message = ?", id);
            update("DELETE FROM " + prefix + "panel_messages WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------ notes and tags (SU-38)

    private List<Note> noteRows(String where, Object... args) throws SQLException {
        List<Note> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, player, player_name, at_ms, author, author_name, text, edited_at FROM " + prefix + "panel_notes " + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Note(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized long noteInsert(String player, String playerName, long at, long authorId, String authorName, String text) throws SQLException {
        return insertReturningId("INSERT INTO " + prefix + "panel_notes (player, player_name, at_ms, author, author_name, text, edited_at) VALUES (?, ?, ?, ?, ?, ?, 0)",
                player, playerName, at, authorId, authorName, text);
    }

    @Override
    public synchronized List<Note> notesOf(String player) throws SQLException {
        return noteRows("WHERE player = ? ORDER BY at_ms, id", player);
    }

    @Override
    public synchronized Optional<Note> note(long id) throws SQLException {
        return noteRows("WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public synchronized void noteUpdate(long id, String text, long editedAt) throws SQLException {
        update("UPDATE " + prefix + "panel_notes SET text = ?, edited_at = ? WHERE id = ?", text, editedAt, id);
    }

    @Override
    public synchronized void noteDelete(long id) throws SQLException {
        update("DELETE FROM " + prefix + "panel_notes WHERE id = ?", id);
    }

    @Override
    public synchronized List<Note> notesOfAll(List<String> players) throws SQLException {
        if (players.isEmpty()) {
            return List.of();
        }
        return noteRows("WHERE player IN (" + String.join(", ", java.util.Collections.nCopies(players.size(), "?")) + ") ORDER BY at_ms, id", players.toArray());
    }

    @Override
    public synchronized List<Tag> tags() throws SQLException {
        List<Tag> out = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, colour, typed, by_staff, by_name, at_ms FROM " + prefix + "panel_tags ORDER BY name")) {
            while (rs.next()) {
                out.add(new Tag(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getLong(5), rs.getString(6), rs.getLong(7)));
            }
        }
        return out;
    }

    @Override
    public synchronized long tagInsert(String name, String colour, boolean typed, long byId, String byName, long at) throws SQLException {
        return insertReturningId("INSERT INTO " + prefix + "panel_tags (name, colour, typed, by_staff, by_name, at_ms) VALUES (?, ?, ?, ?, ?, ?)", name, colour, typed, byId, byName, at);
    }

    @Override
    public synchronized void tagUpdate(long id, String name, String colour, boolean typed) throws SQLException {
        update("UPDATE " + prefix + "panel_tags SET name = ?, colour = ?, typed = ? WHERE id = ?", name, colour, typed, id);
    }

    @Override
    public synchronized void tagDelete(long id) throws SQLException {
        update("DELETE FROM " + prefix + "panel_player_tags WHERE tag = ?", id);
        update("DELETE FROM " + prefix + "panel_tags WHERE id = ?", id);
    }

    @Override
    public synchronized List<PlayerTag> playerTags() throws SQLException {
        List<PlayerTag> out = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT player, tag, by_staff, at_ms FROM " + prefix + "panel_player_tags ORDER BY at_ms")) {
            while (rs.next()) {
                out.add(new PlayerTag(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
            }
        }
        return out;
    }

    @Override
    public synchronized void playerTagAdd(String player, long tagId, long byId, long at) throws SQLException {
        update("INSERT IGNORE INTO " + prefix + "panel_player_tags (player, tag, by_staff, at_ms) VALUES (?, ?, ?, ?)", player, tagId, byId, at);
    }

    @Override
    public synchronized void playerTagRemove(String player, long tagId) throws SQLException {
        update("DELETE FROM " + prefix + "panel_player_tags WHERE player = ? AND tag = ?", player, tagId);
    }

    // ------------------------------------------------------------------ chat (SU-36)

    @Override
    public synchronized void insertChat(String uid, long at, String server, String name, String text, String line) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("INSERT IGNORE INTO " + shared + "web_chat (uid, at_ms, server, name, text, line) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uid);
            ps.setLong(2, at);
            ps.setString(3, server);
            ps.setString(4, name);
            ps.setString(5, text);
            ps.setString(6, line);
            ps.executeUpdate();
        }
    }

    private List<ChatRow> chatRows(String where, Object... args) throws SQLException {
        List<ChatRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, at_ms, line, uid FROM " + shared + "web_chat " + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChatRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<ChatRow> chatBefore(long atMs, int limit) throws SQLException {
        return chatRows("WHERE at_ms <= ? ORDER BY at_ms DESC, id DESC LIMIT ?", atMs, limit);
    }

    @Override
    public synchronized List<ChatRow> chatFrom(long atMs, int limit) throws SQLException {
        return chatRows("WHERE at_ms >= ? ORDER BY at_ms, id LIMIT ?", atMs, limit);
    }

    @Override
    public synchronized List<ChatRow> chatAfter(long afterId, int limit) throws SQLException {
        return chatRows("WHERE id > ? ORDER BY id LIMIT ?", afterId, limit);
    }

    @Override
    public synchronized long chatLastId() throws SQLException {
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + shared + "web_chat")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Override
    public synchronized List<ChatRow> searchChat(String words, String name, String server, long sinceMs, int limit) throws SQLException {
        StringBuilder where = new StringBuilder("WHERE at_ms >= ?");
        List<Object> args = new ArrayList<>(List.of(sinceMs));
        if (!words.isEmpty()) {
            where.append(" AND text LIKE ?"); // the table's collation ignores case
            args.add("%" + words.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (!name.isEmpty()) {
            where.append(" AND name = ?");
            args.add(name);
        }
        if (!server.isEmpty()) {
            where.append(" AND server = ?");
            args.add(server);
        }
        where.append(" ORDER BY at_ms DESC, id DESC LIMIT ?");
        args.add(limit);
        List<ChatRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, at_ms, line, uid FROM " + shared + "web_chat " + where)) {
            ps.setQueryTimeout(8); // a search never holds the panel's one connection for long (audit)
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChatRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized int pruneChat(long beforeMs) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + shared + "web_chat WHERE at_ms < ?")) {
            ps.setLong(1, beforeMs);
            return ps.executeUpdate();
        }
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

    // ------------------------------------------------------------------ the maps shown on the Map tab (SU-83)

    // ------------------------------------------------------------------ the TOTEMS page (SU-97)

    @Override
    public synchronized List<Steal> steals(long sinceMs, long untilMs, String thief, int limit) throws SQLException {
        boolean one = thief != null && !thief.isEmpty();
        List<Steal> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT uid, server, dimension, x, y, z, thief, thief_name, owner, owner_name, source, tier, points,"
                + " started_ms, ended_ms, expected_s, success, reason, ticks, line FROM " + shared + "steals WHERE ended_ms >= ? AND ended_ms <= ?" + (one ? " AND thief = ?" : "")
                + " ORDER BY ended_ms DESC, id DESC LIMIT ?")) {
            int i = 1;
            ps.setLong(i++, sinceMs);
            ps.setLong(i++, untilMs);
            if (one) {
                ps.setString(i++, thief.toLowerCase(java.util.Locale.ROOT));
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Steal(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), rs.getString(10), rs.getString(11), rs.getInt(12), rs.getInt(13), rs.getLong(14), rs.getLong(15), rs.getInt(16),
                            rs.getBoolean(17), rs.getString(18), rs.getInt(19), rs.getString(20) == null ? "" : rs.getString(20)));
                }
            }
        }
        return out;
    }

    private static final String POINT_COLS = "at_ms, player, name, before_pts, after_pts, totems, tier, first";

    private static PointsChange pointsRow(ResultSet rs) throws SQLException {
        return new PointsChange(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getBoolean(8));
    }

    @Override
    public synchronized void insertPoints(List<PointsChange> changes) throws SQLException {
        if (changes.isEmpty()) {
            return;
        }
        Connection c = conn();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + prefix + "panel_points (" + POINT_COLS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (PointsChange p : changes) {
                ps.setLong(1, p.at());
                ps.setString(2, p.player());
                ps.setString(3, p.name().length() > 32 ? p.name().substring(0, 32) : p.name());
                ps.setInt(4, p.before());
                ps.setInt(5, p.after());
                ps.setInt(6, p.totems());
                ps.setInt(7, p.tier());
                ps.setBoolean(8, p.first());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit(); // all or none (audit)
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // the connection is gone: nothing was kept
            }
            throw e;
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
                // reopened on the next call
            }
        }
    }

    @Override
    public synchronized Map<String, int[]> pointsTotals(long sinceMs) throws SQLException {
        Map<String, int[]> out = new java.util.HashMap<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT player, COALESCE(SUM(CASE WHEN after_pts > before_pts THEN after_pts - before_pts ELSE 0 END), 0),"
                + " COALESCE(SUM(CASE WHEN after_pts < before_pts THEN before_pts - after_pts ELSE 0 END), 0) FROM " + prefix + "panel_points"
                + " WHERE at_ms >= ? AND first = FALSE GROUP BY player")) {
            ps.setLong(1, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new int[]{(int) rs.getLong(2), (int) rs.getLong(3)});
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<PointsChange> points(long sinceMs, String player, int limit) throws SQLException {
        boolean one = player != null && !player.isEmpty();
        List<PointsChange> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement("SELECT " + POINT_COLS + " FROM " + prefix + "panel_points WHERE at_ms >= ?" + (one ? " AND player = ?" : "")
                + " ORDER BY at_ms DESC, id DESC LIMIT ?")) {
            int i = 1;
            ps.setLong(i++, sinceMs);
            if (one) {
                ps.setString(i++, player.toLowerCase(java.util.Locale.ROOT));
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(pointsRow(rs));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ private staff messages to players (SU-110)

    private static final String TALK_COLS = "id, player, name, opened_at, opened_by, ended_at, ended_by, last_staff_at, last_player_at, told_at";

    private static List<Talk> talkRows(PreparedStatement ps) throws SQLException {
        List<Talk> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Talk(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        rs.getLong(8), rs.getLong(9), rs.getLong(10)));
            }
        }
        return out;
    }

    @Override
    public synchronized List<Talk> talksOf(String player, int limit) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT " + TALK_COLS + " FROM " + shared + "talks WHERE player = ? ORDER BY opened_at DESC, id DESC LIMIT ?")) {
            ps.setString(1, player.toLowerCase(java.util.Locale.ROOT));
            ps.setInt(2, limit);
            return talkRows(ps);
        }
    }

    @Override
    public synchronized List<Talk> openTalks() throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT " + TALK_COLS + " FROM " + shared + "talks WHERE is_open = 1 ORDER BY id DESC LIMIT 2000")) {
            return talkRows(ps); // the newest 2,000 (audit)
        }
    }

    @Override
    public synchronized List<TalkLine> talkLines(List<Long> talks) throws SQLException {
        List<TalkLine> out = new ArrayList<>();
        if (talks.isEmpty()) {
            return out;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(talks.size(), "?"));
        // the newest 20,000 in the order the table took them, never the oldest (audit of SU-111); turned back to oldest first below
        try (PreparedStatement ps = conn().prepareStatement("SELECT id, uid, talk, player, at_ms, kind, staff, text, server, line FROM " + shared + "talk_lines"
                + " WHERE talk IN (" + marks + ") ORDER BY id DESC LIMIT 20000")) {
            for (int i = 0; i < talks.size(); i++) {
                ps.setLong(i + 1, talks.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TalkLine(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getString(10) == null ? "" : rs.getString(10)));
                }
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    // ------------------------------------------------------------------ offline players (SU-115)

    private static final String SEEN_COLS = "p.uuid, p.name, s.uuid IS NOT NULL, s.name, s.at_ms, s.server, s.dimension, s.x, s.y, s.z, s.kind, s.line";

    /** {@code AND p.uuid NOT IN (?, ...)} for the online players, or nothing. */
    private static String notOnline(java.util.Collection<String> online) {
        return online.isEmpty() ? "" : " AND p.uuid NOT IN (" + String.join(", ", java.util.Collections.nCopies(online.size(), "?")) + ")";
    }

    private static String like(String contains) {
        return "%" + contains.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static int bindNames(PreparedStatement ps, String contains, java.util.Collection<String> online) throws SQLException {
        int i = 1;
        ps.setString(i++, like(contains)); // the table's collation ignores case
        for (String id : online) {
            ps.setString(i++, id.toLowerCase(java.util.Locale.ROOT));
        }
        return i;
    }

    private static SeenPlayer seenRow(ResultSet rs, boolean withSeen) throws SQLException {
        if (!withSeen || !rs.getBoolean(3)) {
            return SeenPlayer.unseen(rs.getString(1), rs.getString(2));
        }
        return new SeenPlayer(rs.getString(1), rs.getString(2), true, rs.getString(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9),
                rs.getInt(10), rs.getString(11), rs.getString(12) == null ? "" : rs.getString(12));
    }

    @Override
    public synchronized List<SeenPlayer> offlinePlayers(String contains, java.util.Collection<String> online, int offset, int limit, boolean withSeen, long maxAtMs) throws SQLException {
        String sql = withSeen
                ? "SELECT " + SEEN_COLS + " FROM " + shared + "players p LEFT JOIN " + shared + "seen s ON s.uuid = p.uuid WHERE p.name LIKE ?" + notOnline(online)
                + " ORDER BY (s.at_ms IS NULL OR s.at_ms > ?), CASE WHEN s.at_ms > ? THEN NULL ELSE s.at_ms END DESC, p.name, p.uuid LIMIT ? OFFSET ?"
                : "SELECT p.uuid, p.name, FALSE FROM " + shared + "players p WHERE p.name LIKE ?" + notOnline(online) + " ORDER BY p.name, p.uuid LIMIT ? OFFSET ?";
        List<SeenPlayer> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            int i = bindNames(ps, contains, online);
            if (withSeen) {
                ps.setLong(i++, maxAtMs);
                ps.setLong(i++, maxAtMs);
            }
            ps.setInt(i++, limit);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(seenRow(rs, withSeen));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized int countOffline(String contains, java.util.Collection<String> online) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT COUNT(*) FROM " + shared + "players p WHERE p.name LIKE ?" + notOnline(online))) {
            bindNames(ps, contains, online);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public synchronized Optional<SeenPlayer> seenPlayer(String id, boolean withSeen) throws SQLException {
        String sql = withSeen
                ? "SELECT " + SEEN_COLS + " FROM " + shared + "players p LEFT JOIN " + shared + "seen s ON s.uuid = p.uuid WHERE p.uuid = ?"
                : "SELECT p.uuid, p.name, FALSE FROM " + shared + "players p WHERE p.uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, id.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(seenRow(rs, withSeen)) : Optional.empty();
            }
        }
    }

    @Override
    public String seenTable() {
        return (shared + "seen").replace("`", "");
    }

    @Override
    public String talkLinesTable() {
        return (shared + "talk_lines").replace("`", "");
    }

    @Override
    public String stealsTable() {
        return (shared + "steals").replace("`", "");
    }

    @Override
    public synchronized Optional<PointsChange> lastPointsBefore(String player, long beforeMs) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT " + POINT_COLS + " FROM " + prefix + "panel_points WHERE player = ? AND at_ms < ? ORDER BY at_ms DESC, id DESC LIMIT 1")) {
            ps.setString(1, player.toLowerCase(java.util.Locale.ROOT));
            ps.setLong(2, beforeMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(pointsRow(rs)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized Map<String, PointsChange> lastPoints() throws SQLException {
        Map<String, PointsChange> out = new java.util.HashMap<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT p.at_ms, p.player, p.name, p.before_pts, p.after_pts, p.totems, p.tier, p.first FROM "
                + prefix + "panel_points p JOIN (SELECT player, MAX(id) id FROM " + prefix + "panel_points GROUP BY player) m ON p.id = m.id")) {
            while (rs.next()) {
                PointsChange p = pointsRow(rs);
                out.put(p.player(), p);
            }
        }
        return out;
    }

    @Override
    public synchronized int prunePoints(long beforeMs) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM " + prefix + "panel_points WHERE at_ms < ?")) {
            ps.setLong(1, beforeMs);
            return ps.executeUpdate();
        }
    }

    @Override
    public synchronized List<MapChoice> mapChoices() throws SQLException {
        List<MapChoice> out = new ArrayList<>();
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery("SELECT map, hidden, by_name, at_ms FROM " + prefix + "panel_maps ORDER BY map")) {
            while (rs.next()) {
                out.add(new MapChoice(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getLong(4)));
            }
        }
        return out;
    }

    @Override
    public synchronized void putMapChoices(List<MapChoice> choices) throws SQLException {
        Connection c = conn();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + prefix + "panel_maps (map, hidden, by_name, at_ms) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE hidden = VALUES(hidden), by_name = VALUES(by_name), at_ms = VALUES(at_ms)")) {
            for (MapChoice m : choices) {
                ps.setString(1, m.map());
                ps.setBoolean(2, m.hidden());
                ps.setString(3, m.byName());
                ps.setLong(4, m.at());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // the connection is gone: nothing was kept
            }
            throw e;
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
                // reopened on the next call
            }
        }
    }
}
