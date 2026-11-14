package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SU-53, the Compact Machines adapter (request R-2): the version it reads, how rooms are counted, a machine item's room
 * tag, and that it stands aside without the mod. With {@code -PwithCm} (the pack's exact Compact Machines, in
 * {@code run/world_cm}) a real room is made and real machine blocks are placed, bound and broken. Rooms are never deleted
 * by Compact Machines, so the room dimension keeps every room a run makes: counts are read for fresh players only.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class CompactMachinesGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void the_adapter_reads_only_its_version_counts_rooms_by_owner_and_reads_a_machine_item_tag(GameTestHelper h) {
        h.assertTrue(CompactMachines.verdict(Optional.empty()).equals("absent"), "no Compact Machines: absent");
        h.assertTrue(CompactMachines.verdict(Optional.of("6.0.3")).equals("ok"), "6.0.3 is read");
        h.assertTrue(CompactMachines.verdict(Optional.of("6.0.4")).equals("version 6.0.4"), "any other build is not");

        UUID ann = UUID.randomUUID(), bob = UUID.randomUUID();
        List<Room> rooms = List.of(new Room(new ChunkPos(0, 0), ann, 7, null), new Room(new ChunkPos(64, 0), ann, 13, "Base"),
                new Room(new ChunkPos(0, 64), bob, 3, null), new Room(new ChunkPos(64, 64), CompactMachines.NO_OWNER, 9, null));
        Map<UUID, Integer> counts = CompactMachines.counts(rooms);
        h.assertTrue(counts.equals(Map.of(ann, 2, bob, 1)), "two rooms for one player, one for the other, the freed slot for nobody: got " + counts);
        h.assertTrue(rooms.get(3).freed() && !rooms.get(0).freed(), "a slot owned by the placeholder is freed");

        CompoundTag tag = new CompoundTag();
        h.assertTrue(CompactMachines.roomTag(null).isEmpty() && CompactMachines.roomTag(tag).isEmpty(), "no tag, no room");
        tag.putIntArray("room_pos", new int[]{3, -4});
        h.assertTrue(CompactMachines.roomTag(tag).equals(Optional.of(new ChunkPos(3, -4))), "room_pos is the room's chunk x and z");
        tag.putIntArray("room_pos", new int[]{3});
        h.assertTrue(CompactMachines.roomTag(tag).isEmpty(), "a malformed room_pos is no room");
        tag.putInt("room_pos", 5);
        h.assertTrue(CompactMachines.roomTag(tag).isEmpty(), "room_pos of another type is no room");

        ItemStack stick = new ItemStack(Items.STICK);
        stick.getOrCreateTag().putIntArray("room_pos", new int[]{1, 1});
        h.assertTrue(CompactMachines.itemRoom(stick).isEmpty() && !CompactMachines.isMachineItem(stick) && !CompactMachines.isShrinkingDevice(stick),
                "an item that is not a machine names no room, whatever its tag");
        h.assertTrue(!CompactMachines.isShrinkingDevice(ItemStack.EMPTY), "an empty hand is not the shrinking device");
        h.assertTrue(!CompactMachines.isMachine(Blocks.CHEST.defaultBlockState()), "a chest is not a machine");
        h.assertTrue(CompactMachines.roomAt(h.getLevel(), h.absolutePos(new BlockPos(1, 1, 1))).isEmpty(), "outside the room dimension nothing is a room");

        h.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        h.assertTrue(CompactMachines.connectedRoom(h.getBlockEntity(new BlockPos(1, 1, 1))).isEmpty() && CompactMachines.connectedRoom(null).isEmpty(),
                "a chest, or no block entity, is bound to no room");
        h.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);

        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            MinecraftServer server = h.getLevel().getServer();
            h.assertTrue(!CompactMachines.present(), "without Compact Machines the adapter is not present");
            h.assertTrue(CompactMachines.rooms(server).isEmpty() && CompactMachines.countsByOwner(server).isEmpty()
                    && CompactMachines.machinesFor(server, new ChunkPos(0, 0)).isEmpty() && CompactMachines.room(server, new ChunkPos(0, 0)).isEmpty(),
                    "and every answer is nothing");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void with_compact_machines_a_real_room_is_counted_for_its_owner_and_its_machine_found(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no room is made (add -PwithCm)");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        h.assertTrue(CompactMachines.present(), "Compact Machines " + CompactMachines.VERSION + " is read");

        Block normal = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_normal"));
        Block small = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_small"));
        BlockPos boundRel = new BlockPos(1, 1, 1), looseRel = new BlockPos(3, 1, 1);
        h.setBlock(boundRel, normal);
        h.setBlock(looseRel, small);
        h.assertTrue(CompactMachines.isMachine(h.getBlockState(boundRel)) && CompactMachines.isMachine(h.getBlockState(looseRel)), "both blocks are machines");

        ServerPlayer owner = TestPlayers.mockServerPlayer(h), other = TestPlayers.mockServerPlayer(h);
        h.assertTrue(CompactMachines.countOwnedBy(server, owner.getUUID()) == 0, "a new player owns no room");
        ChunkPos room = CmTestBridge.newRoom(server, owner.getUUID());
        CmTestBridge.bind(h.getBlockEntity(boundRel), room);

        h.assertTrue(CompactMachines.countOwnedBy(server, owner.getUUID()) == 1, "the room is counted for the player who made it");
        h.assertTrue(CompactMachines.countOwnedBy(server, other.getUUID()) == 0, "and for nobody else");
        Optional<Room> read = CompactMachines.room(server, room);
        h.assertTrue(read.isPresent() && read.get().owner().equals(owner.getUUID()) && read.get().size() == 7 && read.get().name() == null && !read.get().freed(),
                "the room reads back with its owner and its inside size, got " + read);
        h.assertTrue(CompactMachines.rooms(server).stream().anyMatch(r -> r.pos().equals(room)), "and is among every room");

        h.assertTrue(CompactMachines.connectedRoom(h.getBlockEntity(boundRel)).equals(Optional.of(room)), "the bound machine names its room");
        h.assertTrue(CompactMachines.connectedRoom(h.getBlockEntity(looseRel)).isEmpty(), "the unbound machine names none");
        GlobalPos placed = GlobalPos.of(level.dimension(), h.absolutePos(boundRel));
        h.assertTrue(CompactMachines.machinesFor(server, room).contains(placed), "the placed machine is found for its room, got " + CompactMachines.machinesFor(server, room));

        ServerLevel rooms = server.getLevel(CompactMachines.ROOMS_LEVEL);
        h.assertTrue(rooms != null && CompactMachines.roomAt(rooms, room.getMiddleBlockPosition(40)).equals(Optional.of(room)), "a position inside the room is in the room");
        h.assertTrue(CompactMachines.roomAt(rooms, new BlockPos(-30_000_000 + 16, 40, -30_000_000 + 16)).isEmpty(), "a chunk with no room is no room");

        ItemStack item = new ItemStack(normal.asItem());
        h.assertTrue(CompactMachines.isMachineItem(item) && CompactMachines.itemRoom(item).isEmpty(), "a new machine item is unbound");
        CmTestBridge.bindItem(item, room);
        h.assertTrue(CompactMachines.itemRoom(item).equals(Optional.of(room)), "a bound machine item names its room");
        h.assertTrue(CompactMachines.isShrinkingDevice(new ItemStack(ForgeRegistries.ITEMS.getValue(CompactMachines.SHRINKING_DEVICE))), "the Personal Shrinking Device is known");

        h.setBlock(boundRel, Blocks.AIR);
        h.setBlock(looseRel, Blocks.AIR);
        h.assertTrue(!CompactMachines.machinesFor(server, room).contains(placed), "a broken machine is no longer found for its room");
        h.assertTrue(CompactMachines.countOwnedBy(server, owner.getUUID()) == 1, "and the room still counts: Compact Machines never deletes one");
        h.succeed();
    }
}
