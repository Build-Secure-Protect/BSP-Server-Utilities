package com.mrgregles.bsp_server_utilities.chunks;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.network.AdminChunksEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminChunksPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The CHUNKS tab's server side (owner decisions D92 to D96): only players who may edit act or see it; every action is
 * answered with the tab's data. Machines are tickets grouped by dimension, mod and owner (one machine may hold several
 * chunks); BSP-Core's are left out.
 */
public final class ChunkAdmin {
    private ChunkAdmin() {
    }

    public static boolean apply(ServerPlayer player, AdminChunksEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        MinecraftServer server = player.server;
        switch (p.action()) {
            case SETTINGS -> ChunkService.setSettings(server, p.settings());
            case RESTORE_PLAYER -> uuid(p.arg(), id -> ChunkService.restoreNow(server, id));
            case UNLOAD_PLAYER -> uuid(p.arg(), id -> ChunkService.unloadNow(server, id));
            case UNLOAD_MACHINE -> forMachine(server, p.arg(), false);
            case RESTORE_MACHINE -> forMachine(server, p.arg(), true);
            case ADD_ALWAYS -> addAlways(server, p.arg());
            case REFRESH -> {
            }
        }
        ChunkService.drainRestores(server, 64);
        ServerUtilsNetwork.sendTo(player, packet(server));
        return true;
    }

    private static void uuid(String s, java.util.function.Consumer<UUID> then) {
        try {
            then.accept(UUID.fromString(s));
        } catch (IllegalArgumentException ignored) {
            // not a player
        }
    }

    private static void addAlways(MinecraftServer server, String name) {
        if (server.getProfileCache() == null) {
            return;
        }
        server.getProfileCache().get(name.strip()).ifPresent(profile -> {
            ChunkSettings s = ChunkService.settings(server);
            List<ChunkSettings.Player> always = new ArrayList<>(s.alwaysLoaded());
            always.add(new ChunkSettings.Player(profile.getId(), profile.getName()));
            ChunkService.setSettings(server, new ChunkSettings(s.enabled(), s.minutes(), s.exemptMods(), always, s.revision()));
        });
    }

    /** A machine's key: the ticket key without the chunk and ticking. */
    public static String machineKey(ChunkTickets.Ticket t) {
        return t.dimension() + "|" + t.modId() + "|" + (t.entity() ? "e" : "b") + "|" + t.owner();
    }

    private static void forMachine(MinecraftServer server, String key, boolean restore) {
        if (restore) {
            for (ChunkData.Suspended s : List.copyOf(ChunkData.get(server).suspended().values())) {
                if (machineKey(s.ticket()).equals(key)) {
                    ChunkService.restoreTicket(server, s.ticket().key());
                }
            }
            return;
        }
        for (ChunkService.Live l : ChunkService.live(server)) {
            if (l.player() == null && machineKey(l.ticket()).equals(key)) {
                ChunkService.unloadTicket(server, l.ticket().key());
            }
        }
    }

    private static final class Machine {
        final String key, modId, where;
        @Nullable
        final UUID player;
        int loaded, unloaded;
        String how = "";

        Machine(String key, @Nullable UUID player, String modId, String where) {
            this.key = key;
            this.player = player;
            this.modId = modId;
            this.where = where;
        }
    }

    private static String where(ChunkTickets.Ticket t) {
        String dim = t.dimension().startsWith("minecraft:") ? t.dimension().substring(10).replace('_', ' ') : t.dimension();
        return dim + " " + (t.entity() ? "entity" : t.owner().replace(',', ' '));
    }

    public static AdminChunksPacket packet(MinecraftServer server) {
        ChunkSettings settings = ChunkService.settings(server);
        Map<String, Machine> machines = new LinkedHashMap<>();
        TreeSet<String> mods = new TreeSet<>(settings.exemptMods());
        for (ChunkService.Live l : ChunkService.live(server)) {
            ChunkTickets.Ticket t = l.ticket();
            if (ChunkTickets.BSP_CORE.equals(t.modId())) {
                continue;
            }
            mods.add(t.modId());
            Machine m = machines.computeIfAbsent(machineKey(t), k -> new Machine(k, l.player(), t.modId(), where(t)));
            m.loaded++;
            if (l.how() != null) {
                m.how = l.how().name().toLowerCase(java.util.Locale.ROOT);
            }
        }
        for (ChunkData.Suspended s : ChunkData.get(server).suspended().values()) {
            ChunkTickets.Ticket t = s.ticket();
            mods.add(t.modId());
            machines.computeIfAbsent(machineKey(t), k -> new Machine(k, s.player(), t.modId(), where(t))).unloaded++;
        }
        Map<UUID, int[]> counts = new LinkedHashMap<>();
        Map<UUID, TreeMap<String, Integer>> byMod = new LinkedHashMap<>();
        List<AdminChunksPacket.MachineRow> rows = new ArrayList<>();
        for (Machine m : machines.values()) {
            UUID id = m.player == null ? AdminChunksPacket.NOBODY : m.player;
            rows.add(new AdminChunksPacket.MachineRow(m.key, id, m.modId, m.where, m.loaded, m.unloaded, m.how));
            if (m.player != null) {
                int[] c = counts.computeIfAbsent(m.player, k -> new int[2]);
                c[0] += m.loaded;
                c[1] += m.unloaded;
                byMod.computeIfAbsent(m.player, k -> new TreeMap<>()).merge(m.modId, m.loaded + m.unloaded, Integer::sum);
            }
        }
        for (ChunkSettings.Player p : settings.alwaysLoaded()) {
            counts.putIfAbsent(p.id(), new int[2]);
        }
        List<AdminChunksPacket.PlayerRow> players = new ArrayList<>();
        counts.forEach((id, c) -> {
            String name = server.getProfileCache() == null ? "" : server.getProfileCache().get(id).map(g -> g.getName()).orElse("");
            if (name.isEmpty()) {
                name = settings.alwaysLoaded().stream().filter(p -> p.id().equals(id)).map(ChunkSettings.Player::name).findFirst().orElse(id.toString().substring(0, 8));
            }
            StringBuilder m = new StringBuilder();
            byMod.getOrDefault(id, new TreeMap<>()).forEach((mod, n) -> m.append(m.length() == 0 ? "" : " · ").append(mod).append(' ').append(n));
            long away = ChunkService.awayMs(server, id);
            players.add(new AdminChunksPacket.PlayerRow(id, name, away == 0, away, c[0], c[1], m.length() > 250 ? m.substring(0, 250) : m.toString()));
        });
        players.sort(java.util.Comparator.comparing((AdminChunksPacket.PlayerRow p) -> !p.online()).thenComparing(p -> p.name().toLowerCase(java.util.Locale.ROOT)));
        return new AdminChunksPacket(settings, List.copyOf(mods).subList(0, Math.min(mods.size(), AdminChunksPacket.MAX_ROWS)),
                players.subList(0, Math.min(players.size(), AdminChunksPacket.MAX_ROWS)), rows.subList(0, Math.min(rows.size(), AdminChunksPacket.MAX_ROWS)));
    }
}
