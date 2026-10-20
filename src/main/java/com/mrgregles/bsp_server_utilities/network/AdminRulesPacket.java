package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server: an edit from the RULES tab. {@code index} is the rule (from 0) for SET, DELETE and MOVE; for MOVE
 * {@code a} is "-1" (up) or "1" (down). SET carries the title and text in {@code a} and {@code b}; ADD the same for a new
 * rule; TEXTS the heading, intro and leave message in {@code a}, {@code b} and {@code c}. The server checks the admin
 * may edit and answers with a fresh {@link AdminViewPacket}.
 */
public record AdminRulesPacket(Action action, int index, String a, String b, String c) {
    public enum Action { REFRESH, SET, ADD, DELETE, MOVE, TEXTS, ASK_AGAIN }

    public static AdminRulesPacket of(Action action) {
        return new AdminRulesPacket(action, -1, "", "", "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeVarInt(index + 1);
        buf.writeUtf(a, 2048);
        buf.writeUtf(b, 2048);
        buf.writeUtf(c, 2048);
    }

    public static AdminRulesPacket decode(FriendlyByteBuf buf) {
        return new AdminRulesPacket(buf.readEnum(Action.class), buf.readVarInt() - 1, buf.readUtf(2048), buf.readUtf(2048), buf.readUtf(2048));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            AdminPanel.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
