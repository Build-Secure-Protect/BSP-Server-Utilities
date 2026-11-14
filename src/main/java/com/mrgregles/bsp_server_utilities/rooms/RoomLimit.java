package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The room limit (SU-55; owner decisions D437, D440, D441, D442, D452 Z5). A room is made the first time a player
 * right-clicks a machine that has none with the Personal Shrinking Device; Compact Machines' {@code use} reads the main
 * hand whichever hand's click it is, so both hands' {@code RightClickBlock} events are looked at with the device in the
 * main hand. At or over the limit the click is cancelled before Compact Machines sees it (nothing is made, the machine
 * stays unbound) and the player reads one action-bar line, at most once a second. Entering a machine that has a room
 * is never refused; an owner over the limit entering one of their own rooms is told in chat, at most once a minute.
 * Operators and the configured LuckPerms group are never limited. Server thread only.
 */
public final class RoomLimit {
    static final long REFUSED_EVERY_MS = 1000, WARNED_EVERY_MS = 60_000;
    private static final Map<UUID, Long> REFUSED = new HashMap<>(), WARNED = new HashMap<>();
    private static LongSupplier clock = System::currentTimeMillis;

    /** What a click on a machine was. */
    public enum Outcome {
        /** Not a machine, not the device in the main hand, or Compact Machines not read: nothing to do. */
        NONE,
        /** A machine with a room: the player enters it. */
        ENTER,
        /** A new room, under the limit, unlimited or exempt. */
        NEW_ALLOWED,
        /** A new room at or over the limit: refused. */
        REFUSED
    }

    private RoomLimit() {
    }

    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener(RoomLimit::onRightClick);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> forget(e.getEntity().getUUID()));
    }

    static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (click(player, e.getLevel(), e.getPos()) == Outcome.REFUSED) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
        }
    }

    /** Looks at a click on {@code pos}, refusing, warning and noting a change as it says. */
    public static Outcome click(ServerPlayer player, Level level, BlockPos pos) {
        if (!CompactMachines.present() || !CompactMachines.isMachine(level.getBlockState(pos)) || !CompactMachines.isShrinkingDevice(player.getMainHandItem())) {
            return Outcome.NONE;
        }
        MinecraftServer server = player.server;
        RoomSettings s = RoomSettings.current();
        Optional<ChunkPos> room = CompactMachines.connectedRoom(level.getBlockEntity(pos));
        if (room.isPresent() && CompactMachines.room(server, room.get()).map(CompactMachines.Room::freed).orElse(false)) {
            // Z8: bound to a room that was cleared (copies cannot be found): a fresh machine, which makes a new room under the limit
            CompactMachines.disconnect(level.getBlockEntity(pos));
            room = Optional.empty();
        }
        if (room.isPresent()) {
            warnIfOver(player, server, room.get(), s);
            return Outcome.ENTER;
        }
        boolean exempt = exempt(player.hasPermissions(2), LuckPermsBridge.groupsOf(player.getUUID()), s.exemptGroup());
        int count = s.limited() && !exempt ? RoomList.count(server, player.getUUID()) : 0;
        if (refuses(s.limited(), exempt, count, s.limit())) {
            if (due(REFUSED, player.getUUID(), clock.getAsLong(), REFUSED_EVERY_MS)) {
                player.displayClientMessage(refusal(count, s.limit()), true);
            }
            return Outcome.REFUSED;
        }
        RoomList.changed(); // the room Compact Machines is about to make is read and shared at the next tick
        return Outcome.NEW_ALLOWED;
    }

    private static void warnIfOver(ServerPlayer player, MinecraftServer server, ChunkPos room, RoomSettings s) {
        if (!s.limited() || exempt(player.hasPermissions(2), LuckPermsBridge.groupsOf(player.getUUID()), s.exemptGroup())) {
            return;
        }
        Optional<CompactMachines.Room> read = CompactMachines.room(server, room);
        if (read.isEmpty() || !player.getUUID().equals(read.get().owner())) {
            return; // someone else's room: their count is not this player's business
        }
        int count = RoomList.count(server, player.getUUID());
        if (count > s.limit() && due(WARNED, player.getUUID(), clock.getAsLong(), WARNED_EVERY_MS)) {
            int over = count - s.limit();
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.rooms.over", count, s.limit(), over).withStyle(ChatFormatting.GOLD));
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.rooms.over_hint", over).withStyle(ChatFormatting.GOLD));
        }
    }

    // ------------------------------------------------------------------ the rules

    /** Operators, and members of the configured group (case ignored), are never limited (D441). */
    public static boolean exempt(boolean operator, Set<String> groups, String group) {
        return operator || (!group.isEmpty() && groups.contains(group.toLowerCase(Locale.ROOT)));
    }

    /** Whether a new room is refused: a limit set, the player not exempt, and their count at or over it. */
    public static boolean refuses(boolean limited, boolean exempt, int count, int limit) {
        return limited && !exempt && count >= limit;
    }

    /** How many rooms must be given back before one can be made: down to one under the limit. */
    public static int toGiveBack(int count, int limit) {
        return Math.max(0, count - limit + 1);
    }

    /** The action-bar line (D442): at the limit, give one back; over it, how many. */
    public static Component refusal(int count, int limit) {
        if (count <= limit) {
            return Component.translatable("message.bsp_server_utilities.rooms.refused_full", Component.literal(String.valueOf(count)).withStyle(ChatFormatting.YELLOW),
                    Component.literal(String.valueOf(limit)).withStyle(ChatFormatting.YELLOW));
        }
        return Component.translatable("message.bsp_server_utilities.rooms.refused_over", Component.literal(String.valueOf(count)).withStyle(ChatFormatting.YELLOW),
                Component.literal(String.valueOf(limit)).withStyle(ChatFormatting.YELLOW), toGiveBack(count, limit));
    }

    /** Whether a line for this player is due, and if so notes the time. */
    static boolean due(Map<UUID, Long> last, UUID player, long now, long every) {
        Long at = last.get(player);
        if (at != null && now - at < every) {
            return false;
        }
        last.put(player, now);
        return true;
    }

    static void forget(UUID player) {
        REFUSED.remove(player);
        WARNED.remove(player);
    }

    // ------------------------------------------------------------------ tests

    /** A test clock (null: the real one); forgets every line's time. */
    public static void resetForTests(@org.jetbrains.annotations.Nullable LongSupplier testClock) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        REFUSED.clear();
        WARNED.clear();
    }

    /** When the player was last told they are refused or over the limit (tests); null for never. */
    public static Long refusedAt(UUID player) {
        return REFUSED.get(player);
    }

    public static Long warnedAt(UUID player) {
        return WARNED.get(player);
    }
}
