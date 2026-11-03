package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the server images in use (owner decisions D189 to D198), each server's name and its image's id, sent
 * at login and whenever the set changes or the feature is turned off or on. Off, the list is empty and every server's tag
 * reads as its text. The images' bytes come apart, once a login ({@link ServerImagePacket}).
 */
public record ServerImageListPacket(boolean on, List<Item> items) {
    public static final int MAX_ITEMS = 64;

    public record Item(String server, String id) {
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(on);
        buf.writeVarInt(Math.min(MAX_ITEMS, items.size()));
        for (Item i : items.subList(0, Math.min(MAX_ITEMS, items.size()))) {
            buf.writeUtf(i.server(), 64);
            buf.writeUtf(i.id(), 40);
        }
    }

    public static ServerImageListPacket decode(FriendlyByteBuf buf) {
        boolean on = buf.readBoolean();
        int n = Math.min(MAX_ITEMS, buf.readVarInt());
        List<Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            items.add(new Item(buf.readUtf(64), buf.readUtf(40)));
        }
        return new ServerImageListPacket(on, List.copyOf(items));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ServerImageFont.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
