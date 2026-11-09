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
    public static final long MAX_AGE_MS = 30_000L, KEEP_IDS_MS = 300_000L;
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

    /** Whether this server has a usable action key (for {@code /su status}). */
    public static boolean ready() {
        return key.get() != null;
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
        JsonObject a = verify(k, payload);
        if (a == null) {
            warn("an action with a wrong signature was refused (another action key, or changed on the way)");
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
        if (Math.abs(now - at) > MAX_AGE_MS) {
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
            case "broadcast" -> broadcast(server, a, id, by);
            case "graves" -> graves(a, id);
            case "grave_return" -> graveReturn(server, a, id, by);
            default -> new Answer(id, false, "This server does not know that action; is it on the same version as the panel?");
        };
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

    @Nullable
    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String cause(Throwable t) {
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

    public static void useForTests(@Nullable byte[] testKey, @Nullable LongSupplier testClock, @Nullable BiFunction<MinecraftServer, UUID, ServerPlayer> find) {
        key = () -> testKey;
        if (testClock != null) {
            clock = testClock;
        }
        if (find != null) {
            lookup = find;
        }
    }

    public static void useReal() {
        key = WebActions::configKey;
        answerSink = null;
        clock = System::currentTimeMillis;
        lookup = (s, id) -> s.getPlayerList().getPlayer(id);
        synchronized (SEEN) {
            SEEN.clear();
        }
    }
}
