package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.lag.LagTimer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lag profiler's world timings (SU-151), only during a sample ({@link LagTimer}): each entity's tick with its
 * passengers ({@code tickNonPassenger}), each chunk's own tick (random ticks, spawning, lightning), and each scheduled
 * block and fluid tick (redstone, water, lava). HEAD and RETURN only; {@code require = 0} so a server whose code differs
 * keeps running without the profiler ({@link LagMixinPlugin} notices and the profiler says it is unavailable).
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelLagMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), require = 0)
    private void bspSu$lagEntityHead(Entity entity, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.entityStart(entity);
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"), require = 0)
    private void bspSu$lagEntityReturn(Entity entity, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.entityEnd((ServerLevel) (Object) this, entity);
        }
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), require = 0)
    private void bspSu$lagChunkHead(LevelChunk chunk, int randomTicks, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.chunkStart();
        }
    }

    @Inject(method = "tickChunk", at = @At("RETURN"), require = 0)
    private void bspSu$lagChunkReturn(LevelChunk chunk, int randomTicks, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.chunkEnd((ServerLevel) (Object) this, chunk);
        }
    }

    @Inject(method = "tickBlock", at = @At("HEAD"), require = 0)
    private void bspSu$lagBlockHead(BlockPos pos, Block block, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.scheduledStart();
        }
    }

    @Inject(method = "tickBlock", at = @At("RETURN"), require = 0)
    private void bspSu$lagBlockReturn(BlockPos pos, Block block, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.scheduledEnd((ServerLevel) (Object) this, pos, block);
        }
    }

    @Inject(method = "tickFluid", at = @At("HEAD"), require = 0)
    private void bspSu$lagFluidHead(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.scheduledStart();
        }
    }

    @Inject(method = "tickFluid", at = @At("RETURN"), require = 0)
    private void bspSu$lagFluidReturn(BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.scheduledEnd((ServerLevel) (Object) this, pos, fluid);
        }
    }
}
