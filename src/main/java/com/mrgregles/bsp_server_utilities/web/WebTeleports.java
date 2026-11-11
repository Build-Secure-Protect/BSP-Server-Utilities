package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.announce.Broadcast;
import com.mrgregles.bsp_server_utilities.compat.BspTeleport;
import com.mrgregles.bsp_server_utilities.compat.InventorySync;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * The staff panel's teleports (SU-43; D258, D344 to D349, D364 to D366, D374), through BSP-Teleport's API 2.
 * <ul>
 * <li>{@code tp_places}: {@code servers} (names): the network's warps and those servers' {@code /setspawn} points, and the
 * name BSP-Teleport knows this server by. Read only, every rank.</li>
 * <li>{@code tp}: {@code player} (on this server), {@code name} (for the log), {@code to}: {@code server} (BSP-Teleport's
 * name; empty or this server's for a move here), {@code world}, {@code x}, {@code y}, {@code z}, {@code ground} (the top
 * block at x and z; on this server only, D349), {@code what} (words for the log); {@code target}: a player who, when on this
 * server, is gone to where they stand now. Every rank (D345). A forced move: no cost, warm-up or cooldown; another server
 * through the proxy with BSP-Teleport's raid rule on arrival. The player sees "You were moved by staff." (D347); /back
 * undoes it (D348). Refused while their data is still arriving (D366).</li>
 * </ul>
 */
public final class WebTeleports {
    public static final String MESSAGE = "You were moved by staff.";
    static final String NO_TP = "Teleports need BSP-Teleport (its API 2) on this server.";
    private static BiFunction<MinecraftServer, UUID, ServerPlayer> lookup = (s, id) -> s.getPlayerList().getPlayer(id);

    private WebTeleports() {
    }

    public static WebActions.Answer places(JsonObject a, String id) {
        BspTeleport.Api tp = BspTeleport.api();
        if (tp == null) {
            return new WebActions.Answer(id, false, NO_TP);
        }
        JsonObject data = new JsonObject();
        data.addProperty("tpName", tp.serverName());
        JsonArray warps = new JsonArray();
        for (BspTeleport.Warp w : tp.warps()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name());
            o.addProperty("server", w.server());
            o.addProperty("world", w.world());
            o.addProperty("x", w.x());
            o.addProperty("y", w.y());
            o.addProperty("z", w.z());
            o.addProperty("restricted", w.restricted());
            o.addProperty("portal", w.portal());
            warps.add(o);
        }
        data.add("warps", warps);
        JsonArray spawns = new JsonArray();
        JsonElement servers = a.get("servers");
        if (servers != null && servers.isJsonArray()) {
            int n = 0;
            for (JsonElement s : servers.getAsJsonArray()) {
                if (++n > 32 || !s.isJsonPrimitive()) {
                    break;
                }
                String name = s.getAsString();
                tp.spawn(name).ifPresent(p -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("server", name);
                    o.addProperty("world", p.world());
                    o.addProperty("x", p.x());
                    o.addProperty("y", p.y());
                    o.addProperty("z", p.z());
                    spawns.add(o);
                });
            }
        }
        data.add("spawns", spawns);
        return new WebActions.Answer(id, true, "", data, null);
    }

    public static WebActions.Answer move(MinecraftServer server, JsonObject a, String id, String by) {
        BspTeleport.Api tp = BspTeleport.api();
        if (tp == null) {
            return new WebActions.Answer(id, false, NO_TP);
        }
        UUID who = uuid(str(a, "player"));
        ServerPlayer p = who == null ? null : lookup.apply(server, who);
        String here = Storage.currentServerId();
        if (p == null) {
            return new WebActions.Answer(id, false, "They are not on " + here + " any more.");
        }
        String name = p.getGameProfile().getName();
        if (InventorySync.isSyncing(who)) {
            return new WebActions.Answer(id, false, name + " is still arriving on " + here + ": try again in a moment.");
        }
        JsonObject to = a.has("to") && a.get("to").isJsonObject() ? a.getAsJsonObject("to") : new JsonObject();
        String dest = str(to, "server"), world = str(to, "world"), what = str(to, "what");
        boolean local = dest.isEmpty() || dest.equalsIgnoreCase(tp.serverName()) || dest.equalsIgnoreCase(here);
        double x = number(to, "x"), y = number(to, "y"), z = number(to, "z");
        UUID target = uuid(str(a, "target"));
        ServerPlayer t = target == null ? null : lookup.apply(server, target);
        if (t != null) { // the player to go to is here: where they stand now, not five seconds ago
            local = true;
            world = t.level().dimension().location().toString();
            x = t.getX();
            y = t.getY();
            z = t.getZ();
        }
        if (!world.matches("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}") || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || y < -2048 || y > 4096) {
            return new WebActions.Answer(id, false, "Not a place: a world id and x, y, z within the world.");
        }
        if (bool(to, "ground")) {
            if (!local) {
                return new WebActions.Answer(id, false, "\"On the ground\" works only on " + name + "'s own server (" + here + ").");
            }
            ResourceLocation rl = ResourceLocation.tryParse(world);
            ServerLevel level = rl == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
            if (level == null) {
                return new WebActions.Answer(id, false, "No world " + world + " on " + here + ".");
            }
            int g = ground(level, Mth.floor(x), Mth.floor(z)); // loads that chunk if it must
            if (g == Integer.MIN_VALUE) {
                return new WebActions.Answer(id, false, "No ground at " + Mth.floor(x) + ", " + Mth.floor(z) + " in " + world + ": choose a y.");
            }
            y = g;
        }
        BspTeleport.Place place = new BspTeleport.Place(local ? "" : dest, world, x, y, z);
        if (!tp.teleport(p, place, MESSAGE)) {
            return new WebActions.Answer(id, false, "BSP-Teleport could not start the move.");
        }
        String said = Broadcast.clean(what).strip(); // a warp's or player's name as the panel sent it: no colour codes or new lines in the log (audit)
        String where = (said.isEmpty() ? Math.round(x) + ", " + Math.round(y) + ", " + Math.round(z) : said.length() > 80 ? said.substring(0, 80) : said)
                + (local ? "" : " (" + Broadcast.clean(dest) + ")");
        ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "tp " + name + " to " + where));
        return new WebActions.Answer(id, true, "Moved " + name + " to " + where + ". /back takes them back.");
    }

    /**
     * The y to stand on at x, z (D349), or {@link Integer#MIN_VALUE} when there is no ground (the void). Under a roof (the
     * Nether) the heightmap finds the roof, so the column is searched downward from below it for a solid block with two free
     * blocks over it (audit).
     */
    public static int ground(ServerLevel level, int x, int z) {
        int min = level.getMinBuildHeight();
        if (!level.dimensionType().hasCeiling()) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            return y > min ? y : Integer.MIN_VALUE;
        }
        int top = Math.min(level.getMaxBuildHeight(), min + level.dimensionType().logicalHeight()) - 6; // under the roof's bedrock
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = top; y > min; y--) {
            if (free(level, at.set(x, y, z)) && free(level, at.set(x, y + 1, z)) && level.getBlockState(at.set(x, y - 1, z)).blocksMotion()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean free(ServerLevel level, BlockPos at) {
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(at);
        return st.getCollisionShape(level, at).isEmpty() && st.getFluidState().isEmpty();
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static double number(JsonObject o, String k) {
        try {
            return o.get(k).getAsDouble();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    public static void useForTests(@Nullable BiFunction<MinecraftServer, UUID, ServerPlayer> find) {
        lookup = find != null ? find : (s, id) -> s.getPlayerList().getPlayer(id);
    }
}
