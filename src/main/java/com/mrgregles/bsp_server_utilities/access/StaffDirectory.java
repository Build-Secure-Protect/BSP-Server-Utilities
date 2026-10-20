package com.mrgregles.bsp_server_utilities.access;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Who the staff are and where they are, across the network (owner decisions D4, D23, D24, D57, D61 to D63), on the
 * server thread.
 *
 * <ul>
 *   <li><b>Groups</b> live in {@link StaffData}, seeded once from the config, edited with {@link #setGroups}, shared as a
 *   table and a {@value #KIND_GROUPS} notice (the newest revision wins).</li>
 *   <li><b>Presence</b>: every server sends its online staff (name, role, away) on every join, leave and away change and
 *   every {@value #HEARTBEAT_TICKS} ticks; a server's list older than {@value #STALE_MS} ms no longer counts.</li>
 *   <li><b>Away</b> is set by {@code /su away} and ends at logout; staff who are AFK show as away too while they are (D72).</li>
 *   <li><b>Last seen</b> is written when a staff member leaves a server, shared as a table and a notice.</li>
 *   <li><b>The roster</b> is everyone in a staff group per LuckPerms (offline too, refreshed every few minutes and on a
 *   group change), operators who have played here, and anyone online as staff anywhere, in role order.</li>
 * </ul>
 */
public final class StaffDirectory {
    public static final String KIND_GROUPS = "staff_groups", KIND_PRESENCE = "staff_presence", KIND_SEEN = "staff_seen";
    public static final int HEARTBEAT_TICKS = 600;
    public static final long STALE_MS = 90_000L, MEMBERS_REFRESH_MS = 300_000L;

    public enum State { ONLINE, AWAY, OFFLINE }

    /** One staff member as panels and the STAFF tab show them. */
    public record Member(UUID id, String name, Staff.Role role, State state, String server, long lastSeenMs) {
    }

    /** One online staff member as a server reports them. */
    public record Online(UUID id, String name, String group, boolean away) {
    }

    private record Presence(long atMs, List<Online> online) {
    }

    private static final Map<String, Presence> REMOTE = new HashMap<>();
    private static final Set<UUID> AWAY = new HashSet<>();
    /** Staff away without typing {@code /su away}: AFK detection's answer. */
    private static java.util.function.Predicate<UUID> autoAway = id -> false;
    /** LuckPerms members of each staff group (group to members), as last looked up. */
    private static volatile Map<String, Map<UUID, String>> members = Map.of();
    private static long membersAt, ticks;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static Supplier<Collection<ServerPlayer>> playersOverride;

    private StaffDirectory() {
    }

    /** Called when anything panels show may have changed (SU-10 sends fresh views). */
    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    /** Something the roster shows changed outside this class (a player joined). */
    public static void touch() {
        changed();
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    // ------------------------------------------------------------------ groups

    /** Fills the groups from the config the first time, when nothing else has. */
    public static void seed(MinecraftServer server) {
        StaffData data = StaffData.get(server);
        if (!data.seeded()) {
            StaffGroups fromConfig = Staff.fromConfig();
            data.setGroups(new StaffGroups(fromConfig.roles(), fromConfig.operators(), data.groups().revision()).sanitised());
            data.markSeeded();
        }
    }

    /** Replaces the staff groups for the whole network. */
    public static StaffGroups setGroups(MinecraftServer server, List<Staff.Role> roles, boolean operators) {
        StaffData data = StaffData.get(server);
        StaffGroups next = new StaffGroups(roles, operators, Math.max(System.currentTimeMillis(), data.groups().revision() + 1)).sanitised();
        data.setGroups(next);
        data.markSeeded();
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> s.backend().saveStaffGroups(next));
            s.publish(KIND_GROUPS, Long.toString(next.revision()));
        }
        refreshMembers(server);
        publishPresence(server);
        changed();
        return next;
    }

    /** Takes a shared copy of the groups when it is newer. */
    public static void apply(MinecraftServer server, StaffGroups shared) {
        StaffData data = StaffData.get(server);
        if (shared.revision() > data.groups().revision()) {
            data.setGroups(shared);
            data.markSeeded();
            refreshMembers(server);
            changed();
        }
    }

    // ------------------------------------------------------------------ presence, away, last seen

    private static Collection<ServerPlayer> localPlayers(MinecraftServer server) {
        return playersOverride != null ? playersOverride.get() : server.getPlayerList().getPlayers();
    }

    /** This server's online staff. */
    public static List<Online> localOnline(MinecraftServer server) {
        return localOnline(server, null);
    }

    private static List<Online> localOnline(MinecraftServer server, @Nullable UUID leaving) {
        List<Online> out = new ArrayList<>();
        for (ServerPlayer p : localPlayers(server)) {
            if (p.getUUID().equals(leaving)) {
                continue;
            }
            Optional<Staff.Role> role = Staff.roleOf(p);
            role.ifPresent(r -> out.add(new Online(p.getUUID(), p.getGameProfile().getName(), r.group(), AWAY.contains(p.getUUID()) || autoAway.test(p.getUUID()))));
        }
        return out;
    }

    public static boolean isAway(UUID player) {
        return AWAY.contains(player);
    }

    /** Who is away without typing {@code /su away} (AFK detection sets it at server start). */
    public static void setAutoAway(java.util.function.Predicate<UUID> p) {
        autoAway = p;
    }

    /** Someone's automatic away changed: the other servers and the panels follow. */
    public static void awayChanged(MinecraftServer server) {
        publishPresence(server);
        changed();
    }

    /** {@code /su away}: switches the player's away mark; returns whether they are away now. */
    public static boolean toggleAway(ServerPlayer player) {
        boolean away = !AWAY.remove(player.getUUID());
        if (away) {
            AWAY.add(player.getUUID());
        }
        publishPresence(player.server);
        changed();
        return away;
    }

    /** Sends this server's online staff to the other servers. */
    public static void publishPresence(MinecraftServer server) {
        publishPresence(server, null);
    }

    private static void publishPresence(MinecraftServer server, @Nullable UUID leaving) {
        Storage s = Storage.active();
        if (s != null) {
            s.publish(KIND_PRESENCE, encode(s.serverId(), localOnline(server, leaving)));
        }
    }

    public static String encode(String serverId, List<Online> online) {
        StringBuilder b = new StringBuilder(serverId);
        for (Online o : online) {
            b.append('\n').append(o.id()).append('|').append(o.name()).append('|').append(o.group()).append('|').append(o.away() ? '1' : '0');
        }
        return b.toString();
    }

    /** Reads a presence notice; null when it is not one. */
    @Nullable
    public static Map.Entry<String, List<Online>> decode(String payload) {
        String[] lines = payload.split("\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return null;
        }
        List<Online> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            if (f.length == 4) {
                try {
                    out.add(new Online(UUID.fromString(f[0]), f[1], f[2], f[3].equals("1")));
                } catch (IllegalArgumentException ignored) {
                    // a bad line
                }
            }
        }
        return Map.entry(lines[0], out);
    }

    /** Another server's list of online staff. */
    public static void applyPresence(String payload) {
        Map.Entry<String, List<Online>> e = decode(payload);
        if (e != null) {
            REMOTE.put(e.getKey(), new Presence(clock.getAsLong(), List.copyOf(e.getValue())));
            changed();
        }
    }

    /** A staff member left this server: the last-seen record, shared, and the new list of online staff. */
    public static void onLogout(ServerPlayer player) {
        AWAY.remove(player.getUUID());
        if (Staff.roleOf(player).isPresent()) {
            String serverId = Storage.currentServerId();
            StaffData.Seen seen = new StaffData.Seen(player.getGameProfile().getName(), clock.getAsLong(), serverId);
            StaffData.get(player.server).putSeen(player.getUUID(), seen);
            Storage s = Storage.active();
            if (s != null) {
                s.submit(() -> s.backend().saveSeen(player.getUUID(), seen));
                s.publish(KIND_SEEN, player.getUUID() + "|" + seen.name() + "|" + seen.atMs() + "|" + seen.server());
            }
        }
        publishPresence(player.server, player.getUUID());
        changed();
    }

    public static void applySeen(MinecraftServer server, String payload) {
        String[] f = payload.split("\\|", -1);
        if (f.length == 4) {
            try {
                StaffData.get(server).putSeen(UUID.fromString(f[0]), new StaffData.Seen(f[1], Long.parseLong(f[2]), f[3]));
                changed();
            } catch (IllegalArgumentException ignored) {
                // a bad notice
            }
        }
    }

    // ------------------------------------------------------------------ LuckPerms members

    /** Looks the staff groups' members up in LuckPerms again (in the background; the roster follows when they arrive). */
    public static void refreshMembers(MinecraftServer server) {
        membersAt = System.currentTimeMillis();
        if (!LuckPermsBridge.isPresent()) {
            members = Map.of();
            return;
        }
        Map<String, Map<UUID, String>> found = new LinkedHashMap<>();
        List<Staff.Role> roles = Staff.roles(server);
        if (roles.isEmpty()) {
            members = Map.of();
            return;
        }
        int[] left = {roles.size()};
        for (Staff.Role r : roles) {
            LuckPermsBridge.membersOf(r.group()).thenAccept(m -> server.execute(() -> {
                found.put(r.group(), m);
                if (--left[0] == 0) {
                    members = Map.copyOf(found);
                    changed();
                }
            }));
        }
    }

    // ------------------------------------------------------------------ the roster

    /** Every staff member in role order, online ones first within a role, then by name. */
    public static List<Member> roster(MinecraftServer server) {
        List<Staff.Role> roles = Staff.roles(server);
        Map<String, Staff.Role> byGroup = new HashMap<>();
        roles.forEach(r -> byGroup.put(r.group(), r));
        Map<UUID, Member> out = new LinkedHashMap<>();
        StaffData data = StaffData.get(server);
        // online here and on the other servers
        String here = Storage.currentServerId();
        long now = clock.getAsLong();
        Map<String, List<Online>> online = new LinkedHashMap<>();
        online.put(here, localOnline(server));
        REMOTE.forEach((srv, p) -> {
            if (!srv.equals(here) && now - p.atMs() <= STALE_MS) {
                online.put(srv, p.online());
            }
        });
        online.forEach((srv, list) -> {
            for (Online o : list) {
                Staff.Role role = o.group().isEmpty() ? Staff.OPERATOR : byGroup.getOrDefault(o.group(), new Staff.Role(o.group(), o.group(), Staff.DEFAULT_COLOUR));
                out.putIfAbsent(o.id(), new Member(o.id(), o.name(), role, o.away() ? State.AWAY : State.ONLINE, srv, now));
            }
        });
        // offline members of the staff groups, per LuckPerms
        Map<String, Map<UUID, String>> m = members;
        for (Staff.Role r : roles) {
            for (Map.Entry<UUID, String> e : m.getOrDefault(r.group(), Map.of()).entrySet()) {
                addOffline(out, data, e.getKey(), e.getValue(), r);
            }
        }
        // operators who have played here
        if (Staff.operatorsCount(server)) {
            for (String name : server.getPlayerList().getOps().getUserList()) {
                Optional<GameProfile> profile = server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name);
                profile.ifPresent(p -> addOffline(out, data, p.getId(), p.getName(), Staff.OPERATOR));
            }
        }
        List<Member> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingInt((Member x) -> x.role().operator() ? roles.size() : Math.max(0, roles.indexOf(x.role())))
                .thenComparing(x -> x.state() == State.OFFLINE)
                .thenComparing(x -> x.name().toLowerCase(Locale.ROOT)));
        return list;
    }

    private static void addOffline(Map<UUID, Member> out, StaffData data, UUID id, String name, Staff.Role role) {
        if (!out.containsKey(id)) {
            StaffData.Seen seen = data.seen().get(id);
            out.put(id, new Member(id, seen != null && name.equals(id.toString()) ? seen.name() : name, role, State.OFFLINE, "", seen == null ? 0 : seen.atMs()));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_PRESENCE, (srv, n) -> applyPresence(n.payload()));
        Storage.on(KIND_SEEN, (srv, n) -> applySeen(srv, n.payload()));
        Storage.on(KIND_GROUPS, (srv, n) -> {
            Storage s = Storage.active();
            if (s != null) {
                s.submit(() -> {
                    StaffGroups g = s.backend().loadStaffGroups();
                    if (g != null) {
                        srv.execute(() -> apply(srv, g));
                    }
                });
            }
        });
    }

    /** At server start, after the storage: the groups (shared, else seeded), the last-seen records, the members. */
    public static void onServerStarted(MinecraftServer server) {
        REMOTE.clear();
        AWAY.clear();
        seed(server);
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                StaffGroups shared = s.backend().loadStaffGroups();
                Map<UUID, StaffData.Seen> seen = s.backend().loadSeen();
                if (shared == null) {
                    s.backend().saveStaffGroups(StaffData.get(server).groups());
                }
                server.execute(() -> {
                    if (shared != null) {
                        apply(server, shared);
                    }
                    seen.forEach((id, v) -> StaffData.get(server).putSeen(id, v));
                });
            });
        }
        refreshMembers(server);
    }

    /** Every tick: the heartbeat, and the LuckPerms members looked up again every few minutes. */
    public static void tick(MinecraftServer server) {
        if (++ticks % HEARTBEAT_TICKS == 0) {
            publishPresence(server);
            if (System.currentTimeMillis() - membersAt > MEMBERS_REFRESH_MS) {
                refreshMembers(server);
            }
            changed();
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

    public static void forgetRemote() {
        REMOTE.clear();
    }
}
