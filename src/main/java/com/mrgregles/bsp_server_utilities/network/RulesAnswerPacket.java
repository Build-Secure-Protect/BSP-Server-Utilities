package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client to server: the player's answer to the popup. AGREE carries the rules version and revision they read and the
 * numbers (from 0) of the rules they ticked; the server checks every one is ticked (owner decision D9). LEAVE asks to
 * be disconnected (D10).
 */
public record RulesAnswerPacket(Action action, int version, long revision, List<Integer> ticked) {
    public enum Action { AGREE, LEAVE }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeVarInt(version);
        buf.writeLong(revision);
        buf.writeVarInt(ticked.size());
        for (int i : ticked) {
            buf.writeVarInt(i);
        }
    }

    public static RulesAnswerPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        int version = buf.readVarInt();
        long revision = buf.readLong();
        int n = Math.min(buf.readVarInt(), RuleSet.MAX_RULES);
        List<Integer> ticked = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ticked.add(buf.readVarInt());
        }
        return new RulesAnswerPacket(action, version, revision, ticked);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            RulesService.answer(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
