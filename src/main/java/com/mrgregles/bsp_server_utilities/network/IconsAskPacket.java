package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client (SU-41, D340, D373): {@code /su icons} was typed by an admin; their game draws every item of the
 * creative tabs and sends the pictures back under this one-time ticket.
 */
public record IconsAskPacket(String ticket) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(ticket, 64);
    }

    public static IconsAskPacket decode(FriendlyByteBuf buf) {
        return new IconsAskPacket(buf.readUtf(64));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.IconExport.start(ticket));
        ctx.get().setPacketHandled(true);
    }
}
