package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.despawn.DespawnSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the DROPS tab's data (owner decisions D108 to D118): the network's despawn settings, this server's
 * name and the other servers heard from (for the clear's chips), how many items and orbs went in the last hour here,
 * and whether a clear is counting down here.
 */
public record AdminDropsPacket(DespawnSettings settings, String here, List<String> servers, long items, long orbs, boolean counting) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(settings.encode(), 32767);
        buf.writeUtf(here, 64);
        buf.writeVarInt(servers.size());
        servers.forEach(s -> buf.writeUtf(s, 64));
        buf.writeVarLong(items);
        buf.writeVarLong(orbs);
        buf.writeBoolean(counting);
    }

    public static AdminDropsPacket decode(FriendlyByteBuf buf) {
        DespawnSettings s = DespawnSettings.decode(buf.readUtf(32767));
        String here = buf.readUtf(64);
        int n = Math.min(buf.readVarInt(), 64);
        List<String> servers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            servers.add(buf.readUtf(64));
        }
        return new AdminDropsPacket(s == null ? DespawnSettings.DEFAULTS : s, here, servers, buf.readVarLong(), buf.readVarLong(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.drops(this));
        ctx.get().setPacketHandled(true);
    }
}
