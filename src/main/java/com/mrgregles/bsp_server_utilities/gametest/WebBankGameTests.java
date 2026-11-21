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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A player's Bank & Market on the staff panel (SU-70; D502 to D511): the read by UUID or name with the account, the coin
 * tiers, the log and the market as display copies; the database down and an unreadable log said as such; refunds and
 * cancels only for Admins, Owners and the BANK power, with a reason, passed to BSP-Core exactly, every answer worded, the
 * Action Log only on {@code ok}; and the real BSP-Core 1.18 the test server loads, end to end through the adapter.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebBankGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 5 + 11);
        }
    }

    private static String action(String id, long at, String rank, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Admin\",\"rank\":\"" + rank + "\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    private static WebActions.Answer done(WebActions.Answer a) {
        return a.later() == null ? a : a.later().getNow(new WebActions.Answer(a.id(), false, "not yet answered"));
    }

    /** As strict as BSP-Core's api: server thread only, no nulls, {@code by} at most 64 characters on one line. */
    static final class FakeBank implements BspCore.Bank {
        final List<BspCore.Row> rows = new ArrayList<>();
        final List<BspCore.Lot> lots = new ArrayList<>();
        final List<BspCore.Order> orders = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        BspCore.Account account;
        boolean down, logDown, broken;
        String refundAnswer = "ok";
        BspCore.Cancelled cancelAnswer = new BspCore.Cancelled("ok", 0);
        MinecraftServer server;

        @Override
        public void findPlayer(MinecraftServer s, String name, Consumer<BspCore.Named> then, Runnable failed) {
            check(s, name);
            then.accept(account != null && account.name().equalsIgnoreCase(name) ? new BspCore.Named(account.player(), account.name()) : null);
        }

        @Override
        public void account(MinecraftServer s, UUID player, Consumer<BspCore.Account> then, Runnable failed) {
            check(s, player);
            if (broken) {
                failed.run();
                return;
            }
            then.accept(down ? null : account != null && account.player().equals(player) ? account : new BspCore.Account(player, "?", 0, List.of(0, 0, 0), false));
        }

        @Override
        public void log(MinecraftServer s, UUID player, Consumer<List<BspCore.Row>> then, Runnable failed) {
            check(s, player);
            then.accept(logDown ? null : List.copyOf(rows));
        }

        @Override
        public List<BspCore.Lot> lotsOf(MinecraftServer s, UUID seller) {
            check(s, seller);
            return lots.stream().filter(l -> l.seller().equals(seller)).toList();
        }

        @Override
        public List<BspCore.Order> ordersOf(MinecraftServer s, UUID buyer) {
            check(s, buyer);
            return orders.stream().filter(o -> o.buyer().equals(buyer)).toList();
        }

        @Override
        public List<String> tiers() {
            return List.of("Copper", "Silver", "Gold");
        }

        @Override
        public void refund(MinecraftServer s, UUID player, long rowId, long pieces, int coins, boolean alsoOther, String by, Consumer<String> then) {
            check(s, player);
            by(by);
            calls.add("refund " + player + " " + rowId + " " + pieces + " " + coins + " " + alsoOther + " " + by);
            then.accept(refundAnswer);
        }

        @Override
        public void cancelLot(MinecraftServer s, String lotId, String by, Consumer<BspCore.Cancelled> then) {
            check(s, lotId);
            by(by);
            calls.add("lot " + lotId + " " + by);
            then.accept(cancelAnswer);
        }

        @Override
        public void cancelOrder(MinecraftServer s, String orderId, String by, Consumer<BspCore.Cancelled> then) {
            check(s, orderId);
            by(by);
            calls.add("order " + orderId + " " + by);
            then.accept(cancelAnswer);
        }

        private static void by(String by) {
            if (by == null || by.length() > 64 || by.contains("\n")) {
                throw new IllegalArgumentException("by: one line, at most 64 characters");
            }
        }

        private void check(MinecraftServer s, Object arg) {
            if (s == null || arg == null) {
                throw new NullPointerException("no argument may be null");
            }
            if (!s.isSameThread()) {
                throw new IllegalStateException("BSP-Core's bank is server thread only");
            }
            server = s;
        }
    }

    private static ItemStack book() {
        ItemStack b = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag t = b.getOrCreateTag();
        ListTag pages = new ListTag();
        for (int i = 0; i < 50; i++) {
            pages.add(StringTag.valueOf("{\"text\":\"" + "page text ".repeat(100) + "\"}"));
        }
        t.put("pages", pages);
        t.putString("title", "A long story");
        t.putString("author", "Kestrel_88");
        return b;
    }

    private static ItemStack shulker() {
        ItemStack box = new ItemStack(Items.PURPLE_SHULKER_BOX);
        CompoundTag bet = new CompoundTag();
        ListTag items = new ListTag();
        CompoundTag d = new ItemStack(Items.DIAMOND, 24).save(new CompoundTag());
        d.putByte("Slot", (byte) 4);
        items.add(d);
        bet.put("Items", items);
        box.getOrCreateTag().put("BlockEntityTag", bet);
        return box;
    }

    private static List<LogEntry> bankLog(MinecraftServer server) {
        return ActionLog.recent(server, com.mrgregles.bsp_server_utilities.log.LogData.MAX, false, List.of(LogEntry.BANK)); // the whole log: the test world keeps a week of earlier runs (200 filled up)
    }

    @GameTest(template = EMPTY)
    public void a_players_bank_and_market_are_read_and_refunded_by_those_who_may(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        String m = "b" + Integer.toHexString(new java.util.Random().nextInt());
        FakeBank bank = new FakeBank();
        UUID wren = UUID.randomUUID(), steve = UUID.randomUUID();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            BspCore.useBankForTests(null);
            WebActions.Answer none = WebActions.handle(server, action("n0-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\""));
            h.assertTrue(!none.ok() && none.message().contains("BSP-Core 1.18.0"), "without BSP-Core 1.18: said plainly (D511): " + none.message());

            BspCore.useBankForTests(bank);
            bank.account = new BspCore.Account(wren, "Wren_88", 12_400, List.of(3, 0, 2), true);
            bank.rows.add(new BspCore.Row(1234, now - 60_000, "PAID", "Steve_42", "for the diamonds", -320, -1, 0, "frostwarf", 0, 0, 0, 320, 0, true));
            bank.rows.add(new BspCore.Row(1200, now - 120_000, "CONVERT", "", "x".repeat(500), 900, 2, -3, "frostwarf", 100, 1, 0, 800, 2, false));
            bank.rows.add(new BspCore.Row(1199, now - 130_000, "SOMETHING_NEW", "", "", 5, -1, 0, "moongrove", 0, 0, 0, 5, 0, false));
            bank.lots.add(new BspCore.Lot("lot-7", wren, "Wren_88", 4800, now - 7_200_000, now + 600_000_000, "frostwarf", List.of(book(), shulker(), ItemStack.EMPTY)));
            bank.lots.add(new BspCore.Lot("lot-8", steve, "Steve_42", 1, now, now + 1000, "frostwarf", List.of(new ItemStack(Items.DIRT))));
            bank.orders.add(new BspCore.Order("ord-3", wren, "Wren_88", new ItemStack(Items.NETHERITE_INGOT), 4, 1, 2400, now - 1000, now + 400_000_000, "moongrove"));

            // ---- bank_read: every rank (D502)
            WebActions.Answer read = done(WebActions.handle(server, action("r1-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\"")));
            h.assertTrue(read.ok(), "a Moderator reads a player's bank: " + read.message());
            JsonObject d = read.data().getAsJsonObject();
            h.assertTrue(d.getAsJsonObject("player").get("name").getAsString().equals("Wren_88") && d.getAsJsonObject("player").get("known").getAsBoolean()
                    && d.get("pieces").getAsLong() == 12_400 && d.getAsJsonArray("coins").toString().equals("[3,0,2]")
                    && d.getAsJsonArray("tiers").toString().equals("[\"Copper\",\"Silver\",\"Gold\"]") && !d.get("logDown").getAsBoolean(), "the account, coins and tiers: " + d);
            JsonArray log = d.getAsJsonArray("log");
            JsonObject r0 = log.get(0).getAsJsonObject(), r1 = log.get(1).getAsJsonObject();
            h.assertTrue(log.size() == 3 && r0.get("id").getAsLong() == 1234 && r0.get("pieces").getAsLong() == -320 && r0.get("payment").getAsBoolean()
                    && r0.get("piecesLeft").getAsLong() == 320 && r0.get("with").getAsString().equals("Steve_42"), "a payment row, newest first, with what is left: " + r0);
            h.assertTrue(r1.get("coins").getAsInt() == -3 && r1.get("tier").getAsInt() == 2 && r1.get("coinsLeft").getAsInt() == 2 && r1.get("refundedPieces").getAsLong() == 100
                    && r1.get("detail").getAsString().length() <= 200, "a conversion's coins and pieces apart, a long detail cut: " + r1.get("detail").getAsString().length());
            h.assertTrue(log.get(2).getAsJsonObject().get("type").getAsString().equals("SOMETHING_NEW"), "an unknown type passed as it is");
            JsonArray lots = d.getAsJsonArray("lots");
            h.assertTrue(lots.size() == 1 && lots.get(0).getAsJsonObject().get("id").getAsString().equals("lot-7"), "only their own lots: " + lots.size());
            JsonArray items = lots.get(0).getAsJsonObject().getAsJsonArray("items");
            h.assertTrue(items.size() == 2 && items.toString().length() < 600 && !items.toString().contains("page text") && !items.get(1).getAsJsonObject().has("in"),
                    "lots as display copies: no book pages, no box contents, empty stacks left out: " + items);
            JsonObject o = d.getAsJsonArray("orders").get(0).getAsJsonObject();
            h.assertTrue(o.get("wanted").getAsInt() == 4 && o.get("filled").getAsInt() == 1 && o.get("each").getAsLong() == 2400, "their request: " + o);

            WebMarketGameTests.FakeEconomy eco = new WebMarketGameTests.FakeEconomy();
            eco.server = server;
            BspCore.useEconomyForTests(eco);
            WebActions.Answer fresh = done(WebActions.handle(server, action("r1b-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\",\"refresh\":true")));
            h.assertTrue(fresh.ok() && eco.refreshes == 1, "REFRESH has BSP-Core read the market again first: " + eco.refreshes);
            eco.holdRefresh = true;
            WebActions.Answer waits = WebActions.handle(server, action("r1c-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\",\"refresh\":true"));
            h.assertTrue(waits.later() != null && !waits.later().isDone(), "and the answer waits for it");
            eco.held.run();
            h.assertTrue(waits.later().isDone() && waits.later().getNow(null).ok(), "then comes");
            WebActions.Answer plain = done(WebActions.handle(server, action("r1d-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\"")));
            h.assertTrue(plain.ok() && eco.refreshes == 2, "a plain read does not read the market again");
            BspCore.useRealEconomy();
            WebActions.Answer byName = done(WebActions.handle(server, action("r2-" + m, now, "MODERATOR", "bank_read", ",\"name\":\"wren_88\"")));
            h.assertTrue(byName.ok() && byName.data().getAsJsonObject().getAsJsonObject("player").get("id").getAsString().equals(wren.toString()), "found by name: " + byName.message());
            WebActions.Answer nobody = done(WebActions.handle(server, action("r3-" + m, now, "MODERATOR", "bank_read", ",\"name\":\"Nobody_Here\"")));
            h.assertTrue(!nobody.ok() && nobody.message().contains("Nobody called Nobody_Here"), "nobody by that name: " + nobody.message());
            WebActions.Answer badName = done(WebActions.handle(server, action("r3b-" + m, now, "MODERATOR", "bank_read", ",\"name\":\"no spaces!\"")));
            h.assertTrue(!badName.ok() && badName.message().equals("No such player."), "a name that cannot be one is refused before BSP-Core");

            bank.logDown = true;
            WebActions.Answer noLog = done(WebActions.handle(server, action("r4-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\"")));
            h.assertTrue(noLog.ok() && noLog.data().getAsJsonObject().get("logDown").getAsBoolean() && noLog.data().getAsJsonObject().getAsJsonArray("log").isEmpty()
                    && noLog.message().contains("log cannot be read"), "an unreadable log said so, never an empty log: " + noLog.message());
            bank.logDown = false;
            bank.down = true;
            WebActions.Answer down = done(WebActions.handle(server, action("r5-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\"")));
            h.assertTrue(!down.ok() && down.data().getAsJsonObject().get("down").getAsBoolean() && down.message().contains("not connected"),
                    "the database down: said so, never an empty bank: " + down.message());
            bank.down = false;
            bank.broken = true;
            WebActions.Answer broken = done(WebActions.handle(server, action("r6-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + wren + "\"")));
            h.assertTrue(!broken.ok() && broken.message().contains("could not be read") && !broken.message().contains("not connected"),
                    "an error is said as an error: " + broken.message());
            bank.broken = false;

            // ---- bank_refund: Admins, Owners, the BANK power (D503, D504), a reason (D510)
            String pay = ",\"player\":\"" + wren + "\",\"name\":\"Wren_88\",\"row\":1234,\"type\":\"PAID\",\"with\":\"Steve_42\",\"pieces\":320,\"coins\":0,\"other\":true,\"reason\":\"Paid by mistake\"";
            int logged = bankLog(server).size();
            WebActions.Answer mod = done(WebActions.handle(server, action("f1-" + m, now, "MODERATOR", "bank_refund", pay)));
            h.assertTrue(!mod.ok() && bank.calls.isEmpty() && mod.message().contains("bank power"), "a Moderator cannot refund, even with a signed action: " + mod.message());
            WebActions.Answer noWhy = done(WebActions.handle(server, action("f2-" + m, now, "ADMIN", "bank_refund", pay.replace("Paid by mistake", "  "))));
            h.assertTrue(!noWhy.ok() && bank.calls.isEmpty() && noWhy.message().contains("Say why"), "no reason, no refund (D510)");
            WebActions.Answer powered = done(WebActions.handle(server, action("f3-" + m, now, "MODERATOR", "bank_refund", pay + ",\"bank\":true")));
            h.assertTrue(powered.ok() && bank.calls.equals(List.of("refund " + wren + " 1234 320 0 true Ana_Admin (panel)")),
                    "a Moderator with the BANK power refunds; BSP-Core gets exactly the row, amounts, other and who: " + bank.calls);
            h.assertTrue(powered.message().equals("Refunded 320 pieces of Wren_88's row 1234, also on Steve_42. They are told in game."), "the answer says what went out: " + powered.message());
            List<LogEntry> after = bankLog(server);
            h.assertTrue(after.size() == logged + 1 && after.get(0).actor().equals("Ana_Admin (panel)")
                    && after.get(0).text().equals("refund Wren_88 row 1234 (PAID Steve_42): 320 pieces, also on Steve_42: Paid by mistake"),
                    "the refund is in the Action Log with the staff member and the reason: " + after.get(0).text());

            bank.calls.clear();
            String conv = ",\"player\":\"" + wren + "\",\"name\":\"Wren_88\",\"row\":1200,\"type\":\"CONVERT\",\"tier\":2,\"pieces\":0,\"coins\":2,\"reason\":\"Wrong button\"";
            WebActions.Answer coins = done(WebActions.handle(server, action("f4-" + m, now, "OWNER", "bank_refund", conv)));
            h.assertTrue(coins.ok() && bank.calls.equals(List.of("refund " + wren + " 1200 0 2 false Ana_Admin (panel)")) && coins.message().startsWith("Refunded 2 Gold coins of"),
                    "a conversion's coins alone, by tier name: " + coins.message());

            bank.calls.clear();
            for (String bad : List.of(pay.replace("\"pieces\":320", "\"pieces\":-5"), pay.replace("\"pieces\":320", "\"pieces\":1.5"), pay.replace("\"pieces\":320", "\"pieces\":0"),
                    pay.replace("\"row\":1234", "\"row\":0"), pay.replace(wren.toString(), "not-a-uuid"))) {
                WebActions.Answer no = done(WebActions.handle(server, action("f5" + bad.hashCode() + "-" + m, now, "ADMIN", "bank_refund", bad)));
                h.assertTrue(!no.ok(), "refused before BSP-Core: " + bad);
            }
            h.assertTrue(bank.calls.isEmpty(), "nothing bad reached BSP-Core: " + bank.calls);

            logged = bankLog(server).size();
            for (String key : List.of("unreachable", "no_row", "amount", "nothing", "too_much", "no_coins", "not_payment", "other_unknown", "other_row", "own_coins",
                    "already", "short", "other_short")) {
                bank.refundAnswer = key;
                WebActions.Answer no = done(WebActions.handle(server, action("f6" + key + "-" + m, now, "ADMIN", "bank_refund", pay)));
                h.assertTrue(!no.ok() && no.data().getAsJsonObject().get("result").getAsString().equals(key) && !no.message().contains("(" + key + ")")
                        && no.message().contains("othing"), "a refused refund (" + key + ") worded, nothing changed said: " + no.message());
                boolean offer = no.data().getAsJsonObject().get("withoutOther").getAsBoolean();
                h.assertTrue(offer == (key.equals("other_short") || key.equals("other_row")), "the refund without the other player offered only when they are the trouble (D508): " + key);
            }
            h.assertTrue(bankLog(server).size() == logged, "a refused refund is not in the Action Log");
            bank.refundAnswer = "failed";
            WebActions.Answer threw = done(WebActions.handle(server, action("f7-" + m, now, "ADMIN", "bank_refund", pay)));
            h.assertTrue(!threw.ok() && threw.message().contains("may still have happened") && !threw.message().contains("Nothing changed")
                    && bankLog(server).size() == logged + 1 && bankLog(server).get(0).text().contains("BSP-Core error, it may have happened"),
                    "BSP-Core throwing is never called \"nothing changed\" (it may have thrown after the money moved), and is logged as unsure (audit): " + threw.message());
            String floodgate = pay.replace("\"name\":\"Wren_88\"", "\"name\":\".Wren_88\"");
            bank.refundAnswer = "ok";
            done(WebActions.handle(server, action("f8-" + m, now, "ADMIN", "bank_refund", floodgate)));
            h.assertTrue(bankLog(server).get(0).text().startsWith("refund .Wren_88 row 1234"), "a Bedrock name behind Floodgate's dot is kept in the log (audit)");
            done(WebActions.handle(server, action("f9-" + m, now, "ADMIN", "bank_refund", pay.replace("\"name\":\"Wren_88\"", "\"name\":\"no spaces!\""))));
            h.assertTrue(bankLog(server).get(0).text().startsWith("refund " + wren + " row 1234"), "a name the log cannot show: the UUID (audit)");

            // ---- cancels (D503, D507)
            bank.calls.clear();
            String lot = ",\"lot\":\"lot-7\",\"name\":\"Wren_88\",\"what\":\"Written Book, Purple Shulker Box for 4800\",\"reason\":\"Scam listing\"";
            WebActions.Answer modLot = done(WebActions.handle(server, action("c1-" + m, now, "MODERATOR", "market_cancel_lot", lot)));
            h.assertTrue(!modLot.ok() && bank.calls.isEmpty() && modLot.message().contains("bank power"), "a Moderator cannot cancel, and is told why: " + modLot.message());
            WebActions.Answer cl = done(WebActions.handle(server, action("c2-" + m, now, "ADMIN", "market_cancel_lot", lot)));
            h.assertTrue(cl.ok() && bank.calls.equals(List.of("lot lot-7 Ana_Admin (panel)")) && cl.message().contains("Wren_88's Collect box"), "an Admin cancels a lot: " + cl.message());
            h.assertTrue(bankLog(server).get(0).text().equals("cancel lot lot-7 of Wren_88 (Written Book, Purple Shulker Box for 4800): Scam listing"), "in the Action Log");
            bank.cancelAnswer = new BspCore.Cancelled("ok", 7200);
            WebActions.Answer co = done(WebActions.handle(server, action("c3-" + m, now, "MODERATOR", "market_cancel_order",
                    ",\"order\":\"ord-3\",\"name\":\"Wren_88\",\"what\":\"4 Netherite Ingot at 2400\",\"reason\":\"Asked to\",\"bank\":true")));
            h.assertTrue(co.ok() && co.message().contains("7200 pieces went back to Wren_88's bank") && co.data().getAsJsonObject().get("piecesBack").getAsLong() == 7200
                    && bankLog(server).get(0).text().endsWith("Asked to (7200 pieces back)"), "a request cancelled, the pieces back said and logged: " + co.message());
            logged = bankLog(server).size();
            for (String key : List.of("gone", "unreachable")) {
                bank.cancelAnswer = new BspCore.Cancelled(key, 0);
                WebActions.Answer no = done(WebActions.handle(server, action("c4" + key + "-" + m, now, "OWNER", "market_cancel_lot", lot)));
                h.assertTrue(!no.ok() && !no.message().contains("(" + key + ")") && no.message().contains("Nothing changed"), "a refused cancel worded (" + key + "): " + no.message());
            }
            h.assertTrue(bankLog(server).size() == logged, "a refused cancel is not in the Action Log");
            bank.cancelAnswer = new BspCore.Cancelled("failed", 0);
            WebActions.Answer threwC = done(WebActions.handle(server, action("c4f-" + m, now, "OWNER", "market_cancel_lot", lot)));
            h.assertTrue(!threwC.ok() && threwC.message().contains("may still have happened") && bankLog(server).get(0).text().endsWith("(BSP-Core error, it may have happened)"),
                    "a cancel BSP-Core threw on: unsure, and logged so (audit): " + threwC.message());
            logged = bankLog(server).size();
            WebActions.Answer badId = done(WebActions.handle(server, action("c5-" + m, now, "OWNER", "market_cancel_lot", lot.replace("lot-7", "lot 7; drop"))));
            h.assertTrue(!badId.ok() && badId.message().equals("No such lot."), "a lot id that cannot be one is refused");
            h.assertTrue(bankLog(server).size() == logged, "a refused cancel is not in the Action Log");
        } finally {
            BspCore.useRealBank();
            BspCore.useRealEconomy();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    /** The real BSP-Core 1.18 in the test server (a single server: it answers at once): a sale credited, read, refunded in part, refused past what is left. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void the_real_bsp_core_bank_refunds_through_the_adapter(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        BspCore.Bank bank = BspCore.bank();
        h.assertTrue(bank != null, "BSP-Core " + BspCore.BANK_SINCE + "+ is loaded in the test server, so the bank calls are there");
        long now = System.currentTimeMillis();
        String m = "q" + Integer.toHexString(new java.util.Random().nextInt());
        UUID who = UUID.randomUUID();
        List<Object> got = new ArrayList<>();
        com.mrgregles.bsp_core.api.BspBank.credit(server, who, 500, "Test_Buyer", "test sale " + m, got::add);
        h.runAfterDelay(10, () -> {
            h.assertTrue(got.equals(List.of(true)), "500 pieces credited: " + got);
            WebActions.useForTests(KEY, () -> now, null);
            WebAgent.useForTests(true, null, null);
            try {
                WebActions.Answer read = done(WebActions.handle(server, action("q1-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + who + "\"")));
                h.assertTrue(read.ok(), "read through the real BSP-Core: " + read.message());
                JsonObject d = read.data().getAsJsonObject();
                h.assertTrue(d.get("pieces").getAsLong() == 500 && d.getAsJsonArray("tiers").size() > 0, "500 pieces and the coin tiers: " + d.get("pieces"));
                JsonObject row = d.getAsJsonArray("log").get(0).getAsJsonObject();
                h.assertTrue(row.get("pieces").getAsLong() == 500 && row.get("piecesLeft").getAsLong() == 500 && !row.get("payment").getAsBoolean(), "the sale's row: " + row);
                long rowId = row.get("id").getAsLong();
                String base = ",\"player\":\"" + who + "\",\"row\":" + rowId + ",\"type\":\"" + row.get("type").getAsString() + "\",\"coins\":0,\"reason\":\"test\"";
                WebActions.Answer part = done(WebActions.handle(server, action("q2-" + m, now, "ADMIN", "bank_refund", base + ",\"pieces\":200")));
                h.assertTrue(part.ok() && part.data().getAsJsonObject().get("result").getAsString().equals("ok"), "200 of it taken back: " + part.message());
                WebActions.Answer much = done(WebActions.handle(server, action("q3-" + m, now, "ADMIN", "bank_refund", base + ",\"pieces\":301")));
                h.assertTrue(!much.ok() && much.data().getAsJsonObject().get("result").getAsString().equals("too_much"), "past what is left: " + much.message());
                WebActions.Answer notPay = done(WebActions.handle(server, action("q4-" + m, now, "ADMIN", "bank_refund", base + ",\"pieces\":10,\"other\":true")));
                h.assertTrue(!notPay.ok() && notPay.data().getAsJsonObject().get("result").getAsString().equals("not_payment"), "a sale has no other player: " + notPay.message());
                WebActions.Answer again = done(WebActions.handle(server, action("q5-" + m, now, "MODERATOR", "bank_read", ",\"player\":\"" + who + "\"")));
                JsonObject d2 = again.data().getAsJsonObject();
                JsonObject refund = d2.getAsJsonArray("log").get(0).getAsJsonObject();
                h.assertTrue(d2.get("pieces").getAsLong() == 300 && refund.get("type").getAsString().equals("REFUND") && refund.get("refundOf").getAsLong() == rowId
                        && refund.get("with").getAsString().contains("Ana_Admin"), "300 left, and a REFUND row naming the staff member: " + refund);
                WebActions.Answer gone = done(WebActions.handle(server, action("q6-" + m, now, "ADMIN", "market_cancel_lot", ",\"lot\":\"no-such-lot-" + m + "\",\"reason\":\"test\"")));
                h.assertTrue(!gone.ok() && gone.data().getAsJsonObject().get("result").getAsString().equals("gone"), "a lot that is not there: gone: " + gone.message());
            } finally {
                WebActions.useReal();
                WebAgent.useReal();
            }
            h.succeed();
        });
    }
}
