package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the LOG tab's data (owner decisions D25, D29, D30, D56): every command with its switch (private
 * messages marked, they have none), the servers the log holds entries from, and the newest entries in full text that
 * match the tab's filters (a server or "" for all, a kind or "" for all).
 */
public record AdminLogPacket(List<Switch> switches, List<String> servers, String server, String kind, List<LogViewPacket.Row> entries) {
    public static final int MAX_ENTRIES = 300, MAX_SWITCHES = 1000, MAX_TEXT = 512;

    /** A command's switch: its root, whether it is logged, and whether it is a private message (never logged, no switch). */
    public record Switch(String root, boolean on, boolean privateMessage) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(switches.size());
        for (Switch s : switches) {
            buf.writeUtf(s.root(), 64);
            buf.writeBoolean(s.on());
            buf.writeBoolean(s.privateMessage());
        }
        buf.writeVarInt(servers.size());
        servers.forEach(s -> buf.writeUtf(s, 64));
        buf.writeUtf(server, 64);
        buf.writeUtf(kind, 16);
        buf.writeVarInt(entries.size());
        entries.forEach(r -> LogViewPacket.writeRow(buf, r, MAX_TEXT));
    }

    public static AdminLogPacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_SWITCHES);
        List<Switch> switches = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            switches.add(new Switch(buf.readUtf(64), buf.readBoolean(), buf.readBoolean()));
        }
        int m = Math.min(buf.readVarInt(), 64);
        List<String> servers = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            servers.add(buf.readUtf(64));
        }
        String server = buf.readUtf(64), kind = buf.readUtf(16);
        int e = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<LogViewPacket.Row> entries = new ArrayList<>(e);
        for (int i = 0; i < e; i++) {
            entries.add(LogViewPacket.readRow(buf, MAX_TEXT));
        }
        return new AdminLogPacket(switches, servers, server, kind, entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.log(this));
        ctx.get().setPacketHandled(true);
    }
}
