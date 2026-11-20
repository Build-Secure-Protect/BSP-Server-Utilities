package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.support.NetworkJoins;
import com.mrgregles.bsp_server_utilities.support.SupportChat;
import com.mrgregles.bsp_server_utilities.support.SupportService;
import com.mrgregles.bsp_server_utilities.support.SupportSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Support BSP chat line on joining the network (SU-90; owner decisions D597 to D601): sent 5 seconds after a join,
 * in gold with the address underlined and clickable, {@code {link}} placing it; never on a move between servers (still
 * listed on another server at the login, or a leaving from 60 s before the login up to when the line is due, told by
 * the other server's notice); a quick reconnect gets none; a player at the rules gets it 5 seconds after they are let
 * through; nothing without a sentence or an address, or when the player left first. A real move through Velocity and
 * the line's look in chat need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class SupportJoinGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String SHOP = "https://store.example.net/bsp";
    private static final long T0 = 1_800_000_000_000L;

    /** A test clock, the lines sent, who another server lists and who is at the rules; put back with {@link #close}. */
    private static final class Rig {
        final MinecraftServer server;
        final SupportSettings was;
        final AtomicLong clock = new AtomicLong(T0);
        final List<Component> lines = new ArrayList<>();
        final Set<UUID> elsewhere = new HashSet<>(), atRules = new HashSet<>();
        final List<ServerPlayer> players = new ArrayList<>();

        Rig(GameTestHelper h, String sentence, String link, String chatLink) {
            server = h.getLevel().getServer();
            was = SupportService.settings(server);
            Storage.useForTests(null);
            NetworkJoins.useForTests(clock::get, elsewhere::contains);
            SupportChat.useForTests((p, line) -> lines.add(line), atRules::contains);
            SupportService.useForTests(clock::get, p -> {
            });
            SupportService.restoreForTests(server, new SupportSettings(link, sentence, chatLink, "Console", T0, 1).sanitised());
        }

        ServerPlayer player(GameTestHelper h) {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            players.add(p);
            NetworkJoins.forget(p.getUUID());
            return p;
        }

        /** Moves the clock to T0 + ms and runs a tick. */
        void at(long ms) {
            clock.set(T0 + ms);
            SupportChat.tick(server);
        }

        List<String> texts() {
            return lines.stream().map(Component::getString).toList();
        }

        void close() {
            for (ServerPlayer p : players) {
                SupportChat.onLogout(p);
                NetworkJoins.forget(p.getUUID());
            }
            Storage.useForTests(null);
            NetworkJoins.useReal();
            SupportChat.useReal();
            SupportService.useReal();
            SupportService.restoreForTests(server, was);
        }
    }

    @GameTest(template = EMPTY)
    public void a_join_to_the_network_gets_the_support_line_after_five_seconds(GameTestHelper h) {
        Rig rig = new Rig(h, "Enjoying BSP? Help keep it running: {link} Thank you!", SHOP, "");
        try {
            ServerPlayer kat = rig.player(h);
            rig.at(0);
            SupportChat.onLogin(kat);
            rig.at(4_999);
            h.assertTrue(rig.lines.isEmpty() && SupportChat.waiting(kat.getUUID()), "nothing before 5 seconds (D598)");
            rig.at(5_000);
            h.assertTrue(rig.texts().equals(List.of("Enjoying BSP? Help keep it running: " + SHOP + " Thank you!")), "the line at 5 seconds, the address where {link} is (D597): " + rig.texts());
            List<Component> parts = rig.lines.get(0).getSiblings();
            h.assertTrue(parts.size() == 3, "sentence, address, sentence: " + parts);
            Style words = parts.get(0).getStyle(), address = parts.get(1).getStyle();
            h.assertTrue(words.getColor() != null && words.getColor().getValue() == 0xFFAA00 && words.getClickEvent() == null, "the words in gold, not clickable");
            h.assertTrue(address.isUnderlined() && address.getColor() != null && address.getColor().getValue() == ChatFormatting.AQUA.getColor()
                    && address.getClickEvent() != null && address.getClickEvent().getAction() == ClickEvent.Action.OPEN_URL && address.getClickEvent().getValue().equals(SHOP),
                    "the address underlined and opening the link through Minecraft's own prompt (D597)");
            rig.at(60_000);
            h.assertTrue(rig.lines.size() == 1 && !SupportChat.waiting(kat.getUUID()), "once per join");
            // a staff member gets it too (D600): nothing in the rule looks at roles
        } finally {
            rig.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_move_between_servers_gets_no_support_line(GameTestHelper h) throws Exception {
        Rig rig = new Rig(h, "Help keep BSP running:", SHOP, "");
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(rig.server, db, "alpha"), beta = Storage.startWith(rig.server, db, "beta");
        alpha.drain();
        beta.drain();
        try {
            // still listed on another server at the login (Velocity's order)
            ServerPlayer kat = rig.player(h);
            rig.elsewhere.add(kat.getUUID());
            rig.at(0);
            SupportChat.onLogin(kat);
            rig.elsewhere.remove(kat.getUUID());
            rig.at(5_000);
            h.assertTrue(rig.lines.isEmpty() && !SupportChat.waiting(kat.getUUID()), "a player another server still lists at the login moved: no line (D601)");
            // the other server's leaving arrives a moment after the login, through its notice
            ServerPlayer sam = rig.player(h);
            rig.at(10_000);
            SupportChat.onLogin(sam);
            rig.at(10_800);
            Storage.useForTests(alpha);
            NetworkJoins.onLogout(sam, false); // alpha lets go of them (their line long sent there) and tells the network
            alpha.drain();
            NetworkJoins.forget(sam.getUUID()); // so only the notice can tell beta
            Storage.useForTests(beta);
            beta.pollNotices();
            beta.drain();
            Storage.useForTests(null);
            rig.at(15_000);
            h.assertTrue(rig.lines.isEmpty(), "a leaving told by another server's notice after the login: a move, no line: " + rig.texts());
            // left another server 30 s before the login
            ServerPlayer ann = rig.player(h);
            rig.at(20_000);
            NetworkJoins.left(ann.getUUID(), false);
            rig.at(50_000);
            SupportChat.onLogin(ann);
            rig.at(55_000);
            h.assertTrue(rig.lines.isEmpty(), "left 30 s before: a move");
            // left 61 s before the login: a join
            ServerPlayer bo = rig.player(h);
            rig.at(60_000);
            NetworkJoins.left(bo.getUUID(), false);
            rig.at(121_000);
            SupportChat.onLogin(bo);
            rig.at(126_000);
            h.assertTrue(rig.texts().equals(List.of("Help keep BSP running: " + SHOP)), "left 61 s before: a join, the line; without {link} the address follows the sentence: " + rig.texts());
        } finally {
            alpha.close();
            beta.close();
            rig.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_quick_reconnect_gets_no_second_support_line(GameTestHelper h) {
        Rig rig = new Rig(h, "Support us: {link}", SHOP, "");
        try {
            ServerPlayer kat = rig.player(h);
            rig.at(0);
            SupportChat.onLogin(kat);
            rig.at(5_000);
            h.assertTrue(rig.lines.size() == 1, "the join's line");
            rig.at(20_000);
            SupportChat.onLogout(kat);
            rig.at(40_000);
            SupportChat.onLogin(kat);
            rig.at(45_000);
            h.assertTrue(rig.lines.size() == 1, "back on the same server 20 s later: a quick reconnect, no second line (D601)");
            SupportChat.onLogout(kat);
            rig.at(200_000);
            SupportChat.onLogin(kat);
            rig.at(205_000);
            h.assertTrue(rig.lines.size() == 2, "back after more than a minute: a join, the line");
        } finally {
            rig.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_player_who_leaves_before_the_line_gets_it_on_the_next_server(GameTestHelper h) throws Exception {
        Rig rig = new Rig(h, "Support us: {link}", SHOP, "");
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(rig.server, db, "alpha"), beta = Storage.startWith(rig.server, db, "beta");
        alpha.drain();
        beta.drain();
        try {
            // joins the network on alpha and moves on to beta after 3 s, before the line
            ServerPlayer kat = rig.player(h);
            rig.at(0);
            Storage.useForTests(alpha);
            SupportChat.onLogin(kat);
            rig.at(3_000);
            SupportChat.onLogout(kat);
            alpha.drain();
            NetworkJoins.forget(kat.getUUID()); // only alpha's notice can tell beta
            Storage.useForTests(beta);
            beta.pollNotices();
            beta.drain();
            rig.elsewhere.add(kat.getUUID()); // and alpha still lists them as beta's login comes
            rig.at(3_200);
            SupportChat.onLogin(kat);
            rig.elsewhere.remove(kat.getUUID());
            rig.at(8_199);
            h.assertTrue(rig.lines.isEmpty(), "not before 5 s on the new server");
            rig.at(8_200);
            h.assertTrue(rig.lines.size() == 1, "a join followed at once by a move: the line on the server they reached (D599)");
            // held by the rules on one server, they move on: owed there too
            ServerPlayer sam = rig.player(h);
            rig.atRules.add(sam.getUUID());
            Storage.useForTests(null);
            rig.at(10_000);
            SupportChat.onLogin(sam);
            rig.at(30_000);
            SupportChat.onLogout(sam);
            rig.atRules.remove(sam.getUUID());
            rig.at(31_000);
            SupportChat.onLogin(sam);
            rig.at(36_000);
            h.assertTrue(rig.lines.size() == 2, "moved while still at the rules: the line 5 s after arriving");
            // the owed line is sent once: leaving again after it came owes nothing
            rig.at(40_000);
            SupportChat.onLogout(sam);
            rig.at(41_000);
            SupportChat.onLogin(sam);
            rig.at(46_000);
            h.assertTrue(rig.lines.size() == 2, "after the line came, a move owes nothing");
        } finally {
            alpha.close();
            beta.close();
            rig.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void without_redis_the_support_line_waits_for_late_notices(GameTestHelper h) {
        Rig rig = new Rig(h, "Support us: {link}", SHOP, "");
        try {
            SupportChat.useLagForTests(7_000);
            ServerPlayer moved = rig.player(h), joined = rig.player(h);
            rig.at(0);
            SupportChat.onLogin(moved);
            SupportChat.onLogin(joined);
            rig.at(5_000);
            h.assertTrue(rig.lines.isEmpty(), "with notices up to 7 s late, nothing at 5 s");
            rig.at(9_000);
            NetworkJoins.left(moved.getUUID(), false); // the old server's leaving, read from the table 9 s after the login
            rig.at(11_999);
            h.assertTrue(rig.lines.isEmpty(), "still waiting");
            rig.at(12_000);
            h.assertTrue(rig.lines.size() == 1, "at 5 + 7 s: the join gets its line, the move (known by then) none: " + rig.texts());
        } finally {
            rig.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_player_at_the_rules_gets_the_support_line_after_agreeing(GameTestHelper h) {
        Rig rig = new Rig(h, "Support us: {link}", SHOP, "");
        try {
            ServerPlayer kat = rig.player(h);
            rig.atRules.add(kat.getUUID());
            rig.at(0);
            SupportChat.onLogin(kat);
            rig.at(5_000);
            rig.at(19_000);
            h.assertTrue(rig.lines.isEmpty() && SupportChat.waiting(kat.getUUID()), "nothing while held by the rules");
            rig.atRules.remove(kat.getUUID()); // AGREE at 19 s
            rig.at(23_999);
            h.assertTrue(rig.lines.isEmpty(), "not yet 5 seconds after AGREE");
            rig.at(24_000);
            h.assertTrue(rig.lines.size() == 1, "5 seconds after AGREE (D598)");
        } finally {
            rig.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_support_line_needs_a_sentence_and_an_address(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        h.assertTrue(SupportChat.line(new SupportSettings(SHOP, "", "", "", 0, 1)) == null, "no sentence: no line (D612)");
        h.assertTrue(SupportChat.line(new SupportSettings("", "Help us", "", "", 0, 1)) == null, "no address at all: no line");
        Component own = SupportChat.line(new SupportSettings("", "Help us {link} and {link}", "https://other.example.net", "", 0, 1));
        h.assertTrue(own != null && own.getString().equals("Help us https://other.example.net and https://other.example.net"),
                "the chat line's own link, without a support link; every {link} filled: " + (own == null ? null : own.getString()));
        Component start = SupportChat.line(new SupportSettings(SHOP, "{link}", "", "", 0, 1));
        h.assertTrue(start != null && start.getString().equals(SHOP) && start.getSiblings().size() == 1, "only the address");
        Rig rig = new Rig(h, "", SHOP, "");
        try {
            ServerPlayer kat = rig.player(h);
            rig.at(0);
            SupportChat.onLogin(kat);
            rig.at(5_000);
            h.assertTrue(rig.lines.isEmpty() && !SupportChat.waiting(kat.getUUID()), "a join with no sentence set sends nothing");
        } finally {
            rig.close();
        }
        h.assertTrue(server != null, "server");
        h.succeed();
    }
}
