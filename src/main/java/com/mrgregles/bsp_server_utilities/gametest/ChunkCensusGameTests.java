package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCommands;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /su chunks} (SU-147): the census finds a chunk a mod's ticket holds, and the chunks beside it, with the right
 * reason and ticking state; it counts entities and block entities; the busiest list orders and limits; a line runs the
 * teleport; the teleport lands on a safe block and is written to the Action Log once (D750, D752). Far chunks are loaded
 * with this mod's own ticket id, each test its own, and released at the end.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class ChunkCensusGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static ChunkPos far(GameTestHelper h, int chunksEast) {
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        return new ChunkPos(base.x + chunksEast, base.z);
    }

    private static BlockPos owner(ChunkPos c) {
        return new BlockPos(c.getMiddleBlockX(), 0, c.getMiddleBlockZ());
    }

    private static void hold(ServerLevel level, ChunkPos c, boolean add) {
        ForgeChunkManager.forceChunk(level, BSPServerUtilities.MODID, owner(c), c.x, c.z, add, false);
    }

    private static Optional<ChunkCensus.Row> row(ServerLevel level, ChunkPos c) {
        return ChunkCensus.of(level).rows().stream().filter(r -> r.chunk() == c.toLong()).findFirst();
    }

    @GameTest(template = EMPTY, timeoutTicks = 800)
    public static void censusFindsATicketAndTheChunksBesideIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = far(h, 220);
        ChunkPos next = new ChunkPos(c.x + 1, c.z), border = new ChunkPos(c.x + 2, c.z), beyond = new ChunkPos(c.x + 4, c.z);
        hold(level, c, false); // a ticket left by a run that timed out (the test world persists)
        hold(level, c, true);
        h.succeedWhen(() -> {
            Optional<ChunkCensus.Row> held = row(level, c), beside = row(level, next), edge = row(level, border);
            h.assertTrue(held.isPresent() && beside.isPresent() && edge.isPresent(), "the held chunk and the two beside it are loaded");
            h.assertTrue(held.get().why() == ChunkCensus.Why.TICKET && held.get().who().equals(BSPServerUtilities.MODID), "the held chunk is put down to this mod's ticket");
            h.assertTrue(edge.get().why() == ChunkCensus.Why.TICKET, "a chunk two away is put down to the ticket too");
            h.assertTrue(held.get().ticking() && beside.get().ticking(), "the held chunk and the one beside it tick");
            h.assertTrue(!edge.get().ticking(), "the chunk two away is loaded as a border and does not tick");
            h.assertTrue(row(level, beyond).isEmpty(), "four away is not loaded by the ticket");
            h.assertTrue(ChunkCensus.ticketsByMod(level.getServer()).getOrDefault(BSPServerUtilities.MODID, 0) >= 1, "the mod's tickets are counted");
            hold(level, c, false);
        });
    }

    @GameTest(template = EMPTY)
    public static void censusCountsEntitiesAndBlockEntities(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos rel = new BlockPos(2, 2, 2);
        ChunkPos c = new ChunkPos(h.absolutePos(rel));
        int entitiesBefore = row(level, c).map(ChunkCensus.Row::entities).orElse(0);
        int blockEntitiesBefore = row(level, c).map(ChunkCensus.Row::blockEntities).orElse(0);
        h.setBlock(rel, Blocks.CHEST);
        h.setBlock(rel.above(), Blocks.FURNACE); // one column, so one chunk
        h.setBlock(rel.above(2), Blocks.HOPPER);
        for (int i = 0; i < 4; i++) {
            h.spawn(EntityType.ARMOR_STAND, rel.above(3));
        }
        ChunkCensus.Row r = row(level, c).orElseThrow();
        h.assertTrue(r.entities() >= entitiesBefore + 4, "four armour stands counted: " + r.entities() + " from " + entitiesBefore);
        h.assertTrue(r.blockEntities() >= blockEntitiesBefore + 3, "three block entities counted: " + r.blockEntities() + " from " + blockEntitiesBefore);
        h.assertTrue(r.topCount() >= 4, "the most common type has at least the four stands");
        h.assertTrue(r.why() != ChunkCensus.Why.OTHER, "the test's chunk is held by the test runner's /forceload or the spawn area, not 'other'");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void busiestOrdersLimitsAndLinksToTheTeleport(GameTestHelper h) {
        List<ChunkCensus.Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            rows.add(new ChunkCensus.Row(ChunkPos.asLong(i, -i), ChunkCensus.Why.OTHER, "", i % 4, i, true, i % 4 > 0 ? "minecraft:zombie" : "", i % 4));
        }
        rows.add(new ChunkCensus.Row(ChunkPos.asLong(50, 50), ChunkCensus.Why.OTHER, "", 0, 0, false, "", 0));
        List<ChunkCensus.Dim> dims = List.of(new ChunkCensus.Dim("minecraft:overworld", rows));
        List<ChunkCensus.Placed> top = ChunkCensus.busiest(dims, ChunkCommands.BUSIEST);
        h.assertTrue(top.size() == 5, "five listed");
        // entities 3, 3, 2, 2, 1 with block entities 7, 3, 6, 2, 5
        int[] blockEntities = top.stream().mapToInt(p -> p.row().blockEntities()).toArray();
        h.assertTrue(java.util.Arrays.equals(blockEntities, new int[]{7, 3, 6, 2, 5}), "most entities first, block entities break ties: " + java.util.Arrays.toString(blockEntities));
        h.assertTrue(top.stream().noneMatch(p -> p.row().chunk() == ChunkPos.asLong(50, 50)), "an empty chunk is never busy");

        List<Component> lines = ChunkCommands.lines(dims, Map.of("mekanism", 3, "bsp_core", 1), "test");
        String expected = ChunkCommands.command("minecraft:overworld", new ChunkPos(7, -7));
        boolean linked = lines.stream().anyMatch(l -> {
            ClickEvent e = l.getStyle().getClickEvent();
            return e != null && e.getAction() == ClickEvent.Action.RUN_COMMAND && e.getValue().equals(expected);
        });
        h.assertTrue(linked, "the busiest chunk's line runs " + expected);
        h.assertTrue(LogText.selfLogged("su chunks tp minecraft:overworld 7 -7"), "the teleport is not logged twice as a command");
        h.assertTrue(!LogText.selfLogged("su chunks"), "the readout itself is logged like any command");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void standableNeedsGroundAndRoom(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos floor = new BlockPos(5, 1, 5);
        h.setBlock(floor, Blocks.STONE);
        h.assertTrue(ChunkCommands.standable(level, h.absolutePos(floor.above())), "stone below and air above is safe");
        h.setBlock(floor.above(2), Blocks.STONE);
        h.assertTrue(!ChunkCommands.standable(level, h.absolutePos(floor.above())), "no room for the head");
        h.setBlock(floor.above(2), Blocks.AIR);
        h.setBlock(floor.above(), Blocks.LAVA);
        h.assertTrue(!ChunkCommands.standable(level, h.absolutePos(floor.above())), "not in lava");
        h.setBlock(floor.above(), Blocks.AIR);
        h.setBlock(floor, Blocks.MAGMA_BLOCK);
        h.assertTrue(!ChunkCommands.standable(level, h.absolutePos(floor.above())), "not on magma");
        h.setBlock(floor, Blocks.STONE);
        h.setBlock(floor.above(), Blocks.END_GATEWAY);
        h.assertTrue(!ChunkCommands.standable(level, h.absolutePos(floor.above())), "not in a portal");
        h.setBlock(floor.above(), Blocks.AIR);
        h.setBlock(floor, Blocks.AIR);
        h.assertTrue(!ChunkCommands.standable(level, h.absolutePos(floor.above())), "not on nothing");
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 800)
    public static void teleportLandsSafelyAndIsLoggedOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = far(h, 260);
        hold(level, c, false); // a ticket left by a run that timed out
        hold(level, c, true);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(c.x, c.z) != null, "the chunk is loaded"))
                .thenExecute(() -> {
                    ServerPlayer admin = TestPlayers.mockServerPlayer(h);
                    long start = System.currentTimeMillis();
                    try {
                        ChunkCommands.Went w = ChunkCommands.go(admin, level, c);
                        h.assertTrue(w.outcome() == ChunkCommands.Outcome.WENT && w.spot() != null, "a safe place is found in generated land: " + w.outcome());
                        h.assertTrue(new ChunkPos(w.spot()).equals(c), "the place is in the chunk");
                        h.assertTrue(ChunkCommands.standable(level, w.spot()) || level.getFluidState(w.spot().below()).is(net.minecraft.tags.FluidTags.WATER), "the place is safe");
                        h.assertTrue(admin.blockPosition().equals(w.spot()), "the admin stands there");
                        String text = "went to chunk " + c.x + ", " + c.z + " in minecraft:overworld";
                        long logged = ActionLog.recent(level.getServer(), 50, false, List.of(LogEntry.CHUNK_TP)).stream()
                                .filter(e -> e.atMs() >= start && e.text().startsWith(text) && e.actorId().equals(admin.getUUID().toString())).count();
                        h.assertTrue(logged == 1, "the teleport is in the Action Log as chunk_tp exactly once: " + logged);
                    } finally {
                        hold(level, c, false);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public static void safeSpotKeepsToColumnsInsideTheBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(2, 2, 2)));
        int edge = c.getMiddleBlockX(); // a border through the chunk's middle: only the columns west of it are inside
        BlockPos spot = ChunkCommands.safeSpot(level, c, (x, z) -> x < edge);
        h.assertTrue(spot == null || spot.getX() < edge, "never a column past the border: " + spot);
        h.assertTrue(ChunkCommands.safeSpot(level, c, (x, z) -> false) == null, "no column inside: no place");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void teleportRefusesUnloadedAndOutsideTheBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        BlockPos was = admin.blockPosition();
        long start = System.currentTimeMillis();
        ChunkPos unloaded = far(h, 90_000); // well over a million blocks away: never loaded
        h.assertTrue(level.getChunkSource().getChunkNow(unloaded.x, unloaded.z) == null, "the far chunk starts unloaded");
        h.assertTrue(ChunkCommands.go(admin, level, unloaded).outcome() == ChunkCommands.Outcome.NOT_LOADED, "an unloaded chunk is refused");
        h.assertTrue(level.getChunkSource().getChunkNow(unloaded.x, unloaded.z) == null, "and it is not loaded by asking");
        ChunkPos past = new ChunkPos(ChunkCommands.MAX_CHUNK, 0);
        h.assertTrue(ChunkCommands.go(admin, level, past).outcome() == ChunkCommands.Outcome.OUTSIDE_BORDER, "a chunk past the world border is refused");
        h.assertTrue(admin.blockPosition().equals(was), "the player did not move");
        h.assertTrue(ActionLog.recent(level.getServer(), 50, false, List.of(LogEntry.CHUNK_TP)).stream()
                .noneMatch(e -> e.atMs() >= start && e.actorId().equals(admin.getUUID().toString())), "nothing is logged");
        h.succeed();
    }
}
