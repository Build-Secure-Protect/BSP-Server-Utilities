package com.mrgregles.bsp_server_utilities.chunks;

import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Chunk unloading (the owner's brief of 2026-10-21; decisions D86 to D99), on the server thread.
 *
 * <ul>
 *   <li>Every minute each Forge ticket (BSP-Core's and exempt mods' aside) is matched to its owner
 *   ({@link ChunkOwners}). A ticket whose owner has been away from this server for the set minutes is suspended: taken
 *   from Forge and kept in {@link ChunkData}, so its chunks unload; suspended tickets survive restarts. A ticket a mod
 *   adds back while its owner is still away is suspended again at the next check.</li>
 *   <li>A player online but AFK counts as away since they went AFK (D210).</li>
 *   <li>When the owner joins again, or an AFK owner is active again, their tickets come back a few a tick, each only if its machine is still there (D98);
 *   if any were suspended they are told so (D91).</li>
 *   <li>Players marked always loaded are never suspended, and marking one gives theirs back at once (D99). An admin's
 *   RESTORE NOW holds until the player next joins and leaves (D97); UNLOAD NOW suspends at once (D94). Tickets with no
 *   owner found stay loaded and are listed; an admin can unload them until restored (D96).</li>
 *   <li>The settings are one set for the network (table and a {@value #KIND_SETTINGS} notice, the newest revision wins);
 *   what is loaded is this server's.</li>
 * </ul>
 */
public final class ChunkService {
    public static final String KIND_SETTINGS = "chunk_settings";
    public static final int SWEEP_TICKS = 1200, RESTORES_PER_TICK = 4;

    /** A ticket alive now with what is known of its owner, for the CHUNKS tab. */
    public record Live(ChunkTickets.Ticket ticket, @Nullable UUID player, @Nullable ChunkOwners.How how) {
    }

    private record Pending(ChunkTickets.Ticket ticket, @Nullable UUID player) {
    }

    private static final Deque<Pending> RESTORES = new ArrayDeque<>();
    private static final Map<String, ChunkOwners.Found> OWNERS = new HashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static long ticks, startedMs;
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static Supplier<Collection<ServerPlayer>> playersOverride;

    private ChunkService() {
    }

    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ settings

    public static ChunkSettings settings(MinecraftServer server) {
        return ChunkData.get(server).settings();
    }

    /** Replaces the settings for the whole network; a player newly always loaded gets their chunks back at once (D99). */
    public static ChunkSettings setSettings(MinecraftServer server, ChunkSettings s) {
        com.mrgregles.bsp_server_utilities.setup.SetupService.touched(server, com.mrgregles.bsp_server_utilities.setup.Feature.CHUNKS); // changed in its tab: set up (D152)
        ChunkData data = ChunkData.get(server);
        ChunkSettings before = data.settings();
        ChunkSettings next = s.withRevision(Math.max(System.currentTimeMillis(), before.revision() + 1)).sanitised();
        data.setSettings(next);
        Storage st = Storage.active();
        if (st != null) {
            st.submit(() -> st.backend().saveChunkSettings(next));
            st.publish(KIND_SETTINGS, next.encode());
        }
        afterSettings(server, before, next);
        return next;
    }

    public static void apply(MinecraftServer server, ChunkSettings shared) {
        ChunkData data = ChunkData.get(server);
        ChunkSettings before = data.settings();
        if (shared.revision() > before.revision()) {
            data.setSettings(shared);
            afterSettings(server, before, shared);
        }
    }

    private static void afterSettings(MinecraftServer server, ChunkSettings before, ChunkSettings next) {
        for (ChunkSettings.Player p : next.alwaysLoaded()) {
            if (!before.alwaysLoaded(p.id())) {
                queueRestore(server, p.id());
            }
        }
        for (ChunkData.Suspended s : List.copyOf(ChunkData.get(server).suspended().values())) {
            if (next.exempt(s.ticket().modId())) {
                RESTORES.add(new Pending(s.ticket(), s.player()));
            }
        }
        changed();
    }

    // ------------------------------------------------------------------ who is where

    public static boolean online(MinecraftServer server, UUID player) {
        if (playersOverride != null) {
            return playersOverride.get().stream().anyMatch(p -> p.getUUID().equals(player));
        }
        return server.getPlayerList().getPlayer(player) != null;
    }

    /**
     * How long the player has been away from this server (since this server started, when they never left it); online,
     * how long they have been AFK, 0 when they are not (D210).
     */
    public static long awayMs(MinecraftServer server, UUID player) {
        if (online(server, player)) {
            return com.mrgregles.bsp_server_utilities.afk.AfkService.afkForMs(player);
        }
        Long left = ChunkData.get(server).lastLeft(player);
        return Math.max(0, clock.getAsLong() - (left == null ? startedMs : left));
    }

    // ------------------------------------------------------------------ the check

    /** Every Forge ticket now, with its owner (looked up again each check). */
    public static List<Live> live(MinecraftServer server) {
        ChunkData data = ChunkData.get(server);
        List<Live> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkTickets.Ticket t : ChunkTickets.live(level)) {
                ChunkOwners.Found f = OWNERS.get(t.key());
                if (f == null) {
                    f = ChunkOwners.find(level, t, data);
                    if (f != null) {
                        OWNERS.put(t.key(), f);
                    }
                }
                out.add(new Live(t, f == null ? null : f.player(), f == null ? null : f.how()));
            }
        }
        return out;
    }

    /** The minute's check: suspends what should be; returns how many tickets it took. */
    public static int sweep(MinecraftServer server) {
        ChunkData data = ChunkData.get(server);
        ChunkSettings s = data.settings();
        if (!s.enabled()) {
            return 0;
        }
        OWNERS.clear();
        Set<String> unloadedNoOwner = new HashSet<>();
        for (ChunkData.Suspended x : data.suspended().values()) {
            if (x.player() == null) {
                unloadedNoOwner.add(x.ticket().key());
            }
        }
        int taken = 0;
        for (Live l : live(server)) {
            ChunkTickets.Ticket t = l.ticket();
            if (s.exempt(t.modId())) {
                continue;
            }
            boolean suspend;
            if (l.player() == null) {
                suspend = unloadedNoOwner.contains(t.key()); // an admin unloaded it and a mod put it back
            } else {
                suspend = !s.alwaysLoaded(l.player()) && !data.held().contains(l.player()) && awayMs(server, l.player()) >= s.awayMs();
            }
            if (suspend && suspend(server, t, l.player())) {
                taken++;
            }
        }
        if (taken > 0) {
            changed();
        }
        return taken;
    }

    private static boolean suspend(MinecraftServer server, ChunkTickets.Ticket t, @Nullable UUID player) {
        ServerLevel level = t.level(server);
        if (level == null || !ChunkTickets.remove(level, t)) {
            return false;
        }
        ChunkData data = ChunkData.get(server);
        ChunkData.Suspended before = data.suspended().get(t.key());
        String name = player == null ? "" : server.getProfileCache() == null ? "" : server.getProfileCache().get(player).map(p -> p.getName()).orElse("");
        data.putSuspended(new ChunkData.Suspended(t, player, name, before == null ? clock.getAsLong() : before.sinceMs()));
        return true;
    }

    // ------------------------------------------------------------------ giving back

    private static int queueRestore(MinecraftServer server, UUID player) {
        int n = 0;
        for (ChunkData.Suspended s : ChunkData.get(server).suspended().values()) {
            if (player.equals(s.player())) {
                RESTORES.add(new Pending(s.ticket(), player));
                n++;
            }
        }
        return n;
    }

    /** A few restores a tick; returns how many were done (tests run the whole queue). */
    public static int drainRestores(MinecraftServer server, int max) {
        int done = 0;
        while (done < max && !RESTORES.isEmpty()) {
            restore(server, RESTORES.poll().ticket());
            done++;
        }
        if (done > 0) {
            changed();
        }
        return done;
    }

    /** Gives one ticket back if its machine is still there (D98: otherwise it is forgotten quietly). */
    private static void restore(MinecraftServer server, ChunkTickets.Ticket t) {
        ChunkData data = ChunkData.get(server);
        if (data.takeSuspended(t.key()) == null) {
            return;
        }
        ServerLevel level = t.level(server);
        if (level == null) {
            return;
        }
        BlockPos pos = t.blockOwner();
        if (pos != null && level.getBlockEntity(pos) == null) {
            data.putPlacer(ChunkOwners.posKey(level, pos), null);
            return;
        }
        ChunkTickets.add(level, t);
    }

    // ------------------------------------------------------------------ players coming and going

    public static void onLogin(ServerPlayer player) {
        MinecraftServer server = player.server;
        ChunkData.get(server).hold(player.getUUID(), false);
        if (queueRestore(server, player.getUUID()) > 0) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.chunks.back").withStyle(ChatFormatting.GOLD));
        }
    }

    /** An AFK player is active again: their tickets come back as on joining (D210). */
    public static void onActive(ServerPlayer player) {
        if (queueRestore(player.server, player.getUUID()) > 0) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.chunks.back").withStyle(ChatFormatting.GOLD));
        }
    }

    public static void onLogout(ServerPlayer player) {
        ChunkData.get(player.server).setLastLeft(player.getUUID(), clock.getAsLong());
    }

    // ------------------------------------------------------------------ the admin's keys

    /** RESTORE NOW: the player's chunks come back and stay until they next join and leave (D97), an AFK player's too (D210). */
    public static int restoreNow(MinecraftServer server, UUID player) {
        if (!online(server, player) || com.mrgregles.bsp_server_utilities.afk.AfkService.isAfk(player)) {
            ChunkData.get(server).hold(player, true);
        }
        int n = queueRestore(server, player);
        changed();
        return n;
    }

    /** UNLOAD NOW: the player's chunks unload at once, whatever the time (D94). */
    public static int unloadNow(MinecraftServer server, UUID player) {
        ChunkData.get(server).hold(player, false);
        int n = 0;
        for (Live l : live(server)) {
            if (player.equals(l.player()) && !settings(server).exempt(l.ticket().modId()) && suspend(server, l.ticket(), player)) {
                n++;
            }
        }
        changed();
        return n;
    }

    /** UNLOAD on a machine with no owner found (D96): kept unloaded until an admin restores it. */
    public static boolean unloadTicket(MinecraftServer server, String key) {
        ChunkTickets.Ticket t = ChunkTickets.Ticket.decode(key);
        boolean ok = t != null && !settings(server).exempt(t.modId()) && suspend(server, t, null);
        changed();
        return ok;
    }

    public static boolean restoreTicket(MinecraftServer server, String key) {
        ChunkData.Suspended s = ChunkData.get(server).suspended().get(key);
        if (s != null) {
            RESTORES.add(new Pending(s.ticket(), s.player()));
        }
        return s != null;
    }

    // ------------------------------------------------------------------ a BSP-Core player reset

    /** Ticks a reset waits before its owners are judged again, so BSP-Region Guard's own reset (PR16) has abandoned the claims first. */
    public static final int REJUDGE_DELAY_TICKS = 20;
    private static final Map<UUID, Long> REJUDGES = new HashMap<>();
    private static long claimsDue = -1;

    /**
     * BSP-Core reset the player (PR17), on every server it reaches. Their suspended tickets stay suspended and when they
     * last left is kept, so nothing loads because of the reset; an admin's RESTORE NOW hold is forgotten. A second later
     * each of their suspended tickets has its owner found again ({@link #rejudge}). Who placed each machine is kept: the
     * blocks stay in the world, and without it their machines would have no owner and stay loaded always (D96). The
     * always-loaded list is the staff's setting and stays.
     */
    public static void forgetPlayer(MinecraftServer server, UUID player) {
        ChunkData data = ChunkData.get(server);
        data.hold(player, false);
        queueRejudge(data, player);
        changed();
    }

    /**
     * BSP-Core's season reset (SR15), on every server it reaches: {@link #forgetPlayer} for every player with an entry here
     * (an unloaded ticket or an admin's hold), as the player reset does for one. Unloaded tickets stay unloaded and
     * when each player last left is kept; a second later each one's owner is found again (BSP-Region Guard gives every
     * claim up for the same reset). The loaded tickets charged to a player only through a claim (those online at the
     * reset, or not away long enough) are checked then too: one with no owner left is unloaded with no owner, as an
     * admin's unload is, so it does not stay loaded for good (D96). Machines with no owner an admin unloaded, the placers
     * and the always-loaded list stay. Returns how many players were forgotten.
     */
    public static int forgetEveryone(MinecraftServer server) {
        ChunkData data = ChunkData.get(server);
        ChunkSettings settings = data.settings();
        if (settings.enabled()) {
            for (Live l : live(server)) { // owners as the last check found them, before BSP-Region Guard's reset if it ran first
                if (l.how() == ChunkOwners.How.CLAIM && !settings.exempt(l.ticket().modId())) {
                    data.claimCheck(l.ticket().key(), true);
                }
            }
        }
        if (!data.claimChecks().isEmpty()) {
            claimsDue = ticks + REJUDGE_DELAY_TICKS;
        }
        Set<UUID> players = new HashSet<>(data.held());
        for (ChunkData.Suspended s : data.suspended().values()) {
            if (s.player() != null) {
                players.add(s.player());
            }
        }
        for (UUID id : players) {
            data.hold(id, false);
            queueRejudge(data, id);
        }
        if (!players.isEmpty()) {
            changed();
        }
        return players.size();
    }

    /**
     * Finds the owner of each of the player's suspended tickets again. A ticket that was theirs only through a claim
     * BSP-Region Guard abandoned for the reset now has no owner: it is kept unloaded with no owner, listed, as if an admin
     * had unloaded it (D96), not given back, where it would stay loaded for good. A ticket now someone else's is kept
     * under that player (it comes back when they join). Returns how many changed owner.
     */
    public static int rejudge(MinecraftServer server, UUID player) {
        ChunkData data = ChunkData.get(server);
        int changedOwner = 0;
        for (ChunkData.Suspended s : List.copyOf(data.suspended().values())) {
            if (!player.equals(s.player())) {
                continue;
            }
            ServerLevel level = s.ticket().level(server);
            if (level == null) {
                continue;
            }
            ChunkOwners.Found f = ChunkOwners.find(level, s.ticket(), data);
            UUID now = f == null ? null : f.player();
            if (player.equals(now)) {
                continue;
            }
            String name = now == null || server.getProfileCache() == null ? "" : server.getProfileCache().get(now).map(p -> p.getName()).orElse("");
            data.putSuspended(new ChunkData.Suspended(s.ticket(), now, name, s.sinceMs()));
            changedOwner++;
        }
        if (changedOwner > 0) {
            com.mrgregles.bsp_server_utilities.BSPServerUtilities.LOGGER.info("Chunk unloading: {} unloaded ticket(s) of reset player {} have a new owner or none (kept unloaded)", changedOwner, player);
            changed();
        }
        return changedOwner;
    }

    /**
     * Queues the player's owner check, kept in the world too: a reset heard as the server stops (BSP-Core answers late
     * there, with no tick left) or just before it does is checked a second after the next start instead of never.
     */
    private static void queueRejudge(ChunkData data, UUID player) {
        data.rejudgeLater(player, true);
        REJUDGES.put(player, ticks + REJUDGE_DELAY_TICKS);
    }

    /** At start: the owner checks a reset left waiting when the server stopped, a second from now. */
    private static void requeueRejudges(MinecraftServer server) {
        REJUDGES.clear();
        ChunkData data = ChunkData.get(server);
        for (UUID id : data.rejudges()) {
            REJUDGES.put(id, ticks + REJUDGE_DELAY_TICKS);
        }
        claimsDue = data.claimChecks().isEmpty() ? -1 : ticks + REJUDGE_DELAY_TICKS;
    }

    /**
     * The season reset's loaded tickets that were a player's only through a claim: each still loaded and with no owner
     * now is unloaded with no owner, listed, as an admin's unload is (D96); one with an owner again is left to the
     * minute's check. Returns how many were unloaded.
     */
    public static int checkClaims(MinecraftServer server) {
        claimsDue = -1;
        ChunkData data = ChunkData.get(server);
        int taken = 0;
        for (String key : data.claimChecks()) {
            data.claimCheck(key, false);
            ChunkTickets.Ticket t = ChunkTickets.Ticket.decode(key);
            ServerLevel level = t == null ? null : t.level(server);
            if (level == null || ChunkTickets.live(level).stream().noneMatch(x -> x.key().equals(key))) {
                continue;
            }
            OWNERS.remove(key);
            if (ChunkOwners.find(level, t, data) == null && suspend(server, t, null)) {
                taken++;
            }
        }
        if (taken > 0) {
            com.mrgregles.bsp_server_utilities.BSPServerUtilities.LOGGER.info("Chunk unloading: {} loaded ticket(s) held only through a claim given up in the season reset have no owner now (unloaded)", taken);
            changed();
        }
        return taken;
    }

    /** Charges a ticket's owner as the last check found it (tests: BSP-Region Guard's claims are not there). */
    public static void ownerForTests(String ticketKey, @Nullable ChunkOwners.Found found) {
        if (found == null) {
            OWNERS.remove(ticketKey);
        } else {
            OWNERS.put(ticketKey, found);
        }
    }

    /** Whether the season reset's claim check is still waiting (tests). */
    public static boolean claimsPending() {
        return claimsDue >= 0;
    }

    /** What a restart does to the owner checks waiting (tests): the in-memory queue lost, the world's kept. */
    public static void restartRejudgesForTests(MinecraftServer server) {
        requeueRejudges(server);
    }

    private static void drainRejudges(MinecraftServer server) {
        if (claimsDue >= 0 && ticks >= claimsDue) {
            checkClaims(server);
        }
        if (REJUDGES.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(REJUDGES.keySet())) {
            Long due = REJUDGES.get(id);
            if (due != null && ticks >= due) {
                REJUDGES.remove(id);
                rejudge(server, id);
                ChunkData.get(server).rejudgeLater(id, false);
            }
        }
    }

    /** Whether a reset's owner check is still waiting (tests). */
    public static boolean rejudgePending(UUID player) {
        return REJUDGES.containsKey(player);
    }

    /** Runs a waiting reset owner check now (tests). */
    public static int rejudgeNowForTests(MinecraftServer server, UUID player) {
        REJUDGES.remove(player);
        int n = rejudge(server, player);
        ChunkData.get(server).rejudgeLater(player, false);
        return n;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Who placed each machine (D87), for owners the machine does not save itself. */
    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && e.getLevel() instanceof ServerLevel level && e.getPlacedBlock().hasBlockEntity()) {
                ChunkData.get(level.getServer()).putPlacer(ChunkOwners.posKey(level, e.getPos()), p.getUUID());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getLevel() instanceof ServerLevel level && e.getState().hasBlockEntity()) {
                ChunkData.get(level.getServer()).putPlacer(ChunkOwners.posKey(level, e.getPos()), null);
            }
        });
    }

    public static void registerNotices() {
        Storage.on(KIND_SETTINGS, (srv, n) -> {
            ChunkSettings s = ChunkSettings.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
    }

    public static void onServerStarted(MinecraftServer server) {
        startedMs = clock.getAsLong();
        RESTORES.clear();
        OWNERS.clear();
        ticks = 0;
        requeueRejudges(server); // a reset's owner checks the last run had no time for
        Storage s = Storage.active();
        if (s != null) {
            ChunkSettings ours = settings(server); // read here, on the server thread, not on the storage worker
            s.submit(() -> {
                ChunkSettings shared = s.backend().loadChunkSettings();
                if (shared == null) {
                    s.backend().saveChunkSettings(ours);
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    public static void tick(MinecraftServer server) {
        drainRestores(server, RESTORES_PER_TICK);
        drainRejudges(server);
        if (++ticks % SWEEP_TICKS == 0) {
            sweep(server);
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock, @Nullable Supplier<Collection<ServerPlayer>> players) {
        clock = testClock;
        playersOverride = players;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        playersOverride = null;
    }
}
