package com.mrgregles.bsp_server_utilities;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Where this mod's own network database and Redis are, and their passwords: {@code config/bsp_server_utilities-network.toml},
 * a COMMON config (owner decisions D183, D184). Forge sends a server's SERVER config file to every player who joins, so these
 * settings must never be in {@code bsp_server_utilities-server.toml}. The same keys are still read there once
 * ({@link #moveFromServerConfig}): a value found there is copied here at the start and cleared there. Used only when this
 * mod does not borrow BSP-Core's settings (useBspCoreDatabase, useBspCoreRedis) or BSP-Core is not installed.
 */
public final class ServerUtilsNetworkConfig {
    public static final String FILE = "bsp_server_utilities-network.toml";

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> DB_HOST, DB_DATABASE, DB_USER, DB_PASSWORD, DB_TABLE_PREFIX, REDIS_HOST, REDIS_USERNAME, REDIS_PASSWORD;
    public static final ForgeConfigSpec.IntValue DB_PORT, REDIS_PORT;
    public static final ForgeConfigSpec.BooleanValue DB_SSL;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("BSP-Server Utilities' own network connection: the MySQL or MariaDB database and Redis the servers share.",
                        "Used when shared storage is on (storage.mode in the server config) and useBspCoreDatabase / useBspCoreRedis are off,",
                        "or BSP-Core is not installed; otherwise BSP-Core's config/bsp_core-network.toml is used.",
                        "This file stays on the server: unlike the server config it is never sent to players. Keep it private,",
                        "and leave it out of any client pack.")
                .push("database");
        DB_HOST = b.comment("Database host name or address.").define("host", "localhost");
        DB_PORT = b.comment("Database port.").defineInRange("port", 3306, 1, 65535);
        DB_DATABASE = b.comment("Name of the existing database (schema) to use.").define("database", "bsp");
        DB_USER = b.comment("Database user. It needs CREATE, SELECT, INSERT, UPDATE and DELETE on that database.").define("user", "bsp");
        DB_PASSWORD = b.comment("Database password.").define("password", "");
        DB_SSL = b.comment("Connect with SSL/TLS.").define("useSsl", false);
        DB_TABLE_PREFIX = b.comment("Prefix for this mod's table names (letters, digits and underscores), used with BSP-Core's database too.")
                .define("tablePrefix", "bsp_su_");
        b.pop();
        b.comment("Redis, optional (switched on with redis.enabled in the server config).").push("redis");
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_USERNAME = b.comment("Leave empty unless Redis uses ACL users; the password alone is the usual requirepass.").define("username", "");
        REDIS_PASSWORD = b.comment("Password, if Redis has one.").define("password", "");
        b.pop();
        SPEC = b.build();
    }

    private ServerUtilsNetworkConfig() {
    }

    /** One setting that moved: its old place in the server config, its new one here, and its name in the old file. */
    private record Moved<T>(String name, ForgeConfigSpec.ConfigValue<T> old, ForgeConfigSpec.ConfigValue<T> now) {
        /** 0: nothing in the old place; 1: copied over; 2: one was already set here, which was kept. The old place is cleared either way. */
        int move() {
            T was = old.get();
            if (was.equals(old.getDefault())) {
                return 0;
            }
            int result = 2;
            if (now.get().equals(now.getDefault())) {
                now.set(was);
                result = 1;
            }
            old.set(old.getDefault());
            return result;
        }
    }

    /** What the move did: the names moved, the names left as they were here, and the log line (never a value). */
    public record Result(List<String> moved, List<String> kept, String logLine) {
        public boolean any() {
            return !moved.isEmpty() || !kept.isEmpty();
        }
    }

    private static List<Moved<?>> pairs() {
        return List.of(
                new Moved<>("storage.host", ServerUtilsConfig.OLD_STORAGE_HOST, DB_HOST),
                new Moved<>("storage.port", ServerUtilsConfig.OLD_STORAGE_PORT, DB_PORT),
                new Moved<>("storage.database", ServerUtilsConfig.OLD_STORAGE_DATABASE, DB_DATABASE),
                new Moved<>("storage.user", ServerUtilsConfig.OLD_STORAGE_USER, DB_USER),
                new Moved<>("storage.password", ServerUtilsConfig.OLD_STORAGE_PASSWORD, DB_PASSWORD),
                new Moved<>("storage.useSsl", ServerUtilsConfig.OLD_STORAGE_SSL, DB_SSL),
                new Moved<>("storage.tablePrefix", ServerUtilsConfig.OLD_STORAGE_TABLE_PREFIX, DB_TABLE_PREFIX),
                new Moved<>("storage.redis.host", ServerUtilsConfig.OLD_REDIS_HOST, REDIS_HOST),
                new Moved<>("storage.redis.port", ServerUtilsConfig.OLD_REDIS_PORT, REDIS_PORT),
                new Moved<>("storage.redis.username", ServerUtilsConfig.OLD_REDIS_USERNAME, REDIS_USERNAME),
                new Moved<>("storage.redis.password", ServerUtilsConfig.OLD_REDIS_PASSWORD, REDIS_PASSWORD));
    }

    /**
     * The one-time move (D184, D186): every connection setting still set in the server config is copied here (a value
     * already set here wins), cleared there, and both files are saved. Called at {@code ServerAboutToStartEvent}, when the
     * server config is loaded and nobody can have joined yet.
     */
    public static Result moveFromServerConfig() {
        List<String> moved = new ArrayList<>(), kept = new ArrayList<>();
        for (Moved<?> m : pairs()) {
            switch (m.move()) {
                case 1 -> moved.add(m.name());
                case 2 -> kept.add(m.name());
                default -> {
                }
            }
        }
        if (moved.isEmpty() && kept.isEmpty()) {
            return new Result(List.of(), List.of(), "");
        }
        SPEC.save();
        ServerUtilsConfig.SPEC.save();
        StringBuilder line = new StringBuilder("Moved ");
        List<String> all = new ArrayList<>(moved);
        all.addAll(kept);
        line.append(String.join(", ", all)).append(" from serverconfig/bsp_server_utilities-server.toml (which Forge sends to every player who joins) to config/")
                .append(FILE).append(", which stays on the server");
        if (!kept.isEmpty()) {
            line.append("; already set there, kept: ").append(String.join(", ", kept));
        }
        boolean secrets = all.contains("storage.password") || all.contains("storage.redis.password");
        line.append(secrets ? ". Change the database and Redis passwords: the old ones were sent to every player who joined." : ".");
        return new Result(List.copyOf(moved), List.copyOf(kept), line.toString());
    }

    /** {@code ServerAboutToStartEvent}: the move, and one warning when anything moved. */
    public static void onAboutToStart() {
        try {
            Result r = moveFromServerConfig();
            if (r.any()) {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: {}", r.logLine());
            }
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.error("BSP-Server Utilities: could not move the network settings to config/{}: {}", FILE, e.toString());
        }
    }
}
