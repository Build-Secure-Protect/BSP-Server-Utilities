package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server to client: an admin's path or area mode as it stands (SU-144), or none (an empty tag). */
public record TutorialModePacket(CompoundTag state) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeNbt(state);
    }

    public static TutorialModePacket decode(FriendlyByteBuf buf) {
        CompoundTag t = buf.readNbt();
        return new TutorialModePacket(t == null ? new CompoundTag() : t);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTutorialAdmin.mode(state));
        ctx.get().setPacketHandled(true);
    }
}
