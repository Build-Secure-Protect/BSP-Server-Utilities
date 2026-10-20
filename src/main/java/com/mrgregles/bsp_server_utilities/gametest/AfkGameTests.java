package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.afk.AfkData;
import com.mrgregles.bsp_server_utilities.afk.AfkService;
import com.mrgregles.bsp_server_utilities.afk.AfkSettings;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.network.AdminAfkEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminAfkPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AFK detection (owner decisions D70 to D77, D83 to D85): the settings within their limits and shared, idle long enough
 * is AFK and a head turn or an action brings a player back while being pushed does not, {@code /afk}, staff shown away,
 * kicking with its warning, its exemptions and the time counted from going AFK, and the network's AFK list.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class AfkGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long MIN = 60_000L;

    private static void command(MinecraftServer server, ServerPlayer p, String command) {
        CommandDispatcher<CommandSourceStack> d = server.getCommands().getDispatcher();
        MinecraftForge.EVENT_BUS.post(new CommandEvent(d.parse(command, p.createCommandSourceStack())));
    }

    @GameTest(template = EMPTY)
    public void afk_settings_keep_to_their_limits_and_are_shared(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        AfkSettings odd = new AfkSettings(true, 0, true, 999, "  §cgo away\n" + "x".repeat(300), 5).sanitised();
        h.assertTrue(odd.afkMinutes() == 1 && odd.kickMinutes() == 240 && odd.message().length() == AfkSettings.MAX_MESSAGE && !odd.message().contains("§"),
                "minutes and message kept to their limits: " + odd);
        h.assertTrue(new AfkSettings(false, 99, false, 1, "", 0).sanitised().equals(new AfkSettings(false, 60, false, 5, AfkSettings.DEFAULT_MESSAGE, 0)), "an empty message is the default one");
        h.assertTrue(AfkSettings.decode(odd.encode()).equals(odd) && AfkSettings.load(odd.save()).equals(odd), "encoded and saved, they round trip");

        AfkSettings before = AfkData.get(server).settings();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            AfkSettings saved = AfkService.setSettings(server, new AfkSettings(true, 7, true, 45, "Bye for now", 0));
            alpha.drain();
            h.assertTrue(saved.revision() > before.revision() && db.loadAfkSettings().equals(saved), "saved for the network with a rising revision");
            h.assertTrue(db.fetchNoticesAfter(0, 20).stream().anyMatch(n -> n.kind().equals(AfkService.KIND_SETTINGS) && AfkSettings.decode(n.payload()).equals(saved)), "and announced");
            AfkService.apply(server, new AfkSettings(false, 3, false, 10, "old", saved.revision() - 1));
            h.assertTrue(AfkService.settings(server).equals(saved), "an older copy changes nothing");
            AfkSettings newer = new AfkSettings(false, 3, false, 10, "new", saved.revision() + 1);
            AfkService.apply(server, newer);
            h.assertTrue(AfkService.settings(server).equals(newer), "a newer copy from another server is taken");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            AfkData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void idle_players_go_afk_and_come_back_on_a_head_turn_or_an_action_but_not_a_push(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        AtomicLong now = new AtomicLong(1_000_000_000L);
        AfkSettings before = AfkData.get(server).settings();
        AfkService.useForTests(now::get, () -> List.of(p), (pl, why) -> { });
        try {
            AfkData.get(server).setSettings(new AfkSettings(true, 5, false, 30, AfkSettings.DEFAULT_MESSAGE, before.revision()));
            AfkService.onLogin(p);
            now.addAndGet(4 * MIN);
            AfkService.check(server);
            h.assertFalse(AfkService.isAfk(p.getUUID()), "four minutes idle is not AFK");
            p.setPos(p.getX() + 3, p.getY(), p.getZ() + 2); // carried by water, say
            now.addAndGet(MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.isAfk(p.getUUID()), "five minutes idle is AFK, a push notwithstanding");
            p.setYRot(p.getYRot() + 40);
            AfkService.check(server);
            h.assertFalse(AfkService.isAfk(p.getUUID()), "turning the head brings them back");
            now.addAndGet(5 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.isAfk(p.getUUID()), "AFK again");
            MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(p, InteractionHand.MAIN_HAND));
            h.assertFalse(AfkService.isAfk(p.getUUID()), "using an item brings them back");

            command(server, p, "afk");
            server.getCommands().getDispatcher().execute("afk", p.createCommandSourceStack());
            h.assertTrue(AfkService.isAfk(p.getUUID()), "/afk marks them at once (D77)");
            command(server, p, "seed");
            h.assertFalse(AfkService.isAfk(p.getUUID()), "any other command brings them back");

            AfkData.get(server).setSettings(new AfkSettings(false, 5, false, 30, AfkSettings.DEFAULT_MESSAGE, before.revision()));
            now.addAndGet(20 * MIN);
            AfkService.check(server);
            h.assertFalse(AfkService.isAfk(p.getUUID()), "with detection off nobody is marked");
        } finally {
            AfkService.forget(p.getUUID());
            AfkService.useReal();
            AfkData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void afk_staff_show_away_and_a_typed_away_stays(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        AtomicLong now = new AtomicLong(2_000_000_000L);
        Staff.useForTests(List.of(), true);
        StaffDirectory.useForTests(now::get, () -> List.of(admin));
        AfkService.useForTests(now::get, () -> List.of(admin), (pl, why) -> { });
        StaffDirectory.setAutoAway(AfkService::isAfk);
        try {
            AfkService.onLogin(admin);
            h.assertFalse(away(server, admin), "online and active");
            AfkService.markAfk(admin);
            h.assertTrue(away(server, admin), "AFK staff show as away (D72)");
            AfkService.active(admin);
            h.assertFalse(away(server, admin), "and online again when they act");
            StaffDirectory.toggleAway(admin);
            AfkService.markAfk(admin);
            AfkService.active(admin);
            h.assertTrue(away(server, admin), "a typed /su away stays after AFK ends");
            StaffDirectory.toggleAway(admin);
            h.assertFalse(away(server, admin), "until it is typed again");
        } finally {
            if (StaffDirectory.isAway(admin.getUUID())) {
                StaffDirectory.toggleAway(admin);
            }
            AfkService.forget(admin.getUUID());
            AfkService.useReal();
            StaffDirectory.useReal();
            Staff.useConfig();
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    private static boolean away(MinecraftServer server, ServerPlayer p) {
        return StaffDirectory.localOnline(server).stream().anyMatch(o -> o.id().equals(p.getUUID()) && o.away());
    }

    @GameTest(template = EMPTY)
    public void players_afk_too_long_are_warned_then_kicked_and_staff_never(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer late = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        AtomicLong now = new AtomicLong(3_000_000_000L);
        List<UUID> kicked = new ArrayList<>();
        List<String> why = new ArrayList<>();
        AfkSettings before = AfkData.get(server).settings();
        List<ServerPlayer> online = new ArrayList<>(List.of(player, admin));
        AfkService.useForTests(now::get, () -> online, (pl, w) -> {
            kicked.add(pl.getUUID());
            why.add(w.getString());
        });
        try {
            AfkData.get(server).setSettings(new AfkSettings(true, 5, true, 30, "Idle too long", before.revision()));
            AfkService.onLogin(player);
            AfkService.onLogin(admin);
            now.addAndGet(5 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.isAfk(player.getUUID()) && AfkService.isAfk(admin.getUUID()), "both AFK after five minutes");
            now.addAndGet(29 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.warned(player.getUUID()) && kicked.isEmpty(), "a minute before the limit the player is warned (D84)");
            h.assertFalse(AfkService.warned(admin.getUUID()), "an operator is never warned");
            now.addAndGet(MIN);
            AfkService.check(server);
            h.assertTrue(kicked.contains(player.getUUID()) && why.contains("Idle too long"), "at 30 minutes AFK the player is kicked with the message");
            h.assertFalse(kicked.contains(admin.getUUID()), "staff and operators are never kicked (D85)");

            // R1: kicking turned on with someone already past the limit
            AfkData.get(server).setSettings(new AfkSettings(true, 5, false, 30, "Idle too long", before.revision()));
            online.remove(player);
            online.add(late);
            AfkService.onLogin(late);
            now.addAndGet(5 * MIN);
            AfkService.check(server);
            now.addAndGet(40 * MIN);
            AfkService.check(server);
            h.assertFalse(kicked.contains(late.getUUID()), "with kicking off nobody is kicked");
            AfkData.get(server).setSettings(new AfkSettings(true, 5, true, 30, "Idle too long", before.revision()));
            AfkService.check(server);
            h.assertTrue(kicked.contains(late.getUUID()), "turned on, a player past the limit goes at once (D83)");
        } finally {
            for (ServerPlayer p : List.of(player, admin, late)) {
                AfkService.forget(p.getUUID());
            }
            AfkService.useReal();
            AfkData.get(server).setSettings(before);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_network_afk_list_reads_other_servers_until_their_list_is_old(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong now = new AtomicLong(4_000_000_000L);
        AfkService.useForTests(now::get, List::of, null);
        try {
            UUID pip = UUID.randomUUID();
            String payload = AfkService.encode("afktest-beta", List.of(new AfkService.Afk(pip, "Pip", 12 * MIN, false)));
            Map.Entry<String, List<AfkService.Afk>> back = AfkService.decode(payload);
            h.assertTrue(back != null && back.getKey().equals("afktest-beta") && back.getValue().get(0).name().equals("Pip"), "the notice round trips");
            AfkService.applyPresence(payload);
            now.addAndGet(30_000);
            AfkService.Row row = AfkService.afkNow(server).stream().filter(r -> r.id().equals(pip)).findFirst().orElse(null);
            h.assertTrue(row != null && row.server().equals("afktest-beta") && row.afkForMs() == 12 * MIN + 30_000, "another server's AFK player, their time still counting: " + row);
            now.addAndGet(AfkService.STALE_MS);
            h.assertTrue(AfkService.afkNow(server).stream().noneMatch(r -> r.id().equals(pip)), "an old list no longer counts");
        } finally {
            AfkService.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_afk_tab_shows_who_is_afk_and_only_admins_change_the_settings(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        AtomicLong now = new AtomicLong(5_000_000_000L);
        AfkSettings before = AfkData.get(server).settings();
        Staff.useForTests(List.of(), true);
        StaffDirectory.useForTests(now::get, () -> List.of(admin));
        AfkService.useForTests(now::get, () -> List.of(player, admin), (pl, w) -> { });
        StaffDirectory.setAutoAway(AfkService::isAfk);
        try {
            AdminAfkEditPacket edit = new AdminAfkEditPacket(false, new AfkSettings(true, 8, true, 45, "See you later", 0));
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            edit.encode(buf);
            h.assertTrue(AdminAfkEditPacket.decode(buf).equals(edit), "an edit round trips");
            h.assertFalse(AdminPanel.applyAfk(player, edit), "a player who may not edit changes nothing");
            h.assertTrue(AfkService.settings(server).equals(before), "the settings are unchanged");
            h.assertTrue(AdminPanel.applyAfk(admin, edit), "an admin's edit is taken");
            AfkSettings now1 = AfkService.settings(server);
            h.assertTrue(now1.afkMinutes() == 8 && now1.kick() && now1.kickMinutes() == 45 && now1.message().equals("See you later") && now1.revision() > before.revision(),
                    "and is the network's settings: " + now1);

            AfkService.onLogin(player);
            AfkService.onLogin(admin);
            AfkService.markAfk(player);
            StaffDirectory.toggleAway(admin);
            now.addAndGet(3 * MIN);
            AdminAfkPacket tab = AdminPanel.afkPacket(server);
            AdminAfkPacket.Row afkRow = tab.rows().stream().filter(r -> r.name().equals(player.getGameProfile().getName())).findFirst().orElse(null);
            h.assertTrue(afkRow != null && afkRow.afkForMs() == 3 * MIN && !afkRow.exempt() && !afkRow.typedAway(), "an AFK player, how long, can be kicked: " + afkRow);
            AdminAfkPacket.Row awayRow = tab.rows().stream().filter(r -> r.name().equals(admin.getGameProfile().getName())).findFirst().orElse(null);
            h.assertTrue(awayRow != null && awayRow.typedAway() && awayRow.exempt() && awayRow.colour() == Staff.OPERATOR_COLOUR, "staff away by /su away, marked so (D85): " + awayRow);
            buf = new FriendlyByteBuf(Unpooled.buffer());
            tab.encode(buf);
            h.assertTrue(AdminAfkPacket.decode(buf).equals(tab), "the tab's data round trips");
        } finally {
            if (StaffDirectory.isAway(admin.getUUID())) {
                StaffDirectory.toggleAway(admin);
            }
            AfkService.forget(player.getUUID());
            AfkService.forget(admin.getUUID());
            AfkService.useReal();
            StaffDirectory.useReal();
            Staff.useConfig();
            AfkData.get(server).setSettings(before);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }
}
