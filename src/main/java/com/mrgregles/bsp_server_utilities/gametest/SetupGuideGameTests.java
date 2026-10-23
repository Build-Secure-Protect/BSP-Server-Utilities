package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.access.StaffSync;
import com.mrgregles.bsp_server_utilities.afk.AfkData;
import com.mrgregles.bsp_server_utilities.afk.AfkService;
import com.mrgregles.bsp_server_utilities.afk.AfkSettings;
import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.chunks.ChunkData;
import com.mrgregles.bsp_server_utilities.chunks.ChunkService;
import com.mrgregles.bsp_server_utilities.chunks.ChunkSettings;
import com.mrgregles.bsp_server_utilities.despawn.DespawnService;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.SetupCardPacket;
import com.mrgregles.bsp_server_utilities.network.SetupEditPacket;
import com.mrgregles.bsp_server_utilities.network.SetupFlagsPacket;
import com.mrgregles.bsp_server_utilities.network.SetupViewPacket;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupAdmin;
import com.mrgregles.bsp_server_utilities.setup.SetupData;
import com.mrgregles.bsp_server_utilities.setup.SetupService;
import com.mrgregles.bsp_server_utilities.setup.SetupState;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The setup guide (owner decisions D143 to D161). SU-22: the state and its round trip, the newest revision winning
 * between servers; NEXT, SETUP LATER, closing part way, a change in a tab counting; every off switch stopping its
 * feature (rules, the action log, despawn times, the client check, staff online, display panels, AFK, chunk unloading);
 * who is shown the card and when; the guide's actions for admins only. SU-23: the card's and the guide's actions as the
 * client sends them. The card and the guide's screens themselves need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class SetupGuideGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    /** Everything the setup tests change, put back afterwards. */
    private record Saved(SetupState state, SetupState.Status welcome, AfkSettings afk, ChunkSettings chunks) {
        static Saved of(MinecraftServer server) {
            return new Saved(SetupService.state(server), SetupData.get(server).welcome(), AfkService.settings(server), ChunkService.settings(server));
        }

        void restore(MinecraftServer server) {
            SetupService.restoreForTests(server, state, welcome);
            AfkData.get(server).setSettings(afk);
            ChunkData.get(server).setSettings(chunks);
        }
    }

    private static void fresh(MinecraftServer server) {
        SetupService.restoreForTests(server, SetupState.EMPTY, SetupState.Status.NOT_YET);
        AfkSettings a = AfkService.settings(server);
        AfkData.get(server).setSettings(new AfkSettings(true, a.afkMinutes(), a.kick(), a.kickMinutes(), a.message(), a.revision()));
        ChunkSettings c = ChunkService.settings(server);
        ChunkData.get(server).setSettings(new ChunkSettings(true, c.minutes(), c.exemptMods(), c.alwaysLoaded(), c.revision()));
    }

    @GameTest(template = EMPTY)
    public void the_state_round_trips_and_the_newest_copy_wins(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            fresh(server);
            SetupState s = SetupState.EMPTY.with(Feature.RULES, SetupState.Status.DONE).with(Feature.AFK, SetupState.Status.LATER).withOff(Feature.LOG, true)
                    .withOff(Feature.AFK, true).withIgnored(UUID.randomUUID()).with(Feature.WELCOME, SetupState.Status.DONE).withRevision(7);
            h.assertTrue(SetupState.decode(s.encode()).equals(s), "encoded, it round trips");
            h.assertTrue(!s.off().contains(Feature.AFK) && !s.status().containsKey(Feature.WELCOME), "AFK keeps its own switch; Welcome is this server's, not the network's");
            SetupService.next(server, Feature.STAFF);
            alpha.drain();
            SetupState now = SetupService.state(server);
            h.assertTrue(db.loadSetupState().equals(now) && db.fetchNoticesAfter(0, 20).stream().anyMatch(n -> n.kind().equals(SetupService.KIND_STATE)), "saved and announced for the network");
            SetupService.apply(server, SetupState.EMPTY.withRevision(now.revision() - 1));
            h.assertTrue(SetupService.state(server).equals(now), "an older copy changes nothing");
            SetupState newer = SetupState.EMPTY.withOff(Feature.PANELS, true).withRevision(now.revision() + 1);
            SetupService.apply(server, newer);
            h.assertTrue(SetupService.state(server).equals(newer) && Switches.off(Feature.PANELS), "a newer copy is taken and its switches put into force");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void pages_are_set_up_left_for_later_or_left_when_the_guide_closes(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        try {
            fresh(server);
            h.assertTrue(SetupService.left(server).size() == Feature.values().length, "every page starts not set up (D153)");
            SetupService.next(server, Feature.WELCOME);
            SetupService.next(server, Feature.STAFF);
            SetupService.later(server, Feature.RULES);
            h.assertTrue(SetupService.status(server, Feature.WELCOME) == SetupState.Status.DONE && SetupService.status(server, Feature.STAFF) == SetupState.Status.DONE
                    && SetupService.status(server, Feature.RULES) == SetupState.Status.LATER, "NEXT sets up, SETUP LATER leaves for later");
            SetupService.closedAt(server, Feature.AFK);
            h.assertTrue(SetupService.status(server, Feature.LOG) == SetupState.Status.NOT_YET && SetupService.status(server, Feature.AFK) == SetupState.Status.LATER
                    && SetupService.status(server, Feature.PANELS) == SetupState.Status.LATER, "closing on AFK leaves it and the pages after it for later (D158), not those before");
            AfkSettings a = AfkService.settings(server);
            AfkService.setSettings(server, new AfkSettings(true, a.afkMinutes() == 5 ? 6 : 5, a.kick(), a.kickMinutes(), a.message(), a.revision()));
            h.assertTrue(SetupService.status(server, Feature.AFK) == SetupState.Status.DONE, "a change in the AFK tab counts as set up (D152)");
            SetupService.turnOff(server, Feature.CHUNKS);
            SetupService.next(server, Feature.CHUNKS);
            h.assertTrue(SetupService.status(server, Feature.CHUNKS) == SetupState.Status.OFF && !SetupService.left(server).contains(Feature.CHUNKS), "an off feature stays off on NEXT and is not left");
            SetupService.turnOn(server, Feature.CHUNKS);
            h.assertTrue(ChunkService.settings(server).enabled() && SetupService.status(server, Feature.CHUNKS) == SetupState.Status.DONE, "TURN ON works again and counts as set up");
        } finally {
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void every_off_switch_stops_its_feature(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        try {
            fresh(server);
            boolean rulesConfig = ServerUtilsConfig.getOr(ServerUtilsConfig.RULES_ENABLED, true);
            SetupService.turnOff(server, Feature.RULES);
            h.assertTrue(!RulesService.enabled() && !RulesService.needsAgreement(server, UUID.randomUUID()), "rules off: nobody asked or held");
            SetupService.turnOn(server, Feature.RULES);
            h.assertTrue(RulesService.enabled() == rulesConfig, "rules on again");

            int before = ActionLog.recent(server, 5000, true, List.of()).size();
            SetupService.turnOff(server, Feature.LOG);
            ActionLog.record(server, ActionLog.entryForServer("Console", LogEntry.CMD, "/say off " + UUID.randomUUID()));
            h.assertTrue(ActionLog.recent(server, 5000, true, List.of()).size() == before, "the action log off: nothing recorded");
            SetupService.turnOn(server, Feature.LOG);

            SetupService.turnOff(server, Feature.DESPAWN);
            BlockPos p = h.absolutePos(new BlockPos(1, 2, 1));
            ItemEntity item = new ItemEntity(h.getLevel(), p.getX() + 0.5, p.getY(), p.getZ() + 0.5, new ItemStack(Items.COBBLESTONE));
            h.assertTrue(DespawnService.onJoin(item, false) && !item.getPersistentData().contains(DespawnService.STAMP) && item.lifespan == DespawnService.VANILLA_TICKS,
                    "despawn times off: an item is left to vanilla's five minutes");
            h.assertTrue(DespawnService.check(server) == 0, "and the check removes nothing");
            SetupService.turnOn(server, Feature.DESPAWN);

            ServerPlayer p1 = TestPlayers.mockServerPlayer(h);
            SetupService.turnOff(server, Feature.CHECK);
            ClientCheckService.onReport(p1, ClientCheckGameTests.clean("s1"));
            h.assertTrue(ClientCheckService.report(p1.getUUID()) == null, "the client check off: reports are not taken");
            SetupService.turnOn(server, Feature.CHECK);

            SetupService.turnOff(server, Feature.STAFF);
            h.assertTrue(StaffSync.shown(server).isEmpty(), "staff online off: players' panels and boards get nobody");
            SetupService.turnOn(server, Feature.STAFF);

            SetupService.turnOff(server, Feature.PANELS);
            h.assertTrue(Switches.off(Feature.PANELS), "display panels off: dark and not placeable (checked on both sides)");
            SetupService.turnOn(server, Feature.PANELS);

            SetupService.turnOff(server, Feature.AFK);
            h.assertTrue(!AfkService.settings(server).enabled() && SetupService.status(server, Feature.AFK) == SetupState.Status.OFF, "AFK off through its own settings");
            SetupService.turnOn(server, Feature.AFK);
            h.assertTrue(AfkService.settings(server).enabled(), "and on again");
            h.assertTrue(Switches.all().isEmpty(), "every switch back on");
        } finally {
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_card_goes_to_admins_with_something_left_who_have_not_ignored_it(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        List<SetupCardPacket> cards = new ArrayList<>();
        SetupService.useForTests(System::currentTimeMillis, (pl, c) -> cards.add(c));
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            fresh(server);
            SetupService.onLogin(admin);
            SetupService.onLogin(player);
            h.assertTrue(SetupService.waiting(admin.getUUID()) == SetupService.CARD_DELAY_TICKS && SetupService.waiting(player.getUUID()) == -1,
                    "an admin waits a few seconds for the card (D144); a player never does");
            h.assertTrue(!SetupService.offerCard(player) && SetupService.offerCard(admin) && cards.get(0).first() && cards.get(0).left().size() == Feature.values().length,
                    "the first card lists every page: " + cards);
            for (Feature f : Feature.values()) {
                if (f != Feature.DESPAWN) {
                    SetupService.next(server, f);
                }
            }
            SetupService.later(server, Feature.DESPAWN);
            h.assertTrue(SetupService.offerCard(admin) && !cards.get(1).first() && cards.get(1).left().equals(List.of(Feature.DESPAWN)), "the reminder lists what was left for later (D147)");
            SetupService.next(server, Feature.DESPAWN);
            h.assertTrue(!SetupService.offerCard(admin), "nothing left, no card");
            SetupService.later(server, Feature.RULES);
            SetupService.ignore(server, admin.getUUID());
            h.assertTrue(!SetupService.offerCard(admin), "an admin who ignored the guide is not asked again (D145)");
            h.assertTrue(!SetupAdmin.apply(player, new SetupEditPacket(SetupEditPacket.Action.OFF, Feature.LOG, "")) && !Switches.off(Feature.LOG), "a player who may not edit changes nothing");
            h.assertTrue(SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.NEXT, Feature.RULES, "")) && SetupService.status(server, Feature.RULES) == SetupState.Status.DONE,
                    "an admin's NEXT from the guide");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            SetupService.onLogout(admin);
            SetupService.useReal();
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_guide_packets_round_trip_and_the_name_must_be_valid(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SetupViewPacket v = SetupService.view(server, true, Feature.RULES);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        v.encode(buf);
        h.assertTrue(SetupViewPacket.decode(buf).equals(v) && v.statuses().size() == Feature.values().length, "the guide's data");
        SetupCardPacket c = new SetupCardPacket(true, false, List.of(Feature.AFK, Feature.CHECK));
        buf = new FriendlyByteBuf(Unpooled.buffer());
        c.encode(buf);
        h.assertTrue(SetupCardPacket.decode(buf).equals(c), "the card");
        SetupEditPacket e = new SetupEditPacket(SetupEditPacket.Action.NAME, Feature.WELCOME, "BSP-Spawn");
        buf = new FriendlyByteBuf(Unpooled.buffer());
        e.encode(buf);
        h.assertTrue(SetupEditPacket.decode(buf).equals(e), "an action");
        SetupFlagsPacket f = new SetupFlagsPacket(EnumSet.of(Feature.PANELS, Feature.LOG));
        buf = new FriendlyByteBuf(Unpooled.buffer());
        f.encode(buf);
        h.assertTrue(SetupFlagsPacket.decode(buf).off().equals(Set.of(Feature.PANELS, Feature.LOG)), "the switches");
        h.assertTrue(!SetupService.setServerName("bad name!") && !SetupService.setServerName("") && !SetupService.setServerName("x".repeat(40)), "a name with spaces, empty or too long is refused");
        String before = ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_SERVER_ID, "");
        try {
            h.assertTrue(SetupService.setServerName("BSP-Test_1") && ServerUtilsConfig.STORAGE_SERVER_ID.get().equals("BSP-Test_1"), "a valid name is written to the config file");
        } finally {
            ServerUtilsConfig.STORAGE_SERVER_ID.set(before);
            ServerUtilsConfig.SPEC.save();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ SU-23

    @GameTest(template = EMPTY)
    public void the_card_and_the_guide_act_as_the_client_asks(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            fresh(server);
            SetupService.next(server, Feature.WELCOME);
            SetupService.next(server, Feature.STAFF);
            h.assertTrue(SetupService.view(server, true, SetupService.left(server).get(0)).startAt() == Feature.RULES, "the guide opens at the first page not finished (D147)");
            h.assertTrue(SetupAdmin.apply(admin, SetupEditPacket.of(SetupEditPacket.Action.OPEN)), "SETUP NOW opens the guide");
            SetupAdmin.apply(admin, SetupEditPacket.of(SetupEditPacket.Action.CARD_LATER));
            h.assertTrue(SetupService.left(server).size() == Feature.values().length - 2 && !SetupService.state(server).ignored().contains(admin.getUUID()), "LATER on the card changes nothing kept");
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.OFF, Feature.RULES, ""));
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.LATER, Feature.LOG, ""));
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.CLOSE, Feature.AFK, ""));
            Map<Feature, SetupState.Status> st = SetupService.statuses(server);
            h.assertTrue(st.get(Feature.RULES) == SetupState.Status.OFF && st.get(Feature.LOG) == SetupState.Status.LATER && st.get(Feature.AFK) == SetupState.Status.LATER
                    && st.get(Feature.PANELS) == SetupState.Status.LATER, "TURN OFF, SETUP LATER, then closing on AFK: " + st);
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.ON, Feature.RULES, ""));
            h.assertTrue(SetupService.status(server, Feature.RULES) == SetupState.Status.DONE && !Switches.off(Feature.RULES), "TURN ON from the guide");
            SetupAdmin.apply(admin, SetupEditPacket.of(SetupEditPacket.Action.IGNORE));
            h.assertTrue(SetupService.state(server).ignored().contains(admin.getUUID()) && !SetupService.offerCard(admin), "IGNORE SETUP on the card");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            saved.restore(server);
        }
        h.succeed();
    }
}
