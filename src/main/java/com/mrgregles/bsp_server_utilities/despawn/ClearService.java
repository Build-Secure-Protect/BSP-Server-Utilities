package com.mrgregles.bsp_server_utilities.despawn;

import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Clearing dropped items (the owner's addition D110; decisions D112 to D116, D118): an admin picks this server, every
 * server or one by name; each server chosen warns its players at 60 and at 30 seconds, then removes every dropped item
 * and experience orb it has loaded (mod-timed items, BSP-Core's and never exceptions kept), remembers the time so items
 * in chunks not loaded go when their chunk next loads, and reports its count to the admin, wherever they are. A clear
 * started during a countdown replaces it.
 */
public final class ClearService {
    public static final String KIND_CLEAR = "clear_items", KIND_DONE = "clear_done", ALL = "*";
    public static final int COUNTDOWN_TICKS = 1200, SECOND_WARNING_TICKS = 600;
    private static final String SEP = "|";

    private record Countdown(String id, String adminServer, String adminId, int left) {
    }

    @Nullable
    private static Countdown countdown;
    /** Tests: where reports for this server's admins go, instead of the player list. */
    @Nullable
    private static Consumer<Component> reportOverride;

    private ClearService() {
    }

    /** Starts a clear on {@code target} ("here", "all", or a server's name); returns the server name or {@link #ALL} it went to. */
    public static String start(MinecraftServer server, String target, @Nullable ServerPlayer admin) {
        String here = Storage.currentServerId();
        String to = target.equalsIgnoreCase("all") ? ALL : target.equalsIgnoreCase("here") || target.isBlank() ? here : target;
        String id = UUID.randomUUID().toString();
        String adminId = admin == null ? "" : admin.getUUID().toString();
        if (to.equals(ALL) || to.equals(here)) {
            begin(server, id, here, adminId);
        }
        Storage s = Storage.active();
        if (s != null && !to.equals(here)) {
            s.publish(KIND_CLEAR, String.join(SEP, id, to, here, adminId));
        }
        return to;
    }

    private static void begin(MinecraftServer server, String id, String adminServer, String adminId) {
        countdown = new Countdown(id, adminServer, adminId, COUNTDOWN_TICKS);
        warn(server, 60);
    }

    private static void warn(MinecraftServer server, int seconds) {
        server.getPlayerList().broadcastSystemMessage(Component.translatable("message.bsp_server_utilities.clear.warn", seconds).withStyle(ChatFormatting.RED), false);
    }

    /** Whether a countdown is running here. */
    public static boolean counting() {
        return countdown != null;
    }

    public static void tick(MinecraftServer server) {
        Countdown c = countdown;
        if (c == null) {
            return;
        }
        int left = c.left() - 1;
        if (left == SECOND_WARNING_TICKS) {
            warn(server, 30);
        }
        if (left <= 0) {
            countdown = null;
            clearNow(server, c.id(), c.adminServer(), c.adminId());
        } else {
            countdown = new Countdown(c.id(), c.adminServer(), c.adminId(), left);
        }
    }

    /** The clear itself (tests call it without the countdown); returns {items, orbs}. */
    public static int[] clearNow(MinecraftServer server, String id, String adminServer, String adminId) {
        DespawnSettings s = DespawnService.settings(server);
        int items = 0, orbs = 0;
        for (Entity e : DespawnService.dropped()) {
            if (DespawnService.clearKeeps(e, s)) {
                continue;
            }
            if (e instanceof ExperienceOrb) {
                orbs++;
            } else {
                items++;
            }
            DespawnService.count(server, e);
            e.discard();
            DespawnService.onLeave(e);
        }
        DespawnData.get(server).setClearedBefore(DespawnService.now(server));
        server.getPlayerList().broadcastSystemMessage(Component.translatable("message.bsp_server_utilities.clear.done", items + orbs).withStyle(ChatFormatting.GOLD), false);
        report(server, adminServer, adminId, Storage.currentServerId(), items, orbs);
        return new int[]{items, orbs};
    }

    private static void report(MinecraftServer server, String adminServer, String adminId, String from, int items, int orbs) {
        if (adminServer.equals(Storage.currentServerId())) {
            Component line = Component.translatable("message.bsp_server_utilities.clear.report", from, items, orbs).withStyle(ChatFormatting.GRAY);
            if (reportOverride != null) {
                reportOverride.accept(line);
                return;
            }
            try {
                ServerPlayer admin = adminId.isEmpty() ? null : server.getPlayerList().getPlayer(UUID.fromString(adminId));
                if (admin != null) {
                    admin.sendSystemMessage(line);
                } else {
                    server.sendSystemMessage(line);
                }
            } catch (IllegalArgumentException ignored) {
                server.sendSystemMessage(line);
            }
            return;
        }
        Storage s = Storage.active();
        if (s != null) {
            s.publish(KIND_DONE, String.join(SEP, from, Integer.toString(items), Integer.toString(orbs), adminServer, adminId));
        }
    }

    public static void registerNotices() {
        Storage.on(KIND_CLEAR, (srv, n) -> {
            String[] f = n.payload().split("\\|", -1);
            if (f.length == 4 && (f[1].equals(ALL) || f[1].equals(Storage.currentServerId())) && !f[2].equals(Storage.currentServerId())) {
                begin(srv, f[0], f[2], f[3]);
            }
        });
        Storage.on(KIND_DONE, (srv, n) -> {
            String[] f = n.payload().split("\\|", -1);
            if (f.length == 5 && f[3].equals(Storage.currentServerId())) {
                try {
                    report(srv, f[3], f[4], f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                } catch (NumberFormatException ignored) {
                    // a bad notice
                }
            }
        });
    }

    public static void onServerStarted() {
        countdown = null;
    }

    /** Tests: stops a countdown without clearing. */
    public static void cancelForTests() {
        countdown = null;
    }

    public static void useForTests(@Nullable Consumer<Component> reports) {
        reportOverride = reports;
    }
}
