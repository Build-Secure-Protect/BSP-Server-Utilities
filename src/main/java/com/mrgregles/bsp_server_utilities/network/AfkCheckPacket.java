package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the presence check's card (owner decisions D216, D220, D222, D223), or {@code id} 0 to take it
 * away. The server picks the word ({@code word}, an index into the eight woods) and the three keys' order; {@code round}
 * counts the words of one check, so an answer to an old word is not taken; {@code kick} is false for staff, who are
 * never kicked; {@code wrong} when the last click was the wrong key.
 */
public record AfkCheckPacket(int id, int round, List<Integer> keys, int word, long msLeft, boolean kick, boolean wrong) {
    public static final AfkCheckPacket HIDE = new AfkCheckPacket(0, 0, List.of(), 0, 0, false, false);

    public boolean hide() {
        return id == 0;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeVarInt(round);
        buf.writeVarInt(keys.size());
        for (int k : keys) {
            buf.writeVarInt(k);
        }
        buf.writeVarInt(word);
        buf.writeVarLong(msLeft);
        buf.writeBoolean(kick);
        buf.writeBoolean(wrong);
    }

    public static AfkCheckPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt(), round = buf.readVarInt(), n = Math.min(buf.readVarInt(), 8);
        List<Integer> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            keys.add(buf.readVarInt());
        }
        return new AfkCheckPacket(id, round, List.copyOf(keys), buf.readVarInt(), buf.readVarLong(), buf.readBoolean(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAfkCheck.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
