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
    private static int ticks;
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
        return Math.max(2, Math.min(30, seconds.getAsInt()));
    }

    /** Every server tick: sends a snapshot once every {@link #seconds()} while on and shared. */
    public static void tick(MinecraftServer server) {
        Storage s = Storage.active();
        if (!enabled() || s == null) {
            ticks = 0;
            return;
        }
        if (++ticks < seconds() * 20) {
            return;
        }
        ticks = 0;
        send(server, s);
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
        if (on != null) {
            enabled = () -> on;
        }
        if (every != null) {
            seconds = () -> every;
        }
        if (who != null) {
            players = who;
        }
        ticks = 0;
    }

    public static void useReal() {
        enabled = WebAgent::configEnabled;
        seconds = WebAgent::configSeconds;
        players = s -> List.copyOf(s.getPlayerList().getPlayers());
        ticks = 0;
        lastSentMs = 0;
        lastCount = 0;
    }
}
