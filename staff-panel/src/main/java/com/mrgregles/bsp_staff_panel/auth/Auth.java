package com.mrgregles.bsp_staff_panel.auth;

import com.mrgregles.bsp_staff_panel.crypto.Base32;
import com.mrgregles.bsp_staff_panel.crypto.Passwords;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.crypto.Tokens;
import com.mrgregles.bsp_staff_panel.crypto.Totp;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The panel's second lock and its staff accounts (owner decisions D248, D250, D280 to D283). Behind Cloudflare Access,
 * a staff member logs in with their password and a code from their authenticator app (or one of their ten backup codes);
 * the login lasts as long as their rank allows and only for the same Cloudflare identity. Five wrong tries lock the
 * account for 15 minutes. The first Owner is made from the panel's console; Owners add Moderators and Admins, who set
 * their password and authenticator through a one-time link valid 15 minutes. An Owner can reset someone's authenticator
 * (they set up a new one at their next login) or their whole login (a new link); an Owner's own is reset from the console.
 *
 * <p>Every method that changes something writes a line to the panel's log. Tokens and backup codes are kept only as
 * fingerprints; the authenticator secret only sealed with the panel's key.
 */
public final class Auth {
    public static final int MAX_FAILS = 5, BACKUP_CODES = 10;
    public static final long LOCK_MS = 15 * 60_000L, LINK_MS = 15 * 60_000L, ENROL_MS = 10 * 60_000L;
    public static final String KIND_SETUP = "setup", KIND_ENROL = "enrol", CONSOLE = "console";
    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s,]{1,64}@[^@\\s,]{1,189}\\.[^@\\s,]{2,}");
    private static final Pattern SECRET = Pattern.compile("[A-Z2-7]{32}");

    /** Refused, with the words to show the staff member. */
    public static final class Refused extends Exception {
        public Refused(String why) {
            super(why);
        }
    }

    public record NewLink(String token, Account account) {
    }

    public enum Outcome { OK, WRONG, LOCKED, UNKNOWN, ENROL }

    /**
     * What a login came to. OK: {@code token} is the session's; ENROL: the authenticator was reset, {@code token} is the
     * 10-minute step to set up a new one; LOCKED: {@code until} says when it opens again.
     */
    public record Login(Outcome outcome, String token, Account account, long until, int backupLeft) {
    }

    /** A finished setup: the new session, and the backup codes, shown once. */
    public record Done(String token, Account account, List<String> backupCodes) {
    }

    private final PanelStore store;
    private final SecretBox box;
    private final LongSupplier clock;
    private final int passwordCost;

    public Auth(PanelStore store, SecretBox box, LongSupplier clock) {
        this(store, box, clock, Passwords.ITERATIONS);
    }

    /** With a given password cost; tests use a small one. */
    public Auth(PanelStore store, SecretBox box, LongSupplier clock, int passwordCost) {
        this.store = store;
        this.box = box;
        this.clock = clock;
        this.passwordCost = passwordCost;
    }

    /** Work done under one account's lock. */
    private interface Body<T> {
        T run() throws Exception;
    }

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * One change at a time per account (audit 2026-11-09): every change reads the account again inside the lock, so a
     * login that takes a moment (the password hash) can never write back an old copy over an Owner's removal or reset,
     * wrong tries are all counted, and a code or backup code is taken once.
     */
    private <T> T locked(long id, Body<T> body) throws Exception {
        ReentrantLock l = locks.computeIfAbsent(id, k -> new ReentrantLock());
        l.lock();
        try {
            return body.run();
        } finally {
            l.unlock();
        }
    }

    public static String normalEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void log(String who, String what) throws Exception {
        store.log(new PanelStore.LogLine(clock.getAsLong(), who, what));
    }

    // ------------------------------------------------------------------ accounts

    private Account newAccount(String email, String mcName, Rank rank, String by) throws Refused {
        String e = normalEmail(email);
        if (e.length() > 254 || !EMAIL.matcher(e).matches()) {
            throw new Refused("That is not an email address.");
        }
        if (mcName == null || !MC_NAME.matcher(mcName.trim()).matches()) {
            throw new Refused("A Minecraft name is 3 to 16 letters, digits or underscores.");
        }
        Account a = new Account();
        a.email = e;
        a.mcName = mcName.trim();
        a.rank = rank;
        a.createdAt = clock.getAsLong();
        a.createdBy = by;
        return a;
    }

    private NewLink link(Account a, String kind, long forMs) throws Exception {
        String token = Tokens.token();
        store.saveLink(new PanelStore.Link(box.fingerprint(token), a.id, kind, clock.getAsLong() + forMs));
        return new NewLink(token, a);
    }

    /** The panel's console (D280): the first Owner, or another, with a setup link valid 15 minutes. */
    public synchronized NewLink createOwner(String email, String mcName) throws Exception {
        Account a = newAccount(email, mcName, Rank.OWNER, CONSOLE);
        if (store.accountByEmail(a.email).isPresent()) {
            throw new Refused("There is already an account for " + a.email + ". To let them in again use reset-owner.");
        }
        a.id = store.insert(a);
        log(CONSOLE, "made " + a.email + " (" + a.mcName + ") an Owner");
        return link(a, KIND_SETUP, LINK_MS);
    }

    private static void requireOwner(Account by) throws Refused {
        if (by == null || by.rank != Rank.OWNER || !by.active) {
            throw new Refused("Only an Owner can do that.");
        }
    }

    private Account target(Account by, long id) throws Exception {
        requireOwner(by);
        if (id == by.id) {
            throw new Refused("Not on your own account: an Owner's own login is reset from the panel's console.");
        }
        Account t = store.account(id).filter(a -> a.active).orElseThrow(() -> new Refused("No such staff member."));
        if (t.rank == Rank.OWNER) {
            throw new Refused("Owners are looked after from the panel's console.");
        }
        return t;
    }

    /** An Owner adds a Moderator or an Admin; they get a link valid 15 minutes. Owners are made from the console only. */
    public synchronized NewLink addStaff(Account by, String email, String mcName, Rank rank) throws Exception {
        requireOwner(by);
        if (rank == Rank.OWNER) {
            throw new Refused("Owners are made from the panel's console.");
        }
        Account a = newAccount(email, mcName, rank, by.email);
        Optional<Account> old = store.accountByEmail(a.email);
        if (old.isPresent() && old.get().active) {
            throw new Refused("There is already an account for " + a.email + ".");
        }
        if (old.isPresent()) { // removed before: brought back fresh
            Account back = old.get();
            a.id = back.id;
            store.update(a);
        } else {
            a.id = store.insert(a);
        }
        log(by.email, "added " + a.email + " (" + a.mcName + ") as " + a.rank.label());
        return link(a, KIND_SETUP, LINK_MS);
    }

    /** A forgotten password: the login is cleared and a new setup link made (password and authenticator again). */
    public NewLink resetLogin(Account by, long id) throws Exception {
        return locked(id, () -> resetLoginLocked(by, id));
    }

    private NewLink resetLoginLocked(Account by, long id) throws Exception {
        Account t = target(by, id);
        clearLogin(t, true);
        log(by.email, "reset the login of " + t.email);
        return link(t, KIND_SETUP, LINK_MS);
    }

    /** A lost phone (D283): the authenticator and backup codes are cleared; at their next login they set up a new one. */
    public void resetAuthenticator(Account by, long id) throws Exception {
        locked(id, () -> {
            resetAuthenticatorLocked(by, id);
            return null;
        });
    }

    private void resetAuthenticatorLocked(Account by, long id) throws Exception {
        Account t = target(by, id);
        clearLogin(t, false);
        log(by.email, "reset the authenticator of " + t.email);
    }

    /** The console's reset for anyone, Owners included (D283): the authenticator, or with {@code password} the whole login and a new link. */
    public Optional<NewLink> resetFromConsole(String email, boolean password) throws Exception {
        Account found = store.accountByEmail(normalEmail(email)).filter(a -> a.active).orElseThrow(() -> new Refused("No such staff member."));
        return locked(found.id, () -> resetFromConsoleLocked(found.id, password));
    }

    private Optional<NewLink> resetFromConsoleLocked(long id, boolean password) throws Exception {
        Account t = store.account(id).filter(a -> a.active).orElseThrow(() -> new Refused("No such staff member."));
        clearLogin(t, password);
        log(CONSOLE, (password ? "reset the login of " : "reset the authenticator of ") + t.email);
        return password ? Optional.of(link(t, KIND_SETUP, LINK_MS)) : Optional.empty();
    }

    private void clearLogin(Account t, boolean password) throws Exception {
        t.totp = null;
        t.totpStep = -1;
        t.backup = new ArrayList<>();
        t.failed = 0;
        t.lockedUntil = 0;
        if (password) {
            t.password = null;
        }
        store.update(t);
        store.deleteSessionsOf(t.id);
        store.deleteLinksOf(t.id);
    }

    public void unlock(Account by, long id) throws Exception {
        locked(id, () -> {
            unlockLocked(by, id);
            return null;
        });
    }

    private void unlockLocked(Account by, long id) throws Exception {
        Account t = target(by, id);
        t.failed = 0;
        t.lockedUntil = 0;
        store.update(t);
        log(by.email, "unlocked " + t.email);
    }

    public void setRank(Account by, long id, Rank rank) throws Exception {
        locked(id, () -> {
            setRankLocked(by, id, rank);
            return null;
        });
    }

    private void setRankLocked(Account by, long id, Rank rank) throws Exception {
        Account t = target(by, id);
        if (rank == Rank.OWNER) {
            throw new Refused("Owners are made from the panel's console.");
        }
        Rank was = t.rank;
        t.rank = rank;
        store.update(t);
        store.deleteSessionsOf(t.id); // logs in again at the new rank, for the new length
        log(by.email, "changed " + t.email + " from " + was.label() + " to " + rank.label());
    }

    public void remove(Account by, long id) throws Exception {
        locked(id, () -> {
            removeLocked(by, id);
            return null;
        });
    }

    private void removeLocked(Account by, long id) throws Exception {
        Account t = target(by, id);
        t.active = false;
        store.update(t);
        store.deleteSessionsOf(t.id);
        store.deleteLinksOf(t.id);
        log(by.email, "removed " + t.email);
    }

    // ------------------------------------------------------------------ setup through a link

    /** The account a setup link is for, while it is unused and in time. */
    public Optional<Account> setupLinkAccount(String token) throws Exception {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        Optional<PanelStore.Link> l = store.peekLink(box.fingerprint(token));
        if (l.isEmpty() || !l.get().kind().equals(KIND_SETUP) || l.get().expiresAt() < clock.getAsLong()) {
            return Optional.empty();
        }
        return store.account(l.get().accountId()).filter(a -> a.active);
    }

    /**
     * The setup page sent back: the new password, the authenticator secret the page showed and a code from the app to
     * prove it was scanned. The link works once and only for the Cloudflare identity it was made for.
     */
    public Done completeSetup(String token, String accessEmail, String password, String secret, String code) throws Exception {
        Account first = setupLinkAccount(token).orElseThrow(() -> new Refused("This link has expired or was used. Ask an Owner for a new one."));
        return locked(first.id, () -> completeSetupLocked(token, accessEmail, password, secret, code));
    }

    private Done completeSetupLocked(String token, String accessEmail, String password, String secret, String code) throws Exception {
        Account a = setupLinkAccount(token).orElseThrow(() -> new Refused("This link has expired or was used. Ask an Owner for a new one."));
        if (!a.email.equals(normalEmail(accessEmail))) {
            throw new Refused("This link is for another email than the one you signed in to Cloudflare with.");
        }
        String problem = Passwords.problem(password);
        if (problem != null) {
            throw new Refused("The password needs " + problem + ".");
        }
        long step = checkNewSecret(secret, code);
        if (store.takeLink(box.fingerprint(token)).isEmpty()) {
            throw new Refused("This link has expired or was used. Ask an Owner for a new one.");
        }
        a.password = Passwords.hash(password, passwordCost);
        a.previousLoginAt = a.lastLoginAt;
        a.lastLoginAt = clock.getAsLong(); // setting up logs them in
        Done d = finishAuthenticator(a, secret, step);
        log(a.email, "set up their login");
        return d;
    }

    private long checkNewSecret(String secret, String code) throws Refused {
        if (secret == null || !SECRET.matcher(secret).matches()) {
            throw new Refused("The authenticator code on the page is not right; load the page again.");
        }
        long step = Totp.verify(secret, code, clock.getAsLong(), -1);
        if (step < 0) {
            throw new Refused("That code does not match. Check the time on your phone and try the newest code.");
        }
        return step;
    }

    private Done finishAuthenticator(Account a, String secret, long step) throws Exception {
        a.totp = box.seal(secret);
        a.totpStep = step;
        List<String> codes = Tokens.backupCodes(BACKUP_CODES);
        a.backup = new ArrayList<>(codes.stream().map(box::fingerprint).toList());
        a.failed = 0;
        a.lockedUntil = 0;
        store.update(a);
        store.deleteSessionsOf(a.id);
        return new Done(openSession(a), a, codes);
    }

    // ------------------------------------------------------------------ logging in

    /**
     * A login for the person Cloudflare says this is: their password, then either a 6-digit code from the app or one of
     * their backup codes (each works once).
     */
    public Login login(String accessEmail, String password, String code) throws Exception {
        Optional<Account> first = store.accountByEmail(normalEmail(accessEmail));
        if (first.isEmpty()) {
            return loginLocked(accessEmail, password, code);
        }
        return locked(first.get().id, () -> loginLocked(accessEmail, password, code));
    }

    private Login loginLocked(String accessEmail, String password, String code) throws Exception {
        long now = clock.getAsLong();
        String email = normalEmail(accessEmail);
        Optional<Account> found = store.accountByEmail(email).filter(a -> a.active && a.password != null);
        if (found.isEmpty()) {
            log(email, "tried to log in without a set-up account");
            return new Login(Outcome.UNKNOWN, null, null, 0, 0);
        }
        Account a = found.get();
        if (a.lockedUntil > now) {
            return new Login(Outcome.LOCKED, null, a, a.lockedUntil, a.backup.size());
        }
        boolean passwordOk = Passwords.verify(password == null ? "" : password, a.password);
        if (passwordOk && a.totp == null) { // the authenticator was reset: set up a new one now
            a.failed = 0;
            store.update(a);
            NewLink step = link(a, KIND_ENROL, ENROL_MS);
            log(a.email, "logged in with their password to set up a new authenticator");
            return new Login(Outcome.ENROL, step.token(), a, 0, 0);
        }
        boolean codeOk = false;
        if (passwordOk) {
            long step = Totp.verify(openSecret(a), code, now, a.totpStep);
            if (step >= 0) {
                a.totpStep = step;
                codeOk = true;
            } else {
                String fp = box.fingerprint(Tokens.normalBackupCode(code));
                if (a.backup.remove(fp)) {
                    codeOk = true;
                    log(a.email, "used a backup code (" + a.backup.size() + " left)");
                }
            }
        }
        if (!codeOk) {
            a.failed++;
            if (a.failed >= MAX_FAILS) {
                a.failed = 0;
                a.lockedUntil = now + LOCK_MS;
                store.update(a);
                log(a.email, "locked out for 15 minutes after " + MAX_FAILS + " wrong tries");
                return new Login(Outcome.LOCKED, null, a, a.lockedUntil, a.backup.size());
            }
            store.update(a);
            return new Login(Outcome.WRONG, null, a, 0, a.backup.size());
        }
        a.failed = 0;
        a.previousLoginAt = a.lastLoginAt;
        a.lastLoginAt = now;
        store.update(a);
        log(a.email, "logged in");
        return new Login(Outcome.OK, openSession(a), a, 0, a.backup.size());
    }

    private String openSecret(Account a) {
        try {
            return box.open(a.totp);
        } catch (GeneralSecurityException e) {
            return ""; // sealed with another key: no code can match; an Owner resets it
        }
    }

    /** The new authenticator after a reset (the ENROL step from {@link #login}). */
    public Done completeEnrol(String token, String accessEmail, String secret, String code) throws Exception {
        Optional<PanelStore.Link> l = token == null ? Optional.empty() : store.peekLink(box.fingerprint(token));
        if (l.isEmpty()) {
            throw new Refused("That took too long. Log in again.");
        }
        return locked(l.get().accountId(), () -> completeEnrolLocked(token, accessEmail, secret, code));
    }

    private Done completeEnrolLocked(String token, String accessEmail, String secret, String code) throws Exception {
        Optional<PanelStore.Link> l = token == null ? Optional.empty() : store.peekLink(box.fingerprint(token));
        if (l.isEmpty() || !l.get().kind().equals(KIND_ENROL) || l.get().expiresAt() < clock.getAsLong()) {
            throw new Refused("That took too long. Log in again.");
        }
        Account a = store.account(l.get().accountId()).filter(x -> x.active).orElseThrow(() -> new Refused("That took too long. Log in again."));
        if (!a.email.equals(normalEmail(accessEmail))) {
            throw new Refused("That took too long. Log in again.");
        }
        long step = checkNewSecret(secret, code);
        if (store.takeLink(box.fingerprint(token)).isEmpty()) {
            throw new Refused("That took too long. Log in again.");
        }
        a.previousLoginAt = a.lastLoginAt;
        a.lastLoginAt = clock.getAsLong();
        Done d = finishAuthenticator(a, secret, step);
        log(a.email, "set up a new authenticator");
        return d;
    }

    private String openSession(Account a) throws Exception {
        String token = Tokens.token();
        long now = clock.getAsLong();
        store.saveSession(new PanelStore.Session(box.fingerprint(token), a.id, now, now + a.rank.session().toMillis()));
        return token;
    }

    /**
     * Who a session belongs to, while it lasts and only for the same Cloudflare identity (a session taken to another
     * identity is ended and logged).
     */
    public Optional<Account> check(String token, String accessEmail) throws Exception {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        String fp = box.fingerprint(token);
        Optional<PanelStore.Session> s = store.session(fp);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        Optional<Account> a = store.account(s.get().accountId()).filter(x -> x.active && x.setUp());
        if (s.get().expiresAt() < clock.getAsLong() || a.isEmpty()) {
            store.deleteSession(fp);
            return Optional.empty();
        }
        if (!a.get().email.equals(normalEmail(accessEmail))) {
            store.deleteSession(fp);
            log(normalEmail(accessEmail), "used the login of " + a.get().email + " under another Cloudflare identity; that login was ended");
            return Optional.empty();
        }
        return a;
    }

    public void logout(String token) throws Exception {
        if (token != null && !token.isEmpty()) {
            store.deleteSession(box.fingerprint(token));
        }
    }

    /** Lock-outs since a time (an Owner sees those since their previous visit, D282). */
    public List<PanelStore.LogLine> lockoutsSince(long sinceMs) throws Exception {
        return store.logStarting(sinceMs, "locked out", 200);
    }

    /** A new authenticator secret for a setup page, and the address its QR code holds. */
    public static String[] newAuthenticator(String accountEmail) {
        String secret = Totp.newSecret();
        return new String[]{secret, Totp.uri("BSP Staff", accountEmail, secret)};
    }

    static boolean validSecret(String s) {
        try {
            return s != null && SECRET.matcher(s).matches() && Base32.decode(s).length == Totp.SECRET_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
