package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: the offer card for a tutorial (owner decisions D722, D738): YES, LATER or NO THANKS. */
public record TutorialOfferPacket(String tutorial, String name, String text) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(tutorial, 64);
        buf.writeUtf(name, 128);
        buf.writeUtf(text, 1200);
    }

    public static TutorialOfferPacket decode(FriendlyByteBuf buf) {
        return new TutorialOfferPacket(buf.readUtf(64), buf.readUtf(128), buf.readUtf(1200));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTutorial.offer(this));
        ctx.get().setPacketHandled(true);
    }
}
