package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.rooms.RoomClearing;
import com.mrgregles.bsp_server_utilities.rooms.RoomGrace;
import com.mrgregles.bsp_server_utilities.rooms.RoomLimit;
import com.mrgregles.bsp_server_utilities.rooms.RoomList;
import com.mrgregles.bsp_server_utilities.rooms.RoomLosses;
import com.mrgregles.bsp_server_utilities.rooms.RoomSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SU-57, clearing a room after its grace (owner decisions D446, D447, D449, D450, D452 Z8; the handoff's 7.1 and 7.6):
 * which rooms are due, in what order, how many at a time; who must leave a room kept for nobody. With {@code -PwithCm}
 * a real room with a chest of diamonds, a machine bound to another room, a dropped machine item, a tunnel in its wall
 * and a chunk-loading upgrade is cleared: nothing inside, nothing dropped, the tunnel back to wall, the upgrade gone,
 * the slot kept for nobody so the next new room lands on a fresh position and is its maker's (the 7.1 regression), the
 * rooms of the machine and the item inside in grace; a room with a placed machine is not cleared; a machine bound to the
 * cleared room is a fresh machine when entered.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RoomClearGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static RoomGrace.Entry entry(int x, long until) {
        return new RoomGrace.Entry(new ChunkPos(x, 0), UUID.randomUUID(), 7, 0, until, "burnt", true);
    }

    @GameTest(template = EMPTY)
    public void rooms_are_cleared_when_their_grace_ends_soonest_first_a_few_at_a_time(GameTestHelper h) {
        List<RoomGrace.Entry> all = List.of(entry(1, 500), entry(2, 100), entry(3, 1001), entry(4, 300), entry(5, 1000), entry(6, 200), entry(7, 400));
        List<Integer> due = RoomClearing.due(all, 1000, 4).stream().map(e -> e.room().x).toList();
        h.assertTrue(due.equals(List.of(2, 6, 4, 7)), "ended at 1000: soonest first, four at a time, got " + due);
        h.assertTrue(RoomClearing.due(all, 1000, 10).size() == 6 && RoomClearing.due(all, 99, 4).isEmpty(), "the one ending after 1000 waits; nothing is due before its time");

        MinecraftServer server = h.getLevel().getServer();
        h.assertTrue(!RoomClearing.mustLeave(server, h.getLevel(), h.absolutePos(new BlockPos(1, 1, 1))), "nobody has to leave a place outside the room dimension");
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            h.assertTrue(!RoomClearing.clear(server, new ChunkPos(0, 0)) && RoomClearing.sweep(server) == 0, "without Compact Machines nothing is cleared");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public void with_compact_machines_a_room_is_cleared_its_slot_kept_for_nobody_and_what_was_inside_followed(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no room is cleared (add -PwithCm)");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel rooms = server.getLevel(CompactMachines.ROOMS_LEVEL);
        Block normal = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_normal"));
        Block small = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_small"));
        Block tunnelWall = ForgeRegistries.BLOCKS.getValue(CompactMachines.TUNNEL_WALL), solidWall = ForgeRegistries.BLOCKS.getValue(CompactMachines.SOLID_WALL);
        BlockPos keptRel = new BlockPos(1, 1, 1), freshRel = new BlockPos(3, 1, 1);
        long now = 500L * 86_400_000L;
        List<ChunkPos> graced = new ArrayList<>();
        RoomGrace grace = RoomGrace.get(server);
        Runnable cleanup = () -> {
            RoomLosses.useClockForTests(null);
            RoomList.resetForTests(null);
            RoomLimit.resetForTests(null);
            RoomSettings.useForTests(null);
            graced.forEach(grace::remove); // the test rooms stay in Compact Machines' world
            h.setBlock(keptRel, Blocks.AIR);
            h.setBlock(freshRel, Blocks.AIR);
        };
        RoomLosses.useClockForTests(() -> now);
        RoomList.resetForTests(() -> now);
        RoomLimit.resetForTests(() -> now);
        RoomClearing.resetForTests();
        ServerPlayer ann = TestPlayers.mockServerPlayer(h), bob = TestPlayers.mockServerPlayer(h);
        ChunkPos room = CmTestBridge.newRoom(server, ann.getUUID());
        ChunkPos inner = CmTestBridge.newRoom(server, ann.getUUID());
        ChunkPos lying = CmTestBridge.newRoom(server, ann.getUUID());
        graced.addAll(List.of(room, inner, lying));

        BlockPos centre = CmTestBridge.centre(server, room);
        AABB inside = CompactMachines.roomBounds(server, room).orElseThrow();
        rooms.setBlockAndUpdate(centre, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) rooms.getBlockEntity(centre)).setItem(0, new ItemStack(Items.DIAMOND, 64));
        rooms.setBlockAndUpdate(centre.north(), small.defaultBlockState());
        CmTestBridge.bind(rooms.getBlockEntity(centre.north()), inner);
        rooms.setBlockAndUpdate(centre.south(), small.defaultBlockState()); // never bound: Compact Machines gave it no graph node (6.0.3's disconnect trips on that)
        ItemStack lyingItem = new ItemStack(normal.asItem());
        CmTestBridge.bindItem(lyingItem, lying);
        rooms.addFreshEntity(new ItemEntity(rooms, centre.getX() + 0.5, centre.getY() + 1, centre.getZ() + 2.5, lyingItem));
        BlockPos tunnelAt = new BlockPos(Mth.ceil(inside.maxX), centre.getY(), centre.getZ());
        h.assertTrue(rooms.getBlockState(tunnelAt).is(solidWall), "the room's east wall is just outside its bounds: " + rooms.getBlockState(tunnelAt));
        rooms.setBlockAndUpdate(tunnelAt, tunnelWall.defaultBlockState());
        CmTestBridge.addChunkloadUpgrade(rooms, room);
        h.assertTrue(CmTestBridge.hasChunkloadUpgrade(rooms, room), "the upgrade is on");
        CmTestBridge.forceForTunnels(rooms, room);
        h.assertTrue(rooms.getForcedChunks().contains(room.toLong()), "Compact Machines force-loads a room with tunnels");

        Set<ChunkPos> before = CompactMachines.rooms(server).stream().map(CompactMachines.Room::pos).collect(Collectors.toSet());
        grace.put(new RoomGrace.Entry(room, ann.getUUID(), 7, now - 8 * 86_400_000L, now - 1, "burnt", true));
        RoomSettings.useForTests(new RoomSettings(0, false, "", 7));

        // the room's entities load on another thread: the first clear asks for them, a later one clears
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(RoomClearing.clear(server, room), "the room whose grace ended is cleared once its entities have loaded"))
                .thenExecute(() -> {
                    try {
                        h.assertTrue(rooms.getBlockState(centre).isAir() && rooms.getBlockState(centre.north()).isAir() && rooms.getBlockState(centre.south()).isAir(),
                                "the chest and the machines inside are gone");
                        h.assertTrue(CompactMachines.present(), "an unbound machine inside did not stand the adapter aside");
                        h.assertTrue(!rooms.getForcedChunks().contains(room.toLong()), "the cleared room is no longer force-loaded");
                        h.assertTrue(rooms.getEntitiesOfClass(ItemEntity.class, inside.inflate(1)).isEmpty(), "nothing was dropped and nothing is left lying there (D449)");
                        h.assertTrue(rooms.getBlockState(tunnelAt).is(solidWall), "the tunnel in its wall is plain wall again");
                        h.assertTrue(!CmTestBridge.hasChunkloadUpgrade(rooms, room), "its upgrade is gone");
                        Optional<CompactMachines.Room> slot = CompactMachines.room(server, room);
                        h.assertTrue(slot.isPresent() && slot.get().freed() && slot.get().size() == 7, "the slot is kept, owned by nobody (D447): " + slot);
                        h.assertTrue(CompactMachines.rooms(server).stream().map(CompactMachines.Room::pos).collect(Collectors.toSet()).containsAll(before),
                            "every room registered before is still registered: the count never drops (7.1; other tests may add rooms meanwhile)");
                        h.assertTrue(!grace.contains(room), "and it has left the grace list");
                        h.assertTrue(grace.get(inner).map(RoomGrace.Entry::reason).orElse("").equals("inside")
                                    && grace.get(lying).map(RoomGrace.Entry::reason).filter(r -> r.equals("inside") || r.equals("cleared")).isPresent(), // "cleared" when another test's item clear got there first while this one waited
                                "the room of the machine that stood inside and of the machine item lying there are in grace (D450): inner " + grace.get(inner) + " machines "
                                        + CompactMachines.machinesFor(server, inner) + ", lying " + grace.get(lying));
                        h.assertTrue(RoomList.count(server, ann.getUUID()) == 0, "none of ann's three rooms counts now");
                        h.assertTrue(RoomClearing.mustLeave(server, rooms, centre) && !RoomClearing.mustLeave(server, rooms, CmTestBridge.centre(server, inner)),
                                "a player logging in where the cleared room is must leave; in a room in grace, not");

                        ChunkPos next = CmTestBridge.newRoom(server, bob.getUUID());
                        h.assertTrue(!before.contains(next) && !next.equals(room), "the next new room lands on a fresh position (7.1), got " + next);
                        h.assertTrue(CompactMachines.room(server, next).map(r -> r.owner().equals(bob.getUUID())).orElse(false) && CompactMachines.room(server, room).map(CompactMachines.Room::freed).orElse(false),
                                "it is its maker's, and the cleared slot is still nobody's");
                        graced.add(next);

                        h.setBlock(freshRel, normal);
                        CmTestBridge.bind(h.getBlockEntity(freshRel), room);
                        bob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ForgeRegistries.ITEMS.getValue(CompactMachines.SHRINKING_DEVICE)));
                        h.assertTrue(RoomLimit.click(bob, h.getLevel(), h.absolutePos(freshRel)) == RoomLimit.Outcome.NEW_ALLOWED
                                        && CompactMachines.connectedRoom(h.getBlockEntity(freshRel)).isEmpty(),
                                "a machine still bound to the cleared room is a fresh machine when entered: it will make a new room (Z8)");

                        ChunkPos kept = CmTestBridge.newRoom(server, ann.getUUID());
                        graced.add(kept);
                        h.setBlock(keptRel, normal);
                        CmTestBridge.bind(h.getBlockEntity(keptRel), kept);
                        grace.put(new RoomGrace.Entry(kept, ann.getUUID(), 7, 0, now - 1, "burnt", true));
                        h.assertTrue(!RoomClearing.clear(server, kept) && !grace.contains(kept) && CompactMachines.room(server, kept).map(r -> !r.freed()).orElse(false),
                                "a room with a placed machine bound to it is not cleared: it leaves grace, still its owner's");
                    } finally {
                        cleanup.run();
                    }
                })
                .thenSucceed();
    }
}
