package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the SERVERS tab (owner decisions D199 to D204). This server's name, whether the set is the network's
 * (shared storage) or this server's alone, how many files the folder holds, and one row per name in alphabetical order
 * (D202): its mark, the image in use (empty id: none) and the folder's file for it (empty file: none; a problem when it
 * was refused). Then what the last REFRESH did or would do: {@code outcome} is NONE, PENDING (it waits for SURE?, D203),
 * DONE or NOTHING (no change), with the servers added, replaced and removed.
 */
public record AdminServersPacket(String here, boolean shared, int files, List<Row> rows, Outcome outcome, List<String> added, List<String> replaced, List<String> removed) {
    public enum Mark { NONE, HERE, OFF, UNKNOWN }

    public enum Outcome { NONE, PENDING, DONE, NOTHING }

    public static final int MAX_ROWS = 96;

    public record Row(String name, Mark mark, String id, String file, int width, int height, int bytes,
                      String folderFile, int folderWidth, int folderHeight, int folderBytes, String folderId, String problem) {
        public boolean inUse() {
            return !id.isEmpty();
        }

        public boolean refused() {
            return !folderFile.isEmpty() && !problem.isEmpty();
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(here, 64);
        buf.writeBoolean(shared);
        buf.writeVarInt(files);
        buf.writeVarInt(Math.min(MAX_ROWS, rows.size()));
        for (Row r : rows.subList(0, Math.min(MAX_ROWS, rows.size()))) {
            buf.writeUtf(r.name(), 64);
            buf.writeEnum(r.mark());
            buf.writeUtf(r.id(), 40);
            buf.writeUtf(r.file(), 128);
            buf.writeVarInt(r.width());
            buf.writeVarInt(r.height());
            buf.writeVarInt(r.bytes());
            buf.writeUtf(r.folderFile(), 128);
            buf.writeVarInt(r.folderWidth());
            buf.writeVarInt(r.folderHeight());
            buf.writeVarInt(r.folderBytes());
            buf.writeUtf(r.folderId(), 40);
            buf.writeUtf(r.problem(), 128);
        }
        buf.writeEnum(outcome);
        writeNames(buf, added);
        writeNames(buf, replaced);
        writeNames(buf, removed);
    }

    public static AdminServersPacket decode(FriendlyByteBuf buf) {
        String here = buf.readUtf(64);
        boolean shared = buf.readBoolean();
        int files = buf.readVarInt();
        int n = Math.min(MAX_ROWS, buf.readVarInt());
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(new Row(buf.readUtf(64), buf.readEnum(Mark.class), buf.readUtf(40), buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(40), buf.readUtf(128)));
        }
        Outcome o = buf.readEnum(Outcome.class);
        return new AdminServersPacket(here, shared, files, List.copyOf(rows), o, readNames(buf), readNames(buf), readNames(buf));
    }

    private static void writeNames(FriendlyByteBuf buf, List<String> names) {
        buf.writeVarInt(Math.min(MAX_ROWS, names.size()));
        names.stream().limit(MAX_ROWS).forEach(s -> buf.writeUtf(s, 64));
    }

    private static List<String> readNames(FriendlyByteBuf buf) {
        int n = Math.min(MAX_ROWS, buf.readVarInt());
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf(64));
        }
        return List.copyOf(out);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.servers(this));
        ctx.get().setPacketHandled(true);
    }
}
