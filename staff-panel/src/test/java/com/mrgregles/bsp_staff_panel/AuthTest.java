package com.mrgregles.bsp_staff_panel;

import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.Base32;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.crypto.Totp;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's own login and staff accounts (D248, D250, D280 to D283): the first Owner from the console, one-time setup
 * links, password plus authenticator code or a backup code, a lock after five wrong tries, logins lasting by rank and
 * only for the same Cloudflare identity, resets, and nothing secret stored in the clear.
 */
class AuthTest {
    private static final String PW = "frost-wolf-lantern-9";
    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE, DAY = 24 * HOUR;
    private MemoryStore store;
    private SecretBox box;
    private AtomicLong clock;
    private Auth auth;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryStore();
        box = new SecretBox(SecretBox.newKey());
        clock = new AtomicLong(1_800_000_000_000L);
        auth = new Auth(store, box, clock::get, 1000);
        store.init();
    }

    private String code(String secret) {
        return Totp.format(Totp.code(Base32.decode(secret), Totp.step(clock.get())));
    }

    /** A set-up account; returns {secret, first backup code}. */
    private String[] setUpAccount(Auth.NewLink link) throws Exception {
        String secret = Auth.newAuthenticator(link.account().email)[0];
        Auth.Done d = auth.completeSetup(link.token(), link.account().email, PW, secret, code(secret));
        clock.addAndGet(31_000); // the next code
        return new String[]{secret, d.backupCodes().get(0)};
    }

    private Account owner() throws Exception {
        return store.accountByEmail("owner@bsp.test").orElseThrow();
    }

    @Test
    void the_first_owner_sets_up_through_a_one_time_link_from_the_console() throws Exception {
        Auth.NewLink link = auth.createOwner("Owner@BSP.test", "Mrgregles");
        assertEquals("owner@bsp.test", link.account().email);
        assertEquals(Rank.OWNER, link.account().rank);
        assertTrue(auth.setupLinkAccount(link.token()).isPresent());
        assertThrows(Auth.Refused.class, () -> auth.createOwner("owner@bsp.test", "Other"), "one account per email");

        String secret = Auth.newAuthenticator("owner@bsp.test")[0];
        assertThrows(Auth.Refused.class, () -> auth.completeSetup(link.token(), "someone@else.test", PW, secret, code(secret)), "only for the email it was made for");
        assertThrows(Auth.Refused.class, () -> auth.completeSetup(link.token(), "owner@bsp.test", "short", secret, code(secret)), "a weak password");
        assertThrows(Auth.Refused.class, () -> auth.completeSetup(link.token(), "owner@bsp.test", PW, secret, "000000".equals(code(secret)) ? "111111" : "000000"), "a wrong code");
        assertTrue(auth.setupLinkAccount(link.token()).isPresent(), "a refused try does not use the link up");

        Auth.Done done = auth.completeSetup(link.token(), "owner@bsp.test", PW, secret, code(secret));
        assertEquals(10, done.backupCodes().size(), "ten backup codes, shown once (D283)");
        assertTrue(auth.check(done.token(), "owner@bsp.test").isPresent(), "and logged in");
        assertTrue(auth.setupLinkAccount(link.token()).isEmpty(), "the link works once");
        assertThrows(Auth.Refused.class, () -> auth.completeSetup(link.token(), "owner@bsp.test", PW, secret, code(secret)));

        Account stored = owner();
        assertNotEquals(secret, stored.totp, "the authenticator secret is sealed");
        assertFalse(stored.password.contains(PW));
        assertTrue(stored.backup.stream().noneMatch(done.backupCodes()::contains), "backup codes are kept only as fingerprints");
    }

    @Test
    void a_setup_link_lasts_fifteen_minutes() throws Exception {
        Auth.NewLink link = auth.createOwner("owner@bsp.test", "Mrgregles");
        clock.addAndGet(15 * MINUTE + 1);
        assertTrue(auth.setupLinkAccount(link.token()).isEmpty());
        String secret = Auth.newAuthenticator("x")[0];
        assertThrows(Auth.Refused.class, () -> auth.completeSetup(link.token(), "owner@bsp.test", PW, secret, code(secret)));
    }

    @Test
    void login_needs_the_password_and_a_fresh_code_or_a_backup_code() throws Exception {
        String[] s = setUpAccount(auth.createOwner("owner@bsp.test", "Mrgregles"));
        assertEquals(Auth.Outcome.WRONG, auth.login("owner@bsp.test", "wrong password!!", code(s[0])).outcome());
        assertEquals(Auth.Outcome.WRONG, auth.login("owner@bsp.test", PW, "123456".equals(code(s[0])) ? "654321" : "123456").outcome());
        String c = code(s[0]);
        Auth.Login ok = auth.login("Owner@bsp.test", PW, c);
        assertEquals(Auth.Outcome.OK, ok.outcome());
        assertTrue(auth.check(ok.token(), "owner@bsp.test").isPresent());
        assertEquals(Auth.Outcome.WRONG, auth.login("owner@bsp.test", PW, c).outcome(), "the same code never works twice");

        Auth.Login backup = auth.login("owner@bsp.test", PW, s[1].toLowerCase());
        assertEquals(Auth.Outcome.OK, backup.outcome(), "a backup code instead of the app");
        assertEquals(9, backup.backupLeft());
        assertEquals(Auth.Outcome.WRONG, auth.login("owner@bsp.test", PW, s[1]).outcome(), "each backup code works once");
        assertEquals(Auth.Outcome.UNKNOWN, auth.login("nobody@bsp.test", PW, c).outcome());
    }

    @Test
    void five_wrong_tries_lock_the_account_for_fifteen_minutes() throws Exception {
        String[] s = setUpAccount(auth.createOwner("owner@bsp.test", "Mrgregles"));
        long start = clock.get();
        for (int i = 0; i < 4; i++) {
            assertEquals(Auth.Outcome.WRONG, auth.login("owner@bsp.test", "wrong password!!", "000000").outcome());
        }
        Auth.Login locked = auth.login("owner@bsp.test", "wrong password!!", "000000");
        assertEquals(Auth.Outcome.LOCKED, locked.outcome());
        assertEquals(start + 15 * MINUTE, locked.until());
        assertEquals(Auth.Outcome.LOCKED, auth.login("owner@bsp.test", PW, code(s[0])).outcome(), "even the right password waits");
        assertEquals(1, auth.lockoutsSince(start).size(), "the lock-out is in the log for the Owner (D282)");
        clock.addAndGet(15 * MINUTE + 1);
        assertEquals(Auth.Outcome.OK, auth.login("owner@bsp.test", PW, code(s[0])).outcome(), "open again after 15 minutes");
    }

    @Test
    void a_login_lasts_seven_days_for_moderators_and_48_hours_for_admins_and_owners() throws Exception {
        setUpAccount(auth.createOwner("owner@bsp.test", "Mrgregles"));
        Auth.NewLink mod = auth.addStaff(owner(), "mod@bsp.test", "Ana_Mod", Rank.MODERATOR);
        Auth.NewLink adm = auth.addStaff(owner(), "admin@bsp.test", "Big_Admin", Rank.ADMIN);
        String secretM = Auth.newAuthenticator("m")[0], secretA = Auth.newAuthenticator("a")[0];
        String m = auth.completeSetup(mod.token(), "mod@bsp.test", PW, secretM, code(secretM)).token();
        String a = auth.completeSetup(adm.token(), "admin@bsp.test", PW, secretA, code(secretA)).token();
        clock.addAndGet(48 * HOUR - MINUTE);
        assertTrue(auth.check(a, "admin@bsp.test").isPresent());
        clock.addAndGet(2 * MINUTE);
        assertTrue(auth.check(a, "admin@bsp.test").isEmpty(), "an Admin logs in again after 48 hours (D281)");
        assertTrue(auth.check(m, "mod@bsp.test").isPresent());
        clock.addAndGet(4 * DAY); // 6 days and a minute in
        assertTrue(auth.check(m, "mod@bsp.test").isPresent(), "a Moderator's lasts 7 days");
        clock.addAndGet(DAY);
        assertTrue(auth.check(m, "mod@bsp.test").isEmpty());
    }

    @Test
    void a_login_works_only_for_the_cloudflare_identity_that_made_it() throws Exception {
        String[] s = setUpAccount(auth.createOwner("owner@bsp.test", "Mrgregles"));
        String t = auth.login("owner@bsp.test", PW, code(s[0])).token();
        assertTrue(auth.check(t, "thief@bsp.test").isEmpty(), "a copied login under another identity is refused");
        assertTrue(auth.check(t, "owner@bsp.test").isEmpty(), "and ended");
        assertTrue(store.log(0, 50).stream().anyMatch(l -> l.what().contains("another Cloudflare identity")));
        assertTrue(auth.check("made-up", "owner@bsp.test").isEmpty());
        auth.logout(null);
    }

    @Test
    void only_owners_manage_staff_and_owners_come_from_the_console() throws Exception {
        setUpAccount(auth.createOwner("owner@bsp.test", "Mrgregles"));
        Auth.NewLink mod = auth.addStaff(owner(), "mod@bsp.test", "Ana_Mod", Rank.MODERATOR);
        setUpAccount(mod);
        Account ana = store.accountByEmail("mod@bsp.test").orElseThrow();
        assertThrows(Auth.Refused.class, () -> auth.addStaff(ana, "x@bsp.test", "Someone", Rank.MODERATOR), "a Moderator cannot add staff");
        assertThrows(Auth.Refused.class, () -> auth.addStaff(owner(), "x@bsp.test", "Someone", Rank.OWNER), "Owners only from the console");
        assertThrows(Auth.Refused.class, () -> auth.addStaff(owner(), "mod@bsp.test", "Ana_Mod", Rank.ADMIN), "one account per email");
        assertThrows(Auth.Refused.class, () -> auth.addStaff(owner(), "not an email", "Someone", Rank.ADMIN));
        assertThrows(Auth.Refused.class, () -> auth.addStaff(owner(), "y@bsp.test", "no spaces allowed", Rank.ADMIN));
        assertThrows(Auth.Refused.class, () -> auth.resetAuthenticator(owner(), owner().id), "an Owner's own is reset from the console");
        assertThrows(Auth.Refused.class, () -> auth.setRank(owner(), ana.id, Rank.OWNER));

        String t = auth.login("mod@bsp.test", PW, code(Auth.newAuthenticator("z")[0])).token();
        assertNull(t, "a code from another secret does not work");
        auth.setRank(owner(), ana.id, Rank.ADMIN);
        assertEquals(Rank.ADMIN, store.account(ana.id).orElseThrow().rank);
        auth.remove(owner(), ana.id);
        assertEquals(Auth.Outcome.UNKNOWN, auth.login("mod@bsp.test", PW, "000000").outcome(), "a removed account cannot log in");
        List<PanelStore.LogLine> log = store.log(0, 50);
        assertTrue(log.stream().anyMatch(l -> l.who().equals("owner@bsp.test") && l.what().equals("removed mod@bsp.test")), "every change is in the panel's log");
    }

    @Test
    void after_an_authenticator_reset_the_next_login_sets_up_a_new_one() throws Exception {
        setUpAccount(auth.createOwner("owner@bsp.test", "Mrgregles"));
        Auth.NewLink mod = auth.addStaff(owner(), "mod@bsp.test", "Ana_Mod", Rank.MODERATOR);
        String[] old = setUpAccount(mod);
        String session = auth.login("mod@bsp.test", PW, code(old[0])).token();
        Account ana = store.accountByEmail("mod@bsp.test").orElseThrow();
        auth.resetAuthenticator(owner(), ana.id);
        assertTrue(auth.check(session, "mod@bsp.test").isEmpty(), "a reset ends their logins");
        assertTrue(store.account(ana.id).orElseThrow().backup.isEmpty() && store.account(ana.id).orElseThrow().totp == null, "the old authenticator and backup codes are gone");

        Auth.Login step = auth.login("mod@bsp.test", PW, "");
        assertEquals(Auth.Outcome.ENROL, step.outcome(), "the right password leads to setting up a new authenticator");
        String secret = Auth.newAuthenticator("mod@bsp.test")[0];
        assertThrows(Auth.Refused.class, () -> auth.completeEnrol(step.token(), "thief@bsp.test", secret, code(secret)));
        Auth.Done d = auth.completeEnrol(step.token(), "mod@bsp.test", secret, code(secret));
        assertEquals(10, d.backupCodes().size());
        assertTrue(auth.check(d.token(), "mod@bsp.test").isPresent());

        Auth.Login late = auth.login("mod@bsp.test", PW, d.backupCodes().get(3));
        assertEquals(Auth.Outcome.OK, late.outcome(), "the new backup codes work");
    }

    @Test
    void the_console_resets_an_owner_and_a_forgotten_password_gets_a_new_link() throws Exception {
        setUpAccount(auth.createOwner("owner@bsp.test", "Mrgregles"));
        assertTrue(auth.resetFromConsole("owner@bsp.test", false).isEmpty());
        assertEquals(Auth.Outcome.ENROL, auth.login("owner@bsp.test", PW, "").outcome());
        Auth.NewLink again = auth.resetFromConsole("owner@bsp.test", true).orElseThrow();
        assertEquals(Auth.Outcome.UNKNOWN, auth.login("owner@bsp.test", PW, "").outcome(), "no password until the new link is used");
        setUpAccount(again);
        Auth.NewLink mod = auth.addStaff(owner(), "mod@bsp.test", "Ana_Mod", Rank.MODERATOR);
        setUpAccount(mod);
        Auth.NewLink fresh = auth.resetLogin(owner(), store.accountByEmail("mod@bsp.test").orElseThrow().id);
        assertTrue(auth.setupLinkAccount(fresh.token()).isPresent());
        assertThrows(Auth.Refused.class, () -> auth.resetFromConsole("nobody@bsp.test", false));
    }
}
