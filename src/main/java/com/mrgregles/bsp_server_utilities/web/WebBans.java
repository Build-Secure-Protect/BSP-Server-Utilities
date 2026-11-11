package com.mrgregles.bsp_server_utilities.web;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsNetworkConfig;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Network bans (SU-44; D255, D350 to D356): the bans in force, held in memory so a login is checked without waiting on the
 * database (D350). Read from {@code <prefix>bans} when the server starts, every minute, and at once when another server
 * says a ban changed (notice {@link #KIND}). A banned player is refused at login on every server, with the reason, when it
 * ends (or that it is permanent) and the appeal line from the network config ({@code webPanel.banAppeal}).
 */
public final class WebBans {
    public static final String KIND = "bans_changed";
    public static final long RELOAD_MS = 60_000L;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private static final Map<UUID, StorageBackend.Ban> ACTIVE = new ConcurrentHashMap<>();
    private static LongSupplier clock = System::currentTimeMillis;
    private static Supplier<String> appeal = WebBans::configAppeal;
    private static long loadedAt;
    private static java.util.function.Function<MinecraftServer, java.util.Collection<ServerPlayer>> players = s -> s.getPlayerList().getPlayers();

    private WebBans() {
    }

    private static String configAppeal() {
        try {
            return ServerUtilsNetworkConfig.WEB_BAN_APPEAL.get().trim();
        } catch (IllegalStateException e) {
            return "";
        }
    }

    public static void registerNotices() {
        Storage.on(KIND, (server, n) -> reload(server));
    }

    /** Reads the bans in force again (on the storage worker); quietly nothing without shared storage. */
    public static void reload() {
        reload(null);
    }

    /**
     * Reads the bans in force again, then sends any banned player still on this server off the network (D350): a ban
     * written by another server while they were moving here, or as they joined, reaches them this way (audit).
     */
    public static void reload(@Nullable MinecraftServer server) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        s.submit(() -> {
            List<StorageBackend.Ban> list = s.backend().loadActiveBans(clock.getAsLong());
            replace(list);
            if (server != null) {
                server.execute(() -> sendOff(server));
            }
        });
    }

    /** Every player here with a ban in force leaves the network with its screen; how many. */
    public static int sendOff(MinecraftServer server) {
        int n = 0;
        for (ServerPlayer p : List.copyOf(players.apply(server))) {
            StorageBackend.Ban b = banOf(p.getUUID());
            if (b != null) {
                ProxyKick.kick(p, screen(b));
                n++;
            }
        }
        return n;
    }

    /** The bans in force, all at once (what a reload read). */
    public static void replace(List<StorageBackend.Ban> list) {
        Map<UUID, StorageBackend.Ban> next = new java.util.HashMap<>();
        for (StorageBackend.Ban b : list) {
            StorageBackend.Ban had = next.get(b.player());
            if (had == null || longer(b, had)) {
                next.put(b.player(), b);
            }
        }
        ACTIVE.keySet().retainAll(next.keySet());
        ACTIVE.putAll(next);
        loadedAt = clock.getAsLong();
    }

    private static boolean longer(StorageBackend.Ban a, StorageBackend.Ban b) {
        return a.untilMs() == 0 || (b.untilMs() != 0 && a.untilMs() > b.untilMs());
    }

    /** One ban known at once (this server just wrote it), before the next reload. */
    public static void put(StorageBackend.Ban b) {
        if (b.activeAt(clock.getAsLong())) {
            ACTIVE.merge(b.player(), b, (old, nu) -> longer(nu, old) ? nu : old);
        }
    }

    /** This ban is no longer in force as held (lifted or changed); another ban of theirs stays until the reload (audit). */
    public static void forget(UUID player, long banId) {
        ACTIVE.computeIfPresent(player, (k, b) -> b.id() == banId ? null : b);
    }

    /** Every minute from the server tick: a reload when one is due. */
    public static void tick(MinecraftServer server) {
        if (clock.getAsLong() - loadedAt >= RELOAD_MS) {
            loadedAt = clock.getAsLong();
            reload(server);
        }
    }

    /** The ban in force for this player, or null. */
    @Nullable
    public static StorageBackend.Ban banOf(UUID player) {
        StorageBackend.Ban b = ACTIVE.get(player);
        if (b != null && !b.activeAt(clock.getAsLong())) {
            ACTIVE.remove(player, b);
            return null;
        }
        return b;
    }

    /** The screen a banned player sees at login (D354), or null when they may join. */
    @Nullable
    public static Component refusal(UUID player) {
        StorageBackend.Ban b = banOf(player);
        return b == null ? null : screen(b);
    }

    public static Component screen(StorageBackend.Ban b) {
        MutableComponent c = Component.translatableWithFallback("message.bsp_server_utilities.ban.title", "You are banned from the Build Secure Protect network.")
                .withStyle(ChatFormatting.RED);
        if (!b.reason().isBlank()) {
            c.append("\n\n").append(Component.literal(b.reason()).withStyle(ChatFormatting.WHITE));
        }
        c.append("\n\n");
        if (b.untilMs() == 0) {
            c.append(Component.translatableWithFallback("message.bsp_server_utilities.ban.permanent", "This ban is permanent.").withStyle(ChatFormatting.GRAY));
        } else {
            c.append(Component.translatableWithFallback("message.bsp_server_utilities.ban.ends", "Ends %s UTC (in %s)", WHEN.format(Instant.ofEpochMilli(b.untilMs())),
                    left(b.untilMs() - clock.getAsLong())).withStyle(ChatFormatting.GRAY));
        }
        String a = appeal.get();
        if (!a.isEmpty()) {
            c.append("\n\n").append(Component.literal(a.length() > 200 ? a.substring(0, 200) : a).withStyle(ChatFormatting.AQUA));
        }
        return c;
    }

    /** "2 days 3 hours", "5 hours 10 minutes", "12 minutes". */
    public static String left(long ms) {
        long m = Math.max(1, (ms + 59_999) / 60_000), h = m / 60, d = h / 24;
        if (d > 0) {
            return d + (d == 1 ? " day" : " days") + (h % 24 > 0 ? " " + h % 24 + (h % 24 == 1 ? " hour" : " hours") : "");
        }
        if (h > 0) {
            return h + (h == 1 ? " hour" : " hours") + (m % 60 > 0 ? " " + m % 60 + (m % 60 == 1 ? " minute" : " minutes") : "");
        }
        return m + (m == 1 ? " minute" : " minutes");
    }

    public static void useForTests(@Nullable LongSupplier testClock, @Nullable Supplier<String> testAppeal) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        appeal = testAppeal != null ? testAppeal : WebBans::configAppeal;
    }

    public static void clearForTests() {
        ACTIVE.clear();
        players = s -> s.getPlayerList().getPlayers();
    }

    /** The players on this server, for tests (mock players are not in the player list). */
    public static void usePlayersForTests(java.util.function.Function<MinecraftServer, java.util.Collection<ServerPlayer>> testPlayers) {
        players = testPlayers;
    }

    static {
        BSPServerUtilities.LOGGER.debug("Network bans ready");
    }
}
