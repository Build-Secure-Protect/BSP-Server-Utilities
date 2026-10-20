package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.RulesAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.RulesOpenPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.mrgregles.bsp_server_utilities.rules.Agreement;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import com.mrgregles.bsp_server_utilities.rules.RulesData;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The rules popup's server rules (owner decisions D7 to D13, D31 to D37, R3): the limits, the gate and the hold, the
 * answer checked on the server, LEAVE SERVER, asking everyone again, an edit resending or releasing, what a held
 * player cannot do, and the shared storage. Each test puts the rules back as they were.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class RulesGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final List<RuleSet.Rule> THREE = List.of(new RuleSet.Rule("Be kind", "No hate or spam."), new RuleSet.Rule("No cheats", ""), new RuleSet.Rule("Listen to staff", "Stop when asked."));

    private static RulesAnswerPacket agree(RuleSet set, List<Integer> ticked) {
        return new RulesAnswerPacket(RulesAnswerPacket.Action.AGREE, set.version(), set.revision(), ticked);
    }

    private static List<Integer> all(RuleSet set) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < set.rules().size(); i++) {
            out.add(i);
        }
        return out;
    }

    @GameTest(template = EMPTY)
    public void rule_set_keeps_to_its_limits_and_round_trips(GameTestHelper h) {
        List<RuleSet.Rule> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(new RuleSet.Rule("Rule " + i, "text " + i));
        }
        many.add(0, new RuleSet.Rule("   ", "a rule with no title"));
        many.add(1, new RuleSet.Rule("x".repeat(60) + "\nsecond line §cred", "t".repeat(450)));
        RuleSet s = new RuleSet("", "intro\nnext", "bye", many, 0, 5).sanitised();
        h.assertTrue(s.rules().size() == RuleSet.MAX_RULES, "at most 20 rules, the untitled one dropped: " + s.rules().size());
        h.assertTrue(s.rules().get(0).title().length() == RuleSet.MAX_TITLE && !s.rules().get(0).title().contains("\n"), "a title keeps to 48 characters on one line: " + s.rules().get(0).title());
        h.assertTrue(s.rules().get(0).text().length() == RuleSet.MAX_TEXT, "a text keeps to 400: " + s.rules().get(0).text().length());
        RuleSet.Rule colour = new RuleSet("h", "", "", List.of(new RuleSet.Rule("a §cred", "")), 1, 0).sanitised().rules().get(0);
        h.assertTrue(colour.title().equals("a cred"), "formatting codes are taken out: " + colour.title());
        h.assertTrue(s.heading().equals(RuleSet.DEFAULT_HEADING) && s.intro().equals("intro\nnext") && s.version() == 1, "an empty heading is the default, the intro keeps its line break, the version is at least 1: " + s.heading() + " / " + s.version());
        h.assertTrue(RuleSet.load(s.save()).equals(s), "saved and loaded unchanged");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_new_player_is_held_after_the_gate_and_freed_only_by_agreeing_to_every_rule(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        RuleSet before = RulesService.rules(server);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        try {
            RuleSet set = RulesService.edit(server, s -> s.withRules(THREE));
            h.assertTrue(set.rules().size() == 3 && set.revision() > before.revision() && set.version() == before.version(), "an edit keeps the version and raises the revision: " + set);
            RulesService.login(player);
            h.assertFalse(RulesService.isHeld(player.getUUID()), "nobody is held at login itself (the popup waits until they settle in)");
            RulesService.settled(player);
            h.assertTrue(RulesService.isHeld(player.getUUID()) && RulesService.needsAgreement(server, player.getUUID()), "a player who never agreed is held once settled");

            h.assertTrue(RulesService.answer(player, agree(set, List.of(0, 2))) == RulesService.Outcome.NOT_ALL_TICKED, "AGREE with a box left unticked is refused");
            h.assertTrue(RulesService.answer(player, new RulesAnswerPacket(RulesAnswerPacket.Action.AGREE, set.version(), set.revision() - 1, all(set))) == RulesService.Outcome.CHANGED, "AGREE to an older copy of the rules is refused");
            h.assertTrue(RulesService.isHeld(player.getUUID()), "still held after both");
            h.assertTrue(RulesService.answer(player, agree(set, all(set))) == RulesService.Outcome.AGREED, "AGREE with every rule ticked is taken");
            h.assertFalse(RulesService.isHeld(player.getUUID()), "and the hold ends");
            Agreement a = RulesService.agreement(server, player.getUUID());
            h.assertTrue(a != null && a.version() == set.version() && a.name().equals(player.getGameProfile().getName()), "the agreement is recorded with the version and name: " + a);
            h.assertTrue(RulesService.answer(player, agree(set, all(set))) == RulesService.Outcome.NOT_HELD, "an answer from a player who is not held does nothing");

            RulesService.forget(player.getUUID());
            RulesService.login(player);
            RulesService.settled(player);
            h.assertFalse(RulesService.isHeld(player.getUUID()), "a player who agreed is not asked again at the next login (D7)");
        } finally {
            RulesService.forget(player.getUUID());
            RulesService.edit(server, s -> s.withRules(before.rules()));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void ask_everyone_again_holds_players_who_agreed_and_an_edit_resends_or_releases(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        RuleSet before = RulesService.rules(server);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        try {
            RuleSet set = RulesService.edit(server, s -> s.withRules(THREE));
            RulesService.check(player);
            RulesService.answer(player, agree(set, all(set)));
            h.assertFalse(RulesService.isHeld(player.getUUID()), "agreed to version " + set.version());
            RuleSet again = RulesService.askEveryoneAgain(server);
            h.assertTrue(again.version() == set.version() + 1, "ask everyone again raises the version: " + again.version());
            h.assertTrue(RulesService.needsAgreement(server, player.getUUID()), "a player who agreed to the old version must agree again");
            RulesService.check(player); // a mock player is not in the player list, so the recheck of everyone online is run for it here
            h.assertTrue(RulesService.isHeld(player.getUUID()), "and is held at once (R3)");
            h.assertTrue(RulesService.answer(player, agree(set, all(set))) == RulesService.Outcome.CHANGED, "an answer to the old version is refused");

            RuleSet edited = RulesService.edit(server, s -> s.withRules(List.of(new RuleSet.Rule("Only rule", ""))));
            h.assertTrue(RulesService.isHeld(player.getUUID()) && edited.version() == again.version(), "an edit keeps them held (the popup is sent again) and keeps the version");
            RulesService.edit(server, s -> s.withRules(List.of()));
            h.assertFalse(RulesService.isHeld(player.getUUID()), "with no rules left nobody is held");
            h.assertFalse(RulesService.needsAgreement(server, player.getUUID()), "and nobody needs to agree");

            RulesService.edit(server, s -> s.withRules(THREE));
            RulesService.check(player);
            RuleSet now = RulesService.rules(server);
            RulesService.answer(player, agree(now, all(now)));
            RulesService.reset(server, player.getUUID());
            h.assertTrue(RulesService.agreement(server, player.getUUID()) == null && RulesService.needsAgreement(server, player.getUUID()), "a reset forgets one player's agreement");
        } finally {
            RulesService.forget(player.getUUID());
            RulesService.edit(server, s -> s.withRules(before.rules()));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void leave_server_disconnects_with_the_admins_message(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        RuleSet before = RulesService.rules(server);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        AtomicReference<Component> said = new AtomicReference<>();
        RulesService.useDisconnectorForTests((p, message) -> said.set(message));
        try {
            RulesService.edit(server, s -> s.withRules(THREE).withTexts(s.heading(), s.intro(), "Come back when you are ready."));
            RulesService.check(player);
            RuleSet set = RulesService.rules(server);
            h.assertTrue(RulesService.answer(player, new RulesAnswerPacket(RulesAnswerPacket.Action.LEAVE, set.version(), set.revision(), List.of())) == RulesService.Outcome.LEFT, "LEAVE SERVER is taken");
            h.assertTrue(said.get() != null && said.get().getString().equals("Come back when you are ready."), "with the admin's message: " + said.get());
            h.assertFalse(RulesService.isHeld(player.getUUID()), "the hold ends with the connection");
            h.assertTrue(RulesService.needsAgreement(server, player.getUUID()), "and they will be asked again next time (D10)");
        } finally {
            RulesService.useRealDisconnector();
            RulesService.forget(player.getUUID());
            RulesService.edit(server, s -> s.withRules(before.rules()).withTexts(before.heading(), before.intro(), before.leaveMessage()));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_held_player_cannot_act_chat_or_be_hurt_and_a_tossed_item_comes_back(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        RuleSet before = RulesService.rules(server);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Vec3 start = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        player.moveTo(start.x, start.y, start.z, 0, 0);
        try {
            RulesService.edit(server, s -> s.withRules(THREE));
            RulesService.check(player);
            h.assertTrue(RulesService.isHeld(player.getUUID()), "held");

            player.getInventory().selected = 0;
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
            player.drop(true);
            h.assertTrue(player.getInventory().countItem(Items.DIAMOND) == 5, "a tossed stack comes back into the inventory: " + player.getInventory().countItem(Items.DIAMOND));
            List<ItemEntity> dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(start, start).inflate(4));
            h.assertTrue(dropped.isEmpty(), "and nothing is dropped: " + dropped);

            h.assertFalse(ForgeHooks.onPlayerAttack(player, player.damageSources().generic(), 5f), "nothing can hurt them");
            ServerChatEvent chat = new ServerChatEvent(player, "hello", Component.literal("hello"));
            MinecraftForge.EVENT_BUS.post(chat);
            h.assertTrue(chat.isCanceled(), "chat is blocked");
            CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
            CommandSourceStack source = player.createCommandSourceStack();
            CommandEvent seed = new CommandEvent(d.parse("seed", source));
            MinecraftForge.EVENT_BUS.post(seed);
            CommandEvent rules = new CommandEvent(d.parse("rules", source));
            MinecraftForge.EVENT_BUS.post(rules);
            h.assertTrue(seed.isCanceled() && !rules.isCanceled(), "commands are blocked except /rules: " + seed.isCanceled() + " " + rules.isCanceled());

            player.setPos(start.x + 1, start.y, start.z);
            RulesService.tick(server);
            h.assertTrue(player.position().distanceTo(start) < 0.01, "a step is undone: " + player.position());
            player.setPos(start.x + 8, start.y, start.z);
            RulesService.tick(server);
            RulesService.tick(server);
            h.assertTrue(player.position().distanceTo(start) > 7, "a teleport-sized move (a landing) moves the anchor instead: " + player.position());

            RuleSet set = RulesService.rules(server);
            RulesService.answer(player, agree(set, all(set)));
            ServerChatEvent after = new ServerChatEvent(player, "hi", Component.literal("hi"));
            MinecraftForge.EVENT_BUS.post(after);
            h.assertFalse(after.isCanceled(), "after agreeing they can chat");
        } finally {
            RulesService.forget(player.getUUID());
            RulesService.edit(server, s -> s.withRules(before.rules()));
            player.getInventory().clearContent();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void rules_and_agreements_go_through_the_shared_storage(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        RuleSet before = RulesService.rules(server);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        try {
            RulesService.onServerStarted(server);
            alpha.drain();
            h.assertTrue(db.loadRules() != null, "with no shared rules yet, ours are written");
            RuleSet set = RulesService.edit(server, s -> s.withRules(THREE));
            alpha.drain();
            h.assertTrue(db.loadRules().equals(set), "an edit is written through: " + db.loadRules());
            h.assertTrue(db.fetchNoticesAfter(0, 10).stream().anyMatch(n -> n.kind().equals(RulesService.KIND_RULES)), "and announced");

            db.saveAgreement(player.getUUID(), new Agreement(set.version(), 1L, "beta", "someone")); // agreed on another server
            RulesData.get(server).putAgreementForTests(player.getUUID(), null);
            RulesService.login(player);
            RulesService.settled(player);
            h.assertFalse(RulesService.isHeld(player.getUUID()), "while the database has not answered, nobody is held");
            alpha.drain();
            h.assertFalse(RulesService.isHeld(player.getUUID()), "the agreement made on another server counts here");
            RulesService.forget(player.getUUID());

            db.saveAgreement(player.getUUID(), null);
            RulesService.login(player);
            RulesService.settled(player);
            alpha.drain();
            h.assertTrue(RulesService.isHeld(player.getUUID()), "with no agreement anywhere they are held");
            RulesService.answer(player, agree(set, all(set)));
            alpha.drain();
            Agreement saved = db.loadAgreement(player.getUUID());
            h.assertTrue(saved != null && saved.version() == set.version() && saved.server().equals(Storage.currentServerId()), "the agreement is written for the network: " + saved);
            int[] counted = {-1};
            RulesService.countAgreed(server, n -> counted[0] = n);
            alpha.drain();
            h.assertTrue(counted[0] == 1, "the count comes from the database: " + counted[0]);

            RuleSet newer = set.withRules(List.of(new RuleSet.Rule("From beta", ""))).withRevision(set.revision() + 1000);
            db.saveRules(newer);
            RulesService.reloadShared(server, false);
            alpha.drain();
            h.assertTrue(RulesService.rules(server).equals(newer), "a newer shared copy replaces ours on a notice");
            RulesService.reloadShared(server, false);
            db.saveRules(set);
            alpha.drain();
            h.assertTrue(RulesService.rules(server).equals(newer), "an older copy never does");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            RulesService.forget(player.getUUID());
            RulesService.edit(server, s -> s.withRules(before.rules()));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void rules_packets_round_trip(GameTestHelper h) {
        RuleSet set = new RuleSet("Server rules", "Intro\nline two", "Bye", THREE, 4, 123456789L);
        RulesOpenPacket open = RulesOpenPacket.of(set, true, 0);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        open.encode(buf);
        RulesOpenPacket back = RulesOpenPacket.decode(buf);
        h.assertTrue(back.equals(open), "the popup packet comes back unchanged: " + back);
        RulesOpenPacket read = RulesOpenPacket.of(set, false, 1_700_000_000_000L);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        read.encode(buf);
        h.assertTrue(RulesOpenPacket.decode(buf).equals(read), "and the read-only one with its date");
        RulesAnswerPacket answer = new RulesAnswerPacket(RulesAnswerPacket.Action.AGREE, 4, 123456789L, List.of(0, 1, 2));
        buf = new FriendlyByteBuf(Unpooled.buffer());
        answer.encode(buf);
        h.assertTrue(RulesAnswerPacket.decode(buf).equals(answer), "the answer comes back unchanged");
        h.succeed();
    }
}
