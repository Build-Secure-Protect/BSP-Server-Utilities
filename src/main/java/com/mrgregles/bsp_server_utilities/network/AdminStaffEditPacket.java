package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffGroups;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Client to server: the STAFF tab's groups and operators switch as they now stand ({@code refresh} only asks for the data). */
public record AdminStaffEditPacket(boolean refresh, List<Staff.Role> roles, boolean operators) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(refresh);
        buf.writeVarInt(roles.size());
        for (Staff.Role r : roles) {
            buf.writeUtf(r.group(), 64);
            buf.writeUtf(r.label(), 64);
            buf.writeInt(r.colour());
        }
        buf.writeBoolean(operators);
    }

    public static AdminStaffEditPacket decode(FriendlyByteBuf buf) {
        boolean refresh = buf.readBoolean();
        int n = Math.min(buf.readVarInt(), StaffGroups.MAX_GROUPS);
        List<Staff.Role> roles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            roles.add(new Staff.Role(buf.readUtf(64), buf.readUtf(64), buf.readInt()));
        }
        return new AdminStaffEditPacket(refresh, roles, buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) {
            AdminPanel.applyStaff(player, this);
        }
        ctx.get().setPacketHandled(true);
    }
}
