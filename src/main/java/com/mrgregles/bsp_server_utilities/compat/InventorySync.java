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
 * The only class that touches BSP-InventorySync for the staff web panel's graves (D295 to D302; its D30): its api's
 * {@code graves(UUID)} and {@code returnGrave(UUID, id, by)}, from API version 2 (its 0.7.0). Read by reflection, as this
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
    }

    @Nullable
    private static Graves override;

    private InventorySync() {
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
