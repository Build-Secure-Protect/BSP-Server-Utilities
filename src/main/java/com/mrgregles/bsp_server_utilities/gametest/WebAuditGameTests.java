package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.ProxyKick;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebBanActions;
import com.mrgregles.bsp_server_utilities.web.WebBans;
import com.mrgregles.bsp_server_utilities.web.WebMarket;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The audit of the whole staff panel (D420 to D424), game side: a server started before its database answers never
 * replays the notices table; actions are carried out only when their answer can reach the panel, and never past the
 * panel's deadline; bans kept on disk for a start while the database is down; a ban's note kept whole; a market never
 * read yet is read rather than called "not connected"; and the signature test vector the panel's JUnit checks too.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebAuditGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    /** The same vector as the panel's {@code WholeAuditTest}, worked out apart from either side's code (Python's hmac). */
    static final byte[] GOLDEN_KEY = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    static final String GOLDEN_ACTION_JSON = "{\"v\":1,\"id\":\"golden-1\",\"at\":1700000000000,\"until\":1700000006000,\"by\":\"Ana\",\"rank\":\"ADMIN\",\"server\":\"Frostwarf\",\"action\":\"kick\",\"player\":\"11111111-2222-3333-4444-555555555555\",\"reason\":\"é ok\"}";
    static final String GOLDEN_ACTION = "eyJ2IjoxLCJpZCI6ImdvbGRlbi0xIiwiYXQiOjE3MDAwMDAwMDAwMDAsInVudGlsIjoxNzAwMDAwMDA2MDAwLCJieSI6IkFuYSIsInJhbmsiOiJBRE1JTiIsInNlcnZlciI6IkZyb3N0d2FyZiIsImFjdGlvbiI6ImtpY2siLCJwbGF5ZXIiOiIxMTExMTExMS0yMjIyLTMzMzMtNDQ0NC01NTU1NTU1NTU1NTUiLCJyZWFzb24iOiLDqSBvayJ9.a1f6e8f125af2d8d2853d00decfb7924338a661c587753628284af132279a5e0";
    static final String GOLDEN_ANSWER_JSON = "{\"id\":\"golden-1\",\"ok\":true,\"message\":\"Vexa was kicked from the network.\",\"server\":\"Frostwarf\"}";
    static final String GOLDEN_ANSWER = "403b073d2e98b5457f8025d3641f217095aa41415b8dc70800beb35cd93b9bdb." + GOLDEN_ANSWER_JSON;

    /** RFC 8032's first test key's public half, and the golden action signed with its private half by the panel's {@code PanelSigner}. */
    static final String RFC_PUBLIC = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=";
    static final String GOLDEN_PANEL_ACTION = "eyJ2IjoxLCJpZCI6ImdvbGRlbi0xIiwiYXQiOjE3MDAwMDAwMDAwMDAsInVudGlsIjoxNzAwMDAwMDA2MDAwLCJieSI6IkFuYSIsInJhbmsiOiJBRE1JTiIsInNlcnZlciI6IkZyb3N0d2FyZiIsImFjdGlvbiI6ImtpY2siLCJwbGF5ZXIiOiIxMTExMTExMS0yMjIyLTMzMzMtNDQ0NC01NTU1NTU1NTU1NTUiLCJyZWFzb24iOiLDqSBvayJ9.gL6Ij3f_AF4tOyws13_mLVjqM5RWgTetrAG464O0Fb1ykBTsnkc8aKtnyyFmbTSX-jv5VHVwaMk2F9WYlhMUDQ";

    @GameTest(template = EMPTY)
    public void only_the_panels_own_key_orders_an_action(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        java.security.PublicKey panel = WebActions.panelKey(RFC_PUBLIC);
        JsonObject a = WebActions.verifyPanel(panel, GOLDEN_PANEL_ACTION);
        h.assertTrue(a != null && a.get("reason").getAsString().equals("\u00e9 ok"), "the panel's Ed25519 signature checked with its public key (D426): " + a);
        String tampered = GOLDEN_PANEL_ACTION.substring(0, GOLDEN_PANEL_ACTION.length() - 4) + "AAAA";
        h.assertTrue(WebActions.verifyPanel(panel, tampered) == null, "a changed signature: refused");
        h.assertTrue(WebActions.verifyPanel(panel, GOLDEN_ACTION) == null, "signed with the shared key (anyone with a game server's files has it): never an action");
        h.assertTrue(WebActions.panelKey("not a key") == null && WebActions.panelKey(Base64.getEncoder().encodeToString(new byte[31])) == null, "a key that is not one: none");
        long now = System.currentTimeMillis();
        WebActions.useForTests(GOLDEN_KEY, () -> now, null);
        WebActions.usePanelKeyForTests(panel);
        try {
            WebActions.Answer hmac = WebActions.handle(server, action("k1-" + now, now, 0, "kick", ",\"player\":\"" + UUID.randomUUID() + "\""));
            h.assertTrue(hmac == null, "the shared key's signature on an action: not carried out, with the panel's key set");
        } finally {
            WebActions.useReal();
        }
        h.succeed();
    }

    private static String action(String id, long at, long until, String action, String extra) {
        return WebActions.sign(GOLDEN_KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + (until > 0 ? ",\"until\":" + until : "") + ",\"by\":\"Ana_Mod\",\"rank\":\"ADMIN\",\"server\":\""
                + Storage.currentServerId() + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    @GameTest(template = EMPTY)
    public void the_signatures_match_the_shared_test_vector(GameTestHelper h) {
        h.assertTrue(WebActions.sign(GOLDEN_KEY, GOLDEN_ACTION_JSON).equals(GOLDEN_ACTION), "an action signed as the panel signs it");
        JsonObject read = WebActions.verify(GOLDEN_KEY, GOLDEN_ACTION);
        h.assertTrue(read != null && read.get("reason").getAsString().equals("é ok") && read.get("until").getAsLong() == 1_700_000_006_000L, "and read back: " + read);
        h.assertTrue(WebActions.signText(GOLDEN_KEY, GOLDEN_ANSWER_JSON).equals(GOLDEN_ANSWER), "an answer sealed as the panel opens it");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_server_started_before_its_database_never_replays_the_notices_table(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        String kind = "zz_audit_" + Integer.toHexString(new java.util.Random().nextInt());
        MemoryBackend db = new MemoryBackend();
        db.publish(new StorageBackend.Notice(0, "old-" + kind, now - 3_600_000L, "beta", kind, "an hour old"));
        db.setFailing(true);
        Storage s = Storage.startWith(server, db, "alpha");
        List<String> got = new CopyOnWriteArrayList<>();
        s.onNotice(n -> {
            if (n.kind().equals(kind)) {
                got.add(n.payload());
            }
        });
        s.drain();
        s.pollNotices();
        s.drain();
        h.assertTrue(!s.isReady(), "the database not answering: not ready, nothing read");
        db.setFailing(false);
        s.pollNotices();
        s.drain();
        h.assertTrue(s.isReady(), "ready once it answers (the tables made then)");
        db.publish(new StorageBackend.Notice(0, "late-" + kind, now - Storage.TABLE_NOTICE_MAX_AGE_MS - 1, "beta", kind, "twenty minutes old"));
        db.publish(new StorageBackend.Notice(0, "new-" + kind, now, "beta", kind, "fresh"));
        s.pollNotices();
        s.drain();
        h.runAfterDelay(3, () -> {
            s.close();
            h.assertTrue(got.equals(List.of("fresh")), "the notices already in the table are history, and one far too old is skipped: " + got);
            h.succeed();
        });
    }

    @GameTest(template = EMPTY)
    public void an_action_is_taken_only_when_its_answer_can_reach_the_panel_and_before_its_deadline(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<byte[]> sent = new ArrayList<>();
        long now = System.currentTimeMillis();
        String m = "q" + Integer.toHexString(new java.util.Random().nextInt());
        WebActions.useForTests(GOLDEN_KEY, () -> now, (s, id) -> id.equals(p.getUUID()) ? p : null);
        ProxyKick.useForTests((pl, b) -> sent.add(b), (pl, why) -> { }, null);
        try {
            WebAgent.useForTests(true, null, null, null, false);
            String kick = ",\"player\":\"" + p.getUUID() + "\",\"reason\":\"" + m + "\"";
            WebActions.dispatch(server, new StorageBackend.Notice(0, "n1-" + m, now, "panel", WebActions.KIND_ACTION, action("d1-" + m, now, now + 6000, "kick", kick)));
            h.assertTrue(sent.isEmpty(), "this server's Redis down: nothing carried out (the panel would be told \"no answer\" for something done)");
            WebAgent.useForTests(true, null, null, null, true);
            WebActions.dispatch(server, new StorageBackend.Notice(0, "n2-" + m, now, "panel", WebActions.KIND_ACTION, action("d2-" + m, now, now + 6000, "kick", kick)));
            h.assertTrue(sent.size() == 1, "with Redis up: carried out");
            WebActions.Answer late = WebActions.handle(server, action("d3-" + m, now - 8000, now - 2000, "kick", kick));
            h.assertTrue(late != null && !late.ok() && late.message().contains("took too long") && sent.size() == 1,
                    "past the panel's deadline (it has told staff there was no answer): refused: " + late);
        } finally {
            WebActions.useReal();
            WebAgent.useReal();
            ProxyKick.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void bans_are_kept_on_disk_for_a_start_while_the_database_is_down(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Path file = Files.createTempDirectory("bsp-su-bans").resolve("bans.json");
        MemoryBackend db = new MemoryBackend();
        UUID vexa = UUID.randomUUID();
        long now = System.currentTimeMillis();
        db.insertBan(new StorageBackend.Ban(0, vexa, "Vexa_a", "griefing spawn", "seen by two staff", "Ana_Admin", now, 0, 0, "", 0));
        Storage s = Storage.startWith(server, db, "alpha");
        Storage.useForTests(s);
        try {
            WebBans.clearForTests();
            WebBans.useCacheForTests(file);
            WebBans.reload();
            s.drain();
            String kept = Files.readString(file);
            h.assertTrue(kept.contains("griefing spawn") && !kept.contains("seen by two staff"), "each good read kept on disk, without the staff note: " + kept);
            WebBans.clearForTests();
            h.assertTrue(WebBans.banOf(vexa) == null, "a fresh start: nothing held");
            h.assertTrue(WebBans.readCache() == 1 && WebBans.banOf(vexa) != null && WebBans.refusal(vexa) != null,
                    "read back before the database answers: still refused at login");
        } finally {
            WebBans.clearForTests();
            WebBans.useCacheForTests(null);
            Storage.useForTests(null);
            s.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_bans_note_is_kept_whole_with_its_lines(GameTestHelper h) {
        String note = "Line one about the grief.\n" + "x".repeat(380) + "\nLine three ‮ hidden";
        String clean = WebBanActions.cleanNote(note);
        h.assertTrue(clean.length() > 256 && clean.split("\n").length == 3 && !clean.contains("‮"),
                "up to 500 characters with its line breaks (not cut at 256 and joined); hidden characters as spaces");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_market_never_read_yet_is_read_rather_than_called_not_connected(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        WebMarketGameTests.FakeEconomy eco = new WebMarketGameTests.FakeEconomy();
        eco.server = server;
        eco.readMs = 0;
        WebActions.useForTests(GOLDEN_KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        WebMarket.useForTests(() -> now);
        try {
            BspCore.useEconomyForTests(eco);
            String m = "r" + Integer.toHexString(new java.util.Random().nextInt());
            WebActions.Answer a = WebActions.handle(server, action("m1-" + m, now, 0, "market_read", ""));
            h.assertTrue(a != null && a.later() != null && eco.refreshes == 1, "connected, never read: BSP-Core is asked to read now");
            WebActions.Answer done = a.later().getNow(null);
            h.assertTrue(done != null && done.ok() && !done.data().getAsJsonObject().get("down").getAsBoolean(), "and the answer is not \"not connected\": " + done);
            eco.connected = false;
            WebActions.Answer down = WebActions.handle(server, action("m2-" + m, now, 0, "market_read", ""));
            h.assertTrue(down != null && down.later() == null && down.data().getAsJsonObject().get("down").getAsBoolean(), "not connected: said so (D414)");
        } finally {
            BspCore.useEconomyForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
            WebMarket.useForTests(null);
        }
        h.succeed();
    }
}
