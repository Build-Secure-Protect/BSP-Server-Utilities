package com.mrgregles.bsp_staff_panel.store;

import com.mrgregles.bsp_staff_panel.auth.Rank;

import java.util.ArrayList;
import java.util.List;

/**
 * A staff member's panel account. Secrets are never held in the clear: {@code password} is a {@link
 * com.mrgregles.bsp_staff_panel.crypto.Passwords} hash, {@code totp} the authenticator secret sealed with the panel's key,
 * {@code backup} the fingerprints of the unused backup codes. A copy: change it, then save it through the store.
 */
public final class Account {
    public long id;
    public String email = "", mcName = "", createdBy = "";
    public Rank rank = Rank.MODERATOR;
    /** Null until the staff member sets one (through their one-time link). */
    public String password;
    /** Null until an authenticator is set up, and again after an Owner resets it (D283). */
    public String totp;
    /** The last authenticator step used, so a code never works twice. */
    public long totpStep = -1;
    public List<String> backup = new ArrayList<>();
    public int failed;
    public long lockedUntil, createdAt, lastLoginAt, previousLoginAt;
    public boolean active = true;

    public boolean setUp() {
        return password != null && totp != null;
    }

    public Account copy() {
        Account a = new Account();
        a.id = id;
        a.email = email;
        a.mcName = mcName;
        a.createdBy = createdBy;
        a.rank = rank;
        a.password = password;
        a.totp = totp;
        a.totpStep = totpStep;
        a.backup = new ArrayList<>(backup);
        a.failed = failed;
        a.lockedUntil = lockedUntil;
        a.createdAt = createdAt;
        a.lastLoginAt = lastLoginAt;
        a.previousLoginAt = previousLoginAt;
        a.active = active;
        return a;
    }
}
