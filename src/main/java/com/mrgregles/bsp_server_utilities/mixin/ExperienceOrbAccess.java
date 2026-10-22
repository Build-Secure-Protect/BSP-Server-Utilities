package com.mrgregles.bsp_server_utilities.mixin;

import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * An experience orb's age, which vanilla keeps private and counts only while the orb ticks: despawn sets it back for
 * orbs given longer than vanilla's 5 minutes, so the orb leaves by the running clock instead (owner decision D104).
 */
@Mixin(ExperienceOrb.class)
public interface ExperienceOrbAccess {
    @Accessor("age")
    void bspSu$setAge(int age);
}
