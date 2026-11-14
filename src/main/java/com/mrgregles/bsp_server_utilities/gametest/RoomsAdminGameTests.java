package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.AdminRoomsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminRoomsPacket;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.rooms.RoomGrace;
import com.mrgregles.bsp_server_utilities.rooms.RoomList;
import com.mrgregles.bsp_server_utilities.rooms.RoomLosses;
import com.mrgregles.bsp_server_utilities.rooms.RoomNotices;
import com.mrgregles.bsp_server_utilities.rooms.RoomRow;
import com.mrgregles.bsp_server_utilities.rooms.RoomSettings;
import com.mrgregles.bsp_server_utilities.rooms.RoomsAdmin;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SU-59, the admins' ROOMS tab (owner decisions D448, D452 look A, D453 R4 to R7): owners sorted most over the limit
 * first, then most rooms; the packets through the wire; what FREE may do with each kind of room; the owner's line names
 * no staff member; a player who may not edit is refused; with {@code -PwithCm} FREE on real rooms (refused for a
 * non-admin, a placed machine, a room not the owner's, a room already in grace; freed into grace and logged under the
 * staff member's name), and the tab's data for a real owner with a stale server named.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RoomsAdminGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static RoomRow row(String server, int x, UUID owner, String name, String state, int machines) {
        return new RoomRow(server, x, 0, owner, 7, "", machines > 0 ? "minecraft:overworld 1 2 3" : "", machines, state, RoomRow.GRACE.equals(state) ? 5000 : 0,
                RoomRow.GRACE.equals(state) ? "burnt" : "", false, name);
    }

    @GameTest(template = EMPTY)
    public void the_rooms_tab_orders_owners_crosses_the_wire_and_refuses_a_player(GameTestHelper h) {
        UUID ann = UUID.randomUUID(), bob = UUID.randomUUID(), cat = UUID.randomUUID(), dan = UUID.randomUUID(), eve = UUID.randomUUID();
        List<RoomRow> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(row(i < 4 ? "alpha" : "beta", i, ann, "Ann", RoomRow.ACTIVE, 0)); // 7 with a limit of 5: two over
        }
        for (int i = 0; i < 5; i++) {
            rows.add(row("alpha", 10 + i, bob, i == 0 ? "" : "Bob", RoomRow.ACTIVE, 1)); // at the limit; the name from a row that has it
        }
        for (int i = 0; i < 4; i++) {
            rows.add(row(i == 0 ? "alpha" : i == 1 ? "beta" : "gamma", 20 + i, cat, "Cat", RoomRow.ACTIVE, 0)); // under, on three servers
        }
        for (int i = 0; i < 9; i++) {
            rows.add(row("alpha", 30 + i, dan, "Dan", RoomRow.ACTIVE, 0)); // exempt with nine
        }
        rows.add(row("alpha", 40, eve, "Eve", RoomRow.GRACE, 0)); // only rooms in grace: counts none
        rows.add(row("alpha", 41, eve, "Eve", RoomRow.GRACE, 0));
        rows.add(row("alpha", 50, CompactMachines.NO_OWNER, "", RoomRow.ACTIVE, 0)); // a slot kept for nobody is nobody's
        List<AdminRoomsPacket.Owner> owners = RoomsAdmin.owners(rows, 5, id -> id.equals(dan));
        List<String> order = owners.stream().map(AdminRoomsPacket.Owner::name).toList();
        h.assertTrue(order.equals(List.of("Ann", "Dan", "Bob", "Cat", "Eve")), "most over the limit first, then most rooms (R4); exempt never over: " + order);
        AdminRoomsPacket.Owner a = owners.get(0), d = owners.get(1), c = owners.get(3), e = owners.get(4);
        h.assertTrue(a.over(5) == 2 && a.count() == 7 && a.servers() == 2, "Ann: seven on two servers, two over: " + a);
        h.assertTrue(d.exempt() && d.over(5) == 0 && d.count() == 9, "Dan exempt: " + d);
        h.assertTrue(c.servers() == 3 && c.over(5) == 0, "Cat on three servers: " + c);
        h.assertTrue(e.count() == 0 && e.rooms() == 2, "Eve's rooms in grace are listed but do not count: " + e);
        h.assertTrue(RoomsAdmin.owners(rows, 0, id -> false).stream().allMatch(o -> o.over(0) == 0), "with no limit nobody is over");
        List<String> byCount = RoomsAdmin.owners(rows, 0, id -> false).stream().map(AdminRoomsPacket.Owner::name).toList();
        h.assertTrue(byCount.equals(List.of("Dan", "Ann", "Bob", "Cat", "Eve")), "with no limit, most rooms first: " + byCount);

        // the packets through the wire
        List<RoomsViewPacket.Room> rooms = List.of(new RoomsViewPacket.Room("alpha", 1, 2, 9, "Base", "minecraft:overworld 1 2 3", 1, false, 0, "", RoomsViewPacket.GIVE_PLACED),
                new RoomsViewPacket.Room("beta", 3, 4, 3, "", "", 0, true, 86_400_000L, "freed", RoomsViewPacket.GIVE_GRACE));
        AdminRoomsPacket p = new AdminRoomsPacket(5, true, true, "alpha", 7, 2, List.of(new AdminRoomsPacket.Stale("gamma", 720_000L)), owners, ann, "Ann", rooms, "freed");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        p.encode(buf);
        h.assertTrue(AdminRoomsPacket.decode(buf).equals(p), "the tab's data arrives as sent");
        AdminRoomsEditPacket edit = new AdminRoomsEditPacket(AdminRoomsEditPacket.Action.FREE, ann, -7, 12);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        edit.encode(buf);
        h.assertTrue(AdminRoomsEditPacket.decode(buf).equals(edit), "a FREE arrives as sent");

        // what FREE may do: only this server's rooms in use with no placed machine (the server checks again)
        RoomRow here = row("alpha", 1, ann, "Ann", RoomRow.ACTIVE, 0);
        h.assertTrue(roomGive(here, "alpha") == RoomsViewPacket.GIVE_OK, "an empty room here can be freed");
        h.assertTrue(roomGive(row("alpha", 1, ann, "Ann", RoomRow.ACTIVE, 1), "alpha") == RoomsViewPacket.GIVE_PLACED, "not with a placed machine");
        h.assertTrue(roomGive(row("beta", 1, ann, "Ann", RoomRow.ACTIVE, 0), "alpha") == RoomsViewPacket.GIVE_ELSEWHERE, "another server's room is freed there (Z4)");
        h.assertTrue(roomGive(row("alpha", 1, ann, "Ann", RoomRow.GRACE, 0), "alpha") == RoomsViewPacket.GIVE_GRACE, "a room in grace is not freed again");

        // the owner's line names no staff member (R7)
        var lead = RoomNotices.lead("alpha", 9, "freed");
        h.assertTrue(lead.getContents() instanceof TranslatableContents t && t.getKey().equals("message.bsp_server_utilities.rooms.freed") && t.getArgs().length == 2,
                "the owner is told 'Staff freed your room' with the server and size only: " + lead);

        // operators are exempt in the list even when offline; a player who may not edit gets nothing
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h), player = TestPlayers.mockServerPlayer(h);
        GameProfile offlineOp = new GameProfile(UUID.randomUUID(), "su_test_op");
        server.getPlayerList().getOps().add(new ServerOpListEntry(offlineOp, 4, false));
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            List<AdminRoomsPacket.Owner> withOp = RoomsAdmin.owners(List.of(row("alpha", 1, offlineOp.getId(), "op", RoomRow.ACTIVE, 0)), 5,
                    id -> RoomsAdmin.exempt(server, id, ""));
            h.assertTrue(withOp.get(0).exempt(), "an offline operator shows as exempt");
            h.assertTrue(!RoomsAdmin.exempt(server, UUID.randomUUID(), "vip"), "an offline player who is not an operator is not");
            h.assertFalse(RoomsAdmin.apply(player, AdminRoomsEditPacket.of(AdminRoomsEditPacket.Action.REFRESH, AdminRoomsPacket.NONE)), "a player who may not edit gets no data");
            h.assertFalse(RoomsAdmin.apply(player, edit), "nor can they FREE (R6)");
            h.assertTrue(RoomsAdmin.apply(admin, AdminRoomsEditPacket.of(AdminRoomsEditPacket.Action.REFRESH, AdminRoomsPacket.NONE)), "an operator gets the tab");
        } finally {
            server.getPlayerList().getOps().remove(offlineOp);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    private static int roomGive(RoomRow r, String self) {
        return RoomsAdmin.room(r, self, 0).give();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void with_compact_machines_staff_free_a_room_and_the_log_names_them(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no room is freed (add -PwithCm)");
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
        ServerPlayer staff = TestPlayers.mockServerPlayer(h), bob = TestPlayers.mockServerPlayer(h), ann = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(staff.getGameProfile(), 4, false));
        try {
            Storage.useForTests(alpha);
            RoomList.resetForTests(() -> now);
            RoomLosses.useClockForTests(() -> now);
            RoomSettings.useForTests(new RoomSettings(1, true, "", 7));
            ChunkPos free = CmTestBridge.newRoom(server, ann.getUUID()), placed = CmTestBridge.newRoom(server, ann.getUUID());
            ChunkPos bobs = CmTestBridge.newRoom(server, bob.getUUID());
            made.addAll(List.of(free, placed, bobs));
            h.setBlock(placedRel, normal);
            CmTestBridge.bind(h.getBlockEntity(placedRel), placed);
            db.saveRooms("beta", now - 12 * 60_000L, List.of(new RoomRow("beta", 40, 41, ann.getUUID(), 13, "Base", "", 0, RoomRow.ACTIVE, 0, "", false, "Ann")));
            RoomList.reload(server);
            alpha.drain();
            h.assertTrue(RoomList.table().heard().containsKey("beta"), "beta's row was read: " + RoomList.table().heard());

            AdminRoomsPacket tab = RoomsAdmin.packet(server, ann.getUUID(), "");
            AdminRoomsPacket.Owner annRow = tab.owners().stream().filter(o -> o.id().equals(ann.getUUID())).findFirst().orElseThrow();
            h.assertTrue(annRow.count() == 3 && annRow.servers() == 2 && annRow.over(1) == 2 && annRow.name().equals("Ann"), "her rooms here and on beta, two over, named by beta's row: " + annRow);
            // the world keeps every room earlier tests made, so other owners are listed too: the order holds over the whole list (R4)
            List<AdminRoomsPacket.Owner> all = tab.owners();
            for (int i = 1; i < all.size(); i++) {
                AdminRoomsPacket.Owner prev = all.get(i - 1), next = all.get(i);
                h.assertTrue(prev.over(1) > next.over(1) || (prev.over(1) == next.over(1) && prev.count() >= next.count()), "most over first, then most rooms (R4): " + prev + " before " + next);
            }
            int annAt = all.indexOf(annRow), bobAt = all.indexOf(all.stream().filter(o -> o.id().equals(bob.getUUID())).findFirst().orElseThrow());
            h.assertTrue(annAt < bobAt, "Ann, two over, before Bob, at the limit");
            h.assertTrue(tab.rooms().size() == 3 && tab.pickedName().equals("Ann"), "the picked owner's rooms: " + tab.rooms());
            Map<String, Integer> gives = new java.util.HashMap<>();
            tab.rooms().forEach(r -> gives.put(r.server() + " " + r.x() + " " + r.z(), r.give()));
            h.assertTrue(gives.get("alpha " + free.x + " " + free.z) == RoomsViewPacket.GIVE_OK && gives.get("alpha " + placed.x + " " + placed.z) == RoomsViewPacket.GIVE_PLACED
                    && gives.get("beta 40 41") == RoomsViewPacket.GIVE_ELSEWHERE, "FREE here for the empty room, not with a machine, and beta's room there: " + gives);
            h.assertTrue(tab.stale().size() == 1 && tab.stale().get(0).server().equals("beta") && tab.stale().get(0).ageMs() == 12 * 60_000L,
                    "beta, not heard from for 12 minutes, is named with its age (R5): " + tab.stale());

            AdminRoomsEditPacket freeIt = new AdminRoomsEditPacket(AdminRoomsEditPacket.Action.FREE, ann.getUUID(), free.x, free.z);
            h.assertFalse(RoomsAdmin.apply(bob, freeIt), "a player who may not edit cannot FREE");
            h.assertFalse(RoomGrace.get(server).contains(free), "and the room is untouched");
            h.assertTrue(RoomsAdmin.free(staff, ann.getUUID(), placed) == RoomsAdmin.Free.PLACED, "a room with a placed machine is not freed");
            h.assertTrue(RoomsAdmin.free(staff, bob.getUUID(), free) == RoomsAdmin.Free.GONE, "nor a room that is not the named owner's");
            h.assertTrue(RoomsAdmin.apply(staff, freeIt), "staff free her empty room");
            RoomGrace.Entry entry = RoomGrace.get(server).get(free).orElse(null);
            h.assertTrue(entry != null && entry.reason().equals("freed") && entry.owner().equals(ann.getUUID()) && entry.until() == now + 7 * 86_400_000L,
                    "it is in grace for seven days, freed: " + entry);
            LogEntry logged = ActionLog.recent(server, 50, false, List.of(LogEntry.ROOM)).stream()
                    .filter(e -> e.text().contains(free.x + ", " + free.z) && e.text().startsWith("freed")).findFirst().orElse(null);
            h.assertTrue(logged != null && logged.actor().equals(staff.getGameProfile().getName()) && logged.roleLabel().equals("Operator"),
                    "the action log names the staff member (D448): " + logged);
            h.assertTrue(RoomsAdmin.free(staff, ann.getUUID(), free) == RoomsAdmin.Free.GRACE, "freeing it twice does nothing more");
            AdminRoomsPacket after = RoomsAdmin.packet(server, ann.getUUID(), "");
            AdminRoomsPacket.Owner annAfter = after.owners().stream().filter(o -> o.id().equals(ann.getUUID())).findFirst().orElseThrow();
            h.assertTrue(annAfter.count() == 2 && annAfter.rooms() == 3 && after.rooms().stream().filter(RoomsViewPacket.Room::grace).count() == 1,
                    "it no longer counts, and is listed in grace: " + annAfter);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            server.getPlayerList().getOps().remove(staff.getGameProfile());
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
