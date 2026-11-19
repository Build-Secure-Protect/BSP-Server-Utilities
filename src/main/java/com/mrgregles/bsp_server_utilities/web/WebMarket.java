package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The staff panel's market and lottery (SU-50; D268, D269, D400 to D415), through BSP-Core 1.16's {@code BspMarket} and
 * {@code BspLottery} ({@link BspCore#economy()}). Actions arrive on the server thread, where BSP-Core wants its calls.
 * <ul>
 * <li>{@code market_read} ({@code refresh}: read BSP-Core's database again first): the Market Board's lots and the Wanted
 * Board's orders, newest first, and when each was read; {@code down} when the database is not connected (BSP-Core then
 * answers empty lists and read times 0, which must never look like an empty market, D414). Every rank; nothing logged.</li>
 * <li>{@code lottery_read}: the pot, who is in (names), the draw's phase, the last wins. Every rank; nothing logged.</li>
 * <li>{@code lottery_draw}: draws as {@code /bsp lottery draw}, for Admins and Owners only (D409; the panel checks the rank
 * before signing, and this checks it again, as BSP-Core checks nothing); the Action Log on a draw that began.</li>
 * </ul>
 * The snapshot carries the lottery's phase and when it began ({@link #watch}), so every open panel sees a draw (D411), and a
 * change of phase sends a snapshot at once.
 */
public final class WebMarket {
    static final String NO_ECONOMY = "The market and the lottery need BSP-Core 1.16.0 or newer on this server.";
    /** At most this many characters of lots and orders in one answer (it travels over Redis like any answer). */
    public static final int MAX_CHARS = 1_500_000;
    private static LongSupplier clock = System::currentTimeMillis;
    private static String phase = "IDLE";
    private static long phaseSince;

    private WebMarket() {
    }

    // ------------------------------------------------------------------ the snapshot's lottery phase

    /** Called about once a second: true when the lottery's phase here changed since the last call (send a snapshot now). */
    public static boolean watch() {
        BspCore.Economy eco = BspCore.economy();
        String now = eco == null ? "IDLE" : eco.phase();
        if (now.equals(phase)) {
            return false;
        }
        phase = now;
        phaseSince = clock.getAsLong();
        return true;
    }

    /** The phase last seen by {@link #watch} and when it began (0: not since start). */
    public static String phase() {
        return phase;
    }

    public static long phaseSince() {
        return phaseSince;
    }

    /** The snapshot's server fields: {@code market} (BSP-Core 1.16+ here), {@code bank} (1.18+), {@code lottery} (phase), {@code lotterySince}, {@code lotteryUp}. */
    public static void addTo(JsonObject server) {
        BspCore.Economy eco = BspCore.economy();
        server.addProperty("market", eco != null);
        server.addProperty("bank", BspCore.bank() != null); // a player's Bank & Market (BSP-Core 1.18, SU-70)
        server.addProperty("bounties", BspCore.bounties() != null); // the Bounty Board (BSP-Core 1.19, SU-75)
        if (eco != null) {
            server.addProperty("lottery", eco.phase());
            server.addProperty("lotterySince", eco.phase().equals(phase) ? phaseSince : 0);
            server.addProperty("lotteryUp", eco.connected());
        }
    }

    // ------------------------------------------------------------------ actions

    public static WebActions.Answer read(MinecraftServer server, JsonObject a, String id) {
        BspCore.Economy eco = BspCore.economy();
        if (eco == null) {
            return new WebActions.Answer(id, false, NO_ECONOMY);
        }
        if (!bool(a, "refresh")) {
            BspCore.Market held = safelyRead(server, eco);
            boolean neverRead = held != null && held.lotsReadMs() <= 0 && held.ordersReadMs() <= 0 && eco.connected();
            if (!neverRead) {
                return safely(id, "The market", () -> answer(id, held, eco.connected())); // guarded like the REFRESH path (audit)
            }
            // connected but not read yet (a server just started): read now rather than call it "not connected" (audit)
        }
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        eco.refresh(server, () -> later.complete(safely(id, "The market", () -> answer(id, eco.market(server), eco.connected()))));
        return new WebActions.Answer(id, true, "", null, later);
    }

    /** A callback's answer, or a refusal saying why when building it failed: the panel always gets exactly one answer (audit). */
    private static WebActions.Answer safely(String id, String what, java.util.function.Supplier<WebActions.Answer> build) {
        try {
            return build.get();
        } catch (RuntimeException e) {
            com.mrgregles.bsp_server_utilities.BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the staff panel's answer failed: {}", e.toString());
            return new WebActions.Answer(id, false, what + " could not be read: " + e.getMessage());
        }
    }

    /** At most this many stacks (inside shulker boxes counted) read for one answer, so a huge market cannot stall the server thread. */
    public static final int MAX_STACKS = 6000;
    /** The Wanted Board's share of {@link #MAX_CHARS}, filled first, so many lots never leave the orders empty (audit). */
    public static final int ORDER_CHARS = 300_000;

    @Nullable
    private static BspCore.Market safelyRead(MinecraftServer server, BspCore.Economy eco) {
        try {
            return eco.market(server);
        } catch (RuntimeException e) {
            return null; // answered as "could not be read"
        }
    }

    private static WebActions.Answer answer(String id, BspCore.Market m, boolean connected) {
        if (m == null) {
            return new WebActions.Answer(id, false, "The market could not be read on " + Storage.currentServerId() + " (BSP-Core answered with an error; its log says why).");
        }
        // BSP-Core's own word first (D414: a copy read before the database went away is not "fine"), then a copy never read
        boolean down = !connected || m.lotsReadMs() <= 0 && m.ordersReadMs() <= 0 && m.lots().isEmpty() && m.orders().isEmpty();
        JsonObject data = new JsonObject();
        data.addProperty("lotsReadMs", m.lotsReadMs());
        data.addProperty("ordersReadMs", m.ordersReadMs());
        data.addProperty("down", down);
        boolean cut = false;
        int used = 0, stacks = 0;
        JsonArray orders = new JsonArray();
        for (BspCore.Order r : m.orders()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("buyer", r.buyer().toString());
            o.addProperty("buyerName", r.buyerName());
            o.add("item", WebItems.stack(r.item(), false, false));
            o.addProperty("wanted", r.wanted());
            o.addProperty("filled", r.filled());
            o.addProperty("each", r.each());
            o.addProperty("placed", r.placedMs());
            o.addProperty("ends", r.endsMs());
            o.addProperty("server", r.serverId());
            int size = o.toString().length();
            if (used + size > ORDER_CHARS) {
                cut = true;
                break;
            }
            used += size;
            orders.add(o);
        }
        JsonArray lots = new JsonArray();
        for (BspCore.Lot l : m.lots()) {
            if (stacks >= MAX_STACKS) {
                cut = true;
                break;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id());
            o.addProperty("seller", l.seller().toString());
            o.addProperty("sellerName", l.sellerName());
            o.addProperty("price", l.price());
            o.addProperty("listed", l.listedMs());
            o.addProperty("ends", l.endsMs());
            o.addProperty("server", l.serverId());
            JsonArray items = new JsonArray();
            for (ItemStack st : l.items()) {
                if (!st.isEmpty()) {
                    JsonObject it = WebItems.stack(st, true, false); // view only (D407): no hash to save
                    stacks += 1 + (it.has("in") ? it.getAsJsonArray("in").size() : 0);
                    items.add(it);
                }
            }
            o.add("items", items);
            int size = o.toString().length();
            if (used + size > MAX_CHARS) {
                cut = true;
                break;
            }
            used += size;
            lots.add(o);
        }
        data.add("lots", lots);
        data.add("orders", orders);
        data.addProperty("cut", cut);
        String msg = down ? "The market cannot be read: BSP-Core's database is not connected on " + Storage.currentServerId() + "."
                : lots.size() + " lots, " + orders.size() + " orders";
        return new WebActions.Answer(id, !down, msg, data, null);
    }

    public static WebActions.Answer lottery(MinecraftServer server, String id) {
        BspCore.Economy eco = BspCore.economy();
        if (eco == null) {
            return new WebActions.Answer(id, false, NO_ECONOMY);
        }
        if (!eco.connected()) {
            return new WebActions.Answer(id, false, "The lottery cannot be reached: BSP-Core's database is not connected on " + Storage.currentServerId() + ".");
        }
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        String broken = "The lottery could not be read on " + Storage.currentServerId() + " (BSP-Core answered with an error; its log says why).";
        eco.lottery(server, s -> {
            if (s == null) {
                later.complete(new WebActions.Answer(id, false, broken));
                return;
            }
            eco.entrants(server, names -> later.complete(safely(id, "The lottery", () -> {
                if (names == null) {
                    return new WebActions.Answer(id, false, broken);
                }
                JsonObject data = new JsonObject();
                data.addProperty("pot", s.pot());
                data.addProperty("entrants", s.entrants());
                data.addProperty("phase", s.phase());
                JsonArray wins = new JsonArray();
                for (BspCore.Win w : s.wins()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", w.name());
                    o.addProperty("pieces", w.pieces());
                    o.addProperty("at", w.atMs());
                    wins.add(o);
                }
                data.add("wins", wins);
                JsonArray in = new JsonArray();
                names.stream().limit(5000).forEach(in::add);
                data.add("names", in);
                return new WebActions.Answer(id, true, s.entrants() + " in", data, null);
            })));
        });
        return new WebActions.Answer(id, true, "", null, later);
    }

    public static WebActions.Answer draw(MinecraftServer server, JsonObject a, String id, String by) {
        String rank = str(a, "rank");
        if (!rank.equals("ADMIN") && !rank.equals("OWNER")) {
            return new WebActions.Answer(id, false, "Only Admins and Owners draw the lottery.");
        }
        if (!by.matches("[A-Za-z0-9_]{1,16}")) {
            return new WebActions.Answer(id, false, "Refused: that is not a staff name.");
        }
        BspCore.Economy eco = BspCore.economy();
        if (eco == null) {
            return new WebActions.Answer(id, false, NO_ECONOMY);
        }
        String here = Storage.currentServerId();
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        eco.draw(server, by + " (panel)", key -> {
            String result = key == null ? "failed" : key;
            boolean ok = "ok".equals(result);
            if (ok) {
                try {
                    ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "lottery draw"));
                } catch (RuntimeException e) {
                    com.mrgregles.bsp_server_utilities.BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the lottery draw by {} began but could not be logged: {}", by, e.toString());
                }
            }
            JsonObject data = new JsonObject();
            data.addProperty("result", result);
            data.addProperty("server", here);
            later.complete(new WebActions.Answer(id, ok, drawMessage(result, here), data, null)); // never a second answer (audit)
        });
        return new WebActions.Answer(id, true, "", null, later);
    }

    static String drawMessage(String key, String server) {
        return switch (key) {
            case "ok" -> "Drawn on " + server + ": the countdown has begun on every server.";
            case "busy" -> "A draw is already running on the network.";
            case "unreachable" -> "The network could not be reached; nothing was drawn.";
            case "nobody" -> "Nobody is in the lottery; nothing was drawn.";
            case "empty" -> "The pot is empty; nothing was drawn.";
            default -> "The draw could not start (" + key + ").";
        };
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
        phase = "IDLE";
        phaseSince = 0;
    }
}
