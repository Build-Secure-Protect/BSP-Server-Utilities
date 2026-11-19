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
 * The Bounty Board on the panel (SU-76; D524 to D538), through the game servers' {@code bounty_*} actions (BSP-Core 1.19's
 * {@code BspBounties}).
 * <ul>
 * <li>Reads (the board, the history, the add form's player search) for every rank, asked of a live server with BSP-Core
 * 1.19, database connected first, then the busiest ({@link Feed#liveWithBounties()}); never kept: the page asks.</li>
 * <li>Changes for Admins, Owners and Moderators with the bounty power (D526, D527), checked here before signing and again by
 * the game server; a reason required (D531); fields checked here so nothing malformed is signed. A change goes to the server
 * whose board the page showed ({@code server}), refused when it has gone (bounty ids are that server's without the network
 * database); it waits the long wait, as a refund does.</li>
 * </ul>
 * Every answer is {@code ok}, {@code message}, {@code server}, {@code at} and the game server's data; a change also carries
 * {@code panelLog}, the line for the panel's own log (taken off before the page sees it).
 */
public final class BountyDesk {
    public static final String NOT_ALLOWED = "Adding, removing and extending bounties is for Admins, Owners and Moderators given the bounty power.";
    static final String NO_SERVER = "No game server with BSP-Core 1.19.0 or newer is sending to the panel right now.";
    static final String GONE = "%s, which this page was read from, is not sending to the panel now. REFRESH to read again from a server that is, then try again.";
    public static final int MAX_REASON = 160, MAX_NOTES = 512;
    public static final long HOUR = 3_600_000L, MAX_MS = 366L * 24 * HOUR;
    private static final String NAME = "[.A-Za-z0-9_]{1,17}";

    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;

    public BountyDesk(Feed feed, Supplier<Actions> actions, LongSupplier clock) {
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
    }

    public JsonObject list(Account me) {
        return read(me, "bounty_list", new JsonObject());
    }

    public JsonObject history(Account me) {
        return read(me, "bounty_history", new JsonObject());
    }

    public JsonObject players(Account me, String search) {
        String q = search == null ? "" : search.strip();
        if (!q.matches("[.A-Za-z0-9_]{0,17}")) {
            return refused("Type part of a player's name.");
        }
        JsonObject f = new JsonObject();
        f.addProperty("search", q);
        return read(me, "bounty_players", f);
    }

    /** A bounty paid by the server: target and name from the search, price, time, bonus, notes, reason. */
    public JsonObject add(Account me, JsonObject b) {
        String name = str(b, "name"), tried = "tried to add a bounty on " + (name.matches(NAME) ? name : "?");
        if (!me.mayActOnBounties()) {
            return logged(refused(NOT_ALLOWED), tried);
        }
        long tier = whole(b, "tier"), amount = whole(b, "amount"), bonus = b.has("bonus") ? whole(b, "bonus") : 0, within = b.has("bonusWithinMs") ? whole(b, "bonusWithinMs") : 0,
                duration = whole(b, "durationMs");
        String target = str(b, "target"), reason = reason(b);
        if (!target.matches("[0-9a-fA-F-]{36}") || !name.matches(NAME)) {
            return logged(refused("Pick a player from the list."), tried);
        }
        if (tier < -1 || tier > 64 || amount < 1 || bonus < 0 || within < 0 || duration < HOUR || duration > MAX_MS || (bonus > 0 && (within < 1 || within >= duration))) {
            return logged(refused("Check the reward, the time and the bonus window."), tried);
        }
        if (reason.isEmpty()) {
            return logged(refused("Say why (it goes in the Action Log)."), tried);
        }
        String notes = clean(str(b, "notes")).strip();
        JsonObject f = new JsonObject();
        f.addProperty("action", "bounty_add");
        f.addProperty("target", target);
        f.addProperty("name", name);
        f.addProperty("tier", tier);
        f.addProperty("amount", amount);
        f.addProperty("bonus", bonus);
        f.addProperty("bonusWithinMs", bonus > 0 ? within : 0);
        f.addProperty("durationMs", duration);
        f.addProperty("notes", notes.length() > MAX_NOTES ? notes.substring(0, MAX_NOTES) : notes);
        f.addProperty("reason", reason);
        return change(me, b, f, "added a bounty on " + name + " (" + amount + (tier < 0 ? " pieces" : " coins of tier " + tier) + "): " + reason, tried);
    }

    /** {@code kind}: remove (with {@code refund}), extend (with {@code addMs}) or blank. */
    public JsonObject change(Account me, String kind, JsonObject b) {
        String bid = str(b, "bounty"), tried = "tried to " + kind + " bounty " + (bid.matches("[A-Za-z0-9_.:-]{1,64}") ? bid : "?");
        if (!me.mayActOnBounties()) {
            return logged(refused(NOT_ALLOWED), tried);
        }
        if (!bid.matches("[A-Za-z0-9_.:-]{1,64}")) {
            return logged(refused("No such bounty."), tried);
        }
        String reason = reason(b), targetName = str(b, "targetName");
        if (reason.isEmpty()) {
            return logged(refused("Say why (it goes in the Action Log)."), tried);
        }
        JsonObject f = new JsonObject();
        f.addProperty("bounty", bid);
        f.addProperty("targetName", targetName.matches(NAME) ? targetName : "");
        f.addProperty("reason", reason);
        String line;
        switch (kind) {
            case "remove" -> {
                f.addProperty("action", "bounty_remove");
                f.addProperty("refund", bool(b, "refund"));
                line = "removed bounty " + bid + (bool(b, "refund") ? " with a refund" : " without a refund");
            }
            case "extend" -> {
                long add = whole(b, "addMs");
                if (add < 1 || add > MAX_MS) {
                    return logged(refused("Add a positive amount of time."), tried);
                }
                f.addProperty("action", "bounty_extend");
                f.addProperty("addMs", add);
                line = "asked to extend bounty " + bid + " by " + Math.max(1, add / HOUR) + " h";
            }
            case "blank" -> {
                f.addProperty("action", "bounty_blank_notes");
                line = "blanked the notes of bounty " + bid;
            }
            default -> {
                return refused("Not a change the panel knows.");
            }
        }
        return change(me, b, f, line + (targetName.matches(NAME) ? " on " + targetName : "") + ": " + reason, tried);
    }

    // ------------------------------------------------------------------ sending

    private JsonObject read(Account me, String action, JsonObject f) {
        f.addProperty("action", action);
        List<String> able = feed.liveWithBounties();
        return send(me, able.isEmpty() ? null : able.get(0), f, true);
    }

    private JsonObject change(Account me, JsonObject b, JsonObject f, String line, String tried) {
        String shown = str(b, "server"), to = sameServer(shown);
        if (to == null && !shown.isBlank()) {
            return logged(refused(GONE.formatted(shown)), tried);
        }
        if (me.rank == Rank.MODERATOR) {
            f.addProperty("bountyPower", true); // the bounty power, which the game server checks too ("bounty" is a bounty's id)
        }
        JsonObject out = send(me, to, f, false);
        out.addProperty("panelLog", line);
        return out;
    }

    /** The server whose board the page showed, while it can answer; with none named, the first able one. */
    String sameServer(String shown) {
        List<String> able = feed.liveWithBounties();
        if (shown != null && !shown.isBlank()) {
            for (String s : able) {
                if (s.equalsIgnoreCase(shown)) {
                    return s;
                }
            }
            return null;
        }
        return able.isEmpty() ? null : able.get(0);
    }

    private JsonObject send(Account me, String server, JsonObject f, boolean read) {
        Actions act = actions.get();
        if (server == null || act == null) {
            return refused(NO_SERVER);
        }
        // a change is one database transaction on a network: the long wait, as refunds (audit of Bank & Market)
        Actions.Answer ans = read ? act.read(server, me.mcName, me.rank.name(), f) : act.ask(server, me.mcName, me.rank.name(), f, Actions.LONG_WAIT_SECONDS);
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

    private static JsonObject logged(JsonObject out, String line) {
        out.addProperty("panelLog", line);
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

    private static String clean(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(c -> out.appendCodePoint(c < 32 || c == 127 || c == 0xA7 ? ' ' : c));
        return out.toString();
    }

    /** A whole number from the page, else -2 (-1 is a real tier). */
    private static long whole(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) {
            return -2;
        }
        try {
            double v = e.getAsDouble();
            return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : -2;
        } catch (NumberFormatException bad) {
            return -2;
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
