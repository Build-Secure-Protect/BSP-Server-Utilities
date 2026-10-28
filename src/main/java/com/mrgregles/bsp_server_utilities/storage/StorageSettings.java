package com.mrgregles.bsp_server_utilities.storage;

import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Where the shared storage lives, worked out from this mod's configs and, when BSP-Core is installed, BSP-Core's
 * (owner decision D5): the connection from BSP-Core's {@code config/bsp_core-network.toml} or this mod's
 * {@code config/bsp_server_utilities-network.toml}, the switches from the server configs (D183 to D185). Pure: {@link #resolve} takes both as values, so the rules are tested without either config.
 *
 * <p>The rules: {@code mode = auto} shares when BSP-Core's {@code storage.mode} is {@code mysql}; {@code mysql} always
 * shares; anything else keeps to this server. The database comes from BSP-Core when {@code useBspCoreDatabase} is on
 * and BSP-Core answers, else from this mod's own keys; the table prefix is always this mod's. The server id is this
 * mod's when set, else BSP-Core's, else {@code server-1}. Redis follows BSP-Core's switch and address when
 * {@code useBspCoreRedis} is on, except that a TLS Redis is left off (this client speaks plain TCP); Redis is only
 * used with shared storage.
 */
public final class StorageSettings {
    public static final String DEFAULT_SERVER_ID = "server-1";
    /** Where the connection settings are read from, as the log and {@code /su status} name them (D185). */
    public static final String CORE_FILE = "BSP-Core's config/bsp_core-network.toml", OWN_FILE = "config/bsp_server_utilities-network.toml";

    /** This mod's own config values. */
    public record Own(String mode, boolean useBspCoreDatabase, String host, int port, String database, String user, String password, boolean ssl,
                      String tablePrefix, String serverId, int pollSeconds,
                      boolean useBspCoreRedis, boolean redisEnabled, String redisHost, int redisPort, String redisUsername, String redisPassword,
                      String redisChannel, int safetyPollSeconds) {
    }

    /** A MySQL database to connect to. */
    public record Database(String host, int port, String database, String user, String password, boolean ssl, String tablePrefix) {
        public String describe() {
            return "mysql " + host + ":" + port + "/" + database + " as " + user + ", prefix " + tablePrefix;
        }
    }

    /**
     * The answer: {@code database} is null when nothing is shared; {@code redis} is null when Redis is not used;
     * {@code notes} say which choices were taken from BSP-Core and anything left off, for the log and the status command.
     */
    public record Resolved(@Nullable Database database, String serverId, int pollSeconds, @Nullable RedisLink.Settings redis, int safetyPollSeconds, List<String> notes) {
        public boolean shared() {
            return database != null;
        }
    }

    private StorageSettings() {
    }

    /**
     * Works the storage out. {@code core} answers a BSP-Core config field by name and type (empty when BSP-Core is
     * absent or the field cannot be read).
     */
    public static Resolved resolve(Own own, BiFunction<String, Class<?>, Optional<?>> core) {
        List<String> notes = new ArrayList<>();
        String coreMode = string(core, "STORAGE_MODE").orElse("");
        String mode = own.mode().trim().toLowerCase(Locale.ROOT);
        boolean shared = switch (mode) {
            case "mysql" -> true;
            case "auto" -> "mysql".equalsIgnoreCase(coreMode);
            default -> false;
        };
        if (mode.equals("auto")) {
            notes.add(coreMode.isEmpty() ? "mode auto: BSP-Core not installed, this server only" : "mode auto: BSP-Core's storage.mode is " + coreMode);
        }

        String serverId = own.serverId().trim();
        if (serverId.isEmpty()) {
            Optional<String> fromCore = string(core, "STORAGE_SERVER_ID").filter(s -> !s.isBlank());
            serverId = fromCore.orElse(DEFAULT_SERVER_ID);
            notes.add(fromCore.isPresent() ? "server id from BSP-Core" : "server id defaulted");
        }

        Database database = null;
        if (shared) {
            Optional<String> coreHost = string(core, "STORAGE_HOST");
            if (own.useBspCoreDatabase() && coreHost.isPresent()) {
                database = new Database(coreHost.get(),
                        integer(core, "STORAGE_PORT").orElse(own.port()),
                        string(core, "STORAGE_DATABASE").orElse(own.database()),
                        string(core, "STORAGE_USER").orElse(own.user()),
                        string(core, "STORAGE_PASSWORD").orElse(own.password()),
                        bool(core, "STORAGE_SSL").orElse(own.ssl()),
                        own.tablePrefix());
                notes.add("database settings from " + CORE_FILE);
            } else {
                database = new Database(own.host(), own.port(), own.database(), own.user(), own.password(), own.ssl(), own.tablePrefix());
                notes.add("database settings from " + OWN_FILE);
            }
        }

        RedisLink.Settings redis = null;
        if (shared) {
            Optional<Boolean> coreEnabled = bool(core, "REDIS_ENABLED");
            if (own.useBspCoreRedis() && coreEnabled.isPresent()) {
                notes.add("Redis settings from " + CORE_FILE);
                if (coreEnabled.get()) {
                    if (bool(core, "REDIS_SSL").orElse(false)) {
                        notes.add("BSP-Core reaches Redis with TLS, which this mod's client does not speak: Redis left off, the table carries notices");
                    } else {
                        redis = new RedisLink.Settings(string(core, "REDIS_HOST").orElse(own.redisHost()), integer(core, "REDIS_PORT").orElse(own.redisPort()),
                                "", string(core, "REDIS_PASSWORD").orElse(own.redisPassword()), own.redisChannel());
                    }
                }
            } else if (own.redisEnabled()) {
                redis = new RedisLink.Settings(own.redisHost(), own.redisPort(), own.redisUsername(), own.redisPassword(), own.redisChannel());
                notes.add("Redis settings from " + OWN_FILE);
            }
        } else if (own.redisEnabled()) {
            notes.add("redis.enabled is on but nothing is shared: Redis not used");
        }
        return new Resolved(database, serverId, own.pollSeconds(), redis, own.safetyPollSeconds(), List.copyOf(notes));
    }

    /** This mod's config as it stands now. */
    public static Own ownFromConfig() {
        return new Own(
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_MODE, "auto"),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_USE_BSP_CORE, true),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_HOST, "localhost"),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_PORT, 3306),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_DATABASE, "bsp"),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_USER, "bsp"),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_PASSWORD, ""),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_SSL, false),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_TABLE_PREFIX, "bsp_su_"),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_SERVER_ID, ""),
                ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_POLL_SECONDS, 5),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_USE_BSP_CORE, true),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_ENABLED, false),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_HOST, "localhost"),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_PORT, 6379),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_USERNAME, ""),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_PASSWORD, ""),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_CHANNEL, "network"),
                ServerUtilsConfig.getOr(ServerUtilsConfig.REDIS_SAFETY_POLL_SECONDS, 30));
    }

    /** Both configs as they stand now. */
    public static Resolved current() {
        return resolve(ownFromConfig(), (field, type) -> BspCore.setting(field, type));
    }

    private static Optional<String> string(BiFunction<String, Class<?>, Optional<?>> core, String field) {
        return core.apply(field, String.class).map(String.class::cast);
    }

    private static Optional<Integer> integer(BiFunction<String, Class<?>, Optional<?>> core, String field) {
        return core.apply(field, Integer.class).map(Integer.class::cast);
    }

    private static Optional<Boolean> bool(BiFunction<String, Class<?>, Optional<?>> core, String field) {
        return core.apply(field, Boolean.class).map(Boolean.class::cast);
    }
}
