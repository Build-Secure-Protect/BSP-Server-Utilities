package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client to server, from the private window: {@link #SEND} a line ({@code text}), {@link #END} the conversation (staff
 * only, after SURE?), or {@link #CLOSE} the window. {@code player} is whose conversation: the sender's own for the player's
 * window. The server checks everything again (who may, the limits).
 */
public record TalkActionPacket(byte action, UUID player, String text) {
    public static final byte SEND = 0, END = 1, CLOSE = 2;
    public static final int MAX_TEXT = 1024;

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action);
        buf.writeUUID(player);
        buf.writeUtf(text, MAX_TEXT);
    }

    public static TalkActionPacket decode(FriendlyByteBuf buf) {
        return new TalkActionPacket(buf.readByte(), buf.readUUID(), buf.readUtf(MAX_TEXT));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        var sender = ctx.get().getSender();
        if (sender != null) {
            com.mrgregles.bsp_server_utilities.talk.TalkWindows.onAction(sender, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
