package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.check.CheckData;
import com.mrgregles.bsp_server_utilities.check.CheckRules;
import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.check.ClientReport;
import com.mrgregles.bsp_server_utilities.check.ClientsAdmin;
import com.mrgregles.bsp_server_utilities.check.TrustedSetup;
import com.mrgregles.bsp_server_utilities.check.Verdict;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.AdminClientsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminClientsPacket;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The client check (owner decisions D120 to D141). SU-19: the report a client sends, read back exactly, bounded in size
 * and refused past its bounds; the latest report kept per player, a report in game replacing it but not more than once
 * a second. SU-20: every rule of the verdict; a refusal with its message, the log and today's list; a pack switched on
 * in game kicking, a newly blocked mod not (D139); TRUST MY SETUP and the file; the network's rules against a server's
 * own; other servers' players, a staff kick across servers. SU-21: the CLIENTS tab's server side, for admins only. The
 * handshake itself (the question in Forge's login, the held login) and the tab's drawing need a real client: untested
 * here.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class ClientCheckGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    /** The most a login answer may carry (vanilla's limit for a custom query answer). */
    private static final int LOGIN_ANSWER_LIMIT = 1_048_576;

    static ClientReport sample(String marker) {
        List<ClientReport.Mod> mods = List.of(
                new ClientReport.Mod("jei", "Just Enough Items", "15.56.0.205", "0123456789abcdef0123456789abcdef01234567"),
                new ClientReport.Mod("minecraft", "Minecraft", "1.20.1", ""),
                new ClientReport.Mod("x" + marker, "Marker " + marker, "1.0", "fedcba9876543210fedcba9876543210fedcba98"));
        List<ClientReport.Pack> packs = List.of(
                new ClientReport.Pack("file/BSP-Prefixes.zip", "BSP-Prefixes.zip", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                new ClientReport.Pack("programmer_art", "Programmer Art", ""));
        return new ClientReport(mods, packs, "Bliss-Shader-main.zip", true, false, "stone", "Ore Vision 32x.zip", 5.0);
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @GameTest(template = EMPTY)
    public void a_report_reads_back_exactly_over_the_wire_and_as_text(GameTestHelper h) {
        ClientReport r = sample("a" + UUID.randomUUID().toString().substring(0, 8));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        r.encode(buf);
        ClientReport back = ClientReport.decode(buf);
        h.assertTrue(back.equals(r), "over the wire: " + back);
        h.assertTrue(r.equals(ClientReport.fromText(r.toText())), "as text, for notices between servers");
        h.assertTrue(back.brightnessHigh() && !ClientReport.EMPTY.brightnessHigh(), "brightness 500 % is past the top; 100 % is not");
        h.assertTrue(ClientReport.fromText("not a report") == null, "rubbish text reads as nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_report_is_bounded_and_one_past_its_bounds_is_refused(GameTestHelper h) {
        List<ClientReport.Mod> mods = new ArrayList<>();
        for (int i = 0; i < ClientReport.MAX_MODS + 50; i++) {
            mods.add(new ClientReport.Mod(repeat('m', 200) + i, repeat('n', 300), repeat('v', 100), repeat('f', 40)));
        }
        List<ClientReport.Pack> packs = new ArrayList<>();
        for (int i = 0; i < ClientReport.MAX_PACKS + 5; i++) {
            packs.add(new ClientReport.Pack(repeat('p', 300), repeat('q', 300), repeat('f', 40)));
        }
        ClientReport big = new ClientReport(mods, packs, repeat('s', 500), true, true, "", "", 1.0);
        h.assertTrue(big.mods().size() == ClientReport.MAX_MODS && big.packs().size() == ClientReport.MAX_PACKS, "counts are cut to the bounds");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        big.encode(buf);
        h.assertTrue(buf.readableBytes() < LOGIN_ANSWER_LIMIT, "the largest report fits a login answer: " + buf.readableBytes() + " bytes");
        ClientReport back = ClientReport.decode(buf);
        h.assertTrue(back.mods().get(0).id().length() == 64 && back.shader().length() == 128, "long names are cut, not refused");
        FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
        bad.writeVarInt(ClientReport.FORMAT);
        bad.writeVarInt(ClientReport.MAX_MODS + 1);
        boolean refused = false;
        try {
            ClientReport.decode(bad);
        } catch (RuntimeException e) {
            refused = true;
        }
        h.assertTrue(refused, "a packet claiming more mods than the bound is refused");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_latest_report_is_kept_and_one_in_game_replaces_it_at_most_once_a_second(GameTestHelper h) {
        AtomicLong now = new AtomicLong(1_000_000L);
        ClientCheckService.useForTests(now::get);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            ClientReport first = clean("b1");
            ClientCheckService.accept(h.getLevel().getServer(), p.getGameProfile(), first, List.of("forge", "jei", "minecraft"));
            ClientCheckService.Held held = ClientCheckService.report(p.getUUID());
            h.assertTrue(held != null && held.report().equals(first) && held.forgeMods().equals(List.of("forge", "jei", "minecraft")), "the login's report and Forge's list are kept");
            ClientReport second = clean("b2");
            now.addAndGet(200);
            ClientCheckService.onReport(p, second);
            h.assertTrue(ClientCheckService.report(p.getUUID()).report().equals(first), "a second report within a second is ignored");
            now.addAndGet(1000);
            ClientCheckService.onReport(p, second);
            held = ClientCheckService.report(p.getUUID());
            h.assertTrue(held.report().equals(second) && held.forgeMods().size() == 3, "a later one replaces it; Forge's list stays from the login");
            GameProfile other = new GameProfile(UUID.randomUUID(), "su_check_other");
            ClientCheckService.accept(h.getLevel().getServer(), other, first, List.of());
            h.assertTrue(ClientCheckService.report(other.getId()) != null && ClientCheckService.report(p.getUUID()).report().equals(second), "each player's report is their own");
            ClientCheckService.forget(other.getId());
        } finally {
            ClientCheckService.forget(p.getUUID());
            ClientCheckService.useReal();
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ SU-20

    /** A report nothing in the default rules refuses. */
    static ClientReport clean(String marker) {
        ClientReport r = sample(marker);
        return new ClientReport(r.mods(), r.packs(), "Bliss-Shader-main.zip", true, true, "", "", 1.0);
    }

    private static ClientReport with(ClientReport r, List<ClientReport.Mod> mods, List<ClientReport.Pack> packs, boolean stone, String stonePack, String shader, double brightness) {
        return new ClientReport(mods, packs, shader, true, stone, stone ? "" : "stone", stonePack, brightness);
    }

    private static List<ClientReport.Mod> plus(List<ClientReport.Mod> mods, ClientReport.Mod m) {
        List<ClientReport.Mod> out = new ArrayList<>(mods);
        out.add(m);
        return out;
    }

    private static List<ClientReport.Pack> plusPack(List<ClientReport.Pack> packs, ClientReport.Pack p) {
        List<ClientReport.Pack> out = new ArrayList<>(packs);
        out.add(p);
        return out;
    }

    /** The client check's data, put back after a test. */
    private record Saved(CheckRules network, CheckRules own, TrustedSetup networkSetup, TrustedSetup ownSetup, boolean useOwn) {
        static Saved of(MinecraftServer server) {
            CheckData d = CheckData.get(server);
            return new Saved(d.networkRules(), d.ownRules(), d.networkSetup(), d.ownSetup(), d.useOwn());
        }

        void restore(MinecraftServer server) {
            CheckData d = CheckData.get(server);
            d.setNetworkRules(network);
            d.setOwnRules(own);
            d.setNetworkSetup(networkSetup);
            d.setOwnSetup(ownSetup);
            d.setUseOwn(useOwn);
        }
    }

    private static String sha(char c) {
        return repeat(c, 40);
    }

    @GameTest(template = EMPTY)
    public void the_verdict_follows_every_rule(GameTestHelper h) {
        CheckRules rules = new CheckRules(List.of(CheckRules.word("xray"), CheckRules.word("freecam"), new CheckRules.Entry(CheckRules.Kind.MOD, "wurst", ""),
                new CheckRules.Entry(CheckRules.Kind.PACK, sha('c'), "Cave Glow 16x.zip")), List.of("xrayfix"), List.of(new CheckRules.Exempt("Lumen", "builder")), CheckRules.DEFAULT_MESSAGE, 1).sanitised();
        ClientReport base = clean("c1");
        TrustedSetup setup = TrustedSetup.of(base, "Mrgregles", 5);
        h.assertTrue(Verdict.judge(base, List.of(), rules, setup, "Steve").equals(new Verdict(List.of(), List.of(), List.of(), List.of(), false, List.of(), false))
                && Verdict.judge(base, List.of(), rules, setup, "Steve").flagCount() == 0, "the trusted setup itself is clean");
        Verdict word = Verdict.judge(with(base, plus(base.mods(), new ClientReport.Mod("advancedxrayforge", "Advanced XRay", "2.0", "")), base.packs(), true, "", "", 1), List.of(), rules, setup, "Steve");
        h.assertTrue(word.refused() && word.refuse().equals(List.of(new Verdict.Item("advancedxrayforge", Verdict.What.MOD))), "a word anywhere in a mod id refuses: " + word);
        Verdict byName = Verdict.judge(with(base, plus(base.mods(), new ClientReport.Mod("cam_tools", "Free Cam Plus", "1", "")), base.packs(), true, "", "", 1), List.of(), rules, null, "Steve");
        h.assertTrue(!byName.refused(), "words are whole: 'Free Cam' is not 'freecam'");
        Verdict nameWord = Verdict.judge(with(base, plus(base.mods(), new ClientReport.Mod("ft", "FreeCam Reloaded", "1", "")), base.packs(), true, "", "", 1), List.of(), rules, null, "Steve");
        h.assertTrue(nameWord.refused(), "a word in a mod's display name refuses");
        Verdict exact = Verdict.judge(with(base, plus(base.mods(), new ClientReport.Mod("wurst", "W", "1", "")), base.packs(), true, "", "", 1), List.of(), rules, null, "Steve");
        h.assertTrue(exact.refuse().equals(List.of(new Verdict.Item("wurst", Verdict.What.MOD))), "an exact mod id refuses");
        Verdict allowed = Verdict.judge(with(base, plus(base.mods(), new ClientReport.Mod("xrayfix", "X", "1", "")), base.packs(), true, "", "", 1), List.of(), rules, null, "Steve");
        h.assertTrue(!allowed.refused(), "allowed anyway beats a word");
        Verdict pack = Verdict.judge(with(base, base.mods(), plusPack(base.packs(), new ClientReport.Pack("file/Renamed.zip", "Faithful 32x.zip", sha('c'))), true, "", "", 1), List.of(), rules, setup, "Steve");
        h.assertTrue(pack.refuse().equals(List.of(new Verdict.Item("Faithful 32x.zip", Verdict.What.PACK))) && pack.notTrustedPacks().equals(List.of("Faithful 32x.zip")),
                "a blocked fingerprint refuses whatever the pack is called, and it is not in the setup: " + pack);
        Verdict packWord = Verdict.judge(with(base, base.mods(), plusPack(base.packs(), new ClientReport.Pack("file/XRay Ultimate.zip", "XRay Ultimate.zip", sha('d'))), true, "", "", 1), List.of(), rules, null, "Steve");
        h.assertTrue(packWord.refused(), "a word in a pack's name refuses");
        Verdict stone = Verdict.judge(with(base, base.mods(), base.packs(), false, "Ore Vision 32x.zip", "", 1), List.of(), rules, null, "Steve");
        h.assertTrue(stone.refuse().equals(List.of(new Verdict.Item("Ore Vision 32x.zip", Verdict.What.STONE))), "a failed stone test refuses, naming the pack");
        Verdict shader = Verdict.judge(with(base, base.mods(), base.packs(), true, "", "XRayShader.zip", 1), List.of(), rules, null, "Steve");
        h.assertTrue(shader.refuse().equals(List.of(new Verdict.Item("XRayShader.zip", Verdict.What.SHADER))), "a word in the shader's name refuses");
        Verdict exempt = Verdict.judge(with(base, plus(base.mods(), new ClientReport.Mod("wurst", "W", "1", "")), base.packs(), true, "", "", 1), List.of(), rules, null, "lumen");
        h.assertTrue(exempt.exempt() && !exempt.refused() && exempt.refuse().size() == 1, "an exempt player is never refused; what would refuse still shows");
        List<ClientReport.Mod> changed = new ArrayList<>();
        for (ClientReport.Mod m : base.mods()) {
            changed.add(m.id().equals("jei") ? new ClientReport.Mod("jei", m.name(), m.version(), sha('e')) : m);
        }
        changed.add(new ClientReport.Mod("xaerominimap", "Xaero's Minimap", "24.3.0", sha('f')));
        Verdict flags = Verdict.judge(with(base, changed, base.packs(), true, "", "", 5.0), List.of("forge", "hiddenmod"), rules, setup, "Steve");
        h.assertTrue(!flags.refused() && flags.notTrustedMods().equals(List.of("xaerominimap")) && flags.fileDiffers().equals(List.of("jei"))
                && flags.brightness() && flags.hidden().equals(List.of("forge", "hiddenmod")) && flags.flagCount() == 4, "flags without refusing: " + flags);
        h.assertTrue(Verdict.judge(with(base, changed, base.packs(), true, "", "", 1), List.of(), rules, null, "Steve").flagCount() == 0, "with no trusted setup nothing is 'not trusted'");
        h.assertTrue(Verdict.decode(flags.encode()).equals(flags) && Verdict.decode(stone.encode()).equals(stone), "verdicts round trip for notices");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void rules_keep_to_their_limits_and_round_trip(GameTestHelper h) {
        h.assertTrue(CheckRules.DEFAULTS.block().stream().map(CheckRules.Entry::value).toList().equals(List.of("xray", "x-ray", "freecam", "baritone", "wurst", "meteor")),
                "the starting words (D122)");
        CheckRules odd = new CheckRules(List.of(CheckRules.word("  XRay "), CheckRules.word("xray"), new CheckRules.Entry(CheckRules.Kind.PACK, "not-a-sha", "x"),
                new CheckRules.Entry(CheckRules.Kind.PACK, sha('A'), "Pack\u0007")), List.of("Mod One", "mod one"), List.of(new CheckRules.Exempt("bad name!", ""), new CheckRules.Exempt("Lumen", "n")),
                "x".repeat(400), 3).sanitised();
        h.assertTrue(odd.block().size() == 2 && odd.block().get(0).value().equals("xray") && odd.block().get(1).value().equals(sha('a')), "lower case, one each, packs by a 40-digit fingerprint: " + odd.block());
        h.assertTrue(odd.allowed().equals(List.of("mod one")) && odd.exempt().size() == 1 && odd.message().length() == CheckRules.MAX_MESSAGE, "names, exempt players and the message kept to their limits");
        h.assertTrue(new CheckRules(List.of(), List.of(), List.of(), " ", 0).sanitised().message().equals(CheckRules.DEFAULT_MESSAGE), "an empty message is the default one");
        h.assertTrue(CheckRules.decode(odd.encode()).equals(odd) && CheckRules.load(odd.save()).equals(odd), "encoded and saved, they round trip");
        TrustedSetup t = TrustedSetup.of(sample("r1"), "Mrgregles", 1234);
        h.assertTrue(t.equals(TrustedSetup.fromJson(t.toJson())) && t.equals(TrustedSetup.fromJson(t.toFileJson())) && TrustedSetup.fromJson("{nope") == null, "the setup's JSON round trips");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_refusal_names_what_to_remove_and_is_logged_and_listed(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        ClientCheckService.useForTests(now::get);
        ClientCheckService.clearRefusalsForTests();
        try {
            CheckData.get(server).setUseOwn(false);
            ClientCheckService.setRules(server, CheckRules.DEFAULTS);
            ClientReport base = clean("d1");
            ClientReport bad = with(base, plus(base.mods(), new ClientReport.Mod("freecam", "Freecam", "1.2", "")), base.packs(), false, "Ore Vision 32x.zip", "", 1);
            GameProfile who = new GameProfile(UUID.randomUUID(), "su_check_bad");
            Component why = ClientCheckService.accept(server, who, bad, List.of());
            String text = why == null ? "" : why.getString();
            h.assertTrue(text.startsWith("You can't join with:") && text.contains("freecam") && text.contains("Ore Vision 32x.zip")
                    && text.contains("Remove them and join again; ask staff if you think this is wrong."), "the refusal lists what to remove: " + text);
            h.assertTrue(ClientCheckService.report(who.getId()) == null, "a refused player's report is not kept");
            List<ClientCheckService.Refusal> today = ClientCheckService.refusedToday();
            h.assertTrue(today.size() == 1 && today.get(0).name().equals("su_check_bad") && today.get(0).reason().equals("freecam (mod), Ore Vision 32x.zip (see-through stone)"), "listed for today: " + today);
            LogEntry e = ActionLog.recent(server, 5, false, List.of(LogEntry.CHECK)).get(0);
            h.assertTrue(e.actor().equals("Client check") && e.text().equals("refused su_check_bad: freecam (mod), Ore Vision 32x.zip (see-through stone)"), "and in the Action Log: " + e);
            h.assertTrue(com.mrgregles.bsp_server_utilities.log.LogSync.view(server).rows().stream().noneMatch(r -> r.kind().equals(LogEntry.CHECK)),
                    "players' copy of the log leaves client-check entries out (D142)");
            h.assertTrue(ClientCheckService.accept(server, new GameProfile(UUID.randomUUID(), "su_check_ok"), base, List.of()) == null, "a clean report lets the player in");
            String noList = ClientCheckService.refusal("Not with that.", List.of(new Verdict.Item("wurst", Verdict.What.MOD))).getString();
            h.assertTrue(noList.startsWith("Not with that.") && noList.contains("wurst"), "a message without <list> gets the list after it: " + noList);
            now.addAndGet(-2L * 86_400_000L);
            ClientCheckService.accept(server, new GameProfile(UUID.randomUUID(), "su_check_old"), bad, List.of());
            now.addAndGet(2L * 86_400_000L);
            h.assertTrue(ClientCheckService.refusedToday().size() == 1, "a refusal from two days ago is not today's");
        } finally {
            ClientCheckService.useReal();
            ClientCheckService.clearRefusalsForTests();
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_pack_switched_on_in_game_kicks_but_a_newly_blocked_mod_does_not(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        AtomicLong now = new AtomicLong(5_000_000L);
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        Map<UUID, String> kicked = new HashMap<>();
        ClientCheckService.useForTests(now::get, () -> List.of(p), (pl, why) -> kicked.put(pl.getUUID(), why.getString()), null);
        try {
            CheckData.get(server).setUseOwn(false);
            ClientCheckService.setRules(server, CheckRules.DEFAULTS);
            ClientReport base = clean("e1");
            ClientCheckService.accept(server, p.getGameProfile(), with(base, base.mods(), base.packs(), true, "", "", 5.0), List.of());
            ClientCheckService.onLoggedIn(p);
            h.assertTrue(ClientCheckService.toldBright(p.getUUID()), "a brightness past the top is told at join (D133)");
            CheckRules withMarker = new CheckRules(plusEntry(CheckRules.DEFAULTS.block(), new CheckRules.Entry(CheckRules.Kind.MOD, base.mods().get(2).id(), "")), List.of(), List.of(), CheckRules.DEFAULT_MESSAGE, 0);
            ClientCheckService.setRules(server, withMarker);
            ClientCheckService.Held held = ClientCheckService.report(p.getUUID());
            h.assertTrue(kicked.isEmpty() && held.verdict().refuse().size() == 1, "blocking a mod someone online has flags them and kicks nobody (D139): " + held.verdict());
            now.addAndGet(5000);
            ClientCheckService.onReport(p, base);
            h.assertTrue(kicked.isEmpty(), "their next report, with the same mod, still does not kick");
            now.addAndGet(5000);
            ClientCheckService.onReport(p, with(base, base.mods(), plusPack(base.packs(), new ClientReport.Pack("file/XRay.zip", "XRay.zip", sha('9'))), true, "", "", 1));
            String why = kicked.get(p.getUUID());
            h.assertTrue(why != null && why.contains("XRay.zip") && !why.contains(base.mods().get(2).id()), "a blocked pack switched on in game kicks, naming only the new thing (D127): " + why);
        } finally {
            ClientCheckService.forget(p.getUUID());
            ClientCheckService.useReal();
            ClientCheckService.clearRefusalsForTests();
            saved.restore(server);
        }
        h.succeed();
    }

    private static List<CheckRules.Entry> plusEntry(List<CheckRules.Entry> b, CheckRules.Entry e) {
        List<CheckRules.Entry> out = new ArrayList<>(b);
        out.add(e);
        return out;
    }

    @GameTest(template = EMPTY)
    public void trust_my_setup_judges_everyone_again_and_writes_the_file(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Path dir = Files.createTempDirectory("su_check");
        ClientCheckService.useForTests(now::get, () -> List.of(admin, player), (pl, why) -> h.fail("nobody is kicked by trusting a setup"), dir);
        try {
            CheckData d = CheckData.get(server);
            d.setUseOwn(false);
            d.setNetworkSetup(null);
            ClientReport mine = clean("f1");
            ClientReport theirs = with(mine, plus(mine.mods(), new ClientReport.Mod("xaerominimap", "Xaero's Minimap", "24.3.0", sha('7'))), mine.packs(), true, "", "", 1);
            ClientCheckService.accept(server, admin.getGameProfile(), mine, List.of());
            ClientCheckService.accept(server, player.getGameProfile(), theirs, List.of());
            h.assertTrue(ClientCheckService.report(player.getUUID()).verdict().flagCount() == 0, "no setup, nothing flagged");
            h.assertTrue(ClientCheckService.trust(server, admin), "TRUST MY SETUP takes the admin's report");
            TrustedSetup t = ClientCheckService.setup(server);
            h.assertTrue(t != null && t.takenBy().equals(admin.getGameProfile().getName()) && t.mods().equals(mine.mods()), "the setup is the admin's client");
            h.assertTrue(ClientCheckService.report(player.getUUID()).verdict().notTrustedMods().equals(List.of("xaerominimap")), "everyone online is judged again at once (D141)");
            h.assertTrue(Files.isRegularFile(dir.resolve(TrustedSetup.FILE)), "and the file is written (D132)");
            h.assertTrue(ClientCheckService.readFile(server) == ClientCheckService.FileResult.OLDER, "the same file read back is not newer");
            TrustedSetup newer = new TrustedSetup(t.takenAtMs() + 60_000, "OtherAdmin", theirs.mods(), theirs.packs(), "", 0);
            Files.writeString(dir.resolve(TrustedSetup.FILE), newer.toFileJson());
            h.assertTrue(ClientCheckService.readFile(server) == ClientCheckService.FileResult.LOADED && ClientCheckService.setup(server).takenBy().equals("OtherAdmin")
                    && ClientCheckService.report(player.getUUID()).verdict().notTrustedMods().isEmpty(), "a newer file dropped in is taken and everyone judged again");
            Files.writeString(dir.resolve(TrustedSetup.FILE), "not json");
            h.assertTrue(ClientCheckService.readFile(server) == ClientCheckService.FileResult.BAD, "a broken file changes nothing");
            ServerPlayer nobody = TestPlayers.mockServerPlayer(h);
            h.assertTrue(!ClientCheckService.trust(server, nobody), "no report, nothing to trust");
        } finally {
            ClientCheckService.forget(admin.getUUID());
            ClientCheckService.forget(player.getUUID());
            ClientCheckService.useReal();
            saved.restore(server);
            Files.deleteIfExists(dir.resolve(TrustedSetup.FILE));
            Files.deleteIfExists(dir);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void network_rules_are_shared_and_a_server_may_keep_its_own(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            CheckData.get(server).setUseOwn(false);
            CheckRules net = ClientCheckService.setRules(server, CheckRules.DEFAULTS.withBlock(plusEntry(CheckRules.DEFAULTS.block(), CheckRules.word("killaura"))));
            alpha.drain();
            h.assertTrue(db.loadCheckRules().equals(net) && db.fetchNoticesAfter(0, 50).stream().anyMatch(n -> n.kind().equals(ClientCheckService.KIND_RULES)), "the network's rules are saved and announced");
            ClientCheckService.applyRules(server, CheckRules.DEFAULTS.withRevision(net.revision() - 1));
            h.assertTrue(ClientCheckService.rules(server).equals(net), "an older copy changes nothing");
            ClientCheckService.setUseOwn(server, true);
            h.assertTrue(ClientCheckService.rules(server).equals(net), "own rules start as a copy of the network's (D134)");
            CheckRules own = ClientCheckService.setRules(server, net.withBlock(List.of(CheckRules.word("minimap"))));
            alpha.drain();
            h.assertTrue(ClientCheckService.rules(server).equals(own) && db.loadCheckRules().equals(net), "changes stay on this server");
            CheckRules newer = CheckRules.DEFAULTS.withRevision(own.revision() + 10);
            ClientCheckService.applyRules(server, newer);
            h.assertTrue(ClientCheckService.rules(server).equals(own) && CheckData.get(server).networkRules().equals(newer), "the network's copy is kept up to date underneath");
            ClientCheckService.setUseOwn(server, false);
            h.assertTrue(ClientCheckService.rules(server).equals(newer), "switched back, the network's rules are in force");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void other_servers_players_show_and_a_staff_kick_reaches_them(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        ServerPlayer here = TestPlayers.mockServerPlayer(h);
        Map<UUID, String> kicked = new HashMap<>();
        ClientCheckService.useForTests(System::currentTimeMillis, () -> List.of(here), (pl, why) -> kicked.put(pl.getUUID(), why.getString()), null);
        UUID remote = UUID.randomUUID();
        try {
            ClientReport r = clean("g1");
            Verdict v = new Verdict(List.of(), List.of("xaerominimap"), List.of(), List.of(), true, List.of(), false);
            String payload = String.join("\n", "beta", remote.toString(), "Kat_Miner", "123", v.encode(), r.toText(), "forge,jei");
            alphaNotice(alpha, ClientCheckService.KIND_REPORT, payload);
            ClientCheckService.Row row = ClientCheckService.find(remote);
            h.assertTrue(row != null && row.server().equals("beta") && row.held().verdict().equals(v) && row.held().report().equals(r), "another server's player shows with their report");
            h.assertTrue(ClientCheckService.players(server).stream().anyMatch(x -> x.held().id().equals(remote)), "and in the network's list");
            h.assertTrue(ClientCheckService.kickByStaff(server, null, remote, "Please remove the minimap mod and join again."), "a staff kick for them is sent on");
            alpha.drain();
            h.assertTrue(db.fetchNoticesAfter(0, 50).stream().anyMatch(n -> n.kind().equals(ClientCheckService.KIND_KICK) && n.payload().startsWith(remote.toString())), "as a notice to their server");
            alphaNotice(alpha, ClientCheckService.KIND_PRESENT, "beta\n");
            h.assertTrue(ClientCheckService.find(remote) == null, "gone when their server's list no longer has them");
            ClientCheckService.accept(server, here.getGameProfile(), r, List.of());
            alphaNotice(alpha, ClientCheckService.KIND_KICK, here.getUUID() + "\nBye \u00a7cnow");
            String why = kicked.get(here.getUUID());
            h.assertTrue(why != null && (why.startsWith("message.bsp_server_utilities.check.removed_by_staff") || why.startsWith("Removed by staff")), "a kick from another server reaches a player here: " + why);
            h.assertTrue(why.endsWith("\nBye now"), "the reason under it, without formatting codes: " + why.replace('\n', '|'));
        } finally {
            ClientCheckService.forget(here.getUUID());
            ClientCheckService.forget(remote);
            ClientCheckService.useReal();
            Storage.useForTests(null);
            alpha.close();
        }
        h.succeed();
    }

    /** A notice as if from another server: handled as the table poll would. */
    private static void alphaNotice(Storage s, String kind, String payload) {
        com.mrgregles.bsp_server_utilities.storage.StorageBackend.Notice n = new com.mrgregles.bsp_server_utilities.storage.StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", kind, payload);
        s.deliverForTests(n);
    }

    // ------------------------------------------------------------------ SU-21

    @GameTest(template = EMPTY)
    public void the_clients_tab_shows_the_network_and_only_admins_act(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer other = TestPlayers.mockServerPlayer(h);
        Map<UUID, String> kicked = new HashMap<>();
        Path dir = Files.createTempDirectory("su_tab");
        ClientCheckService.useForTests(System::currentTimeMillis, () -> List.of(admin, other), (pl, why) -> kicked.put(pl.getUUID(), why.getString()), dir);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            CheckData d = CheckData.get(server);
            d.setUseOwn(false);
            d.setNetworkSetup(null);
            ClientCheckService.setRules(server, CheckRules.DEFAULTS);
            ClientReport mine = clean("t1");
            ClientReport theirs = with(mine, plus(mine.mods(), new ClientReport.Mod("xaerominimap", "Xaero's Minimap", "24.3.0", sha('7'))), mine.packs(), true, "", "", 1);
            ClientCheckService.accept(server, admin.getGameProfile(), mine, List.of());
            ClientCheckService.accept(server, other.getGameProfile(), theirs, List.of());
            CheckRules before = ClientCheckService.rules(server);
            h.assertTrue(!ClientsAdmin.apply(other, new AdminClientsEditPacket(AdminClientsEditPacket.Action.RULES, AdminClientsEditPacket.NOBODY, CheckRules.DEFAULTS.withBlock(List.of()).encode(), ""))
                    && ClientCheckService.rules(server).equals(before), "a player who may not edit changes nothing");
            ClientsAdmin.apply(admin, new AdminClientsEditPacket(AdminClientsEditPacket.Action.SELECT, other.getUUID(), "", ""));
            AdminClientsPacket p = ClientsAdmin.packet(server, admin.getUUID(), "");
            h.assertTrue(p.detail() != null && p.detail().id().equals(other.getUUID()) && p.detail().report().equals(theirs) && p.rows().size() == 2 && p.setup() == null,
                    "the picked player's report and everyone's rows: " + p.rows());
            io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
            FriendlyByteBuf buf = new FriendlyByteBuf(raw);
            p.encode(buf);
            h.assertTrue(AdminClientsPacket.decode(buf).equals(p), "the tab's data round trips");
            AdminClientsEditPacket edit = new AdminClientsEditPacket(AdminClientsEditPacket.Action.BLOCK_PACK, other.getUUID(), sha('c'), "Cave Glow 16x.zip");
            FriendlyByteBuf eb = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            edit.encode(eb);
            h.assertTrue(AdminClientsEditPacket.decode(eb).equals(edit), "and the tab's actions");
            ClientsAdmin.apply(admin, AdminClientsEditPacket.of(AdminClientsEditPacket.Action.TRUST));
            p = ClientsAdmin.packet(server, admin.getUUID(), "");
            AdminClientsPacket.PlayerRow row = p.rows().stream().filter(r -> r.id().equals(other.getUUID())).findFirst().orElseThrow();
            h.assertTrue(p.setup() != null && p.setup().takenBy().equals(admin.getGameProfile().getName()) && row.flags() == 1 && !row.blocked(), "TRUST MY SETUP: the other player now has one flag");
            h.assertTrue(p.rows().get(0).id().equals(other.getUUID()), "flagged players first");
            ClientsAdmin.apply(admin, new AdminClientsEditPacket(AdminClientsEditPacket.Action.BLOCK_MOD, other.getUUID(), "xaerominimap", ""));
            row = ClientsAdmin.packet(server, admin.getUUID(), "").rows().stream().filter(r -> r.id().equals(other.getUUID())).findFirst().orElseThrow();
            h.assertTrue(ClientCheckService.rules(server).block().contains(new CheckRules.Entry(CheckRules.Kind.MOD, "xaerominimap", "")) && row.blocked() && kicked.isEmpty(),
                    "BLOCK adds the mod, marks them blocked and kicks nobody (D139)");
            int n = ClientCheckService.rules(server).block().size();
            ClientsAdmin.apply(admin, new AdminClientsEditPacket(AdminClientsEditPacket.Action.BLOCK_PACK, other.getUUID(), "nope", "x"));
            h.assertTrue(ClientCheckService.rules(server).block().size() == n, "a pack without a fingerprint cannot be blocked");
            ClientsAdmin.apply(admin, new AdminClientsEditPacket(AdminClientsEditPacket.Action.USE_OWN, AdminClientsEditPacket.NOBODY, "true", ""));
            ClientsAdmin.apply(admin, new AdminClientsEditPacket(AdminClientsEditPacket.Action.RULES, AdminClientsEditPacket.NOBODY, CheckRules.DEFAULTS.withBlock(List.of(CheckRules.word("minimap"))).encode(), ""));
            h.assertTrue(ClientCheckService.useOwn(server) && ClientCheckService.rules(server).block().equals(List.of(CheckRules.word("minimap")))
                    && !d.networkRules().block().equals(List.of(CheckRules.word("minimap"))), "this server's own rules from the tab");
            ClientsAdmin.apply(admin, AdminClientsEditPacket.of(AdminClientsEditPacket.Action.EXPORT));
            h.assertTrue(Files.isRegularFile(dir.resolve(TrustedSetup.FILE)), "EXPORT writes the file");
            ClientsAdmin.apply(admin, new AdminClientsEditPacket(AdminClientsEditPacket.Action.KICK, other.getUUID(), "Please remove the minimap mod.", ""));
            String why = kicked.get(other.getUUID());
            h.assertTrue(why != null && why.endsWith("Please remove the minimap mod."), "KICK with the typed reason: " + why);
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            ClientCheckService.forget(admin.getUUID());
            ClientCheckService.forget(other.getUUID());
            ClientsAdmin.forget(admin.getUUID());
            ClientCheckService.useReal();
            ClientCheckService.clearRefusalsForTests();
            saved.restore(server);
            Files.deleteIfExists(dir.resolve(TrustedSetup.FILE));
            Files.deleteIfExists(dir);
        }
        h.succeed();
    }
}
