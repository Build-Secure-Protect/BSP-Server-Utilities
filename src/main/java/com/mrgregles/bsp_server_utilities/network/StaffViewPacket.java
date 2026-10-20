package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the staff roster for Staff online faces and boards (owner decisions D23, D53 to D55, D61), sent to
 * every player when it changes (at most once a second) and at login. Each row: name, role group, label and colour,
 * state, server, and how long ago they were seen (milliseconds at sending; 0 when never or online).
 */
public record StaffViewPacket(List<Row> rows) {
    public record Row(String name, String group, String label, int colour, StaffDirectory.State state, String server, long agoMs) {
    }

    public static StaffViewPacket of(List<StaffDirectory.Member> roster, long now) {
        List<Row> rows = new ArrayList<>();
        for (StaffDirectory.Member m : roster) {
            rows.add(new Row(m.name(), m.role().group(), m.role().label(), m.role().colour(), m.state(), m.server(),
                    m.state() == StaffDirectory.State.OFFLINE && m.lastSeenMs() > 0 ? Math.max(0, now - m.lastSeenMs()) : 0));
        }
        return new StaffViewPacket(rows);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(rows.size());
        for (Row r : rows) {
            buf.writeUtf(r.name(), 64);
            buf.writeUtf(r.group(), 64);
            buf.writeUtf(r.label(), 64);
            buf.writeInt(r.colour());
            buf.writeEnum(r.state());
            buf.writeUtf(r.server(), 64);
            buf.writeVarLong(r.agoMs());
        }
    }

    public static StaffViewPacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 500);
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new Row(buf.readUtf(64), buf.readUtf(64), buf.readUtf(64), buf.readInt(), buf.readEnum(StaffDirectory.State.class), buf.readUtf(64), buf.readVarLong()));
        }
        return new StaffViewPacket(rows);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientStaff.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
