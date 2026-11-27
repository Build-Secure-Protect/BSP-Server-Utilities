package com.mrgregles.bsp_staff_panel.store;

import java.util.List;
import java.util.Map;
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

    // ------------------------------------------------------------------ server alerts (SU-167, D826 to D834)

    /**
     * One server alert ({@code <prefix>panel_alerts} in the panel's own database): a game server frozen, crashed, not
     * answering, not sending, not back after a planned stop, slow, or the panel hearing no server ({@code kind}). Times in
     * ms, 0 when not yet; {@code checks} the port checks' times, comma separated. Kept 30 days (D832 e).
     */
    record AlertRow(long id, String server, String kind, long sinceMs, long decidedMs, long lastHeardMs, String port, String addr, int players, double mspt,
                    String checks, long emailedMs, String emailNote, long backMs, long backEmailedMs, long endedMs, String note) {
    }

    /** Saves an alert as it is now (a new one, or a change to it). */
    void saveAlert(AlertRow row) throws Exception;

    /** The alerts that began at or after {@code sinceMs}, the oldest first. */
    List<AlertRow> alerts(long sinceMs) throws Exception;

    /** Forgets ended alerts that began before {@code beforeMs}; returns how many. */
    int pruneAlerts(long beforeMs) throws Exception;

    // ------------------------------------------------------------------ the TOTEMS page (SU-97, D613 to D622)

    /**
     * An ended steal as BSP-Server Utilities records it ({@code <prefix>steals}, written by the game server where it ran;
     * docs/API.md "Totem steals"): {@code owner} empty for an unclaimed totem; {@code tier} 1 to 5 and {@code points} 0 when
     * unknown; {@code startedMs} and {@code expectedSeconds} 0 when the server did not see the start. {@code line} is the
     * game server's signed copy of it all: the panel shows a row only through that copy (audit of SU-97).
     */
    record Steal(String uid, String server, String dimension, int x, int y, int z, String thief, String thiefName, String owner, String ownerName,
                 String source, int tier, int points, long startedMs, long endedMs, int expectedSeconds, boolean success, String reason, int ticks, String line) {
    }

    /**
     * Steals that ended from {@code sinceMs} to {@code untilMs}, newest first, by {@code thief} (a UUID; empty: anyone), at
     * most {@code limit}. The upper bound keeps rows dated in the future from crowding out the real ones (audit of 0.16.0).
     */
    List<Steal> steals(long sinceMs, long untilMs, String thief, int limit) throws Exception;

    /** The game servers' steals table as named in the database (for the words when it cannot be read). */
    default String stealsTable() {
        return "bsp_su_steals";
    }

    /**
     * A player's points on BSP-Core's board as the panel saw them change ({@code <prefix>panel_points}, D618): from
     * {@code before} to {@code after} at {@code at}, with the totems held and best tier after. {@code first} marks the
     * first time the panel saw them, so their points then are where the line starts, never a gain.
     */
    record PointsChange(long at, String player, String name, int before, int after, int totems, int tier, boolean first) {
    }

    /** Saves these changes together, all or none (a half-saved batch would be saved again on the next read and counted twice). */
    void insertPoints(List<PointsChange> changes) throws Exception;

    /** Changes at or after {@code sinceMs}, newest first, of {@code player} (empty: anyone), at most {@code limit}. */
    List<PointsChange> points(long sinceMs, String player, int limit) throws Exception;

    /** Per player, the points gained and lost (as {@code [gained, lost]}) over changes since {@code sinceMs}, first sightings left out; summed by the database. */
    Map<String, int[]> pointsTotals(long sinceMs) throws Exception;

    /** A player's newest change before {@code beforeMs}, if any: where their line starts when nothing changed in a window. */
    Optional<PointsChange> lastPointsBefore(String player, long beforeMs) throws Exception;

    /** Each player's newest change: where the board stood when the panel last looked. */
    Map<String, PointsChange> lastPoints() throws Exception;

    /** Drops changes older than {@code beforeMs}; how many went. */
    int prunePoints(long beforeMs) throws Exception;
    // ------------------------------------------------------------------ private staff messages to players (SU-110, D630 to D652)

    /**
     * A conversation between staff and one player as BSP-Server Utilities keeps it ({@code <prefix>talks}, written only by
     * the game servers; docs/API.md "Private staff messages"): {@code endedAt} 0 while open. Not signed: the panel takes
     * who wrote what only from the lines' signed copies, and uses this row for the state and "not seen yet".
     */
    record Talk(long id, String player, String name, long openedAt, String openedBy, long endedAt, String endedBy, long lastStaffAt, long lastPlayerAt, long toldAt) {
    }

    /** One line ({@code <prefix>talk_lines}): {@code kind} staff, player or end; {@code line} its signed copy, which is what the panel shows. */
    record TalkLine(long id, String uid, long talk, String player, long at, String kind, String staff, String text, String server, String line) {
    }

    /** A player's conversations, newest first (at most {@code limit}). */
    List<Talk> talksOf(String player, int limit) throws Exception;

    /** Every open conversation. */
    List<Talk> openTalks() throws Exception;

    /** These conversations' lines, oldest first. */
    List<TalkLine> talkLines(List<Long> talks) throws Exception;

    /** The lines table's name, for the words when its grant is missing. */
    default String talkLinesTable() {
        return "bsp_su_talk_lines";
    }

    // ------------------------------------------------------------------ offline players (SU-115; D653 to D671)

    /**
     * A player who ever joined ({@code <prefix>players}) and, when {@code seen}, where they were last seen
     * ({@code <prefix>seen}, written by BSP-Server Utilities 0.18.0 and newer): the row's columns as they are, unchecked;
     * {@code line} is the signed copy the panel believes.
     */
    record SeenPlayer(String id, String name, boolean seen, String seenName, long atMs, String server, String dimension, int x, int y, int z, String kind, String line) {
        public static SeenPlayer unseen(String id, String name) {
            return new SeenPlayer(id, name, false, "", 0, "", "", 0, 0, 0, "", "");
        }
    }

    /**
     * Players who ever joined, none of {@code online} (UUIDs), whose name holds {@code contains} (any case; empty: everyone):
     * those with a last-seen row dated at or before {@code maxAtMs} first, the newest first, then the rest by name (then UUID, so
     * pages never swap rows); from {@code offset}, at most {@code limit}. A row dated later than {@code maxAtMs} sorts with the
     * unseen, so a made-up far-future time cannot hold the top (audit of SU-115). {@code withSeen} false reads the players
     * table alone (every row unseen), for a panel without the grant on the seen table.
     */
    List<SeenPlayer> offlinePlayers(String contains, java.util.Collection<String> online, int offset, int limit, boolean withSeen, long maxAtMs) throws Exception;

    /** How many players {@link #offlinePlayers} would list in all. */
    int countOffline(String contains, java.util.Collection<String> online) throws Exception;

    /** One player who ever joined, with their last-seen row when there is one (and {@code withSeen}). */
    Optional<SeenPlayer> seenPlayer(String id, boolean withSeen) throws Exception;

    /** The seen table's name, for the words when its grant is missing. */
    default String seenTable() {
        return "bsp_su_seen";
    }
}
