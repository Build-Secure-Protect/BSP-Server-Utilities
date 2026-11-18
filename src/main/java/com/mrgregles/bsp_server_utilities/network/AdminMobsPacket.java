package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.mobs.MobPlan;
import com.mrgregles.bsp_server_utilities.mobs.MobSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the MOBS tab's data (SU-65, D480 to D486): the network's mob limits, this server's name, the fullest
 * chunks on every server (how long ago each server was heard from), every dimension on the network, and how many mobs
 * the admin's last REMOVE EXTRAS took ({@link #NO_NOTE} when there is nothing to say).
 */
public record AdminMobsPacket(MobSettings settings, String here, List<Row> rows, List<String> dims, int removed, List<DropRow> drops, long clears, long clearedItems,
                              long clearedArrows, int cleared) {
    public static final int NO_NOTE = -1, MAX_ROWS = 16, MAX_DIMS = 128, MAX_TYPES = 16;

    /** One chunk in the list. */
    public record Row(String server, String dim, int x, int z, int count, int limit, MobSettings.From from, String owner, List<MobPlan.TypeCount> types, long ageMs) {
        public Row {
            types = List.copyOf(types);
        }
    }

    /** One chunk on the DROPS &amp; ARROWS page: items, arrows, how many items are kept, how long each kind has been over (0: not). */
    public record DropRow(String server, String dim, int x, int z, int items, int arrows, int kept, long itemsOverMs, long arrowsOverMs, String owner, long ageMs) {
    }

    public AdminMobsPacket {
        rows = List.copyOf(rows);
        dims = List.copyOf(dims);
        drops = List.copyOf(drops);
    }

    private static String cut(String s, int max) {
        return RoomsViewPacket.cut(s, max);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(settings.encode(), 262_144);
        buf.writeUtf(cut(here, 64), 64);
        List<Row> r = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;
        buf.writeVarInt(r.size());
        for (Row row : r) {
            buf.writeUtf(cut(row.server(), 64), 64);
            buf.writeUtf(cut(row.dim(), 128), 128);
            buf.writeVarInt(row.x());
            buf.writeVarInt(row.z());
            buf.writeVarInt(row.count());
            buf.writeVarInt(row.limit());
            buf.writeEnum(row.from());
            buf.writeUtf(cut(row.owner(), 32), 32);
            List<MobPlan.TypeCount> t = row.types().size() > MAX_TYPES ? row.types().subList(0, MAX_TYPES) : row.types();
            buf.writeVarInt(t.size());
            for (MobPlan.TypeCount c : t) {
                buf.writeUtf(cut(c.type(), 128), 128);
                buf.writeVarInt(c.count());
                buf.writeVarInt(c.kept());
            }
            buf.writeVarLong(row.ageMs());
        }
        List<String> d = dims.size() > MAX_DIMS ? dims.subList(0, MAX_DIMS) : dims;
        buf.writeVarInt(d.size());
        for (String s : d) {
            buf.writeUtf(cut(s, 128), 128);
        }
        buf.writeVarInt(removed + 1);
        List<DropRow> dr = drops.size() > MAX_ROWS ? drops.subList(0, MAX_ROWS) : drops;
        buf.writeVarInt(dr.size());
        for (DropRow dr1 : dr) {
            buf.writeUtf(cut(dr1.server(), 64), 64);
            buf.writeUtf(cut(dr1.dim(), 128), 128);
            buf.writeVarInt(dr1.x());
            buf.writeVarInt(dr1.z());
            buf.writeVarInt(dr1.items());
            buf.writeVarInt(dr1.arrows());
            buf.writeVarInt(dr1.kept());
            buf.writeVarLong(dr1.itemsOverMs());
            buf.writeVarLong(dr1.arrowsOverMs());
            buf.writeUtf(cut(dr1.owner(), 32), 32);
            buf.writeVarLong(dr1.ageMs());
        }
        buf.writeVarLong(clears);
        buf.writeVarLong(clearedItems);
        buf.writeVarLong(clearedArrows);
        buf.writeVarInt(cleared + 1);
    }

    public static AdminMobsPacket decode(FriendlyByteBuf buf) {
        MobSettings s = MobSettings.decode(buf.readUtf(262_144));
        String here = buf.readUtf(64);
        int n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String server = buf.readUtf(64), dim = buf.readUtf(128);
            int x = buf.readVarInt(), z = buf.readVarInt(), count = buf.readVarInt(), limit = buf.readVarInt();
            MobSettings.From from = buf.readEnum(MobSettings.From.class);
            String owner = buf.readUtf(32);
            int tn = Math.min(buf.readVarInt(), MAX_TYPES);
            List<MobPlan.TypeCount> types = new ArrayList<>(tn);
            for (int j = 0; j < tn; j++) {
                types.add(new MobPlan.TypeCount(buf.readUtf(128), buf.readVarInt(), buf.readVarInt()));
            }
            rows.add(new Row(server, dim, x, z, count, limit, from, owner, types, buf.readVarLong()));
        }
        int dn = Math.min(buf.readVarInt(), MAX_DIMS);
        List<String> dims = new ArrayList<>(dn);
        for (int i = 0; i < dn; i++) {
            dims.add(buf.readUtf(128));
        }
        int removed = buf.readVarInt() - 1;
        int drn = Math.min(buf.readVarInt(), MAX_ROWS);
        List<DropRow> drops = new ArrayList<>(drn);
        for (int i = 0; i < drn; i++) {
            drops.add(new DropRow(buf.readUtf(64), buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarLong(), buf.readVarLong(), buf.readUtf(32), buf.readVarLong()));
        }
        return new AdminMobsPacket(s == null ? MobSettings.DEFAULTS : s, here, rows, dims, removed, drops, buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarInt() - 1);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.mobs(this));
        ctx.get().setPacketHandled(true);
    }
}
