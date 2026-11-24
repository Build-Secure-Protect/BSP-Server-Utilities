package com.mrgregles.bsp_server_utilities.tutorial;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where one player is in one tutorial (owner decisions D722, D733, D738): offered and put off (LATER), turned down
 * (DECLINED), under way (ACTIVE), finished, or ended early. While under way: the point the player is heading for or
 * looking at, whether they have reached it (its popup shown), and the points behind them for PREVIOUS. Kept for the
 * network, so a logout or a move to another server resumes where they were.
 */
public record TutorialProgress(UUID player, String tutorial, State state, int point, boolean reached, List<Integer> history, long atMs) {
    public static final int MAX_HISTORY = 64;

    public enum State { LATER, DECLINED, ACTIVE, FINISHED, ENDED }

    public TutorialProgress {
        history = List.copyOf(history.size() > MAX_HISTORY ? history.subList(history.size() - MAX_HISTORY, history.size()) : history);
    }

    public static TutorialProgress of(UUID player, String tutorial, State state, long atMs) {
        return new TutorialProgress(player, tutorial, state, -1, false, List.of(), atMs);
    }

    public boolean active() {
        return state == State.ACTIVE;
    }

    public TutorialProgress with(State s, long at) {
        // the point is kept for ENDED, so the TUTORIAL tab can count where players stopped (D735)
        return new TutorialProgress(player, tutorial, s, s == State.ACTIVE || s == State.ENDED ? point : -1, s == State.ACTIVE && reached, s == State.ACTIVE ? history : List.of(), at);
    }

    /** Heading for a point (not reached yet), the one before it added to the history when there was one. */
    public TutorialProgress toward(int next, boolean pushCurrent, long at) {
        List<Integer> h = new ArrayList<>(history);
        if (pushCurrent && point >= 0) {
            h.add(point);
        }
        return new TutorialProgress(player, tutorial, State.ACTIVE, next, false, h, at);
    }

    public TutorialProgress arrived(long at) {
        return new TutorialProgress(player, tutorial, state, point, true, history, at);
    }

    /** Back one point (PREVIOUS): the last point of the history, reached again. */
    public TutorialProgress back(long at) {
        if (history.isEmpty()) {
            return arrived(at);
        }
        List<Integer> h = new ArrayList<>(history);
        int last = h.remove(h.size() - 1);
        return new TutorialProgress(player, tutorial, State.ACTIVE, last, true, h, at);
    }

    public String historyText() {
        StringBuilder b = new StringBuilder();
        for (int p : history) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(p);
        }
        return b.toString();
    }

    public static List<Integer> parseHistory(@Nullable String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String part : s.split(",")) {
            try {
                out.add(Integer.parseInt(part.strip()));
            } catch (NumberFormatException ignored) {
                // a damaged entry is left out
            }
        }
        return out;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("player", player);
        t.putString("tutorial", tutorial);
        t.putString("state", state.name());
        t.putInt("point", point);
        t.putBoolean("reached", reached);
        t.putString("history", historyText());
        t.putLong("at", atMs);
        return t;
    }

    @Nullable
    public static TutorialProgress load(CompoundTag t) {
        if (!t.hasUUID("player")) {
            return null;
        }
        return new TutorialProgress(t.getUUID("player"), t.getString("tutorial"), Tutorial.named(State.class, t.getString("state"), State.LATER), t.getInt("point"),
                t.getBoolean("reached"), parseHistory(t.getString("history")), t.getLong("at"));
    }
}
