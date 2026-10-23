package com.mrgregles.bsp_server_utilities;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * The server config: what must exist before the shared storage is up (the database, Redis and this server's name) and
 * the staff groups. With BSP-Core installed the database, Redis and server name are read from BSP-Core's own config by
 * default (owner decision D5), so a network that already runs BSP-Core needs nothing filled in here.
 */
public final class ServerUtilsConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> STORAGE_MODE, STORAGE_HOST, STORAGE_DATABASE, STORAGE_USER, STORAGE_PASSWORD, STORAGE_TABLE_PREFIX, STORAGE_SERVER_ID;
    public static final ForgeConfigSpec.IntValue STORAGE_PORT, STORAGE_POLL_SECONDS;
    public static final ForgeConfigSpec.BooleanValue STORAGE_USE_BSP_CORE, STORAGE_SSL;
    public static final ForgeConfigSpec.BooleanValue REDIS_ENABLED, REDIS_USE_BSP_CORE;
    public static final ForgeConfigSpec.ConfigValue<String> REDIS_HOST, REDIS_USERNAME, REDIS_PASSWORD, REDIS_CHANNEL;
    public static final ForgeConfigSpec.IntValue REDIS_PORT, REDIS_SAFETY_POLL_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> STAFF_GROUPS;
    public static final ForgeConfigSpec.BooleanValue STAFF_OPERATORS;
    public static final ForgeConfigSpec.BooleanValue RULES_ENABLED;
    public static final ForgeConfigSpec.IntValue RULES_DELAY_TICKS, RULES_RESTORE_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.IntValue IMAGES_MAX_DOWNLOAD_KB, IMAGES_TIMEOUT_SECONDS, IMAGES_PIXELS_PER_BLOCK, IMAGES_CACHE_MB;
    public static final ForgeConfigSpec.IntValue LOG_KEEP_DAYS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Shared storage for the servers of the network: the rules and who agreed to them, the staff online and the action log.",
                "No MySQL driver is bundled: the server's own is used when it has one, else BSP-Core's full jar's (not its -slim jar).").push("storage");
        STORAGE_MODE = b.comment("auto: shared when BSP-Core's storage.mode is mysql, otherwise this server only.",
                "mysql: always shared (with the settings below or BSP-Core's). local: this server only, kept in the world.").define("mode", "auto");
        STORAGE_USE_BSP_CORE = b.comment("Use BSP-Core's database settings (host, port, database, user, password, SSL) when BSP-Core is installed.",
                "The tables are this mod's own either way (see tablePrefix).").define("useBspCoreDatabase", true);
        STORAGE_HOST = b.comment("Used when useBspCoreDatabase is false or BSP-Core is not installed.").define("host", "localhost");
        STORAGE_PORT = b.defineInRange("port", 3306, 1, 65535);
        STORAGE_DATABASE = b.define("database", "bsp");
        STORAGE_USER = b.define("user", "bsp");
        STORAGE_PASSWORD = b.comment("Plain text, as BSP-Core's; keep the config file private.").define("password", "");
        STORAGE_SSL = b.define("useSsl", false);
        STORAGE_TABLE_PREFIX = b.comment("Prefix for this mod's table names (letters, digits and underscores).").define("tablePrefix", "bsp_su_");
        STORAGE_SERVER_ID = b.comment("This server's name on the network, as the players see it on the staff panel and in the action log.",
                "Empty: BSP-Core's storage.serverId when BSP-Core is installed, else server-1. Keep it the same as BSP-Core's.").define("serverId", "");
        STORAGE_POLL_SECONDS = b.comment("How often notices from other servers are read from the table when Redis is off.").defineInRange("pollSeconds", 5, 1, 300);
        b.comment("Redis carries notices between servers within milliseconds (needs shared storage). Every notice is also written",
                "to the MySQL table, so nothing is lost while Redis is down. This mod has its own small Redis client; nothing is bundled.",
                "It speaks plain TCP: keep Redis on the local network.").push("redis");
        REDIS_USE_BSP_CORE = b.comment("Use BSP-Core's Redis settings (enabled, host, port, password) when BSP-Core is installed.",
                "If BSP-Core connects to Redis with TLS, this mod leaves Redis off and reads the table instead.").define("useBspCoreRedis", true);
        REDIS_ENABLED = b.comment("Used when useBspCoreRedis is false or BSP-Core is not installed.").define("enabled", false);
        REDIS_HOST = b.define("host", "localhost");
        REDIS_PORT = b.defineInRange("port", 6379, 1, 65535);
        REDIS_USERNAME = b.comment("Leave empty unless Redis uses ACL users; the password alone is the usual requirepass.").define("username", "");
        REDIS_PASSWORD = b.comment("Plain text; keep the config file private.").define("password", "");
        REDIS_CHANNEL = b.comment("Servers that share notices use the same channel. The mod adds the prefix bsp_su: to it.").define("channel", "network");
        REDIS_SAFETY_POLL_SECONDS = b.comment("How often the MySQL notice table is still read while Redis is connected.").defineInRange("safetyPollSeconds", 30, 5, 600);
        b.pop();
        b.pop();

        b.comment("Staff: the players shown on the staff-online panel and whose actions go into the action log (owner decision D4).").push("staff");
        STAFF_GROUPS = b.comment("LuckPerms groups that count as staff, highest first, each as group:Label:#RRGGBB,",
                        "for example \"admin:Admin:#B58CFF\" or \"mod:Moderator:#19D3B0\". Label and colour may be left out.")
                .defineListAllowEmpty(List.of("groups"), List::of, o -> o instanceof String);
        STAFF_OPERATORS = b.comment("Server operators (permission level 2 or more) count as staff too, labelled Operator.").define("operators", true);
        b.pop();

        b.comment("The rules popup (owner decisions D7 to D13): players agree once for the network; the rules are written in game",
                "(the RULES tab of /su, or /su rules add). Nobody is asked while there are no rules.").push("rules");
        RULES_ENABLED = b.comment("Master switch. When false nobody is asked or held; the rules and agreements are kept.").define("enabled", true);
        RULES_DELAY_TICKS = b.comment("Ticks after login before the popup shows, when BSP-InventorySync is not running (20 ticks = 1 second).")
                .defineInRange("delayTicks", 40, 0, 1200);
        RULES_RESTORE_TIMEOUT_TICKS = b.comment("With BSP-InventorySync running, the popup shows when the player's inventory has arrived,",
                "or after this many ticks if that never comes.").defineInRange("restoreTimeoutTicks", 400, 20, 6000);
        b.pop();

        b.comment("Pictures on Display Panels (owner decisions D18 to D21): the server fetches a web address or reads a file from",
                "config/bsp_server_utilities/images/, makes it a PNG no bigger than the panel needs and sends it to players in pieces;",
                "players never contact the address themselves. PNG, JPEG and GIF (the first frame) are read.").push("images");
        IMAGES_MAX_DOWNLOAD_KB = b.comment("The largest file fetched or read, in KB (4096 = 4 MB).").defineInRange("maxDownloadKB", 4096, 64, 32768);
        IMAGES_TIMEOUT_SECONDS = b.comment("How long a web address may take to answer.").defineInRange("timeoutSeconds", 10, 2, 60);
        IMAGES_PIXELS_PER_BLOCK = b.comment("At most this many pixels per block of the panel are kept (an 8 x 6 panel at 128 is 1024 x 768).")
                .defineInRange("pixelsPerBlock", 128, 16, 256);
        IMAGES_CACHE_MB = b.comment("How much picture data the server keeps in memory to send to players, in MB.").defineInRange("cacheMB", 64, 8, 1024);
        b.pop();

        b.comment("The action log (owner decisions D25 to D27): every command staff, operators, the console and RCON run (private",
                "messages never; admins switch single commands off in the LOG tab), items they take from the creative menu, gives",
                "and game-mode changes, shared by the network. Nobody can edit or delete an entry in game.").push("log");
        LOG_KEEP_DAYS = b.comment("Entries older than this are deleted (every hour, by every server).").defineInRange("keepDays", 7, 1, 90);
        b.pop();
        SPEC = b.build();
    }

    private ServerUtilsConfig() {
    }

    /** The value, or the fallback while the config is not loaded yet. */
    public static <T> T getOr(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            return value.get();
        } catch (IllegalStateException | NullPointerException e) {
            return fallback;
        }
    }
}
