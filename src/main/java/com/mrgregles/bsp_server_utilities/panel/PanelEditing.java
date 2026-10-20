package com.mrgregles.bsp_server_utilities.panel;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.image.ImageService;
import com.mrgregles.bsp_server_utilities.network.PanelOpenPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The server side of a panel's settings screen (owner decisions D16, D43, D48, D50): opening it for an admin who
 * right-clicks a panel (with the images folder's files for the Image pane), SAVE and REFRESH NOW, checked here (an admin,
 * close enough, a panel still there) before the settings go to every block of the panel.
 */
public final class PanelEditing {
    /** How near (in blocks) an admin must be to save a panel's settings. */
    public static final double REACH = 40;

    public enum Outcome { SAVED, REFUSED, TOO_FAR, NOT_A_PANEL }

    private PanelEditing() {
    }

    public static void open(ServerPlayer player, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(DisplayPanelBlock.FACING);
        PanelShape.Rect rect = PanelShape.rectOf(player.level(), pos, facing);
        PanelSettings settings = PanelShape.sync(player.level(), pos, facing);
        ServerUtilsNetwork.sendTo(player, new PanelOpenPacket(rect.origin(), rect.width(), rect.height(), settings.save(), Storage.currentServerId(), ImageService.folderFiles()));
    }

    /** REFRESH NOW: loads the panel's picture again, under the same checks as a save. */
    public static Outcome refresh(ServerPlayer player, BlockPos pos) {
        Outcome check = check(player, pos);
        if (check != Outcome.SAVED) {
            return check;
        }
        ImageService.load((ServerLevel) player.level(), pos);
        return Outcome.SAVED;
    }

    private static Outcome check(ServerPlayer player, BlockPos pos) {
        if (!Perms.canEdit(player)) {
            return Outcome.REFUSED;
        }
        Level level = player.level();
        if (!level.isLoaded(pos) || player.position().distanceTo(Vec3.atCenterOf(pos)) > REACH) {
            return Outcome.TOO_FAR;
        }
        return level.getBlockState(pos).getBlock() instanceof DisplayPanelBlock ? Outcome.SAVED : Outcome.NOT_A_PANEL;
    }

    /**
     * SAVE. The placer and what the server made of the picture come from the panel, never from the client; a new picture
     * source (or one that never loaded) is loaded in the background.
     */
    public static Outcome save(ServerPlayer player, BlockPos pos, CompoundTag tag) {
        Outcome check = check(player, pos);
        if (check != Outcome.SAVED) {
            return check;
        }
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        Direction facing = state.getValue(DisplayPanelBlock.FACING);
        PanelSettings current = PanelShape.sync(level, pos, facing);
        PanelSettings next = PanelSettings.load(tag).withPlacedBy(current.placedBy()).withLoaded(current.loaded());
        PanelSettings saved = PanelShape.save(level, pos, facing, next);
        if (saved.mode() == PanelSettings.Mode.IMAGE && (!saved.image().equals(current.image()) || !saved.loaded().ok()) && level instanceof ServerLevel server) {
            ImageService.load(server, pos);
        }
        return Outcome.SAVED;
    }
}
