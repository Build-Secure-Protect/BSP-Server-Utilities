package com.mrgregles.bsp_server_utilities.rooms;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.AdminRoomsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminRoomsPacket;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The admins' ROOMS tab, server side (SU-59; owner decisions D443, D448, D452 look A, D453 R4 to R7): for operators and
 * BSP-Core admins only, as the other tabs (R6). Every owner with their count against the limit (this server's rooms read
 * live, and with {@code scope = network} every other server's shared rows), most over the limit first, then most rooms
 * (R4); the picked owner's rooms; the servers not heard from for five minutes (R5). FREE puts one of this server's rooms
 * into grace as a lost room is (reason {@code freed}): its owner is told without the staff member's name (R7), and the
 * action log names the staff member.
 */
public final class RoomsAdmin {
    /** What a FREE came to. */
    public enum Free { FREED, GONE, GRACE, PLACED }

    /** Why a server's rows may not be forgotten (D456), or OK. */
    public enum Forget { OK, SELF, HEARD, UNKNOWN }

    private RoomsAdmin() {
    }

    /** One action from the tab, answered with the tab's data; false (and nothing sent) for a player who may not edit. */
    public static boolean apply(ServerPlayer player, AdminRoomsEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        MinecraftServer server = player.server;
        String note = "";
        switch (p.action()) {
            case OPEN -> RoomList.reload(server); // the other servers' rows read again; shown at the next refresh
            case FREE -> note = switch (free(player, p.owner(), new ChunkPos(p.x(), p.z()))) {
                case FREED -> "freed";
                case GONE -> "gone";
                case GRACE -> "grace";
                case PLACED -> "placed";
            };
            case REFRESH -> {
            }
        }
        ServerUtilsNetwork.sendTo(player, packet(server, p.owner(), note));
        return true;
    }

    // ------------------------------------------------------------------ the data

    public static AdminRoomsPacket packet(MinecraftServer server, UUID picked, String note) {
        RoomSettings s = RoomSettings.current();
        String self = Storage.currentServerId();
        long now = RoomLosses.now();
        List<RoomRow> rows = rows(server, self, s.network());
        List<AdminRoomsPacket.Owner> owners = keepPicked(owners(rows, s.limit(), id -> exempt(server, id, s.exemptGroup())), picked);
        int inGrace = (int) rows.stream().filter(r -> RoomRow.GRACE.equals(r.state())).count();
        List<AdminRoomsPacket.Stale> stale = new ArrayList<>();
        if (s.network()) {
            Map<String, Long> heard = RoomList.table().heard();
            for (String name : RoomList.stale(RoomList.table(), self, now)) {
                stale.add(new AdminRoomsPacket.Stale(name, now - heard.getOrDefault(name, now)));
            }
        }
        List<RoomsViewPacket.Room> rooms = new ArrayList<>();
        String pickedName = "";
        if (!AdminRoomsPacket.NONE.equals(picked)) {
            for (RoomRow r : rows) {
                if (r.owner().equals(picked)) {
                    rooms.add(room(r, self, now));
                    if (pickedName.isEmpty()) {
                        pickedName = r.ownerName();
                    }
                }
            }
            rooms.sort(RoomsPage.order(self));
        }
        boolean unread = s.network() && Storage.active() != null && !RoomList.read();
        return new AdminRoomsPacket(s.limit(), s.network(), unread, self, s.graceDays(), inGrace, List.copyOf(stale), owners, picked, pickedName, List.copyOf(rooms), note);
    }

    /** The rooms listed: this server's read live (when Compact Machines runs here), and with {@code network} every other server's rows. */
    static List<RoomRow> rows(MinecraftServer server, String self, boolean network) {
        List<RoomRow> out = new ArrayList<>(CompactMachines.present() ? RoomList.snapshot(server, self) : List.of());
        if (network) {
            for (RoomRow r : RoomList.table().rows()) {
                if (!r.server().equalsIgnoreCase(self) && !CompactMachines.NO_OWNER.equals(r.owner())) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    /**
     * Every owner in {@code rows}: their name (the first a server knew), the rooms that count, every room listed, on how many
     * servers, and whether exempt; most over the limit first, then most rooms counted, then by name (R4).
     */
    public static List<AdminRoomsPacket.Owner> owners(List<RoomRow> rows, int limit, Predicate<UUID> exempt) {
        Map<UUID, int[]> counts = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, Set<String>> servers = new LinkedHashMap<>();
        for (RoomRow r : rows) {
            if (r.owner() == null || CompactMachines.NO_OWNER.equals(r.owner())) {
                continue;
            }
            int[] c = counts.computeIfAbsent(r.owner(), k -> new int[2]);
            if (r.counts()) {
                c[0]++;
            }
            c[1]++;
            if (!r.ownerName().isEmpty()) {
                names.putIfAbsent(r.owner(), r.ownerName());
            }
            servers.computeIfAbsent(r.owner(), k -> new HashSet<>()).add(r.server().toLowerCase(Locale.ROOT));
        }
        List<AdminRoomsPacket.Owner> out = new ArrayList<>();
        counts.forEach((id, c) -> out.add(new AdminRoomsPacket.Owner(id, names.getOrDefault(id, ""), c[0], c[1], servers.get(id).size(), exempt.test(id))));
        out.sort(Comparator.<AdminRoomsPacket.Owner>comparingInt(o -> -o.over(limit))
                .thenComparingInt(o -> -o.count())
                .thenComparing(o -> o.name().isEmpty() ? "~" + o.id() : o.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** The picked owner moved within the first {@link AdminRoomsPacket#MAX_OWNERS} sent, so the tab can always show who is picked. */
    public static List<AdminRoomsPacket.Owner> keepPicked(List<AdminRoomsPacket.Owner> owners, UUID picked) {
        if (owners.size() <= AdminRoomsPacket.MAX_OWNERS) {
            return owners;
        }
        for (int i = AdminRoomsPacket.MAX_OWNERS; i < owners.size(); i++) {
            if (owners.get(i).id().equals(picked)) {
                List<AdminRoomsPacket.Owner> out = new ArrayList<>(owners);
                out.add(AdminRoomsPacket.MAX_OWNERS - 1, out.remove(i));
                return out;
            }
        }
        return owners;
    }

    /** Exempt as the limit sees it (D441): an operator, or a player online here in the exempt group (offline players' groups are not known). */
    public static boolean exempt(MinecraftServer server, UUID id, String group) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return RoomLimit.exempt(online.hasPermissions(Perms.OPERATOR_LEVEL), LuckPermsBridge.groupsOf(id), group);
        }
        return server.getPlayerList().isOp(new GameProfile(id, null));
    }

    /** A room as the tab shows it; {@code give} says whether FREE can be used here and if not why. */
    public static RoomsViewPacket.Room room(RoomRow r, String self, long now) {
        boolean grace = RoomRow.GRACE.equals(r.state());
        int give;
        if (grace) {
            give = RoomsViewPacket.GIVE_GRACE;
        } else if (!r.server().equalsIgnoreCase(self)) {
            give = RoomsViewPacket.GIVE_ELSEWHERE;
        } else if (r.machines() > 0) {
            give = RoomsViewPacket.GIVE_PLACED;
        } else {
            give = RoomsViewPacket.GIVE_OK;
        }
        return new RoomsViewPacket.Room(r.server(), r.x(), r.z(), r.size(), r.name(), r.machine(), r.machines(), grace, grace ? r.graceUntil() - now : 0, r.reason(), give);
    }

    // ------------------------------------------------------------------ forgetting a server (D456)

    /**
     * Whether {@code target}'s rows may be dropped: never this server's, nor a server heard from in the last
     * {@link RoomList#STALE_MS} (it would write them again), nor a name the table does not have.
     */
    public static Forget mayForget(String target, String self, RoomTable t, long now) {
        if (target.equalsIgnoreCase(self)) {
            return Forget.SELF;
        }
        Long heard = null;
        for (Map.Entry<String, Long> e : t.heard().entrySet()) {
            if (e.getKey().equalsIgnoreCase(target)) {
                heard = e.getValue();
            }
        }
        boolean rows = t.rows().stream().anyMatch(r -> r.server().equalsIgnoreCase(target));
        if (heard == null && !rows) {
            return Forget.UNKNOWN;
        }
        return heard != null && now - heard < RoomList.STALE_MS ? Forget.HEARD : Forget.OK;
    }

    /**
     * {@code /su rooms forget <server>} (D456): drops a renamed or retired server's rows from the shared tables, tells
     * the other servers, and writes the action log. The answer comes once the database has done it.
     */
    public static int forget(net.minecraft.commands.CommandSourceStack src, String target) {
        MinecraftServer server = src.getServer();
        Storage s = Storage.active();
        if (s == null) {
            src.sendFailure(net.minecraft.network.chat.Component.translatable("message.bsp_server_utilities.rooms.forget_storage"));
            return 0;
        }
        String self = Storage.currentServerId();
        if (target.equalsIgnoreCase(self)) {
            src.sendFailure(net.minecraft.network.chat.Component.translatable("message.bsp_server_utilities.rooms.forget_self", target));
            return 0;
        }
        ServerPlayer by = src.getPlayer();
        long now = RoomLosses.now();
        s.submit(() -> {
            // judged on the table as the database holds it now, not this server's copy of a minute ago
            Forget why = mayForget(target, self, s.backend().loadRooms(), now);
            if (why != Forget.OK) {
                String key = "message.bsp_server_utilities.rooms.forget_" + why.name().toLowerCase(Locale.ROOT); // built first: tools/check_lang.py reads a joined literal as a missing key
                server.execute(() -> src.sendFailure(net.minecraft.network.chat.Component.translatable(key, target)));
                return;
            }
            int n = s.backend().forgetRooms(target);
            s.publish(RoomList.KIND, self); // the others read the table again
            server.execute(() -> {
                RoomList.reload(server);
                String text = "forgot the Compact Machine rooms of server " + target + " (" + n + " rooms): it no longer counts";
                ActionLog.record(server, by != null
                        ? ActionLog.entryFor(by, ActionLog.tracked(by).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.ROOM, text)
                        : ActionLog.entryForServer("Console", LogEntry.ROOM, text));
                src.sendSuccess(() -> net.minecraft.network.chat.Component.translatable("message.bsp_server_utilities.rooms.forgot", target, n), true);
            });
        });
        return 1;
    }

    // ------------------------------------------------------------------ FREE

    /**
     * Frees one of this server's rooms (D448): it must still be {@code owner}'s, not in grace, and have no placed machine
     * bound to it (a placed machine would bring it straight back). It goes into grace with the reason {@code freed}, its
     * owner is told without the staff member's name (R7), and the action log names the staff member.
     */
    public static Free free(ServerPlayer staff, UUID owner, ChunkPos room) {
        MinecraftServer server = staff.server;
        Optional<CompactMachines.Room> read = CompactMachines.room(server, room);
        if (read.isEmpty() || read.get().freed() || !owner.equals(read.get().owner())) {
            return Free.GONE;
        }
        if (RoomGrace.get(server).contains(room)) {
            return Free.GRACE;
        }
        if (!CompactMachines.machinesFor(server, room).isEmpty() || !RoomLosses.lose(server, room, "freed")) {
            return Free.PLACED;
        }
        String name = RoomList.ownerName(server, owner);
        Staff.Role role = ActionLog.tracked(staff).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR));
        ActionLog.record(server, ActionLog.entryFor(staff, role, LogEntry.ROOM, "freed " + (name.isEmpty() ? owner.toString() : name) + "'s Compact Machine room at "
                + room.x + ", " + room.z + " on " + Storage.currentServerId() + " (" + read.get().size() + " blocks inside); cleared in " + RoomSettings.current().graceDays() + " days"));
        return Free.FREED;
    }
}
