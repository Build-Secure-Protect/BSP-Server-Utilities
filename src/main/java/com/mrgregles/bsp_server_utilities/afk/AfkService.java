package com.mrgregles.bsp_server_utilities.afk;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * AFK detection (owner decisions D70 to D77, D83 to D85), on the server thread.
 *
 * <ul>
 *   <li><b>Active</b> is turning the head, or anything the server sees a player do: chat, a command, breaking, placing,
 *   using an item or block, attacking, clicking in a container, dropping, steering a vehicle. Being moved by water, a
 *   minecart or a push is not (D70). A player held by the rules popup is never idle.</li>
 *   <li>After {@code afkMinutes} with nothing active a player is AFK and told so; their next action brings them back
 *   (D71, D73). {@code /afk} marks them at once (D77). Staff who are AFK show as away on the staff panels (D72).</li>
 *   <li>With kicking on, a player AFK for {@code kickMinutes} is sent off with the message, warned a minute before;
 *   the time counts from when they went AFK (D74, D75, D83, D84). Staff and operators are never kicked (D85).</li>
 *   <li>The settings are one set for the network (table and a {@value #KIND_SETTINGS} notice, the newest revision wins);
 *   each server sends who is AFK on it on every change and every {@value #HEARTBEAT_TICKS} ticks, for the AFK tab.</li>
 * </ul>
 */
public final class AfkService {
    public static final String KIND_SETTINGS = "afk_settings", KIND_PRESENCE = "afk_presence";
    public static final int CHECK_TICKS = 20, HEARTBEAT_TICKS = 600;
    public static final long STALE_MS = 90_000L, WARN_MS = 60_000L;
    /** How far (in degrees) the head must turn to count. */
    private static final float TURN = 0.5f;

    private static final class Track {
        long lastActive, afkSince;
        boolean warned;
        float yaw, pitch;
    }

    /** One AFK player as a server reports them: how long they have been AFK, and whether they can never be kicked. */
    public record Afk(UUID id, String name, long afkForMs, boolean exempt) {
    }

    /** One AFK player anywhere on the network, for the AFK tab. */
    public record Row(UUID id, String name, String server, long afkForMs, boolean exempt) {
    }

    private record Remote(long atMs, List<Afk> afk) {
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    private static final Map<String, Remote> REMOTE = new HashMap<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static long ticks;
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static Supplier<Collection<ServerPlayer>> playersOverride;
    private static BiConsumer<ServerPlayer, Component> kicker = (p, why) -> p.connection.disconnect(why);

    private AfkService() {
    }

    /** Called when who is AFK, or the settings, may have changed (the AFK tab follows). */
    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ settings

    public static AfkSettings settings(MinecraftServer server) {
        return AfkData.get(server).settings();
    }

    /** Replaces the settings for the whole network. */
    public static AfkSettings setSettings(MinecraftServer server, AfkSettings s) {
        com.mrgregles.bsp_server_utilities.setup.SetupService.touched(server, com.mrgregles.bsp_server_utilities.setup.Feature.AFK); // changed in its tab: set up (D152)
        AfkData data = AfkData.get(server);
        AfkSettings next = s.withRevision(Math.max(System.currentTimeMillis(), data.settings().revision() + 1)).sanitised();
        data.setSettings(next);
        Storage st = Storage.active();
        if (st != null) {
            st.submit(() -> st.backend().saveAfkSettings(next));
            st.publish(KIND_SETTINGS, next.encode());
        }
        changed();
        return next;
    }

    /** Takes a shared copy of the settings when it is newer. */
    public static void apply(MinecraftServer server, AfkSettings shared) {
        AfkData data = AfkData.get(server);
        if (shared.revision() > data.settings().revision()) {
            data.setSettings(shared);
            changed();
        }
    }

    // ------------------------------------------------------------------ activity

    private static Collection<ServerPlayer> localPlayers(MinecraftServer server) {
        return playersOverride != null ? playersOverride.get() : server.getPlayerList().getPlayers();
    }

    private static Track track(ServerPlayer p) {
        return TRACKS.computeIfAbsent(p.getUUID(), id -> {
            Track t = new Track();
            t.lastActive = clock.getAsLong();
            t.yaw = p.getYRot();
            t.pitch = p.getXRot();
            return t;
        });
    }

    public static boolean isAfk(UUID player) {
        Track t = TRACKS.get(player);
        return t != null && t.afkSince > 0;
    }

    /** The player did something: the idle clock starts again and, when they were AFK, they are back. */
    public static void active(ServerPlayer p) {
        if (!p.server.isSameThread()) {
            return; // packet handlers run first on the network thread; the server thread's run counts
        }
        Track t = track(p);
        t.lastActive = clock.getAsLong();
        if (t.afkSince > 0) {
            t.afkSince = 0;
            t.warned = false;
            p.sendSystemMessage(Component.translatable("message.bsp_server_utilities.afk.back").withStyle(ChatFormatting.GREEN));
            afkChanged(p.server);
        }
    }

    /** Marks the player AFK now ({@code /afk}, or idle long enough); nothing when they already are. */
    public static void markAfk(ServerPlayer p) {
        Track t = track(p);
        if (t.afkSince > 0) {
            return;
        }
        t.afkSince = clock.getAsLong();
        t.warned = false;
        p.sendSystemMessage(Component.translatable("message.bsp_server_utilities.afk.now").withStyle(ChatFormatting.GOLD));
        afkChanged(p.server);
    }

    /** Staff and operators are never kicked for being AFK (D74, D85). */
    public static boolean exempt(ServerPlayer p) {
        return Staff.isStaff(p) || Perms.canEdit(p);
    }

    /** Once a second: head turns and steering, then who has gone AFK, who is warned, who is kicked. */
    public static void check(MinecraftServer server) {
        long now = clock.getAsLong();
        AfkSettings s = settings(server);
        for (ServerPlayer p : List.copyOf(localPlayers(server))) {
            Track t = track(p);
            if (RulesService.isHeld(p.getUUID())) {
                t.lastActive = now; // reading the rules is not idling
                continue;
            }
            float yaw = p.getYRot(), pitch = p.getXRot();
            boolean turned = Math.abs(Mth.wrapDegrees(yaw - t.yaw)) > TURN || Math.abs(pitch - t.pitch) > TURN;
            t.yaw = yaw;
            t.pitch = pitch;
            if (turned || (p.isPassenger() && (p.xxa != 0 || p.zza != 0))) {
                active(p);
                continue;
            }
            if (!s.enabled()) {
                continue;
            }
            if (t.afkSince == 0 && now - t.lastActive >= s.afkMs()) {
                markAfk(p);
            }
            if (s.kick() && t.afkSince > 0 && !exempt(p)) {
                long afkFor = now - t.afkSince;
                if (afkFor >= s.kickMs()) {
                    TRACKS.remove(p.getUUID());
                    kicker.accept(p, Component.literal(s.message()));
                    afkChanged(server);
                } else if (afkFor >= s.kickMs() - WARN_MS && !t.warned) {
                    t.warned = true;
                    p.sendSystemMessage(Component.translatable("message.bsp_server_utilities.afk.warn").withStyle(ChatFormatting.RED));
                }
            }
        }
    }

    /** Whether the player has been warned of a kick (tests). */
    public static boolean warned(UUID player) {
        Track t = TRACKS.get(player);
        return t != null && t.warned;
    }

    // ------------------------------------------------------------------ who is AFK, across the network

    private static void afkChanged(MinecraftServer server) {
        StaffDirectory.awayChanged(server);
        publish(server, null);
        changed();
    }

    public static List<Afk> localAfk(MinecraftServer server) {
        return localAfk(server, null);
    }

    private static List<Afk> localAfk(MinecraftServer server, @Nullable UUID leaving) {
        long now = clock.getAsLong();
        List<Afk> out = new ArrayList<>();
        for (ServerPlayer p : localPlayers(server)) {
            Track t = TRACKS.get(p.getUUID());
            if (t != null && t.afkSince > 0 && !p.getUUID().equals(leaving)) {
                out.add(new Afk(p.getUUID(), p.getGameProfile().getName(), Math.max(0, now - t.afkSince), exempt(p)));
            }
        }
        return out;
    }

    private static void publish(MinecraftServer server, @Nullable UUID leaving) {
        Storage s = Storage.active();
        if (s != null) {
            s.publish(KIND_PRESENCE, encode(s.serverId(), localAfk(server, leaving)));
        }
    }

    public static String encode(String serverId, List<Afk> afk) {
        StringBuilder b = new StringBuilder(serverId);
        for (Afk a : afk) {
            b.append('\n').append(a.id()).append('|').append(a.name()).append('|').append(a.afkForMs()).append('|').append(a.exempt() ? '1' : '0');
        }
        return b.toString();
    }

    /** Reads an AFK notice; null when it is not one. */
    @Nullable
    public static Map.Entry<String, List<Afk>> decode(String payload) {
        String[] lines = payload.split("\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return null;
        }
        List<Afk> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            if (f.length == 4) {
                try {
                    out.add(new Afk(UUID.fromString(f[0]), f[1], Long.parseLong(f[2]), f[3].equals("1")));
                } catch (IllegalArgumentException ignored) {
                    // a bad line
                }
            }
        }
        return Map.entry(lines[0], out);
    }

    /** Another server's list of AFK players. */
    public static void applyPresence(String payload) {
        Map.Entry<String, List<Afk>> e = decode(payload);
        if (e != null && !e.getKey().equals(Storage.currentServerId())) {
            REMOTE.put(e.getKey(), new Remote(clock.getAsLong(), List.copyOf(e.getValue())));
            changed();
        }
    }

    /** Everyone AFK on the network (lists older than {@value #STALE_MS} ms left out), longest AFK first. */
    public static List<Row> afkNow(MinecraftServer server) {
        String here = Storage.currentServerId();
        long now = clock.getAsLong();
        List<Row> out = new ArrayList<>();
        for (Afk a : localAfk(server)) {
            out.add(new Row(a.id(), a.name(), here, a.afkForMs(), a.exempt()));
        }
        REMOTE.forEach((srv, r) -> {
            if (now - r.atMs() <= STALE_MS) {
                for (Afk a : r.afk()) {
                    out.add(new Row(a.id(), a.name(), srv, a.afkForMs() + (now - r.atMs()), a.exempt()));
                }
            }
        });
        out.sort(Comparator.comparingLong(Row::afkForMs).reversed());
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void onLogin(ServerPlayer p) {
        TRACKS.remove(p.getUUID());
        track(p);
    }

    public static void onLogout(ServerPlayer p) {
        boolean was = isAfk(p.getUUID());
        TRACKS.remove(p.getUUID());
        if (was) {
            publish(p.server, p.getUUID());
            changed();
        }
    }

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_SETTINGS, (srv, n) -> {
            AfkSettings s = AfkSettings.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
        Storage.on(KIND_PRESENCE, (srv, n) -> applyPresence(n.payload()));
    }

    /** The actions that count as active (D70); once, in the mod constructor. Container clicks come through a mixin. */
    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((ServerChatEvent e) -> active(e.getPlayer()));
        MinecraftForge.EVENT_BUS.addListener((CommandEvent e) -> {
            ServerPlayer p = e.getParseResults().getContext().getSource().getPlayer();
            if (p != null && !LogText.root(e.getParseResults().getReader().getString()).equals("afk")) {
                active(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getPlayer() instanceof ServerPlayer p) {
                active(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                active(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> interacted(e));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem e) -> interacted(e));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock e) -> interacted(e));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract e) -> interacted(e));
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteractSpecific e) -> interacted(e));
        MinecraftForge.EVENT_BUS.addListener((AttackEntityEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                active(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ItemTossEvent e) -> {
            if (e.getPlayer() instanceof ServerPlayer p) {
                active(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerContainerEvent.Open e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                active(p);
            }
        });
    }

    private static void interacted(PlayerInteractEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            active(p);
        }
    }

    /** At server start, after the storage: the shared settings (or ours written as the first). */
    public static void onServerStarted(MinecraftServer server) {
        TRACKS.clear();
        REMOTE.clear();
        StaffDirectory.setAutoAway(AfkService::isAfk);
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                AfkSettings shared = s.backend().loadAfkSettings();
                if (shared == null) {
                    s.backend().saveAfkSettings(settings(server));
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % CHECK_TICKS == 0) {
            check(server);
        }
        if (ticks % HEARTBEAT_TICKS == 0) {
            publish(server, null);
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock, @Nullable Supplier<Collection<ServerPlayer>> players, @Nullable BiConsumer<ServerPlayer, Component> kick) {
        clock = testClock;
        playersOverride = players;
        if (kick != null) {
            kicker = kick;
        }
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        playersOverride = null;
        kicker = (p, why) -> p.connection.disconnect(why);
    }

    public static void forget(UUID player) {
        TRACKS.remove(player);
    }
}
