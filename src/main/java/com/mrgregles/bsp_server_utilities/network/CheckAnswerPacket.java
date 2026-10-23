package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.check.ClientReport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Client to server during login: the client's report, the answer to {@link CheckQueryPacket}. */
public final class CheckAnswerPacket implements IntSupplier {
    private final ClientReport report;
    private int loginIndex;

    public CheckAnswerPacket(ClientReport report) {
        this.report = report;
    }

    public ClientReport report() {
        return report;
    }

    public void setLoginIndex(int index) {
        loginIndex = index;
    }

    @Override
    public int getAsInt() {
        return loginIndex;
    }

    public void encode(FriendlyByteBuf buf) {
        report.encode(buf);
    }

    public static CheckAnswerPacket decode(FriendlyByteBuf buf) {
        return new CheckAnswerPacket(ClientReport.decode(buf));
    }

    /** On the network thread, after Forge has counted the answer: the verdict is reached on the server thread. */
    public static void handle(HandshakeHandler handler, CheckAnswerPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ClientCheckService.onAnswer(ctx.get().getNetworkManager(), msg.report);
        ctx.get().setPacketHandled(true);
    }
}
