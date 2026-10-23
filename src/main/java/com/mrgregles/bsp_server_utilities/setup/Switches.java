package com.mrgregles.bsp_server_utilities.setup;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which features the setup guide has turned off (owner decision D150), readable anywhere without a server at hand: the
 * server keeps it in step with the network's state, and every client is told at login and on each change (so Display
 * Panels can go dark and refuse to be placed on either side).
 */
public final class Switches {
    private static volatile Set<Feature> off = Set.of();

    private Switches() {
    }

    public static boolean off(Feature f) {
        return off.contains(f);
    }

    public static Set<Feature> all() {
        return off;
    }

    /** Replaces the set; true when it changed. */
    public static boolean set(Set<Feature> next) {
        Set<Feature> copy = next.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(next));
        boolean changed = !copy.equals(off);
        off = copy;
        return changed;
    }
}
