package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: open the private window (owner decisions D648, D652 R1 and R2). {@code staff} false: the player's own
 * window, with their open conversation only (D633); true: the staff window for {@code player}, with their conversations of
 * the last 90 days, where they are ({@code where}: a server's name, empty when offline), when the open one started and by
 * whom ({@code openSince} 0 when none is open) and whether the player has been told of the staff lines yet ({@code seen}).
 * At most {@link #MAX_LINES} lines, the newest.
 */
public record TalkViewPacket(boolean staff, UUID player, String name, String where, long openSince, String openedBy, boolean seen, List<Entry> lines) {
    public static final int MAX_LINES = 300;
    public static final byte STAFF = 0, PLAYER = 1, END = 2;

    /** One line: its conversation, time, {@link #STAFF}, {@link #PLAYER} or {@link #END}, who on staff, and the words. */
    public record Entry(long talk, long at, byte kind, String staff, String text) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarLong(talk);
            buf.writeLong(at);
            buf.writeByte(kind);
            buf.writeUtf(cut(staff, 32), 32);
            buf.writeUtf(cut(text, 1024), 1024);
        }

        static Entry decode(FriendlyByteBuf buf) {
            return new Entry(buf.readVarLong(), buf.readLong(), buf.readByte(), buf.readUtf(32), buf.readUtf(1024));
        }
    }

    /** A string cut to what the packet carries: a long name (a command block's, an entity's) never stops a send (audit). */
    static String cut(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(staff);
        buf.writeUUID(player);
        buf.writeUtf(cut(name, 32), 32);
        buf.writeUtf(cut(where, 64), 64);
        buf.writeLong(openSince);
        buf.writeUtf(cut(openedBy, 32), 32);
        buf.writeBoolean(seen);
        List<Entry> l = lines.size() <= MAX_LINES ? lines : lines.subList(lines.size() - MAX_LINES, lines.size());
        buf.writeVarInt(l.size());
        l.forEach(e -> e.encode(buf));
    }

    public static TalkViewPacket decode(FriendlyByteBuf buf) {
        boolean staff = buf.readBoolean();
        UUID player = buf.readUUID();
        String name = buf.readUtf(32), where = buf.readUtf(64);
        long since = buf.readLong();
        String by = buf.readUtf(32);
        boolean seen = buf.readBoolean();
        int n = Math.min(buf.readVarInt(), MAX_LINES);
        List<Entry> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(Entry.decode(buf));
        }
        return new TalkViewPacket(staff, player, name, where, since, by, seen, lines);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTalk.acceptView(this));
        ctx.get().setPacketHandled(true);
    }
}
