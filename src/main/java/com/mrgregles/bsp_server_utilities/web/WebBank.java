package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.announce.Broadcast;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A player's Bank & Market on the staff panel (SU-70; D502 to D511), through BSP-Core 1.18's staff calls
 * ({@link BspCore#bank()}). Actions arrive on the server thread, where BSP-Core wants its calls; BSP-Core checks no
 * permission, so this checks the rank again after the panel did, and passes the staff member as {@code "<name> (panel)"}.
 * <ul>
 * <li>{@code bank_read} ({@code player} a UUID, or {@code name}; {@code refresh}: the market read again first): the
 * player's pieces, the coins in their bank by tier with the tiers' names, their bank log (newest first, every row BSP-Core
 * keeps), and their open lots (display copies: item, count, name, enchantments; never full item data) and requests.
 * Every rank (D502); nothing logged.</li>
 * <li>{@code bank_refund} ({@code player}, {@code row}, {@code pieces}, {@code coins}, {@code other}, {@code reason}; for
 * the log and the answer {@code name}, {@code type}, {@code with}, {@code tier}): one refund of a row, BSP-Core's key in
 * {@code result}; Admins, Owners and Moderators with the BANK power (D503, D504); a reason required (D510); the Action
 * Log on {@code ok}. {@code other_short} and {@code other_row} carry {@code withoutOther}, so the page can offer the
 * refund without the other player (D508).</li>
 * <li>{@code market_cancel_lot} / {@code market_cancel_order} ({@code lot} or {@code order}, {@code reason}; for the log
 * {@code name}, {@code what}): the same people, the same reason, the Action Log on {@code ok}.</li>
 * </ul>
 */
public final class WebBank {
    static final String NO_BANK = "A player's bank and market need BSP-Core 1.18.0 or newer on this server.";
    static final String NOT_ALLOWED = "Refunds and cancels are for Admins, Owners and Moderators given the bank power.";
    /** At most this many characters of lots and requests in one answer; the log is at most BSP-Core's 500 rows. */
    public static final int MAX_MARKET_CHARS = 300_000;
    /** A log row's detail is cut to this (it is shown on one line). */
    public static final int MAX_DETAIL = 200;
    /** A lot or request's description for the Action Log is cut to this. */
    public static final int MAX_WHAT = 120;

    private WebBank() {
    }

    static boolean allowed(JsonObject a) {
        String rank = str(a, "rank");
        return rank.equals("ADMIN") || rank.equals("OWNER") || bool(a, "bank");
    }

    // ------------------------------------------------------------------ bank_read

    public static WebActions.Answer read(MinecraftServer server, JsonObject a, String id) {
        BspCore.Bank bank = BspCore.bank();
        if (bank == null) {
            return new WebActions.Answer(id, false, NO_BANK);
        }
        String here = Storage.currentServerId();
        String broken = "The bank could not be read on " + here + " (BSP-Core answered with an error; its log says why).";
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        Runnable fail = () -> later.complete(new WebActions.Answer(id, false, broken));
        UUID who = uuid(str(a, "player"));
        String name = str(a, "name");
        if (who != null) {
            readAccount(server, bank, who, bool(a, "refresh"), id, later, fail);
        } else if (name.matches("[.A-Za-z0-9_]{1,17}")) { // Bedrock players behind Floodgate's "." too (audit)
            bank.findPlayer(server, name, n -> {
                if (n == null) {
                    later.complete(new WebActions.Answer(id, false, "Nobody called " + name + " is known to the bank or this server."));
                } else {
                    readAccount(server, bank, n.id(), bool(a, "refresh"), id, later, fail);
                }
            }, fail);
        } else {
            return new WebActions.Answer(id, false, "No such player.");
        }
        return new WebActions.Answer(id, true, "", null, later);
    }

    private static void readAccount(MinecraftServer server, BspCore.Bank bank, UUID who, boolean refresh, String id, CompletableFuture<WebActions.Answer> later, Runnable fail) {
        String here = Storage.currentServerId();
        bank.account(server, who, acc -> {
            if (acc == null) {
                JsonObject d = new JsonObject();
                d.addProperty("down", true);
                later.complete(new WebActions.Answer(id, false, "The bank cannot be read: BSP-Core's database is not connected on " + here + ".", d, null));
                return;
            }
            bank.log(server, who, rows -> {
                Runnable finish = () -> later.complete(safely(id, () -> answer(server, bank, acc, rows, id)));
                BspCore.Economy eco = refresh ? BspCore.economy() : null;
                if (eco == null) {
                    finish.run();
                    return;
                }
                try {
                    eco.refresh(server, finish);
                } catch (RuntimeException e) {
                    finish.run(); // never left without an answer (audit)
                }
            }, fail);
        }, fail);
    }

    private static WebActions.Answer answer(MinecraftServer server, BspCore.Bank bank, BspCore.Account acc, @Nullable List<BspCore.Row> rows, String id) {
        JsonObject d = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("id", acc.player().toString());
        p.addProperty("name", acc.name());
        p.addProperty("known", acc.known());
        d.add("player", p);
        d.addProperty("pieces", acc.pieces());
        JsonArray coins = new JsonArray();
        acc.coins().forEach(c -> coins.add(c == null ? 0 : c));
        d.add("coins", coins);
        JsonArray tiers = new JsonArray();
        bank.tiers().forEach(tiers::add);
        d.add("tiers", tiers);
        d.addProperty("server", Storage.currentServerId());
        d.addProperty("logDown", rows == null);
        JsonArray log = new JsonArray();
        if (rows != null) {
            for (BspCore.Row r : rows) {
                log.add(row(r));
            }
        }
        d.add("log", log);
        boolean cut = false;
        int used = 0;
        JsonArray orders = new JsonArray();
        for (BspCore.Order r : bank.ordersOf(server, acc.player())) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.add("item", WebItems.stack(r.item(), false, false));
            o.addProperty("wanted", r.wanted());
            o.addProperty("filled", r.filled());
            o.addProperty("each", r.each());
            o.addProperty("placed", r.placedMs());
            o.addProperty("ends", r.endsMs());
            o.addProperty("server", r.serverId());
            used += o.toString().length();
            if (used > MAX_MARKET_CHARS) {
                cut = true;
                break;
            }
            orders.add(o);
        }
        JsonArray lots = new JsonArray();
        if (!cut) {
            for (BspCore.Lot l : bank.lotsOf(server, acc.player())) {
                JsonObject o = new JsonObject();
                o.addProperty("id", l.id());
                o.addProperty("price", l.price());
                o.addProperty("listed", l.listedMs());
                o.addProperty("ends", l.endsMs());
                o.addProperty("server", l.serverId());
                JsonArray items = new JsonArray();
                for (ItemStack st : l.items()) {
                    if (!st.isEmpty()) {
                        items.add(WebItems.stack(st, false, false)); // a display copy: never a book's pages or a box's contents
                    }
                }
                o.add("items", items);
                used += o.toString().length();
                if (used > MAX_MARKET_CHARS) {
                    cut = true;
                    break;
                }
                lots.add(o);
            }
        }
        d.add("lots", lots);
        d.add("orders", orders);
        d.addProperty("cut", cut);
        BspCore.Economy eco = BspCore.economy();
        d.addProperty("marketReadMs", eco == null ? 0 : marketReadMs(server, eco));
        String msg = acc.name() + ": " + log.size() + " log rows, " + lots.size() + " lots, " + orders.size() + " requests";
        return new WebActions.Answer(id, true, rows == null ? msg + " (the log cannot be read)" : msg, d, null);
    }

    private static long marketReadMs(MinecraftServer server, BspCore.Economy eco) {
        try {
            BspCore.Market m = eco.market(server);
            return m == null ? 0 : Math.min(m.lotsReadMs(), m.ordersReadMs());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static JsonObject row(BspCore.Row r) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id());
        o.addProperty("ms", r.ms());
        o.addProperty("type", r.type());
        o.addProperty("with", r.with());
        String detail = r.detail() == null ? "" : r.detail();
        o.addProperty("detail", detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL - 1) + "…" : detail);
        o.addProperty("pieces", r.pieces());
        o.addProperty("tier", r.coinTier());
        o.addProperty("coins", r.coinCount());
        o.addProperty("server", r.serverId());
        o.addProperty("refundedPieces", r.refundedPieces());
        o.addProperty("refundedCoins", r.refundedCoins());
        o.addProperty("refundOf", r.refundOf());
        o.addProperty("piecesLeft", r.piecesLeft());
        o.addProperty("coinsLeft", r.coinsLeft());
        o.addProperty("payment", r.payment());
        return o;
    }

    // ------------------------------------------------------------------ bank_refund

    public static WebActions.Answer refund(MinecraftServer server, JsonObject a, String id, String by) {
        String why = common(a, by);
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        BspCore.Bank bank = BspCore.bank();
        if (bank == null) {
            return new WebActions.Answer(id, false, NO_BANK);
        }
        UUID who = uuid(str(a, "player"));
        long row = num(a, "row");
        long pieces = num(a, "pieces"), coinsL = num(a, "coins");
        if (who == null) {
            return new WebActions.Answer(id, false, "No such player.");
        }
        if (row <= 0) {
            return new WebActions.Answer(id, false, "No such row.");
        }
        if (pieces < 0 || coinsL < 0 || coinsL > Integer.MAX_VALUE) {
            return new WebActions.Answer(id, false, refundMessage("amount"));
        }
        if (pieces == 0 && coinsL == 0) {
            return new WebActions.Answer(id, false, refundMessage("nothing"));
        }
        int coins = (int) coinsL;
        boolean other = bool(a, "other");
        String name = nameOr(str(a, "name"), who.toString()), with = nameOr(str(a, "with"), "the other player"); // a name the log cannot show: the UUID (audit)
        String type = str(a, "type").matches("[A-Z_]{1,16}") ? str(a, "type") : "row";
        String coinName = coinName(bank, (int) num(a, "tier"), coins);
        String reason = reason(a);
        String staff = by + " (panel)";
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        bank.refund(server, who, row, pieces, coins, other, staff, key -> {
            boolean ok = "ok".equals(key);
            String amounts = amounts(pieces, coins, coinName);
            if ("failed".equals(key)) { // BSP-Core threw: it may have thrown after the money moved (audit), so it is logged as unsure
                log(server, staff, "refund " + name + " row " + row + " (" + type + "): " + amounts + ": BSP-Core error, it may have happened: " + reason);
            }
            if (ok) {
                log(server, staff, "refund " + name + " row " + row + " (" + type + ("".equals(str(a, "with")) ? "" : " " + with) + "): " + amounts
                        + (other ? ", also on " + with : "") + ": " + reason);
            }
            JsonObject d = new JsonObject();
            d.addProperty("result", key);
            d.addProperty("withoutOther", other && ("other_short".equals(key) || "other_row".equals(key)));
            String msg = ok ? "Refunded " + amounts + " of " + name + "'s row " + row + (other ? ", also on " + with : "") + ". They are told in game."
                    : refundMessage(key);
            later.complete(new WebActions.Answer(id, ok, msg, d, null));
        });
        return new WebActions.Answer(id, true, "", null, later);
    }

    /** BSP-Core's answers, in its own in-game page's words where it has them. */
    static String refundMessage(String key) {
        return switch (key) {
            case "ok" -> "Done.";
            case "unreachable" -> "The bank cannot be reached right now. Nothing changed.";
            case "no_row" -> "That row is not in the log any more. Nothing changed.";
            case "amount" -> "That amount cannot be refunded. Nothing changed.";
            case "nothing" -> "Nothing to refund: pick an amount.";
            case "too_much" -> "That is more than is left of the row. Look again. Nothing changed.";
            case "no_coins" -> "That row moved no coins. Nothing changed.";
            case "not_payment" -> "Only a payment has another player. Nothing changed.";
            case "other_unknown" -> "The other player cannot be found. Nothing changed.";
            case "other_row" -> "The other player's own row of that payment is gone or already refunded. Nothing changed: refund it without them, or reverse it on them by hand.";
            case "own_coins" -> "A deposit or withdrawal only moved the player's own coins between their pocket and the bank: nothing to refund.";
            case "already" -> "Someone refunded that row meanwhile. Look again. Nothing changed.";
            case "short" -> "The player no longer has that much. Nothing changed.";
            case "other_short" -> "The other player no longer has that much. Nothing changed: refund it without them if you mean to.";
            case "failed" -> "BSP-Core answered with an error (the server's log says why). It may still have happened: read the player's bank again before trying again.";
            default -> "Not refunded (" + key + "). Nothing changed.";
        };
    }

    static String amounts(long pieces, int coins, String coinName) {
        String p = pieces > 0 ? pieces + (pieces == 1 ? " piece" : " pieces") : "";
        String c = coins > 0 ? coins + (coinName.isEmpty() ? "" : " " + coinName) + (coins == 1 ? " coin" : " coins") : "";
        return p.isEmpty() ? c : c.isEmpty() ? p : p + " and " + c;
    }

    private static String coinName(BspCore.Bank bank, int tier, int coins) {
        if (coins <= 0) {
            return "";
        }
        List<String> tiers = bank.tiers();
        return tier >= 0 && tier < tiers.size() ? tiers.get(tier) : "";
    }

    // ------------------------------------------------------------------ market_cancel_lot, market_cancel_order

    public static WebActions.Answer cancelLot(MinecraftServer server, JsonObject a, String id, String by) {
        return cancel(server, a, id, by, true);
    }

    public static WebActions.Answer cancelOrder(MinecraftServer server, JsonObject a, String id, String by) {
        return cancel(server, a, id, by, false);
    }

    private static WebActions.Answer cancel(MinecraftServer server, JsonObject a, String id, String by, boolean lot) {
        String why = common(a, by);
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        BspCore.Bank bank = BspCore.bank();
        if (bank == null) {
            return new WebActions.Answer(id, false, NO_BANK);
        }
        String key = str(a, lot ? "lot" : "order");
        if (!key.matches("[A-Za-z0-9_.:-]{1,64}")) {
            return new WebActions.Answer(id, false, lot ? "No such lot." : "No such request.");
        }
        String name = nameOr(str(a, "name"), lot ? "the seller" : "the buyer");
        String what = Broadcast.clean(str(a, "what"));
        if (what.length() > MAX_WHAT) {
            what = what.substring(0, MAX_WHAT);
        }
        String reason = reason(a), staff = by + " (panel)", text = (lot ? "cancel lot " : "cancel request ") + key + " of " + name
                + (what.isBlank() ? "" : " (" + what + ")") + ": " + reason;
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        java.util.function.Consumer<BspCore.Cancelled> then = c -> {
            String k = c == null ? "failed" : c.key();
            long back = c == null ? 0 : c.piecesBack();
            boolean ok = "ok".equals(k);
            if (ok) {
                log(server, staff, text + (lot ? "" : " (" + back + " pieces back)"));
            } else if ("failed".equals(k)) {
                log(server, staff, text + " (BSP-Core error, it may have happened)");
            }
            JsonObject d = new JsonObject();
            d.addProperty("result", k);
            d.addProperty("piecesBack", back);
            later.complete(new WebActions.Answer(id, ok, cancelMessage(k, lot, name, back), d, null));
        };
        if (lot) {
            bank.cancelLot(server, key, staff, then);
        } else {
            bank.cancelOrder(server, key, staff, then);
        }
        return new WebActions.Answer(id, true, "", null, later);
    }

    static String cancelMessage(String key, boolean lot, String name, long back) {
        return switch (key) {
            case "ok" -> lot ? "Cancelled: the items go to " + name + "'s Collect box (any Trade Post). They are told in game."
                    : "Cancelled: " + back + (back == 1 ? " piece" : " pieces") + " went back to " + name + "'s bank. They are told in game.";
            case "gone" -> "That is off the market already (sold, cancelled or run out). Nothing changed.";
            case "unreachable" -> "The market cannot be reached right now. Nothing changed.";
            case "failed" -> "BSP-Core answered with an error (the server's log says why). It may still have happened: read again before trying again.";
            default -> "Not cancelled (" + key + "). Nothing changed.";
        };
    }

    // ------------------------------------------------------------------ shared

    /** Who may, who did it, and why: null when all is in order. */
    @Nullable
    private static String common(JsonObject a, String by) {
        if (!allowed(a)) {
            return NOT_ALLOWED;
        }
        if (!by.matches("[A-Za-z0-9_]{1,16}")) {
            return "Refused: that is not a staff name.";
        }
        if (reason(a).isBlank()) {
            return "Say why (it goes in the Action Log).";
        }
        return null;
    }

    private static String reason(JsonObject a) {
        String r = Broadcast.clean(str(a, "reason")).trim();
        return r.length() > WebActions.MAX_REASON ? r.substring(0, WebActions.MAX_REASON) : r;
    }

    private static void log(MinecraftServer server, String staff, String text) {
        try {
            ActionLog.record(server, ActionLog.entryForServer(staff, LogEntry.BANK, text));
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: {} was done but could not be logged: {}", text, e.toString());
        }
    }

    private static WebActions.Answer safely(String id, Supplier<WebActions.Answer> build) {
        try {
            return build.get();
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the staff panel's bank answer failed: {}", e.toString());
            return new WebActions.Answer(id, false, "The bank could not be read: " + e.getMessage());
        }
    }

    private static String nameOr(String s, String fallback) {
        return s.matches("[.A-Za-z0-9_]{1,17}") ? s : fallback;
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A whole number, or -1 when it is missing or not one. */
    private static long num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        double v = e.getAsDouble();
        return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : -1;
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
