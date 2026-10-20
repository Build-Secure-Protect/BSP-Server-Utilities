package com.mrgregles.bsp_server_utilities.log;

import com.mrgregles.bsp_server_utilities.access.Staff;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Items that appear from the creative menu (owner decision D25), counted per item over {@value #WINDOW_MS} ms so that
 * moving items around the inventory is not logged: each creative slot packet is measured as the change in how many of
 * its items the player has; a stack thrown out of the menu counts as taken. When the window ends, every item the player
 * gained becomes one entry ("took 64 × diamond from creative"). Only staff and operators in creative are watched.
 */
public final class CreativeTracker {
    public static final long WINDOW_MS = 5000;

    private static final class Window {
        final ServerPlayer player;
        final long endsAt;
        final Map<Item, Integer> gained = new LinkedHashMap<>();
        Map<Item, Integer> before = Map.of();

        Window(ServerPlayer player, long endsAt) {
            this.player = player;
            this.endsAt = endsAt;
        }
    }

    private static final Map<UUID, Window> WINDOWS = new HashMap<>();

    private CreativeTracker() {
    }

    private static boolean watched(ServerPlayer player) {
        return player.isCreative() && player.server.isSameThread() && ActionLog.tracked(player).isPresent();
    }

    private static int count(ServerPlayer player, Item item) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) {
            n += s.is(item) ? s.getCount() : 0;
        }
        for (ItemStack s : player.getInventory().armor) {
            n += s.is(item) ? s.getCount() : 0;
        }
        for (ItemStack s : player.getInventory().offhand) {
            n += s.is(item) ? s.getCount() : 0;
        }
        return n;
    }

    public static void before(ServerPlayer player, int slot, ItemStack stack) {
        if (!watched(player)) {
            return;
        }
        Window w = WINDOWS.computeIfAbsent(player.getUUID(), k -> new Window(player, System.currentTimeMillis() + WINDOW_MS));
        Map<Item, Integer> counts = new HashMap<>();
        if (!stack.isEmpty()) {
            counts.put(stack.getItem(), count(player, stack.getItem()));
        }
        if (slot >= 0 && slot < player.inventoryMenu.slots.size()) {
            ItemStack old = player.inventoryMenu.getSlot(slot).getItem();
            if (!old.isEmpty()) {
                counts.put(old.getItem(), count(player, old.getItem()));
            }
        }
        w.before = counts;
    }

    public static void after(ServerPlayer player, int slot, ItemStack stack) {
        Window w = WINDOWS.get(player.getUUID());
        if (w == null || !watched(player)) {
            return;
        }
        for (Map.Entry<Item, Integer> e : w.before.entrySet()) {
            int delta = count(player, e.getKey()) - e.getValue();
            if (delta != 0) {
                w.gained.merge(e.getKey(), delta, Integer::sum);
            }
        }
        if (slot < 0 && !stack.isEmpty()) {
            w.gained.merge(stack.getItem(), stack.getCount(), Integer::sum); // thrown straight out of the menu
        }
        w.before = Map.of();
    }

    /** Ends the windows that are due: one entry per item gained. */
    public static void tick(MinecraftServer server) {
        if (WINDOWS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        WINDOWS.values().removeIf(w -> {
            if (now < w.endsAt) {
                return false;
            }
            flush(server, w);
            return true;
        });
    }

    /** Tests: ends a player's window now. */
    public static void flushNow(MinecraftServer server, ServerPlayer player) {
        Window w = WINDOWS.remove(player.getUUID());
        if (w != null) {
            flush(server, w);
        }
    }

    private static void flush(MinecraftServer server, Window w) {
        Optional<Staff.Role> role = ActionLog.tracked(w.player);
        if (role.isEmpty()) {
            return;
        }
        w.gained.forEach((item, n) -> {
            if (n > 0) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                String name = id == null ? "?" : id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
                ActionLog.record(server, ActionLog.entryFor(w.player, role.get(), LogEntry.ITEM, "took " + n + " × " + name + " from creative"));
            }
        });
    }
}
