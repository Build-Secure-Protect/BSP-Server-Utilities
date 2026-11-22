package com.mrgregles.bsp_server_utilities.talk;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * The rules of private staff messages on any {@link TalkStore} (owner decisions D630 to D652), apart from who may write
 * (checked by {@link TalkService} before anything is stored):
 * <ul>
 * <li>One conversation per player, shared by all staff (D631): a staff line opens one when none is open, else joins it.</li>
 * <li>A player writes only into an open conversation (D633: staff write first; after the end the player cannot reopen it).</li>
 * <li>Any staff member ends it, whoever wrote last (D632); only once, the first end wins.</li>
 * <li>Every line is kept 90 days from when it was written; a conversation left with no line ends itself, and an ended one
 * with no line is dropped (D643).</li>
 * </ul>
 * Every line carries a signed copy for the staff panel ({@code line}, docs/API.md "Private staff messages").
 */
public final class TalkBook {
    public static final int MAX_TEXT = 256;
    public static final long KEEP_MS = 90L * 86_400_000L;
    /** A conversation younger than this with no line is being opened right now on some server: never pruned. */
    static final long OPENING_MS = 60_000L;

    private TalkBook() {
    }

    /** A line written: the conversation after it, the line, and whether this line opened the conversation. */
    public record Written(TalkStore.Talk talk, TalkStore.Line line, boolean opened) {
    }

    /**
     * The JSON the panel reads (docs/API.md): type "talk", v 1, uid, talk, player, name, at, kind, staff, text, server;
     * {@code sign} seals it with the action key (it answers "" without a key).
     */
    public static String signed(UnaryOperator<String> sign, String uid, long talk, UUID player, String name, long at, String kind, String staff, String text, String server) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "talk");
        o.addProperty("v", 1);
        o.addProperty("uid", uid);
        o.addProperty("talk", talk);
        o.addProperty("player", player.toString());
        o.addProperty("name", name);
        o.addProperty("at", at);
        o.addProperty("kind", kind);
        o.addProperty("staff", staff);
        o.addProperty("text", text);
        o.addProperty("server", server);
        String s = sign.apply(o.toString());
        return s == null ? "" : s;
    }

    /** A staff name as the tables keep it (32 characters): a command block's or an entity's long name is cut, never refused (audit). */
    static String clip(String name) {
        return name.length() <= 32 ? name : name.substring(0, 32);
    }

    private static TalkStore.Line line(UnaryOperator<String> sign, long talk, UUID player, String name, long at, String kind, String staff, String text, String server) {
        String uid = UUID.randomUUID().toString();
        return new TalkStore.Line(0, uid, talk, player, at, kind, staff, text, server, signed(sign, uid, talk, player, name, at, kind, staff, text, server));
    }

    /** A staff member's line to a player: into the open conversation, or opening one (D631). {@code text} is already clean. */
    public static Written staffWrites(TalkStore s, UUID player, String name, String staff, String text, long at, String server, UnaryOperator<String> sign) throws Exception {
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("a line of 1 to " + MAX_TEXT + " characters");
        }
        String by = clip(staff);
        for (int tries = 0; ; tries++) {
            TalkStore.Talk before = s.openTalk(player);
            TalkStore.Talk t = before != null ? before : s.openOrCreateTalk(player, name, at, by);
            boolean opened = before == null && t.openedAt() == at && t.openedBy().equals(by);
            TalkStore.Line l = line(sign, t.id(), player, name, at, TalkStore.KIND_STAFF, by, text, server);
            if (!s.insertTalkLine(l, true)) {
                if (tries < 2) {
                    continue; // ended on another server a moment ago: the line opens a new conversation (audit of SU-111)
                }
                throw new IllegalStateException("the conversation kept ending while a line was written");
            }
            s.touchTalk(t.id(), TalkStore.KIND_STAFF, at, name);
            TalkStore.Talk now = s.talk(t.id());
            return new Written(now != null ? now : t, l, opened);
        }
    }

    /** The player's answer; null when they have no open conversation (D633). */
    @Nullable
    public static Written playerWrites(TalkStore s, UUID player, String name, String text, long at, String server, UnaryOperator<String> sign) throws Exception {
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("a line of 1 to " + MAX_TEXT + " characters");
        }
        TalkStore.Talk t = s.openTalk(player);
        if (t == null) {
            return null;
        }
        TalkStore.Line l = line(sign, t.id(), player, name, at, TalkStore.KIND_PLAYER, "", text, server);
        if (!s.insertTalkLine(l, true)) {
            return null; // ended a moment ago: never an answer into a closed conversation (audit)
        }
        s.touchTalk(t.id(), TalkStore.KIND_PLAYER, at, name);
        TalkStore.Talk now = s.talk(t.id());
        return new Written(now != null ? now : t, l, false);
    }

    /**
     * Ends the player's open conversation (D632): {@code staff} is who ended it (empty: it ended itself, D643). Null when
     * there was none open, or another server ended it first.
     */
    @Nullable
    public static Written end(TalkStore s, UUID player, String staff, long at, String server, UnaryOperator<String> sign) throws Exception {
        TalkStore.Talk t = s.openTalk(player);
        return t == null ? null : endTalk(s, t, staff, at, server, sign);
    }

    @Nullable
    private static Written endTalk(TalkStore s, TalkStore.Talk t, String staff, long at, String server, UnaryOperator<String> sign) throws Exception {
        String by = clip(staff);
        if (!s.endTalk(t.id(), at, by)) {
            return null;
        }
        TalkStore.Line l = line(sign, t.id(), t.player(), t.name(), at, TalkStore.KIND_END, by, "", server);
        s.insertTalkLine(l, false);
        TalkStore.Talk now = s.talk(t.id());
        return new Written(now != null ? now : t, l, false);
    }

    /** What the 90-day prune did: lines dropped, conversations that ended themselves (their end lines), ended ones dropped. */
    public record Pruned(int lines, List<Written> ended, int dropped) {
    }

    /** The 90-day prune (D643), run every few hours by every server (the result is the same whichever runs it). */
    public static Pruned prune(TalkStore s, long now, String server, UnaryOperator<String> sign) throws Exception {
        int lines = s.pruneTalkLines(now - KEEP_MS);
        List<Written> ended = new ArrayList<>();
        List<Long> drop = new ArrayList<>();
        for (TalkStore.Talk t : s.talksWithoutLines(now - OPENING_MS)) {
            if (t.open()) {
                Written w = endTalk(s, t, "", now, server, sign); // nobody wrote for 90 days: it ends itself, and the key goes
                if (w != null) {
                    ended.add(w);
                }
            } else if (t.endedAt() < now - OPENING_MS) {
                drop.add(t.id()); // ended over a minute ago: another server's prune that ended it a moment ago is still writing its end line (audit)
            }
        }
        s.deleteTalks(drop);
        return new Pruned(lines, ended, drop.size());
    }

    /** A player's conversations of the last 90 days and their lines (the staff window, R2; the panel). */
    public record History(List<TalkStore.Talk> talks, List<TalkStore.Line> lines) {
        @Nullable
        public TalkStore.Talk open() {
            return talks.stream().filter(TalkStore.Talk::open).findFirst().orElse(null);
        }
    }

    public static History history(TalkStore s, UUID player) throws Exception {
        List<TalkStore.Talk> talks = s.talksOf(player);
        List<TalkStore.Line> lines = s.talkLines(talks.stream().map(TalkStore.Talk::id).toList());
        return new History(talks, lines);
    }

    /** Only the open conversation and its lines (what the player sees, D633). */
    public static History playerView(TalkStore s, UUID player) throws Exception {
        TalkStore.Talk t = s.openTalk(player);
        if (t == null) {
            return new History(List.of(), List.of());
        }
        return new History(List.of(t), s.talkLines(List.of(t.id())));
    }
}
