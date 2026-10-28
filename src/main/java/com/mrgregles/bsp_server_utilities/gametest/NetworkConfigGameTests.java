package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.ServerUtilsNetworkConfig;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.storage.StorageSettings;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Network passwords out of the synced server config (owner decisions D183 to D187): the one-time move from
 * {@code bsp_server_utilities-server.toml} to {@code config/bsp_server_utilities-network.toml} (a value already set there wins,
 * the old place is cleared, the log line never carries a value, a second start moves nothing); the old names answering
 * from the new file; BSP-Core's moved settings still read through its old field names, and the notes naming both files.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class NetworkConfigGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    /** A config value and what it held, to put back afterwards. */
    private record Was<T>(ForgeConfigSpec.ConfigValue<T> value, T held) {
        static <T> Was<T> of(ForgeConfigSpec.ConfigValue<T> v) {
            return new Was<>(v, v.get());
        }

        void put() {
            value.set(held);
        }
    }

    private static List<Was<?>> snapshot() {
        List<Was<?>> out = new ArrayList<>();
        for (ForgeConfigSpec.ConfigValue<?> v : List.of(ServerUtilsConfig.OLD_STORAGE_HOST, ServerUtilsConfig.OLD_STORAGE_PORT, ServerUtilsConfig.OLD_STORAGE_DATABASE,
                ServerUtilsConfig.OLD_STORAGE_USER, ServerUtilsConfig.OLD_STORAGE_PASSWORD, ServerUtilsConfig.OLD_STORAGE_SSL, ServerUtilsConfig.OLD_STORAGE_TABLE_PREFIX,
                ServerUtilsConfig.OLD_REDIS_HOST, ServerUtilsConfig.OLD_REDIS_PORT, ServerUtilsConfig.OLD_REDIS_USERNAME, ServerUtilsConfig.OLD_REDIS_PASSWORD,
                ServerUtilsNetworkConfig.DB_HOST, ServerUtilsNetworkConfig.DB_PORT, ServerUtilsNetworkConfig.DB_DATABASE, ServerUtilsNetworkConfig.DB_USER,
                ServerUtilsNetworkConfig.DB_PASSWORD, ServerUtilsNetworkConfig.DB_SSL, ServerUtilsNetworkConfig.DB_TABLE_PREFIX, ServerUtilsNetworkConfig.REDIS_HOST,
                ServerUtilsNetworkConfig.REDIS_PORT, ServerUtilsNetworkConfig.REDIS_USERNAME, ServerUtilsNetworkConfig.REDIS_PASSWORD)) {
            out.add(Was.of(v));
        }
        return out;
    }

    private static <T> void reset(ForgeConfigSpec.ConfigValue<T> v) {
        v.set(v.getDefault());
    }

    @GameTest(template = EMPTY)
    public void the_move_takes_old_settings_to_the_network_file_and_never_logs_a_password(GameTestHelper h) {
        List<Was<?>> before = snapshot();
        String marker = "t" + UUID.randomUUID().toString().substring(0, 8);
        String pw = "pw-" + marker, rpw = "rpw-" + marker, kept = "kept-" + marker;
        try {
            before.forEach(w -> reset(w.value()));
            ServerUtilsConfig.OLD_STORAGE_HOST.set("db." + marker + ".example");
            ServerUtilsConfig.OLD_STORAGE_PASSWORD.set(pw);
            ServerUtilsConfig.OLD_STORAGE_PORT.set(3307);
            ServerUtilsConfig.OLD_REDIS_PASSWORD.set(rpw);
            ServerUtilsNetworkConfig.REDIS_PASSWORD.set(kept);
            ServerUtilsNetworkConfig.Result r = ServerUtilsNetworkConfig.moveFromServerConfig();
            h.assertTrue(ServerUtilsNetworkConfig.DB_HOST.get().equals("db." + marker + ".example") && ServerUtilsNetworkConfig.DB_PASSWORD.get().equals(pw)
                    && ServerUtilsNetworkConfig.DB_PORT.get() == 3307, "the database settings moved to the network file");
            h.assertTrue(ServerUtilsNetworkConfig.REDIS_PASSWORD.get().equals(kept), "a value already set in the network file wins");
            h.assertTrue(ServerUtilsConfig.OLD_STORAGE_HOST.get().equals("localhost") && ServerUtilsConfig.OLD_STORAGE_PASSWORD.get().isEmpty()
                    && ServerUtilsConfig.OLD_STORAGE_PORT.get() == 3306 && ServerUtilsConfig.OLD_REDIS_PASSWORD.get().isEmpty(), "and the server config is cleared, including the one not copied");
            h.assertTrue(r.moved().equals(List.of("storage.host", "storage.port", "storage.password")) && r.kept().equals(List.of("storage.redis.password")),
                    "the move names what moved and what was kept: " + r.moved() + " / " + r.kept());
            String line = r.logLine();
            h.assertTrue(!line.contains(marker) && line.contains("storage.password") && line.contains("config/bsp_server_utilities-network.toml")
                    && line.contains("Change the database and Redis passwords"), "the log line names keys and files, never a value: " + line.replace(marker, "<marker>"));
            h.assertTrue(!ServerUtilsNetworkConfig.moveFromServerConfig().any(), "a second start moves nothing");
            h.assertTrue(ServerUtilsConfig.STORAGE_PASSWORD == ServerUtilsNetworkConfig.DB_PASSWORD && ServerUtilsConfig.REDIS_PASSWORD == ServerUtilsNetworkConfig.REDIS_PASSWORD
                    && StorageSettings.ownFromConfig().password().equals(pw), "the old names read the network file");
        } finally {
            before.forEach(Was::put);
            ServerUtilsNetworkConfig.SPEC.save();
            ServerUtilsConfig.SPEC.save();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void bsp_core_s_moved_settings_still_answer_and_the_notes_name_both_files(GameTestHelper h) throws Exception {
        Class<?> network = null;
        try {
            network = BspCore.isLoaded() ? Class.forName("com.mrgregles.bsp_core.storage.NetworkConfig") : null;
        } catch (ClassNotFoundException e) {
            // a BSP-Core before 1.6.2 keeps its settings in its server config: nothing moved to check
        }
        if (network != null) {
            Object host = ((ForgeConfigSpec.ConfigValue<?>) network.getField("DB_HOST").get(null)).get();
            Optional<String> read = BspCore.setting("STORAGE_HOST", String.class);
            h.assertTrue(read.isPresent() && read.get().equals(host), "BSP-Core's STORAGE_HOST answers from its config/bsp_core-network.toml");
            h.assertTrue(BspCore.setting("STORAGE_PASSWORD", String.class).isPresent() && BspCore.setting("REDIS_PASSWORD", String.class).isPresent()
                    && BspCore.setting("REDIS_ENABLED", Boolean.class).isPresent() && BspCore.setting("STORAGE_MODE", String.class).isPresent(),
                    "and the other fields this mod reads still answer");
        }
        StorageSettings.Own own = new StorageSettings.Own("mysql", true, "h", 3306, "d", "u", "p", false, "bsp_su_", "alpha", 5, true, false, "rh", 6379, "", "rp", "network", 30);
        Map<String, Object> core = Map.of("STORAGE_MODE", "mysql", "STORAGE_HOST", "core-db", "REDIS_ENABLED", true, "REDIS_HOST", "core-redis");
        StorageSettings.Resolved fromCore = StorageSettings.resolve(own, (f, t) -> Optional.ofNullable(core.get(f)).filter(t::isInstance));
        h.assertTrue(fromCore.notes().contains("database settings from BSP-Core's config/bsp_core-network.toml")
                && fromCore.notes().contains("Redis settings from BSP-Core's config/bsp_core-network.toml"), "the notes name BSP-Core's network file: " + fromCore.notes());
        StorageSettings.Own mine = new StorageSettings.Own("mysql", false, "h", 3306, "d", "u", "p", false, "bsp_su_", "alpha", 5, false, true, "rh", 6379, "", "rp", "network", 30);
        StorageSettings.Resolved fromOwn = StorageSettings.resolve(mine, (f, t) -> Optional.empty());
        h.assertTrue(fromOwn.notes().contains("database settings from config/bsp_server_utilities-network.toml")
                && fromOwn.notes().contains("Redis settings from config/bsp_server_utilities-network.toml"), "and this mod's own: " + fromOwn.notes());
        h.succeed();
    }
}
