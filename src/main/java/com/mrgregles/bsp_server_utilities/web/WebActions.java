package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsNetworkConfig;
import com.mrgregles.bsp_server_utilities.announce.Broadcast;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the staff web panel asks a game server to do (SU-35): a notice {@code web_action} on the servers' own channel,
 * whose payload is the action as JSON in URL-safe Base64, a dot, and an HMAC-SHA256 of that Base64 text in hex, keyed
 * with the panel's action key ({@code webPanel.actionKey} here, {@code actionKey} in the panel's {@code panel.properties}).
 * Every server hears every action and acts only on its own ({@code server}); an action is refused unless its signature is
 * right, it is at most {@link #MAX_AGE_MS} old and its {@code id} was never seen, so a copy cannot be sent again. The panel
 * has checked the staff member's rank before signing. The answer goes back on the web channel (kind {@code answer}), and
 * every action is an Action Log entry by "&lt;name&gt; (panel)".
 */
public final class WebActions {
    public static final String KIND_ACTION = "web_action", KIND_ANSWER = "answer";
    public static final long MAX_AGE_MS = 30_000L, KEEP_IDS_MS = 300_000L, UNTIL_GRACE_MS = 1_000L;
    public static final int MAX_REASON = 160;

    /**
     * What came of an action; {@code null} from {@link #handle} when it was for another server. {@code data} is the
     * answer's own content (a list of graves); {@code later}, when set, is the real answer still on its way (an action
     * that waits on another mod), sent once it completes.
     */
    public record Answer(String id, boolean ok, String message, @Nullable JsonElement data, @Nullable java.util.concurrent.CompletableFuture<Answer> later) {
        public Answer(String id, boolean ok, String message) {
            this(id, ok, message, null, null);
        }
    }

    private static final Map<String, Long> SEEN = new LinkedHashMap<>();
    private static Supplier<byte[]> key = WebActions::configKey;
    /** How an action or a notice from the panel is checked: the panel's Ed25519 signature with {@code webPanel.panelKey} (D426); tests swap it. */
    private static final java.util.function.Function<String, JsonObject> BY_CONFIG = WebActions::verifyWithConfigPanelKey;
    private static java.util.function.Function<String, JsonObject> fromPanel = BY_CONFIG;
    private static LongSupplier clock = System::currentTimeMillis;
    private static BiFunction<MinecraftServer, UUID, ServerPlayer> lookup = (s, id) -> s.getPlayerList().getPlayer(id);

    private WebActions() {
    }

    @Nullable
    private static byte[] configKey() {
        try {
            String k = ServerUtilsNetworkConfig.WEB_ACTION_KEY.get().trim();
            if (k.isEmpty()) {
                return null;
            }
            byte[] b = Base64.getDecoder().decode(k);
            return b.length == 32 ? b : null;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    /** Whether {@code webPanel.actionKey} holds something that is not a key the panel made (for {@code /su status}). */
    public static boolean keyIsMalformed() {
        try {
            return !ServerUtilsNetworkConfig.WEB_ACTION_KEY.get().trim().isEmpty() && configKey() == null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Whether this server has a usable action key and the panel's public key (for {@code /su status}). */
    public static boolean ready() {
        return key.get() != null && (fromPanel != BY_CONFIG || configPanelKey() != null);
    }

    private static java.security.PublicKey cachedPanelKey;
    private static String cachedPanelKeyText = "";

    /** {@code webPanel.panelKey} as a key, or null when it is empty or not one. */
    @Nullable
    public static java.security.PublicKey configPanelKey() {
        try {
            String t = ServerUtilsNetworkConfig.WEB_PANEL_KEY.get().trim();
            if (!t.equals(cachedPanelKeyText)) {
                cachedPanelKeyText = t;
                cachedPanelKey = t.isEmpty() ? null : panelKey(t);
            }
            return cachedPanelKey;
        } catch (IllegalStateException notLoaded) {
            return null;
        }
    }

    /** Whether {@code webPanel.panelKey} holds something that is not a public key the panel made. */
    public static boolean panelKeyIsMalformed() {
        try {
            return !ServerUtilsNetworkConfig.WEB_PANEL_KEY.get().trim().isEmpty() && configPanelKey() == null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static final byte[] ED25519_SPKI = HexFormat.of().parseHex("302a300506032b6570032100");

    /** The panel's public key from its 32 raw bytes in Base64 (what its console prints), or null. */
    @Nullable
    public static java.security.PublicKey panelKey(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            if (raw.length != 32) {
                return null;
            }
            byte[] spki = new byte[ED25519_SPKI.length + 32];
            System.arraycopy(ED25519_SPKI, 0, spki, 0, ED25519_SPKI.length);
            System.arraycopy(raw, 0, spki, ED25519_SPKI.length, 32);
            return java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(spki));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return null;
        }
    }

    @Nullable
    private static JsonObject verifyWithConfigPanelKey(String payload) {
        return verifyPanel(configPanelKey(), payload);
    }

    /**
     * An action or notice signed by the panel's private key (D426): its JSON in URL-safe Base64, a dot, the Ed25519
     * signature of that Base64 text in URL-safe Base64. Only the panel holds the private key, so nothing on a game
     * server (its files, another mod) can order an action. Null when it is not so signed.
     */
    @Nullable
    public static JsonObject verifyPanel(@Nullable java.security.PublicKey panel, String payload) {
        if (panel == null || payload == null) {
            return null;
        }
        int dot = payload.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        String body = payload.substring(0, dot);
        try {
            byte[] sig = Base64.getUrlDecoder().decode(payload.substring(dot + 1));
            java.security.Signature v = java.security.Signature.getInstance("Ed25519");
            v.initVerify(panel);
            v.update(body.getBytes(StandardCharsets.US_ASCII));
            if (sig.length != 64 || !v.verify(sig)) {
                return null;
            }
            JsonElement e = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (GeneralSecurityException | RuntimeException bad) {
            return null;
        }
    }

    /** An action or notice from the panel, checked as {@link #verifyPanel} does (tests: as they set it). */
    @Nullable
    public static JsonObject fromPanel(String payload) {
        return fromPanel.apply(payload);
    }

    /** The key, or null (the web agent signs what it sends with it). */
    @Nullable
    public static byte[] currentKey() {
        return key.get();
    }

    /**
     * What this server sends to the panel, signed with the same key (audit 2026-11-09): the HMAC-SHA256 of the text's
     * UTF-8 bytes in hex, a dot, then the text. The panel drops anything else, so nobody with only Redis or database access
     * can show it made-up players or a made-up answer.
     */
    public static String signText(byte[] key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal(text.getBytes(StandardCharsets.UTF_8))) + "." + text;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long lastWarn;
    private static int quietWarnings;

    /** At most one warning a minute, so a flood of bad notices cannot fill the log (audit). */
    private static void warn(String what) {
        long now = System.currentTimeMillis();
        if (now - lastWarn < 60_000L) {
            quietWarnings++;
            return;
        }
        BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: {}{}", what, quietWarnings > 0 ? " (" + quietWarnings + " more in the last minute)" : "");
        lastWarn = now;
        quietWarnings = 0;
    }

    // ------------------------------------------------------------------ the signature (the panel's Actions does the same)

    public static String sign(byte[] key, String json) {
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return body + "." + HexFormat.of().formatHex(hmac(key, body));
    }

    private static byte[] hmac(byte[] key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(text.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The action's JSON when the signature holds, else null. */
    @Nullable
    public static JsonObject verify(@Nullable byte[] key, String payload) {
        if (key == null || payload == null) {
            return null;
        }
        int dot = payload.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        String body = payload.substring(0, dot);
        byte[] sig;
        try {
            sig = HexFormat.of().parseHex(payload.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(hmac(key, body), sig)) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException bad) {
            return null;
        }
    }

    // ------------------------------------------------------------------ handling

    public static void registerNotices() {
        Storage.on(KIND_ACTION, WebActions::dispatch);
    }

    /** One notice: handled, and its answer sent (at once, or when an answer still on its way completes). */
    public static void dispatch(MinecraftServer server, com.mrgregles.bsp_server_utilities.storage.StorageBackend.Notice n) {
        if (answerSink == null && !WebAgent.canAnswer()) {
            JsonObject mine = fromPanel(n.payload());
            if (mine != null && Storage.currentServerId().equalsIgnoreCase(str(mine, "server"))) {
                warn("an action from the staff web panel was not carried out: this server's Redis is not connected, so no answer could reach the panel");
            }
            return; // answers travel on Redis only: carried out here, the panel would say "no answer" for something done (audit)
        }
        Answer a = handle(server, n.payload());
        if (a != null && a.later() != null) {
            a.later().whenComplete((done, t) -> answer(done != null ? done : new Answer(a.id(), false, "It failed: " + (t == null ? "no answer" : t.getMessage()))));
        } else {
            answer(a);
        }
    }

    @Nullable
    private static java.util.function.Consumer<String> answerSink;

    private static void answer(@Nullable Answer a) {
        Storage s = Storage.active();
        byte[] k = key.get();
        if (a != null && k != null && answerSink != null) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("ok", a.ok());
            o.addProperty("message", a.message());
            if (a.data() != null) {
                o.add("data", a.data());
            }
            answerSink.accept(signText(k, o.toString()));
            return;
        }
        if (a != null && s != null && k != null) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("ok", a.ok());
            o.addProperty("message", a.message());
            o.addProperty("server", s.serverId());
            if (a.data() != null) {
                o.add("data", a.data());
            }
            s.publishWeb(KIND_ANSWER, signText(k, o.toString()), false);
        }
    }

    /** Checks and carries out one action; null when it is not for this server (or cannot even be read). */
    @Nullable
    public static Answer handle(MinecraftServer server, String payload) {
        if (!WebAgent.enabled()) {
            return null; // the panel is off on this server: nothing is taken (audit)
        }
        byte[] k = key.get();
        if (k == null) {
            warn("an action from the staff web panel was refused: no webPanel.actionKey in config/" + ServerUtilsNetworkConfig.FILE);
            return null; // without a key nothing can be trusted, not even which server it was for
        }
        JsonObject a = fromPanel(payload);
        if (a == null) {
            warn(configPanelKey() == null && fromPanel == BY_CONFIG
                    ? "an action from the staff web panel was refused: no webPanel.panelKey (the panel's public key) in config/" + ServerUtilsNetworkConfig.FILE
                    : "an action with a wrong signature was refused (not signed by the panel's own key, or changed on the way)");
            return null;
        }
        if (!Storage.currentServerId().equalsIgnoreCase(str(a, "server"))) {
            return null;
        }
        String id = str(a, "id");
        long now = clock.getAsLong(), at;
        try {
            at = a.get("at").getAsLong();
        } catch (RuntimeException bad) {
            at = 0; // refused below as too old
        }
        long until = a.has("until") && a.get("until").isJsonPrimitive() ? a.get("until").getAsLong() : Long.MAX_VALUE;
        if (Math.abs(now - at) > MAX_AGE_MS || now - UNTIL_GRACE_MS > until) { // (no "until": Long.MAX_VALUE, never past)
            // past "until" the panel has stopped waiting and told staff there was no answer: doing it now would surprise them (audit)
            return new Answer(id, false, "Refused: the request took too long to arrive (or the clocks differ). Try again.");
        }
        synchronized (SEEN) {
            for (Iterator<Long> it = SEEN.values().iterator(); it.hasNext(); ) {
                if (it.next() < now - KEEP_IDS_MS) {
                    it.remove();
                } else {
                    break;
                }
            }
            if (id.isEmpty() || SEEN.putIfAbsent(id, now) != null) {
                return new Answer(id, false, "Refused: this request was already used.");
            }
        }
        String by = str(a, "by");
        return switch (str(a, "action")) {
            case "kick" -> kick(server, a, id, by);
            case "broadcast" -> admin(a) ? broadcast(server, a, id, by) : new Answer(id, false, ADMINS_ONLY); // D250, checked here too (audit)
            case "graves" -> graves(a, id);
            case "grave_return" -> admin(a) ? graveReturn(server, a, id, by) : new Answer(id, false, ADMINS_ONLY); // D296
            case "grave_items" -> graveItems(a, id);
            case "tp_places" -> WebTeleports.places(a, id);
            case "tp" -> WebTeleports.move(server, a, id, by);
            case "ban" -> WebBanActions.ban(server, a, id, by);
            case "unban" -> WebBanActions.unban(server, a, id, by);
            case "ban_edit" -> WebBanActions.edit(server, a, id, by);
            case "say" -> WebChat.say(server, a, id, by);
            case "inv_read" -> itemsRead(a, id);
            case "inv_add" -> WebItemEdits.add(server, a, id, by);
            case "inv_remove" -> WebItemEdits.remove(server, a, id, by);
            case "inv_take" -> WebItemEdits.take(server, a, id, by);
            case "market_read" -> WebMarket.read(server, a, id);
            case "lottery_read" -> WebMarket.lottery(server, id);
            case "lottery_draw" -> WebMarket.draw(server, a, id, by);
            case "bank_read" -> WebBank.read(server, a, id);
            case "bank_refund" -> WebBank.refund(server, a, id, by);
            case "market_cancel_lot" -> WebBank.cancelLot(server, a, id, by);
            case "market_cancel_order" -> WebBank.cancelOrder(server, a, id, by);
            case "bounty_list" -> WebBounties.list(server, id);
            case "bounty_history" -> WebBounties.history(server, id);
            case "bounty_players" -> WebBounties.players(server, a, id);
            case "bounty_add" -> WebBounties.add(server, a, id, by);
            case "bounty_remove" -> WebBounties.remove(server, a, id, by);
            case "bounty_extend" -> WebBounties.extend(server, a, id, by);
            case "bounty_blank_notes" -> WebBounties.blankNotes(server, a, id, by);
            default -> new Answer(id, false, "This server does not know that action; is it on the same version as the panel?");
        };
    }

    static final String ADMINS_ONLY = "Refused: only Admins and Owners may do that.";

    private static boolean admin(JsonObject a) {
        String r = str(a, "rank");
        return r.equals("ADMIN") || r.equals("OWNER");
    }

    private static Answer kick(MinecraftServer server, JsonObject a, String id, String by) {
        UUID who;
        try {
            who = UUID.fromString(str(a, "player"));
        } catch (IllegalArgumentException e) {
            return new Answer(id, false, "No such player.");
        }
        ServerPlayer p = lookup.apply(server, who);
        if (p == null) {
            return new Answer(id, false, "They are not on " + Storage.currentServerId() + " any more.");
        }
        String reason = Broadcast.clean(str(a, "reason"));
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON);
        }
        String name = p.getGameProfile().getName();
        ProxyKick.kick(p, Component.literal(reason.isBlank() ? "You were kicked by staff." : "You were kicked by staff: " + reason));
        ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "kick " + name + (reason.isBlank() ? "" : ": " + reason)));
        return new Answer(id, true, name + " was kicked from the network.");
    }

    private static Answer broadcast(MinecraftServer server, JsonObject a, String id, String by) {
        List<String> said = new ArrayList<>();
        boolean[] failed = {false};
        CommandSource collect = new CommandSource() {
            @Override
            public void sendSystemMessage(Component c) {
                said.add(c.getString());
                if (c.getStyle().getColor() != null && c.getStyle().getColor().equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.RED))) {
                    failed[0] = true; // sendFailure's red line
                }
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        String who = by + " (panel)";
        CommandSourceStack src = new CommandSourceStack(collect, Vec3.ZERO, Vec2.ZERO, server.overworld(), 4, who, Component.literal(who), server, null);
        boolean title = bool(a, "title"), network = bool(a, "network");
        String target = str(a, "target").isBlank() ? "all" : str(a, "target"), message = str(a, "message");
        Broadcast.send(src, title, str(a, "colour"), target, network, message);
        boolean ok = !failed[0] && !said.isEmpty();
        if (!ok) {
            return new Answer(id, false, said.isEmpty() ? "Nothing was sent." : String.join(" ", said));
        }
        ActionLog.record(server, ActionLog.entryForServer(who, LogEntry.CMD, "broadcast " + (title ? "title " : "chat ") + str(a, "colour") + " " + target + " "
                + (network ? "network " : "server ") + Broadcast.clean(message)));
        return new Answer(id, ok, said.isEmpty() ? "Nothing was sent." : String.join(" ", said));
    }

    // ------------------------------------------------------------------ graves (D295 to D302, BSP-InventorySync's D30)

    private static final String NO_GRAVES = "Graves need BSP-InventorySync 0.7.0 or newer on this server.";

    /** A player's graves on the whole network (read only; nothing logged). */
    private static Answer graves(JsonObject a, String id) {
        com.mrgregles.bsp_server_utilities.compat.InventorySync.Graves g = com.mrgregles.bsp_server_utilities.compat.InventorySync.graves();
        UUID who = uuid(str(a, "player"));
        if (g == null || who == null) {
            return new Answer(id, false, g == null ? NO_GRAVES : "No such player.");
        }
        return new Answer(id, true, "", null, g.graves(who).handle((list, t) -> t != null
                ? new Answer(id, false, "The graves could not be read: " + cause(t))
                : new Answer(id, true, list.size() + (list.size() == 1 ? " grave" : " graves"), list, null)));
    }

    /** Returns one grave to its owner (BSP-InventorySync checks it may be returned, and logs it too). */
    private static Answer graveReturn(MinecraftServer server, JsonObject a, String id, String by) {
        com.mrgregles.bsp_server_utilities.compat.InventorySync.Graves g = com.mrgregles.bsp_server_utilities.compat.InventorySync.graves();
        UUID who = uuid(str(a, "player"));
        String grave = str(a, "grave"), name = str(a, "name");
        if (g == null || who == null || !grave.matches("[0-9a-fA-F-]{8,36}")) {
            return new Answer(id, false, g == null ? NO_GRAVES : "No such grave.");
        }
        return new Answer(id, true, "", null, g.returnGrave(who, grave, by).handle((r, t) -> {
            if (t != null) {
                return new Answer(id, false, "The grave could not be returned: " + cause(t));
            }
            boolean ok = r.has("ok") && r.get("ok").getAsBoolean();
            if (ok) {
                ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "grave return " + (name.isEmpty() ? who : name) + " " + grave.substring(0, 8)));
            }
            return new Answer(id, ok, r.has("message") ? r.get("message").getAsString() : (ok ? "Returned." : "Not returned."), r, null);
        }));
    }

    // ------------------------------------------------------------------ a player's items (SU-39, D256, D336)

    static final String NO_ITEMS = "Items need BSP-InventorySync 0.7.0 or newer on this server.";

    /**
     * A player's items, live when they are on this server, else from their newest snapshot (read only; nothing logged).
     * Every rank may look (D335); the panel asks the server holding the player, or any server for one offline.
     */
    private static Answer itemsRead(JsonObject a, String id) {
        com.mrgregles.bsp_server_utilities.compat.InventorySync.Items items = com.mrgregles.bsp_server_utilities.compat.InventorySync.items();
        UUID who = uuid(str(a, "player"));
        if (items == null || who == null) {
            return new Answer(id, false, items == null ? NO_ITEMS : "No such player.");
        }
        return new Answer(id, true, "", null, items.read(who).handle((r, t) -> {
            if (t != null) {
                return new Answer(id, false, "The items could not be read: " + cause(t));
            }
            if (r.isEmpty()) {
                return new Answer(id, false, "Nothing has been saved for that player yet.");
            }
            com.google.gson.JsonObject data = WebItems.toJson(who, r.get());
            return new Answer(id, true, r.get().live() ? "live" : "saved", data, null);
        }));
    }

    /**
     * What one grave holds (D298, BSP-InventorySync's SU-IS3), read only, for every rank; nothing logged. The answer's data:
     * {@code xp} and {@code areas} as {@code inv_read}'s (the area {@code grave}, then backpacks and danks inside it).
     */
    private static Answer graveItems(JsonObject a, String id) {
        com.mrgregles.bsp_server_utilities.compat.InventorySync.Graves g = com.mrgregles.bsp_server_utilities.compat.InventorySync.graves();
        UUID who = uuid(str(a, "player"));
        String grave = str(a, "grave");
        if (g == null || who == null || !grave.matches("[0-9a-fA-F-]{8,36}")) {
            return new Answer(id, false, g == null ? NO_GRAVES : "No such grave.");
        }
        return new Answer(id, true, "", null, g.graveItems(who, grave).handle((r, t) -> {
            if (t != null) {
                return new Answer(id, false, "The grave's items could not be read: " + cause(t));
            }
            if (r.isEmpty()) {
                return new Answer(id, false, "That grave is empty, or not theirs.");
            }
            JsonObject data = new JsonObject();
            data.addProperty("xp", r.get().xp());
            com.google.gson.JsonArray areas = new com.google.gson.JsonArray();
            r.get().areas().forEach(ar -> areas.add(WebItems.area(ar)));
            data.add("areas", areas);
            return new Answer(id, true, "", data, null);
        }));
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String cause(Throwable t) {
        Throwable c = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
        return String.valueOf(c.getMessage());
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: where answers go instead of the web channel (null: the channel). */
    public static void answersForTests(@Nullable java.util.function.Consumer<String> sink) {
        answerSink = sink;
    }

    /** Tests: actions checked against this public key, as {@code webPanel.panelKey} would be (null: back to the config). */
    public static void usePanelKeyForTests(@Nullable java.security.PublicKey panel) {
        fromPanel = panel == null ? BY_CONFIG : payload -> verifyPanel(panel, payload);
    }

    public static void useForTests(@Nullable byte[] testKey, @Nullable LongSupplier testClock, @Nullable BiFunction<MinecraftServer, UUID, ServerPlayer> find) {
        key = () -> testKey;
        fromPanel = payload -> verify(testKey, payload); // tests sign with the shared key; the panel's real Ed25519 path has its own test
        if (testClock != null) {
            clock = testClock;
        }
        if (find != null) {
            lookup = find;
        }
    }

    public static void useReal() {
        key = WebActions::configKey;
        fromPanel = BY_CONFIG;
        answerSink = null;
        clock = System::currentTimeMillis;
        lookup = (s, id) -> s.getPlayerList().getPlayer(id);
        synchronized (SEEN) {
            SEEN.clear();
        }
    }
}
