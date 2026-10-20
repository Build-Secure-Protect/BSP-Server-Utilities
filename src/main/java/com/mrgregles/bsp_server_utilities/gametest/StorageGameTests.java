package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.RedisLink;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.storage.StorageSettings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The shared storage: where it lives (this mod's config against BSP-Core's, owner decision D5), notices between two
 * servers through the table on an in-memory backend, and through a fake Redis with the table as the safety net.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class StorageGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static StorageSettings.Own own(String mode, boolean useCoreDb, boolean useCoreRedis, boolean redisEnabled, String serverId) {
        return new StorageSettings.Own(mode, useCoreDb, "own-host", 3307, "own_db", "own_user", "own_pw", false, "bsp_su_", serverId, 5,
                useCoreRedis, redisEnabled, "own-redis", 6380, "", "own_rpw", "network", 30);
    }

    @GameTest(template = EMPTY)
    public void storage_settings_follow_bsp_core_and_fall_back_to_this_mods_own(GameTestHelper h) {
        Map<String, Object> core = new HashMap<>(Map.of(
                "STORAGE_MODE", "mysql", "STORAGE_SERVER_ID", "BSP-Wild", "STORAGE_HOST", "db.lan", "STORAGE_PORT", 3306,
                "STORAGE_DATABASE", "bsp", "STORAGE_USER", "core", "STORAGE_PASSWORD", "core_pw", "STORAGE_SSL", false,
                "REDIS_ENABLED", true, "REDIS_HOST", "redis.lan"));
        core.put("REDIS_PORT", 6379);
        core.put("REDIS_PASSWORD", "core_rpw");
        core.put("REDIS_SSL", false);
        java.util.function.BiFunction<String, Class<?>, Optional<?>> coreFn = (f, type) -> Optional.ofNullable(core.get(f)).filter(type::isInstance);
        java.util.function.BiFunction<String, Class<?>, Optional<?>> noCore = (f, type) -> Optional.empty();

        StorageSettings.Resolved r = StorageSettings.resolve(own("auto", true, true, false, ""), coreFn);
        h.assertTrue(r.shared() && r.database().host().equals("db.lan") && r.database().user().equals("core") && r.database().tablePrefix().equals("bsp_su_"),
                "auto follows BSP-Core's mysql, its database, our own prefix: " + r);
        h.assertTrue(r.serverId().equals("BSP-Wild"), "the server id comes from BSP-Core: " + r.serverId());
        h.assertTrue(r.redis() != null && r.redis().host().equals("redis.lan") && r.redis().password().equals("core_rpw") && r.redis().fullChannel().equals("bsp_su:network"),
                "Redis from BSP-Core on our own channel: " + r.redis());

        core.put("REDIS_SSL", true);
        r = StorageSettings.resolve(own("auto", true, true, false, ""), coreFn);
        h.assertTrue(r.shared() && r.redis() == null && r.notes().stream().anyMatch(n -> n.contains("TLS")), "a TLS Redis is left off, the table carries notices: " + r.notes());
        core.put("REDIS_SSL", false);

        r = StorageSettings.resolve(own("auto", false, false, true, "Spawn"), coreFn);
        h.assertTrue(r.database().host().equals("own-host") && r.database().port() == 3307 && r.serverId().equals("Spawn") && r.redis() != null && r.redis().host().equals("own-redis"),
                "with the switches off this mod's own settings win: " + r);

        core.put("STORAGE_MODE", "local");
        r = StorageSettings.resolve(own("auto", true, true, true, ""), coreFn);
        h.assertTrue(!r.shared() && r.redis() == null && r.serverId().equals("BSP-Wild"), "auto with BSP-Core local keeps to this server: " + r);

        r = StorageSettings.resolve(own("auto", true, true, false, ""), noCore);
        h.assertTrue(!r.shared() && r.serverId().equals(StorageSettings.DEFAULT_SERVER_ID), "auto without BSP-Core keeps to this server, server-1: " + r);
        r = StorageSettings.resolve(own("mysql", true, true, true, ""), noCore);
        h.assertTrue(r.shared() && r.database().host().equals("own-host") && r.redis() != null && r.redis().host().equals("own-redis"), "mysql without BSP-Core uses our own settings: " + r);
        r = StorageSettings.resolve(own("local", true, true, true, ""), coreFn);
        h.assertTrue(!r.shared(), "local is local whatever BSP-Core says");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void notices_reach_the_other_server_once_through_the_table(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        List<String> handled = new CopyOnWriteArrayList<>();
        Storage.on("su_test_table", (s, n) -> handled.add(n.source() + ":" + n.payload()));
        Storage alpha = Storage.startWith(server, db, "alpha");
        Storage beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        try {
            alpha.publish("su_test_table", "hello");
            alpha.drain();
            h.assertTrue(db.noticeCount() == 1, "the notice is in the table");
            alpha.pollNotices();
            alpha.drain();
            h.assertTrue(handled.isEmpty() && alpha.delivered() == 0, "the sender never acts on its own notice: " + handled);
            beta.pollNotices();
            beta.drain();
            h.assertTrue(handled.equals(List.of("alpha:hello")), "the other server's handler ran once with source and payload: " + handled);
            beta.pollNotices();
            beta.drain();
            h.assertTrue(handled.size() == 1 && beta.delivered() == 1, "reading the table again does not repeat it: " + handled);

            beta.publish("su_test_unknown", "x");
            beta.drain();
            alpha.pollNotices();
            alpha.drain();
            h.assertTrue(alpha.delivered() == 1 && handled.size() == 1, "a kind nobody handles is taken quietly");

            db.publish(new StorageBackend.Notice(0, UUID.randomUUID().toString(), 1000L, "gamma", "su_test_table", "old"));
            h.assertTrue(db.pruneNotices(System.currentTimeMillis() - 86_400_000L) == 1 && db.noticeCount() == 2, "pruning drops only notices older than the cut-off: " + db.noticeCount());
            db.setFailing(true);
            alpha.publish("su_test_table", "lost");
            alpha.drain();
            h.assertTrue(alpha.status().contains("1 errors"), "a database failure is counted, not thrown: " + alpha.status());
            db.setFailing(false);
        } finally {
            alpha.close();
            beta.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "storage-redis")
    public void notices_arrive_through_redis_and_through_the_table_when_redis_is_down(GameTestHelper h) throws IOException {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        FakeRedis redis = FakeRedis.start("secret");
        List<StorageBackend.Notice> got = new CopyOnWriteArrayList<>();
        Storage alpha = Storage.startWith(server, db, "alpha");
        Storage beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        RedisLink.Settings settings = new RedisLink.Settings("127.0.0.1", redis.port(), "", "secret", "su-test");
        alpha.connectRedis(settings);
        beta.connectRedis(settings);
        beta.onNotice(got::add);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(redis.subscribers(settings.fullChannel()) == 2 && alpha.redis().publishing() && beta.redis().subscribed(), "both connect and subscribe"))
                .thenExecute(() -> alpha.publish("su_test_redis", "first"))
                .thenWaitUntil(() -> h.assertTrue(got.size() == 1, "the other server gets it through Redis (no table read happened): " + got))
                .thenExecute(() -> {
                    h.assertTrue(got.get(0).source().equals("alpha") && got.get(0).payload().equals("first"), "source and payload carried: " + got.get(0));
                    alpha.drain(); // the table copy
                    beta.pollNotices();
                    beta.drain();
                    h.assertTrue(got.size() == 1, "the table's copy of the same notice is dropped: " + got.size());
                    redis.stop();
                })
                .thenWaitUntil(() -> h.assertTrue(!beta.redis().subscribed(), "the receiver notices the outage"))
                .thenExecute(() -> {
                    alpha.publish("su_test_redis", "second");
                    alpha.drain();
                    beta.pollNotices();
                    beta.drain();
                    h.assertTrue(got.size() == 2 && got.get(1).payload().equals("second"), "the table carried it: " + got);
                })
                .thenExecute(() -> {
                    alpha.close();
                    beta.close();
                    redis.close();
                })
                .thenSucceed();
    }
}
