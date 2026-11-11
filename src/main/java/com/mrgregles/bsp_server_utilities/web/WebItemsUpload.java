package com.mrgregles.bsp_server_utilities.web;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.IconsAskPacket;
import com.mrgregles.bsp_server_utilities.network.IconsBeginPacket;
import com.mrgregles.bsp_server_utilities.network.IconsPartPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * The staff web panel's item pictures (SU-41; D271, D340, D373): {@code /su icons}, typed by an admin in their own game,
 * gives them a one-time ticket; their game draws every item of the creative tabs, then sends a zip (the pictures, the tabs,
 * the English names) back in pieces under that ticket. This server checks the sender is still an admin with that ticket,
 * the pieces come in order, the size stays under {@link #MAX_SIZE} and the whole matches the SHA-256 announced, then
 * stores it in the shared database ({@code <prefix>web_items}, {@code <prefix>web_item_parts}) for the panel to read.
 * Nothing goes to the panel any other way, and nothing else is done with the zip here.
 */
public final class WebItemsUpload {
    public static final int MAX_SIZE = 48 * 1024 * 1024, PIECE = 30_000;
    public static final long TICKET_MS = 15 * 60_000L;

    record Ticket(String id, long until) {
    }

    static final class Upload {
        final String ticket, sha256;
        final int size, items, tabs, mods;
        final ByteArrayOutputStream bytes;
        int next;

        Upload(IconsBeginPacket b) {
            ticket = b.ticket();
            sha256 = b.sha256();
            size = b.size();
            items = b.items();
            tabs = b.tabs();
            mods = b.mods();
            bytes = new ByteArrayOutputStream(b.size());
        }
    }

    private static final Map<UUID, Ticket> TICKETS = new ConcurrentHashMap<>();
    private static final Map<UUID, Upload> UPLOADS = new ConcurrentHashMap<>();
    private static LongSupplier clock = System::currentTimeMillis;
    private static BiConsumer<ServerPlayer, Object> sender = ServerUtilsNetwork::sendTo;
    private static BiConsumer<ServerPlayer, Component> tell = ServerPlayer::sendSystemMessage;

    private WebItemsUpload() {
    }

    private static Component gold(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("message.bsp_server_utilities.icons." + key, fallback, args).withStyle(ChatFormatting.GOLD);
    }

    /** {@code /su icons}: a ticket for this admin and the ask to their game. */
    public static boolean ask(ServerPlayer p) {
        if (!Perms.canEdit(p)) {
            return false;
        }
        if (Storage.active() == null) {
            tell.accept(p, gold("no_storage", "The item pictures need the shared database: this server keeps its records alone."));
            return false;
        }
        String id = UUID.randomUUID().toString();
        TICKETS.put(p.getUUID(), new Ticket(id, clock.getAsLong() + TICKET_MS));
        UPLOADS.remove(p.getUUID());
        sender.accept(p, new IconsAskPacket(id));
        tell.accept(p, gold("asked", "Drawing the item pictures in your game; you can keep playing. They go to the staff panel when done."));
        return true;
    }

    private static boolean ticketOk(ServerPlayer p, String ticket) {
        Ticket t = TICKETS.get(p.getUUID());
        return t != null && t.id().equals(ticket) && t.until() >= clock.getAsLong() && Perms.canEdit(p);
    }

    private static void refuse(ServerPlayer p, String why) {
        TICKETS.remove(p.getUUID());
        UPLOADS.remove(p.getUUID());
        tell.accept(p, gold("refused", "The item pictures were not taken: %s", why));
    }

    public static void begin(ServerPlayer p, IconsBeginPacket b) {
        if (!ticketOk(p, b.ticket())) {
            return; // not asked for (or no longer an admin): ignored
        }
        if (b.size() <= 0 || b.size() > MAX_SIZE || !b.sha256().matches("[0-9a-f]{64}") || b.items() < 1) {
            refuse(p, "too big, or not an export (" + b.size() + " bytes).");
            return;
        }
        UPLOADS.put(p.getUUID(), new Upload(b));
    }

    public static void part(ServerPlayer p, IconsPartPacket m) {
        Upload u = UPLOADS.get(p.getUUID());
        if (u == null || !u.ticket.equals(m.ticket()) || !ticketOk(p, m.ticket())) {
            return;
        }
        if (m.index() != u.next || u.bytes.size() + m.data().length > u.size) {
            refuse(p, "a piece came out of order.");
            return;
        }
        u.bytes.write(m.data(), 0, m.data().length);
        u.next++;
        if (u.bytes.size() == u.size) {
            UPLOADS.remove(p.getUUID());
            TICKETS.remove(p.getUUID());
            finish(p, u);
        }
    }

    private static void finish(ServerPlayer p, Upload u) {
        byte[] all = u.bytes.toByteArray();
        if (!sha256(all).equals(u.sha256)) {
            tell.accept(p, gold("refused", "The item pictures were not taken: %s", "they did not arrive whole; type /su icons again."));
            return;
        }
        Storage s = Storage.active();
        if (s == null) {
            tell.accept(p, gold("no_storage", "The item pictures need the shared database: this server keeps its records alone."));
            return;
        }
        List<byte[]> parts = new ArrayList<>();
        for (int at = 0; at < all.length; at += StorageBackend.WEB_ITEMS_PART) {
            parts.add(java.util.Arrays.copyOfRange(all, at, Math.min(all.length, at + StorageBackend.WEB_ITEMS_PART)));
        }
        long now = clock.getAsLong();
        String name = p.getGameProfile().getName();
        StorageBackend.WebItemsExport e = new StorageBackend.WebItemsExport(now, now, name, s.serverId(), u.items, u.tabs, u.mods, all.length, parts.size(), u.sha256);
        MinecraftServer server = p.getServer();
        UUID who = p.getUUID();
        s.submit(() -> {
            try {
                s.backend().saveWebItems(e, parts);
            } catch (Exception x) {
                if (server != null) {
                    server.execute(() -> {
                        ServerPlayer q = server.getPlayerList().getPlayer(who);
                        if (q != null) {
                            tell.accept(q, gold("refused", "The item pictures were not taken: %s", "the database refused them (" + x.getMessage() + ")."));
                        }
                    });
                }
                throw x;
            }
            if (server != null) {
                server.execute(() -> {
                    ActionLog.record(server, ActionLog.entryForServer(name, LogEntry.CMD, "su icons: " + u.items + " item pictures in " + u.tabs + " tabs to the staff panel"));
                    ServerPlayer q = server.getPlayerList().getPlayer(who);
                    if (q != null) {
                        tell.accept(q, gold("done", "Done: %s items in %s tabs are on the staff panel.", u.items, u.tabs));
                    }
                });
            }
        });
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A player left: their ticket and half-sent export go. */
    public static void forget(UUID player) {
        TICKETS.remove(player);
        UPLOADS.remove(player);
    }

    public static void useForTests(@Nullable LongSupplier testClock, @Nullable BiConsumer<ServerPlayer, Object> sent, @Nullable BiConsumer<ServerPlayer, Component> shown) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        sender = sent != null ? sent : ServerUtilsNetwork::sendTo;
        tell = shown != null ? shown : ServerPlayer::sendSystemMessage;
        TICKETS.clear();
        UPLOADS.clear();
    }
}
