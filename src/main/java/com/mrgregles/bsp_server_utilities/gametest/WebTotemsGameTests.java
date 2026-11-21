package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.steals.StealRecorder;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import com.mrgregles.bsp_server_utilities.web.WebTotems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The TOTEMS page, the game server's part (SU-96, owner decisions D614, D618, D620): {@code totem_board} answers
 * BSP-Core's board and the steals running to any staff rank, says plainly when BSP-Core is missing or fails, and the
 * snapshot says which servers can answer; every steal that starts or ends here is told to the panel at once, signed,
 * the end with the same uid as its row; nothing is told while the panel is off here; the real BSP-Core answers.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebTotemsGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 5 + 3);
        }
    }

    private static String action(String id, long at, String rank, String action) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Mod\",\"rank\":\"" + rank + "\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"}");
    }

    /** The JSON of a message signed as the game servers sign for the panel (hex HMAC, a dot, the JSON), or null when the signature does not hold. */
    private static JsonObject opened(String signed) throws Exception {
        int dot = signed.indexOf('.');
        String json = signed.substring(dot + 1);
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(KEY, "HmacSHA256"));
        String want = HexFormat.of().formatHex(m.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        return want.equals(signed.substring(0, dot)) ? JsonParser.parseString(json).getAsJsonObject() : null;
    }

    static final class FakeScores implements BspCore.Scores {
        final List<BspCore.BoardLine> board = new ArrayList<>();
        final List<BspCore.StealNow> stealing = new ArrayList<>();
        boolean broken;

        @Override
        public List<BspCore.BoardLine> board(MinecraftServer server) {
            if (broken) {
                throw new IllegalStateException("broken (test)");
            }
            return board;
        }

        @Override
        public List<BspCore.StealNow> stealing(MinecraftServer server) {
            return stealing;
        }
    }

    @GameTest(template = EMPTY)
    public void the_board_and_the_steals_running_are_answered_to_every_staff_rank(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        String m = "t" + Integer.toHexString(new java.util.Random().nextInt());
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            BspCore.useScoresForTests(null);
            WebActions.Answer none = WebActions.handle(server, action("n-" + m, now, "MODERATOR", "totem_board"));
            h.assertTrue(none != null && !none.ok() && none.message().contains("BSP-Core is not installed"), "without BSP-Core: said plainly: " + (none == null ? null : none.message()));
            h.assertTrue(!WebSnapshot.build(server, "alpha", List.of(), now).get("totems").getAsBoolean(), "the snapshot: no board here");

            FakeScores sc = new FakeScores();
            BspCore.useScoresForTests(sc);
            h.assertTrue(WebSnapshot.build(server, "alpha", List.of(), now).get("totems").getAsBoolean(), "the snapshot says the board can be read here");
            UUID vexa = UUID.randomUUID(), wren = UUID.randomUUID();
            sc.board.add(new BspCore.BoardLine(vexa, "Vexa", 1, 31, 5, 4));
            sc.board.add(new BspCore.BoardLine(wren, "Wren_88", 2, 7, 1, 5));
            sc.stealing.add(new BspCore.StealNow("frostwarf", false, "minecraft:the_nether", 120, 64, -880, wren, vexa, "Vexa", 0.33333f));
            sc.stealing.add(new BspCore.StealNow("core-name", true, "minecraft:overworld", 5, 70, 5, null, wren, "Wren_88", 1f));
            sc.board.add(new BspCore.BoardLine(null, "Broken", 3, 1, 1, 1)); // a bad line from BSP-Core: left out, never failing the rest (audit)
            sc.stealing.add(new BspCore.StealNow("frostwarf", false, "minecraft:overworld", 0, 0, 0, null, null, "", 0.5f));
            for (String rank : List.of("MODERATOR", "ADMIN", "OWNER")) {
                WebActions.Answer a = WebActions.handle(server, action(rank + "-" + m, now, rank, "totem_board"));
                h.assertTrue(a != null && a.ok() && a.message().equals("2 on the board, 2 stealing now"), rank + " reads the board (D614), the bad entries left out: " + (a == null ? null : a.message()));
            }
            JsonObject d = WebActions.handle(server, action("d-" + m, now, "MODERATOR", "totem_board")).data().getAsJsonObject();
            JsonArray board = d.getAsJsonArray("board");
            JsonObject first = board.get(0).getAsJsonObject();
            h.assertTrue(board.size() == 2 && first.get("id").getAsString().equals(vexa.toString()) && first.get("name").getAsString().equals("Vexa")
                    && first.get("rank").getAsInt() == 1 && first.get("points").getAsInt() == 31 && first.get("totems").getAsInt() == 5 && first.get("tier").getAsInt() == 4,
                    "a board line: " + first);
            h.assertTrue(d.get("lines").getAsInt() == 2 && d.get("v").getAsInt() == 1 && d.get("server").getAsString().equals(Storage.currentServerId()), "the answer's head: " + d);
            JsonObject s0 = d.getAsJsonArray("stealing").get(0).getAsJsonObject(), s1 = d.getAsJsonArray("stealing").get(1).getAsJsonObject();
            h.assertTrue(s0.get("server").getAsString().equals("frostwarf") && s0.get("dim").getAsString().equals("minecraft:the_nether") && s0.get("x").getAsInt() == 120
                    && s0.get("z").getAsInt() == -880 && s0.get("thief").getAsString().equals(vexa.toString()) && s0.get("owner").getAsString().equals(wren.toString())
                    && s0.get("left").getAsDouble() == 0.333, "a steal running on another server: " + s0);
            h.assertTrue(s1.get("owner").getAsString().isEmpty() && s1.get("ownerName").getAsString().isEmpty() && s1.get("left").getAsDouble() == 1.0, "an unclaimed totem: " + s1);
            h.assertTrue(s1.get("server").getAsString().equals(Storage.currentServerId()), "this server's own steal under this mod's server name, as its rows are: " + s1);
            h.assertTrue(d.get("ready").getAsBoolean(), "a board with players on it is ready");
            sc.board.clear();
            JsonObject empty = WebActions.handle(server, action("e-" + m, now, "MODERATOR", "totem_board")).data().getAsJsonObject();
            h.assertTrue(empty.get("ready").getAsBoolean() == (server.getTickCount() >= 2 * 30 * 20),
                    "an empty board is ready only after twice BSP-Core's 30 s refresh since the start (ticks " + server.getTickCount() + ")");

            sc.broken = true;
            WebActions.Answer broken = WebActions.handle(server, action("b-" + m, now, "OWNER", "totem_board"));
            h.assertTrue(!broken.ok() && broken.message().contains("BSP-Core answered with an error"), "BSP-Core failing is said as such: " + broken.message());
        } finally {
            BspCore.useRealScores();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_steal_start_and_end_are_told_to_the_panel_signed(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage s = Storage.startWith(server, db, "alpha");
        s.drain();
        List<String> sent = new ArrayList<>();
        WebActions.useForTests(KEY, System::currentTimeMillis, null);
        WebAgent.useForTests(true, null, null);
        WebTotems.sentForTests(sent::add);
        StealRecorder.resetForTests();
        BspCore.useTotemInfoForTests((srv, where) -> new BspCore.TotemInfo(StealRecorder.SOURCE_TOTEM, 2, 2));
        Storage before = Storage.active();
        Storage.useForTests(s);
        try {
            GlobalPos where = GlobalPos.of(h.getLevel().dimension(), h.absolutePos(new BlockPos(1, 2, 1)));
            UUID owner = UUID.randomUUID(), thief = UUID.randomUUID();
            MinecraftForge.EVENT_BUS.post(new com.mrgregles.bsp_core.api.event.TotemEvent.StealStarted(server, where, owner, thief, "Quickhand", 40));
            MinecraftForge.EVENT_BUS.post(new com.mrgregles.bsp_core.api.event.TotemEvent.StealEnded(server, where, owner, thief, "Quickhand", true, ""));
            s.drain();
            h.assertTrue(sent.size() == 2, "two messages: " + sent.size());
            JsonObject start = opened(sent.get(0)), end = opened(sent.get(1));
            h.assertTrue(start != null && end != null, "both signed with the action key");
            h.assertTrue(start.get("type").getAsString().equals("steal") && start.get("event").getAsString().equals("start") && start.get("seconds").getAsInt() == 40 && start.get("tier").getAsInt() == 2 && start.get("source").getAsString().equals("totem")
                    && start.get("thief").getAsString().equals(thief.toString()) && start.get("owner").getAsString().equals(owner.toString())
                    && start.get("x").getAsInt() == where.pos().getX() && start.get("dim").getAsString().equals("minecraft:overworld"), "the start: " + start);
            StorageBackend.Steal row = db.loadSteals(0, 5).get(0);
            h.assertTrue(end.get("event").getAsString().equals("end") && end.get("uid").getAsString().equals(row.uid()) && end.get("success").getAsBoolean()
                    && end.get("tier").getAsInt() == 2 && end.get("points").getAsInt() == 2 && end.get("started").getAsLong() == row.startedMs()
                    && end.get("ended").getAsLong() == row.endedMs() && end.get("seconds").getAsInt() == 40 && end.get("server").getAsString().equals("alpha"),
                    "the end, the same as its row: " + end);
            h.assertTrue(sent.get(1).equals(row.line()), "the end sent is the row's own signed copy, word for word");

            WebAgent.useForTests(false, null, null);
            sent.clear();
            StealRecorder.ended(server, where, owner, thief, "Quickhand", false, "message.bsp_core.steal.failed_left");
            s.drain();
            h.assertTrue(sent.isEmpty() && db.stealCount() == 2, "with the panel off here nothing is told, and the row is still written");
        } finally {
            Storage.useForTests(before); // as it was found (audit)
            s.close();
            WebTotems.sentForTests(null);
            BspCore.useTotemInfoForTests(null);
            StealRecorder.resetForTests();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_real_bsp_core_board_answers_through_the_adapter(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            BspCore.useRealScores();
            h.assertTrue(BspCore.scores() != null, "BSP-Core 1.19 in the test server has the board");
            WebActions.Answer a = WebActions.handle(server, action("r-" + Integer.toHexString(new java.util.Random().nextInt()), now, "MODERATOR", "totem_board"));
            JsonObject d = a.data() == null ? null : a.data().getAsJsonObject();
            h.assertTrue(a.ok() && d != null && d.get("board").isJsonArray() && d.get("stealing").isJsonArray() && d.get("lines").getAsInt() == d.getAsJsonArray("board").size(),
                    "the real board and steals answer: " + a.message());
        } finally {
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }
}
