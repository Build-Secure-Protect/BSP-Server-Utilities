package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.lag.LagTimer;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lag profiler's block entity timing (SU-151): each block entity's tick, timed only during a sample ({@link LagTimer}).
 * HEAD and RETURN only; {@code require = 0} so a server whose code differs keeps running without the profiler
 * ({@link LagMixinPlugin} notices and the profiler says it is unavailable). No field of the target is shadowed (a server
 * that renamed one would fail to load the class): the place and type come from the ticker's own {@link TickingBlockEntity}
 * methods, the level from {@link LevelLagMixin}.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickLagMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void bspSu$lagBlockEntityHead(CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.blockEntityStart();
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void bspSu$lagBlockEntityReturn(CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.blockEntityEnd((TickingBlockEntity) (Object) this);
        }
    }
}
