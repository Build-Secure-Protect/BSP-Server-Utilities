package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.support.SupportSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: the SUPPORT tab's data (owner decisions D605, D609, D612): the network's Support BSP settings as
 * saved, with who changed them last and when, and the answer to the admin's last SAVE or CLEAR ({@code note}: empty,
 * {@code saved}, {@code cleared}, {@code unchanged}, {@code bad_link} or {@code bad_chat_link} with the reason's
 * {@code problem}, the name of a {@code SupportLinks.Problem}).
 */
public record AdminSupportPacket(SupportSettings settings, String note, String problem) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(settings.encode(), 2048);
        buf.writeUtf(note, 32);
        buf.writeUtf(problem, 32);
    }

    public static AdminSupportPacket decode(FriendlyByteBuf buf) {
        SupportSettings s = SupportSettings.decode(buf.readUtf(2048));
        return new AdminSupportPacket(s == null ? SupportSettings.NONE : s, buf.readUtf(32), buf.readUtf(32));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.support(this));
        ctx.get().setPacketHandled(true);
    }
}
