package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_server_utilities.compat.InventorySync;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Adding and removing a player's items from the staff panel (SU-40; D257, D335, D339, D342, D343, D361), through
 * BSP-InventorySync's {@code edit}: the server holding the player makes the change live, any server changes an offline
 * player's newest save (it answers {@code ONLINE} with the server to ask when they are on another). Only Admins, Owners
 * and Moderators given the items power (the action's {@code items}) may; the panel checks it before signing.
 *
 * <ul>
 * <li>{@code inv_add}: {@code item} (id), {@code snbt} (its tags, optional), {@code count} (1 to {@link #MAX_COUNT}),
 * {@code area}, {@code version} (the save the panel showed, for an offline player).</li>
 * <li>{@code inv_remove}: {@code area}, {@code slot}, {@code hash} (the item the panel showed, {@link WebItems#hash}),
 * {@code count}. The items are read again: a slot holding something else is not touched.</li>
 * <li>{@code inv_take}: {@code area}, {@code hash}, {@code count}: up to that many of that item out of the area, slot after
 * slot (the UNDO of an add, which may have filled several stacks).</li>
 * </ul>
 * A change that is made: an Action Log entry, a gold line to the player when they are on this server (D361; nothing for an
 * offline player, D362), and in the answer's data the change that would undo it (D343) for the panel to keep.
 */
public final class WebItemEdits {
    public static final int MAX_COUNT = 6400, MAX_SNBT = 65536, MAX_TAKES = 64;
    /** An UNDO puts back the item that was removed, whose data may be larger than a typed one (a shulker box of books). */
    public static final int MAX_UNDO_SNBT = 1_048_576;
    /** Nesting of the item's data: the game's own reader refuses over 512, and nothing real comes near this (audit). */
    public static final int MAX_DEPTH = 96;

    private static BiFunction<MinecraftServer, UUID, ServerPlayer> lookup = (s, id) -> s.getPlayerList().getPlayer(id);
    private static BiConsumer<ServerPlayer, Component> tell = ServerPlayer::sendSystemMessage;

    private WebItemEdits() {
    }

    static final String NOT_ALLOWED = "Adding and removing items is for Admins, Owners and Moderators given the items power.";

    private static boolean allowed(JsonObject a) {
        String rank = str(a, "rank");
        return rank.equals("ADMIN") || rank.equals("OWNER") || bool(a, "items");
    }

    @Nullable
    private static String checkCommon(JsonObject a, @Nullable UUID who) {
        if (!allowed(a)) {
            return NOT_ALLOWED;
        }
        if (who == null) {
            return "No such player.";
        }
        if (!str(a, "area").matches("[a-z_]{1,32}(/[A-Za-z0-9_.:-]{1,80})?")) {
            return "No such place in their items.";
        }
        return null;
    }

    // ------------------------------------------------------------------ add

    public static WebActions.Answer add(MinecraftServer server, JsonObject a, String id, String by) {
        InventorySync.Edits edits = InventorySync.edits();
        UUID who = uuid(str(a, "player"));
        String why = edits == null ? WebActions.NO_ITEMS : checkCommon(a, who);
        int count = integer(a, "count");
        if (why == null && (count < 1 || count > MAX_COUNT)) {
            why = "Between 1 and " + MAX_COUNT + " at once.";
        }
        ItemStack stack = ItemStack.EMPTY;
        if (why == null) {
            ResourceLocation rl = ResourceLocation.tryParse(str(a, "item"));
            Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            if (item == null || item == Items.AIR) {
                why = "No item called " + str(a, "item") + " on this server.";
            } else {
                stack = new ItemStack(item, count);
                String snbt = str(a, "snbt");
                if (snbt.length() > (bool(a, "undo") ? MAX_UNDO_SNBT : MAX_SNBT)) {
                    why = "The item's data is too long.";
                } else if (depth(snbt) > MAX_DEPTH) {
                    why = "The item's data is nested too deeply."; // checked before reading: deep nesting would overflow the stack (audit)
                } else if (!snbt.isEmpty()) {
                    try {
                        CompoundTag tag = TagParser.parseTag(snbt);
                        stack.setTag(tag.isEmpty() ? null : tag);
                    } catch (CommandSyntaxException | RuntimeException e) {
                        why = "The item's data could not be read.";
                    }
                }
            }
        }
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        ItemStack added = stack;
        String area = str(a, "area"), name = playerName(a, who);
        boolean undo = bool(a, "undo");
        return new WebActions.Answer(id, true, "", null, edits.add(who, longOf(a, "version"), area, stack.copy(), by).handle((r, t) -> {
            if (t != null) {
                return new WebActions.Answer(id, false, "The items could not be changed: " + WebActions.cause(t));
            }
            JsonObject data = outcome(r);
            if (!r.ok()) {
                return new WebActions.Answer(id, false, r.message(), data, null);
            }
            String itemId = String.valueOf(ForgeRegistries.ITEMS.getKey(added.getItem()));
            ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, (undo ? "items undo: " : "items ") + "add " + name + " " + count + " " + itemId
                    + " into " + area + (live(server, who) ? "" : " (offline)")));
            told(server, who, true, count, added, area);
            data.addProperty("hash", WebItems.hash(added));
            if (!undo) {
                JsonObject u = new JsonObject();
                u.addProperty("action", "inv_take");
                u.addProperty("area", area);
                u.addProperty("hash", WebItems.hash(added));
                u.addProperty("count", count);
                data.add("undo", u);
            }
            return new WebActions.Answer(id, true, r.message(), data, null);
        }));
    }

    // ------------------------------------------------------------------ remove

    public static WebActions.Answer remove(MinecraftServer server, JsonObject a, String id, String by) {
        InventorySync.Edits edits = InventorySync.edits();
        InventorySync.Items items = InventorySync.items();
        UUID who = uuid(str(a, "player"));
        String why = edits == null || items == null ? WebActions.NO_ITEMS : checkCommon(a, who);
        int count = integer(a, "count"), slot = integer(a, "slot");
        String hash = str(a, "hash"), area = str(a, "area"), name = playerName(a, who);
        if (why == null && (count < 1 || slot < 0 || !hash.matches("[0-9a-f]{12}"))) {
            why = "Not a removal the server understands.";
        }
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        boolean undo = bool(a, "undo");
        CompletableFuture<WebActions.Answer> done = items.read(who).thenCompose(read -> {
            Optional<InventorySync.ReadArea> ar = read.flatMap(r -> r.areas().stream().filter(x -> x.id().equals(area)).findFirst());
            InventorySync.ReadSlot sl = ar.flatMap(x -> x.slots().stream().filter(s -> s.index() == slot).findFirst()).orElse(null);
            if (sl == null || sl.stack().isEmpty() || !WebItems.hash(sl.stack()).equals(hash)) {
                return CompletableFuture.completedFuture(new WebActions.Answer(id, false, "That slot changed since you looked: read their items again.", changed(), null));
            }
            if (sl.locked()) {
                return CompletableFuture.completedFuture(new WebActions.Answer(id, false, "A live Shatter Totem is in it: the panel never moves it."));
            }
            ItemStack seen = sl.stack().copy();
            int n = Math.min(count, seen.getCount());
            boolean addable = ar.get().addable();
            return edits.remove(who, read.get().version(), area, slot, seen.copy(), n, by).thenApply(r -> {
                JsonObject data = outcome(r);
                if (!r.ok()) {
                    return new WebActions.Answer(id, false, r.message(), data, null);
                }
                String itemId = String.valueOf(ForgeRegistries.ITEMS.getKey(seen.getItem()));
                ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, (undo ? "items undo: " : "items ") + "remove " + name + " " + n + " " + itemId
                        + " from " + area + " slot " + slot + (live(server, who) ? "" : " (offline)")));
                told(server, who, false, n, seen, area);
                if (!undo) {
                    JsonObject u = new JsonObject();
                    u.addProperty("action", "inv_add");
                    u.addProperty("area", addable ? area : "inventory"); // armour, offhand, Curios and the cursor take nothing: back into the inventory
                    u.addProperty("item", itemId);
                    CompoundTag tag = seen.getTag();
                    u.addProperty("snbt", tag == null ? "" : tag.toString());
                    u.addProperty("count", n);
                    u.addProperty("version", r.version()); // an offline player's snapshot changes only from the version it is at (audit)
                    data.add("undo", u);
                }
                return new WebActions.Answer(id, true, r.message(), data, null);
            });
        }).exceptionally(t -> new WebActions.Answer(id, false, "The items could not be changed: " + WebActions.cause(t)));
        return new WebActions.Answer(id, true, "", null, done);
    }

    // ------------------------------------------------------------------ take back (the UNDO of an add)

    public static WebActions.Answer take(MinecraftServer server, JsonObject a, String id, String by) {
        InventorySync.Edits edits = InventorySync.edits();
        InventorySync.Items items = InventorySync.items();
        UUID who = uuid(str(a, "player"));
        String why = edits == null || items == null ? WebActions.NO_ITEMS : checkCommon(a, who);
        int count = integer(a, "count");
        String hash = str(a, "hash"), area = str(a, "area"), name = playerName(a, who);
        if (why == null && (count < 1 || count > MAX_COUNT || !hash.matches("[0-9a-f]{12}"))) {
            why = "Not a change the server understands.";
        }
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        ItemStack[] what = {ItemStack.EMPTY};
        InventorySync.EditOut[] stop = {null};
        CompletableFuture<int[]> taken = takeStep(edits, items, who, area, hash, count, by, new int[]{0, 0}, what, stop);
        return new WebActions.Answer(id, true, "", null, taken.thenApply(n -> {
            if (n[0] == 0) {
                // a refusal is said as it is (ONLINE lets the panel ask their server; BUSY may be tried again), not as "gone" (audit)
                return stop[0] != null ? new WebActions.Answer(id, false, stop[0].message(), outcome(stop[0]), null)
                        : new WebActions.Answer(id, false, "None of it is there any more: nothing was taken back.");
            }
            ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "items undo: take back " + n[0] + " from " + name + "'s " + area
                    + (live(server, who) ? "" : " (offline)")));
            told(server, who, false, n[0], what[0], area);
            String rest = stop[0] != null ? ": the rest was refused (" + stop[0].message() + ")" : n[1] >= MAX_TAKES ? ": it was spread over more than " + MAX_TAKES + " slots, take the rest by hand"
                    : ": the rest is gone already";
            return new WebActions.Answer(id, true, n[0] == count ? "Took back all " + count + "." : "Took back " + n[0] + " of " + count + rest + ".");
        }).exceptionally(t -> new WebActions.Answer(id, false, "The items could not be changed: " + WebActions.cause(t))));
    }

    /** One slot at a time, reading again before each (an offline save moves on with every change). {@code n}: taken, steps. */
    private static CompletableFuture<int[]> takeStep(InventorySync.Edits edits, InventorySync.Items items, UUID who, String area, String hash, int left, String by,
                                                     int[] n, ItemStack[] what, InventorySync.EditOut[] stop) {
        if (left <= 0 || n[1] >= MAX_TAKES) {
            return CompletableFuture.completedFuture(n);
        }
        return items.read(who).thenCompose(read -> {
            InventorySync.ReadSlot sl = read.flatMap(r -> r.areas().stream().filter(x -> x.id().equals(area)).findFirst())
                    .flatMap(x -> x.slots().stream().filter(s -> !s.stack().isEmpty() && !s.locked() && WebItems.hash(s.stack()).equals(hash)).reduce((first, second) -> second))
                    .orElse(null);
            if (sl == null) {
                return CompletableFuture.completedFuture(n);
            }
            int take = Math.min(left, sl.stack().getCount());
            n[1]++;
            what[0] = sl.stack().copy();
            return edits.remove(who, read.get().version(), area, sl.index(), sl.stack().copy(), take, by).thenCompose(r -> {
                if (!r.ok()) {
                    stop[0] = r;
                    return CompletableFuture.completedFuture(n);
                }
                n[0] += take;
                return takeStep(edits, items, who, area, hash, left - take, by, n, what, stop);
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The gold line in game (D361), only to a player on this server; the words are the client's own (lang keys). */
    private static void told(MinecraftServer server, @Nullable UUID who, boolean added, int count, ItemStack item, String area) {
        ServerPlayer p = who == null ? null : lookup.apply(server, who);
        if (p == null) {
            return;
        }
        String kind = area.contains("/") ? area.substring(0, area.indexOf('/')) : area;
        if (kind.equals("curios_cosmetic")) {
            kind = "curios";
        }
        Component place = Component.translatableWithFallback("message.bsp_server_utilities.web.area." + kind, fallbackArea(kind));
        Component line = added ? Component.translatableWithFallback("message.bsp_server_utilities.web.items_added", "Staff added %s %s to %s.", count, item.getHoverName(), place)
                : Component.translatableWithFallback("message.bsp_server_utilities.web.items_removed", "Staff removed %s %s from %s.", count, item.getHoverName(), place);
        tell.accept(p, line.copy().withStyle(ChatFormatting.GOLD));
    }

    static String fallbackArea(String kind) {
        return switch (kind) {
            case "inventory" -> "your inventory";
            case "ender_chest" -> "your ender chest";
            case "armor" -> "your armour";
            case "offhand" -> "your offhand";
            case "cursor" -> "your hand";
            case "curios" -> "your Curios slots";
            case "backpack" -> "a backpack";
            case "dank" -> "a dank";
            default -> "your items";
        };
    }

    private static boolean live(MinecraftServer server, @Nullable UUID who) {
        return who != null && lookup.apply(server, who) != null;
    }

    private static JsonObject outcome(InventorySync.EditOut r) {
        JsonObject o = new JsonObject();
        o.addProperty("outcome", r.outcome());
        o.addProperty("version", r.version());
        o.addProperty("pinnedVersion", r.pinnedVersion());
        o.addProperty("serverId", r.serverId());
        return o;
    }

    /** The deepest nesting of {@code {}} and {@code []} outside quoted strings. */
    public static int depth(String snbt) {
        int d = 0, max = 0;
        char quote = 0;
        for (int i = 0; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                max = Math.max(max, ++d);
            } else if (c == '}' || c == ']') {
                d--;
            }
        }
        return max;
    }

    private static JsonObject changed() {
        JsonObject o = new JsonObject();
        o.addProperty("outcome", "CHANGED");
        return o;
    }

    private static String playerName(JsonObject a, @Nullable UUID who) {
        String n = str(a, "name");
        return n.matches("[A-Za-z0-9_]{1,16}") ? n : String.valueOf(who);
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static int integer(JsonObject o, String k) {
        try {
            return o.get(k).getAsInt();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static long longOf(JsonObject o, String k) {
        try {
            return o.get(k).getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static void useForTests(@Nullable BiFunction<MinecraftServer, UUID, ServerPlayer> find, @Nullable BiConsumer<ServerPlayer, Component> shown) {
        lookup = find != null ? find : (s, id) -> s.getPlayerList().getPlayer(id);
        tell = shown != null ? shown : ServerPlayer::sendSystemMessage;
    }
}
