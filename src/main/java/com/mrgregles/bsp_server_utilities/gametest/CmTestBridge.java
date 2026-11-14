package com.mrgregles.bsp_server_utilities.gametest;

import dev.compactmods.machines.api.room.RoomSize;
import dev.compactmods.machines.machine.CompactMachineBlockEntity;
import dev.compactmods.machines.machine.CompactMachineItem;
import dev.compactmods.machines.room.Rooms;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

/**
 * Compact Machines' own classes for the room tests, named here only, so the test classes load without the mod; a test
 * touches this class only after it has seen that Compact Machines is loaded ({@code -PwithCm}).
 */
final class CmTestBridge {
    private CmTestBridge() {
    }

    /** A new normal-sized room owned by {@code owner}, made as entering a new machine makes it. */
    static ChunkPos newRoom(MinecraftServer server, UUID owner) {
        try {
            return Rooms.createNew(server, RoomSize.NORMAL, owner);
        } catch (Throwable t) {
            throw new AssertionError("could not make a room: " + t, t);
        }
    }

    /** Binds a placed machine to a room, as entering a new machine does. */
    static void bind(BlockEntity machine, ChunkPos room) {
        ((CompactMachineBlockEntity) machine).setConnectedRoom(room);
    }

    /** The room's centre (inside). */
    static net.minecraft.core.BlockPos centre(MinecraftServer server, ChunkPos room) {
        try {
            return dev.compactmods.machines.room.data.CompactRoomData.get(dev.compactmods.machines.api.dimension.CompactDimension.forServer(server)).getData(room).getCenter();
        } catch (Throwable t) {
            throw new AssertionError("no such room: " + t, t);
        }
    }

    /** Adds the chunk-loading upgrade to a room, as the admin command does. */
    static void addChunkloadUpgrade(net.minecraft.server.level.ServerLevel rooms, ChunkPos room) {
        dev.compactmods.machines.upgrade.RoomUpgradeManager.get(rooms).addUpgrade(dev.compactmods.machines.upgrade.MachineRoomUpgrades.CHUNKLOAD.get(), room);
        dev.compactmods.machines.upgrade.MachineRoomUpgrades.CHUNKLOAD.get().onAdded(rooms, room);
    }

    static boolean hasChunkloadUpgrade(net.minecraft.server.level.ServerLevel rooms, ChunkPos room) {
        return dev.compactmods.machines.upgrade.RoomUpgradeManager.get(rooms).hasUpgrade(room, dev.compactmods.machines.upgrade.MachineRoomUpgrades.CHUNKLOAD.get());
    }

    /** Force-loads a room as Compact Machines does for one with tunnels. */
    static void forceForTunnels(net.minecraft.server.level.ServerLevel rooms, ChunkPos room) {
        dev.compactmods.machines.tunnel.TunnelHelper.setChunkMode(rooms, room, true);
    }

    /** Binds a machine item to a room, as the machine's own drop does. */
    static void bindItem(ItemStack stack, ChunkPos room) {
        CompactMachineItem.setRoom(stack, room);
    }
}
