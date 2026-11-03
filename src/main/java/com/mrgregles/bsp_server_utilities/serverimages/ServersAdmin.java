package com.mrgregles.bsp_server_utilities.serverimages;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.network.AdminServersEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminServersPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The SERVERS tab's server side (owner decisions D199 to D204): only players who may edit; VIEW reads the folder and
 * sends its usable files for the previews; REFRESH plans and, when it only adds or is confirmed, applies. Every action is
 * answered with the tab's data.
 */
public final class ServersAdmin {
    private static BiConsumer<ServerPlayer, Object> sender = ServerUtilsNetwork::sendTo;

    private ServersAdmin() {
    }

    public static boolean apply(ServerPlayer player, AdminServersEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        MinecraftServer server = player.server;
        ServerImages.Plan plan = null;
        if (p.action() == AdminServersEditPacket.Action.REFRESH) {
            plan = ServerImages.refresh(server, player, player.getGameProfile().getName(), p.confirmed());
        }
        List<ServerImages.Read> files = ServerImages.scan();
        ServerImages.sendFolderImages(player, files);
        sender.accept(player, packet(server, files, plan));
        return true;
    }

    /** The tab's data: every name in one alphabetical list with its mark (D202), and what a refresh did or would do. */
    public static AdminServersPacket packet(MinecraftServer server, List<ServerImages.Read> files, ServerImages.Plan plan) {
        String here = Storage.currentServerId();
        List<String> running = StaffDirectory.knownServers();
        Set<String> heard = StaffDirectory.heardServers();
        ServerImageSet set = ServerImages.set(server);
        Map<String, String> names = new LinkedHashMap<>();
        set.entries().forEach(e -> names.putIfAbsent(lower(e.name()), e.name()));
        files.forEach(r -> names.putIfAbsent(lower(r.info().server()), r.info().server()));
        names.putIfAbsent(lower(here), here);
        running.forEach(s -> names.putIfAbsent(lower(s), s));
        List<AdminServersPacket.Row> rows = new ArrayList<>();
        for (String name : names.values()) {
            AdminServersPacket.Mark mark = name.equalsIgnoreCase(here) ? AdminServersPacket.Mark.HERE
                    : contains(running, name) ? AdminServersPacket.Mark.NONE
                    : contains(heard, name) ? AdminServersPacket.Mark.OFF : AdminServersPacket.Mark.UNKNOWN;
            ServerImageSet.Entry e = set.of(name);
            ServerImages.FileInfo f = null;
            for (ServerImages.Read r : files) {
                if (r.info().server().equalsIgnoreCase(name) && (f == null || !f.usable())) {
                    f = r.info();
                }
            }
            rows.add(new AdminServersPacket.Row(name, mark,
                    e == null ? "" : e.id(), e == null ? "" : e.file(), e == null ? 0 : e.width(), e == null ? 0 : e.height(), e == null ? 0 : e.bytes(),
                    f == null ? "" : f.file(), f == null ? 0 : f.width(), f == null ? 0 : f.height(), f == null ? 0 : f.bytes(), f == null ? "" : f.id(), f == null ? "" : f.problem()));
        }
        rows.sort(Comparator.comparing(r -> lower(r.name())));
        AdminServersPacket.Outcome outcome = plan == null ? AdminServersPacket.Outcome.NONE
                : plan.applied() ? AdminServersPacket.Outcome.DONE
                : plan.changes() ? AdminServersPacket.Outcome.PENDING : AdminServersPacket.Outcome.NOTHING;
        return new AdminServersPacket(here, Storage.active() != null, files.size(), rows, outcome,
                plan == null ? List.of() : plan.added(), plan == null ? List.of() : plan.replaced(), plan == null ? List.of() : plan.removed());
    }

    private static boolean contains(java.util.Collection<String> names, String name) {
        return names.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Where the tab's data goes (tests; null: to the player). */
    public static void useForTests(BiConsumer<ServerPlayer, Object> packets) {
        sender = packets != null ? packets : ServerUtilsNetwork::sendTo;
    }
}
