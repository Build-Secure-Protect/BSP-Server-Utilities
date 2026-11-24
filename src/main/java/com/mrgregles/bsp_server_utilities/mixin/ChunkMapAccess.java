package com.mrgregles.bsp_server_utilities.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Every chunk the level holds, which vanilla keeps behind a protected method: read by the chunk census ({@code /su
 * chunks}, SU-147) and nothing else.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Invoker("getChunks")
    Iterable<ChunkHolder> bspSu$chunks();
}
