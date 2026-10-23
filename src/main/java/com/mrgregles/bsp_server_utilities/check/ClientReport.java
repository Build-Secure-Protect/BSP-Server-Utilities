package com.mrgregles.bsp_server_utilities.check;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * What a player's client says about itself (the client check, owner decisions D120 to D127): every mod with its version
 * and the SHA-1 of its file, the resource packs switched on (bottom to top) with the SHA-1 of each pack file, the shader
 * pack Oculus has chosen and whether shaders are on, the stone test (common stone-type blocks still solid and opaque;
 * when it fails, which block and the pack that changed it) and the brightness slider (1.0 is the game's top).
 *
 * <p>Sent in the login handshake and again when the client reloads its resources. Lengths and counts are bounded; a
 * fingerprint is empty when there is no file to read (the game itself, a pack built into a mod).
 */
public record ClientReport(List<Mod> mods, List<Pack> packs, String shader, boolean shadersOn, boolean stonePassed, String stoneBlock, String stonePack, double brightness) {
    public static final int FORMAT = 1, MAX_MODS = 1024, MAX_PACKS = 64;
    static final int ID = 64, NAME = 128, VERSION = 64, SHA1 = 40;
    public static final ClientReport EMPTY = new ClientReport(List.of(), List.of(), "", false, true, "", "", 1.0);

    /** A mod: its id, display name, version and the fingerprint of the file it came from. */
    public record Mod(String id, String name, String version, String sha1) {
    }

    /** A resource pack switched on: its id (such as {@code file/Faithful.zip}), title and fingerprint. */
    public record Pack(String id, String name, String sha1) {
    }

    public ClientReport {
        mods = List.copyOf(mods.subList(0, Math.min(MAX_MODS, mods.size())));
        packs = List.copyOf(packs.subList(0, Math.min(MAX_PACKS, packs.size())));
        shader = cut(shader, NAME);
        stoneBlock = cut(stoneBlock, ID);
        stonePack = cut(stonePack, NAME);
    }

    /** Whether the brightness is past the slider's top (a fullbright edit, D126). */
    public boolean brightnessHigh() {
        return brightness > 1.0001;
    }

    @Nullable
    public Mod mod(String id) {
        for (Mod m : mods) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    static String cut(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(FORMAT);
        buf.writeVarInt(mods.size());
        for (Mod m : mods) {
            buf.writeUtf(cut(m.id(), ID), ID);
            buf.writeUtf(cut(m.name(), NAME), NAME);
            buf.writeUtf(cut(m.version(), VERSION), VERSION);
            buf.writeUtf(cut(m.sha1(), SHA1), SHA1);
        }
        buf.writeVarInt(packs.size());
        for (Pack p : packs) {
            buf.writeUtf(cut(p.id(), NAME), NAME);
            buf.writeUtf(cut(p.name(), NAME), NAME);
            buf.writeUtf(cut(p.sha1(), SHA1), SHA1);
        }
        buf.writeUtf(shader, NAME);
        buf.writeBoolean(shadersOn);
        buf.writeBoolean(stonePassed);
        buf.writeUtf(stoneBlock, ID);
        buf.writeUtf(stonePack, NAME);
        buf.writeDouble(brightness);
    }

    /** Reads a report; a count past the bounds, or a string past its length, is refused as a bad packet. */
    public static ClientReport decode(FriendlyByteBuf buf) {
        int format = buf.readVarInt();
        if (format != FORMAT) {
            throw new DecoderException("client check report format " + format);
        }
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_MODS) {
            throw new DecoderException("client check report with " + n + " mods");
        }
        List<Mod> mods = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            mods.add(new Mod(buf.readUtf(ID), buf.readUtf(NAME), buf.readUtf(VERSION), buf.readUtf(SHA1)));
        }
        int k = buf.readVarInt();
        if (k < 0 || k > MAX_PACKS) {
            throw new DecoderException("client check report with " + k + " packs");
        }
        List<Pack> packs = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            packs.add(new Pack(buf.readUtf(NAME), buf.readUtf(NAME), buf.readUtf(SHA1)));
        }
        String shader = buf.readUtf(NAME);
        boolean on = buf.readBoolean();
        boolean passed = buf.readBoolean();
        String block = buf.readUtf(ID);
        String pack = buf.readUtf(NAME);
        double brightness = buf.readDouble();
        return new ClientReport(mods, packs, shader, on, passed, block, pack, Double.isFinite(brightness) ? brightness : 1.0);
    }

    /** The report as text, for notices between servers. */
    public String toText() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encode(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            buf.release();
        }
    }

    @Nullable
    public static ClientReport fromText(String text) {
        FriendlyByteBuf buf = null;
        try {
            buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(Base64.getDecoder().decode(text)));
            return decode(buf);
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
    }
}
