package com.mrgregles.bsp_server_utilities.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_region_guard.api.Border;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.FlagInfo;
import com.mrgregles.bsp_region_guard.api.RegionAdmin;
import com.mrgregles.bsp_region_guard.api.RegionGuardApi;
import com.mrgregles.bsp_region_guard.api.RegionView;
import com.mrgregles.bsp_region_guard.api.WorldView;
import com.mrgregles.bsp_region_guard.api.event.RegionChangedEvent;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * The only class that touches BSP-Region Guard, which is optional.
 * <ul>
 * <li>The owner of the claim a chunk is in (owner decision D87), through {@code claims().claimsNear} by reflection, so it
 * works with any Region Guard that has it. Null when Region Guard is absent or the chunk is in nobody's claim.</li>
 * <li>The staff web panel's regions (SU-158; owner decisions D762 to D787): every region of this server with its flags
 * and settings as the panel's JSON ({@link #list}), and admin edits, removal, mine resets and creating from two corners
 * through Region Guard's own rules ({@code RegionGuardApi.get().admin()}). Only with Region Guard's api 10 or newer
 * ({@link #regionsReady}); its types are touched only inside {@link Live}, which is loaded only then (D782).</li>
 * </ul>
 * Every regions call runs on the server thread: Region Guard refuses its api from any other.
 */
public final class RegionGuard {
    public static final String MODID = "bsp_region_guard";
    /** Region Guard's api version that reads, edits and creates regions for other mods (its requests SU-RG3 to SU-RG5). */
    public static final int REGIONS_API = 10;
    /** The panel's region kinds with member and owner lists for some flags. */
    public static final List<String> LISTED_KINDS = List.of("plot_for_sale", "open_for_buying", "claim");
    private static boolean failed;
    @Nullable
    private static Boolean regionsReady;
    /** Forced on or off by tests (null: the real check). */
    @Nullable
    private static Boolean readyForTests;
    private static volatile long changedAt;
    private static LongSupplier clock = System::currentTimeMillis;

    private RegionGuard() {
    }

    public static boolean isLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MODID);
    }

    @Nullable
    public static UUID claimOwner(ServerLevel level, ChunkPos chunk) {
        if (failed || !isLoaded()) {
            return null;
        }
        try {
            Class<?> apiType = Class.forName("com.mrgregles.bsp_region_guard.api.RegionGuardApi");
            Class<?> claimsType = Class.forName("com.mrgregles.bsp_region_guard.api.ClaimService");
            Class<?> cuboidType = Class.forName("com.mrgregles.bsp_region_guard.api.Cuboid");
            Object api = apiType.getMethod("get").invoke(null);
            Object claims = apiType.getMethod("claims").invoke(api);
            Object area = cuboidType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                    .newInstance(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(), chunk.getMaxBlockX(), level.getMaxBuildHeight(), chunk.getMaxBlockZ());
            Method near = claimsType.getMethod("claimsNear", ServerLevel.class, cuboidType);
            for (Object nearby : (List<?>) near.invoke(claims, level, area)) {
                Object owner = nearby.getClass().getMethod("owner").invoke(nearby);
                if (owner instanceof UUID id) {
                    return id;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            BSPServerUtilities.LOGGER.warn("BSP-Region Guard's claims could not be read for chunk owners: {}", e.toString());
        }
        return null;
    }

    // ------------------------------------------------------------------ the staff panel's regions (SU-158)

    /**
     * Whether Region Guard is here with api {@value #REGIONS_API} or newer. Its {@code API_VERSION} is read by reflection:
     * as an interface constant it would otherwise be the number this mod was compiled against, whatever is installed.
     */
    public static boolean regionsReady() {
        if (readyForTests != null) {
            return readyForTests;
        }
        if (regionsReady == null) {
            boolean ok = false;
            if (isLoaded()) {
                try {
                    boolean ten = Class.forName("com.mrgregles.bsp_region_guard.api.RegionGuardApi").getField("API_VERSION").getInt(null) >= REGIONS_API;
                    // and the last parts api 10 gained (a build part way to 0.8.0 said 10 without them; audit: checked before ok)
                    Class.forName("com.mrgregles.bsp_region_guard.api.RegionService").getMethod("world", ServerLevel.class);
                    Class.forName("com.mrgregles.bsp_region_guard.api.event.RegionChangedEvent");
                    ok = ten;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    BSPServerUtilities.LOGGER.warn("BSP-Region Guard's api version could not be read: {}", e.toString());
                }
                if (!ok) {
                    BSPServerUtilities.LOGGER.info("BSP-Region Guard is older than api {}: the staff web panel cannot read or edit its regions here", REGIONS_API);
                }
            }
            regionsReady = ok;
        }
        return regionsReady;
    }

    /** Once, at mod start: every region change here (a mine's timer aside) moves {@link #changedAt} (D781). */
    public static void listenForChanges() {
        if (regionsReady()) {
            try {
                Live.listen();
            } catch (RuntimeException | LinkageError e) {
                regionsReady = false; // never stops the mod loading
                BSPServerUtilities.LOGGER.warn("BSP-Region Guard's region changes could not be heard: {}", e.toString());
            }
        }
    }

    /** When a region of this server last changed (ms; a mine's timer aside), 0 when none has since start. */
    public static long changedAt() {
        return changedAt;
    }

    static void changed() {
        long now = clock.getAsLong();
        changedAt = Math.max(now, changedAt + 1); // always moves, so two changes in one millisecond are both seen
    }

    /**
     * What came of an edit. {@code region} is the id afterwards ({@code null} when it is gone), {@code version} the
     * version afterwards; {@code saved} and {@code notSaved} name what a save did and did not do, in order (D783).
     */
    public record Done(boolean ok, boolean stale, String message, @Nullable String region, int version, List<String> saved, List<String> notSaved) {
        static Done of(boolean ok, boolean stale, String message, @Nullable String region, int version) {
            return new Done(ok, stale, message, region, version, List.of(), List.of());
        }
    }

    /** {@code regions_list}'s data: this server's worlds, every region of each, and every flag Region Guard knows. */
    public static JsonObject list(MinecraftServer server) {
        return Live.list(server);
    }

    /** A region's kind ({@code protected}, {@code claim}, ...), or null when there is none by that id here. */
    @Nullable
    public static String typeOf(ServerLevel level, String id) {
        return Live.typeOf(level, id);
    }

    /** A region's version, or -1 when there is none by that id here (0 for the world default not made yet). */
    public static int versionOf(ServerLevel level, String id) {
        return Live.versionOf(level, id);
    }

    /** The name a region shows, or its id. */
    public static String nameOf(ServerLevel level, String id) {
        return Live.nameOf(level, id);
    }

    /**
     * {@code region_save}: the panel's {@code set}, applied in the order of D783 with each call's version passed to the
     * next, stopping at the first refusal (nothing is rolled back). {@code people} maps each name to add, lower case, to
     * its UUID; every name must be in it.
     */
    public static Done save(ServerLevel level, String id, int version, String actor, JsonObject set, Map<String, UUID> people) {
        return Live.save(level, id, version, actor, set, people);
    }

    /** {@code region_remove}, by Region Guard's rules (a claim is given up with the admin refund, a mine takes its shell). */
    public static Done remove(ServerLevel level, String id, int version, String actor) {
        return Live.remove(level, id, version, actor);
    }

    /** {@code region_mine_reset}. */
    public static Done resetMine(ServerLevel level, String id, String actor) {
        return Live.resetMine(level, id, actor);
    }

    /** {@code region_check}: Region Guard's dry run of {@link #create}; nothing is made. */
    public static Done check(ServerLevel level, JsonObject act) {
        return Live.make(level, act, null);
    }

    /** {@code region_create}: a new region from the panel's action. */
    public static Done create(ServerLevel level, JsonObject act, String actor) {
        return Live.make(level, act, actor);
    }

    /** Tests: Region Guard treated as here (true), absent (false) or as it is (null). */
    public static void regionsForTests(@Nullable Boolean ready) {
        readyForTests = ready;
    }

    /** Tests: the clock {@link #changedAt} is read from (null: the real one). */
    public static void clockForTests(@Nullable LongSupplier c) {
        clock = c == null ? System::currentTimeMillis : c;
    }

    // ------------------------------------------------------------------ Region Guard's own types, loaded only with api 10

    private static final class Live {
        private Live() {
        }

        static void listen() {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegionChangedEvent.class, e -> {
                if (!e.timerOnly()) {
                    changed();
                }
            });
        }

        private static RegionGuardApi api() {
            return RegionGuardApi.get();
        }

        @Nullable
        static String typeOf(ServerLevel level, String id) {
            RegionView r = api().regions().view(level, id);
            return r == null ? null : r.type();
        }

        static int versionOf(ServerLevel level, String id) {
            RegionView r = api().regions().view(level, id);
            return r != null ? r.version() : id.equals(GLOBAL) ? 0 : -1;
        }

        static String nameOf(ServerLevel level, String id) {
            RegionView r = api().regions().view(level, id);
            return r == null || r.name() == null || r.name().isBlank() ? id : r.name();
        }

        // -------------------------------------------------------------- reading

        static JsonObject list(MinecraftServer server) {
            RegionGuardApi a = api();
            Map<String, String> kinds = new HashMap<>();
            JsonArray flags = new JsonArray();
            for (FlagInfo f : a.regions().flags()) {
                kinds.put(f.name(), f.kind());
                JsonObject o = new JsonObject();
                o.addProperty("name", f.name());
                o.addProperty("kind", f.kind());
                o.addProperty("group", f.group());
                o.addProperty("def", f.def());
                o.addProperty("about", f.about());
                o.addProperty("lists", f.lists());
                flags.add(o);
            }
            JsonArray dims = new JsonArray(), regions = new JsonArray();
            for (ServerLevel level : server.getAllLevels()) {
                WorldView w = a.regions().world(level);
                JsonObject d = new JsonObject();
                d.addProperty("id", level.dimension().location().toString());
                d.addProperty("minY", w.minY());
                d.addProperty("maxY", w.maxY());
                d.addProperty("pvp", w.pvpWorld()); // D778
                d.addProperty("plotsAllowed", w.plotsAllowed());
                d.addProperty("plotRule", w.plotRule());
                dims.add(d);
                for (RegionView r : a.regions().all(level)) {
                    regions.add(region(level, r, kinds));
                }
            }
            JsonObject out = new JsonObject();
            out.addProperty("v", 1);
            out.addProperty("rg", a.modVersion());
            out.add("dims", dims);
            out.add("regions", regions);
            out.add("flags", flags);
            JsonArray tiers = new JsonArray();
            BspCore.coinTiers().forEach(tiers::add);
            out.add("coinTiers", tiers); // D779
            return out;
        }

        static JsonObject region(ServerLevel level, RegionView r, Map<String, String> kinds) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("dim", level.dimension().location().toString());
            o.addProperty("name", r.name() == null || r.name().isBlank() ? r.id() : r.name());
            o.addProperty("type", r.type());
            if (r.bounds() != null) {
                Cuboid b = r.bounds();
                JsonArray box = new JsonArray();
                for (int v : new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) {
                    box.add(v);
                }
                o.add("box", box);
            }
            o.addProperty("priority", r.priority());
            o.addProperty("parent", r.parentId() == null ? "" : r.parentId());
            o.add("owners", people(r.owners()));
            o.add("members", people(r.members()));
            JsonObject flags = new JsonObject();
            r.flags().forEach((name, text) -> flags.add(name, flagJson(kinds.getOrDefault(name, "other"), text)));
            o.add("flags", flags);
            if (LISTED_KINDS.contains(r.type())) {
                JsonObject lists = new JsonObject();
                for (String who : List.of("members", "owners")) { // a claim owner's own choices (memberSet) are not sent (D785)
                    JsonObject one = new JsonObject();
                    Map<String, String> m = r.lists().get(who);
                    if (m != null) {
                        m.forEach(one::addProperty);
                    }
                    lists.add(who, one);
                }
                o.add("lists", lists);
            }
            if (r.shellOf() != null) {
                o.addProperty("shellOf", r.shellOf());
            }
            o.addProperty("plotLimit", r.plotLimit());
            if (r.claim() != null) {
                RegionView.Claim c = r.claim();
                JsonObject cl = new JsonObject();
                cl.addProperty("price", c.price());
                cl.addProperty("costPerBlock", c.costPerBlock());
                cl.addProperty("minSize", c.minSize());
                cl.addProperty("maxSize", c.maxSize());
                cl.addProperty("coinTier", c.coinTier() == null ? "" : c.coinTier()); // a tier's name, empty for the config's (D779)
                cl.addProperty("plotLimit", r.plotLimit());
                if (r.type().equals("claim")) {
                    cl.addProperty("boughtFor", c.boughtFor());
                    cl.addProperty("boughtCoins", c.boughtCoins());
                    cl.addProperty("boughtAt", c.boughtAt());
                    cl.addProperty("origin", c.origin());
                }
                o.add("claim", cl);
            }
            if (r.mine() != null) {
                RegionView.Mine m = r.mine();
                JsonObject mo = new JsonObject();
                JsonArray fill = new JsonArray();
                for (RegionView.Fill f : m.fill()) {
                    JsonObject one = new JsonObject();
                    one.addProperty("block", f.block());
                    one.addProperty("weight", f.weight());
                    fill.add(one);
                }
                mo.add("fill", fill);
                mo.addProperty("intervalSeconds", m.intervalSeconds());
                mo.addProperty("nextResetAt", m.nextResetAt());
                if (m.spawn() == null) {
                    mo.add("spawn", com.google.gson.JsonNull.INSTANCE);
                } else {
                    JsonObject s = new JsonObject();
                    s.addProperty("x", (int) Math.floor(m.spawn().x()));
                    s.addProperty("y", (int) Math.floor(m.spawn().y()));
                    s.addProperty("z", (int) Math.floor(m.spawn().z()));
                    mo.add("spawn", s);
                }
                if (m.shellId() != null) {
                    mo.addProperty("shell", m.shellId());
                }
                o.add("mine", mo);
            }
            if (r.world() != null) {
                Border b = r.world().border();
                JsonObject bd = new JsonObject();
                bd.addProperty("size", b == null ? 0 : b.size());
                bd.addProperty("x", b == null ? 0 : (long) Math.floor(b.centerX()));
                bd.addProperty("z", b == null ? 0 : (long) Math.floor(b.centerZ()));
                bd.addProperty("plotsInPvp", r.world().plotsInPvp()); // allow, deny or default (D780)
                o.add("border", bd);
            }
            o.addProperty("version", r.version());
            o.addProperty("updatedAt", r.updatedAt());
            return o;
        }

        private static JsonArray people(List<RegionView.Person> list) {
            JsonArray out = new JsonArray();
            for (RegionView.Person p : list) {
                JsonObject o = new JsonObject();
                o.addProperty("id", p.id().toString());
                o.addProperty("name", p.name() == null ? "" : p.name());
                out.add(o);
            }
            return out;
        }

        /** A flag's text as {@code /rg flag} prints it, as the panel reads it: booleans as JSON, string sets as a list. */
        static JsonElement flagJson(String kind, String text) {
            if (kind.equals("boolean") && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
                return new com.google.gson.JsonPrimitive(Boolean.parseBoolean(text));
            }
            if (kind.equals("string-set")) {
                JsonArray a = new JsonArray();
                for (String s : text.split(",")) {
                    if (!s.isBlank()) {
                        a.add(s.strip());
                    }
                }
                return a;
            }
            return new com.google.gson.JsonPrimitive(text);
        }

        // -------------------------------------------------------------- saving (D783)

        /** One save: the calls in order, each version passed to the next, stopping at the first refusal. */
        private static final class Run {
            final List<String> saved = new ArrayList<>(), notSaved = new ArrayList<>();
            String id;
            int version;
            @Nullable
            RegionAdmin.Result stop;

            Run(String id, int version) {
                this.id = id;
                this.version = version;
            }

            void step(String what, BiFunction<String, Integer, RegionAdmin.Result> call) {
                if (stop != null) {
                    notSaved.add(what);
                    return;
                }
                RegionAdmin.Result r;
                try {
                    r = call.apply(id, version);
                } catch (RuntimeException | LinkageError e) {
                    BSPServerUtilities.LOGGER.warn("BSP-Region Guard failed on the staff panel's edit of {}: {}", id, e.toString());
                    r = new RegionAdmin.Result(false, false, "Region Guard failed: " + e.getMessage(), id, version);
                }
                if (r.ok()) {
                    version = r.version();
                    if (r.regionId() != null) {
                        id = r.regionId();
                    }
                    saved.add(what);
                } else {
                    stop = r;
                    notSaved.add(what);
                }
            }

            /** A part of the save that could not be read: it stops there, saying what was saved before (audit). */
            void unreadable(RuntimeException e) {
                if (stop == null) {
                    stop = new RegionAdmin.Result(false, false, "A setting in this save could not be read (" + e.getClass().getSimpleName() + "); the rest was not tried.", id, version);
                }
            }

            /** A refusal of this mod's own, as if Region Guard had answered it. */
            void refuse(String what, String why) {
                step(what, (i, v) -> new RegionAdmin.Result(false, false, why, i, v));
            }

            Done done() {
                if (stop == null) {
                    return new Done(true, false, "", id, version, saved, notSaved);
                }
                return new Done(false, stop.stale(), stop.message(), id, stop.stale() && stop.version() >= 0 ? stop.version() : version, saved, notSaved);
            }
        }

        static Done save(ServerLevel level, String id, int version, String actor, JsonObject set, Map<String, UUID> people) {
            RegionAdmin ad = api().admin();
            RegionView r = api().regions().view(level, id);
            if (r == null && !(id.equals(GLOBAL) && version == 0)) {
                return Done.of(false, false, "There is no region " + id + " in " + level.dimension().location() + " any more.", null, -1);
            }
            Run run = new Run(id, version);
            try {
                steps(level, actor, set, people, ad, r, run);
            } catch (RuntimeException e) {
                run.unreadable(e);
            }
            return run.done();
        }

        private static void steps(ServerLevel level, String actor, JsonObject set, Map<String, UUID> people, RegionAdmin ad, @Nullable RegionView r, Run run) {
            String now = r == null ? "global" : r.type();
            JsonObject c = set.has("claim") && set.get("claim").isJsonObject() ? set.getAsJsonObject("claim") : new JsonObject();
            RegionView.Claim cur = r == null ? null : r.claim();
            // 1. the kind and its price (a plot's price and an open area's cost make the kind with it)
            String type = str(set, "type");
            Long price = num(c, "price"), cost = num(c, "costPerBlock"), min = num(c, "minSize"), max = num(c, "maxSize");
            long p = price != null ? price : cur == null ? 0 : cur.price();
            long k = cost != null ? cost : cur == null ? 0 : cur.costPerBlock();
            int mn = (int) (min != null ? min : cur == null ? 0 : cur.minSize()), mx = (int) (max != null ? max : cur == null ? 0 : cur.maxSize());
            if (!type.isEmpty() && !type.equals(now)) {
                switch (type) {
                    case "plot_for_sale" -> run.step(p > 0 ? "kind plot for sale at " + p : "kind plot for sale",
                            (i, v) -> p > 0 ? ad.sale(level, i, v, actor, p) : ad.setType(level, i, v, actor, type));
                    case "open_for_buying" -> run.step(k > 0 ? "kind open area at " + k + " a block" : "kind open area",
                            (i, v) -> k > 0 ? ad.open(level, i, v, actor, k, mn, mx) : ad.setType(level, i, v, actor, type));
                    default -> run.step("kind " + type, (i, v) -> ad.setType(level, i, v, actor, type));
                }
            } else {
                String kind = type.isEmpty() ? now : type;
                if (price != null) {
                    if (!kind.equals("plot_for_sale")) {
                        run.refuse("price", "Only a plot for sale has a price.");
                    } else if (price <= 0) {
                        run.refuse("price", "A plot for sale needs a price above 0; make it a region to take it off sale.");
                    } else {
                        run.step("price " + price, (i, v) -> ad.sale(level, i, v, actor, price));
                    }
                }
                if (cost != null || min != null || max != null) {
                    if (!kind.equals("open_for_buying")) {
                        run.refuse("cost and sizes", "Only an open area has a cost per block and sizes.");
                    } else if (k <= 0) {
                        run.refuse("cost and sizes", "An open area needs a cost above 0; make it a region to close it.");
                    } else {
                        run.step("cost and sizes", (i, v) -> ad.open(level, i, v, actor, k, mn, mx));
                    }
                }
            }
            // 2. the bounds, 3. the priority, 4. the parent
            if (set.has("box") && set.get("box").isJsonArray() && set.getAsJsonArray("box").size() == 6) {
                JsonArray b = set.getAsJsonArray("box");
                Cuboid box = new Cuboid(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt(), b.get(4).getAsInt(), b.get(5).getAsInt());
                run.step("bounds", (i, v) -> ad.redefine(level, i, v, actor, box));
            }
            if (num(set, "priority") != null) {
                int pr = (int) (long) num(set, "priority");
                run.step("priority " + pr, (i, v) -> ad.setPriority(level, i, v, actor, pr));
            }
            if (set.has("parent")) {
                String parent = str(set, "parent");
                run.step(parent.isEmpty() ? "no parent" : "parent " + parent, (i, v) -> ad.setParent(level, i, v, actor, parent.isEmpty() ? null : parent));
            }
            // 5. flags, 6. the member and owner values
            if (set.has("flags") && set.get("flags").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : set.getAsJsonObject("flags").entrySet()) {
                    String value = flagText(level, e.getValue());
                    run.step("flag " + e.getKey(), (i, v) -> ad.setFlag(level, i, v, actor, e.getKey(), value, null));
                }
            }
            if (set.has("lists") && set.get("lists").isJsonObject()) {
                for (String who : List.of("members", "owners")) {
                    JsonObject l = set.getAsJsonObject("lists").has(who) && set.getAsJsonObject("lists").get(who).isJsonObject() ? set.getAsJsonObject("lists").getAsJsonObject(who) : null;
                    if (l == null) {
                        continue;
                    }
                    for (Map.Entry<String, JsonElement> e : l.entrySet()) {
                        String value = e.getValue().isJsonNull() ? null : e.getValue().getAsString();
                        run.step(who + "' flag " + e.getKey(), (i, v) -> ad.setFlag(level, i, v, actor, e.getKey(), value, who));
                    }
                }
            }
            // 7. people
            for (String who : List.of("owners", "members")) {
                JsonObject pl = set.has(who) && set.get(who).isJsonObject() ? set.getAsJsonObject(who) : null;
                if (pl == null) {
                    continue;
                }
                boolean owner = who.equals("owners");
                for (JsonElement e : arr(pl, "add")) {
                    String name = e.getAsString();
                    UUID u = people.get(name.toLowerCase(Locale.ROOT));
                    if (u == null) {
                        run.refuse((owner ? "owner " : "member ") + name, name + " has never joined the network.");
                        continue;
                    }
                    run.step((owner ? "owner " : "member ") + name, (i, v) -> owner ? ad.addOwner(level, i, v, actor, u) : ad.addMember(level, i, v, actor, u));
                }
                for (JsonElement e : arr(pl, "remove")) {
                    UUID u;
                    try {
                        u = UUID.fromString(e.getAsString());
                    } catch (IllegalArgumentException bad) {
                        run.refuse("a player off the list", "That is not a player.");
                        continue;
                    }
                    String name = nameIn(r, u, owner);
                    run.step((owner ? "owner " : "member ") + name + " off", (i, v) -> owner ? ad.removeOwner(level, i, v, actor, u) : ad.removeMember(level, i, v, actor, u));
                }
            }
            // 8. plot settings
            if (c.has("coinTier")) {
                String tier = coinTier(c.get("coinTier"));
                if (tier == null) {
                    run.refuse("coin tier", "Not a coin tier: " + String.join(", ", BspCore.coinTiers()) + ", or default.");
                } else {
                    run.step("coin tier " + tier, (i, v) -> ad.coinTier(level, i, v, actor, tier.equals("default") ? null : tier));
                }
            }
            if (num(c, "plotLimit") != null) {
                int lim = (int) (long) num(c, "plotLimit");
                run.step("plot limit " + lim, (i, v) -> ad.plotLimit(level, i, v, actor, lim));
            }
            // 9. the mine
            JsonObject m = set.has("mine") && set.get("mine").isJsonObject() ? set.getAsJsonObject("mine") : null;
            if (m != null) {
                if (m.has("fill") && m.get("fill").isJsonArray()) {
                    List<RegionView.Fill> fill = fill(m.getAsJsonArray("fill"));
                    String filler = r != null && r.mine() != null && r.mine().filler() != null && fill.stream().anyMatch(f -> f.block().equals(r.mine().filler())) ? r.mine().filler() : null;
                    run.step("mine blocks", (i, v) -> ad.mineFill(level, i, v, actor, fill, filler));
                }
                if (num(m, "intervalSeconds") != null) {
                    int s = (int) (long) num(m, "intervalSeconds");
                    run.step("reset every " + s + " s", (i, v) -> ad.mineInterval(level, i, v, actor, s));
                }
                if (m.has("spawn")) {
                    RegionView.Location was = r != null && r.mine() != null ? r.mine().spawn() : null;
                    RegionView.Location at = null;
                    if (m.get("spawn").isJsonObject()) {
                        JsonObject sp = m.getAsJsonObject("spawn");
                        // the middle of the block, facing as before, else north (D786)
                        at = new RegionView.Location(level.dimension().location().toString(), sp.get("x").getAsInt() + 0.5, sp.get("y").getAsInt(), sp.get("z").getAsInt() + 0.5,
                                was != null ? was.yaw() : 180f, was != null ? was.pitch() : 0f);
                    }
                    RegionView.Location spawn = at;
                    run.step(spawn == null ? "players' place above the mine" : "players' place", (i, v) -> ad.mineSpawn(level, i, v, actor, spawn));
                }
            }
            // 10. the world default's border and plots in PvP worlds
            JsonObject bd = set.has("border") && set.get("border").isJsonObject() ? set.getAsJsonObject("border") : null;
            if (bd != null && !now.equals("global")) {
                run.refuse("border", "Only the world default has the world border.");
            } else if (bd != null) {
                Border was = r != null && r.world() != null ? r.world().border() : null;
                if (bd.has("size") || bd.has("x") || bd.has("z")) {
                    long size = num(bd, "size") != null ? num(bd, "size") : was == null ? 0 : was.size();
                    double x = num(bd, "x") != null ? num(bd, "x") : was == null ? 0 : was.centerX();
                    double z = num(bd, "z") != null ? num(bd, "z") : was == null ? 0 : was.centerZ();
                    Border border = size <= 0 ? null : new Border(x, z, (int) size);
                    run.step(border == null ? "no border" : "border " + size, (i, v) -> ad.border(level, v, actor, border));
                }
                if (bd.has("plotsInPvp")) {
                    String pip = plotsInPvp(bd.get("plotsInPvp"));
                    if (pip == null) {
                        run.refuse("plots in PvP worlds", "Plots in PvP worlds is allow, deny or default.");
                    } else {
                        run.step("plots in PvP worlds " + pip, (i, v) -> ad.plotsInPvp(level, v, actor, pip));
                    }
                }
            }
            // 11. the name, 12. the id last
            if (set.has("name")) {
                String name = str(set, "name");
                run.step("name", (i, v) -> ad.setName(level, i, v, actor, name));
            }
            if (set.has("newId")) {
                String newId = str(set, "newId");
                run.step("id to " + newId, (i, v) -> ad.rename(level, i, v, actor, newId));
            }
        }

        private static String nameIn(@Nullable RegionView r, UUID u, boolean owner) {
            if (r != null) {
                for (RegionView.Person p : owner ? r.owners() : r.members()) {
                    if (p.id().equals(u) && p.name() != null && !p.name().isBlank()) {
                        return p.name();
                    }
                }
            }
            return u.toString();
        }

        /** The panel's value of a flag as {@code /rg flag} takes it (null clears it). */
        @Nullable
        static String flagText(ServerLevel level, JsonElement v) {
            if (v == null || v.isJsonNull()) {
                return null;
            }
            if (v.isJsonArray()) {
                List<String> parts = new ArrayList<>();
                v.getAsJsonArray().forEach(e -> parts.add(e.getAsString()));
                return String.join(",", parts);
            }
            String s = v.getAsString().strip();
            return s.isEmpty() ? null : s; // a place as "x y z" is in this world (Region Guard's own reading)
        }

        /** A coin tier from the panel: a tier's name, {@code default}, or (panel 0.8.0) an index into BSP Core's tiers with -1 for default; null when none of these. */
        @Nullable
        static String coinTier(JsonElement e) {
            List<String> tiers = BspCore.coinTiers();
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                int i = e.getAsInt();
                return i < 0 ? "default" : i < tiers.size() ? tiers.get(i) : null;
            }
            String s = e.isJsonPrimitive() ? e.getAsString().strip().toLowerCase(Locale.ROOT) : "";
            return s.isEmpty() || s.equals("default") ? "default" : tiers.contains(s) ? s : null;
        }

        /** Plots in PvP worlds from the panel: {@code allow}, {@code deny}, {@code default}, or (panel 0.8.0) on and off. */
        @Nullable
        static String plotsInPvp(JsonElement e) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
                return e.getAsBoolean() ? "allow" : "deny";
            }
            String s = e.isJsonPrimitive() ? e.getAsString().strip().toLowerCase(Locale.ROOT) : "";
            return List.of("allow", "deny", "default").contains(s) ? s : null;
        }

        private static List<RegionView.Fill> fill(JsonArray a) {
            List<RegionView.Fill> out = new ArrayList<>();
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                out.add(new RegionView.Fill(o.get("block").getAsString(), o.get("weight").getAsInt()));
            }
            return out;
        }

        // -------------------------------------------------------------- removing, resetting, creating

        static Done remove(ServerLevel level, String id, int version, String actor) {
            return done(api().admin().remove(level, id, version, actor));
        }

        static Done resetMine(ServerLevel level, String id, String actor) {
            return done(api().admin().resetMine(level, id, actor));
        }

        /** {@code region_check} (actor null) or {@code region_create}. */
        static Done make(ServerLevel level, JsonObject act, @Nullable String actor) {
            JsonArray b = act.has("box") && act.get("box").isJsonArray() ? act.getAsJsonArray("box") : null;
            if (b == null || b.size() != 6) {
                return Done.of(false, false, "Set both corners on the map.", null, -1);
            }
            JsonObject c = act.has("claim") && act.get("claim").isJsonObject() ? act.getAsJsonObject("claim") : new JsonObject();
            JsonObject m = act.has("mine") && act.get("mine").isJsonObject() ? act.getAsJsonObject("mine") : new JsonObject();
            String parent = str(act, "parent"), name = str(act, "name");
            Long pr = num(act, "priority");
            RegionAdmin.NewRegion n = new RegionAdmin.NewRegion(str(act, "region"),
                    new Cuboid(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt(), b.get(4).getAsInt(), b.get(5).getAsInt()),
                    str(act, "type"), pr == null ? 0 : (int) (long) pr, parent.isEmpty() ? null : parent, name.isBlank() ? null : name,
                    orZero(num(c, "price")), orZero(num(c, "costPerBlock")), (int) orZero(num(c, "minSize")), (int) orZero(num(c, "maxSize")),
                    m.has("fill") && m.get("fill").isJsonArray() ? fill(m.getAsJsonArray("fill")) : List.of(), (int) orZero(num(m, "intervalSeconds")));
            return done(actor == null ? api().admin().preview(level, n) : api().admin().create(level, n, actor));
        }

        private static Done done(RegionAdmin.Result r) {
            return Done.of(r.ok(), r.stale(), r.message(), r.regionId(), r.version());
        }
    }

    // ------------------------------------------------------------------ JSON helpers (no Region Guard types)

    static final String GLOBAL = "__global__";

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    @Nullable
    private static Long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsLong() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long orZero(@Nullable Long v) {
        return v == null ? 0 : v;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
}
