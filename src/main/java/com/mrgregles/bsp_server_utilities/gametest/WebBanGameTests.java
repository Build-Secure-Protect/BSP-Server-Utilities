package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.ProxyKick;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebBanActions;
import com.mrgregles.bsp_server_utilities.web.WebBans;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network bans (SU-44; D350 to D356, D370): the screen a banned player sees (reason, end or "This ban is permanent.", the
 * appeal line), the login refused from the copy in memory (and not after it ends or is lifted), and the panel's ban, edit
 * and unban: refused for Moderators and without a reason, written to the bans table, known here at once, the other servers
 * told, a banned player here sent off the network, the Action Log.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebBanGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 7);
        }
    }

    private static String action(String id, long at, String rank, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Admin\",\"rank\":\"" + rank + "\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    @GameTest(template = EMPTY)
    public void a_banned_player_is_refused_at_login_with_the_reason_and_the_end(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        WebBans.useForTests(now::get, () -> "Appeal on our Discord");
        UUID vexa = UUID.randomUUID(), steve = UUID.randomUUID();
        try {
            WebBans.replace(List.of(new StorageBackend.Ban(1, vexa, "Vexa", "X-ray on Moongrove", "", "Ana", now.get(), now.get() + 7 * 86_400_000L, 0, "", 0),
                    new StorageBackend.Ban(2, steve, "Steve", "Griefing", "", "Ana", now.get(), 0, 0, "", 0)));
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 25565);
            Component v = server.getPlayerList().canPlayerLogin(addr, new GameProfile(vexa, "Vexa"));
            h.assertTrue(v != null && v.getString().contains("You are banned from the Build Secure Protect network.") && v.getString().contains("X-ray on Moongrove")
                    && v.getString().contains("(in 7 days)") && v.getString().contains("UTC") && v.getString().contains("Appeal on our Discord"), "the screen: " + (v == null ? null : v.getString()));
            Component s = server.getPlayerList().canPlayerLogin(addr, new GameProfile(steve, "Steve"));
            h.assertTrue(s != null && s.getString().contains("This ban is permanent."), "permanent: " + (s == null ? null : s.getString()));
            h.assertTrue(server.getPlayerList().canPlayerLogin(addr, new GameProfile(UUID.randomUUID(), "Someone")) == null, "anyone else joins");
            now.addAndGet(7 * 86_400_000L + 1);
            h.assertTrue(server.getPlayerList().canPlayerLogin(addr, new GameProfile(vexa, "Vexa")) == null, "after its end: joins");
            WebBans.replace(List.of());
            h.assertTrue(server.getPlayerList().canPlayerLogin(addr, new GameProfile(steve, "Steve")) == null, "lifted (gone from the bans read): joins");
            h.assertTrue(WebBans.left(2 * 86_400_000L + 3 * 3_600_000L).equals("2 days 3 hours") && WebBans.left(30 * 60_000L).equals("30 minutes"), "the time left in words");
        } finally {
            WebBans.clearForTests();
            WebBans.useForTests(null, null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public void the_panel_bans_edits_and_lifts_through_the_bans_table(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer vexa = TestPlayers.mockServerPlayer(h);
        long now = System.currentTimeMillis();
        String m = "b" + Integer.toHexString(new java.util.Random().nextInt());
        MemoryBackend db = new MemoryBackend();
        Storage store = Storage.startWith(server, db, "alpha");
        store.drain();
        List<Component> kicked = new ArrayList<>();
        // the hooks again at every step: tests in the same batch reset these shared hooks in their own clean-up
        Runnable hooks = () -> {
            Storage.useForTests(store);
            WebActions.useForTests(KEY, () -> now, null);
            WebAgent.useForTests(true, null, null);
            WebBanActions.useForTests((s, id) -> id.equals(vexa.getUUID()) ? vexa : null, () -> now);
            WebBans.useForTests(() -> now, () -> "");
            // the network kick asks the proxy first (the server's own kick follows 3 s later): what the proxy is asked carries the screen
            ProxyKick.useForTests((p, b) -> kicked.add(Component.literal(new String(b, java.nio.charset.StandardCharsets.UTF_8))), (p, why) -> { }, (s, id) -> null);
        };
        hooks.run();
        String who = ",\"player\":\"" + vexa.getUUID() + "\",\"name\":\"Vexa_" + m.substring(0, 6) + "\"";
        WebActions.Answer mod = WebActions.handle(server, action("m0-" + m, now, "MODERATOR", "ban", who + ",\"lengthMs\":604800000,\"reason\":\"x\""));
        WebActions.Answer noReason = WebActions.handle(server, action("m1-" + m, now, "ADMIN", "ban", who + ",\"lengthMs\":604800000,\"reason\":\"  \""));
        WebActions.Answer tooShort = WebActions.handle(server, action("m2-" + m, now, "ADMIN", "ban", who + ",\"lengthMs\":5,\"reason\":\"x\""));
        WebActions.Answer ban = WebActions.handle(server, action("m3-" + m, now, "ADMIN", "ban", who + ",\"lengthMs\":604800000,\"reason\":\"X-ray " + m + "\",\"note\":\"three reports\""));
        store.drain();
        h.runAfterDelay(3, () -> {
            hooks.run();
            try {
                h.assertTrue(!mod.ok() && mod.message().contains("Admins and Owners"), "a Moderator: refused (D370)");
                h.assertTrue(!noReason.ok() && noReason.message().contains("reason"), "no reason: refused");
                h.assertTrue(!tooShort.ok(), "under a minute: refused");
                h.assertTrue(db.bans.size() == 1 && db.bans.get(0).reason().equals("X-ray " + m) && db.bans.get(0).note().equals("three reports")
                        && db.bans.get(0).untilMs() == now + 604_800_000L && db.bans.get(0).by().equals("Ana_Admin"), "written to the bans table: " + db.bans);
                WebActions.Answer done = ban.later().getNow(null);
                h.assertTrue(done != null && done.ok() && done.message().contains("has left it"), "answered: " + (done == null ? null : done.message()));
                h.assertTrue(WebBans.banOf(vexa.getUUID()) != null, "known here at once");
                h.assertTrue(kicked.size() == 1 && kicked.get(0).getString().contains("X-ray " + m), "a banned player here leaves the network with the screen");
                store.drain();
                h.assertTrue(told(db), "the other servers are told");
                h.assertTrue(ActionLog.recent(server, 5000, true, List.of()).stream().anyMatch(e -> e.text().contains("ban Vexa_") && e.text().contains("X-ray " + m)), "in the Action Log");

                WebActions.Answer edit = WebActions.handle(server, action("m4-" + m, now, "OWNER", "ban_edit", ",\"ban\":1,\"player\":\"" + vexa.getUUID() + "\",\"lengthMs\":0,\"reason\":\"X-ray, twice\",\"note\":\"\""));
                store.drain();
                h.runAfterDelay(3, () -> {
                    hooks.run();
                    try {
                        h.assertTrue(edit.later().getNow(null) != null && edit.later().getNow(null).ok() && db.bans.get(0).untilMs() == 0 && db.bans.get(0).reason().equals("X-ray, twice"),
                                "edited: permanent, a new reason: " + db.bans.get(0));
                        WebActions.Answer lift = WebActions.handle(server, action("m5-" + m, now, "ADMIN", "unban", ",\"ban\":1"));
                        store.drain();
                        h.runAfterDelay(3, () -> {
                            hooks.run();
                            try {
                                store.drain();
                                WebActions.Answer l = lift.later().getNow(null);
                                h.assertTrue(l != null && l.ok() && db.bans.get(0).liftedBy().equals("Ana_Admin"), "lifted, by whom: " + db.bans.get(0));
                                h.assertTrue(WebBans.banOf(vexa.getUUID()) == null, "and may join again");
                                WebActions.Answer again = WebActions.handle(server, action("m6-" + m, now, "ADMIN", "unban", ",\"ban\":1"));
                                store.drain();
                                h.runAfterDelay(3, () -> {
                                    hooks.run();
                                    WebActions.Answer a2 = again.later().getNow(null);
                                    cleanUp();
                                    h.assertTrue(a2 != null && !a2.ok() && a2.message().contains("already"), "lifted twice: said so");
                                    h.succeed();
                                });
                            } catch (RuntimeException e) {
                                cleanUp();
                                throw e;
                            }
                        });
                    } catch (RuntimeException e) {
                        cleanUp();
                        throw e;
                    }
                });
            } catch (RuntimeException e) {
                cleanUp();
                throw e;
            }
        });
    }

    @GameTest(template = EMPTY)
    public void staff_elsewhere_are_banned_by_the_owner_alone_and_a_ban_read_later_sends_them_off(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer vexa = TestPlayers.mockServerPlayer(h);
        UUID helper = UUID.randomUUID();
        long now = System.currentTimeMillis();
        String m = "s" + Integer.toHexString(new java.util.Random().nextInt());
        MemoryBackend db = new MemoryBackend();
        Storage store = Storage.startWith(server, db, "alpha");
        store.drain();
        List<Component> kicked = new ArrayList<>();
        try {
            Storage.useForTests(store);
            WebActions.useForTests(KEY, () -> now, null);
            WebAgent.useForTests(true, null, null);
            WebBanActions.useForTests((s, id) -> null, () -> now);
            WebBanActions.useStaffForTests((s, id) -> id.equals(helper)); // a staff group's member, offline (LuckPerms in the real roster)
            WebBans.useForTests(() -> now, () -> "");
            ProxyKick.useForTests((p, b) -> kicked.add(Component.literal(new String(b, java.nio.charset.StandardCharsets.UTF_8))), (p, why) -> { }, (s, id) -> null);
            String staff = ",\"player\":\"" + helper + "\",\"name\":\"Helper_" + m.substring(0, 6) + "\",\"lengthMs\":3600000,\"reason\":\"x\"";
            WebActions.Answer admin = WebActions.handle(server, action("s0-" + m, now, "ADMIN", "ban", staff));
            h.assertTrue(!admin.ok() && admin.message().contains("only the Owner"), "an Admin cannot ban staff who are not here (D355): " + admin.message());
            WebActions.Answer edit = WebActions.handle(server, action("s1-" + m, now, "ADMIN", "ban_edit", ",\"ban\":7,\"player\":\"" + helper + "\",\"lengthMs\":0,\"reason\":\"x\""));
            h.assertTrue(!edit.ok() && edit.message().contains("only the Owner"), "nor make a staff member's ban longer: " + edit.message());
            WebActions.Answer noPlayer = WebActions.handle(server, action("s2-" + m, now, "OWNER", "ban_edit", ",\"ban\":7,\"lengthMs\":0,\"reason\":\"x\""));
            h.assertTrue(!noPlayer.ok(), "an edit names the ban's player");
            WebActions.Answer owner = WebActions.handle(server, action("s3-" + m, now, "OWNER", "ban", staff));
            h.assertTrue(owner.ok() && owner.later() != null, "the Owner may: " + owner.message());

            // a ban written elsewhere reaches a player on this server when the bans are read again (D350, audit)
            WebBans.usePlayersForTests(s -> List.of(vexa));
            WebBans.replace(List.of(new StorageBackend.Ban(41, vexa.getUUID(), "Vexa", "Dupe " + m, "", "Ana", now, now + 86_400_000L, 0, "", 0),
                    new StorageBackend.Ban(42, vexa.getUUID(), "Vexa", "Older", "", "Ana", now, now + 3_600_000L, 0, "", 0)));
            h.assertTrue(WebBans.sendOff(server) == 1 && kicked.size() == 1 && kicked.get(0).getString().contains("Dupe " + m), "sent off the network with the screen: " + kicked);
            WebBans.forget(vexa.getUUID(), 42);
            h.assertTrue(WebBans.banOf(vexa.getUUID()) != null && WebBans.banOf(vexa.getUUID()).id() == 41, "lifting another ban of theirs keeps this one in force");
            WebBans.forget(vexa.getUUID(), 41);
            h.assertTrue(WebBans.banOf(vexa.getUUID()) == null && WebBans.sendOff(server) == 0, "lifted: stays");
        } finally {
            cleanUp();
        }
        h.succeed();
    }

    private static boolean told(MemoryBackend db) {
        try {
            return db.fetchNoticesAfter(0, 100).stream().anyMatch(n -> n.kind().equals(WebBans.KIND));
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanUp() {
        Storage.useForTests(null);
        WebBans.clearForTests();
        WebBans.useForTests(null, null);
        WebBanActions.useForTests(null, null);
        ProxyKick.useReal();
        WebActions.useReal();
        WebAgent.useReal();
    }
}
