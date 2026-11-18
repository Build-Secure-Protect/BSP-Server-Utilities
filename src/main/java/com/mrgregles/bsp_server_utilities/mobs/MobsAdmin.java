package com.mrgregles.bsp_server_utilities.mobs;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.network.AdminMobsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminMobsPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/** The MOBS tab's server side (SU-65): only players who may edit (admins and operators); every action answered with the tab's data. */
public final class MobsAdmin {
    private MobsAdmin() {
    }

    public static boolean apply(ServerPlayer player, AdminMobsEditPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        MinecraftServer server = player.server;
        String here = Storage.currentServerId();
        int removed = AdminMobsPacket.NO_NOTE, cleared = AdminMobsPacket.NO_NOTE;
        MobSettings.Drops d = MobLimits.settings(server).drops();
        switch (p.action()) {
            case OPEN, REFRESH -> MobLimits.askNetwork();
            case EVERY -> MobLimits.setSettings(server, MobLimits.settings(server).withEvery(p.count()));
            case DIM -> MobLimits.setSettings(server, MobLimits.settings(server).withDim(p.dim(), p.count() < 0 ? null : p.count()));
            case CHUNK -> MobLimits.setSettings(server, MobLimits.settings(server).withChunk(p.server(), p.dim(), p.x(), p.z(), p.count() < 0 ? null : p.count()));
            case NEVER -> MobLimits.setSettings(server, MobLimits.settings(server).withNever(p.dim(), p.count() > 0));
            case HERE -> {
                ServerLevel level = player.serverLevel();
                ChunkPos c = player.chunkPosition();
                MobLimits.addChunk(server, here, MobLimits.dimId(level), c.x, c.z, MobLimits.count(level, c));
            }
            case LIMIT -> MobLimits.addChunk(server, p.server(), p.dim(), p.x(), p.z(), p.count());
            case ITEMS -> MobLimits.setSettings(server, MobLimits.settings(server).withDrops(new MobSettings.Drops(p.count(), d.arrows(), d.minutes())));
            case ARROWS -> MobLimits.setSettings(server, MobLimits.settings(server).withDrops(new MobSettings.Drops(d.items(), p.count(), d.minutes())));
            case MINUTES -> MobLimits.setSettings(server, MobLimits.settings(server).withDrops(new MobSettings.Drops(d.items(), d.arrows(), p.count())));
            case CLEAR -> {
                if (p.server().equals(here)) {
                    cleared = Crowding.clearNow(player, p.dim(), p.x(), p.z());
                }
            }
            case REMOVE -> {
                if (p.server().equals(here)) {
                    removed = MobLimits.removeExtras(player, p.dim(), p.x(), p.z());
                }
            }
        }
        ServerUtilsNetwork.sendTo(player, packet(server, removed, cleared));
        return true;
    }

    public static AdminMobsPacket packet(MinecraftServer server, int removed, int cleared) {
        List<AdminMobsPacket.Row> rows = new ArrayList<>();
        for (MobLimits.Seen s : MobLimits.networkFullest(server)) {
            MobLimits.Row r = s.row();
            rows.add(new AdminMobsPacket.Row(r.server(), r.dim(), r.x(), r.z(), r.count(), r.limit(), r.from(), r.owner(), r.types(), s.ageMs()));
        }
        List<AdminMobsPacket.DropRow> drops = new ArrayList<>();
        for (Crowding.Seen s : Crowding.networkFullest(server)) {
            Crowding.Row r = s.row();
            drops.add(new AdminMobsPacket.DropRow(r.server(), r.dim(), r.x(), r.z(), r.items(), r.arrows(), r.kept(), r.itemsOverMs(), r.arrowsOverMs(), r.owner(), s.ageMs()));
        }
        long[] hour = Crowding.lastHour();
        return new AdminMobsPacket(MobLimits.settings(server), Storage.currentServerId(), rows, MobLimits.networkDims(server), removed, drops, hour[0], hour[1], hour[2], cleared);
    }
}
