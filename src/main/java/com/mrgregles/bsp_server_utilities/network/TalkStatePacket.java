package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the player's conversation with staff (owner decisions D633, D634, D650): {@code open} shows the
 * MESSAGE STAFF key, {@code waiting} (the newest line is from staff) makes it flash red. Sent at login and on every
 * change, from whichever server the player is on.
 */
public record TalkStatePacket(boolean open, boolean waiting) {
    public static final TalkStatePacket NONE = new TalkStatePacket(false, false);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeBoolean(waiting);
    }

    public static TalkStatePacket decode(FriendlyByteBuf buf) {
        return new TalkStatePacket(buf.readBoolean(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTalk.acceptState(this));
        ctx.get().setPacketHandled(true);
    }
}
