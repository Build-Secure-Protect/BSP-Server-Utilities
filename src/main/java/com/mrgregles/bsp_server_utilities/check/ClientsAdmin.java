package com.mrgregles.bsp_server_utilities.check;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.network.AdminClientsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminClientsPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The CLIENTS tab's server side (owner decisions D130, D134 to D141): only players who may edit; every action answered
 * with the tab's data, including the player this admin has picked.
 */
public final class ClientsAdmin {
    private static final Map<UUID, UUID> PICKED = new ConcurrentHashMap<>();

    private ClientsAdmin() {
    }

    public static boolean apply(ServerPlayer player, AdminClientsEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        MinecraftServer server = player.server;
        String note = "";
        switch (p.action()) {
            case SELECT -> PICKED.put(player.getUUID(), p.target());
            case KICK -> note = ClientCheckService.kickByStaff(server, player, p.target(), p.arg()) ? "kicked" : "not_found";
            case BLOCK_MOD -> {
                String id = p.arg().strip();
                if (!id.isEmpty()) {
                    ClientCheckService.block(server, new CheckRules.Entry(CheckRules.Kind.MOD, id, ""));
                    note = "blocked";
                }
            }
            case BLOCK_PACK -> {
                String sha = p.arg().strip();
                if (sha.matches("[0-9a-fA-F]{40}")) {
                    ClientCheckService.block(server, new CheckRules.Entry(CheckRules.Kind.PACK, sha, p.arg2()));
                    note = "blocked";
                }
            }
            case RULES -> {
                CheckRules r = CheckRules.decode(p.arg());
                if (r != null) {
                    ClientCheckService.setRules(server, r);
                    note = "saved";
                }
            }
            case USE_OWN -> ClientCheckService.setUseOwn(server, Boolean.parseBoolean(p.arg()));
            case TRUST -> note = ClientCheckService.trust(server, player) ? "trusted" : "no_report";
            case EXPORT -> note = ClientCheckService.exportFile(server) != null ? "exported" : "no_setup";
            case REFRESH -> {
            }
        }
        ServerUtilsNetwork.sendTo(player, packet(server, player.getUUID(), note));
        return true;
    }

    public static AdminClientsPacket packet(MinecraftServer server, UUID admin, String note) {
        TrustedSetup t = ClientCheckService.setup(server);
        AdminClientsPacket.Setup setup = t == null ? null : new AdminClientsPacket.Setup(t.takenAtMs(), t.takenBy(), t.mods().size(), t.packs().size(), t.shader());
        List<AdminClientsPacket.PlayerRow> rows = new ArrayList<>();
        for (ClientCheckService.Row r : ClientCheckService.players(server)) {
            Verdict v = r.held().verdict();
            rows.add(new AdminClientsPacket.PlayerRow(r.held().id(), r.held().name(), r.server(), v.flagCount(), !v.refuse().isEmpty() && !v.exempt(), v.exempt()));
        }
        List<AdminClientsPacket.Refused> refused = new ArrayList<>();
        for (ClientCheckService.Refusal r : ClientCheckService.refusedToday()) {
            refused.add(new AdminClientsPacket.Refused(r.atMs(), r.server(), r.name(), r.reason()));
        }
        AdminClientsPacket.Detail detail = null;
        UUID picked = PICKED.get(admin);
        ClientCheckService.Row row = picked == null ? null : ClientCheckService.find(picked);
        if (row != null) {
            ClientCheckService.Held h = row.held();
            detail = new AdminClientsPacket.Detail(h.id(), h.name(), row.server(), h.atMs(), h.report(), h.verdict(), t != null);
        }
        return new AdminClientsPacket(Storage.currentServerId(), ClientCheckService.useOwn(server), ClientCheckService.rules(server), setup, rows, refused, detail, note);
    }

    public static void forget(UUID admin) {
        PICKED.remove(admin);
    }
}
