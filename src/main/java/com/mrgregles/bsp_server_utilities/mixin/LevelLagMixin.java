package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.lag.LagTimer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lag profiler's block entity timing (audit of SU-153): which server level's block entities tick, so
 * {@link BlockEntityTickLagMixin} needs nothing from its own target's fields. Kept only during a sample ({@link LagTimer}),
 * for server levels only; HEAD and RETURN with {@code require = 0}, checked by {@link LagMixinPlugin}.
 */
@Mixin(Level.class)
public abstract class LevelLagMixin {
    @Inject(method = "tickBlockEntities", at = @At("HEAD"), require = 0)
    private void bspSu$lagBlockEntitiesHead(CallbackInfo ci) {
        if (LagTimer.on) {
            LagTimer.blockEntitiesStart((Level) (Object) this);
        }
    }

    @Inject(method = "tickBlockEntities", at = @At("RETURN"), require = 0)
    private void bspSu$lagBlockEntitiesReturn(CallbackInfo ci) {
        LagTimer.blockEntitiesEnd();
    }
}
