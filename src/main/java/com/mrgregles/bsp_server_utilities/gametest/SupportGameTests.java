package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.network.SupportLinkPacket;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.support.SupportLinks;
import com.mrgregles.bsp_server_utilities.support.SupportLinks.Problem;
import com.mrgregles.bsp_server_utilities.support.SupportService;
import com.mrgregles.bsp_server_utilities.support.SupportService.Outcome;
import com.mrgregles.bsp_server_utilities.support.SupportSettings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Support BSP's settings, server side (SU-89; owner decisions D596, D597, D602 to D606, D612): the link check, who may
 * change the settings, a refused link saving nothing, the Action Log line, the copy sent to every client when the link
 * changes, CLEAR, one set for the network with the newest revision winning, and {@code /su support} from the console.
 * The inventory key, the popup and the SUPPORT tab need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class SupportGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String SHOP = "https://store.example.net/bsp";

    private static void restore(MinecraftServer server, SupportSettings was) {
        Storage.useForTests(null);
        SupportService.useReal();
        SupportService.restoreForTests(server, was);
    }

    private static LogEntry lastCommand(MinecraftServer server) {
        return ActionLog.recent(server, 1, false, List.of(LogEntry.CMD)).get(0);
    }

    @GameTest(template = EMPTY)
    public void support_links_are_checked(GameTestHelper h) {
        Map<String, Problem> cases = new java.util.LinkedHashMap<>();
        cases.put("", Problem.OK);
        cases.put("   ", Problem.OK);
        cases.put(SHOP, Problem.OK);
        cases.put("  " + SHOP + "  ", Problem.OK);
        cases.put("HTTPS://Store.Example.NET/bsp?ref=game#top", Problem.OK);
        cases.put("https://shop-1.example.co.uk/", Problem.OK);
        cases.put("https://store.example.net:8443/x", Problem.OK);
        cases.put("http://store.example.net/bsp", Problem.HTTPS);
        cases.put("store.example.net/bsp", Problem.HTTPS);
        cases.put("javascript:alert(1)", Problem.HTTPS);
        cases.put("ftp://store.example.net", Problem.HTTPS);
        cases.put("https://store example.net/bsp", Problem.SPACES);
        cases.put("https://store.example.net/b sp", Problem.SPACES);
        cases.put("https://store.example.net/\tbsp", Problem.SPACES);
        cases.put("https://user:secret@store.example.net/bsp", Problem.USER);
        cases.put("https://user@store.example.net", Problem.USER);
        cases.put("https://", Problem.NOT_ADDRESS);
        cases.put("https://localhost/bsp", Problem.NOT_ADDRESS);
        cases.put("https://.example.net", Problem.NOT_ADDRESS);
        cases.put("https://example.net./x", Problem.NOT_ADDRESS);
        cases.put("https://exa_mple.net", Problem.NOT_ADDRESS);
        cases.put("https://-bad.example.net", Problem.NOT_ADDRESS);
        cases.put("https://store.example.net/§cred", Problem.NOT_ADDRESS);
        cases.put("https://store.example.net/<script>", Problem.NOT_ADDRESS);
        cases.put("https://store.example.net/\u202Egpj.exe", Problem.NOT_ADDRESS);
        cases.put("https://store.example.net/a\u200Bb", Problem.NOT_ADDRESS);
        cases.put("https://" + "a".repeat(240) + ".example.net", Problem.TOO_LONG);
        List<String> wrong = new ArrayList<>();
        cases.forEach((typed, want) -> {
            Problem got = SupportLinks.check(typed);
            if (got != want) {
                wrong.add("\"" + typed + "\" " + got + " (wanted " + want + ")");
            }
        });
        h.assertTrue(wrong.isEmpty(), "https only, a host with a dot, no spaces, no name and password, at most 256 (D603): " + wrong);
        String longest = "https://store.example.net/" + "a".repeat(SupportLinks.MAX - 26);
        h.assertTrue(longest.length() == SupportLinks.MAX && SupportLinks.check(longest) == Problem.OK && SupportLinks.check(longest + "a") == Problem.TOO_LONG,
                "exactly 256 characters is allowed, 257 is not");
        h.assertTrue(!SupportLinks.usable("") && SupportLinks.usable(SHOP) && !SupportLinks.usable("http://x.net"), "usable: allowed and not empty");
        h.assertTrue(SupportSettings.cleanSentence("Help \u200Bus\u202E now").equals("Help us now"), "zero-width and direction marks are taken out of the sentence (audit of 0.15.0)");
        for (Problem p : Problem.values()) {
            h.assertTrue(p.langKey().startsWith("support.link."), "every reason has a lang line: " + p);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void only_operators_and_admins_change_support_and_each_change_is_logged(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SupportSettings was = SupportService.settings(server);
        List<SupportLinkPacket> sent = new ArrayList<>();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        try {
            Storage.useForTests(null);
            SupportService.useForTests(() -> 1_700_000_000_000L, sent::add);
            SupportService.restoreForTests(server, SupportSettings.NONE);
            SupportService.Result r = SupportService.edit(server, player, "", SHOP, "Help keep BSP running: {link}", "");
            h.assertTrue(r.outcome() == Outcome.NOT_ALLOWED && !SupportService.settings(server).hasLink() && sent.isEmpty(),
                    "a player who is not an operator or a BSP-Core admin changes nothing (D602): " + r);
            h.assertTrue(SupportService.clear(server, player, "").outcome() == Outcome.NOT_ALLOWED, "nor clears it");
            server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 2, false));
            String name = admin.getGameProfile().getName();
            r = SupportService.edit(server, admin, "", "  " + SHOP + " ", "Help keep §6BSP running: {link}", "");
            SupportSettings s = SupportService.settings(server);
            h.assertTrue(r.saved() && s.link().equals(SHOP) && s.sentence().equals("Help keep  6BSP running: {link}") && s.chatLink().isEmpty(),
                    "an operator saves all three; the link tidied, formatting codes not read (D612): " + s);
            h.assertTrue(s.by().equals(name) && s.atMs() == 1_700_000_000_000L && s.revision() >= 1_700_000_000_000L, "who and when are kept for \"Last change\": " + s);
            h.assertTrue(s.chatAddress().equals(SHOP) && s.hasChatLine(), "an empty chat link uses the support link (D596)");
            h.assertTrue(sent.size() == 1 && sent.get(0).link().equals(SHOP), "every client is sent the new link: " + sent);
            LogEntry e = lastCommand(server);
            h.assertTrue(e.actor().equals(name) && e.text().equals("set the support link to " + SHOP + "; set the support chat line to \"Help keep  6BSP running: {link}\""),
                    "the change is in the Action Log: " + e);
            r = SupportService.edit(server, admin, "", SHOP, s.sentence(), "");
            h.assertTrue(r.outcome() == Outcome.UNCHANGED && SupportService.settings(server).revision() == s.revision() && sent.size() == 1 && lastCommand(server).id() == e.id(),
                    "saving the same again changes nothing, logs nothing, sends nothing");
            r = SupportService.editSentence(server, admin, "", "x".repeat(250));
            h.assertTrue(r.saved() && SupportService.settings(server).sentence().length() == SupportSettings.MAX_SENTENCE && sent.size() == 1,
                    "the sentence is cut to 200 characters; the link unchanged sends nothing");
            r = SupportService.editSentence(server, admin, "", "");
            h.assertTrue(r.saved() && !SupportService.settings(server).hasChatLine() && SupportService.settings(server).hasLink(), "an empty sentence: no chat line, the key stays (D612)");
            h.assertTrue(lastCommand(server).text().equals("removed the support chat line"), "logged: " + lastCommand(server).text());
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            restore(server, was);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_refused_support_link_saves_nothing(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SupportSettings was = SupportService.settings(server);
        List<SupportLinkPacket> sent = new ArrayList<>();
        try {
            Storage.useForTests(null);
            SupportService.useForTests(System::currentTimeMillis, sent::add);
            SupportService.restoreForTests(server, SupportSettings.NONE);
            SupportService.Result r = SupportService.edit(server, null, "Console", SHOP, "Thank you", "");
            SupportSettings before = r.settings();
            h.assertTrue(r.saved(), "a first save");
            r = SupportService.edit(server, null, "Console", "http://store.example.net", "Other words", "");
            h.assertTrue(r.outcome() == Outcome.BAD_LINK && r.problem() == Problem.HTTPS && SupportService.settings(server).equals(before) && sent.size() == 1,
                    "an http support link is refused with its reason and nothing is saved, the sentence neither (D603): " + r);
            r = SupportService.edit(server, null, "Console", SHOP, "Other words", "https://a@b.example.net");
            h.assertTrue(r.outcome() == Outcome.BAD_CHAT_LINK && r.problem() == Problem.USER && SupportService.settings(server).equals(before),
                    "a refused chat link refuses the whole save: " + r);
            h.assertTrue(SupportSettings.decode(new SupportSettings("http://bad.example.net", "Hi", "https://x y", "Console", 1, 2).encode()).link().isEmpty()
                    && SupportSettings.decode(new SupportSettings("http://bad.example.net", "Hi", "https://x y", "Console", 1, 2).encode()).chatLink().isEmpty(),
                    "a stored link that would be refused is never taken from the shared copy");
        } finally {
            restore(server, was);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void clear_empties_support_and_hides_the_key(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SupportSettings was = SupportService.settings(server);
        List<SupportLinkPacket> sent = new ArrayList<>();
        try {
            Storage.useForTests(null);
            SupportService.useForTests(System::currentTimeMillis, sent::add);
            SupportService.restoreForTests(server, SupportSettings.NONE);
            SupportService.edit(server, null, "Console", SHOP, "Help: {link}", "https://other.example.net");
            SupportService.Result r = SupportService.clear(server, null, "Console");
            SupportSettings s = SupportService.settings(server);
            h.assertTrue(r.saved() && !s.hasLink() && !s.hasChatLine() && s.sentence().isEmpty() && s.chatLink().isEmpty(), "CLEAR empties all three (D612): " + s);
            h.assertTrue(sent.size() == 2 && sent.get(1).link().isEmpty(), "every client is told there is no link, so the key hides: " + sent);
            h.assertTrue(lastCommand(server).text().equals("cleared the support link and the support chat line"), "logged: " + lastCommand(server).text());
            h.assertTrue(SupportService.clear(server, null, "Console").outcome() == Outcome.UNCHANGED, "a second CLEAR changes nothing");
        } finally {
            restore(server, was);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void support_is_one_setting_for_the_network_and_the_newest_wins(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        SupportSettings was = SupportService.settings(server);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha"), beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        List<SupportLinkPacket> sent = new ArrayList<>();
        try {
            SupportService.useForTests(System::currentTimeMillis, sent::add);
            SupportService.restoreForTests(server, SupportSettings.NONE);
            Storage.useForTests(alpha);
            SupportService.onServerStarted(server);
            alpha.drain();
            h.assertTrue(db.loadSupportSettings() != null && !db.loadSupportSettings().hasLink(), "an empty table gets this server's settings at start");
            SupportService.Result r = SupportService.edit(server, null, "Console", SHOP, "Help: {link}", "");
            alpha.drain();
            SupportSettings saved = r.settings();
            h.assertTrue(saved.equals(db.loadSupportSettings()), "the save is written to the network's table: " + db.loadSupportSettings());
            // the other server, with an older copy, hears the notice
            SupportService.restoreForTests(server, SupportSettings.NONE);
            sent.clear();
            Storage.useForTests(beta);
            beta.pollNotices();
            beta.drain();
            h.assertTrue(SupportService.settings(server).equals(saved) && sent.size() == 1 && sent.get(0).link().equals(SHOP),
                    "another server takes the newer settings and tells its clients (D604): " + SupportService.settings(server));
            // an older copy never replaces a newer one
            SupportService.apply(server, new SupportSettings("https://old.example.net", "Old", "", "Console", 1, saved.revision() - 1));
            h.assertTrue(SupportService.settings(server).equals(saved), "an older revision is ignored");
            db.saveSupportSettings(new SupportSettings("https://old.example.net", "Old", "", "Console", 1, saved.revision() - 1));
            h.assertTrue(db.loadSupportSettings().equals(saved), "nor does a late older write replace the table's row");
            // a server starting with an older copy takes the table's (the worker's answer runs on the server thread at once here)
            SupportService.restoreForTests(server, SupportSettings.NONE);
            SupportService.onServerStarted(server);
            beta.drain();
            h.assertTrue(SupportService.settings(server).equals(saved), "a starting server with an older copy takes the table's: " + SupportService.settings(server));
            // a server whose copy is newer than the table's (a save that reached the servers but not the table) puts it back
            SupportSettings newer = new SupportSettings("https://newer.example.net", "Newer", "", "Console", 2, saved.revision() + 10);
            SupportService.restoreForTests(server, newer);
            Storage.useForTests(alpha);
            SupportService.onServerStarted(server);
            alpha.drain();
            h.assertTrue(newer.equals(db.loadSupportSettings()), "a newer copy than the table's is written back at start (audit): " + db.loadSupportSettings());
            SupportService.restoreForTests(server, SupportSettings.NONE);
            Storage.useForTests(beta);
            beta.pollNotices();
            beta.drain();
            h.assertTrue(SupportService.settings(server).equals(newer), "and the other servers are told: " + SupportService.settings(server));
            // a save the table refused is tried again until it lands (audit of 0.15.0)
            Storage.useForTests(alpha);
            db.setFailing(true);
            SupportService.Result retry = SupportService.edit(server, null, "Console", "https://retry.example.net", "Again", "");
            alpha.drain();
            db.setFailing(false);
            h.assertTrue(retry.saved() && SupportService.unwritten() && !"https://retry.example.net".equals(db.loadSupportSettings().link()),
                    "the table was down: the save is kept to write again");
            SupportService.tick(server);
            alpha.drain();
            h.assertTrue(!SupportService.unwritten() && db.loadSupportSettings().link().equals("https://retry.example.net"), "and lands when the table is back: " + db.loadSupportSettings());
        } finally {
            alpha.close();
            beta.close();
            restore(server, was);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void su_support_from_the_console(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SupportSettings was = SupportService.settings(server);
        try {
            Storage.useForTests(null);
            SupportService.useForTests(System::currentTimeMillis, p -> {
            });
            SupportService.restoreForTests(server, SupportSettings.NONE);
            var console = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(console, "su support link " + SHOP);
            server.getCommands().performPrefixedCommand(console, "su support message Enjoying BSP? Help keep it running: {link}");
            server.getCommands().performPrefixedCommand(console, "su support chatlink https://other.example.net/x");
            SupportSettings s = SupportService.settings(server);
            h.assertTrue(s.link().equals(SHOP) && s.sentence().equals("Enjoying BSP? Help keep it running: {link}") && s.chatLink().equals("https://other.example.net/x"),
                    "link, message and chatlink each set one thing (D606): " + s);
            h.assertTrue(lastCommand(server).actor().equals("Console"), "logged under the console");
            server.getCommands().performPrefixedCommand(console, "su support link http://store.example.net");
            h.assertTrue(SupportService.settings(server).link().equals(SHOP), "a refused link from the console saves nothing");
            server.getCommands().performPrefixedCommand(console, "su support chatlink none");
            h.assertTrue(SupportService.settings(server).chatLink().isEmpty() && SupportService.settings(server).chatAddress().equals(SHOP), "none empties the chat link");
            server.getCommands().performPrefixedCommand(console, "su support clear");
            h.assertTrue(!SupportService.settings(server).hasLink() && SupportService.settings(server).sentence().isEmpty(), "clear empties all three");
            h.assertTrue(LogText.selfLogged("su support link " + SHOP) && LogText.selfLogged("/serverutils support clear") && LogText.selfLogged("su support message hi")
                    && LogText.selfLogged("su support chatlink none") && !LogText.selfLogged("su support"), "the changes are not logged twice (their own entry instead)");
        } finally {
            restore(server, was);
        }
        h.succeed();
    }
}
