package com.mrgregles.bsp_staff_panel.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The market and the lottery on the panel (SU-51; D400 to D415), through the game servers' {@code market_read},
 * {@code lottery_read} and {@code lottery_draw} (BSP-Core 1.16's {@code BspMarket} and {@code BspLottery}).
 * <ul>
 * <li>The market is asked of a live server with BSP-Core 1.16, one whose database is connected first, and kept
 * {@link #MARKET_MS} (D401); REFRESH asks again at once and has BSP-Core read its database first.</li>
 * <li>The lottery is asked the same way, kept {@link #LOTTERY_MS}.</li>
 * <li>A draw: Admins and Owners only (D409), on the busiest server with the lottery (D410); remembered with who drew, so
 * the end of the draw can say "drawn by" (D411).</li>
 * <li>{@link #tick()} (once a second) watches the feed: a draw beginning anywhere and its end are told to every open page
 * as {@code lottery} events ({@code counting}; then {@code won} with the winner, or {@code ended}).</li>
 * </ul>
 */
public final class MarketDesk {
    public static final long MARKET_MS = 30_000L, LOTTERY_MS = 3_000L, DRAWN_BY_MS = 120_000L;
    /** A failed read is remembered this long, so a server that does not answer is asked once, not by every page in turn (audit). */
    public static final long FAILED_MS = 8_000L;
    /** REFRESH has BSP-Core read its whole database: at most once in this long for the whole panel (audit). */
    public static final long REFRESH_GAP_MS = 5_000L;
    static final String NO_SERVER = "No game server with BSP-Core 1.16.0 or newer is sending to the panel right now.";

    private final Feed feed;
    private final Supplier<Actions> actions;
    private final LongSupplier clock;
    private volatile Consumer<JsonObject> events = e -> {
    };
    private volatile Consumer<Runnable> background = r -> {
        Thread t = new Thread(r, "panel-lottery");
        t.setDaemon(true);
        t.start();
    };

    private volatile JsonObject market;
    private volatile long marketAt;
    private volatile JsonObject lottery;
    private volatile long lotteryAt;
    private final Object marketLock = new Object(), lotteryLock = new Object();

    private volatile String drawnBy;
    private volatile long drawnAt;
    private volatile JsonObject counting;

    public MarketDesk(Feed feed, Supplier<Actions> actions, LongSupplier clock) {
        this.feed = feed;
        this.actions = actions;
        this.clock = clock;
    }

    /** Where {@code lottery} events go (every open page). */
    public MarketDesk onEvent(Consumer<JsonObject> c) {
        this.events = c;
        return this;
    }

    /** Tests: run the end-of-draw read at once instead of on its own thread. */
    public MarketDesk runInline() {
        this.background = Runnable::run;
        return this;
    }

    // ------------------------------------------------------------------ reads

    private volatile JsonObject marketFailed, lotteryFailed;
    private volatile long marketFailedAt, lotteryFailedAt, refreshedAt;
    private final java.util.concurrent.atomic.AtomicBoolean marketReading = new java.util.concurrent.atomic.AtomicBoolean(),
            lotteryReading = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The market for a page: {@code ok}, {@code message}, {@code at} (when the panel asked), the game server's data,
     * {@code server}. One read goes out at a time; the others get the copy held, or the failure remembered, at once, so a
     * game server that does not answer never makes pages queue behind each other (audit).
     */
    public JsonObject market(Account me, boolean refresh) {
        long now = clock.getAsLong();
        JsonObject held = market;
        if (refresh && now - refreshedAt < REFRESH_GAP_MS && held != null) {
            refresh = false; // someone has just refreshed: their copy is the fresh one
        }
        if (!refresh && held != null && now - marketAt < MARKET_MS) {
            return held;
        }
        JsonObject failed = marketFailed;
        if (!refresh && failed != null && now - marketFailedAt < FAILED_MS) {
            return failed;
        }
        if (!marketReading.compareAndSet(false, true)) {
            return held != null ? held : failed != null ? failed : busy("The market is being read; it shows in a moment.");
        }
        try {
            JsonObject f = new JsonObject();
            f.addProperty("action", "market_read");
            if (refresh) {
                f.addProperty("refresh", true);
                refreshedAt = now;
            }
            JsonObject out = ask(me, f, "market");
            boolean down = out.has("down") && out.get("down").isJsonPrimitive() && out.get("down").getAsBoolean();
            if (out.get("ok").getAsBoolean() || down) {
                market = out;
                marketAt = now;
                marketFailed = null;
            } else {
                marketFailed = out;
                marketFailedAt = now;
            }
            return out;
        } finally {
            marketReading.set(false);
        }
    }

    /** The lottery for a page: pot, entrants, phase, wins, names (as the game server sends them), plus the network's draw. */
    public JsonObject lottery(Account me) {
        long now = clock.getAsLong();
        JsonObject held = lottery, failed = lotteryFailed, out;
        if (held != null && now - lotteryAt < LOTTERY_MS) {
            out = held.deepCopy();
        } else if (failed != null && now - lotteryFailedAt < FAILED_MS) {
            out = failed.deepCopy();
        } else if (!lotteryReading.compareAndSet(false, true)) {
            out = held != null ? held.deepCopy() : failed != null ? failed.deepCopy() : busy("The lottery is being read; it shows in a moment.");
        } else {
            try {
                JsonObject f = new JsonObject();
                f.addProperty("action", "lottery_read");
                out = ask(me, f, "lottery");
                if (out.get("ok").getAsBoolean()) {
                    lottery = out.deepCopy();
                    lotteryAt = now;
                    lotteryFailed = null;
                } else {
                    lotteryFailed = out.deepCopy();
                    lotteryFailedAt = now;
                }
            } finally {
                lotteryReading.set(false);
            }
        }
        JsonObject draw = feed.lottery();
        if (draw != null) {
            out.add("draw", draw);
        }
        return out;
    }

    private static JsonObject busy(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("message", message);
        o.addProperty("busy", true);
        return o;
    }

    private JsonObject ask(Account me, JsonObject f, String what) {
        JsonObject out = new JsonObject();
        List<String> able = feed.liveWithMarket();
        Actions act = actions.get();
        if (able.isEmpty() || act == null) {
            out.addProperty("ok", false);
            out.addProperty("message", NO_SERVER);
            return out;
        }
        String server = able.get(0);
        boolean slow = f.has("refresh"); // BSP-Core reads its whole database first (audit: 6 s was too short)
        Actions.Answer ans = slow ? act.read(server, me.mcName, me.rank.name(), f, Actions.LONG_WAIT_SECONDS) : act.read(server, me.mcName, me.rank.name(), f);
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

    // ------------------------------------------------------------------ the draw

    /** DRAW (D409, D410): refused below Admin; on the busiest server with the lottery. {@code ok}, {@code message}, {@code result}, {@code server}. */
    public JsonObject draw(Account me) {
        JsonObject out = new JsonObject();
        if (!me.rank.atLeast(Rank.ADMIN)) {
            out.addProperty("ok", false);
            out.addProperty("message", "Only Admins and Owners draw the lottery.");
            out.addProperty("status", 403);
            return out;
        }
        List<String> able = feed.liveWithMarket();
        Actions act = actions.get();
        if (able.isEmpty() || act == null) {
            out.addProperty("ok", false);
            out.addProperty("message", NO_SERVER);
            return out;
        }
        String server = able.get(0);
        JsonObject f = new JsonObject();
        f.addProperty("action", "lottery_draw");
        long asked = clock.getAsLong();
        drawnBy = me.mcName; // before the answer: the countdown's snapshot can arrive first (audit)
        drawnAt = asked;
        Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f);
        if (ans.ok()) {
            lotteryAt = 0; // read again on the next page load
        } else if (drawnAt == asked && me.mcName.equals(drawnBy)) {
            drawnBy = null; // refused: nobody drew
        }
        out.addProperty("ok", ans.ok());
        out.addProperty("message", ans.message());
        out.addProperty("server", server);
        JsonElement result = ans.data() != null && ans.data().isJsonObject() ? ans.data().getAsJsonObject().get("result") : null;
        out.addProperty("result", result != null && result.isJsonPrimitive() ? result.getAsString() : "");
        return out;
    }

    /** Who drew from the panel, when it was within {@link #DRAWN_BY_MS} of {@code at}; else null (a draw from the game). */
    private String drawnBy(long at) {
        String by = drawnBy;
        return by != null && Math.abs(at - drawnAt) <= DRAWN_BY_MS ? by : null;
    }

    // ------------------------------------------------------------------ watching the feed (D411)

    /** Once a second: a draw seen beginning, or ending, anywhere on the network is told to every open page. */
    public void tick() {
        JsonObject now = feed.lottery();
        JsonObject was = counting;
        if (now != null && was == null) {
            counting = now;
            JsonObject copy = lottery;
            entrantsAtDraw = copy != null && copy.has("entrants") && copy.get("entrants").isJsonPrimitive() ? copy.get("entrants").getAsInt() : -1;
            JsonObject e = now.deepCopy();
            e.addProperty("kind", "counting");
            long since = now.get("since").getAsLong();
            String by = drawnBy(since > 0 ? since : clock.getAsLong());
            if (by != null) {
                e.addProperty("by", by);
            }
            events.accept(e);
        } else if (now == null && was != null) {
            counting = null;
            long since = was.get("since").getAsLong();
            background.accept(() -> told(since > 0 ? since : clock.getAsLong() - 30_000L));
        } else if (now != null) {
            counting = now;
        }
    }

    private volatile int entrantsAtDraw = -1;

    /**
     * After a draw: the newest win, when it came after the draw began (read again once 2 s later if not there yet, or the
     * read was refused); else that it ended without one. Always exactly one event, whatever the answer holds (audit).
     */
    private void told(long since) {
        lotteryAt = 0;
        JsonObject e = new JsonObject();
        String by = drawnBy(since);
        JsonObject win = null;
        try {
            win = newestWin(since);
            if (win == null) {
                Thread.sleep(2_000L); // BSP-Core may still be writing the win, or the panel's actions were all busy
                win = newestWin(since);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException bad) {
            win = null;
        }
        if (by != null) {
            e.addProperty("by", by);
        }
        if (entrantsAtDraw >= 0) {
            e.addProperty("entrants", entrantsAtDraw);
        }
        if (win != null) {
            e.addProperty("kind", "won");
            e.addProperty("name", win.get("name").getAsString());
            e.addProperty("pieces", win.get("pieces").getAsLong());
            e.addProperty("at", win.get("at").getAsLong());
        } else {
            e.addProperty("kind", "ended");
        }
        if (by != null) {
            drawnBy = null; // told once
        }
        events.accept(e);
    }

    /** The newest win when it is whole and came after the draw began, else null; keeps the lottery copy fresh. */
    private JsonObject newestWin(long since) {
        List<String> able = feed.liveWithMarket();
        Actions act = actions.get();
        if (able.isEmpty() || act == null) {
            return null;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "lottery_read");
        Actions.Answer ans = act.read(able.get(0), "panel", Rank.MODERATOR.name(), f);
        if (!ans.ok() || ans.data() == null || !ans.data().isJsonObject()) {
            return null;
        }
        JsonObject d = ans.data().getAsJsonObject();
        JsonObject copy = d.deepCopy();
        copy.addProperty("ok", true);
        copy.addProperty("message", ans.message());
        copy.addProperty("server", able.get(0));
        copy.addProperty("at", clock.getAsLong());
        lottery = copy;
        lotteryAt = clock.getAsLong();
        JsonArray wins = d.has("wins") && d.get("wins").isJsonArray() ? d.getAsJsonArray("wins") : new JsonArray();
        if (wins.isEmpty() || !wins.get(0).isJsonObject()) {
            return null;
        }
        JsonObject w = wins.get(0).getAsJsonObject();
        try {
            if (w.get("name").getAsString().isEmpty() || w.get("at").getAsLong() < since - 10_000L) {
                return null;
            }
            w.get("pieces").getAsLong();
            return w;
        } catch (RuntimeException | Error notWhole) {
            return null; // a win without its name, pieces or time is not told (audit)
        }
    }
}
