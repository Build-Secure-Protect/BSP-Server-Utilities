package com.mrgregles.bsp_staff_panel.notes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Player notes and tags (SU-38, D266, D267, D320 to D326, D330, D331). Notes: every staff member sees them with author and
 * time; the author or an Admin or Owner edits ("edited", the earlier words kept in the panel's log) or deletes them; plain
 * text up to 1000 characters. Tags: a list admins keep with colours, beside names everywhere in the panel; any staff member
 * puts a tag on a player or takes it off, and may type a new one, which joins the list grey until an admin colours,
 * renames or removes it. Everything is written to the panel's own log, not the game's Action Log (D326).
 */
public final class PlayerNotes {
    public static final int MAX_NOTE = 1000, MAX_TAG = 24;
    public static final String GREY = "#6b7180";
    /** The colours an admin picks from (the panel's palette). */
    public static final Set<String> COLOURS = Set.of("#ff6b5c", "#ffd23a", "#8fe04a", "#19d3b0", "#7fb2ff", "#b58cff", "#ff55ff", "#9aa3b5");

    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    private final PanelStore store;
    private final LongSupplier clock;
    private final List<Consumer<String>> noteListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> tagListeners = new CopyOnWriteArrayList<>();

    public PlayerNotes(PanelStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Hears the player whose notes changed. */
    public void onNotes(Consumer<String> l) {
        noteListeners.add(l);
    }

    /** Hears any change to the tag list or to who carries which tag. */
    public void onTags(Runnable l) {
        tagListeners.add(l);
    }

    private static String player(String id) throws Refused {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new Refused("No such player.");
        }
        return id;
    }

    private static String playerName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}") ? name : "?";
    }

    /** A note's words as they may be kept: trimmed, line breaks allowed, no other control characters, 1 to 1000 characters. */
    public static String cleanNote(String raw) {
        String t = raw == null ? "" : raw.replace("\r\n", "\n").strip();
        if (t.isEmpty() || t.length() > MAX_NOTE || t.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            return null;
        }
        return t;
    }

    /** A tag's name: letters, digits, spaces and a little punctuation, 1 to 24 characters, single spaces. */
    public static String cleanTag(String raw) {
        String t = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        return t.isEmpty() || t.length() > MAX_TAG || !t.matches("[\\p{L}\\p{N} ?!'._-]+") ? null : t;
    }

    private static boolean admin(Account a) {
        return a.rank.atLeast(Rank.ADMIN);
    }

    private void log(Account by, String what) throws Exception {
        store.log(new PanelStore.LogLine(clock.getAsLong(), by.email, what));
    }

    // ------------------------------------------------------------------ notes

    /** A note as the pages get it, with its author's rank now (D320: the card shows it; nothing for a removed account). */
    private JsonObject json(PanelStore.Note n) throws Exception {
        return json(n, ranks());
    }

    private java.util.Map<Long, com.mrgregles.bsp_staff_panel.auth.Rank> ranks() throws Exception {
        java.util.Map<Long, com.mrgregles.bsp_staff_panel.auth.Rank> r = new java.util.HashMap<>();
        for (com.mrgregles.bsp_staff_panel.store.Account a : store.accounts()) {
            if (a.active) {
                r.put(a.id, a.rank);
            }
        }
        return r;
    }

    private static JsonObject json(PanelStore.Note n, java.util.Map<Long, com.mrgregles.bsp_staff_panel.auth.Rank> ranks) {
        JsonObject o = new JsonObject();
        com.mrgregles.bsp_staff_panel.auth.Rank rank = ranks.get(n.authorId());
        if (rank != null) {
            o.addProperty("authorRank", rank.name());
            o.addProperty("authorRankLabel", rank.label());
        }
        o.addProperty("id", n.id());
        o.addProperty("at", n.at());
        o.addProperty("authorId", n.authorId());
        o.addProperty("author", n.authorName());
        o.addProperty("text", n.text());
        o.addProperty("editedAt", n.editedAt());
        return o;
    }

    /** A player's notes, oldest first. */
    public JsonArray notes(String playerId) throws Exception {
        JsonArray out = new JsonArray();
        java.util.Map<Long, com.mrgregles.bsp_staff_panel.auth.Rank> ranks = ranks();
        store.notesOf(player(playerId)).forEach(n -> out.add(json(n, ranks)));
        return out;
    }

    public JsonObject add(Account by, String playerId, String name, String raw) throws Exception {
        String p = player(playerId), text = cleanNote(raw);
        if (text == null) {
            throw new Refused("Write up to " + MAX_NOTE + " characters.");
        }
        long at = clock.getAsLong();
        long id = store.noteInsert(p, playerName(name), at, by.id, by.mcName, text);
        log(by, "added a note about " + playerName(name) + " (" + p + "): " + text);
        noteListeners.forEach(l -> l.accept(p));
        return json(new PanelStore.Note(id, p, playerName(name), at, by.id, by.mcName, text, 0));
    }

    private PanelStore.Note mine(Account by, long id, String verb) throws Exception {
        PanelStore.Note n = store.note(id).orElseThrow(() -> new Refused("That note is gone."));
        if (n.authorId() != by.id && !admin(by)) {
            throw new Refused("Only whoever wrote a note, or an Admin, " + verb + " it.");
        }
        return n;
    }

    /** The author or an Admin (D330): "edited", the earlier words in the panel's log (D324). */
    public JsonObject edit(Account by, long id, String raw) throws Exception {
        PanelStore.Note n = mine(by, id, "changes");
        String text = cleanNote(raw);
        if (text == null) {
            throw new Refused("Write up to " + MAX_NOTE + " characters.");
        }
        long at = clock.getAsLong();
        store.noteUpdate(id, text, at);
        log(by, "edited a note about " + n.playerName() + " (" + n.player() + ") by " + n.authorName() + "; it said: " + n.text());
        noteListeners.forEach(l -> l.accept(n.player()));
        return json(new PanelStore.Note(n.id(), n.player(), n.playerName(), n.at(), n.authorId(), n.authorName(), text, at));
    }

    public void delete(Account by, long id) throws Exception {
        PanelStore.Note n = mine(by, id, "deletes");
        store.noteDelete(id);
        log(by, "deleted a note about " + n.playerName() + " (" + n.player() + ") by " + n.authorName() + ": " + n.text());
        noteListeners.forEach(l -> l.accept(n.player()));
    }

    /**
     * For the game servers (D332): each of these players' notes count, the newest's author and time; players without
     * notes are left out.
     */
    public JsonObject counts(List<String> players) throws Exception {
        JsonObject out = new JsonObject();
        for (PanelStore.Note n : store.notesOfAll(players)) { // oldest first: the last one seen is the newest
            JsonObject c = out.has(n.player()) ? out.getAsJsonObject(n.player()) : new JsonObject();
            c.addProperty("n", (c.has("n") ? c.get("n").getAsInt() : 0) + 1);
            c.addProperty("by", n.authorName());
            c.addProperty("at", n.at());
            out.add(n.player(), c);
        }
        return out;
    }

    // ------------------------------------------------------------------ tags

    /** The list (with how many players carry each) and who carries what: {tags: [...], players: {uuid: [ids]}}. */
    public JsonObject tags() throws Exception {
        Map<Long, Integer> counts = new HashMap<>();
        JsonObject players = new JsonObject();
        for (PanelStore.PlayerTag pt : store.playerTags()) {
            counts.merge(pt.tagId(), 1, Integer::sum);
            if (!players.has(pt.player())) {
                players.add(pt.player(), new JsonArray());
            }
            players.getAsJsonArray(pt.player()).add(pt.tagId());
        }
        JsonArray list = new JsonArray();
        for (PanelStore.Tag t : store.tags()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("name", t.name());
            o.addProperty("colour", t.colour());
            o.addProperty("typed", t.typed());
            o.addProperty("by", t.byName());
            o.addProperty("at", t.at());
            o.addProperty("count", counts.getOrDefault(t.id(), 0));
            list.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("tags", list);
        o.add("players", players);
        return o;
    }

    private Optional<PanelStore.Tag> byName(String name) throws Exception {
        return store.tags().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * Puts a tag on a player (any staff member, D267): one from the list by id, or by name, which joins the list grey when it
     * is new (D322).
     */
    public void put(Account by, String playerId, String name, long tagId, String typedName) throws Exception {
        String p = player(playerId);
        PanelStore.Tag tag;
        if (tagId > 0) {
            tag = store.tags().stream().filter(t -> t.id() == tagId).findFirst().orElseThrow(() -> new Refused("That tag is gone."));
        } else {
            String n = cleanTag(typedName);
            if (n == null) {
                throw new Refused("A tag is 1 to " + MAX_TAG + " letters, digits or spaces.");
            }
            Optional<PanelStore.Tag> known = byName(n);
            if (known.isPresent()) {
                tag = known.get();
            } else {
                long at = clock.getAsLong();
                try {
                    long id = store.tagInsert(n, GREY, true, by.id, by.mcName, at);
                    tag = new PanelStore.Tag(id, n, GREY, true, by.id, by.mcName, at);
                    log(by, "typed a new tag: " + n);
                } catch (Exception clash) { // someone typed the same tag a moment before (audit)
                    tag = byName(n).orElseThrow(() -> clash);
                }
            }
        }
        store.playerTagAdd(p, tag.id(), by.id, clock.getAsLong());
        log(by, "tagged " + playerName(name) + " (" + p + ") " + tag.name());
        tagListeners.forEach(Runnable::run);
    }

    /** Takes a tag off a player (any staff member, D325). */
    public void take(Account by, String playerId, String name, long tagId) throws Exception {
        String p = player(playerId);
        PanelStore.Tag tag = store.tags().stream().filter(t -> t.id() == tagId).findFirst().orElseThrow(() -> new Refused("That tag is gone."));
        store.playerTagRemove(p, tagId);
        log(by, "took " + tag.name() + " off " + playerName(name) + " (" + p + ")");
        tagListeners.forEach(Runnable::run);
    }

    /** An admin renames a tag or gives it a colour (which keeps a typed tag in the list for good, D322). */
    public void editTag(Account by, long tagId, String newName, String colour) throws Exception {
        if (!admin(by)) {
            throw new Refused("Only Admins and Owners change the tag list.");
        }
        PanelStore.Tag t = store.tags().stream().filter(x -> x.id() == tagId).findFirst().orElseThrow(() -> new Refused("That tag is gone."));
        String name = newName == null || newName.isBlank() ? t.name() : cleanTag(newName);
        if (name == null) {
            throw new Refused("A tag is 1 to " + MAX_TAG + " letters, digits or spaces.");
        }
        Optional<PanelStore.Tag> clash = byName(name);
        if (clash.isPresent() && clash.get().id() != tagId) {
            throw new Refused("There is a tag called " + clash.get().name() + " already.");
        }
        String c = colour == null || colour.isBlank() ? t.colour() : colour.toLowerCase(Locale.ROOT);
        if (!c.equals(GREY) && !COLOURS.contains(c)) {
            throw new Refused("Pick one of the colours.");
        }
        boolean typed = t.typed() && (colour == null || colour.isBlank());
        store.tagUpdate(tagId, name, c, typed);
        log(by, "changed the tag " + t.name() + (name.equals(t.name()) ? "" : " to " + name) + (c.equals(t.colour()) ? "" : ", colour " + c));
        tagListeners.forEach(Runnable::run);
    }

    /** An admin removes a tag from the list and from every player. */
    public void removeTag(Account by, long tagId) throws Exception {
        if (!admin(by)) {
            throw new Refused("Only Admins and Owners change the tag list.");
        }
        PanelStore.Tag t = store.tags().stream().filter(x -> x.id() == tagId).findFirst().orElseThrow(() -> new Refused("That tag is gone."));
        store.tagDelete(tagId);
        log(by, "removed the tag " + t.name() + " from the list and every player");
        tagListeners.forEach(Runnable::run);
    }
}
