package com.mrgregles.bsp_staff_panel.auth;

import java.time.Duration;
import java.util.Locale;

/**
 * The panel's ranks (owner decision D250) and how long a login lasts for each (D281): 7 days for Moderators, 48 hours
 * for Admins and the Owner, who can ban and give items.
 */
public enum Rank {
    MODERATOR("Moderator", Duration.ofDays(7)),
    ADMIN("Admin", Duration.ofHours(48)),
    OWNER("Owner", Duration.ofHours(48));

    private final String label;
    private final Duration session;

    Rank(String label, Duration session) {
        this.label = label;
        this.session = session;
    }

    public String label() {
        return label;
    }

    public Duration session() {
        return session;
    }

    public boolean atLeast(Rank other) {
        return ordinal() >= other.ordinal();
    }

    public static Rank parse(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
