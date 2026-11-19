package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bounties on the staff panel (SU-75; D524 to D534): the board, the history and the player search for every rank, notes
 * included; adds, removes, extends and blanks only for Admins, Owners and the bounty power, with a reason, passed to BSP-Core
 * exactly, refused targets stopped before BSP-Core, every answer worded, the Action Log only on {@code ok} (and as unsure on
 * an error); and the real BSP-Core 1.19 the test server loads, end to end.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebBountyGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];
    private static final long H = 3_600_000L, D = 24 * H;

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 3 + 17);
        }
    }

    private static String action(String id, long at, String rank, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Admin\",\"rank\":\"" + rank + "\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    private static WebActions.Answer done(WebActions.Answer a) {
        return a.later() == null ? a : a.later().getNow(new WebActions.Answer(a.id(), false, "not yet answered"));
    }

    /** As strict as BSP-Core's api: server thread only, no nulls, {@code by} one line of at most 64 characters. */
    static final class FakeBounties implements BspCore.Bounties {
        final List<BspCore.Bounty> open = new ArrayList<>();
        final List<BspCore.BountyClosed> closed = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        String answer = "ok", refusal = null;
        boolean broken;

        @Override
        public List<BspCore.Bounty> open(MinecraftServer s) {
            check(s, "");
            if (broken) {
                throw new IllegalStateException("broken (test)");
            }
            return List.copyOf(open);
        }

        @Override
        public List<BspCore.BountyClosed> history(MinecraftServer s) {
            check(s, "");
            return List.copyOf(closed);
        }

        @Override
        public void players(MinecraftServer s, String search, Consumer<List<BspCore.BountyPlayer>> then, Runnable failed) {
            check(s, search);
            then.accept(List.of(new BspCore.BountyPlayer(UUID.randomUUID(), "Wren_88", true), new BspCore.BountyPlayer(UUID.randomUUID(), "Glimmer", false)));
        }

        @Override
        public String targetRefusal(MinecraftServer s, UUID target, String name) {
            check(s, target);
            check(s, name);
            return name.equals("Glimmer") ? "staff" : refusal;
        }

        @Override
        public List<String> tiers() {
            return List.of("copper", "gold", "diamond", "netherite", "illyrium");
        }

        @Override
        public void add(MinecraftServer s, String by, UUID target, String targetName, int coinTier, long amount, long bonus, long bonusWithinMs, long durationMs,
                        String notes, Consumer<BspCore.BountyResult> then) {
            check(s, target);
            check(s, notes);
            by(by);
            calls.add("add " + targetName + " " + coinTier + " " + amount + " " + bonus + " " + bonusWithinMs + " " + durationMs + " [" + notes + "] " + by);
            then.accept(new BspCore.BountyResult(answer, null));
        }

        @Override
        public void remove(MinecraftServer s, String id, boolean refund, String by, Consumer<BspCore.BountyResult> then) {
            check(s, id);
            by(by);
            calls.add("remove " + id + " " + refund + " " + by);
            then.accept(new BspCore.BountyResult(answer, open.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null))); // the bounty as it was, as BSP-Core gives it
        }

        @Override
        public void extend(MinecraftServer s, String id, long addMs, String by, Consumer<BspCore.BountyResult> then) {
            check(s, id);
            by(by);
            calls.add("extend " + id + " " + addMs + " " + by);
            then.accept(new BspCore.BountyResult(answer, open.isEmpty() ? null : open.get(0)));
        }

        @Override
        public void blankNotes(MinecraftServer s, String id, String by, Consumer<BspCore.BountyResult> then) {
            check(s, id);
            by(by);
            calls.add("blank " + id + " " + by);
            then.accept(new BspCore.BountyResult(answer, null));
        }

        private static void by(String by) {
            if (by == null || by.length() > 64 || by.contains("\n")) {
                throw new IllegalArgumentException("by: one line, at most 64 characters");
            }
        }

        private static void check(MinecraftServer s, Object arg) {
            if (s == null || arg == null) {
                throw new NullPointerException("no argument may be null");
            }
            if (!s.isSameThread()) {
                throw new IllegalStateException("BSP-Core's bounties are server thread only");
            }
        }
    }

    private static List<LogEntry> bountyLog(MinecraftServer server) {
        return ActionLog.recent(server, 200, false, List.of(LogEntry.BOUNTY));
    }

    @GameTest(template = EMPTY)
    public void the_board_is_read_by_everyone_and_changed_by_those_who_may(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        String m = "y" + Integer.toHexString(new java.util.Random().nextInt());
        FakeBounties b = new FakeBounties();
        UUID wren = UUID.randomUUID(), kes = UUID.randomUUID();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            BspCore.useBountiesForTests(null);
            WebActions.Answer none = WebActions.handle(server, action("n-" + m, now, "MODERATOR", "bounty_list", ""));
            h.assertTrue(!none.ok() && none.message().contains("BSP-Core 1.19.0"), "without BSP-Core 1.19: said plainly: " + none.message());
            h.assertTrue(!WebSnapshot.build(server, "alpha", List.of(), now).get("bounties").getAsBoolean(), "the snapshot: no bounties here");

            BspCore.useBountiesForTests(b);
            h.assertTrue(WebSnapshot.build(server, "alpha", List.of(), now).get("bounties").getAsBoolean(), "the snapshot says bounties are here");
            b.open.add(new BspCore.Bounty("b-1", wren, "Wren_88", kes, "Kestrel_88", false, -1, 25_000, 5_000, now + D, 1_250, now - H, now + 3 * D,
                    "camps the nether hub", "frostwarf", List.of(new BspCore.BountyHunter(UUID.randomUUID(), "Mossy_Fen", now - 60_000))));
            b.open.add(new BspCore.Bounty("b-2", kes, "Kestrel_88", UUID.randomUUID(), "Server", true, 2, 3, 0, 0, 0, now - 2 * H, now + D, "", "moongrove", List.of()));
            b.closed.add(new BspCore.BountyClosed(b.open.get(0), "CLAIMED", now - 5000, kes, "Kestrel_88", "", true));
            b.closed.add(new BspCore.BountyClosed(b.open.get(1), "SOMETHING_NEW", now - 9000, null, "", "Ana_Admin (panel)", false));

            // ---- reads: every rank (D524), notes included (D525)
            WebActions.Answer list = done(WebActions.handle(server, action("l-" + m, now, "MODERATOR", "bounty_list", "")));
            h.assertTrue(list.ok(), "a Moderator reads the board: " + list.message());
            JsonArray open = list.data().getAsJsonObject().getAsJsonArray("open");
            JsonObject b1 = open.get(0).getAsJsonObject();
            h.assertTrue(open.size() == 2 && b1.get("notes").getAsString().equals("camps the nether hub") && b1.get("amount").getAsLong() == 25_000 && b1.get("tier").getAsInt() == -1
                    && b1.get("bonus").getAsLong() == 5_000 && b1.get("fee").getAsLong() == 1_250 && b1.getAsJsonArray("hunters").get(0).getAsJsonObject().get("name").getAsString().equals("Mossy_Fen")
                    && b1.get("placerName").getAsString().equals("Kestrel_88") && !b1.get("byServer").getAsBoolean(), "a bounty with its notes, hunters, fee and bonus: " + b1);
            h.assertTrue(open.get(1).getAsJsonObject().get("byServer").getAsBoolean() && list.data().getAsJsonObject().getAsJsonArray("tiers").size() == 5, "a staff bounty, and the tiers");
            WebActions.Answer hist = done(WebActions.handle(server, action("h-" + m, now, "MODERATOR", "bounty_history", "")));
            JsonArray hl = hist.data().getAsJsonObject().getAsJsonArray("history");
            h.assertTrue(hist.ok() && hl.size() == 2 && hl.get(0).getAsJsonObject().get("ending").getAsString().equals("CLAIMED") && hl.get(0).getAsJsonObject().get("bonusPaid").getAsBoolean()
                    && hl.get(1).getAsJsonObject().get("ending").getAsString().equals("SOMETHING_NEW") && hl.get(1).getAsJsonObject().get("hunter").getAsString().isEmpty(),
                    "the history, an unknown ending passed on as it is, no hunter as empty: " + hl);
            WebActions.Answer ps = done(WebActions.handle(server, action("p-" + m, now, "MODERATOR", "bounty_players", ",\"search\":\"wr\"")));
            JsonArray pl = ps.data().getAsJsonObject().getAsJsonArray("players");
            h.assertTrue(ps.ok() && pl.size() == 2 && pl.get(0).getAsJsonObject().get("refusal").getAsString().isEmpty() && pl.get(1).getAsJsonObject().get("refusal").getAsString().equals("staff"),
                    "the add form's search, with who may not be a target: " + pl);
            h.assertTrue(!done(WebActions.handle(server, action("p2-" + m, now, "MODERATOR", "bounty_players", ",\"search\":\"no spaces!\""))).ok(), "a search that cannot be a name is refused");
            b.broken = true;
            WebActions.Answer broken = done(WebActions.handle(server, action("l2-" + m, now, "MODERATOR", "bounty_list", "")));
            h.assertTrue(!broken.ok() && broken.message().contains("could not be read"), "an error is said as one, never an empty board: " + broken.message());
            b.broken = false;

            // ---- adds (D526, D529, D531)
            String add = ",\"target\":\"" + wren + "\",\"name\":\"Wren_88\",\"tier\":-1,\"amount\":25000,\"bonus\":5000,\"bonusWithinMs\":" + D + ",\"durationMs\":" + 3 * D
                    + ",\"notes\":\"Seen near spawn\",\"reason\":\"Event bounty\"";
            int logged = bountyLog(server).size();
            WebActions.Answer mod = done(WebActions.handle(server, action("a1-" + m, now, "MODERATOR", "bounty_add", add)));
            h.assertTrue(!mod.ok() && b.calls.isEmpty() && mod.message().contains("bounty power"), "a Moderator cannot add, even with a signed action: " + mod.message());
            WebActions.Answer noWhy = done(WebActions.handle(server, action("a2-" + m, now, "ADMIN", "bounty_add", add.replace("Event bounty", " "))));
            h.assertTrue(!noWhy.ok() && b.calls.isEmpty() && noWhy.message().contains("Say why"), "no reason, no bounty (D531)");
            WebActions.Answer staff = done(WebActions.handle(server, action("a3-" + m, now, "ADMIN", "bounty_add", add.replace("Wren_88", "Glimmer"))));
            h.assertTrue(!staff.ok() && b.calls.isEmpty() && staff.message().contains("Staff cannot"), "a staff target stopped before BSP-Core (D529): " + staff.message());
            WebActions.Answer added = done(WebActions.handle(server, action("a4-" + m, now, "MODERATOR", "bounty_add", add + ",\"bountyPower\":true")));
            h.assertTrue(added.ok() && b.calls.equals(List.of("add Wren_88 -1 25000 5000 " + D + " " + 3 * D + " [Seen near spawn] Ana_Admin (panel)")),
                    "a Moderator with the bounty power adds; BSP-Core gets exactly the target, price, bonus, window, time, notes and who: " + b.calls);
            h.assertTrue(added.message().startsWith("Placed on Wren_88: 25,000 pieces for 3 days, bonus 5,000 pieces within 1 day"), "the answer says what was placed: " + added.message());
            h.assertTrue(bountyLog(server).size() == logged + 1 && bountyLog(server).get(0).text().equals("bounty add on Wren_88: 25,000 pieces for 3 days, bonus 5,000 pieces within 1 day: Event bounty")
                    && bountyLog(server).get(0).actor().equals("Ana_Admin (panel)"), "in the Action Log with who and why: " + bountyLog(server).get(0).text());
            b.calls.clear();
            done(WebActions.handle(server, action("a5-" + m, now, "OWNER", "bounty_add", add.replace("\"tier\":-1", "\"tier\":2").replace("\"amount\":25000", "\"amount\":3").replace("\"bonus\":5000", "\"bonus\":0"))));
            h.assertTrue(b.calls.get(0).startsWith("add Wren_88 2 3 0 0 "), "coins of one tier, no bonus means no window: " + b.calls);
            b.calls.clear();
            for (String bad : List.of(add.replace("\"tier\":-1", "\"tier\":9"), add.replace("\"amount\":25000", "\"amount\":0"), add.replace("\"amount\":25000", "\"amount\":1.5"),
                    add.replace(wren.toString(), "nope"), add.replace("\"durationMs\":" + 3 * D, "\"durationMs\":-1"))) {
                h.assertTrue(!done(WebActions.handle(server, action("a6" + bad.hashCode() + "-" + m, now, "OWNER", "bounty_add", bad))).ok(), "refused: " + bad);
            }
            h.assertTrue(b.calls.isEmpty(), "nothing bad reached BSP-Core: " + b.calls);
            logged = bountyLog(server).size();
            for (String key : List.of("unreachable", "time", "price", "bonus", "bonus_time", "staff", "protected")) {
                b.answer = key;
                WebActions.Answer no = done(WebActions.handle(server, action("a7" + key + "-" + m, now, "OWNER", "bounty_add", add)));
                h.assertTrue(!no.ok() && no.data().getAsJsonObject().get("result").getAsString().equals(key) && !no.message().contains("(" + key + ")"), "a refused add worded (" + key + "): " + no.message());
            }
            h.assertTrue(bountyLog(server).size() == logged, "a refused add is not in the Action Log");
            b.answer = "ok";

            // ---- remove, extend, blank (D530, D532)
            b.calls.clear();
            String one = ",\"bounty\":\"b-1\",\"targetName\":\"Wren_88\",\"reason\":\"Abuse of the board\"";
            h.assertTrue(!done(WebActions.handle(server, action("r1-" + m, now, "MODERATOR", "bounty_remove", one + ",\"refund\":true"))).ok() && b.calls.isEmpty(), "a Moderator cannot remove");
            WebActions.Answer rm = done(WebActions.handle(server, action("r2-" + m, now, "ADMIN", "bounty_remove", one + ",\"refund\":true")));
            WebActions.Answer rm2 = done(WebActions.handle(server, action("r3-" + m, now, "ADMIN", "bounty_remove", one)));
            h.assertTrue(rm.ok() && rm2.ok() && b.calls.equals(List.of("remove b-1 true Ana_Admin (panel)", "remove b-1 false Ana_Admin (panel)"))
                    && rm.message().contains("Kestrel_88 has the reward and bonus back") && rm2.message().contains("nothing was given back"), "with and without a refund, said so: " + rm.message());
            h.assertTrue(bountyLog(server).get(0).text().equals("bounty remove b-1 on Wren_88 (no refund): Abuse of the board")
                    && bountyLog(server).get(1).text().equals("bounty remove b-1 on Wren_88 (refunded): Abuse of the board"), "both in the Action Log");
            WebActions.Answer rmServer = done(WebActions.handle(server, action("r4-" + m, now, "ADMIN", "bounty_remove", one.replace("b-1", "b-2").replace("Wren_88", "Kestrel_88") + ",\"refund\":true")));
            h.assertTrue(rmServer.ok() && rmServer.message().contains("placed by the server, so nothing went back") && !rmServer.message().contains("has the reward")
                    && bountyLog(server).get(0).text().contains("(placed by the server: nothing to give back)"), "a staff bounty removed \"with a refund\" says nothing went back (audit): " + rmServer.message());
            String longNotes = "n".repeat(400);
            b.calls.clear();
            done(WebActions.handle(server, action("a8-" + m, now, "OWNER", "bounty_add", add.replace("Seen near spawn", longNotes))));
            h.assertTrue(b.calls.get(0).contains("[" + longNotes + "]"), "notes over 256 reach BSP-Core whole (it cuts to its own limit; audit)");
            b.calls.clear();
            WebActions.Answer ex = done(WebActions.handle(server, action("e1-" + m, now, "OWNER", "bounty_extend", one + ",\"addMs\":" + D)));
            h.assertTrue(ex.ok() && b.calls.equals(List.of("extend b-1 " + D + " Ana_Admin (panel)")) && ex.data().getAsJsonObject().has("bounty"), "extended by a day: " + ex.message());
            h.assertTrue(!done(WebActions.handle(server, action("e2-" + m, now, "OWNER", "bounty_extend", one + ",\"addMs\":0"))).ok() && b.calls.size() == 1, "no time to add: refused here");
            WebActions.Answer modEx = done(WebActions.handle(server, action("e3-" + m, now, "MODERATOR", "bounty_extend", one + ",\"addMs\":" + H + ",\"bountyPower\":true")));
            h.assertTrue(modEx.ok() && b.calls.get(1).equals("extend b-1 " + H + " Ana_Admin (panel)"), "a Moderator with the power extends the bounty by its id (the power is not the id): " + b.calls);
            b.calls.remove(1);
            WebActions.Answer bl = done(WebActions.handle(server, action("k1-" + m, now, "OWNER", "bounty_blank_notes", one)));
            h.assertTrue(bl.ok() && b.calls.get(1).equals("blank b-1 Ana_Admin (panel)") && bountyLog(server).get(0).text().equals("bounty notes blanked b-1 on Wren_88: Abuse of the board"), "notes blanked, logged");
            h.assertTrue(!done(WebActions.handle(server, action("k2-" + m, now, "OWNER", "bounty_blank_notes", one.replace("b-1", "b 1; x")))).ok(), "a bounty id that cannot be one is refused");
            logged = bountyLog(server).size();
            for (String key : List.of("unreachable", "gone", "max")) {
                b.answer = key;
                WebActions.Answer no = done(WebActions.handle(server, action("x" + key + "-" + m, now, "OWNER", "bounty_extend", one + ",\"addMs\":" + D)));
                h.assertTrue(!no.ok() && !no.message().contains("(" + key + ")"), "a refused change worded (" + key + "): " + no.message());
            }
            h.assertTrue(bountyLog(server).size() == logged, "a refused change is not logged");
            b.answer = "failed";
            WebActions.Answer threw = done(WebActions.handle(server, action("xf-" + m, now, "OWNER", "bounty_remove", one + ",\"refund\":true")));
            h.assertTrue(!threw.ok() && threw.message().contains("may still have happened") && bountyLog(server).get(0).text().contains("BSP-Core error, it may have happened"),
                    "BSP-Core throwing: unsure, and logged so: " + threw.message());
        } finally {
            BspCore.useRealBounties();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    /** The real BSP-Core 1.19 in the test server (one server: it answers at once): a staff bounty added, read, extended, blanked, removed with a refund, in the history. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void the_real_bsp_core_bounties_answer_through_the_adapter(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        h.assertTrue(BspCore.bounties() != null, "BSP-Core " + BspCore.BOUNTIES_SINCE + "+ is loaded in the test server");
        long now = System.currentTimeMillis();
        String m = "z" + Integer.toHexString(new java.util.Random().nextInt());
        String name = ("Tg" + m).substring(0, Math.min(16, 2 + m.length()));
        UUID target = UUID.randomUUID();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            String add = ",\"target\":\"" + target + "\",\"name\":\"" + name + "\",\"tier\":-1,\"amount\":2000,\"bonus\":0,\"durationMs\":" + 2 * D
                    + ",\"notes\":\"test notes\",\"reason\":\"test\"";
            WebActions.Answer added = done(WebActions.handle(server, action("q1-" + m, now, "ADMIN", "bounty_add", add)));
            String key = added.data() == null ? "?" : added.data().getAsJsonObject().get("result").getAsString();
            if (key.equals("protected")) { // a new player under protection on this test world: the refusal is the real one, worded
                h.assertTrue(added.message().contains("new-player protection"), "protected, worded: " + added.message());
                h.succeed();
                return;
            }
            h.assertTrue(added.ok(), "a staff bounty placed through the real BSP-Core: " + key + " " + added.message());
            WebActions.Answer list = done(WebActions.handle(server, action("q2-" + m, now, "MODERATOR", "bounty_list", "")));
            JsonObject mine = null;
            for (var e : list.data().getAsJsonObject().getAsJsonArray("open")) {
                if (e.getAsJsonObject().get("target").getAsString().equals(target.toString())) {
                    mine = e.getAsJsonObject();
                }
            }
            h.assertTrue(mine != null && mine.get("amount").getAsLong() == 2000 && mine.get("byServer").getAsBoolean() && mine.get("notes").getAsString().equals("test notes"),
                    "on the board, by the server, with its notes: " + mine);
            String one = ",\"bounty\":\"" + mine.get("id").getAsString() + "\",\"targetName\":\"" + name + "\",\"reason\":\"test\"";
            long ends = mine.get("ends").getAsLong();
            WebActions.Answer ex = done(WebActions.handle(server, action("q3-" + m, now, "ADMIN", "bounty_extend", one + ",\"addMs\":" + H)));
            h.assertTrue(ex.ok() && ex.data().getAsJsonObject().getAsJsonObject("bounty").get("ends").getAsLong() >= ends + H - 1000, "an hour more: " + ex.message());
            WebActions.Answer bl = done(WebActions.handle(server, action("q4-" + m, now, "ADMIN", "bounty_blank_notes", one)));
            h.assertTrue(bl.ok(), "notes blanked: " + bl.message());
            WebActions.Answer rm = done(WebActions.handle(server, action("q5-" + m, now, "ADMIN", "bounty_remove", one + ",\"refund\":true")));
            h.assertTrue(rm.ok() && rm.message().contains("placed by the server, so nothing went back"), "a staff bounty removed with a refund: the real BSP-Core's bounty says it gives nothing back: " + rm.message());
            WebActions.Answer again = done(WebActions.handle(server, action("q6-" + m, now, "ADMIN", "bounty_remove", one + ",\"refund\":true")));
            h.assertTrue(!again.ok() && again.data().getAsJsonObject().get("result").getAsString().equals("gone"), "twice: gone: " + again.message());
            WebActions.Answer hist = done(WebActions.handle(server, action("q7-" + m, now, "MODERATOR", "bounty_history", "")));
            boolean found = false;
            for (var e : hist.data().getAsJsonObject().getAsJsonArray("history")) {
                JsonObject c = e.getAsJsonObject();
                if (c.getAsJsonObject("bounty").get("target").getAsString().equals(target.toString())) {
                    found = c.get("ending").getAsString().equals("REMOVED") && c.get("by").getAsString().contains("Ana_Admin");
                }
            }
            h.assertTrue(found, "in the history as REMOVED, by the staff member");
        } finally {
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }
}
