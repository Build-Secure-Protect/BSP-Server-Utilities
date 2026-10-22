package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.despawn.ClearService;
import com.mrgregles.bsp_server_utilities.despawn.DespawnData;
import com.mrgregles.bsp_server_utilities.despawn.DespawnService;
import com.mrgregles.bsp_server_utilities.despawn.DespawnSettings;
import com.mrgregles.bsp_server_utilities.despawn.DropsAdmin;
import com.mrgregles.bsp_server_utilities.network.AdminDropsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminDropsPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Despawn times and the clear (owner decisions D101 to D118): items, death drops, orbs and exceptions removed by the
 * server's running time whether or not they tick; a mod's own lifespan and never exceptions kept; items loaded from disk
 * that ran out, or lay there before a clear, turned away; the clear with its counts; and the check timed over thousands
 * of items (the owner's lag condition, D101).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class DespawnGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long MIN = 1200L;

    private static ItemEntity drop(GameTestHelper h, Item item, int x, boolean death) {
        BlockPos p = h.absolutePos(new BlockPos(x, 2, 3));
        ItemEntity e = new ItemEntity(h.getLevel(), p.getX() + 0.5, p.getY(), p.getZ() + 0.5, new ItemStack(item));
        e.setDeltaMovement(0, 0, 0);
        if (death) {
            e.getPersistentData().putBoolean(DespawnService.DEATH, true);
        }
        h.getLevel().addFreshEntity(e);
        return e;
    }

    private static DespawnSettings settings(List<DespawnSettings.ItemTime> exceptions) {
        return new DespawnSettings(true, 5, 15, true, exceptions, 0).sanitised();
    }

    @GameTest(template = EMPTY)
    public void items_orbs_and_death_drops_go_by_running_time_and_exceptions_hold(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong now = new AtomicLong(1_000_000L);
        DespawnSettings before = DespawnData.get(server).settings();
        DespawnService.useForTests(now::get);
        List<Entity> made = new ArrayList<>();
        try {
            DespawnData.get(server).setSettings(settings(List.of(new DespawnSettings.ItemTime("minecraft:dirt", DespawnSettings.NEVER),
                    new DespawnSettings.ItemTime("minecraft:sand", 1))).withRevision(before.revision()));
            ItemEntity cobble = drop(h, Items.COBBLESTONE, 1, false);
            ItemEntity dirt = drop(h, Items.DIRT, 3, false);
            ItemEntity sand = drop(h, Items.SAND, 5, false);
            ItemEntity death = drop(h, Items.STONE, 7, true);
            BlockPos op = h.absolutePos(new BlockPos(9, 2, 3));
            ExperienceOrb orb = new ExperienceOrb(h.getLevel(), op.getX() + 0.5, op.getY(), op.getZ() + 0.5, 5);
            h.getLevel().addFreshEntity(orb);
            made.addAll(List.of(cobble, dirt, sand, death, orb));
            h.assertTrue(cobble.getPersistentData().getLong(DespawnService.STAMP) == 1_000_000L, "stamped once with the running time");
            h.assertTrue(cobble.lifespan == 5 * MIN + MIN && dirt.lifespan == Integer.MAX_VALUE, "vanilla's clock set a little longer than ours, never for never");

            now.addAndGet(MIN);
            DespawnService.check(server);
            h.assertTrue(sand.isRemoved() && !cobble.isRemoved(), "an exception of 1 minute goes at 1 minute (D105)");
            now.addAndGet(4 * MIN - 1);
            DespawnService.check(server);
            h.assertTrue(!cobble.isRemoved() && !orb.isRemoved(), "a tick short of 5 minutes: still there");
            now.addAndGet(1);
            DespawnService.check(server);
            h.assertTrue(cobble.isRemoved() && orb.isRemoved(), "at 5 minutes the item and the orb go (D102, D104)");
            h.assertTrue(!death.isRemoved() && !dirt.isRemoved(), "a death drop and a never item stay");
            now.addAndGet(10 * MIN);
            DespawnService.check(server);
            h.assertTrue(death.isRemoved() && !dirt.isRemoved(), "the death drop goes at 15 minutes (D103); never is never");
            long[] gone = DespawnService.removedLastHour(server);
            h.assertTrue(gone[0] >= 3 && gone[1] >= 1, "counted for /su status (D109): " + gone[0] + " items, " + gone[1] + " orbs");

            Item modTimed = BuiltInRegistries.ITEM.stream().filter(i -> BuiltInRegistries.ITEM.getKey(i).getNamespace().equals("bsp_core")).findFirst().orElse(null);
            if (modTimed != null) {
                // not dropped into the level: BSP-Core handles some of its own dropped items itself (the totem)
                ItemEntity core = new ItemEntity(h.getLevel(), 0, 0, 0, new ItemStack(modTimed));
                DespawnSettings s = DespawnService.settings(server);
                h.assertTrue(DespawnService.modTimed(core) && DespawnService.limitTicks(core, s) < 0 && DespawnService.clearKeeps(core, s),
                        "BSP-Core's items are never timed or cleared (D106): " + BuiltInRegistries.ITEM.getKey(modTimed));
            }
        } finally {
            made.forEach(Entity::discard);
            DespawnData.get(server).setSettings(before);
            DespawnService.useForTests(null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void items_loaded_from_disk_that_ran_out_or_lay_before_a_clear_are_turned_away(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        AtomicLong now = new AtomicLong(5_000_000L);
        DespawnSettings before = DespawnData.get(server).settings();
        long clearedBefore = DespawnData.get(server).clearedBefore();
        DespawnService.useForTests(now::get);
        try {
            DespawnData.get(server).setSettings(settings(List.of(new DespawnSettings.ItemTime("minecraft:dirt", DespawnSettings.NEVER))).withRevision(before.revision()));
            ItemEntity old = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.COBBLESTONE));
            old.getPersistentData().putLong(DespawnService.STAMP, now.get() - 6 * MIN);
            h.assertFalse(DespawnService.onJoin(old, true), "it ran out while its chunk was unloaded: it never comes back");
            ItemEntity fresh = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.COBBLESTONE));
            fresh.getPersistentData().putLong(DespawnService.STAMP, now.get() - 2 * MIN);
            h.assertTrue(DespawnService.onJoin(fresh, true), "two minutes old: it comes back with its stamp");
            DespawnService.onLeave(fresh);

            DespawnData.get(server).setClearedBefore(now.get());
            ItemEntity unstamped = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.COBBLESTONE));
            h.assertFalse(DespawnService.onJoin(unstamped, true), "on the ground before the clear (no stamp: older than this mod): turned away (D114)");
            ItemEntity never = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.DIRT));
            never.getPersistentData().putLong(DespawnService.STAMP, now.get() - 1);
            h.assertTrue(DespawnService.onJoin(never, true), "a never item survives the clear (D118)");
            DespawnService.onLeave(never);
            ItemEntity after = new ItemEntity(level, 0, 0, 0, new ItemStack(Items.COBBLESTONE));
            h.assertTrue(DespawnService.onJoin(after, false), "dropped after the clear: kept");
            DespawnService.onLeave(after);
        } finally {
            DespawnData.get(server).setSettings(before);
            DespawnData.get(server).setClearedBefore(clearedBefore);
            DespawnService.useForTests(null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_clear_warns_then_takes_items_and_orbs_and_reports_counts(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        DespawnSettings before = DespawnData.get(server).settings();
        long clearedBefore = DespawnData.get(server).clearedBefore();
        List<Component> reports = new ArrayList<>();
        ClearService.useForTests(reports::add);
        List<Entity> made = new ArrayList<>();
        try {
            DespawnData.get(server).setSettings(settings(List.of(new DespawnSettings.ItemTime("minecraft:dirt", DespawnSettings.NEVER))).withRevision(before.revision()));
            ItemEntity cobble = drop(h, Items.COBBLESTONE, 2, false);
            ItemEntity dirt = drop(h, Items.DIRT, 4, false);
            BlockPos op = h.absolutePos(new BlockPos(6, 2, 3));
            ExperienceOrb orb = new ExperienceOrb(h.getLevel(), op.getX() + 0.5, op.getY(), op.getZ() + 0.5, 3);
            h.getLevel().addFreshEntity(orb);
            made.addAll(List.of(cobble, dirt, orb));

            h.assertTrue(ClearService.start(server, "here", null).equals(Storage.currentServerId()) && ClearService.counting(), "a clear of this server starts its countdown");
            for (int i = 0; i < ClearService.COUNTDOWN_TICKS - ClearService.SECOND_WARNING_TICKS; i++) {
                ClearService.tick(server);
            }
            h.assertTrue(ClearService.counting() && !cobble.isRemoved(), "30 seconds left: warned twice, nothing cleared yet (D113)");
            ClearService.start(server, "here", null);
            for (int i = 0; i < ClearService.COUNTDOWN_TICKS - 1; i++) {
                ClearService.tick(server);
            }
            h.assertTrue(ClearService.counting() && !cobble.isRemoved(), "a second clear replaced the first and started again (D116)");
            ClearService.tick(server);
            h.assertTrue(!ClearService.counting() && cobble.isRemoved() && orb.isRemoved() && !dirt.isRemoved(), "cleared: the item and the orb, not the never item (D112, D118)");
            h.assertTrue(reports.size() == 1 && reports.get(0).getString().contains(Storage.currentServerId()), "the admin gets this server's count: " + reports);
            h.assertTrue(DespawnData.get(server).clearedBefore() > 0, "the time is kept for chunks not loaded (D114)");
            int[] again = ClearService.clearNow(server, UUID.randomUUID().toString(), Storage.currentServerId(), "");
            h.assertTrue(again[0] == 0 && again[1] == 0, "nothing left to clear but the never item");
        } finally {
            made.forEach(Entity::discard);
            ClearService.useForTests(null);
            DespawnData.get(server).setSettings(before);
            DespawnData.get(server).setClearedBefore(clearedBefore);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_check_over_five_thousand_items_takes_a_few_milliseconds(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        AtomicLong now = new AtomicLong(9_000_000L);
        DespawnService.useForTests(now::get);
        List<ItemEntity> many = new ArrayList<>();
        try {
            for (int i = 0; i < 5000; i++) {
                ItemEntity e = new ItemEntity(h.getLevel(), 0, 0, 0, new ItemStack(i % 2 == 0 ? Items.COBBLESTONE : Items.ROTTEN_FLESH));
                e.getPersistentData().putLong(DespawnService.STAMP, now.get());
                DespawnService.trackForTests(e);
                many.add(e);
            }
            DespawnService.check(server); // warm up
            long best = Long.MAX_VALUE;
            for (int run = 0; run < 5; run++) {
                long t0 = System.nanoTime();
                DespawnService.check(server);
                best = Math.min(best, System.nanoTime() - t0);
            }
            double ms = best / 1_000_000.0;
            BSPServerUtilities.LOGGER.info("Despawn check over 5000 dropped items: {} ms", String.format(java.util.Locale.ROOT, "%.2f", ms));
            h.assertTrue(ms < 25, "a check over 5000 items, run every 5 seconds, takes " + ms + " ms (vanilla spends more than that on them every tick)");
        } finally {
            many.forEach(DespawnService::forgetForTests);
            DespawnService.useForTests(null);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void despawn_settings_keep_to_their_limits(GameTestHelper h) {
        DespawnSettings odd = new DespawnSettings(true, 0, 999, false, List.of(new DespawnSettings.ItemTime("minecraft:dirt", -3),
                new DespawnSettings.ItemTime("Minecraft:Sand", 500), new DespawnSettings.ItemTime("not an id", 5), new DespawnSettings.ItemTime("minecraft:dirt", 9)), 4).sanitised();
        h.assertTrue(odd.itemMinutes() == 1 && odd.deathMinutes() == 60, "minutes 1 to 60 (D102, D103)");
        h.assertTrue(odd.exceptions().equals(List.of(new DespawnSettings.ItemTime("minecraft:dirt", 0), new DespawnSettings.ItemTime("minecraft:sand", 120))),
                "exceptions: never, at most 120 minutes (D117), ids well formed, one each: " + odd.exceptions());
        h.assertTrue(DespawnSettings.decode(odd.encode()).equals(odd) && DespawnSettings.load(odd.save()).equals(odd), "round trip");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_drops_tab_sets_the_times_and_starts_a_clear_for_admins_only(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        DespawnSettings before = DespawnData.get(server).settings();
        try {
            AdminDropsPacket tab = DropsAdmin.packet(server);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            tab.encode(buf);
            h.assertTrue(AdminDropsPacket.decode(buf).equals(tab) && tab.here().equals(Storage.currentServerId()), "the tab's data round trips");
            AdminDropsEditPacket edit = new AdminDropsEditPacket(AdminDropsEditPacket.Action.SETTINGS, "",
                    new DespawnSettings(true, 8, 20, false, List.of(new DespawnSettings.ItemTime("minecraft:elytra", DespawnSettings.NEVER)), 0));
            buf = new FriendlyByteBuf(Unpooled.buffer());
            edit.encode(buf);
            h.assertTrue(AdminDropsEditPacket.decode(buf).equals(edit), "an edit round trips");
            h.assertFalse(DropsAdmin.apply(player, edit), "a player who may not edit changes nothing");
            h.assertTrue(DespawnService.settings(server).equals(before), "unchanged");
            h.assertTrue(DropsAdmin.apply(admin, edit), "an admin's edit is taken");
            DespawnSettings now = DespawnService.settings(server);
            h.assertTrue(now.itemMinutes() == 8 && now.deathMinutes() == 20 && !now.orbsFollow() && now.exception("minecraft:elytra") != null && now.revision() > before.revision(),
                    "and is the network's: " + now);
            h.assertFalse(DropsAdmin.apply(player, AdminDropsEditPacket.of(AdminDropsEditPacket.Action.CLEAR, "here")) || ClearService.counting(), "a player cannot clear");
            DropsAdmin.apply(admin, AdminDropsEditPacket.of(AdminDropsEditPacket.Action.CLEAR, "nowhere-" + UUID.randomUUID()));
            h.assertFalse(ClearService.counting(), "an unknown server starts nothing");
            DropsAdmin.apply(admin, AdminDropsEditPacket.of(AdminDropsEditPacket.Action.CLEAR, "here"));
            h.assertTrue(ClearService.counting() && DropsAdmin.packet(server).counting(), "CLEAR ITEMS for this server starts the countdown, and the tab shows it");
        } finally {
            ClearService.cancelForTests();
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            DespawnData.get(server).setSettings(before);
        }
        h.succeed();
    }
}
