package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.lag.LagNetwork;
import com.mrgregles.bsp_server_utilities.lag.LagResult;
import com.mrgregles.bsp_server_utilities.lag.LagSnapshot;
import com.mrgregles.bsp_server_utilities.lag.LagView;
import com.mrgregles.bsp_server_utilities.network.LagAskPacket;
import com.mrgregles.bsp_server_utilities.network.LagGoPacket;
import com.mrgregles.bsp_server_utilities.network.LagSamplePacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The LAG tab's LOADED half (SU-150; D746 to D748, D750, D752, D758; frames R1 to R3, R7 and R8 of
 * {@code tools/preview/lag_refined.html}), for editors. A map of the loaded chunks around a centre, coloured by
 * ENTITIES, B.E. (block entities) or WHY each is loaded; a side panel for the picked chunk with GO THERE and HOME; a legend counting
 * each colour; a footer with the world's and the network's totals. LIST shows every server's busiest chunks as a table
 * sorted by any column, with TP and SHOW ON MAP.
 * <ul>
 *   <li>The map opens on this server and the viewer's world, centred on the viewer (a white inner frame), or on the
 *   world's busiest chunk when the viewer is elsewhere. It shows as many squares as the page has room for at about 10 px
 *   a square, odd so there is a middle one, at most {@value #MAX_N} across (17 at a 480 × 270 window, 13 at 427 × 240).</li>
 *   <li>The wheel zooms out ×2 a step, up to {@value #MAX_ZOOM} steps, and back in: a square then stands for the chunks
 *   it covers (entities and block entities summed, the first reason in {@link ChunkCensus.Why}'s order, which is the
 *   census's own). Dragging moves the map; a click picks a square (zoomed out: its busiest chunk).</li>
 *   <li>Other servers are asked when the tab opens and when the map moves to another world (D748), never on a timer;
 *   the world menu shows how long ago each server answered, and greys one not heard from within
 *   {@value #SILENT_MS} ms of asking. A server is said not to have answered only once its question has closed
 *   ({@link LagNetwork#OPEN_MS}, as long as an answer between servers can take); until then the screen is waiting, and an
 *   answer that comes late is still taken.</li>
 * </ul>
 * The PROFILER half (SU-152; D749 to D752, D759 to D761; frames R4, R5 and R8) keeps the map, coloured by
 * milliseconds per tick from the last sample of the server the world menu picked ({@link LagView}): 10 S, 30 S or 60 S and
 * RUN SAMPLE (STOP while one runs), a line with the running sample's progress or the last one's who, when, tick and the
 * part not in any chunk; the side panel ranks the picked chunk's blocks and entities with TP beside the slowest of each
 * kind, OUTLINE TOP 10 ({@link LagOutline}) and GO THERE; the footer's WORST ANYWHERE chips move the map to the slowest
 * chunks of every world; LIST shows the sample's chunks. While a sample runs, PROFILE is asked about once each
 * {@value LagView#POLL_MS} ms (only while the PROFILER is open) and once more when it should have ended; a finished sample
 * is never pushed, so the screen asks.
 * <p>
 * Until this server's name is known (the staff screen's counts), no server is taken for this one where it matters: GO
 * THERE, TP and OUTLINE wait, and a map answer is not drawn (audit of SU-153); the map, the side panel and the keys' hovers
 * say so. A map answer names its server and world,
 * and is drawn only for the ones the map shows. Every ask is paced: at most one of a kind each {@value #ASK_GAP_MS} ms.
 */
final class LagTab {
    private static final float TINY = 0.6f, SMALL = 0.7f, TITLE = 0.8f;
    /** {@code LEGEND_H}: under the map, two legend lines of {@value #LEGEND_LINE} with 2 px above and below them. */
    private static final int KEYS_Y = 22, MAP_Y = 36, PROF_LINE_Y = 35, PROF_MAP_Y = 47, KEY_H = 10, LEGEND_H = 14, LEGEND_LINE = 6, FOOT_H = 14, GAP = 6, SIDE_MIN = 120,
            LINE = 10, ROW_H = 11;
    /** A square and the line after it, the target the map's size is worked out from. */
    private static final int PITCH = 10, MIN_CELL = 3, MAX_N = 33, MAX_ZOOM = 4;
    private static final long SILENT_MS = 5000, ASK_GAP_MS = 400, NOTE_MS = 5000;
    /**
     * How long after asking another server for a chunk's safe spot GO THERE or TP still moves the editor: as long as the
     * question is open, since an answer between servers without Redis takes up to about 14 s.
     */
    private static final long GO_MS = LagNetwork.OPEN_MS;
    private static final int UNLOADED = 0xFF0E1117, GRID = 0xFF0A0C10, OFF = 0xFF4A5060, GREY = 0xFF5F6676, SEL = 0xFF1A1530,
            HOVER = 0x22B58CFF, AMBER = 0xFFF2B544, WHITE = 0xFFE6EAF2, CHIP = 0xFF161B23;
    /** Entities (and block entities) 0 to 9, 10 to 49, 50 to 99, 100 and over. */
    private static final int[] RAMP = {0xFF26303C, 0xFF5E7F45, 0xFFF2B544, 0xFFFF6B5C};
    /** In {@link ChunkCensus.Why}'s order: players, a mod's ticket, /forceload, spawn, other. */
    private static final int[] WHY = {0xFF19D3B0, 0xFFB58CFF, 0xFFFFD23A, 0xFF5FA8FF, 0xFF5F6676};
    /** The sample lengths offered, in seconds (D749). */
    private static final int[] LENGTHS = {10, 30, 60};
    /** How many WORST ANYWHERE chips the footer can hold at most. */
    private static final int WORST_CHIPS = 8;

    enum Colour { ENTITIES, BLOCKS, WHY }

    /** The chunks one square stands for at a zoom. */
    private static final class Square {
        int chunks, entities, blocks, why = Integer.MAX_VALUE, entX, entZ, entMax = -1, beX, beZ, beMax = -1;
        boolean ticking;
    }

    /** A line of the list: a server's world's busy chunk. */
    private record Row(String server, String dim, LagSnapshot.ListRow r) {
    }

    /** A chunk to go to once its server has given its safe spot. */
    private record Target(String server, String dim, int x, int z) {
    }

    private final AdminScreen host;
    // what the map shows: a server ("" is this one until its name is known), a world, the middle chunk and the zoom
    private String server = "";
    @Nullable
    private String dim;
    private int cx, cz, zoom;
    private boolean centred, list, menu;
    private Colour colour = Colour.ENTITIES;
    // the map rows shown and their squares at each zoom
    @Nullable
    private ClientLag.Map shown;
    private final List<Map<Long, Square>> squares = new ArrayList<>();
    private final int[] whyCounts = new int[ChunkCensus.Why.values().length];
    // asks: one of a kind at a time, at most one each ASK_GAP_MS (the server drops closer ones)
    // (a TP's DETAIL has a slot of its own, so a pick right after it does not replace it; both keep the kind's one gap)
    @Nullable
    private LagAskPacket mapDue, detailDue, goDue, profDue;
    private long summaryAskedAt, mapSentAt, detailSentAt, goSentAt, detailKindAt, profAskAt;
    // the footer's sums of the map rows, for the rows they were worked out from (when the world's summary is missing)
    @Nullable
    private ClientLag.Map footFor;
    private int footE, footB;
    // the picked chunk
    private boolean picked;
    private int px, pz;
    // the mouse on the map
    private boolean pressed, dragging;
    private double pressX, pressY, dragX, dragY;
    // the menu and the list
    private int menuScroll, listScroll, sortCol = 4;
    private boolean sortDesc = true;
    @Nullable
    private Row listPick;
    @Nullable
    private Target goWaiting;
    private long goAskedAt, noteUntil;
    private String note = "";
    // the map's place on the last frame, for the mouse
    private int gx, gy, n = 1, cell = PITCH - 1;
    // the PROFILER (SU-152): on or off, the length picked, the last PROFILE ask, the list's sort and pick
    private boolean profiler;
    private int seconds = 30, profSortCol = 3;
    private boolean profSortDesc = true;
    private long profSentAt;
    @Nullable
    private LagResult.Chunk profPick;
    // the sample's squares at each zoom, for the result and world they were worked out for
    @Nullable
    private LagResult profFor;
    @Nullable
    private String profDim;
    private final List<Map<Long, LagView.Square>> profSquares = new ArrayList<>();
    // the lists' rows, sorted, for the data, sort and server they were made for (not made again every frame)
    @Nullable
    private List<Row> rowsMade;
    private long rowsVersion;
    private int rowsCol;
    private boolean rowsDesc;
    private String rowsHere = "";
    @Nullable
    private List<LagResult.Chunk> profRowsMade;
    @Nullable
    private LagResult profRowsFor;
    private int profRowsCol;
    private boolean profRowsDesc;

    LagTab(AdminScreen host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ asking

    void open() {
        if (dim == null) {
            server = "";
            dim = viewerDim();
        }
        ClientLag.clearMapAndDetail(); // what an earlier look left: asked again below
        summaryAskedAt = System.currentTimeMillis();
        ServerUtilsNetwork.CHANNEL.sendToServer(LagAskPacket.summaries());
        askMap();
        if (picked && loadedAt(px, pz)) {
            askDetail(px, pz);
        }
        if (profiler) {
            askProfile();
        }
    }

    /** Leaving the tab or the screen: a drag or an open menu ends, and a GO THERE waiting on another server is dropped. */
    void close() {
        pressed = dragging = menu = false;
        goWaiting = null;
        goDue = null;
    }

    /**
     * Asks for the map's world, naming its middle so a cut answer keeps what is near it: the map's centre, or before the
     * map is centred the viewer's chunk, or (the viewer elsewhere) {@link LagSnapshot#BUSIEST}, the world's busiest chunk,
     * which the map then centres on.
     */
    private void askMap() {
        int mx = cx, mz = cz;
        if (!centred) {
            if (viewerHere()) {
                ChunkPos v = Minecraft.getInstance().player.chunkPosition();
                mx = v.x;
                mz = v.z;
            } else {
                mx = mz = LagSnapshot.BUSIEST;
            }
        }
        mapDue = new LagAskPacket(LagAskPacket.Kind.MAP, server, dim, mx, mz);
        flush();
    }

    private void askDetail(int x, int z) {
        detailDue = new LagAskPacket(LagAskPacket.Kind.DETAIL, server, dim, x, z);
        flush();
    }

    private void flush() {
        long now = System.currentTimeMillis();
        if (mapDue != null && now - mapSentAt >= ASK_GAP_MS) {
            ServerUtilsNetwork.CHANNEL.sendToServer(mapDue);
            mapDue = null;
            mapSentAt = now;
        }
        if (goDue != null && now - detailKindAt >= ASK_GAP_MS) {
            ServerUtilsNetwork.CHANNEL.sendToServer(goDue); // a TP's first: the editor waits on it
            goDue = null;
            goSentAt = now;
            detailKindAt = now;
        }
        if (detailDue != null && now - detailKindAt >= ASK_GAP_MS) {
            ServerUtilsNetwork.CHANNEL.sendToServer(detailDue);
            detailDue = null;
            detailSentAt = now;
            detailKindAt = now;
        }
        if (profDue != null && now - profAskAt >= ASK_GAP_MS) {
            ServerUtilsNetwork.CHANNEL.sendToServer(profDue);
            profDue = null;
            profAskAt = now;
            profSentAt = now;
        }
    }

    void tick() {
        flush();
        if (goWaiting != null && goDue == null) {
            Target r = goWaiting;
            LagSnapshot.Detail d = ClientLag.detail(from -> sameId(from, r.server()), r.dim(), r.x(), r.z(), goSentAt);
            if (d == null && System.currentTimeMillis() - goAskedAt > GO_MS) {
                goWaiting = null; // too late to move the editor: they may be doing something else
                note(Tr.t("lag.no_answer", r.server()));
            } else if (d != null) {
                goWaiting = null;
                if (d.loaded() && d.spot() != null) {
                    go(r.server(), r.dim(), r.x(), r.z(), d.spot());
                } else {
                    note(Tr.t("lag.list.no_spot", r.server(), r.x(), r.z()));
                }
            }
        }
        if (profiler) {
            // a running sample: asked about each POLL_MS, and once more when it should have ended (a finished one is not pushed)
            ClientLag.Profile cp = profileAnswer();
            LagResult run = cp == null ? null : cp.profile().running();
            if (run != null) {
                long now = System.currentTimeMillis(), endAt = cp.atMs() + LagView.leftMs(run.ticksAsked(), run.ticks(), 0);
                if (LagView.askDue(now, profSentAt, endAt, profSentAt >= endAt)) {
                    askProfile();
                }
            }
        }
    }

    // ------------------------------------------------------------------ the profiler's asks

    /** Asks the picked server for its samples (PROFILE): the one running and the last finished; paced as the other asks. */
    private void askProfile() {
        profDue = new LagAskPacket(LagAskPacket.Kind.PROFILE, isHere(server) ? "" : server, "", 0, 0);
        flush();
    }

    /** RUN SAMPLE or STOP on the picked server (D761); its PROFILE comes back. */
    private void sample(LagSamplePacket.Action action) {
        ServerUtilsNetwork.CHANNEL.sendToServer(new LagSamplePacket(isHere(server) ? "" : server, action, action == LagSamplePacket.Action.START ? seconds : 0));
        profSentAt = System.currentTimeMillis();
    }

    /** The server the PROFILER is about, by name; empty while this server's name is not known (never guessed). */
    private String profKey() {
        return server.isEmpty() ? here() : server;
    }

    @Nullable
    private ClientLag.Profile profileAnswer() {
        String k = profKey();
        return k.isEmpty() ? null : ClientLag.profile(k);
    }

    @Nullable
    private LagResult running() {
        ClientLag.Profile cp = profileAnswer();
        return cp == null ? null : cp.profile().running();
    }

    @Nullable
    private LagResult last() {
        ClientLag.Profile cp = profileAnswer();
        return cp == null ? null : cp.profile().last();
    }

    private void setProfiler(boolean on) {
        if (profiler == on) {
            return;
        }
        profiler = on;
        menu = false;
        listScroll = 0;
        if (on) {
            askProfile();
        }
    }

    /** The last sample's squares at a zoom in the map's world, worked out again when the result or the world changes. */
    private Map<Long, LagView.Square> profSquare(int zoomAt) {
        LagResult r = last();
        if (r != profFor || !java.util.Objects.equals(dim, profDim) || profSquares.isEmpty()) {
            profFor = r;
            profDim = dim;
            profSquares.clear();
            for (int z = 0; z <= MAX_ZOOM; z++) {
                profSquares.add(r == null || dim == null ? Map.of() : LagView.squares(r.chunks(), dim, z));
            }
        }
        return profSquares.get(zoomAt);
    }

    /** The last sample's chunk at a place in the map's world, or null. */
    @Nullable
    private LagResult.Chunk timed(int x, int z) {
        LagResult r = last();
        if (r == null || dim == null) {
            return null;
        }
        for (LagResult.Chunk c : r.chunks()) {
            if (c.x() == x && c.z() == z && c.dimension().equals(dim)) {
                return c;
            }
        }
        return null;
    }

    private void note(String s) {
        note = s;
        noteUntil = System.currentTimeMillis() + NOTE_MS;
    }

    // ------------------------------------------------------------------ the servers

    private static String viewerDim() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "minecraft:overworld" : mc.level.dimension().location().toString();
    }

    /** This server's name, from the staff screen's counts; empty until they come (it is never guessed from an answer). */
    private String here() {
        return host.hereServer();
    }

    /** Whether this server's name is known: until it is, GO THERE, TP and OUTLINE wait. */
    private boolean hereKnown() {
        return !here().isEmpty();
    }

    /** Two server names as the notices write them ({@code |} cleaned to {@code /} on the way between servers). */
    private static boolean sameId(String a, String b) {
        return a.replace('|', '/').equals(b.replace('|', '/'));
    }

    /** Whether a server is this one: {@code ""} always is; a name only once this server's own name is known. */
    private boolean isHere(String s) {
        return s.isEmpty() || hereKnown() && sameId(s, here());
    }

    private String serverName(String s) {
        if (!s.isEmpty()) {
            return s;
        }
        return here().isEmpty() ? Tr.t("lag.this_server") : here();
    }

    /** Whether the viewer stands in the world the map shows. */
    private boolean viewerHere() {
        return Minecraft.getInstance().player != null && isHere(server) && viewerDim().equals(dim);
    }

    /** A server that has not answered the last ask within {@value #SILENT_MS} ms (greyed; said not to have answered only after {@link #GO_MS}). */
    private boolean stale(ClientLag.Summaries s) {
        return s.atMs() < summaryAskedAt && System.currentTimeMillis() - summaryAskedAt > SILENT_MS;
    }

    /**
     * An answer not in yet, asked at {@code sentAt} (0: not sent): {@code asking} at first, then that the map's server is
     * waited for, and once its question has closed ({@link #GO_MS}) that it did not answer.
     */
    private String waitText(long sentAt, String asking) {
        long since = System.currentTimeMillis() - sentAt;
        if (sentAt == 0 || since <= SILENT_MS) {
            return asking;
        }
        return since <= GO_MS ? Tr.t("lag.waiting_for", serverName(server)) : Tr.t("lag.no_answer", serverName(server));
    }

    /** The servers that answered, this one first, then by name. */
    private List<String> servers() {
        List<String> out = new ArrayList<>(ClientLag.summaries().keySet());
        String h = here();
        out.sort(Comparator.comparing((String s) -> !s.equals(h)).thenComparing(s -> s.toLowerCase(Locale.ROOT)));
        return out;
    }

    @Nullable
    private LagSnapshot.Summary summary(String s, @Nullable String d) {
        String k = s.isEmpty() ? here() : s;
        ClientLag.Summaries ss = k.isEmpty() ? null : ClientLag.summaries().get(k);
        if (ss == null || d == null) {
            return null;
        }
        return ss.worlds().stream().filter(w -> w.dimension().equals(d)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ the map's data

    /** Takes the last map answer when it is the one asked for (its server and world the map's), and works out its squares. */
    private void takeMap() {
        ClientLag.Map m = ClientLag.map();
        if (m == null || m == shown || mapSentAt == 0 || m.atMs() < mapSentAt || mapDue != null || !m.dimension().equals(dim)) {
            return;
        }
        if (server.isEmpty()) {
            if (!hereKnown() || !sameId(m.server(), here())) {
                return; // this server's name is not known yet: wait for it rather than guess
            }
            server = here();
        } else if (!sameId(m.server(), server)) {
            return;
        }
        shown = m;
        index(m.rows());
        if (!centred) {
            centred = true;
            if (viewerHere()) {
                ChunkPos v = Minecraft.getInstance().player.chunkPosition();
                cx = v.x;
                cz = v.z;
            } else {
                LagSnapshot.MapRow top = LagSnapshot.busiest(m.rows()); // the chunk a cut answer kept the rows nearest
                cx = top == null ? 0 : top.x();
                cz = top == null ? 0 : top.z();
            }
        }
    }

    private void index(List<LagSnapshot.MapRow> rows) {
        squares.clear();
        java.util.Arrays.fill(whyCounts, 0);
        for (int z = 0; z <= MAX_ZOOM; z++) {
            squares.add(new HashMap<>());
        }
        for (LagSnapshot.MapRow r : rows) {
            whyCounts[r.why().ordinal()]++;
            for (int z = 0; z <= MAX_ZOOM; z++) {
                int s = 1 << z;
                Square q = squares.get(z).computeIfAbsent(ChunkPos.asLong(Math.floorDiv(r.x(), s), Math.floorDiv(r.z(), s)), k -> new Square());
                q.chunks++;
                q.entities += r.entities();
                q.blocks += r.blockEntities();
                q.why = Math.min(q.why, r.why().ordinal());
                q.ticking |= r.ticking();
                if (r.entities() > q.entMax) {
                    q.entMax = r.entities();
                    q.entX = r.x();
                    q.entZ = r.z();
                }
                if (r.blockEntities() > q.beMax) {
                    q.beMax = r.blockEntities();
                    q.beX = r.x();
                    q.beZ = r.z();
                }
            }
        }
    }

    @Nullable
    private Square square(int zoomAt, int sx, int sz) {
        return squares.isEmpty() ? null : squares.get(zoomAt).get(ChunkPos.asLong(sx, sz));
    }

    private boolean loadedAt(int x, int z) {
        return square(0, x, z) != null;
    }

    /** The picked chunk's detail when it is the answer to the last ask about it, from the map's server. */
    @Nullable
    private LagSnapshot.Detail detail() {
        String s = server.isEmpty() ? here() : server;
        if (detailDue != null || detailSentAt == 0 || s.isEmpty() || dim == null) {
            return null;
        }
        return ClientLag.detail(from -> sameId(from, s), dim, px, pz, detailSentAt);
    }

    /** Whether the map shows this server while its name is not known: what waits on the name says so. */
    private boolean waitingForHere() {
        return server.isEmpty() && !hereKnown();
    }

    /** The map's rows and squares are dropped (another world or server): its counts by reason with them. */
    private void resetMap() {
        shown = null;
        squares.clear();
        java.util.Arrays.fill(whyCounts, 0);
        footFor = null;
    }

    // ------------------------------------------------------------------ actions

    /** Shows another world (or asks this one again): its map is asked for, the map centres afresh. */
    private void select(String s, String d) {
        menu = false;
        boolean sameServer = s.equals(server) || isHere(s) && isHere(server);
        boolean same = sameServer && d.equals(dim);
        if (!same) {
            server = s;
            dim = d;
            resetMap();
            centred = false;
            cx = cz = 0; // the last world's centre means nothing in this one: the ask names the viewer or the busiest chunk
            picked = false;
            zoom = 0;
        }
        askMap();
        if (profiler && !sameServer) {
            profPick = null;
            askProfile(); // one server per sample (D761): the menu picks it
        }
    }

    private void pick(int x, int z) {
        picked = true;
        px = x;
        pz = z;
        if (loadedAt(x, z)) {
            askDetail(x, z);
        }
    }

    /** HOME: back to the viewer, in their world on this server. */
    private void home() {
        zoom = 0;
        if (viewerHere()) {
            ChunkPos v = Minecraft.getInstance().player.chunkPosition();
            cx = v.x;
            cz = v.z;
        } else {
            select(here(), viewerDim());
        }
    }

    /**
     * GO THERE or TP: the move is the server's (D750, D752); the screen closes so the editor sees where they are. Whether
     * another server can be gone to (BSP-Teleport) is the server's to say: it tells the editor when it cannot.
     */
    private void go(String s, String d, int x, int z, @Nullable BlockPos spot) {
        if (!hereKnown()) {
            note(Tr.t("lag.side.wait_here"));
            return;
        }
        boolean local = isHere(s);
        ServerUtilsNetwork.CHANNEL.sendToServer(new LagGoPacket(local ? "" : s, d, x, z, spot == null ? 0 : spot.getX(), spot == null ? 0 : spot.getY(), spot == null ? 0 : spot.getZ()));
        host.onClose();
    }

    /** TP on a list row or GO THERE: this server's chunk at once; another server's once it has given its safe spot. */
    private void goChunk(String s, String d, int x, int z) {
        if (!hereKnown()) {
            note(Tr.t("lag.side.wait_here"));
            return;
        }
        if (isHere(s)) {
            go("", d, x, z, null);
            return;
        }
        goWaiting = new Target(s, d, x, z);
        goAskedAt = System.currentTimeMillis();
        goDue = new LagAskPacket(LagAskPacket.Kind.DETAIL, s, d, x, z);
        flush();
        note(Tr.t("lag.list.asking", s, x, z));
    }

    /**
     * TP on a block or entity in the PROFILER (D750): on this server beside its slowest one, facing it; on another, as GO
     * THERE does, to the safe spot that server gives in the chunk.
     */
    private void goBeside(String d, int chunkX, int chunkZ, BlockPos at) {
        if (!hereKnown()) {
            note(Tr.t("lag.side.wait_here"));
            return;
        }
        if (isHere(server)) {
            ServerUtilsNetwork.CHANNEL.sendToServer(new LagGoPacket("", d, chunkX, chunkZ, at.getX(), at.getY(), at.getZ(), true));
            host.onClose();
            return;
        }
        goChunk(server, d, chunkX, chunkZ);
    }

    private void showOnMap(Row r) {
        showChunk(r.server(), r.dim(), r.r().x(), r.r().z());
    }

    /** The map on a chunk of a server's world, picked (SHOW ON MAP, a WORST ANYWHERE chip). */
    private void showChunk(String s, String d, int x, int z) {
        list = false;
        menu = false;
        boolean same = (s.equals(server) || isHere(s) && isHere(server)) && d.equals(dim);
        centred = true;
        cx = x;
        cz = z;
        zoom = 0;
        if (!same) {
            server = s;
            dim = d;
            resetMap();
            askMap(); // after the centre is set: the ask names it
        }
        picked = true;
        px = x;
        pz = z;
        askDetail(px, pz);
    }

    private void toMap() {
        list = false;
        if (picked && loadedAt(px, pz) && detail() == null) {
            askDetail(px, pz); // a TP from the list may have asked about another chunk since
        }
    }

    // ------------------------------------------------------------------ the mouse

    /** Where the mouse is on the map: the square's column and row, or null. */
    @Nullable
    private int[] squareAt(double mx, double my) {
        if (list || mx < gx || my < gy || mx >= gx + mapW() || my >= gy + mapW()) {
            return null;
        }
        int i = (int) ((mx - gx) / (cell + 1)), j = (int) ((my - gy) / (cell + 1));
        return i < n && j < n ? new int[]{i, j} : null;
    }

    boolean press(double mx, double my) {
        if (menu || squareAt(mx, my) == null) {
            return false;
        }
        pressed = true;
        dragging = false;
        pressX = mx;
        pressY = my;
        return true;
    }

    boolean drag(double mx, double my, double dx, double dy) {
        if (!pressed) {
            return false;
        }
        if (!dragging) {
            if (Math.abs(mx - pressX) <= 2 && Math.abs(my - pressY) <= 2) {
                return true;
            }
            dragging = true;
            dragX = mx - pressX;
            dragY = my - pressY;
        } else {
            dragX += dx;
            dragY += dy;
        }
        int pitch = cell + 1, s = 1 << zoom;
        int mi = (int) (dragX / pitch), mj = (int) (dragY / pitch);
        cx -= mi * s;
        cz -= mj * s;
        dragX -= mi * pitch;
        dragY -= mj * pitch;
        return true;
    }

    boolean release(double mx, double my) {
        if (!pressed) {
            return false;
        }
        pressed = false;
        if (!dragging) {
            click(mx, my);
        }
        dragging = false;
        return true;
    }

    private void click(double mx, double my) {
        int[] at = squareAt(mx, my);
        if (at == null) {
            return;
        }
        int s = 1 << zoom, sx = Math.floorDiv(cx, s) - n / 2 + at[0], sz = Math.floorDiv(cz, s) - n / 2 + at[1];
        if (zoom == 0) {
            pick(sx, sz);
            return;
        }
        Square q = square(zoom, sx, sz);
        LagView.Square t = profiler ? profSquare(zoom).get(ChunkPos.asLong(sx, sz)) : null;
        if (t != null) {
            pick(t.worstX(), t.worstZ()); // zoomed out in the PROFILER: its slowest chunk
        } else if (q == null) {
            pick(sx * s + s / 2, sz * s + s / 2);
        } else if (colour == Colour.BLOCKS) {
            pick(q.beX, q.beZ);
        } else {
            pick(q.entX, q.entZ);
        }
    }

    boolean scroll(double mx, double my, double delta) {

        int step = (int) Math.signum(delta);
        if (menu) {
            menuScroll = Math.max(0, menuScroll - step);
        } else if (list) {
            listScroll = Math.max(0, listScroll - step);
        } else if (squareAt(mx, my) != null) {
            zoom = Math.max(0, Math.min(MAX_ZOOM, zoom - step));
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing

    private static String up(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static String num(int v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static int ramp(int v) {
        return v < 10 ? RAMP[0] : v < 50 ? RAMP[1] : v < 100 ? RAMP[2] : RAMP[3];
    }

    private static int whyColour(int ordinal) {
        return ordinal >= 0 && ordinal < WHY.length ? WHY[ordinal] : WHY[WHY.length - 1];
    }

    private static String whyWord(ChunkCensus.Why w) {
        return switch (w) {
            case PLAYER -> Tr.t("lag.why.player");
            case TICKET -> Tr.t("lag.why.ticket");
            case FORCED -> Tr.t("lag.why.forced");
            case SPAWN -> Tr.t("lag.why.spawn");
            case OTHER -> Tr.t("lag.why.other");
        };
    }

    private static String loadedBy(ChunkCensus.Why w, String who) {
        return who.isEmpty() ? whyWord(w) : whyWord(w) + " · " + who;
    }

    /** A kind of entity or block entity without the game's own namespace. */
    private static String kind(String type) {
        return type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int keyW(String label) {
        return Ui.widthBold(host.font(), label, TINY) + 10;
    }

    /** A key under the heading line: lit (violet), plain, or off (grey, no click). */
    private void key(GuiGraphics g, int x, int y, int w, String label, boolean on, boolean enabled, int mx, int my, List<Ui.Hit> hits, @Nullable Runnable action) {
        Font font = host.font();
        g.fill(x, y, x + w, y + KEY_H, Ui.SLOT);
        if (enabled && !on && in(mx, my, x, y, w, KEY_H)) {
            g.fill(x, y, x + w, y + KEY_H, HOVER);
        }
        Ui.frame(g, x, y, w, KEY_H, on ? Ui.VIOLET : Ui.DIM);
        String fitted = Ui.fitBold(font, label, w - 6, TINY);
        Ui.text(g, font, Component.literal(fitted).withStyle(ChatFormatting.BOLD), x + (w - Ui.widthBold(font, fitted, TINY)) / 2, y + 3, on ? Ui.VIOLET : enabled ? Ui.MUTED : OFF, TINY);
        if (enabled && action != null) {
            hits.add(new Ui.Hit(x, y, w, KEY_H, action));
        }
    }

    /** A key in a box (GO THERE, HOME, TP, SHOW ON MAP): {@code colour} its outline and text. */
    private int boxKey(GuiGraphics g, int x, int y, int h, String label, int colour, boolean enabled, int mx, int my, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        String s = up(label);
        int w = Ui.widthBold(font, s, TINY) + 10;
        boolean hot = enabled && in(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, hot ? 0xFF1E2430 : Ui.SLOT);
        Ui.frame(g, x, y, w, h, enabled ? colour : Ui.DIM);
        Ui.text(g, font, Component.literal(s).withStyle(ChatFormatting.BOLD), x + 5, y + (h - 4) / 2, enabled ? colour : OFF, TINY);
        if (enabled) {
            hits.add(new Ui.Hit(x, y, w, h, action));
        }
        return w;
    }

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        takeMap();
        int start = hits.size();
        String tip = keys(g, x, y, mx, my, hits);
        String tip2;
        if (profiler) {
            drawProfLine(g, x, y);
        }
        if (list) {
            tip2 = profiler ? drawProfList(g, x, y, mx, my, hits) : drawList(g, x, y, mx, my, hits);
        } else {
            layout(x, y);
            drawMap(g, mx, my);
            tip2 = profiler ? drawProfSide(g, x, mx, my, hits) : drawSide(g, x, mx, my, hits);
            drawLegend(g, x, y);
        }
        String tip3 = drawFooter(g, x, y, mx, my, hits);
        if (tip2 == null) {
            tip2 = tip3;
        }
        if (menu) {
            hits.subList(start, hits.size()).clear(); // while the menu is open only its lines (and closing it) take clicks
            drawMenu(g, x, y, mx, my, hits);
        }
        String t = tip2 != null ? tip2 : tip;
        if (t != null && !menu && !pressed) {
            g.renderTooltip(host.font(), host.font().split(Component.literal(t), 200), mx, my);
        }
    }

    /** The world key's left edge on the last frame, for the menu under it. */
    private int menuX;

    /** LOADED and PROFILER, the world key, the colour keys and LIST (or MAP); short forms when the page is narrow. */
    @Nullable
    private String keys(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        int ky = y + KEYS_Y, kx = x + 8, right = x + host.pageW() - 8;
        String loaded = up(Tr.t("lag.loaded")), prof = up(Tr.t("lag.profiler")), other = up(list ? Tr.t("lag.map") : Tr.t("lag.list"));
        int lw = keyW(loaded), pw = keyW(prof), ow = keyW(other);
        String tip = null;
        key(g, kx, ky, lw, loaded, !profiler, true, mx, my, hits, () -> setProfiler(false));
        key(g, kx + lw - 1, ky, pw, prof, profiler, true, mx, my, hits, () -> setProfiler(true));
        kx += lw + pw + 3;
        int ox = right - ow;
        key(g, ox, ky, ow, other, false, true, mx, my, hits, () -> {
            menu = false;
            if (list) {
                toMap();
            } else {
                list = true;
            }
        });
        if (list && !profiler || dim == null) {
            return tip;
        }
        String world = up(serverName(server) + " · " + MobsTab.dimName(dim)), worldShort = up(MobsTab.dimName(dim));
        int room = ox - 4 - kx;
        if (profiler) {
            return profKeys(g, kx, ky, room, world, worldShort, mx, my, hits);
        }
        String[] longC = {up(Tr.t("lag.c.entities")), up(Tr.t("lag.c.blocks")), up(Tr.t("lag.c.why"))};
        String[] shortC = {up(Tr.t("lag.c.entities_short")), up(Tr.t("lag.c.blocks")), up(Tr.t("lag.c.why"))};
        int longW = keyW(world + ARROW) + 3 + colourW(longC);
        String[] cl = longC;
        String w = world;
        if (longW > room) {
            w = worldShort; // a narrow page (R7): the world without its server, the colours short
            cl = shortC;
        }
        int cw = colourW(cl);
        int ww = Math.max(24, Math.min(keyW(w + ARROW), room - 3 - cw));
        menuX = kx;
        key(g, kx, ky, ww, arrowed(w, ww), menu, true, mx, my, hits, () -> {
            menu = !menu;
            menuScroll = 0;
        });
        int cxk = kx + ww + 3;
        for (int i = 0; i < 3; i++) {
            Colour c = Colour.values()[i];
            int kw = keyW(cl[i]);
            key(g, cxk, ky, kw, cl[i], colour == c, true, mx, my, hits, () -> colour = c);
            cxk += kw - 1;
        }
        return tip;
    }

    /** The PROFILER's keys after LOADED and PROFILER: the server and world, 10 S / 30 S / 60 S, RUN SAMPLE or STOP. */
    @Nullable
    private String profKeys(GuiGraphics g, int kx, int ky, int room, String world, String worldShort, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        LagResult run = running();
        String[] secs = new String[LENGTHS.length];
        for (int i = 0; i < secs.length; i++) {
            secs[i] = up(Tr.t("lag.p.seconds", LENGTHS[i]));
        }
        // RUN SAMPLE and STOP: the wider of the two is measured for the keys before them, so the world key keeps one
        // width and one form (the short one when the long does not fit beside either) as a sample starts and ends
        String runL = Ui.fitBold(font, up(Tr.t("lag.p.run")), Math.max(16, room / 3), TINY), stopL = Ui.fitBold(font, up(Tr.t("lag.p.stop")), Math.max(16, room / 3), TINY);
        String go = run != null ? stopL : runL;
        int rw = Ui.widthBold(font, go, TINY) + 10, rwMax = Math.max(Ui.widthBold(font, runL, TINY), Ui.widthBold(font, stopL, TINY)) + 10;
        if (keyW(worldShort + ARROW) + 3 + colourW(secs) + 3 + rwMax > room) {
            for (int i = 0; i < secs.length; i++) {
                secs[i] = Integer.toString(LENGTHS[i]); // the narrowest page: the lengths without their unit
            }
        }
        int sw = colourW(secs);
        String w = keyW(world + ARROW) + 3 + sw + 3 + rwMax > room ? worldShort : world;
        int ww = Math.max(24, Math.min(keyW(w + ARROW), room - 3 - sw - 3 - rwMax));
        menuX = kx;
        key(g, kx, ky, ww, arrowed(w, ww), menu, true, mx, my, hits, () -> {
            menu = !menu;
            menuScroll = 0;
        });
        String tip = null;
        if (in(mx, my, kx, ky, ww, KEY_H)) {
            tip = Tr.t("lag.p.server_tip");
        }
        int sx = kx + ww + 3;
        for (int i = 0; i < secs.length; i++) {
            int len = LENGTHS[i], kw = keyW(secs[i]);
            boolean on = run != null ? run.lengthS() == len : seconds == len;
            key(g, sx, ky, kw, secs[i], on, run == null, mx, my, hits, () -> seconds = len);
            sx += kw - 1;
        }
        int rx = sx + 3;
        boolean hot = in(mx, my, rx, ky, rw, KEY_H);
        if (run != null) {
            g.fill(rx, ky, rx + rw, ky + KEY_H, hot ? 0xFF2A1A1A : Ui.SLOT);
            Ui.frame(g, rx, ky, rw, KEY_H, Ui.BAD);
            Ui.text(g, font, Component.literal(go).withStyle(ChatFormatting.BOLD), rx + 5, ky + 3, Ui.BAD, TINY);
            hits.add(new Ui.Hit(rx, ky, rw, KEY_H, () -> sample(LagSamplePacket.Action.STOP)));
            if (hot) {
                tip = Tr.t("lag.p.stop_tip");
            }
        } else {
            g.fill(rx, ky, rx + rw, ky + KEY_H, hot ? 0xFFC9A8FF : Ui.VIOLET);
            Ui.text(g, font, Component.literal(go).withStyle(ChatFormatting.BOLD), rx + 5, ky + 3, 0xFF120D1C, TINY);
            hits.add(new Ui.Hit(rx, ky, rw, KEY_H, () -> sample(LagSamplePacket.Action.START)));
            if (hot) {
                tip = Tr.t("lag.p.run_tip", seconds, serverName(server));
            }
        }
        return tip;
    }

    /** The world key's ▾, never cut: the name is fitted to the key less its width. */
    private static final String ARROW = " ▾";

    private String arrowed(String name, int keyW) {
        Font font = host.font();
        return Ui.fitBold(font, name, Math.max(0, keyW - 6 - Ui.widthBold(font, ARROW, TINY)), TINY) + ARROW;
    }

    private int colourW(String[] labels) {
        int w = 0;
        for (String s : labels) {
            w += keyW(s) - 1;
        }
        return w + 1;
    }

    /** The map's place and size: as big as the page allows beside a side panel, about {@value #PITCH} px a square. */
    private void layout(int x, int y) {
        int top = profiler ? PROF_MAP_Y : MAP_Y;
        int availH = host.pageH() - top - LEGEND_H - FOOT_H - 2;
        int availW = host.pageW() - 16 - GAP - SIDE_MIN;
        int box = Math.max(3 * MIN_CELL + 2, Math.min(availH, availW));
        n = Math.max(3, Math.min(MAX_N, (box + 1) / PITCH));
        if (n % 2 == 0) {
            n--;
        }
        cell = (box - (n - 1)) / n;
        while (cell < MIN_CELL && n > 3) {
            n -= 2;
            cell = (box - (n - 1)) / n;
        }
        gx = x + 8;
        gy = y + top;
    }

    private int mapW() {
        return n * cell + n - 1;
    }

    private void drawMap(GuiGraphics g, int mx, int my) {
        Font font = host.font();
        int w = mapW(), s = 1 << zoom, half = n / 2;
        int sx0 = Math.floorDiv(cx, s) - half, sz0 = Math.floorDiv(cz, s) - half;
        g.fill(gx - 1, gy - 1, gx + w + 1, gy + w + 1, Ui.DIM);
        g.fill(gx, gy, gx + w, gy + w, GRID);
        Map<Long, LagView.Square> timed = profiler ? profSquare(zoom) : Map.of(); // once a frame, not once a square
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                Square q = square(zoom, sx0 + i, sz0 + j);
                int c = profiler ? LagView.squareColour(timed.get(ChunkPos.asLong(sx0 + i, sz0 + j)), q != null) : q == null ? UNLOADED : switch (colour) {
                    case ENTITIES -> ramp(q.entities);
                    case BLOCKS -> ramp(q.blocks);
                    case WHY -> (whyColour(q.why) & 0x00FFFFFF) | 0xBB000000;
                };
                int x0 = gx + i * (cell + 1), y0 = gy + j * (cell + 1);
                g.fill(x0, y0, x0 + cell, y0 + cell, c);
            }
        }
        if (viewerHere()) {
            ChunkPos v = Minecraft.getInstance().player.chunkPosition();
            int vi = Math.floorDiv(v.x, s) - sx0, vj = Math.floorDiv(v.z, s) - sz0;
            if (vi >= 0 && vi < n && vj >= 0 && vj < n) {
                int t = Math.max(1, cell / 4), x0 = gx + vi * (cell + 1), y0 = gy + vj * (cell + 1);
                g.fill(x0, y0, x0 + cell, y0 + t, WHITE);
                g.fill(x0, y0 + cell - t, x0 + cell, y0 + cell, WHITE);
                g.fill(x0, y0, x0 + t, y0 + cell, WHITE);
                g.fill(x0 + cell - t, y0, x0 + cell, y0 + cell, WHITE);
            }
        }
        if (picked) {
            int pi = Math.floorDiv(px, s) - sx0, pj = Math.floorDiv(pz, s) - sz0;
            if (pi >= 0 && pi < n && pj >= 0 && pj < n) {
                Ui.frame(g, gx + pi * (cell + 1) - 1, gy + pj * (cell + 1) - 1, cell + 2, cell + 2, Ui.VIOLET);
            }
        }
        int[] at = menu || pressed ? null : squareAt(mx, my);
        if (at != null) {
            Ui.frame(g, gx + at[0] * (cell + 1), gy + at[1] * (cell + 1), cell, cell, Ui.MUTED);
        }
        String status = null;
        if (shown == null) {
            String asking = Tr.t("lag.asking_map", serverName(server));
            status = waitingForHere() ? Tr.t("lag.side.wait_here") : mapDue != null ? asking : waitText(mapSentAt, asking);
        } else if (shown.rows().isEmpty()) {
            status = Tr.t("lag.no_chunks");
        }
        if (status != null) {
            List<String> lines = Ui.wrap(font, status, w - 16, SMALL);
            int bh = lines.size() * 8 + 8, by = gy + (w - bh) / 2;
            g.fill(gx + 4, by, gx + w - 4, by + bh, Ui.PANEL);
            for (int i = 0; i < lines.size(); i++) {
                Ui.text(g, font, lines.get(i), gx + 8, by + 5 + i * 8, Ui.MUTED, SMALL);
            }
        }
    }

    /** The picked chunk: where, why and whose, its entities and block entities by kind, GO THERE and HOME. */
    @Nullable
    private String drawSide(GuiGraphics g, int x, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int w = mapW(), sx = gx + w + GAP, sw = x + host.pageW() - 8 - sx, sy = gy, sh = w;
        Ui.inset(g, sx, sy, sw, sh);
        int ix = sx + 6, iw = sw - 12, keysY = sy + sh - 17, limit = keysY - 3, ty = sy + 6;
        String tip = null;
        Ui.scissor(g, sx + 1, sy + 1, sx + sw - 1, sy + sh - 1);
        LagSnapshot.Detail d = picked ? detail() : null;
        if (!picked) {
            for (String line : Ui.wrap(font, Tr.t("lag.side.pick"), iw, TINY)) {
                if (ty + 6 > limit) {
                    break;
                }
                Ui.text(g, font, line, ix, ty, Ui.MUTED, TINY);
                ty += 7;
            }
        } else {
            String title = up(Tr.t("lag.side.chunk", px, pz));
            Ui.text(g, font, Component.literal(Ui.fitBold(font, title, iw, TITLE)).withStyle(ChatFormatting.BOLD), ix, ty, Ui.VIOLET, TITLE);
            ty += 9;
            ChunkPos c = new ChunkPos(px, pz);
            Square q0 = square(0, px, pz);
            boolean ticking = d != null ? d.ticking() : q0 != null && q0.ticking;
            String where = Tr.t("lag.side.blocks", c.getMinBlockX(), c.getMaxBlockX(), c.getMinBlockZ(), c.getMaxBlockZ())
                    + (q0 != null || d != null && d.loaded() ? " · " + (ticking ? Tr.t("lag.side.ticking") : Tr.t("lag.side.not_ticking")) : "");
            Ui.text(g, font, Ui.fit(font, where, iw, TINY), ix, ty, Ui.MUTED, TINY);
            ty += 9;
            String status = null;
            if (d != null && !d.loaded() || d == null && shown != null && q0 == null) {
                status = Tr.t("lag.side.not_loaded");
            } else if (d == null) {
                status = waitingForHere() ? Tr.t("lag.side.wait_here") : Tr.t("lag.side.asking", serverName(server));
            }
            if (status != null) {
                for (String line : Ui.wrap(font, status, iw, TINY)) {
                    if (ty + 6 > limit) {
                        break;
                    }
                    Ui.text(g, font, line, ix, ty, Ui.MUTED, TINY);
                    ty += 7;
                }
            } else {
                ty = kv(g, ix, iw, ty, Tr.t("lag.side.loaded_by"), loadedBy(d.why(), d.who()), whyColour(d.why().ordinal()), limit);
                int e = d.entityTotal(), b = d.blockEntityTotal();
                ty = kv(g, ix, iw, ty, Tr.t("lag.side.entities"), num(e), e >= 50 ? ramp(e) : Ui.TEXT, limit);
                ty = kv(g, ix, iw, ty, Tr.t("lag.side.block_entities"), num(b), b >= 50 ? ramp(b) : Ui.TEXT, limit);
                // the entities in at most half the room left (more when the block entities need less), the block entities after
                int entLimit = Math.max(ty + 3 + (limit - ty - 3) / 2, limit - 3 - chipsHeight(iw, d.blockEntities()));
                ty = chips(g, ix, iw, ty + 3, Tr.t("lag.side.entities"), d.entities(), Math.min(limit, entLimit));
                chips(g, ix, iw, ty + 3, Tr.t("lag.side.block_entities"), d.blockEntities(), limit);
            }
        }
        g.disableScissor();
        // GO THERE: a loaded chunk, once this server's name is known; on another server its safe spot is needed (and the
        // server says when BSP-Teleport is missing)
        boolean here = isHere(server);
        boolean can = hereKnown() && d != null && d.loaded() && (here || d.spot() != null);
        String go = Tr.t("lag.side.go");
        int gw = Ui.widthBold(font, up(go), TINY) + 10;
        int kx = ix;
        if (picked) {
            LagSnapshot.Detail fd = d;
            kx += boxKey(g, kx, keysY, 12, go, Ui.TEAL, can, mx, my, hits, () -> go(server, dim, px, pz, fd.spot())) + 3;
            if (in(mx, my, ix, keysY, gw, 12) && (!hereKnown() || d != null && d.loaded())) {
                tip = can ? Tr.t("lag.side.go_tip") : !hereKnown() ? Tr.t("lag.side.wait_here") : Tr.t("lag.side.no_spot");
            }
        }
        boxKey(g, kx, keysY, 12, Tr.t("lag.side.home"), Ui.MUTED, true, mx, my, hits, this::home);
        return tip;
    }

    /** A label and its value on one line, the value on the right; returns the next line's top. */
    private int kv(GuiGraphics g, int ix, int iw, int ty, String label, String value, int colour, int limit) {
        if (ty + 8 > limit) {
            return ty;
        }
        Font font = host.font();
        String v = Ui.fit(font, value, iw * 2 / 3, TINY);
        int vw = Ui.width(font, v, TINY);
        Ui.text(g, font, Ui.fit(font, label, iw - vw - 6, TINY), ix, ty + 1, Ui.MUTED, TINY);
        Ui.text(g, font, v, ix + iw - vw, ty + 1, colour, TINY);
        g.fill(ix, ty + 8, ix + iw, ty + 9, Ui.ROW_LINE);
        return ty + 10;
    }

    /** The chips' texts for some kinds, each fitted to the panel. */
    private List<String> chipTexts(int iw, List<LagSnapshot.TypeCount> kinds) {
        List<String> out = new ArrayList<>();
        for (LagSnapshot.TypeCount k : kinds) {
            out.add(Ui.fit(host.font(), kind(k.type()) + " ×" + k.count(), iw - 6, TINY));
        }
        return out;
    }

    /** Where chips go, left to right and down, from {@code ty}: each one's left and top. */
    private List<int[]> chipPlaces(List<String> texts, int ix, int iw, int ty) {
        List<int[]> out = new ArrayList<>();
        int cxp = ix;
        for (String t : texts) {
            int w = Ui.width(host.font(), t, TINY) + 6;
            if (cxp > ix && cxp + w > ix + iw) {
                cxp = ix;
                ty += 10;
            }
            out.add(new int[]{cxp, ty, w});
            cxp += w + 3;
        }
        return out;
    }

    /** The height a label and all its kinds' chips take. */
    private int chipsHeight(int iw, List<LagSnapshot.TypeCount> kinds) {
        if (kinds.isEmpty()) {
            return 16;
        }
        List<int[]> at = chipPlaces(chipTexts(iw, kinds), 0, iw, 0);
        return 7 + at.get(at.size() - 1)[1] + 10;
    }

    /**
     * A label and the kinds as chips, as many lines as there is room for down to {@code limit}; when not all fit, the
     * last chip says how many more there are. Returns the next top.
     */
    private int chips(GuiGraphics g, int ix, int iw, int ty, String label, List<LagSnapshot.TypeCount> kinds, int limit) {
        if (ty + 6 > limit) {
            return ty;
        }
        Font font = host.font();
        Ui.text(g, font, Component.literal(Ui.fitBold(font, up(label), iw, TINY)).withStyle(ChatFormatting.BOLD), ix, ty, 0xFF6B7180, TINY);
        ty += 7;
        if (kinds.isEmpty()) {
            if (ty + 8 <= limit) {
                Ui.text(g, font, Ui.fit(font, Tr.t("lag.side.none"), iw, TINY), ix, ty + 1, Ui.MUTED, TINY);
            }
            return ty + 9;
        }
        List<String> texts = chipTexts(iw, kinds);
        int shown = texts.size();
        List<int[]> at = chipPlaces(texts, ix, iw, ty);
        while (at.get(at.size() - 1)[1] + 8 > limit && shown > 0) {
            shown--; // one fewer, and a chip for the rest
            List<String> some = new ArrayList<>(texts.subList(0, shown));
            some.add(Ui.fit(font, Tr.t("lag.side.more", texts.size() - shown), iw - 6, TINY));
            at = chipPlaces(some, ix, iw, ty);
        }
        if (at.get(at.size() - 1)[1] + 8 > limit) {
            return ty;
        }
        for (int i = 0; i < at.size(); i++) {
            int[] c = at.get(i);
            boolean more = i >= shown;
            String t = more ? Ui.fit(font, Tr.t("lag.side.more", texts.size() - shown), iw - 6, TINY) : texts.get(i);
            g.fill(c[0], c[1], c[0] + c[2], c[1] + 8, more ? Ui.SLOT : CHIP);
            Ui.frame(g, c[0], c[1], c[2], 8, Ui.DIM);
            Ui.text(g, font, t, c[0] + 3, c[1] + 2, more ? 0xFF6B7180 : Ui.MUTED, TINY);
        }
        return at.get(at.size() - 1)[1] + 10;
    }

    /**
     * What each colour means, with the counts of WHY, on one line or, when they do not all fit, more (the room left above
     * the footer's line holds two at least, each line kept 2 px clear of it); an item cut only when it is wider than the
     * line. Zoomed out, how many chunks a square is ends the first line, its width kept free (the items wrap round it), so
     * it never covers the map.
     */
    private void drawLegend(GuiGraphics g, int x, int y) {
        Font font = host.font();
        int ly = gy + mapW() + 3, lx = x + 8, end = x + host.pageW() - 8, foot = y + host.pageH() - FOOT_H;
        int firstEnd = end;
        if (zoom > 0) {
            String z = Ui.fit(font, Tr.t("lag.zoom", 1 << zoom, 1 << zoom), (end - lx) / 2, TINY);
            int zw = Ui.width(font, z, TINY);
            Ui.text(g, font, z, end - zw, ly, Ui.MUTED, TINY);
            firstEnd = end - zw - 8;
        }
        List<int[]> colours = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (profiler) {
            for (int i = 0; i < 4; i++) {
                colours.add(new int[]{LagView.MS_RAMP[i]});
            }
            labels.add(Tr.t("lag.p.legend.0"));
            labels.add(Tr.t("lag.p.legend.1"));
            labels.add(Tr.t("lag.p.legend.2"));
            labels.add(Tr.t("lag.p.legend.3"));
        } else if (colour == Colour.WHY) {
            for (int i = 0; i < WHY.length; i++) {
                colours.add(new int[]{(WHY[i] & 0x00FFFFFF) | 0xBB000000});
            }
            labels.add(Tr.t("lag.legend.player", num(whyCounts[0])));
            labels.add(Tr.t("lag.legend.ticket", num(whyCounts[1])));
            labels.add(Tr.t("lag.legend.forced", num(whyCounts[2])));
            labels.add(Tr.t("lag.legend.spawn", num(whyCounts[3])));
            labels.add(Tr.t("lag.legend.other", num(whyCounts[4])));
        } else {
            for (int i = 0; i < 4; i++) {
                colours.add(new int[]{RAMP[i]});
            }
            labels.add(Tr.t("lag.legend.e0"));
            labels.add(Tr.t("lag.legend.e1"));
            labels.add(Tr.t("lag.legend.e2"));
            labels.add(colour == Colour.BLOCKS ? Tr.t("lag.legend.b3") : Tr.t("lag.legend.e3"));
        }
        colours.add(new int[]{UNLOADED});
        labels.add(Tr.t("lag.legend.unloaded"));
        int left = lx, top = ly, lineH = LEGEND_LINE;
        for (int i = 0; i < labels.size(); i++) {
            int need = 10 + Ui.width(font, labels.get(i), TINY), lineEnd = ly == top ? firstEnd : end;
            if (lx > left && lx + need > lineEnd) {
                if (ly + lineH + 5 + 2 > foot) {
                    break; // no room for another line 2 px clear of the footer's
                }
                lx = left;
                ly += lineH;
                lineEnd = end;
            }
            g.fill(lx, ly, lx + 8, ly + 5, colours.get(i)[0]);
            if (colours.get(i)[0] == UNLOADED) {
                Ui.frame(g, lx, ly, 8, 5, Ui.DIM);
            }
            String s = Ui.fit(font, labels.get(i), lineEnd - lx - 10, TINY);
            Ui.text(g, font, s, lx + 10, ly, 0xFF6B7180, TINY);
            lx += 10 + Ui.width(font, s, TINY) + 6;
        }
    }

    /**
     * The world's totals and the network's (MAP), or every server's with SHOW ON MAP (LIST); in the PROFILER, WORST
     * ANYWHERE (MAP) or the sample's chunks with SHOW ON MAP (LIST); a note in gold for a while.
     */
    @Nullable
    private String drawFooter(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int fy = y + host.pageH() - FOOT_H, left = x + 8, right = x + host.pageW() - 8;
        g.fill(x + 1, fy, x + host.pageW() - 1, fy + 1, Ui.DIM);
        if (profiler) {
            return drawProfFooter(g, fy, left, right, mx, my, hits);
        }
        int chunks = 0, entities = 0, blocks = 0, servers = 0;
        for (ClientLag.Summaries ss : ClientLag.summaries().values()) {
            servers++;
            for (LagSnapshot.Summary w : ss.worlds()) {
                chunks += w.chunks();
                entities += w.entities();
                blocks += w.blockEntities();
            }
        }
        String rightText;
        int rw;
        if (list) {
            String show = Tr.t("lag.foot.show");
            rw = Ui.widthBold(font, up(show), TINY) + 10;
            Row r = listPick;
            boxKey(g, right - rw, fy + 2, 10, show, Ui.VIOLET, r != null, mx, my, hits, () -> showOnMap(r));
            rightText = "";
        } else {
            rightText = Ui.fit(font, Tr.t("lag.foot.network", num(chunks)), (right - left) / 3, TINY);
            rw = Ui.width(font, rightText, TINY);
            Ui.text(g, font, rightText, right - rw, fy + 5, 0xFF6B7180, TINY);
        }
        String leftText;
        int colourL = 0xFF6B7180;
        if (System.currentTimeMillis() < noteUntil) {
            leftText = note;
            colourL = Ui.GOLD;
        } else if (list) {
            leftText = Tr.t("lag.foot.list", num(chunks), num(entities), num(blocks), servers);
        } else {
            LagSnapshot.Summary s = summary(server, dim);
            int c = 0, e = 0, b = 0;
            if (s != null) {
                c = s.chunks();
                e = s.entities();
                b = s.blockEntities();
            } else if (shown != null) {
                if (footFor != shown) {
                    footFor = shown; // summed once for these rows, not every frame
                    footE = footB = 0;
                    for (LagSnapshot.MapRow r : shown.rows()) {
                        footE += r.entities();
                        footB += r.blockEntities();
                    }
                }
                c = shown.rows().size();
                e = footE;
                b = footB;
            }
            leftText = dim == null ? "" : Tr.t("lag.foot.world", MobsTab.dimName(dim).toLowerCase(Locale.ROOT), num(c), num(e), num(b));
        }
        Ui.text(g, font, Ui.fit(font, leftText, Math.max(0, right - rw - 8 - left), TINY), left, fy + 5, colourL, TINY);
        return null;
    }

    /** Each server's worlds with their chunks and how long ago the server answered; a click shows that world. */
    private void drawMenu(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        record Line(String server, @Nullable String dim, String left, String right, int colour, boolean head) {
        }
        List<Line> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String s : servers()) {
            ClientLag.Summaries ss = ClientLag.summaries().get(s);
            boolean grey = stale(ss);
            long since = now - summaryAskedAt;
            String age = ss.atMs() >= summaryAskedAt ? Tr.t("lag.menu.answered", (now - ss.atMs()) / 1000)
                    : since <= SILENT_MS ? Tr.t("lag.menu.asking") : since <= GO_MS ? Tr.t("lag.menu.waiting") : Tr.t("lag.menu.not_heard", (now - ss.atMs()) / 1000);
            lines.add(new Line(s, null, up(s), up(age), grey ? GREY : 0xFF6B7180, true));
            for (LagSnapshot.Summary w : ss.worlds()) {
                lines.add(new Line(s, w.dimension(), MobsTab.dimName(w.dimension()), Tr.t("lag.menu.chunks", num(w.chunks())), grey ? GREY : Ui.TEXT, false));
            }
        }
        if (lines.isEmpty()) {
            String none = now - summaryAskedAt <= GO_MS ? Tr.t("lag.waiting") : Tr.t("lag.menu.none");
            lines.add(new Line("", null, none, "", Ui.MUTED, false));
        }
        int mxp = menuX, myp = y + KEYS_Y + KEY_H + 2, w = Math.min(190, x + host.pageW() - 8 - mxp);
        int fits = Math.max(1, (y + host.pageH() - FOOT_H - 4 - myp - 4) / LINE);
        menuScroll = Math.max(0, Math.min(menuScroll, Math.max(0, lines.size() - fits)));
        int shownLines = Math.min(fits, lines.size() - menuScroll), h = shownLines * LINE + 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(mxp - 1, myp - 1, mxp + w + 1, myp + h + 1, Ui.VIOLET);
        g.fill(mxp, myp, mxp + w, myp + h, 0xFF0E1218);
        for (int i = 0; i < shownLines; i++) {
            Line l = lines.get(menuScroll + i);
            int ry = myp + 2 + i * LINE;
            boolean current = l.dim() != null && l.dim().equals(dim) && (l.server().equals(server) || isHere(l.server()) && isHere(server));
            boolean hover = l.dim() != null && in(mx, my, mxp, ry, w, LINE);
            if (current) {
                g.fill(mxp + 1, ry, mxp + w - 1, ry + LINE, SEL);
            } else if (hover) {
                g.fill(mxp + 1, ry, mxp + w - 1, ry + LINE, HOVER);
            }
            String right = Ui.fit(font, l.right(), w / 2, TINY);
            int rwid = Ui.width(font, right, TINY);
            if (l.head()) {
                String left = Ui.fitBold(font, l.left(), w - 14 - rwid, TINY);
                Ui.text(g, font, Component.literal(left).withStyle(ChatFormatting.BOLD), mxp + 4, ry + 3, l.colour(), TINY);
                Ui.text(g, font, right, mxp + w - 4 - rwid, ry + 3, l.colour(), TINY);
            } else {
                Ui.text(g, font, Ui.fit(font, l.left(), w - 18 - rwid, TINY), mxp + 8, ry + 3, current ? Ui.VIOLET : l.colour(), TINY);
                Ui.text(g, font, right, mxp + w - 4 - rwid, ry + 3, l.colour() == GREY ? GREY : 0xFF6B7180, TINY);
            }
            if (l.dim() != null) {
                String s = l.server(), d = l.dim();
                hits.add(new Ui.Hit(mxp, ry, w, LINE, () -> select(s, d)));
            }
        }
        if (lines.size() > fits) {
            // more lines than fit: a 1 px bar on the right says where the wheel has the list
            int track = h - 4, bar = Math.max(4, track * fits / lines.size()), at = (track - bar) * menuScroll / Math.max(1, lines.size() - fits);
            g.fill(mxp + w - 2, myp + 2, mxp + w - 1, myp + 2 + track, Ui.DIM);
            g.fill(mxp + w - 2, myp + 2 + at, mxp + w - 1, myp + 2 + at + bar, Ui.VIOLET);
        }
        g.pose().popPose();
        hits.add(new Ui.Hit(x, y, host.pageW(), host.pageH(), () -> menu = false)); // a click anywhere else closes it
    }

    // ------------------------------------------------------------------ the list

    /** Every server's busy chunks, sorted; made again only when the summaries, the sort or this server's name change. */
    private List<Row> rows() {
        if (rowsMade != null && rowsVersion == ClientLag.summariesVersion() && rowsCol == sortCol && rowsDesc == sortDesc && rowsHere.equals(here())) {
            return rowsMade;
        }
        List<Row> out = new ArrayList<>();
        for (String s : servers()) {
            ClientLag.Summaries ss = ClientLag.summaries().get(s);
            for (LagSnapshot.Summary w : ss.worlds()) {
                for (LagSnapshot.ListRow r : w.rows()) {
                    out.add(new Row(s, w.dimension(), r));
                }
            }
        }
        Comparator<Row> by = switch (sortCol) {
            case 0 -> Comparator.comparing((Row r) -> r.server().toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparing((Row r) -> MobsTab.dimName(r.dim()).toLowerCase(Locale.ROOT));
            case 2 -> Comparator.comparingInt((Row r) -> r.r().x()).thenComparingInt(r -> r.r().z());
            case 3 -> Comparator.comparing((Row r) -> loadedBy(r.r().why(), r.r().who()).toLowerCase(Locale.ROOT));
            case 5 -> Comparator.comparingInt((Row r) -> r.r().blockEntities());
            default -> Comparator.comparingInt((Row r) -> r.r().entities());
        };
        out.sort(sortDesc ? by.reversed() : by);
        rowsMade = out;
        rowsVersion = ClientLag.summariesVersion();
        rowsCol = sortCol;
        rowsDesc = sortDesc;
        rowsHere = here();
        return out;
    }

    private void sortBy(int col) {
        if (sortCol == col) {
            sortDesc = !sortDesc;
        } else {
            sortCol = col;
            sortDesc = col >= 4; // the numbers biggest first, the words from A
        }
        listScroll = 0;
    }

    private static boolean same(@Nullable Row a, Row b) {
        return a != null && a.server().equals(b.server()) && a.dim().equals(b.dim()) && a.r().x() == b.r().x() && a.r().z() == b.r().z();
    }

    /** Every server's busiest chunks, sortable by any column; TP on each row, the picked row for SHOW ON MAP. */
    @Nullable
    private String drawList(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int tx = x + 8, ty = y + MAP_Y, tw = host.pageW() - 16, th = y + host.pageH() - FOOT_H - 4 - ty;
        Ui.inset(g, tx, ty, tw, th);
        int inner = tw - 8, tpW = Ui.widthBold(font, up(Tr.t("lag.tp")), TINY) + 10, entW = 26, beW = 30, chunkW = 48, gap = 4;
        int rest = Math.max(30, inner - tpW - entW - beW - chunkW - 5 * gap);
        int serverW = rest * 22 / 100, worldW = rest * 28 / 100, whoW = rest - serverW - worldW;
        int[] widths = {serverW, worldW, chunkW, whoW, entW, beW};
        int[] xs = new int[7];
        xs[0] = tx + 4;
        for (int i = 1; i < 7; i++) {
            xs[i] = xs[i - 1] + widths[i - 1] + gap;
        }
        String[] heads = {Tr.t("lag.col.server"), Tr.t("lag.col.world"), Tr.t("lag.col.chunk"), Tr.t("lag.col.who"), Tr.t("lag.col.ent"), Tr.t("lag.col.be")};
        for (int i = 0; i < heads.length; i++) {
            boolean on = sortCol == i;
            String h = up(heads[i]) + (on ? sortDesc ? " ▾" : " ▴" : "");
            Ui.text(g, font, Component.literal(Ui.fitBold(font, h, widths[i], TINY)).withStyle(ChatFormatting.BOLD), xs[i], ty + 3, on ? Ui.VIOLET : 0xFF6B7180, TINY);
            int col = i;
            hits.add(new Ui.Hit(xs[i], ty + 1, widths[i], 9, () -> sortBy(col)));
        }
        g.fill(tx + 1, ty + 10, tx + tw - 1, ty + 11, Ui.ROW_LINE);
        List<Row> rows = rows();
        int top = ty + 11, fits = Math.max(1, (th - 12) / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - fits)));
        if (rows.isEmpty()) {
            String s = ClientLag.summaries().isEmpty() && System.currentTimeMillis() - summaryAskedAt <= GO_MS ? Tr.t("lag.waiting") : Tr.t("lag.list.none");
            int ly = top + 4;
            for (String line : Ui.wrap(font, s, tw - 12, TINY)) {
                Ui.text(g, font, line, tx + 6, ly, Ui.MUTED, TINY);
                ly += 7;
            }
        }
        String tip = null;
        boolean can = hereKnown();
        for (int i = listScroll; i < Math.min(rows.size(), listScroll + fits); i++) {
            Row r = rows.get(i);
            int ry = top + (i - listScroll) * ROW_H;
            if (same(listPick, r)) {
                g.fill(tx + 1, ry, tx + tw - 1, ry + ROW_H, SEL);
                g.fill(tx + 1, ry, tx + 3, ry + ROW_H, Ui.VIOLET);
            } else if (in(mx, my, tx, ry, tw - tpW - 6, ROW_H)) {
                g.fill(tx + 1, ry, tx + tw - 1, ry + ROW_H, HOVER);
            }
            ClientLag.Summaries from = ClientLag.summaries().get(r.server());
            boolean stale = from != null && stale(from);
            int muted = stale ? GREY : Ui.MUTED, text = stale ? GREY : Ui.TEXT;
            int e = r.r().entities();
            Ui.text(g, font, Ui.fit(font, r.server(), widths[0], TINY), xs[0], ry + 3, muted, TINY);
            Ui.text(g, font, Ui.fit(font, MobsTab.dimName(r.dim()).toLowerCase(Locale.ROOT), widths[1], TINY), xs[1], ry + 3, muted, TINY);
            Ui.text(g, font, Ui.fit(font, r.r().x() + ", " + r.r().z(), widths[2], TINY), xs[2], ry + 3, muted, TINY);
            Ui.text(g, font, Ui.fit(font, loadedBy(r.r().why(), r.r().who()), widths[3], TINY), xs[3], ry + 3, text, TINY);
            String es = Ui.fitBold(font, num(e), widths[4], TINY);
            Ui.text(g, font, Component.literal(es).withStyle(ChatFormatting.BOLD), xs[4], ry + 3, stale ? GREY : e >= 100 ? Ui.BAD : e >= 50 ? AMBER : Ui.TEXT, TINY);
            Ui.text(g, font, Ui.fit(font, num(r.r().blockEntities()), widths[5], TINY), xs[5], ry + 3, text, TINY);
            g.fill(tx + 1, ry + ROW_H - 1, tx + tw - 1, ry + ROW_H, Ui.ROW_LINE);
            int kx = tx + tw - 4 - tpW;
            hits.add(new Ui.Hit(tx, ry, kx - 2 - tx, ROW_H, () -> listPick = r)); // up to the TP key, never over it
            boxKey(g, kx, ry + 1, 9, Tr.t("lag.tp"), Ui.TEAL, can, mx, my, hits, () -> goChunk(r.server(), r.dim(), r.r().x(), r.r().z()));
            if (in(mx, my, kx, ry + 1, tpW, 9)) {
                tip = can ? Tr.t("lag.side.go_tip") : Tr.t("lag.side.wait_here");
            }
        }
        return tip;
    }

    // ------------------------------------------------------------------ the profiler (SU-152)

    private static String agoText(long ms) {
        long[] a = LagView.ago(ms);
        return switch ((int) a[1]) {
            case 0 -> Tr.t("lag.p.ago.s", a[0]);
            case 1 -> Tr.t("lag.p.ago.min", a[0]);
            case 2 -> Tr.t("lag.p.ago.h", a[0]);
            default -> Tr.t("lag.p.ago.d", a[0]);
        };
    }

    /** How long a finished sample ran: its length, or how far it got when stopped. */
    private static String lengthText(LagResult r) {
        return r.stoppedBy().isEmpty() ? Tr.t("lag.p.length", r.lengthS()) : Tr.t("lag.p.length_stopped", Math.round(r.ticks() / 20.0), r.lengthS());
    }

    /** The tick's colour: plain with room to spare, amber from half the tick, red near its 50 ms. */
    private static int tickColour(double ms) {
        return ms >= 45 ? Ui.BAD : ms >= 25 ? AMBER : Ui.TEXT;
    }

    /** Milliseconds as text in the ramp's colours, the two darkest lightened to read on the page. */
    private static int msText(double ms) {
        return ms >= 5 ? Ui.BAD : ms >= 2 ? AMBER : ms >= 0.5 ? 0xFF8FB86A : Ui.MUTED;
    }

    /** The PROFILE answer is not in: waiting for this server's name, asking, waiting, or the server did not answer. */
    private String askingText() {
        if (profKey().isEmpty()) {
            return Tr.t("lag.side.wait_here");
        }
        return profDue != null ? Tr.t("lag.p.asking", serverName(server)) : waitText(profSentAt, Tr.t("lag.p.asking", serverName(server)));
    }

    /** One piece of the line under the keys, fitted to what is left; where the next starts (past {@code end} when there is no room). */
    private int piece(GuiGraphics g, String s, int x, int end, int y, int colour, boolean bold) {
        Font font = host.font();
        if (end - x < 12) {
            return end + 1;
        }
        String f = bold ? Ui.fitBold(font, s, end - x, TINY) : Ui.fit(font, s, end - x, TINY);
        if (bold) {
            Ui.text(g, font, Component.literal(f).withStyle(ChatFormatting.BOLD), x, y, colour, TINY);
            return x + Ui.widthBold(font, f, TINY) + 3;
        }
        Ui.text(g, font, f, x, y, colour, TINY);
        return x + Ui.width(font, f, TINY) + 3;
    }

    /** Wrapped lines from {@code ty} down to {@code limit}; the next top. */
    private int para(GuiGraphics g, int ix, int iw, int ty, int limit, String s, int colour) {
        for (String line : Ui.wrap(host.font(), s, iw, TINY)) {
            if (ty + 6 > limit) {
                break;
            }
            Ui.text(g, host.font(), line, ix, ty, colour, TINY);
            ty += 7;
        }
        return ty;
    }

    /**
     * The line under the keys (R4, R5): while a sample runs, its server, a bar, the time left and who started it; else the
     * last sample's server, length, when and who (D759), its tick and the part not in any chunk (D760).
     */
    private void drawProfLine(GuiGraphics g, int x, int y) {
        Font font = host.font();
        int ly = y + PROF_LINE_Y + 1, lx = x + 8, end = x + host.pageW() - 8;
        ClientLag.Profile cp = profileAnswer();
        LagResult run = cp == null ? null : cp.profile().running(), last = cp == null ? null : cp.profile().last();
        long now = System.currentTimeMillis();
        if (run != null) {
            long since = now - cp.atMs();
            String a = Ui.fitBold(font, Tr.t("lag.p.sampling", serverName(server)), (end - lx) / 2, TINY);
            Ui.text(g, font, Component.literal(a).withStyle(ChatFormatting.BOLD), lx, ly, Ui.VIOLET, TINY);
            int cxp = lx + Ui.widthBold(font, a, TINY) + 6;
            int barW = Math.min(120, (end - cxp) / 3);
            if (barW >= 16) {
                g.fill(cxp, ly, cxp + barW, ly + 5, 0xFF1E2430);
                g.fill(cxp, ly, cxp + (int) Math.round(barW * LagView.progress(run.ticksAsked(), run.ticks(), since)), ly + 5, Ui.VIOLET);
                cxp += barW + 6;
            }
            cxp = piece(g, Tr.t("lag.p.left", LagView.secondsLeft(run.ticksAsked(), run.ticks(), since)), cxp, end, ly, Ui.TEXT, false);
            piece(g, Tr.t("lag.p.started_by", run.by()), cxp, end, ly, Ui.MUTED, false);
            return;
        }
        if (last != null) {
            // the tick and the part not in any chunk are measured first; who, when and how long get what is left
            String tickL = Tr.t("lag.p.tick"), tickV = LagView.ms(last.tickMs()), of = Tr.t("lag.p.of_tick"), outV = LagView.ms(last.outsideMs());
            String[] out = Tr.t("lag.p.outside", "\u0001").split("\u0001", -1);
            String outA = out[0].trim(), outB = out.length > 1 ? out[1].trim() : "";
            int tail = Ui.width(font, tickL, TINY) + 3 + Ui.widthBold(font, tickV, TINY) + 3 + Ui.width(font, of, TINY) + 3 + Ui.width(font, "·", TINY) + 3
                    + (outA.isEmpty() ? 0 : Ui.width(font, outA, TINY) + 3) + Ui.widthBold(font, outV, TINY) + 3 + Ui.width(font, outB, TINY);
            int firstEnd = end - tail - 3;
            int cxp = firstEnd - lx < 12 ? lx // no room for who and when at all: the figures alone
                    : piece(g, serverName(server) + " · " + lengthText(last) + " · " + agoText(now - last.endsAtMs()) + " · " + last.by() + " ·", lx, firstEnd, ly, Ui.MUTED, false);
            cxp = piece(g, tickL, cxp, end, ly, Ui.TEXT, false);
            cxp = piece(g, tickV, cxp, end, ly, tickColour(last.tickMs()), true);
            cxp = piece(g, of, cxp, end, ly, Ui.TEXT, false);
            cxp = piece(g, "·", cxp, end, ly, Ui.MUTED, false);
            if (!outA.isEmpty()) {
                cxp = piece(g, outA, cxp, end, ly, Ui.MUTED, false);
            }
            cxp = piece(g, outV, cxp, end, ly, Ui.TEXT, true);
            piece(g, outB, cxp, end, ly, Ui.MUTED, false);
            return;
        }
        String s = cp == null ? askingText() : Tr.t("lag.p.none_line", serverName(server));
        Ui.text(g, font, Ui.fit(font, s, end - lx, TINY), lx, ly, Ui.MUTED, TINY);
    }

    /** A kind's name in the side panel: its id without the namespace, or what the two kinds without a type are. */
    private static String entryName(LagResult.Entry e) {
        return switch (e.kind()) {
            case SCHEDULED -> Tr.t("lag.p.k.scheduled");
            case CHUNK -> Tr.t("lag.p.k.chunk");
            default -> {
                int at = e.label().indexOf(':');
                yield at < 0 ? e.label() : e.label().substring(at + 1);
            }
        };
    }

    /** What a kind is, after its name in grey: the mod of a block entity, an entity, or what the typeless kinds hold. */
    private static String entryWhat(LagResult.Entry e) {
        int at = e.label().indexOf(':');
        String ns = at < 0 ? "minecraft" : e.label().substring(0, at);
        return switch (e.kind()) {
            case BLOCK_ENTITY -> ns;
            case ENTITY -> ns.equals("minecraft") ? Tr.t("lag.p.k.entity") : Tr.t("lag.p.k.mod_entity", ns);
            case SCHEDULED -> Tr.t("lag.p.k.scheduled_what");
            case CHUNK -> Tr.t("lag.p.k.chunk_what");
        };
    }

    /**
     * The side panel in the PROFILER (R4, R5): the picked chunk's time, share and why, its kinds ranked with TP beside the
     * slowest of each (D750); OUTLINE TOP 10 (D751) and GO THERE under them. Before a pick or a sample, what to do.
     */
    @Nullable
    private String drawProfSide(GuiGraphics g, int x, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int w = mapW(), sx = gx + w + GAP, sw = x + host.pageW() - 8 - sx, sy = gy, sh = w;
        Ui.inset(g, sx, sy, sw, sh);
        int ix = sx + 6, iw = sw - 12, keysY = sy + sh - 17, limit = keysY - 3, ty = sy + 6;
        String tip = null;
        ClientLag.Profile cp = profileAnswer();
        LagResult run = cp == null ? null : cp.profile().running(), last = cp == null ? null : cp.profile().last();
        long now = System.currentTimeMillis();
        boolean here = isHere(server);
        Ui.scissor(g, sx + 1, sy + 1, sx + sw - 1, sy + sh - 1);
        if (cp == null) {
            para(g, ix, iw, ty, limit, askingText(), Ui.MUTED);
        } else if (last == null) {
            para(g, ix, iw, ty, limit, run != null ? Tr.t("lag.p.side.first", serverName(server)) : Tr.t("lag.p.side.none", serverName(server)), Ui.MUTED);
        } else if (!picked) {
            if (run != null) {
                String head = Ui.fitBold(font, up(Tr.t("lag.p.side.last", agoText(now - last.endsAtMs()), last.by())), iw, TINY);
                Ui.text(g, font, Component.literal(head).withStyle(ChatFormatting.BOLD), ix, ty, 0xFF6B7180, TINY);
                para(g, ix, iw, ty + 9, limit, Tr.t("lag.p.side.keeps"), Ui.MUTED);
            } else {
                para(g, ix, iw, ty, limit, Tr.t("lag.p.side.pick", serverName(server)), Ui.MUTED);
            }
        } else {
            LagResult.Chunk c = timed(px, pz);
            String title = up(c == null ? Tr.t("lag.side.chunk", px, pz) : Tr.t("lag.p.side.chunk", px, pz, LagView.ms(c.ms())));
            Ui.text(g, font, Component.literal(Ui.fitBold(font, title, iw, TITLE)).withStyle(ChatFormatting.BOLD), ix, ty, Ui.VIOLET, TITLE);
            ty += 9;
            if (c == null) {
                para(g, ix, iw, ty, limit, Tr.t("lag.p.side.not_timed", LagResult.TOP_CHUNKS), Ui.MUTED);
            } else {
                String why = Tr.t("lag.p.side.share", LagView.sharePercent(c.ms())) + " · " + loadedBy(c.why(), c.who());
                Ui.text(g, font, Ui.fit(font, why, iw, TINY), ix, ty, Ui.MUTED, TINY);
                ty += 9;
                tip = entries(g, c, ix, iw, ty, limit, mx, my, hits);
            }
        }
        g.disableScissor();
        // OUTLINE TOP 10 (this server's sample only, once its name is known: the boxes are drawn in the viewer's own worlds) and GO THERE
        int kx = ix;
        if (last != null) {
            boolean can = hereKnown() && here && !last.blocks().isEmpty(), on = LagOutline.showing(profKey(), last);
            String go = up(Tr.t("lag.side.go"));
            int goW = picked ? Ui.widthBold(font, go, TINY) + 13 : 0;
            String ol = up(Tr.t("lag.p.outline"));
            if (Ui.widthBold(font, ol, TINY) + 10 + goW > iw) {
                ol = up(Tr.t("lag.p.outline_short"));
            }
            ol = Ui.fitBold(font, ol, Math.max(10, iw - goW - 10), TINY);
            int ow = Ui.widthBold(font, ol, TINY) + 10;
            boolean hot = can && in(mx, my, kx, keysY, ow, 12);
            g.fill(kx, keysY, kx + ow, keysY + 12, on ? 0xFF3A1714 : hot ? 0xFF1E2430 : Ui.SLOT);
            Ui.frame(g, kx, keysY, ow, 12, can ? Ui.BAD : Ui.DIM);
            Ui.text(g, font, Component.literal(ol).withStyle(ChatFormatting.BOLD), kx + 5, keysY + 4, can ? Ui.BAD : OFF, TINY);
            if (can) {
                String from = profKey();
                hits.add(new Ui.Hit(kx, keysY, ow, 12, () -> outline(from, last)));
            }
            if (in(mx, my, kx, keysY, ow, 12)) {
                tip = !hereKnown() ? Tr.t("lag.side.wait_here") : !here ? Tr.t("lag.p.outline_here_only") : last.blocks().isEmpty() ? Tr.t("lag.p.outline_none")
                        : on ? Tr.t("lag.p.outline_on", LagView.clock(LagOutline.leftMs()))
                        : LagView.blocksIn(last.blocks(), viewerDim()).isEmpty() ? Tr.t("lag.p.outline_none_here") : Tr.t("lag.p.outline_tip");
            }
            kx += ow + 3;
        }
        if (picked) {
            LagSnapshot.Detail d = detail();
            boolean can = hereKnown() && d != null && d.loaded() && (here || d.spot() != null);
            String go = Tr.t("lag.side.go");
            int gw = Ui.widthBold(font, up(go), TINY) + 10;
            if (kx + gw <= sx + sw - 4) {
                LagSnapshot.Detail fd = d;
                boxKey(g, kx, keysY, 12, go, Ui.TEAL, can, mx, my, hits, () -> go(server, dim, px, pz, fd.spot()));
                if (in(mx, my, kx, keysY, gw, 12) && (!hereKnown() || d != null && d.loaded())) {
                    tip = can ? Tr.t("lag.side.go_tip") : !hereKnown() ? Tr.t("lag.side.wait_here") : Tr.t("lag.side.no_spot");
                }
            }
        }
        return tip;
    }

    /** OUTLINE TOP 10, or off again when it is up for this sample (D751); refused with a note when no block is in the viewer's world. */
    private void outline(String from, LagResult r) {
        if (LagOutline.showing(from, r)) {
            LagOutline.clear();
            note(Tr.t("lag.p.outline_cleared"));
        } else if (LagOutline.show(from, r)) {
            note(Tr.t("lag.p.outline_shown", LagView.blocksIn(r.blocks(), viewerDim()).size(), r.blocks().size()));
        } else {
            note(Tr.t("lag.p.outline_none_here"));
        }
    }

    /**
     * The picked chunk's kinds, slowest first: name and how many ticked during the sample, what it is, its ms, TP beside
     * the slowest one. When not all fit, the last line says how many more there are.
     */
    @Nullable
    private String entries(GuiGraphics g, LagResult.Chunk c, int ix, int iw, int ty, int limit, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        String tp = up(Tr.t("lag.tp"));
        int tpW = Ui.widthBold(font, tp, TINY) + 10, msW = Ui.widthBold(font, "00.00", TINY) + 2;
        int msX = ix + iw - tpW - 3 - msW, nameW = msX - ix - 4;
        String tip = null;
        if (ty + 8 > limit) {
            return null;
        }
        String head = Ui.fitBold(font, up(Tr.t("lag.p.side.kinds")), nameW, TINY);
        Ui.text(g, font, Component.literal(head).withStyle(ChatFormatting.BOLD), ix, ty, 0xFF6B7180, TINY);
        String msHead = Ui.fitBold(font, up(Tr.t("lag.p.col.ms_short")), msW, TINY);
        Ui.text(g, font, Component.literal(msHead).withStyle(ChatFormatting.BOLD), msX, ty, 0xFF6B7180, TINY);
        ty += 7;
        g.fill(ix, ty, ix + iw, ty + 1, Ui.ROW_LINE);
        ty += 1;
        boolean other = !isHere(server), canTp = hereKnown();
        List<LagResult.Entry> list = c.entries();
        int fits = ty + ROW_H - 1 > limit ? 0 : (limit - ty - (ROW_H - 1)) / (ROW_H - 1) + 1;
        for (int i = 0; i < list.size(); i++) {
            if (i >= fits) {
                break;
            }
            if (i == fits - 1 && list.size() > fits) {
                Ui.text(g, font, Ui.fit(font, Tr.t("lag.side.more", list.size() - i), iw, TINY), ix, ty + 2, 0xFF6B7180, TINY);
                break; // the last line that fits says how many more there are
            }
            LagResult.Entry e = list.get(i);
            boolean counted = e.count() > 1 && e.kind() != LagResult.Kind.CHUNK;
            // how many ticked during the sample, not how many are there: said in full when it fits, else in the hover
            String name = counted ? Tr.t("lag.p.k.ticked", entryName(e), e.count()) : entryName(e);
            if (counted && Ui.width(font, name, TINY) > nameW) {
                name = entryName(e) + " ×" + e.count();
                if (in(mx, my, ix, ty, nameW, ROW_H - 1)) {
                    tip = Tr.t("lag.p.k.ticked_tip", e.count());
                }
            }
            String nf = Ui.fit(font, name, nameW, TINY);
            Ui.text(g, font, nf, ix, ty + 2, Ui.TEXT, TINY);
            int after = ix + Ui.width(font, nf, TINY) + 3;
            if (nameW - (after - ix) >= 14) {
                Ui.text(g, font, Ui.fit(font, entryWhat(e), nameW - (after - ix), TINY), after, ty + 2, 0xFF6B7180, TINY);
            }
            String ms = Ui.fitBold(font, LagView.ms(e.ms()), msW, TINY);
            Ui.text(g, font, Component.literal(ms).withStyle(ChatFormatting.BOLD), msX + msW - Ui.widthBold(font, ms, TINY), ty + 2, msText(e.ms()), TINY);
            if (e.worst() != null) {
                BlockPos at = e.worst();
                int kx = ix + iw - tpW;
                boxKey(g, kx, ty + 1, 8, Tr.t("lag.tp"), Ui.TEAL, canTp, mx, my, hits, () -> goBeside(c.dimension(), c.x(), c.z(), at));
                if (in(mx, my, kx, ty + 1, tpW, 8)) {
                    tip = !canTp ? Tr.t("lag.side.wait_here") : other ? Tr.t("lag.p.tp_other_tip") : Tr.t("lag.p.tp_tip", at.getX(), at.getY(), at.getZ());
                }
            }
            g.fill(ix, ty + ROW_H - 2, ix + iw, ty + ROW_H - 1, Ui.ROW_LINE);
            ty += ROW_H - 1;
        }
        return tip;
    }

    /** The footer in the PROFILER: WORST ANYWHERE (MAP, R5), or the sample's chunks and SHOW ON MAP (LIST); a note first. */
    @Nullable
    private String drawProfFooter(GuiGraphics g, int fy, int left, int right, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        LagResult last = last();
        boolean noted = System.currentTimeMillis() < noteUntil;
        if (list) {
            String show = Tr.t("lag.foot.show");
            int rw = Ui.widthBold(font, up(show), TINY) + 10;
            LagResult.Chunk pick = profPick;
            String s = server;
            boxKey(g, right - rw, fy + 2, 10, show, Ui.VIOLET, pick != null, mx, my, hits, () -> showChunk(s, pick.dimension(), pick.x(), pick.z()));
            String text = noted ? note : last == null ? "" : Tr.t("lag.p.foot.list", num(last.chunks().size()), serverName(server));
            Ui.text(g, font, Ui.fit(font, text, Math.max(0, right - rw - 8 - left), TINY), left, fy + 5, noted ? Ui.GOLD : 0xFF6B7180, TINY);
            return null;
        }
        if (noted) {
            Ui.text(g, font, Ui.fit(font, note, right - left, TINY), left, fy + 5, Ui.GOLD, TINY);
            return null;
        }
        if (last == null || last.chunks().isEmpty()) {
            return null;
        }
        String head = Ui.fitBold(font, up(Tr.t("lag.p.worst")), (right - left) / 3, TINY);
        Ui.text(g, font, Component.literal(head).withStyle(ChatFormatting.BOLD), left, fy + 5, 0xFF6B7180, TINY);
        int cxp = left + Ui.widthBold(font, head, TINY) + 5;
        String tip = null;
        for (LagResult.Chunk c : LagView.worst(last, WORST_CHIPS)) {
            String where = (c.dimension().equals(dim) ? "" : MobsTab.dimName(c.dimension()).toLowerCase(Locale.ROOT) + " ") + c.x() + ", " + c.z();
            String s = Ui.fit(font, Tr.t("lag.p.chip", where, LagView.ms(c.ms())), right - left, TINY);
            int w = Ui.width(font, s, TINY) + 6;
            if (cxp + w > right) {
                break;
            }
            boolean hot = in(mx, my, cxp, fy + 3, w, 9);
            g.fill(cxp, fy + 3, cxp + w, fy + 12, hot ? 0xFF1E2430 : CHIP);
            Ui.frame(g, cxp, fy + 3, w, 9, hot ? Ui.VIOLET : Ui.DIM);
            Ui.text(g, font, s, cxp + 3, fy + 5, msText(c.ms()), TINY);
            String d = c.dimension(), sv = server;
            int x = c.x(), z = c.z();
            hits.add(new Ui.Hit(cxp, fy + 3, w, 9, () -> showChunk(sv, d, x, z)));
            if (hot) {
                tip = Tr.t("lag.p.worst_tip", MobsTab.dimName(d), x, z);
            }
            cxp += w + 3;
        }
        return tip;
    }

    /** The sample's chunks, sorted; made again only when the sample shown (its server's last) or the sort changes. */
    private List<LagResult.Chunk> profRows() {
        LagResult last = last();
        if (profRowsMade != null && profRowsFor == last && profRowsCol == profSortCol && profRowsDesc == profSortDesc) {
            return profRowsMade;
        }
        List<LagResult.Chunk> out = last == null ? new ArrayList<>() : new ArrayList<>(last.chunks());
        Comparator<LagResult.Chunk> by = switch (profSortCol) {
            case 0 -> Comparator.comparing((LagResult.Chunk c) -> MobsTab.dimName(c.dimension()).toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingInt(LagResult.Chunk::x).thenComparingInt(LagResult.Chunk::z);
            case 2 -> Comparator.comparing((LagResult.Chunk c) -> loadedBy(c.why(), c.who()).toLowerCase(Locale.ROOT));
            default -> Comparator.comparingDouble(LagResult.Chunk::ms);
        };
        out.sort(profSortDesc ? by.reversed() : by);
        profRowsMade = out;
        profRowsFor = last;
        profRowsCol = profSortCol;
        profRowsDesc = profSortDesc;
        return out;
    }

    private void profSortBy(int col) {
        if (profSortCol == col) {
            profSortDesc = !profSortDesc;
        } else {
            profSortCol = col;
            profSortDesc = col >= 3; // the times slowest first, the words from A
        }
        listScroll = 0;
    }

    private static boolean same(@Nullable LagResult.Chunk a, LagResult.Chunk b) {
        return a != null && a.dimension().equals(b.dimension()) && a.x() == b.x() && a.z() == b.z();
    }

    /** LIST in the PROFILER: the sample's chunks (world, chunk, loaded by, ms per tick, share), sortable, with TP. */
    @Nullable
    private String drawProfList(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int tx = x + 8, ty = y + PROF_MAP_Y, tw = host.pageW() - 16, th = y + host.pageH() - FOOT_H - 4 - ty;
        Ui.inset(g, tx, ty, tw, th);
        int inner = tw - 8, tpW = Ui.widthBold(font, up(Tr.t("lag.tp")), TINY) + 10, msW = 36, shareW = 30, chunkW = 48, gap = 4;
        int rest = Math.max(30, inner - tpW - msW - shareW - chunkW - 5 * gap);
        int worldW = rest * 40 / 100, whoW = rest - worldW;
        int[] widths = {worldW, chunkW, whoW, msW, shareW};
        int[] xs = new int[6];
        xs[0] = tx + 4;
        for (int i = 1; i < 6; i++) {
            xs[i] = xs[i - 1] + widths[i - 1] + gap;
        }
        String[] heads = {Tr.t("lag.col.world"), Tr.t("lag.col.chunk"), Tr.t("lag.col.who"), Tr.t("lag.p.col.ms"), Tr.t("lag.p.col.share")};
        for (int i = 0; i < heads.length; i++) {
            boolean on = profSortCol == i;
            String h = up(heads[i]) + (on ? profSortDesc ? " ▾" : " ▴" : "");
            Ui.text(g, font, Component.literal(Ui.fitBold(font, h, widths[i], TINY)).withStyle(ChatFormatting.BOLD), xs[i], ty + 3, on ? Ui.VIOLET : 0xFF6B7180, TINY);
            int col = i;
            hits.add(new Ui.Hit(xs[i], ty + 1, widths[i], 9, () -> profSortBy(col)));
        }
        g.fill(tx + 1, ty + 10, tx + tw - 1, ty + 11, Ui.ROW_LINE);
        List<LagResult.Chunk> rows = profRows();
        int top = ty + 11, fits = Math.max(1, (th - 12) / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, rows.size() - fits)));
        if (rows.isEmpty()) {
            ClientLag.Profile cp = profileAnswer();
            String s = cp == null ? askingText() : Tr.t("lag.p.list.none", serverName(server));
            int ly = top + 4;
            for (String line : Ui.wrap(font, s, tw - 12, TINY)) {
                Ui.text(g, font, line, tx + 6, ly, Ui.MUTED, TINY);
                ly += 7;
            }
        }
        String tip = null;
        boolean can = hereKnown();
        for (int i = listScroll; i < Math.min(rows.size(), listScroll + fits); i++) {
            LagResult.Chunk c = rows.get(i);
            int ry = top + (i - listScroll) * ROW_H;
            if (same(profPick, c)) {
                g.fill(tx + 1, ry, tx + tw - 1, ry + ROW_H, SEL);
                g.fill(tx + 1, ry, tx + 3, ry + ROW_H, Ui.VIOLET);
            } else if (in(mx, my, tx, ry, tw - tpW - 6, ROW_H)) {
                g.fill(tx + 1, ry, tx + tw - 1, ry + ROW_H, HOVER);
            }
            Ui.text(g, font, Ui.fit(font, MobsTab.dimName(c.dimension()).toLowerCase(Locale.ROOT), widths[0], TINY), xs[0], ry + 3, Ui.MUTED, TINY);
            Ui.text(g, font, Ui.fit(font, c.x() + ", " + c.z(), widths[1], TINY), xs[1], ry + 3, Ui.MUTED, TINY);
            Ui.text(g, font, Ui.fit(font, loadedBy(c.why(), c.who()), widths[2], TINY), xs[2], ry + 3, Ui.TEXT, TINY);
            String ms = Ui.fitBold(font, LagView.ms(c.ms()), widths[3], TINY);
            Ui.text(g, font, Component.literal(ms).withStyle(ChatFormatting.BOLD), xs[3], ry + 3, msText(c.ms()), TINY);
            Ui.text(g, font, Ui.fit(font, Tr.t("lag.p.share", LagView.sharePercent(c.ms())), widths[4], TINY), xs[4], ry + 3, Ui.TEXT, TINY);
            g.fill(tx + 1, ry + ROW_H - 1, tx + tw - 1, ry + ROW_H, Ui.ROW_LINE);
            hits.add(new Ui.Hit(tx, ry, xs[5] - tx - 2, ROW_H, () -> profPick = c));
            int kx = tx + tw - 4 - tpW;
            String s = server;
            boxKey(g, kx, ry + 1, 9, Tr.t("lag.tp"), Ui.TEAL, can, mx, my, hits, () -> goChunk(s, c.dimension(), c.x(), c.z()));
            if (in(mx, my, kx, ry + 1, tpW, 9)) {
                tip = can ? Tr.t("lag.side.go_tip") : Tr.t("lag.side.wait_here");
            }
        }
        return tip;
    }
}
