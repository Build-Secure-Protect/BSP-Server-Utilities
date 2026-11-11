package com.mrgregles.bsp_staff_panel.talk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The Staff tab (SU-37, D265, D314 to D319, D327 to D329): one live staff room for everyone on the panel, and messages to
 * one staff member or all staff that wait until they next open the panel. Panel only: nothing reaches the game. Your own
 * line or message may be deleted within 5 minutes ("deleted" stays in its place); Owners delete any. Kept 30 days; a
 * message somebody has not read yet is kept until they have (D316).
 */
public final class StaffTalk {
    public static final int MAX_TEXT = 1000, PAGE = 100, PER_MINUTE = 30;
    public static final long DELETE_MS = 5 * 60_000L, KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    /** Refused, with the words for the staff member. */
    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    private final PanelStore store;
    private final LongSupplier clock;
    private final List<Consumer<JsonObject>> roomListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Set<Long>>> messageListeners = new CopyOnWriteArrayList<>();
    private final Map<Long, ArrayDeque<Long>> recent = new ConcurrentHashMap<>();

    public StaffTalk(PanelStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Hears each new or deleted room line (for every open page). */
    public void onRoom(Consumer<JsonObject> l) {
        roomListeners.add(l);
    }

    /** Hears which accounts' messages changed (a new one, read, deleted): their pages ask again. */
    public void onMessages(Consumer<Set<Long>> l) {
        messageListeners.add(l);
    }

    /**
     * The words as they may be kept: trimmed, at most {@value #MAX_TEXT} characters, no control characters (line breaks
     * allowed in messages only); null when empty or not allowed.
     */
    public static String clean(String raw, boolean lines) {
        String t = raw == null ? "" : raw.replace("\r\n", "\n").strip();
        if (t.isEmpty() || t.length() > MAX_TEXT || t.chars().anyMatch(c -> Character.isISOControl(c) && !(lines && c == '\n'))) {
            return null;
        }
        return t;
    }

    private void limit(Account a) throws Refused {
        long now = clock.getAsLong();
        ArrayDeque<Long> mine = recent.computeIfAbsent(a.id, k -> new ArrayDeque<>());
        synchronized (mine) {
            while (!mine.isEmpty() && mine.peekFirst() < now - 60_000L) {
                mine.pollFirst();
            }
            if (mine.size() >= PER_MINUTE) {
                throw new Refused("That is " + PER_MINUTE + " in a minute: wait a moment.");
            }
            mine.addLast(now);
        }
    }

    // ------------------------------------------------------------------ the room

    private static JsonObject json(PanelStore.RoomLine l) {
        JsonObject o = new JsonObject();
        o.addProperty("id", l.id());
        o.addProperty("at", l.at());
        o.addProperty("staffId", l.staffId());
        o.addProperty("name", l.name());
        o.addProperty("rank", l.rank());
        o.addProperty("rankLabel", Rank.parse(l.rank()).label());
        o.addProperty("text", l.deleted() ? "" : l.text());
        o.addProperty("deleted", l.deleted());
        return o;
    }

    public JsonObject say(Account by, String raw) throws Exception {
        String text = clean(raw, false);
        if (text == null) {
            throw new Refused("Write up to " + MAX_TEXT + " characters on one line.");
        }
        limit(by);
        long at = clock.getAsLong();
        long id = store.roomInsert(at, by.id, by.mcName, by.rank.name(), text);
        JsonObject o = json(new PanelStore.RoomLine(id, at, by.id, by.mcName, by.rank.name(), text, false));
        roomListeners.forEach(l -> l.accept(o));
        return o;
    }

    /** Up to {@link #PAGE} lines before an id (none: the newest), oldest first. */
    public List<JsonObject> room(long beforeId) throws Exception {
        List<JsonObject> out = new ArrayList<>();
        for (PanelStore.RoomLine l : store.roomBefore(beforeId, PAGE)) {
            out.add(0, json(l));
        }
        return out;
    }

    /** Your own line within 5 minutes, or any line for an Owner (D327); returns the line as it stands now. */
    public JsonObject deleteRoom(Account by, long id) throws Exception {
        PanelStore.RoomLine l = store.roomLine(id).orElseThrow(() -> new Refused("That line is gone."));
        if (!mayDelete(by, l.staffId(), l.at())) {
            throw new Refused(l.staffId() == by.id ? "Your own lines can be deleted for 5 minutes after you send them." : "Only an Owner deletes other people's lines.");
        }
        store.roomDelete(id);
        JsonObject o = json(new PanelStore.RoomLine(l.id(), l.at(), l.staffId(), l.name(), l.rank(), "", true));
        roomListeners.forEach(x -> x.accept(o));
        return o;
    }

    private boolean mayDelete(Account by, long authorId, long at) {
        return by.rank == Rank.OWNER || (authorId == by.id && clock.getAsLong() - at <= DELETE_MS);
    }

    // ------------------------------------------------------------------ messages

    /**
     * Sends a message: {@code to} "all" or a staff account's id; {@code replyTo} (0 for none) is marked read for the
     * sender, as REPLY does (D328).
     */
    public JsonObject send(Account from, String to, String raw, long replyTo) throws Exception {
        String text = clean(raw, true);
        if (text == null) {
            throw new Refused("Write up to " + MAX_TEXT + " characters.");
        }
        long toId;
        if (to.equals("all")) {
            toId = 0; // every rank may write to all staff (D319)
        } else {
            try {
                toId = Long.parseLong(to);
            } catch (NumberFormatException e) {
                throw new Refused("Pick a staff member or All staff.");
            }
            long t = toId;
            if (t == from.id || store.account(t).filter(a -> a.active).isEmpty()) {
                throw new Refused(t == from.id ? "Pick someone else, or All staff." : "That staff account is gone.");
            }
        }
        if (replyTo > 0) {
            PanelStore.Message original = store.message(replyTo).orElseThrow(() -> new Refused("The message you are replying to is gone."));
            if (original.toId() != 0 && original.toId() != from.id) {
                throw new Refused("That message is not yours."); // checked before anything is stored (audit)
            }
        }
        limit(from);
        long now = clock.getAsLong();
        long id = store.messageInsert(now, from.id, toId, text);
        if (replyTo > 0) {
            read(from, replyTo);
        }
        tell(toId == 0 ? null : Set.of(toId, from.id));
        return store.message(id).map(m -> {
            try {
                return json(m, from, names(), store.reads(List.of(m.id())), activeCount());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).orElseThrow();
    }

    private void tell(Set<Long> ids) {
        messageListeners.forEach(l -> l.accept(ids)); // null: everyone
    }

    /** Marks a message read by this staff member (GOT IT, D328); only one meant for them. */
    public void read(Account me, long id) throws Exception {
        PanelStore.Message m = store.message(id).orElseThrow(() -> new Refused("That message is gone."));
        if (m.toId() != 0 && m.toId() != me.id) {
            throw new Refused("That message is not yours.");
        }
        if (m.fromId() == me.id) {
            return;
        }
        store.markRead(id, me.id, clock.getAsLong());
        tell(new HashSet<>(List.of(me.id, m.fromId())));
    }

    public void deleteMessage(Account by, long id) throws Exception {
        PanelStore.Message m = store.message(id).orElseThrow(() -> new Refused("That message is gone."));
        if (!mayDelete(by, m.fromId(), m.at())) {
            throw new Refused(m.fromId() == by.id ? "Your own messages can be deleted for 5 minutes after you send them." : "Only an Owner deletes other people's messages.");
        }
        store.messageDelete(id);
        tell(m.toId() == 0 ? null : Set.of(m.toId(), m.fromId()));
    }

    private Map<Long, Account> names() throws Exception {
        Map<Long, Account> out = new HashMap<>();
        for (Account a : store.accounts()) {
            out.put(a.id, a);
        }
        return out;
    }

    private int activeCount() throws Exception {
        return (int) store.accounts().stream().filter(a -> a.active).count();
    }

    private static String nameOf(Map<Long, Account> who, long id) {
        Account a = who.get(id);
        return a == null ? "(gone)" : a.mcName;
    }

    /**
     * A message as {@code me} sees it: who from and to, whether it is theirs, whether they read it; for its sender, when it
     * was read ("Read 21:40") or by whom ("Read by 4 of 6", D315).
     */
    private static JsonObject json(PanelStore.Message m, Account me, Map<Long, Account> who, List<PanelStore.Read> reads, int active) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id());
        o.addProperty("at", m.at());
        o.addProperty("fromId", m.fromId());
        o.addProperty("from", nameOf(who, m.fromId()));
        Account f = who.get(m.fromId());
        o.addProperty("fromRank", f == null ? "MODERATOR" : f.rank.name());
        o.addProperty("fromRankLabel", f == null ? "" : f.rank.label());
        o.addProperty("to", m.toId() == 0 ? "all" : nameOf(who, m.toId()));
        o.addProperty("toId", m.toId());
        o.addProperty("text", m.deleted() ? "" : m.text());
        o.addProperty("deleted", m.deleted());
        boolean mine = m.fromId() == me.id;
        o.addProperty("mine", mine);
        List<PanelStore.Read> of = reads.stream().filter(r -> r.messageId() == m.id()).toList();
        o.addProperty("read", mine || of.stream().anyMatch(r -> r.staffId() == me.id));
        of.stream().filter(r -> r.staffId() == me.id).findFirst().ifPresent(r -> o.addProperty("readAt", r.at()));
        if (mine) {
            if (m.toId() != 0) {
                of.stream().filter(r -> r.staffId() == m.toId()).findFirst().ifPresent(r -> o.addProperty("theyReadAt", r.at()));
            } else {
                JsonArray names = new JsonArray();
                of.stream().filter(r -> r.staffId() != me.id).forEach(r -> names.add(nameOf(who, r.staffId())));
                o.add("readers", names);
                o.addProperty("of", Math.max(0, active - 1));
            }
        }
        return o;
    }

    /** Every message this staff member sees, newest first, and how many are unread. */
    public JsonObject messages(Account me) throws Exception {
        List<PanelStore.Message> list = store.messagesOf(me.id);
        List<PanelStore.Read> reads = store.reads(list.stream().map(PanelStore.Message::id).toList());
        Map<Long, Account> who = names();
        int active = activeCount(), unread = 0;
        JsonArray out = new JsonArray();
        for (PanelStore.Message m : list) {
            JsonObject o = json(m, me, who, reads, active);
            if (!o.get("read").getAsBoolean() && !m.deleted()) {
                unread++;
            }
            out.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("messages", out);
        o.addProperty("unread", unread);
        return o;
    }

    /** Who can be written to: every active account but this one, by name. */
    public JsonArray people(Account me) throws Exception {
        JsonArray out = new JsonArray();
        store.accounts().stream().filter(a -> a.active && a.id != me.id).sorted((x, y) -> x.mcName.compareToIgnoreCase(y.mcName)).forEach(a -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id);
            o.addProperty("name", a.mcName);
            o.addProperty("rank", a.rank.name());
            o.addProperty("rankLabel", a.rank.label());
            out.add(o);
        });
        return out;
    }

    /**
     * Drops room lines older than 30 days, and messages older than 30 days that everyone they were for has read (or whose
     * reader's account is gone): an unread message is kept until it is read (D316). How many of each went.
     */
    public int[] prune() throws Exception {
        long cut = clock.getAsLong() - KEEP_MS;
        int lines = store.roomPrune(cut);
        List<PanelStore.Message> old = store.messagesBefore(cut);
        List<PanelStore.Read> reads = store.reads(old.stream().map(PanelStore.Message::id).toList());
        Set<Long> active = new HashSet<>();
        store.accounts().stream().filter(a -> a.active).forEach(a -> active.add(a.id));
        List<Long> drop = new ArrayList<>();
        for (PanelStore.Message m : old) {
            Set<Long> readBy = new HashSet<>();
            reads.stream().filter(r -> r.messageId() == m.id()).forEach(r -> readBy.add(r.staffId()));
            Set<Long> for_ = m.toId() == 0 ? new HashSet<>(active) : new HashSet<>(Set.of(m.toId()));
            for_.remove(m.fromId());
            for_.retainAll(active); // a removed account will never read it
            if (m.deleted() || readBy.containsAll(for_)) {
                drop.add(m.id());
            }
        }
        store.messagesDelete(drop);
        return new int[]{lines, drop.size()};
    }
}
