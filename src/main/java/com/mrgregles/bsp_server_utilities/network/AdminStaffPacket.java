package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffGroups;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the STAFF tab's data (owner decisions D56, D57, D63): the staff groups and the operators switch, the
 * LuckPerms groups there are to pick from, and the roster.
 */
public record AdminStaffPacket(List<Staff.Role> roles, boolean operators, List<String> luckPermsGroups, StaffViewPacket roster) {
    public static AdminStaffPacket of(StaffGroups groups, List<String> lp, StaffViewPacket roster) {
        return new AdminStaffPacket(groups.roles(), groups.operators(), lp, roster);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(roles.size());
        for (Staff.Role r : roles) {
            buf.writeUtf(r.group(), 64);
            buf.writeUtf(r.label(), 64);
            buf.writeInt(r.colour());
        }
        buf.writeBoolean(operators);
        buf.writeVarInt(luckPermsGroups.size());
        luckPermsGroups.forEach(g -> buf.writeUtf(g, 64));
        roster.encode(buf);
    }

    public static AdminStaffPacket decode(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), StaffGroups.MAX_GROUPS);
        List<Staff.Role> roles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            roles.add(new Staff.Role(buf.readUtf(64), buf.readUtf(64), buf.readInt()));
        }
        boolean operators = buf.readBoolean();
        int m = Math.min(buf.readVarInt(), 200);
        List<String> lp = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            lp.add(buf.readUtf(64));
        }
        return new AdminStaffPacket(roles, operators, lp, StaffViewPacket.decode(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientAdmin.staff(this));
        ctx.get().setPacketHandled(true);
    }
}
