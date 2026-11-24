package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * A tutorial builder's data in pieces, both ways (SU-142, SU-143): a compressed tag cut into parts, because a client
 * may send at most 32 KB a packet. Server to client: the builder's view (VIEW). Client to server: a draft to save (SAVE).
 */
public record TutorialBlobPacket(Kind kind, int upload, int index, int total, byte[] data) {
    public enum Kind { VIEW, SAVE }

    /** A part's size: under a client's 32 KB limit with room for the rest of the packet. */
    public static final int PART = 30_000, MAX_PARTS = 80;
    /** The most a builder's data may unpack to (a tutorial's text is at most 3 MB; its tag is larger). */
    public static final int MAX_UNPACKED = 16 * 1024 * 1024;

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(kind);
        buf.writeVarInt(upload);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(data);
    }

    public static TutorialBlobPacket decode(FriendlyByteBuf buf) {
        return new TutorialBlobPacket(buf.readEnum(Kind.class), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(PART + 64));
    }

    /** A tag compressed and cut into parts. */
    public static List<TutorialBlobPacket> split(Kind kind, int upload, CompoundTag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(tag, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] all = out.toByteArray();
        int total = Math.max(1, (all.length + PART - 1) / PART);
        List<TutorialBlobPacket> parts = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            parts.add(new TutorialBlobPacket(kind, upload, i, total, Arrays.copyOfRange(all, i * PART, Math.min(all.length, (i + 1) * PART))));
        }
        return parts;
    }

    /** Collects parts of one upload; the tag once every part is in (null before, or when it cannot be read). */
    public static final class Collector {
        private int upload = -1;
        private byte[][] parts = new byte[0][];

        @Nullable
        public CompoundTag add(TutorialBlobPacket p) {
            if (p.total() <= 0 || p.total() > MAX_PARTS || p.index() < 0 || p.index() >= p.total()) {
                return null;
            }
            if (p.upload() != upload || parts.length != p.total()) {
                upload = p.upload();
                parts = new byte[p.total()][];
            }
            parts[p.index()] = p.data();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] part : parts) {
                if (part == null) {
                    return null;
                }
                all.writeBytes(part);
            }
            parts = new byte[0][];
            upload = -1;
            try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    com.google.common.io.ByteStreams.limit(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(all.toByteArray())), MAX_UNPACKED)))) {
                // read within a limit, so a small upload cannot unpack into gigabytes (audit of SU-146)
                return NbtIo.read(in, new net.minecraft.nbt.NbtAccounter(MAX_UNPACKED));
            } catch (IOException | RuntimeException e) {
                BSPServerUtilities.LOGGER.warn("A tutorial builder's data could not be read: {}", e.toString());
                return null;
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        if (c.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
            ServerPlayer player = c.getSender();
            if (player != null && kind == Kind.SAVE) {
                com.mrgregles.bsp_server_utilities.tutorial.TutorialAdmin.part(player, this);
            }
        } else if (kind == Kind.VIEW) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTutorialAdmin.part(this));
        }
        c.setPacketHandled(true);
    }
}
