package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.despawn.DespawnData;
import com.mrgregles.bsp_server_utilities.despawn.DespawnService;
import com.mrgregles.bsp_server_utilities.despawn.DespawnSettings;
import com.mrgregles.bsp_server_utilities.rooms.RoomGrace;
import com.mrgregles.bsp_server_utilities.rooms.RoomList;
import com.mrgregles.bsp_server_utilities.rooms.RoomLosses;
import com.mrgregles.bsp_server_utilities.rooms.RoomNotices;
import com.mrgregles.bsp_server_utilities.rooms.RoomRow;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SU-56, losses and the grace list (owner decisions D444, D446, D447, D451, D452 Z6): where a lost machine is acted on,
 * the reason words, the grace list kept with the world, how long is left, owners told about other servers' rooms once
 * and the room's server told back, the login delay. With {@code -PwithCm}: real bound machine items burnt, blown up with
 * a placed machine still bound (nothing), discarded as a pickup is (nothing), fallen into the void, expired by vanilla
 * and by this mod's despawn timer; a machine marked for another server sent there; a loss received from another server;
 * the room stops counting, its row says grace, and placing a machine bound to it revives it.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RoomLossGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long DAY = 86_400_000L;

    @GameTest(template = EMPTY)
    public void losses_go_to_the_rooms_server_and_owners_are_told_once(GameTestHelper h) {
        h.assertTrue(RoomLosses.route("alpha", true, "alpha") == RoomLosses.Route.HERE && RoomLosses.route("ALPHA", true, "alpha") == RoomLosses.Route.HERE,
                "a machine marked for this server is acted on here (case ignored)");
        h.assertTrue(RoomLosses.route("beta", false, "alpha") == RoomLosses.Route.FORWARD, "marked for another server: sent there");
        h.assertTrue(RoomLosses.route(null, true, "alpha") == RoomLosses.Route.IGNORE && RoomLosses.route(" ", true, "alpha") == RoomLosses.Route.IGNORE,
                "unmarked with BSP-InventorySync running: its room could be any server's, so nothing");
        h.assertTrue(RoomLosses.route(null, false, "alpha") == RoomLosses.Route.HERE, "unmarked without BSP-InventorySync: this server's");

        ServerLevel level = h.getLevel();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        h.assertTrue(RoomLosses.reason(level.damageSources().inFire()).equals("burnt") && RoomLosses.reason(level.damageSources().lava()).equals("burnt"), "fire and lava burn");
        h.assertTrue(RoomLosses.reason(level.damageSources().explosion(null, null)).equals("exploded") && RoomLosses.reason(level.damageSources().cactus()).equals("cactus")
                && RoomLosses.reason(level.damageSources().anvil(p)).equals("crushed") && RoomLosses.reason(level.damageSources().generic()).equals("destroyed"),
                "an explosion, a cactus, an anvil, anything else");

        long now = 50 * DAY;
        h.assertTrue(RoomNotices.daysLeft(now + 7 * DAY, now) == 7 && RoomNotices.daysLeft(now + 1, now) == 1 && RoomNotices.daysLeft(now + 6 * DAY + 1, now) == 7
                && RoomNotices.daysLeft(now - DAY, now) == 1, "days left rounded up, at least 1");

        RoomGrace g = new RoomGrace();
        UUID ann = UUID.randomUUID();
        g.put(new RoomGrace.Entry(new ChunkPos(3, 4), ann, 7, 10, 20, "burnt", false));
        g.put(new RoomGrace.Entry(new ChunkPos(5, 6), ann, 13, 11, 21, "void", false));
        h.assertTrue(g.markTold(new ChunkPos(5, 6)) && !g.markTold(new ChunkPos(9, 9)), "a room in grace is marked told; one not in grace is not");
        RoomGrace back = RoomGrace.load(g.save(new CompoundTag()));
        h.assertTrue(back.all().equals(g.all()) && back.countFor(ann) == 2 && back.countFor(UUID.randomUUID()) == 0, "the grace list survives a save and a load: " + back.all());
        h.assertTrue(back.remove(new ChunkPos(3, 4)).isPresent() && back.countFor(ann) == 1 && !back.contains(new ChunkPos(3, 4)), "and a room leaves it");

        MinecraftServer server = level.getServer();
        ItemEntity stick = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.STICK));
        RoomLosses.itemGone(stick, "burnt"); // not a machine: nothing, with or without Compact Machines
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            h.assertTrue(!RoomLosses.lose(server, new ChunkPos(0, 0), "burnt"), "without Compact Machines no room is ever lost");
        }

        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        try {
            Storage.useForTests(alpha);
            RoomList.resetForTests(null);
            RoomNotices.resetForTests();
            db.saveRooms("beta", 1, List.of(new RoomRow("beta", 8, 9, p.getUUID(), 9, "", "", 0, RoomRow.GRACE, now + 3 * DAY, "despawned", false, ""),
                    new RoomRow("beta", 10, 9, p.getUUID(), 9, "", "", 0, RoomRow.GRACE, now + 3 * DAY, "void", true, ""),
                    new RoomRow("beta", 12, 9, p.getUUID(), 9, "", "", 0, RoomRow.ACTIVE, 0, "", false, "")));
            RoomList.reload(server);
            alpha.drain();
            h.assertTrue(RoomNotices.deliver(server, p) == 1, "told about the one room in grace on beta not yet told, not the told one nor the active one");
            alpha.drain();
            List<StorageBackend.Notice> told = db.fetchNoticesAfter(0, 100).stream().filter(n -> n.kind().equals(RoomNotices.KIND_TOLD)).toList();
            h.assertTrue(told.size() == 1 && told.get(0).payload().equals("beta\n8\n9"), "and beta is told back which room: " + told);
            h.assertTrue(RoomNotices.deliver(server, p) == 0, "not told twice while beta's row catches up");

            ChunkPos local = new ChunkPos(-77, 31);
            RoomGrace.get(server).put(new RoomGrace.Entry(local, p.getUUID(), 5, 1, 2, "burnt", false));
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), 1, "beta", RoomNotices.KIND_TOLD, "alpha\n-77\n31"));
            h.assertTrue(RoomGrace.get(server).get(local).map(RoomGrace.Entry::told).orElse(false), "another server telling the owner marks this server's room told");
            RoomGrace.get(server).remove(local);

            RoomNotices.loggedInForTests(p.getUUID());
            for (int i = 1; i < 60; i++) {
                RoomNotices.tick(server);
            }
            h.assertTrue(RoomNotices.pending(p.getUUID()), "the lines wait three seconds after login");
            RoomNotices.tick(server);
            h.assertTrue(!RoomNotices.pending(p.getUUID()), "then they are shown (to a player still online)");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            Storage.useForTests(null);
            RoomList.resetForTests(null);
            RoomNotices.resetForTests();
        }
        h.succeed();
    }

    private static ItemEntity drop(GameTestHelper h, ItemStack stack, int x) {
        BlockPos at = h.absolutePos(new BlockPos(x, 2, 6));
        ItemEntity e = new ItemEntity(h.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, stack);
        e.setDeltaMovement(0, 0, 0);
        h.getLevel().addFreshEntity(e);
        return e;
    }

    private static ItemStack bound(Block machine, ChunkPos room) {
        ItemStack s = new ItemStack(machine.asItem());
        CmTestBridge.bindItem(s, room);
        return s;
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void with_compact_machines_a_destroyed_machine_puts_its_room_in_grace_and_a_placed_one_revives_it(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no machine is lost (add -PwithCm)");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        Block normal = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_normal"));
        AtomicLong now = new AtomicLong(100 * DAY);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        List<ChunkPos> rooms = new ArrayList<>();
        List<ItemEntity> made = new ArrayList<>();
        BlockPos placedRel = new BlockPos(1, 1, 1);
        DespawnSettings despawnBefore = DespawnData.get(server).settings();
        try {
            Storage.useForTests(alpha);
            RoomList.resetForTests(now::get);
            RoomLosses.useClockForTests(now::get);
            RoomNotices.resetForTests();
            ServerPlayer ann = TestPlayers.mockServerPlayer(h);
            for (int i = 0; i < 7; i++) {
                rooms.add(CmTestBridge.newRoom(server, ann.getUUID()));
            }
            RoomGrace grace = RoomGrace.get(server);
            h.assertTrue(RoomList.count(server, ann.getUUID()) == 7, "seven rooms count");

            ItemEntity burnt = drop(h, bound(normal, rooms.get(0)), 1);
            made.add(burnt);
            burnt.hurt(level.damageSources().inFire(), 10f);
            h.assertTrue(burnt.isRemoved(), "the machine item burnt up");
            RoomGrace.Entry e0 = grace.get(rooms.get(0)).orElse(null);
            h.assertTrue(e0 != null && e0.reason().equals("burnt") && e0.owner().equals(ann.getUUID()) && e0.until() == now.get() + 7 * DAY && !e0.told(),
                    "its room is in grace for 7 days, its owner not told yet (not online here): " + e0);
            h.assertTrue(RoomList.count(server, ann.getUUID()) == 6 && RoomList.dirty(), "it stops counting at once and the room list is read again");
            h.assertTrue(!RoomLosses.lose(server, rooms.get(0), "burnt"), "a room already in grace is not put in again");

            h.setBlock(placedRel, normal);
            CmTestBridge.bind(h.getBlockEntity(placedRel), rooms.get(1));
            ItemEntity copy = drop(h, bound(normal, rooms.get(1)), 2);
            made.add(copy);
            copy.hurt(level.damageSources().explosion(null, null), 10f);
            h.assertTrue(copy.isRemoved() && !grace.contains(rooms.get(1)), "a copy blown up while a placed machine is still bound to the room: the room stays");

            ItemEntity picked = drop(h, bound(normal, rooms.get(2)), 3);
            made.add(picked);
            picked.discard();
            h.assertTrue(!grace.contains(rooms.get(2)), "an item removed as a pickup removes it: nothing");

            ItemEntity fell = drop(h, bound(normal, rooms.get(3)), 4);
            made.add(fell);
            fell.setPos(fell.getX(), level.getMinBuildHeight() - 100, fell.getZ());
            fell.checkBelowWorld();
            h.assertTrue(fell.isRemoved() && grace.get(rooms.get(3)).map(RoomGrace.Entry::reason).orElse("").equals("void"), "fallen into the void: in grace");

            ItemEntity expired = drop(h, bound(normal, rooms.get(4)), 5);
            made.add(expired);
            h.assertTrue(ForgeEventFactory.onItemExpire(expired, expired.getItem()) < 0, "vanilla's expiry, kept by nobody");
            h.assertTrue(grace.get(rooms.get(4)).map(RoomGrace.Entry::reason).orElse("").equals("despawned"), "despawned by vanilla: in grace");

            DespawnService.useForTests(() -> 0L);
            DespawnData.get(server).setSettings(new DespawnSettings(true, 5, 15, true, List.of(), 0).sanitised().withRevision(despawnBefore.revision()));
            ItemEntity timed = drop(h, bound(normal, rooms.get(5)), 6);
            made.add(timed);
            DespawnService.useForTests(() -> 5 * 60 * 20L);
            DespawnService.check(server);
            h.assertTrue(timed.isRemoved() && grace.get(rooms.get(5)).map(RoomGrace.Entry::reason).orElse("").equals("despawned"), "despawned by this mod's timer: in grace");

            ItemStack marked = bound(normal, rooms.get(6));
            marked.getOrCreateTag().putString(RoomLosses.HOME_TAG, "beta");
            ItemEntity foreign = drop(h, marked, 7);
            made.add(foreign);
            foreign.hurt(level.damageSources().lava(), 10f);
            alpha.drain();
            h.assertTrue(!grace.contains(rooms.get(6)), "a machine marked for beta frees nothing here, though a room here has its position");
            h.assertTrue(db.fetchNoticesAfter(0, 100).stream().anyMatch(n -> n.kind().equals(RoomLosses.KIND_LOST) && n.payload().equals("beta\n" + rooms.get(6).x + "\n" + rooms.get(6).z + "\nburnt")),
                    "beta is sent the loss");
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), 1, "beta", RoomLosses.KIND_LOST, "alpha\n" + rooms.get(6).x + "\n" + rooms.get(6).z + "\nburnt"));
            h.assertTrue(grace.contains(rooms.get(6)), "a loss sent here from another server puts this server's room in grace");
            h.assertTrue(RoomList.count(server, ann.getUUID()) == 2, "five rooms in grace: two count, got " + RoomList.count(server, ann.getUUID()));

            RoomList.refresh(server);
            alpha.drain();
            RoomRow row = db.loadRooms().rows().stream().filter(r -> r.x() == rooms.get(3).x && r.z() == rooms.get(3).z).findFirst().orElse(null);
            h.assertTrue(row != null && row.state().equals(RoomRow.GRACE) && row.reason().equals("void") && row.graceUntil() == now.get() + 7 * DAY && !row.told() && !row.counts(),
                    "the shared row says grace, why and until when: " + row);

            h.assertTrue(RoomNotices.deliver(server, ann) == 5 && grace.all().stream().filter(x -> x.owner().equals(ann.getUUID())).allMatch(RoomGrace.Entry::told),
                    "at login the owner is told about each of the five, and each is marked told");

            h.setBlock(placedRel, Blocks.AIR);
            h.setBlock(placedRel, normal);
            CmTestBridge.bind(h.getBlockEntity(placedRel), rooms.get(0));
            RoomList.refresh(server);
            alpha.drain();
            h.assertTrue(!grace.contains(rooms.get(0)) && RoomList.count(server, ann.getUUID()) == 3, "a machine bound to a room in grace placed again: revived, it counts again");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            Storage.useForTests(null);
            RoomList.resetForTests(null);
            RoomLosses.useClockForTests(null);
            RoomNotices.resetForTests();
            DespawnService.useForTests(null);
            DespawnData.get(server).setSettings(despawnBefore);
            made.forEach(ItemEntity::discard);
            rooms.forEach(r -> RoomGrace.get(server).remove(r)); // the test rooms stay in Compact Machines' world, never cleared
            h.setBlock(placedRel, Blocks.AIR);
        }
        h.succeed();
    }
}
