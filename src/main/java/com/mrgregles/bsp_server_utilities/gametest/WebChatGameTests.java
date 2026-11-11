package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebChat;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chat on the staff web panel (SU-36, D261 to D263, D304 to D313): public lines go to the panel signed, once, with the
 * server, the player and the time, and into the shared chat table; cancelled lines, a panel switched off or no action key
 * send nothing; the panel's "say" shows "[Staff] Name:" or "[Server]" (Admins and Owners only) with a hover, refuses
 * what it should, and writes the Action Log.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebChatGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 7);
        }
    }

    private static String marker() {
        return "c" + UUID.randomUUID().toString().substring(0, 7);
    }

    /** The JSON inside a signed line, when the signature is this key's. */
    private static JsonObject open(String signed) {
        String text = signed.substring(65);
        return WebActions.signText(KEY, text).equals(signed) ? JsonParser.parseString(text).getAsJsonObject() : null;
    }

    @GameTest(template = EMPTY)
    public void a_public_line_goes_to_the_panel_signed_with_its_server_player_and_time(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<String> sent = new ArrayList<>();
        String m = marker();
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        WebChat.useForTests(() -> 1_800_000_000_123L, null, null, sent::add);
        try {
            WebChat.onChat(new ServerChatEvent(p, "hello " + m, Component.literal("hello " + m)));
            h.assertTrue(sent.size() == 1, "one line sent: " + sent);
            JsonObject o = open(sent.get(0));
            h.assertTrue(o != null, "signed with the action key, as the panel checks it");
            h.assertTrue(o.get("type").getAsString().equals("chat") && o.get("v").getAsInt() == 1 && o.get("kind").getAsString().equals("player"), "a chat line, version 1, said by a player");
            h.assertTrue(o.get("text").getAsString().equals("hello " + m) && o.get("name").getAsString().equals(p.getGameProfile().getName())
                    && o.get("player").getAsString().equals(p.getUUID().toString()), "the words, the name and the player's id: " + o);
            h.assertTrue(o.get("server").getAsString().equals(Storage.currentServerId()) && o.get("at").getAsLong() == 1_800_000_000_123L
                    && o.get("uid").getAsString().length() == 36, "this server, the time, a one-time id");
        } finally {
            WebChat.useReal();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void cancelled_lines_an_empty_line_a_switched_off_panel_or_no_key_send_nothing(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<String> sent = new ArrayList<>();
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        WebChat.useForTests(null, null, null, sent::add);
        try {
            ServerChatEvent team = new ServerChatEvent(p, "team only", Component.literal("team only"));
            team.setCanceled(true); // team chat, a muted player, someone held by the rules popup
            WebChat.onChat(team);
            h.assertTrue(sent.isEmpty(), "a line another mod cancelled never reached the others, so not the panel either");
            h.assertTrue(WebChat.capture(p, "   ") == null && sent.isEmpty(), "nothing to say: nothing sent");
            WebAgent.useForTests(false, null, null);
            h.assertTrue(WebChat.capture(p, "hello") == null && sent.isEmpty(), "the panel off on this server: nothing sent");
            WebAgent.useForTests(true, null, null);
            WebActions.useForTests(null, null, null);
            h.assertTrue(WebChat.capture(p, "hello") == null && sent.isEmpty(), "no action key: nothing sent (it would be dropped unsigned)");
            WebActions.useForTests(KEY, null, null);
            String bidi = JsonParser.parseString(WebChat.capture(p, "ab\u202Ecd\u0085e")).getAsJsonObject().get("text").getAsString();
            h.assertTrue(bidi.equals("ab cd e"), "right-to-left overrides and C1 controls out too (audit): " + bidi);
            String json = WebChat.capture(p, "a§cb\u0007 " + "x".repeat(400));
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String t = o.get("text").getAsString();
            h.assertTrue(t.length() == WebChat.MAX_TEXT && t.startsWith("a cb  x") && !t.contains("§"), "section signs and control characters out, 256 characters at most: " + t);
        } finally {
            WebChat.useReal();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void chat_lines_reach_the_shared_table_in_one_write(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "Chat-Alpha");
        s.drain();
        Storage.useForTests(s);
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        WebChat.useForTests(null, null, null, null);
        String m = marker();
        try {
            WebChat.capture(p, "one " + m);
            WebChat.capture(p, "two " + m);
            WebChat.capture(p, "three " + m);
            h.assertTrue(db.webChat().isEmpty(), "written on the storage worker, not on the server thread");
            s.drain();
            List<StorageBackend.WebChat> rows = db.webChat();
            h.assertTrue(rows.size() == 3, "all three lines in the table: " + rows.size());
            StorageBackend.WebChat r = rows.get(1);
            JsonObject o = open(r.line());
            h.assertTrue(o != null && o.get("text").getAsString().equals("two " + m) && r.text().equals("two " + m) && r.server().equals("Chat-Alpha")
                    && r.name().equals(p.getGameProfile().getName()) && r.uid().equals(o.get("uid").getAsString()), "each row: the signed line and its plain columns for searching");
            db.saveWebChat(List.of(rows.get(0)));
            h.assertTrue(db.webChat().size() == 3, "a line already stored is not stored twice");
            h.assertTrue(s.chatDropped() == 0, "nothing dropped");
            db.setFailing(true);
            WebChat.capture(p, "while down " + m);
            s.drain();
            db.setFailing(false);
            h.assertTrue(db.webChat().size() == 3, "the database away: not written");
            WebChat.capture(p, "back " + m);
            s.drain();
            h.assertTrue(db.webChat().size() == 5 && db.webChat().stream().anyMatch(x -> x.text().equals("while down " + m)), "the line kept waiting is written with the next one (audit)");
        } finally {
            Storage.useForTests(null);
            WebChat.useReal();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    private static String say(long at, String id, String rank, String voice, String text) {
        JsonObject a = new JsonObject();
        a.addProperty("v", 1);
        a.addProperty("id", id);
        a.addProperty("at", at);
        a.addProperty("by", "Ana_Mod");
        a.addProperty("rank", rank);
        a.addProperty("server", Storage.currentServerId());
        a.addProperty("action", "say");
        a.addProperty("voice", voice);
        a.addProperty("text", text);
        return WebActions.sign(KEY, a.toString());
    }

    @GameTest(template = EMPTY)
    public void say_shows_the_staff_line_with_its_hover_and_writes_the_action_log(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<Component> shown = new ArrayList<>();
        long now = System.currentTimeMillis();
        String m = marker();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        WebChat.useForTests(null, s -> List.of(p), (pl, c) -> shown.add(c), null);
        try {
            WebActions.Answer a = WebActions.handle(server, say(now, "s1-" + m, "MODERATOR", "staff", "hello " + m));
            h.assertTrue(a != null && a.ok() && a.message().equals("Shown to 1 player on " + Storage.currentServerId() + "."), "said, and how many saw it: " + a);
            h.assertTrue(shown.size() == 1, "the player got the line");
            Component line = shown.get(0);
            h.assertTrue(line.getStyle().getHoverEvent() != null, "a hover saying where it came from (D310)");
            Component tag = line.getSiblings().get(0), words = line.getSiblings().get(1);
            h.assertTrue(tag.getContents() instanceof TranslatableContents tc && tc.getKey().equals("message.bsp_server_utilities.web.staff_line")
                    && tc.getArgs().length == 1 && "Ana_Mod".equals(String.valueOf(tc.getArgs()[0])) && tc.getFallback().equals("[Staff] %s:"), "\"[Staff] Ana_Mod:\" (D305)");
            h.assertTrue(tag.getStyle().getColor() != null && tag.getStyle().getColor().getValue() == WebChat.STAFF_COLOUR, "in violet");
            h.assertTrue(words.getString().equals(" hello " + m), "then the words: " + words.getString());
            boolean logged = ActionLog.recent(server, 20, false, List.of()).stream().anyMatch(e -> e.actor().equals("Ana_Mod (panel)") && e.text().contains("chat on") && e.text().contains("hello " + m));
            h.assertTrue(logged, "in the Action Log (D306)");
        } finally {
            WebChat.useReal();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void server_lines_are_for_admins_and_owners_and_bad_lines_are_refused(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<Component> shown = new ArrayList<>();
        long now = System.currentTimeMillis();
        String m = marker();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        WebChat.useForTests(null, s -> List.of(p), (pl, c) -> shown.add(c), null);
        try {
            WebActions.Answer mod = WebActions.handle(server, say(now, "v1-" + m, "MODERATOR", "server", "Restart soon"));
            h.assertTrue(mod != null && !mod.ok() && shown.isEmpty(), "a Moderator cannot speak as [Server] (D307): " + mod);
            WebActions.Answer admin = WebActions.handle(server, say(now, "v2-" + m, "ADMIN", "server", "Restart soon"));
            h.assertTrue(admin != null && admin.ok() && shown.size() == 1, "an Admin can: " + admin);
            Component tag = shown.get(0).getSiblings().get(0);
            h.assertTrue(tag.getContents() instanceof TranslatableContents tc && tc.getKey().equals("message.bsp_server_utilities.web.server_line")
                    && tag.getStyle().getColor() != null && tag.getStyle().getColor().getValue() == WebChat.SERVER_COLOUR, "\"[Server]\" in gold");
            WebActions.Answer voice = WebActions.handle(server, say(now, "v3-" + m, "OWNER", "Vexa", "hi"));
            WebActions.Answer blank = WebActions.handle(server, say(now, "v4-" + m, "OWNER", "staff", "  "));
            WebActions.Answer longer = WebActions.handle(server, say(now, "v5-" + m, "OWNER", "staff", "x".repeat(WebChat.MAX_TEXT + 1)));
            h.assertTrue(voice != null && !voice.ok() && blank != null && !blank.ok() && longer != null && !longer.ok() && shown.size() == 1,
                    "never as a player's name (D262), not empty, not over 256 characters");
            String noName = WebActions.sign(KEY, "{\"v\":1,\"id\":\"v7-" + m + "\",\"at\":" + now + ",\"by\":\"Ana Mod!\",\"rank\":\"OWNER\",\"server\":\"" + Storage.currentServerId()
                    + "\",\"action\":\"say\",\"voice\":\"staff\",\"text\":\"hi\"}");
            WebActions.Answer badName = WebActions.handle(server, noName);
            h.assertTrue(badName != null && !badName.ok() && shown.size() == 1, "a staff name that is not a Minecraft name is refused");
            WebActions.Answer codes = WebActions.handle(server, say(now, "v6-" + m, "OWNER", "staff", "§kmagic§r"));
            h.assertTrue(codes != null && codes.ok() && shown.get(1).getSiblings().get(1).getString().equals(" kmagic r"), "no formatting codes slip in (D313)");
        } finally {
            WebChat.useReal();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }
}
