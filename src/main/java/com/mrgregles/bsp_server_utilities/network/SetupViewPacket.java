package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server to client: the setup guide's data (D146 to D158): whether to open the guide and at which page, where every page
 * stands, and for the Welcome page this server's name in use and in the config file, whether storage is shared and its
 * status, and the other servers heard from.
 */
public record SetupViewPacket(boolean open, Feature startAt, Map<Feature, SetupState.Status> statuses, String here, String named, boolean shared, String storage,
                              List<String> servers) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeEnum(startAt);
        buf.writeVarInt(statuses.size());
        statuses.forEach((f, s) -> {
            buf.writeEnum(f);
            buf.writeEnum(s);
        });
        buf.writeUtf(here, 64);
        buf.writeUtf(named, 64);
        buf.writeBoolean(shared);
        buf.writeUtf(storage.length() > 512 ? storage.substring(0, 512) : storage, 512);
        buf.writeVarInt(Math.min(servers.size(), 64));
        servers.stream().limit(64).forEach(s -> buf.writeUtf(s, 64));
    }

    public static SetupViewPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        Feature start = buf.readEnum(Feature.class);
        int n = Math.min(buf.readVarInt(), Feature.values().length);
        Map<Feature, SetupState.Status> st = new EnumMap<>(Feature.class);
        for (int i = 0; i < n; i++) {
            st.put(buf.readEnum(Feature.class), buf.readEnum(SetupState.Status.class));
        }
        String here = buf.readUtf(64), named = buf.readUtf(64);
        boolean shared = buf.readBoolean();
        String storage = buf.readUtf(512);
        int k = Math.min(buf.readVarInt(), 64);
        List<String> servers = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            servers.add(buf.readUtf(64));
        }
        return new SetupViewPacket(open, start, st, here, named, shared, storage, servers);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.setup(this));
        ctx.get().setPacketHandled(true);
    }
}
