package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.compat.CompactMachines;

import java.util.UUID;

/**
 * One Compact Machines room as the network shares it (a row of {@code <prefix>cm_rooms}, documented in docs/API.md): a
 * room is a server plus its chunk in that server's room dimension, never the chunk alone (every server numbers its
 * rooms in the same order).
 *
 * @param server     the server the room is on ({@code storage.serverId})
 * @param x          the room's chunk x in the room dimension
 * @param z          the room's chunk z
 * @param owner      who owns it ({@link CompactMachines#NO_OWNER} for a slot kept for nobody)
 * @param size       its inside size in blocks, 3 to 13
 * @param name       its name, or empty
 * @param machine    where one of its placed machines stands ("dimension x y z"), or empty when none is placed
 * @param machines   how many placed machines are bound to it
 * @param state      {@link #ACTIVE} (counts) or {@link #GRACE} (lost, given back or freed: waiting to be cleared, counts for nobody)
 * @param graceUntil when a room in grace is cleared (ms), else 0
 * @param reason     why a room is in grace (burnt, despawned, void, given back, freed...), else empty
 * @param told       whether its owner has been told it is in grace
 * @param ownerName  the owner's name as the room's server last knew it, or empty (the admins' ROOMS tab, SU-59)
 */
public record RoomRow(String server, int x, int z, UUID owner, int size, String name, String machine, int machines, String state, long graceUntil, String reason, boolean told,
                      String ownerName) {
    public static final String ACTIVE = "active", GRACE = "grace";
    /** The longest name kept (the table's column). */
    public static final int NAME_MAX = 64;
    /** The longest player name kept (the table's column). */
    public static final int OWNER_NAME_MAX = 16;

    /** Whether it counts against its owner's limit: a room in use, owned by someone. */
    public boolean counts() {
        return ACTIVE.equals(state) && owner != null && !CompactMachines.NO_OWNER.equals(owner);
    }
}
