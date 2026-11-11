package com.mrgregles.bsp_server_utilities.compat;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The only class that touches BSP-Teleport, for the staff web panel's teleports (SU-43; D258, D344 to D349): its API 2's
 * {@code serverName()}, {@code warps()}, {@code spawn(server)} and {@code teleport(player, destination)} (a forced move: no
 * cost, warm-up, cooldown or region flags; another server through the proxy, its raid rule on arrival). Read by
 * reflection, as this mod is not built against BSP-Teleport; {@code API_VERSION} is read at run time (a compiled-in
 * constant would always say 2). Anything missing makes it unavailable, never an error.
 */
public final class BspTeleport {
    public static final String MODID = "bsp_teleport";
    private static final String API = "com.mrgregles.bsp_teleport.api.BspTeleportApi", DEST = "com.mrgregles.bsp_teleport.api.Destination";

    /** A warp: its name (lower case), where it leads (server always named), whether it needs a permission, a portal warp. */
    public record Warp(String name, String server, String world, double x, double y, double z, boolean restricted, boolean portal) {
    }

    /** A place on a server. */
    public record Place(String server, String world, double x, double y, double z) {
    }

    /** What the panel's teleports need; the real one is BSP-Teleport, tests give their own. */
    public interface Api {
        String serverName();

        List<Warp> warps();

        Optional<Place> spawn(String server);

        /** A forced move; {@code server} empty or this server's name (any case) is a move here. False when it could not start. */
        boolean teleport(ServerPlayer player, Place to, String message);
    }

    @Nullable
    private static Api override;
    @Nullable
    private static Api real;
    private static boolean looked;

    private BspTeleport() {
    }

    /** BSP-Teleport's API 2, or null when it is not installed or older. */
    @Nullable
    public static Api api() {
        if (override != null) {
            return override;
        }
        if (looked) {
            return real;
        }
        if (ModList.get() == null || !ModList.get().isLoaded(MODID)) {
            return null; // not cached: asked again once mods are loaded
        }
        looked = true;
        try {
            Class<?> api = Class.forName(API), dest = Class.forName(DEST);
            if (api.getField("API_VERSION").getInt(null) < 2) {
                return null;
            }
            Object instance = api.getMethod("get").invoke(null);
            if (instance == null) {
                return null;
            }
            Method name = api.getMethod("serverName"), warps = api.getMethod("warps"), spawn = api.getMethod("spawn", String.class),
                    move = api.getMethod("teleport", ServerPlayer.class, dest);
            Constructor<?> make = dest.getConstructor(String.class, String.class, double.class, double.class, double.class, String.class);
            real = new Api() {
                @Override
                public String serverName() {
                    try {
                        return String.valueOf(name.invoke(instance));
                    } catch (ReflectiveOperationException e) {
                        return "";
                    }
                }

                @Override
                public List<Warp> warps() {
                    List<Warp> out = new ArrayList<>();
                    try {
                        for (Object w : (List<?>) warps.invoke(instance)) {
                            Place p = place(call(w, "position"));
                            out.add(new Warp(String.valueOf(call(w, "name")), p.server(), p.world(), p.x(), p.y(), p.z(), (Boolean) call(w, "restricted"), (Boolean) call(w, "portal")));
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        BSPServerUtilities.LOGGER.debug("BSP-Teleport's warps: {}", e.toString());
                    }
                    return out;
                }

                @Override
                public Optional<Place> spawn(String server) {
                    try {
                        return ((Optional<?>) spawn.invoke(instance, server)).map(BspTeleport::place);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return Optional.empty();
                    }
                }

                @Override
                public boolean teleport(ServerPlayer player, Place to, String message) {
                    try {
                        return (Boolean) move.invoke(instance, player, make.newInstance(to.server(), to.world(), to.x(), to.y(), to.z(), message));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        BSPServerUtilities.LOGGER.warn("BSP-Teleport refused a move from the staff panel: {}", e.toString());
                        return false;
                    }
                }
            };
            return real;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.debug("BSP-Teleport's API 2 not usable: {}", e.toString());
            return null;
        }
    }

    /** A {@code Destination} as a place (public for the GameTests' look-alike). */
    public static Place place(Object destination) {
        try {
            return new Place(String.valueOf(call(destination, "server")), String.valueOf(call(destination, "world")), (Double) call(destination, "x"), (Double) call(destination, "y"),
                    (Double) call(destination, "z"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BSP-Teleport's Destination changed", e);
        }
    }

    private static Object call(Object target, String accessor) throws ReflectiveOperationException {
        return target.getClass().getMethod(accessor).invoke(target);
    }

    public static void useForTests(@Nullable Api a) {
        override = a;
    }
}
