package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.check.ClientsAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client to server from the CLIENTS tab: a refresh; pick a player; KICK a player with a reason; BLOCK a mod (its id) or
 * a pack (its fingerprint and name); the rules as they now stand (encoded); follow this server's own rules or the
 * network's ("true" / "false"); TRUST MY SETUP; EXPORT the setup to its file.
 */
public record AdminClientsEditPacket(Action action, UUID target, String arg, String arg2) {
    public enum Action { REFRESH, SELECT, KICK, BLOCK_MOD, BLOCK_PACK, RULES, USE_OWN, TRUST, EXPORT }

    public static final UUID NOBODY = new UUID(0, 0);

    public static AdminClientsEditPacket of(Action action) {
        return new AdminClientsEditPacket(action, NOBODY, "", "");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUUID(target);
        AdminClientsPacket.writeLong(buf, arg);
        buf.writeUtf(arg2, 256);
    }

    public static AdminClientsEditPacket decode(FriendlyByteBuf buf) {
        return new AdminClientsEditPacket(buf.readEnum(Action.class), buf.readUUID(), AdminClientsPacket.readLong(buf), buf.readUtf(256));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            ClientsAdmin.apply(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
