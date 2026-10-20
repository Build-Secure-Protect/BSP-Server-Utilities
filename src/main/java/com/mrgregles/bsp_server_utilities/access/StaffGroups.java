package com.mrgregles.bsp_server_utilities.access;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * The staff groups for the whole network (owner decisions D4, D57): LuckPerms groups in order (a player's role is the
 * first group they are in), each with the label and colour panels show, whether operators count as staff, and a
 * revision so the newest copy wins across servers.
 */
public record StaffGroups(List<Staff.Role> roles, boolean operators, long revision) {
    public static final int MAX_GROUPS = 12, MAX_LABEL = 24;

    public StaffGroups {
        roles = List.copyOf(roles);
    }

    public static StaffGroups empty() {
        return new StaffGroups(List.of(), true, 0);
    }

    /** The same groups within the limits: names as {@link Staff#parse} wants them, labels cut to 24, at most 12, no duplicates. */
    public StaffGroups sanitised() {
        List<String> entries = new ArrayList<>();
        for (Staff.Role r : roles) {
            String label = r.label().replace(":", " ").strip();
            entries.add(r.group() + ":" + (label.length() > MAX_LABEL ? label.substring(0, MAX_LABEL) : label) + ":#" + String.format("%06X", r.colour() & 0xFFFFFF));
        }
        List<Staff.Role> parsed = Staff.parse(entries);
        return new StaffGroups(parsed.size() > MAX_GROUPS ? parsed.subList(0, MAX_GROUPS) : parsed, operators, revision);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Staff.Role r : roles) {
            CompoundTag rt = new CompoundTag();
            rt.putString("group", r.group());
            rt.putString("label", r.label());
            rt.putInt("colour", r.colour());
            list.add(rt);
        }
        tag.put("roles", list);
        tag.putBoolean("operators", operators);
        tag.putLong("revision", revision);
        return tag;
    }

    public static StaffGroups load(CompoundTag tag) {
        List<Staff.Role> roles = new ArrayList<>();
        for (Tag t : tag.getList("roles", Tag.TAG_COMPOUND)) {
            CompoundTag rt = (CompoundTag) t;
            roles.add(new Staff.Role(rt.getString("group"), rt.getString("label"), rt.getInt("colour")));
        }
        return new StaffGroups(roles, !tag.contains("operators") || tag.getBoolean("operators"), tag.getLong("revision")).sanitised();
    }
}
