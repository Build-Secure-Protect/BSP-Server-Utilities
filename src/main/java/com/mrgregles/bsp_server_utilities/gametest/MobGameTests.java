package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.mobs.MobData;
import com.mrgregles.bsp_server_utilities.mobs.MobLimits;
import com.mrgregles.bsp_server_utilities.mobs.MobPlan;
import com.mrgregles.bsp_server_utilities.mobs.MobSettings;
import com.mrgregles.bsp_server_utilities.mobs.MobsAdmin;
import com.mrgregles.bsp_server_utilities.network.AdminMobsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminMobsPacket;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mob limits (owner decisions D471 to D486): the settings within their limits, shared, the most specific number winning;
 * a full chunk refusing new spawns by reason (and {@code /summon}) but never conversions (a real skeleton to stray),
 * buckets, jockeys, raiders or mobs never counted; breeding and spawn eggs refused, a never-counted kind not; the fullest chunks and REMOVE EXTRAS taking the most numerous kind first,
 * never named ones; HERE and LIMIT; the network list. Each test limits only its own chunk, counts what was there before
 * it, and uses mobs no other test makes, so tests running beside it change nothing.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class MobGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY, OVERWORLD = "minecraft:overworld";

    private static Mob mob(GameTestHelper h, EntityType<? extends Mob> type, BlockPos at) {
        Mob m = type.create(h.getLevel());
        m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        return m;
    }

    /** A block of the test's own chunk, inside the structure. */
    private static BlockPos spot(GameTestHelper h) {
        return h.absolutePos(new BlockPos(1, 2, 1));
    }

    private static MobSettings limitHere(MobSettings s, ChunkPos c, int limit) {
        return s.withChunk(Storage.currentServerId(), OVERWORLD, c.x, c.z, limit).sanitised();
    }

    private static void clean(List<? extends Entity> made) {
        for (Entity e : made) {
            e.discard();
        }
    }

    @GameTest(template = EMPTY)
    public void mob_limits_keep_to_their_limits_round_trip_are_shared_and_the_most_specific_wins(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MobSettings odd = new MobSettings(5000, List.of(new MobSettings.DimLimit(" Minecraft:The_Nether ", 60), new MobSettings.DimLimit("minecraft:the_nether", 9), new MobSettings.DimLimit("bad id", 3)),
                List.of(new MobSettings.ChunkLimit("Frostwarf", "minecraft:overworld", 120, -45, -7), new MobSettings.ChunkLimit("bad|server", "minecraft:overworld", 1, 1, 3)),
                List.of("Minecraft:Villager", "minecraft:villager", "nonsense"), 4).sanitised();
        h.assertTrue(odd.every() == MobSettings.MAX_LIMIT && odd.dims().equals(List.of(new MobSettings.DimLimit("minecraft:the_nether", 60))), "numbers kept to 0 to 1000, ids cleaned, no doubles: " + odd);
        h.assertTrue(odd.chunks().equals(List.of(new MobSettings.ChunkLimit("Frostwarf", "minecraft:overworld", 120, -45, MobSettings.OFF))) && odd.never().equals(List.of("minecraft:villager")),
                "a chunk below 0 is OFF, a bad server name and a bad mob id are dropped: " + odd);
        h.assertTrue(MobSettings.decode(odd.encode()).equals(odd) && MobSettings.load(odd.save()).equals(odd) && MobSettings.decode("nonsense") == null, "encoded and saved, they round trip");
        h.assertTrue(!MobSettings.DEFAULTS.anyOn() && MobSettings.DEFAULTS.every() == MobSettings.OFF, "every number starts OFF (D484)");

        MobSettings s = new MobSettings(40, List.of(new MobSettings.DimLimit("compactmachines:compact_world", 25), new MobSettings.DimLimit("minecraft:the_end", MobSettings.OFF)),
                List.of(new MobSettings.ChunkLimit("Frostwarf", "compactmachines:compact_world", 3, 4, 80)), List.of(), 1).sanitised();
        h.assertTrue(s.limitFor("Frostwarf", "compactmachines:compact_world", 3, 4).equals(new MobSettings.Limit(80, MobSettings.From.CHUNK)), "a chunk's own number wins (D472)");
        h.assertTrue(s.limitFor("Moongrove", "compactmachines:compact_world", 3, 4).equals(new MobSettings.Limit(25, MobSettings.From.DIMENSION)), "the same chunk on another server is another place: its dimension's (D476)");
        h.assertTrue(s.limitFor("Moongrove", "minecraft:overworld", 3, 4).equals(new MobSettings.Limit(40, MobSettings.From.EVERY)), "elsewhere every chunk's");
        h.assertTrue(!s.limitFor("Moongrove", "minecraft:the_end", 0, 0).on(), "a dimension set to OFF has no limit whatever every chunk's number says");
        h.assertTrue(s.withDim("compactmachines:compact_world", null).limitFor("Moongrove", "compactmachines:compact_world", 0, 0).limit() == 40, "× takes a dimension away");

        MobSettings before = MobData.get(server).settings();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        Storage.useForTests(alpha);
        try {
            MobSettings saved = MobLimits.setSettings(server, s);
            alpha.drain();
            h.assertTrue(saved.revision() > before.revision() && db.loadMobSettings().equals(saved), "saved for the network with a rising revision");
            h.assertTrue(db.fetchNoticesAfter(0, 20).stream().anyMatch(n -> n.kind().equals(MobLimits.KIND_SETTINGS) && MobSettings.decode(n.payload()).equals(saved)), "and announced");
            MobLimits.apply(server, s.withEvery(3).withRevision(saved.revision() - 1));
            h.assertTrue(MobLimits.settings(server).equals(saved), "an older copy changes nothing");
            MobSettings newer = s.withEvery(7).withRevision(saved.revision() + 1);
            MobLimits.apply(server, newer);
            h.assertTrue(MobLimits.settings(server).equals(newer), "a newer copy from another server is taken");
        } finally {
            Storage.useForTests(null);
            alpha.close();
            MobData.get(server).setSettings(before);
        }
        h.succeed();
    }

    /** A mob made as the game makes it for {@code why}: asked through Forge's spawn event, then added. True when it came in. */
    private static boolean spawnAs(GameTestHelper h, Mob m, MobSpawnType why) {
        ServerLevel level = h.getLevel();
        ForgeEventFactory.onFinalizeSpawn(m, level, level.getCurrentDifficultyAt(m.blockPosition()), why, null, null);
        return level.addFreshEntity(m);
    }

    @GameTest(template = EMPTY)
    public void a_full_chunk_refuses_new_spawns_but_never_conversions_buckets_jockeys_raiders_or_mobs_never_counted(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos at = spot(h);
        ChunkPos c = new ChunkPos(at);
        MobSettings before = MobData.get(server).settings();
        List<Entity> made = new ArrayList<>();
        try {
            int base = MobLimits.count(level, c);
            MobData.get(server).setSettings(limitHere(before, c, base + 2));
            Mob skeleton = mob(h, EntityType.SKELETON, at), mite = mob(h, EntityType.ENDERMITE, at);
            h.assertTrue(spawnAs(h, skeleton, MobSpawnType.NATURAL) && spawnAs(h, mite, MobSpawnType.SPAWNER), "under the limit new mobs come");
            made.add(skeleton);
            made.add(mite);
            ArmorStand stand = EntityType.ARMOR_STAND.create(level);
            stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            h.assertTrue(level.addFreshEntity(stand) && MobLimits.count(level, c) == base + 2, "an armour stand is not a mob: it is let in and not counted (D471)");
            made.add(stand);

            long refusedBefore = MobLimits.refused();
            for (MobSpawnType why : List.of(MobSpawnType.NATURAL, MobSpawnType.SPAWNER, MobSpawnType.SPAWN_EGG, MobSpawnType.MOB_SUMMONED, MobSpawnType.REINFORCEMENT, MobSpawnType.PATROL, MobSpawnType.DISPENSER)) {
                Mob m = mob(h, EntityType.ENDERMITE, at);
                h.assertFalse(spawnAs(h, m, why), "at the limit a new mob is refused: " + why + " (D474)");
            }
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "summon minecraft:endermite " + at.getX() + " " + at.getY() + " " + at.getZ());
            h.assertTrue(MobLimits.count(level, c) == base + 2 && MobLimits.refused() == refusedBefore + 8, "and /summon too: " + MobLimits.count(level, c) + ", refused " + (MobLimits.refused() - refusedBefore));

            for (MobSpawnType why : List.of(MobSpawnType.BUCKET, MobSpawnType.JOCKEY, MobSpawnType.EVENT, MobSpawnType.CONVERSION, MobSpawnType.TRIGGERED)) {
                Mob m = mob(h, EntityType.ENDERMITE, at);
                h.assertTrue(spawnAs(h, m, why), "never refused: " + why + " (the game adds them whatever the answer, audit)");
                made.add(m);
            }
            Mob stray = skeleton.convertTo(EntityType.STRAY, true);
            h.assertTrue(stray != null && stray.isAddedToWorld() && stray.isAlive() && !skeleton.isAlive(), "a skeleton freezing into a stray in a full chunk becomes a stray, it does not vanish (audit)");
            made.add(stray);

            MobData.get(server).setSettings(MobData.get(server).settings().withNever("minecraft:silverfish", true).sanitised());
            Mob never = mob(h, EntityType.SILVERFISH, at);
            h.assertTrue(spawnAs(h, never, MobSpawnType.NATURAL), "a mob type never counted is never refused (D477)");
            made.add(never);

            MobData.get(server).setSettings(before);
            Mob free = mob(h, EntityType.ENDERMITE, at);
            h.assertTrue(spawnAs(h, free, MobSpawnType.NATURAL), "with the limit taken away, mobs come again");
            made.add(free);
        } finally {
            for (Entity e : level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(at).inflate(2), e -> e.getType() == EntityType.ENDERMITE || e.getType() == EntityType.STRAY)) {
                e.discard(); // the summoned one too, should it have come
            }
            clean(made);
            MobData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void breeding_and_spawn_eggs_are_refused_in_a_full_chunk(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos at = spot(h);
        ChunkPos c = new ChunkPos(at);
        MobSettings before = MobData.get(server).settings();
        List<Entity> made = new ArrayList<>();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            int base = MobLimits.count(level, c);
            Cow a = (Cow) mob(h, EntityType.COW, at), b = (Cow) mob(h, EntityType.COW, at);
            level.addFreshEntity(a);
            level.addFreshEntity(b);
            made.add(a);
            made.add(b);
            MobData.get(server).setSettings(limitHere(before, c, base + 2));
            int cows = level.getEntities(EntityType.COW, new net.minecraft.world.phys.AABB(at).inflate(4), e -> true).size();
            a.spawnChildFromBreeding(level, b);
            int after = level.getEntities(EntityType.COW, new net.minecraft.world.phys.AABB(at).inflate(4), e -> true).size();
            h.assertTrue(after == cows && MobLimits.count(level, c) == base + 2, "two cows bred in a full chunk give no calf (D474): " + cows + " then " + after);

            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ZOMBIE_SPAWN_EGG));
            PlayerInteractEvent.RightClickBlock egg = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, at,
                    new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
            MinecraftForge.EVENT_BUS.post(egg);
            h.assertTrue(egg.isCanceled(), "a spawn egg used in a full chunk does nothing, and is kept (D474)");

            p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            PlayerInteractEvent.RightClickItem water = new PlayerInteractEvent.RightClickItem(p, InteractionHand.MAIN_HAND);
            MinecraftForge.EVENT_BUS.post(water);
            h.assertTrue(water.isCanceled(), "an egg used on water there is refused before use, so it is kept (audit)");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COW_SPAWN_EGG));
            PlayerInteractEvent.EntityInteract onCow = new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, a);
            MinecraftForge.EVENT_BUS.post(onCow);
            h.assertTrue(onCow.isCanceled(), "and one used on a cow to make a calf (audit)");

            MobData.get(server).setSettings(limitHere(before, c, base + 2).withNever("minecraft:cow", true).sanitised());
            PlayerInteractEvent.EntityInteract neverCow = new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, a);
            MinecraftForge.EVENT_BUS.post(neverCow);
            h.assertFalse(neverCow.isCanceled(), "a cow egg is never refused when cows are never counted (audit)");
            a.setAge(0);
            b.setAge(0);
            a.spawnChildFromBreeding(level, b);
            int calves = level.getEntities(EntityType.COW, new net.minecraft.world.phys.AABB(at).inflate(4), e -> true).size();
            h.assertTrue(calves == cows + 1, "and cows breed (audit): " + calves);
            level.getEntities(EntityType.COW, new net.minecraft.world.phys.AABB(at).inflate(4), e -> !made.contains(e)).forEach(Entity::discard);

            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ZOMBIE_SPAWN_EGG));
            MobData.get(server).setSettings(limitHere(before, c, base + 5));
            PlayerInteractEvent.RightClickBlock room = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, at,
                    new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
            MinecraftForge.EVENT_BUS.post(room);
            h.assertFalse(room.isCanceled(), "with room under the limit the egg works");
        } finally {
            clean(made);
            MobData.get(server).setSettings(before);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_fullest_list_names_its_chunks_and_remove_extras_takes_the_most_numerous_kind_first(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos at = spot(h);
        ChunkPos c = new ChunkPos(at);
        MobSettings before = MobData.get(server).settings();
        List<Mob> made = new ArrayList<>();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            List<MobPlan.TypeCount> plan = MobPlan.plan(List.of(new MobPlan.TypeCount("minecraft:zombie", 71, 0), new MobPlan.TypeCount("minecraft:skeleton", 18, 2),
                    new MobPlan.TypeCount("minecraft:spider", 7, 0)), 96, 25);
            h.assertTrue(plan.equals(List.of(new MobPlan.TypeCount("minecraft:zombie", 71, 0))) && MobPlan.describe(plan).equals("71 zombie"), "the refined page's room: 71 zombies go (D485): " + plan);
            List<MobPlan.TypeCount> named = MobPlan.plan(List.of(new MobPlan.TypeCount("minecraft:cow", 5, 4), new MobPlan.TypeCount("minecraft:pig", 3, 0)), 8, 2);
            h.assertTrue(named.equals(List.of(new MobPlan.TypeCount("minecraft:cow", 1, 0), new MobPlan.TypeCount("minecraft:pig", 3, 0))), "named or tamed ones never: the next kind gives the rest: " + named);

            int base = MobLimits.count(level, c);
            for (int i = 0; i < 12; i++) {
                Mob m = mob(h, EntityType.ENDERMITE, at);
                level.addFreshEntity(m);
                made.add(m);
            }
            for (int i = 0; i < 3; i++) {
                Mob m = mob(h, EntityType.SILVERFISH, at);
                if (i == 0) {
                    m.setCustomName(Component.literal("Silvy"));
                }
                level.addFreshEntity(m);
                made.add(m);
            }
            MobLimits.Row row = MobLimits.localFullest(server).stream().filter(r -> r.dim().equals(OVERWORLD) && r.x() == c.x && r.z() == c.z).findFirst().orElse(null);
            h.assertTrue(row != null && row.count() == base + 15, "the chunk is in the fullest list with its count: " + row);
            MobPlan.TypeCount mites = row.types().stream().filter(t -> t.type().equals("minecraft:endermite")).findFirst().orElseThrow();
            MobPlan.TypeCount fish = row.types().stream().filter(t -> t.type().equals("minecraft:silverfish")).findFirst().orElseThrow();
            h.assertTrue(mites.count() == 12 && fish.count() == 3 && fish.kept() == 1, "with what is in it, the named one marked kept: " + row.types());

            MobData.get(server).setSettings(limitHere(before, c, base + 5));
            AdminMobsEditPacket remove = AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.REMOVE, Storage.currentServerId(), OVERWORLD, c.x, c.z, base + 15);
            h.assertFalse(MobsAdmin.apply(player, remove), "a player who may not edit removes nothing");
            h.assertTrue(made.stream().allMatch(Entity::isAlive), "nothing gone");
            h.assertTrue(MobsAdmin.apply(admin, remove), "an admin's REMOVE EXTRAS is taken");
            long mitesLeft = made.stream().filter(m -> m.isAlive() && m.getType() == EntityType.ENDERMITE).count();
            long fishLeft = made.stream().filter(m -> m.isAlive() && m.getType() == EntityType.SILVERFISH).count();
            h.assertTrue(mitesLeft == 2 && fishLeft == 3 && made.get(12).isAlive(), "10 endermites (the most numerous) go, the silverfish and the named one stay (D485): " + mitesLeft + ", " + fishLeft);
            h.assertTrue(MobLimits.count(level, c) == base + 5, "the chunk is at its limit");
            String name = admin.getGameProfile().getName();
            h.assertTrue(ActionLog.recent(server, 20, false, List.of(LogEntry.MOB)).stream().anyMatch(e -> e.text().startsWith(name + " removed 10 mobs from ") && e.text().endsWith("): 10 endermite")),
                    "written to the Action Log, kind mob (D475)");
            h.assertFalse(LogEntry.PUBLIC_KINDS.contains(LogEntry.MOB), "for staff only");
            h.assertTrue(MobLimits.removeExtras(admin, OVERWORLD, c.x, c.z) == 0, "at the limit there is nothing more to remove");
        } finally {
            for (Mob m : made) {
                m.discard();
            }
            MobData.get(server).setSettings(before);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void here_and_limit_add_a_single_chunk_at_its_current_number(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos at = spot(h);
        ChunkPos c = new ChunkPos(at);
        MobSettings before = MobData.get(server).settings();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        String here = Storage.currentServerId();
        try {
            MobData.get(server).setSettings(MobSettings.DEFAULTS);
            admin.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            int base = MobLimits.count(level, c);
            MobsAdmin.apply(admin, AdminMobsEditPacket.of(AdminMobsEditPacket.Action.HERE));
            MobSettings.ChunkLimit added = MobLimits.settings(server).chunks().stream().filter(x -> x.is(here, OVERWORLD, c.x, c.z)).findFirst().orElse(null);
            h.assertTrue(added != null && added.limit() == Math.max(1, base), "HERE adds the chunk the admin stands in; with no number set, at what it holds (D481): " + added);
            MobsAdmin.apply(admin, AdminMobsEditPacket.of(AdminMobsEditPacket.Action.HERE));
            h.assertTrue(MobLimits.settings(server).chunks().size() == 1, "twice adds it once");

            MobData.get(server).setSettings(MobSettings.DEFAULTS.withDim("compactmachines:compact_world", 25).sanitised());
            MobsAdmin.apply(admin, AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.LIMIT, "mobtest-beta", "compactmachines:compact_world", 7, 9, 96));
            h.assertTrue(MobLimits.settings(server).chunks().equals(List.of(new MobSettings.ChunkLimit("mobtest-beta", "compactmachines:compact_world", 7, 9, 25))),
                    "LIMIT adds a listed chunk on its own server at the number that applies to it: " + MobLimits.settings(server).chunks());
        } finally {
            MobData.get(server).setSettings(before);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_network_list_dims_old_servers_and_drops_them_and_the_tab_s_data_round_trips(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong now = new AtomicLong(20_000_000_000L);
        MobLimits.useForTests(now::get);
        try {
            MobLimits.Row big = new MobLimits.Row("mobtest-beta", "compactmachines:compact_world", 7, 9, 960, 25, MobSettings.From.DIMENSION, "Steve_42",
                    List.of(new MobPlan.TypeCount("minecraft:zombie", 900, 0), new MobPlan.TypeCount("minecraft:skeleton", 60, 2)));
            MobLimits.applyFullest(MobLimits.encode("mobtest-beta", List.of("minecraft:overworld", "mobtest:far_lands"), List.of(big)));
            List<MobLimits.Seen> seen = MobLimits.networkFullest(server);
            h.assertTrue(!seen.isEmpty() && seen.get(0).row().equals(big) && !seen.get(0).stale(), "another server's chunk, decoded whole, worst first: " + seen);
            h.assertTrue(MobLimits.networkDims(server).contains("mobtest:far_lands"), "its dimensions are offered by ADD (D483)");
            now.addAndGet(MobLimits.DIM_MS + 1);
            h.assertTrue(MobLimits.networkFullest(server).get(0).stale(), "not heard from for 30 s: dimmed (D486)");
            now.addAndGet(MobLimits.DROP_MS);
            h.assertTrue(MobLimits.networkFullest(server).stream().noneMatch(s -> s.row().server().equals("mobtest-beta")) && !MobLimits.networkDims(server).contains("mobtest:far_lands"),
                    "after 2 minutes: left out");

            AdminMobsPacket tab = new AdminMobsPacket(MobSettings.DEFAULTS.withEvery(40).withDim("minecraft:the_nether", 60).sanitised(), "s".repeat(80),
                    List.of(new AdminMobsPacket.Row("mobtest-beta", "compactmachines:compact_world", 7, 9, 960, 25, MobSettings.From.DIMENSION, "Steve_42", big.types(), 31_000)),
                    List.of("minecraft:overworld"), 71, List.of(new AdminMobsPacket.DropRow("mobtest-beta", "minecraft:overworld", 120, -45, 1204, 3, 41, 204_000, 0, "", 2_000)),
                    3, 2914, 311, 948);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            tab.encode(buf);
            AdminMobsPacket back = AdminMobsPacket.decode(buf);
            h.assertTrue(back.settings().equals(tab.settings()) && back.rows().equals(tab.rows()) && back.dims().equals(tab.dims()) && back.removed() == 71 && back.here().length() == 64
                            && back.drops().equals(tab.drops()) && back.clears() == 3 && back.clearedItems() == 2914 && back.clearedArrows() == 311 && back.cleared() == 948,
                    "the tab's data round trips, a long server name cut");
            AdminMobsEditPacket edit = AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.LIMIT, "mobtest-beta", "minecraft:overworld", -3, 4, 12);
            buf = new FriendlyByteBuf(Unpooled.buffer());
            edit.encode(buf);
            h.assertTrue(AdminMobsEditPacket.decode(buf).equals(edit), "and an edit");
        } finally {
            MobLimits.useReal();
        }
        h.succeed();
    }
}
