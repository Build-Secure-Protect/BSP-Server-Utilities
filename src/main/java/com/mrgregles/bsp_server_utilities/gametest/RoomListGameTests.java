package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.rooms.RoomList;
import com.mrgregles.bsp_server_utilities.rooms.RoomRow;
import com.mrgregles.bsp_server_utilities.rooms.RoomSettings;
import com.mrgregles.bsp_server_utilities.rooms.RoomTable;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * SU-54, the network room list (owner decisions D437, D438, D441, D446, D453 R5): the settings' defaults; a player's count
 * as this server's rooms plus every other server's counting rows (network) or this server's only; stale servers; each
 * server's rows replaced as a whole in the shared table and read back by the others on a notice. With {@code -PwithCm} a
 * real room on this server is written with its placed machine, shared with a notice when it changes, and only touched
 * when it does not.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RoomListGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static RoomRow row(String server, int x, UUID owner, String state) {
        return new RoomRow(server, x, 0, owner, 7, "", "", 0, state, 0, "", false, "");
    }

    @GameTest(template = EMPTY)
    public void a_count_adds_other_servers_rooms_in_network_scope_and_the_table_is_replaced_per_server(GameTestHelper h) {
        h.assertTrue(RoomSettings.current().equals(RoomSettings.DEFAULTS) && !RoomSettings.DEFAULTS.limited() && RoomSettings.DEFAULTS.network()
                && RoomSettings.DEFAULTS.graceDays() == 7 && RoomSettings.DEFAULTS.exemptGroup().isEmpty(),
                "by default nothing is limited, rooms count across the network, 7 days' grace, no group exempt: got " + RoomSettings.current());

        UUID ann = UUID.randomUUID(), bob = UUID.randomUUID();
        RoomTable t = new RoomTable(Map.of(), List.of(
                row("alpha", 0, ann, RoomRow.ACTIVE), // this server's own row: the live count is used instead
                row("beta", 0, ann, RoomRow.ACTIVE), row("beta", 1, bob, RoomRow.ACTIVE), row("beta", 2, ann, RoomRow.GRACE),
                row("gamma", 0, ann, RoomRow.ACTIVE), row("gamma", 1, CompactMachines.NO_OWNER, RoomRow.ACTIVE)));
        h.assertTrue(RoomList.count(2, ann, true, t, "alpha") == 4, "network: this server's 2 and one on each other server; the room in grace and this server's own row do not count");
        h.assertTrue(RoomList.count(2, ann, false, t, "alpha") == 2, "per server: this server's rooms only");
        h.assertTrue(RoomList.count(0, bob, true, t, "alpha") == 1 && RoomList.count(0, UUID.randomUUID(), true, t, "alpha") == 0, "each player's own rooms");
        h.assertTrue(!row("beta", 0, CompactMachines.NO_OWNER, RoomRow.ACTIVE).counts() && !row("beta", 0, ann, RoomRow.GRACE).counts() && row("beta", 0, ann, RoomRow.ACTIVE).counts(),
                "a room counts when it is in use and owned by someone");

        long now = 10_000_000L;
        RoomTable heard = new RoomTable(Map.of("alpha", now - 600_000L, "beta", now - 240_000L, "gamma", now - 360_000L, "delta", now - RoomList.STALE_MS), List.of());
        h.assertTrue(RoomList.stale(heard, "alpha", now).equals(List.of("delta", "gamma")), "servers not heard from for 5 minutes are stale, this one never: got " + RoomList.stale(heard, "alpha", now));

        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        try {
            Storage.useForTests(alpha);
            RoomList.resetForTests(null);
            db.saveRooms("beta", 1000, List.of(row("beta", 0, ann, RoomRow.ACTIVE), row("beta", 1, ann, RoomRow.ACTIVE)));
            db.saveRooms("beta", 2000, List.of(row("beta", 1, ann, RoomRow.ACTIVE)));
            db.touchRooms("gamma", 3000);
            RoomTable read = db.loadRooms();
            h.assertTrue(read.rows().size() == 1 && read.rows().get(0).x() == 1, "a server's rows are replaced as a whole, got " + read.rows());
            h.assertTrue(read.heard().equals(Map.of("beta", 2000L, "gamma", 3000L)), "each server's last word is kept, rows or not: got " + read.heard());

            RoomList.reload(server);
            alpha.drain();
            h.assertTrue(RoomList.table().rows().size() == 1 && RoomList.count(0, ann, true, RoomList.table(), "alpha") == 1, "the table read back counts beta's room");

            db.saveRooms("beta", 4000, List.of(row("beta", 1, ann, RoomRow.ACTIVE), row("beta", 2, ann, RoomRow.ACTIVE)));
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), 4000, "beta", RoomList.KIND, "beta"));
            alpha.drain();
            h.assertTrue(RoomList.count(0, ann, true, RoomList.table(), "alpha") == 2, "a notice from another server reads the table again");

            if (!ModList.get().isLoaded(CompactMachines.MODID)) {
                RoomList.refresh(server);
                alpha.drain();
                h.assertTrue(!db.loadRooms().heard().containsKey("alpha"), "without Compact Machines this server writes nothing");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            Storage.useForTests(null);
            RoomList.resetForTests(null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void with_compact_machines_this_servers_rooms_are_written_shared_when_changed_and_counted(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no room is shared (add -PwithCm)");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        AtomicLong now = new AtomicLong(5_000_000L);
        Block normal = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_normal"));
        BlockPos rel = new BlockPos(1, 1, 1);
        try {
            Storage.useForTests(alpha);
            RoomList.resetForTests(now::get);
            ServerPlayer owner = TestPlayers.mockServerPlayer(h);
            h.setBlock(rel, normal);
            ChunkPos room = CmTestBridge.newRoom(server, owner.getUUID());
            CmTestBridge.bind(h.getBlockEntity(rel), room);

            RoomList.refresh(server);
            alpha.drain();
            RoomTable t = db.loadRooms();
            BlockPos at = h.absolutePos(rel);
            Optional<RoomRow> mine = t.rows().stream().filter(r -> r.owner().equals(owner.getUUID())).findFirst();
            h.assertTrue(mine.isPresent() && mine.get().server().equals("alpha") && mine.get().x() == room.x && mine.get().z() == room.z && mine.get().size() == 7
                            && mine.get().machines() == 1 && mine.get().machine().equals("minecraft:overworld " + at.getX() + " " + at.getY() + " " + at.getZ())
                            && mine.get().counts(),
                    "the room is written under this server with its placed machine, got " + mine);
            h.assertTrue(t.heard().get("alpha") == 5_000_000L, "with the time it was written");
            long notices = db.fetchNoticesAfter(0, 1000).stream().filter(n -> n.kind().equals(RoomList.KIND) && n.source().equals("alpha")).count();
            h.assertTrue(notices == 1, "and the other servers are told once, got " + notices);

            now.set(5_060_000L);
            RoomList.refresh(server);
            alpha.drain();
            h.assertTrue(db.loadRooms().heard().get("alpha") == 5_060_000L, "unchanged rooms: only the time heard is written");
            h.assertTrue(db.fetchNoticesAfter(0, 1000).stream().filter(n -> n.kind().equals(RoomList.KIND)).count() == 1, "and nobody is told");

            db.saveRooms("beta", 1, List.of(row("beta", 0, owner.getUUID(), RoomRow.ACTIVE)));
            RoomList.reload(server);
            alpha.drain();
            h.assertTrue(RoomList.count(server, owner.getUUID()) == 2, "network scope: the room here and the one on beta, got " + RoomList.count(server, owner.getUUID()));

            h.setBlock(rel, Blocks.AIR);
            RoomList.changed();
            h.assertTrue(RoomList.dirty(), "a change waits for the next tick");
            RoomList.refresh(server);
            alpha.drain();
            Optional<RoomRow> after = db.loadRooms().rows().stream().filter(r -> r.owner().equals(owner.getUUID()) && r.server().equals("alpha")).findFirst();
            h.assertTrue(after.isPresent() && after.get().machines() == 0 && after.get().machine().isEmpty(), "with its machine broken the room is written with none placed, got " + after);
            h.assertTrue(db.fetchNoticesAfter(0, 1000).stream().filter(n -> n.kind().equals(RoomList.KIND)).count() == 2, "and the others are told again");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            Storage.useForTests(null);
            RoomList.resetForTests(null);
            h.setBlock(rel, Blocks.AIR);
        }
        h.succeed();
    }
}
