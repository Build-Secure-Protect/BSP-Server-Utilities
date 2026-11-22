package com.mrgregles.bsp_server_utilities.talk;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Where private staff messages are kept (owner decisions D630 to D652): the shared tables {@code <prefix>talks} and
 * {@code <prefix>talk_lines} on the network ({@code JdbcBackend}), a table in memory for tests ({@code MemoryBackend}), or
 * this world's save on a server without shared storage ({@link TalkData}, D645). Every method is called from one thread at
 * a time (the storage's worker, or the server thread for the save). The rules are in {@link TalkBook}; this is only where.
 */
public interface TalkStore {
    /**
     * A conversation between staff and one player (D631: all staff share it): who opened it and when, who ended it and when
     * ({@code endedAt} 0 while open), the newest staff and player lines' times, and when the player was last told of the
     * staff lines (the popup or the login line, D635, D636; "not seen it yet" on the panel while it is older than
     * {@code lastStaffAt}). {@code endedBy} is empty for a conversation that ended itself after 90 days with no line (D643).
     * {@code lastKind} is who wrote the newest line ({@link #KIND_STAFF} or {@link #KIND_PLAYER}) in the order the table
     * took them, so two servers' clocks never decide who is waiting (audit of SU-111); empty on a row from before it.
     */
    record Talk(long id, UUID player, String name, long openedAt, String openedBy, long endedAt, String endedBy, long lastStaffAt, long lastPlayerAt, long toldAt,
                String lastKind) {
        public boolean open() {
            return endedAt == 0;
        }

        /** The newest line is from staff and the player has not answered it (D634: the key flashes). */
        public boolean waitingOnPlayer() {
            return lastKind.isEmpty() ? lastStaffAt > lastPlayerAt : lastKind.equals(KIND_STAFF);
        }

        /** The player wrote last (D639: the panel counts these). */
        public boolean waitingOnStaff() {
            return lastKind.isEmpty() ? lastPlayerAt > lastStaffAt : lastKind.equals(KIND_PLAYER);
        }

        /** Staff lines the player has not been told of yet (D636). */
        public boolean untold() {
            return lastStaffAt > toldAt;
        }
    }

    String KIND_STAFF = "staff", KIND_PLAYER = "player", KIND_END = "end";

    /**
     * One line: {@code kind} {@link #KIND_STAFF} (by {@code staff}), {@link #KIND_PLAYER}, or {@link #KIND_END} (the
     * conversation ended, by {@code staff}, empty when it ended itself; no text). {@code server} is where it was written;
     * {@code line} is all of it signed with the action key, which is what the staff panel believes (empty without a key).
     */
    record Line(long id, String uid, long talk, UUID player, long at, String kind, String staff, String text, String server, String line) {
    }

    /** The player's open conversation, or null. */
    @Nullable
    Talk openTalk(UUID player) throws Exception;

    /**
     * The player's open conversation, opened now by {@code by} when there is none. Only ever one open per player, even when
     * two servers open one at the same moment (the table's unique key decides).
     */
    Talk openOrCreateTalk(UUID player, String name, long at, String by) throws Exception;

    @Nullable
    Talk talk(long id) throws Exception;

    /** The player's conversations, newest first. */
    List<Talk> talksOf(UUID player) throws Exception;

    /** Every open conversation. */
    List<Talk> openTalks() throws Exception;

    /**
     * Adds a line; one whose {@code uid} is stored already is skipped. {@code whileOpen}: only while its conversation is
     * still open, so a line never lands in a conversation another server ended a moment before (audit of SU-111); false
     * when it was not added for that.
     */
    boolean insertTalkLine(Line line, boolean whileOpen) throws Exception;

    /**
     * Moves a conversation's newest staff or player line time forward (never back), records {@code kind} as the newest
     * line's writer, and keeps the player's name current.
     */
    void touchTalk(long id, String kind, long at, String name) throws Exception;

    /** Ends a conversation that is open; false when it was ended already (by someone else first). */
    boolean endTalk(long id, long at, String by) throws Exception;

    /** Records that the player was told of the staff lines up to {@code at} (never moves back). */
    void talkTold(long id, long at) throws Exception;

    /** These conversations' lines, oldest first. */
    List<Line> talkLines(List<Long> talks) throws Exception;

    /** Drops lines written before {@code beforeMs}; how many went. */
    int pruneTalkLines(long beforeMs) throws Exception;

    /**
     * Conversations opened before {@code openedBefore} with no line left (for the 90-day prune, D643); the time keeps a
     * conversation being opened on another server this moment, before its first line is written, out of it.
     */
    List<Talk> talksWithoutLines(long openedBefore) throws Exception;

    /** Drops these conversations (ended ones with no line left). */
    void deleteTalks(List<Long> ids) throws Exception;
}
