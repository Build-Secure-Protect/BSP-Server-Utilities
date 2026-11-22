package com.mrgregles.bsp_server_utilities.mixin;

import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the Select World grid reads from a vanilla world entry: its level summary and the icon texture vanilla already
 * loaded for it (vanilla closes that texture when the list changes or the screen closes, so the grid loads none of its
 * own). Client only.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public interface WorldListEntryAccess {
    @Accessor("summary")
    LevelSummary bspSu$summary();

    @Accessor("icon")
    FaviconTexture bspSu$icon();
}
