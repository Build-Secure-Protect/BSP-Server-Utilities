package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.despawn.DespawnService;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.mobs.Crowding;
import com.mrgregles.bsp_server_utilities.mobs.MobData;
import com.mrgregles.bsp_server_utilities.mobs.MobSettings;
import com.mrgregles.bsp_server_utilities.mobs.MobsAdmin;
import com.mrgregles.bsp_server_utilities.network.AdminMobsEditPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Crowded drops and arrows (owner decisions D487 to D501): the numbers on from the start and round tripping; a chunk over
 * its numbers for the minutes cleared down to them, oldest first, death drops and tridents kept, the time starting again
 * under the numbers, the Action Log; CLEAR NOW for admins on this server; the list and its sharing. Each test clears its
 * own chunk of items and arrows first (left by earlier tests; every test body runs on its own).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class CrowdGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY, OVERWORLD = "minecraft:overworld";
    private static final long MIN = 60_000L;

    private static BlockPos spot(GameTestHelper h) {
        return h.absolutePos(new BlockPos(1, 2, 1));
    }

    private static AABB chunkBox(ServerLevel level, ChunkPos c) {
        return new AABB(c.getMinBlockX(), level.getMinBuildHeight(), c.getMinBlockZ(), c.getMaxBlockX() + 1, level.getMaxBuildHeight(), c.getMaxBlockZ() + 1);
    }

    private static void tidy(ServerLevel level, ChunkPos c) {
        for (Entity e : level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity || e instanceof AbstractArrow)) {
            e.discard();
        }
    }

    private static ItemEntity item(GameTestHelper h, BlockPos at, long stamp) {
        ItemEntity i = new ItemEntity(h.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, new ItemStack(Items.COBBLESTONE));
        i.setNoPickUpDelay();
        h.getLevel().addFreshEntity(i);
        i.getPersistentData().putLong(DespawnService.STAMP, stamp); // when it was dropped: lower is older
        return i;
    }

    private static Arrow arrow(GameTestHelper h, BlockPos at, int age) {
        Arrow a = new Arrow(h.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        h.getLevel().addFreshEntity(a);
        a.tickCount = age; // older is higher
        return a;
    }

    private static MobSettings with(MobSettings s, int items, int arrows, int minutes) {
        return s.withDrops(new MobSettings.Drops(items, arrows, minutes)).sanitised();
    }

    @GameTest(template = EMPTY)
    public void drop_numbers_start_on_keep_to_their_limits_and_round_trip(GameTestHelper h) {
        h.assertTrue(MobSettings.DEFAULTS.drops().equals(new MobSettings.Drops(256, 128, 5)), "on from the start: 256 items, 128 arrows, 5 minutes (D488, D489)");
        MobSettings odd = with(MobSettings.DEFAULTS, -4, 99_999, 0);
        h.assertTrue(odd.drops().equals(new MobSettings.Drops(MobSettings.OFF, MobSettings.Drops.MAX, MobSettings.Drops.MIN_MINUTES)), "below 0 is OFF (D497), numbers and minutes kept to their limits: " + odd.drops());
        MobSettings s = with(MobSettings.DEFAULTS.withEvery(40).withDim("minecraft:the_nether", 60).sanitised(), 300, MobSettings.OFF, 7);
        h.assertTrue(MobSettings.decode(s.encode()).equals(s) && MobSettings.load(s.save()).equals(s), "they round trip with the mob limits");
        h.assertTrue(s.withEvery(10).drops().equals(s.drops()) && s.withDim("minecraft:overworld", 3).drops().equals(s.drops()), "changing a mob limit keeps them");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_chunk_over_its_numbers_for_the_minutes_is_cleared_down_to_them_oldest_first(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos at = spot(h);
        ChunkPos c = new ChunkPos(at);
        MobSettings before = MobData.get(server).settings();
        AtomicLong now = new AtomicLong(30_000_000_000L);
        Crowding.useForTests(now::get);
        List<Entity> made = new ArrayList<>();
        try {
            tidy(level, c);
            Crowding.check(server); // forget any time over from before
            MobData.get(server).setSettings(with(before, 5, 3, 1));
            for (int i = 0; i < 8; i++) {
                made.add(item(h, at, 1000 + i));
            }
            ItemEntity death = item(h, at, 1); // the oldest of all, but a player's death drop
            death.getPersistentData().putBoolean(DespawnService.DEATH, true);
            made.add(death);
            for (int i = 0; i < 5; i++) {
                made.add(arrow(h, at, 100 * (i + 1)));
            }
            ThrownTrident trident = new ThrownTrident(level, new net.minecraft.world.entity.monster.Zombie(level), new ItemStack(Items.TRIDENT));
            trident.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            level.addFreshEntity(trident);
            made.add(trident);

            Crowding.check(server);
            h.assertTrue(made.stream().allMatch(Entity::isAlive), "over the numbers, nothing goes at once");
            now.addAndGet(MIN - 1000);
            Crowding.check(server);
            h.assertTrue(made.stream().allMatch(Entity::isAlive), "nor a second before the minute (D489)");
            now.addAndGet(1000);
            Crowding.check(server);
            long items = level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity && e.isAlive()).size();
            h.assertTrue(items == 5 && death.isAlive(), "9 items over 5 for a minute: down to 5, the death drop kept (D490, D491): " + items);
            h.assertTrue(!made.get(0).isAlive() && !made.get(1).isAlive() && !made.get(2).isAlive() && !made.get(3).isAlive() && made.get(4).isAlive() && made.get(7).isAlive(),
                    "the oldest four of the others went");
            List<Entity> arrows = made.subList(9, 14);
            h.assertTrue(!arrows.get(4).isAlive() && !arrows.get(3).isAlive() && arrows.get(0).isAlive() && arrows.get(2).isAlive() && trident.isAlive(),
                    "5 arrows over 3: the two oldest go, the trident is never counted (D487)");
            List<LogEntry> log = ActionLog.recent(server, 20, false, List.of(LogEntry.DROPS));
            h.assertTrue(log.stream().anyMatch(e -> e.text().startsWith("4 items cleared on ") && e.text().endsWith("(9 for 1 min, limit 5)") && e.actor().equals(Crowding.CHECKER))
                    && log.stream().anyMatch(e -> e.text().startsWith("2 arrows cleared on ")), "each clear is in the Action Log (D492): " + log);
            h.assertFalse(LogEntry.PUBLIC_KINDS.contains(LogEntry.DROPS), "for staff only");
            h.assertTrue(Crowding.lastHour()[1] >= 4 && Crowding.lastHour()[2] >= 2, "and counted for the last hour (D501)");

            // dipping under the number starts the time again (audit: tested for real)
            ItemEntity sixth = item(h, at, 5000);
            made.add(sixth);
            Crowding.check(server);
            now.addAndGet(50_000);
            Crowding.check(server);
            h.assertTrue(sixth.isAlive(), "over for 50 s: nothing yet");
            sixth.discard();
            Crowding.check(server); // under: the time is forgotten
            ItemEntity again = item(h, at, 5001);
            made.add(again);
            Crowding.check(server); // over again: the time starts now
            now.addAndGet(50_000);
            Crowding.check(server);
            h.assertTrue(again.isAlive() && level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity && e.isAlive()).size() == 6,
                    "100 s after it first went over, but only 50 s since it went over again: nothing goes (D489)");
            now.addAndGet(10_000);
            Crowding.check(server);
            h.assertTrue(level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity && e.isAlive()).size() == 5, "a minute after going over again, it is cleared");

            MobData.get(server).setSettings(with(before, MobSettings.OFF, 3, 1));
            for (int i = 0; i < 20; i++) {
                made.add(item(h, at, 6000 + i));
            }
            Crowding.check(server);
            now.addAndGet(2 * MIN);
            Crowding.check(server);
            h.assertTrue(level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity && e.isAlive()).size() == 25, "items at OFF are left alone (D497)");
        } finally {
            tidy(level, c);
            trident(level, c);
            Crowding.check(server);
            Crowding.useReal();
            MobData.get(server).setSettings(before);
        }
        h.succeed();
    }

    private static void trident(ServerLevel level, ChunkPos c) {
        for (Entity e : level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ThrownTrident)) {
            e.discard();
        }
    }

    @GameTest(template = EMPTY)
    public void clear_now_is_an_admin_s_on_this_server_and_the_list_shows_and_shares_crowded_chunks(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos at = spot(h);
        ChunkPos c = new ChunkPos(at);
        MobSettings before = MobData.get(server).settings();
        AtomicLong now = new AtomicLong(40_000_000_000L);
        Crowding.useForTests(now::get);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            tidy(level, c);
            Crowding.check(server);
            MobData.get(server).setSettings(with(before, 8, 64, 5));
            for (int i = 0; i < 12; i++) {
                item(h, at, 2000 + i);
            }
            Crowding.Row row = Crowding.localFullest(server).stream().filter(r -> r.dim().equals(OVERWORLD) && r.x() == c.x && r.z() == c.z).findFirst().orElse(null);
            h.assertTrue(row != null && row.items() == 12 && row.itemsOverMs() == 0, "a chunk with a quarter of a number or more is listed, not yet over: " + row);
            Crowding.check(server);
            now.addAndGet(90_000);
            row = Crowding.localFullest(server).stream().filter(r -> r.dim().equals(OVERWORLD) && r.x() == c.x && r.z() == c.z).findFirst().orElseThrow();
            h.assertTrue(row.itemsOverMs() == 90_000, "and once over, how long (D496): " + row.itemsOverMs());

            AdminMobsEditPacket clear = AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.CLEAR, Storage.currentServerId(), OVERWORLD, c.x, c.z, 0);
            h.assertFalse(MobsAdmin.apply(player, clear), "a player who may not edit clears nothing");
            h.assertTrue(level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity).size() == 12, "nothing gone");
            MobsAdmin.apply(admin, AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.CLEAR, "another-server", OVERWORLD, c.x, c.z, 0));
            h.assertTrue(level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity).size() == 12, "another server's chunk is cleared there, not here");
            h.assertTrue(MobsAdmin.apply(admin, clear), "an admin's CLEAR NOW is taken");
            h.assertTrue(level.getEntities((Entity) null, chunkBox(level, c), e -> e instanceof ItemEntity && e.isAlive()).size() == 8, "at once, down to the number (D499)");
            String name = admin.getGameProfile().getName();
            h.assertTrue(ActionLog.recent(server, 20, false, List.of(LogEntry.DROPS)).stream().anyMatch(e -> e.text().startsWith("4 items cleared on ") && e.text().endsWith("with CLEAR NOW") && e.actor().equals(name)),
                    "the log names the admin");

            Crowding.Row remote = new Crowding.Row("crowdtest-beta", "minecraft:the_nether", 14, 9, 2, 311, 0, 0, 60_000, "Rin");
            Crowding.applyFullest(Crowding.encode("crowdtest-beta", List.of(remote)));
            List<Crowding.Seen> seen = Crowding.networkFullest(server);
            h.assertTrue(!seen.isEmpty() && seen.get(0).row().equals(remote), "another server's crowded chunk comes first, decoded whole: " + seen);
            now.addAndGet(121_000);
            h.assertTrue(Crowding.networkFullest(server).stream().noneMatch(s -> s.row().server().equals("crowdtest-beta")), "and is left out after 2 minutes");
        } finally {
            tidy(level, c);
            Crowding.check(server);
            Crowding.useReal();
            MobData.get(server).setSettings(before);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void oldest_first_holds_for_items_never_stamped(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos at = spot(h);
        ChunkPos c = new ChunkPos(at);
        MobSettings before = MobData.get(server).settings();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            tidy(level, c);
            MobData.get(server).setSettings(with(before, 2, 64, 5));
            List<ItemEntity> made = new ArrayList<>();
            for (int age : new int[]{10, 2000, 500}) {
                ItemEntity i = item(h, at, 0);
                i.getPersistentData().remove(DespawnService.STAMP); // as with despawn times off
                net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
                i.saveWithoutId(t);
                t.putShort("Age", (short) age);
                i.load(t);
                made.add(i);
            }
            h.assertTrue(made.get(1).getAge() == 2000, "ages set: " + made.get(1).getAge());
            MobsAdmin.apply(admin, AdminMobsEditPacket.chunk(AdminMobsEditPacket.Action.CLEAR, Storage.currentServerId(), OVERWORLD, c.x, c.z, 0));
            h.assertTrue(!made.get(1).isAlive() && made.get(0).isAlive() && made.get(2).isAlive(), "the item lying longest goes first, stamped or not (audit)");
        } finally {
            tidy(level, c);
            MobData.get(server).setSettings(before);
            server.getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }
}
