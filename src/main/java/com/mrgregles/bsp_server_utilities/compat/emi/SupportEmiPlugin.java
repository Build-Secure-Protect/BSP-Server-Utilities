package com.mrgregles.bsp_server_utilities.compat.emi;

import com.mrgregles.bsp_server_utilities.client.SupportButton;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.widget.Bounds;

/**
 * EMI, when installed: its panels leave the SUPPORT BSP and MESSAGE STAFF keys free (D592, D652 R5), as BSP-Core's plugin does for
 * TEAM and KITS. Found and loaded only by EMI, in the player's game.
 */
@EmiEntrypoint
public class SupportEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addGenericExclusionArea((screen, out) -> {
            int[] r = SupportButton.area(screen);
            if (r != null) {
                out.accept(new Bounds(r[0], r[1], r[2], r[3]));
            }
            int[] t = com.mrgregles.bsp_server_utilities.client.TalkButton.area(screen); // MESSAGE STAFF (D652 R5)
            if (t != null) {
                out.accept(new Bounds(t[0], t[1], t[2], t[3]));
            }
        });
    }
}
