package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.CreativeTracker;
import com.mrgregles.bsp_server_utilities.log.LogData;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogSync;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.network.AdminLogEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminLogPacket;
import com.mrgregles.bsp_server_utilities.network.LogOpenPacket;
import com.mrgregles.bsp_server_utilities.network.LogViewPacket;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The action log, server side (owner decisions D25 to D27, D29, D30): commands by staff, operators and the console,
 * private messages never, switches (and their own entries), game-mode and give kinds, coordinates hidden for players,
 * the shared table and notices, pruning, and items from the creative menu through the packet hook (the mixin, end to end).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class LogGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static void run(MinecraftServer server, CommandSourceStack source, String command) {
        CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
        MinecraftForge.EVENT_BUS.post(new CommandEvent(d.parse(command, source)));
    }

    private static LogEntry newest(MinecraftServer server) {
        List<LogEntry> all = LogData.get(server).entries();
        return all.isEmpty() ? null : all.get(0);
    }

    @GameTest(template = EMPTY)
    public void commands_by_staff_and_the_console_are_logged_and_private_messages_never(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        Staff.useForTests(List.of(), true);
        try {
            run(server, admin.createCommandSourceStack(), "seed");
            LogEntry e = newest(server);
            h.assertTrue(e != null && e.actor().equals(admin.getGameProfile().getName()) && e.text().equals("/seed") && e.kind().equals(LogEntry.CMD) && e.roleLabel().equals("Operator"), "an operator's command: " + e);
            run(server, admin.createCommandSourceStack(), "msg someone hello there");
            h.assertTrue(newest(server).text().equals("/seed"), "a private message is never logged");
            run(server, admin.createCommandSourceStack(), "gamemode creative");
            h.assertTrue(newest(server).kind().equals(LogEntry.MODE), "a game-mode change has its kind: " + newest(server));
            run(server, admin.createCommandSourceStack(), "give " + admin.getGameProfile().getName() + " minecraft:diamond 64");
            h.assertTrue(newest(server).kind().equals(LogEntry.GIVE), "a give has its kind");
            LogEntry before = newest(server);
            run(server, player.createCommandSourceStack(), "seed");
            h.assertTrue(newest(server).uid().equals(before.uid()), "a player who is not staff is not logged");
            run(server, server.createCommandSourceStack(), "list");
            h.assertTrue(newest(server).actor().equals("Console") && newest(server).text().equals("/list"), "the console is logged as Console: " + newest(server));

            ActionLog.setSwitch(server, admin, "seed", false);
            LogEntry sw = newest(server);
            h.assertTrue(sw.kind().equals(LogEntry.SWITCH) && sw.text().equals("stopped logging /seed") && sw.actor().equals(admin.getGameProfile().getName()), "turning a switch off is itself an entry (D29): " + sw);
            run(server, admin.createCommandSourceStack(), "seed");
            h.assertTrue(newest(server).uid().equals(sw.uid()), "a switched-off command is not logged");
            ActionLog.setSwitch(server, admin, "msg", false);
            h.assertTrue(newest(server).uid().equals(sw.uid()), "private messages have no switch");
            ActionLog.setSwitch(server, admin, "seed", true);
            h.assertTrue(newest(server).text().equals("started logging /seed"), "and on again");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            Staff.useConfig();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void players_see_coordinates_hidden_and_counts_kept(GameTestHelper h) {
        h.assertTrue(LogText.masked("/tp 120 64 -300").equals("/tp · · ·"), "x y z hidden: " + LogText.masked("/tp 120 64 -300"));
        h.assertTrue(LogText.masked("/tp Steve ~ ~1 ~-2.5").equals("/tp Steve · · ·"), "relative ones too: " + LogText.masked("/tp Steve ~ ~1 ~-2.5"));
        h.assertTrue(LogText.masked("/give Steve minecraft:diamond 64").equals("/give Steve minecraft:diamond 64"), "a lone count stays");
        h.assertTrue(LogText.masked("took 64 × diamond from creative").equals("took 64 × diamond from creative"), "item entries read the same");
        h.assertTrue(LogText.root("/minecraft:TP a b").equals("tp") && LogText.root("gamemode creative").equals("gamemode"), "the root without slash or namespace");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void entries_are_shared_once_and_old_ones_go(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            LogEntry e = ActionLog.entryForServer("Console", LogEntry.CMD, "/say test " + UUID.randomUUID());
            ActionLog.record(server, e);
            alpha.drain();
            h.assertTrue(db.logCount() == 1 && db.fetchNoticesAfter(0, 20).stream().anyMatch(n -> n.kind().equals(ActionLog.KIND_ENTRY)), "written to the table and announced");
            LogEntry fromBeta = LogEntry.decode(new LogEntry(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", "", "RCON", "console", "RCON", 0x9AA3B5, LogEntry.CMD, "/stop").encode());
            h.assertTrue(fromBeta != null && LogData.get(server).add(fromBeta) && !LogData.get(server).add(fromBeta), "an entry from another server is kept once");
            h.assertTrue(ActionLog.recent(server, 50, false, List.of()).stream().noneMatch(x -> x.server().equals("beta")) && ActionLog.recent(server, 50, true, List.of(LogEntry.CMD)).contains(fromBeta),
                    "this server only, or all; filtered by kind");
            LogEntry old = new LogEntry(0, UUID.randomUUID().toString(), System.currentTimeMillis() - ActionLog.keepMs() - 60_000, "alpha", "", "Console", "console", "Console", 0, LogEntry.CMD, "/old");
            LogData.get(server).add(old);
            db.insertLog(old);
            h.assertTrue(LogData.get(server).prune(System.currentTimeMillis() - ActionLog.keepMs()) >= 1 && !LogData.get(server).entries().contains(old), "older than 7 days goes");
            h.assertTrue(db.pruneLog(System.currentTimeMillis() - ActionLog.keepMs()) == 1 && db.logCount() == 1, "from the table too");
        } finally {
            Storage.useForTests(null);
            alpha.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void items_taken_from_the_creative_menu_are_logged_and_moving_them_is_not(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        Staff.useForTests(List.of(), true);
        try {
            admin.setGameMode(GameType.CREATIVE);
            LogEntry before = newest(server);
            admin.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.DIAMOND, 64)));
            h.assertTrue(admin.getInventory().countItem(Items.DIAMOND) == 64, "the packet put the diamonds in the hotbar");
            admin.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(36, ItemStack.EMPTY));
            admin.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(37, new ItemStack(Items.DIAMOND, 64)));
            h.assertTrue(newest(server) == null || before == null || newest(server).uid().equals(before.uid()), "nothing is written until the window ends");
            CreativeTracker.flushNow(server, admin);
            LogEntry e = newest(server);
            h.assertTrue(e != null && e.kind().equals(LogEntry.ITEM) && e.text().equals("took 64 × diamond from creative"), "one entry for the diamonds; the move did not count: " + e);
            admin.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(37, ItemStack.EMPTY));
            admin.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(38, new ItemStack(Items.DIAMOND, 64)));
            CreativeTracker.flushNow(server, admin);
            h.assertTrue(newest(server).uid().equals(e.uid()), "only moving them writes nothing");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            Staff.useConfig();
            admin.getInventory().clearContent();
        }
        h.succeed();
    }

    private static LogEntry at(String server, String kind, String text, long atMs) {
        return new LogEntry(0, UUID.randomUUID().toString(), atMs, server, "", "Ferrous", "admin", "Admin", 0xB58CFF, kind, text);
    }

    @GameTest(template = EMPTY)
    public void players_get_the_newest_entries_with_coordinates_hidden_and_panels_filter_them(GameTestHelper h) {
        long now = System.currentTimeMillis();
        List<LogEntry> newestFirst = new ArrayList<>();
        newestFirst.add(at("alpha", LogEntry.CMD, "/tp 120 64 -300", now - 120_000));
        newestFirst.add(at("beta", LogEntry.ITEM, "took 64 × diamond from creative", now - 360_000));
        newestFirst.add(at("alpha", LogEntry.MODE, "/gamemode creative", now - 660_000));
        for (int i = 0; i < 250; i++) {
            newestFirst.add(at(i % 2 == 0 ? "alpha" : "beta", LogEntry.CMD, "/time set day", now - 3_600_000L - i));
        }
        LogViewPacket view = LogViewPacket.of(newestFirst, "alpha", 7, now);
        h.assertTrue(view.rows().size() == LogViewPacket.SENT, "only the newest " + LogViewPacket.SENT + " go to players: " + view.rows().size());
        LogViewPacket.Row first = view.rows().get(0);
        h.assertTrue(first.text().equals("/tp · · ·") && first.agoMs() == 120_000 && first.actor().equals("Ferrous") && first.colour() == 0xB58CFF,
                "coordinates hidden, how long ago, who in their colour: " + first);
        h.assertTrue(view.rows().get(1).text().equals("took 64 × diamond from creative"), "counts stay");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        view.encode(buf);
        h.assertTrue(LogViewPacket.decode(buf).equals(view), "the view round trips");

        List<LogViewPacket.Row> all6 = LogViewPacket.visible(view.rows(), "alpha", new PanelSettings.LogView(true, List.of(), 6));
        h.assertTrue(all6.size() == 6 && all6.get(1).server().equals("beta"), "every server, six lines");
        List<LogViewPacket.Row> here = LogViewPacket.visible(view.rows(), "alpha", new PanelSettings.LogView(false, List.of(), 12));
        h.assertTrue(here.size() == 12 && here.stream().allMatch(r -> r.server().equals("alpha")), "this server only, twelve lines");
        List<LogViewPacket.Row> items = LogViewPacket.visible(view.rows(), "alpha", new PanelSettings.LogView(true, List.of(LogEntry.ITEM, LogEntry.MODE), 8));
        h.assertTrue(items.size() == 2 && items.get(0).kind().equals(LogEntry.ITEM) && items.get(1).kind().equals(LogEntry.MODE), "only the chosen kinds: " + items);

        PanelSettings s = new PanelSettings(PanelSettings.Mode.LOG, PanelSettings.DEFAULT_BACKGROUND, List.of(), PanelSettings.Image.NONE, "a", 1)
                .withViews(PanelSettings.StaffView.ALL, new PanelSettings.LogView(false, List.of("ITEM", "item", "teleports"), 12)).sanitised();
        h.assertTrue(s.log().kinds().equals(List.of(LogEntry.ITEM)) && s.log().lines() == 12 && !s.log().allServers(), "a log panel keeps known kinds only: " + s.log());
        h.assertTrue(PanelSettings.load(s.save()).equals(s), "and its view round trips");

        MinecraftServer server = h.getLevel().getServer();
        LogEntry fresh = ActionLog.entryForServer("Console", LogEntry.CMD, "/setworldspawn 10 70 -20 " + UUID.randomUUID());
        ActionLog.record(server, fresh);
        LogViewPacket.Row sent = LogSync.view(server).rows().get(0);
        h.assertTrue(sent.text().startsWith("/setworldspawn · · · ") && sent.actor().equals("Console"), "what the server sends after an entry: " + sent);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_log_tab_lists_switches_and_full_entries_and_only_admins_turn_them(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        Staff.useForTests(List.of(), true);
        try {
            String marker = "m" + UUID.randomUUID().toString().substring(0, 8); // a letter first, or digits alone read as a coordinate
            ActionLog.record(server, ActionLog.entryForServer("Console", LogEntry.CMD, "/tp Steve 120 64 -300 " + marker));
            LogData.get(server).add(at("tabtest-beta", LogEntry.ITEM, "took 1 × stone from creative " + marker, System.currentTimeMillis()));
            AdminLogPacket tab = AdminPanel.logPacket(server, "", "");
            h.assertTrue(tab.switches().stream().anyMatch(sw -> sw.root().equals("tp") && !sw.privateMessage()), "every command the server knows has a switch");
            h.assertTrue(tab.switches().stream().anyMatch(sw -> sw.root().equals("msg") && sw.privateMessage()), "private messages are listed as never logged");
            int firstPrivate = 0;
            while (firstPrivate < tab.switches().size() && !tab.switches().get(firstPrivate).privateMessage()) {
                firstPrivate++;
            }
            int fp = firstPrivate;
            h.assertTrue(tab.switches().subList(fp, tab.switches().size()).stream().allMatch(AdminLogPacket.Switch::privateMessage), "private messages come last");
            h.assertTrue(tab.entries().stream().anyMatch(r -> r.text().equals("/tp Steve 120 64 -300 " + marker)), "staff read the full text, coordinates and all");
            h.assertTrue(tab.servers().contains("tabtest-beta"), "the servers in the log are offered as filters");
            AdminLogPacket beta = AdminPanel.logPacket(server, "tabtest-beta", "");
            h.assertTrue(!beta.entries().isEmpty() && beta.entries().stream().allMatch(r -> r.server().equals("tabtest-beta")), "one server's entries only");
            AdminLogPacket modes = AdminPanel.logPacket(server, "", LogEntry.ITEM);
            h.assertTrue(modes.entries().stream().allMatch(r -> r.kind().equals(LogEntry.ITEM)), "one kind's entries only");
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            tab.encode(buf);
            h.assertTrue(AdminLogPacket.decode(buf).equals(tab), "the tab's data round trips");
            AdminLogEditPacket turn = new AdminLogEditPacket("", "", "weather", false);
            buf = new FriendlyByteBuf(Unpooled.buffer());
            turn.encode(buf);
            h.assertTrue(AdminLogEditPacket.decode(buf).equals(turn), "a switch change round trips");

            h.assertFalse(AdminPanel.applyLog(player, turn), "a player who may not edit turns nothing");
            h.assertTrue(ActionLog.logged(server, "weather"), "the switch is unchanged");
            h.assertTrue(AdminPanel.applyLog(admin, turn) && !ActionLog.logged(server, "weather"), "an admin turns /weather off from the tab");
            LogEntry e = newest(server);
            h.assertTrue(e.kind().equals(LogEntry.SWITCH) && e.text().equals("stopped logging /weather") && e.actor().equals(admin.getGameProfile().getName()), "and the change is an entry (D29): " + e);
            h.assertTrue(AdminPanel.logPacket(server, "", "").switches().stream().anyMatch(sw -> sw.root().equals("weather") && !sw.on()), "the tab shows it off");
            AdminPanel.applyLog(admin, new AdminLogEditPacket("", "", "weather", true));
            h.assertTrue(ActionLog.logged(server, "weather"), "and on again");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            Staff.useConfig();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void every_player_can_open_the_action_log_and_reads_coordinates_hidden(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
        var node = d.getRoot().getChild("actionlog");
        h.assertTrue(node != null && node.canUse(player.createCommandSourceStack()), "/actionlog is there for a player who is not staff (D58)");
        String marker = "m" + UUID.randomUUID().toString().substring(0, 8); // a letter first, or digits alone read as a coordinate
        ActionLog.record(server, ActionLog.entryForServer("Console", LogEntry.CMD, "/tp Steve 120 64 -300 " + marker));
        h.assertTrue(d.execute("actionlog", player.createCommandSourceStack()) == 1, "the command runs for them");
        LogOpenPacket open = new LogOpenPacket(com.mrgregles.bsp_server_utilities.log.LogSync.view(server));
        LogViewPacket.Row first = open.view().rows().get(0);
        h.assertTrue(first.text().equals("/tp Steve · · · " + marker), "what the screen shows has coordinates hidden: " + first.text());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        open.encode(buf);
        h.assertTrue(LogOpenPacket.decode(buf).equals(open), "the screen's data round trips");
        h.succeed();
    }
}
