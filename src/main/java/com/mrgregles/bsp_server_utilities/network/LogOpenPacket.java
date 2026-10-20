package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: {@code /actionlog} (owner decision D58): the newest entries, coordinates hidden, and open the screen. */
public record LogOpenPacket(LogViewPacket view) {
    public void encode(FriendlyByteBuf buf) {
        view.encode(buf);
    }

    public static LogOpenPacket decode(FriendlyByteBuf buf) {
        return new LogOpenPacket(LogViewPacket.decode(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ActionLogScreen.open(view));
        ctx.get().setPacketHandled(true);
    }
}
