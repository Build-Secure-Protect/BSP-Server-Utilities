package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.check.ClientReport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server in game: the report again after the client reloaded its resources or its brightness crossed the top (D127). */
public record CheckReportPacket(ClientReport report) {
    public void encode(FriendlyByteBuf buf) {
        report.encode(buf);
    }

    public static CheckReportPacket decode(FriendlyByteBuf buf) {
        return new CheckReportPacket(ClientReport.decode(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            ClientCheckService.onReport(player, report);
        }
        ctx.get().setPacketHandled(true);
    }
}
