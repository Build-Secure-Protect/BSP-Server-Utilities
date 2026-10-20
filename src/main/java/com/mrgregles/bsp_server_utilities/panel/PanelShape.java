package com.mrgregles.bsp_server_utilities.panel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * How Display Panels join (owner decision D15), the way BSP-Core's Score Screen does: blocks of the same facing on one
 * wall make one panel, the largest full rectangle up to {@value #MAX_W} wide and {@value #MAX_H} high, counted from the
 * bottom-left block (as seen from the front). Unlike the Score Screen, every block keeps a copy of the settings, and
 * {@link #sync} hands the newest copy to every block of a rectangle, so a panel keeps its settings when a block is added
 * on its left or bottom or its bottom-left block is broken.
 */
public final class PanelShape {
    public static final int MAX_W = 8, MAX_H = 6;
    /** The last revision handed out on this server, so two saves in the same millisecond still differ. */
    private static final java.util.concurrent.atomic.AtomicLong LAST_REVISION = new java.util.concurrent.atomic.AtomicLong();

    /** A joined panel: its bottom-left block, its facing, and its size in blocks. */
    public record Rect(BlockPos origin, Direction facing, int width, int height) {
        public Direction right() {
            return PanelShape.right(facing);
        }

        public boolean contains(BlockPos pos) {
            Direction r = right();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (origin.relative(r, x).above(y).equals(pos)) {
                        return true;
                    }
                }
            }
            return false;
        }

        public List<BlockPos> blocks() {
            List<BlockPos> out = new ArrayList<>();
            Direction r = right();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    out.add(origin.relative(r, x).above(y));
                }
            }
            return out;
        }
    }

    private PanelShape() {
    }

    /** The viewer's right when looking at the front of a panel facing {@code facing}. */
    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    public static boolean isPanel(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof DisplayPanelBlock && s.getValue(DisplayPanelBlock.FACING) == facing;
    }

    /** The bottom-left block of the panel that {@code pos} belongs to: as far left as it goes, then as far down. */
    public static BlockPos origin(BlockGetter level, BlockPos pos, Direction facing) {
        Direction left = right(facing).getOpposite();
        BlockPos p = pos;
        for (int i = 1; i < MAX_W && isPanel(level, p.relative(left), facing); i++) {
            p = p.relative(left);
        }
        for (int i = 1; i < MAX_H && isPanel(level, p.below(), facing); i++) {
            p = p.below();
        }
        return p;
    }

    /** The full rectangle that starts at {@code origin}: the width along its bottom row, then every complete row above. */
    public static Rect rectAt(BlockGetter level, BlockPos origin, Direction facing) {
        Direction right = right(facing);
        int w = 1;
        while (w < MAX_W && isPanel(level, origin.relative(right, w), facing)) {
            w++;
        }
        int h = 1;
        rows:
        while (h < MAX_H) {
            for (int x = 0; x < w; x++) {
                if (!isPanel(level, origin.relative(right, x).above(h), facing)) {
                    break rows;
                }
            }
            h++;
        }
        return new Rect(origin, facing, w, h);
    }

    /** The panel {@code pos} belongs to. A block outside its origin's rectangle (an odd shape) is a panel of its own. */
    public static Rect rectOf(BlockGetter level, BlockPos pos, Direction facing) {
        Rect r = rectAt(level, origin(level, pos, facing), facing);
        return r.contains(pos) ? r : rectAt(level, pos, facing);
    }

    /** Gives every block of the panel at {@code pos} the newest settings any of them holds; returns that copy. */
    public static PanelSettings sync(Level level, BlockPos pos, Direction facing) {
        Rect rect = rectOf(level, pos, facing);
        PanelSettings newest = null;
        List<DisplayPanelBlockEntity> blocks = new ArrayList<>();
        for (BlockPos p : rect.blocks()) {
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof DisplayPanelBlockEntity panel) {
                blocks.add(panel);
                if (newest == null || panel.settings().revision() > newest.revision()) {
                    newest = panel.settings();
                }
            }
        }
        if (newest == null) {
            return PanelSettings.defaults();
        }
        for (DisplayPanelBlockEntity panel : blocks) {
            if (panel.settings().revision() != newest.revision() || !panel.settings().equals(newest)) {
                panel.setSettings(newest);
            }
        }
        return newest;
    }

    /** Saves new settings for the whole panel at {@code pos} (a fresh revision, newer than any before it) and returns them. */
    public static PanelSettings save(Level level, BlockPos pos, Direction facing, PanelSettings settings) {
        Rect rect = rectOf(level, pos, facing);
        long floor = System.currentTimeMillis();
        for (BlockPos p : rect.blocks()) {
            if (level.getBlockEntity(p) instanceof DisplayPanelBlockEntity panel) {
                floor = Math.max(floor, panel.settings().revision() + 1);
            }
        }
        long min = floor;
        long revision = LAST_REVISION.updateAndGet(last -> Math.max(min, last + 1));
        PanelSettings next = settings.sanitised().withRevision(revision);
        for (BlockPos p : rect.blocks()) {
            if (level.getBlockEntity(p) instanceof DisplayPanelBlockEntity panel) {
                panel.setSettings(next);
            }
        }
        return next;
    }
}
