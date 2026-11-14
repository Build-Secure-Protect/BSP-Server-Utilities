package com.mrgregles.bsp_server_utilities.rooms;

import java.util.List;
import java.util.Map;

/**
 * Every server's rooms as the shared tables hold them: the rows, and when each server last wrote (ms).
 */
public record RoomTable(Map<String, Long> heard, List<RoomRow> rows) {
    public static final RoomTable EMPTY = new RoomTable(Map.of(), List.of());
}
