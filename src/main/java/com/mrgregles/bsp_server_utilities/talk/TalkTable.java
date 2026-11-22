package com.mrgregles.bsp_server_utilities.talk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The conversations and lines in memory, as the shared tables hold them: the store of {@link TalkData} (this world's
 * save, D645) and of the tests' {@code MemoryBackend}. One open conversation per player, as the tables' unique key keeps it.
 */
public final class TalkTable implements TalkStore {
    private final Map<Long, Talk> talks = new LinkedHashMap<>();
    private final List<Line> lines = new ArrayList<>();
    private final Set<String> uids = new HashSet<>();
    private long nextTalk = 1, nextLine = 1;
    @Nullable
    private Runnable changed;

    /** Called after every change (the save marks itself dirty). */
    public TalkTable onChange(@Nullable Runnable r) {
        changed = r;
        return this;
    }

    private void changed() {
        if (changed != null) {
            changed.run();
        }
    }

    @Override
    @Nullable
    public synchronized Talk openTalk(UUID player) {
        return talks.values().stream().filter(t -> t.open() && t.player().equals(player)).findFirst().orElse(null);
    }

    @Override
    public synchronized Talk openOrCreateTalk(UUID player, String name, long at, String by) {
        Talk t = openTalk(player);
        if (t != null) {
            return t;
        }
        t = new Talk(nextTalk++, player, name, at, by, 0, "", 0, 0, 0, "");
        talks.put(t.id(), t);
        changed();
        return t;
    }

    @Override
    @Nullable
    public synchronized Talk talk(long id) {
        return talks.get(id);
    }

    @Override
    public synchronized List<Talk> talksOf(UUID player) {
        return talks.values().stream().filter(t -> t.player().equals(player))
                .sorted(Comparator.comparingLong(Talk::openedAt).thenComparingLong(Talk::id).reversed()).toList();
    }

    @Override
    public synchronized List<Talk> openTalks() {
        return talks.values().stream().filter(Talk::open).toList();
    }

    @Override
    public synchronized boolean insertTalkLine(Line l, boolean whileOpen) {
        Talk t = talks.get(l.talk());
        if (whileOpen && (t == null || !t.open())) {
            return false;
        }
        if (!uids.add(l.uid())) {
            return true;
        }
        lines.add(new Line(nextLine++, l.uid(), l.talk(), l.player(), l.at(), l.kind(), l.staff(), l.text(), l.server(), l.line()));
        changed();
        return true;
    }

    @Override
    public synchronized void touchTalk(long id, String kind, long at, String name) {
        Talk t = talks.get(id);
        if (t == null) {
            return;
        }
        boolean player = kind.equals(KIND_PLAYER);
        talks.put(id, new Talk(t.id(), t.player(), name.isEmpty() ? t.name() : name, t.openedAt(), t.openedBy(), t.endedAt(), t.endedBy(),
                player ? t.lastStaffAt() : Math.max(t.lastStaffAt(), at), player ? Math.max(t.lastPlayerAt(), at) : t.lastPlayerAt(), t.toldAt(), kind));
        changed();
    }

    @Override
    public synchronized boolean endTalk(long id, long at, String by) {
        Talk t = talks.get(id);
        if (t == null || !t.open()) {
            return false;
        }
        talks.put(id, new Talk(t.id(), t.player(), t.name(), t.openedAt(), t.openedBy(), Math.max(1, at), by, t.lastStaffAt(), t.lastPlayerAt(), t.toldAt(), t.lastKind()));
        changed();
        return true;
    }

    @Override
    public synchronized void talkTold(long id, long at) {
        Talk t = talks.get(id);
        if (t != null && at > t.toldAt()) {
            talks.put(id, new Talk(t.id(), t.player(), t.name(), t.openedAt(), t.openedBy(), t.endedAt(), t.endedBy(), t.lastStaffAt(), t.lastPlayerAt(), at, t.lastKind()));
            changed();
        }
    }

    @Override
    public synchronized List<Line> talkLines(List<Long> ids) {
        Set<Long> want = new HashSet<>(ids);
        return lines.stream().filter(l -> want.contains(l.talk())).sorted(Comparator.comparingLong(Line::id)).toList(); // the order taken (audit)
    }

    @Override
    public synchronized int pruneTalkLines(long beforeMs) {
        int n = lines.size();
        lines.removeIf(l -> {
            if (l.at() < beforeMs) {
                uids.remove(l.uid());
                return true;
            }
            return false;
        });
        if (n != lines.size()) {
            changed();
        }
        return n - lines.size();
    }

    @Override
    public synchronized List<Talk> talksWithoutLines(long openedBefore) {
        Set<Long> used = new HashSet<>();
        lines.forEach(l -> used.add(l.talk()));
        return talks.values().stream().filter(t -> t.openedAt() < openedBefore && !used.contains(t.id())).toList();
    }

    @Override
    public synchronized void deleteTalks(List<Long> ids) {
        boolean any = false;
        for (long id : ids) {
            Talk t = talks.get(id);
            if (t != null && !t.open()) {
                talks.remove(id);
                any = true;
            }
        }
        if (any) {
            changed();
        }
    }

    /** How many lines are kept (tests). */
    public synchronized int lineCount() {
        return lines.size();
    }

    // ------------------------------------------------------------------ the world's save

    public synchronized CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("nextTalk", nextTalk);
        tag.putLong("nextLine", nextLine);
        ListTag ts = new ListTag();
        for (Talk t : talks.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("id", t.id());
            c.putUUID("player", t.player());
            c.putString("name", t.name());
            c.putLong("openedAt", t.openedAt());
            c.putString("openedBy", t.openedBy());
            c.putLong("endedAt", t.endedAt());
            c.putString("endedBy", t.endedBy());
            c.putLong("lastStaffAt", t.lastStaffAt());
            c.putLong("lastPlayerAt", t.lastPlayerAt());
            c.putLong("toldAt", t.toldAt());
            c.putString("lastKind", t.lastKind());
            ts.add(c);
        }
        tag.put("talks", ts);
        ListTag ls = new ListTag();
        for (Line l : lines) {
            CompoundTag c = new CompoundTag();
            c.putLong("id", l.id());
            c.putString("uid", l.uid());
            c.putLong("talk", l.talk());
            c.putUUID("player", l.player());
            c.putLong("at", l.at());
            c.putString("kind", l.kind());
            c.putString("staff", l.staff());
            c.putString("text", l.text());
            c.putString("server", l.server());
            c.putString("line", l.line());
            ls.add(c);
        }
        tag.put("lines", ls);
        return tag;
    }

    public static TalkTable load(CompoundTag tag) {
        TalkTable t = new TalkTable();
        for (Tag x : tag.getList("talks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (!c.hasUUID("player")) {
                continue;
            }
            Talk k = new Talk(c.getLong("id"), c.getUUID("player"), c.getString("name"), c.getLong("openedAt"), c.getString("openedBy"), c.getLong("endedAt"),
                    c.getString("endedBy"), c.getLong("lastStaffAt"), c.getLong("lastPlayerAt"), c.getLong("toldAt"), c.getString("lastKind"));
            t.talks.put(k.id(), k);
        }
        for (Tag x : tag.getList("lines", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            if (!c.hasUUID("player") || !t.uids.add(c.getString("uid"))) {
                continue;
            }
            t.lines.add(new Line(c.getLong("id"), c.getString("uid"), c.getLong("talk"), c.getUUID("player"), c.getLong("at"), c.getString("kind"),
                    c.getString("staff"), c.getString("text"), c.getString("server"), c.getString("line")));
        }
        long maxTalk = t.talks.keySet().stream().mapToLong(Long::longValue).max().orElse(0), maxLine = t.lines.stream().mapToLong(Line::id).max().orElse(0);
        t.nextTalk = Math.max(tag.getLong("nextTalk"), maxTalk + 1);
        t.nextLine = Math.max(tag.getLong("nextLine"), maxLine + 1);
        return t;
    }
}
