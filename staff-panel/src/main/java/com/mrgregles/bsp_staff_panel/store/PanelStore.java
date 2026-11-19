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

    /**
     * A network ban as BSP-Server Utilities keeps it ({@code <prefix>bans}, SU-44): {@code untilMs} 0 permanent,
     * {@code liftedAt} 0 not lifted.
     */
    record Ban(long id, String player, String name, String reason, String note, String by, long atMs, long untilMs, long liftedAt, String liftedBy, long editedAt) {
        public boolean activeAt(long now) {
            return liftedAt == 0 && (untilMs == 0 || untilMs > now);
        }
    }

    /** Bans in force at {@code now} ({@code active}) or not, newest first, whose name starts with {@code q} (any case; empty: all). */
    List<Ban> bans(boolean active, String q, long now, int limit) throws Exception;

    /** One player's bans, newest first. */
    List<Ban> bansOf(String player) throws Exception;

    Optional<Ban> ban(long id) throws Exception;

    /** An item picture export as BSP-Server Utilities stored it ({@code <prefix>web_items}, SU-41). */
    record ItemsExport(long id, long atMs, String by, String server, int items, int tabs, int mods, int size, int parts, String sha256) {
    }

    /** The newest exports, newest first (the More › Items page; at most {@code limit}). */
    List<ItemsExport> itemsExports(int limit) throws Exception;

    /** One export's zip, its parts joined in order; null when its parts are gone. */
    byte[] itemsExportBytes(long id) throws Exception;

    /** Players who ever joined whose name starts with {@code prefix} (any case), by name, at most {@code limit}: {uuid, name} (SU-39, D336). */
    List<String[]> searchPlayers(String prefix, int limit) throws Exception;

    /** The game servers' newest snapshots (written by the game servers; empty while none has sent one). */
    List<WebRow> webServers() throws Exception;

    // ------------------------------------------------------------------ chat (SU-36)

    /**
     * A chat line in {@code <prefix>web_chat}: BSP-Server Utilities writes the lines players say, the panel its own. {@code line}
     * is the signed line (docs/API.md); the panel shows only what it can open with its key.
     */
    record ChatRow(long id, long atMs, String line, String uid) {
        public ChatRow(long id, long atMs, String line) {
            this(id, atMs, line, null);
        }

    }

    /** The chat table, word for word as BSP-Server Utilities' JdbcBackend makes it (a JUnit test compares the two). */
    String WEB_CHAT_TABLE = "CREATE TABLE IF NOT EXISTS <prefix>web_chat (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uid CHAR(36) NOT NULL,"
            + " at_ms BIGINT NOT NULL, server VARCHAR(64) NOT NULL, name VARCHAR(32) NOT NULL, text VARCHAR(512) NOT NULL, line TEXT NOT NULL,"
            + " UNIQUE KEY uid (uid), KEY at (at_ms), KEY name (name)) DEFAULT CHARSET=utf8mb4";

    /** Adds a line (the panel's own); one whose uid is there already is skipped. */
    void insertChat(String uid, long at, String server, String name, String text, String line) throws Exception;

    /** Up to {@code limit} lines at or before {@code atMs}, newest first. */
    List<ChatRow> chatBefore(long atMs, int limit) throws Exception;

    /** Up to {@code limit} lines at or after {@code atMs}, oldest first. */
    List<ChatRow> chatFrom(long atMs, int limit) throws Exception;

    /** Up to {@code limit} lines added after {@code afterId}, oldest first (what Redis may have missed). */
    List<ChatRow> chatAfter(long afterId, int limit) throws Exception;

    /** The newest line's id, 0 when there is none. */
    long chatLastId() throws Exception;

    /**
     * Lines since {@code sinceMs}, newest first: holding {@code words} (any case; empty for any), said by {@code name} (any case;
     * empty for anyone), on {@code server} (empty for any).
     */
    List<ChatRow> searchChat(String words, String name, String server, long sinceMs, int limit) throws Exception;

    /** Drops lines older than {@code beforeMs}; how many went. */
    int pruneChat(long beforeMs) throws Exception;

    // ------------------------------------------------------------------ the staff room and messages (SU-37, D265, D314 to D319, D327 to D329)

    /** A line in the staff room ({@code <prefix>panel_room}): who said it, their name and rank then, and whether it was deleted. */
    record RoomLine(long id, long at, long staffId, String name, String rank, String text, boolean deleted) {
    }

    /** A message ({@code <prefix>panel_messages}): {@code toId} 0 is all staff. */
    record Message(long id, long at, long fromId, long toId, String text, boolean deleted) {
    }

    /** Who read a message, and when ({@code <prefix>panel_reads}). */
    record Read(long messageId, long staffId, long at) {
    }

    long roomInsert(long at, long staffId, String name, String rank, String text) throws Exception;

    /** Up to {@code limit} lines with an id below {@code beforeId}, newest first. */
    List<RoomLine> roomBefore(long beforeId, int limit) throws Exception;

    Optional<RoomLine> roomLine(long id) throws Exception;

    /** Marks a line deleted and clears its words ("deleted" stays in its place, D327). */
    void roomDelete(long id) throws Exception;

    int roomPrune(long beforeMs) throws Exception;

    long messageInsert(long at, long fromId, long toId, String text) throws Exception;

    /** Messages to this staff member, to all staff, or from them; newest first. */
    List<Message> messagesOf(long staffId) throws Exception;

    Optional<Message> message(long id) throws Exception;

    void messageDelete(long id) throws Exception;

    /** Marks a message read by a staff member (the first time counts). */
    void markRead(long messageId, long staffId, long at) throws Exception;

    List<Read> reads(List<Long> messageIds) throws Exception;

    /** Messages older than {@code beforeMs}, for the 30-day prune to decide on (D316). */
    List<Message> messagesBefore(long beforeMs) throws Exception;

    /** Drops these messages and their read marks. */
    void messagesDelete(List<Long> ids) throws Exception;

    // ------------------------------------------------------------------ player notes and tags (SU-38, D266, D267, D320 to D326, D330 to D332)

    /** A note about a player: who wrote it (their name then), when, and when it was last edited (0: never). */
    record Note(long id, String player, String playerName, long at, long authorId, String authorName, String text, long editedAt) {
    }

    /** A tag in the list: {@code colour} "#rrggbb", {@code typed} while it waits for an admin's colour (D322). */
    record Tag(long id, String name, String colour, boolean typed, long byId, String byName, long at) {
    }

    record PlayerTag(String player, long tagId, long byId, long at) {
    }

    long noteInsert(String player, String playerName, long at, long authorId, String authorName, String text) throws Exception;

    /** A player's notes, oldest first. */
    List<Note> notesOf(String player) throws Exception;

    Optional<Note> note(long id) throws Exception;

    void noteUpdate(long id, String text, long editedAt) throws Exception;

    void noteDelete(long id) throws Exception;

    /** Every note of these players (for the counts the game servers get, D332). */
    List<Note> notesOfAll(List<String> players) throws Exception;

    List<Tag> tags() throws Exception;

    long tagInsert(String name, String colour, boolean typed, long byId, String byName, long at) throws Exception;

    void tagUpdate(long id, String name, String colour, boolean typed) throws Exception;

    /** Drops a tag and takes it off every player. */
    void tagDelete(long id) throws Exception;

    List<PlayerTag> playerTags() throws Exception;

    /** Puts a tag on a player (once). */
    void playerTagAdd(String player, long tagId, long byId, long at) throws Exception;

    void playerTagRemove(String player, long tagId) throws Exception;

    // ------------------------------------------------------------------ the maps shown on the Map tab (SU-83, D570 to D586)

    /** An Owner's choice for one map ({@code <prefix>panel_maps}): hidden or shown, by whom (their name then) and when. A map with no row is shown (D573). */
    record MapChoice(String map, boolean hidden, String byName, long at) {
    }

    /** Every map an Owner has ever hidden or shown again. */
    List<MapChoice> mapChoices() throws Exception;

    /** Saves these choices together, all or none (ALL and NONE are one change). */
    void putMapChoices(List<MapChoice> choices) throws Exception;
}
