package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.afk.AfkData;
import com.mrgregles.bsp_server_utilities.afk.AfkService;
import com.mrgregles.bsp_server_utilities.afk.AfkSettings;
import com.mrgregles.bsp_server_utilities.afk.MacroWatch;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.AdminAfkEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminAfkPacket;
import com.mrgregles.bsp_server_utilities.network.AfkAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.AfkCheckPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AFK detection (owner decisions D70 to D77, D83 to D85) and the presence check with macros (D207 to D224): the settings
 * within their limits, shared, and read from before the macros switch; idle long enough is AFK and a head turn or an
 * action brings a player back while being pushed does not; {@code /afk}; staff shown away; at the kick time a check for
 * players and staff, never admins or operators, warned a minute before; the right word, a wrong one, silence; kicking
 * off; an auto-clicker and a mouse jiggle not counting after ten minutes while varied input does; the hour's clearance;
 * the network's AFK list and the AFK tab's data.
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

    private static AfkSettings settings(boolean kick, boolean macros, long revision) {
        return new AfkSettings(true, 5, kick, 30, "Idle too long", macros, revision);
    }

    private static void use(ServerPlayer p) {
        MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickItem(p, InteractionHand.MAIN_HAND));
    }

    /** Cards sent to each player, in order. */
    private static Map<UUID, List<AfkCheckPacket>> catchCards() {
        Map<UUID, List<AfkCheckPacket>> sent = new HashMap<>();
        AfkService.sendChecksForTests((pl, packet) -> sent.computeIfAbsent(pl.getUUID(), k -> new ArrayList<>()).add(packet));
        return sent;
    }

    private static AfkCheckPacket last(Map<UUID, List<AfkCheckPacket>> sent, ServerPlayer p) {
        List<AfkCheckPacket> l = sent.getOrDefault(p.getUUID(), List.of());
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    @GameTest(template = EMPTY)
    public void afk_settings_keep_to_their_limits_are_shared_and_read_from_before_the_macros_switch(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        AfkSettings odd = new AfkSettings(true, 0, true, 999, "  §cgo away\n" + "x".repeat(300), false, 5).sanitised();
        h.assertTrue(odd.afkMinutes() == 1 && odd.kickMinutes() == 240 && odd.message().length() == AfkSettings.MAX_MESSAGE && !odd.message().contains("§"),
                "minutes and message kept to their limits: " + odd);
        h.assertTrue(new AfkSettings(false, 99, false, 1, "", true, 0).sanitised().equals(new AfkSettings(false, 60, false, 5, AfkSettings.DEFAULT_MESSAGE, true, 0)), "an empty message is the default one");
        h.assertTrue(AfkSettings.decode(odd.encode()).equals(odd) && AfkSettings.load(odd.save()).equals(odd), "encoded and saved, they round trip, the macros switch off included");
        h.assertTrue(AfkSettings.DEFAULTS.macros(), "repeated input does not count by default (D221)");

        AfkSettings old = AfkSettings.decode(String.join("\u001f", "true", "7", "true", "45", "Bye", "123"));
        h.assertTrue(old != null && old.macros() && old.afkMinutes() == 7 && old.kickMinutes() == 45 && old.revision() == 123, "a 0.6.0 encoding reads with the macros switch on: " + old);
        CompoundTag oldTag = odd.save();
        oldTag.remove("macros");
        h.assertTrue(AfkSettings.load(oldTag).macros(), "and so does a world saved before it");

        AfkSettings before = AfkData.get(server).settings();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            AfkSettings saved = AfkService.setSettings(server, new AfkSettings(true, 7, true, 45, "Bye for now", false, 0));
            alpha.drain();
            h.assertTrue(saved.revision() > before.revision() && db.loadAfkSettings().equals(saved), "saved for the network with a rising revision");
            h.assertTrue(db.fetchNoticesAfter(0, 20).stream().anyMatch(n -> n.kind().equals(AfkService.KIND_SETTINGS) && AfkSettings.decode(n.payload()).equals(saved)), "and announced");
            AfkService.apply(server, new AfkSettings(false, 3, false, 10, "old", true, saved.revision() - 1));
            h.assertTrue(AfkService.settings(server).equals(saved), "an older copy changes nothing");
            AfkSettings newer = new AfkSettings(false, 3, false, 10, "new", true, saved.revision() + 1);
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
        catchCards();
        try {
            AfkData.get(server).setSettings(new AfkSettings(true, 5, false, 30, AfkSettings.DEFAULT_MESSAGE, true, before.revision()));
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
            use(p);
            h.assertFalse(AfkService.isAfk(p.getUUID()), "using an item brings them back");

            command(server, p, "afk");
            server.getCommands().getDispatcher().execute("afk", p.createCommandSourceStack());
            h.assertTrue(AfkService.isAfk(p.getUUID()), "/afk marks them at once (D77)");
            command(server, p, "seed");
            h.assertFalse(AfkService.isAfk(p.getUUID()), "any other command brings them back");

            AfkData.get(server).setSettings(new AfkSettings(false, 5, false, 30, AfkSettings.DEFAULT_MESSAGE, true, before.revision()));
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
    public void at_the_kick_time_players_and_staff_are_checked_and_admins_never_and_silence_kicks_only_players(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer staffer = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer late = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        AtomicLong now = new AtomicLong(3_000_000_000L);
        List<UUID> kicked = new ArrayList<>();
        List<String> why = new ArrayList<>();
        AfkSettings before = AfkData.get(server).settings();
        List<ServerPlayer> online = new ArrayList<>(List.of(player, staffer, admin));
        AfkService.useForTests(now::get, () -> online, (pl, w) -> {
            kicked.add(pl.getUUID());
            why.add(w.getString());
            online.remove(pl);
        });
        AfkService.staffForTests(p -> p == staffer);
        Map<UUID, List<AfkCheckPacket>> cards = catchCards();
        try {
            AfkData.get(server).setSettings(settings(true, true, before.revision()));
            for (ServerPlayer p : online) {
                AfkService.onLogin(p);
            }
            now.addAndGet(5 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.isAfk(player.getUUID()) && AfkService.isAfk(staffer.getUUID()) && AfkService.isAfk(admin.getUUID()), "all AFK after five minutes");
            now.addAndGet(29 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.warned(player.getUUID()) && AfkService.warned(staffer.getUUID()) && cards.isEmpty(), "a minute before the kick time players and staff are warned of the check");
            h.assertFalse(AfkService.warned(admin.getUUID()), "an operator is never warned");
            now.addAndGet(MIN);
            AfkService.check(server);
            AfkService.CheckView pc = AfkService.checkFor(player.getUUID()), sc = AfkService.checkFor(staffer.getUUID());
            h.assertTrue(pc != null && pc.kick() && pc.msLeft() == AfkService.CHECK_MS && pc.keys().size() == 3 && pc.keys().contains(pc.word()),
                    "at 30 minutes AFK the player gets the check instead of a kick, 2 minutes, the word among three keys (D216): " + pc);
            h.assertTrue(pc.keys().stream().distinct().count() == 3 && pc.word() >= 0 && pc.word() < AfkService.WORDS, "three different words of the eight");
            AfkCheckPacket card = last(cards, player);
            h.assertTrue(card != null && card.id() == pc.id() && card.word() == pc.word() && card.keys().equals(pc.keys()) && !card.wrong(), "and is sent the card: " + card);
            h.assertTrue(sc != null && !sc.kick(), "staff are checked too, told they are not kicked (D218)");
            h.assertTrue(AfkService.checkFor(admin.getUUID()) == null && last(cards, admin) == null && kicked.isEmpty(), "admins and operators are never checked (D212)");

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            card.encode(buf);
            h.assertTrue(AfkCheckPacket.decode(buf).equals(card), "the card round trips");
            buf = new FriendlyByteBuf(Unpooled.buffer());
            new AfkAnswerPacket(1, 2, 3).encode(buf);
            h.assertTrue(AfkAnswerPacket.decode(buf).equals(new AfkAnswerPacket(1, 2, 3)), "and the answer");

            now.addAndGet(AfkService.CHECK_MS - 1000);
            AfkService.check(server);
            h.assertTrue(kicked.isEmpty() && AfkService.checkFor(player.getUUID()) != null, "1:59 into the check nobody is kicked");
            now.addAndGet(1000);
            AfkService.check(server);
            h.assertTrue(kicked.contains(player.getUUID()) && why.contains("Idle too long"), "no answer in 2 minutes kicks the player with the message");
            h.assertFalse(kicked.contains(staffer.getUUID()), "staff who do not answer are never kicked (D218)");
            h.assertTrue(AfkService.isAfk(staffer.getUUID()) && AfkService.checkFor(staffer.getUUID()) == null && last(cards, staffer).hide(), "they stay AFK and the card goes");
            List<LogEntry> log = ActionLog.recent(server, 20, false, List.of(LogEntry.AFK));
            String name = player.getGameProfile().getName(), staffName = staffer.getGameProfile().getName();
            h.assertTrue(log.stream().anyMatch(e -> e.text().equals(name + " did not answer the presence check: no input for 37 min") && e.actor().equals(AfkService.CHECKER)),
                    "the unanswered check is in the Action Log with what was seen (D213): " + log);
            h.assertTrue(log.stream().anyMatch(e -> e.text().startsWith(staffName + " did not answer the presence check") && e.text().endsWith("Left AFK: staff are never kicked")), "the staff member's too");
            h.assertFalse(LogEntry.PUBLIC_KINDS.contains(LogEntry.AFK), "for staff only");
            now.addAndGet(60 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.checkFor(staffer.getUUID()) == null && AfkService.isAfk(staffer.getUUID()), "staff left AFK are not checked again until they are back");

            // kicking turned on with someone already past the kick time (D83): checked at once
            AfkData.get(server).setSettings(settings(false, true, before.revision()));
            online.add(late);
            AfkService.onLogin(late);
            now.addAndGet(5 * MIN);
            AfkService.check(server);
            now.addAndGet(40 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.checkFor(late.getUUID()) == null && last(cards, late) == null && !AfkService.warned(late.getUUID()), "with kicking off nothing is sent (D219)");
            AfkData.get(server).setSettings(settings(true, true, before.revision()));
            AfkService.check(server);
            h.assertTrue(AfkService.checkFor(late.getUUID()) != null, "turned on, a player past the time is checked at once");
            AfkData.get(server).setSettings(settings(false, true, before.revision()));
            AfkService.check(server);
            h.assertTrue(AfkService.checkFor(late.getUUID()) == null && last(cards, late).hide() && !kicked.contains(late.getUUID()), "turned off mid-check, the card goes and nobody is kicked");
        } finally {
            for (ServerPlayer p : List.of(player, staffer, admin, late)) {
                AfkService.forget(p.getUUID());
            }
            AfkService.useReal();
            AfkData.get(server).setSettings(before);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_right_word_brings_a_player_back_and_a_wrong_one_gives_a_new_word(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        ServerPlayer other = TestPlayers.mockServerPlayer(h);
        AtomicLong now = new AtomicLong(3_500_000_000L);
        List<UUID> kicked = new ArrayList<>();
        AfkSettings before = AfkData.get(server).settings();
        AfkService.useForTests(now::get, () -> List.of(p, other), (pl, w) -> kicked.add(pl.getUUID()));
        Map<UUID, List<AfkCheckPacket>> cards = catchCards();
        try {
            AfkData.get(server).setSettings(settings(true, true, before.revision()));
            AfkService.onLogin(p);
            AfkService.onLogin(other);
            now.addAndGet(5 * MIN);
            AfkService.check(server);
            now.addAndGet(30 * MIN);
            AfkService.check(server);
            AfkService.CheckView c = AfkService.checkFor(p.getUUID());
            h.assertTrue(c != null, "checked at the kick time");
            int wrongWord = c.keys().stream().filter(k -> k != c.word()).findFirst().orElseThrow();
            h.assertFalse(AfkService.answer(other, c.id(), c.round(), c.word()), "another player cannot answer it");
            now.addAndGet(20_000);
            h.assertFalse(AfkService.answer(p, c.id(), c.round(), wrongWord), "a wrong key does not clear them");
            AfkService.CheckView c2 = AfkService.checkFor(p.getUUID());
            h.assertTrue(c2 != null && c2.id() == c.id() && c2.round() == c.round() + 1 && c2.word() != c.word() && c2.msLeft() == AfkService.CHECK_MS - 20_000,
                    "it gives a new word and order and the time keeps running (D223): " + c2);
            AfkCheckPacket card = last(cards, p);
            h.assertTrue(card.wrong() && card.round() == c2.round() && card.word() == c2.word(), "the card says so");
            h.assertFalse(AfkService.answer(p, c.id(), c.round(), c2.word()), "an answer to the old word is not taken");
            h.assertTrue(AfkService.isAfk(p.getUUID()) && AfkService.checkFor(p.getUUID()) != null, "still checking");
            now.addAndGet(30_000);
            h.assertTrue(AfkService.answer(p, c2.id(), c2.round(), c2.word()), "the right key is taken");
            h.assertTrue(!AfkService.isAfk(p.getUUID()) && AfkService.checkFor(p.getUUID()) == null && last(cards, p).hide() && kicked.isEmpty(), "they are back and the card goes");
            h.assertFalse(AfkService.answer(p, c2.id(), c2.round(), c2.word()), "and the check is over");

            // a check under way ends when the player is active by other means
            now.addAndGet(5 * MIN);
            AfkService.check(server);
            now.addAndGet(30 * MIN);
            AfkService.check(server);
            h.assertTrue(AfkService.checkFor(p.getUUID()) != null, "checked again at the next kick time");
            use(p);
            h.assertTrue(!AfkService.isAfk(p.getUUID()) && AfkService.checkFor(p.getUUID()) == null && last(cards, p).hide(), "using an item brings them back and the card goes");
        } finally {
            AfkService.forget(p.getUUID());
            AfkService.forget(other.getUUID());
            AfkService.useReal();
            AfkData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void an_auto_clicker_and_a_mouse_jiggle_stop_counting_after_ten_minutes_and_varied_input_does_not(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer clicker = TestPlayers.mockServerPlayer(h);
        ServerPlayer jiggler = TestPlayers.mockServerPlayer(h);
        ServerPlayer person = TestPlayers.mockServerPlayer(h);
        ServerPlayer walker = TestPlayers.mockServerPlayer(h);
        List<ServerPlayer> all = List.of(clicker, jiggler, person, walker);
        AtomicLong now = new AtomicLong(4_000_000_000L);
        AfkSettings before = AfkData.get(server).settings();
        AfkService.useForTests(now::get, () -> all, (pl, w) -> { });
        catchCards();
        try {
            AfkData.get(server).setSettings(settings(false, true, before.revision()));
            for (ServerPlayer p : all) {
                AfkService.onLogin(p);
            }
            clicker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
            walker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
            Random rnd = new Random(20261103L);
            float base = jiggler.getYRot();
            long nextPerson = 0, nextWalker = 0;
            double walk = 0.4;
            for (int q = 1; q <= 16 * 240; q++) { // quarter seconds, 16 minutes
                now.addAndGet(250);
                long t = q * 250L;
                if (q % 4 == 0) {
                    use(clicker); // the same use every 1.000 s, never moving
                    jiggler.setYRot(base + (q % 8 == 0 ? 2f : 0f)); // between two exact angles
                }
                if (t >= nextPerson) { // a person: varied gaps, a look around, a step now and then
                    nextPerson = t + 700 + rnd.nextInt(2200);
                    if (rnd.nextBoolean()) {
                        use(person);
                    } else {
                        person.setYRot(person.getYRot() + 5 + rnd.nextInt(40));
                        person.setXRot(rnd.nextInt(60) - 30);
                    }
                    if (rnd.nextInt(4) == 0) {
                        person.setPos(person.getX() + rnd.nextInt(5) - 2, person.getY(), person.getZ() + rnd.nextInt(5) - 2);
                    }
                }
                if (t >= nextWalker) { // the same use, but uneven, never at the same angle, walking up and down a long row
                    nextWalker = t + 600 + rnd.nextInt(1800);
                    walker.setYRot(walker.getYRot() + 0.01f + rnd.nextFloat() * 0.15f);
                    if (Math.abs(walker.getX() + walk - clicker.getX()) > 15) {
                        walk = -walk;
                    }
                    walker.setPos(walker.getX() + walk, walker.getY(), walker.getZ());
                    use(walker);
                }
                if (q % 4 == 0) {
                    AfkService.check(server);
                }
                if (q == 9 * 240) {
                    h.assertTrue(all.stream().noneMatch(p -> AfkService.suspected(p.getUUID()) || AfkService.isAfk(p.getUUID())), "nine minutes in, nobody is suspected or AFK");
                }
            }
            h.assertTrue(AfkService.suspected(clicker.getUUID()) && AfkService.isAfk(clicker.getUUID()),
                    "the auto-clicker's use stops counting after ten minutes, and five minutes later it is AFK (D209, D217)");
            h.assertTrue(AfkService.suspected(jiggler.getUUID()) && AfkService.isAfk(jiggler.getUUID()), "so does the mouse jiggle between two exact angles");
            h.assertTrue(!AfkService.suspected(person.getUUID()) && !AfkService.isAfk(person.getUUID()), "a person's varied input still counts");
            h.assertTrue(!AfkService.suspected(walker.getUUID()) && !AfkService.isAfk(walker.getUUID()),
                    "the same use alone is not a macro: it needs even timing, the same angles or one spot too (D208)");
            AfkService.Afk row = AfkService.localAfk(server).stream().filter(a -> a.id().equals(clicker.getUUID())).findFirst().orElseThrow();
            h.assertTrue(row.macro() && row.seen().equals("the same use of the fishing rod every 1.0 s at the same head angle at one spot, for 16 min"),
                    "what was seen, in words: " + row.seen());
            AfkService.Afk jig = AfkService.localAfk(server).stream().filter(a -> a.id().equals(jiggler.getUUID())).findFirst().orElseThrow();
            h.assertTrue(jig.seen().startsWith("the same head turns between 2 exact angles every 2.0 s at one spot"), "the jiggle in words: " + jig.seen());

            clicker.setYRot(clicker.getYRot() + 30);
            AfkService.check(server);
            h.assertFalse(AfkService.isAfk(clicker.getUUID()), "a real head turn still brings the clicker back: only the macro's own input does not count");
            AfkData.get(server).setSettings(settings(false, false, before.revision()));
            AfkService.check(server);
            use(jiggler);
            jiggler.setYRot(base + 2f);
            AfkService.check(server);
            h.assertTrue(!AfkService.suspected(jiggler.getUUID()) && !AfkService.isAfk(jiggler.getUUID()), "with the macros switch off, repeated input counts again");
        } finally {
            for (ServerPlayer p : all) {
                AfkService.forget(p.getUUID());
            }
            AfkService.useReal();
            AfkData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_correct_answer_clears_a_macro_for_an_hour_and_a_silent_one_is_logged_with_what_was_seen(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer present = TestPlayers.mockServerPlayer(h);
        ServerPlayer gone = TestPlayers.mockServerPlayer(h);
        List<ServerPlayer> online = new ArrayList<>(List.of(present, gone));
        AtomicLong now = new AtomicLong(5_000_000_000L);
        List<UUID> kicked = new ArrayList<>();
        AfkSettings before = AfkData.get(server).settings();
        AfkService.useForTests(now::get, () -> online, (pl, w) -> {
            kicked.add(pl.getUUID());
            online.remove(pl);
        });
        catchCards();
        try {
            AfkData.get(server).setSettings(settings(true, true, before.revision()));
            for (ServerPlayer p : List.copyOf(online)) {
                AfkService.onLogin(p);
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
            }
            long clearedAt = 0;
            for (int s = 1; s <= 120 * 60; s++) {
                now.addAndGet(1000);
                for (ServerPlayer p : List.copyOf(online)) {
                    use(p);
                }
                AfkService.check(server);
                AfkService.CheckView c = AfkService.checkFor(present.getUUID());
                if (c != null && clearedAt == 0) {
                    h.assertTrue(s == 45 * 60, "the macro is suspected at 10 min, AFK at 15 and checked at 45: " + s);
                    h.assertTrue(AfkService.answer(present, c.id(), c.round(), c.word()), "the person at the keyboard answers");
                    clearedAt = s;
                }
                if (clearedAt > 0 && s == clearedAt + 59 * 60) {
                    h.assertTrue(!AfkService.suspected(present.getUUID()) && !AfkService.isAfk(present.getUUID()), "for an hour after a correct answer their input counts (D214)");
                }
                if (clearedAt > 0 && s == clearedAt + 66 * 60) {
                    h.assertTrue(AfkService.suspected(present.getUUID()) && AfkService.isAfk(present.getUUID()), "after the hour the macro signs count again");
                    break;
                }
            }
            h.assertTrue(clearedAt > 0, "the check came");
            h.assertTrue(kicked.contains(gone.getUUID()) && !kicked.contains(present.getUUID()), "the macro with nobody at the keyboard was kicked at 47 min");
            String name = gone.getGameProfile().getName();
            LogEntry e = ActionLog.recent(server, 20, false, List.of(LogEntry.AFK)).stream().filter(x -> x.text().startsWith(name)).findFirst().orElse(null);
            h.assertTrue(e != null && e.text().equals(name + " did not answer the presence check: the same use of the bread every 1.0 s at the same head angle at one spot, for 47 min"),
                    "the Action Log says what was seen (D213): " + (e == null ? null : e.text()));
        } finally {
            AfkService.forget(present.getUUID());
            AfkService.forget(gone.getUUID());
            AfkService.useReal();
            AfkData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_macro_watch_needs_the_whole_window_and_most_input_from_three_keys(GameTestHelper h) {
        long t0 = 6_000_000_000L;
        MacroWatch w = new MacroWatch(t0);
        for (int s = 1; s <= 300; s++) {
            w.add(new MacroWatch.Input("attack:minecraft:zombie", t0 + s * 1000L, 10f, 5f, 0, 64, 0));
        }
        h.assertTrue(w.find(t0 + 300_000) == null, "five minutes watched is not enough");
        for (int s = 301; s <= 660; s++) {
            w.add(new MacroWatch.Input("attack:minecraft:zombie", t0 + s * 1000L, 10f, 5f, 0, 64, 0));
            w.add(new MacroWatch.Input("attack:minecraft:zombie", t0 + s * 1000L + 10, 10f, 5f, 0, 64, 0)); // the same click twice in a tick
        }
        MacroWatch.Pattern p = w.find(t0 + 660_000);
        h.assertTrue(p != null && p.even() && p.sameAngles() && p.oneSpot() && Math.abs(p.everyS() - 1.0) < 0.001, "ten minutes of the same click: " + p);
        h.assertTrue(p.seen(t0 + 660_000).equals("the same click on a zombie every 1.0 s at the same head angle at one spot, for 10 min"), p.seen(t0 + 660_000));
        for (int s = 661; s <= 720; s++) {
            w.add(new MacroWatch.Input("chat", t0 + s * 1000L - 500, 10f, 5f, 0, 64, 0));
            w.add(new MacroWatch.Input("cmd:spawn", t0 + s * 1000L - 400, 10f, 5f, 0, 64, 0));
            w.add(new MacroWatch.Input("toss:minecraft:dirt", t0 + s * 1000L - 300, 10f, 5f, 0, 64, 0));
            w.add(new MacroWatch.Input("place:minecraft:dirt", t0 + s * 1000L - 200, 10f, 5f, 0, 64, 0));
        }
        h.assertTrue(w.find(t0 + 720_000) == null, "with a fourth key the three commonest are under 90 %");
        h.assertTrue(MacroWatch.describe("entity:minecraft:iron_golem").equals("right click on an iron golem") && MacroWatch.describe("block:minecraft:lever").equals("right click on the lever"),
                "inputs in words");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_network_afk_list_reads_other_servers_until_their_list_is_old(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong now = new AtomicLong(7_000_000_000L);
        AfkService.useForTests(now::get, List::of, null);
        try {
            UUID pip = UUID.randomUUID(), kat = UUID.randomUUID(), old = UUID.randomUUID();
            String payload = AfkService.encode("afktest-beta", List.of(new AfkService.Afk(pip, "Pip", 12 * MIN, false, false, 0, ""),
                    new AfkService.Afk(kat, "Kat", 31 * MIN, false, true, 100_000, "the same click on a zombie every 1.0 s at one spot, for 18 min")));
            Map.Entry<String, List<AfkService.Afk>> back = AfkService.decode(payload);
            h.assertTrue(back != null && back.getKey().equals("afktest-beta") && back.getValue().get(0).name().equals("Pip") && back.getValue().get(1).seen().endsWith("for 18 min"),
                    "the notice round trips, with what was seen");
            AfkService.applyPresence(payload);
            AfkService.applyPresence("afktest-gamma\n" + old + "|Old|60000|1");
            now.addAndGet(30_000);
            List<AfkService.Row> rows = AfkService.afkNow(server);
            AfkService.Row row = rows.stream().filter(r -> r.id().equals(pip)).findFirst().orElse(null);
            h.assertTrue(row != null && row.server().equals("afktest-beta") && row.afkForMs() == 12 * MIN + 30_000, "another server's AFK player, their time still counting: " + row);
            AfkService.Row kr = rows.stream().filter(r -> r.id().equals(kat)).findFirst().orElse(null);
            h.assertTrue(kr != null && kr.macro() && kr.checkLeftMs() == 70_000, "a macro under a check, its time running down: " + kr);
            AfkService.Row or = rows.stream().filter(r -> r.id().equals(old)).findFirst().orElse(null);
            h.assertTrue(or != null && or.exempt() && !or.macro() && or.checkLeftMs() == 0, "a list from a server before the check still reads: " + or);
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
        AtomicLong now = new AtomicLong(8_000_000_000L);
        AfkSettings before = AfkData.get(server).settings();
        Staff.useForTests(List.of(), true);
        StaffDirectory.useForTests(now::get, () -> List.of(admin));
        AfkService.useForTests(now::get, () -> List.of(player, admin), (pl, w) -> { });
        catchCards();
        StaffDirectory.setAutoAway(AfkService::isAfk);
        try {
            AdminAfkEditPacket edit = new AdminAfkEditPacket(false, new AfkSettings(true, 8, true, 45, "See you later", false, 0));
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            edit.encode(buf);
            h.assertTrue(AdminAfkEditPacket.decode(buf).equals(edit), "an edit round trips");
            h.assertFalse(AdminPanel.applyAfk(player, edit), "a player who may not edit changes nothing");
            h.assertTrue(AfkService.settings(server).equals(before), "the settings are unchanged");
            h.assertTrue(AdminPanel.applyAfk(admin, edit), "an admin's edit is taken");
            AfkSettings now1 = AfkService.settings(server);
            h.assertTrue(now1.afkMinutes() == 8 && now1.kick() && now1.kickMinutes() == 45 && now1.message().equals("See you later") && !now1.macros() && now1.revision() > before.revision(),
                    "and is the network's settings, the macros switch included: " + now1);

            AfkService.onLogin(player);
            AfkService.onLogin(admin);
            AfkService.markAfk(player);
            StaffDirectory.toggleAway(admin);
            now.addAndGet(3 * MIN);
            AdminAfkPacket tab = AdminPanel.afkPacket(server);
            AdminAfkPacket.Row afkRow = tab.rows().stream().filter(r -> r.name().equals(player.getGameProfile().getName())).findFirst().orElse(null);
            h.assertTrue(afkRow != null && afkRow.afkForMs() == 3 * MIN && !afkRow.exempt() && !afkRow.typedAway() && !afkRow.macro() && afkRow.checkLeftMs() == 0,
                    "an AFK player, how long, can be kicked, no macro, no check: " + afkRow);
            AdminAfkPacket.Row awayRow = tab.rows().stream().filter(r -> r.name().equals(admin.getGameProfile().getName())).findFirst().orElse(null);
            h.assertTrue(awayRow != null && awayRow.typedAway() && awayRow.exempt() && awayRow.colour() == Staff.OPERATOR_COLOUR, "staff away by /su away, marked so (D85): " + awayRow);
            now.addAndGet(42 * MIN);
            AfkService.check(server);
            tab = AdminPanel.afkPacket(server);
            afkRow = tab.rows().stream().filter(r -> r.name().equals(player.getGameProfile().getName())).findFirst().orElse(null);
            h.assertTrue(afkRow != null && afkRow.checkLeftMs() == AfkService.CHECK_MS, "a check under way shows its time left: " + afkRow);
            buf = new FriendlyByteBuf(Unpooled.buffer());
            tab.encode(buf);
            h.assertTrue(AdminAfkPacket.decode(buf).equals(tab), "the tab's data round trips");
            AdminAfkPacket macro = new AdminAfkPacket(now1, List.of(new AdminAfkPacket.Row("Juniper", "BSP-Stormfall", 29 * MIN, false, 0x9AA3B5, false, true, 102_000,
                    "the same click on a zombie every 1.0 s at one spot, for 18 min")));
            buf = new FriendlyByteBuf(Unpooled.buffer());
            macro.encode(buf);
            h.assertTrue(AdminAfkPacket.decode(buf).equals(macro), "a macro row and what was seen round trip (D224)");
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
