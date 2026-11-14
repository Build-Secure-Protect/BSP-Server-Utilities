package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Telling an owner their room went into grace (owner decision D452 Z6): at once when they are online on the room's
 * server, otherwise a few seconds after their next login on any server. A server that tells an owner about another
 * server's room sends that server {@value #KIND_TOLD}, which marks the room told so nobody is told twice.
 */
public final class RoomNotices {
    public static final String KIND_TOLD = "cm_told";
    /** Ticks after login before the lines show (after the join messages and the inventory's arrival). */
    static final int LOGIN_DELAY_TICKS = 60;
    private static final Map<UUID, Integer> PENDING = new HashMap<>();
    /** Other servers' rooms this server has told their owners about since it started (server|x|z). */
    private static final Set<String> TOLD_ELSEWHERE = new HashSet<>();

    private RoomNotices() {
    }

    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                PENDING.put(p.getUUID(), LOGIN_DELAY_TICKS);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> PENDING.remove(e.getEntity().getUUID()));
    }

    public static void registerNotices() {
        Storage.on(KIND_TOLD, (server, n) -> {
            String[] p = n.payload().split("\n");
            if (p.length < 3 || !p[0].equalsIgnoreCase(Storage.currentServerId())) {
                return;
            }
            try {
                if (RoomGrace.get(server).markTold(new ChunkPos(Integer.parseInt(p[1]), Integer.parseInt(p[2])))) {
                    RoomList.changed();
                }
            } catch (NumberFormatException ignored) {
                // not ours
            }
        });
    }

    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Integer>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            if (e.getValue() > 1) {
                e.setValue(e.getValue() - 1);
                continue;
            }
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) {
                deliver(server, p);
            }
        }
    }

    /** Tells the player about each of their rooms in grace they have not heard of, here and on other servers; how many. */
    public static int deliver(MinecraftServer server, ServerPlayer player) {
        String self = Storage.currentServerId();
        long now = RoomLosses.now();
        int told = 0;
        RoomGrace grace = RoomGrace.get(server);
        for (RoomGrace.Entry e : grace.all()) {
            if (e.owner().equals(player.getUUID()) && !e.told()) {
                tell(player, self, e.size(), e.reason(), e.until(), now);
                grace.markTold(e.room());
                RoomList.changed();
                told++;
            }
        }
        for (RoomRow r : RoomList.table().rows()) {
            String key = r.server() + "|" + r.x() + "|" + r.z();
            if (r.server().equalsIgnoreCase(self) || !r.owner().equals(player.getUUID()) || !RoomRow.GRACE.equals(r.state()) || r.told() || TOLD_ELSEWHERE.contains(key)) {
                continue;
            }
            tell(player, r.server(), r.size(), r.reason(), r.graceUntil(), now);
            TOLD_ELSEWHERE.add(key);
            RoomOutbox.send(KIND_TOLD, r.server(), r.x(), r.z(), r.server() + "\n" + r.x() + "\n" + r.z(), now);
            told++;
        }
        return told;
    }

    /** The two lines: which room, why (given back, freed by staff, or how its machine was lost), and how long until it is cleared. */
    public static void tell(ServerPlayer player, String server, int size, String reason, long until, long now) {
        player.sendSystemMessage(lead(server, size, reason).copy().withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.rooms.lost_hint", daysLeft(until, now)).withStyle(ChatFormatting.GRAY));
    }

    /** The first line: given back, freed by staff (never naming them, R7), or how the machine was lost. */
    public static Component lead(String server, int size, String reason) {
        return switch (reason) {
            case "given_back" -> Component.translatable("message.bsp_server_utilities.rooms.given_back", server, sizeName(size));
            case "freed" -> Component.translatable("message.bsp_server_utilities.rooms.freed", server, sizeName(size)); // without the staff member's name (R7)
            default -> Component.translatable("message.bsp_server_utilities.rooms.lost", server, sizeName(size), Component.translatable("message.bsp_server_utilities.rooms.reason." + RoomLosses.known(reason)));
        };
    }

    /** Whole days until {@code until}, at least 1. */
    public static long daysLeft(long until, long now) {
        return Math.max(1, (until - now + RoomLosses.DAY_MS - 1) / RoomLosses.DAY_MS);
    }

    static Component sizeName(int size) {
        return switch (size) {
            case 3, 5, 7, 9, 11, 13 -> Component.translatable("message.bsp_server_utilities.rooms.size." + size);
            default -> Component.literal(String.valueOf(size));
        };
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests() {
        PENDING.clear();
        TOLD_ELSEWHERE.clear();
    }

    /** Pretends the player just logged in (tests: mock players never do). */
    public static void loggedInForTests(UUID player) {
        PENDING.put(player, LOGIN_DELAY_TICKS);
    }

    public static boolean pending(UUID player) {
        return PENDING.containsKey(player);
    }
}
