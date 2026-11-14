package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.rooms.RoomGrace;
import com.mrgregles.bsp_server_utilities.rooms.RoomSettings;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import com.mrgregles.bsp_server_utilities.network.AdminRoomsPacket;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.rooms.RoomList;
import com.mrgregles.bsp_server_utilities.rooms.RoomLosses;
import com.mrgregles.bsp_server_utilities.rooms.RoomNotices;
import com.mrgregles.bsp_server_utilities.rooms.RoomOutbox;
import com.mrgregles.bsp_server_utilities.rooms.RoomRow;
import com.mrgregles.bsp_server_utilities.rooms.RoomTable;
import com.mrgregles.bsp_server_utilities.rooms.RoomsAdmin;
import com.mrgregles.bsp_server_utilities.rooms.RoomsPage;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The audit of the room limit before 0.11.0 (2026-11-12): notices about another server's room sent again until its rows
 * show them; unknown reasons never stored or shown raw; server names compared ignoring case; a change rewrites the rows
 * at most every five seconds; long names and server ids never stop a packet; a player inside a room is seen; give-backs
 * at most twice a second; the picked owner always among the owners sent. The clear's own fixes are in
 * {@link RoomClearGameTests} ({@code -PwithCm}).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RoomAuditGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static RoomRow row(String server, int x, String state, int machines, boolean told) {
        return new RoomRow(server, x, 0, UUID.randomUUID(), 7, "", "", machines, state, 0, "", told, "");
    }

    @GameTest(template = EMPTY)
    public void notices_about_another_servers_room_are_sent_again_until_its_rows_show_them(GameTestHelper h) {
        long t0 = 1_000_000L;
        RoomOutbox.Item lost = new RoomOutbox.Item(RoomLosses.KIND_LOST, "Alpha", 3, 0, "Alpha\n3\n0\nburnt", t0, t0, 1);
        RoomOutbox.Item told = new RoomOutbox.Item(RoomNotices.KIND_TOLD, "alpha", 3, 0, "alpha\n3\n0", t0, t0, 1);
        Optional<RoomRow> active = Optional.of(row("alpha", 3, RoomRow.ACTIVE, 0, false));
        Optional<RoomRow> grace = Optional.of(row("alpha", 3, RoomRow.GRACE, 0, false));
        Optional<RoomRow> graceTold = Optional.of(row("alpha", 3, RoomRow.GRACE, 0, true));
        Optional<RoomRow> placed = Optional.of(row("alpha", 3, RoomRow.ACTIVE, 1, false));
        long later = t0 + 120_000L;

        h.assertTrue(RoomOutbox.verdict(lost, active, t0 + 10_000L, true, later) == RoomOutbox.Verdict.WAIT, "the room's server has not written since: wait");
        h.assertTrue(RoomOutbox.verdict(lost, active, null, true, later) == RoomOutbox.Verdict.WAIT, "a server never heard from: wait");
        h.assertTrue(RoomOutbox.verdict(lost, active, t0 + 60_000L, true, later) == RoomOutbox.Verdict.AGAIN, "it wrote a minute after and the room is not in grace: missed, sent again");
        h.assertTrue(RoomOutbox.verdict(lost, grace, t0 + 60_000L, true, later) == RoomOutbox.Verdict.DONE, "the room is in grace: done");
        h.assertTrue(RoomOutbox.verdict(lost, placed, t0 + 60_000L, true, later) == RoomOutbox.Verdict.DONE, "a placed machine (the room's server refuses on it): done, never sent again");
        h.assertTrue(RoomOutbox.verdict(lost, Optional.empty(), null, true, later) == RoomOutbox.Verdict.DONE
                && RoomOutbox.verdict(lost, Optional.empty(), null, false, later) == RoomOutbox.Verdict.WAIT, "no row: done once the table was read, else wait");
        RoomOutbox.Item tenth = new RoomOutbox.Item(lost.kind(), lost.server(), 3, 0, lost.payload(), t0, t0, 10);
        RoomOutbox.Item old = new RoomOutbox.Item(lost.kind(), lost.server(), 3, 0, lost.payload(), t0 - 25 * 3_600_000L, t0, 2);
        h.assertTrue(RoomOutbox.verdict(tenth, active, t0 + 60_000L, true, later) == RoomOutbox.Verdict.DONE
                && RoomOutbox.verdict(old, active, t0 + 60_000L, true, later) == RoomOutbox.Verdict.DONE, "given up after ten sends or a day");
        h.assertTrue(RoomOutbox.verdict(told, grace, t0 + 60_000L, true, later) == RoomOutbox.Verdict.AGAIN, "told: not marked told after a write: sent again");
        h.assertTrue(RoomOutbox.verdict(told, graceTold, t0 + 60_000L, true, later) == RoomOutbox.Verdict.DONE
                && RoomOutbox.verdict(told, active, t0 + 60_000L, true, later) == RoomOutbox.Verdict.DONE, "told: marked told, or no longer in grace: done");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void reasons_server_names_and_long_strings_are_kept_in_bounds(GameTestHelper h) {
        h.assertTrue(RoomLosses.known("burnt").equals("burnt") && RoomLosses.known("freed").equals("freed"), "known reasons are kept");
        h.assertTrue(RoomLosses.known("x".repeat(40)).equals("destroyed") && RoomLosses.known(null).equals("destroyed"), "an unknown or overlong one is 'destroyed'");
        var lead = RoomNotices.lead("alpha", 7, "made_up");
        h.assertTrue(lead.getContents() instanceof TranslatableContents t && t.getArgs().length == 3 && t.getArgs()[2] instanceof net.minecraft.network.chat.Component c
                && c.getContents() instanceof TranslatableContents r && r.getKey().endsWith(".reason.destroyed"), "the owner never reads a raw key: " + lead);

        UUID ann = UUID.randomUUID();
        RoomTable t = new RoomTable(Map.of("Alpha", 0L, "beta", 0L), List.of(
                new RoomRow("Alpha", 1, 0, ann, 7, "", "", 0, RoomRow.ACTIVE, 0, "", false, ""),
                new RoomRow("beta", 2, 0, ann, 7, "", "", 0, RoomRow.ACTIVE, 0, "", false, "")));
        h.assertTrue(RoomList.count(1, ann, true, t, "alpha") == 2, "this server's own rows under another spelling are not counted twice");
        h.assertTrue(RoomList.stale(t, "alpha", RoomList.STALE_MS).equals(List.of("beta")), "nor named as a stale server");

        String longName = "s".repeat(80);
        RoomsViewPacket.Room room = new RoomsViewPacket.Room(longName, 1, 2, 7, "n".repeat(90), "m".repeat(200), 1, false, 0, "r".repeat(40), RoomsViewPacket.GIVE_OK);
        RoomsViewPacket p = new RoomsViewPacket(5, 1, true, false, longName, 7, List.of(room));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        p.encode(buf);
        RoomsViewPacket back = RoomsViewPacket.decode(buf);
        h.assertTrue(back.server().length() == 64 && back.rooms().get(0).machine().length() == 160 && back.rooms().get(0).reason().length() == 32,
                "a server id over 64 characters and other long strings are cut, never an error");
        AdminRoomsPacket a = new AdminRoomsPacket(0, true, false, longName, 7, 0, List.of(new AdminRoomsPacket.Stale(longName, 1)),
                List.of(new AdminRoomsPacket.Owner(ann, "o".repeat(30), 1, 1, 1, false)), ann, "p".repeat(30), List.of(room), "n".repeat(30));
        FriendlyByteBuf abuf = new FriendlyByteBuf(Unpooled.buffer());
        a.encode(abuf);
        AdminRoomsPacket aback = AdminRoomsPacket.decode(abuf);
        h.assertTrue(aback.server().length() == 64 && aback.owners().get(0).name().length() == 16 && aback.pickedName().length() == 16, "the tab's packet too");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void list_rewrites_are_spaced_the_picked_owner_is_kept_and_players_are_seen(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        try {
            RoomList.resetForTests(null);
            RoomList.changed();
            for (int i = 0; i < 99; i++) {
                RoomList.tick(server);
            }
            h.assertTrue(RoomList.dirty(), "a change waits up to five seconds after the last refresh");
            RoomList.tick(server);
            h.assertTrue(!RoomList.dirty(), "then it is read and shared");
            RoomList.changed();
            RoomList.tick(server);
            h.assertTrue(RoomList.dirty(), "another change right after waits again: placing machines over and over rewrites the rows at most every five seconds");
        } finally {
            RoomList.resetForTests(null);
        }

        List<AdminRoomsPacket.Owner> many = new ArrayList<>();
        for (int i = 0; i < AdminRoomsPacket.MAX_OWNERS + 5; i++) {
            many.add(new AdminRoomsPacket.Owner(UUID.randomUUID(), "o" + i, 1, 1, 1, false));
        }
        UUID last = many.get(many.size() - 1).id();
        List<AdminRoomsPacket.Owner> kept = RoomsAdmin.keepPicked(many, last);
        h.assertTrue(kept.size() == many.size() && kept.get(AdminRoomsPacket.MAX_OWNERS - 1).id().equals(last), "an owner picked past the first 1000 is moved among them");
        h.assertTrue(RoomsAdmin.keepPicked(many, many.get(3).id()) == many, "one already among them: unchanged");

        ServerPlayer inside = TestPlayers.mockServerPlayer(h), outside = TestPlayers.mockServerPlayer(h);
        inside.setPos(16 * 5 + 8, 40, 16 * 7 + 8);
        outside.setPos(16 * 6 + 8, 40, 16 * 7 + 8);
        h.assertTrue(RoomsPage.occupied(List.of(outside, inside), new ChunkPos(5, 7)) && !RoomsPage.occupied(List.of(outside), new ChunkPos(5, 7)),
                "a player standing in the room's chunk is inside; one next door is not");

        ServerPlayer clicker = TestPlayers.mockServerPlayer(h);
        h.assertTrue(RoomsPage.mayAct(clicker) && !RoomsPage.mayAct(clicker), "a second give-back in the same tick is ignored");
        clicker.tickCount += 10;
        h.assertTrue(RoomsPage.mayAct(clicker), "half a second later it is taken");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void owners_decisions_after_the_audit_d455_to_d457(GameTestHelper h) throws Exception {
        // D455: a room in grace is not brought back past the limit
        h.assertTrue(RoomLosses.reviveRefused(true, false, 5, 5) && RoomLosses.reviveRefused(true, false, 6, 5), "at or over the limit: placing the old machine is refused");
        h.assertTrue(!RoomLosses.reviveRefused(true, false, 4, 5) && !RoomLosses.reviveRefused(true, true, 9, 5) && !RoomLosses.reviveRefused(false, false, 9, 0),
                "under the limit, exempt, or no limit: it comes back");
        // D457: players see the dimension only
        h.assertTrue(RoomsPage.dimensionOnly("minecraft:overworld 1204 64 -388").equals("minecraft:overworld") && RoomsPage.dimensionOnly("").isEmpty()
                && RoomsPage.dimensionOnly("minecraft:the_nether").equals("minecraft:the_nether"), "a machine's coordinates never reach the player's page");
        // D456: a retired server's rows
        long now = 10_000_000L;
        RoomTable t = new RoomTable(Map.of("alpha", now, "Beta", now - RoomList.STALE_MS, "gamma", now - 60_000L),
                List.of(row("Beta", 1, RoomRow.ACTIVE, 0, false), row("delta", 2, RoomRow.ACTIVE, 0, false)));
        h.assertTrue(RoomsAdmin.mayForget("ALPHA", "alpha", t, now) == RoomsAdmin.Forget.SELF, "never this server's");
        h.assertTrue(RoomsAdmin.mayForget("gamma", "alpha", t, now) == RoomsAdmin.Forget.HEARD, "nor one heard from in the last 5 minutes");
        h.assertTrue(RoomsAdmin.mayForget("nowhere", "alpha", t, now) == RoomsAdmin.Forget.UNKNOWN, "nor a name the list does not have");
        h.assertTrue(RoomsAdmin.mayForget("beta", "alpha", t, now) == RoomsAdmin.Forget.OK && RoomsAdmin.mayForget("delta", "alpha", t, now) == RoomsAdmin.Forget.OK,
                "a server stale for 5 minutes, or with rows and never heard, can be forgotten");
        MemoryBackend db = new MemoryBackend();
        db.saveRooms("Beta", now, List.of(row("Beta", 1, RoomRow.ACTIVE, 0, false), row("Beta", 2, RoomRow.GRACE, 0, false)));
        db.saveRooms("alpha", now, List.of(row("alpha", 3, RoomRow.ACTIVE, 0, false)));
        h.assertTrue(db.forgetRooms("beta") == 2, "both of Beta's rooms are dropped, the name compared ignoring case as MySQL does");
        RoomTable left = db.loadRooms();
        h.assertTrue(left.rows().size() == 1 && left.rows().get(0).server().equals("alpha") && !left.heard().containsKey("Beta"), "and its heard time; alpha's stay: " + left);

        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend shared = new MemoryBackend();
        Storage alpha = Storage.startWith(server, shared, "alpha");
        try {
            Storage.useForTests(alpha);
            RoomLosses.useClockForTests(() -> now);
            shared.saveRooms("beta", now - 3 * RoomList.STALE_MS, List.of(row("beta", 1, RoomRow.ACTIVE, 0, false)));
            shared.saveRooms("gamma", now - 60_000L, List.of(row("gamma", 2, RoomRow.ACTIVE, 0, false)));
            h.assertTrue(RoomsAdmin.forget(server.createCommandSourceStack(), "alpha") == 0, "this server's own rows are refused at once");
            h.assertTrue(RoomsAdmin.forget(server.createCommandSourceStack(), "gamma") == 1 && RoomsAdmin.forget(server.createCommandSourceStack(), "beta") == 1, "the console asks for two");
            alpha.drain();
            RoomTable after = shared.loadRooms();
            h.assertTrue(after.rows().size() == 1 && after.rows().get(0).server().equals("gamma"), "beta's rows are gone; gamma, heard a minute ago, is kept: " + after.rows());
            h.assertTrue(shared.fetchNoticesAfter(0, 100).stream().filter(n -> n.kind().equals(RoomList.KIND)).count() == 1, "and the other servers are told once to read the list again");
        } finally {
            Storage.useForTests(null);
            RoomLosses.useClockForTests(null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void with_compact_machines_a_given_back_room_is_not_placed_back_past_the_limit(GameTestHelper h) {
        if (!ModList.get().isLoaded(CompactMachines.MODID)) {
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities test: Compact Machines is not loaded, no machine is placed (add -PwithCm)");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        Block normal = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(CompactMachines.MODID, "machine_normal"));
        BlockPos at = new BlockPos(2, 1, 2), other = new BlockPos(4, 1, 2);
        long now = 700L * 86_400_000L;
        ServerPlayer ann = TestPlayers.mockServerPlayer(h);
        RoomGrace grace = RoomGrace.get(server);
        List<ChunkPos> made = new ArrayList<>();
        try {
            RoomList.resetForTests(() -> now);
            RoomLosses.useClockForTests(() -> now);
            ChunkPos kept = CmTestBridge.newRoom(server, ann.getUUID()), given = CmTestBridge.newRoom(server, ann.getUUID());
            made.addAll(List.of(kept, given));
            grace.put(new RoomGrace.Entry(given, ann.getUUID(), 7, now, now + 7 * 86_400_000L, "given_back", true));
            RoomSettings.useForTests(new RoomSettings(1, false, "", 7));
            h.assertTrue(RoomList.count(server, ann.getUUID()) == 1, "one room counts, the given-back one does not");

            h.setBlock(at, normal);
            CmTestBridge.bind(h.getBlockEntity(at), given); // as Compact Machines binds a placed machine before the place event
            h.assertTrue(RoomLosses.refusesRevive(h.getLevel(), h.absolutePos(at), ann), "at the limit, placing the given-back room's machine is refused");
            h.assertTrue(CompactMachines.machinesFor(server, given).isEmpty() && grace.contains(given), "the machine is unbound again and the room stays in grace");

            RoomSettings.useForTests(new RoomSettings(2, false, "", 7));
            CmTestBridge.bind(h.getBlockEntity(at), given);
            h.assertTrue(!RoomLosses.refusesRevive(h.getLevel(), h.absolutePos(at), ann), "under the limit it is placed, and the room comes back at the next refresh");
            h.setBlock(other, normal);
            CmTestBridge.bind(h.getBlockEntity(other), kept);
            RoomSettings.useForTests(new RoomSettings(1, false, "", 7));
            h.assertTrue(!RoomLosses.refusesRevive(h.getLevel(), h.absolutePos(other), ann), "a machine of a room in use is never refused");
        } finally {
            RoomList.resetForTests(null);
            RoomLosses.useClockForTests(null);
            RoomSettings.useForTests(null);
            made.forEach(grace::remove);
            h.setBlock(at, Blocks.AIR);
            h.setBlock(other, Blocks.AIR);
        }
        h.succeed();
    }
}
