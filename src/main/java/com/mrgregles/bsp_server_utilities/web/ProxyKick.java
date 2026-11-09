package com.mrgregles.bsp_server_utilities.web;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A kick from the whole network (owner decisions D254, D272): this server asks BSP-Velocity Additions, over the kicked
 * player's own connection, to disconnect them from the proxy (its channel {@code bsp_server_utilities:proxy}, request
 * SU-VA2), so Velocity cannot move them to another server instead. A proxy without that version, or with
 * {@code [networkKick]} off, drops the message unread; if the player is still here {@link #FALLBACK_TICKS} later (3 s),
 * this server kicks them itself. Used by the staff web panel's kick, the AFK kick and the failed presence check, and the
 * rules popup's LEAVE SERVER.
 */
public final class ProxyKick {
    public static final ResourceLocation CHANNEL = new ResourceLocation("bsp_server_utilities", "proxy");
    public static final int VERSION = 1, FALLBACK_TICKS = 60, MAX_REASON = 262_144;
    public static final String KIND_KICK = "kick";

    private static final class Pending {
        final Component reason;
        int ticksLeft = FALLBACK_TICKS;

        Pending(Component reason) {
            this.reason = reason;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static BiConsumer<ServerPlayer, byte[]> sender = ProxyKick::sendToProxy;
    private static BiConsumer<ServerPlayer, Component> disconnect = (p, why) -> p.connection.disconnect(why);
    private static BiFunction<MinecraftServer, UUID, ServerPlayer> lookup = (s, id) -> s.getPlayerList().getPlayer(id);

    private ProxyKick() {
    }

    /** Version 1: a byte 1, the kind "kick", the player's UUID, the reason as Component JSON (Velocity Additions' contract). */
    public static byte[] payload(UUID player, Component reason) {
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeByte(VERSION);
        b.writeUtf(KIND_KICK);
        b.writeUUID(player);
        b.writeUtf(Component.Serializer.toJson(reason), MAX_REASON);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private static void sendToProxy(ServerPlayer p, byte[] bytes) {
        p.connection.send(new ClientboundCustomPayloadPacket(CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))));
    }

    /** Asks the proxy to remove the player from the network; kicks them here in 3 s if they are still on this server. */
    public static void kick(ServerPlayer player, Component reason) {
        try {
            sender.accept(player, payload(player.getUUID(), reason));
        } catch (RuntimeException e) {
            disconnect.accept(player, reason); // the message could not go: kick here at once
            return;
        }
        PENDING.putIfAbsent(player.getUUID(), new Pending(reason)); // a second request keeps the first deadline (audit)
    }

    /** Every server tick: the fallback for players the proxy did not remove. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> e = it.next();
            if (--e.getValue().ticksLeft > 0) {
                continue;
            }
            it.remove();
            ServerPlayer p = lookup.apply(server, e.getKey());
            if (p != null) {
                disconnect.accept(p, e.getValue().reason);
            }
        }
    }

    /** A player who left (the proxy removed them, or they went themselves) needs no fallback. */
    public static void forget(UUID player) {
        PENDING.remove(player);
    }

    public static boolean pending(UUID player) {
        return PENDING.containsKey(player);
    }

    /** Tests: where the proxy message goes, how a fallback kick is made, and how players are found (null keeps one). */
    public static void useForTests(@Nullable BiConsumer<ServerPlayer, byte[]> send, @Nullable BiConsumer<ServerPlayer, Component> kick,
                                   @Nullable BiFunction<MinecraftServer, UUID, ServerPlayer> find) {
        if (send != null) {
            sender = send;
        }
        if (kick != null) {
            disconnect = kick;
        }
        if (find != null) {
            lookup = find;
        }
    }

    public static void useReal() {
        sender = ProxyKick::sendToProxy;
        disconnect = (p, why) -> p.connection.disconnect(why);
        lookup = (s, id) -> s.getPlayerList().getPlayer(id);
        PENDING.clear();
    }
}
