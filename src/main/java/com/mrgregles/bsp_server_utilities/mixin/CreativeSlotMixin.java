package com.mrgregles.bsp_server_utilities.mixin;

import com.mrgregles.bsp_server_utilities.log.CreativeTracker;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The creative inventory's slot packet, watched for the action log (owner decision D25): before and after the vanilla
 * handler, so items that appear count and items moved around do not. HEAD and RETURN only (never a redirect: Mohist
 * rewrites some vanilla calls). The handler first runs on the network thread and hands itself to the server thread;
 * {@link CreativeTracker} looks only at the server thread's run.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CreativeSlotMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"))
    private void bspSu$before(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        CreativeTracker.before(player, packet.getSlotNum(), packet.getItem());
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
    private void bspSu$after(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        CreativeTracker.after(player, packet.getSlotNum(), packet.getItem());
    }
}
