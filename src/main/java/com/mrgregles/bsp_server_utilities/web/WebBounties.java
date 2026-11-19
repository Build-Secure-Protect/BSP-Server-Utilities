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
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bounties on the staff panel (SU-75; D524 to D534), through BSP-Core 1.19's {@code BspBounties} ({@link BspCore#bounties()}).
 * Actions arrive on the server thread, where BSP-Core wants its calls; BSP-Core checks no permission, so the rank is checked
 * here again after the panel did, and the staff member is passed as {@code "<name> (panel)"}.
 * <ul>
 * <li>{@code bounty_list}: every running bounty with its hunters and notes (D524, D525), newest first, and the coin tiers.
 * {@code bounty_history}: the closed ones, newest first. {@code bounty_players} ({@code search}): players for the add form,
 * online first, the first ones with whether they may be a target. Every rank; nothing logged.</li>
 * <li>{@code bounty_add}, {@code bounty_remove} ({@code refund}), {@code bounty_extend} ({@code addMs}),
 * {@code bounty_blank_notes}: Admins, Owners and Moderators with the bounty power ({@code bountyPower}; D526, D527), a reason required (D531);
 * BSP-Core's key in {@code result}, the bounty as it is after in {@code bounty}; the Action Log (kind "Bounties") on
 * {@code ok}, and as unsure when BSP-Core threw.</li>
 * </ul>
 */
public final class WebBounties {
    static final String NO_BOUNTIES = "Bounties need BSP-Core 1.19.0 or newer on this server.";
    static final String NOT_ALLOWED = "Adding, removing and extending bounties is for Admins, Owners and Moderators given the bounty power.";
    /** At most this many players in a search answer, and how many of the first get whether they may be a target. */
    public static final int MAX_PLAYERS = 200, CHECKED_PLAYERS = 40;
    /** The notes sent to BSP-Core are cut to this (BSP-Core cuts again to its own {@code bounty.notesMax}, 256 by default). */
    public static final int MAX_NOTES = 512;
    public static final long HOUR = 3_600_000L, MAX_DURATION = 366L * 24 * HOUR;

    private WebBounties() {
    }

    static boolean allowed(JsonObject a) {
        String rank = str(a, "rank");
        return rank.equals("ADMIN") || rank.equals("OWNER") || bool(a, "bountyPower"); // ("bounty" is a bounty's id)
    }

    // ------------------------------------------------------------------ reads

    public static WebActions.Answer list(MinecraftServer server, String id) {
        BspCore.Bounties b = BspCore.bounties();
        if (b == null) {
            return new WebActions.Answer(id, false, NO_BOUNTIES);
        }
        return safely(id, () -> {
            JsonObject d = new JsonObject();
            JsonArray open = new JsonArray();
            for (BspCore.Bounty x : b.open(server)) {
                open.add(bounty(x));
            }
            d.add("open", open);
            d.add("tiers", tiers(b));
            d.addProperty("server", Storage.currentServerId());
            return new WebActions.Answer(id, true, open.size() + " open", d, null);
        });
    }

    public static WebActions.Answer history(MinecraftServer server, String id) {
        BspCore.Bounties b = BspCore.bounties();
        if (b == null) {
            return new WebActions.Answer(id, false, NO_BOUNTIES);
        }
        return safely(id, () -> {
            JsonObject d = new JsonObject();
            JsonArray list = new JsonArray();
            for (BspCore.BountyClosed c : b.history(server)) {
                JsonObject o = new JsonObject();
                o.add("bounty", bounty(c.bounty()));
                o.addProperty("ending", c.ending());
                o.addProperty("closed", c.closedMs());
                o.addProperty("hunter", c.hunter() == null ? "" : c.hunter().toString());
                o.addProperty("hunterName", c.hunterName());
                o.addProperty("by", c.by());
                o.addProperty("bonusPaid", c.bonusPaid());
                list.add(o);
            }
            d.add("history", list);
            d.add("tiers", tiers(b));
            return new WebActions.Answer(id, true, list.size() + " closed", d, null);
        });
    }

    public static WebActions.Answer players(MinecraftServer server, JsonObject a, String id) {
        BspCore.Bounties b = BspCore.bounties();
        if (b == null) {
            return new WebActions.Answer(id, false, NO_BOUNTIES);
        }
        String search = str(a, "search").strip();
        if (!search.matches("[.A-Za-z0-9_]{0,17}")) {
            return new WebActions.Answer(id, false, "Type part of a player's name.");
        }
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        b.players(server, search, list -> later.complete(safely(id, () -> {
            JsonArray out = new JsonArray();
            int i = 0;
            for (BspCore.BountyPlayer p : list) {
                if (i >= MAX_PLAYERS) {
                    break;
                }
                JsonObject o = new JsonObject();
                o.addProperty("id", p.id().toString());
                o.addProperty("name", p.name());
                o.addProperty("online", p.online());
                if (i < CHECKED_PLAYERS) {
                    String why = b.targetRefusal(server, p.id(), p.name());
                    o.addProperty("refusal", why == null ? "" : why);
                }
                out.add(o);
                i++;
            }
            JsonObject d = new JsonObject();
            d.add("players", out);
            return new WebActions.Answer(id, true, out.size() + " players", d, null);
        })), () -> later.complete(new WebActions.Answer(id, false, "The player list could not be read on " + Storage.currentServerId() + " (BSP-Core answered with an error).")));
        return new WebActions.Answer(id, true, "", null, later);
    }

    // ------------------------------------------------------------------ changes

    public static WebActions.Answer add(MinecraftServer server, JsonObject a, String id, String by) {
        String why = common(a, by);
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        BspCore.Bounties b = BspCore.bounties();
        if (b == null) {
            return new WebActions.Answer(id, false, NO_BOUNTIES);
        }
        UUID target = uuid(str(a, "target"));
        String name = str(a, "name");
        long tier = num(a, "tier"), amount = num(a, "amount"), bonus = a.has("bonus") ? num(a, "bonus") : 0, within = a.has("bonusWithinMs") ? num(a, "bonusWithinMs") : 0,
                duration = num(a, "durationMs");
        if (target == null || !name.matches("[.A-Za-z0-9_]{1,17}")) {
            return new WebActions.Answer(id, false, "Pick a player from the list.");
        }
        List<String> tiers = b.tiers();
        if (tier < -1 || tier >= Math.max(0, tiers.size()) && tier != -1) {
            return new WebActions.Answer(id, false, "Pieces, or coins of one tier.");
        }
        if (amount < 1 || bonus < 0 || within < 0 || duration < 1 || duration > MAX_DURATION) {
            return new WebActions.Answer(id, false, message("price", false));
        }
        String refusal;
        try {
            refusal = b.targetRefusal(server, target, name);
        } catch (RuntimeException e) { // answered, never left to time out as "it may have happened" (audit)
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: BSP-Core could not check a bounty target: {}", e.toString());
            return new WebActions.Answer(id, false, "BSP-Core could not check whether " + name + " may have a bounty (the server's log says why). Nothing was placed.");
        }
        if (refusal != null) {
            JsonObject d = new JsonObject();
            d.addProperty("result", refusal);
            return new WebActions.Answer(id, false, message(refusal, false), d, null);
        }
        String notes = cleanNotes(str(a, "notes")); // not Broadcast.clean, which cuts at 256: BSP-Core cuts to its own bounty.notesMax (audit)
        if (notes.length() > MAX_NOTES) {
            notes = notes.substring(0, MAX_NOTES);
        }
        String reason = reason(a), staff = by + " (panel)", what = price((int) tier, amount, tiers) + " for " + span(duration)
                + (bonus > 0 ? ", bonus " + price((int) tier, bonus, tiers) + " within " + span(within) : "");
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        b.add(server, staff, target, name, (int) tier, amount, bonus, bonus > 0 ? within : 0, duration, notes, r ->
                later.complete(answer(server, id, r, staff, "bounty add on " + name + ": " + what, reason,
                        "Placed on " + name + ": " + what + ", paid by the server. They are told in game.")));
        return new WebActions.Answer(id, true, "", null, later);
    }

    public static WebActions.Answer remove(MinecraftServer server, JsonObject a, String id, String by) {
        return change(server, a, id, by, "remove");
    }

    public static WebActions.Answer extend(MinecraftServer server, JsonObject a, String id, String by) {
        return change(server, a, id, by, "extend");
    }

    public static WebActions.Answer blankNotes(MinecraftServer server, JsonObject a, String id, String by) {
        return change(server, a, id, by, "blank");
    }

    private static WebActions.Answer change(MinecraftServer server, JsonObject a, String id, String by, String kind) {
        String why = common(a, by);
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        BspCore.Bounties b = BspCore.bounties();
        if (b == null) {
            return new WebActions.Answer(id, false, NO_BOUNTIES);
        }
        String bid = str(a, "bounty");
        if (!bid.matches("[A-Za-z0-9_.:-]{1,64}")) {
            return new WebActions.Answer(id, false, "No such bounty.");
        }
        String target = str(a, "targetName").matches("[.A-Za-z0-9_]{1,17}") ? str(a, "targetName") : "?";
        String reason = reason(a), staff = by + " (panel)";
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        switch (kind) {
            case "remove" -> {
                boolean refund = bool(a, "refund");
                b.remove(server, bid, refund, staff, r -> {
                    // a staff bounty (placed by the server) gives nothing back and has no placer to tell (BSP-Core's giveBack; audit)
                    boolean byServer = r != null && r.bounty() != null && r.bounty().byServer();
                    String placer = r != null && r.bounty() != null ? r.bounty().placerName() : "the placer";
                    later.complete(answer(server, id, r, staff, "bounty remove " + bid + " on " + target
                                    + (byServer ? " (placed by the server: nothing to give back)" : refund ? " (refunded)" : " (no refund)"), reason,
                            "Taken off the board" + (byServer ? "; it was placed by the server, so nothing went back" : refund ? "; " + placer
                                    + " has the reward and bonus back (the fee stays paid)" : "; nothing was given back") + ". "
                                    + (byServer ? "The hunters and " + target : placer + ", the hunters and " + target) + " are told in game."));
                });
            }
            case "extend" -> {
                long add = num(a, "addMs");
                if (add < 1 || add > MAX_DURATION) {
                    return new WebActions.Answer(id, false, message("time", true));
                }
                long before = endsOf(server, b, bid);
                b.extend(server, bid, add, staff, r -> {
                    long added = r != null && r.bounty() != null && before > 0 ? r.bounty().endsMs() - before : add; // BSP-Core stops at its longest (audit)
                    later.complete(answer(server, id, r, staff, "bounty extend " + bid + " on " + target + " by " + span(added) + (added < add ? " (asked " + span(add) + ", stopped at the longest)" : ""),
                            reason, "Extended" + (r != null && r.bounty() != null ? ": it ends " + endsIn(r.bounty().endsMs()) : "")
                                    + (added < add ? " (it stops at the longest a bounty may run)" : "")
                                    + (r != null && r.bounty() != null && r.bounty().byServer() ? ". The hunters are told in game." : ". The placer and the hunters are told in game.")));
                });
            }
            default -> b.blankNotes(server, bid, staff, r -> later.complete(answer(server, id, r, staff, "bounty notes blanked " + bid + " on " + target, reason,
                    "The notes are blank.")));
        }
        return new WebActions.Answer(id, true, "", null, later);
    }

    private static WebActions.Answer answer(MinecraftServer server, String id, BspCore.BountyResult r, String staff, String logText, String reason, String okMessage) {
        String key = r == null ? "failed" : r.key();
        boolean ok = "ok".equals(key);
        if (ok) {
            log(server, staff, logText + ": " + reason);
        } else if ("failed".equals(key)) {
            log(server, staff, logText + " (BSP-Core error, it may have happened): " + reason);
        }
        JsonObject d = new JsonObject();
        d.addProperty("result", key);
        if (r != null && r.bounty() != null) {
            d.add("bounty", bounty(r.bounty()));
        }
        return new WebActions.Answer(id, ok, ok ? okMessage : message(key, true), d, null);
    }

    /** BSP-Core's answers in its own in-game words where it has them. */
    static String message(String key, boolean change) {
        return switch (key) {
            case "unreachable" -> "The bounty board cannot reach the network right now. Nothing changed.";
            case "time" -> change ? "Add a positive amount of time." : "A bounty runs from 1 hour to the longest the server allows (30 days by default).";
            case "price" -> "That reward is too small or too large (at least 1,000 pieces or 1 coin).";
            case "bonus" -> "That bonus is too large.";
            case "bonus_time" -> "The bonus needs a time window shorter than the bounty itself.";
            case "staff", "self" -> "Staff cannot have bounties on them.";
            case "protected" -> "That player is still under new-player protection.";
            case "gone" -> "That bounty has ended. Nothing changed.";
            case "max" -> "That bounty already ends as late as it may.";
            case "failed" -> "BSP-Core answered with an error (the server's log says why). It may still have happened: read the board again before trying again.";
            default -> "Not done (" + key + ").";
        };
    }

    // ------------------------------------------------------------------ shared

    static JsonObject bounty(BspCore.Bounty x) {
        JsonObject o = new JsonObject();
        o.addProperty("id", x.id());
        o.addProperty("target", x.target().toString());
        o.addProperty("targetName", x.targetName());
        o.addProperty("placer", x.placer() == null ? "" : x.placer().toString());
        o.addProperty("placerName", x.placerName());
        o.addProperty("byServer", x.byServer());
        o.addProperty("tier", x.coinTier());
        o.addProperty("amount", x.amount());
        o.addProperty("bonus", x.bonus());
        o.addProperty("bonusEnds", x.bonusEndsMs());
        o.addProperty("fee", x.fee());
        o.addProperty("placed", x.placedMs());
        o.addProperty("ends", x.endsMs());
        o.addProperty("notes", x.notes());
        o.addProperty("server", x.serverId());
        JsonArray hs = new JsonArray();
        for (BspCore.BountyHunter h : x.hunters()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", h.id().toString());
            j.addProperty("name", h.name());
            j.addProperty("accepted", h.acceptedMs());
            hs.add(j);
        }
        o.add("hunters", hs);
        return o;
    }

    private static JsonArray tiers(BspCore.Bounties b) {
        JsonArray t = new JsonArray();
        b.tiers().forEach(t::add);
        return t;
    }

    static String price(int tier, long n, List<String> tiers) {
        if (tier < 0) {
            return String.format("%,d", n) + (n == 1 ? " piece" : " pieces");
        }
        String name = tier < tiers.size() ? tiers.get(tier) + " " : "";
        return n + " " + name + (n == 1 ? "coin" : "coins");
    }

    static String span(long ms) {
        long h = ms / HOUR;
        return h >= 24 && h % 24 == 0 ? h / 24 + (h == 24 ? " day" : " days") : h >= 24 ? h / 24 + " d " + h % 24 + " h" : Math.max(1, h) + (h <= 1 ? " hour" : " hours");
    }

    /** When the bounty ends now, as this server's board has it (0: not found or not readable). */
    private static long endsOf(MinecraftServer server, BspCore.Bounties b, String bid) {
        try {
            return b.open(server).stream().filter(x -> x.id().equals(bid)).mapToLong(BspCore.Bounty::endsMs).findFirst().orElse(0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String endsIn(long endsMs) {
        long left = endsMs - System.currentTimeMillis();
        return left <= 0 ? "now" : "in " + span(left);
    }

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

    /** Control characters and section signs become spaces, kept to {@link #MAX_NOTES}. */
    static String cleanNotes(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NOTES));
        raw.codePoints().forEach(c -> out.appendCodePoint(c < 32 || c == 127 || c == 0xA7 || (c >= 0x2028 && c <= 0x202E) ? ' ' : c));
        String s = out.toString().strip();
        return s.length() > MAX_NOTES ? s.substring(0, MAX_NOTES) : s;
    }

    private static String reason(JsonObject a) {
        String r = Broadcast.clean(str(a, "reason")).trim();
        return r.length() > WebActions.MAX_REASON ? r.substring(0, WebActions.MAX_REASON) : r;
    }

    private static void log(MinecraftServer server, String staff, String text) {
        try {
            ActionLog.record(server, ActionLog.entryForServer(staff, LogEntry.BOUNTY, text));
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: {} was done but could not be logged: {}", text, e.toString());
        }
    }

    private static WebActions.Answer safely(String id, java.util.function.Supplier<WebActions.Answer> build) {
        try {
            return build.get();
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the staff panel's bounty answer failed: {}", e.toString());
            return new WebActions.Answer(id, false, "The bounties could not be read on " + Storage.currentServerId() + " (BSP-Core answered with an error; its log says why).");
        }
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A whole number, or -2 when it is missing or not one (-1 is a real tier). */
    private static long num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return -2;
        }
        double v = e.getAsDouble();
        return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : -2;
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
