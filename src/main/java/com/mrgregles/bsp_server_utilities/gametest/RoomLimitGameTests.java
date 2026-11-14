package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.rooms.RoomLimit;
import com.mrgregles.bsp_server_utilities.rooms.RoomList;
import com.mrgregles.bsp_server_utilities.rooms.RoomRow;
import com.mrgregles.bsp_server_utilities.rooms.RoomSettings;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SU-55, the room limit (owner decisions D437, D440, D441, D442, D452 Z5): who is refused, how many must be given back,
 * the lines' wording and pace. With {@code -PwithCm} the clicks go through Forge's real {@code RightClickBlock} event on
 * real machine blocks (the room itself is made with Compact Machines' own {@code Rooms.createNew}, as the click would):
 * a new room under the limit passes, at the limit it is cancelled before Compact Machines sees it, on either hand's
 * event; entering a bound machine is never refused; operators pass; another player's count is their own; other
 * servers' rooms count in network scope; an owner over the limit entering their own room is warned once a minute.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RoomLimitGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void the_limit_refuses_at_or_over_it_and_says_how_many_to_give_back(GameTestHelper h) {
        h.assertTrue(!RoomLimit.refuses(false, false, 50, 0), "no limit set: never refused");
        h.assertTrue(!RoomLimit.refuses(true, false, 4, 5) && RoomLimit.refuses(true, false, 5, 5) && RoomLimit.refuses(true, false, 7, 5), "refused at the limit and over it, not under");
        h.assertTrue(!RoomLimit.refuses(true, true, 9, 5), "an exempt player never");
        h.assertTrue(RoomLimit.toGiveBack(5, 5) == 1 && RoomLimit.toGiveBack(7, 5) == 3 && RoomLimit.toGiveBack(2, 5) == 0, "give back down to one under the limit");

        h.assertTrue(RoomLimit.exempt(true, Set.of(), ""), "operators are exempt");
        h.assertTrue(RoomLimit.exempt(false, Set.of("builders", "vip"), "VIP") && !RoomLimit.exempt(false, Set.of("vip"), "") && !RoomLimit.exempt(false, Set.of("default"), "vip"),
                "the configured group, case ignored; none configured, nobody by group");

        TranslatableContents full = (TranslatableContents) RoomLimit.refusal(5, 5).getContents();
        TranslatableContents over = (TranslatableContents) RoomLimit.refusal(7, 5).getContents();
        h.assertTrue(full.getKey().equals("message.bsp_server_utilities.rooms.refused_full") && over.getKey().equals("message.bsp_server_utilities.rooms.refused_over")
                && over.getArgs()[2].equals(3), "at the limit: give one back; over it: how many (3 for 7 of 5)");

        h.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        h.assertTrue(RoomLimit.click(p, h.getLevel(), h.absolutePos(new BlockPos(1, 1, 1))) == RoomLimit.Outcome.NONE, "a chest is no machine: nothing to do");
        h.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
        h.succeed();
    }

    private static PlayerInteractEvent.RightClickBlock click(GameTestHelper h, ServerPlayer p, InteractionHand hand, BlockPos rel) {
        BlockPos pos = h.absolutePos(rel);
        return ForgeHooks.onRightClickBlock(p, hand, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void with_compact_machines_a_new_room_is_refused_at_the_limit_but_entering_never_is(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no click is made (add -PwithCm)");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        Block normal = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_normal"));
        ItemStack device = new ItemStack(ForgeRegistries.ITEMS.getValue(CompactMachines.SHRINKING_DEVICE));
        BlockPos loose = new BlockPos(1, 1, 1), bound = new BlockPos(3, 1, 1), othersRoom = new BlockPos(5, 1, 1);
        AtomicLong now = new AtomicLong(1_000_000L);
        ServerPlayer ann = TestPlayers.mockServerPlayer(h), op = TestPlayers.mockServerPlayer(h), cat = TestPlayers.mockServerPlayer(h);
        for (ServerPlayer p : List.of(ann, op, cat)) {
            p.setItemInHand(InteractionHand.MAIN_HAND, device.copy());
        }
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        try {
            RoomLimit.resetForTests(now::get);
            RoomList.resetForTests(now::get);
            RoomSettings.useForTests(new RoomSettings(1, false, "", 7));
            h.setBlock(loose, normal);
            h.setBlock(bound, normal);
            h.setBlock(othersRoom, normal);

            PlayerInteractEvent.RightClickBlock e = click(h, ann, InteractionHand.MAIN_HAND, loose);
            h.assertTrue(!e.isCanceled() && RoomList.dirty(), "no room yet, limit 1: the new room passes and the room list is read again at the next tick");

            ChunkPos annRoom = CmTestBridge.newRoom(server, ann.getUUID()); // what Compact Machines makes for that click
            e = click(h, ann, InteractionHand.MAIN_HAND, loose);
            h.assertTrue(e.isCanceled() && e.getCancellationResult() == InteractionResult.FAIL, "one room, limit 1: the next new room is refused before Compact Machines sees it");
            h.assertTrue(CompactMachines.connectedRoom(h.getBlockEntity(loose)).isEmpty() && CompactMachines.countOwnedBy(server, ann.getUUID()) == 1, "nothing was made, the machine is still unbound");
            h.assertTrue(RoomLimit.refusedAt(ann.getUUID()) == 1_000_000L, "the player was told");
            now.addAndGet(500);
            click(h, ann, InteractionHand.MAIN_HAND, loose);
            h.assertTrue(RoomLimit.refusedAt(ann.getUUID()) == 1_000_000L, "not again within a second");
            now.addAndGet(600);
            click(h, ann, InteractionHand.MAIN_HAND, loose);
            h.assertTrue(RoomLimit.refusedAt(ann.getUUID()) == 1_001_100L, "again after a second");
            h.assertTrue(click(h, ann, InteractionHand.OFF_HAND, loose).isCanceled(), "the off hand's event is refused too: Compact Machines reads the main hand whichever hand clicks");

            ItemStack held = ann.getMainHandItem();
            ann.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            ann.setItemInHand(InteractionHand.OFF_HAND, held);
            h.assertTrue(!click(h, ann, InteractionHand.OFF_HAND, loose).isCanceled(), "the device in the off hand makes no room, so nothing is refused");
            ann.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            ann.setItemInHand(InteractionHand.MAIN_HAND, held);

            CmTestBridge.bind(h.getBlockEntity(bound), annRoom);
            h.assertTrue(!click(h, ann, InteractionHand.MAIN_HAND, bound).isCanceled(), "entering a machine that has a room is never refused");
            h.assertTrue(RoomLimit.warnedAt(ann.getUUID()) == null, "at the limit, not over it: no warning");

            CmTestBridge.newRoom(server, ann.getUUID()); // over now: 2 of 1
            h.assertTrue(!click(h, ann, InteractionHand.MAIN_HAND, bound).isCanceled() && RoomLimit.warnedAt(ann.getUUID()) == now.get(), "over the limit, entering their own room: still allowed, and warned");
            long warned = now.get();
            now.addAndGet(30_000);
            click(h, ann, InteractionHand.MAIN_HAND, bound);
            h.assertTrue(RoomLimit.warnedAt(ann.getUUID()) == warned, "not again within a minute");
            now.addAndGet(31_000);
            click(h, ann, InteractionHand.MAIN_HAND, bound);
            h.assertTrue(RoomLimit.warnedAt(ann.getUUID()) == now.get(), "again after a minute");

            ChunkPos catRoom = CmTestBridge.newRoom(server, cat.getUUID());
            CmTestBridge.bind(h.getBlockEntity(othersRoom), catRoom);
            h.assertTrue(!click(h, cat, InteractionHand.MAIN_HAND, othersRoom).isCanceled() && RoomLimit.warnedAt(cat.getUUID()) == null, "a player at the limit entering their room: no warning");
            RoomLimit.resetForTests(now::get);
            click(h, ann, InteractionHand.MAIN_HAND, othersRoom);
            h.assertTrue(RoomLimit.warnedAt(ann.getUUID()) == null, "entering someone else's room says nothing about one's own count");

            h.assertTrue(click(h, cat, InteractionHand.MAIN_HAND, loose).isCanceled(), "cat has one room too: refused");
            server.getPlayerList().getOps().add(new ServerOpListEntry(op.getGameProfile(), 2, false));
            CmTestBridge.newRoom(server, op.getUUID());
            h.assertTrue(!click(h, op, InteractionHand.MAIN_HAND, loose).isCanceled(), "an operator with a room is not refused");
            server.getPlayerList().getOps().remove(op.getGameProfile());

            ServerPlayer dan = TestPlayers.mockServerPlayer(h);
            dan.setItemInHand(InteractionHand.MAIN_HAND, device.copy());
            h.assertTrue(!click(h, dan, InteractionHand.MAIN_HAND, loose).isCanceled(), "a player with no room is not refused because of anyone else's");

            Storage.useForTests(alpha);
            db.saveRooms("beta", 1, List.of(new RoomRow("beta", 0, 0, dan.getUUID(), 7, "", "", 0, RoomRow.ACTIVE, 0, "", false, "")));
            RoomList.reload(server);
            alpha.drain();
            h.assertTrue(!click(h, dan, InteractionHand.MAIN_HAND, loose).isCanceled(), "per server: a room on another server does not count here");
            RoomSettings.useForTests(new RoomSettings(1, true, "", 7));
            h.assertTrue(click(h, dan, InteractionHand.MAIN_HAND, loose).isCanceled(), "network: the room on beta counts, so the limit is reached");
            RoomSettings.useForTests(new RoomSettings(0, true, "", 7));
            h.assertTrue(!click(h, ann, InteractionHand.MAIN_HAND, loose).isCanceled(), "with no limit nothing is refused");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            Storage.useForTests(null);
            RoomSettings.useForTests(null);
            RoomLimit.resetForTests(null);
            RoomList.resetForTests(null);
            server.getPlayerList().getOps().remove(op.getGameProfile());
            h.setBlock(loose, Blocks.AIR);
            h.setBlock(bound, Blocks.AIR);
            h.setBlock(othersRoom, Blocks.AIR);
        }
        h.succeed();
    }
}
