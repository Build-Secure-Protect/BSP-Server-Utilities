package com.mrgregles.bsp_server_utilities.mixin;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.web.WebBans;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * Network bans at login (SU-44, D350): after the game's own checks (its ban lists, the whitelist, a full server), a player
 * banned from the network is refused with the ban's screen. Read from the copy in memory, never the database, as this runs
 * on the server thread. A RETURN inject only.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true)
    private void bspSu$networkBan(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        if (cir.getReturnValue() == null && profile != null && profile.getId() != null) {
            Component refused = WebBans.refusal(profile.getId());
            if (refused != null) {
                cir.setReturnValue(refused);
            }
        }
    }
}
