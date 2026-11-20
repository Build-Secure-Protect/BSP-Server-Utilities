package com.mrgregles.bsp_server_utilities.support;

import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Whether a login is a join to the network or a move between its servers (owner decision D601). A login is a move when,
 * at the login, another server still listed the player (the client check's network list: on Velocity the new server's
 * login comes before the old one lets go), or when the player left any server of the network, this one included, from
 * {@value #MOVE_MS} ms before the login up to when it is asked (the old server's leaving usually arrives a moment after
 * the login). Every server tells the others of a player leaving with the notice {@value #KIND_LEFT}, so this works
 * with the client check switched off too; the notice also says whether the player left before their chat line was sent
 * ("owed"), so a player who joins and moves on at once, or moves while still at the rules, gets it on the next server
 * (D599, audit of SU-90). An owed line stays owed until a server sends it, which tells the others ({@value #KIND_SENT}),
 * so a later leaving never cancels it. A leaving read late from the table is dated when it happened, not when it was
 * read. A stopping server tells the others of everyone it still has before its storage closes (audit of 0.15.0). Without
 * shared storage only this server's own leavings count. Kept in memory: a server just started knows of no leavings
 * before it.
 */
public final class NetworkJoins {
    public static final String KIND_LEFT = "support_left", KIND_SENT = "support_sent";
    public static final long MOVE_MS = 60_000L;
    /** How long an owed line is kept: a player may read the rules for a long time on the next server. */
    private static final long OWED_KEEP_MS = 30 * 60_000L;
    private static final Map<UUID, Long> LEFT = new ConcurrentHashMap<>(), OWED = new ConcurrentHashMap<>();
    private static LongSupplier clock = System::currentTimeMillis;
    private static Predicate<UUID> elsewhere = ClientCheckService::listedElsewhere;

    private NetworkJoins() {
    }

    /** What is known at a login, to decide later. */
    public record Arrival(long atMs, boolean listedElsewhere) {
    }

    public static long now() {
        return clock.getAsLong();
    }

    /** At login: the time, and whether another server still lists the player. */
    public static Arrival arrived(ServerPlayer player) {
        return new Arrival(clock.getAsLong(), elsewhere.test(player.getUUID()));
    }

    /** Whether this arrival was a move between servers (or a quick reconnect) rather than a join to the network. */
    public static boolean isMove(UUID player, Arrival a) {
        if (a.listedElsewhere()) {
            return true;
        }
        Long left = LEFT.get(player);
        return left != null && left >= a.atMs() - MOVE_MS;
    }

    /** Whether the player left a server, around this arrival, before their chat line was sent there. */
    public static boolean owed(UUID player, Arrival a) {
        Long at = OWED.get(player);
        return at != null && at >= a.atMs() - MOVE_MS;
    }

    /** A player left this server ({@code owed}: before their chat line was sent): remembered here and told to the other servers. */
    public static void onLogout(ServerPlayer player, boolean owed) {
        left(player.getUUID(), owed, clock.getAsLong());
        Storage s = Storage.active();
        if (s != null) {
            s.publish(KIND_LEFT, player.getUUID() + (owed ? "\n1" : ""));
        }
    }

    /** A player left a server of the network (this one or another) at {@code atMs}; an owed line is kept until it is sent. */
    public static void left(UUID player, boolean owed, long atMs) {
        LEFT.merge(player, atMs, Math::max);
        if (owed) {
            OWED.merge(player, atMs, Math::max);
        }
        prune();
    }

    /** This server sent the player's line: nothing is owed any more, here or (told) on the other servers. */
    public static void sent(UUID player) {
        if (OWED.remove(player) != null) {
            Storage s = Storage.active();
            if (s != null) {
                s.publish(KIND_SENT, player.toString());
            }
        }
    }

    /** Leavings older than the window are of no use. */
    private static void prune() {
        if (LEFT.size() > 256 || OWED.size() > 256) {
            long now = clock.getAsLong();
            LEFT.values().removeIf(t -> t < now - 2 * MOVE_MS);
            OWED.values().removeIf(t -> t < now - OWED_KEEP_MS);
        }
    }

    public static void registerNotices() {
        Storage.on(KIND_LEFT, (srv, n) -> {
            String[] f = n.payload().strip().split("\n", -1);
            try {
                // dated when it happened: a notice read late from the table is older than it looks
                long age = Math.max(0, System.currentTimeMillis() - n.createdAt());
                left(UUID.fromString(f[0].strip()), f.length > 1 && f[1].strip().equals("1"), clock.getAsLong() - age);
            } catch (IllegalArgumentException ignored) {
                // not a leaving notice
            }
        });
        Storage.on(KIND_SENT, (srv, n) -> {
            try {
                OWED.remove(UUID.fromString(n.payload().strip()));
            } catch (IllegalArgumentException ignored) {
                // not a notice of ours
            }
        });
    }

    // ------------------------------------------------------------------ tests

    /** A test clock and who another server lists (null: the client check's list). */
    public static void useForTests(LongSupplier testClock, Predicate<UUID> listedElsewhere) {
        clock = testClock;
        elsewhere = listedElsewhere != null ? listedElsewhere : ClientCheckService::listedElsewhere;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        elsewhere = ClientCheckService::listedElsewhere;
    }

    /** Forgets a player's leavings (tests). */
    public static void forget(UUID player) {
        LEFT.remove(player);
        OWED.remove(player);
    }
}
