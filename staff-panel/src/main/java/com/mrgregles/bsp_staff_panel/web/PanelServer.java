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
    public static final String COOKIE = "bsp_panel";
    public static final int MAX_BODY = 16 * 1024, MAX_LIVE = 50;
    public static final long LIVE_PING_MS = 20_000L;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; font-src 'self';"
            + " base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

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
    private final Heads heads;
    private final String publicUrl;
    private final boolean https;
    private final LongSupplier clock;
    private final List<Live> live = new CopyOnWriteArrayList<>();
    private final List<Live> closing = new CopyOnWriteArrayList<>();
    private volatile boolean dirty = true;
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

    public PanelServer start() {
        http.start();
        pusher.scheduleWithFixedDelay(this::push, 1, 1, TimeUnit.SECONDS);
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

    private static String cookie(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    return p.substring(COOKIE.length() + 1);
                }
            }
        }
        return null;
    }

    private void setCookie(HttpExchange ex, String token, Rank rank) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + rank.session().toSeconds()
                + (https ? "; Secure" : ""));
    }

    private void clearCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (https ? "; Secure" : ""));
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
            default -> {
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
                auth.logout(cookie(ex));
                clearCookie(ex);
                json(ex, 200, ok());
            }
            case "/api/act" -> act(ex, id, body);
            case "/api/graves" -> graves(ex, id, body);
            case "/api/grave/return" -> graveReturn(ex, id, body);
            case "/api/staff/add" -> staffAdd(ex, id, body);
            case "/api/staff/action" -> staffAction(ex, id, body);
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
                setCookie(ex, l.token(), l.account().rank);
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
        setCookie(ex, d.token(), d.account().rank);
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
        o.addProperty("email", me.email);
        o.addProperty("mcName", me.mcName);
        o.addProperty("rank", me.rank.name());
        o.addProperty("rankLabel", me.rank.label());
        o.addProperty("backupLeft", me.backup.size());
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
        if (player[2] != null) {
            return player[2];
        }
        List<String> able = feed.liveWithGraves();
        return able.isEmpty() ? null : able.get(0);
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
            if (dirty || !gone.isEmpty()) {
                dirty = false;
                msg = event();
            } else if (now - lastPing >= LIVE_PING_MS) {
                msg = ": ping\n\n";
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
