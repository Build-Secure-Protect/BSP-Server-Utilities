package com.mrgregles.bsp_server_utilities.chunks;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code /su chunks} for editors (SU-147): this server's loaded chunks by dimension, why they are loaded, the entities and
 * block entities in them, the mods holding tickets, and the five busiest chunks as lines that teleport when clicked.
 * {@code /su chunks tp <dimension> <x> <z>} (chunk coordinates, a loaded chunk inside the border) goes to the highest safe
 * block nearest the chunk's middle (owner decision D750), written to the Action Log as kind {@code chunk_tp} (D752).
 */
public final class ChunkCommands {
    /** How many busy chunks the command lists. */
    public static final int BUSIEST = 5;
    private static final String M = "message.bsp_server_utilities.chunks.";
    /** The farthest chunk coordinate inside Minecraft's largest world border (30 million blocks). */
    public static final int MAX_CHUNK = 1_875_000;

    private ChunkCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("chunks").requires(Perms::canEdit)
                .executes(c -> show(c.getSource()))
                .then(Commands.literal("tp")
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .then(Commands.argument("x", IntegerArgumentType.integer(-MAX_CHUNK, MAX_CHUNK))
                                        .then(Commands.argument("z", IntegerArgumentType.integer(-MAX_CHUNK, MAX_CHUNK))
                                                .executes(c -> teleport(c.getSource().getPlayerOrException(), DimensionArgument.getDimension(c, "dimension"),
                                                        IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")))))));
    }

    // ------------------------------------------------------------------ the readout

    private static int show(CommandSourceStack source) {
        List<ChunkCensus.Dim> dims = ChunkCensus.take(source.getServer());
        for (Component line : lines(dims, ChunkCensus.ticketsByMod(source.getServer()), Storage.currentServerId())) {
            source.sendSuccess(() -> line, false);
        }
        return dims.stream().mapToInt(ChunkCensus.Dim::chunks).sum();
    }

    /** The readout's lines, built apart from sending so a test can read them. */
    public static List<Component> lines(List<ChunkCensus.Dim> dims, Map<String, Integer> mods, String server) {
        List<Component> out = new ArrayList<>();
        int chunks = 0, ticking = 0, entities = 0, blockEntities = 0;
        for (ChunkCensus.Dim d : dims) {
            chunks += d.chunks();
            ticking += d.ticking();
            entities += d.entities();
            blockEntities += d.blockEntities();
        }
        out.add(Component.translatable(M + "header", server, chunks, ticking, entities, blockEntities).withStyle(ChatFormatting.GOLD));
        for (ChunkCensus.Dim d : dims) {
            out.add(Component.translatable(M + "dimension", d.dimension(), d.chunks(), d.ticking(), d.entities(), d.blockEntities()));
            MutableComponent why = Component.empty();
            boolean first = true;
            for (Map.Entry<ChunkCensus.Why, Integer> e : d.byWhy().entrySet()) {
                if (e.getValue() == 0) {
                    continue;
                }
                if (!first) {
                    why.append(", ");
                }
                why.append(Component.translatable(M + "why." + whyKey(e.getKey()), e.getValue()));
                first = false;
            }
            out.add(Component.translatable(M + "why", why).withStyle(ChatFormatting.GRAY));
        }
        if (mods.isEmpty()) {
            out.add(Component.translatable(M + "mods_none").withStyle(ChatFormatting.GRAY));
        } else {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(mods.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            StringBuilder s = new StringBuilder();
            for (Map.Entry<String, Integer> e : sorted) {
                s.append(s.length() == 0 ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
            }
            out.add(Component.translatable(M + "mods", s.toString()).withStyle(ChatFormatting.GRAY));
        }
        List<ChunkCensus.Placed> busy = ChunkCensus.busiest(dims, BUSIEST);
        if (busy.isEmpty()) {
            out.add(Component.translatable(M + "busiest_none"));
        } else {
            out.add(Component.translatable(M + "busiest").withStyle(ChatFormatting.GOLD));
            int n = 1;
            for (ChunkCensus.Placed p : busy) {
                out.add(busyLine(n++, p));
            }
        }
        out.add(Component.translatable(M + "note").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    private static Component busyLine(int n, ChunkCensus.Placed p) {
        ChunkCensus.Row r = p.row();
        ChunkPos c = r.pos();
        Component most = r.topCount() > 0 ? Component.translatable(M + "most", r.topCount(), r.topType()) : Component.empty();
        Component reason = switch (r.why()) {
            case PLAYER -> Component.translatable(M + "reason.player", r.who());
            case TICKET -> Component.translatable(M + "reason.ticket", r.who());
            case FORCED -> Component.translatable(M + "reason.forced");
            case SPAWN -> Component.translatable(M + "reason.spawn");
            case OTHER -> Component.translatable(M + "reason.other");
        };
        String command = command(p.dimension(), c);
        return Component.translatable(M + "row", n, shortDim(p.dimension()), c.x, c.z, c.getMiddleBlockX(), c.getMiddleBlockZ(), r.entities(), most, r.blockEntities(), reason)
                .withStyle(s -> s.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(M + "row_hover"))));
    }

    /** The command a line runs to go to a chunk. */
    public static String command(String dimension, ChunkPos c) {
        return "/su chunks tp " + dimension + " " + c.x + " " + c.z;
    }

    private static String whyKey(ChunkCensus.Why w) {
        return w.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String shortDim(String dimension) {
        return dimension.startsWith("minecraft:") ? dimension.substring("minecraft:".length()) : dimension;
    }

    // ------------------------------------------------------------------ the teleport

    /** How a teleport went. */
    public enum Outcome {
        WENT, OUTSIDE_BORDER, NOT_LOADED, NO_SPOT, STOPPED
    }

    /** The outcome, and where the player stands when they went. */
    public record Went(Outcome outcome, @Nullable BlockPos spot) {
    }

    /** {@link #go} and the line that says how it went; 1 when the player went (the command and the LAG tab's GO THERE). */
    public static int teleport(ServerPlayer player, ServerLevel level, int x, int z) {
        String dim = shortDim(level.dimension().location().toString());
        Went w = go(player, level, new ChunkPos(x, z));
        player.sendSystemMessage(switch (w.outcome()) {
            case WENT -> Component.translatable(M + "tp_done", dim, x, z, w.spot().getX(), w.spot().getY(), w.spot().getZ());
            case OUTSIDE_BORDER -> Component.translatable(M + "tp_border", dim, x, z).withStyle(ChatFormatting.RED);
            case NOT_LOADED -> Component.translatable(M + "tp_not_loaded", dim, x, z).withStyle(ChatFormatting.RED);
            case NO_SPOT -> Component.translatable(M + "tp_no_spot", dim, x, z).withStyle(ChatFormatting.RED);
            case STOPPED -> Component.translatable(M + "tp_stopped", dim, x, z).withStyle(ChatFormatting.RED);
        });
        return w.outcome() == Outcome.WENT ? 1 : 0;
    }

    /**
     * Moves the player to the chunk's safe spot and writes it to the Action Log. Only a chunk inside the world border that
     * is loaded already: a typed command never loads or generates one. Nothing is logged unless the player arrived (a
     * mod or plugin may stop a move between worlds).
     */
    public static Went go(ServerPlayer player, ServerLevel level, ChunkPos chunk) {
        if (!level.getWorldBorder().isWithinBounds(chunk)) {
            return new Went(Outcome.OUTSIDE_BORDER, null);
        }
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
            return new Went(Outcome.NOT_LOADED, null);
        }
        BlockPos spot = safeSpot(level, chunk);
        if (spot == null) {
            return new Went(Outcome.NO_SPOT, null);
        }
        player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(), player.getXRot());
        if (player.serverLevel() != level || !player.blockPosition().equals(spot)) {
            return new Went(Outcome.STOPPED, null);
        }
        ActionLog.record(player.server, ActionLog.entryFor(player, ActionLog.tracked(player).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)),
                LogEntry.CHUNK_TP, "went to chunk " + chunk.x + ", " + chunk.z + " in " + level.dimension().location() + " (" + spot.getX() + " " + spot.getY() + " " + spot.getZ() + ")"));
        return new Went(Outcome.WENT, spot);
    }

    /** How far from a block the LAG tab's TP beside it looks for a place to stand (D750), in blocks each way. */
    public static final int BESIDE_REACH = 3;

    /**
     * {@link #goBeside} and the line that says how it went; 1 when the player went (TP on a block in the LAG tab's
     * PROFILER, D750).
     */
    public static int teleportBeside(ServerPlayer player, ServerLevel level, BlockPos block) {
        String dim = shortDim(level.dimension().location().toString());
        ChunkPos c = new ChunkPos(block);
        Went w = goBeside(player, level, block);
        player.sendSystemMessage(switch (w.outcome()) {
            case WENT -> near(block, w.spot(), BESIDE_REACH)
                    ? Component.translatable(M + "tp_beside_done", block.getX(), block.getY(), block.getZ(), dim, w.spot().getX(), w.spot().getY(), w.spot().getZ())
                    : Component.translatable(M + "tp_beside_chunk", BESIDE_REACH, block.getX(), block.getY(), block.getZ(), dim, w.spot().getX(), w.spot().getY(), w.spot().getZ());
            case OUTSIDE_BORDER -> Component.translatable(M + "tp_border", dim, c.x, c.z).withStyle(ChatFormatting.RED);
            case NOT_LOADED -> Component.translatable(M + "tp_not_loaded", dim, c.x, c.z).withStyle(ChatFormatting.RED);
            case NO_SPOT -> Component.translatable(M + "tp_no_spot", dim, c.x, c.z).withStyle(ChatFormatting.RED);
            case STOPPED -> Component.translatable(M + "tp_stopped", dim, c.x, c.z).withStyle(ChatFormatting.RED);
        });
        return w.outcome() == Outcome.WENT ? 1 : 0;
    }

    /** Whether a spot is within {@code reach} blocks of a block each way. */
    public static boolean near(BlockPos block, BlockPos spot, int reach) {
        return Math.abs(spot.getX() - block.getX()) <= reach && Math.abs(spot.getY() - block.getY()) <= reach && Math.abs(spot.getZ() - block.getZ()) <= reach;
    }

    /**
     * Moves the player beside a block (D750), facing it: the nearest place to stand within {@value #BESIDE_REACH} blocks
     * ({@link #spotNear}), else the safe spot of the block's chunk ({@link #safeSpot(ServerLevel, ChunkPos)}). Only a
     * loaded chunk inside the world border; logged as {@code chunk_tp} once the player arrived, as {@link #go} is.
     */
    public static Went goBeside(ServerPlayer player, ServerLevel level, BlockPos block) {
        ChunkPos chunk = new ChunkPos(block);
        if (!level.getWorldBorder().isWithinBounds(chunk)) {
            return new Went(Outcome.OUTSIDE_BORDER, null);
        }
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
            return new Went(Outcome.NOT_LOADED, null);
        }
        BlockPos spot = spotNear(level, block, BESIDE_REACH);
        if (spot == null) {
            spot = safeSpot(level, chunk);
        }
        if (spot == null) {
            return new Went(Outcome.NO_SPOT, null);
        }
        double dx = block.getX() - spot.getX(), dz = block.getZ() - spot.getZ(), dy = block.getY() + 0.5 - (spot.getY() + player.getEyeHeight());
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = flat < 1e-6 ? player.getYRot() : (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(flat, 1e-6)));
        player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, pitch);
        if (player.serverLevel() != level || !player.blockPosition().equals(spot)) {
            return new Went(Outcome.STOPPED, null);
        }
        ActionLog.record(player.server, ActionLog.entryFor(player, ActionLog.tracked(player).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.CHUNK_TP,
                "went beside " + block.getX() + " " + block.getY() + " " + block.getZ() + " in chunk " + chunk.x + ", " + chunk.z + " in " + level.dimension().location()
                        + " (" + spot.getX() + " " + spot.getY() + " " + spot.getZ() + ")"));
        return new Went(Outcome.WENT, spot);
    }

    /**
     * The nearest place to stand within {@code reach} blocks of a block each way (D750): the same level as the block
     * first among places as near, never the block's own place. Only places in loaded chunks inside the world border are
     * read, so nothing is loaded. Null when there is none.
     */
    @Nullable
    public static BlockPos spotNear(ServerLevel level, BlockPos block, int reach) {
        List<BlockPos> tries = new ArrayList<>();
        int low = level.getMinBuildHeight() + 1, high = level.getMaxBuildHeight() - 2;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = block.getX() + dx, z = block.getZ() + dz;
                if (!level.getWorldBorder().isWithinBounds(x + 0.5, z + 0.5) || level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                    continue;
                }
                for (int dy = -reach; dy <= reach; dy++) {
                    int y = block.getY() + dy;
                    if ((dx != 0 || dy != 0 || dz != 0) && y >= low && y <= high) {
                        tries.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        tries.sort(Comparator.comparingInt((BlockPos p) -> (int) p.distSqr(block)).thenComparingInt(p -> Math.abs(p.getY() - block.getY()))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (BlockPos p : tries) {
            if (standable(level, p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Where to stand in a loaded chunk (D750), the columns tried nearest its middle first. With an open sky: dry ground at
     * the surface (up to three blocks under the top), else the top of water, else the highest safe place lower down (a
     * cave). With a ceiling: the highest safe place under the roof. Null when there is none (an empty End chunk). Reads
     * only the chunk's own blocks, so nothing is loaded.
     */
    @Nullable
    public static BlockPos safeSpot(ServerLevel level, ChunkPos chunk) {
        return safeSpot(level, chunk, (x, z) -> level.getWorldBorder().isWithinBounds(x + 0.5, z + 0.5));
    }

    /** As {@link #safeSpot(ServerLevel, ChunkPos)}, with only the columns {@code inside} accepts (the world border; a test's own). */
    @Nullable
    public static BlockPos safeSpot(ServerLevel level, ChunkPos chunk, java.util.function.BiPredicate<Integer, Integer> inside) {
        List<int[]> columns = new ArrayList<>();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int cx = chunk.getMinBlockX() + dx, cz = chunk.getMinBlockZ() + dz;
                if (inside.test(cx, cz)) {
                    columns.add(new int[]{cx, cz}); // a chunk the border cuts through: only its columns inside (review of SU-147)
                }
            }
        }
        int mx = chunk.getMiddleBlockX(), mz = chunk.getMiddleBlockZ();
        columns.sort(Comparator.comparingInt((int[] a) -> (a[0] - mx) * (a[0] - mx) + (a[1] - mz) * (a[1] - mz)).thenComparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        int bottom = level.getMinBuildHeight() + 1;
        if (level.dimensionType().hasCeiling()) {
            int roof = Math.min(level.getMinBuildHeight() + level.getLogicalHeight(), level.getMaxBuildHeight()) - 2;
            return firstDown(level, columns, c -> roof, bottom, false);
        }
        java.util.function.ToIntFunction<int[]> top = c -> Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0], c[1]), level.getMaxBuildHeight() - 2);
        BlockPos surface = firstDown(level, columns, top, -SURFACE_DEPTH, false);
        if (surface == null) {
            surface = firstDown(level, columns, top, 0, true);
        }
        return surface != null ? surface : firstDown(level, columns, top, bottom, false);
    }

    /** How far under a column's top the surface search looks. */
    private static final int SURFACE_DEPTH = 3;

    /**
     * The first safe place going down each column in turn: from {@code top} to {@code bottom} (a negative {@code bottom}
     * means that many blocks under the top); {@code onWater} takes the top of water instead of ground.
     */
    @Nullable
    private static BlockPos firstDown(ServerLevel level, List<int[]> columns, java.util.function.ToIntFunction<int[]> top, int bottom, boolean onWater) {
        int floor = level.getMinBuildHeight() + 1;
        for (int[] col : columns) {
            int from = top.applyAsInt(col);
            int to = Math.max(floor, bottom < 0 ? from + bottom : bottom);
            if (onWater) {
                to = from;
            }
            for (int y = from; y >= to; y--) {
                BlockPos pos = new BlockPos(col[0], y, col[1]);
                if (onWater ? onWater(level, pos) : standable(level, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    /** Solid ground below, room for a player, nothing harmful and no portal. */
    public static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.isFaceSturdy(level, below, Direction.UP) || ground.getFluidState().is(FluidTags.LAVA) || ground.is(Blocks.MAGMA_BLOCK)) {
            return false;
        }
        return clear(level, feet) && clear(level, feet.above());
    }

    /** Water right below (to swim in), room above it. */
    static boolean onWater(ServerLevel level, BlockPos feet) {
        return level.getFluidState(feet.below()).is(FluidTags.WATER) && clear(level, feet) && clear(level, feet.above());
    }

    private static boolean clear(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty() && !s.is(BlockTags.FIRE) && !s.is(BlockTags.PORTALS)
                && !s.is(Blocks.SWEET_BERRY_BUSH) && !s.is(Blocks.POWDER_SNOW) && !s.is(Blocks.COBWEB);
    }
}
