package com.mrgregles.bsp_server_utilities.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: a point's popup to show (SU-140), or none ({@code tutorial} empty: close it). With the tutorial's
 * name, the point, the stop on this branch and how many there are (0 when a fork lies ahead), the popup as saved, each
 * box's picture hash ("" while it loads), for each button the server its point is on when that is another one (its
 * NEXT then reads TAKE ME THERE, D730), whether BSP-Teleport can move the player there, and whether PREVIOUS has
 * somewhere to go. The same point sent again (a picture arrived) updates the popup in place.
 */
public record TutorialPopupPacket(String tutorial, String name, int point, String title, int stop, int total, CompoundTag popup,
                                  List<String> hashes, List<String> away, boolean canMove, boolean canBack) {
    public static final TutorialPopupPacket CLOSE = new TutorialPopupPacket("", "", -1, "", 0, 0, new CompoundTag(), List.of(), List.of(), false, false);

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(tutorial, 64);
        buf.writeUtf(name, 128);
        buf.writeVarInt(point);
        buf.writeUtf(title, 128);
        buf.writeVarInt(stop);
        buf.writeVarInt(total);
        buf.writeNbt(popup);
        buf.writeCollection(hashes, (b, s) -> b.writeUtf(s, 64));
        buf.writeCollection(away, (b, s) -> b.writeUtf(s, 128));
        buf.writeBoolean(canMove);
        buf.writeBoolean(canBack);
    }

    public static TutorialPopupPacket decode(FriendlyByteBuf buf) {
        String t = buf.readUtf(64), n = buf.readUtf(128);
        int point = buf.readVarInt();
        String title = buf.readUtf(128);
        int stop = buf.readVarInt(), total = buf.readVarInt();
        CompoundTag popup = buf.readNbt();
        List<String> hashes = buf.readCollection(ArrayList::new, b -> b.readUtf(64));
        List<String> away = buf.readCollection(ArrayList::new, b -> b.readUtf(128));
        return new TutorialPopupPacket(t, n, point, title, stop, total, popup == null ? new CompoundTag() : popup, hashes, away, buf.readBoolean(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientTutorial.popup(this));
        ctx.get().setPacketHandled(true);
    }
}
