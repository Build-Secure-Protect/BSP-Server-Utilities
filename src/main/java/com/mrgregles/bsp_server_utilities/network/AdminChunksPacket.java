package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.chunks.ChunkSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: the CHUNKS tab's data (owner decisions D92 to D96): the network's settings, the mods holding chunks
 * on this server (for the exempt chips), every player whose machines hold chunks here (where they are, how many chunks
 * are loaded and unloaded, by which mods), and every machine (by player; a zero UUID for machines with no owner found).
 */
public record AdminChunksPacket(ChunkSettings settings, List<String> mods, List<PlayerRow> players, List<MachineRow> machines) {
    public static final UUID NOBODY = new UUID(0, 0);
    public static final int MAX_ROWS = 400;

    public record PlayerRow(UUID id, String name, boolean online, long awayMs, int loaded, int unloaded, String mods) {
    }

    /** One machine: its key (dimension, mod, owner), whose, the mod, where, chunks loaded and unloaded, how its owner was found. */
    public record MachineRow(String key, UUID player, String modId, String where, int loaded, int unloaded, String how) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(settings.encode(), 8192);
        buf.writeVarInt(mods.size());
        mods.forEach(m -> buf.writeUtf(m, 64));
        buf.writeVarInt(players.size());
        for (PlayerRow p : players) {
            buf.writeUUID(p.id());
            buf.writeUtf(p.name(), 32);
            buf.writeBoolean(p.online());
            buf.writeVarLong(p.awayMs());
            buf.writeVarInt(p.loaded());
            buf.writeVarInt(p.unloaded());
            buf.writeUtf(p.mods(), 256);
        }
        buf.writeVarInt(machines.size());
        for (MachineRow m : machines) {
            buf.writeUtf(m.key(), 256);
            buf.writeUUID(m.player());
            buf.writeUtf(m.modId(), 64);
            buf.writeUtf(m.where(), 96);
            buf.writeVarInt(m.loaded());
            buf.writeVarInt(m.unloaded());
            buf.writeUtf(m.how(), 16);
        }
    }

    public static AdminChunksPacket decode(FriendlyByteBuf buf) {
        ChunkSettings s = ChunkSettings.decode(buf.readUtf(8192));
        int n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<String> mods = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            mods.add(buf.readUtf(64));
        }
        n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<PlayerRow> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            players.add(new PlayerRow(buf.readUUID(), buf.readUtf(32), buf.readBoolean(), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256)));
        }
        n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<MachineRow> machines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            machines.add(new MachineRow(buf.readUtf(256), buf.readUUID(), buf.readUtf(64), buf.readUtf(96), buf.readVarInt(), buf.readVarInt(), buf.readUtf(16)));
        }
        return new AdminChunksPacket(s == null ? ChunkSettings.DEFAULTS : s, mods, players, machines);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.chunks(this));
        ctx.get().setPacketHandled(true);
    }
}
