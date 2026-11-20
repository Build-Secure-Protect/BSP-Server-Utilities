package com.mrgregles.bsp_server_utilities.compat.jei;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.client.SupportButton;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;

/**
 * JEI, when installed: its bookmark and ingredient lists leave the SUPPORT BSP key free (owner decision D592), as
 * BSP-Core's plugin does for TEAM and KITS. Found and loaded only by JEI, in the player's game.
 */
@JeiPlugin
public class SupportJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, "support");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration reg) {
        reg.addGlobalGuiHandler(new IGlobalGuiHandler() {
            @Override
            public Collection<Rect2i> getGuiExtraAreas() {
                int[] r = SupportButton.area(Minecraft.getInstance().screen);
                return r == null ? List.of() : List.of(new Rect2i(r[0], r[1], r[2], r[3]));
            }
        });
    }
}
