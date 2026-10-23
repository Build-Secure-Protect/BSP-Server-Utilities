package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.check.CheckRules;
import com.mrgregles.bsp_server_utilities.check.ClientReport;
import com.mrgregles.bsp_server_utilities.check.Verdict;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server to client: the CLIENTS tab's data (owner decisions D130, D134 to D141): this server's name, whether it follows
 * its own rules, the rules in force, the trusted setup in short, everyone on the network with a report (flags, blocked
 * since joining, exempt), today's refusals, the picked player's whole report and verdict, and a note to show (a key, or
 * empty).
 */
public record AdminClientsPacket(String here, boolean useOwn, CheckRules rules, @Nullable Setup setup, List<PlayerRow> rows, List<Refused> refusals,
                                 @Nullable Detail detail, String note) {
    public static final int MAX_ROWS = 500;

    /** The trusted setup in short. */
    public record Setup(long takenAtMs, String takenBy, int mods, int packs, String shader) {
    }

    public record PlayerRow(UUID id, String name, String server, int flags, boolean blocked, boolean exempt) {
    }

    public record Refused(long atMs, String server, String name, String reason) {
    }

    /** The picked player: their report and what the check made of it. */
    public record Detail(UUID id, String name, String server, long atMs, ClientReport report, Verdict verdict, boolean hasSetup) {
    }

    static void writeLong(FriendlyByteBuf buf, String s) {
        buf.writeByteArray(s.getBytes(StandardCharsets.UTF_8));
    }

    static String readLong(FriendlyByteBuf buf) {
        return new String(buf.readByteArray(1 << 20), StandardCharsets.UTF_8);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(here, 64);
        buf.writeBoolean(useOwn);
        writeLong(buf, rules.encode());
        buf.writeBoolean(setup != null);
        if (setup != null) {
            buf.writeLong(setup.takenAtMs());
            buf.writeUtf(setup.takenBy(), 64);
            buf.writeVarInt(setup.mods());
            buf.writeVarInt(setup.packs());
            buf.writeUtf(setup.shader(), 128);
        }
        buf.writeVarInt(Math.min(rows.size(), MAX_ROWS));
        for (PlayerRow r : rows.subList(0, Math.min(rows.size(), MAX_ROWS))) {
            buf.writeUUID(r.id());
            buf.writeUtf(r.name(), 32);
            buf.writeUtf(r.server(), 64);
            buf.writeVarInt(r.flags());
            buf.writeBoolean(r.blocked());
            buf.writeBoolean(r.exempt());
        }
        buf.writeVarInt(refusals.size());
        for (Refused r : refusals) {
            buf.writeLong(r.atMs());
            buf.writeUtf(r.server(), 64);
            buf.writeUtf(r.name(), 32);
            writeLong(buf, r.reason());
        }
        buf.writeBoolean(detail != null);
        if (detail != null) {
            buf.writeUUID(detail.id());
            buf.writeUtf(detail.name(), 32);
            buf.writeUtf(detail.server(), 64);
            buf.writeLong(detail.atMs());
            detail.report().encode(buf);
            writeLong(buf, detail.verdict().encode());
            buf.writeBoolean(detail.hasSetup());
        }
        buf.writeUtf(note, 256);
    }

    public static AdminClientsPacket decode(FriendlyByteBuf buf) {
        String here = buf.readUtf(64);
        boolean own = buf.readBoolean();
        CheckRules rules = CheckRules.decode(readLong(buf));
        Setup setup = buf.readBoolean() ? new Setup(buf.readLong(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readUtf(128)) : null;
        int n = Math.min(buf.readVarInt(), MAX_ROWS);
        List<PlayerRow> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new PlayerRow(buf.readUUID(), buf.readUtf(32), buf.readUtf(64), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));
        }
        int k = Math.min(buf.readVarInt(), 64);
        List<Refused> refusals = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            refusals.add(new Refused(buf.readLong(), buf.readUtf(64), buf.readUtf(32), readLong(buf)));
        }
        Detail detail = null;
        if (buf.readBoolean()) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(32), server = buf.readUtf(64);
            long at = buf.readLong();
            ClientReport report = ClientReport.decode(buf);
            Verdict v = Verdict.decode(readLong(buf));
            detail = new Detail(id, name, server, at, report, v == null ? Verdict.CLEAN : v, buf.readBoolean());
        }
        return new AdminClientsPacket(here, own, rules == null ? CheckRules.DEFAULTS : rules, setup, rows, refusals, detail, buf.readUtf(256));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.clients(this));
        ctx.get().setPacketHandled(true);
    }
}
