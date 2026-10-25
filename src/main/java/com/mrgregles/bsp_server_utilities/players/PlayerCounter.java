package com.mrgregles.bsp_server_utilities.players;

import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.PlayersViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupService;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * The player counter (owner decisions D166 to D181).
 *
 * <ul>
 *   <li>Every player who joins any server of the network for the first time is counted once, by UUID (D166), from the
 *   counter's start (D167): with shared storage one table for the network, a row per UUID inserted once, so two servers
 *   never count the same player; without it, this server's own list (D174). Players the client check refuses never log in,
 *   so they are never counted.</li>
 *   <li>The number shown is the real list plus an adjustment admins set, add to, reset (0 from now) or take back to the
 *   real count (D170); the real list is never changed. Each change is written to the Action Log.</li>
 *   <li>A new player is welcomed in gold, in words admins type with {name} and {number} filled in (D171, D177, D179), on
 *   every server of the network (D176): on the server joined as typed, elsewhere after the server's name in grey (D180);
 *   the number is the one shown (D181).</li>
 *   <li>Every player's copy (for the PLAYERS board and Player count panels) is kept current: sent to everyone at most
 *   once a second when it changes, to a player at login, and again when the day's or week's counts move.</li>
 *   <li>Turned off in the setup guide (D173): nobody is counted or welcomed and the board and panels show nothing.</li>
 * </ul>
 */
public final class PlayerCounter {
    public static final String KIND_SETTINGS = "players_settings", KIND_NEW = "players_new";
    private static final String SEP = "\u001e";
    private static final int MINUTE_TICKS = 1200;
    private static final long REFRESH_MS = 600_000L;
    private static volatile PlayerCounts counts = PlayerCounts.NONE;
    private static volatile boolean dirty;
    private static int wait, ticks;
    private static long lastRefresh;
    @Nullable
    private static PlayersViewPacket lastSent;
    private static LongSupplier clock = System::currentTimeMillis;
    private static BiConsumer<MinecraftServer, Component> broadcaster = (server, line) -> server.getPlayerList().broadcastSystemMessage(line, false);

    private PlayerCounter() {
    }

    // ------------------------------------------------------------------ reading

    public static PlayersSettings settings(MinecraftServer server) {
        return PlayersData.get(server).settings();
    }

    public static PlayerCounts counts() {
        return counts;
    }

    public static int real() {
        return counts.real();
    }

    public static int shown(MinecraftServer server) {
        return settings(server).shown(counts.real());
    }

    public static boolean off() {
        return Switches.off(Feature.PLAYERS);
    }

    /** A number as the board, the panels and the welcome line write it: 1,235. */
    public static String format(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** The welcome line's words with the name and number filled in. */
    public static String welcomeText(String words, String name, int number) {
        return words.replace(PlayersSettings.NAME, name).replace(PlayersSettings.NUMBER, format(number));
    }

    /** Every player's copy of the counter. */
    public static PlayersViewPacket view(MinecraftServer server) {
        if (off()) {
            return PlayersViewPacket.NONE;
        }
        long now = clock.getAsLong();
        PlayerCounts c = counts;
        Joined n = c.newest();
        return new PlayersViewPacket(false, settings(server).title(), settings(server).shown(c.real()), c.today(now), c.week(now),
                n == null ? "" : n.name(), n == null ? 0 : Math.max(0, now - n.atMs()));
    }

    // ------------------------------------------------------------------ counting

    /** At login (after the client check let the player in): counts a first join on the network and welcomes it. */
    public static void onLogin(ServerPlayer player) {
        sendTo(player);
        if (off()) {
            return;
        }
        MinecraftServer server = player.server;
        Joined j = new Joined(player.getUUID(), player.getGameProfile().getName(), clock.getAsLong(), Storage.currentServerId());
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                if (s.backend().insertPlayer(j)) {
                    PlayerCounts c = s.backend().playerCounts(PlayerCounts.recentSince(j.atMs()), PlayerCounts.RECENT_MAX);
                    server.execute(() -> counted(server, j, c, s));
                }
            });
        } else if (PlayersData.get(server).add(j)) {
            counted(server, j, PlayersData.get(server).counts(clock.getAsLong()), null);
        }
    }

    private static void counted(MinecraftServer server, Joined j, PlayerCounts c, @Nullable Storage s) {
        counts = c;
        dirty = true;
        int number = shown(server);
        welcome(server, j, number, false);
        if (s != null) {
            s.publish(KIND_NEW, number + SEP + j.encode());
        }
    }

    /** The gold welcome line to everyone here; after the server's name in grey when the player joined another server. */
    private static void welcome(MinecraftServer server, Joined j, int number, boolean elsewhere) {
        if (off()) {
            return;
        }
        MutableComponent line = Component.empty();
        if (elsewhere) {
            line.append(Component.literal("[" + j.server() + "] ").withStyle(ChatFormatting.GRAY));
        }
        line.append(Component.literal(welcomeText(settings(server).welcome(), j.name(), number)).withStyle(ChatFormatting.GOLD));
        broadcaster.accept(server, line);
    }

    /** Reads the counts again: from the network's table with shared storage, else from this server's list. */
    public static void refresh(MinecraftServer server) {
        lastRefresh = clock.getAsLong();
        Storage s = Storage.active();
        if (s != null) {
            long since = PlayerCounts.recentSince(clock.getAsLong());
            s.submit(() -> {
                PlayerCounts c = s.backend().playerCounts(since, PlayerCounts.RECENT_MAX);
                server.execute(() -> {
                    counts = c;
                    dirty = true;
                });
            });
        } else {
            counts = PlayersData.get(server).counts(clock.getAsLong());
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ settings and adjusting

    /** Saves new settings for the network (the newest revision wins); a change counts as set up in the guide (D152). */
    public static PlayersSettings setSettings(MinecraftServer server, PlayersSettings s) {
        SetupService.touched(server, Feature.PLAYERS);
        PlayersData data = PlayersData.get(server);
        PlayersSettings next = s.withRevision(Math.max(clock.getAsLong(), data.settings().revision() + 1)).sanitised();
        data.setSettings(next);
        Storage st = Storage.active();
        if (st != null) {
            st.submit(() -> st.backend().savePlayersSettings(next));
            st.publish(KIND_SETTINGS, next.encode());
        }
        dirty = true;
        return next;
    }

    /** Takes a shared copy of the settings when it is newer. */
    public static void apply(MinecraftServer server, PlayersSettings shared) {
        PlayersData data = PlayersData.get(server);
        if (shared.revision() > data.settings().revision()) {
            data.setSettings(shared);
            dirty = true;
        }
    }

    /** {@code set <n>}: the number shown becomes n (the adjustment is n less the real count). */
    public static int set(MinecraftServer server, @Nullable ServerPlayer by, String byName, int n) {
        int to = Math.max(0, n);
        setSettings(server, settings(server).withOffset(clampOffset((long) to - real())));
        log(server, by, byName, "set the player count to " + format(to) + " (real count " + format(real()) + ")");
        return shown(server);
    }

    /** {@code add <n>}: n more shown (a negative n takes away, never below 0). */
    public static int add(MinecraftServer server, @Nullable ServerPlayer by, String byName, int n) {
        int was = shown(server);
        long to = Math.max(0, (long) was + n);
        setSettings(server, settings(server).withOffset(clampOffset(to - real())));
        log(server, by, byName, (n < 0 ? "took " + format(-n) + " from" : "added " + format(n) + " to") + " the player count (now " + format(shown(server)) + ")");
        return shown(server);
    }

    /** {@code reset}: 0 from now; the real list is kept. */
    public static int reset(MinecraftServer server, @Nullable ServerPlayer by, String byName) {
        setSettings(server, settings(server).withOffset(-real()));
        log(server, by, byName, "reset the player count to 0 (real count " + format(real()) + ")");
        return shown(server);
    }

    /** {@code true}: back to the real count. */
    public static int backToReal(MinecraftServer server, @Nullable ServerPlayer by, String byName) {
        setSettings(server, settings(server).withOffset(0));
        log(server, by, byName, "set the player count back to the real count (" + format(real()) + ")");
        return shown(server);
    }

    public static void setTitle(MinecraftServer server, @Nullable ServerPlayer by, String byName, String title) {
        PlayersSettings next = setSettings(server, settings(server).withTitle(PlayersSettings.clean(title, PlayersSettings.MAX_TITLE, PlayersSettings.DEFAULT_TITLE)));
        log(server, by, byName, "set the player counter's title to \"" + next.title() + "\"");
    }

    public static void setWelcome(MinecraftServer server, @Nullable ServerPlayer by, String byName, String words) {
        PlayersSettings next = setSettings(server, settings(server).withWelcome(PlayersSettings.clean(words, PlayersSettings.MAX_WELCOME, PlayersSettings.DEFAULT_WELCOME)));
        log(server, by, byName, "set the welcome line to \"" + next.welcome() + "\"");
    }

    private static int clampOffset(long o) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, o));
    }

    /** The change in the Action Log: under the admin's role, "Admin" for an admin who is not staff, or the console. */
    private static void log(MinecraftServer server, @Nullable ServerPlayer by, String byName, String text) {
        LogEntry e;
        if (by != null) {
            Optional<Staff.Role> role = ActionLog.tracked(by);
            e = ActionLog.entryFor(by, role.orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.CMD, text);
        } else {
            e = ActionLog.entryForServer(byName, LogEntry.CMD, text);
        }
        ActionLog.record(server, e);
    }

    // ------------------------------------------------------------------ players' copies

    public static void sendTo(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, view(player.server));
    }

    /** The setup guide turned the counter off or on: everyone's copy follows. */
    public static void switched(MinecraftServer server) {
        if (!off()) {
            refresh(server);
        }
        dirty = true;
    }

    /** Every tick: everyone's copy at most once a second; the day's and week's counts checked each minute. */
    public static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % MINUTE_TICKS == 0) {
            if (Storage.active() != null && clock.getAsLong() - lastRefresh >= REFRESH_MS) {
                refresh(server);
            }
            PlayersViewPacket v = view(server), last = lastSent;
            if (last != null && (v.today() != last.today() || v.week() != last.week())) {
                dirty = true;
            }
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (dirty) {
            dirty = false;
            wait = 20;
            PlayersViewPacket v = view(server);
            lastSent = v;
            ServerUtilsNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), v);
        }
    }

    /** Whether everyone's copy waits to be sent (tests). */
    public static boolean dirty() {
        return dirty;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void registerNotices() {
        Storage.on(KIND_SETTINGS, (srv, n) -> {
            PlayersSettings s = PlayersSettings.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
        Storage.on(KIND_NEW, (srv, n) -> {
            int at = n.payload().indexOf(SEP);
            Joined j = at > 0 ? Joined.decode(n.payload().substring(at + 1)) : null;
            if (j == null) {
                return;
            }
            try {
                welcome(srv, j, Integer.parseInt(n.payload().substring(0, at)), true);
            } catch (NumberFormatException ignored) {
                // not a counter notice
            }
            refresh(srv);
        });
    }

    public static void onServerStarted(MinecraftServer server) {
        counts = PlayerCounts.NONE;
        lastSent = null;
        ticks = 0;
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                PlayersSettings shared = s.backend().loadPlayersSettings();
                if (shared == null) {
                    s.backend().savePlayersSettings(settings(server));
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
        refresh(server);
    }

    // ------------------------------------------------------------------ tests

    /** A test clock and where welcome lines go (null: to everyone on the server). */
    public static void useForTests(LongSupplier testClock, @Nullable BiConsumer<MinecraftServer, Component> lines) {
        clock = testClock;
        broadcaster = lines != null ? lines : (server, line) -> server.getPlayerList().broadcastSystemMessage(line, false);
    }

    public static void useReal() {
        useForTests(System::currentTimeMillis, null);
    }

    /** Puts the counts back as given (tests). */
    public static void setCountsForTests(PlayerCounts c) {
        counts = c;
    }
}
