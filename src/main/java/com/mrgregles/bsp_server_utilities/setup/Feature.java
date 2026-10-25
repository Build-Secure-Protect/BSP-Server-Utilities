package com.mrgregles.bsp_server_utilities.setup;

/**
 * The setup guide's pages, in order (owner decision D148; the player counter after Display panels, D173), Finish aside. WELCOME is this server's own (its name and
 * storage, D146, D149); the others are set up once for the network. Every feature but WELCOME can be turned off (D150).
 */
public enum Feature {
    WELCOME, STAFF, RULES, LOG, AFK, CHUNKS, DESPAWN, CHECK, PANELS, PLAYERS;

    /** Whether the page belongs to this server rather than the network. */
    public boolean perServer() {
        return this == WELCOME;
    }

    public boolean canTurnOff() {
        return this != WELCOME;
    }

    /** Whether its off switch is the setup guide's own (AFK and chunk unloading keep theirs in their settings). */
    public boolean ownSwitch() {
        return canTurnOff() && this != AFK && this != CHUNKS;
    }
}
