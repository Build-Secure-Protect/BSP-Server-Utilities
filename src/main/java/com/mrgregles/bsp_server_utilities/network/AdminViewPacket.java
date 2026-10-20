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
 * Server to client: what the admin panel shows. {@code open} opens the panel ({@code /su}); otherwise it refreshes a
 * panel already open. The RULES tab's data: the whole rule set, how many players agreed to its version (-1 while the
 * count is not known), and {@code note}, a short language key saying what the last action did ("" for none).
 */
public record AdminViewPacket(boolean open, String heading, String intro, String leaveMessage, List<RuleSet.Rule> rules, int version, long revision, int agreed, String note) {
    public static AdminViewPacket of(RuleSet set, boolean open, int agreed, String note) {
        return new AdminViewPacket(open, set.heading(), set.intro(), set.leaveMessage(), set.rules(), set.version(), set.revision(), agreed, note);
    }

    public RuleSet ruleSet() {
        return new RuleSet(heading, intro, leaveMessage, rules, version, revision);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeUtf(heading, 256);
        buf.writeUtf(intro, 1024);
        buf.writeUtf(leaveMessage, 1024);
        buf.writeVarInt(rules.size());
        for (RuleSet.Rule r : rules) {
            buf.writeUtf(r.title(), 256);
            buf.writeUtf(r.text(), 2048);
        }
        buf.writeVarInt(version);
        buf.writeLong(revision);
        buf.writeInt(agreed);
        buf.writeUtf(note, 64);
    }

    public static AdminViewPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        String heading = buf.readUtf(256), intro = buf.readUtf(1024), leave = buf.readUtf(1024);
        int n = Math.min(buf.readVarInt(), RuleSet.MAX_RULES);
        List<RuleSet.Rule> rules = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rules.add(new RuleSet.Rule(buf.readUtf(256), buf.readUtf(2048)));
        }
        return new AdminViewPacket(open, heading, intro, leave, rules, buf.readVarInt(), buf.readLong(), buf.readInt(), buf.readUtf(64));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.view(this));
        ctx.get().setPacketHandled(true);
    }
}
