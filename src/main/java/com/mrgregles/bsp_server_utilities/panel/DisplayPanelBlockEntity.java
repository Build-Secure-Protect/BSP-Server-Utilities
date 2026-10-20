package com.mrgregles.bsp_server_utilities.panel;

import com.mrgregles.bsp_server_utilities.image.ImageService;
import com.mrgregles.bsp_server_utilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * One block of a Display Panel: its copy of the panel's settings (every block of a joined panel holds the same copy, so
 * any of them can become the bottom-left block that draws). Sent to clients in the block entity's update tag.
 */
public class DisplayPanelBlockEntity extends BlockEntity {
    private PanelSettings settings = PanelSettings.defaults();

    public DisplayPanelBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.DISPLAY_PANEL_ENTITY.get(), pos, state);
    }

    public PanelSettings settings() {
        return settings;
    }

    /** Replaces this block's copy and tells the clients. */
    public void setSettings(PanelSettings next) {
        settings = next;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
            if (level instanceof ServerLevel server) {
                ImageService.track(server, worldPosition, next);
            }
        }
    }

    /** On the server: the picture's bytes and refresh timer are looked after while the block is in the world. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            ImageService.track(server, worldPosition, settings);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) {
            ImageService.untrack(server, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("panel", settings.save());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        settings = PanelSettings.load(tag.getCompound("panel"));
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** The panel is drawn from its bottom-left block across up to 8 x 6 blocks. */
    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).inflate(PanelShape.MAX_W);
    }
}
