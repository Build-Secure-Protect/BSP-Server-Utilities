package com.mrgregles.bsp_server_utilities.admin;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.StaffData;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogData;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.network.AdminLogEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminLogPacket;
import com.mrgregles.bsp_server_utilities.network.LogViewPacket;
import com.mrgregles.bsp_server_utilities.network.AdminStaffEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminStaffPacket;
import com.mrgregles.bsp_server_utilities.network.StaffViewPacket;
import com.mrgregles.bsp_server_utilities.network.AdminRulesPacket;
import com.mrgregles.bsp_server_utilities.network.AdminViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The admin panel's server side (owner decisions D6, D34 to D37): {@code /su} opens it; every action from the RULES tab
 * is checked here (only players who may edit, D3), applied through {@link RulesService} (so the popup, the shared storage
 * and the other servers follow), and answered with a fresh view carrying the agreement count of the network. The STAFF
 * LOG and AFK tabs are answered the same way.
 */
public final class AdminPanel {
    public static final String NOTE_SAVED = "saved", NOTE_ADDED = "added", NOTE_DELETED = "deleted", NOTE_FULL = "full",
            NOTE_ASKED = "asked_again", NOTE_REFUSED = "refused", NOTE_NO_TITLE = "no_title", NOTE_NONE = "";

    private AdminPanel() {
    }

    /** Opens the panel for an admin. */
    public static void open(ServerPlayer player) {
        send(player, true, NOTE_NONE);
    }

    /** Sends the view, with the agreement count once it is known. */
    public static void send(ServerPlayer player, boolean open, String note) {
        MinecraftServer server = player.server;
        RulesService.countAgreed(server, n -> ServerUtilsNetwork.sendTo(player, AdminViewPacket.of(RulesService.rules(server), open, n, note)));
    }

    /** Applies an action from the RULES tab and answers with a fresh view; returns the note it carried (tests). */
    public static String apply(ServerPlayer player, AdminRulesPacket p) {
        if (!Perms.canEdit(player)) {
            return NOTE_REFUSED;
        }
        MinecraftServer server = player.server;
        RuleSet set = RulesService.rules(server);
        int n = set.rules().size();
        String note = switch (p.action()) {
            case REFRESH -> NOTE_NONE;
            case SET -> {
                if (p.index() < 0 || p.index() >= n) {
                    yield NOTE_REFUSED;
                }
                if (p.a().isBlank()) {
                    yield NOTE_NO_TITLE; // a rule without a title would be dropped: keep the old one
                }
                RulesService.edit(server, s -> s.withRules(replace(s.rules(), p.index(), new RuleSet.Rule(p.a(), p.b()))));
                yield NOTE_SAVED;
            }
            case ADD -> {
                if (n >= RuleSet.MAX_RULES) {
                    yield NOTE_FULL;
                }
                RuleSet next = RulesService.edit(server, s -> {
                    List<RuleSet.Rule> rules = new ArrayList<>(s.rules());
                    rules.add(new RuleSet.Rule(p.a(), p.b()));
                    return s.withRules(rules);
                });
                yield next.rules().size() > n ? NOTE_ADDED : NOTE_REFUSED;
            }
            case DELETE -> {
                if (p.index() < 0 || p.index() >= n) {
                    yield NOTE_REFUSED;
                }
                RulesService.edit(server, s -> {
                    List<RuleSet.Rule> rules = new ArrayList<>(s.rules());
                    rules.remove(p.index());
                    return s.withRules(rules);
                });
                yield NOTE_DELETED;
            }
            case MOVE -> {
                int to = p.index() + ("-1".equals(p.a()) ? -1 : 1);
                if (p.index() < 0 || p.index() >= n || to < 0 || to >= n) {
                    yield NOTE_REFUSED;
                }
                RulesService.edit(server, s -> {
                    List<RuleSet.Rule> rules = new ArrayList<>(s.rules());
                    rules.add(to, rules.remove(p.index()));
                    return s.withRules(rules);
                });
                yield NOTE_SAVED;
            }
            case TEXTS -> {
                RulesService.edit(server, s -> s.withTexts(p.a(), p.b(), p.c()));
                yield NOTE_SAVED;
            }
            case ASK_AGAIN -> {
                RulesService.askEveryoneAgain(server);
                yield NOTE_ASKED;
            }
        };
        send(player, false, note);
        return note;
    }

    /** The STAFF tab: refresh, or the groups as the admin left them (D57); answered with the tab's data. */
    public static boolean applyStaff(ServerPlayer player, AdminStaffEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        if (!p.refresh()) {
            StaffDirectory.setGroups(player.server, p.roles(), p.operators());
        }
        sendStaff(player);
        return true;
    }

    public static void sendStaff(ServerPlayer player) {
        MinecraftServer server = player.server;
        ServerUtilsNetwork.sendTo(player, AdminStaffPacket.of(StaffData.get(server).groups(), LuckPermsBridge.groupNames(),
                StaffViewPacket.of(StaffDirectory.roster(server), System.currentTimeMillis())));
    }

    /** The LOG tab: its filters, and a switch turned when {@code root} is set (D29: the change is an entry); answered with the tab's data. */
    public static boolean applyLog(ServerPlayer player, AdminLogEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        if (!p.root().isBlank()) {
            ActionLog.setSwitch(player.server, player, p.root(), p.on());
        }
        ServerUtilsNetwork.sendTo(player, logPacket(player.server, p.server(), p.kind()));
        return true;
    }

    /**
     * The LOG tab's data: a switch for every command this server knows, every command seen in the log and every switch
     * stored (private messages last and marked, D30), the servers in the log, and the newest entries in full text that
     * match the filters.
     */
    public static AdminLogPacket logPacket(MinecraftServer server, String onServer, String kind) {
        LogData data = LogData.get(server);
        TreeSet<String> roots = new TreeSet<>(data.switches().keySet());
        server.getCommands().getDispatcher().getRoot().getChildren().forEach(n -> roots.add(LogText.root(n.getName())));
        TreeSet<String> servers = new TreeSet<>();
        servers.add(com.mrgregles.bsp_server_utilities.storage.Storage.currentServerId());
        List<LogEntry> all = data.entries();
        for (LogEntry e : all) {
            servers.add(e.server());
            if (e.kind().equals(LogEntry.CMD)) {
                roots.add(LogText.root(e.text()));
            }
        }
        roots.removeIf(r -> r.isBlank() || r.length() > 64);
        Map<String, AdminLogPacket.Switch> open = new TreeMap<>(), never = new TreeMap<>();
        for (String r : roots) {
            if (LogText.PRIVATE.contains(r)) {
                never.put(r, new AdminLogPacket.Switch(r, false, true));
            } else {
                open.put(r, new AdminLogPacket.Switch(r, data.switches().getOrDefault(r, true), false));
            }
        }
        List<AdminLogPacket.Switch> switches = new ArrayList<>(open.values());
        switches.addAll(never.values());
        if (switches.size() > AdminLogPacket.MAX_SWITCHES) {
            switches = switches.subList(0, AdminLogPacket.MAX_SWITCHES);
        }
        long now = System.currentTimeMillis();
        List<LogViewPacket.Row> entries = new ArrayList<>();
        for (LogEntry e : all) {
            if (entries.size() >= AdminLogPacket.MAX_ENTRIES) {
                break;
            }
            if ((onServer.isEmpty() || e.server().equals(onServer)) && (kind.isEmpty() || e.kind().equals(kind))) {
                entries.add(LogViewPacket.row(e, e.text(), AdminLogPacket.MAX_TEXT, now));
            }
        }
        List<String> serverList = new ArrayList<>(servers);
        return new AdminLogPacket(switches, serverList.subList(0, Math.min(64, serverList.size())), onServer, kind, entries);
    }

    /** The AFK tab: refresh, or the settings as the admin left them (D76); answered with the tab's data. */
    public static boolean applyAfk(ServerPlayer player, com.mrgregles.bsp_server_utilities.network.AdminAfkEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        if (!p.refresh()) {
            com.mrgregles.bsp_server_utilities.afk.AfkService.setSettings(player.server, p.settings());
        }
        ServerUtilsNetwork.sendTo(player, afkPacket(player.server));
        return true;
    }

    /** The AFK tab's data: the settings, everyone AFK on the network (staff in their role colour), then staff away by {@code /su away} (D85). */
    public static com.mrgregles.bsp_server_utilities.network.AdminAfkPacket afkPacket(MinecraftServer server) {
        Map<java.util.UUID, StaffDirectory.Member> staff = new java.util.HashMap<>();
        for (StaffDirectory.Member m : StaffDirectory.roster(server)) {
            staff.put(m.id(), m);
        }
        List<com.mrgregles.bsp_server_utilities.network.AdminAfkPacket.Row> rows = new ArrayList<>();
        java.util.Set<java.util.UUID> afk = new java.util.HashSet<>();
        for (com.mrgregles.bsp_server_utilities.afk.AfkService.Row r : com.mrgregles.bsp_server_utilities.afk.AfkService.afkNow(server)) {
            afk.add(r.id());
            StaffDirectory.Member m = staff.get(r.id());
            rows.add(new com.mrgregles.bsp_server_utilities.network.AdminAfkPacket.Row(r.name(), r.server(), r.afkForMs(), r.exempt() || m != null,
                    m == null ? 0x9AA3B5 : m.role().colour(), false));
        }
        for (StaffDirectory.Member m : staff.values()) {
            if (m.state() == StaffDirectory.State.AWAY && !afk.contains(m.id())) {
                rows.add(new com.mrgregles.bsp_server_utilities.network.AdminAfkPacket.Row(m.name(), m.server(), 0, true, m.role().colour(), true));
            }
        }
        if (rows.size() > com.mrgregles.bsp_server_utilities.network.AdminAfkPacket.MAX_ROWS) {
            rows = rows.subList(0, com.mrgregles.bsp_server_utilities.network.AdminAfkPacket.MAX_ROWS);
        }
        return new com.mrgregles.bsp_server_utilities.network.AdminAfkPacket(com.mrgregles.bsp_server_utilities.afk.AfkService.settings(server), List.copyOf(rows));
    }

    private static List<RuleSet.Rule> replace(List<RuleSet.Rule> rules, int index, RuleSet.Rule rule) {
        List<RuleSet.Rule> out = new ArrayList<>(rules);
        if (index >= 0 && index < out.size()) {
            out.set(index, rule);
        }
        return out;
    }
}
