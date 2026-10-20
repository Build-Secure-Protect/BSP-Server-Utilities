package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffData;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.access.StaffGroups;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import com.mrgregles.bsp_server_utilities.network.AdminStaffEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminStaffPacket;
import com.mrgregles.bsp_server_utilities.network.StaffViewPacket;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Staff online, server side (owner decisions D4, D23, D24, D57, D61 to D63): the staff groups shared for the network,
 * presence from another server and its going stale, away, the last-seen record at logout, and offline members found
 * through LuckPerms (with the LuckPerms dev jar; that part skips itself without it).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class StaffGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void staff_groups_are_shared_for_the_network_and_the_newest_copy_wins(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        StaffGroups before = StaffData.get(server).groups();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            StaffGroups set = StaffDirectory.setGroups(server, List.of(new Staff.Role("admin", "Admin", 0xB58CFF), new Staff.Role("mod", "Moderator", 0x19D3B0), new Staff.Role("ADMIN", "Twice", 0)), false);
            alpha.drain();
            h.assertTrue(set.roles().size() == 2 && !set.operators() && StaffData.get(server).seeded(), "duplicates dropped, operators off, marked seeded: " + set);
            h.assertTrue(set.equals(db.loadStaffGroups()), "written for the network: " + db.loadStaffGroups());
            h.assertTrue(db.fetchNoticesAfter(0, 50).stream().anyMatch(n -> n.kind().equals(StaffDirectory.KIND_GROUPS)), "and announced");
            StaffGroups newer = new StaffGroups(List.of(new Staff.Role("owner", "Owner", 0xFFD23A)), true, set.revision() + 10);
            StaffDirectory.apply(server, newer);
            h.assertTrue(StaffData.get(server).groups().equals(newer), "a newer shared copy replaces ours");
            StaffDirectory.apply(server, set);
            h.assertTrue(StaffData.get(server).groups().equals(newer), "an older one never does");
            h.assertTrue(StaffGroups.load(newer.save()).equals(newer), "saved and loaded unchanged");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            StaffData.get(server).setGroups(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void staff_on_other_servers_show_until_their_list_goes_stale(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong now = new AtomicLong(1_000_000L);
        StaffDirectory.useForTests(now::get, List::of);
        Staff.useForTests(List.of("admin:Admin:#B58CFF", "mod:Moderator:#19D3B0"), true);
        UUID ferrous = UUID.randomUUID(), quill = UUID.randomUUID();
        try {
            StaffDirectory.forgetRemote();
            String payload = "BSP-Wild\n" + ferrous + "|Ferrous|admin|0\n" + quill + "|Quill|mod|1";
            StaffDirectory.applyPresence(payload);
            List<StaffDirectory.Member> roster = StaffDirectory.roster(server);
            StaffDirectory.Member f = roster.stream().filter(m -> m.id().equals(ferrous)).findFirst().orElse(null);
            StaffDirectory.Member q = roster.stream().filter(m -> m.id().equals(quill)).findFirst().orElse(null);
            h.assertTrue(f != null && f.state() == StaffDirectory.State.ONLINE && f.server().equals("BSP-Wild") && f.role().label().equals("Admin"), "online on another server with their role: " + f);
            h.assertTrue(q != null && q.state() == StaffDirectory.State.AWAY && q.role().group().equals("mod"), "away shows as away: " + q);
            h.assertTrue(roster.indexOf(f) < roster.indexOf(q), "admins before moderators: " + roster);
            now.addAndGet(StaffDirectory.STALE_MS + 1000);
            h.assertTrue(StaffDirectory.roster(server).stream().noneMatch(m -> m.id().equals(ferrous) && m.state() != StaffDirectory.State.OFFLINE), "a list older than 90 s no longer counts");
            h.assertTrue(StaffDirectory.decode("BSP-Wild\nnot-a-line\n" + ferrous + "|F|admin|0").getValue().size() == 1, "a bad line is skipped");
        } finally {
            StaffDirectory.forgetRemote();
            StaffDirectory.useReal();
            Staff.useConfig();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void staff_here_can_be_away_and_leave_a_last_seen_record(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        List<ServerPlayer> online = new ArrayList<>(List.of(admin));
        AtomicLong now = new AtomicLong(5_000_000L);
        StaffDirectory.useForTests(now::get, () -> online);
        Staff.useForTests(List.of(), true);
        try {
            StaffDirectory.Member m = StaffDirectory.roster(server).stream().filter(x -> x.id().equals(admin.getUUID())).findFirst().orElse(null);
            h.assertTrue(m != null && m.state() == StaffDirectory.State.ONLINE && m.role().operator() && m.server().equals(Storage.currentServerId()), "an operator online here is staff, on this server: " + m);
            h.assertTrue(StaffDirectory.toggleAway(admin) && StaffDirectory.isAway(admin.getUUID()), "/su away marks them away");
            h.assertTrue(StaffDirectory.roster(server).stream().anyMatch(x -> x.id().equals(admin.getUUID()) && x.state() == StaffDirectory.State.AWAY), "and the roster shows it");
            h.assertFalse(StaffDirectory.toggleAway(admin), "again: back");
            StaffDirectory.toggleAway(admin);
            StaffDirectory.onLogout(admin);
            online.clear();
            StaffData.Seen seen = StaffData.get(server).seen().get(admin.getUUID());
            h.assertTrue(seen != null && seen.atMs() == 5_000_000L && seen.server().equals(Storage.currentServerId()), "leaving records when and where: " + seen);
            h.assertFalse(StaffDirectory.isAway(admin.getUUID()), "and away ends at logout");
            StaffDirectory.applySeen(server, admin.getUUID() + "|" + admin.getGameProfile().getName() + "|4000000|BSP-Wild");
            h.assertTrue(StaffData.get(server).seen().get(admin.getUUID()).atMs() == 5_000_000L, "an older sighting from elsewhere does not replace a newer one");
            StaffDirectory.applySeen(server, admin.getUUID() + "|" + admin.getGameProfile().getName() + "|6000000|BSP-Wild");
            h.assertTrue(StaffData.get(server).seen().get(admin.getUUID()).server().equals("BSP-Wild"), "a newer one does");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            StaffDirectory.useReal();
            Staff.useConfig();
        }
        h.succeed();
    }

    /** Its own batch: another test's group change looks the members up again and would race this one. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "staff-luckperms")
    public void offline_members_of_staff_groups_come_from_luckperms(GameTestHelper h) {
        if (!LuckPermsBridge.isPresent()) {
            BSPServerUtilities.LOGGER.warn("LuckPerms is not loaded in this run; the staff members test did nothing.");
            h.succeed();
            return;
        }
        MinecraftServer server = h.getLevel().getServer();
        String group = "su_test_mods";
        ServerPlayer mod = TestPlayers.mockServerPlayer(h);
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        net.luckperms.api.node.Node node = net.luckperms.api.node.types.InheritanceNode.builder(group).build();
        lp.getGroupManager().createAndLoadGroup(group).join();
        lp.getUserManager().modifyUser(mod.getUUID(), u -> u.data().add(node)).join();
        StaffDirectory.useForTests(System::currentTimeMillis, List::of);
        Staff.useForTests(List.of(group + ":Tester:#19D3B0"), false);
        StaffDirectory.refreshMembers(server);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(StaffDirectory.roster(server).stream().anyMatch(m -> m.id().equals(mod.getUUID())), "the LuckPerms member arrives in the roster"))
                .thenExecute(() -> {
                    StaffDirectory.Member m = StaffDirectory.roster(server).stream().filter(x -> x.id().equals(mod.getUUID())).findFirst().orElseThrow();
                    h.assertTrue(m.state() == StaffDirectory.State.OFFLINE && m.role().label().equals("Tester"), "offline, with the group's label (D63): " + m);
                    lp.getUserManager().modifyUser(mod.getUUID(), u -> u.data().remove(node)).join();
                    StaffDirectory.useReal();
                    Staff.useConfig();
                    StaffDirectory.refreshMembers(server);
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public void the_staff_view_and_tab_packets_round_trip_and_the_tab_is_for_admins(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        StaffViewPacket view = new StaffViewPacket(List.of(new StaffViewPacket.Row("Ferrous", "admin", "Admin", 0xB58CFF, StaffDirectory.State.ONLINE, "BSP-Wild", 0),
                new StaffViewPacket.Row("Wren", "mod", "Moderator", 0x19D3B0, StaffDirectory.State.OFFLINE, "", 7_200_000L)));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        view.encode(buf);
        h.assertTrue(StaffViewPacket.decode(buf).equals(view), "the roster round trips");
        AdminStaffPacket tab = new AdminStaffPacket(List.of(new Staff.Role("admin", "Admin", 0xB58CFF)), false, List.of("admin", "mod", "vip"), view);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        tab.encode(buf);
        h.assertTrue(AdminStaffPacket.decode(buf).equals(tab), "the STAFF tab's data round trips");
        AdminStaffEditPacket edit = new AdminStaffEditPacket(false, List.of(new Staff.Role("mod", "Mods", 0x19D3B0)), true);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        edit.encode(buf);
        h.assertTrue(AdminStaffEditPacket.decode(buf).equals(edit), "an edit round trips");

        PanelSettings s = new PanelSettings(PanelSettings.Mode.STAFF, PanelSettings.DEFAULT_BACKGROUND, List.of(), PanelSettings.Image.NONE, "a", 1)
                .withViews(new PanelSettings.StaffView(false, List.of("Admin", "admin", "operator")), new PanelSettings.LogView(false, List.of("cmd"), 7)).sanitised();
        h.assertTrue(s.staff().roles().equals(List.of("admin", "operator")) && !s.staff().offline() && s.log().lines() == 8 && !s.log().allServers(), "panel views keep to their choices: " + s.staff() + " " + s.log());
        h.assertTrue(PanelSettings.load(s.save()).equals(s), "and round trip");

        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        StaffGroups before = StaffData.get(server).groups();
        try {
            h.assertFalse(AdminPanel.applyStaff(player, edit), "a player who may not edit changes nothing");
            h.assertTrue(StaffData.get(server).groups().equals(before), "the groups are unchanged");
            h.assertTrue(AdminPanel.applyStaff(admin, edit) && StaffData.get(server).groups().roles().equals(edit.roles()) && StaffData.get(server).groups().operators(), "an admin's edit is the network's groups");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            StaffData.get(server).setGroups(before);
        }
        h.succeed();
    }
}
