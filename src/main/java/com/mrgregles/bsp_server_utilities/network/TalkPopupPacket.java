package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: staff wrote, show the popup (owner decisions D635, D636, D649, D652 R3): {@code count} staff lines,
 * by {@code writer} when they all came from one staff member (else empty), {@code away} when they were written while the
 * player was offline (the login wording). Never the words of the message. The client waits for a quiet moment (no other
 * screen, not hurt in the last 10 s) and folds popups that arrive meanwhile into one.
 */
public record TalkPopupPacket(String writer, int count, boolean away) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(TalkViewPacket.cut(writer, 32), 32);
        buf.writeVarInt(count);
        buf.writeBoolean(away);
    }

    public static TalkPopupPacket decode(FriendlyByteBuf buf) {
        return new TalkPopupPacket(buf.readUtf(32), buf.readVarInt(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTalk.acceptPopup(this));
        ctx.get().setPacketHandled(true);
    }
}
