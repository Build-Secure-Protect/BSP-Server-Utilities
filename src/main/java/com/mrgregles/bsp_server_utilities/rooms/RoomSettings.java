package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;

/**
 * The room limit's settings, from the server config's {@code [rooms]} section (D437, D438, D441, D446).
 *
 * @param limit       the most rooms a player may own; 0: no limit, rooms are only counted (D437)
 * @param network     whether a player's rooms on every server count together (D438); false: this server's only
 * @param exemptGroup a LuckPerms group never limited, or empty (D441; operators never are)
 * @param graceDays   how long a lost, given-back or freed room waits before it is cleared (D446)
 */
public record RoomSettings(int limit, boolean network, String exemptGroup, int graceDays) {
    public static final RoomSettings DEFAULTS = new RoomSettings(0, true, "", 7);

    /** Whether a limit is set at all. */
    public boolean limited() {
        return limit > 0;
    }

    /** Settings that replace the config's (tests); null: the config's. */
    @org.jetbrains.annotations.Nullable
    private static RoomSettings override;

    public static void useForTests(@org.jetbrains.annotations.Nullable RoomSettings settings) {
        override = settings;
    }

    /** The settings as the config holds them now (the defaults while it is not loaded). */
    public static RoomSettings current() {
        RoomSettings o = override;
        if (o != null) {
            return o;
        }
        return new RoomSettings(
                ServerUtilsConfig.getOr(ServerUtilsConfig.ROOMS_LIMIT, DEFAULTS.limit()),
                !"server".equals(ServerUtilsConfig.getOr(ServerUtilsConfig.ROOMS_SCOPE, "network")),
                ServerUtilsConfig.getOr(ServerUtilsConfig.ROOMS_EXEMPT_GROUP, "").trim(),
                ServerUtilsConfig.getOr(ServerUtilsConfig.ROOMS_GRACE_DAYS, DEFAULTS.graceDays()));
    }
}
