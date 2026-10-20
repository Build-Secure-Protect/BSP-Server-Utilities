package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.ImageChunkPacket;
import com.mrgregles.bsp_server_utilities.network.ImageRequestPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Pictures on the client: asks the server for a picture by its hash (once a while per hash), puts the pieces back
 * together and keeps the PNG bytes. Drawing them on panels is SU-8.
 */
public final class ClientImages {
    private static final long ASK_AGAIN_MS = 15_000;
    private static final Map<String, byte[]> PICTURES = new HashMap<>();
    private static final Map<String, byte[][]> PARTS = new HashMap<>();
    private static final Map<String, Long> ASKED = new HashMap<>();

    private ClientImages() {
    }

    /** The picture's PNG bytes, or null while they have not arrived (they are asked for). */
    @Nullable
    public static byte[] get(String hash) {
        byte[] png = PICTURES.get(hash);
        if (png == null && !hash.isEmpty()) {
            long now = System.currentTimeMillis();
            Long asked = ASKED.get(hash);
            if (asked == null || now - asked > ASK_AGAIN_MS) {
                ASKED.put(hash, now);
                ServerUtilsNetwork.CHANNEL.sendToServer(new ImageRequestPacket(hash));
            }
        }
        return png;
    }

    public static void piece(ImageChunkPacket p) {
        if (p.total() <= 0 || p.total() > 512 || p.index() < 0 || p.index() >= p.total() || PICTURES.containsKey(p.hash())) {
            return;
        }
        byte[][] parts = PARTS.computeIfAbsent(p.hash(), k -> new byte[p.total()][]);
        if (parts.length != p.total()) {
            PARTS.remove(p.hash());
            return;
        }
        parts[p.index()] = p.data();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            if (part == null) {
                return; // more to come
            }
            all.writeBytes(part);
        }
        PARTS.remove(p.hash());
        PICTURES.put(p.hash(), all.toByteArray());
    }

    public static void clear() {
        PICTURES.clear();
        PARTS.clear();
        ASKED.clear();
    }
}
