package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the player counter for the PLAYERS board and Player count panels (owner decisions D168, D172, D175,
 * D178), sent to every player when it changes (at most once a second) and at login: whether the counter is off (the
 * board and panels then show nothing, D173), its title, the number shown, the new players today and in the last seven
 * days, and the newest player with how long ago they joined (milliseconds at sending; empty name when none yet).
 */
public record PlayersViewPacket(boolean off, String title, int shown, int today, int week, String newest, long newestAgoMs) {
    public static final PlayersViewPacket NONE = new PlayersViewPacket(true, "", 0, 0, 0, "", 0);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(off);
        buf.writeUtf(title, 64);
        buf.writeVarInt(shown);
        buf.writeVarInt(today);
        buf.writeVarInt(week);
        buf.writeUtf(newest, 64);
        buf.writeVarLong(newestAgoMs);
    }

    public static PlayersViewPacket decode(FriendlyByteBuf buf) {
        return new PlayersViewPacket(buf.readBoolean(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readVarLong());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientPlayers.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
