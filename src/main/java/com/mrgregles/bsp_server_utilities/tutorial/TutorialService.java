package com.mrgregles.bsp_server_utilities.tutorial;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * The tutorials' definitions (SU-137; owner decisions D719 to D744), all on the server thread. Admins edit a draft;
 * PUBLISH copies it to the published copy players follow (D734). Only those who may edit (operator level 2 or a BSP-Core
 * admin) change anything. Only one published tutorial starts on first join: publishing another takes it over (and a
 * draft set to first join takes it from the other drafts). With shared storage every copy is a row of the network's
 * table, the newest revision winning, deletions included; a notice names the copy, and the other servers read it back.
 * A write the table refused is tried again every minute until it lands.
 */
public final class TutorialService {
    public static final String KIND = "tutorial";
    private static final long RETRY_MS = 60_000L;
    private static LongSupplier clock = System::currentTimeMillis;
    private static final Map<String, Tutorial> UNWRITTEN = new HashMap<>();
    private static long lastRetry;

    private TutorialService() {
    }

    /** What an edit came to. */
    public enum Outcome { SAVED, UNCHANGED, NOT_ALLOWED, NOT_FOUND, TOO_BIG }

    /** The longest copy the shared table takes (its text), far above any real tutorial; MySQL's default packet is 4 MB or more. */
    public static final int MAX_TEXT = 3_000_000;

    /** An edit's answer: what happened and the copy in force afterwards (null when there is none). */
    public record Result(Outcome outcome, @Nullable Tutorial tutorial) {
        public boolean saved() {
            return outcome == Outcome.SAVED;
        }
    }

    // ------------------------------------------------------------------ reading

    /** The live (not deleted) copies of a stage, in the order they were made. */
    public static List<Tutorial> list(MinecraftServer server, TutorialStage stage) {
        List<Tutorial> out = new ArrayList<>();
        for (Tutorial t : TutorialData.get(server).all(stage)) {
            if (!t.deleted()) {
                out.add(t);
            }
        }
        return out;
    }

    /** A live copy by id, or empty. */
    public static Optional<Tutorial> get(MinecraftServer server, TutorialStage stage, String id) {
        Tutorial t = TutorialData.get(server).get(stage, Tutorial.uuid(id));
        return t == null || t.deleted() ? Optional.empty() : Optional.of(t);
    }

    /** The published tutorial offered on first join, if one is set. */
    public static Optional<Tutorial> firstJoin(MinecraftServer server) {
        return list(server, TutorialStage.PUBLISHED).stream().filter(t -> t.start() == Tutorial.Start.FIRST_JOIN).findFirst();
    }

    // ------------------------------------------------------------------ editing

    private static boolean allowed(@Nullable ServerPlayer by) {
        return by == null || Perms.canEdit(by);
    }

    private static String who(@Nullable ServerPlayer by, String byName) {
        return by != null ? by.getGameProfile().getName() : byName;
    }

    /** A new tutorial's draft (D720); {@code by} null is the console with {@code byName}. */
    public static Result create(MinecraftServer server, @Nullable ServerPlayer by, String byName, String name) {
        if (!allowed(by)) {
            return new Result(Outcome.NOT_ALLOWED, null);
        }
        Tutorial t = Tutorial.create(name);
        return new Result(Outcome.SAVED, save(server, TutorialStage.DRAFT, t, who(by, byName)));
    }

    /**
     * Changes a draft: the change is sanitised and saved with a new revision. A draft set to start on first join takes
     * that from every other draft (they start with {@code /tutorial} instead).
     */
    public static Result editDraft(MinecraftServer server, @Nullable ServerPlayer by, String byName, String id, UnaryOperator<Tutorial> change) {
        if (!allowed(by)) {
            return new Result(Outcome.NOT_ALLOWED, get(server, TutorialStage.DRAFT, id).orElse(null));
        }
        Optional<Tutorial> was = get(server, TutorialStage.DRAFT, id);
        if (was.isEmpty()) {
            return new Result(Outcome.NOT_FOUND, null);
        }
        Tutorial old = was.get();
        Tutorial next = change.apply(old).sanitised();
        next = new Tutorial(old.id(), next.name(), next.start(), next.audience(), next.area(), next.offer(), next.points(), next.links(), next.editedBy(), old.revision(), false, next.since());
        if (next.equals(old)) {
            return new Result(Outcome.UNCHANGED, old);
        }
        if (next.encode().length() > MAX_TEXT) {
            return new Result(Outcome.TOO_BIG, old);
        }
        String name = who(by, byName);
        Tutorial saved = save(server, TutorialStage.DRAFT, next, name);
        if (saved.start() == Tutorial.Start.FIRST_JOIN) {
            demoteOthers(server, TutorialStage.DRAFT, saved.id(), name);
        }
        return new Result(Outcome.SAVED, saved);
    }

    /**
     * PUBLISH (D734): the draft becomes what players follow. Publishing a first-join tutorial takes first join from the
     * one published before. The builder asks again while {@link Tutorial#issues()} has any; this does not refuse them.
     */
    public static Result publish(MinecraftServer server, @Nullable ServerPlayer by, String byName, String id) {
        if (!allowed(by)) {
            return new Result(Outcome.NOT_ALLOWED, get(server, TutorialStage.PUBLISHED, id).orElse(null));
        }
        Optional<Tutorial> draft = get(server, TutorialStage.DRAFT, id);
        if (draft.isEmpty()) {
            return new Result(Outcome.NOT_FOUND, null);
        }
        Optional<Tutorial> live = get(server, TutorialStage.PUBLISHED, id);
        if (live.isPresent() && sameContent(live.get(), draft.get())) {
            return new Result(Outcome.UNCHANGED, live.get());
        }
        String name = who(by, byName);
        Tutorial copy = draft.get();
        if (copy.start() == Tutorial.Start.FIRST_JOIN) {
            // first join since: kept while it stays the first-join tutorial, else from now
            boolean was = live.isPresent() && live.get().start() == Tutorial.Start.FIRST_JOIN && live.get().since() > 0;
            copy = copy.withSince(was ? live.get().since() : clock.getAsLong());
        }
        Tutorial saved = save(server, TutorialStage.PUBLISHED, copy, name);
        if (saved.start() == Tutorial.Start.FIRST_JOIN) {
            demoteOthers(server, TutorialStage.PUBLISHED, saved.id(), name);
        }
        return new Result(Outcome.SAVED, saved);
    }

    /** Deletes a tutorial, draft and published copy both, for the network. */
    public static Result delete(MinecraftServer server, @Nullable ServerPlayer by, String byName, String id) {
        if (!allowed(by)) {
            return new Result(Outcome.NOT_ALLOWED, null);
        }
        Optional<Tutorial> any = get(server, TutorialStage.DRAFT, id).or(() -> get(server, TutorialStage.PUBLISHED, id));
        if (any.isEmpty()) {
            return new Result(Outcome.NOT_FOUND, null);
        }
        // both copies are marked, even one this server has not heard of yet, so a publish racing the delete cannot survive it (audit of SU-146)
        for (TutorialStage s : TutorialStage.values()) {
            save(server, s, any.get().asDeleted(who(by, byName), 0), who(by, byName));
        }
        return new Result(Outcome.SAVED, null);
    }

    /** Whether two copies say the same, whoever saved them and whenever. */
    static boolean sameContent(Tutorial a, Tutorial b) {
        return a.withRevision(0, "").withSince(0).equals(b.withRevision(0, "").withSince(0));
    }

    private static void demoteOthers(MinecraftServer server, TutorialStage stage, String keep, String by) {
        for (Tutorial t : list(server, stage)) {
            if (!t.id().equals(keep) && t.start() == Tutorial.Start.FIRST_JOIN) {
                save(server, stage, t.withStart(Tutorial.Start.COMMAND), by);
            }
        }
    }

    /** Keeps a copy with a rising revision, writes it to the network's table and tells the other servers. */
    private static Tutorial save(MinecraftServer server, TutorialStage stage, Tutorial t, String by) {
        TutorialData data = TutorialData.get(server);
        Tutorial old = data.get(stage, t.id());
        long rev = Math.max(clock.getAsLong(), Math.max(t.revision(), old == null ? 0 : old.revision()) + 1);
        Tutorial next = t.withRevision(rev, by).sanitised();
        data.put(stage, next);
        Storage st = Storage.active();
        if (st != null) {
            write(st, stage, next); // the notice goes once the row is written (audit of SU-146)
        }
        if (stage == TutorialStage.PUBLISHED) {
            TutorialRunner.changed(server, next.id());
        }
        return next;
    }

    private static String notice(TutorialStage stage, Tutorial t) {
        return stage.key + "|" + t.id() + "|" + t.revision();
    }

    private static void write(Storage st, TutorialStage stage, Tutorial t) {
        String key = stage.key + "|" + t.id();
        synchronized (UNWRITTEN) {
            UNWRITTEN.put(key, t);
        }
        st.submit(() -> {
            st.backend().saveTutorial(stage.key, t);
            synchronized (UNWRITTEN) {
                UNWRITTEN.remove(key, t);
            }
            st.publish(KIND, notice(stage, t));
        });
    }

    /** Every server tick: writes the table refused are tried again once a minute, each with this server's newest copy. */
    public static void tick(MinecraftServer server) {
        Storage st = Storage.active();
        if (st == null || clock.getAsLong() - lastRetry < RETRY_MS) {
            return;
        }
        List<Map.Entry<String, Tutorial>> todo;
        synchronized (UNWRITTEN) {
            if (UNWRITTEN.isEmpty()) {
                return;
            }
            todo = new ArrayList<>(UNWRITTEN.entrySet());
        }
        lastRetry = clock.getAsLong();
        for (Map.Entry<String, Tutorial> e : todo) {
            TutorialStage stage = TutorialStage.of(e.getKey().substring(0, e.getKey().indexOf('|')));
            if (stage == null) {
                continue;
            }
            Tutorial now = TutorialData.get(server).get(stage, e.getValue().id());
            write(st, stage, now != null && now.revision() >= e.getValue().revision() ? now : e.getValue());
        }
    }

    /** Whether any save still waits to reach the network's table (tests). */
    public static boolean unwritten() {
        synchronized (UNWRITTEN) {
            return !UNWRITTEN.isEmpty();
        }
    }

    // ------------------------------------------------------------------ the network

    /** Takes a shared copy when it is newer than ours (a deletion too). True when it was taken. */
    public static boolean apply(MinecraftServer server, TutorialStage stage, Tutorial shared) {
        TutorialData data = TutorialData.get(server);
        Tutorial mine = data.get(stage, shared.id());
        if (shared.id().isEmpty() || (mine != null && mine.revision() >= shared.revision())) {
            return false;
        }
        data.put(stage, shared);
        if (stage == TutorialStage.PUBLISHED) {
            TutorialRunner.changed(server, shared.id());
        }
        return true;
    }

    /** The notice handler for the other servers' saves; once, when the mod is constructed. */
    public static void registerNotices() {
        Storage.on(KIND, (srv, n) -> {
            String[] f = n.payload().split("\\|", -1);
            TutorialStage stage = f.length == 3 ? TutorialStage.of(f[0]) : null;
            if (stage == null) {
                return;
            }
            long rev;
            try {
                rev = Long.parseLong(f[2]);
            } catch (NumberFormatException e) {
                return;
            }
            Tutorial mine = TutorialData.get(srv).get(stage, f[1]);
            if (mine != null && mine.revision() >= rev) {
                return;
            }
            Storage s = Storage.active();
            if (s != null) {
                s.submit(() -> {
                    Tutorial shared = s.backend().loadTutorial(stage.key, f[1]);
                    if (shared != null) {
                        srv.execute(() -> apply(srv, stage, shared));
                    }
                });
            }
        });
    }

    /**
     * At server start, after the storage: takes every newer copy from the network's table, and writes back each of this
     * server's copies the table lacks or holds older (telling the other servers when it held an older one). This
     * server's copies are read here, on the server thread.
     */
    public static void onServerStarted(MinecraftServer server) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        Map<TutorialStage, List<Tutorial>> mine = new HashMap<>();
        for (TutorialStage stage : TutorialStage.values()) {
            mine.put(stage, TutorialData.get(server).all(stage));
        }
        s.submit(() -> {
            Map<String, Tutorial> shared = new HashMap<>();
            for (StorageBackend.StoredTutorial row : s.backend().loadTutorials()) {
                shared.put(row.stage() + "|" + row.tutorial().id(), row.tutorial());
            }
            List<Runnable> take = new ArrayList<>();
            for (TutorialStage stage : TutorialStage.values()) {
                for (Tutorial t : mine.get(stage)) {
                    Tutorial row = shared.remove(stage.key + "|" + t.id());
                    if (row == null || t.revision() > row.revision()) {
                        s.backend().saveTutorial(stage.key, t);
                        if (row != null) {
                            s.publish(KIND, notice(stage, t));
                        }
                    } else if (row.revision() > t.revision()) {
                        take.add(() -> apply(server, stage, row));
                    }
                }
            }
            shared.forEach((key, row) -> {
                TutorialStage stage = TutorialStage.of(key.substring(0, key.indexOf('|')));
                if (stage != null) {
                    take.add(() -> apply(server, stage, row));
                }
            });
            if (!take.isEmpty()) {
                server.execute(() -> take.forEach(Runnable::run));
            }
        });
        BSPServerUtilities.LOGGER.debug("Tutorials: reading the network's table");
    }

    // ------------------------------------------------------------------ tests

    public static void useClockForTests(LongSupplier c) {
        clock = c;
        lastRetry = 0;
        synchronized (UNWRITTEN) {
            UNWRITTEN.clear();
        }
    }

    public static void useRealClock() {
        useClockForTests(System::currentTimeMillis);
    }

    /** Forgets every tutorial in this world (tests), without a revision or a notice. */
    public static void clearForTests(MinecraftServer server) {
        TutorialData.get(server).clear();
    }

    /** Puts one copy in this world as it is (tests), without a revision or a notice. */
    public static void putForTests(MinecraftServer server, TutorialStage stage, Tutorial t) {
        TutorialData.get(server).put(stage, t);
    }
}
