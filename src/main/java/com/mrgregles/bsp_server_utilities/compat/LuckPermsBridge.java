package com.mrgregles.bsp_server_utilities.compat;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The only class that touches LuckPerms, copied from BSP RankUp's bridge (reads only, plus the members of a group).
 *
 * <p>Two ways in, as in BSP-Region Guard's bridge. On a Forge server with the LuckPerms Forge mod the API classes
 * are on our class path and {@code LuckPermsProvider.get()} is called directly. On a Mohist server with the
 * LuckPerms Bukkit plugin the plugin lives in its own class loader, so the bridge finds it through Bukkit's plugin
 * manager and loads the API from that loader. After that, <b>every call goes by reflection on both routes</b>,
 * through the same code, so the dev runs (Forge route, as the live servers use since the network moved to plain Forge)
 * also cover the Bukkit route, instead of a second copy that no test can reach.
 *
 * <p>Reads answer at once from LuckPerms's loaded data (online players are always loaded). {@link #membersOf} asks
 * LuckPerms's storage and completes on a LuckPerms thread: callers hop back to the server thread before touching the game.
 */
public final class LuckPermsBridge {
    private static final String PROVIDER = "net.luckperms.api.LuckPermsProvider";
    private static final String NODE_MATCHER = "net.luckperms.api.node.matcher.NodeMatcher";
    private static final long RETRY_NANOS = 5_000_000_000L;

    /** How LuckPerms was reached. */
    public enum Route { NONE, FORGE, BUKKIT }

    @Nullable
    private static Route route;
    /** The LuckPerms API object (a class from the loader below). */
    @Nullable
    private static Object api;
    /** The class loader the API classes live in: ours on Forge, the plugin's on Bukkit. */
    @Nullable
    private static ClassLoader loader;
    private static long lastMiss;

    private LuckPermsBridge() {
    }

    public static boolean isPresent() {
        return route() != Route.NONE;
    }

    /**
     * The route in use. A found route is cached; a miss is retried every few seconds, since LuckPerms may register
     * its provider after the first question is asked during start-up.
     */
    public static Route route() {
        if (route == null || (route == Route.NONE && System.nanoTime() - lastMiss > RETRY_NANOS)) {
            route = detect();
            if (route != Route.NONE) {
                BSPServerUtilities.LOGGER.info("LuckPerms reached through the {} edition", route == Route.FORGE ? "Forge" : "Bukkit");
            } else {
                lastMiss = System.nanoTime();
            }
        }
        return route;
    }

    private static Route detect() {
        try {
            Class<?> provider = Class.forName(PROVIDER);
            api = net.luckperms.api.LuckPermsProvider.get();
            loader = provider.getClassLoader();
            return Route.FORGE;
        } catch (ClassNotFoundException | NoClassDefFoundError | IllegalStateException e) {
            // not on our class path, or not registered yet: try Bukkit
        }
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);
            if (pluginManager == null) {
                return Route.NONE;
            }
            Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class).invoke(pluginManager, "LuckPerms");
            if (plugin == null) {
                return Route.NONE;
            }
            ClassLoader pluginLoader = plugin.getClass().getClassLoader();
            Class<?> provider = pluginLoader.loadClass(PROVIDER);
            api = provider.getMethod("get").invoke(null);
            loader = pluginLoader;
            return api == null ? Route.NONE : Route.BUKKIT;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.debug("LuckPerms not reachable through Bukkit: {}", e.toString());
            api = null;
            loader = null;
            return Route.NONE;
        }
    }

    /** Forgets the cached answer, for example after a reload. */
    public static void reset() {
        route = null;
        api = null;
        loader = null;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The groups a player is in, directly or by inheritance, lower-cased. Empty when LuckPerms is absent or the
     * user is not loaded (online players always are).
     */
    public static Set<String> groupsOf(UUID player) {
        if (!isPresent()) {
            return Collections.emptySet();
        }
        try {
            Object user = loadedUser(player);
            if (user == null) {
                return Collections.emptySet();
            }
            Set<String> out = new TreeSet<>();
            out.add(((String) call(user, "getPrimaryGroup")).toLowerCase(Locale.ROOT));
            Object queryOptions = call(user, "getQueryOptions");
            Collection<?> groups = (Collection<?>) call(user, "getInheritedGroups", queryOptions);
            for (Object g : groups) {
                out.add(((String) call(g, "getName")).toLowerCase(Locale.ROOT));
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("user lookup", e);
            return Collections.emptySet();
        }
    }

    /** The player's primary group, lower-cased; empty when LuckPerms is absent or the user is not loaded. */
    public static Optional<String> primaryGroupOf(UUID player) {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            Object user = loadedUser(player);
            return user == null ? Optional.empty() : Optional.of(((String) call(user, "getPrimaryGroup")).toLowerCase(Locale.ROOT));
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("primary group lookup", e);
            return Optional.empty();
        }
    }

    /** Names of every loaded group. Empty when LuckPerms is absent. */
    public static List<String> groupNames() {
        if (!isPresent()) {
            return Collections.emptyList();
        }
        try {
            Object groupManager = call(api, "getGroupManager");
            Collection<?> groups = (Collection<?>) call(groupManager, "getLoadedGroups");
            Set<String> out = new TreeSet<>();
            for (Object g : groups) {
                out.add((String) call(g, "getName"));
            }
            return List.copyOf(out);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("group lookup", e);
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ members of a group
    /**
     * Every player whose own nodes put them in the group (directly, not by inheritance), with their last known name,
     * from LuckPerms's storage, so offline players are included. Empty when LuckPerms is absent or the lookup fails.
     */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<Map<UUID, String>> membersOf(String group) {
        if (!isPresent()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        try {
            Object userManager = call(api, "getUserManager");
            Object matcher = callStatic(NODE_MATCHER, "key", "group." + group.toLowerCase(Locale.ROOT));
            CompletableFuture<Map<UUID, ?>> found = (CompletableFuture<Map<UUID, ?>>) call(userManager, "searchAll", matcher);
            return found.thenCompose(map -> {
                Map<UUID, CompletableFuture<String>> names = new LinkedHashMap<>();
                for (UUID id : map.keySet()) {
                    try {
                        names.put(id, ((CompletableFuture<String>) call(userManager, "lookupUsername", id)).exceptionally(e -> null));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        names.put(id, CompletableFuture.completedFuture(null));
                    }
                }
                return CompletableFuture.allOf(names.values().toArray(CompletableFuture[]::new)).thenApply(v -> {
                    Map<UUID, String> out = new LinkedHashMap<>();
                    names.forEach((id, name) -> {
                        String n = name.join();
                        out.put(id, n == null || n.isBlank() ? id.toString() : n);
                    });
                    return out;
                });
            }).exceptionally(e -> {
                warn("group members lookup", e instanceof Exception ex ? ex : new RuntimeException(e));
                return Map.of();
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            warn("group members lookup", e);
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    // ------------------------------------------------------------------ LuckPerms objects

    @Nullable
    private static Object loadedUser(UUID player) throws ReflectiveOperationException {
        Object userManager = call(api, "getUserManager");
        return call(userManager, "getUser", player);
    }

    private static void warn(String what, Exception e) {
        Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
        BSPServerUtilities.LOGGER.warn("LuckPerms {} failed: {}", what, cause.toString());
    }

    // ------------------------------------------------------------------ reflection helpers

    /** Calls a public method by name, chosen by argument count and types, looking through the class and its interfaces. */
    private static Object call(Object target, String method, Object... args) throws ReflectiveOperationException {
        Method m = find(target.getClass(), method, args);
        return m.invoke(target, args);
    }

    /** Calls a public static method of an API class in the LuckPerms class loader. */
    private static Object callStatic(String className, String method, Object... args) throws ReflectiveOperationException {
        if (loader == null) {
            throw new IllegalStateException("LuckPerms not reached");
        }
        Method m = find(loader.loadClass(className), method, args);
        return m.invoke(null, args);
    }

    private static Method find(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length && accepts(m.getParameterTypes(), args)) {
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // a public method of a public type is callable without it
                }
                return m;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " with " + args.length + " arguments");
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            Class<?> t = types[i];
            if (a == null) {
                if (t.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (t.isPrimitive()) {
                boolean ok = (t == int.class && a instanceof Integer) || (t == long.class && a instanceof Long) || (t == boolean.class && a instanceof Boolean);
                if (!ok) {
                    return false;
                }
                continue;
            }
            if (!t.isInstance(a)) {
                return false;
            }
        }
        return true;
    }
}
