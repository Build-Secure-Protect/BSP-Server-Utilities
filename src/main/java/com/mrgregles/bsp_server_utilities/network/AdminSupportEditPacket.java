package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.support.SupportAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server from the SUPPORT tab: ask for the data, SAVE the three boxes as typed, or CLEAR (after SURE?). */
public record AdminSupportEditPacket(Action action, String link, String sentence, String chatLink) {
    public enum Action { REFRESH, SAVE, CLEAR }

    /** Room for what a box can hold and more: the server checks every limit again. */
    private static final int MAX = 1024;

    public static AdminSupportEditPacket ask() {
        return new AdminSupportEditPacket(Action.REFRESH, "", "", "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(cut(link), MAX);
        buf.writeUtf(cut(sentence), MAX);
        buf.writeUtf(cut(chatLink), MAX);
    }

    private static String cut(String s) {
        return s.length() > MAX / 4 * 3 ? s.substring(0, MAX / 4 * 3) : s;
    }

    public static AdminSupportEditPacket decode(FriendlyByteBuf buf) {
        return new AdminSupportEditPacket(buf.readEnum(Action.class), buf.readUtf(MAX), buf.readUtf(MAX), buf.readUtf(MAX));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            SupportAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
