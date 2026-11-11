package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspTeleport;
import com.mrgregles.bsp_server_utilities.compat.InventorySync;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import com.mrgregles.bsp_server_utilities.web.WebTeleports;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The staff panel's teleports (SU-43; D344 to D349, D366): the places through BSP-Teleport, a move here or to another
 * server with the message, "on the ground" on this server only, a player to go to read where they stand now, refusals
 * (not here, still arriving, not a place), the Action Log; the snapshot's teleport and arriving marks; a Destination read
 * through its accessors.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebTeleportGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 5);
        }
    }

    private static String action(String id, long at, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Mod\",\"rank\":\"MODERATOR\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    record Move(String player, BspTeleport.Place to, String message) {
    }

    static final class FakeTp implements BspTeleport.Api {
        final List<Move> moves = new ArrayList<>();
        boolean works = true;

        @Override
        public String serverName() {
            return "Moongrove";
        }

        @Override
        public List<BspTeleport.Warp> warps() {
            return List.of(new BspTeleport.Warp("mine", "Frostwarf", "minecraft:overworld", 1500, 40, -600, false, false),
                    new BspTeleport.Warp("vault", "Frostwarf", "minecraft:overworld", 0, 5, 0, true, true));
        }

        @Override
        public Optional<BspTeleport.Place> spawn(String server) {
            return server.equalsIgnoreCase("Frostwarf") ? Optional.of(new BspTeleport.Place("Frostwarf", "minecraft:overworld", 0, 70, 0)) : Optional.empty();
        }

        @Override
        public boolean teleport(ServerPlayer player, BspTeleport.Place to, String message) {
            moves.add(new Move(player.getGameProfile().getName(), to, message));
            return works;
        }
    }

    public record FakeDest(String server, String world, double x, double y, double z, float yaw, float pitch, String message, int protect) {
    }

    @GameTest(template = EMPTY)
    public void players_are_moved_through_bsp_teleport_with_the_message_and_logged(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer vexa = TestPlayers.mockServerPlayer(h), kestrel = TestPlayers.mockServerPlayer(h);
        kestrel.setPos(100.5, 70, -200.5);
        long now = System.currentTimeMillis();
        String m = "t" + Integer.toHexString(new java.util.Random().nextInt());
        FakeTp tp = new FakeTp();
        List<UUID> arriving = new ArrayList<>();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        WebTeleports.useForTests((s, id) -> id.equals(vexa.getUUID()) ? vexa : id.equals(kestrel.getUUID()) ? kestrel : null);
        InventorySync.useSyncingForTests(arriving::contains);
        String who = ",\"player\":\"" + vexa.getUUID() + "\"";
        try {
            BspTeleport.useForTests(null);
            WebActions.Answer none = WebActions.handle(server, action("a0-" + m, now, "tp", who + ",\"to\":{\"world\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":1}"));
            h.assertTrue(!none.ok() && none.message().contains("BSP-Teleport"), "without BSP-Teleport: said plainly");
            BspTeleport.useForTests(tp);

            WebActions.Answer places = WebActions.handle(server, action("a1-" + m, now, "tp_places", ",\"servers\":[\"Frostwarf\",\"Stormfall\"]"));
            JsonObject d = places.data().getAsJsonObject();
            h.assertTrue(places.ok() && d.get("tpName").getAsString().equals("Moongrove") && d.getAsJsonArray("warps").size() == 2
                    && d.getAsJsonArray("warps").get(1).getAsJsonObject().get("portal").getAsBoolean(), "the warps, portal ones marked: " + d);
            JsonArray spawns = d.getAsJsonArray("spawns");
            h.assertTrue(spawns.size() == 1 && spawns.get(0).getAsJsonObject().get("server").getAsString().equals("Frostwarf"), "the spawns known: " + spawns);

            WebActions.Answer away = WebActions.handle(server, action("a2-" + m, now, "tp", ",\"player\":\"" + UUID.randomUUID() + "\",\"to\":{\"world\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":1}"));
            h.assertTrue(!away.ok() && away.message().contains("not on") && tp.moves.isEmpty(), "not on this server: refused");
            arriving.add(vexa.getUUID());
            WebActions.Answer arr = WebActions.handle(server, action("a3-" + m, now, "tp", who + ",\"to\":{\"world\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":1}"));
            h.assertTrue(!arr.ok() && arr.message().contains("still arriving") && tp.moves.isEmpty(), "still arriving: refused (D366)");
            arriving.clear();
            WebActions.Answer bad = WebActions.handle(server, action("a4-" + m, now, "tp", who + ",\"to\":{\"world\":\"not a world\",\"x\":1,\"y\":64,\"z\":1}"));
            h.assertTrue(!bad.ok() && tp.moves.isEmpty(), "not a place: refused");

            WebActions.Answer here = WebActions.handle(server, action("a5-" + m, now, "tp", who + ",\"name\":\"Vexa\",\"to\":{\"server\":\"moongrove\",\"world\":\"minecraft:overworld\",\"x\":10.5,\"y\":64,\"z\":-3.5,\"what\":\"10, 64, -3 (" + m + ")\"}"));
            Move mv = tp.moves.get(0);
            h.assertTrue(here.ok() && mv.to().server().isEmpty() && mv.to().x() == 10.5 && mv.to().z() == -3.5 && mv.message().equals("You were moved by staff."),
                    "this server's own name in any case: a move here, with the message: " + mv);
            h.assertTrue(ActionLog.recent(server, 30, false, List.of()).stream().anyMatch(e -> e.actor().equals("Ana_Mod (panel)") && e.text().contains("(" + m + ")")), "in the Action Log");

            WebActions.Answer far = WebActions.handle(server, action("a6-" + m, now, "tp", who + ",\"to\":{\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":1500,\"y\":40,\"z\":-600,\"what\":\"warp mine\"}"));
            h.assertTrue(far.ok() && tp.moves.get(1).to().server().equals("Frostwarf") && far.message().contains("warp mine (Frostwarf)"), "another server: through BSP-Teleport by name: " + far.message());
            WebActions.Answer groundFar = WebActions.handle(server, action("a7-" + m, now, "tp", who + ",\"to\":{\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":1,\"y\":0,\"z\":1,\"ground\":true}"));
            h.assertTrue(!groundFar.ok() && groundFar.message().contains("own server") && tp.moves.size() == 2, "on the ground on another server: refused (D349)");
            int x = h.absolutePos(new net.minecraft.core.BlockPos(1, 0, 1)).getX(), z = h.absolutePos(new net.minecraft.core.BlockPos(1, 0, 1)).getZ();
            WebActions.Answer ground = WebActions.handle(server, action("a8-" + m, now, "tp", who + ",\"to\":{\"world\":\"minecraft:overworld\",\"x\":" + x + ",\"y\":0,\"z\":" + z + ",\"ground\":true}"));
            int top = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            h.assertTrue(ground.ok() && tp.moves.get(2).to().y() == top, "on the ground here: the top block (" + top + "): " + tp.moves.get(2));

            WebActions.Answer toKestrel = WebActions.handle(server, action("a9-" + m, now, "tp", who + ",\"target\":\"" + kestrel.getUUID() + "\",\"to\":{\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":0,\"y\":0,\"z\":0}"));
            Move k = tp.moves.get(3);
            h.assertTrue(toKestrel.ok() && k.to().server().isEmpty() && k.to().x() == 100.5 && k.to().z() == -200.5, "a player on this server: where they stand now: " + k);
            tp.works = false;
            WebActions.Answer refused = WebActions.handle(server, action("ab-" + m, now, "tp", who + ",\"to\":{\"world\":\"minecraft:overworld\",\"x\":1,\"y\":64,\"z\":1}"));
            h.assertTrue(!refused.ok() && refused.message().contains("could not start"), "BSP-Teleport saying no: said so");

            JsonObject snap = WebSnapshot.build(server, "alpha", List.of(vexa), now);
            h.assertTrue(snap.get("tp").getAsBoolean() && snap.get("tpName").getAsString().equals("Moongrove"), "the snapshot says teleports work here, and the name");
            h.assertTrue(!snap.getAsJsonArray("players").get(0).getAsJsonObject().has("arriving"), "not arriving: no mark");
            arriving.add(vexa.getUUID());
            h.assertTrue(WebSnapshot.player(server, vexa).get("arriving").getAsBoolean(), "arriving: marked (D366)");

            BspTeleport.Place p = BspTeleport.place(new FakeDest("Frostwarf", "minecraft:the_nether", 1, 2, 3, 0, 0, "hi", -1));
            h.assertTrue(p.server().equals("Frostwarf") && p.world().equals("minecraft:the_nether") && p.y() == 2, "a Destination read through its accessors: " + p);
        } finally {
            BspTeleport.useForTests(null);
            InventorySync.useSyncingForTests(null);
            WebTeleports.useForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void on_the_ground_stays_under_the_nether_roof_and_refuses_the_void(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        net.minecraft.server.level.ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER), end = server.getLevel(net.minecraft.world.level.Level.END);
        h.assertTrue(nether != null && end != null, "the Nether and the End are loaded");
        int x = 3000 + new java.util.Random().nextInt(2000), z = 3000 + new java.util.Random().nextInt(2000);
        // a cave from y 40 up to under the roof, on netherrack: the heightmap alone gives the roof (128) (audit)
        for (int y = 40; y <= 121; y++) {
            nether.setBlock(new net.minecraft.core.BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        nether.setBlock(new net.minecraft.core.BlockPos(x, 39, z), net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState(), 2);
        int g = WebTeleports.ground(nether, x, z);
        h.assertTrue(g == 40, "on the netherrack under the roof: " + g);
        h.assertTrue(WebTeleports.ground(end, 600, 600) == Integer.MIN_VALUE, "the End's void between the islands: no ground (never the bottom of the world)");
        int top = WebTeleports.ground(h.getLevel(), x, z), map = h.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        h.assertTrue(map > h.getLevel().getMinBuildHeight() ? top == map : top == Integer.MIN_VALUE,
                "the overworld: the top block, as before, or no ground in an empty column: " + top + " / " + map);
        h.succeed();
    }
}
