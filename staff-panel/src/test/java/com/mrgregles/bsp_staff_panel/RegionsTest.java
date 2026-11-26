package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import com.mrgregles.bsp_staff_panel.auth.Rank;
import com.mrgregles.bsp_staff_panel.crypto.Signed;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.regions.RegionDesk;
import com.mrgregles.bsp_staff_panel.store.Account;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WORLDS tab's desk (SU-155; D762 to D777): reads for every rank, kept a minute and shared; changes for Admins and
 * Owners only, every field checked before anything is signed; a claim removed by Owners only and the global region never;
 * a stale version refused and the server read again. The made-up game server is {@link DemoRegions}, as {@code --demo}
 * answers.
 */
class RegionsTest {
    static final byte[] KEY = new byte[32];

    /** A desk over a feed with Frostwarf (Region Guard answering) and Moongrove (not), the actions answered by the demo's regions. */
    private static final class Rig {
        final AtomicLong clock = new AtomicLong(50_000_000L);
        final Feed feed = new Feed(clock::get);
        final List<JsonObject> sent = new CopyOnWriteArrayList<>();
        final Actions actions;
        final RegionDesk desk;

        Rig() {
            DemoRegions.reset();
            Actions[] a = new Actions[1];
            a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
                JsonObject act = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(payload.substring(0, payload.indexOf('.'))), StandardCharsets.UTF_8)).getAsJsonObject();
                sent.add(act);
                JsonObject rg = DemoRegions.answer(act.get("action").getAsString(), act);
                JsonObject ans = new JsonObject();
                ans.addProperty("id", uid);
                ans.add("ok", rg.get("ok"));
                ans.add("message", rg.get("message"));
                if (!rg.get("data").isJsonNull()) {
                    ans.add("data", rg.get("data"));
                }
                new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", Signed.seal(KEY, ans.toString())))).start();
            }, clock::get, 3);
            actions = a[0];
            desk = new RegionDesk(feed, () -> actions, clock::get);
            snapshots();
        }

        void snapshots() {
            feed.accept("Frostwarf", clock.get(), "{\"v\":1,\"server\":\"Frostwarf\",\"at\":" + clock.get() + ",\"answers\":true,\"regions\":true,\"mspt\":21.5,\"players\":[]}");
            feed.accept("Moongrove", clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + clock.get() + ",\"answers\":true,\"players\":[]}");
        }

        long count(String action) {
            return sent.stream().filter(s -> s.get("action").getAsString().equals(action)).count();
        }
    }

    private static Account account(Rank rank) {
        Account a = new Account();
        a.email = rank.name().toLowerCase() + "@test";
        a.mcName = "Staff_" + rank.name();
        a.rank = rank;
        return a;
    }

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject region(JsonObject page, String id) {
        for (var e : page.getAsJsonArray("regions")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id) && e.getAsJsonObject().get("dim").getAsString().equals("minecraft:overworld")) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError(id);
    }

    @Test
    void every_rank_reads_the_grid_and_moderators_change_nothing() {
        Rig r = new Rig();
        Account mod = account(Rank.MODERATOR);
        JsonObject grid = r.desk.servers(mod);
        assertTrue(grid.get("ok").getAsBoolean());
        JsonArray servers = grid.getAsJsonArray("servers");
        JsonObject frost = null, moon = null;
        for (var e : servers) {
            String n = e.getAsJsonObject().get("server").getAsString();
            if (n.equals("Frostwarf")) {
                frost = e.getAsJsonObject();
            } else if (n.equals("Moongrove")) {
                moon = e.getAsJsonObject();
            }
        }
        assertTrue(frost != null && frost.get("live").getAsBoolean() && frost.getAsJsonObject("counts").get("claim").getAsInt() == 3, "Frostwarf's regions counted by kind: " + frost);
        assertEquals(4, frost.getAsJsonArray("dims").size(), "its four dimensions");
        assertTrue(moon != null && moon.has("error") && !moon.has("counts"), "a server whose Region Guard does not answer is named, with nothing counted");
        assertEquals(1, r.count("regions_list"), "only the server that answers for regions was asked");

        JsonObject save = r.desk.save(mod, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"market\",\"version\":1,\"set\":{\"name\":\"x\"}}"));
        assertFalse(save.get("ok").getAsBoolean());
        assertEquals(RegionDesk.ADMINS, save.get("message").getAsString());
        assertFalse(r.desk.remove(mod, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"market\",\"version\":1}")).get("ok").getAsBoolean());
        assertFalse(r.desk.resetMine(mod, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"iron_mine\"}")).get("ok").getAsBoolean());
        assertEquals(1, r.sent.size(), "no change was signed for a Moderator");
    }

    @Test
    void an_answer_is_kept_a_minute_for_everyone_then_read_again() {
        Rig r = new Rig();
        r.desk.servers(account(Rank.MODERATOR));
        r.desk.server(account(Rank.ADMIN), "frostwarf", false);
        assertEquals(1, r.count("regions_list"), "the second staff member, within the minute, is given the same answer");
        r.desk.server(account(Rank.ADMIN), "Frostwarf", true);
        assertEquals(2, r.count("regions_list"), "REFRESH reads again");
        r.clock.addAndGet(RegionDesk.FRESH_MS + 1);
        r.snapshots();
        r.desk.servers(account(Rank.OWNER));
        assertEquals(3, r.count("regions_list"), "after a minute, read again");
    }

    @Test
    void a_save_signs_only_checked_fields_with_the_version_it_was_read_at() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        JsonObject page = r.desk.server(admin, "Frostwarf", false);
        JsonObject market = region(page, "market");
        long v = market.get("version").getAsLong();
        int before = r.sent.size();
        String[] bad = {
                "{\"flags\":{\"Not A Flag\":\"allow\"}}",
                "{\"colour\":\"red\"}",
                "{\"box\":[1,2,3]}",
                "{\"box\":[1,2,3,4,99999,6]}",
                "{\"priority\":1.5}",
                "{\"mine\":{\"fill\":[{\"block\":\"minecraft:stone\",\"weight\":600}]}}",
                "{\"type\":\"claim\"}",
                "{\"owners\":{\"add\":[\"not a name!\"]}}",
                "{\"border\":{\"size\":100}}",
        };
        for (String set : bad) {
            JsonObject out = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"market\",\"version\":" + v + ",\"set\":" + set + "}"));
            assertFalse(out.get("ok").getAsBoolean(), "refused: " + set);
        }
        assertEquals(before, r.sent.size(), "nothing malformed was signed");

        JsonObject out = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"market\",\"version\":" + v
                + ",\"set\":{\"flags\":{\"build\":\"allow\",\"greeting\":null,\"deny-spawn\":[\"minecraft:zombie\"]},\"box\":[30,320,20,-40,-64,-35],\"members\":{\"add\":[\"Vexa\"],\"remove\":[]}}}"));
        assertTrue(out.get("ok").getAsBoolean(), out.toString());
        assertTrue(out.has("panelLog") && out.get("panelLog").getAsString().contains("changed region market on Frostwarf"), "a line for the panel's log");
        JsonObject signed = r.sent.get(r.sent.size() - 1);
        assertEquals("region_save", signed.get("action").getAsString());
        assertEquals(v, signed.get("version").getAsLong(), "the version the page read");
        assertEquals("[-40,-64,-35,30,320,20]", signed.getAsJsonObject("set").get("box").toString(), "the corners put in order");
        assertEquals(java.util.Set.of("flags", "box", "members"), signed.getAsJsonObject("set").keySet());

        JsonObject again = r.desk.server(admin, "Frostwarf", false);
        assertEquals(v + 1, region(again, "market").get("version").getAsLong(), "the server is read again after a change");
        assertEquals("allow", region(again, "market").getAsJsonObject("flags").get("build").getAsString());
    }

    @Test
    void a_save_on_an_old_version_is_refused_and_the_region_read_again() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        JsonObject page = r.desk.server(owner, "Frostwarf", false);
        long v = region(page, "arena").get("version").getAsLong();
        JsonObject out = r.desk.save(owner, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"arena\",\"version\":" + (v - 1) + ",\"set\":{\"priority\":12}}"));
        assertFalse(out.get("ok").getAsBoolean());
        assertTrue(out.get("message").getAsString().contains("changed in game"), out.toString());
        assertTrue(out.has("stale") && out.get("stale").getAsBoolean(), "the page is told to show the region as it is now");
        long reads = r.count("regions_list");
        r.desk.server(owner, "Frostwarf", false);
        assertEquals(reads + 1, r.count("regions_list"), "read again at once");
    }

    @Test
    void a_claim_is_removed_by_owners_only_and_the_global_region_never() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN), owner = account(Rank.OWNER);
        JsonObject page = r.desk.server(admin, "Frostwarf", false);
        long v = region(page, "kestrel_88_claim").get("version").getAsLong();
        String body = "{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"kestrel_88_claim\",\"version\":" + v + "}";
        JsonObject byAdmin = r.desk.remove(admin, obj(body));
        assertFalse(byAdmin.get("ok").getAsBoolean());
        assertEquals(RegionDesk.CLAIM_OWNERS, byAdmin.get("message").getAsString());
        assertEquals(0, r.count("region_remove"), "refused before signing");
        JsonObject global = r.desk.remove(owner, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"__global__\",\"version\":1}"));
        assertFalse(global.get("ok").getAsBoolean(), "the global region stays");
        JsonObject byOwner = r.desk.remove(owner, obj(body));
        assertTrue(byOwner.get("ok").getAsBoolean(), byOwner.toString());
        assertTrue(r.desk.server(owner, "Frostwarf", false).getAsJsonArray("regions").asList().stream()
                .noneMatch(e -> e.getAsJsonObject().get("id").getAsString().equals("kestrel_88_claim")), "gone from the next read");
    }

    @Test
    void a_server_that_stops_keeps_its_last_answer_greyed_and_takes_no_changes() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        r.desk.servers(admin);
        r.clock.addAndGet(5 * 60_000L); // Frostwarf sends nothing more
        r.feed.accept("Moongrove", r.clock.get(), "{\"v\":1,\"server\":\"Moongrove\",\"at\":" + r.clock.get() + ",\"answers\":true,\"players\":[]}");
        JsonObject frost = null;
        for (var e : r.desk.servers(admin).getAsJsonArray("servers")) {
            if (e.getAsJsonObject().get("server").getAsString().equals("Frostwarf")) {
                frost = e.getAsJsonObject();
            }
        }
        assertTrue(frost != null && !frost.get("live").getAsBoolean() && frost.get("heardAt").getAsLong() > 0, "kept, greyed, with when it was last heard from: " + frost);
        JsonObject page = r.desk.server(admin, "Frostwarf", false);
        assertTrue(page.get("ok").getAsBoolean() && !page.get("live").getAsBoolean() && page.has("note"), "its last read, said to be earlier");
        JsonObject save = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"market\",\"version\":1,\"set\":{\"name\":\"x\"}}"));
        assertFalse(save.get("ok").getAsBoolean(), "nothing is changed on a server that is not sending");
    }

    @Test
    void a_region_drawn_on_the_map_is_checked_and_created_by_admins_only() {
        Rig r = new Rig();
        String good = "{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"North_Farms\",\"type\":\"protected\",\"name\":\"North farms\","
                + "\"box\":[-250,-64,-95,-330,319,-160],\"priority\":10,\"parent\":\"\"}";
        JsonObject mod = r.desk.create(account(Rank.MODERATOR), obj(good));
        assertEquals(RegionDesk.ADMINS, mod.get("message").getAsString());
        assertFalse(r.desk.check(account(Rank.MODERATOR), obj(good)).get("ok").getAsBoolean());
        String[] bad = {
                good.replace("\"protected\"", "\"claim\""),
                good.replace("\"protected\"", "\"global\""),
                good.replace("North_Farms", "__global__"),
                good.replace("[-250,-64,-95,-330,319,-160]", "[1,2,3]"),
                good.replace(",\"box\":[-250,-64,-95,-330,319,-160]", ""),
                good.replace("\"priority\":10", "\"priority\":\"high\""),
        };
        Account admin = account(Rank.ADMIN);
        for (String b : bad) {
            assertFalse(r.desk.create(admin, obj(b)).get("ok").getAsBoolean(), "refused: " + b);
        }
        assertEquals(0, r.count("region_create") + r.count("region_check"), "nothing malformed was signed");

        JsonObject check = r.desk.check(admin, obj(good));
        assertTrue(check.get("ok").getAsBoolean(), check.toString());
        assertEquals(1, r.count("region_check"));
        JsonObject made = r.desk.create(admin, obj(good));
        assertTrue(made.get("ok").getAsBoolean(), made.toString());
        assertTrue(made.get("panelLog").getAsString().contains("created region north_farms (protected) on Frostwarf"), made.get("panelLog").getAsString());
        JsonObject signed = r.sent.get(r.sent.size() - 1);
        assertEquals("north_farms", signed.get("region").getAsString(), "the id in small letters, as Region Guard keeps it");
        assertEquals("[-330,-64,-160,-250,319,-95]", signed.get("box").toString(), "the corners put in order");
        assertEquals(1, region(r.desk.server(admin, "Frostwarf", false), "north_farms").get("version").getAsLong(), "there at once in the next read");
        assertFalse(r.desk.create(admin, obj(good)).get("ok").getAsBoolean(), "a second with the same id is refused by Region Guard");
    }

    @Test
    void region_guards_rules_come_back_word_for_word() {
        Rig r = new Rig();
        Account owner = account(Rank.OWNER);
        JsonObject over = r.desk.check(owner, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"plot_9\",\"type\":\"plot_for_sale\","
                + "\"box\":[-330,60,-160,-120,120,60],\"priority\":30,\"claim\":{\"price\":6000}}"));
        assertFalse(over.get("ok").getAsBoolean());
        assertTrue(over.get("message").getAsString().contains("plot_1"), over.toString());
        JsonObject high = r.desk.check(owner, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"sky\",\"type\":\"protected\",\"box\":[0,0,0,10,400,10]}"));
        assertFalse(high.get("ok").getAsBoolean());
        assertTrue(high.get("message").getAsString().contains("height"), high.toString());
        JsonObject mine = r.desk.create(owner, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"gold_mine\",\"type\":\"mine\",\"box\":[-330,14,-160,-280,62,-110],"
                + "\"priority\":15,\"mine\":{\"intervalSeconds\":1200,\"fill\":[{\"block\":\"minecraft:stone\",\"weight\":700},{\"block\":\"minecraft:gold_ore\",\"weight\":300}]}}"));
        assertTrue(mine.get("ok").getAsBoolean(), mine.toString());
        JsonObject made = region(r.desk.server(owner, "Frostwarf", false), "gold_mine");
        assertEquals(2, made.getAsJsonObject("mine").getAsJsonArray("fill").size(), "its block mix as drawn");
    }

    @Test
    void audit_a_claim_stays_a_claim_and_only_owners_remove_it_even_unread() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        long v = region(DemoRegions.world("Frostwarf"), "kestrel_88_claim").get("version").getAsLong();
        String claim = "{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"kestrel_88_claim\",\"version\":" + v;
        JsonObject unread = r.desk.remove(admin, obj(claim + "}"));
        assertEquals(RegionDesk.CLAIM_OWNERS, unread.get("message").getAsString(), "no read yet: the panel reads it first, never lets it through");
        JsonObject switched = r.desk.save(admin, obj(claim + ",\"set\":{\"type\":\"protected\"}}"));
        assertFalse(switched.get("ok").getAsBoolean(), "a claim keeps its kind: " + switched);
        long mv = region(DemoRegions.world("Frostwarf"), "iron_mine").get("version").getAsLong();
        assertFalse(r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"iron_mine\",\"version\":" + mv + ",\"set\":{\"type\":\"protected\"}}")).get("ok").getAsBoolean(), "a mine too");
        long sv = region(DemoRegions.world("Frostwarf"), "iron_mine_shell").get("version").getAsLong();
        assertFalse(r.desk.remove(account(Rank.OWNER), obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"iron_mine_shell\",\"version\":" + sv + "}")).get("ok").getAsBoolean(), "a shell goes with its mine");
        assertEquals(0, r.count("region_remove") + r.count("region_save"), "none of them signed");
    }

    @Test
    void audit_nothing_is_renamed_or_parented_to_the_global_region_and_odd_names_stay_out_of_the_log() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        String base = "{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"market\",\"version\":1,\"set\":";
        assertFalse(r.desk.save(admin, obj(base + "{\"newId\":\"__global__\"}}")).get("ok").getAsBoolean());
        assertFalse(r.desk.save(admin, obj(base + "{\"parent\":\"__global__\"}}")).get("ok").getAsBoolean());
        assertFalse(r.desk.save(admin, obj(base + "{\"parent\":null}}")).get("ok").getAsBoolean(), "a missing parent is not taken as none");
        assertFalse(r.desk.save(admin, obj(base + "[]}")).get("ok").getAsBoolean(), "a change that is not an object");
        JsonObject odd = r.desk.save(admin, obj("{\"server\":\"x\\n12:00 someone removed spawn\",\"dim\":\"minecraft:overworld\",\"id\":\"market\",\"version\":1,\"set\":{\"name\":\"y\"}}"));
        assertFalse(odd.get("message").getAsString().contains("\n") || odd.get("message").getAsString().contains("removed spawn"), "a request's text never reaches the log as it is: " + odd);
    }

    @Test
    void a_coin_tier_is_a_name_plots_in_pvp_has_three_settings_and_a_plot_limit_may_be_none() {
        Rig r = new Rig();
        Account admin = account(Rank.ADMIN);
        JsonObject page = r.desk.server(admin, "Frostwarf", false);
        assertTrue(page.getAsJsonArray("coinTiers").size() == 5, "BSP Core's tiers come with the page (D779)");
        JsonObject plot = region(page, "plot_1");
        assertEquals("gold", plot.getAsJsonObject("claim").get("coinTier").getAsString(), "a tier by name");
        long v = plot.get("version").getAsLong();
        JsonObject num = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"plot_1\",\"version\":" + v + ",\"set\":{\"claim\":{\"coinTier\":2}}}"));
        assertFalse(num.get("ok").getAsBoolean(), "a number is no tier now: " + num);
        JsonObject bad = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"plot_1\",\"version\":" + v + ",\"set\":{\"claim\":{\"plotLimit\":-2}}}"));
        assertFalse(bad.get("ok").getAsBoolean(), "-2 is no limit");
        JsonObject ok = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"plot_1\",\"version\":" + v + ",\"set\":{\"claim\":{\"coinTier\":\"\",\"plotLimit\":-1}}}"));
        assertTrue(ok.get("ok").getAsBoolean(), "the config's tier and no limit: " + ok);
        JsonObject signed = r.sent.get(r.sent.size() - 1).getAsJsonObject("set").getAsJsonObject("claim");
        assertEquals("default", signed.get("coinTier").getAsString(), "the config's tier is sent as default");
        assertEquals(-1, signed.get("plotLimit").getAsInt());

        JsonObject g = region(r.desk.server(admin, "Frostwarf", true), "__global__");
        assertEquals("default", g.getAsJsonObject("border").get("plotsInPvp").getAsString(), "three settings, as Region Guard keeps them (D780)");
        long gv = g.get("version").getAsLong();
        JsonObject on = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"__global__\",\"version\":" + gv + ",\"set\":{\"border\":{\"plotsInPvp\":true}}}"));
        assertFalse(on.get("ok").getAsBoolean(), "on and off is no setting now");
        JsonObject allow = r.desk.save(admin, obj("{\"server\":\"Frostwarf\",\"dim\":\"minecraft:overworld\",\"id\":\"__global__\",\"version\":" + gv + ",\"set\":{\"border\":{\"plotsInPvp\":\"allow\"}}}"));
        assertTrue(allow.get("ok").getAsBoolean(), "allow: " + allow);
        JsonObject dim = page.getAsJsonArray("dims").get(0).getAsJsonObject();
        assertTrue(dim.has("plotsAllowed") && dim.has("plotRule"), "whether plots may be made, and why (D778): " + dim);
    }

    @Test
    void a_change_on_the_server_makes_the_next_read_fresh() {
        Rig r = new Rig();
        Account mod = account(Rank.MODERATOR);
        r.desk.server(mod, "Frostwarf", false);
        r.desk.server(mod, "Frostwarf", false);
        assertEquals(1, r.count("regions_list"), "kept a minute and shared");
        String snap = "{\"v\":1,\"server\":\"Frostwarf\",\"at\":%d,\"answers\":true,\"regions\":true,\"regionsAt\":%d,\"mspt\":21.5,\"players\":[]}";
        r.clock.addAndGet(1000);
        r.feed.accept("Frostwarf", r.clock.get(), snap.formatted(r.clock.get(), 100L));
        r.desk.server(mod, "Frostwarf", false);
        assertEquals(1, r.count("regions_list"), "the first regionsAt heard is only remembered");
        r.clock.addAndGet(1000);
        r.feed.accept("Frostwarf", r.clock.get(), snap.formatted(r.clock.get(), 100L));
        r.desk.server(mod, "Frostwarf", false);
        assertEquals(1, r.count("regions_list"), "the same regionsAt changes nothing");
        r.clock.addAndGet(1000);
        r.feed.accept("Frostwarf", r.clock.get(), snap.formatted(r.clock.get(), 250L));
        r.desk.server(mod, "Frostwarf", false);
        assertEquals(2, r.count("regions_list"), "a region changed there: read again (D781)");
        JsonObject live = r.feed.json();
        assertEquals(250L, live.getAsJsonArray("servers").get(0).getAsJsonObject().get("regionsAt").getAsLong(), "the live page gets it too");
    }
}
