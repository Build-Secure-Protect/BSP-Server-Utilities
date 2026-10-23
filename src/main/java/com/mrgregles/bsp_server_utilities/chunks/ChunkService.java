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
 *   <li>When the owner joins again their tickets come back a few a tick, each only if its machine is still there (D98);
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

    private static boolean online(MinecraftServer server, UUID player) {
        if (playersOverride != null) {
            return playersOverride.get().stream().anyMatch(p -> p.getUUID().equals(player));
        }
        return server.getPlayerList().getPlayer(player) != null;
    }

    /** How long the player has been away from this server (since this server started, when they never left it). */
    public static long awayMs(MinecraftServer server, UUID player) {
        if (online(server, player)) {
            return 0;
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

    public static void onLogout(ServerPlayer player) {
        ChunkData.get(player.server).setLastLeft(player.getUUID(), clock.getAsLong());
    }

    // ------------------------------------------------------------------ the admin's keys

    /** RESTORE NOW: the player's chunks come back and stay until they next join and leave (D97). */
    public static int restoreNow(MinecraftServer server, UUID player) {
        if (!online(server, player)) {
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
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                ChunkSettings shared = s.backend().loadChunkSettings();
                if (shared == null) {
                    s.backend().saveChunkSettings(settings(server));
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    public static void tick(MinecraftServer server) {
        drainRestores(server, RESTORES_PER_TICK);
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
