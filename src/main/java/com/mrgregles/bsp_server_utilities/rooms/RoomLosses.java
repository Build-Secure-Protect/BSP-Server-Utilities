package com.mrgregles.bsp_server_utilities.rooms;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.CompactMachines;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.item.ItemExpireEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * A bound machine item fully destroyed (SU-56; owner decisions D444, D446, D451). Only the losses the server can be sure
 * of: a dropped machine item burnt, blown up, on a cactus, crushed or otherwise destroyed ({@code ItemEntity.hurt}, a
 * mixin), fallen into the void (a mixin), despawned (this mod's despawn timer and clear, and vanilla's own expiry when
 * nothing kept it). A machine in an inventory, a block broken with no drop, {@code /clear} or {@code /kill}: nothing.
 *
 * <p>The room is on the server named in the item's {@value #HOME_TAG} (BSP-InventorySync's mark): that server acts; any
 * other sends it the loss (notice {@value #KIND_LOST}). An unmarked machine counts as this server's only where
 * BSP-InventorySync is not running; with it, an unmarked machine never frees anything, since its room could be another
 * server's. The home server then puts the room into grace unless a placed machine is still bound to it.
 */
public final class RoomLosses {
    public static final String KIND_LOST = "cm_lost";
    public static final String INVENTORY_SYNC = "bsp_inventorysync";
    /** BSP-InventorySync's home-server mark on a bound machine item: the server id (D31 there). */
    public static final String HOME_TAG = "bsp_inventorysync:home";
    static final long DAY_MS = 86_400_000L;
    /** The reasons a room goes into grace (the lang file has each; the table's column holds 32 characters). */
    public static final java.util.Set<String> REASONS = java.util.Set.of("burnt", "exploded", "cactus", "crushed", "destroyed", "despawned", "cleared", "void", "inside",
            "given_back", "freed");
    private static LongSupplier clock = System::currentTimeMillis;
    /** Whether the warning that BSP-InventorySync is installed but not syncing has been logged since the start. */
    private static boolean warnedNoSync;

    /** Where a lost machine's room is acted on. */
    public enum Route {
        HERE, FORWARD, IGNORE
    }

    private RoomLosses() {
    }

    public static void registerEvents() {
        // vanilla's expiry: at the lowest priority, so a mod that keeps the item (cancelling) has had its say
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ItemExpireEvent e) -> itemGone(e.getEntity(), "despawned"));
        // a machine placed: a room in grace may have its way in back (checked at the next tick, after Compact Machines binds it)
        MinecraftForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (!e.getLevel().isClientSide() && CompactMachines.isMachine(e.getPlacedBlock()) && e.getLevel() instanceof ServerLevel level) {
                if (refusesRevive(level, e.getPos(), e.getEntity())) {
                    e.setCanceled(true);
                    return;
                }
                RoomList.changed();
            }
        });
    }

    public static void registerNotices() {
        Storage.on(KIND_LOST, (server, n) -> {
            String[] p = n.payload().split("\n");
            if (p.length < 4 || !p[0].equalsIgnoreCase(Storage.currentServerId())) {
                return;
            }
            try {
                lose(server, new ChunkPos(Integer.parseInt(p[1]), Integer.parseInt(p[2])), p[3]);
            } catch (NumberFormatException ignored) {
                // not ours
            }
        });
    }

    // ------------------------------------------------------------------ the rules

    /** Where a lost machine marked {@code home} (null: unmarked) is acted on (D451). */
    public static Route route(@Nullable String home, boolean inventorySyncHere, String self) {
        if (home != null && !home.isBlank()) {
            return home.equalsIgnoreCase(self) ? Route.HERE : Route.FORWARD;
        }
        return inventorySyncHere ? Route.IGNORE : Route.HERE;
    }

    /** The reason for a destroyed item, from what hurt it. */
    public static String reason(DamageSource source) {
        if (source.is(DamageTypeTags.IS_FIRE)) {
            return "burnt";
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return "exploded";
        }
        if (source.is(DamageTypes.CACTUS)) {
            return "cactus";
        }
        if (source.is(DamageTypes.FALLING_ANVIL)) {
            return "crushed";
        }
        return "destroyed";
    }

    /**
     * D455: placing a machine bound to a room in grace brings the room back (D446), so it is refused while the room's
     * owner is at or over the limit (exempt owners never): otherwise giving a room back, making a new one and placing the
     * old machine again gets round the limit. Compact Machines has bound the machine before the event, so it is unbound
     * again; the placer reads why on the action bar.
     */
    public static boolean refusesRevive(ServerLevel level, BlockPos pos, @Nullable Entity placer) {
        if (!CompactMachines.present()) {
            return false;
        }
        MinecraftServer server = level.getServer();
        Optional<ChunkPos> room = CompactMachines.connectedRoom(level.getBlockEntity(pos));
        Optional<RoomGrace.Entry> g = room.flatMap(r -> RoomGrace.get(server).get(r));
        if (g.isEmpty()) {
            return false;
        }
        RoomSettings s = RoomSettings.current();
        java.util.UUID owner = g.get().owner();
        int count = RoomList.count(server, owner);
        if (!reviveRefused(s.limited(), RoomsAdmin.exempt(server, owner, s.exemptGroup()), count, s.limit())) {
            return false;
        }
        CompactMachines.disconnect(level.getBlockEntity(pos));
        if (placer instanceof ServerPlayer p) {
            p.displayClientMessage(net.minecraft.network.chat.Component.translatable(p.getUUID().equals(owner)
                    ? "message.bsp_server_utilities.rooms.revive_refused_own" : "message.bsp_server_utilities.rooms.revive_refused", count, s.limit()), true);
        }
        return true;
    }

    /** The rule of {@link #refusesRevive}: with a limit, an owner not exempt who already counts {@code limit} rooms or more. */
    public static boolean reviveRefused(boolean limited, boolean exempt, int count, int limit) {
        return limited && !exempt && count >= limit;
    }

    @Nullable
    static String home(@Nullable CompoundTag tag) {
        return tag != null && tag.contains(HOME_TAG, Tag.TAG_STRING) ? tag.getString(HOME_TAG) : null;
    }

    // ------------------------------------------------------------------ a loss

    /** A dropped item is gone for good (the mixins, the despawn timer and clear, vanilla's expiry). */
    public static void itemGone(Entity entity, String reason) {
        if (!(entity instanceof ItemEntity item) || !(item.level() instanceof ServerLevel level) || !CompactMachines.present()) {
            return;
        }
        Optional<ChunkPos> room = CompactMachines.itemRoom(item.getItem());
        if (room.isEmpty()) {
            return;
        }
        MinecraftServer server = level.getServer();
        String home = home(item.getItem().getTag());
        boolean installed = ModList.get() != null && ModList.get().isLoaded(INVENTORY_SYNC);
        if (installed && home == null && !warnedNoSync && !com.mrgregles.bsp_server_utilities.compat.InventorySync.syncsHere()) {
            warnedNoSync = true; // its machines are not marked here, so no loss can be placed on a server: said once, never guessed
            BSPServerUtilities.LOGGER.warn("A Compact Machine bound to a room was lost here, but BSP-InventorySync is not syncing on this server, so it is not marked with its home"
                    + " server: no room is put into grace for it. Start BSP-InventorySync's sync (its database) for lost machines to free their rooms.");
        }
        switch (route(home, installed, Storage.currentServerId())) {
            case HERE -> lose(server, room.get(), reason);
            case FORWARD -> RoomOutbox.send(KIND_LOST, home, room.get().x, room.get().z, home + "\n" + room.get().x + "\n" + room.get().z + "\n" + reason, clock.getAsLong());
            case IGNORE -> {
            }
        }
    }

    /**
     * Puts this server's room into grace: it stops counting at once and is cleared after the grace days unless a machine
     * bound to it is placed again. Nothing when the room is not registered, is kept for nobody, is in grace already, or
     * still has a placed machine bound to it (another way in). Returns whether it went into grace.
     */
    public static boolean lose(MinecraftServer server, ChunkPos room, String reason) {
        reason = known(reason);
        Optional<CompactMachines.Room> read = CompactMachines.room(server, room);
        if (read.isEmpty() || read.get().freed()) {
            return false;
        }
        RoomGrace grace = RoomGrace.get(server);
        if (grace.contains(room) || !CompactMachines.machinesFor(server, room).isEmpty()) {
            return false;
        }
        CompactMachines.Room r = read.get();
        long now = clock.getAsLong();
        int days = RoomSettings.current().graceDays();
        ServerPlayer owner = server.getPlayerList().getPlayer(r.owner());
        RoomGrace.Entry e = new RoomGrace.Entry(room, r.owner(), r.size(), now, now + days * DAY_MS, reason, owner != null);
        grace.put(e);
        if (owner != null) {
            RoomNotices.tell(owner, Storage.currentServerId(), e.size(), e.reason(), e.until(), now);
        }
        RoomList.changed();
        BSPServerUtilities.LOGGER.info("Compact Machines room {} ({}) of {} lost its machine ({}): it no longer counts and is cleared in {} days unless a machine bound to it is placed",
                room, r.size(), r.owner(), reason, days);
        return true;
    }

    /** {@code reason} when it is one of {@link #REASONS}, else {@code destroyed} (a notice from elsewhere is never stored or shown raw). */
    public static String known(@Nullable String reason) {
        return reason != null && REASONS.contains(reason) ? reason : "destroyed";
    }

    /** A test clock (null: the real one). */
    public static void useClockForTests(@Nullable LongSupplier testClock) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
    }

    static long now() {
        return clock.getAsLong();
    }
}
