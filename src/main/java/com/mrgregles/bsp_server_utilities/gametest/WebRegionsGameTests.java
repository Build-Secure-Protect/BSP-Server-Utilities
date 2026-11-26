package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_region_guard.api.Cuboid;
import com.mrgregles.bsp_region_guard.api.RegionAdmin;
import com.mrgregles.bsp_region_guard.api.RegionGuardApi;
import com.mrgregles.bsp_region_guard.api.RegionView;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.RegionGuard;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebRegions;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The staff web panel's regions, the game server's part (SU-158; owner decisions D762 to D787), against the real BSP
 * Region Guard (api 10) loaded in the test server: {@code regions_list} reads every kind with its flags as the panel
 * reads them, to every rank; a save is applied in order with each version passed on, refused whole when the page's
 * version is old, and stops at the first refusal saying what was and was not saved; names this server never saw are
 * found in the network's last-seen table; a claim is removed by Owners only, a mine takes its shell, the world default
 * stays; check makes nothing, create makes and logs; changes move the snapshot's {@code regionsAt}; without Region
 * Guard every action says so. Every region is removed again afterwards (the test world persists).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebRegionsGameTests {
    /**
     * Whether Region Guard's api 10 is here to test against. Without it (a {@code -PnoRg} or {@code -PrgRun=0.7.3} run)
     * these pass at once: {@link WebRegionsAbsentGameTests} covers that case. Region Guard's types are only in {@link Live},
     * loaded only here, so Forge can list these tests with Region Guard missing.
     */
    private static boolean here(GameTestHelper h) {
        if (RegionGuard.regionsReady()) {
            return true;
        }
        BSPServerUtilities.LOGGER.info("Region test passed without running: no BSP Region Guard api 10 here");
        h.succeed();
        return false;
    }

    @GameTest(template = SetupGameTests.EMPTY, batch = "su-regions-1")
    public void every_kind_is_listed_as_the_panel_reads_it_to_every_rank(GameTestHelper h) {
        if (here(h)) {
            Live.every_kind_is_listed_as_the_panel_reads_it_to_every_rank(h);
        }
    }

    @GameTest(template = SetupGameTests.EMPTY, batch = "su-regions-2")
    public void a_save_goes_in_order_and_stops_at_the_first_refusal(GameTestHelper h) {
        if (here(h)) {
            Live.a_save_goes_in_order_and_stops_at_the_first_refusal(h);
        }
    }

    @GameTest(template = SetupGameTests.EMPTY, batch = "su-regions-3", timeoutTicks = 100)
    public void a_name_this_server_never_saw_is_found_on_the_network(GameTestHelper h) {
        if (here(h)) {
            Live.a_name_this_server_never_saw_is_found_on_the_network(h);
        }
    }

    @GameTest(template = SetupGameTests.EMPTY, batch = "su-regions-4")
    public void removing_follows_region_guard_and_the_owner_rule(GameTestHelper h) {
        if (here(h)) {
            Live.removing_follows_region_guard_and_the_owner_rule(h);
        }
    }

    @GameTest(template = SetupGameTests.EMPTY, batch = "su-regions-5")
    public void check_makes_nothing_and_create_makes_and_logs(GameTestHelper h) {
        if (here(h)) {
            Live.check_makes_nothing_and_create_makes_and_logs(h);
        }
    }

    @GameTest(template = SetupGameTests.EMPTY, batch = "su-regions-6", timeoutTicks = 60)
    public void a_change_moves_regions_at_and_without_region_guard_every_action_says_so(GameTestHelper h) {
        if (here(h)) {
            Live.a_change_moves_regions_at_and_without_region_guard_every_action_says_so(h);
        }
    }

    private static final class Live {
        private static final String EMPTY = SetupGameTests.EMPTY;
        private static final byte[] KEY = new byte[32];
        private static final String GLOBAL = "__global__";

        static {
            for (int i = 0; i < 32; i++) {
                KEY[i] = (byte) (i * 7 + 1);
            }
        }

        private static String tag() {
            return Integer.toHexString(new java.util.Random().nextInt(0x1000000));
        }

        private static int seq;

        /** A signed action as the panel sends it, with the given fields. */
        private static String action(String rank, String action, JsonObject fields) {
            JsonObject o = fields.deepCopy();
            o.addProperty("v", 1);
            o.addProperty("id", "rg-" + tag() + "-" + (++seq));
            o.addProperty("at", System.currentTimeMillis());
            o.addProperty("by", "Ana_Mod");
            o.addProperty("rank", rank);
            o.addProperty("server", Storage.currentServerId());
            o.addProperty("action", action);
            return WebActions.sign(KEY, o.toString());
        }

        private static WebActions.Answer act(MinecraftServer server, String rank, String action, JsonObject fields) {
            return WebActions.handle(server, action(rank, action, fields));
        }

        private static JsonObject at(ServerLevel level, String region) {
            JsonObject f = new JsonObject();
            f.addProperty("dim", level.dimension().location().toString());
            f.addProperty("region", region);
            return f;
        }

        private static JsonArray box(GameTestHelper h, int x1, int y1, int z1, int x2, int y2, int z2) {
            BlockPos a = h.absolutePos(new BlockPos(x1, y1, z1)), b = h.absolutePos(new BlockPos(x2, y2, z2));
            JsonArray o = new JsonArray();
            for (int v : new int[]{Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())}) {
                o.add(v);
            }
            return o;
        }

        private static Cuboid cuboid(JsonArray b) {
            return new Cuboid(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt(), b.get(4).getAsInt(), b.get(5).getAsInt());
        }

        private static RegionAdmin admin() {
            return RegionGuardApi.get().admin();
        }

        private static int version(ServerLevel level, String id) {
            RegionView r = RegionGuardApi.get().regions().view(level, id);
            return r == null ? -1 : r.version();
        }

        /** Removes the test's regions, whatever state they were left in. */
        private static void clean(ServerLevel level, List<String> ids) {
            for (String id : ids) {
                for (int tries = 0; tries < 3 && RegionGuardApi.get().regions().view(level, id) != null; tries++) {
                    admin().remove(level, id, version(level, id), "test");
                }
            }
        }

        /** The region by id in a {@code regions_list} answer, or null. */
        private static JsonObject find(JsonObject data, String id) {
            for (JsonElement e : data.getAsJsonArray("regions")) {
                if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
                    return e.getAsJsonObject();
                }
            }
            return null;
        }

        private static long logged(MinecraftServer server, String kind, String text) {
            return ActionLog.recent(server, 200, false, List.of(kind)).stream().filter(e -> e.text().contains(text) && e.actor().contains("Ana_Mod (panel)")).count();
        }

        /** Lets plots be made in this world (the test world counts as a PvP world), returning the world default's setting before. */
        private static String allowPlots(ServerLevel level) {
            RegionView g = RegionGuardApi.get().regions().view(level, GLOBAL);
            String was = g == null || g.world() == null ? "default" : g.world().plotsInPvp();
            admin().plotsInPvp(level, g == null ? 0 : g.version(), "test", "allow");
            return was;
        }

        private static void putBackPlots(ServerLevel level, String was) {
            admin().plotsInPvp(level, version(level, GLOBAL), "test", was);
        }

        private static void setUp() {
            WebActions.useForTests(KEY, null, null);
            WebAgent.useForTests(true, null, null);
            RegionGuard.regionsForTests(null);
        }

        private static void tearDown() {
            WebActions.useReal();
            WebAgent.useReal();
            RegionGuard.regionsForTests(null);
        }

        // ------------------------------------------------------------------ the tests

        static void every_kind_is_listed_as_the_panel_reads_it_to_every_rank(GameTestHelper h) {
            ServerLevel level = h.getLevel();
            MinecraftServer server = level.getServer();
            String t = tag(), prot = "su_l_" + t, plot = "su_lp_" + t, mine = "su_lm_" + t;
            List<String> made = List.of(plot, mine, prot);
            setUp();
            String was = allowPlots(level);
            try {
                h.assertTrue(RegionGuard.regionsReady(), "Region Guard 0.8.0 (api 10) is loaded in the test server");
                h.assertTrue(admin().create(level, new RegionAdmin.NewRegion(prot, cuboid(box(h, 0, 1, 0, 9, 4, 9)), "protected", 5, null, "Town hall", 0, 0, 0, 0, List.of(), 0), "test").ok(), "a region");
                h.assertTrue(admin().create(level, new RegionAdmin.NewRegion(plot, cuboid(box(h, 1, 1, 1, 3, 3, 3)), "plot_for_sale", 10, prot, null, 250, 0, 0, 0, List.of(), 0), "test").ok(), "a plot");
                h.assertTrue(admin().create(level, new RegionAdmin.NewRegion(mine, cuboid(box(h, 6, 1, 6, 8, 3, 8)), "mine", 10, prot, null, 0, 0, 0, 0,
                        List.of(new RegionView.Fill("minecraft:stone", 700), new RegionView.Fill("minecraft:coal_ore", 300)), 600), "test").ok(), "a mine");
                int v = version(level, prot);
                admin().setFlag(level, prot, v, "test", "deny-spawn", "minecraft:zombie,minecraft:creeper", null);
                admin().setFlag(level, prot, version(level, prot), "test", "notify-enter", "true", null);
                admin().setFlag(level, prot, version(level, prot), "test", "build", "deny", null);
                admin().setFlag(level, plot, version(level, plot), "test", "chest-access", "deny", "members");

                for (String rank : List.of("MODERATOR", "ADMIN", "OWNER")) {
                    WebActions.Answer a = act(server, rank, "regions_list", new JsonObject());
                    h.assertTrue(a != null && a.ok() && a.data() != null, rank + " reads the regions (D769): " + (a == null ? null : a.message()));
                }
                JsonObject d = act(server, "MODERATOR", "regions_list", new JsonObject()).data().getAsJsonObject();
                h.assertTrue(d.get("v").getAsInt() == 1 && !d.get("rg").getAsString().isEmpty(), "the answer's head: v and Region Guard's version");
                JsonObject dim = null;
                for (JsonElement e : d.getAsJsonArray("dims")) {
                    if (e.getAsJsonObject().get("id").getAsString().equals(level.dimension().location().toString())) {
                        dim = e.getAsJsonObject();
                    }
                }
                h.assertTrue(dim != null && dim.get("minY").getAsInt() == level.getMinBuildHeight() && dim.get("maxY").getAsInt() == level.getMaxBuildHeight() - 1,
                        "this world with its heights, both inclusive: " + dim);
                h.assertTrue(dim.has("pvp") && dim.has("plotsAllowed") && dim.has("plotRule") && dim.get("plotsAllowed").getAsBoolean(), "pvp, plotsAllowed and why (D778): " + dim);
                JsonObject p = find(d, prot), s = find(d, plot), m = find(d, mine), g = find(d, GLOBAL);
                h.assertTrue(p != null && p.get("type").getAsString().equals("protected") && p.get("name").getAsString().equals("Town hall") && p.get("priority").getAsInt() == 5
                        && p.get("parent").getAsString().isEmpty() && p.getAsJsonArray("box").size() == 6 && !p.has("lists"), "the region: " + p);
                JsonObject pf = p.getAsJsonObject("flags");
                h.assertTrue(pf.get("build").getAsString().equals("deny"), "a state flag as allow/deny text: " + pf);
                h.assertTrue(pf.get("notify-enter").isJsonPrimitive() && pf.get("notify-enter").getAsJsonPrimitive().isBoolean() && pf.get("notify-enter").getAsBoolean(), "a boolean as JSON: " + pf);
                h.assertTrue(pf.get("deny-spawn").isJsonArray() && pf.getAsJsonArray("deny-spawn").size() == 2, "a string set as a list: " + pf);
                h.assertTrue(!pf.has("pvp"), "an unset flag is absent");
                h.assertTrue(s != null && s.get("type").getAsString().equals("plot_for_sale") && s.get("parent").getAsString().equals(prot)
                        && s.getAsJsonObject("claim").get("price").getAsLong() == 250 && s.getAsJsonObject("claim").has("coinTier")
                        && s.getAsJsonObject("lists").getAsJsonObject("members").get("chest-access").getAsString().equals("deny")
                        && !s.getAsJsonObject("lists").has("memberSet"), "the plot, its price, its members' value, no memberSet (D785): " + s);
                JsonArray fill = m.getAsJsonObject("mine").getAsJsonArray("fill");
                h.assertTrue(m.get("type").getAsString().equals("mine") && fill.size() == 2 && fill.get(0).getAsJsonObject().get("weight").getAsInt() == 700
                        && m.getAsJsonObject("mine").get("intervalSeconds").getAsInt() == 600 && m.getAsJsonObject("mine").get("nextResetAt").getAsLong() > 0, "the mine: " + m);
                h.assertTrue(g != null && !g.has("box") && g.getAsJsonObject("border").get("plotsInPvp").getAsString().equals("allow"), "the world default: no box, plots in PvP as text (D780): " + g);
                JsonObject build = null;
                for (JsonElement e : d.getAsJsonArray("flags")) {
                    if (e.getAsJsonObject().get("name").getAsString().equals("build")) {
                        build = e.getAsJsonObject();
                    }
                }
                h.assertTrue(build != null && build.get("kind").getAsString().equals("state") && build.get("def").getAsString().equals("build") && build.get("lists").getAsBoolean(),
                        "the flag list: " + build);
                h.assertTrue(d.has("coinTiers") && d.getAsJsonArray("coinTiers").size() > 0, "BSP Core's coin tiers (D779): " + d.get("coinTiers"));

                JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
                h.assertTrue(snap.get("regions").getAsBoolean() && snap.has("regionsAt"), "the snapshot: regions answered here, and when they last changed");
                WebActions.Answer mod = act(server, "MODERATOR", "region_save", at(level, prot));
                h.assertTrue(!mod.ok() && mod.message().contains("only Admins and Owners"), "a Moderator changes nothing: " + mod.message());
            } finally {
                clean(level, made);
                putBackPlots(level, was);
                tearDown();
            }
            h.succeed();
        }

        static void a_save_goes_in_order_and_stops_at_the_first_refusal(GameTestHelper h) {
            ServerLevel level = h.getLevel();
            MinecraftServer server = level.getServer();
            String t = tag(), id = "su_s_" + t, renamed = "su_s2_" + t;
            List<String> made = List.of(id, renamed);
            setUp();
            try {
                h.assertTrue(admin().create(level, RegionAdmin.NewRegion.protectedRegion(id, cuboid(box(h, 0, 1, 0, 5, 3, 5)), 1), "test").ok(), "a region to edit");
                int v0 = version(level, id);
                JsonObject f = at(level, id);
                f.addProperty("version", v0);
                JsonObject set = new JsonObject(), flags = new JsonObject();
                set.addProperty("name", "Market square");
                set.addProperty("priority", 4);
                flags.addProperty("build", "deny");
                flags.addProperty("greeting", "Welcome");
                JsonArray mobs = new JsonArray();
                mobs.add("minecraft:zombie");
                flags.add("deny-spawn", mobs);
                flags.addProperty("notify-enter", true);
                flags.addProperty("spawn", "1 70 2");
                set.add("flags", flags);
                f.add("set", set);
                WebActions.Answer a = act(server, "ADMIN", "region_save", f);
                h.assertTrue(a.ok() && a.message().startsWith("Saved " + id), "saved: " + a.message());
                JsonObject data = a.data().getAsJsonObject();
                int v1 = data.get("version").getAsInt();
                h.assertTrue(v1 > v0 && data.get("region").getAsString().equals(id) && data.getAsJsonArray("notSaved").isEmpty(), "the new version and what was saved: " + data);
                h.assertTrue(data.getAsJsonArray("saved").get(0).getAsString().startsWith("priority"), "the priority before the flags, the name last (D783): " + data.get("saved"));
                RegionView r = RegionGuardApi.get().regions().view(level, id);
                h.assertTrue(r.name().equals("Market square") && r.priority() == 4 && "deny".equals(r.flags().get("build")) && "Welcome".equals(r.flags().get("greeting"))
                        && "minecraft:zombie".equals(r.flags().get("deny-spawn")) && "true".equals(r.flags().get("notify-enter")), "Region Guard holds it: " + r.flags());
                h.assertTrue(r.flags().get("spawn") != null && r.flags().get("spawn").startsWith(level.dimension().location().toString()), "a place as three numbers is in this world: " + r.flags().get("spawn"));
                h.assertTrue(logged(server, LogEntry.REGION_EDIT, "changed region " + id) == 1, "logged once as region_edit (D774)");

                WebActions.Answer stale = act(server, "ADMIN", "region_save", f); // the old version again
                h.assertTrue(!stale.ok() && stale.data().getAsJsonObject().get("stale").getAsBoolean() && stale.message().contains("changed in game")
                        && version(level, id) == v1, "an old version saves nothing (D773): " + stale.message());

                JsonObject g = at(level, id), set2 = new JsonObject(), clear = new JsonObject();
                g.addProperty("version", v1);
                set2.addProperty("priority", 7);
                set2.addProperty("parent", "su_none_" + t);
                set2.addProperty("name", "Never");
                clear.add("greeting", JsonNull.INSTANCE);
                set2.add("flags", clear);
                g.add("set", set2);
                WebActions.Answer part = act(server, "OWNER", "region_save", g);
                JsonObject pd = part.data().getAsJsonObject();
                r = RegionGuardApi.get().regions().view(level, id);
                h.assertTrue(!part.ok() && r.priority() == 7 && r.parentId() == null && r.name().equals("Market square") && r.flags().containsKey("greeting"),
                        "the priority saved, the parent refused, the rest not tried: " + part.message());
                h.assertTrue(part.message().contains("Saved before that: priority 7") && part.message().contains("Not saved:") && pd.getAsJsonArray("notSaved").size() == 3
                        && pd.get("version").getAsInt() == r.version(), "it says what was and was not saved: " + part.message());

                JsonObject u = at(level, id), set4 = new JsonObject(), mine = new JsonObject(), spot = new JsonObject();
            u.addProperty("version", r.version());
            set4.addProperty("priority", 3);
            spot.addProperty("x", 1); // no y or z: unreadable (a panel out of step)
            mine.add("spawn", spot);
            set4.add("mine", mine);
            u.add("set", set4);
            WebActions.Answer bad = act(server, "ADMIN", "region_save", u);
            r = RegionGuardApi.get().regions().view(level, id);
            h.assertTrue(!bad.ok() && r.priority() == 3 && bad.data().getAsJsonObject().getAsJsonArray("saved").size() == 1 && bad.message().contains("could not be read")
                    && bad.data().getAsJsonObject().get("version").getAsInt() == r.version(), "an unreadable part stops the save, saying what was saved: " + bad.message());
            h.assertTrue(logged(server, LogEntry.REGION_EDIT, "changed region " + id + " in " + level.dimension().location() + ": priority 3 (stopped") == 1, "and what was saved is logged (audit)");

            JsonObject k = at(level, id), set3 = new JsonObject();
                k.addProperty("version", r.version());
                set3.addProperty("newId", renamed);
                set3.add("flags", clear);
                k.add("set", set3);
                WebActions.Answer ren = act(server, "ADMIN", "region_save", k);
                h.assertTrue(ren.ok() && ren.data().getAsJsonObject().get("region").getAsString().equals(renamed) && RegionGuardApi.get().regions().view(level, id) == null
                        && !RegionGuardApi.get().regions().view(level, renamed).flags().containsKey("greeting"), "a flag cleared, then the id changed last: " + ren.message());
            } finally {
                clean(level, made);
                tearDown();
            }
            h.succeed();
        }

        static void a_name_this_server_never_saw_is_found_on_the_network(GameTestHelper h) {
            ServerLevel level = h.getLevel();
            MinecraftServer server = level.getServer();
            String t = tag(), id = "su_n_" + t;
            MemoryBackend db = new MemoryBackend();
            Storage s = Storage.startWith(server, db, "alpha");
            s.drain();
            Storage before = Storage.active();
            Storage.useForTests(s);
            setUp();
            UUID wren = UUID.randomUUID();
            String name = "Wr" + t.substring(0, Math.min(6, t.length()));
            AtomicReference<WebActions.Answer> got = new AtomicReference<>();
            try {
                db.saveSeen(List.of(new StorageBackend.Seen(wren, name, System.currentTimeMillis(), "beta", "minecraft:overworld", 0, 64, 0, StorageBackend.SEEN_LOGOUT, "")));
                h.assertTrue(admin().create(level, RegionAdmin.NewRegion.protectedRegion(id, cuboid(box(h, 0, 1, 0, 3, 3, 3)), 1), "test").ok(), "a region");
                JsonObject f = at(level, id), set = new JsonObject(), mem = new JsonObject(), add = new JsonObject();
                f.addProperty("version", version(level, id));
                JsonArray names = new JsonArray();
                names.add(name.toLowerCase());
                mem.add("add", names);
                mem.add("remove", new JsonArray());
                set.add("members", mem);
                f.add("set", set);
                WebActions.Answer a = act(server, "ADMIN", "region_save", f);
                h.assertTrue(a.later() != null, "a name not in this server's cache is looked up on the network's last-seen table (D784)");
                a.later().thenAccept(got::set);
                s.drain();

                JsonObject u = at(level, id), set2 = new JsonObject(), own = new JsonObject();
                JsonArray nobody = new JsonArray();
                nobody.add("Nobody_" + t.substring(0, 3));
                own.add("add", nobody);
                set2.add("owners", own);
                u.add("set", set2);
                AtomicReference<WebActions.Answer> refused = new AtomicReference<>();
                h.runAfterDelay(3, () -> {
                    WebActions.Answer done = got.get();
                    h.assertTrue(done != null && done.ok(), "saved once found: " + (done == null ? "no answer yet" : done.message()));
                    RegionView r = RegionGuardApi.get().regions().view(level, id);
                    h.assertTrue(r.members().stream().anyMatch(p -> p.id().equals(wren)), "the member is the network's player by UUID: " + r.members());
                    u.addProperty("version", r.version());
                    WebActions.Answer b = act(server, "ADMIN", "region_save", u);
                    b.later().thenAccept(refused::set);
                    s.drain();
                });
                h.runAfterDelay(6, () -> {
                    WebActions.Answer no = refused.get();
                    h.assertTrue(no != null && !no.ok() && no.message().contains("has never joined the network")
                            && RegionGuardApi.get().regions().view(level, id).owners().isEmpty(), "an unknown name saves nothing: " + (no == null ? "no answer" : no.message()));
                    clean(level, List.of(id));
                    Storage.useForTests(before);
                    s.close();
                    tearDown();
                    h.succeed();
                });
            } catch (Exception e) {
                clean(level, List.of(id));
                Storage.useForTests(before);
                s.close();
                tearDown();
                throw new RuntimeException(e);
            }
        }

        static void removing_follows_region_guard_and_the_owner_rule(GameTestHelper h) {
            ServerLevel level = h.getLevel();
            MinecraftServer server = level.getServer();
            String t = tag(), claim = "su_rc_" + t, mine = "su_rm_" + t, prot = "su_rp_" + t;
            List<String> made = new ArrayList<>(List.of(claim, mine, mine + "_shell", prot));
            setUp();
            try {
                // a claim as a player's purchase leaves it (Region Guard's own store: its api makes no claims)
                com.mrgregles.bsp_region_guard.region.Region c = new com.mrgregles.bsp_region_guard.region.Region(claim, level.dimension(), cuboid(box(h, 0, 1, 0, 3, 3, 3)),
                        com.mrgregles.bsp_region_guard.region.RegionType.CLAIM);
                c.setPriority(1);
                com.mrgregles.bsp_region_guard.region.RegionStore.get(level).add(c);
                JsonObject f = at(level, claim);
                f.addProperty("version", version(level, claim));
                WebActions.Answer adm = act(server, "ADMIN", "region_remove", f);
                h.assertTrue(!adm.ok() && adm.message().contains("only Owners") && RegionGuardApi.get().regions().view(level, claim) != null, "an Admin keeps a player's claim (D772): " + adm.message());
                WebActions.Answer own = act(server, "OWNER", "region_remove", f);
                h.assertTrue(own.ok() && RegionGuardApi.get().regions().view(level, claim) == null, "an Owner gives it up by Region Guard's rule: " + own.message());
                h.assertTrue(logged(server, LogEntry.REGION_REMOVE, "removed claim " + claim) == 1, "logged once as region_remove");

                h.assertTrue(admin().create(level, new RegionAdmin.NewRegion(mine, cuboid(box(h, 5, 1, 5, 7, 3, 7)), "mine", 3, null, null, 0, 0, 0, 0, List.of(new RegionView.Fill("minecraft:stone", 1000)), 300), "test").ok(), "a mine");
                admin().mineShell(level, mine, version(level, mine), "test", 1);
                h.assertTrue(RegionGuardApi.get().regions().view(level, mine + "_shell") != null, "with a shell");
                WebActions.Answer reset = act(server, "ADMIN", "region_mine_reset", at(level, mine));
                h.assertTrue(reset.ok() && logged(server, LogEntry.REGION_EDIT, "reset mine " + mine) == 1, "a mine reset now, logged: " + reset.message());
                JsonObject sh = at(level, mine + "_shell");
                sh.addProperty("version", version(level, mine + "_shell"));
                h.assertTrue(!act(server, "OWNER", "region_remove", sh).ok() && RegionGuardApi.get().regions().view(level, mine + "_shell") != null, "a shell alone stays (D184)");
                JsonObject mf = at(level, mine);
                mf.addProperty("version", version(level, mine));
                h.assertTrue(act(server, "ADMIN", "region_remove", mf).ok() && RegionGuardApi.get().regions().view(level, mine) == null
                        && RegionGuardApi.get().regions().view(level, mine + "_shell") == null, "a mine takes its shell");
                if (RegionGuardApi.get().regions().view(level, GLOBAL) != null) {
                    JsonObject gf = at(level, GLOBAL);
                    gf.addProperty("version", version(level, GLOBAL));
                    h.assertTrue(!act(server, "OWNER", "region_remove", gf).ok() && RegionGuardApi.get().regions().view(level, GLOBAL) != null, "the world default stays");
                }

                h.assertTrue(admin().create(level, RegionAdmin.NewRegion.protectedRegion(prot, cuboid(box(h, 9, 1, 9, 10, 2, 10)), 1), "test").ok(), "a region");
                JsonObject pf = at(level, prot);
                pf.addProperty("version", version(level, prot) - 1);
                WebActions.Answer old = act(server, "ADMIN", "region_remove", pf);
                h.assertTrue(!old.ok() && old.data().getAsJsonObject().get("stale").getAsBoolean() && RegionGuardApi.get().regions().view(level, prot) != null, "an old version removes nothing: " + old.message());
                JsonObject far = at(level, prot);
                far.addProperty("dim", "minecraft:no_such_world");
                h.assertTrue(!act(server, "ADMIN", "region_remove", far).ok(), "a world not loaded here is refused");
            } finally {
                clean(level, made);
                tearDown();
            }
            h.succeed();
        }

        static void check_makes_nothing_and_create_makes_and_logs(GameTestHelper h) {
            ServerLevel level = h.getLevel();
            MinecraftServer server = level.getServer();
            String t = tag(), first = "su_c1_" + t, second = "su_c2_" + t, made3 = "su_c3_" + t;
            List<String> made = List.of(first, second, made3);
            setUp();
            String was = allowPlots(level);
            try {
                h.assertTrue(admin().create(level, new RegionAdmin.NewRegion(first, cuboid(box(h, 0, 1, 0, 4, 3, 4)), "plot_for_sale", 5, null, null, 100, 0, 0, 0, List.of(), 0), "test").ok(), "a plot");
                JsonObject across = at(level, second);
                across.addProperty("type", "plot_for_sale");
                across.add("box", box(h, 2, 1, 2, 7, 3, 7));
                across.addProperty("priority", 5);
                across.addProperty("parent", "");
                across.addProperty("name", "");
                JsonObject price = new JsonObject();
                price.addProperty("price", 50);
                across.add("claim", price);
                WebActions.Answer no = act(server, "ADMIN", "region_check", across);
                h.assertTrue(!no.ok() && no.message().contains(first) && RegionGuardApi.get().regions().view(level, second) == null, "a plot partly over another is refused, naming it: " + no.message());
                WebActions.Answer noMake = act(server, "ADMIN", "region_create", across);
                h.assertTrue(!noMake.ok() && RegionGuardApi.get().regions().view(level, second) == null, "create refuses it the same way");

                JsonObject ok = at(level, made3);
                ok.addProperty("type", "mine");
                ok.add("box", box(h, 6, 1, 6, 8, 2, 8));
                ok.addProperty("priority", 10);
                ok.addProperty("parent", "");
                ok.addProperty("name", "Quarry");
                JsonObject m = new JsonObject();
                JsonArray fill = new JsonArray();
                JsonObject stone = new JsonObject();
                stone.addProperty("block", "minecraft:stone");
                stone.addProperty("weight", 1000);
                fill.add(stone);
                m.add("fill", fill);
                m.addProperty("intervalSeconds", 900);
                ok.add("mine", m);
                WebActions.Answer good = act(server, "ADMIN", "region_check", ok);
                h.assertTrue(good.ok() && RegionGuardApi.get().regions().view(level, made3) == null && logged(server, LogEntry.REGION_CREATE, made3) == 0, "a good check makes and logs nothing: " + good.message());
                WebActions.Answer mod = act(server, "MODERATOR", "region_create", ok);
                h.assertTrue(!mod.ok() && RegionGuardApi.get().regions().view(level, made3) == null, "a Moderator makes nothing");
                WebActions.Answer make = act(server, "OWNER", "region_create", ok);
                RegionView r = RegionGuardApi.get().regions().view(level, made3);
                h.assertTrue(make.ok() && r != null && r.type().equals("mine") && r.name().equals("Quarry") && r.mine().intervalSeconds() == 900
                        && make.data().getAsJsonObject().get("region").getAsString().equals(made3) && make.data().getAsJsonObject().get("version").getAsInt() == r.version(), "made: " + make.message());
                h.assertTrue(logged(server, LogEntry.REGION_CREATE, "made mine " + made3) == 1, "logged once as region_create");
            } finally {
                clean(level, made);
                putBackPlots(level, was);
                tearDown();
            }
            h.succeed();
        }

        static void a_change_moves_regions_at_and_without_region_guard_every_action_says_so(GameTestHelper h) {
            ServerLevel level = h.getLevel();
            MinecraftServer server = level.getServer();
            String id = "su_w_" + tag();
            setUp();
            long[] before = {RegionGuard.changedAt()};
            WebRegions.resetWatchForTests();
            try {
                RegionGuard.regionsForTests(false);
                WebActions.Answer off = act(server, "OWNER", "regions_list", new JsonObject());
                h.assertTrue(!off.ok() && off.message().contains("Region Guard 0.8.0 or newer is not on this server"), "without Region Guard: said plainly: " + off.message());
                h.assertTrue(!act(server, "OWNER", "region_create", at(level, id)).ok(), "and nothing is made");
                JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
                h.assertTrue(!snap.get("regions").getAsBoolean() && !snap.has("regionsAt"), "the snapshot says no regions here");
                RegionGuard.regionsForTests(null);
                h.assertTrue(admin().create(level, RegionAdmin.NewRegion.protectedRegion(id, cuboid(box(h, 0, 1, 0, 2, 2, 2)), 1), "test").ok(), "a region made in game");
            } catch (RuntimeException e) {
                clean(level, List.of(id));
                tearDown();
                throw e;
            }
            h.runAfterDelay(2, () -> {
                try {
                    h.assertTrue(RegionGuard.changedAt() > before[0], "Region Guard's change event at the tick's end moved regionsAt (D781)");
                    JsonObject snap = WebSnapshot.build(server, "alpha", List.of(), System.currentTimeMillis());
                    h.assertTrue(snap.get("regionsAt").getAsLong() == RegionGuard.changedAt(), "the snapshot carries it");
                } finally {
                    clean(level, List.of(id));
                    tearDown();
                }
                h.succeed();
            });
        }

    }
}
