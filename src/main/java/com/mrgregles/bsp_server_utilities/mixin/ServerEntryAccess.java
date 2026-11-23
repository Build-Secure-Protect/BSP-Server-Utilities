package com.mrgregles.bsp_server_utilities.mixin;

import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * What the Multiplayer grid uses of a vanilla server entry: the icon texture vanilla keeps for it (vanilla uploads the
 * server's icon into it; the grid loads none of its own and only reads its location), and vanilla's own {@code swap},
 * which moves a server one place in the saved list as the arrows on vanilla's row icon do. Client only.
 */
@Mixin(ServerSelectionList.OnlineServerEntry.class)
public interface ServerEntryAccess {
    @Accessor("icon")
    FaviconTexture bspSu$icon();

    @Invoker("swap")
    void bspSu$swap(int from, int to);
}
