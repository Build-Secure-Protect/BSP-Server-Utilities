package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.setup.Feature;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server to client: show the setup card in the corner (first time, or which pages are left), or hide it (D155, D147). */
public record SetupCardPacket(boolean show, boolean first, List<Feature> left) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(show);
        buf.writeBoolean(first);
        buf.writeVarInt(left.size());
        left.forEach(buf::writeEnum);
    }

    public static SetupCardPacket decode(FriendlyByteBuf buf) {
        boolean show = buf.readBoolean(), first = buf.readBoolean();
        int n = Math.min(buf.readVarInt(), Feature.values().length);
        List<Feature> left = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            left.add(buf.readEnum(Feature.class));
        }
        return new SetupCardPacket(show, first, left);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientSetupGuide.card(this));
        ctx.get().setPacketHandled(true);
    }
}
