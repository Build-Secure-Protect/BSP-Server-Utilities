package com.mrgregles.bsp_staff_panel.store;

import java.util.List;
import java.util.Optional;

/**
 * Where the panel keeps its own records: staff accounts, logins, one-time links and its log. MySQL on the network
 * ({@link JdbcStore}, the same database as the game servers, tables {@code <prefix>panel_*}); {@link MemoryStore} for
 * tests. Tokens are stored only as fingerprints.
 */
public interface PanelStore {
    /** A login: the fingerprint of its token, whose it is, and until when. */
    record Session(String token, long accountId, long createdAt, long expiresAt) {
    }

    /**
     * A one-time link or step: {@code kind} "setup" (a new account, or an Owner's reset: password and authenticator)
     * or "enrol" (a new authenticator after a correct password, D283).
     */
    record Link(String token, long accountId, String kind, long expiresAt) {
    }

    record LogLine(long at, String who, String what) {
    }

    /** A game server's newest snapshot as BSP-Server Utilities keeps it in {@code <prefix>web_servers}. */
    record WebRow(String server, long atMs, String snapshot) {
    }

    void init() throws Exception;

    Optional<Account> accountByEmail(String email) throws Exception;

    Optional<Account> account(long id) throws Exception;

    List<Account> accounts() throws Exception;

    /** Saves a new account and returns its id. */
    long insert(Account a) throws Exception;

    void update(Account a) throws Exception;

    void saveSession(Session s) throws Exception;

    Optional<Session> session(String token) throws Exception;

    void deleteSession(String token) throws Exception;

    void deleteSessionsOf(long accountId) throws Exception;

    void saveLink(Link l) throws Exception;

    /** The link, which is deleted as it is read: it works once. */
    Optional<Link> takeLink(String token) throws Exception;

    /** The link without using it up (to show its page before the form is sent). */
    Optional<Link> peekLink(String token) throws Exception;

    void deleteLinksOf(long accountId) throws Exception;

    void log(LogLine line) throws Exception;

    /** Lines at or after {@code sinceMs}, newest first. */
    List<LogLine> log(long sinceMs, int limit) throws Exception;

    /** Lines at or after {@code sinceMs} whose text starts with {@code prefix}, newest first (lock-outs, D282). */
    List<LogLine> logStarting(long sinceMs, String prefix, int limit) throws Exception;

    /** A notice for the game servers in their {@code <prefix>notices} table, the safety net when Redis misses it. */
    void insertNotice(String uid, long at, String source, String kind, String payload) throws Exception;

    /** A player who ever joined, by name in any case or by UUID, from BSP-Server Utilities' {@code <prefix>players}: {uuid, name}. */
    Optional<String[]> findPlayer(String name) throws Exception;

    /** The game servers' newest snapshots (written by the game servers; empty while none has sent one). */
    List<WebRow> webServers() throws Exception;
}
