package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: show the rules. {@code agree} is the first-join popup (tick boxes, LEAVE SERVER, AGREE; the player
 * is held until they answer); otherwise the read-only copy from {@code /rules}. {@code agreedAtMs} is when this player
 * agreed (0 when they have not). The version and revision come back in the answer, so an answer to rules that changed
 * meanwhile is not taken.
 */
public record RulesOpenPacket(boolean agree, String heading, String intro, List<RuleSet.Rule> rules, int version, long revision, long agreedAtMs) {
    public static RulesOpenPacket of(RuleSet set, boolean agree, long agreedAtMs) {
        return new RulesOpenPacket(agree, set.heading(), set.intro(), set.rules(), set.version(), set.revision(), agreedAtMs);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(agree);
        buf.writeUtf(heading, 256);
        buf.writeUtf(intro, 1024);
        buf.writeVarInt(rules.size());
        for (RuleSet.Rule r : rules) {
            buf.writeUtf(r.title(), 256);
            buf.writeUtf(r.text(), 2048);
        }
        buf.writeVarInt(version);
        buf.writeLong(revision);
        buf.writeLong(agreedAtMs);
    }

    public static RulesOpenPacket decode(FriendlyByteBuf buf) {
        boolean agree = buf.readBoolean();
        String heading = buf.readUtf(256);
        String intro = buf.readUtf(1024);
        int n = Math.min(buf.readVarInt(), RuleSet.MAX_RULES);
        List<RuleSet.Rule> rules = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rules.add(new RuleSet.Rule(buf.readUtf(256), buf.readUtf(2048)));
        }
        return new RulesOpenPacket(agree, heading, intro, rules, buf.readVarInt(), buf.readLong(), buf.readLong());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientRules.open(this));
        ctx.get().setPacketHandled(true);
    }
}
