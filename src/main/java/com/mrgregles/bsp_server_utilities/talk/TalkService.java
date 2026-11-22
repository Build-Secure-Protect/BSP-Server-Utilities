package com.mrgregles.bsp_server_utilities.talk;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebChat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Private staff messages on this server (SU-105; owner decisions D630 to D652): who may write, the limits, the Action Log,
 * and where it is kept. Staff write to a player and end the conversation; the player answers. With shared storage the
 * network's tables are the record and the work runs on the storage's worker, its result coming back on the server thread;
 * without it, this world's save (D645) answers at once. Every result is a future completed on the server thread.
 *
 * <ul>
 * <li>Who writes to players (D630): in game, staff ({@link Staff#isStaff}); from the panel, every rank (the panel
 * checked); the console. Never to another staff member (D641).</li>
 * <li>Lines of 1 to 256 characters, plain (colour codes and control characters taken out, as chat, D642); staff 30 lines a
 * minute each, a player 10.</li>
 * <li>The Action Log gets one entry when a conversation starts and one when it ends (D644), never the lines.</li>
 * </ul>
 * Listeners ({@link #onChange}) hear every line written here, on the server thread, for delivery (SU-106).
 */
public final class TalkService {
    public static final int STAFF_PER_MINUTE = 30, PLAYER_PER_MINUTE = 10;
    static final long PRUNE_EVERY_MS = 6 * 3_600_000L;

    /** What came of a request. */
    public enum Outcome { SENT, ENDED, NOT_STAFF, TO_STAFF, EMPTY, TOO_LONG, TOO_FAST, NO_TALK, FAILED }

    /** The outcome, what was written (null when refused), and the words for whoever asked (the panel shows them as they are). */
    public record Result(Outcome outcome, @Nullable TalkBook.Written written, String message) {
        public boolean ok() {
            return outcome == Outcome.SENT || outcome == Outcome.ENDED;
        }
    }

    /** Who asked: a staff member in game ({@code player} set), the panel ({@code panel}), or the console. */
    public record By(@Nullable ServerPlayer player, String name, boolean panel) {
        public static By of(ServerPlayer p) {
            return new By(p, p.getGameProfile().getName(), false);
        }

        public static By panel(String name) {
            return new By(null, name, true);
        }

        public static By console(String name) {
            return new By(null, name, false);
        }
    }

    private static LongSupplier clock = System::currentTimeMillis;
    private static Predicate<ServerPlayer> staffInGame = Staff::isStaff;
    private static BiPredicate<MinecraftServer, UUID> staffTarget = TalkService::isStaffMember;
    private static final Map<String, ArrayDeque<Long>> RECENT = new ConcurrentHashMap<>();
    private static final List<java.util.function.BiConsumer<MinecraftServer, TalkBook.Written>> LISTENERS = new CopyOnWriteArrayList<>();
    private static long lastPrune;

    private TalkService() {
    }

    /** Hears every line written on this server (a staff line, an answer, an end), on the server thread. */
    public static void onChange(java.util.function.BiConsumer<MinecraftServer, TalkBook.Written> l) {
        LISTENERS.add(l);
    }

    private static void changed(MinecraftServer server, TalkBook.Written w) {
        for (java.util.function.BiConsumer<MinecraftServer, TalkBook.Written> l : LISTENERS) {
            try {
                l.accept(server, w);
            } catch (RuntimeException e) {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities staff messages: {}", e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ who and how much

    /** Whether a player is staff: online, by their role here; offline, by the network's staff list. */
    private static boolean isStaffMember(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            return Staff.isStaff(p);
        }
        if (StaffDirectory.roster(server).stream().anyMatch(m -> m.id().equals(id))) {
            return true;
        }
        // an operator offline: on this server's op list at level 2 or more, when operators count as staff (D641, audit)
        var op = server.getPlayerList().getOps().get(new com.mojang.authlib.GameProfile(id, ""));
        return op != null && op.getLevel() >= com.mrgregles.bsp_server_utilities.access.Perms.OPERATOR_LEVEL && Staff.operatorsCount(server);
    }

    /** A line as it may be kept: as chat cleans it (D642); empty when there is nothing left. */
    public static String clean(String raw) {
        return WebChat.clean(raw);
    }

    /** False (and nothing counted) when {@code key} wrote {@code max} lines in the last minute. */
    private static boolean allowed(String key, int max) {
        long now = clock.getAsLong();
        ArrayDeque<Long> mine = RECENT.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (mine) {
            while (!mine.isEmpty() && mine.peekFirst() <= now - 60_000L) {
                mine.pollFirst();
            }
            if (mine.size() >= max) {
                return false;
            }
            mine.addLast(now);
            return true;
        }
    }

    private static UnaryOperator<String> signer() {
        byte[] key = WebActions.currentKey();
        return key == null ? json -> "" : json -> WebActions.signText(key, json);
    }

    private static CompletableFuture<Result> refused(Outcome o, String message) {
        return CompletableFuture.completedFuture(new Result(o, null, message));
    }

    /** Runs a step on the network's tables (on the storage's worker) or on this world's save (at once); the result on the server thread. */
    private static <T> CompletableFuture<T> run(MinecraftServer server, StoreStep<T> step) {
        Storage s = Storage.active();
        CompletableFuture<T> out = new CompletableFuture<>();
        if (s == null) {
            try {
                out.complete(step.run(TalkData.get(server).table()));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
            return out;
        }
        s.submit(() -> {
            T v = step.run(s.backend());
            server.execute(() -> out.complete(v));
        }, e -> server.execute(() -> out.completeExceptionally(e))); // also when the database has not answered since the start (audit)
        return out;
    }

    private interface StoreStep<T> {
        T run(TalkStore store) throws Exception;
    }

    private static CompletableFuture<Result> failed(CompletableFuture<Result> f) {
        return f.exceptionally(e -> new Result(Outcome.FAILED, null, "The messages could not be saved: " + (e.getCause() != null ? e.getCause() : e)));
    }

    // ------------------------------------------------------------------ staff

    /**
     * A staff member writes to a player (D630, D631): opens the conversation when none is open. {@code playerName} is the
     * player's name as staff typed or the panel knew it.
     */
    public static CompletableFuture<Result> staffSend(MinecraftServer server, By by, UUID player, String playerName, String raw) {
        if (by.player() != null && !staffInGame.test(by.player())) {
            return refused(Outcome.NOT_STAFF, "Only staff send private messages to players.");
        }
        if (staffTarget.test(server, player)) {
            return refused(Outcome.TO_STAFF, playerName + " is staff: use the staff room.");
        }
        if (raw.strip().length() > TalkBook.MAX_TEXT) {
            return refused(Outcome.TOO_LONG, "At most " + TalkBook.MAX_TEXT + " characters.");
        }
        String text = clean(raw);
        if (text.isEmpty()) {
            return refused(Outcome.EMPTY, "There is nothing to send.");
        }
        if (!allowed("staff:" + by.name().toLowerCase(java.util.Locale.ROOT), STAFF_PER_MINUTE)) {
            return refused(Outcome.TOO_FAST, "That is " + STAFF_PER_MINUTE + " messages in a minute: wait a moment.");
        }
        long at = clock.getAsLong();
        String here = Storage.currentServerId();
        UnaryOperator<String> sign = signer();
        return failed(run(server, st -> TalkBook.staffWrites(st, player, playerName, by.name(), text, at, here, sign)).thenApply(w -> {
            if (w.opened()) {
                log(server, by, "started a private conversation with " + w.talk().name());
            }
            changed(server, w);
            return new Result(Outcome.SENT, w, "Sent to " + w.talk().name() + ".");
        }));
    }

    /** Ends the player's conversation (D632, D633): any staff member, whoever wrote last. */
    public static CompletableFuture<Result> end(MinecraftServer server, By by, UUID player, String playerName) {
        if (by.player() != null && !staffInGame.test(by.player())) {
            return refused(Outcome.NOT_STAFF, "Only staff end conversations.");
        }
        long at = clock.getAsLong();
        String here = Storage.currentServerId();
        UnaryOperator<String> sign = signer();
        return failed(run(server, st -> TalkBook.end(st, player, by.name(), at, here, sign)).thenApply(w -> {
            if (w == null) {
                return new Result(Outcome.NO_TALK, null, playerName + " has no open conversation with staff.");
            }
            log(server, by, "ended the private conversation with " + w.talk().name());
            changed(server, w);
            return new Result(Outcome.ENDED, w, "Ended the conversation with " + w.talk().name() + ".");
        }));
    }

    /** A player's conversations of the last 90 days with their lines (the staff window, the panel). */
    public static CompletableFuture<TalkBook.History> history(MinecraftServer server, UUID player) {
        return run(server, st -> TalkBook.history(st, player));
    }

    /** Every open conversation (the staff's {@code /su talks}). */
    public static CompletableFuture<List<TalkStore.Talk>> openTalks(MinecraftServer server) {
        return run(server, TalkStore::openTalks);
    }

    /** Whether a player counts as staff here (in-game staff checks; tests give their own). */
    public static boolean isStaff(ServerPlayer p) {
        return staffInGame.test(p);
    }

    /** Whether a player, online or not, is staff (never written to, D641). */
    public static boolean isStaffTarget(MinecraftServer server, UUID id) {
        return staffTarget.test(server, id);
    }

    /** A player found by name: their id and name as last known. */
    public record Found(UUID id, String name) {
    }

    /**
     * A player by name, online or not: on this server, elsewhere on the network (the client check's list), Forge's
     * name cache, then the network's list of everyone who ever joined. Empty when nobody of that name has played.
     */
    public static CompletableFuture<java.util.Optional<Found>> find(MinecraftServer server, String name) {
        for (ServerPlayer p : TalkDelivery.here(server)) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                return CompletableFuture.completedFuture(java.util.Optional.of(new Found(p.getUUID(), p.getGameProfile().getName())));
            }
        }
        var row = com.mrgregles.bsp_server_utilities.check.ClientCheckService.findByName(name);
        if (row != null) {
            return CompletableFuture.completedFuture(java.util.Optional.of(new Found(row.held().id(), row.held().name())));
        }
        // Forge's name cache of everyone who joined this server (never the server's profile cache: it asks Mojang for unknown names)
        for (Map.Entry<UUID, String> e : net.minecraftforge.common.UsernameCache.getMap().entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return CompletableFuture.completedFuture(java.util.Optional.of(new Found(e.getKey(), e.getValue())));
            }
        }
        Storage s = Storage.active();
        if (s == null) {
            return CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        CompletableFuture<java.util.Optional<Found>> out = new CompletableFuture<>();
        s.submit(() -> {
            com.mrgregles.bsp_server_utilities.players.Joined j = s.backend().playerByName(name);
            server.execute(() -> out.complete(j == null ? java.util.Optional.empty() : java.util.Optional.of(new Found(j.id(), j.name()))));
        }, e -> server.execute(() -> out.completeExceptionally(e))); // the database not answering: said, never "nobody of that name" (audit)
        return out;
    }

    // ------------------------------------------------------------------ the player

    /** The player answers (D633): only into an open conversation. */
    public static CompletableFuture<Result> playerSend(ServerPlayer p, String raw) {
        if (raw.strip().length() > TalkBook.MAX_TEXT) {
            return refused(Outcome.TOO_LONG, "At most " + TalkBook.MAX_TEXT + " characters.");
        }
        String text = clean(raw);
        if (text.isEmpty()) {
            return refused(Outcome.EMPTY, "There is nothing to send.");
        }
        if (!allowed("player:" + p.getUUID(), PLAYER_PER_MINUTE)) {
            return refused(Outcome.TOO_FAST, "That is " + PLAYER_PER_MINUTE + " messages in a minute: wait a moment.");
        }
        long at = clock.getAsLong();
        String here = Storage.currentServerId(), name = p.getGameProfile().getName();
        UUID id = p.getUUID();
        UnaryOperator<String> sign = signer();
        return failed(run(p.server, st -> TalkBook.playerWrites(st, id, name, text, at, here, sign)).thenApply(w -> {
            if (w == null) {
                return new Result(Outcome.NO_TALK, null, "You have no open conversation with staff.");
            }
            changed(p.server, w);
            return new Result(Outcome.SENT, w, "Sent.");
        }));
    }

    /** Records that the player was told of the staff lines up to {@code at} (the popup or the login line, D635, D636). */
    public static CompletableFuture<Void> told(MinecraftServer server, long talk, long at) {
        return run(server, st -> {
            st.talkTold(talk, at);
            return null;
        });
    }

    /** What the player sees: their open conversation only (D633). */
    public static CompletableFuture<TalkBook.History> playerView(MinecraftServer server, UUID player) {
        return run(server, st -> TalkBook.playerView(st, player));
    }

    // ------------------------------------------------------------------ the Action Log (D644)

    private static void log(MinecraftServer server, By by, String text) {
        LogEntry e;
        if (by.player() != null) {
            e = ActionLog.entryForPlayer(by.player(), LogEntry.CMD, text);
        } else {
            e = ActionLog.entryForServer(by.panel() ? by.name() + " (panel)" : by.name(), LogEntry.CMD, text);
        }
        ActionLog.record(server, e);
    }

    // ------------------------------------------------------------------ the 90-day prune (D643)

    /** Every server tick: the prune when it is due (the first tick after the start, then every 6 hours). */
    public static void tick(MinecraftServer server) {
        long now = clock.getAsLong();
        if (now - lastPrune < PRUNE_EVERY_MS) {
            return;
        }
        lastPrune = now;
        prune(server);
    }

    /** Runs the prune now; conversations that ended themselves go to the listeners (the key goes, D643). */
    public static CompletableFuture<TalkBook.Pruned> prune(MinecraftServer server) {
        long now = clock.getAsLong();
        String here = Storage.currentServerId();
        UnaryOperator<String> sign = signer();
        CompletableFuture<TalkBook.Pruned> f = run(server, st -> TalkBook.prune(st, now, here, sign));
        f.thenAccept(p -> {
            p.ended().forEach(w -> {
                ActionLog.record(server, ActionLog.entryForServer("Server", LogEntry.CMD, "ended the private conversation with " + w.talk().name() + " (no line for 90 days)"));
                changed(server, w);
            });
            if (p.lines() > 0 || !p.ended().isEmpty() || p.dropped() > 0) {
                BSPServerUtilities.LOGGER.info("BSP-Server Utilities staff messages: {} lines over 90 days dropped, {} conversations ended with no line left, {} dropped",
                        p.lines(), p.ended().size(), p.dropped());
            }
        });
        return f;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the clock, who counts as staff in game and as a staff target (LuckPerms is not loaded there); null puts each back. */
    public static void useForTests(@Nullable LongSupplier testClock, @Nullable Predicate<ServerPlayer> staff, @Nullable BiPredicate<MinecraftServer, UUID> target) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        staffInGame = staff != null ? staff : Staff::isStaff;
        staffTarget = target != null ? target : TalkService::isStaffMember;
        RECENT.clear();
        lastPrune = 0;
    }

    public static void useReal() {
        useForTests(null, null, null);
    }

    /** Drops the listeners added by a test (the ones registered at start stay, being added first). */
    public static void removeListenerForTests(java.util.function.BiConsumer<MinecraftServer, TalkBook.Written> l) {
        LISTENERS.remove(l);
    }
}
