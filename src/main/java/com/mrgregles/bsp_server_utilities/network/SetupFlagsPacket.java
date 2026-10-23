package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/** Server to client: the features turned off (D150), so Display Panels go dark and refuse placing on the client too. */
public record SetupFlagsPacket(Set<Feature> off) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(off.size());
        off.forEach(buf::writeEnum);
    }

    public static SetupFlagsPacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), Feature.values().length);
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        for (int i = 0; i < n; i++) {
            off.add(buf.readEnum(Feature.class));
        }
        return new SetupFlagsPacket(off);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        if (ctx.get().getDirection().getReceptionSide().isClient()) {
            Switches.set(off);
        }
        ctx.get().setPacketHandled(true);
    }
}
