package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.afk.AfkSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the AFK tab's data (owner decisions D76, D79 to D85): the network's AFK settings and everyone AFK
 * now on any server (how long, whether they can never be kicked, their role colour when staff), plus staff away by
 * {@code /su away} (marked {@code typedAway}).
 */
public record AdminAfkPacket(AfkSettings settings, List<Row> rows) {
    public static final int MAX_ROWS = 200;

    public record Row(String name, String server, long afkForMs, boolean exempt, int colour, boolean typedAway) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(settings.encode(), 1024);
        buf.writeVarInt(rows.size());
        for (Row r : rows) {
            buf.writeUtf(r.name(), 64);
            buf.writeUtf(r.server(), 64);
            buf.writeVarLong(r.afkForMs());
            buf.writeBoolean(r.exempt());
            buf.writeInt(r.colour());
            buf.writeBoolean(r.typedAway());
        }
    }

    public static AdminAfkPacket decode(FriendlyByteBuf buf) {
        AfkSettings s = AfkSettings.decode(buf.readUtf(1024));
        int n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new Row(buf.readUtf(64), buf.readUtf(64), buf.readVarLong(), buf.readBoolean(), buf.readInt(), buf.readBoolean()));
        }
        return new AdminAfkPacket(s == null ? AfkSettings.DEFAULTS : s, rows);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.afk(this));
        ctx.get().setPacketHandled(true);
    }
}
