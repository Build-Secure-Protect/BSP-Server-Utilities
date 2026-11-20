package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.support.SupportLinks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: {@code /support} opens the Support BSP popup at this link (owner decisions D593, D608, D611). */
public record SupportOpenPacket(String link) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(link, SupportLinks.MAX);
    }

    public static SupportOpenPacket decode(FriendlyByteBuf buf) {
        return new SupportOpenPacket(buf.readUtf(SupportLinks.MAX));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientSupport.open(link));
        ctx.get().setPacketHandled(true);
    }
}
