package com.mrgregles.bsp_staff_panel.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nayuki.qrcodegen.QrCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The panel's web server (the JDK's own, no framework). Every request, pages and files included, must carry a valid
 * Cloudflare Access token ({@link Gate}); every API call but the login and setup ones needs the panel's own login (a
 * cookie, {@link Auth#check}). Every POST must come from the panel's own pages: a JSON body, the {@code X-BSP} header (a
 * page elsewhere cannot send it without asking first) and an {@code Origin} equal to {@code publicUrl}. Pages run under a
 * strict Content Security Policy: their own scripts and styles only, nothing from other sites.
 *
 * <p>Setup and enrolment tokens travel in the page address's {@code #} part and in POST bodies, never in an address the
 * server or Cloudflare would log.
 */
public final class PanelServer {
    /** The login cookie's name over plain http (the demo, tests); over https {@link #SECURE_COOKIE}. */
    public static final String COOKIE = "bsp_panel";
    /** {@code __Host-}: only this host may set it, so another *.buildsecureprotect.com page cannot push a cookie that locks staff out (audit). */
    public static final String SECURE_COOKIE = "__Host-bsp_panel";

    /** The login cookie's name: {@link #SECURE_COOKIE} over https. */
    public static String cookieName(boolean https) {
        return https ? SECURE_COOKIE : COOKIE;
    }
    public static final int MAX_BODY = 16 * 1024, MAX_LIVE = 50;
    public static final long LIVE_PING_MS = 20_000L;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self';"
            + " frame-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"; // the map's frame (SU-47) from this panel only

    /** The first lock: who Cloudflare says this request is from, or an exception. */
    public interface Gate {
        AccessJwt.Identity check(String header) throws AccessJwt.Invalid;
    }

    /**
     * One open page's live feed. Its own writer thread with a short queue (audit): a page that stops reading fills its queue
     * and is dropped, and never holds up the others.
     */
    private static final class Live {
        final OutputStream out;
        final String session, email;
        final Account account;
        final java.util.concurrent.ThreadPoolExecutor writer = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(8), r -> daemon(r, "panel-live-writer"));
        volatile boolean closed;

        Live(OutputStream out, String session, String email, Account account) {
            this.out = out;
            this.session = session;
            this.email = email;
            this.account = account;
        }
    }

    public static final int MAX_LIVE_PER_ACCOUNT = 4;
    public static final long LIVE_CHECK_MS = 10_000L;
    private volatile long lastCheck;

    private final HttpServer http;
    private final ExecutorService pool = Executors.newFixedThreadPool(16, r -> daemon(r, "panel-http"));
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "panel-live"));
    private final Gate gate;
    private final Auth auth;
    private final PanelStore store;
    private final Feed feed;
    private volatile com.mrgregles.bsp_staff_panel.act.Actions actions;
    private volatile com.mrgregles.bsp_staff_panel.chat.Chat chat;
    private volatile com.mrgregles.bsp_staff_panel.talk.StaffTalk talk;
    private volatile com.mrgregles.bsp_staff_panel.notes.PlayerNotes notes;
    private final Heads heads;
    private final String publicUrl;
    private final boolean https;
    private final LongSupplier clock;
    private final List<Live> live = new CopyOnWriteArrayList<>();
    private final List<Live> closing = new CopyOnWriteArrayList<>();
    private volatile boolean dirty = true;
    private volatile long lastLive;
    /** The live view goes to the pages at most this often (D251: positions every 5 s). */
    public static final long LIVE_EVERY_MS = 5_000L;
    private volatile long lastPing;

    public PanelServer(String host, int port, String publicUrl, Gate gate, Auth auth, PanelStore store, Feed feed, LongSupplier clock) throws IOException {
        this(host, port, publicUrl, gate, auth, store, feed, clock, Heads.fromMojang());
    }

    public PanelServer(String host, int port, String publicUrl, Gate gate, Auth auth, PanelStore store, Feed feed, LongSupplier clock, Heads heads) throws IOException {
        this.heads = heads;
        this.gate = gate;
        this.auth = auth;
        this.store = store;
        this.feed = feed;
        this.publicUrl = publicUrl;
        this.https = publicUrl.startsWith("https://");
        this.clock = clock;
        http = HttpServer.create(new InetSocketAddress(host, port), 64);
        http.createContext("/", this::handle);
        http.setExecutor(pool);
        feed.onChange(() -> dirty = true);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** What sends actions to the game servers (SU-35); without it the panel only shows. */
    public PanelServer withActions(com.mrgregles.bsp_staff_panel.act.Actions a) {
        this.actions = a;
        return this;
    }

    /** The network's chat (SU-36): its lines go to every open page the moment they arrive. */
    public PanelServer withChat(com.mrgregles.bsp_staff_panel.chat.Chat c) {
        this.chat = c;
        c.onLine(chatOut::add); // sent in one event every quarter second, so a burst cannot fill a page's queue (audit)
        return this;
    }

    /** The Staff tab (SU-37): room lines to every open page, a nudge to the pages whose messages changed. */
    public PanelServer withTalk(com.mrgregles.bsp_staff_panel.talk.StaffTalk t) {
        this.talk = t;
        t.onRoom(line -> {
            String msg = "event: room\ndata: " + GSON.toJson(line) + "\n\n";
            for (Live l : live) {
                write(l, msg);
            }
        });
        t.onMessages(ids -> {
            for (Live l : live) {
                if (ids == null || ids.contains(l.account.id)) {
                    write(l, "event: messages\ndata: {}\n\n");
                }
            }
        });
        return this;
    }

    // ------------------------------------------------------------------ the map (SU-45; D259, D381, D382, D397)

    /**
     * The looser rules BlueMap's page needs (D382), on {@code /map/} only: its translations are compiled at run time
     * ({@code 'unsafe-eval'}) and it sets inline styles; it may be framed by the panel's own page.
     */
    public static final String MAP_CSP = "default-src 'none'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:;"
            + " connect-src 'self'; font-src 'self' data:; worker-src 'self' blob:; base-uri 'none'; form-action 'none'; frame-ancestors 'self'";
    /** A login seen on a map request is believed for this long, so a view's hundreds of tiles do not each read the database. */
    public static final long MAP_LOGIN_MS = 30_000L;
    private static final Map<String, String> MAP_TYPES = Map.ofEntries(Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"), Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
            Map.entry("webmanifest", "application/manifest+json"), Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("conf", "text/plain; charset=utf-8"),
            Map.entry("prbm", "application/octet-stream"));
    private static final java.util.regex.Pattern MAP_LIVE = java.util.regex.Pattern.compile("maps/([A-Za-z0-9_-]{1,80})/live/(players\\.json|markers\\.json|sse)"),
            MAP_HEAD = java.util.regex.Pattern.compile("maps/[A-Za-z0-9_-]{1,80}/assets/playerheads/([0-9a-f-]{36})\\.png");
    private volatile java.nio.file.Path mapRoot;
    private final Map<String, Long> mapLogins = new java.util.concurrent.ConcurrentHashMap<>();

    /** BlueMap's web folder (its webroot: {@code index.html}, {@code settings.json}, {@code maps/}), mounted read only. */
    public PanelServer withMap(java.nio.file.Path root) {
        this.mapRoot = root == null ? null : root.toAbsolutePath().normalize();
        return this;
    }

    private volatile MapShown mapShown;

    /** Which maps the Map tab shows (SU-83; D570 to D586): a change goes to every open page as a {@code maps} event, which reads the map list again. */
    public PanelServer withMapShown(MapShown m) {
        this.mapShown = m;
        m.onChange(() -> {
            for (Live l : live) {
                write(l, "event: maps\ndata: {}\n\n");
            }
        });
        return this;
    }

    private volatile com.mrgregles.bsp_staff_panel.items.ItemPictures pictures;
    private volatile com.mrgregles.bsp_staff_panel.market.MarketDesk marketDesk;
    private volatile com.mrgregles.bsp_staff_panel.market.BankDesk bankDesk;
    private volatile com.mrgregles.bsp_staff_panel.market.BountyDesk bountyDesk;

    private volatile com.mrgregles.bsp_staff_panel.totems.TotemDesk totemDesk;

    /**
     * The TOTEMS page (SU-97; D613 to D622): each change (the board read, a steal starting or ending anywhere) goes to every
     * open page as a {@code totems} event, which reads the page again or moves "stealing now".
     */
    public PanelServer withTotems(com.mrgregles.bsp_staff_panel.totems.TotemDesk d) {
        this.totemDesk = d;
        d.onEvent(e -> {
            String msg = "event: totems\ndata: " + GSON.toJson(e) + "\n\n";
            for (Live l : live) {
                write(l, msg);
            }
        });
        return this;
    }

    private volatile com.mrgregles.bsp_staff_panel.talk.PlayerTalk playerTalk;
    private volatile com.mrgregles.bsp_staff_panel.players.OfflineDesk offline;

    /** Offline players (SU-115; D653 to D671): the Players tab's OFFLINE group, the map's offline rows, an offline player's card. */
    public PanelServer withOffline(com.mrgregles.bsp_staff_panel.players.OfflineDesk d) {
        this.offline = d;
        return this;
    }

    /**
     * {@code /api/players/offline} ({@code q}, {@code offset}, {@code limit}) a page of offline players, the most recently
     * seen first; {@code /api/players/seen} ({@code player}, a UUID) one player with where they were last seen. Every rank (D664).
     */
    private void offlinePost(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.players.OfflineDesk d = offline;
        if (d == null) {
            json(ex, 200, refused("Offline players are not set up on this panel."));
            return;
        }
        if (path.equals("/api/players/seen")) {
            json(ex, 200, d.one(str(b, "player")));
            return;
        }
        json(ex, 200, d.list(str(b, "q"), intOf(b, "offset", 0), intOf(b, "limit", com.mrgregles.bsp_staff_panel.players.OfflineDesk.PAGE)));
    }

    private static int intOf(JsonObject b, String k, int otherwise) {
        try {
            return b.has(k) && b.get(k).isJsonPrimitive() ? b.get(k).getAsInt() : otherwise;
        } catch (RuntimeException bad) {
            return otherwise;
        }
    }

    /**
     * Private staff messages to players (SU-110; D630 to D652): each new line, from the game or the panel, goes to every open
     * page as a {@code pmsg} event with the line and the waiting count (the red count on More).
     */
    public PanelServer withPlayerTalk(com.mrgregles.bsp_staff_panel.talk.PlayerTalk d) {
        this.playerTalk = d;
        d.onEvent(e -> {
            String msg = "event: pmsg\ndata: " + GSON.toJson(e) + "\n\n";
            for (Live l : live) {
                write(l, msg);
            }
        });
        return this;
    }

    /** The Bounty Board (SU-76; D524 to D538). */
    public PanelServer withBounties(com.mrgregles.bsp_staff_panel.market.BountyDesk d) {
        this.bountyDesk = d;
        return this;
    }

    /** A player's Bank & Market (SU-71; D502 to D511). */
    public PanelServer withBank(com.mrgregles.bsp_staff_panel.market.BankDesk d) {
        this.bankDesk = d;
        return this;
    }

    /** The market and the lottery (SU-51): a draw beginning or ending anywhere goes to every open page as a {@code lottery} event. */
    public PanelServer withMarket(com.mrgregles.bsp_staff_panel.market.MarketDesk d) {
        this.marketDesk = d;
        d.onEvent(e -> {
            String msg = "event: lottery\ndata: " + GSON.toJson(e) + "\n\n";
            for (Live l : live) {
                write(l, msg);
            }
        });
        return this;
    }

    /** The item pictures from an admin's game (SU-41): served to the pages, for icons, names and the picker. */
    public PanelServer withPictures(com.mrgregles.bsp_staff_panel.items.ItemPictures p) {
        this.pictures = p;
        return this;
    }

    /** Player notes and tags (SU-38): the pages hear which player's notes changed, and when the tags did. */
    public PanelServer withNotes(com.mrgregles.bsp_staff_panel.notes.PlayerNotes n) {
        this.notes = n;
        n.onNotes(player -> {
            JsonObject o = new JsonObject();
            o.addProperty("player", player);
            String msg = "event: notes\ndata: " + GSON.toJson(o) + "\n\n";
            for (Live l : live) {
                write(l, msg);
            }
        });
        n.onTags(() -> {
            for (Live l : live) {
                write(l, "event: tags\ndata: {}\n\n");
            }
        });
        return this;
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<JsonObject> chatOut = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The chat lines waiting, as one {@code chat} event of up to 200 lines (the rest in the next). */
    void pushChat() {
        JsonArray lines = new JsonArray();
        JsonObject l;
        while (lines.size() < 200 && (l = chatOut.poll()) != null) {
            lines.add(l);
        }
        if (lines.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.add("lines", lines);
        String msg = "event: chat\ndata: " + GSON.toJson(o) + "\n\n";
        for (Live x : live) {
            write(x, msg);
        }
    }

    public PanelServer start() {
        http.start();
        pusher.scheduleWithFixedDelay(this::push, 1, 1, TimeUnit.SECONDS);
        pusher.scheduleWithFixedDelay(this::pushChat, 250, 250, TimeUnit.MILLISECONDS);
        return this;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        pusher.shutdownNow();
        live.forEach(this::close);
        http.stop(0);
        pool.shutdownNow();
    }

    /** Staff with the panel open now (D293), by Minecraft name. */
    public List<String> onPanel() {
        return live.stream().map(l -> l.account.mcName).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    // ------------------------------------------------------------------ requests

    private void handle(HttpExchange ex) throws IOException {
        try {
            secureHeaders(ex.getResponseHeaders());
            AccessJwt.Identity id;
            try {
                id = gate.check(ex.getRequestHeaders().getFirst("Cf-Access-Jwt-Assertion"));
            } catch (AccessJwt.Invalid e) {
                sayRefusal(e.getMessage());
                text(ex, 403, "Open this panel through its address, signed in to Cloudflare.");
                return;
            }
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            if (method.equals("POST")) {
                if (!sameSite(ex)) {
                    text(ex, 403, "Refused: not sent from the panel's own page.");
                    return;
                }
                post(ex, path, id);
            } else if (method.equals("GET") || method.equals("HEAD")) {
                get(ex, path, id);
            } else {
                text(ex, 405, "Not allowed.");
            }
        } catch (Exception e) {
            try {
                text(ex, 500, "Something went wrong; it is in the panel's console.");
            } catch (IOException ignored) {
                // the connection is gone
            }
            System.err.println("[panel] " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + ": " + e);
        }
    }

    private void secureHeaders(Headers h) {
        h.set("Content-Security-Policy", CSP);
        h.set("X-Frame-Options", "DENY");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        if (https) {
            h.set("Strict-Transport-Security", "max-age=31536000");
        }
    }

    private boolean sameSite(HttpExchange ex) {
        Headers h = ex.getRequestHeaders();
        String origin = h.getFirst("Origin");
        String type = h.getFirst("Content-Type");
        return "1".equals(h.getFirst("X-BSP")) && origin != null && origin.equals(publicUrl)
                && type != null && type.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private Optional<Account> session(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        return auth.check(cookie(ex), id.email());
    }

    private String cookie(HttpExchange ex) {
        String name = cookieName(https);
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) {
                    return p.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    private void setCookie(HttpExchange ex, String token, Account who) {
        ex.getResponseHeaders().add("Set-Cookie", cookieName(https) + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + who.session().toSeconds()
                + (https ? "; Secure" : ""));
    }

    private void clearCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", cookieName(https) + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (https ? "; Secure" : ""));
    }

    private void get(HttpExchange ex, String path, AccessJwt.Identity id) throws Exception {
        switch (path) {
            case "/" -> {
                if (session(ex, id).isPresent()) {
                    resource(ex, "app.html");
                } else {
                    redirect(ex, "/login");
                }
            }
            case "/login" -> {
                if (session(ex, id).isPresent()) {
                    redirect(ex, "/");
                } else {
                    resource(ex, "login.html");
                }
            }
            case "/setup" -> resource(ex, "setup.html");
            case "/api/whoami" -> {
                JsonObject o = ok();
                o.addProperty("email", id.email());
                json(ex, 200, o);
            }
            case "/api/me" -> me(ex, id);
            case "/api/staff" -> staffList(ex, id);
            case "/api/live" -> openLive(ex, id);
            case "/api/chat" -> chatLines(ex, id);
            case "/api/room" -> roomLines(ex, id);
            case "/api/messages" -> messages(ex, id);
            case "/api/notes", "/api/tags" -> notesGet(ex, id, path);
            case "/api/maps" -> mapsSheet(ex, id);
            case "/api/items/exports" -> itemsExports(ex, id);
            case "/api/items/catalog" -> itemsCatalog(ex, id);
            case "/map" -> redirect(ex, "/map/"); // BlueMap's paths are relative to the folder
            default -> {
                if (path.startsWith("/map/")) {
                    mapFile(ex, id, path.substring("/map/".length()));
                    return;
                }
                if (path.startsWith("/api/items/sheet/")) {
                    itemsSheet(ex, id, path.substring("/api/items/sheet/".length()));
                    return;
                }
                if (path.startsWith("/api/head/")) {
                    head(ex, id, path.substring("/api/head/".length()));
                    return;
                }
                if (path.startsWith("/static/") && path.substring(8).matches("[a-z0-9_-]+\\.(css|js|svg|png)")) {
                    resource(ex, "static/" + path.substring(8));
                } else {
                    text(ex, 404, "Not found.");
                }
            }
        }
    }

    private void post(HttpExchange ex, String path, AccessJwt.Identity id) throws Exception {
        JsonObject body = body(ex);
        if (body == null) {
            text(ex, 400, "Refused: not a request the panel understands.");
            return;
        }
        switch (path) {
            case "/api/login" -> login(ex, id, body);
            case "/api/setup/info" -> setupInfo(ex, id, body);
            case "/api/setup" -> setup(ex, id, body);
            case "/api/enrol/info" -> enrolInfo(ex, id, body);
            case "/api/enrol" -> enrol(ex, id, body);
            case "/api/logout" -> {
                String c = cookie(ex);
                if (c != null) {
                    String k = sha256(c) + " ";
                    mapLogins.keySet().removeIf(x -> x.startsWith(k));
                }
                auth.logout(c);
                clearCookie(ex);
                json(ex, 200, ok());
            }
            case "/api/act" -> act(ex, id, body);
            case "/api/graves" -> graves(ex, id, body);
            case "/api/grave/return" -> graveReturn(ex, id, body);
            case "/api/grave/items" -> graveItems(ex, id, body);
            case "/api/items" -> items(ex, id, body);
            case "/api/players/find" -> playersFind(ex, id, body);
            case "/api/players/offline", "/api/players/seen" -> offlinePost(ex, id, path, body);
            case "/api/items/add", "/api/items/remove", "/api/items/undo" -> itemsEdit(ex, id, path, body);
            case "/api/tp/places" -> tpPlaces(ex, id);
            case "/api/tp" -> tp(ex, id, body);
            case "/api/market", "/api/lottery", "/api/lottery/draw" -> marketPost(ex, id, path, body);
            case "/api/bank", "/api/bank/refund", "/api/bank/cancel" -> bankPost(ex, id, path, body);
            case "/api/bounties", "/api/bounties/history", "/api/bounties/players", "/api/bounties/add", "/api/bounties/remove", "/api/bounties/extend",
                 "/api/bounties/blank" -> bountyPost(ex, id, path, body);
            case "/api/totems", "/api/totems/player" -> totemsPost(ex, id, path, body);
            case "/api/pmsg", "/api/pmsg/list", "/api/pmsg/send", "/api/pmsg/end" -> playerTalkPost(ex, id, path, body);
            case "/api/bans", "/api/ban/history" -> bansList(ex, id, path, body);
            case "/api/ban", "/api/ban/edit", "/api/ban/lift" -> banChange(ex, id, path, body);
            case "/api/staff/add" -> staffAdd(ex, id, body);
            case "/api/staff/action" -> staffAction(ex, id, body);
            case "/api/chat/search" -> chatSearch(ex, id, body);
            case "/api/chat/say" -> chatSay(ex, id, body);
            case "/api/room/say", "/api/room/delete", "/api/messages/send", "/api/messages/read", "/api/messages/delete" -> talk(ex, id, path, body);
            case "/api/notes/add", "/api/notes/edit", "/api/notes/delete", "/api/tags/put", "/api/tags/take", "/api/tags/edit", "/api/tags/remove" -> notesPost(ex, id, path, body);
            case "/api/maps/set" -> mapsSet(ex, id, body);
            default -> text(ex, 404, "Not found.");
        }
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                return null;
            }
            JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException bad) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Minecraft's 16 colour names, as the Broadcast page offers them (the game servers take more; the panel sends only these). */
    static final java.util.Set<String> COLOURS = java.util.Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject refused(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("message", message);
        return o;
    }

    // ------------------------------------------------------------------ logging in and setting up

    private void login(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Auth.Login l = auth.login(id.email(), str(b, "password"), str(b, "code"));
        JsonObject o = new JsonObject();
        o.addProperty("outcome", l.outcome().name());
        switch (l.outcome()) {
            case OK -> {
                setCookie(ex, l.token(), l.account());
                o.addProperty("ok", true);
                o.addProperty("backupLeft", l.backupLeft());
            }
            case ENROL -> {
                o.addProperty("ok", false);
                o.addProperty("enrol", l.token());
                o.addProperty("message", "Your authenticator was reset. Set up a new one now.");
            }
            case LOCKED -> {
                o.addProperty("ok", false);
                o.addProperty("until", l.until());
                o.addProperty("message", "Locked after " + Auth.MAX_FAILS + " wrong tries. It opens again in " + minutes(l.until()) + ".");
            }
            case WRONG -> {
                int left = Auth.MAX_FAILS - l.account().failed;
                o.addProperty("ok", false);
                o.addProperty("message", "Wrong password or code. " + left + (left == 1 ? " try" : " tries") + " left, then this account locks for 15 minutes.");
            }
            default -> {
                o.addProperty("ok", false);
                o.addProperty("message", "There is no staff login for " + id.email() + ". Ask an Owner, or use the link you were sent.");
            }
        }
        json(ex, 200, o);
    }

    private String minutes(long until) {
        long m = Math.max(1, (until - clock.getAsLong() + 59_999) / 60_000);
        return m + (m == 1 ? " minute" : " minutes");
    }

    private void setupInfo(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = auth.setupLinkAccount(str(b, "token"));
        if (a.isEmpty()) {
            json(ex, 200, refused("This link has expired or was used. Ask an Owner for a new one."));
            return;
        }
        if (!a.get().email.equals(id.email())) {
            json(ex, 200, refused("This link is for another email than the one you signed in to Cloudflare with (" + id.email() + ")."));
            return;
        }
        JsonObject o = authenticator(a.get());
        o.addProperty("mcName", a.get().mcName);
        o.addProperty("rank", a.get().rank.label());
        o.addProperty("createdBy", a.get().createdBy);
        json(ex, 200, o);
    }

    private JsonObject authenticator(Account a) {
        String[] s = Auth.newAuthenticator(a.email);
        JsonObject o = ok();
        o.addProperty("email", a.email);
        o.addProperty("secret", s[0]);
        o.addProperty("otpauth", s[1]);
        o.addProperty("qr", qrDataUri(s[1]));
        return o;
    }

    private void setup(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        try {
            Auth.Done d = auth.completeSetup(str(b, "token"), id.email(), str(b, "password"), str(b, "secret"), str(b, "code"));
            done(ex, d);
        } catch (Auth.Refused r) {
            json(ex, 200, refused(r.getMessage()));
        }
    }

    private void enrolInfo(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = store.accountByEmail(id.email());
        if (a.isEmpty() || str(b, "token").isEmpty()) {
            json(ex, 200, refused("That took too long. Log in again."));
            return;
        }
        json(ex, 200, authenticator(a.get()));
    }

    private void enrol(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        try {
            done(ex, auth.completeEnrol(str(b, "token"), id.email(), str(b, "secret"), str(b, "code")));
        } catch (Auth.Refused r) {
            json(ex, 200, refused(r.getMessage()));
        }
    }

    private void done(HttpExchange ex, Auth.Done d) throws IOException {
        setCookie(ex, d.token(), d.account());
        JsonObject o = ok();
        JsonArray codes = new JsonArray();
        d.backupCodes().forEach(codes::add);
        o.add("codes", codes);
        o.addProperty("email", d.account().email);
        json(ex, 200, o);
    }

    /** The QR code as an SVG picture in a {@code data:} address (the page's policy allows those for pictures only). */
    static String qrDataUri(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int border = 2, n = qr.size + border * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + border).append(',').append(y + border).append("h1v1h-1z");
                }
            }
        }
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + n + " " + n + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\""
                + path + "\" fill=\"#000\"/></svg>";
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ who is logged in, staff accounts

    private void me(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        Account me = a.get();
        JsonObject o = ok();
        o.addProperty("id", me.id);
        o.addProperty("email", me.email);
        o.addProperty("mcName", me.mcName);
        o.addProperty("rank", me.rank.name());
        o.addProperty("rankLabel", me.rank.label());
        o.addProperty("backupLeft", me.backup.size());
        o.addProperty("itemsPower", me.mayEditItems());
        o.addProperty("bankPower", me.mayActOnBank());
        o.addProperty("bountyPower", me.mayActOnBounties());
        if (me.rank == Rank.OWNER) {
            JsonArray locks = new JsonArray();
            for (PanelStore.LogLine l : auth.lockoutsSince(me.previousLoginAt)) {
                JsonObject x = new JsonObject();
                x.addProperty("at", l.at());
                x.addProperty("who", l.who());
                locks.add(x);
            }
            o.add("lockouts", locks);
        }
        json(ex, 200, o);
    }

    private Optional<Account> owner(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return Optional.empty();
        }
        if (a.get().rank != Rank.OWNER) {
            json(ex, 403, refused("Only an Owner can do that."));
            return Optional.empty();
        }
        return a;
    }

    private void staffList(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        Optional<Account> me = owner(ex, id);
        if (me.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        List<String> on = onPanel();
        JsonArray list = new JsonArray();
        for (Account a : store.accounts()) {
            if (!a.active) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id);
            o.addProperty("email", a.email);
            o.addProperty("mcName", a.mcName);
            o.addProperty("rank", a.rank.name());
            o.addProperty("rankLabel", a.rank.label());
            o.addProperty("you", a.id == me.get().id);
            o.addProperty("setUp", a.setUp());
            o.addProperty("password", a.password != null);
            o.addProperty("lockedUntil", a.lockedUntil > now ? a.lockedUntil : 0);
            o.addProperty("lastLogin", a.lastLoginAt);
            o.addProperty("backupLeft", a.backup.size());
            o.addProperty("onPanel", on.contains(a.mcName));
            o.addProperty("itemsPower", a.itemsPower);
            o.addProperty("bankPower", a.bankPower);
            o.addProperty("bountyPower", a.bountyPower);
            list.add(o);
        }
        JsonObject o = ok();
        o.add("staff", list);
        o.addProperty("now", now);
        json(ex, 200, o);
    }

    private void staffAdd(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> me = owner(ex, id);
        if (me.isEmpty()) {
            return;
        }
        try {
            Rank r = "ADMIN".equals(str(b, "rank")) ? Rank.ADMIN : Rank.MODERATOR;
            Auth.NewLink l = auth.addStaff(me.get(), str(b, "email"), str(b, "mcName"), r);
            JsonObject o = ok();
            o.addProperty("link", publicUrl + "/setup#" + l.token());
            json(ex, 200, o);
        } catch (Auth.Refused e) {
            json(ex, 200, refused(e.getMessage()));
        }
    }

    private void staffAction(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> me = owner(ex, id);
        if (me.isEmpty()) {
            return;
        }
        long target;
        try {
            target = b.get("id").getAsLong();
        } catch (RuntimeException e) {
            json(ex, 200, refused("No such staff member."));
            return;
        }
        JsonObject o = ok();
        try {
            switch (str(b, "action")) {
                case "unlock" -> auth.unlock(me.get(), target);
                case "reset-code" -> auth.resetAuthenticator(me.get(), target);
                case "reset-login" -> o.addProperty("link", publicUrl + "/setup#" + auth.resetLogin(me.get(), target).token());
                case "remove" -> auth.remove(me.get(), target);
                case "rank" -> auth.setRank(me.get(), target, "ADMIN".equals(str(b, "rank")) ? Rank.ADMIN : Rank.MODERATOR);
                case "items" -> auth.setItemsPower(me.get(), target, bool(b, "on"));
                case "bank" -> auth.setBankPower(me.get(), target, bool(b, "on"));
                case "bounty" -> auth.setBountyPower(me.get(), target, bool(b, "on"));
                default -> {
                    json(ex, 200, refused("Not an action the panel knows."));
                    return;
                }
            }
            json(ex, 200, o);
        } catch (Auth.Refused e) {
            json(ex, 200, refused(e.getMessage()));
        }
    }

    /** A player's head (64 px PNG) for a logged-in page; 404 when they have none, and the page draws a plain face. */
    // ------------------------------------------------------------------ the item pictures (SU-41, D340, D373)

    /** More › Items: the export in use (or why the newest could not be), and the last five. */
    private void itemsExports(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        JsonObject o = ok();
        com.mrgregles.bsp_staff_panel.items.ItemPictures p = pictures;
        com.mrgregles.bsp_staff_panel.items.ItemPictures.Loaded l = p == null ? null : p.current();
        o.add("current", l == null ? null : exportJson(l.export()));
        o.addProperty("problem", p == null ? "" : p.problem());
        JsonArray list = new JsonArray();
        for (PanelStore.ItemsExport e : store.itemsExports(5)) {
            list.add(exportJson(e));
        }
        o.add("list", list);
        json(ex, 200, o);
    }

    private static JsonObject exportJson(PanelStore.ItemsExport e) {
        JsonObject x = new JsonObject();
        x.addProperty("id", e.id());
        x.addProperty("at", e.atMs());
        x.addProperty("by", e.by());
        x.addProperty("server", e.server());
        x.addProperty("items", e.items());
        x.addProperty("tabs", e.tabs());
        x.addProperty("mods", e.mods());
        x.addProperty("size", e.size());
        return x;
    }

    /** The catalog (items, tabs, names): cached by the browser for a day, its address naming the export ({@code ?v=}). */
    private void itemsCatalog(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.items.ItemPictures p = pictures;
        com.mrgregles.bsp_staff_panel.items.ItemPictures.Loaded l = p == null ? null : p.current();
        if (l == null) {
            json(ex, 200, refused("No item pictures yet: an Admin types /su icons in their own game."));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=86400");
        send(ex, 200, ("{\"ok\":true,\"catalog\":" + l.catalog() + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** {@code /api/items/sheet/<export>/<n>.png}: one sheet of pictures of the export in use. */
    private void itemsSheet(HttpExchange ex, AccessJwt.Identity id, String rest) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.items.ItemPictures p = pictures;
        com.mrgregles.bsp_staff_panel.items.ItemPictures.Loaded l = p == null ? null : p.current();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]{1,19})/([0-9]{1,2})\\.png").matcher(rest);
        byte[] png = l != null && m.matches() && m.group(1).equals(String.valueOf(l.export().id())) ? l.sheets().get(Integer.parseInt(m.group(2))) : null;
        if (png == null) {
            text(ex, 404, "Not found.");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=604800");
        send(ex, 200, png);
    }

    private void head(HttpExchange ex, AccessJwt.Identity id, String playerId) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        if (!playerId.matches("[0-9a-f-]{36}")) {
            text(ex, 404, "Not found.");
            return;
        }
        Optional<byte[]> png = heads.head(feed.skinOf(playerId));
        if (png.isEmpty()) {
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=600"); // not asked again at every redraw (audit)
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            send(ex, 404, "No head.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
        send(ex, 200, png.get());
    }

    // ------------------------------------------------------------------ actions (SU-35)

    /**
     * An action, after the rank check (D250): a kick for every rank, an announcement for Admins and Owners. It goes to the
     * server the player is on (a kick), or the chosen one (an announcement; for the network any live server, which sends it
     * on). The answer is the game server's own.
     */
    private void act(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        Account me = a.get();
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (act == null) {
            json(ex, 200, refused("This panel cannot send actions."));
            return;
        }
        JsonObject f = new JsonObject();
        String server, what;
        switch (str(b, "action")) {
            case "kick" -> {
                String[] where = feed.find(str(b, "player"));
                if (where == null) {
                    json(ex, 200, refused("They are not online any more."));
                    return;
                }
                server = where[0];
                String reason = str(b, "reason").strip();
                f.addProperty("action", "kick");
                f.addProperty("player", str(b, "player"));
                f.addProperty("reason", reason.length() > 160 ? reason.substring(0, 160) : reason);
                what = "kicked " + where[1] + " on " + server + (reason.isEmpty() ? "" : " (" + reason + ")");
            }
            case "broadcast" -> {
                if (!me.rank.atLeast(Rank.ADMIN)) {
                    json(ex, 403, refused("Only Admins and Owners can send announcements."));
                    return;
                }
                boolean network = bool(b, "network");
                String target = str(b, "target").strip(), message = str(b, "message");
                if (message.isBlank() || message.length() > 256) {
                    json(ex, 200, refused("Write a message of up to 256 characters."));
                    return;
                }
                List<String> live = feed.live();
                if (network) {
                    String[] where = target.isEmpty() || target.equalsIgnoreCase("all") ? null : feed.find(target);
                    server = where != null ? where[0] : live.isEmpty() ? null : live.get(0);
                } else {
                    server = live.stream().filter(s -> s.equalsIgnoreCase(str(b, "server"))).findFirst().orElse(null);
                }
                if (server == null) {
                    json(ex, 200, refused("No game server is sending to the panel right now."));
                    return;
                }
                if (!COLOURS.contains(str(b, "colour")) || !(target.isEmpty() || target.equalsIgnoreCase("all") || target.matches("[A-Za-z0-9_]{3,16}"))) {
                    json(ex, 200, refused("Pick a colour from the list, and a player's name or nobody."));
                    return;
                }
                f.addProperty("action", "broadcast");
                f.addProperty("title", bool(b, "title"));
                f.addProperty("colour", str(b, "colour"));
                f.addProperty("target", target.isEmpty() ? "all" : target);
                f.addProperty("network", network);
                f.addProperty("message", message);
                what = "sent an announcement through " + server + (network ? " to the network" : "") + ": " + message;
            }
            default -> {
                json(ex, 200, refused("Not an action the panel knows."));
                return;
            }
        }
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f);
        store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, what + (ans.ok() ? "" : "; refused: " + ans.message())));
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        json(ex, 200, o);
    }

    // ------------------------------------------------------------------ chat (SU-36, D304 to D313)

    /** {@code ?before=<ms>} older lines (none: the newest), {@code ?around=<ms>} the lines around a search result; oldest first. */
    private void chatLines(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.chat.Chat c = chat;
        JsonObject o = ok();
        JsonArray lines = new JsonArray();
        if (c != null) {
            Map<String, String> q = query(ex);
            long before = number(q.get("before"), Long.MAX_VALUE), around = number(q.get("around"), -1);
            if (around >= 0) {
                c.around(around).forEach(lines::add);
                o.addProperty("more", false);
            } else {
                com.mrgregles.bsp_staff_panel.chat.Chat.Page page = c.before(before);
                page.lines().forEach(lines::add);
                o.addProperty("more", page.more());
            }
        }
        o.add("lines", lines);
        json(ex, 200, o);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new java.util.HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String part : raw.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    out.put(java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    private static long number(String s, long otherwise) {
        try {
            return s == null ? otherwise : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private final Map<Long, java.util.ArrayDeque<Long>> searches = new java.util.concurrent.ConcurrentHashMap<>();

    private void chatSearch(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> who = session(ex, id);
        if (who.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        java.util.ArrayDeque<Long> mine = searches.computeIfAbsent(who.get().id, k -> new java.util.ArrayDeque<>());
        synchronized (mine) { // a search reads 30 days of chat: ten a minute each (audit)
            long now = clock.getAsLong();
            while (!mine.isEmpty() && mine.peekFirst() < now - 60_000L) {
                mine.pollFirst();
            }
            if (mine.size() >= 10) {
                json(ex, 200, refused("That is ten searches in a minute: wait a moment."));
                return;
            }
            mine.addLast(now);
        }
        com.mrgregles.bsp_staff_panel.chat.Chat c = chat;
        String words = str(b, "q"), player = str(b, "player"), server = str(b, "server");
        int days = (int) number(str(b, "days"), 30);
        if (words.length() > 100 || !(player.isEmpty() || player.matches("[A-Za-z0-9_]{1,16}")) || server.length() > 64) {
            json(ex, 200, refused("Search for up to 100 characters, a player's name, or a server."));
            return;
        }
        JsonObject o = ok();
        JsonArray lines = new JsonArray();
        if (c != null) {
            c.search(words, player, server, days).forEach(lines::add);
        }
        o.add("lines", lines);
        json(ex, 200, o);
    }

    /**
     * Says a line in the game's chat (D262, D305 to D307, D313): as yourself ("staff") or as [Server] (Admins and Owners),
     * to one live server or every live one (one signed action each); the answer names each server's result. The line is
     * kept once for the panel's chat, unless this is a RETRY for one server ({@code retry}: true).
     */
    private void chatSay(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        Account me = a.get();
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        com.mrgregles.bsp_staff_panel.chat.Chat c = chat;
        if (act == null || c == null) {
            json(ex, 200, refused("This panel cannot speak in chat."));
            return;
        }
        String voice = str(b, "voice"), to = str(b, "to");
        if (!voice.equals("staff") && !voice.equals("server")) {
            json(ex, 200, refused("Speak as yourself or as [Server]."));
            return;
        }
        if (voice.equals("server") && !me.rank.atLeast(Rank.ADMIN)) {
            json(ex, 403, refused("Only Admins and Owners speak as [Server]."));
            return;
        }
        String text = com.mrgregles.bsp_staff_panel.chat.Chat.clean(str(b, "text"));
        if (text == null) {
            json(ex, 200, refused("Write up to 256 characters of plain text (no § codes)."));
            return;
        }
        List<String> live = feed.live();
        boolean network = to.equalsIgnoreCase("network");
        List<String> targets = network ? live : live.stream().filter(s -> s.equalsIgnoreCase(to)).toList();
        if (targets.isEmpty()) {
            json(ex, 200, refused(network ? "No game server is sending to the panel right now." : to + " is not sending to the panel right now."));
            return;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "say");
        f.addProperty("voice", voice);
        f.addProperty("text", text);
        Map<String, com.mrgregles.bsp_staff_panel.act.Actions.Answer> answers = act.askAll(targets, me.mcName, me.rank.name(), f);
        JsonArray results = new JsonArray();
        int good = 0;
        for (Map.Entry<String, com.mrgregles.bsp_staff_panel.act.Actions.Answer> e : answers.entrySet()) {
            JsonObject r = new JsonObject();
            r.addProperty("server", e.getKey());
            r.addProperty("ok", e.getValue().ok());
            r.addProperty("message", e.getValue().message());
            results.add(r);
            good += e.getValue().ok() ? 1 : 0;
        }
        String where = network ? "network" : targets.get(0);
        JsonObject o = new JsonObject();
        o.addProperty("ok", good > 0);
        o.add("results", results);
        // kept once it may have been shown: a server said so, or did not answer in time (it may still have happened, audit);
        // a RETRY keeps it only when the first try kept nothing
        boolean mayHaveShown = good > 0 || answers.values().stream().anyMatch(x -> x.message().startsWith("No answer from"));
        if (mayHaveShown && !(bool(b, "retry") && bool(b, "kept"))) {
            o.add("line", c.record(voice, where, me.mcName, text));
        }
        store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, (bool(b, "retry") ? "said again" : "said") + " in chat" + (voice.equals("server") ? " as [Server]" : "")
                + " to " + (network ? "the network" : where) + ": " + text + " (" + good + " of " + targets.size() + " servers)"));
        json(ex, 200, o);
    }

    // ------------------------------------------------------------------ the Staff tab (SU-37, D314 to D319, D327 to D329)

    private void roomLines(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        JsonObject o = ok();
        JsonArray lines = new JsonArray();
        com.mrgregles.bsp_staff_panel.talk.StaffTalk t = talk;
        if (t != null) {
            List<JsonObject> got = t.room(number(query(ex).get("before"), Long.MAX_VALUE));
            got.forEach(lines::add);
            o.addProperty("more", got.size() >= com.mrgregles.bsp_staff_panel.talk.StaffTalk.PAGE);
        }
        o.add("lines", lines);
        json(ex, 200, o);
    }

    private void messages(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.talk.StaffTalk t = talk;
        if (t == null) {
            json(ex, 200, refused("This panel has no staff messages."));
            return;
        }
        JsonObject o = t.messages(a.get());
        o.add("people", t.people(a.get()));
        json(ex, 200, o);
    }

    private void talk(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.talk.StaffTalk t = talk;
        if (t == null) {
            json(ex, 200, refused("This panel has no staff room."));
            return;
        }
        Account me = a.get();
        long target = number(str(b, "id"), -1);
        try {
            JsonObject o = ok();
            switch (path) {
                case "/api/room/say" -> o.add("line", t.say(me, str(b, "text")));
                case "/api/room/delete" -> {
                    JsonObject line = t.deleteRoom(me, target);
                    o.add("line", line);
                    store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, "deleted a staff room line by " + str(line, "name")));
                }
                case "/api/messages/send" -> o.add("message", t.send(me, str(b, "to"), str(b, "text"), number(str(b, "replyTo"), 0)));
                case "/api/messages/read" -> t.read(me, target);
                default -> {
                    t.deleteMessage(me, target);
                    store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, "deleted staff message " + target));
                }
            }
            json(ex, 200, o);
        } catch (com.mrgregles.bsp_staff_panel.talk.StaffTalk.Refused r) {
            json(ex, 200, refused(r.getMessage()));
        }
    }

    // ------------------------------------------------------------------ notes and tags (SU-38, D320 to D326, D330, D331)

    private void notesGet(HttpExchange ex, AccessJwt.Identity id, String path) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.notes.PlayerNotes n = notes;
        if (n == null) {
            json(ex, 200, refused("This panel keeps no notes."));
            return;
        }
        try {
            if (path.equals("/api/tags")) {
                json(ex, 200, n.tags());
            } else {
                JsonObject o = ok();
                o.add("notes", n.notes(query(ex).get("player")));
                json(ex, 200, o);
            }
        } catch (com.mrgregles.bsp_staff_panel.notes.PlayerNotes.Refused r) {
            json(ex, 200, refused(r.getMessage()));
        }
    }

    private void notesPost(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.notes.PlayerNotes n = notes;
        if (n == null) {
            json(ex, 200, refused("This panel keeps no notes."));
            return;
        }
        Account me = a.get();
        long target = number(str(b, "id"), -1);
        try {
            JsonObject o = ok();
            switch (path) {
                case "/api/notes/add" -> o.add("note", n.add(me, str(b, "player"), str(b, "name"), str(b, "text")));
                case "/api/notes/edit" -> o.add("note", n.edit(me, target, str(b, "text")));
                case "/api/notes/delete" -> n.delete(me, target);
                case "/api/tags/put" -> n.put(me, str(b, "player"), str(b, "name"), number(str(b, "tag"), 0), str(b, "typed"));
                case "/api/tags/take" -> n.take(me, str(b, "player"), str(b, "name"), number(str(b, "tag"), 0));
                case "/api/tags/edit" -> n.editTag(me, target, str(b, "name"), str(b, "colour"));
                default -> n.removeTag(me, target);
            }
            json(ex, 200, o);
        } catch (com.mrgregles.bsp_staff_panel.notes.PlayerNotes.Refused r) {
            json(ex, 200, refused(r.getMessage()));
        }
    }

    // ------------------------------------------------------------------ graves (D295 to D302)

    /** A player by name (or id), online or not: {id, name, server or null}. */
    private String[] findPlayer(String nameOrId) throws Exception {
        String q = nameOrId == null ? "" : nameOrId.strip();
        if (!q.matches("[A-Za-z0-9_]{3,16}|[0-9a-fA-F-]{36}")) {
            return null;
        }
        String[] online = feed.find(q);
        if (online != null) {
            return new String[]{online[2], online[1], online[0]};
        }
        Optional<String[]> known = store.findPlayer(q);
        return known.map(k -> new String[]{k[0], k[1], null}).orElse(null);
    }

    /** Where a grave action goes: the player's server, or a server whose BSP-InventorySync can answer (graves are network-wide). */
    private String serverFor(String[] player) {
        List<String> able = feed.liveWithGraves();
        if (player[2] != null && (able.isEmpty() || able.contains(player[2]))) {
            return player[2];
        }
        return able.isEmpty() ? player[2] : able.get(0); // their server cannot answer about graves: one that can (audit)
    }

    private void graves(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        String[] p = findPlayer(str(b, "player"));
        if (p == null) {
            json(ex, 200, refused("Nobody by that name has played on the network."));
            return;
        }
        String server = serverFor(p);
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (server == null || act == null) {
            json(ex, 200, refused("No game server with BSP-InventorySync 0.7.0 is sending to the panel right now."));
            return;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "graves");
        f.addProperty("player", p[0]);
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.ask(server, a.get().mcName, a.get().rank.name(), f);
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        JsonObject who = new JsonObject();
        who.addProperty("id", p[0]);
        who.addProperty("name", p[1]);
        who.addProperty("server", p[2]);
        o.add("player", who);
        o.add("graves", ans.data() != null && ans.data().isJsonArray() ? ans.data() : new JsonArray());
        json(ex, 200, o);
    }

    /** What one grave holds (D298, SU-42): every rank, read only, asked where the graves are asked. */
    private void graveItems(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        String[] p = findPlayer(str(b, "player"));
        String grave = str(b, "grave");
        if (p == null || !grave.matches("[0-9a-fA-F-]{8,36}")) {
            json(ex, 200, refused("No such grave."));
            return;
        }
        String server = serverFor(p);
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (server == null || act == null) {
            json(ex, 200, refused("No game server with BSP-InventorySync 0.7.0 is sending to the panel right now."));
            return;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "grave_items");
        f.addProperty("player", p[0]);
        f.addProperty("grave", grave);
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.read(server, a.get().mcName, a.get().rank.name(), f);
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        if (ans.ok() && ans.data() != null && ans.data().isJsonObject()) {
            o.add("items", ans.data());
        }
        json(ex, 200, o);
    }

    private void graveReturn(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        if (!a.get().rank.atLeast(Rank.ADMIN)) {
            json(ex, 403, refused("Only Admins and Owners can return graves."));
            return;
        }
        String[] p = findPlayer(str(b, "player"));
        String grave = str(b, "grave");
        if (p == null || !grave.matches("[0-9a-fA-F-]{8,36}")) {
            json(ex, 200, refused("No such grave."));
            return;
        }
        String server = serverFor(p);
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (server == null || act == null) {
            json(ex, 200, refused("No game server with BSP-InventorySync 0.7.0 is sending to the panel right now."));
            return;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "grave_return");
        f.addProperty("player", p[0]);
        f.addProperty("name", p[1]);
        f.addProperty("grave", grave);
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.ask(server, a.get().mcName, a.get().rank.name(), f);
        if (ans.data() != null && ans.data().isJsonObject() && "ONLINE".equals(str(ans.data().getAsJsonObject(), "outcome"))) {
            String there = str(ans.data().getAsJsonObject(), "serverId");
            String live = feed.live().stream().filter(x -> x.equalsIgnoreCase(there)).findFirst().orElse(null);
            ans = live != null ? act.ask(live, a.get().mcName, a.get().rank.name(), f) // they moved: once more, there
                    : new com.mrgregles.bsp_staff_panel.act.Actions.Answer(false, p[1] + " is on " + there + ", which is not sending to the panel: return it there in game with /invsync grave return.");
        }
        store.log(new PanelStore.LogLine(clock.getAsLong(), a.get().email, "returned grave " + grave + " of " + p[1] + (ans.ok() ? "" : "; refused: " + ans.message())));
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        json(ex, 200, o);
    }

    // ------------------------------------------------------------------ a player's items (SU-39, D256, D335 to D338, D372)

    /**
     * A player's items: asked of the server they are on (live), or of any server whose BSP-InventorySync reads items for
     * one offline (their last save). A player who moved between the feed's update and the read is asked for once more on
     * the server the answer names. Every rank may look; nothing is logged (a read changes nothing).
     */
    private void items(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        String[] p = findPlayer(str(b, "player"));
        if (p == null) {
            json(ex, 200, refused("Nobody by that name has played on the network."));
            return;
        }
        List<String> able = feed.liveWithItems();
        // their own server first; when it cannot read items, any that can (an offline-style read; audit)
        String server = p[2] != null && (able.isEmpty() || able.contains(p[2])) ? p[2] : able.isEmpty() ? p[2] : able.get(0);
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (server == null || act == null) {
            json(ex, 200, refused("No game server with BSP-InventorySync 0.7.0 is sending to the panel right now."));
            return;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "inv_read");
        f.addProperty("player", p[0]);
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.read(server, a.get().mcName, a.get().rank.name(), f);
        if (ans.ok() && ans.data() != null && ans.data().isJsonObject()) {
            JsonObject d = ans.data().getAsJsonObject();
            String there = str(d, "server");
            if (bool(d, "online") && !bool(d, "live") && !there.equalsIgnoreCase(server)) {
                String moved = feed.live().stream().filter(x -> x.equalsIgnoreCase(there)).findFirst().orElse(null);
                if (moved != null) {
                    ans = act.read(moved, a.get().mcName, a.get().rank.name(), f); // online elsewhere: their live items from there
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        JsonObject who = new JsonObject();
        who.addProperty("id", p[0]);
        who.addProperty("name", p[1]);
        who.addProperty("server", p[2]);
        o.add("player", who);
        if (ans.ok() && ans.data() != null && ans.data().isJsonObject()) {
            o.add("items", ans.data());
        }
        o.addProperty("at", clock.getAsLong());
        json(ex, 200, o);
    }

    // ------------------------------------------------------------------ adding and removing items (SU-40; D257, D335, D339, D343)

    public static final long UNDO_MS = 5 * 60_000L;
    public static final int MAX_ITEM_COUNT = 6400, MAX_SNBT = 8192;

    /** A change that can be undone for 5 minutes (D343): by whom, for whom, and the action that undoes it. */
    private record Undo(long account, String player, String name, JsonObject fields, long until) {
    }

    private final Map<String, Undo> undos = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Adds or removes a player's items, or undoes such a change within 5 minutes: Admins, Owners and Moderators given the
     * items power (D335). Sent to the server holding the player (any server whose BSP-InventorySync reads items for one
     * offline); an {@code ONLINE} answer is sent on once to the live server it names. A made change answers with an UNDO
     * token; every attempt is in the panel's log.
     */
    private void itemsEdit(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        Account me = a.get();
        if (!me.mayEditItems()) {
            json(ex, 403, refused("Adding and removing items is for Admins, Owners and Moderators given the items power."));
            return;
        }
        long now = clock.getAsLong();
        undos.values().removeIf(u -> u.until < now);
        JsonObject f = new JsonObject();
        String[] p;
        String what, undoToken = null;
        Undo undone = null;
        if (path.endsWith("/undo")) {
            undoToken = str(b, "token");
            Undo u = undos.get(undoToken);
            if (u == null || u.account != me.id || !undos.remove(undoToken, u)) { // spent at once: two at the same moment cannot both undo (audit)
                json(ex, 200, refused("Too late to undo (5 minutes), or already undone."));
                return;
            }
            undone = u;
            p = findPlayer(u.player);
            if (p == null) {
                p = new String[]{u.player, u.name, null};
            }
            u.fields.entrySet().forEach(e -> f.add(e.getKey(), e.getValue()));
            f.addProperty("undo", true);
            what = "undid a change to " + u.name + "'s items (" + str(u.fields, "action").replace("inv_", "") + " " + str(u.fields, "count") + ")";
        } else {
            p = findPlayer(str(b, "player"));
            if (p == null) {
                json(ex, 200, refused("Nobody by that name has played on the network."));
                return;
            }
            String area = str(b, "area");
            int count = (int) number(str(b, "count"), 0);
            if (!area.matches("[a-z_]{1,32}(/[A-Za-z0-9_.:-]{1,80})?") || count < 1 || count > MAX_ITEM_COUNT) {
                json(ex, 200, refused("Between 1 and " + MAX_ITEM_COUNT + " at once, in a place of their items."));
                return;
            }
            f.addProperty("area", area);
            f.addProperty("count", count);
            if (path.endsWith("/add")) {
                String item = str(b, "item").strip().toLowerCase(Locale.ROOT), snbt = str(b, "snbt");
                if (!item.contains(":")) {
                    item = "minecraft:" + item;
                }
                if (!item.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}") || snbt.length() > MAX_SNBT) {
                    json(ex, 200, refused("Not an item id (such as minecraft:diamond or create:brass_ingot)."));
                    return;
                }
                com.mrgregles.bsp_staff_panel.items.ItemPictures pics = pictures;
                if (!snbt.isEmpty() && (pics == null || !pics.knowsVariant(item, snbt))) {
                    // item data only as the picker lists it (a potion, an enchanted book), never typed in (audit)
                    json(ex, 200, refused("That item's data is not one the item pictures list: choose it in the picker."));
                    return;
                }
                f.addProperty("action", "inv_add");
                f.addProperty("item", item);
                f.addProperty("snbt", snbt);
                f.addProperty("version", number(str(b, "version"), 0));
                what = "added " + count + " " + item + " to " + p[1] + "'s " + area;
            } else {
                String hash = str(b, "hash");
                long slot = number(str(b, "slot"), -1);
                if (!hash.matches("[0-9a-f]{12}") || slot < 0 || slot > 4096) {
                    json(ex, 200, refused("Not a slot of their items."));
                    return;
                }
                f.addProperty("action", "inv_remove");
                f.addProperty("slot", slot);
                f.addProperty("hash", hash);
                what = "removed " + count + " from " + p[1] + "'s " + area + " slot " + slot;
            }
        }
        f.addProperty("player", p[0]);
        f.addProperty("name", p[1]);
        if (me.rank == Rank.MODERATOR) {
            f.addProperty("items", true); // the items power, which the game server checks too
        }
        List<String> able = feed.liveWithItems();
        // their own server first; when it cannot read items, any that can (an offline-style read; audit)
        String server = p[2] != null && (able.isEmpty() || able.contains(p[2])) ? p[2] : able.isEmpty() ? p[2] : able.get(0);
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (server == null || act == null) {
            json(ex, 200, refused("No game server with BSP-InventorySync 0.7.0 is sending to the panel right now."));
            return;
        }
        int wait = "inv_take".equals(str(f, "action")) ? com.mrgregles.bsp_staff_panel.act.Actions.LONG_WAIT_SECONDS : com.mrgregles.bsp_staff_panel.act.Actions.WAIT_SECONDS; // an UNDO takes back slot by slot (audit)
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f, wait);
        if (!ans.ok() && ans.data() != null && ans.data().isJsonObject() && "ONLINE".equals(str(ans.data().getAsJsonObject(), "outcome"))) {
            String there = str(ans.data().getAsJsonObject(), "serverId");
            String live = feed.live().stream().filter(x -> x.equalsIgnoreCase(there)).findFirst().orElse(null);
            if (live != null) {
                ans = act.ask(live, me.mcName, me.rank.name(), f, wait); // they moved: once more, there
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        if (undone != null && !ans.ok() && ans.data() != null && ans.data().isJsonObject()
                && List.of("BUSY", "ONLINE").contains(str(ans.data().getAsJsonObject(), "outcome"))) {
            undos.putIfAbsent(undoToken, undone); // nothing changed (the game server says so): the UNDO may be tried again in its 5 minutes (audit)
            o.addProperty("undo", undoToken);
            o.addProperty("until", undone.until);
        }
        if (ans.data() != null && ans.data().isJsonObject()) {
            JsonObject d = ans.data().getAsJsonObject();
            o.addProperty("outcome", str(d, "outcome"));
            o.addProperty("hash", str(d, "hash"));
            JsonObject undoFields = ans.ok() && d.has("undo") && d.get("undo").isJsonObject() ? undoFields(d.getAsJsonObject("undo")) : null;
            if (undoFields != null) {
                String token = com.mrgregles.bsp_staff_panel.crypto.Tokens.token();
                undos.put(token, new Undo(me.id, p[0], p[1], undoFields, now + UNDO_MS));
                o.addProperty("undo", token);
                o.addProperty("until", now + UNDO_MS);
            }
        }
        store.log(new PanelStore.LogLine(now, me.email, what + (ans.ok() ? "" : "; refused: " + ans.message())));
        json(ex, 200, o);
    }

    /**
     * An UNDO's fields from the game server's answer, kept only as an opposite items change (take or add, with its own
     * fields): an answer can never turn an UNDO into another kind of action (audit). Null when it is anything else.
     */
    public static JsonObject undoFields(JsonObject u) {
        String action = str(u, "action");
        if (!action.equals("inv_take") && !action.equals("inv_add")) {
            return null;
        }
        JsonObject out = new JsonObject();
        for (String k : List.of("action", "area", "hash", "count", "item", "snbt", "version")) {
            if (u.has(k) && u.get(k).isJsonPrimitive()) {
                out.add(k, u.get(k));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ teleports (SU-43; D258, D344 to D349, D364 to D366)

    private volatile JsonObject places;
    private volatile long placesAt;
    public static final long PLACES_MS = 30_000L;

    /** The warps and spawns (asked of a server where BSP-Teleport answers, kept 30 s), spawns by the panel's server ids. */
    private JsonObject placesNow(Account me) {
        long now = clock.getAsLong();
        JsonObject held = places;
        if (now - placesAt < PLACES_MS) {
            return held; // a failure is remembered too, so the map's pins (every 10 s a page) never queue behind a slow server (audit)
        }
        placesAt = now;
        List<String> able = feed.liveWithTp();
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (able.isEmpty() || act == null) {
            return held; // the copy from a moment ago, if any, rather than nothing (audit)
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "tp_places");
        JsonArray names = new JsonArray();
        Map<String, String> byTp = new java.util.HashMap<>();
        for (String s : feed.live()) {
            names.add(feed.tpName(s));
            byTp.put(feed.tpName(s).toLowerCase(Locale.ROOT), s);
        }
        f.add("servers", names);
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.read(able.get(0), me.mcName, me.rank.name(), f);
        if (!ans.ok() || ans.data() == null || !ans.data().isJsonObject()) {
            return held;
        }
        JsonObject d = ans.data().getAsJsonObject();
        JsonObject out = new JsonObject();
        out.add("warps", d.has("warps") ? d.get("warps") : new JsonArray());
        JsonArray spawns = new JsonArray();
        if (d.has("spawns") && d.get("spawns").isJsonArray()) {
            for (JsonElement e : d.getAsJsonArray("spawns")) {
                JsonObject sp = e.getAsJsonObject().deepCopy();
                sp.addProperty("tpServer", str(sp, "server"));
                sp.addProperty("server", byTp.getOrDefault(str(sp, "server").toLowerCase(Locale.ROOT), str(sp, "server")));
                spawns.add(sp);
            }
        }
        out.add("spawns", spawns);
        places = out;
        placesAt = now;
        return out;
    }

    /** The market and the lottery (SU-51; D400 to D415): reads for every rank, DRAW for Admins and Owners. */
    private void marketPost(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.market.MarketDesk desk = marketDesk;
        if (desk == null) {
            json(ex, 200, refused("The market is not set up on this panel."));
            return;
        }
        Account me = a.get();
        switch (path) {
            case "/api/market" -> json(ex, 200, desk.market(me, bool(b, "refresh")));
            case "/api/lottery" -> json(ex, 200, desk.lottery(me));
            default -> {
                JsonObject o = desk.draw(me);
                if (o.has("status")) {
                    int status = o.remove("status").getAsInt();
                    json(ex, status, o);
                    return;
                }
                String on = str(o, "server");
                store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, (on.isEmpty() ? "tried to draw the lottery" : "drew the lottery on " + on)
                        + (o.get("ok").getAsBoolean() ? "" : "; refused: " + str(o, "message"))));
                json(ex, 200, o);
            }
        }
    }

    /**
     * A player's Bank & Market (SU-71; D502 to D511): {@code /api/bank} reads for every rank; {@code /api/bank/refund} and
     * {@code /api/bank/cancel} for Admins, Owners and Moderators with the bank power (403 otherwise, nothing sent); every
     * refund and cancel tried is in the panel's log.
     */
    private void bankPost(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.market.BankDesk desk = bankDesk;
        if (desk == null) {
            json(ex, 200, refused("The bank is not set up on this panel."));
            return;
        }
        Account me = a.get();
        if (!path.equals("/api/bank") && !me.mayActOnBank()) {
            store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, "tried to " + (path.endsWith("/cancel") ? "cancel a lot or request" : "refund a bank row") + " without the bank power"));
            json(ex, 403, refused(com.mrgregles.bsp_staff_panel.market.BankDesk.NOT_ALLOWED));
            return;
        }
        String[] p = path.endsWith("/cancel") ? null : findPlayer(str(b, "player"));
        if (p == null && !path.endsWith("/cancel")) {
            if (path.endsWith("/refund")) {
                store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, "tried to refund a bank row of " + str(b, "player") .replaceAll("[^A-Za-z0-9_.-]", "") + "; refused: nobody by that name")); // (audit)
            }
            json(ex, 200, refused("Nobody by that name has played on the network."));
            return;
        }
        JsonObject o = switch (path) {
            case "/api/bank" -> desk.read(me, p, bool(b, "refresh"));
            case "/api/bank/refund" -> desk.refund(me, p, b);
            default -> desk.cancel(me, b);
        };
        String log = o.has("panelLog") ? str(o, "panelLog") : null;
        o.remove("panelLog");
        if (log != null) {
            String said = str(o, "message");
            store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, log + (o.get("ok").getAsBoolean() ? "" : said.startsWith("No answer") ? "; no answer in time: it may still have happened" : "; refused: " + said)));
        }
        json(ex, 200, o);
    }

    /**
     * The Bounty Board (SU-76; D524 to D538): reads for every rank; add, remove, extend and blank for Admins, Owners and
     * Moderators with the bounty power (403 otherwise, nothing sent); every change tried is in the panel's log.
     */
    private void bountyPost(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.market.BountyDesk desk = bountyDesk;
        if (desk == null) {
            json(ex, 200, refused("Bounties are not set up on this panel."));
            return;
        }
        Account me = a.get();
        String what = path.substring("/api/bounties".length());
        boolean change = what.equals("/add") || what.equals("/remove") || what.equals("/extend") || what.equals("/blank");
        if (change && !me.mayActOnBounties()) {
            store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, "tried to change a bounty without the bounty power"));
            json(ex, 403, refused(com.mrgregles.bsp_staff_panel.market.BountyDesk.NOT_ALLOWED));
            return;
        }
        JsonObject o = switch (what) {
            case "" -> desk.list(me);
            case "/history" -> desk.history(me);
            case "/players" -> desk.players(me, str(b, "search"));
            case "/add" -> desk.add(me, b);
            default -> desk.change(me, what.substring(1), b);
        };
        String log = o.has("panelLog") ? str(o, "panelLog") : null;
        o.remove("panelLog");
        if (log != null) {
            String said = str(o, "message");
            store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, log + (o.get("ok").getAsBoolean() ? "" : said.startsWith("No answer") ? "; no answer in time: it may still have happened" : "; refused: " + said)));
        }
        json(ex, 200, o);
    }

    /**
     * Private staff messages to players (SU-110): {@code /api/pmsg} ({@code id}, {@code name}) a player's conversations,
     * {@code /api/pmsg/list} the open ones, {@code /api/pmsg/send} ({@code id}, {@code name}, {@code text}),
     * {@code /api/pmsg/end} ({@code id}, {@code name}); every rank (D630). An end is in the panel's log.
     */
    private void playerTalkPost(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.talk.PlayerTalk d = playerTalk;
        if (d == null) {
            json(ex, 200, refused("Player messages are not set up on this panel."));
            return;
        }
        Account me = a.get();
        JsonObject out = switch (path) {
            case "/api/pmsg" -> d.history(str(b, "id"), str(b, "name"));
            case "/api/pmsg/list" -> d.list();
            case "/api/pmsg/send" -> d.send(me, str(b, "id"), str(b, "name"), str(b, "text"));
            default -> d.end(me, str(b, "id"), str(b, "name"));
        };
        if (path.equals("/api/pmsg/end") && out.has("ok") && out.get("ok").getAsBoolean()) {
            store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, "ended the private conversation with " + str(b, "name")));
        }
        json(ex, 200, out);
    }

    /** The TOTEMS page (SU-97): every rank reads it (D614); {@code days} 1, 7, 30 or 90; {@code id} a player's UUID for their row. */
    private void totemsPost(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        com.mrgregles.bsp_staff_panel.totems.TotemDesk desk = totemDesk;
        if (desk == null) {
            json(ex, 200, refused("The TOTEMS page is not set up on this panel."));
            return;
        }
        int days = 7;
        try {
            days = b.has("days") && b.get("days").isJsonPrimitive() ? b.get("days").getAsInt() : 7;
        } catch (RuntimeException notANumber) {
            // the usual week
        }
        JsonObject page = path.equals("/api/totems") ? desk.page(days) : desk.player(str(b, "id"), days);
        JsonObject out = new JsonObject(); // the page may be a cached one shared by every request: its fields are copied, never changed
        page.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        out.addProperty("now", clock.getAsLong()); // the panel's time as this answer leaves, for the page's countdowns (audit of 0.16.0)
        json(ex, 200, out);
    }

    private void tpPlaces(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        JsonObject p = placesNow(a.get());
        if (p == null) {
            json(ex, 200, refused("No game server with BSP-Teleport (its API 2) is sending to the panel right now."));
            return;
        }
        JsonObject o = p.deepCopy();
        o.addProperty("ok", true);
        json(ex, 200, o);
    }

    /**
     * Moves a player (every rank, D345): {@code kind} {@code spawn} (a server's), {@code warp} (a name), {@code player} (to
     * another, {@code target}), {@code coords} ({@code server}, {@code world}, x, y, z, {@code ground}); or {@code goto} /
     * {@code bring} (D346): the staff member's own account to the player, or the player to them. Sent to the server of the
     * one who moves; one tap (D348).
     */
    private void tp(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        Account me = a.get();
        JsonObject who = feed.player(str(b, "player"));
        if (who == null) {
            json(ex, 200, refused("They are not online now."));
            return;
        }
        String kind = str(b, "kind");
        JsonObject mover = who, target = null;
        if (kind.equals("goto") || kind.equals("bring")) {
            JsonObject self = null;
            for (JsonElement e : feed.json().getAsJsonArray("players")) {
                if (str(e.getAsJsonObject(), "name").equalsIgnoreCase(me.mcName)) {
                    self = e.getAsJsonObject();
                }
            }
            if (self == null) {
                json(ex, 200, refused("Your own account (" + me.mcName + ") is not in game."));
                return;
            }
            mover = kind.equals("goto") ? self : who;
            target = kind.equals("goto") ? who : self;
            kind = "player";
        }
        JsonObject to = new JsonObject();
        String what;
        switch (kind) {
            case "spawn" -> {
                JsonObject p = placesNow(me);
                String srv = str(b, "server");
                JsonObject sp = null;
                if (p != null) {
                    for (JsonElement e : p.getAsJsonArray("spawns")) {
                        if (str(e.getAsJsonObject(), "server").equalsIgnoreCase(srv)) {
                            sp = e.getAsJsonObject();
                        }
                    }
                }
                if (sp == null) {
                    json(ex, 200, refused("No spawn known for " + srv + " (set with /setspawn there)."));
                    return;
                }
                to.addProperty("server", str(sp, "tpServer"));
                to.add("world", sp.get("world"));
                to.add("x", sp.get("x"));
                to.add("y", sp.get("y"));
                to.add("z", sp.get("z"));
                what = "spawn of " + srv;
            }
            case "warp" -> {
                JsonObject p = placesNow(me);
                String name = str(b, "warp");
                JsonObject w = null;
                if (p != null) {
                    for (JsonElement e : p.getAsJsonArray("warps")) {
                        if (str(e.getAsJsonObject(), "name").equals(name)) {
                            w = e.getAsJsonObject();
                        }
                    }
                }
                if (w == null) {
                    json(ex, 200, refused("No warp called " + name + "."));
                    return;
                }
                for (String k : new String[]{"server", "world", "x", "y", "z"}) {
                    to.add(k, w.get(k));
                }
                what = "warp " + name;
            }
            case "player" -> {
                if (target == null) {
                    target = feed.player(str(b, "target"));
                }
                if (target == null) {
                    json(ex, 200, refused("The player to go to is not online now."));
                    return;
                }
                to.addProperty("server", feed.tpName(str(target, "server")));
                to.add("world", target.get("dim"));
                to.add("x", target.get("x"));
                to.add("y", target.get("y"));
                to.add("z", target.get("z"));
                what = str(target, "name");
            }
            case "coords" -> {
                String srv = feed.live().stream().filter(x -> x.equalsIgnoreCase(str(b, "server"))).findFirst().orElse(null);
                if (srv == null || !str(b, "world").matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}")) {
                    json(ex, 200, refused("Choose a server that is sending to the panel, and a world."));
                    return;
                }
                to.addProperty("server", feed.tpName(srv));
                to.addProperty("world", str(b, "world"));
                long x = number(str(b, "x").strip(), Long.MIN_VALUE), y = number(str(b, "y").strip(), Long.MIN_VALUE), z = number(str(b, "z").strip(), Long.MIN_VALUE);
                if (x == Long.MIN_VALUE || z == Long.MIN_VALUE || (y == Long.MIN_VALUE && !bool(b, "ground"))) {
                    json(ex, 200, refused("Whole numbers for x, y and z (y may be left out with \"on the ground\")."));
                    return;
                }
                to.addProperty("x", x + 0.5); // the middle of the block
                to.addProperty("y", bool(b, "ground") ? 0 : y);
                to.addProperty("z", z + 0.5);
                to.addProperty("ground", bool(b, "ground"));
                if (bool(b, "ground") && !srv.equalsIgnoreCase(str(mover, "server"))) {
                    json(ex, 200, refused("\"On the ground\" works only on " + str(mover, "name") + "'s own server (" + str(mover, "server") + ")."));
                    return;
                }
                what = str(b, "x") + ", " + (bool(b, "ground") ? "the ground" : str(b, "y")) + ", " + str(b, "z") + (str(b, "world").equals("minecraft:overworld") ? "" : " in " + str(b, "world"));
            }
            default -> {
                json(ex, 200, refused("Not a teleport the panel knows."));
                return;
            }
        }
        to.addProperty("what", what);
        String server = str(mover, "server");
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (act == null || !feed.liveWithTp().contains(server)) {
            json(ex, 200, refused(server + " has no BSP-Teleport with its API 2 sending to the panel."));
            return;
        }
        if (bool(mover, "arriving")) {
            json(ex, 200, refused(str(mover, "name") + " is arriving on " + server + ": a moment."));
            return;
        }
        JsonObject f = new JsonObject();
        f.addProperty("action", "tp");
        f.addProperty("player", str(mover, "id"));
        f.addProperty("name", str(mover, "name"));
        if (target != null) {
            f.addProperty("target", str(target, "id"));
        }
        f.add("to", to);
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f);
        store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, "moved " + str(mover, "name") + " to " + what + (ans.ok() ? "" : "; refused: " + ans.message())));
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        json(ex, 200, o);
    }

    // ------------------------------------------------------------------ network bans (SU-44; D255, D350 to D359, D367 to D370, D375, D376)

    private static JsonObject banJson(PanelStore.Ban b, long now) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.id());
        o.addProperty("player", b.player());
        o.addProperty("name", b.name());
        o.addProperty("reason", b.reason());
        o.addProperty("note", b.note());
        o.addProperty("by", b.by());
        o.addProperty("at", b.atMs());
        o.addProperty("until", b.untilMs());
        o.addProperty("liftedAt", b.liftedAt());
        o.addProperty("liftedBy", b.liftedBy());
        o.addProperty("editedAt", b.editedAt());
        o.addProperty("active", b.activeAt(now));
        return o;
    }

    /** More › Bans (ACTIVE or PAST, a search) and one player's bans (the sheet's "Banned N times before"): every rank sees them. */
    private void bansList(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        long now = clock.getAsLong();
        List<PanelStore.Ban> list;
        if (path.endsWith("/history")) {
            String[] p = findPlayer(str(b, "player"));
            list = p == null ? List.of() : store.bansOf(p[0]);
        } else {
            String q = str(b, "q").strip();
            list = q.matches("[A-Za-z0-9_]{0,16}") ? store.bans(!str(b, "which").equals("past"), q, now, 200) : List.of();
        }
        JsonArray out = new JsonArray();
        for (PanelStore.Ban x : list) {
            JsonObject j = banJson(x, now);
            j.addProperty("staff", staffLevel(x.player(), x.name())); // UNBAN and EDIT dimmed for an Admin on a staff member's ban (D355, D427)
            out.add(j);
        }
        JsonObject o = ok();
        o.add("bans", out);
        o.addProperty("now", now);
        if (path.endsWith("/history")) {
            String[] p = findPlayer(str(b, "player"));
            o.addProperty("staff", p == null ? 0 : staffLevel(p[0], p[1])); // the form dims BAN as the server would refuse it (audit)
        }
        json(ex, 200, o);
    }

    /** 0: not staff; 1: staff (an active panel account, or a staff role in game while online); 2: an Owner of the panel. */
    private int staffLevel(String player, String name) throws Exception {
        int level = 0;
        for (Account x : store.accounts()) {
            if (x.active && x.mcName.equalsIgnoreCase(name)) {
                level = Math.max(level, x.rank == Rank.OWNER ? 2 : 1);
            }
        }
        JsonObject online = feed.player(player);
        if (level == 0 && online != null && online.has("role") && !online.get("role").isJsonNull()) {
            level = 1;
        }
        return level;
    }

    /**
     * Why {@code me} may not ban (or change the ban of) this player, or null (D355): nobody bans an Owner, and staff are
     * banned by the Owner alone. Staff in a staff group who are offline are known to the game server, which checks again.
     */
    private String mayNotBan(Account me, String player, String name) throws Exception {
        int level = staffLevel(player, name);
        return level == 2 ? name + " is an Owner of the panel: an Owner cannot be banned from here."
                : level == 1 && me.rank != Rank.OWNER ? name + " is staff: only the Owner bans staff." : null;
    }

    /** A ban's length from the form: 1h, 1d, 7d, 30d, perm, custom (days and hours, D352); -1 keeps an edited ban's end; -2 not a length. */
    static long banLength(JsonObject b) {
        return switch (str(b, "length")) {
            case "1h" -> 3_600_000L;
            case "1d" -> 86_400_000L;
            case "7d" -> 7 * 86_400_000L;
            case "30d" -> 30 * 86_400_000L;
            case "perm" -> 0L;
            case "keep" -> -1L;
            case "custom" -> {
                long d = number(str(b, "days").strip(), -1), h = number(str(b, "hours").strip(), -1);
                d = Math.max(0, d);
                h = Math.max(0, h);
                long ms = d * 86_400_000L + h * 3_600_000L;
                yield d > 3650 || h > 1000 || ms < 3_600_000L ? -2L : ms;
            }
            default -> -2L;
        };
    }

    /**
     * Bans, edits and lifts (Admins and Owners, D370). A ban needs the player's name typed back (the second check, D368);
     * a player who is staff (a panel account, or a staff role in game) is banned by the Owner alone and an Owner by nobody
     * (D355). Sent to the player's server, or any server sending to the panel; the game server writes the row.
     */
    private void banChange(HttpExchange ex, AccessJwt.Identity id, String path, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        Account me = a.get();
        if (!me.rank.atLeast(Rank.ADMIN)) {
            json(ex, 403, refused("Bans are for Admins and Owners."));
            return;
        }
        JsonObject f = new JsonObject();
        String server, what;
        if (path.equals("/api/ban")) {
            String[] p = findPlayer(str(b, "player"));
            if (p == null) {
                json(ex, 200, refused("Nobody by that name has played on the network."));
                return;
            }
            if (!str(b, "confirm").strip().equalsIgnoreCase(p[1])) {
                json(ex, 200, refused("Type " + p[1] + " to ban them."));
                return;
            }
            String not = mayNotBan(me, p[0], p[1]);
            if (not != null) {
                json(ex, 200, refused(not));
                return;
            }
            long length = banLength(b);
            if (length < 0) {
                json(ex, 200, refused("Choose how long: 1 hour, 1 day, 7 days, 30 days, at least an hour of your own, or permanent."));
                return;
            }
            f.addProperty("action", "ban");
            f.addProperty("player", p[0]);
            f.addProperty("name", p[1]);
            f.addProperty("lengthMs", length);
            f.addProperty("reason", str(b, "reason"));
            f.addProperty("note", str(b, "note"));
            server = p[2];
            what = "banned " + p[1] + " (" + (length == 0 ? "permanent" : str(b, "length")) + "): " + str(b, "reason");
        } else {
            Optional<PanelStore.Ban> ban = store.ban(number(str(b, "ban"), -1));
            if (ban.isEmpty()) {
                json(ex, 200, refused("No such ban."));
                return;
            }
            JsonObject online = feed.player(ban.get().player());
            server = online == null ? null : str(online, "server");
            f.addProperty("ban", ban.get().id());
            f.addProperty("player", ban.get().player()); // the game server checks staff by it (D355, audit)
            String not = mayNotBan(me, ban.get().player(), ban.get().name());
            if (not != null && path.endsWith("/lift")) {
                not = ban.get().name() + " is staff: only the Owner lifts a ban on staff, as only the Owner gives one (D427).";
            }
            if (not != null) {
                json(ex, 200, refused(not)); // a staff member's ban is changed by the Owner alone, as it is given (D355, audit)
                return;
            }
            if (path.endsWith("/lift")) {
                f.addProperty("action", "unban");
                what = "lifted the ban on " + ban.get().name();
            } else {
                long length = banLength(b);
                if (length < -1) {
                    json(ex, 200, refused("Choose how long from now, or keep the end."));
                    return;
                }
                f.addProperty("action", "ban_edit");
                f.addProperty("lengthMs", length);
                f.addProperty("reason", str(b, "reason"));
                f.addProperty("note", str(b, "note"));
                what = "changed the ban on " + ban.get().name() + " (" + str(b, "length") + "): " + str(b, "reason");
            }
        }
        if (server == null) {
            List<String> live = feed.live();
            server = live.isEmpty() ? null : live.get(0);
        }
        com.mrgregles.bsp_staff_panel.act.Actions act = actions;
        if (server == null || act == null) {
            json(ex, 200, refused("No game server is sending to the panel right now: bans are written by a game server."));
            return;
        }
        com.mrgregles.bsp_staff_panel.act.Actions.Answer ans = act.ask(server, me.mcName, me.rank.name(), f, com.mrgregles.bsp_staff_panel.act.Actions.LONG_WAIT_SECONDS); // written to the database first (audit)
        store.log(new PanelStore.LogLine(clock.getAsLong(), me.email, what + (ans.ok() ? "" : "; refused: " + ans.message())));
        JsonObject o = new JsonObject();
        o.addProperty("ok", ans.ok());
        o.addProperty("message", ans.message());
        json(ex, 200, o);
    }

    /** More › Find a player (D336, D372): online players first, then anyone who joined, by the start of their name. */
    private void playersFind(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        if (session(ex, id).isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        String q = str(b, "q").strip();
        JsonArray out = new JsonArray();
        if (q.matches("[A-Za-z0-9_]{1,16}")) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            String lower = q.toLowerCase(Locale.ROOT);
            JsonObject snap = feed.json();
            for (JsonElement e : snap.getAsJsonArray("players")) {
                JsonObject pl = e.getAsJsonObject();
                if (out.size() < 10 && str(pl, "name").toLowerCase(Locale.ROOT).startsWith(lower) && seen.add(str(pl, "id").toLowerCase(Locale.ROOT))) {
                    JsonObject r = new JsonObject();
                    r.addProperty("id", str(pl, "id"));
                    r.addProperty("name", str(pl, "name"));
                    r.addProperty("server", str(pl, "server"));
                    out.add(r);
                }
            }
            for (String[] k : store.searchPlayers(q, 10)) {
                if (out.size() < 10 && seen.add(k[0].toLowerCase(Locale.ROOT))) {
                    JsonObject r = new JsonObject();
                    r.addProperty("id", k[0]);
                    r.addProperty("name", k[1]);
                    out.add(r);
                }
            }
        }
        JsonObject o = ok();
        o.add("players", out);
        json(ex, 200, o);
    }

    // ------------------------------------------------------------------ the live feed (server-sent events)

    private void openLive(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        closing.removeIf(x -> x.writer.isTerminated());
        if (closing.stream().filter(x -> x.account.id == a.get().id).count() >= MAX_LIVE_PER_ACCOUNT) {
            text(ex, 503, "Too many of your pages are still closing; try again in a minute.");
            return;
        }
        List<Live> mine = live.stream().filter(x -> x.account.id == a.get().id).toList();
        if (mine.size() >= MAX_LIVE_PER_ACCOUNT) {
            Live oldest = mine.get(0); // the oldest of this staff member's pages makes room, and is told so it does not come back (audit)
            write(oldest, "event: replaced\ndata: {}\n\n");
            try {
                oldest.writer.execute(() -> close(oldest));
            } catch (java.util.concurrent.RejectedExecutionException full) {
                close(oldest);
            }
        }
        if (live.size() + closing.size() >= MAX_LIVE) {
            text(ex, 503, "Too many open pages; close one.");
            return;
        }
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        Live l = new Live(out, cookie(ex), id.email(), a.get());
        live.add(l);
        write(l, event());
        dirty = true; // the others see this one on the panel
    }

    private String event() {
        JsonObject o = feed.json();
        JsonArray staff = new JsonArray();
        onPanel().forEach(staff::add);
        o.add("onPanel", staff);
        return "event: live\ndata: " + GSON.toJson(o) + "\n\n";
    }

    /** Queues a write on the page's own thread; a full queue (a page not reading) closes it. Never blocks the caller. */
    private void write(Live l, String text) {
        if (l.closed) {
            return;
        }
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        try {
            l.writer.execute(() -> {
                try {
                    l.out.write(b);
                    l.out.flush();
                } catch (IOException e) {
                    close(l);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            close(l);
        }
    }

    /** Drops a page: off the list at once; its stream is closed on its own thread, which may be stuck on a dead connection. */
    private void close(Live l) {
        if (l.closed) {
            return;
        }
        l.closed = true;
        live.remove(l);
        closing.add(l); // counted until its thread has finished, so stuck connections cannot pile up (audit)
        try {
            l.writer.execute(() -> closeQuietly(l.out)); // after what is queued; on the page's own thread
        } catch (java.util.concurrent.RejectedExecutionException full) {
            Thread t = new Thread(() -> closeQuietly(l.out), "panel-live-close");
            t.setDaemon(true);
            t.start();
        }
        l.writer.shutdown();
        dirty = true;
    }

    /** Once a second: changes to every open page (at most once a second), a ping every 20 s, and logins that ended are closed. */
    void push() {
        try {
            com.mrgregles.bsp_staff_panel.market.MarketDesk desk = marketDesk;
            if (desk != null) {
                try {
                    desk.tick(); // a draw seen beginning or ending (D411)
                } catch (RuntimeException bad) {
                    System.err.println("[panel] watching the lottery: " + bad); // never stops the live feed (audit)
                }
            }
            long now = clock.getAsLong();
            List<Live> gone = new ArrayList<>();
            if (now - lastCheck >= LIVE_CHECK_MS) { // logins that ended, every 10 s (not every second for every page)
                lastCheck = now;
                for (Live l : live) {
                    if (auth.check(l.session, l.email).isEmpty()) {
                        write(l, "event: ended\ndata: {}\n\n");
                        gone.add(l);
                    }
                }
                for (Live l : gone) {
                    try {
                        l.writer.execute(() -> close(l)); // after the "ended" line
                    } catch (java.util.concurrent.RejectedExecutionException full) {
                        close(l);
                    }
                }
            }
            String msg = null;
            if (!gone.isEmpty() || dirty && now - lastLive >= LIVE_EVERY_MS) {
                // at most every 5 s (D251): seven servers' snapshots each set "dirty", which redrew every page every second (audit)
                dirty = false;
                lastLive = now;
                msg = event();
            } else if (now - lastPing >= LIVE_PING_MS) {
                msg = "event: ping\ndata: {}\n\n"; // a named event, so the page sees the feed is alive (an SSE comment it cannot see; audit)
            }
            if (msg != null) {
                lastPing = now;
                for (Live l : live) {
                    write(l, msg);
                }
            }
        } catch (Exception e) {
            System.err.println("[panel] live feed: " + e);
        }
    }

    private final Map<String, Long> refusalsSaid = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Why Cloudflare's token was refused, in the console at most once a minute for each reason: a wrong cloudflare.aud
     * or team name shows up there, not only as the same 403 for everyone (audit).
     */
    private void sayRefusal(String why) {
        String key = String.valueOf(why);
        long now = clock.getAsLong();
        Long last = refusalsSaid.get(key);
        if (refusalsSaid.size() < 100 && (last == null || now - last >= 60_000L)) {
            refusalsSaid.put(key, now);
            System.err.println("[panel] a request without a good Cloudflare Access token was refused: " + key);
        }
    }

    public int liveCount() {
        return live.size();
    }

    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // already gone
        }
    }

    // ------------------------------------------------------------------ which maps the Map tab shows (SU-83; D570 to D586)

    /**
     * BlueMap's own {@code settings.json} (its every map, none taken off), or null when the map is not set up or the file is
     * missing. Unreadable JSON is an error: the panel never serves a list it could not filter.
     */
    private static JsonObject mapSettings(java.nio.file.Path root) throws IOException {
        if (root == null) {
            return null;
        }
        java.nio.file.Path f = root.resolve("settings.json");
        if (!java.nio.file.Files.isRegularFile(f) || !f.toRealPath().startsWith(root.toRealPath())) {
            return null;
        }
        JsonElement e = JsonParser.parseString(java.nio.file.Files.readString(f, StandardCharsets.UTF_8));
        if (!e.isJsonObject()) {
            throw new IOException("BlueMap's settings.json is not a JSON object");
        }
        return e.getAsJsonObject();
    }

    /** The ids of every map BlueMap has drawn (its {@code settings.json}), hidden ones too; empty when there is no map. */
    private List<String> mapsDrawn() throws IOException {
        JsonObject s = mapSettings(mapRoot);
        List<String> out = new ArrayList<>();
        if (s != null && s.has("maps") && s.get("maps").isJsonArray()) {
            s.getAsJsonArray("maps").forEach(e -> {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            });
        }
        return out;
    }

    /** A server's name as the feed has it, matched by its map id ("bsp_spawn": "BSP-Spawn"); one not sending now made readable. */
    private java.util.function.Function<String, String> serverNames() {
        Map<String, String> names = new java.util.HashMap<>();
        JsonObject f = feed.json();
        if (f.has("servers") && f.get("servers").isJsonArray()) {
            f.getAsJsonArray("servers").forEach(e -> {
                if (e.isJsonObject() && e.getAsJsonObject().has("server")) {
                    String n = e.getAsJsonObject().get("server").getAsString();
                    names.put(MapLive.mapId(n, "overworld").replaceFirst("__overworld$", ""), n);
                }
            });
        }
        return s -> names.getOrDefault(s, MapShown.readable(s));
    }

    private void mapsSheet(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        MapShown m = mapShown;
        if (m == null || mapRoot == null) {
            json(ex, 200, refused("The map is not set up on this panel (map.root in panel.properties)."));
            return;
        }
        try {
            json(ex, 200, m.sheet(a.get(), mapsDrawn(), serverNames()));
        } catch (MapShown.Refused r) {
            json(ex, 200, refused(r.getMessage()));
        }
    }

    private void mapsSet(HttpExchange ex, AccessJwt.Identity id, JsonObject b) throws Exception {
        Optional<Account> a = session(ex, id);
        if (a.isEmpty()) {
            json(ex, 401, refused("Log in again."));
            return;
        }
        MapShown m = mapShown;
        if (m == null || mapRoot == null) {
            json(ex, 200, refused("The map is not set up on this panel (map.root in panel.properties)."));
            return;
        }
        if (!b.has("show") || !b.get("show").isJsonPrimitive() || !b.get("show").getAsJsonPrimitive().isBoolean()) {
            json(ex, 200, refused("Say whether to show or hide.")); // never hidden by a missing field (audit)
            return;
        }
        List<String> ids = new ArrayList<>();
        if (b.has("maps") && b.get("maps").isJsonArray()) {
            for (JsonElement e : b.getAsJsonArray("maps")) {
                ids.add(e.isJsonPrimitive() ? e.getAsString() : null);
            }
        }
        try {
            json(ex, 200, m.set(a.get(), mapsDrawn(), ids, bool(b, "show"), str(b, "how"), serverNames()));
        } catch (MapShown.Refused r) {
            json(ex, 200, refused(r.getMessage()));
        }
    }

    // ------------------------------------------------------------------ the map's files (SU-45)

    /**
     * One of BlueMap's files, for a logged-in staff member: as it is, else its {@code .gz} with {@code Content-Encoding: gzip}
     * (BlueMap's file storage), else for a tile not drawn yet 204 (the page draws nothing there), else 404. Only paths of
     * plain characters inside the folder; links leading outside it are refused.
     */
    private void mapFile(HttpExchange ex, AccessJwt.Identity id, String rest) throws Exception {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", MAP_CSP);
        h.set("X-Frame-Options", "SAMEORIGIN");
        if (!mapLogin(ex, id)) {
            text(ex, 401, "Log in to the staff panel first.");
            return;
        }
        java.nio.file.Path root = mapRoot;
        if (root == null) {
            text(ex, 404, "The map is not set up on this panel (map.root in panel.properties).");
            return;
        }
        String want = rest.isEmpty() ? "index.html" : rest;
        boolean page = want.endsWith(".html") || want.endsWith(".htm"), script = want.endsWith(".js") || want.endsWith(".mjs");
        if (!want.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+){0,12}") || want.length() > 300 || java.util.Arrays.asList(want.split("/")).stream().anyMatch(s -> s.equals("..") || s.equals("."))
                || page && !want.equals("index.html") || script && !want.startsWith("assets/")) { // only BlueMap's own page and scripts run here (audit)
            text(ex, 404, "Not found.");
            return;
        }
        MapShown shown = mapShown;
        java.util.Set<String> hidden = shown == null ? java.util.Set.of() : shown.hidden();
        if (MapShown.hiddenPath(want, hidden)) { // a hidden map is not reachable by its address either (D572)
            text(ex, 404, "Not found.");
            return;
        }
        if (want.equalsIgnoreCase("settings.json") && shown != null) { // "." segments are refused above, so no other address reaches it (audit)
            JsonObject settings = mapSettings(root);
            if (settings == null) {
                text(ex, 404, "Not found.");
                return;
            }
            h.set("Cache-Control", "no-store");
            json(ex, 200, MapShown.filterSettings(settings, hidden));
            return;
        }
        java.util.regex.Matcher live = MAP_LIVE.matcher(want);
        if (live.matches()) { // SU-46: the panel's own live data in place of a BlueMap plugin's
            h.set("Cache-Control", "no-store");
            switch (live.group(2)) {
                case "players.json" -> json(ex, 200, MapLive.players(feed.json().getAsJsonArray("players"), live.group(1)));
                case "markers.json" -> {
                    Optional<Account> a = session(ex, id);
                    JsonObject places = a.isEmpty() ? null : placesNow(a.get());
                    Map<String, String> byTp = new java.util.HashMap<>();
                    feed.live().forEach(x -> byTp.put(feed.tpName(x).toLowerCase(Locale.ROOT), x));
                    json(ex, 200, MapLive.markers(places, n -> byTp.getOrDefault(n.toLowerCase(Locale.ROOT), n), live.group(1)));
                }
                default -> text(ex, 404, "No live stream here: BlueMap's page reads players.json instead."); // its polling stays on
            }
            return;
        }
        java.util.regex.Matcher face = MAP_HEAD.matcher(want);
        if (face.matches()) { // the heads BlueMap's page puts on the players, from the panel's own heads
            Optional<byte[]> png = heads.head(feed.skinOf(face.group(1)));
            if (png.isEmpty()) {
                h.set("Cache-Control", "private, max-age=600");
                text(ex, 404, "No head."); // the page draws Steve
                return;
            }
            h.set("Content-Type", "image/png");
            h.set("Cache-Control", "private, max-age=3600");
            send(ex, 200, png.get());
            return;
        }
        java.nio.file.Path f = root.resolve(want).normalize(), real = null;
        String encoding = null;
        if (f.startsWith(root) && !java.nio.file.Files.isRegularFile(f)) {
            java.nio.file.Path gz = f.resolveSibling(f.getFileName() + ".gz");
            if (java.nio.file.Files.isRegularFile(gz)) {
                f = gz;
                encoding = "gzip";
            }
        }
        if (f.startsWith(root) && java.nio.file.Files.isRegularFile(f)) {
            real = f.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                real = null; // a link out of BlueMap's folder
            }
        }
        if (real == null) {
            if (want.matches("maps/[^/]+/tiles/.+")) {
                h.set("Cache-Control", "private, max-age=60");
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } else {
                text(ex, 404, "Not found.");
            }
            return;
        }
        String ext = want.substring(want.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        h.set("Content-Type", MAP_TYPES.getOrDefault(ext, "application/octet-stream"));
        if (encoding != null) {
            h.set("Content-Encoding", encoding);
        }
        h.set("Cache-Control", want.contains("/tiles/") ? "private, max-age=60" : want.startsWith("assets/") ? "private, max-age=86400" : "private, no-cache");
        long modified = java.nio.file.Files.getLastModifiedTime(real).toMillis() / 1000 * 1000;
        h.set("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(modified).atZone(java.time.ZoneOffset.UTC)));
        String since = ex.getRequestHeaders().getFirst("If-Modified-Since");
        if (since != null) {
            try {
                if (java.time.ZonedDateTime.parse(since, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() >= modified) {
                    h.remove("Content-Encoding");
                    ex.sendResponseHeaders(304, -1);
                    ex.close();
                    return;
                }
            } catch (java.time.format.DateTimeParseException ignored) {
                // sent in full
            }
        }
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(200, head ? -1 : java.nio.file.Files.size(real));
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                java.nio.file.Files.copy(real, out);
            }
        }
        ex.close();
    }

    /** The panel's login on a map request, believed for {@link #MAP_LOGIN_MS} once seen (forgotten at logout). */
    private boolean mapLogin(HttpExchange ex, AccessJwt.Identity id) throws Exception {
        String c = cookie(ex);
        if (c == null || c.isEmpty()) {
            return false;
        }
        String key = sha256(c) + " " + id.email();
        long now = clock.getAsLong();
        Long until = mapLogins.get(key);
        if (until != null && until > now) {
            return true;
        }
        if (session(ex, id).isEmpty()) {
            mapLogins.remove(key);
            return false;
        }
        if (mapLogins.size() > 2000) {
            mapLogins.values().removeIf(u -> u <= now);
        }
        mapLogins.put(key, now + MAP_LOGIN_MS);
        return true;
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ responses

    /** Every server tick of the pusher also forgets pages whose threads have finished. */
    public int closingCount() {
        closing.removeIf(x -> x.writer.isTerminated());
        return closing.size();
    }

    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png");

    private void resource(HttpExchange ex, String name) throws IOException {
        try (InputStream in = PanelServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                text(ex, 404, "Not found.");
                return;
            }
            byte[] b = in.readAllBytes();
            String ext = name.substring(name.lastIndexOf('.') + 1);
            ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
            send(ex, 200, b);
        }
    }

    private static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    private static void text(HttpExchange ex, int status, String message) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        send(ex, status, message.getBytes(StandardCharsets.UTF_8));
    }

    private static void json(HttpExchange ex, int status, JsonObject o) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(ex, status, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, byte[] b) throws IOException {
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : b.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        }
        ex.close();
    }
}
