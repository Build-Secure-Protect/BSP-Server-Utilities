package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server to client: a new line in the conversation with {@code player}, for a private window that is open (D652 R1, R2: lines arrive live). */
public record TalkLinePacket(UUID player, TalkViewPacket.Entry line) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(player);
        line.encode(buf);
    }

    public static TalkLinePacket decode(FriendlyByteBuf buf) {
        return new TalkLinePacket(buf.readUUID(), TalkViewPacket.Entry.decode(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTalk.acceptLine(this));
        ctx.get().setPacketHandled(true);
    }
}
