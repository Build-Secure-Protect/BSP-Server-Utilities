package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.network.PlayersViewPacket;
import com.mrgregles.bsp_server_utilities.network.SetupEditPacket;
import com.mrgregles.bsp_server_utilities.network.SetupViewPacket;
import com.mrgregles.bsp_server_utilities.players.Joined;
import com.mrgregles.bsp_server_utilities.players.PlayerCounter;
import com.mrgregles.bsp_server_utilities.players.PlayerCounts;
import com.mrgregles.bsp_server_utilities.players.PlayersData;
import com.mrgregles.bsp_server_utilities.players.PlayersSettings;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupAdmin;
import com.mrgregles.bsp_server_utilities.setup.SetupData;
import com.mrgregles.bsp_server_utilities.setup.SetupService;
import com.mrgregles.bsp_server_utilities.setup.SetupState;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlock;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlockEntity;
import com.mrgregles.bsp_server_utilities.panel.PanelEditing;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import com.mrgregles.bsp_server_utilities.panel.PanelShape;
import com.mrgregles.bsp_server_utilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The player counter, server side (SU-24; owner decisions D166 to D181): a player counted once by UUID across two servers
 * sharing storage, the welcome line on the server joined and, after the server's name, on the others; a returning player
 * not counted; set, add, reset and back to the real count, each in the Action Log; the copy sent to players (title,
 * number, today, this week, newest); the counter turned off in the setup guide; the guide page's actions for admins only.
 * SU-25: a Player count panel keeps its own title (32 characters, empty for the counter's) and the new-counts tick, saved
 * by an admin as any panel. The PLAYERS board, the Player count face and the guide page's screen need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class PlayerCounterGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    /** Everything these tests change, put back afterwards. */
    private record Saved(PlayersSettings settings, PlayerCounts counts, SetupState setup, SetupState.Status welcome) {
        static Saved of(MinecraftServer server) {
            return new Saved(PlayerCounter.settings(server), PlayerCounter.counts(), SetupService.state(server), SetupData.get(server).welcome());
        }

        void restore(MinecraftServer server, List<ServerPlayer> players) {
            Storage.useForTests(null);
            PlayerCounter.useReal();
            players.forEach(p -> PlayersData.get(server).remove(p.getUUID()));
            PlayersData.get(server).setSettings(settings);
            PlayerCounter.setCountsForTests(counts);
            SetupService.restoreForTests(server, setup, welcome);
        }
    }

    /** The default settings, newer than anything saved, and the counter on. */
    private static void fresh(MinecraftServer server) {
        PlayersData.get(server).setSettings(PlayersSettings.DEFAULTS.withRevision(PlayerCounter.settings(server).revision() + 1));
        SetupService.restoreForTests(server, SetupService.state(server).withOff(Feature.PLAYERS, false), SetupData.get(server).welcome());
    }

    private static List<String> texts(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }

    @GameTest(template = EMPTY)
    public void a_player_is_counted_once_across_two_servers_and_welcomed_on_both(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        List<ServerPlayer> made = new ArrayList<>();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha"), beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        List<Component> lines = new ArrayList<>();
        try {
            fresh(server);
            PlayerCounter.useForTests(System::currentTimeMillis, (srv, line) -> lines.add(line));
            ServerPlayer kat = TestPlayers.mockServerPlayer(h);
            made.add(kat);
            String name = kat.getGameProfile().getName();
            Storage.useForTests(alpha);
            PlayerCounter.onLogin(kat);
            alpha.drain();
            h.assertTrue(PlayerCounter.real() == 1 && PlayerCounter.shown(server) == 1, "the first join on the network is counted (D166, from 0, D167): " + PlayerCounter.real());
            h.assertTrue(texts(lines).equals(List.of("Welcome " + name + ", player number 1 on BSP!")), "and welcomed in the default words (D179): " + texts(lines));
            h.assertTrue(lines.get(0).getSiblings().get(0).getStyle().getColor() != null && lines.get(0).getSiblings().get(0).getStyle().getColor().getValue() == 0xFFAA00, "in gold (D171)");
            // the same player on the second server
            Storage.useForTests(beta);
            PlayerCounter.onLogin(kat);
            beta.drain();
            h.assertTrue(db.playerCounts(0, 10).real() == 1 && lines.size() == 1, "the same UUID on another server is not counted again");
            // the second server hears of the first join
            beta.pollNotices();
            beta.drain();
            h.assertTrue(texts(lines).get(lines.size() - 1).equals("[alpha] Welcome " + name + ", player number 1 on BSP!"), "elsewhere the server joined comes first (D176, D180): " + texts(lines));
            h.assertTrue(PlayerCounter.real() == 1, "and the other server reads the count again");
            // a second player on the second server
            ServerPlayer sam = TestPlayers.mockServerPlayer(h);
            made.add(sam);
            PlayerCounter.onLogin(sam);
            beta.drain();
            h.assertTrue(PlayerCounter.real() == 2 && texts(lines).get(lines.size() - 1).equals("Welcome " + sam.getGameProfile().getName() + ", player number 2 on BSP!"),
                    "a new player on any server counts for the network (D174): " + texts(lines));
            Joined newest = db.playerCounts(0, 10).newest();
            h.assertTrue(newest != null && newest.id().equals(sam.getUUID()) && newest.server().equals("beta"), "the table keeps who joined first, and where");
        } finally {
            alpha.close();
            beta.close();
            saved.restore(server, made);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_returning_player_is_not_counted_on_a_server_of_its_own(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        List<ServerPlayer> made = new ArrayList<>();
        List<Component> lines = new ArrayList<>();
        try {
            fresh(server);
            Storage.useForTests(null);
            PlayerCounter.useForTests(System::currentTimeMillis, (srv, line) -> lines.add(line));
            PlayerCounter.refresh(server);
            int before = PlayerCounter.real();
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            made.add(p);
            PlayerCounter.onLogin(p);
            h.assertTrue(PlayerCounter.real() == before + 1 && PlayersData.get(server).has(p.getUUID()) && lines.size() == 1, "without shared storage this server keeps its own list (D174)");
            PlayerCounter.onLogin(p);
            h.assertTrue(PlayerCounter.real() == before + 1 && lines.size() == 1, "joining again is not a new player");
            PlayersSettings s = PlayerCounter.settings(server);
            PlayerCounter.setSettings(server, s.withWelcome("{name} is our player {number}!"));
            ServerPlayer q = TestPlayers.mockServerPlayer(h);
            made.add(q);
            PlayerCounter.onLogin(q);
            h.assertTrue(texts(lines).get(1).equals(q.getGameProfile().getName() + " is our player " + PlayerCounter.format(PlayerCounter.shown(server)) + "!"),
                    "the welcome is in the words admins type (D177): " + texts(lines));
        } finally {
            saved.restore(server, made);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void set_add_reset_and_back_to_the_real_count_are_logged(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        try {
            fresh(server);
            Storage.useForTests(null);
            PlayerCounter.setCountsForTests(new PlayerCounts(10, List.of(), null));
            h.assertTrue(PlayerCounter.set(server, null, "Console", 1500) == 1500 && PlayerCounter.real() == 10 && PlayerCounter.settings(server).offset() == 1490,
                    "set: the number shown, the real list untouched (D170)");
            LogEntry e = ActionLog.recent(server, 1, false, List.of(LogEntry.CMD)).get(0);
            h.assertTrue(e.actor().equals("Console") && e.text().equals("set the player count to 1,500 (real count 10)"), "and written to the Action Log: " + e);
            h.assertTrue(PlayerCounter.add(server, null, "Console", 5) == 1505, "add");
            h.assertTrue(PlayerCounter.add(server, null, "RCON", -2000) == 0, "a negative add takes away, never below 0");
            e = ActionLog.recent(server, 1, false, List.of(LogEntry.CMD)).get(0);
            h.assertTrue(e.actor().equals("RCON") && e.text().equals("took 2,000 from the player count (now 0)"), "logged: " + e);
            PlayerCounter.add(server, null, "Console", 7);
            h.assertTrue(PlayerCounter.reset(server, null, "Console") == 0 && PlayerCounter.settings(server).offset() == -10, "reset: 0 from now");
            PlayerCounter.setCountsForTests(new PlayerCounts(12, List.of(), null));
            h.assertTrue(PlayerCounter.shown(server) == 2, "and new players count up from there");
            h.assertTrue(PlayerCounter.backToReal(server, null, "Console") == 12 && PlayerCounter.settings(server).offset() == 0, "true: back to the real count");
            e = ActionLog.recent(server, 1, false, List.of(LogEntry.CMD)).get(0);
            h.assertTrue(e.text().equals("set the player count back to the real count (12)"), "logged: " + e);
            h.assertTrue(LogText.selfLogged("su players set 5") && LogText.selfLogged("/serverutils players reset") && LogText.selfLogged("su players true")
                    && !LogText.selfLogged("su players") && !LogText.selfLogged("su status"), "the commands are not logged twice (their own entry instead)");
        } finally {
            saved.restore(server, List.of());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void players_get_the_title_number_new_counts_and_newest(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        try {
            fresh(server);
            Storage.useForTests(null);
            long now = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            PlayerCounter.useForTests(() -> now, (srv, line) -> {
            });
            Joined a = new Joined(UUID.randomUUID(), "Kat_Miner", now - 3_600_000L, "spawn"), b = new Joined(UUID.randomUUID(), "Sam", now - 3 * PlayerCounts.DAY_MS, "spawn");
            PlayerCounter.setCountsForTests(new PlayerCounts(1234, List.of(a, b), a));
            PlayerCounter.setSettings(server, PlayerCounter.settings(server).withTitle("Welcome to the network"));
            h.assertTrue(PlayerCounter.dirty(), "a change is sent to every player");
            PlayersViewPacket v = PlayerCounter.view(server);
            h.assertTrue(!v.off() && v.title().equals("Welcome to the network") && v.shown() == 1234 && v.today() == 1 && v.week() == 2
                    && v.newest().equals("Kat_Miner") && v.newestAgoMs() == 3_600_000L, "title, number, today, this week, newest and how long ago (D168, D169): " + v);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            v.encode(buf);
            h.assertTrue(PlayersViewPacket.decode(buf).equals(v), "the copy round trips");
            PlayersSettings odd = new PlayersSettings(3, "  §c" + "x".repeat(40), "", 9).sanitised();
            h.assertTrue(odd.title().length() == PlayersSettings.MAX_TITLE && !odd.title().contains("§") && odd.welcome().equals(PlayersSettings.DEFAULT_WELCOME),
                    "titles are cut to 32 characters without formatting codes; an empty welcome is the default: " + odd);
            h.assertTrue(PlayersSettings.decode(odd.encode()).equals(odd), "the settings round trip");
            h.assertTrue(PlayerCounter.welcomeText(PlayersSettings.DEFAULT_WELCOME, "Kat_Miner", 1235).equals("Welcome Kat_Miner, player number 1,235 on BSP!"), "{name} and {number} filled in (D179)");
        } finally {
            saved.restore(server, List.of());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_newest_settings_win_between_servers(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        try {
            fresh(server);
            Storage.useForTests(alpha);
            PlayersSettings mine = PlayerCounter.setSettings(server, PlayerCounter.settings(server).withTitle("Our players"));
            alpha.drain();
            h.assertTrue(db.loadPlayersSettings().equals(mine) && db.fetchNoticesAfter(0, 20).stream().anyMatch(n -> n.kind().equals(PlayerCounter.KIND_SETTINGS)), "saved and announced for the network");
            PlayerCounter.apply(server, mine.withTitle("Older").withRevision(mine.revision() - 1));
            h.assertTrue(PlayerCounter.settings(server).title().equals("Our players"), "an older copy changes nothing");
            PlayerCounter.apply(server, mine.withOffset(50).withRevision(mine.revision() + 1));
            h.assertTrue(PlayerCounter.settings(server).offset() == 50, "a newer one is taken");
        } finally {
            alpha.close();
            saved.restore(server, List.of());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void turned_off_nobody_is_counted_and_players_see_nothing(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        List<ServerPlayer> made = new ArrayList<>();
        List<Component> lines = new ArrayList<>();
        try {
            fresh(server);
            Storage.useForTests(null);
            PlayerCounter.useForTests(System::currentTimeMillis, (srv, line) -> lines.add(line));
            h.assertTrue(Feature.values()[Feature.values().length - 1] == Feature.PLAYERS && Feature.PLAYERS.ordinal() == Feature.PANELS.ordinal() + 1 && Feature.PLAYERS.ownSwitch(),
                    "the guide's page comes after Display panels, with its own off switch (D173)");
            SetupService.turnOff(server, Feature.PLAYERS);
            h.assertTrue(Switches.off(Feature.PLAYERS) && PlayerCounter.off(), "TURN OFF FEATURE");
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            made.add(p);
            PlayerCounter.onLogin(p);
            h.assertTrue(!PlayersData.get(server).has(p.getUUID()) && lines.isEmpty(), "off: nobody is counted or welcomed");
            h.assertTrue(PlayerCounter.view(server).equals(PlayersViewPacket.NONE), "and the board and panels show nothing");
            SetupService.turnOn(server, Feature.PLAYERS);
            PlayerCounter.onLogin(p);
            h.assertTrue(PlayersData.get(server).has(p.getUUID()) && lines.size() == 1 && !PlayerCounter.view(server).off(), "on again: counted from then");
        } finally {
            saved.restore(server, made);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_guide_page_changes_the_counter_for_admins_only(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h), player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            fresh(server);
            Storage.useForTests(null);
            PlayerCounter.setCountsForTests(new PlayerCounts(40, List.of(), null));
            h.assertTrue(!SetupAdmin.apply(player, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_SET, Feature.PLAYERS, "999")) && PlayerCounter.shown(server) == 40, "players who may not edit change nothing");
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_SET, Feature.PLAYERS, "1,500"));
            h.assertTrue(PlayerCounter.shown(server) == 1500, "SET with the number typed");
            LogEntry e = ActionLog.recent(server, 1, false, List.of(LogEntry.CMD)).get(0);
            h.assertTrue(e.actor().equals(admin.getGameProfile().getName()) && e.text().startsWith("set the player count to 1,500"), "under the admin's name in the Action Log: " + e);
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_ADD, Feature.PLAYERS, "-500"));
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_ADD, Feature.PLAYERS, "lots"));
            h.assertTrue(PlayerCounter.shown(server) == 1000, "ADD; not a number changes nothing");
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_RESET, Feature.PLAYERS, ""));
            h.assertTrue(PlayerCounter.shown(server) == 0, "RESET TO 0");
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_REAL, Feature.PLAYERS, ""));
            h.assertTrue(PlayerCounter.shown(server) == 40, "REAL COUNT");
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_TITLE, Feature.PLAYERS, "Network players"));
            String words = "Hello {name}, you are number {number}! " + "y".repeat(200);
            SetupAdmin.apply(admin, new SetupEditPacket(SetupEditPacket.Action.PLAYERS_WELCOME, Feature.PLAYERS, words));
            PlayersSettings s = PlayerCounter.settings(server);
            h.assertTrue(s.title().equals("Network players") && s.welcome().length() == PlayersSettings.MAX_WELCOME && s.welcome().startsWith("Hello {name}"), "the title, and the words cut to 160 characters: " + s);
            SetupViewPacket view = SetupService.view(server, false, Feature.PLAYERS);
            h.assertTrue(view.players().equals(new SetupViewPacket.Players(40, 40, "Network players", s.welcome())), "the guide page's data: " + view.players());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            view.encode(buf);
            h.assertTrue(SetupViewPacket.decode(buf).players().equals(view.players()), "and it round trips");
            FriendlyByteBuf edit = new FriendlyByteBuf(Unpooled.buffer());
            new SetupEditPacket(SetupEditPacket.Action.PLAYERS_WELCOME, Feature.PLAYERS, words).encode(edit);
            h.assertTrue(SetupEditPacket.decode(edit).arg().equals(words), "the words travel whole to the server");
            h.assertTrue(SetupService.status(server, Feature.PLAYERS) == SetupState.Status.DONE, "a change on the page counts as set up (D152)");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            saved.restore(server, List.of());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_player_count_panel_keeps_its_own_title_and_tick(GameTestHelper h) {
        PanelSettings.CountView odd = new PanelSettings.CountView("  Welcome\u00a7 to the network, everyone who comes  ", false);
        PanelSettings s = new PanelSettings(PanelSettings.Mode.PLAYERS, PanelSettings.DEFAULT_BACKGROUND, List.of(), PanelSettings.Image.NONE, "admin", 1).withCount(odd).sanitised();
        h.assertTrue(s.mode() == PanelSettings.Mode.PLAYERS && s.count().title().length() <= PanelSettings.CountView.MAX_TITLE && !s.count().title().contains("\u00a7")
                && s.count().title().equals("Welcome to the network, everyone") && !s.count().newCounts(), "the panel's own title, cut to 32 characters, and the tick (D178): " + s.count());
        h.assertTrue(PanelSettings.load(s.save()).equals(s), "saved and loaded unchanged");
        PanelSettings older = PanelSettings.load(new PanelSettings(PanelSettings.Mode.STAFF, PanelSettings.DEFAULT_BACKGROUND, List.of(), PanelSettings.Image.NONE, "admin", 1).save());
        h.assertTrue(older.count().equals(PanelSettings.CountView.DEFAULT) && older.count().title().isEmpty() && older.count().newCounts(),
                "a panel saved before has the counter's title and the new counts");
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        BlockPos o = h.absolutePos(new BlockPos(16, 1, 20));
        try {
            var state = ModBlocks.DISPLAY_PANEL.get().defaultBlockState().setValue(DisplayPanelBlock.FACING, Direction.NORTH);
            level.setBlock(o, DisplayPanelBlock.connected(state, level, o), 3);
            Vec3 near = Vec3.atCenterOf(o).add(0, 0, -3);
            admin.moveTo(near.x, near.y, near.z);
            PanelShape.save(level, o, Direction.NORTH, PanelSettings.defaults());
            h.assertTrue(PanelEditing.save(admin, o, s.save()) == PanelEditing.Outcome.SAVED, "an admin saves a Player count panel (D172)");
            PanelSettings kept = level.getBlockEntity(o) instanceof DisplayPanelBlockEntity be ? be.settings() : null;
            h.assertTrue(kept != null && kept.mode() == PanelSettings.Mode.PLAYERS && kept.count().equals(s.count()), "and the panel keeps its mode, title and tick: " + kept);
        } finally {
            level.getServer().getPlayerList().getOps().remove(admin.getGameProfile());
            level.setBlock(o, Blocks.AIR.defaultBlockState(), 3);
        }
        h.succeed();
    }
}
