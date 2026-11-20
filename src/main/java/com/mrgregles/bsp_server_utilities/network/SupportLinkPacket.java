package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.support.SupportLinks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the network's support link (owner decisions D587 to D595), for the SUPPORT BSP key in the inventory
 * and the popup; sent at login and to everyone when it changes. Empty: no link, the key hidden.
 */
public record SupportLinkPacket(String link) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(link, SupportLinks.MAX);
    }

    public static SupportLinkPacket decode(FriendlyByteBuf buf) {
        return new SupportLinkPacket(buf.readUtf(SupportLinks.MAX));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientSupport.acceptLink(this));
        ctx.get().setPacketHandled(true);
    }
}
