package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.talk.TalkService;
import com.mrgregles.bsp_server_utilities.talk.TalkStore;
import com.mrgregles.bsp_server_utilities.talk.TalkWindows;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Private staff messages from the staff web panel, the game server's part (SU-109; owner decisions D630, D640, D652 R6):
 * <ul>
 * <li>{@code talk_send}: {@code player} (UUID), {@code name}, {@code text}: a staff line from the panel, by the staff
 * account's Minecraft name ({@code by}); every rank (D630). The same rules as in game ({@link TalkService}): never to
 * staff, 1 to 256 plain characters, 30 a minute.</li>
 * <li>{@code talk_end}: {@code player}, {@code name}: ends their open conversation (D632).</li>
 * </ul>
 * The answer's {@code data} holds the line's signed copy ({@code line}, docs/API.md) and where the player is
 * ({@code where}: a server's name, empty when offline). Every line written on this server, from the game or the panel,
 * is also told to the panel at once on the web channel as kind {@value #KIND}: its signed copy, the same as the
 * {@code line} column of {@code <prefix>talk_lines}. The snapshot's {@code talk} says this server takes these actions.
 */
public final class WebTalk {
    public static final String KIND = "talk";
    @Nullable
    private static Consumer<String> sentForTests;

    private WebTalk() {
    }

    /** Once, at mod start: every line written here is told to the panel. */
    public static void register() {
        TalkService.onChange((server, w) -> announce(w.line()));
    }

    /** The snapshot's {@code talk}: this server takes {@code talk_send} and {@code talk_end}. */
    public static void addTo(JsonObject snapshot) {
        snapshot.addProperty("talk", true);
    }

    static void announce(TalkStore.Line l) {
        if (l.line().isEmpty() || !WebAgent.enabled()) {
            return;
        }
        if (sentForTests != null) {
            sentForTests.accept(l.line());
            return;
        }
        Storage s = Storage.active();
        if (s != null) {
            s.publishWeb(KIND, l.line(), false);
        }
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static WebActions.Answer later(MinecraftServer server, String id, CompletableFuture<TalkService.Result> f) {
        CompletableFuture<WebActions.Answer> out = f.thenApply(r -> {
            JsonObject data = new JsonObject();
            data.addProperty("outcome", r.outcome().name());
            if (r.written() != null) {
                data.addProperty("line", r.written().line().line());
                data.addProperty("where", TalkWindows.where(server, r.written().talk().player()));
            }
            return new WebActions.Answer(id, r.ok(), r.message(), data, null);
        });
        return new WebActions.Answer(id, false, "", null, out);
    }

    /** {@code talk_send}. */
    static WebActions.Answer send(MinecraftServer server, JsonObject a, String id, String by) {
        UUID player = uuid(str(a, "player"));
        String name = str(a, "name");
        if (player == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            return new WebActions.Answer(id, false, "That is not a player.");
        }
        if (!by.matches("[A-Za-z0-9_]{1,16}")) {
            return new WebActions.Answer(id, false, "The staff member's Minecraft name is not a name.");
        }
        return later(server, id, TalkService.staffSend(server, TalkService.By.panel(by), player, name, str(a, "text")));
    }

    /** {@code talk_end}. */
    static WebActions.Answer end(MinecraftServer server, JsonObject a, String id, String by) {
        UUID player = uuid(str(a, "player"));
        String name = str(a, "name");
        if (player == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            return new WebActions.Answer(id, false, "That is not a player.");
        }
        if (!by.matches("[A-Za-z0-9_]{1,16}")) {
            return new WebActions.Answer(id, false, "The staff member's Minecraft name is not a name.");
        }
        return later(server, id, TalkService.end(server, TalkService.By.panel(by), player, name));
    }

    /** Tests: where the live lines go instead of the web channel (null: the channel). */
    public static void sentForTests(@Nullable Consumer<String> sink) {
        sentForTests = sink;
    }
}
