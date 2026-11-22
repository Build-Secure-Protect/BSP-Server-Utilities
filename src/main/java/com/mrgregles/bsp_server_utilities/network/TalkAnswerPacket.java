package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: why a line or an end from the private window was refused ({@code outcome}: a
 * {@code TalkService.Outcome} name, shown in red under the box as {@code gui.bsp_server_utilities.talk.refused.<outcome>});
 * nothing is sent for what worked, the line itself arrives instead.
 */
public record TalkAnswerPacket(String outcome) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(TalkViewPacket.cut(outcome, 32), 32);
    }

    public static TalkAnswerPacket decode(FriendlyByteBuf buf) {
        return new TalkAnswerPacket(buf.readUtf(32));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTalk.acceptAnswer(this));
        ctx.get().setPacketHandled(true);
    }
}
