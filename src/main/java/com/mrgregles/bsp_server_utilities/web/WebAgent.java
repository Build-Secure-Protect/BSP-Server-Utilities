package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.ServerUtilsNetworkConfig;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * This server's part of the staff web panel (owner decisions D247, D251): while {@code webPanel.enabled} is on in
 * {@code config/bsp_server_utilities-network.toml}, every {@code snapshotSeconds} (5 by default) it sends a
 * {@link WebSnapshot} of its players to the panel: on Redis's web channel, which the other game servers never subscribe
 * to, and as this server's row in {@code <prefix>web_servers}. Nothing is sent while it is off or without shared storage.
 *
 * <p>The cost on the server thread is reading a few numbers per player once every few seconds; turning the JSON into
 * text and sending it happen there too but are small (measured by {@code WebGameTests}), and the database write runs on
 * the storage worker.
 */
public final class WebAgent {
    public static final String KIND_SNAPSHOT = "snapshot";
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private static BooleanSupplier enabled = WebAgent::configEnabled;
    private static IntSupplier seconds = WebAgent::configSeconds;
    private static Function<MinecraftServer, Collection<ServerPlayer>> players = s -> List.copyOf(s.getPlayerList().getPlayers());
    private static LongSupplier clock = System::currentTimeMillis;
    private static long nextAt;
    private static int watchTicks;
    /** The longest gap between snapshots: the panel counts a server as gone after about three missed ones (audit: 30 s made servers flicker). */
    public static final int MAX_SECONDS = 10;
    private static volatile long lastSentMs;
    private static volatile int lastCount;

    private WebAgent() {
    }

    private static boolean configEnabled() {
        try {
            return ServerUtilsNetworkConfig.WEB_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    private static int configSeconds() {
        try {
            return ServerUtilsNetworkConfig.WEB_SNAPSHOT_SECONDS.get();
        } catch (IllegalStateException notLoaded) {
            return 5;
        }
    }

    public static boolean enabled() {
        return enabled.getAsBoolean();
    }

    public static int seconds() {
        return Math.max(2, Math.min(MAX_SECONDS, seconds.getAsInt()));
    }

    /**
     * Every server tick: sends a snapshot once every {@link #seconds()} of real time while on and shared. Timed by the
     * clock, not by ticks, so a server lagging at 5 TPS still sends every 5 seconds and does not drop off the panel (audit).
     */
    public static void tick(MinecraftServer server) {
        Storage s = Storage.active();
        if (!enabled() || s == null) {
            nextAt = 0;
            return;
        }
        long now = clock.getAsLong();
        if (++watchTicks >= 20) {
            watchTicks = 0;
            if (WebMarket.watch() | WebRegions.watch() | WebShops.watch() | WebKits.watch()) { // each is asked every second (not ||); shops and stalls too (D817), kits (D845)
                nextAt = now + seconds() * 1000L;
                send(server, s); // a draw began or ended, or a region changed: every panel sees it at once (D411, D781)
                return;
            }
        }
        if (nextAt == 0) {
            nextAt = now + seconds() * 1000L;
            return;
        }
        if (now < nextAt) {
            return;
        }
        nextAt = now + seconds() * 1000L;
        send(server, s);
    }

    /**
     * Whether an answer can reach the panel from here: answers travel on Redis only, so while this server's Redis
     * publishing connection is down, an action would be carried out with the panel told "no answer" (audit). The snapshot
     * says so, the panel then sends nothing here, and {@link WebActions#dispatch} carries nothing out.
     */
    public static boolean canAnswer() {
        Storage s = Storage.active();
        com.mrgregles.bsp_server_utilities.storage.RedisLink r = s == null ? null : s.redis();
        return answersForTests != null ? answersForTests : r != null && r.publishing();
    }

    @Nullable
    private static Boolean answersForTests;

    /** The last snapshot before this server stops: nobody online and {@code stopping}, so the panel drops it at once (audit). */
    public static void farewell(MinecraftServer server) {
        Storage s = Storage.active();
        byte[] key = WebActions.currentKey();
        if (!enabled() || s == null || key == null) {
            return;
        }
        JsonObject snapshot = WebSnapshot.build(server, s.serverId(), List.of(), System.currentTimeMillis());
        snapshot.addProperty("every", seconds());
        snapshot.addProperty("answers", false);
        snapshot.addProperty("stopping", true);
        s.publishWeb(KIND_SNAPSHOT, WebActions.signText(key, GSON.toJson(snapshot)), true);
    }

    /** Builds and sends this server's snapshot now; returns the JSON sent. */
    public static String send(MinecraftServer server, Storage s) {
        byte[] key = WebActions.currentKey();
        if (key == null) {
            return ""; // unsigned it would be dropped by the panel: nothing is sent until webPanel.actionKey is set
        }
        Collection<ServerPlayer> online = players.apply(server);
        long now = System.currentTimeMillis();
        JsonObject snapshot = WebSnapshot.build(server, s.serverId(), online, now);
        snapshot.addProperty("every", seconds()); // the panel counts this server gone after about three missed (audit)
        snapshot.addProperty("answers", canAnswer());
        String json = GSON.toJson(snapshot);
        s.publishWeb(KIND_SNAPSHOT, WebActions.signText(key, json), true);
        lastSentMs = now;
        lastCount = online.size();
        return json;
    }

    /** When the last snapshot went (0: none yet) and how many players it held, for {@code /su status}. */
    public static long lastSentMs() {
        return lastSentMs;
    }

    public static int lastCount() {
        return lastCount;
    }

    /** Tests: whether it is on, how often, and whose players it reads (null leaves that one as it is). */
    public static void useForTests(@Nullable Boolean on, @Nullable Integer every, @Nullable Function<MinecraftServer, Collection<ServerPlayer>> who) {
        useForTests(on, every, who, null, null);
    }

    /** Tests: also the clock the snapshots are timed by and whether answers can reach the panel (null: as it is). */
    public static void useForTests(@Nullable Boolean on, @Nullable Integer every, @Nullable Function<MinecraftServer, Collection<ServerPlayer>> who,
                                   @Nullable LongSupplier time, @Nullable Boolean answers) {
        if (time != null) {
            clock = time;
        }
        if (answers != null) {
            answersForTests = answers;
        }
        if (on != null) {
            enabled = () -> on;
        }
        if (every != null) {
            seconds = () -> every;
        }
        if (who != null) {
            players = who;
        }
        nextAt = 0;
    }

    public static void useReal() {
        enabled = WebAgent::configEnabled;
        seconds = WebAgent::configSeconds;
        players = s -> List.copyOf(s.getPlayerList().getPlayers());
        clock = System::currentTimeMillis;
        answersForTests = null;
        nextAt = 0;
        lastSentMs = 0;
        lastCount = 0;
    }
}
