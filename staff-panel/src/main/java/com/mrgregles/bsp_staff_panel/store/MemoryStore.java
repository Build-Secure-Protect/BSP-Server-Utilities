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
