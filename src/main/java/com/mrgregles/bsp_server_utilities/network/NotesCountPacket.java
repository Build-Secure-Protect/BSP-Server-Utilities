package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.web.WebNotes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client (protocol 6): the staff web panel's notes counts for the players online (D266, D321, D332), sent to
 * staff who can open the admin panel at login and when they change. Each entry: the player, their name, how many notes,
 * the newest's author and time (ms).
 */
public record NotesCountPacket(List<WebNotes.Count> counts) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(counts.size());
        for (WebNotes.Count c : counts) {
            buf.writeUUID(c.id());
            buf.writeUtf(c.name(), 32);
            buf.writeVarInt(c.n());
            buf.writeUtf(c.by(), 32);
            buf.writeVarLong(c.at());
        }
    }

    public static NotesCountPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > WebNotes.MAX_PLAYERS) {
            throw new IllegalArgumentException("too many notes counts: " + n);
        }
        List<WebNotes.Count> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new WebNotes.Count(buf.readUUID(), buf.readUtf(32), buf.readVarInt(), buf.readUtf(32), buf.readVarLong()));
        }
        return new NotesCountPacket(out);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientNotes.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
