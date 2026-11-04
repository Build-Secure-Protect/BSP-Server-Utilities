package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.afk.AfkService;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A click in a container counts as active for AFK detection (owner decision D70), as an input on its slot for the
 * macro watch (D208); Forge has no event for it. HEAD only (never a redirect: Mohist rewrites some vanilla calls);
 * {@link AfkService#input} ignores the network thread's run.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ContainerClickMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At("HEAD"))
    private void bspSu$click(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        AfkService.input(player, "click", Integer.toString(packet.getSlotNum()));
    }
}
