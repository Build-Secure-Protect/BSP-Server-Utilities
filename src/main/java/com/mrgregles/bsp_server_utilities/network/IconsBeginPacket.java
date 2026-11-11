package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.web.WebItemsUpload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server (SU-41): an export is ready to send: its ticket, its size in bytes, the SHA-256 of the whole zip, and
 * how many items, tabs and mods it holds. {@link IconsPartPacket}s follow in order.
 */
public record IconsBeginPacket(String ticket, int size, String sha256, int items, int tabs, int mods) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(ticket, 64);
        buf.writeVarInt(size);
        buf.writeUtf(sha256, 64);
        buf.writeVarInt(items);
        buf.writeVarInt(tabs);
        buf.writeVarInt(mods);
    }

    public static IconsBeginPacket decode(FriendlyByteBuf buf) {
        return new IconsBeginPacket(buf.readUtf(64), buf.readVarInt(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getSender() != null) {
            WebItemsUpload.begin(ctx.get().getSender(), this);
        }
        ctx.get().setPacketHandled(true);
    }
}
