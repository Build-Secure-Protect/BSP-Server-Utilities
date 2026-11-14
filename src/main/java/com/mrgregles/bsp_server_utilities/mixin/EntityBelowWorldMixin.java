package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.rooms.RoomLosses;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dropped item fallen into the void, which vanilla simply discards: the room limit's loss of a bound Compact Machine
 * (SU-56, D444). Forge has no event for it, and the discard's removal reason is the same as a pickup's. HEAD only.
 */
@Mixin(Entity.class)
public abstract class EntityBelowWorldMixin {
    @Inject(method = "onBelowWorld", at = @At("HEAD"))
    private void bspSu$void(CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item && !item.level().isClientSide()) {
            RoomLosses.itemGone(item, "void");
        }
    }
}
