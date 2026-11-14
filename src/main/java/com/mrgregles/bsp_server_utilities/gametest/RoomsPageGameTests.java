package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.rooms.RoomGrace;
import com.mrgregles.bsp_server_utilities.rooms.RoomList;
import com.mrgregles.bsp_server_utilities.rooms.RoomLosses;
import com.mrgregles.bsp_server_utilities.rooms.RoomRow;
import com.mrgregles.bsp_server_utilities.rooms.RoomSettings;
import com.mrgregles.bsp_server_utilities.rooms.RoomsPage;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
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

import java.util.ArrayList;
import java.util.List;

/**
 * SU-58, {@code /su rooms} (owner decisions D443, D448, D452 Z3, Z4, D453 R1 to R3, D457): the page's order, the packet
 * through the wire, who may type the command; with {@code -PwithCm} a player's real rooms on the page with what can be
 * given back and why not, another server's room, a room in grace, and giving back (in grace, logged, refused when not
 * theirs, with a machine placed, or already given back).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RoomsPageGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static RoomsViewPacket.Room room(String server, int x, int size, String name, boolean grace) {
        return new RoomsViewPacket.Room(server, x, 0, size, name, "", 0, grace, grace ? 1000 : 0, grace ? "burnt" : "", RoomsViewPacket.GIVE_OK);
    }

    @GameTest(template = EMPTY)
    public void the_page_is_ordered_and_crosses_the_wire_and_only_rooms_is_open_to_players(GameTestHelper h) {
        List<RoomsViewPacket.Room> rooms = new ArrayList<>(List.of(room("beta", 1, 13, "Far", false), room("alpha", 2, 7, "Farm", false), room("alpha", 3, 3, "", true),
                room("alpha", 4, 11, "Ore", false), room("beta", 5, 9, "", true), room("alpha", 6, 7, "Bees", false)));
        rooms.sort(RoomsPage.order("alpha"));
        List<Integer> xs = rooms.stream().map(RoomsViewPacket.Room::x).toList();
        h.assertTrue(xs.equals(List.of(4, 6, 2, 1, 5, 3)), "this server's rooms, then other servers', then rooms in grace; biggest first, then by name: got " + xs);

        RoomsViewPacket p = new RoomsViewPacket(5, 4, true, false, "alpha", 7, List.copyOf(rooms));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        p.encode(buf);
        RoomsViewPacket back = RoomsViewPacket.decode(buf);
        h.assertTrue(back.equals(p), "the page arrives as sent");

        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        var parse = server.getCommands().getDispatcher().parse("su rooms", player.createCommandSourceStack());
        boolean reached = !parse.getReader().canRead() && parse.getExceptions().isEmpty();
        boolean cm = CompactMachines.present();
        h.assertTrue(reached == cm, "a player who is not staff reaches /su rooms exactly where Compact Machines runs (" + cm + "), got " + reached);
        for (String staff : List.of("su status", "su clearitems", "su rules", "su players", "su setup", "su icons", "su serverimages", "su clientcheck",
                "su client", "su away")) {
            var panel = server.getCommands().getDispatcher().parse(staff, player.createCommandSourceStack());
            h.assertTrue(panel.getReader().canRead() || !panel.getExceptions().isEmpty(), "and never the staff commands: /" + staff + " was reached");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void with_compact_machines_the_page_shows_a_players_rooms_and_one_is_given_back(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no page is made (add -PwithCm)");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        Block normal = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_normal"));
        BlockPos placedRel = new BlockPos(1, 1, 1);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        long now = 900L * 86_400_000L;
        List<ChunkPos> made = new ArrayList<>();
        try {
            Storage.useForTests(alpha);
            RoomList.resetForTests(() -> now);
            RoomLosses.useClockForTests(() -> now);
            RoomSettings.useForTests(new RoomSettings(5, true, "", 7));
            ServerPlayer ann = TestPlayers.mockServerPlayer(h), bob = TestPlayers.mockServerPlayer(h);
            ChunkPos free = CmTestBridge.newRoom(server, ann.getUUID()), placed = CmTestBridge.newRoom(server, ann.getUUID()), lost = CmTestBridge.newRoom(server, ann.getUUID());
            ChunkPos bobs = CmTestBridge.newRoom(server, bob.getUUID());
            made.addAll(List.of(free, placed, lost, bobs));
            h.setBlock(placedRel, normal);
            CmTestBridge.bind(h.getBlockEntity(placedRel), placed);
            RoomLosses.lose(server, lost, "burnt");
            db.saveRooms("beta", 1, List.of(new RoomRow("beta", 40, 41, ann.getUUID(), 13, "Base", "minecraft:overworld 1 2 3", 1, RoomRow.ACTIVE, 0, "", false, "")));
            RoomList.reload(server);
            alpha.drain();

            RoomsViewPacket page = RoomsPage.view(ann);
            h.assertTrue(page.limit() == 5 && page.network() && !page.exempt() && page.server().equals("alpha") && page.graceDays() == 7, "the settings: " + page);
            h.assertTrue(page.count() == 3, "two rooms here count and one on beta; the lost one does not: " + page.count());
            h.assertTrue(page.rooms().size() == 4, "every room of hers on the page: " + page.rooms());
            h.assertTrue(page.rooms().get(3).grace() && page.rooms().get(3).x() == lost.x && page.rooms().get(3).reason().equals("burnt") && page.rooms().get(3).graceLeftMs() == 7 * 86_400_000L,
                    "the lost room comes last, with why and how long is left");
            h.assertTrue(page.rooms().get(2).server().equals("beta") && page.rooms().get(2).give() == RoomsViewPacket.GIVE_ELSEWHERE && page.rooms().get(2).machine().equals("minecraft:overworld"),
                    "beta's room after this server's, given back only on beta (Z4), with the dimension its machine stands in and never the coordinates (D457)");
            RoomsViewPacket.Room withMachine = page.rooms().stream().filter(r -> r.x() == placed.x && r.z() == placed.z).findFirst().orElseThrow();
            RoomsViewPacket.Room empty = page.rooms().stream().filter(r -> r.x() == free.x && r.z() == free.z).findFirst().orElseThrow();
            h.assertTrue(withMachine.give() == RoomsViewPacket.GIVE_PLACED && withMachine.machines() == 1 && !withMachine.machine().isEmpty()
                    && !withMachine.machine().contains(" "), "a room with a placed machine: pick it up first (R3); its dimension only (D457): " + withMachine.machine());
            h.assertTrue(empty.give() == RoomsViewPacket.GIVE_OK && empty.machine().isEmpty(), "an empty room with no machine placed can be given back");

            h.assertTrue(RoomsPage.giveBack(bob, free) == RoomsPage.GiveBack.NOT_YOURS, "nobody gives back someone else's room");
            h.assertTrue(RoomsPage.giveBack(ann, placed) == RoomsPage.GiveBack.PLACED, "nor one with a placed machine");
            h.assertTrue(RoomsPage.giveBack(ann, lost) == RoomsPage.GiveBack.GRACE, "nor one already in grace");
            h.assertTrue(RoomsPage.giveBack(ann, free) == RoomsPage.GiveBack.GIVEN, "her empty room is given back");
            h.assertTrue(RoomGrace.get(server).get(free).map(RoomGrace.Entry::reason).orElse("").equals("given_back"), "it is in grace, given back");
            h.assertTrue(RoomsPage.view(ann).count() == 2, "and no longer counts");
            boolean logged = ActionLog.recent(server, 50, false, List.of(LogEntry.ROOM)).stream()
                    .anyMatch(e -> e.actor().equals(ann.getGameProfile().getName()) && e.text().contains(free.x + ", " + free.z) && e.roleLabel().equals("Player"));
            h.assertTrue(logged, "the action log says she gave it back (D448)");
            h.assertTrue(RoomsPage.giveBack(ann, free) == RoomsPage.GiveBack.GRACE, "giving it back twice does nothing more");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            Storage.useForTests(null);
            RoomList.resetForTests(null);
            RoomLosses.useClockForTests(null);
            RoomSettings.useForTests(null);
            made.forEach(r -> RoomGrace.get(server).remove(r));
            h.setBlock(placedRel, Blocks.AIR);
        }
        h.succeed();
    }
}
