package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.steals.StealRecorder;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The staff web panel's TOTEMS page, this server's part (SU-96, owner decisions D613 to D622): the action
 * {@code totem_board} answers BSP-Core's network board and the steals running anywhere, which the panel asks one server
 * for every 30 seconds (D618); and every steal that starts or ends here is told to the panel at once on the web channel
 * (kind {@code steal}, D620), beside the row {@link StealRecorder} writes. The JSON is the contract with the panel,
 * documented in docs/API.md ("Totem steals"); {@link #VERSION} changes only when a field changes meaning or goes away.
 */
public final class WebTotems {
    public static final String KIND_STEAL = "steal";
    public static final int VERSION = 1;
    /** At most this many board lines and running steals in one answer (the answer travels on Redis). */
    public static final int MAX_BOARD = 1000, MAX_STEALING = 200;
    static final String NO_SCORES = "This server cannot read BSP-Core's board (BSP-Core is not installed here).";

    @Nullable
    private static Consumer<String> sentForTests;

    private WebTotems() {
    }

    /** The snapshot's {@code totems}: whether this server can answer {@code totem_board}. */
    public static void addTo(JsonObject snapshot) {
        snapshot.addProperty("totems", BspCore.scores() != null);
    }

    /** The board, best first, and the steals running on the network; read on the server thread. */
    public static WebActions.Answer board(MinecraftServer server, String id) {
        BspCore.Scores sc = BspCore.scores();
        if (sc == null) {
            return new WebActions.Answer(id, false, NO_SCORES);
        }
        try {
            long at = System.currentTimeMillis(); // when the board was read here, before anything else
            List<BspCore.BoardLine> lines = sc.board(server).stream().filter(l -> l != null && l.player() != null).toList(); // a bad line never fails the rest (audit)
            List<BspCore.StealNow> running = sc.stealing(server).stream().filter(x -> x != null && x.thief() != null).toList();
            JsonObject d = new JsonObject();
            d.addProperty("v", VERSION);
            d.addProperty("server", Storage.currentServerId());
            d.addProperty("at", at);
            d.addProperty("lines", lines.size());
            d.addProperty("ready", ready(server, lines.isEmpty()));
            JsonArray board = new JsonArray();
            for (BspCore.BoardLine l : lines.subList(0, Math.min(MAX_BOARD, lines.size()))) {
                JsonObject o = new JsonObject();
                o.addProperty("id", l.player().toString());
                o.addProperty("name", l.name());
                o.addProperty("rank", l.rank());
                o.addProperty("points", l.points());
                o.addProperty("totems", l.totems());
                o.addProperty("tier", l.bestTier());
                board.add(o);
            }
            d.add("board", board);
            JsonArray stealing = new JsonArray();
            for (BspCore.StealNow x : running.subList(0, Math.min(MAX_STEALING, running.size()))) {
                JsonObject o = new JsonObject();
                // this server's own steals under this mod's server name, as its rows and live messages are (audit: BSP-Core's own name may differ)
                o.addProperty("server", x.local() ? Storage.currentServerId() : x.serverId());
                o.addProperty("dim", x.dimension());
                o.addProperty("x", x.x());
                o.addProperty("y", x.y());
                o.addProperty("z", x.z());
                o.addProperty("thief", x.thief().toString());
                o.addProperty("thiefName", x.thiefName());
                o.addProperty("owner", x.owner() == null ? "" : x.owner().toString());
                o.addProperty("ownerName", StealRecorder.nameOf(server, x.owner()));
                o.addProperty("left", Math.round(Math.max(0f, Math.min(1f, x.fractionLeft())) * 1000) / 1000.0);
                stealing.add(o);
            }
            d.add("stealing", stealing);
            return new WebActions.Answer(id, true, lines.size() + " on the board, " + running.size() + " stealing now", d, null);
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the staff panel's board answer failed: {}", e.toString());
            return new WebActions.Answer(id, false, "The board could not be read on " + Storage.currentServerId() + " (BSP-Core answered with an error; its log says why).");
        }
    }

    /**
     * Whether BSP-Core has built its board since this server started: until then {@code BspScores.board} is empty, and a
     * panel reading it would think everyone had lost their totems (audit). Built after {@code scoring.refreshSeconds} of
     * ticks (30 s); "ready" from twice that, or as soon as the board holds anyone.
     */
    static boolean ready(MinecraftServer server, boolean empty) {
        if (!empty) {
            return true;
        }
        int refresh = Math.max(5, BspCore.setting("SCORE_REFRESH_SECONDS", Integer.class).orElse(30));
        return server.getTickCount() >= 2 * refresh * 20;
    }

    /** A steal began here: told to the panel at once. */
    public static void announceStart(MinecraftServer server, GlobalPos where, @Nullable UUID owner, UUID thief, String thiefName, int seconds, long atMs) {
        JsonObject o = head("start", UUID.randomUUID().toString(), atMs);
        place(o, where.dimension().location().toString(), where.pos().getX(), where.pos().getY(), where.pos().getZ());
        o.addProperty("thief", thief.toString());
        o.addProperty("thiefName", StealRecorder.clip(thiefName, 32));
        o.addProperty("owner", owner == null ? "" : owner.toString());
        o.addProperty("ownerName", StealRecorder.clip(StealRecorder.nameOf(server, owner), 32));
        o.addProperty("seconds", Math.max(0, seconds));
        BspCore.TotemInfo info = BspCore.totemInfo(server, where); // what is being stolen, for "stealing now" (audit of SU-99)
        o.addProperty("source", info.source());
        o.addProperty("tier", info.tier());
        send(o);
    }

    /** A steal ended here: its row's signed copy ({@link #signedEnd}), told to the panel at once. */
    public static void announceEnd(StorageBackend.Steal r) {
        if (r.line().isEmpty() || !WebAgent.enabled() || Storage.active() == null) {
            return;
        }
        if (sentForTests != null) {
            sentForTests.accept(r.line());
        }
        Storage.active().publishWeb(KIND_STEAL, r.line(), false);
    }

    /**
     * The ended steal as the panel believes it, signed with the action key: the {@code steal} end message, also kept as the
     * row's {@code line} so the table cannot be filled with made-up steals by someone with only the database (audit of
     * SU-97). Empty without a key.
     */
    public static String signedEnd(StorageBackend.Steal r) {
        byte[] key = WebActions.currentKey();
        if (key == null) {
            return "";
        }
        JsonObject o = head("end", r.uid(), r.endedMs());
        o.addProperty("server", r.server()); // the row's server, as written
        place(o, r.dimension(), r.x(), r.y(), r.z());
        o.addProperty("thief", r.thief().toString());
        o.addProperty("thiefName", r.thiefName());
        o.addProperty("owner", r.owner() == null ? "" : r.owner().toString());
        o.addProperty("ownerName", r.ownerName());
        o.addProperty("seconds", r.expectedSeconds());
        o.addProperty("source", r.source());
        o.addProperty("tier", r.tier());
        o.addProperty("points", r.points());
        o.addProperty("started", r.startedMs());
        o.addProperty("ended", r.endedMs());
        o.addProperty("success", r.success());
        o.addProperty("reason", r.reason());
        o.addProperty("ticks", r.ticks());
        return WebActions.signText(key, o.toString());
    }

    private static JsonObject head(String event, String uid, long atMs) {
        JsonObject o = new JsonObject();
        o.addProperty("type", KIND_STEAL); // never readable as a snapshot, an answer or a chat line
        o.addProperty("v", VERSION);
        o.addProperty("event", event);
        o.addProperty("uid", uid);
        o.addProperty("at", atMs);
        o.addProperty("server", Storage.currentServerId());
        return o;
    }

    private static void place(JsonObject o, String dim, int x, int y, int z) {
        o.addProperty("dim", dim);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
    }

    /** Signed with the action key and sent on the web channel, only while the panel is on here with shared storage and a key. */
    private static void send(JsonObject o) {
        if (!WebAgent.enabled()) {
            return;
        }
        byte[] key = WebActions.currentKey();
        Storage s = Storage.active();
        if (key == null || s == null) {
            return;
        }
        String signed = WebActions.signText(key, o.toString());
        if (sentForTests != null) {
            sentForTests.accept(signed);
        }
        s.publishWeb(KIND_STEAL, signed, false);
    }

    /** Tests: every signed message sent (null: none kept). */
    public static void sentForTests(@Nullable Consumer<String> sink) {
        sentForTests = sink;
    }
}
