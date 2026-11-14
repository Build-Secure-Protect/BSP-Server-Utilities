package com.mrgregles.bsp_server_utilities.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The only class that touches BSP-InventorySync for the staff web panel: graves (D295 to D302; its D30), its api's
 * {@code graves(UUID)} and {@code returnGrave(UUID, id, by)}, and a player's items (SU-39), {@code read(UUID)}, from API
 * version 2 (its 0.7.0). Read by reflection, as this
 * mod is not built against BSP-InventorySync; anything missing (not installed, older) makes it unavailable, never an error.
 * Both calls may come from any thread and their futures complete on the server thread.
 */
public final class InventorySync {
    public static final String MODID = "bsp_inventorysync";
    private static final String API = "com.mrgregles.bsp_inventorysync.api.InventorySyncApi";

    /** What the panel's grave actions need; the real one reads BSP-InventorySync, tests give their own. */
    public interface Graves {
        /** The player's graves as JSON objects (the fields of its {@code GraveInfo}). */
        CompletableFuture<JsonArray> graves(UUID player);

        /** The return's answer as JSON (the fields of its {@code GraveReturn}, plus {@code ok}). */
        CompletableFuture<JsonObject> returnGrave(UUID player, String id, String by);

        /**
         * What one grave holds (its SU-IS3 {@code graveItems}): the area {@code grave}, then backpacks and danks inside it;
         * empty when they have no such grave or it was emptied. Not every BSP-InventorySync has it: failed then.
         */
        default CompletableFuture<java.util.Optional<GraveContents>> graveItems(UUID player, String id) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("this BSP-InventorySync cannot list a grave's items"));
        }
    }

    /** A grave's items, as its {@code GraveItems}: the areas (read only) and the XP. */
    public record GraveContents(List<ReadArea> areas, int xp) {
    }

    @Nullable
    private static Graves override;

    // ------------------------------------------------------------------ a player's items (SU-39, D256, D336, its SU-IS2)

    /** One slot as BSP-InventorySync read it: a copy of the stack, the missing mod's item id, and whether it is locked. */
    public record ReadSlot(int index, net.minecraft.world.item.ItemStack stack, @Nullable String missing, boolean locked) {
    }

    /**
     * One area of a player's items ({@code inventory}, {@code armor}, {@code ender_chest}, {@code curios/ring},
     * {@code backpack/<id>}...): its kind, whether items may be added or removed there, where a backpack or dank sits
     * ({@code placeArea} null when not known), and every slot, empty ones included.
     */
    public record ReadArea(String id, String kind, boolean addable, boolean removable, @Nullable String placeArea, int placeSlot, List<String> inside,
                           List<ReadSlot> slots) {
    }

    /** A player's items: live from this server, or from their newest snapshot ({@code version}, saved at {@code savedAt}). */
    public record Read(boolean online, String serverId, boolean live, long version, long savedAt, List<ReadArea> areas) {
    }

    /** What the panel's item views need; the real one reads BSP-InventorySync, tests give their own. */
    public interface Items {
        /** The player's items, or empty when nothing was ever saved for them. Completes on the server thread. */
        CompletableFuture<java.util.Optional<Read>> read(UUID player);
    }

    @Nullable
    private static Items itemsOverride;

    /** BSP-InventorySync's item reads for the panel, or null when it is not installed or older than API 2. */
    @Nullable
    public static Items items() {
        if (itemsOverride != null) {
            return itemsOverride;
        }
        if (ModList.get() == null || !ModList.get().isLoaded(MODID)) {
            return null;
        }
        try {
            Class<?> api = Class.forName(API);
            if (api.getField("API_VERSION").getInt(null) < 2) {
                return null;
            }
            Method read = api.getMethod("read", UUID.class);
            return player -> {
                try {
                    @SuppressWarnings("unchecked")
                    CompletableFuture<java.util.Optional<Object>> f = (CompletableFuture<java.util.Optional<Object>>) read.invoke(null, player);
                    return f.thenApply(o -> o.map(InventorySync::toRead));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    return CompletableFuture.failedFuture(e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.debug("BSP-InventorySync's item reads not usable: {}", e.toString());
            return null;
        }
    }

    @Nullable
    private static Method optionalMethod(Class<?> c, String name, Class<?>... args) {
        try {
            return c.getMethod(name, args);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Its {@code GraveItems} as our own record (public for the GameTests' look-alike). */
    public static GraveContents toGraveContents(Object g) {
        return new GraveContents(toAreas((List<?>) call(g, "areas")), (Integer) call(g, "xp"));
    }

    /** Its {@code PlayerItems} as our own record, read through the records' accessors (public for the GameTests' look-alikes). */
    public static Read toRead(Object items) {
        return new Read((Boolean) call(items, "online"), (String) call(items, "serverId"), (Boolean) call(items, "live"), (Long) call(items, "version"),
                (Long) call(items, "savedAt"), toAreas((List<?>) call(items, "areas")));
    }

    private static List<ReadArea> toAreas(List<?> list) {
        List<ReadArea> areas = new java.util.ArrayList<>();
        for (Object a : list) {
            Object place = call(a, "place");
            List<ReadSlot> slots = new java.util.ArrayList<>();
            for (Object sl : (List<?>) call(a, "slots")) {
                slots.add(new ReadSlot((Integer) call(sl, "index"), (net.minecraft.world.item.ItemStack) call(sl, "stack"), (String) call(sl, "missingItem"),
                        (Boolean) call(sl, "locked")));
            }
            List<String> inside = new java.util.ArrayList<>();
            if (place != null) {
                for (Object i : (List<?>) call(place, "inside")) {
                    inside.add(String.valueOf(i));
                }
            }
            areas.add(new ReadArea((String) call(a, "id"), String.valueOf(call(a, "kind")), (Boolean) call(a, "addable"), (Boolean) call(a, "removable"),
                    place == null ? null : (String) call(place, "area"), place == null ? -1 : (Integer) call(place, "slot"), inside, slots));
        }
        return areas;
    }

    private static Object call(Object target, String accessor) {
        try {
            return target.getClass().getMethod(accessor).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("BSP-InventorySync's " + target.getClass().getSimpleName() + "." + accessor + " is missing", e);
        }
    }

    public static void useItemsForTests(@Nullable Items i) {
        itemsOverride = i;
    }

    // ------------------------------------------------------------------ changing a player's items (SU-40, D257, D339, its SU-IS2)

    /**
     * What came of one change, as its {@code EditResult}: {@code outcome} (OK, ONLINE, CHANGED, BUSY, NO_ROOM, REFUSED,
     * FAILED), a line for staff, the snapshot saved and the one pinned before it, and the server holding the player.
     */
    public record EditOut(String outcome, String message, long version, long pinnedVersion, String serverId) {
        public boolean ok() {
            return "OK".equals(outcome);
        }
    }

    /** BSP-InventorySync's {@code edit}: one add into an area, or one removal from a slot. Completes on the server thread. */
    public interface Edits {
        CompletableFuture<EditOut> add(UUID player, long expectedVersion, String area, net.minecraft.world.item.ItemStack stack, String by);

        CompletableFuture<EditOut> remove(UUID player, long expectedVersion, String area, int slot, net.minecraft.world.item.ItemStack expected, int count, String by);
    }

    @Nullable
    private static Edits editsOverride;

    /** BSP-InventorySync's edits for the panel, or null when it is not installed or older than API 2. */
    @Nullable
    public static Edits edits() {
        if (editsOverride != null) {
            return editsOverride;
        }
        if (ModList.get() == null || !ModList.get().isLoaded(MODID)) {
            return null;
        }
        try {
            Class<?> api = Class.forName(API), edit = Class.forName("com.mrgregles.bsp_inventorysync.api.Edit");
            if (api.getField("API_VERSION").getInt(null) < 2) {
                return null;
            }
            Method run = api.getMethod("edit", UUID.class, long.class, edit, String.class);
            Method add = edit.getMethod("add", String.class, net.minecraft.world.item.ItemStack.class);
            Method remove = edit.getMethod("remove", String.class, int.class, net.minecraft.world.item.ItemStack.class, int.class);
            return new Edits() {
                @Override
                public CompletableFuture<EditOut> add(UUID player, long expectedVersion, String area, net.minecraft.world.item.ItemStack stack, String by) {
                    return call(player, expectedVersion, () -> add.invoke(null, area, stack), by);
                }

                @Override
                public CompletableFuture<EditOut> remove(UUID player, long expectedVersion, String area, int slot, net.minecraft.world.item.ItemStack expected, int count, String by) {
                    return call(player, expectedVersion, () -> remove.invoke(null, area, slot, expected, count), by);
                }

                @SuppressWarnings("unchecked")
                private CompletableFuture<EditOut> call(UUID player, long expectedVersion, java.util.concurrent.Callable<Object> change, String by) {
                    try {
                        return ((CompletableFuture<Object>) run.invoke(null, player, expectedVersion, change.call(), by)).thenApply(InventorySync::toEditOut);
                    } catch (Exception | LinkageError e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.debug("BSP-InventorySync's item edits not usable: {}", e.toString());
            return null;
        }
    }

    /** Its {@code EditResult} as our own record (public for the GameTests' look-alike). */
    public static EditOut toEditOut(Object r) {
        return new EditOut(String.valueOf(call(r, "outcome")), (String) call(r, "message"), (Long) call(r, "version"), (Long) call(r, "pinnedVersion"),
                (String) call(r, "serverId"));
    }

    public static void useEditsForTests(@Nullable Edits e) {
        editsOverride = e;
    }

    private InventorySync() {
    }

    @Nullable
    /**
     * Whether BSP-InventorySync runs its sync on this server (its {@code isEnabled}: a shared database in use), so it marks
     * machine items with their home server; false when it is not installed or cannot say (Compact Machines rooms, D451).
     */
    public static boolean syncsHere() {
        if (ModList.get() == null || !ModList.get().isLoaded(MODID)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(Class.forName(API).getMethod("isEnabled").invoke(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    private static Method syncing;
    private static boolean syncingLooked;
    @Nullable
    private static java.util.function.Predicate<UUID> syncingOverride;

    /** Whether the player's data is still arriving on this server (its {@code isSyncing}); false without BSP-InventorySync. */
    public static boolean isSyncing(UUID player) {
        if (syncingOverride != null) {
            return syncingOverride.test(player);
        }
        if (!syncingLooked) {
            if (ModList.get() == null) {
                return false;
            }
            syncingLooked = true;
            if (ModList.get().isLoaded(MODID)) {
                try {
                    syncing = Class.forName(API).getMethod("isSyncing", UUID.class);
                } catch (ReflectiveOperationException | LinkageError e) {
                    syncing = null;
                }
            }
        }
        try {
            return syncing != null && (Boolean) syncing.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static void useSyncingForTests(@Nullable java.util.function.Predicate<UUID> p) {
        syncingOverride = p;
    }

    /** BSP-InventorySync's graves for the panel, or null when it is not installed or older than API 2. */
    @Nullable
    public static Graves graves() {
        if (override != null) {
            return override;
        }
        if (ModList.get() == null || !ModList.get().isLoaded(MODID)) {
            return null;
        }
        try {
            Class<?> api = Class.forName(API);
            if (api.getField("API_VERSION").getInt(null) < 2) {
                return null;
            }
            Method graves = api.getMethod("graves", UUID.class), ret = api.getMethod("returnGrave", UUID.class, String.class, String.class);
            Method items = optionalMethod(api, "graveItems", UUID.class, String.class); // SU-IS3; graves work without it
            return new Graves() {
                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<JsonArray> graves(UUID player) {
                    try {
                        return ((CompletableFuture<List<Object>>) graves.invoke(null, player)).thenApply(list -> {
                            JsonArray out = new JsonArray();
                            list.forEach(g -> out.add(fields(g)));
                            return out;
                        });
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }

                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<JsonObject> returnGrave(UUID player, String id, String by) {
                    try {
                        return ((CompletableFuture<Object>) ret.invoke(null, player, id, by)).thenApply(r -> {
                            JsonObject o = fields(r);
                            try {
                                o.addProperty("ok", (Boolean) r.getClass().getMethod("ok").invoke(r));
                            } catch (ReflectiveOperationException e) {
                                o.addProperty("ok", false);
                            }
                            return o;
                        });
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }

                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<java.util.Optional<GraveContents>> graveItems(UUID player, String id) {
                    if (items == null) {
                        return Graves.super.graveItems(player, id);
                    }
                    try {
                        return ((CompletableFuture<java.util.Optional<Object>>) items.invoke(null, player, id)).thenApply(o -> o.map(InventorySync::toGraveContents));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.debug("BSP-InventorySync's graves not usable: {}", e.toString());
            return null;
        }
    }

    /** A record's components as JSON: numbers, booleans, text; anything else as text. */
    public static JsonObject fields(Object record) {
        JsonObject o = new JsonObject();
        RecordComponent[] rc = record.getClass().getRecordComponents();
        if (rc == null) {
            return o;
        }
        for (RecordComponent c : rc) {
            try {
                Object v = c.getAccessor().invoke(record);
                if (v instanceof Number n) {
                    o.addProperty(c.getName(), n);
                } else if (v instanceof Boolean b) {
                    o.addProperty(c.getName(), b);
                } else if (v != null) {
                    o.addProperty(c.getName(), v.toString());
                }
            } catch (ReflectiveOperationException e) {
                // left out
            }
        }
        return o;
    }

    public static void useForTests(@Nullable Graves g) {
        override = g;
    }
}
