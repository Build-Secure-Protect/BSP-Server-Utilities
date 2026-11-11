package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebMarket;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The staff panel's market and lottery (SU-50; D400 to D415): the lots and orders read with their items, a refresh, the
 * database down said as such and never as an empty market, the answer's size cap, the lottery read, a draw for Admins and
 * Owners only with every answer worded and only a begun draw logged, the snapshot's market and lottery fields, the phase
 * watch; and the real BSP-Core 1.16 the test server loads, through the adapter.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebMarketGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 7 + 3);
        }
    }

    private static String action(String id, long at, String rank, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Admin\",\"rank\":\"" + rank + "\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    /** As strict as BSP-Core's api: server thread, read times 0 and empty lists while not connected, draw answers by key. */
    static final class FakeEconomy implements BspCore.Economy {
        final List<BspCore.Lot> lots = new ArrayList<>();
        final List<BspCore.Order> orders = new ArrayList<>();
        final List<String> drawsBy = new ArrayList<>();
        long readMs;
        boolean connected = true, broken, holdRefresh;
        Runnable held;
        int refreshes;
        String phase = "IDLE", drawAnswer = "ok";
        MinecraftServer server;

        @Override
        public BspCore.Market market(MinecraftServer s) {
            check(s);
            if (broken) {
                return null;
            }
            return connected ? new BspCore.Market(List.copyOf(lots), List.copyOf(orders), readMs, readMs) : new BspCore.Market(List.of(), List.of(), 0, 0);
        }

        @Override
        public void refresh(MinecraftServer s, Runnable then) {
            check(s);
            refreshes++;
            readMs += 1000;
            if (holdRefresh) {
                held = then; // on a network BSP-Core answers once its database has: later, or never
            } else {
                then.run();
            }
        }

        @Override
        public boolean connected() {
            check(server);
            return connected;
        }

        @Override
        public String phase() {
            check(server);
            return phase;
        }

        @Override
        public void lottery(MinecraftServer s, Consumer<BspCore.Lottery> then) {
            check(s);
            if (broken) {
                then.accept(null);
                return;
            }
            then.accept(connected ? new BspCore.Lottery(12400, 3, phase, List.of(new BspCore.Win("Mossy_Fen", 11800, 1_700_000_000_000L)))
                    : new BspCore.Lottery(0, 0, phase, List.of()));
        }

        @Override
        public void entrants(MinecraftServer s, Consumer<List<String>> then) {
            check(s);
            then.accept(connected ? List.of("Glimmer", "Kestrel_88", "Vexa") : List.of());
        }

        @Override
        public void draw(MinecraftServer s, String by, Consumer<String> then) {
            check(s);
            if (by == null || by.length() > 64 || by.contains("\n")) {
                throw new IllegalArgumentException("by: one line, at most 64 characters");
            }
            drawsBy.add(by);
            then.accept(connected ? drawAnswer : "unreachable");
        }

        private void check(MinecraftServer s) {
            if (s != null && !s.isSameThread()) {
                throw new IllegalStateException("BSP-Core's market and lottery are server thread only");
            }
            server = s;
        }
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

    private static WebActions.Answer done(WebActions.Answer a) {
        return a.later() == null ? a : a.later().getNow(new WebActions.Answer(a.id(), false, "not yet answered"));
    }

    @GameTest(template = EMPTY)
    public void the_market_and_lottery_are_read_and_drawn_by_admins_only(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        String m = "m" + Integer.toHexString(new java.util.Random().nextInt());
        FakeEconomy eco = new FakeEconomy();
        eco.server = server;
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        WebMarket.useForTests(() -> now);
        try {
            BspCore.useEconomyForTests(null);
            WebActions.Answer none = WebActions.handle(server, action("a0-" + m, now, "MODERATOR", "market_read", ""));
            h.assertTrue(!none.ok() && none.message().contains("BSP-Core 1.16.0"), "without BSP-Core 1.16: said plainly: " + none.message());
            JsonObject snap0 = WebSnapshot.build(server, "alpha", List.of(), now);
            h.assertTrue(!snap0.get("market").getAsBoolean() && !snap0.has("lottery"), "the snapshot: no market here");

            BspCore.useEconomyForTests(eco);
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            pick.enchant(Enchantments.BLOCK_EFFICIENCY, 5);
            UUID kestrel = UUID.randomUUID(), tom = UUID.randomUUID();
            eco.readMs = now - 40_000;
            eco.lots.add(new BspCore.Lot("lot-1", kestrel, "Kestrel_88", 4800, now - 7_200_000, now + 600_000_000, "frostwarf", List.of(pick, new ItemStack(Items.TORCH, 64))));
            eco.lots.add(new BspCore.Lot("lot-2", tom, "BrickLayerTom", 12500, now - 9_000_000, now + 500_000_000, "moongrove", List.of(shulker(), ItemStack.EMPTY)));
            eco.orders.add(new BspCore.Order("ord-1", tom, "BrickLayerTom", new ItemStack(Items.NETHERITE_INGOT), 4, 1, 2400, now - 1000, now + 400_000_000, "frostwarf"));

            WebActions.Answer read = done(WebActions.handle(server, action("a1-" + m, now, "MODERATOR", "market_read", "")));
            JsonObject d = read.data().getAsJsonObject();
            JsonArray lots = d.getAsJsonArray("lots");
            h.assertTrue(read.ok() && lots.size() == 2 && !d.get("down").getAsBoolean() && d.get("lotsReadMs").getAsLong() == now - 40_000, "every rank reads the market: " + read.message());
            JsonObject l1 = lots.get(0).getAsJsonObject();
            h.assertTrue(l1.get("sellerName").getAsString().equals("Kestrel_88") && l1.get("price").getAsLong() == 4800 && l1.get("server").getAsString().equals("frostwarf")
                    && l1.get("seller").getAsString().equals(kestrel.toString()), "a lot's seller, price and server: " + l1);
            JsonObject p = l1.getAsJsonArray("items").get(0).getAsJsonObject();
            h.assertTrue(p.get("id").getAsString().equals("minecraft:diamond_pickaxe") && p.has("ench") && p.has("dmg"), "an item with its enchantments and durability: " + p);
            JsonArray box = lots.get(1).getAsJsonObject().getAsJsonArray("items");
            h.assertTrue(box.size() == 1 && box.get(0).getAsJsonObject().getAsJsonArray("in").get(0).getAsJsonObject().get("n").getAsInt() == 24,
                    "a shulker box with what it holds; an empty stack left out: " + box);
            JsonObject o1 = d.getAsJsonArray("orders").get(0).getAsJsonObject();
            h.assertTrue(o1.get("wanted").getAsInt() == 4 && o1.get("filled").getAsInt() == 1 && o1.get("each").getAsLong() == 2400
                    && o1.getAsJsonObject("item").get("id").getAsString().equals("minecraft:netherite_ingot"), "an order: " + o1);

            WebActions.Answer fresh = done(WebActions.handle(server, action("a2-" + m, now, "MODERATOR", "market_read", ",\"refresh\":true")));
            h.assertTrue(fresh.ok() && eco.refreshes == 1 && fresh.data().getAsJsonObject().get("lotsReadMs").getAsLong() == now - 39_000, "REFRESH reads again first (D401)");
            h.assertTrue(!read.data().getAsJsonObject().get("cut").getAsBoolean() && !p.has("h"), "a normal read is whole, and view-only items carry no hash (audit)");
            eco.holdRefresh = true;
            WebActions.Answer waiting = WebActions.handle(server, action("a2b-" + m, now, "MODERATOR", "market_read", ",\"refresh\":true"));
            h.assertTrue(waiting.later() != null && !waiting.later().isDone(), "on a network the answer waits for BSP-Core's database");
            eco.held.run();
            h.assertTrue(waiting.later().isDone() && waiting.later().getNow(null).ok(), "and is sent once it has answered");
            eco.holdRefresh = false;
            eco.broken = true;
            WebActions.Answer broken = done(WebActions.handle(server, action("a2c-" + m, now, "MODERATOR", "market_read", "")));
            h.assertTrue(!broken.ok() && broken.message().contains("could not be read") && !broken.message().contains("not connected"),
                    "an error is said as an error, never as the database being down (audit): " + broken.message());
            WebActions.Answer brokenLot = done(WebActions.handle(server, action("a2d-" + m, now, "MODERATOR", "lottery_read", "")));
            h.assertTrue(!brokenLot.ok() && brokenLot.message().contains("could not be read"), "an unreadable lottery is refused, never shown empty");
            eco.broken = false;

            eco.connected = false;
            WebActions.Answer down = done(WebActions.handle(server, action("a3-" + m, now, "MODERATOR", "market_read", "")));
            h.assertTrue(!down.ok() && down.data().getAsJsonObject().get("down").getAsBoolean() && down.message().contains("cannot be read"),
                    "the database down: said so, never an empty market (D414): " + down.message());
            WebActions.Answer lotDown = done(WebActions.handle(server, action("a4-" + m, now, "MODERATOR", "lottery_read", "")));
            h.assertTrue(!lotDown.ok() && lotDown.message().contains("cannot be reached"), "the lottery unreachable: said so");
            eco.connected = true;

            ItemStack big = new ItemStack(Items.WRITABLE_BOOK);
            CompoundTag display = new CompoundTag();
            ListTag lore = new ListTag();
            for (int i = 0; i < 8; i++) {
                lore.add(net.minecraft.nbt.StringTag.valueOf("{\"text\":\"" + "x".repeat(120) + "\"}"));
            }
            display.put("Lore", lore);
            big.getOrCreateTag().put("display", display);
            for (int i = 0; i < 2500; i++) {
                eco.lots.add(new BspCore.Lot("big-" + i, kestrel, "Kestrel_88", 1, now, now + 1000, "frostwarf", java.util.Collections.nCopies(9, big)));
            }
            WebActions.Answer capped = done(WebActions.handle(server, action("a5-" + m, now, "MODERATOR", "market_read", "")));
            JsonObject cd = capped.data().getAsJsonObject();
            int chars = cd.getAsJsonArray("lots").toString().length() + cd.getAsJsonArray("orders").toString().length();
            h.assertTrue(capped.ok() && cd.get("cut").getAsBoolean() && chars <= WebMarket.MAX_CHARS + 2 * 2500 + 10, "a huge market is cut to size, and says so: " + chars);
            eco.lots.subList(2, eco.lots.size()).clear();

            WebActions.Answer lot = done(WebActions.handle(server, action("a6-" + m, now, "MODERATOR", "lottery_read", "")));
            JsonObject ld = lot.data().getAsJsonObject();
            h.assertTrue(lot.ok() && ld.get("pot").getAsLong() == 12400 && ld.getAsJsonArray("names").size() == 3 && ld.get("phase").getAsString().equals("IDLE")
                    && ld.getAsJsonArray("wins").get(0).getAsJsonObject().get("name").getAsString().equals("Mossy_Fen"), "the lottery: pot, names, phase, wins: " + ld);

            WebActions.Answer mod = done(WebActions.handle(server, action("a7-" + m, now, "MODERATOR", "lottery_draw", "")));
            h.assertTrue(!mod.ok() && eco.drawsBy.isEmpty() && mod.message().contains("Admins"), "a Moderator cannot draw, even with a signed action (D409)");
            WebActions.Answer drawn = done(WebActions.handle(server, action("a8-" + m, now, "ADMIN", "lottery_draw", "")));
            h.assertTrue(drawn.ok() && eco.drawsBy.equals(List.of("Ana_Admin (panel)")) && drawn.message().startsWith("Drawn on " + Storage.currentServerId())
                    && drawn.data().getAsJsonObject().get("result").getAsString().equals("ok"), "an Admin draws, by name with (panel): " + drawn.message());
            h.assertTrue(ActionLog.recent(server, 30, false, List.of()).stream().anyMatch(e -> e.actor().equals("Ana_Admin (panel)") && e.text().equals("lottery draw")),
                    "a draw that began is in the Action Log");
            long drawsLogged = ActionLog.recent(server, 200, false, List.of()).stream().filter(e -> e.text().equals("lottery draw")).count();
            for (String key : List.of("busy", "nobody", "empty")) {
                eco.drawAnswer = key;
                WebActions.Answer no = done(WebActions.handle(server, action("a9" + key + "-" + m, now, "OWNER", "lottery_draw", "")));
                h.assertTrue(!no.ok() && no.data().getAsJsonObject().get("result").getAsString().equals(key) && !no.message().contains("(" + key + ")"),
                        "a refused draw (" + key + ") worded: " + no.message());
            }
            h.assertTrue(ActionLog.recent(server, 200, false, List.of()).stream().filter(e -> e.text().equals("lottery draw")).count() == drawsLogged,
                    "a refused draw is not in the Action Log");
            String badName = WebActions.sign(KEY, "{\"v\":1,\"id\":\"ab-" + m + "\",\"at\":" + now + ",\"by\":\"not a name!\",\"rank\":\"ADMIN\",\"server\":\""
                    + Storage.currentServerId() + "\",\"action\":\"lottery_draw\"}");
            int before = eco.drawsBy.size();
            WebActions.Answer bad = done(WebActions.handle(server, badName));
            h.assertTrue(!bad.ok() && eco.drawsBy.size() == before, "a drawer whose name is not a staff name is refused");
            eco.connected = false;
            WebActions.Answer unreach = done(WebActions.handle(server, action("aa-" + m, now, "OWNER", "lottery_draw", "")));
            h.assertTrue(!unreach.ok() && unreach.message().contains("could not be reached"), "unreachable worded");
            eco.connected = true;

            JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), now);
            h.assertTrue(snap.get("market").getAsBoolean() && snap.get("lottery").getAsString().equals("IDLE") && snap.get("lotteryUp").getAsBoolean(), "the snapshot's market fields: " + snap);
            h.assertTrue(!WebMarket.watch(), "IDLE as before: no change");
            eco.phase = "COUNTING";
            h.assertTrue(WebMarket.watch() && !WebMarket.watch() && WebMarket.phaseSince() == now, "a draw beginning is seen once, with its time");
            JsonObject counting = WebSnapshot.build(server, "alpha", List.of(), now);
            h.assertTrue(counting.get("lottery").getAsString().equals("COUNTING") && counting.get("lotterySince").getAsLong() == now, "the snapshot says a draw is counting, since when (D411)");
            eco.phase = "IDLE";
            h.assertTrue(WebMarket.watch(), "and its end");
        } finally {
            BspCore.useRealEconomy();
            WebMarket.useForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 100)
    public void the_real_bsp_core_market_and_lottery_answer_through_the_adapter(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        BspCore.Economy eco = BspCore.economy();
        h.assertTrue(eco != null, "BSP-Core " + BspCore.ECONOMY_SINCE + "+ is loaded in the test server, so the market and lottery are there");
        BspCore.Market market = eco.market(server);
        h.assertTrue(market.lotsReadMs() > 0 || !eco.connected(), "a single server reads its own market, always current: " + market.lotsReadMs());
        List<Object> got = new ArrayList<>();
        eco.lottery(server, got::add);
        eco.entrants(server, got::add);
        h.assertTrue(Set.of("IDLE", "COUNTING", "DRAWING").contains(eco.phase()), "a phase by name: " + eco.phase());
        int[] calls = {0};
        try {
            eco.lottery(server, s -> {
                calls[0]++;
                throw new IllegalStateException("our own callback failing (test)");
            });
        } catch (RuntimeException escaped) {
            h.fail("an error in our own callback must not escape onto the server thread: " + escaped);
        }
        h.assertTrue(calls[0] <= 1, "our callback is never called a second time with a made-up answer (audit): " + calls[0]);
        h.runAfterDelay(40, () -> {
            h.assertTrue(got.size() == 2 && got.get(0) instanceof BspCore.Lottery, "the summary and the names arrived: " + got);
            h.succeed();
        });
    }
}
