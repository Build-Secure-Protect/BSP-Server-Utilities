package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The staff web panel's kits, the game server's part (SU-171; owner decisions D838 to D867), through BSP-Core's
 * {@code BspKits} (requests SU-BC11 to SU-BC13). The contract is {@code docs/API.md}, "Kits".
 * <ul>
 * <li>Reads for every rank: {@code kit_list}, {@code kit_read}, {@code kit_choices}, {@code kit_records} (asynchronous).</li>
 * <li>Changes for Admins and Owners (D839, checked here too): {@code kit_create}, {@code kit_set}, {@code kit_items},
 * {@code kit_icon}, {@code kit_enable}, {@code kit_copy}, {@code kit_delete}, {@code kit_give}, {@code kit_reset} (every kit only
 * with {@code all} true). Each
 * change of a kit names the stamp it was read at; BSP-Core refuses a stale one, which goes back with {@code stale}. Each is
 * an Action Log entry by "&lt;name&gt; (panel)" (D844).</li>
 * </ul>
 * Gated on {@code BspKits.VERSION} read at run time (D859): below 2 every kit action says so; 2 answers everything, every
 * server counting as ready with kits on and no {@code owed}; 3 adds {@code ready}, {@code kitsOn} and {@code owed}. The
 * records' "next time" is worked out here from the kit (BSP-Core's handoff, section 3). The snapshot's {@code kits} says this
 * server answers, {@code kitsOn} whether kits are switched on here (D863) and {@code kitsAt} moves on every
 * {@code KitEvent.Changed} here or heard, sending a snapshot at once (D845). Actions arrive on the server thread, as
 * BSP-Core requires.
 */
public final class WebKits {
    public static final String SINCE = "1.27.1";
    static final String NOT_HERE = "Kits need BSP-Core " + SINCE + " or newer on this server.";
    /** BspKits.ready false: no database, or its first full read since connecting not landed yet (D862). */
    static final String NOT_READY = "This server has not finished reading the network's kits since it started or reconnected, so it cannot answer for all of them yet: try again shortly.";
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** How long {@code kitsOn} is kept between reads of BSP-Core's choices (the snapshot asks every few seconds). */
    static final long KITS_ON_MS = 30_000L;
    private static volatile long kitsAt;
    private static long toldKits = -1;
    private static LongSupplier clock = System::currentTimeMillis;
    private static int version = Integer.MIN_VALUE;
    @Nullable
    private static Integer versionForTests;
    private static boolean kitsOn = true;
    private static long kitsOnReadAt; // 0: never read

    private WebKits() {
    }

    // ------------------------------------------------------------------ BSP-Core here?

    /** {@code BspKits.VERSION} of the BSP-Core loaded (read by reflection: a constant would be the one compiled against); 0 without it. */
    public static int version() {
        if (versionForTests != null) {
            return versionForTests;
        }
        if (version != Integer.MIN_VALUE) {
            return version;
        }
        if (ModList.get() == null || !ModList.get().isLoaded("bsp_core")) {
            return 0; // not kept: asked again once mods are loaded
        }
        int v = 0;
        try {
            v = Class.forName("com.mrgregles.bsp_core.api.BspKits").getField("VERSION").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.info("BSP-Core's kit api could not be read: {}", e.toString());
        }
        version = v;
        return v;
    }

    /** The changes and records are here: {@code BspKits.VERSION} 2 or more (BSP-Core 1.27.1). */
    public static boolean available() {
        return version() >= 2;
    }

    /** GameTests: as if this {@code BspKits.VERSION} were loaded (null: as installed). */
    public static void versionForTests(@Nullable Integer v) {
        versionForTests = v;
        kitsOnReadAt = 0;
    }

    /** Once, at mod start: BSP-Core's kit changes move {@code kitsAt} (D845). {@code KitEvent.Changed} is there from VERSION 1. */
    public static void register() {
        if (version() >= 1) {
            try {
                Live.listen();
            } catch (RuntimeException | LinkageError e) {
                version = 0; // never stops the mod loading
                BSPServerUtilities.LOGGER.warn("BSP-Core's kit changes could not be heard: {}", e.toString());
            }
        }
    }

    static void kitChanged() {
        kitsAt = Math.max(clock.getAsLong(), kitsAt + 1); // always moves, so two changes in one millisecond are both seen
    }

    /** When a kit last changed here or was heard of (0: not since start). */
    public static long kitsAt() {
        return kitsAt;
    }

    /** This server answers for every kit now (VERSION 3: {@code BspKits.ready}; VERSION 2: always, D859). */
    static boolean ready(MinecraftServer server) {
        return available() && (version() < 3 || Live.ready(server));
    }

    /** Kits are switched on here (VERSION 3: {@code Choices.kitsOn}, read at most every {@link #KITS_ON_MS}; VERSION 2: on, D859). */
    static boolean kitsOn(MinecraftServer server) {
        if (version() < 3) {
            return true;
        }
        long now = clock.getAsLong();
        if (kitsOnReadAt == 0 || now - kitsOnReadAt >= KITS_ON_MS || now < kitsOnReadAt) { // audit: a MIN_VALUE start overflowed and never read
            kitsOnReadAt = now;
            try {
                JsonObject c = GSON.toJsonTree(Live.choices(server)).getAsJsonObject();
                kitsOn = !c.has("kitsOn") || c.get("kitsOn").getAsBoolean();
            } catch (RuntimeException | LinkageError e) {
                kitsOn = true;
            }
        }
        return kitsOn;
    }

    /** The snapshot's {@code kits}, {@code kitsOn} and {@code kitsAt} (D845, D863). */
    public static void addTo(MinecraftServer server, JsonObject snapshot) {
        boolean on = false, broken = false;
        try {
            on = ready(server);
        } catch (RuntimeException | LinkageError e) {
            broken = true; // a BSP-Core whose api changed under this build: not "still reading" (audit)
        }
        snapshot.addProperty("kits", on);
        if (!on && !broken && available()) {
            snapshot.addProperty("kitsWait", true); // it has the kits but is still reading them, or its database is down (D862)
        }
        if (on) {
            snapshot.addProperty("kitsOn", kitsOn(server));
            snapshot.addProperty("kitsAt", kitsAt);
        }
    }

    /** About once a second: true when a kit changed since the last call (send a snapshot now, D845). */
    static boolean watch() {
        long k = kitsAt;
        boolean first = toldKits < 0;
        boolean moved = k != toldKits;
        toldKits = k;
        return moved && !first;
    }

    // ------------------------------------------------------------------ the actions

    /** The answer to a {@code kit_*} action, or null when {@code action} is not one. */
    @Nullable
    static WebActions.Answer handle(MinecraftServer server, String action, JsonObject a, String id, String by) {
        if (!action.startsWith("kit_")) {
            return null;
        }
        if (!available()) {
            return no(id, NOT_HERE);
        }
        boolean read = switch (action) {
            case "kit_list", "kit_read", "kit_choices", "kit_records" -> true;
            default -> false;
        };
        if (!read && !admin(a)) {
            return no(id, WebActions.ADMINS_ONLY); // D839, checked here too
        }
        try {
            return Live.handle(server, action, a, id, by);
        } catch (RuntimeException | LinkageError e) { // a LinkageError: a BSP-Core whose api changed under this build
            BSPServerUtilities.LOGGER.warn("A kit action from the staff panel failed: {}", e.toString());
            return no(id, "It could not be done here (" + e.getClass().getSimpleName() + "). It may or may not have been made: read the kit again before trying again.");
        }
    }

    static WebActions.Answer no(String id, String why) {
        return new WebActions.Answer(id, false, why);
    }

    static WebActions.Answer no(String id, String why, JsonElement data) {
        return new WebActions.Answer(id, false, why, data, null);
    }

    static WebActions.Answer yes(String id, String message, JsonElement data) {
        return new WebActions.Answer(id, true, message, data, null);
    }

    private static boolean admin(JsonObject a) {
        String r = str(a, "rank");
        return r.equals("ADMIN") || r.equals("OWNER");
    }

    /** A JSON string (data sent as an object or a number is refused, never read as no data). */
    static boolean isText(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    static long whole(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return Long.MIN_VALUE;
        }
        double v = e.getAsDouble();
        return v == Math.rint(v) && Math.abs(v) < 9.0e15 ? (long) v : Long.MIN_VALUE;
    }

    @Nullable
    static UUID uuid(JsonObject o, String k) {
        try {
            return UUID.fromString(str(o, k));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static final java.util.Set<String> LIST_FIELDS = java.util.Set.of("ranks", "servers", "grantServers", "conditions");

    /**
     * A value as BSP-Core's {@code set} takes it: text as it is, true or false, a whole number, or for a list field a list
     * joined by commas. Null when it cannot be one (an object, a list for a single value, a list entry that is not text or
     * holds a comma; audit).
     */
    @Nullable
    static String valueText(String field, @Nullable JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return "";
        }
        if (v.isJsonObject()) {
            return null;
        }
        if (v.isJsonArray()) {
            if (!LIST_FIELDS.contains(field)) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (JsonElement e : v.getAsJsonArray()) {
                if (!e.isJsonPrimitive() || e.getAsString().contains(",")) {
                    return null;
                }
                if (!e.getAsString().isBlank()) {
                    parts.add(e.getAsString().trim());
                }
            }
            return String.join(",", parts);
        }
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
            double d = v.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? Long.toString((long) d) : v.getAsString();
        }
        return v.isJsonPrimitive() ? v.getAsString() : v.toString();
    }

    /** BSP-Core's refusal keys as sentences for staff (D860). {@code max} is the item limit for {@code too_many_items}. */
    static String said(String key, int max, boolean oneItem) {
        return switch (key) {
            case "stale" -> "It was changed in game since you read it: here it is as it is now.";
            case "no_kit" -> "There is no such kit now: it may have been deleted.";
            case "bad_id" -> "An id is 2 to 24 lower-case letters, digits and _.";
            case "taken" -> "There is already a kit with that id: choose another.";
            case "bad_field" -> "That cannot be changed on a kit.";
            case "bad_value" -> "That value does not fit: a name is 1 to 24 characters, a description up to 80, a price or wait a whole number up to a trillion, and ranks or servers up to 512 characters together.";
            case "bad_item" -> "An item was refused: one this server does not know, more than a stack, data too long or too deep, an InventorySync placeholder, or a Shatter Totem or credit ticket inside another item." + (oneItem ? " (the one you just changed)" : "");
            case "too_many_items" -> "A kit holds at most " + max + " stacks.";
            case "missing_items" -> "This kit holds items from a mod this server does not have: change it on a server that has the mod (it can still be deleted here).";
            case "too_large" -> "The kit is too large to save (over 30,000 bytes, the most the in-game KITS tab can send back): take out an item with a lot of data.";
            default -> "BSP-Core refused it (" + key + ").";
        };
    }

    /**
     * When a player may take a kit again, from their network record (BSP-Core's handoff, section 3): -1 never (once only and
     * taken), 0 any time now, else the time in ms. Rank, server, price and conditions are not in a record.
     */
    public static long again(boolean once, long cooldownSeconds, int count, long lastMs, long now) {
        if (once && count > 0) {
            return -1;
        }
        if (cooldownSeconds <= 0 || count <= 0 || lastMs <= 0) {
            return 0;
        }
        long at = lastMs + Math.min(cooldownSeconds, Long.MAX_VALUE / 2000) * 1000L;
        return at <= now ? 0 : at;
    }

    static void log(MinecraftServer server, String by, String kind, String what) {
        ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", kind, what));
    }

    /** A value for a log line: no control or format characters, at most 120 characters. */
    static String shortly(@Nullable JsonElement v) {
        String s = v == null || v.isJsonNull() ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString();
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) || c == '§' || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR ? ' ' : c));
        return out.length() > 120 ? out.substring(0, 120) + "…" : out.toString();
    }

    /** GameTests: the time {@code kitsAt} and the records' next time are read from (null for the wall clock). */
    public static void clockForTests(@Nullable LongSupplier c) {
        clock = c != null ? c : System::currentTimeMillis;
    }

    // ------------------------------------------------------------------ BSP-Core's own types, loaded only with its kit api

    private static final class Live {
        private Live() {
        }

        static void listen() {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                    com.mrgregles.bsp_core.api.event.KitEvent.Changed.class, e -> kitChanged());
        }

        static boolean ready(MinecraftServer server) {
            return com.mrgregles.bsp_core.api.BspKits.ready(server); // VERSION 3
        }

        static com.mrgregles.bsp_core.api.BspKits.Choices choices(MinecraftServer server) {
            return com.mrgregles.bsp_core.api.BspKits.choices(server);
        }

        /** A kit for the panel: every field of BSP-Core's {@code Detail} as it is at run time ({@code owed} only from VERSION 3). */
        static JsonObject kit(com.mrgregles.bsp_core.api.BspKits.Detail d) {
            return GSON.toJsonTree(d).getAsJsonObject();
        }

        static WebActions.Answer handle(MinecraftServer server, String action, JsonObject a, String id, String by) {
            String kit = str(a, "kit");
            if (!action.equals("kit_choices") && !action.equals("kit_records") && !WebKits.ready(server)) {
                return no(id, NOT_READY); // an old copy must never answer, nor pass an old stamp over a newer edit (audit)
            }
            switch (action) {
                case "kit_list" -> {
                    JsonArray list = new JsonArray();
                    for (var d : com.mrgregles.bsp_core.api.BspKits.details(server)) {
                        list.add(kit(d));
                    }
                    JsonObject out = new JsonObject();
                    out.add("kits", list);
                    out.addProperty("version", version());
                    out.addProperty("kitsOn", kitsOn(server));
                    return yes(id, "kits", out);
                }
                case "kit_read" -> {
                    var d = com.mrgregles.bsp_core.api.BspKits.detail(server, kit);
                    if (d.isEmpty()) {
                        return no(id, said("no_kit", 0, false));
                    }
                    JsonObject out = new JsonObject();
                    out.add("kit", kit(d.get()));
                    out.addProperty("version", version());
                    return yes(id, "kit", out);
                }
                case "kit_choices" -> {
                    JsonObject c = GSON.toJsonTree(choices(server)).getAsJsonObject();
                    if (!c.has("kitsOn")) {
                        c.addProperty("kitsOn", true); // VERSION 2: kits count as on (D859)
                    }
                    JsonObject out = new JsonObject();
                    out.add("choices", c);
                    out.addProperty("version", version());
                    return yes(id, "choices", out);
                }
                case "kit_records" -> {
                    UUID player = uuid(a, "player");
                    if (player == null) {
                        return no(id, "There is no such player.");
                    }
                    CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
                    com.mrgregles.bsp_core.api.BspKits.records(server, player, (rows, fresh) -> {
                        try { // called back on the server thread; a failure here must still answer the panel
                            later.complete(records(server, id, player, rows, fresh));
                        } catch (RuntimeException | LinkageError e) {
                            BSPServerUtilities.LOGGER.warn("A player's kit records could not be put for the staff panel: {}", e.toString());
                            later.complete(no(id, "The player's kit records could not be read here: try again in a moment."));
                        }
                    });
                    return new WebActions.Answer(id, true, "", null, later);
                }
                default -> {
                    return change(server, action, a, id, by, kit);
                }
            }
        }

        private static WebActions.Answer records(MinecraftServer server, String id, UUID player, List<com.mrgregles.bsp_core.api.BspKits.Record> rows, Boolean fresh) {
            Map<String, com.mrgregles.bsp_core.api.BspKits.Detail> byId = new HashMap<>();
            for (var d : com.mrgregles.bsp_core.api.BspKits.details(server)) {
                byId.put(d.id(), d);
            }
            long now = clock.getAsLong();
            JsonArray out = new JsonArray();
            for (var r : rows == null ? List.<com.mrgregles.bsp_core.api.BspKits.Record>of() : rows) {
                JsonObject o = GSON.toJsonTree(r).getAsJsonObject();
                var d = byId.get(r.kit());
                o.addProperty("here", d != null); // false: a kit deleted since, still resettable by its id (D866)
                if (d != null && r.scope().isEmpty()) {
                    o.addProperty("again", again(d.once(), d.cooldownSeconds(), r.count(), r.lastMs(), now));
                }
                out.add(o);
            }
            JsonObject data = new JsonObject();
            data.addProperty("player", player.toString());
            data.add("rows", out);
            data.addProperty("fresh", fresh != null && fresh);
            return yes(id, "records", data);
        }

        private static WebActions.Answer change(MinecraftServer server, String action, JsonObject a, String id, String by, String kit) {
            long edited = whole(a, "edited");
            boolean stamped = switch (action) {
                case "kit_set", "kit_items", "kit_icon", "kit_enable", "kit_delete" -> true;
                default -> false;
            };
            if (stamped && edited < 0) {
                return no(id, "Read the kit again first.");
            }
            com.mrgregles.bsp_core.api.BspKits.Answer r;
            String kind, what;
            boolean oneItem = false;
            switch (action) {
                case "kit_create" -> {
                    r = com.mrgregles.bsp_core.api.BspKits.create(server, kit, str(a, "name"), by);
                    kind = LogEntry.KIT_CREATE;
                    what = "made kit " + shortly(a.get("kit")) + ", off";
                }
                case "kit_set" -> {
                    String field = str(a, "field");
                    String text = valueText(field, a.get("value"));
                    if (text == null) {
                        return no(id, said("bad_value", 0, false));
                    }
                    r = com.mrgregles.bsp_core.api.BspKits.set(server, kit, field, text, edited, by);
                    kind = LogEntry.KIT_EDIT;
                    what = "changed " + shortly(a.get("field")) + " of kit " + shortly(a.get("kit")) + " to " + shortly(a.get("value"));
                }
                case "kit_items" -> {
                    List<com.mrgregles.bsp_core.api.BspKits.ItemRow> rows = itemRows(a.get("items"));
                    oneItem = a.has("one") && a.get("one").isJsonPrimitive() && a.get("one").getAsJsonPrimitive().isBoolean() && a.get("one").getAsBoolean();
                    if (rows == null) {
                        return no(id, said("bad_item", 0, oneItem));
                    }
                    r = com.mrgregles.bsp_core.api.BspKits.setItems(server, kit, rows, edited, by);
                    kind = LogEntry.KIT_EDIT;
                    what = "set the items of kit " + shortly(a.get("kit")) + " (" + rows.size() + (rows.size() == 1 ? " stack)" : " stacks)");
                }
                case "kit_icon" -> {
                    if (!a.has("icon")) {
                        return no(id, "The icon, please (null for the first item)."); // never cleared by a missing field (audit)
                    }
                    JsonElement icon = a.get("icon");
                    if (!icon.isJsonNull() && !icon.isJsonObject() || icon.isJsonObject() && icon.getAsJsonObject().has("nbt") && !isText(icon.getAsJsonObject().get("nbt"))) {
                        return no(id, said("bad_item", 0, false));
                    }
                    com.mrgregles.bsp_core.api.BspKits.ItemRow row = null;
                    if (icon != null && icon.isJsonObject() && !str(icon.getAsJsonObject(), "item").isEmpty()) {
                        row = new com.mrgregles.bsp_core.api.BspKits.ItemRow(str(icon.getAsJsonObject(), "item"), 1, str(icon.getAsJsonObject(), "nbt"));
                    }
                    r = com.mrgregles.bsp_core.api.BspKits.setIcon(server, kit, row, edited, by);
                    kind = LogEntry.KIT_EDIT;
                    what = "set the icon of kit " + shortly(a.get("kit")) + " to " + (row == null ? "its first item" : shortly(icon.getAsJsonObject().get("item")));
                }
                case "kit_enable" -> {
                    if (!a.has("on") || !a.get("on").isJsonPrimitive() || !a.get("on").getAsJsonPrimitive().isBoolean()) {
                        return no(id, "On or off, please.");
                    }
                    boolean on = a.get("on").getAsBoolean();
                    r = com.mrgregles.bsp_core.api.BspKits.setEnabled(server, kit, on, edited, by);
                    kind = on ? LogEntry.KIT_ON : LogEntry.KIT_OFF;
                    what = "turned kit " + shortly(a.get("kit")) + (on ? " on" : " off");
                }
                case "kit_copy" -> {
                    r = com.mrgregles.bsp_core.api.BspKits.copy(server, kit, str(a, "newId"), str(a, "name"), by);
                    kind = LogEntry.KIT_COPY;
                    what = "copied kit " + shortly(a.get("kit")) + " to " + shortly(a.get("newId")) + ", off";
                }
                case "kit_delete" -> {
                    r = com.mrgregles.bsp_core.api.BspKits.delete(server, kit, edited, by);
                    kind = LogEntry.KIT_DELETE;
                    what = "deleted kit " + shortly(a.get("kit"));
                }
                case "kit_give" -> {
                    UUID player = uuid(a, "player");
                    if (player == null) {
                        return no(id, "There is no such player.");
                    }
                    r = com.mrgregles.bsp_core.api.BspKits.give(server, player, kit, by);
                    kind = LogEntry.KIT_GIVE;
                    what = "gave kit " + shortly(a.get("kit")) + " to " + (str(a, "name").isEmpty() ? player.toString() : shortly(a.get("name")));
                }
                case "kit_reset" -> {
                    UUID player = uuid(a, "player");
                    if (player == null) {
                        return no(id, "There is no such player.");
                    }
                    boolean all = a.has("all") && a.get("all").isJsonPrimitive() && a.get("all").getAsJsonPrimitive().isBoolean() && a.get("all").getAsBoolean();
                    if (all && !kit.isEmpty()) {
                        return no(id, "Name a kit, or every kit, not both."); // audit
                    }
                    if (!all && kit.isEmpty()) {
                        return no(id, "Name a kit to reset, or every kit."); // every kit only when asked for in so many words
                    }
                    r = com.mrgregles.bsp_core.api.BspKits.reset(server, player, all ? null : kit, by); // null: every kit (BSP-Core takes "all" as an id)
                    kind = LogEntry.KIT_RESET;
                    what = "reset " + (all ? "every kit" : "kit " + shortly(a.get("kit"))) + " for " + (str(a, "name").isEmpty() ? player.toString() : shortly(a.get("name")));
                }
                default -> {
                    return no(id, "This server does not know that kit action; is it on the same version as the panel?");
                }
            }
            JsonObject d = new JsonObject();
            if (action.equals("kit_copy")) { // the copy's new id once made; on a refusal the kit asked to copy, and newId apart (audit)
                d.addProperty("kit", r.ok() ? str(a, "newId") : kit);
                d.addProperty("newId", str(a, "newId"));
            } else {
                d.addProperty("kit", r.kit() == null || r.kit().isEmpty() ? kit : r.kit());
            }
            d.addProperty("edited", r.edited());
            if (!r.ok()) {
                if (r.key().equals("stale")) {
                    d.addProperty("stale", true);
                }
                if (action.equals("kit_reset") && r.key().equals("bad_value")) {
                    return no(id, "There is no such player.", d);
                }
                int max = r.key().equals("too_many_items") ? choices(server).maxItems() : 0;
                return no(id, said(r.key(), max, oneItem), d);
            }
            d.addProperty("owed", r.owed());
            log(server, by, kind, what);
            int n = r.owed();
            return yes(id, switch (r.key()) {
                case "created" -> action.equals("kit_copy") ? "Copied, off." : "Made, off and empty.";
                case "deleted" -> n > 0 ? "Deleted. " + n + (n == 1 ? " player was" : " players were") + " still owed it." : "Deleted.";
                case "given" -> n == 0 ? "Given: it is in their inventory now." : "Given: it goes to them the next time they join a server with kits on, or from their kit browser.";
                case "reset" -> n == 1 ? "Reset: 1 record forgotten." : "Reset: " + n + " records forgotten.";
                default -> "Saved.";
            }, d);
        }

        /** The panel's item rows as BSP-Core's, or null when one is not an item, a count and data. */
        @Nullable
        private static List<com.mrgregles.bsp_core.api.BspKits.ItemRow> itemRows(@Nullable JsonElement items) {
            if (items == null || !items.isJsonArray()) {
                return null;
            }
            List<com.mrgregles.bsp_core.api.BspKits.ItemRow> out = new ArrayList<>();
            for (JsonElement e : items.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    return null;
                }
                JsonObject o = e.getAsJsonObject();
                long count = whole(o, "count");
                if (str(o, "item").isEmpty() || count < 1 || count > Integer.MAX_VALUE // a stack's own limit is BSP-Core's to check
                        || o.has("nbt") && !isText(o.get("nbt"))) { // data that is not text is refused, never dropped (audit)
                    return null;
                }
                out.add(new com.mrgregles.bsp_core.api.BspKits.ItemRow(str(o, "item"), (int) count, str(o, "nbt")));
            }
            return out;
        }
    }
}
