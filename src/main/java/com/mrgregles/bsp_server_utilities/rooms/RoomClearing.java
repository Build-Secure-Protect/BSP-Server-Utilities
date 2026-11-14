package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Clearing a room at the end of its grace (SU-57; owner decisions D446, D447, D449, D450, D452 Z8). Every minute the rooms
 * whose grace has ended are cleared, at most {@value #PER_CHECK} at a time, soonest first, on the server thread:
 * <ol>
 *   <li>a room with a placed machine bound to it again is not cleared but taken out of grace;</li>
 *   <li>its chunk is loaded; players in it are sent to their respawn point (Compact Machines' own eject);</li>
 *   <li>the placed machines inside are noted, every block inside is set to air with its block entity removed first, so
 *   containers drop nothing (D449: what was inside is gone), and tunnels in its walls become plain wall again;</li>
 *   <li>what is left inside (dropped items, mobs, frames) is removed; a dropped machine item there counts as lost;</li>
 *   <li>Compact Machines forgets the room's tunnels, upgrades and graph node, and its slot is kept for nobody (D447);</li>
 *   <li>each room whose machine stood inside goes into grace itself unless another machine is still bound to it (D450).</li>
 * </ol>
 * A player who logs in standing in a room kept for nobody is sent out the same way.
 */
public final class RoomClearing {
    static final int CHECK_TICKS = 1200, PER_CHECK = 4;
    /** Clients updated, no neighbour updates: nothing next to the room reacts, nothing drops. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /**
     * Keeps a room's chunk loaded until its entities have loaded too (they load on another thread since 1.17): asked at
     * one check, cleared at the next. It lapses by itself after two checks.
     */
    private static final net.minecraft.server.level.TicketType<ChunkPos> LOADING = net.minecraft.server.level.TicketType.create(
            "bsp_server_utilities:room_clear", Comparator.comparingLong(ChunkPos::toLong), 2 * CHECK_TICKS + 200);
    /** Rooms whose clear failed since the start: not tried again until the next start (logged once each). */
    private static final java.util.Set<ChunkPos> FAILED = new java.util.HashSet<>();
    private static int ticks;

    private RoomClearing() {
    }

    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && mustLeave(p.server, p.serverLevel(), p.blockPosition())) {
                CompactMachines.sendOut(p.server, p);
            }
        });
    }

    public static void tick(MinecraftServer server) {
        if (++ticks < CHECK_TICKS) {
            return;
        }
        ticks = 0;
        sweep(server);
    }

    /** Clears the rooms whose grace has ended (at most {@value #PER_CHECK}); how many were cleared. */
    public static int sweep(MinecraftServer server) {
        if (!CompactMachines.present()) {
            return 0;
        }
        int n = 0;
        List<RoomGrace.Entry> waiting = RoomGrace.get(server).all().stream().filter(e -> !FAILED.contains(e.room())).toList();
        for (RoomGrace.Entry e : due(waiting, RoomLosses.now(), PER_CHECK)) {
            try {
                if (clear(server, e.room())) {
                    n++;
                }
            } catch (RuntimeException | LinkageError x) {
                // a block of another mod that throws on removal must not stop the server, nor be retried every minute
                FAILED.add(e.room());
                BSPServerUtilities.LOGGER.error("Compact Machines room {} of {} could not be cleared; it stays in grace and is tried again after the next start", e.room(), e.owner(), x);
            }
        }
        return n;
    }

    /** The entries whose grace has ended at {@code now}, soonest first, at most {@code max}. */
    public static List<RoomGrace.Entry> due(List<RoomGrace.Entry> all, long now, int max) {
        return all.stream().filter(e -> e.until() <= now).sorted(Comparator.comparingLong(RoomGrace.Entry::until)).limit(max).toList();
    }

    /** Whether a player standing at {@code pos} is in a room kept for nobody (cleared while they were away). */
    public static boolean mustLeave(MinecraftServer server, ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(CompactMachines.ROOMS_LEVEL)) {
            return false;
        }
        return CompactMachines.room(server, new ChunkPos(pos)).map(CompactMachines.Room::freed).orElse(false);
    }

    /** Clears one room in grace; false when it was not cleared (not in grace, gone, revived, or Compact Machines failed). */
    public static boolean clear(MinecraftServer server, ChunkPos room) {
        RoomGrace grace = RoomGrace.get(server);
        Optional<RoomGrace.Entry> entry = grace.get(room);
        ServerLevel rooms = server.getLevel(CompactMachines.ROOMS_LEVEL);
        if (entry.isEmpty() || rooms == null || !CompactMachines.present()) {
            return false;
        }
        if (!CompactMachines.machinesFor(server, room).isEmpty()) {
            grace.remove(room); // a machine bound to it was placed: it was never really lost
            RoomList.changed();
            return false;
        }
        Optional<AABB> bounds = CompactMachines.roomBounds(server, room);
        if (bounds.isEmpty()) {
            if (CompactMachines.present()) { // the room is gone; when the adapter failed, nothing is known: kept for the next start
                grace.remove(room);
                RoomList.changed();
            }
            return false;
        }
        if (!rooms.areEntitiesLoaded(room.toLong())) {
            rooms.getChunkSource().addRegionTicket(LOADING, room, 1, room); // its entities load in the background: cleared at the next check
            return false;
        }
        AABB inside = bounds.get(), shell = inside.inflate(1);

        for (ServerPlayer p : rooms.getEntitiesOfClass(ServerPlayer.class, shell)) {
            CompactMachines.sendOut(server, p);
        }

        List<ChunkPos> nested = new ArrayList<>();
        int blocks = 0;
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(inside.minX), Mth.floor(inside.minY), Mth.floor(inside.minZ),
                Mth.ceil(inside.maxX) - 1, Mth.ceil(inside.maxY) - 1, Mth.ceil(inside.maxZ) - 1)) {
            BlockState state = rooms.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (CompactMachines.isMachine(state)) {
                CompactMachines.connectedRoom(rooms.getBlockEntity(pos)).ifPresent(r -> nested.add(r));
                CompactMachines.disconnect(rooms.getBlockEntity(pos)); // unbound here: removing its block entity first skips Compact Machines' own
            }
            if (rooms.getBlockEntity(pos) != null) {
                rooms.removeBlockEntity(pos); // first, so a container's contents are not dropped
            }
            rooms.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
            blocks++;
        }

        Block tunnel = ForgeRegistries.BLOCKS.getValue(CompactMachines.TUNNEL_WALL);
        Block wall = ForgeRegistries.BLOCKS.getValue(CompactMachines.SOLID_WALL);
        if (tunnel != null && wall != null && tunnel != Blocks.AIR && wall != Blocks.AIR) {
            for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(shell.minX), Mth.floor(shell.minY), Mth.floor(shell.minZ),
                    Mth.ceil(shell.maxX) - 1, Mth.ceil(shell.maxY) - 1, Mth.ceil(shell.maxZ) - 1)) {
                if (rooms.getBlockState(pos).is(tunnel)) {
                    rooms.removeBlockEntity(pos);
                    rooms.setBlock(pos, wall.defaultBlockState(), FLAGS);
                }
            }
        }

        for (Entity e : rooms.getEntities((Entity) null, shell, e -> !(e instanceof Player))) {
            if (e instanceof ItemEntity) {
                RoomLosses.itemGone(e, "inside"); // a bound machine left lying there is lost with the room
            }
            e.discard();
        }

        boolean kept = CompactMachines.forgetRoom(server, room);
        rooms.getChunkSource().removeRegionTicket(LOADING, room, 1, room);
        if (kept) {
            grace.remove(room);
        } else {
            FAILED.add(room); // emptied, but still its owner's record: it stays in grace (counting for nobody) until tried again after a start
        }
        RoomList.changed();
        for (ChunkPos n : nested) {
            if (!n.equals(room)) {
                RoomLosses.lose(server, n, "inside");
            }
        }
        BSPServerUtilities.LOGGER.info("Compact Machines room {} of {} cleared after its grace ({}): {} blocks removed, {} rooms of machines inside put in grace{}",
                room, entry.get().owner(), entry.get().reason(), blocks, nested.size(), kept ? "" : "; its record could not be kept for nobody");
        return true;
    }

    /** Tests: the next tick runs the check. */
    public static void dueNextTickForTests() {
        ticks = CHECK_TICKS - 1;
    }

    /** Tests: whether a room's clear failed since the start, and a clean slate. */
    public static boolean failedForTests(ChunkPos room) {
        return FAILED.contains(room);
    }

    public static void resetForTests() {
        FAILED.clear();
    }
}
