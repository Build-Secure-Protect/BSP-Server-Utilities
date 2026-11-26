package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.RegionGuard;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The staff web panel's regions, the game server's part (SU-158; owner decisions D762 to D787), through BSP Region Guard's
 * api 10 ({@link RegionGuard}):
 * <ul>
 * <li>{@code regions_list}: this server's worlds, every region and every flag; every rank (D769).</li>
 * <li>{@code region_save}, {@code region_remove}, {@code region_mine_reset}, {@code region_check},
 * {@code region_create}: Admins and Owners; a claim is removed by Owners only (D772). Region Guard applies its own rules,
 * and every change carries the version the page read, so an edit made in game is never overwritten unseen (D773).</li>
 * </ul>
 * A save is several Region Guard calls in a fixed order, stopping at the first refusal, and says what was and was not
 * saved (D783). Players are added by name: online here first, then the network's last-seen table (D784).
 * Every change is an Action Log entry by "&lt;name&gt; (panel)" (D774). The snapshot's {@code regions} says this server
 * answers, and {@code regionsAt} moves on every change of a region here (a mine's timer aside), with a snapshot sent at
 * once, so the panel reads again (D781). Actions arrive on the server thread, as Region Guard requires.
 */
public final class WebRegions {
    static final String GLOBAL = "__global__";
    private static long toldAt = -1;

    private WebRegions() {
    }

    /** Once, at mod start. */
    public static void register() {
        RegionGuard.listenForChanges();
    }

    /** The snapshot's {@code regions} and {@code regionsAt}. */
    public static void addTo(JsonObject snapshot) {
        boolean on = RegionGuard.regionsReady();
        snapshot.addProperty("regions", on);
        if (on) {
            snapshot.addProperty("regionsAt", RegionGuard.changedAt());
        }
    }

    /** About once a second: true when a region changed since the last call (send a snapshot now, D781). */
    static boolean watch() {
        long at = RegionGuard.changedAt();
        if (at == toldAt) {
            return false;
        }
        boolean first = toldAt < 0;
        toldAt = at;
        return !first;
    }

    private static WebActions.Answer no(String id, String why) {
        return new WebActions.Answer(id, false, why);
    }

    private static final String NOT_HERE = "BSP Region Guard 0.8.0 or newer is not on this server, so its regions cannot be read or changed from the panel.";

    @Nullable
    private static ServerLevel level(MinecraftServer server, JsonObject a) {
        ResourceLocation rl = ResourceLocation.tryParse(str(a, "dim"));
        return rl == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    /** The action as Region Guard and the log name who did it. */
    private static String actor(String by) {
        return by + " (panel)";
    }

    // ------------------------------------------------------------------ the actions

    /** Every region action, or null when {@code action} is not one. */
    @Nullable
    static WebActions.Answer handle(MinecraftServer server, String action, JsonObject a, String id, String by) {
        if (!List.of("regions_list", "region_save", "region_remove", "region_mine_reset", "region_check", "region_create").contains(action)) {
            return null;
        }
        if (!RegionGuard.regionsReady()) {
            return no(id, NOT_HERE);
        }
        if (action.equals("regions_list")) {
            return list(server, id);
        }
        if (!admin(a)) {
            return no(id, WebActions.ADMINS_ONLY); // D769, checked here too
        }
        ServerLevel level = level(server, a);
        if (level == null) {
            return no(id, "This server has no world " + str(a, "dim") + " loaded.");
        }
        String region = str(a, "region");
        if (region.isEmpty()) {
            return no(id, "No region was named.");
        }
        try {
            return switch (action) {
                case "region_save" -> save(server, level, a, id, by, region);
                case "region_remove" -> remove(server, level, a, id, by, region);
                case "region_mine_reset" -> reset(server, level, id, by, region);
                case "region_check" -> answer(id, RegionGuard.check(level, a), null);
                default -> create(server, level, a, id, by, region);
            };
        } catch (RuntimeException | LinkageError e) { // LinkageError: a Region Guard that lacks part of its api
            BSPServerUtilities.LOGGER.warn("The staff web panel's {} of {} failed: {}", action, region, e.toString());
            return no(id, "Region Guard failed: " + e.getMessage());
        }
    }

    private static WebActions.Answer list(MinecraftServer server, String id) {
        try {
            JsonObject data = RegionGuard.list(server);
            int n = data.getAsJsonArray("regions").size();
            return new WebActions.Answer(id, true, n + (n == 1 ? " region" : " regions"), data, null);
        } catch (RuntimeException | LinkageError e) { // LinkageError: a Region Guard that lacks part of its api
            BSPServerUtilities.LOGGER.warn("BSP Region Guard's regions could not be read for the staff web panel: {}", e.toString());
            return no(id, "Region Guard could not list its regions: " + e.getMessage());
        }
    }

    /** An answer from what Region Guard did, with {@code region}, {@code version} and {@code stale} as data. */
    private static WebActions.Answer answer(String id, RegionGuard.Done d, @Nullable String message) {
        JsonObject data = new JsonObject();
        data.addProperty("region", d.region() == null ? "" : d.region());
        data.addProperty("version", d.version());
        if (d.stale()) {
            data.addProperty("stale", true);
        }
        return new WebActions.Answer(id, d.ok(), message != null ? message : d.message(), data, null);
    }

    // ------------------------------------------------------------------ saving

    private static WebActions.Answer save(MinecraftServer server, ServerLevel level, JsonObject a, String id, String by, String region) {
        JsonObject set = a.has("set") && a.get("set").isJsonObject() ? a.getAsJsonObject("set") : null;
        if (set == null || set.size() == 0) {
            return no(id, "Nothing has changed.");
        }
        int version = a.has("version") && a.get("version").isJsonPrimitive() ? a.get("version").getAsInt() : -1;
        List<String> names = new ArrayList<>();
        for (String who : List.of("owners", "members")) {
            if (set.has(who) && set.get(who).isJsonObject() && set.getAsJsonObject(who).has("add") && set.getAsJsonObject(who).get("add").isJsonArray()) {
                for (JsonElement e : set.getAsJsonObject(who).getAsJsonArray("add")) {
                    if (e.isJsonPrimitive()) {
                        names.add(e.getAsString());
                    }
                }
            }
        }
        Map<String, UUID> people = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String n : names) {
            // players online here, then the network's last-seen table (D784). Never the profile cache by name: on a miss it
            // asks Mojang from the server thread, and on an offline-mode server it makes up a UUID for any name (audit).
            net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayerByName(n);
            if (p != null) {
                people.put(n.toLowerCase(Locale.ROOT), p.getUUID());
            } else if (!missing.contains(n)) {
                missing.add(n);
            }
        }
        if (missing.isEmpty()) {
            return saved(server, level, id, by, region, version, set, people);
        }
        Storage s = Storage.active();
        if (s == null) {
            return no(id, "Nothing was saved: " + missing.get(0) + " is not online here, and without shared storage the network's players cannot be looked up.");
        }
        // names this server never saw: the network's last-seen table, on the storage's worker, then back here (D784)
        CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
        s.submit(() -> {
            Map<String, UUID> found = new LinkedHashMap<>();
            for (String n : missing) {
                StorageBackend.Seen row = s.backend().loadSeenByName(n);
                if (row != null) {
                    found.put(n.toLowerCase(Locale.ROOT), row.player());
                }
            }
            server.execute(() -> {
                for (String n : missing) {
                    if (!found.containsKey(n.toLowerCase(Locale.ROOT))) {
                        later.complete(no(id, "Nothing was saved: " + n + " has never joined the network."));
                        return;
                    }
                }
                people.putAll(found);
                try {
                    later.complete(saved(server, level, id, by, region, version, set, people));
                } catch (RuntimeException | LinkageError e) { // LinkageError: a Region Guard that lacks part of its api
                    later.complete(no(id, "Region Guard failed: " + e.getMessage()));
                }
            });
        }, failed -> server.execute(() -> later.complete(no(id, "Nothing was saved: the network's player list could not be read (" + failed.getMessage() + ")."))));
        return new WebActions.Answer(id, false, "", null, later);
    }

    private static WebActions.Answer saved(MinecraftServer server, ServerLevel level, String id, String by, String region, int version, JsonObject set, Map<String, UUID> people) {
        RegionGuard.Done d = RegionGuard.save(level, region, version, actor(by), set, people);
        String where = " in " + level.dimension().location();
        String message;
        if (d.ok()) {
            message = "Saved " + d.region() + ": " + String.join(", ", d.saved()) + ".";
        } else if (d.stale() && d.saved().isEmpty()) {
            message = region + " was changed in game since this page read it. Nothing was saved: the page shows it as it is now.";
        } else {
            message = d.message() + (d.saved().isEmpty() ? " Nothing was saved." : " Saved before that: " + String.join(", ", d.saved()) + ".")
                    + (d.notSaved().size() > 1 ? " Not saved: " + String.join(", ", d.notSaved()) + "." : "");
        }
        if (!d.saved().isEmpty()) {
            ActionLog.record(server, ActionLog.entryForServer(actor(by), LogEntry.REGION_EDIT,
                    "changed region " + region + (d.region() != null && !d.region().equals(region) ? " (now " + d.region() + ")" : "") + where + ": " + String.join(", ", d.saved())
                            + (d.ok() ? "" : " (stopped: " + d.message() + ")")));
        }
        WebActions.Answer out = answer(id, d, message);
        JsonObject data = out.data().getAsJsonObject();
        JsonArray s = new JsonArray(), n = new JsonArray();
        d.saved().forEach(s::add);
        d.notSaved().forEach(n::add);
        data.add("saved", s);
        data.add("notSaved", n);
        return out;
    }

    // ------------------------------------------------------------------ removing, resetting, creating

    private static WebActions.Answer remove(MinecraftServer server, ServerLevel level, JsonObject a, String id, String by, String region) {
        String type = RegionGuard.typeOf(level, region);
        if (type == null) {
            return no(id, "There is no region " + region + " in " + level.dimension().location() + " any more.");
        }
        if (type.equals("claim") && !"OWNER".equals(str(a, "rank"))) {
            return no(id, "Refused: only Owners may remove a player's claim."); // D772
        }
        String name = RegionGuard.nameOf(level, region);
        int version = a.has("version") && a.get("version").isJsonPrimitive() ? a.get("version").getAsInt() : -1;
        RegionGuard.Done d = RegionGuard.remove(level, region, version, actor(by));
        if (d.ok()) {
            ActionLog.record(server, ActionLog.entryForServer(actor(by), LogEntry.REGION_REMOVE,
                    "removed " + kind(type) + " " + region + (name.equals(region) ? "" : " (" + name + ")") + " in " + level.dimension().location() + ": " + d.message()));
        }
        return answer(id, d, d.stale() ? region + " was changed in game since this page read it. Nothing was removed: the page shows it as it is now." : null);
    }

    private static WebActions.Answer reset(MinecraftServer server, ServerLevel level, String id, String by, String region) {
        RegionGuard.Done d = RegionGuard.resetMine(level, region, actor(by));
        if (d.ok()) {
            ActionLog.record(server, ActionLog.entryForServer(actor(by), LogEntry.REGION_EDIT, "reset mine " + region + " in " + level.dimension().location()));
        }
        return answer(id, d, null);
    }

    private static WebActions.Answer create(MinecraftServer server, ServerLevel level, JsonObject a, String id, String by, String region) {
        RegionGuard.Done d = RegionGuard.create(level, a, actor(by));
        if (d.ok()) {
            JsonArray b = a.getAsJsonArray("box");
            ActionLog.record(server, ActionLog.entryForServer(actor(by), LogEntry.REGION_CREATE, "made " + kind(str(a, "type")) + " " + d.region() + " in " + level.dimension().location()
                    + " from " + b.get(0) + " " + b.get(1) + " " + b.get(2) + " to " + b.get(3) + " " + b.get(4) + " " + b.get(5)));
        }
        return answer(id, d, null);
    }

    private static String kind(String type) {
        return switch (type) {
            case "plot_for_sale" -> "plot for sale";
            case "open_for_buying" -> "open area";
            case "claim" -> "claim";
            case "mine" -> "mine";
            case "global" -> "world default";
            default -> "region";
        };
    }

    private static boolean admin(JsonObject a) {
        String r = str(a, "rank");
        return r.equals("ADMIN") || r.equals("OWNER");
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /** Tests: the next {@link #watch} starts afresh. */
    public static void resetWatchForTests() {
        toldAt = -1;
    }
}
