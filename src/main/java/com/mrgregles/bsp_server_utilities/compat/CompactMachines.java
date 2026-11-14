package com.mrgregles.bsp_server_utilities.compat;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import dev.compactmods.machines.api.dimension.CompactDimension;
import dev.compactmods.machines.api.room.upgrade.RoomUpgrade;
import dev.compactmods.machines.machine.CompactMachineBlockEntity;
import dev.compactmods.machines.machine.graph.DimensionMachineGraph;
import dev.compactmods.machines.room.data.CompactRoomData;
import dev.compactmods.machines.tunnel.TunnelHelper;
import dev.compactmods.machines.tunnel.graph.TunnelConnectionGraph;
import dev.compactmods.machines.upgrade.MachineRoomUpgrades;
import dev.compactmods.machines.upgrade.RoomUpgradeManager;
import dev.compactmods.machines.util.PlayerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The only class that touches Compact Machines (request R-2, the room limit; owner decisions D437 to D453), which is
 * optional. What it reads is Compact Machines 6.0.3's own saved data, not its api package, so it is used only when exactly
 * that version is loaded ({@link #VERSION}); with any other version, or after anything it calls fails, it stands aside
 * with one WARN and every answer is "nothing": no rooms, no machines, no connection.
 *
 * <p>Facts it relies on, read from the 6.0.3 jar (the assessment note in the vault, section 2): rooms are the saved data
 * {@code compactmachines_rooms} in the room dimension {@code compactmachines:compact_world}, one {@code RoomData} per
 * room chunk with its owner (the player who first entered the machine with the Personal Shrinking Device) and its size;
 * a placed machine knows its room through the per-dimension saved data {@code machine_connections}; a machine item names
 * its room by the int array {@code room_pos} (chunk x, z) in its tag and nothing else.
 *
 * <p>Compact Machines' classes are named only in {@link Cm}, which the JVM loads the first time one of its methods runs,
 * and every call goes through {@link #call} after {@link #present()}: without the mod the classes are never looked up.
 * Server thread only.
 */
public final class CompactMachines {
    public static final String MODID = "compactmachines";
    /** The build this class was written against: {@code cm_version} in gradle.properties. */
    public static final String VERSION = "6.0.3";
    /** The room dimension. */
    public static final ResourceKey<Level> ROOMS_LEVEL = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(MODID, "compact_world"));
    public static final ResourceLocation SHRINKING_DEVICE = new ResourceLocation(MODID, "personal_shrinking_device");
    /** The placeholder owner of a freed room's slot (D447): the slot stays registered so the spiral never reuses it; it counts for nobody. */
    public static final UUID NO_OWNER = new UUID(0L, 0L);
    /** The machine blocks (and their items), by registry path. */
    static final Set<String> MACHINES = Set.of("machine_tiny", "machine_small", "machine_normal", "machine_large", "machine_giant", "machine_maximum");
    static final String ROOM_TAG = "room_pos";

    /** A room: its chunk in the room dimension, its owner, its inside size in blocks (3 to 13) and its name if it has one. */
    public record Room(ChunkPos pos, UUID owner, int size, @Nullable String name) {
        /** A slot given up and kept for nobody (D447). */
        public boolean freed() {
            return NO_OWNER.equals(owner);
        }
    }

    /** Whether the installed Compact Machines can be read: null until first asked. */
    @Nullable
    private static Boolean usable;
    private static boolean failed;

    private CompactMachines() {
    }

    /** Why the installed Compact Machines is or is not read: "absent", "version X" (another build) or "ok". */
    public static String verdict(Optional<String> installedVersion) {
        if (installedVersion.isEmpty()) {
            return "absent";
        }
        return VERSION.equals(installedVersion.get()) ? "ok" : "version " + installedVersion.get();
    }

    /** Whether Compact Machines 6.0.3 is loaded and nothing read from it has failed. */
    public static boolean present() {
        if (failed) {
            return false;
        }
        if (usable == null) {
            ModList mods = ModList.get();
            Optional<String> installed = mods == null ? Optional.empty()
                    : mods.getModContainerById(MODID).map(c -> c.getModInfo().getVersion().toString());
            String verdict = verdict(installed);
            usable = verdict.equals("ok");
            if (verdict.startsWith("version")) {
                BSPServerUtilities.LOGGER.warn("Compact Machines {} is loaded, but the room limit reads only {}: rooms are not counted or limited", installed.get(), VERSION);
            }
        }
        return usable;
    }

    /** Runs a read of Compact Machines, or gives {@code none} when it is absent; a failure stands the adapter aside for good. */
    private static <T> T call(Supplier<T> read, T none) {
        if (!present()) {
            return none;
        }
        try {
            return read.get();
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            BSPServerUtilities.LOGGER.warn("Compact Machines could not be read; rooms are not counted or limited until the next start: {}", e.toString());
            return none;
        }
    }

    // ------------------------------------------------------------------ rooms

    /** Every registered room on this server, freed slots included. */
    public static List<Room> rooms(MinecraftServer server) {
        return call(() -> Cm.rooms(server), List.of());
    }

    /** One room, if it is registered. */
    public static Optional<Room> room(MinecraftServer server, ChunkPos pos) {
        return call(() -> Cm.room(server, pos), Optional.empty());
    }

    /** How many rooms each player owns on this server; freed slots count for nobody. */
    public static Map<UUID, Integer> countsByOwner(MinecraftServer server) {
        return counts(rooms(server));
    }

    /** How many rooms the player owns on this server. */
    public static int countOwnedBy(MinecraftServer server, UUID player) {
        return countsByOwner(server).getOrDefault(player, 0);
    }

    /** Rooms per owner, freed slots left out. */
    public static Map<UUID, Integer> counts(Collection<Room> rooms) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Room r : rooms) {
            if (r.owner() != null && !r.freed()) {
                out.merge(r.owner(), 1, Integer::sum);
            }
        }
        return out;
    }

    /** The room a position in the room dimension belongs to, if it is a registered room's chunk. */
    public static Optional<ChunkPos> roomAt(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(ROOMS_LEVEL)) {
            return Optional.empty();
        }
        ChunkPos chunk = new ChunkPos(pos);
        return call(() -> Cm.isRoom(level, chunk) ? Optional.of(chunk) : Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ machines

    /** Whether the block is one of Compact Machines' six machines. */
    public static boolean isMachine(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && MODID.equals(id.getNamespace()) && MACHINES.contains(id.getPath());
    }

    /** Whether the item is one of Compact Machines' six machine items. */
    public static boolean isMachineItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && MODID.equals(id.getNamespace()) && MACHINES.contains(id.getPath());
    }

    /** Whether the item is the Personal Shrinking Device, which makes a room when it enters a new machine. */
    public static boolean isShrinkingDevice(ItemStack stack) {
        return !stack.isEmpty() && SHRINKING_DEVICE.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    /** The room a placed machine is bound to; empty for an unbound machine or another block. */
    public static Optional<ChunkPos> connectedRoom(@Nullable BlockEntity machine) {
        if (machine == null) {
            return Optional.empty();
        }
        return call(() -> Cm.connectedRoom(machine), Optional.empty());
    }

    /** The room a machine item is bound to, from its {@code room_pos} tag; empty for an unbound machine or another item. */
    public static Optional<ChunkPos> itemRoom(ItemStack stack) {
        if (!isMachineItem(stack)) {
            return Optional.empty();
        }
        return roomTag(stack.getTag());
    }

    /** {@code room_pos} read from an item's tag: two ints, chunk x and z. */
    public static Optional<ChunkPos> roomTag(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(ROOM_TAG, Tag.TAG_INT_ARRAY)) {
            return Optional.empty();
        }
        int[] xz = tag.getIntArray(ROOM_TAG);
        return xz.length == 2 ? Optional.of(new ChunkPos(xz[0], xz[1])) : Optional.empty();
    }

    /** Every placed machine bound to the room, in every dimension of this server (unloaded chunks included). */
    public static List<GlobalPos> machinesFor(MinecraftServer server, ChunkPos room) {
        return call(() -> Cm.machinesFor(server, room), List.of());
    }

    // ------------------------------------------------------------------ clearing a room (SU-57)

    /** The wall block a tunnel sits in, and the plain wall that replaces it. */
    public static final ResourceLocation TUNNEL_WALL = new ResourceLocation(MODID, "tunnel_wall"), SOLID_WALL = new ResourceLocation(MODID, "solid_wall");

    /** The inside of a room (its walls are just outside these bounds). */
    public static Optional<AABB> roomBounds(MinecraftServer server, ChunkPos room) {
        return call(() -> Cm.bounds(server, room), Optional.empty());
    }

    /** Unbinds a placed machine from its room (Compact Machines' own {@code disconnect}). */
    public static void disconnect(@Nullable BlockEntity machine) {
        if (machine != null) {
            call(() -> {
                Cm.disconnect(machine);
                return null;
            }, null);
        }
    }

    /** Sends a player out of the room dimension, to their respawn point or the world spawn (Compact Machines' eject). */
    public static void sendOut(MinecraftServer server, ServerPlayer player) {
        call(() -> {
            Cm.sendOut(server, player);
            return null;
        }, null);
    }

    /**
     * Forgets a room whose inside was cleared, keeping its slot (D447; the assessment's 7.1): its tunnel records and
     * upgrades are dropped, it leaves every dimension's machine graph, and its record is replaced in the same tick by one
     * owned by {@link #NO_OWNER}, so the number of registered rooms never drops and the spiral never hands the slot out
     * again. False when the room is not registered or anything failed.
     */
    public static boolean forgetRoom(MinecraftServer server, ChunkPos room) {
        return call(() -> Cm.forget(server, room), false);
    }

    /** Compact Machines' own classes, named nowhere else; loaded only once {@link #present()} has said yes. */
    private static final class Cm {
        @Nullable
        private static CompactRoomData data(MinecraftServer server) {
            ServerLevel rooms = CompactDimension.forServer(server);
            return rooms == null ? null : CompactRoomData.get(rooms);
        }

        private static Room room(ChunkPos pos, CompactRoomData.RoomData r) {
            return new Room(pos, r.getOwner(), r.getSize().getInternalSize(), r.getName().orElse(null));
        }

        static List<Room> rooms(MinecraftServer server) {
            CompactRoomData data = data(server);
            if (data == null) {
                return List.of();
            }
            List<Room> out = new ArrayList<>();
            data.stream().forEach(pos -> data.forRoom(pos).ifPresent(r -> out.add(room(pos, r))));
            return out;
        }

        static Optional<Room> room(MinecraftServer server, ChunkPos pos) {
            CompactRoomData data = data(server);
            return data == null ? Optional.empty() : data.forRoom(pos).map(r -> room(pos, r));
        }

        static boolean isRoom(ServerLevel rooms, ChunkPos pos) {
            return CompactRoomData.get(rooms).isRegistered(pos);
        }

        static Optional<ChunkPos> connectedRoom(BlockEntity machine) {
            return machine instanceof CompactMachineBlockEntity m ? m.getConnectedRoom() : Optional.empty();
        }

        static Optional<AABB> bounds(MinecraftServer server, ChunkPos room) {
            CompactRoomData data = data(server);
            return data == null ? Optional.empty() : data.forRoom(room).map(CompactRoomData.RoomData::getRoomBounds);
        }

        /**
         * As Compact Machines' own {@code onRemove} and {@code disconnect}: out of the machine graph, its tunnels in the room
         * unregistered, and unbound. 6.0.3's {@code disconnect} ends by clearing a graph node that is null for a machine
         * whose chunk was just loaded (its {@code onLoad} waits for the next tick): the unbinding before it is done, so that
         * one null is caught here rather than standing the adapter aside.
         */
        static void disconnect(BlockEntity machine) {
            if (!(machine instanceof CompactMachineBlockEntity m) || !(m.getLevel() instanceof ServerLevel level)) {
                return;
            }
            Optional<ChunkPos> room = m.getConnectedRoom();
            ServerLevel compact = CompactDimension.forServer(level.getServer());
            if (room.isPresent() && compact != null) {
                TunnelConnectionGraph.forRoom(compact, room.get()).unregister(m.getBlockPos());
            }
            try {
                m.disconnect();
            } catch (NullPointerException e) {
                m.setChanged();
            }
        }

        static void sendOut(MinecraftServer server, ServerPlayer player) {
            PlayerUtil.teleportPlayerToRespawnOrOverworld(server, player);
        }

        static boolean forget(MinecraftServer server, ChunkPos room) {
            ServerLevel rooms = CompactDimension.forServer(server);
            if (rooms == null) {
                return false;
            }
            CompactRoomData data = CompactRoomData.get(rooms);
            Optional<CompactRoomData.RoomData> old = data.forRoom(room);
            if (old.isEmpty()) {
                return false;
            }
            TunnelConnectionGraph tunnels = TunnelConnectionGraph.forRoom(rooms, room);
            tunnels.clear();
            tunnels.setDirty();
            TunnelHelper.setChunkMode(rooms, room, false); // a room with tunnels is force-loaded by Compact Machines; nothing else lets it go
            RoomUpgradeManager upgrades = RoomUpgradeManager.get(rooms);
            for (RoomUpgrade u : MachineRoomUpgrades.REGISTRY.get().getValues()) {
                if (upgrades.hasUpgrade(room, u)) {
                    upgrades.removeUpgrade(u, room);
                    u.onRemoved(rooms, room);
                }
            }
            for (ServerLevel level : server.getAllLevels()) {
                DimensionMachineGraph graph = DimensionMachineGraph.forDimension(level);
                if (graph.getRoomNode(room).isPresent()) {
                    graph.removeRoom(room);
                    graph.setDirty();
                }
            }
            data.remove(room);
            try {
                data.createNew().owner(NO_OWNER).size(old.get().getSize()).chunk(room).register();
            } catch (Exception e) {
                // cannot happen right after the remove; were it to, the count would be one short: say so loudly
                BSPServerUtilities.LOGGER.error("Compact Machines room {} could not be kept for nobody after it was cleared; the next new room may be placed on an existing one: {}", room, e.toString());
                return false;
            }
            return true;
        }

        static List<GlobalPos> machinesFor(MinecraftServer server, ChunkPos room) {
            List<GlobalPos> out = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                for (BlockPos pos : DimensionMachineGraph.forDimension(level).getMachinesFor(room)) {
                    out.add(GlobalPos.of(level.dimension(), pos));
                }
            }
            return out;
        }
    }
}
