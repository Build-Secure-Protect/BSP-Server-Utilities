package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.rooms.RoomLosses;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dropped item destroyed by what hurt it (fire, lava, an explosion, a cactus, an anvil): the room limit's loss of a
 * bound Compact Machine (SU-56, D444). Forge has no event for it. HEAD and RETURN only (never a redirect): an item alive
 * at HEAD and removed at RETURN was destroyed by this hurt, which removes it only once its health is gone.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityHurtMixin {
    @Unique
    private boolean bspSu$aliveBefore;

    @Inject(method = "hurt", at = @At("HEAD"))
    private void bspSu$before(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        bspSu$aliveBefore = !((ItemEntity) (Object) this).isRemoved();
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void bspSu$after(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (bspSu$aliveBefore && self.isRemoved() && !self.level().isClientSide()) {
            RoomLosses.itemGone(self, RoomLosses.reason(source));
        }
    }
}
