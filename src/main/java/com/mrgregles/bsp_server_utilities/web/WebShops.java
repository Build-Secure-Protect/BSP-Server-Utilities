package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The staff web panel's shops, the game server's part (SU-165; owner decisions D788 to D825), through BSP-Core 1.26.0's
 * {@code BspShops} (version 1; requests SU-BC5 to SU-BC10). The contract is {@code docs/API.md}, "Shops".
 * <ul>
 * <li>Reads for every rank: {@code shop_list}, {@code shop_read}, {@code shop_venues} (this server's own stalls),
 * {@code shop_report} (asynchronous), {@code shop_export}.</li>
 * <li>Changes for Admins and Owners (checked here too): {@code shop_create}, {@code shop_set}, {@code shop_enable},
 * {@code shop_copy}, {@code shop_delete}, {@code shop_item_add}, {@code shop_item_set}, {@code shop_item_move},
 * {@code shop_item_remove}, {@code shop_item_refill}. Each names the stamp it was read at; BSP-Core refuses a stale one,
 * which goes back with {@code stale} (D794). Each is an Action Log entry by "&lt;name&gt; (panel)" (D810).</li>
 * </ul>
 * Where BSP-Core's calls differ from the panel's contract this class bridges them: a section picture's potion {@code v} is
 * made SNBT, a note's lines are joined by new lines, opening hours and needs go to their own calls, and a refusal key is
 * put as a sentence (D819). The snapshot's {@code shops} says this server answers ({@code BspShops.ready}, D816);
 * {@code shopsAt} moves on every {@code ShopEvent.Changed} here or heard, {@code stallsAt} on this server's
 * {@code ShopEvent.VenuesChanged}, each sending a snapshot at once (D817). Without BSP-Core 1.26.0 every action says so
 * (D818). Actions arrive on the server thread, as BSP-Core requires.
 */
public final class WebShops {
    public static final String SINCE = "1.26.0";
    static final String NOT_HERE = "Shops need BSP-Core " + SINCE + " on this server.";
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile long shopsAt, stallsAt;
    private static long toldShops = -1, toldStalls = -1;
    private static LongSupplier clock = System::currentTimeMillis;
    @Nullable
    private static Boolean ready, readyForTests;

    private WebShops() {
    }

    // ------------------------------------------------------------------ BSP-Core here?

    /** BSP-Core 1.26.0 or newer with {@code BspShops} version 1 (read by reflection: a constant would be the one compiled against). */
    public static boolean available() {
        if (readyForTests != null) {
            return readyForTests;
        }
        if (ready != null) {
            return ready;
        }
        if (ModList.get() == null || !ModList.get().isLoaded("bsp_core")) {
            return false; // not kept: asked again once mods are loaded
        }
        boolean ok = false;
        try {
            ok = ModList.get().getModContainerById("bsp_core")
                    .map(c -> atLeast(c.getModInfo().getVersion(), 1, 26, 0)).orElse(false)
                    && Class.forName("com.mrgregles.bsp_core.api.BspShops").getField("VERSION").getInt(null) >= 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.info("BSP-Core's shop api could not be read: {}", e.toString());
        }
        ready = ok;
        return ok;
    }

    /** Major, minor and patch only: a 1.26.0-rc1 or -SNAPSHOT build counts as 1.26.0 (audit). */
    static boolean atLeast(org.apache.maven.artifact.versioning.ArtifactVersion v, int major, int minor, int patch) {
        if (v.getMajorVersion() != major) {
            return v.getMajorVersion() > major;
        }
        if (v.getMinorVersion() != minor) {
            return v.getMinorVersion() > minor;
        }
        return v.getIncrementalVersion() >= patch;
    }

    /** GameTests: as if BSP-Core's shops were here or not (null: as installed). */
    public static void availableForTests(@Nullable Boolean on) {
        readyForTests = on;
    }

    /** Once, at mod start: BSP-Core's shop and venue events move {@code shopsAt} and {@code stallsAt} (D817). */
    public static void register() {
        if (available()) {
            try {
                Live.listen();
            } catch (RuntimeException | LinkageError e) {
                ready = false; // never stops the mod loading
                BSPServerUtilities.LOGGER.warn("BSP-Core's shop changes could not be heard: {}", e.toString());
            }
        }
    }

    static void shopChanged() {
        shopsAt = Math.max(clock.getAsLong(), shopsAt + 1); // always moves, so two changes in one millisecond are both seen
    }

    /** Stalls are learned as their chunks load: a player exploring would move stallsAt every second, so it moves at most this often (audit). */
    static final long STALLS_MS = 10_000L;
    private static volatile boolean stallsDirty;
    private static long stallsMovedAt;

    static void stallsChanged() {
        stallsDirty = true; // made known by watch(), at most once in STALLS_MS
    }

    private static void flushStalls() {
        long now = clock.getAsLong();
        if (stallsDirty && now - stallsMovedAt >= STALLS_MS) {
            stallsDirty = false;
            stallsMovedAt = now;
            stallsAt = Math.max(now, stallsAt + 1);
        }
    }

    /** When a shop last changed here or was heard of (0: not since start). */
    public static long shopsAt() {
        return shopsAt;
    }

    /** When this server's own stalls last changed (0: not since start). */
    public static long stallsAt() {
        return stallsAt;
    }

    /** The snapshot's {@code shops}, {@code shopsAt} and {@code stallsAt}. */
    public static void addTo(MinecraftServer server, JsonObject snapshot) {
        boolean on = available() && Live.ready(server);
        snapshot.addProperty("shops", on);
        if (on) {
            snapshot.addProperty("shopsAt", shopsAt);
            snapshot.addProperty("stallsAt", stallsAt);
        }
    }

    /** About once a second: true when a shop or a stall changed since the last call (send a snapshot now, D817). */
    static boolean watch() {
        flushStalls();
        long s = shopsAt, v = stallsAt;
        boolean first = toldShops < 0;
        boolean moved = s != toldShops || v != toldStalls;
        toldShops = s;
        toldStalls = v;
        return moved && !first;
    }

    // ------------------------------------------------------------------ the actions

    /** The answer to a {@code shop_*} action, or null when {@code action} is not one. */
    @Nullable
    static WebActions.Answer handle(MinecraftServer server, String action, JsonObject a, String id, String by) {
        if (!action.startsWith("shop_")) {
            return null;
        }
        if (!available()) {
            return no(id, NOT_HERE);
        }
        boolean read = switch (action) {
            case "shop_list", "shop_read", "shop_venues", "shop_report", "shop_export" -> true;
            default -> false;
        };
        if (!read && !admin(a)) {
            return no(id, WebActions.ADMINS_ONLY); // D788, checked here too
        }
        try {
            return Live.handle(server, action, a, id, by);
        } catch (RuntimeException | LinkageError e) { // a LinkageError: a BSP-Core whose api changed under this build (audit)
            BSPServerUtilities.LOGGER.warn("A shop action from the staff panel failed: {}", e.toString());
            return no(id, "It could not be done here (" + e.getClass().getSimpleName() + "). It may or may not have been made: read the shop again before trying again.");
        }
    }

    static WebActions.Answer no(String id, String why) {
        return new WebActions.Answer(id, false, why);
    }

    static WebActions.Answer yes(String id, String message, JsonElement data) {
        return new WebActions.Answer(id, true, message, data, null);
    }

    private static boolean admin(JsonObject a) {
        String r = str(a, "rank");
        return r.equals("ADMIN") || r.equals("OWNER");
    }

    static String actor(String by) {
        return by + " (panel)";
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

    /** BSP-Core's refusal keys as sentences for staff (D819; worded for BSP-Core 1.27.0's answers, D850 to D852). */
    static String said(String key) {
        return switch (key) {
            case "stale" -> "It was changed in game since you read it: here it is as it is now.";
            case "no_shop" -> "There is no such shop now: it may have been deleted.";
            case "bad_id" -> "An id is 1 to 32 lower-case letters, digits and _, and cannot be all."; // D850
            case "taken" -> "There is already a shop with that id: choose another.";
            case "bad_field" -> "That cannot be changed on this shop or card.";
            case "bad_value" -> "That value does not fit: a name needs a letter or digit you can see and is up to 64 characters; a section moves only into another shop, or to Top level when its shop is gone."; // D851
            case "bad_item" -> "That is not an item this server knows, or its data is too long or nested too deep."; // D852
            case "bad_price" -> "A price in coins is at most 10,000 coins.";
            case "no_item" -> "There is no such card now: read the shop again.";
            case "no_parent" -> "There is no such shop to put it in.";
            case "parent_loop" -> "That would put the shop inside itself, or more than 16 deep.";
            case "not_top" -> "Only a top shop can do that: a section follows its shop.";
            case "rotation_cards" -> "Some cards fill again at each draw, which only a rotating shop makes: change them first.";
            case "rotation_only" -> "Only a rotating shop's cards fill again at each draw.";
            default -> "BSP-Core refused it (" + key + ").";
        };
    }

    /** What a section card shows: a potion's {@code v} as SNBT, as BSP-Core takes it. */
    static String pictureNbt(String v) {
        return v.isEmpty() ? "" : "{Potion:\"" + v.replace("\\", "").replace("\"", "") + "\"}";
    }

    /** A note's lines as BSP-Core takes them: one text with a new line between lines. */
    static String noteText(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        }
        StringBuilder b = new StringBuilder();
        for (JsonElement l : value.getAsJsonArray()) {
            if (l.isJsonPrimitive()) {
                b.append(b.length() > 0 ? "\n" : "").append(l.getAsString());
            }
        }
        return b.toString();
    }

    static void log(MinecraftServer server, String by, String kind, String what) {
        ActionLog.record(server, ActionLog.entryForServer(actor(by), kind, what));
    }

    /** GameTests: the time {@code shopsAt} and {@code stallsAt} are read from (null for the wall clock). */
    public static void clockForTests(@Nullable LongSupplier c) {
        clock = c != null ? c : System::currentTimeMillis;
    }

    // ------------------------------------------------------------------ BSP-Core's own types, loaded only with 1.26.0

    private static final class Live {
        private Live() {
        }

        static void listen() {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                    com.mrgregles.bsp_core.api.event.ShopEvent.Changed.class, e -> shopChanged());
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                    com.mrgregles.bsp_core.api.event.ShopEvent.VenuesChanged.class, e -> stallsChanged());
        }

        static boolean ready(MinecraftServer server) {
            return com.mrgregles.bsp_core.api.BspShops.ready(server);
        }

        static WebActions.Answer handle(MinecraftServer server, String action, JsonObject a, String id, String by) {
            String shop = str(a, "shop");
            long edited = whole(a, "edited");
            switch (action) {
                case "shop_list" -> {
                    if (!ready(server)) {
                        return no(id, "This server's database is not connected, so it cannot answer for every shop now.");
                    }
                    JsonObject d = new JsonObject();
                    d.add("shops", GSON.toJsonTree(com.mrgregles.bsp_core.api.BspShops.list(server)));
                    d.addProperty("timeZone", com.mrgregles.bsp_core.api.BspShops.timeZone());
                    return yes(id, "shops", d);
                }
                case "shop_read" -> {
                    var detail = com.mrgregles.bsp_core.api.BspShops.read(server, shop);
                    if (detail == null) {
                        return no(id, said("no_shop"));
                    }
                    JsonObject s = GSON.toJsonTree(detail.shop()).getAsJsonObject();
                    s.add("cards", GSON.toJsonTree(detail.cards()));
                    s.add("onShow", GSON.toJsonTree(detail.onShow()));
                    s.addProperty("cycleLeft", detail.cycleLeft());
                    s.add("draws", GSON.toJsonTree(detail.draws()));
                    JsonObject d = new JsonObject();
                    d.add("shop", s);
                    return yes(id, "shop", d);
                }
                case "shop_venues" -> {
                    JsonArray v = new JsonArray();
                    for (var venue : com.mrgregles.bsp_core.api.BspShops.venues(server)) {
                        if (!venue.shop().isEmpty()) { // one not given a shop is nothing the panel shows
                            v.add(GSON.toJsonTree(venue));
                        }
                    }
                    JsonObject d = new JsonObject();
                    d.add("venues", v);
                    return yes(id, "stalls", d);
                }
                case "shop_export" -> {
                    String file = com.mrgregles.bsp_core.api.BspShops.export(server, shop);
                    if (file == null) {
                        return no(id, said("no_shop"));
                    }
                    JsonObject json = JsonParser.parseString(file).getAsJsonObject();
                    int shops = 0, cards = 0;
                    if (json.has("shops") && json.get("shops").isJsonArray()) {
                        for (JsonElement e : json.getAsJsonArray("shops")) {
                            shops++;
                            if (e.isJsonObject() && e.getAsJsonObject().has("pages")) {
                                for (JsonElement p : e.getAsJsonObject().getAsJsonArray("pages")) {
                                    cards += p.isJsonArray() ? p.getAsJsonArray().size() : 0;
                                }
                            }
                        }
                    }
                    JsonObject d = new JsonObject();
                    d.add("json", json);
                    d.addProperty("shops", shops);
                    d.addProperty("cards", cards);
                    return yes(id, "exported", d);
                }
                case "shop_report" -> {
                    long days = whole(a, "days");
                    CompletableFuture<WebActions.Answer> later = new CompletableFuture<>();
                    com.mrgregles.bsp_core.api.BspShops.report(server, days == 1 || days == 30 ? (int) days : 7, shop, r -> {
                        try { // on a network BSP-Core calls back from its own task: a failure here must still answer the panel (audit)
                            later.complete(report(id, shop, r));
                        } catch (RuntimeException | LinkageError e) {
                            BSPServerUtilities.LOGGER.warn("The shop report could not be put for the staff panel: {}", e.toString());
                            later.complete(no(id, "The shop report could not be read here: try again in a moment."));
                        }
                    });
                    return new WebActions.Answer(id, true, "", null, later);
                }
                default -> {
                    return change(server, action, a, id, by, shop, edited);
                }
            }
        }

        private static WebActions.Answer report(String id, String shop, com.mrgregles.bsp_core.api.BspShops.Report r) {
            if (r == null || !r.ok()) {
                return no(id, "The shop trades could not be read now (the database did not answer): try again in a moment.");
            }
            JsonObject d = new JsonObject();
            d.addProperty("shop", shop);
            d.addProperty("days", r.days());
            d.addProperty("capped", r.capped());
            d.addProperty("piecesIn", r.total().piecesIn());
            d.addProperty("piecesOut", r.total().piecesOut());
            d.add("coinsIn", GSON.toJsonTree(r.total().coinsIn()));
            d.add("coinsOut", GSON.toJsonTree(r.total().coinsOut()));
            d.add("rows", GSON.toJsonTree(r.items()));
            d.add("sellers", GSON.toJsonTree(r.sellers()));
            return yes(id, "sales", d);
        }

        private static WebActions.Answer change(MinecraftServer server, String action, JsonObject a, String id, String by, String shop, long edited) {
            if (!action.equals("shop_create") && !action.equals("shop_copy") && edited < 0) {
                return no(id, "Read the shop again first.");
            }
            com.mrgregles.bsp_core.api.BspShops.Answer r;
            String kind, what;
            switch (action) {
                case "shop_create" -> {
                    String parent = str(a, "parent");
                    if (parent.isEmpty()) {
                        r = com.mrgregles.bsp_core.api.BspShops.create(server, shop, str(a, "name"), str(a, "kind"), by);
                        what = "made shop " + shortly(a.get("shop")) + " (" + shortly(a.get("kind")) + "), off";
                    } else {
                        r = com.mrgregles.bsp_core.api.BspShops.createSection(server, shop, str(a, "name"), parent, str(a, "picture"), pictureNbt(str(a, "v")), edited, by);
                        what = "made section " + shortly(a.get("shop")) + " in " + shortly(a.get("parent"));
                    }
                    kind = LogEntry.SHOP_CREATE;
                }
                case "shop_set" -> {
                    String field = str(a, "field");
                    JsonElement v = a.get("value");
                    if (field.equals("hours") && v != null && v.isJsonObject()) {
                        JsonObject h = v.getAsJsonObject();
                        r = com.mrgregles.bsp_core.api.BspShops.setHours(server, shop,
                                new com.mrgregles.bsp_core.api.BspShops.Hours(str(h, "mode"), (int) Math.max(-1, Math.min(Integer.MAX_VALUE, whole(h, "open"))),
                                        (int) Math.max(-1, Math.min(Integer.MAX_VALUE, whole(h, "close")))), edited, by); // out of range is refused by BSP-Core, never wrapped (audit)
                    } else if (field.equals("requires") && v != null && v.isJsonObject()) {
                        r = com.mrgregles.bsp_core.api.BspShops.setRequires(server, shop, needs(v.getAsJsonObject()), edited, by);
                    } else {
                        r = com.mrgregles.bsp_core.api.BspShops.set(server, shop, field, v == null ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString(), edited, by);
                    }
                    kind = LogEntry.SHOP_EDIT;
                    what = "changed " + shortly(a.get("field")) + " of " + shortly(a.get("shop")) + " to " + shortly(v);
                }
                case "shop_enable" -> {
                    boolean on = a.has("on") && a.get("on").isJsonPrimitive() && a.get("on").getAsJsonPrimitive().isBoolean() && a.get("on").getAsBoolean();
                    if (!a.has("on") || !a.get("on").isJsonPrimitive() || !a.get("on").getAsJsonPrimitive().isBoolean()) {
                        return no(id, "On or off, please.");
                    }
                    r = com.mrgregles.bsp_core.api.BspShops.setEnabled(server, shop, on, edited, by);
                    kind = on ? LogEntry.SHOP_ON : LogEntry.SHOP_OFF;
                    what = "turned shop " + shortly(a.get("shop")) + (on ? " on" : " off");
                }
                case "shop_copy" -> {
                    r = com.mrgregles.bsp_core.api.BspShops.copy(server, shop, str(a, "newId"), str(a, "name"), by);
                    kind = LogEntry.SHOP_COPY;
                    what = "copied shop " + shortly(a.get("shop")) + " to " + shortly(a.get("newId")) + ", off";
                }
                case "shop_delete" -> {
                    r = com.mrgregles.bsp_core.api.BspShops.delete(server, shop, edited, by);
                    kind = LogEntry.SHOP_DELETE;
                    what = "deleted shop " + shortly(a.get("shop"));
                }
                case "shop_item_add" -> {
                    long count = whole(a, "count"), at = whole(a, "place");
                    r = com.mrgregles.bsp_core.api.BspShops.addItem(server, shop, str(a, "item"), str(a, "nbt").isEmpty() ? pictureNbt(str(a, "v")) : str(a, "nbt"),
                            (int) Math.max(1, Math.min(64, count < 0 ? 1 : count)), (int) Math.max(0, Math.min(Integer.MAX_VALUE, at < 0 ? Integer.MAX_VALUE : at)), edited, by);
                    kind = LogEntry.SHOP_EDIT;
                    what = "added " + shortly(a.get("item")) + " to " + shortly(a.get("shop"));
                }
                case "shop_item_set" -> {
                    String field = str(a, "field");
                    int index = (int) Math.max(-1, Math.min(Integer.MAX_VALUE, whole(a, "index")));
                    JsonElement v = a.get("value");
                    if (field.equals("requires") && v != null && v.isJsonObject()) {
                        r = com.mrgregles.bsp_core.api.BspShops.setItemRequires(server, shop, index, needs(v.getAsJsonObject()), edited, by);
                    } else {
                        String text = field.equals("note") ? noteText(v) : v == null ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString();
                        r = com.mrgregles.bsp_core.api.BspShops.setItem(server, shop, index, field, text, edited, by);
                    }
                    kind = LogEntry.SHOP_EDIT;
                    what = "changed " + shortly(a.get("field")) + " of card " + (index + 1) + " in " + shortly(a.get("shop")) + " to " + shortly(v);
                }
                case "shop_item_move" -> {
                    int from = (int) Math.max(-1, Math.min(Integer.MAX_VALUE, whole(a, "from"))), to = (int) Math.max(-1, Math.min(Integer.MAX_VALUE, whole(a, "to")));
                    r = com.mrgregles.bsp_core.api.BspShops.moveItem(server, shop, from, to, edited, by);
                    kind = LogEntry.SHOP_EDIT;
                    what = "moved card " + (from + 1) + " to " + (to + 1) + " in " + shortly(a.get("shop"));
                }
                case "shop_item_remove", "shop_item_refill" -> {
                    int index = (int) Math.max(-1, Math.min(Integer.MAX_VALUE, whole(a, "index")));
                    boolean remove = action.equals("shop_item_remove");
                    r = remove ? com.mrgregles.bsp_core.api.BspShops.removeItem(server, shop, index, edited, by)
                            : com.mrgregles.bsp_core.api.BspShops.refill(server, shop, index, edited, by);
                    kind = LogEntry.SHOP_EDIT;
                    what = (remove ? "removed card " : "refilled card ") + (index + 1) + " in " + shortly(a.get("shop"));
                }
                default -> {
                    return no(id, "This server does not know that shop action; is it on the same version as the panel?");
                }
            }
            if (!r.ok()) {
                JsonObject d = new JsonObject();
                if (r.key().equals("stale")) {
                    d.addProperty("stale", true);
                    d.addProperty("edited", r.edited());
                }
                return new WebActions.Answer(id, false, said(r.key()), d, null);
            }
            log(server, by, kind, what);
            JsonObject d = new JsonObject();
            d.addProperty("shop", action.equals("shop_copy") ? str(a, "newId") : r.shop() == null || r.shop().isEmpty() ? shop : r.shop());
            d.addProperty("edited", r.edited());
            if (r.parent() != null && !r.parent().isEmpty()) {
                d.addProperty("parent", r.parent());
                d.addProperty("parentEdited", r.parentEdited());
            }
            if (r.index() >= 0) {
                d.addProperty("index", r.index());
            }
            if (!r.deleted().isEmpty()) {
                d.add("deleted", GSON.toJsonTree(r.deleted()));
            }
            if (!r.ids().isEmpty()) {
                JsonObject ids = new JsonObject();
                for (Map.Entry<String, String> e : r.ids().entrySet()) {
                    ids.addProperty(e.getKey(), e.getValue());
                }
                d.add("ids", ids);
                d.addProperty("shop", r.ids().getOrDefault(shop, str(a, "newId")));
            }
            return yes(id, switch (r.key()) {
                case "created" -> action.equals("shop_copy") ? "Copied, off." : action.equals("shop_create") && str(a, "parent").isEmpty() ? "Made, off." : "Made.";
                case "deleted" -> r.deleted().size() > 1 ? "Deleted with its " + (r.deleted().size() - 1) + (r.deleted().size() == 2 ? " section." : " sections.") : "Deleted.";
                default -> action.equals("shop_item_refill") ? "Every count of the card is full again." : "Saved.";
            }, d);
        }

        private static com.mrgregles.bsp_core.api.BspShops.Needs needs(JsonObject n) {
            long h = whole(n, "playtimeHours");
            return new com.mrgregles.bsp_core.api.BspShops.Needs(str(n, "permission"), (int) Math.max(0, Math.min(100_000, h < 0 ? 0 : h)),
                    n.has("hide") && n.get("hide").isJsonPrimitive() && n.get("hide").getAsJsonPrimitive().isBoolean() && n.get("hide").getAsBoolean(), str(n, "reason"));
        }

        private static String shortly(@Nullable JsonElement v) {
            String s = v == null ? "" : v.isJsonPrimitive() ? v.getAsString() : v.toString();
            StringBuilder out = new StringBuilder();
            s.codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) || c == '§' || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR ? ' ' : c));
            return out.length() > 120 ? out.substring(0, 120) + "…" : out.toString();
        }
    }
}
