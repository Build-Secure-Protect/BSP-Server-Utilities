package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.network.AdminRulesPacket;
import com.mrgregles.bsp_server_utilities.network.AdminRulesPacket.Action;
import com.mrgregles.bsp_server_utilities.network.AdminViewPacket;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The admin panel's RULES tab, server side (owner decisions D3, D34 to D39): every action applied through the rules,
 * the limits and the refusals, and the two packets' byte round trip. Puts the rules back as they were.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class AdminPanelGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static AdminRulesPacket p(Action a, int index, String x, String y, String z) {
        return new AdminRulesPacket(a, index, x, y, z);
    }

    private static List<String> titles(MinecraftServer server) {
        return RulesService.rules(server).rules().stream().map(RuleSet.Rule::title).toList();
    }

    @GameTest(template = EMPTY)
    public void the_rules_tab_edits_moves_deletes_and_asks_again_for_admins_only(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        RuleSet before = RulesService.rules(server);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            RulesService.edit(server, s -> s.withRules(List.of()));
            h.assertTrue(AdminPanel.apply(player, p(Action.ADD, -1, "Sneaky", "", "")).equals(AdminPanel.NOTE_REFUSED) && titles(server).isEmpty(), "a player who may not edit changes nothing");

            h.assertTrue(AdminPanel.apply(admin, p(Action.ADD, -1, "Be kind", "No spam.", "")).equals(AdminPanel.NOTE_ADDED), "ADD");
            AdminPanel.apply(admin, p(Action.ADD, -1, "No cheats", "", ""));
            AdminPanel.apply(admin, p(Action.ADD, -1, "Listen to staff", "", ""));
            h.assertTrue(titles(server).equals(List.of("Be kind", "No cheats", "Listen to staff")), "three rules in order: " + titles(server));

            h.assertTrue(AdminPanel.apply(admin, p(Action.SET, 1, "No cheating", "No x-ray.", "")).equals(AdminPanel.NOTE_SAVED), "SET");
            h.assertTrue(RulesService.rules(server).rules().get(1).equals(new RuleSet.Rule("No cheating", "No x-ray.")), "the rule is changed: " + RulesService.rules(server).rules().get(1));
            h.assertTrue(AdminPanel.apply(admin, p(Action.SET, 1, "   ", "gone?", "")).equals(AdminPanel.NOTE_NO_TITLE) && titles(server).size() == 3, "a blank title is refused and the rule kept");
            h.assertTrue(AdminPanel.apply(admin, p(Action.SET, 7, "x", "", "")).equals(AdminPanel.NOTE_REFUSED), "SET on a rule that is not there is refused");

            AdminPanel.apply(admin, p(Action.MOVE, 2, "-1", "", ""));
            h.assertTrue(titles(server).equals(List.of("Be kind", "Listen to staff", "No cheating")), "MOVE up: " + titles(server));
            h.assertTrue(AdminPanel.apply(admin, p(Action.MOVE, 0, "-1", "", "")).equals(AdminPanel.NOTE_REFUSED), "the first rule cannot move up");
            AdminPanel.apply(admin, p(Action.MOVE, 0, "1", "", ""));
            h.assertTrue(titles(server).equals(List.of("Listen to staff", "Be kind", "No cheating")), "MOVE down: " + titles(server));

            h.assertTrue(AdminPanel.apply(admin, p(Action.DELETE, 0, "", "", "")).equals(AdminPanel.NOTE_DELETED) && titles(server).equals(List.of("Be kind", "No cheating")), "DELETE: " + titles(server));

            AdminPanel.apply(admin, p(Action.TEXTS, -1, "House rules", "Read these.", "See you soon."));
            RuleSet set = RulesService.rules(server);
            h.assertTrue(set.heading().equals("House rules") && set.intro().equals("Read these.") && set.leaveMessage().equals("See you soon."), "TEXTS: " + set);

            for (int i = set.rules().size(); i < RuleSet.MAX_RULES; i++) {
                AdminPanel.apply(admin, p(Action.ADD, -1, "Rule " + i, "", ""));
            }
            h.assertTrue(AdminPanel.apply(admin, p(Action.ADD, -1, "One too many", "", "")).equals(AdminPanel.NOTE_FULL) && titles(server).size() == RuleSet.MAX_RULES, "the 21st rule is refused");

            int version = RulesService.rules(server).version();
            h.assertTrue(AdminPanel.apply(admin, AdminRulesPacket.of(Action.ASK_AGAIN)).equals(AdminPanel.NOTE_ASKED) && RulesService.rules(server).version() == version + 1, "ASK_AGAIN raises the version");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            RulesService.forget(admin.getUUID());
            RulesService.edit(server, s -> s.withRules(before.rules()).withTexts(before.heading(), before.intro(), before.leaveMessage()));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void admin_packets_round_trip(GameTestHelper h) {
        RuleSet set = new RuleSet("Rules", "Intro", "Bye", List.of(new RuleSet.Rule("A", "a"), new RuleSet.Rule("B", "")), 3, 99L);
        AdminViewPacket view = AdminViewPacket.of(set, true, 12, AdminPanel.NOTE_SAVED);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        view.encode(buf);
        AdminViewPacket back = AdminViewPacket.decode(buf);
        h.assertTrue(back.equals(view) && back.ruleSet().equals(set), "the view comes back unchanged: " + back);
        AdminRulesPacket action = new AdminRulesPacket(Action.SET, 1, "Title", "Text", "");
        buf = new FriendlyByteBuf(Unpooled.buffer());
        action.encode(buf);
        h.assertTrue(AdminRulesPacket.decode(buf).equals(action), "an action comes back unchanged");
        AdminRulesPacket none = AdminRulesPacket.of(Action.REFRESH);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        none.encode(buf);
        h.assertTrue(AdminRulesPacket.decode(buf).equals(none), "and one without a rule (index -1)");
        h.succeed();
    }
}
