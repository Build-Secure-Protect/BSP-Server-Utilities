package com.mrgregles.bsp_server_utilities.despawn;

import com.mrgregles.bsp_server_utilities.mixin.ExperienceOrbAccess;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Despawn times for dropped items and experience orbs (owner decisions D101 to D107, D109, D114, D117, D118), on the
 * server thread, made not to cost: nothing counts per item.
 *
 * <ul>
 *   <li>When an item or orb enters a level it is stamped once with the server's running time (the overworld's game
 *   time, which advances while the server runs whether or not anyone is near) in its persistent data, and kept in a
 *   list of dropped things; it leaves the list when it leaves the level.</li>
 *   <li>Every {@value #CHECK_TICKS} ticks the list is walked: one comparison each, and those past their time go, in
 *   chunks that tick or not. Items whose time is longer than vanilla's are given a long enough lifespan, and orbs have
 *   their age set back, so vanilla never takes them first.</li>
 *   <li>An item loaded from disk that ran out while its chunk was unloaded, or was on the ground before the last clear,
 *   never enters the level.</li>
 *   <li>Times: items, a player's death drops, orbs as items, per-item exceptions (a time or never). Items whose mod
 *   gives them a lifespan of its own, and BSP-Core's items, are never touched (D106).</li>
 * </ul>
 */
public final class DespawnService {
    public static final String KIND_SETTINGS = "despawn_settings";
    public static final String STAMP = "bsp_su_dropped", DEATH = "bsp_su_death";
    public static final int CHECK_TICKS = 100, VANILLA_TICKS = 6000;
    private static final long TICKS_PER_MINUTE = 1200L;

    private static final Set<Entity> DROPPED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    /** Removals per minute of running time, the newest last: {minute, items, orbs}. */
    private static final Deque<long[]> REMOVED = new ArrayDeque<>();
    private static long ticks;
    @Nullable
    private static LongSupplier clockOverride;

    private DespawnService() {
    }

    public static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    private static void changed() {
        LISTENERS.forEach(Runnable::run);
    }

    /** The server's running time in ticks. */
    public static long now(MinecraftServer server) {
        return clockOverride != null ? clockOverride.getAsLong() : server.overworld().getGameTime();
    }

    // ------------------------------------------------------------------ settings

    public static DespawnSettings settings(MinecraftServer server) {
        return DespawnData.get(server).settings();
    }

    public static DespawnSettings setSettings(MinecraftServer server, DespawnSettings s) {
        com.mrgregles.bsp_server_utilities.setup.SetupService.touched(server, com.mrgregles.bsp_server_utilities.setup.Feature.DESPAWN); // changed in its tab: set up (D152)
        DespawnData data = DespawnData.get(server);
        DespawnSettings next = s.withRevision(Math.max(System.currentTimeMillis(), data.settings().revision() + 1)).sanitised();
        data.setSettings(next);
        Storage st = Storage.active();
        if (st != null) {
            st.submit(() -> st.backend().saveDespawnSettings(next));
            st.publish(KIND_SETTINGS, next.encode());
        }
        relife(server);
        changed();
        return next;
    }

    public static void apply(MinecraftServer server, DespawnSettings shared) {
        DespawnData data = DespawnData.get(server);
        if (shared.revision() > data.settings().revision()) {
            data.setSettings(shared);
            relife(server);
            changed();
        }
    }

    // ------------------------------------------------------------------ which time applies

    /** Whether a mod gives the item a lifespan of its own, or it is BSP-Core's: never touched (D106). */
    public static boolean modTimed(ItemEntity item) {
        ItemStack stack = item.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return "bsp_core".equals(id.getNamespace()) || stack.getEntityLifespan(item.level()) != VANILLA_TICKS;
    }

    /** How many ticks of running time the entity may lie there; -1 for never or not ours to time. */
    public static long limitTicks(Entity e, DespawnSettings s) {
        if (e instanceof ExperienceOrb) {
            return s.orbsFollow() ? s.itemMinutes() * TICKS_PER_MINUTE : -1;
        }
        if (!(e instanceof ItemEntity item) || modTimed(item)) {
            return -1;
        }
        DespawnSettings.ItemTime ex = s.exception(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
        if (ex != null) {
            return ex.minutes() == DespawnSettings.NEVER ? -1 : ex.minutes() * TICKS_PER_MINUTE;
        }
        return (item.getPersistentData().getBoolean(DEATH) ? s.deathMinutes() : s.itemMinutes()) * TICKS_PER_MINUTE;
    }

    /** Whether the clear keeps it: mod-timed items, BSP-Core's and never exceptions (D112, D118). */
    public static boolean clearKeeps(Entity e, DespawnSettings s) {
        if (e instanceof ItemEntity item) {
            if (modTimed(item)) {
                return true;
            }
            DespawnSettings.ItemTime ex = s.exception(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
            return ex != null && ex.minutes() == DespawnSettings.NEVER;
        }
        return !(e instanceof ExperienceOrb);
    }

    /** Vanilla's own clock for ticking items set to agree: a little longer than ours, so ours decides; never is never. */
    private static void lifespan(Entity e, DespawnSettings s) {
        if (e instanceof ItemEntity item && !modTimed(item)) {
            long limit = limitTicks(e, s);
            if (com.mrgregles.bsp_server_utilities.setup.Switches.off(com.mrgregles.bsp_server_utilities.setup.Feature.DESPAWN)) {
                item.lifespan = VANILLA_TICKS; // turned off: vanilla's five minutes (D150)
            } else if (!s.enabled()) {
                item.lifespan = limit < 0 ? Integer.MAX_VALUE : (int) limit;
            } else {
                item.lifespan = limit < 0 ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, limit + TICKS_PER_MINUTE);
            }
        }
    }

    /** Despawn times turned off or on in the setup guide: the items here go back to vanilla's clock, or to ours. */
    public static void switched(MinecraftServer server) {
        relife(server);
    }

    private static void relife(MinecraftServer server) {
        DespawnSettings s = settings(server);
        for (Entity e : List.copyOf(DROPPED)) {
            lifespan(e, s);
        }
    }

    // ------------------------------------------------------------------ the list and the check

    private static boolean ours(Entity e) {
        return e instanceof ItemEntity || e instanceof ExperienceOrb;
    }

    /**
     * An item or orb entering a level: stamped once (a stamp it already has is kept), timed, and listed; one loaded from
     * disk that ran out while away or was on the ground before the last clear is turned away. Returns false to turn it away.
     */
    public static boolean onJoin(Entity e, boolean fromDisk) {
        if (!ours(e) || !(e.level() instanceof ServerLevel level) || com.mrgregles.bsp_server_utilities.setup.Switches.off(com.mrgregles.bsp_server_utilities.setup.Feature.DESPAWN)) {
            return true;
        }
        MinecraftServer server = level.getServer();
        DespawnSettings s = settings(server);
        CompoundTag data = e.getPersistentData();
        long now = now(server);
        boolean stamped = data.contains(STAMP);
        if (fromDisk) {
            long cleared = DespawnData.get(server).clearedBefore();
            boolean beforeClear = cleared > 0 && (!stamped || data.getLong(STAMP) < cleared);
            if (beforeClear && !clearKeeps(e, s)) {
                return false;
            }
            long limit = limitTicks(e, s);
            if (s.enabled() && stamped && limit >= 0 && now - data.getLong(STAMP) >= limit) {
                count(server, e);
                return false;
            }
        }
        if (!stamped) {
            data.putLong(STAMP, now);
        }
        lifespan(e, s);
        DROPPED.add(e);
        return true;
    }

    public static void onLeave(Entity e) {
        DROPPED.remove(e);
    }

    /** The check: one comparison per dropped thing; returns how many went. */
    public static int check(MinecraftServer server) {
        DespawnSettings s = settings(server);
        if (!s.enabled() || com.mrgregles.bsp_server_utilities.setup.Switches.off(com.mrgregles.bsp_server_utilities.setup.Feature.DESPAWN)) {
            return 0;
        }
        long now = now(server);
        int gone = 0;
        for (Entity e : List.copyOf(DROPPED)) {
            if (e.isRemoved()) {
                DROPPED.remove(e);
                continue;
            }
            long limit = limitTicks(e, s);
            if (limit < 0) {
                continue;
            }
            long age = now - e.getPersistentData().getLong(STAMP);
            if (age >= limit) {
                count(server, e);
                e.discard();
                DROPPED.remove(e);
                gone++;
            } else if (e instanceof ExperienceOrb orb && limit > VANILLA_TICKS) {
                ((ExperienceOrbAccess) orb).bspSu$setAge(0); // vanilla's 5 minutes must not come first
            }
        }
        return gone;
    }

    /** Everything dropped that is loaded now (the clear walks it). */
    public static List<Entity> dropped() {
        List<Entity> out = new ArrayList<>();
        for (Entity e : DROPPED) {
            if (!e.isRemoved()) {
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ what admins see (D109)

    static void count(MinecraftServer server, Entity e) {
        long minute = now(server) / TICKS_PER_MINUTE;
        long[] last = REMOVED.peekLast();
        if (last == null || last[0] != minute) {
            last = new long[]{minute, 0, 0};
            REMOVED.addLast(last);
        }
        last[e instanceof ExperienceOrb ? 2 : 1]++;
        while (REMOVED.size() > 1 && REMOVED.peekFirst()[0] < minute - 60) {
            REMOVED.pollFirst();
        }
    }

    /** Items and orbs removed in the last hour of running time: {items, orbs}. */
    public static long[] removedLastHour(MinecraftServer server) {
        long minute = now(server) / TICKS_PER_MINUTE;
        long[] out = new long[2];
        for (long[] r : REMOVED) {
            if (r[0] >= minute - 60) {
                out[0] += r[1];
                out[1] += r[2];
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void registerEvents() {
        MinecraftForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (!e.getLevel().isClientSide() && !onJoin(e.getEntity(), e.loadedFromDisk())) {
                e.setCanceled(true);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((EntityLeaveLevelEvent e) -> {
            if (!e.getLevel().isClientSide()) {
                onLeave(e.getEntity());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((LivingDropsEvent e) -> {
            if (e.getEntity() instanceof Player) {
                for (ItemEntity item : e.getDrops()) {
                    item.getPersistentData().putBoolean(DEATH, true);
                }
            }
        });
    }

    public static void registerNotices() {
        Storage.on(KIND_SETTINGS, (srv, n) -> {
            DespawnSettings s = DespawnSettings.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
    }

    public static void onServerStarted(MinecraftServer server) {
        REMOVED.clear();
        ticks = 0;
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                DespawnSettings shared = s.backend().loadDespawnSettings();
                if (shared == null) {
                    s.backend().saveDespawnSettings(settings(server));
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    public static void onServerStopped() {
        DROPPED.clear();
    }

    public static void tick(MinecraftServer server) {
        if (++ticks % CHECK_TICKS == 0) {
            check(server);
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(@Nullable LongSupplier clock) {
        clockOverride = clock;
    }

    /** Lists an entity that is not in a level (the timing test). */
    public static void trackForTests(Entity e) {
        DROPPED.add(e);
    }

    public static void forgetForTests(Entity e) {
        DROPPED.remove(e);
    }
}
