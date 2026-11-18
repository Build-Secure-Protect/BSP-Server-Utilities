package com.mrgregles.bsp_staff_panel.market;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A player's Bank & Market on the panel (SU-71; D502 to D511), through the game servers' {@code bank_read},
 * {@code bank_refund}, {@code market_cancel_lot} and {@code market_cancel_order} (BSP-Core 1.18's staff calls).
 * <ul>
 * <li>Asked of the player's own server when it has BSP-Core 1.18, else of a live server that has it, database connected
 * first, then the busiest ({@link Feed#liveWithBank()}): on a network every server reads the same database.</li>
 * <li>Reads for every rank (D502), never kept: a page asks when it opens, on REFRESH and after each change.</li>
 * <li>Refunds and cancels for Admins, Owners and Moderators with the bank power (D503, D504), checked here before signing
 * and again by the game server; a reason is required (D510); fields checked here so nothing malformed is signed.</li>
 * </ul>
 * Every answer is {@code ok}, {@code message}, {@code server}, {@code at} and the game server's data; a refund or cancel
 * also carries {@code panelLog}, the line for the panel's own log (taken off before the page sees it).
 */
public final class BankDesk {
    public static final String NOT_ALLOWED = "Refunds and cancels are for Admins, Owners and Moderators given the bank power.";
    static final String NO_SERVER = "No game server with BSP-Core 1.18.0 or newer is sending to the panel right now.";
    static final String GONE = "%s, which this page was read from, is not sending to the panel now. REFRESH to read again from a server that is, then try again.";
    public static final int MAX_REASON = 160, MAX_WHAT = 120;

    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;

    public BankDesk(Feed feed, Supplier<Actions> actions, LongSupplier clock) {
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
    }

    /** {@code player}: id, name and the server they are on (null when offline), as the panel finds players. */
    public JsonObject read(Account me, String[] player, boolean refresh) {
        JsonObject f = new JsonObject();
        f.addProperty("action", "bank_read");
        f.addProperty("player", player[0]);
        if (refresh) {
            f.addProperty("refresh", true);
        }
        return send(me, serverFor(player[2]), f, true, refresh);
    }

    /** One refund of one row; the page sends what it showed (row, amounts, other) and the reason. */
    public JsonObject refund(Account me, String[] player, JsonObject b) {
        String tried = "tried to refund a row of " + player[1];
        if (!me.mayActOnBank()) {
            return logged(refused(NOT_ALLOWED), tried);
        }
        long row = whole(b, "row"), pieces = whole(b, "pieces"), coins = whole(b, "coins"), tier = b.has("tier") ? whole(b, "tier") : -1;
        String reason = reason(b);
        if (row <= 0) {
            return logged(refused("No such row."), tried);
        }
        if (pieces < 0 || coins < 0 || coins > Integer.MAX_VALUE || tier < -1 || tier > 64) {
            return logged(refused("Amounts are whole numbers, 0 or more."), tried);
        }
        if (pieces == 0 && coins == 0) {
            return logged(refused("Nothing to refund: pick an amount."), tried);
        }
        if (reason.isEmpty()) {
            return logged(refused("Say why (it goes in the Action Log)."), tried);
        }
        String type = str(b, "type"), with = str(b, "with");
        JsonObject f = new JsonObject();
        f.addProperty("action", "bank_refund");
        f.addProperty("player", player[0]);
        f.addProperty("name", player[1]);
        f.addProperty("row", row);
        f.addProperty("pieces", pieces);
        f.addProperty("coins", coins);
        f.addProperty("tier", tier);
        f.addProperty("other", bool(b, "other"));
        f.addProperty("type", type.matches("[A-Z_]{1,16}") ? type : "");
        f.addProperty("with", with.matches("[.A-Za-z0-9_]{1,17}") ? with : "");
        f.addProperty("reason", reason);
        power(me, f);
        String to = sameServer(str(b, "server"), player[2]);
        if (to == null && !str(b, "server").isBlank()) {
            return logged(refused(GONE.formatted(str(b, "server"))), tried);
        }
        JsonObject out = send(me, to, f, false, true);
        out.addProperty("panelLog", "refunded row " + row + " of " + player[1] + " (" + (pieces > 0 ? pieces + " pieces" : "") + (pieces > 0 && coins > 0 ? ", " : "")
                + (coins > 0 ? coins + " coins" : "") + (bool(b, "other") ? ", also on " + (with.isEmpty() ? "the other player" : with) : "") + "): " + reason);
        return out;
    }

    /** Cancels a lot ({@code lot}) or a request ({@code order}). */
    public JsonObject cancel(Account me, JsonObject b) {
        String tried = "tried to cancel a lot or request";
        if (!me.mayActOnBank()) {
            return logged(refused(NOT_ALLOWED), tried);
        }
        String lot = str(b, "lot"), order = str(b, "order"), reason = reason(b), name = str(b, "name");
        boolean isLot = !lot.isEmpty();
        String key = isLot ? lot : order;
        if (isLot == !order.isEmpty() || !key.matches("[A-Za-z0-9_.:-]{1,64}")) {
            return logged(refused(isLot ? "No such lot." : "No such request."), tried);
        }
        if (reason.isEmpty()) {
            return logged(refused("Say why (it goes in the Action Log)."), tried);
        }
        String what = clean(str(b, "what"));
        if (what.length() > MAX_WHAT) {
            what = what.substring(0, MAX_WHAT);
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", isLot ? "market_cancel_lot" : "market_cancel_order");
        f.addProperty(isLot ? "lot" : "order", key);
        f.addProperty("name", name.matches("[.A-Za-z0-9_]{1,17}") ? name : "");
        f.addProperty("what", what);
        f.addProperty("reason", reason);
        power(me, f);
        String to = sameServer(str(b, "server"), null);
        if (to == null && !str(b, "server").isBlank()) {
            return logged(refused(GONE.formatted(str(b, "server"))), tried);
        }
        JsonObject out = send(me, to, f, false, true);
        out.addProperty("panelLog", "cancelled " + (isLot ? "lot " : "request ") + key + (name.isEmpty() ? "" : " of " + name) + ": " + reason);
        return out;
    }

    /** The player's own server when it reads bank and market, else the first able one (connected first, then busiest). */
    String serverFor(String theirs) {
        List<String> able = feed.liveWithBank();
        if (theirs != null && able.stream().anyMatch(s -> s.equalsIgnoreCase(theirs))) {
            return theirs;
        }
        return able.isEmpty() ? null : able.get(0);
    }

    /**
     * A change goes to the server whose read the page showed, while it can still answer (audit: row and lot ids are that
     * server's database's; a server keeping its own bank would mean another row by the same number); else as a read would.
     */
    String sameServer(String shown, String theirs) {
        if (shown != null && !shown.isBlank()) {
            for (String s : feed.liveWithBank()) {
                if (s.equalsIgnoreCase(shown)) {
                    return s;
                }
            }
            return null; // gone: never another server, whose row 1234 may be another row without the network database (audit)
        }
        return serverFor(theirs);
    }

    private static JsonObject logged(JsonObject out, String line) {
        out.addProperty("panelLog", line); // every refund and cancel tried is in the panel's log, early refusals too (audit)
        return out;
    }

    private static void power(Account me, JsonObject f) {
        if (me.rank == Rank.MODERATOR) {
            f.addProperty("bank", true); // the bank power, which the game server checks too
        }
    }

    private JsonObject send(Account me, String server, JsonObject f, boolean read, boolean slow) {
        Actions act = actions.get();
        if (server == null || act == null) {
            return refused(NO_SERVER);
        }
        // a read is counted with reads; a change with actions (its limits). A refresh has BSP-Core read the market first, and a
        // refund or cancel is one database transaction on a network, so both get the long wait (audit: 6 s could pass while it
        // still happened, and a second press then refunded twice)
        Actions.Answer ans = read ? slow ? act.read(server, me.mcName, me.rank.name(), f, Actions.LONG_WAIT_SECONDS) : act.read(server, me.mcName, me.rank.name(), f)
                : slow ? act.ask(server, me.mcName, me.rank.name(), f, Actions.LONG_WAIT_SECONDS) : act.ask(server, me.mcName, me.rank.name(), f);
        JsonObject out = new JsonObject();
        if (ans.data() != null && ans.data().isJsonObject()) {
            for (var e : ans.data().getAsJsonObject().entrySet()) {
                out.add(e.getKey(), e.getValue());
            }
        }
        out.addProperty("ok", ans.ok());
        out.addProperty("message", ans.message());
        out.addProperty("server", server);
        out.addProperty("at", clock.getAsLong());
        return out;
    }

    private static JsonObject refused(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("message", why);
        return o;
    }

    private static String reason(JsonObject b) {
        String r = clean(str(b, "reason")).strip();
        return r.length() > MAX_REASON ? r.substring(0, MAX_REASON) : r;
    }

    /** Control characters and section signs become spaces, as the game server does. */
    private static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(c -> out.appendCodePoint(c < 32 || c == 127 || c == 0xA7 ? ' ' : c));
        return out.toString();
    }

    /** A whole number from the page (a number or digits), else -1. */
    private static long whole(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) {
            return -1;
        }
        try {
            double v = e.getAsDouble();
            return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : -1;
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
