package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Server to client during login: "what does your client have?" (the client check, D120). Sent to every connecting
 * client in Forge's handshake, never to a singleplayer or LAN game; the client answers with {@link CheckAnswerPacket}
 * and the server holds the login until the answer is judged.
 */
public final class CheckQueryPacket implements IntSupplier {
    private int loginIndex;

    public void setLoginIndex(int index) {
        loginIndex = index;
    }

    @Override
    public int getAsInt() {
        return loginIndex;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(com.mrgregles.bsp_server_utilities.check.ClientReport.FORMAT);
    }

    public static CheckQueryPacket decode(FriendlyByteBuf buf) {
        buf.readVarInt();
        return new CheckQueryPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientCheck.answer(
                report -> ServerUtilsNetwork.CHANNEL.reply(new CheckAnswerPacket(report), c)));
        c.setPacketHandled(true);
    }
}
