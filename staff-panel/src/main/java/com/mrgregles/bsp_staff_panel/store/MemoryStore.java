package com.mrgregles.bsp_staff_panel.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The panel's records in memory, for tests. */
public final class MemoryStore implements PanelStore {
    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Link> links = new HashMap<>();
    private final List<LogLine> log = new ArrayList<>();
    private long nextId = 1;
    private final List<WebRow> web = new ArrayList<>();

    @Override
    public void init() {
    }

    @Override
    public synchronized Optional<Account> accountByEmail(String email) {
        return accounts.values().stream().filter(a -> a.email.equals(email)).findFirst().map(Account::copy);
    }

    @Override
    public synchronized Optional<Account> account(long id) {
        return Optional.ofNullable(accounts.get(id)).map(Account::copy);
    }

    @Override
    public synchronized List<Account> accounts() {
        return accounts.values().stream().sorted(Comparator.comparingLong(a -> a.id)).map(Account::copy).toList();
    }

    @Override
    public synchronized long insert(Account a) {
        if (accounts.values().stream().anyMatch(x -> x.email.equals(a.email))) {
            throw new IllegalStateException("an account with this email exists");
        }
        Account c = a.copy();
        c.id = nextId++;
        accounts.put(c.id, c);
        return c.id;
    }

    @Override
    public synchronized void update(Account a) {
        accounts.put(a.id, a.copy());
    }

    @Override
    public synchronized void saveSession(Session s) {
        sessions.put(s.token(), s);
    }

    @Override
    public synchronized Optional<Session> session(String token) {
        return Optional.ofNullable(sessions.get(token));
    }

    @Override
    public synchronized void deleteSession(String token) {
        sessions.remove(token);
    }

    @Override
    public synchronized void deleteSessionsOf(long accountId) {
        sessions.values().removeIf(s -> s.accountId() == accountId);
    }

    public synchronized int sessionCount() {
        return sessions.size();
    }

    @Override
    public synchronized void saveLink(Link l) {
        links.put(l.token(), l);
    }

    @Override
    public synchronized Optional<Link> takeLink(String token) {
        return Optional.ofNullable(links.remove(token));
    }

    @Override
    public synchronized Optional<Link> peekLink(String token) {
        return Optional.ofNullable(links.get(token));
    }

    @Override
    public synchronized void deleteLinksOf(long accountId) {
        links.values().removeIf(l -> l.accountId() == accountId);
    }

    @Override
    public synchronized void log(LogLine line) {
        log.add(line);
    }

    public final List<String[]> notices = new ArrayList<>();
    public final Map<String, String> players = new HashMap<>(); // uuid -> name (tests)

    /** Network bans (tests, the demo). */
    public final List<Ban> bans = new ArrayList<>();

    @Override
    public synchronized List<Ban> bans(boolean active, String q, long now, int limit) {
        String p = q.toLowerCase(java.util.Locale.ROOT);
        return bans.stream().filter(b -> b.activeAt(now) == active && b.name().toLowerCase(java.util.Locale.ROOT).startsWith(p))
                .sorted(Comparator.comparingLong(Ban::atMs).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized List<Ban> bansOf(String player) {
        return bans.stream().filter(b -> b.player().equalsIgnoreCase(player)).sorted(Comparator.comparingLong(Ban::atMs).reversed()).toList();
    }

    @Override
    public synchronized Optional<Ban> ban(long id) {
        return bans.stream().filter(b -> b.id() == id).findFirst();
    }

    /** Item picture exports and their zips (tests, the demo). */
    public final List<ItemsExport> exports = new ArrayList<>();
    public final Map<Long, byte[]> exportBytes = new HashMap<>();

    @Override
    public synchronized List<ItemsExport> itemsExports(int limit) {
        return exports.stream().sorted(Comparator.comparingLong(ItemsExport::id).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized byte[] itemsExportBytes(long id) {
        return exportBytes.get(id);
    }

    @Override
    public synchronized List<String[]> searchPlayers(String prefix, int limit) {
        String p = prefix.toLowerCase(java.util.Locale.ROOT);
        return players.entrySet().stream().filter(e -> e.getValue().toLowerCase(java.util.Locale.ROOT).startsWith(p))
                .sorted(java.util.Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER)).limit(limit).map(e -> new String[]{e.getKey(), e.getValue()}).toList();
    }

    @Override
    public synchronized Optional<String[]> findPlayer(String name) {
        return players.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name) || e.getKey().equalsIgnoreCase(name)).findFirst()
                .map(e -> new String[]{e.getKey(), e.getValue()});
    }

    @Override
    public synchronized void insertNotice(String uid, long at, String source, String kind, String payload) {
        notices.add(new String[]{uid, String.valueOf(at), source, kind, payload});
    }

    @Override
    public synchronized List<WebRow> webServers() {
        return List.copyOf(web);
    }

    // ------------------------------------------------------------------ chat (SU-36)

    private record Chat(long id, String uid, long at, String server, String name, String text, String line) {
    }

    private final List<Chat> chat = new ArrayList<>();
    private long chatId;

    @Override
    public synchronized void insertChat(String uid, long at, String server, String name, String text, String line) {
        if (chat.stream().noneMatch(c -> c.uid.equals(uid))) {
            chat.add(new Chat(++chatId, uid, at, server, name, text, line));
        }
    }

    private static ChatRow row(Chat c) {
        return new ChatRow(c.id, c.at, c.line, c.uid);
    }

    private static final Comparator<Chat> OLDEST_FIRST = Comparator.comparingLong(Chat::at).thenComparingLong(Chat::id);

    @Override
    public synchronized List<ChatRow> chatBefore(long atMs, int limit) {
        return chat.stream().filter(c -> c.at <= atMs).sorted(OLDEST_FIRST.reversed()).limit(limit).map(MemoryStore::row).toList();
    }

    @Override
    public synchronized List<ChatRow> chatFrom(long atMs, int limit) {
        return chat.stream().filter(c -> c.at >= atMs).sorted(OLDEST_FIRST).limit(limit).map(MemoryStore::row).toList();
    }

    @Override
    public synchronized List<ChatRow> chatAfter(long afterId, int limit) {
        return chat.stream().filter(c -> c.id > afterId).limit(limit).map(MemoryStore::row).toList();
    }

    @Override
    public synchronized long chatLastId() {
        return chatId;
    }

    @Override
    public synchronized List<ChatRow> searchChat(String words, String name, String server, long sinceMs, int limit) {
        String w = words.toLowerCase(java.util.Locale.ROOT);
        return chat.stream().filter(c -> c.at >= sinceMs && c.text.toLowerCase(java.util.Locale.ROOT).contains(w)
                        && (name.isEmpty() || c.name.equalsIgnoreCase(name)) && (server.isEmpty() || c.server.equalsIgnoreCase(server)))
                .sorted(OLDEST_FIRST.reversed()).limit(limit).map(MemoryStore::row).toList();
    }

    @Override
    public synchronized int pruneChat(long beforeMs) {
        int n = chat.size();
        chat.removeIf(c -> c.at < beforeMs);
        return n - chat.size();
    }

    // ------------------------------------------------------------------ the staff room and messages (SU-37)

    private final List<RoomLine> room = new ArrayList<>();
    private final List<Message> messages = new ArrayList<>();
    private final List<Read> readMarks = new ArrayList<>();
    private long roomId, messageId;

    @Override
    public synchronized long roomInsert(long at, long staffId, String name, String rank, String text) {
        room.add(new RoomLine(++roomId, at, staffId, name, rank, text, false));
        return roomId;
    }

    @Override
    public synchronized List<RoomLine> roomBefore(long beforeId, int limit) {
        return room.stream().filter(r -> r.id() < beforeId).sorted(Comparator.comparingLong(RoomLine::id).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized Optional<RoomLine> roomLine(long id) {
        return room.stream().filter(r -> r.id() == id).findFirst();
    }

    @Override
    public synchronized void roomDelete(long id) {
        room.replaceAll(r -> r.id() == id ? new RoomLine(r.id(), r.at(), r.staffId(), r.name(), r.rank(), "", true) : r);
    }

    @Override
    public synchronized int roomPrune(long beforeMs) {
        int n = room.size();
        room.removeIf(r -> r.at() < beforeMs);
        return n - room.size();
    }

    @Override
    public synchronized long messageInsert(long at, long fromId, long toId, String text) {
        messages.add(new Message(++messageId, at, fromId, toId, text, false));
        return messageId;
    }

    @Override
    public synchronized List<Message> messagesOf(long staffId) {
        return messages.stream().filter(m -> m.toId() == staffId || m.toId() == 0 || m.fromId() == staffId)
                .sorted(Comparator.comparingLong(Message::id).reversed()).limit(500).toList();
    }

    @Override
    public synchronized Optional<Message> message(long id) {
        return messages.stream().filter(m -> m.id() == id).findFirst();
    }

    @Override
    public synchronized void messageDelete(long id) {
        messages.replaceAll(m -> m.id() == id ? new Message(m.id(), m.at(), m.fromId(), m.toId(), "", true) : m);
    }

    @Override
    public synchronized void markRead(long messageId, long staffId, long at) {
        if (readMarks.stream().noneMatch(r -> r.messageId() == messageId && r.staffId() == staffId)) {
            readMarks.add(new Read(messageId, staffId, at));
        }
    }

    @Override
    public synchronized List<Read> reads(List<Long> messageIds) {
        return readMarks.stream().filter(r -> messageIds.contains(r.messageId())).toList();
    }

    @Override
    public synchronized List<Message> messagesBefore(long beforeMs) {
        return messages.stream().filter(m -> m.at() < beforeMs).toList();
    }

    @Override
    public synchronized void messagesDelete(List<Long> ids) {
        messages.removeIf(m -> ids.contains(m.id()));
        readMarks.removeIf(r -> ids.contains(r.messageId()));
    }

    // ------------------------------------------------------------------ notes and tags (SU-38)

    private final List<Note> notes = new ArrayList<>();
    private final List<Tag> tagList = new ArrayList<>();
    private final List<PlayerTag> playerTagList = new ArrayList<>();
    private long noteId, tagId;

    @Override
    public synchronized long noteInsert(String player, String playerName, long at, long authorId, String authorName, String text) {
        notes.add(new Note(++noteId, player, playerName, at, authorId, authorName, text, 0));
        return noteId;
    }

    @Override
    public synchronized List<Note> notesOf(String player) {
        return notes.stream().filter(n -> n.player().equals(player)).sorted(Comparator.comparingLong(Note::at).thenComparingLong(Note::id)).toList();
    }

    @Override
    public synchronized Optional<Note> note(long id) {
        return notes.stream().filter(n -> n.id() == id).findFirst();
    }

    @Override
    public synchronized void noteUpdate(long id, String text, long editedAt) {
        notes.replaceAll(n -> n.id() == id ? new Note(n.id(), n.player(), n.playerName(), n.at(), n.authorId(), n.authorName(), text, editedAt) : n);
    }

    @Override
    public synchronized void noteDelete(long id) {
        notes.removeIf(n -> n.id() == id);
    }

    @Override
    public synchronized List<Note> notesOfAll(List<String> players) {
        return notes.stream().filter(n -> players.contains(n.player())).sorted(Comparator.comparingLong(Note::at).thenComparingLong(Note::id)).toList();
    }

    @Override
    public synchronized List<Tag> tags() {
        return tagList.stream().sorted(Comparator.comparing(Tag::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Override
    public synchronized long tagInsert(String name, String colour, boolean typed, long byId, String byName, long at) {
        if (tagList.stream().anyMatch(t -> t.name().equalsIgnoreCase(name))) {
            throw new IllegalStateException("Duplicate entry for key 'name'"); // as the table's unique key does
        }
        tagList.add(new Tag(++tagId, name, colour, typed, byId, byName, at));
        return tagId;
    }

    @Override
    public synchronized void tagUpdate(long id, String name, String colour, boolean typed) {
        tagList.replaceAll(t -> t.id() == id ? new Tag(t.id(), name, colour, typed, t.byId(), t.byName(), t.at()) : t);
    }

    @Override
    public synchronized void tagDelete(long id) {
        playerTagList.removeIf(p -> p.tagId() == id);
        tagList.removeIf(t -> t.id() == id);
    }

    @Override
    public synchronized List<PlayerTag> playerTags() {
        return List.copyOf(playerTagList);
    }

    @Override
    public synchronized void playerTagAdd(String player, long tagId, long byId, long at) {
        if (playerTagList.stream().noneMatch(p -> p.player().equals(player) && p.tagId() == tagId)) {
            playerTagList.add(new PlayerTag(player, tagId, byId, at));
        }
    }

    @Override
    public synchronized void playerTagRemove(String player, long tagId) {
        playerTagList.removeIf(p -> p.player().equals(player) && p.tagId() == tagId);
    }

    /** Tests: a row as a game server would write it. */
    public synchronized void putWebRow(WebRow row) {
        web.removeIf(r -> r.server().equals(row.server()));
        web.add(row);
    }

    @Override
    public synchronized List<LogLine> logStarting(long sinceMs, String prefix, int limit) {
        return log(sinceMs, Integer.MAX_VALUE).stream().filter(l -> l.what().startsWith(prefix)).limit(limit).toList();
    }

    @Override
    public synchronized List<LogLine> log(long sinceMs, int limit) {
        return log.stream().filter(l -> l.at() >= sinceMs).sorted(Comparator.comparingLong(LogLine::at).reversed()).limit(limit).toList();
    }
}
