package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who may edit (owner decision D3) and who counts as staff (D4): the staff group entries, operators, and LuckPerms
 * groups through the bridge (the last test needs the LuckPerms dev jar in {@code libs/} and skips itself without it).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class AccessGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    @GameTest(template = EMPTY)
    public void staff_group_entries_read_label_and_colour(GameTestHelper h) {
        List<Staff.Role> roles = Staff.parse(List.of("Admin:Admin:#B58CFF", "mod:Moderator", "helper", "bad name!", "mod:Again:#000000", "vip:VIP:pink"));
        h.assertTrue(roles.size() == 4, "four usable entries (bad name skipped, duplicate dropped): " + roles);
        h.assertTrue(roles.get(0).equals(new Staff.Role("admin", "Admin", 0xB58CFF)), "group lower-cased, label and colour read: " + roles.get(0));
        h.assertTrue(roles.get(1).equals(new Staff.Role("mod", "Moderator", Staff.DEFAULT_COLOUR)), "no colour means violet: " + roles.get(1));
        h.assertTrue(roles.get(2).equals(new Staff.Role("helper", "Helper", Staff.DEFAULT_COLOUR)), "no label means the group's name: " + roles.get(2));
        h.assertTrue(roles.get(3).colour() == Staff.DEFAULT_COLOUR, "a bad colour falls back to violet: " + roles.get(3));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void operators_may_edit_and_count_as_staff_when_the_switch_is_on(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        PlayerList list = h.getLevel().getServer().getPlayerList();
        try {
            Staff.useForTests(List.of(), true);
            h.assertFalse(Perms.canEdit(player), "a plain player may not edit");
            h.assertFalse(Staff.isStaff(player), "a plain player is not staff");
            // PlayerList.op takes the server's operator level, which is 0 on the game test server: add a level 4 entry directly.
            list.getOps().add(new ServerOpListEntry(player.getGameProfile(), 4, false));
            h.assertTrue(Perms.canEdit(player), "an operator may edit");
            h.assertTrue(Staff.roleOf(player).equals(java.util.Optional.of(Staff.OPERATOR)), "an operator is staff, labelled Operator: " + Staff.roleOf(player));
            Staff.useForTests(List.of(), false);
            h.assertFalse(Staff.isStaff(player), "with staff.operators off an operator in no staff group is not staff");
        } finally {
            list.getOps().remove(player.getGameProfile());
            Staff.useConfig();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public void luckperms_groups_make_staff_and_their_members_are_found(GameTestHelper h) {
        if (!LuckPermsBridge.isPresent()) {
            BSPServerUtilities.LOGGER.warn("LuckPerms is not loaded in this run; the LuckPerms staff test did nothing.");
            h.succeed();
            return;
        }
        h.assertTrue(LuckPermsBridge.route() == LuckPermsBridge.Route.FORGE, "dev runs reach the Forge edition: " + LuckPermsBridge.route());
        String group = "su_test_staff";
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        UUID id = player.getUUID();
        net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
        net.luckperms.api.node.Node node = net.luckperms.api.node.types.InheritanceNode.builder(group).build();
        try {
            lp.getGroupManager().createAndLoadGroup(group).join();
            lp.getUserManager().modifyUser(id, u -> u.data().add(node)).join();
            Staff.useForTests(List.of("su_test_staff:Tester:#19D3B0"), false);
            h.assertTrue(LuckPermsBridge.groupsOf(id).contains(group), "the bridge sees the group: " + LuckPermsBridge.groupsOf(id));
            h.assertTrue(Staff.roleOf(player).equals(java.util.Optional.of(new Staff.Role(group, "Tester", 0x19D3B0))), "the group makes them staff with its label and colour: " + Staff.roleOf(player));
            h.assertTrue(LuckPermsBridge.groupNames().contains(group), "the group is listed: " + LuckPermsBridge.groupNames());
            Map<UUID, String> members = LuckPermsBridge.membersOf(group).join();
            h.assertTrue(members.containsKey(id), "the member is found in LuckPerms's storage: " + members);
            h.assertTrue(LuckPermsBridge.membersOf("su_test_nobody").join().isEmpty(), "an empty group has no members");
            lp.getUserManager().modifyUser(id, u -> u.data().remove(node)).join();
            h.assertFalse(Staff.isStaff(player), "out of the group, not staff");
            h.assertFalse(LuckPermsBridge.membersOf(group).join().containsKey(id), "and no longer a member");
        } finally {
            Staff.useConfig();
        }
        h.succeed();
    }
}
