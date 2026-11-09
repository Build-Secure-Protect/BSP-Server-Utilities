package com.mrgregles.bsp_staff_panel;

import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.Base32;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.crypto.Totp;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import com.mrgregles.bsp_staff_panel.web.Heads;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixes from the 2026-11-09 audit, each held by a test. */
class AuditTest {
    private static final String PW = "frost-wolf-lantern-9";

    private static String code(String secret, long now) {
        return Totp.format(Totp.code(Base32.decode(secret), Totp.step(now)));
    }

    @Test
    void wrong_tries_at_the_same_moment_are_all_counted_and_a_code_works_once() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        Auth auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 20_000); // a slow hash, as in real use
        Auth.NewLink link = auth.createOwner("owner@bsp.test", "Mrgregles");
        String secret = Auth.newAuthenticator("x")[0];
        auth.completeSetup(link.token(), "owner@bsp.test", PW, secret, code(secret, clock.get()));
        clock.addAndGet(31_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Auth.Login>> tries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tries.add(pool.submit(() -> {
                go.await();
                return auth.login("owner@bsp.test", "wrong password!!", "000000");
            }));
        }
        go.countDown();
        int locked = 0;
        for (Future<Auth.Login> f : tries) {
            if (f.get().outcome() == Auth.Outcome.LOCKED) {
                locked++;
            }
        }
        assertTrue(locked >= 4, "eight tries at once: the fifth locks and the rest find it locked (" + locked + " locked)");
        assertTrue(store.accountByEmail("owner@bsp.test").orElseThrow().lockedUntil > clock.get(), "the account is locked");

        clock.addAndGet(Auth.LOCK_MS + 1);
        String c = code(secret, clock.get());
        CountDownLatch go2 = new CountDownLatch(1);
        List<Future<Auth.Login>> same = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            same.add(pool.submit(() -> {
                go2.await();
                return auth.login("owner@bsp.test", PW, c);
            }));
        }
        go2.countDown();
        int ok = 0;
        for (Future<Auth.Login> f : same) {
            if (f.get().outcome() == Auth.Outcome.OK) {
                ok++;
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "one code, four logins at once: only one gets in");
    }

    @Test
    void a_removal_during_a_login_is_not_undone() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        Auth auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 200_000);
        Auth.NewLink o = auth.createOwner("owner@bsp.test", "Mrgregles");
        String s1 = Auth.newAuthenticator("x")[0];
        auth.completeSetup(o.token(), "owner@bsp.test", PW, s1, code(s1, clock.get()));
        Account owner = store.accountByEmail("owner@bsp.test").orElseThrow();
        Auth.NewLink m = auth.addStaff(owner, "mod@bsp.test", "Ana_Mod", Rank.MODERATOR);
        String s2 = Auth.newAuthenticator("y")[0];
        auth.completeSetup(m.token(), "mod@bsp.test", PW, s2, code(s2, clock.get()));
        clock.addAndGet(31_000);
        long id = store.accountByEmail("mod@bsp.test").orElseThrow().id;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Auth.Login> login = pool.submit(() -> auth.login("mod@bsp.test", PW, code(s2, clock.get())));
        Thread.sleep(5); // the login is hashing the password
        auth.remove(owner, id);
        login.get();
        pool.shutdown();
        assertFalse(store.account(id).orElseThrow().active, "the removal stands, whichever finished first");
        assertEquals(Auth.Outcome.UNKNOWN, auth.login("mod@bsp.test", PW, code(s2, clock.get() + 30_000)).outcome());
    }

    @Test
    void lock_outs_are_found_among_many_log_lines_and_an_unreadable_secret_still_takes_backup_codes() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        Auth auth = new Auth(store, new SecretBox(SecretBox.newKey()), clock::get, 1000);
        store.log(new PanelStore.LogLine(clock.get(), "a@b.test", "locked out for 15 minutes after 5 wrong tries"));
        for (int i = 0; i < 500; i++) {
            store.log(new PanelStore.LogLine(clock.get() + 1 + i, "x@b.test", "tried to log in without a set-up account"));
        }
        assertEquals(1, auth.lockoutsSince(0).size(), "500 other lines do not hide it");

        Auth.NewLink o = auth.createOwner("owner@bsp.test", "Mrgregles");
        String s = Auth.newAuthenticator("x")[0];
        Auth.Done d = auth.completeSetup(o.token(), "owner@bsp.test", PW, s, code(s, clock.get()));
        Account a = store.accountByEmail("owner@bsp.test").orElseThrow();
        a.totp = new SecretBox(SecretBox.newKey()).seal(s); // sealed with another key (a lost panel.properties)
        store.update(a);
        clock.addAndGet(31_000);
        assertEquals(Auth.Outcome.WRONG, auth.login("owner@bsp.test", PW, code(s, clock.get())).outcome(), "no error: the code cannot match");
        assertEquals(Auth.Outcome.OK, auth.login("owner@bsp.test", PW, d.backupCodes().get(0)).outcome(), "a backup code still works");
    }

    @Test
    void the_feed_takes_only_snapshots_signed_with_the_panel_s_key() {
        byte[] key = new byte[32];
        key[5] = 7;
        Feed f = new Feed(System::currentTimeMillis, key);
        String json = "{\"v\":1,\"server\":\"Spawn\",\"at\":" + System.currentTimeMillis() + ",\"players\":[{\"id\":\"a\",\"name\":\"Fake\"}]}";
        assertFalse(f.accept("Spawn", System.currentTimeMillis(), json), "unsigned: dropped");
        byte[] other = key.clone();
        other[0] = 1;
        assertFalse(f.accept("Spawn", System.currentTimeMillis(), Signed.seal(other, json)), "another key: dropped");
        assertTrue(f.acceptMessage("id\n1\nSpawn\nsnapshot\n" + Signed.seal(key, json)), "signed with ours: taken");
        assertEquals("Fake", f.json().getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(json, Signed.open(key, Signed.seal(key, json)));
        String other2 = "{\"v\":1,\"server\":\"Spawn\",\"at\":" + System.currentTimeMillis() + ",\"players\":[]}";
        assertFalse(f.accept("AAA", System.currentTimeMillis(), Signed.seal(key, other2)), "Spawn's signed snapshot under another server's name: dropped (audit)");
        String old = "{\"v\":1,\"server\":\"Frostwarf\",\"at\":" + (System.currentTimeMillis() - 5 * Feed.STALE_MS) + ",\"players\":[]}";
        assertFalse(f.accept("Frostwarf", System.currentTimeMillis(), Signed.seal(key, old)), "an old signed snapshot sent again: dropped");
    }

    @Test
    void a_skin_that_cannot_be_had_is_not_fetched_again_for_a_while() {
        AtomicInteger fetches = new AtomicInteger();
        Heads h = new Heads(u -> {
            fetches.incrementAndGet();
            return null;
        });
        String url = "https://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef";
        for (int i = 0; i < 20; i++) {
            assertFalse(h.head(url).isPresent());
        }
        assertEquals(1, fetches.get(), "twenty redraws, one fetch");
        assertFalse(Heads.allowed("https://textures.minecraft.net:8443/texture/0123456789abcdef0123456789abcdef"), "another port");
        assertTrue(Heads.allowed("https://textures.minecraft.net/texture/0123456789ABCDEF0123456789abcdef"), "capitals as the game servers allow");
    }
}
