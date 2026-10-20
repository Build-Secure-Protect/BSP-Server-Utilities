package com.mrgregles.bsp_server_utilities.panel;

import com.mrgregles.bsp_server_utilities.access.Perms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Display Panel (owner decisions D14 to D16, D41, D52): a thin wall block, 3 pixels deep against the wall behind it,
 * that joins with panels of the same facing beside and above it ({@link PanelShape}). The four {@code UP}, {@code DOWN},
 * {@code LEFT}, {@code RIGHT} properties say which sides join another panel, so the model draws the slim bezel only
 * round the outside of a joined panel, and the teal light only in its bottom-right corner (D41). Only admins place,
 * open and break it; it does not move with pistons and shrugs off explosions. Right-clicking opens the settings
 * screen for an admin ({@link PanelEditing}).
 */
public class DisplayPanelBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty UP = BlockStateProperties.UP, DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty LEFT = BooleanProperty.create("left"), RIGHT = BooleanProperty.create("right");
    private static final VoxelShape NORTH = Block.box(0, 0, 13, 16, 16, 16), SOUTH = Block.box(0, 0, 0, 16, 16, 3),
            WEST = Block.box(13, 0, 0, 16, 16, 16), EAST = Block.box(0, 0, 0, 3, 16, 16);

    public DisplayPanelBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.5F, 3_600_000F).sound(SoundType.METAL).noOcclusion()
                .pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(UP, false).setValue(DOWN, false).setValue(LEFT, false).setValue(RIGHT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT);
    }

    /** Which sides of a panel at {@code pos} facing {@code facing} join another panel. */
    public static BlockState connected(BlockState state, BlockGetter level, BlockPos pos) {
        Direction facing = state.getValue(FACING), right = PanelShape.right(facing);
        return state.setValue(UP, PanelShape.isPanel(level, pos.above(), facing))
                .setValue(DOWN, PanelShape.isPanel(level, pos.below(), facing))
                .setValue(RIGHT, PanelShape.isPanel(level, pos.relative(right), facing))
                .setValue(LEFT, PanelShape.isPanel(level, pos.relative(right.getOpposite()), facing));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        return connected(state, ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return connected(state, level, pos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DisplayPanelBlockEntity(pos, state);
    }

    // ------------------------------------------------------------------ settings follow the panel

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity be && by instanceof Player p) {
            be.setSettings(be.settings().withPlacedBy(p.getGameProfile().getName()));
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide && !old.is(this)) {
            PanelShape.sync(level, pos, state.getValue(FACING));
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!level.isClientSide && !next.is(this)) {
            Direction facing = state.getValue(FACING), right = PanelShape.right(facing);
            for (BlockPos n : new BlockPos[]{pos.above(), pos.below(), pos.relative(right), pos.relative(right.getOpposite())}) {
                if (PanelShape.isPanel(level, n, facing)) {
                    PanelShape.sync(level, n, facing);
                }
            }
        }
    }

    // ------------------------------------------------------------------ admins only (D16, D52)

    /** Whether the player may break and open panels: an editor on the server; an operator as far as a client can tell. */
    public static boolean mayUse(Player player) {
        return player instanceof ServerPlayer sp ? Perms.canEdit(sp) : player.hasPermissions(Perms.OPERATOR_LEVEL);
    }

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return mayUse(player) ? super.getDestroyProgress(state, player, level, pos) : 0F;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer sp)) {
            return mayUse(player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!Perms.canEdit(sp)) {
            return InteractionResult.PASS;
        }
        PanelEditing.open(sp, pos, state);
        return InteractionResult.CONSUME;
    }
}
