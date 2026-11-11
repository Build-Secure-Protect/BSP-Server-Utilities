package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.web.WebItemsUpload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server (SU-41): one piece of an export, in order, at most {@link WebItemsUpload#PIECE} bytes. */
public record IconsPartPacket(String ticket, int index, byte[] data) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(ticket, 64);
        buf.writeVarInt(index);
        buf.writeByteArray(data);
    }

    public static IconsPartPacket decode(FriendlyByteBuf buf) {
        return new IconsPartPacket(buf.readUtf(64), buf.readVarInt(), buf.readByteArray(WebItemsUpload.PIECE));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getSender() != null) {
            WebItemsUpload.part(ctx.get().getSender(), this);
        }
        ctx.get().setPacketHandled(true);
    }
}
