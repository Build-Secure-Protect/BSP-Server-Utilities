package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.InventorySync;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A player's items on the staff panel (SU-39, D256, D335 to D338): what each item carries to the panel (names as keys,
 * enchantments, durability, lore, a shulker box's contents), the hash a removal will name (the same whatever the count,
 * different when the tags differ), BSP-InventorySync's records read through their accessors, and the {@code inv_read}
 * action answering with the items, only filled slots listed, locks and a backpack's place kept.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebItemsGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 3);
        }
    }

    private static String action(String id, long at, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Mod\",\"rank\":\"MODERATOR\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    static ItemStack sword() {
        ItemStack s = new ItemStack(Items.DIAMOND_SWORD);
        s.enchant(Enchantments.SHARPNESS, 5);
        s.setDamageValue(10);
        s.setHoverName(Component.literal("Biter"));
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("Dug the Moongrove mine"))));
        s.getOrCreateTagElement("display").put("Lore", lore);
        return s;
    }

    @GameTest(template = EMPTY)
    public void an_item_carries_its_key_enchantments_durability_lore_and_contents(GameTestHelper h) {
        JsonObject sw = WebItems.stack(sword(), true);
        h.assertTrue(sw.get("id").getAsString().equals("minecraft:diamond_sword") && sw.get("key").getAsString().equals("item.minecraft.diamond_sword"), "id and key: " + sw);
        h.assertTrue(sw.get("name").getAsString().equals("Biter"), "a renamed item's own name: " + sw);
        JsonArray e = sw.getAsJsonArray("ench");
        h.assertTrue(e.size() == 1 && e.get(0).getAsJsonArray().get(0).getAsString().equals("minecraft:sharpness") && e.get(0).getAsJsonArray().get(1).getAsInt() == 5, "enchantments as [id, level]: " + e);
        h.assertTrue(sw.get("dmg").getAsInt() == 10 && sw.get("max").getAsInt() == Items.DIAMOND_SWORD.getMaxDamage(), "durability: " + sw);
        h.assertTrue(sw.getAsJsonArray("lore").get(0).getAsString().equals("Dug the Moongrove mine"), "lore as plain text: " + sw);

        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantedBookItem.addEnchantment(book, new EnchantmentInstance(Enchantments.MENDING, 1));
        JsonObject b = WebItems.stack(book, true);
        h.assertTrue(b.getAsJsonArray("ench").get(0).getAsJsonArray().get(0).getAsString().equals("minecraft:mending") && !b.has("dmg"), "a book's stored enchantments, no durability: " + b);

        ItemStack potion = new ItemStack(Items.POTION);
        potion.getOrCreateTag().putString("Potion", "minecraft:swiftness");
        JsonObject p = WebItems.stack(potion, true);
        h.assertTrue(p.get("v").getAsString().equals("minecraft:swiftness") && p.get("key").getAsString().contains("swiftness"), "a potion: its kind for the picture, its key: " + p);

        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        ListTag items = new ListTag();
        CompoundTag one = new ItemStack(Items.TNT, 16).save(new CompoundTag());
        one.putByte("Slot", (byte) 3);
        items.add(one);
        CompoundTag two = new ItemStack(Items.DIAMOND, 64).save(new CompoundTag());
        two.putByte("Slot", (byte) 26);
        items.add(two);
        box.getOrCreateTagElement("BlockEntityTag").put("Items", items);
        JsonArray in = WebItems.stack(box, true).getAsJsonArray("in");
        h.assertTrue(in.size() == 2 && in.get(0).getAsJsonObject().get("id").getAsString().equals("minecraft:tnt") && in.get(0).getAsJsonObject().get("n").getAsInt() == 16
                && in.get(1).getAsJsonObject().get("i").getAsInt() == 26, "a shulker box's contents, with their slots: " + in);
        JsonObject plain = WebItems.stack(new ItemStack(Items.OAK_LOG, 64), true);
        h.assertTrue(!plain.has("name") && !plain.has("ench") && !plain.has("lore") && !plain.has("in") && plain.get("n").getAsInt() == 64
                && plain.get("key").getAsString().equals("block.minecraft.oak_log"), "a plain stack carries only what it has: " + plain);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_hash_ignores_the_count_and_follows_the_tags(GameTestHelper h) {
        String a = WebItems.hash(new ItemStack(Items.DIAMOND, 1)), b = WebItems.hash(new ItemStack(Items.DIAMOND, 64));
        h.assertTrue(a.equals(b) && a.length() == 12, "the same item, any count: one hash (" + a + ", " + b + ")");
        ItemStack named = new ItemStack(Items.DIAMOND, 64);
        named.setHoverName(Component.literal("Shiny"));
        h.assertTrue(!WebItems.hash(named).equals(a), "renamed: another hash");
        h.assertTrue(!WebItems.hash(new ItemStack(Items.EMERALD)).equals(a), "another item: another hash");
        ItemStack worn = sword(), fresh = sword();
        fresh.setDamageValue(0);
        h.assertTrue(!WebItems.hash(worn).equals(WebItems.hash(fresh)), "a worn tool differs from a fresh one (its damage is a tag)");
        h.succeed();
    }

    // look-alikes of BSP-InventorySync's api records: the same names and types, read through the same accessors
    public record FakeSlot(int index, ItemStack stack, String missingItem, boolean locked) {
    }

    public record FakePlace(String area, int slot, List<String> inside) {
    }

    public enum FakeKind { INVENTORY, BACKPACK }

    public record FakeArea(String id, FakeKind kind, boolean addable, boolean removable, FakePlace place, List<FakeSlot> slots) {
    }

    public record FakeItems(UUID player, boolean online, String serverId, boolean live, long version, long savedAt, List<FakeArea> areas) {
    }

    @GameTest(template = EMPTY)
    public void inventory_syncs_records_are_read_and_inv_read_answers_with_the_items(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        UUID vexa = UUID.randomUUID();
        List<FakeSlot> inv = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            inv.add(new FakeSlot(i, i == 0 ? sword() : i == 7 ? new ItemStack(Items.TOTEM_OF_UNDYING) : i == 12 ? new ItemStack(Items.BARRIER) : ItemStack.EMPTY,
                    i == 12 ? "create:brass_ingot" : null, i == 7));
        }
        List<FakeSlot> pack = List.of(new FakeSlot(0, new ItemStack(Items.COBBLESTONE, 640), null, false), new FakeSlot(1, ItemStack.EMPTY, null, false));
        FakeItems fake = new FakeItems(vexa, true, "Moongrove", true, 41, 1_800_000_000_000L, List.of(
                new FakeArea("inventory", FakeKind.INVENTORY, true, true, null, inv),
                new FakeArea("backpack/abc", FakeKind.BACKPACK, false, true, new FakePlace("inventory", 4, List.of("minecraft:shulker_box")), pack)));
        InventorySync.Read read = InventorySync.toRead(fake);
        h.assertTrue(read.live() && read.version() == 41 && read.serverId().equals("Moongrove") && read.areas().size() == 2, "the records' fields: " + read);
        InventorySync.ReadArea bp = read.areas().get(1);
        h.assertTrue(bp.kind().equals("BACKPACK") && !bp.addable() && bp.placeArea().equals("inventory") && bp.placeSlot() == 4 && bp.inside().equals(List.of("minecraft:shulker_box")),
                "a backpack's kind and place: " + bp);
        h.assertTrue(read.areas().get(0).slots().get(7).locked() && "create:brass_ingot".equals(read.areas().get(0).slots().get(12).missing()), "locks and missing items");

        long now = System.currentTimeMillis();
        String marker = "i" + Integer.toHexString(new java.util.Random().nextInt());
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            InventorySync.useItemsForTests(null);
            WebActions.Answer none = WebActions.handle(server, action("a0-" + marker, now, "inv_read", ",\"player\":\"" + vexa + "\""));
            h.assertTrue(none != null && !none.ok() && none.message().contains("BSP-InventorySync 0.7.0"), "without BSP-InventorySync: said plainly");
            List<UUID> asked = new ArrayList<>();
            InventorySync.useItemsForTests(player -> {
                asked.add(player);
                return CompletableFuture.completedFuture(player.equals(vexa) ? Optional.of(read) : Optional.empty());
            });
            WebActions.Answer bad = WebActions.handle(server, action("a1-" + marker, now, "inv_read", ",\"player\":\"nobody\""));
            h.assertTrue(bad != null && !bad.ok() && bad.later() == null && asked.isEmpty(), "not a player id: refused before BSP-InventorySync");
            WebActions.Answer got = WebActions.handle(server, action("a2-" + marker, now, "inv_read", ",\"player\":\"" + vexa + "\"")).later().get();
            JsonObject d = got.data().getAsJsonObject();
            h.assertTrue(got.ok() && d.get("live").getAsBoolean() && d.get("version").getAsLong() == 41 && d.get("server").getAsString().equals("Moongrove"), "the read's state: " + d);
            JsonObject area = d.getAsJsonArray("areas").get(0).getAsJsonObject();
            JsonArray slots = area.getAsJsonArray("slots");
            h.assertTrue(area.get("size").getAsInt() == 36 && slots.size() == 3, "36 slots, only the 3 filled ones listed: " + slots.size());
            h.assertTrue(slots.get(0).getAsJsonObject().get("i").getAsInt() == 0 && slots.get(0).getAsJsonObject().has("h"), "each with its slot and hash");
            h.assertTrue(slots.get(1).getAsJsonObject().get("lk").getAsBoolean() && slots.get(2).getAsJsonObject().get("miss").getAsString().equals("create:brass_ingot"), "locked and missing kept");
            JsonObject back = d.getAsJsonArray("areas").get(1).getAsJsonObject();
            h.assertTrue(!back.get("add").getAsBoolean() && back.get("rem").getAsBoolean() && back.getAsJsonObject("place").get("slot").getAsInt() == 4
                    && back.getAsJsonArray("slots").get(0).getAsJsonObject().get("n").getAsInt() == 640, "a backpack: removals only, its place, counts above 64: " + back);
            WebActions.Answer nobody = WebActions.handle(server, action("a3-" + marker, now, "inv_read", ",\"player\":\"" + UUID.randomUUID() + "\"")).later().get();
            h.assertTrue(!nobody.ok() && nobody.message().contains("Nothing has been saved"), "never saved: said so: " + nobody.message());
            InventorySync.useItemsForTests(player -> CompletableFuture.failedFuture(new IllegalStateException("the store failed")));
            WebActions.Answer failed = WebActions.handle(server, action("a4-" + marker, now, "inv_read", ",\"player\":\"" + vexa + "\"")).later().get();
            h.assertTrue(!failed.ok() && failed.message().contains("the store failed"), "a failed read says why: " + failed.message());
        } finally {
            InventorySync.useItemsForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    /** A tiny stand-in for one player's items held by BSP-InventorySync: an inventory of 36 slots and a version. */
    private static final class Stash implements InventorySync.Items, InventorySync.Edits {
        final ItemStack[] inv = new ItemStack[36];
        long version = 10;
        final List<String> calls = new ArrayList<>();
        String nextOutcome = "OK";
        /** Offline: as BSP-InventorySync, an add changes the snapshot only while it is still the version expected. */
        boolean offline;

        Stash() {
            java.util.Arrays.fill(inv, ItemStack.EMPTY);
        }

        @Override
        public CompletableFuture<Optional<InventorySync.Read>> read(UUID player) {
            List<InventorySync.ReadSlot> slots = new ArrayList<>();
            for (int i = 0; i < 36; i++) {
                slots.add(new InventorySync.ReadSlot(i, inv[i].copy(), null, inv[i].is(Items.TOTEM_OF_UNDYING)));
            }
            return CompletableFuture.completedFuture(Optional.of(new InventorySync.Read(false, "Moongrove", false, version, 1, List.of(
                    new InventorySync.ReadArea("inventory", "INVENTORY", true, true, null, -1, List.of(), slots),
                    new InventorySync.ReadArea("armor", "ARMOR", false, true, null, -1, List.of(), List.of(new InventorySync.ReadSlot(0, new ItemStack(Items.IRON_BOOTS), null, false)))))));
        }

        @Override
        public CompletableFuture<InventorySync.EditOut> add(UUID player, long expectedVersion, String area, ItemStack stack, String by) {
            calls.add("add " + area + " " + stack.getCount() + " " + stack.getItem() + " v" + expectedVersion + " by " + by + (stack.hasTag() ? " " + stack.getTag() : ""));
            if (!nextOutcome.equals("OK")) {
                return CompletableFuture.completedFuture(new InventorySync.EditOut(nextOutcome, "Vexa is online on Frostwarf.", 0, 0, "Frostwarf"));
            }
            if (offline && expectedVersion != version) {
                return CompletableFuture.completedFuture(new InventorySync.EditOut("CHANGED", "Read again.", 0, 0, "Moongrove"));
            }
            int left = stack.getCount();
            for (int i = 0; i < 36 && left > 0; i++) {
                if (inv[i].isEmpty() || ItemStack.isSameItemSameTags(inv[i], stack)) {
                    int put = Math.min(left, 64 - inv[i].getCount());
                    inv[i] = stack.copyWithCount(inv[i].getCount() + put);
                    left -= put;
                }
            }
            version++;
            return CompletableFuture.completedFuture(new InventorySync.EditOut("OK", "Added.", version, version - 1, "Moongrove"));
        }

        @Override
        public CompletableFuture<InventorySync.EditOut> remove(UUID player, long expectedVersion, String area, int slot, ItemStack expected, int count, String by) {
            calls.add("remove " + area + " " + slot + " " + count + " " + expected.getItem() + " v" + expectedVersion);
            if (!nextOutcome.equals("OK")) {
                return CompletableFuture.completedFuture(new InventorySync.EditOut(nextOutcome, "Vexa is online on Frostwarf.", 0, 0, "Frostwarf"));
            }
            if (area.equals("armor")) {
                version++;
                return CompletableFuture.completedFuture(new InventorySync.EditOut("OK", "Removed.", version, version - 1, "Moongrove"));
            }
            if (expectedVersion != version || !ItemStack.isSameItemSameTags(inv[slot], expected)) {
                return CompletableFuture.completedFuture(new InventorySync.EditOut("CHANGED", "Read again.", 0, 0, "Moongrove"));
            }
            inv[slot] = inv[slot].getCount() <= count ? ItemStack.EMPTY : inv[slot].copyWithCount(inv[slot].getCount() - count);
            version++;
            return CompletableFuture.completedFuture(new InventorySync.EditOut("OK", "Removed.", version, version - 1, "Moongrove"));
        }
    }

    private static String act(String id, long at, String rank, boolean power, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Mod\",\"rank\":\"" + rank + "\"" + (power ? ",\"items\":true" : "")
                + ",\"server\":\"" + Storage.currentServerId() + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    @GameTest(template = EMPTY)
    public void items_are_added_removed_and_taken_back_by_those_allowed_with_a_gold_line(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        net.minecraft.server.level.ServerPlayer vexa = TestPlayers.mockServerPlayer(h);
        UUID id = vexa.getUUID();
        Stash stash = new Stash();
        stash.inv[0] = sword();
        stash.inv[5] = new ItemStack(Items.TNT, 16);
        stash.inv[7] = new ItemStack(Items.TOTEM_OF_UNDYING);
        List<Component> shown = new ArrayList<>();
        long now = System.currentTimeMillis();
        String m = "e" + Integer.toHexString(new java.util.Random().nextInt());
        String who = ",\"player\":\"" + id + "\",\"name\":\"Vexa_" + m + "\"";
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        InventorySync.useItemsForTests(stash);
        InventorySync.useEditsForTests(stash);
        com.mrgregles.bsp_server_utilities.web.WebItemEdits.useForTests((s, u) -> u.equals(id) ? vexa : null, (p, c) -> shown.add(c));
        try {
            WebActions.Answer no = WebActions.handle(server, act("x0-" + m, now, "MODERATOR", false, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"count\":64"));
            h.assertTrue(!no.ok() && no.message().contains("items power") && stash.calls.isEmpty(), "a Moderator without the items power: refused before BSP-InventorySync: " + no.message());
            WebActions.Answer bad = WebActions.handle(server, act("x1-" + m, now, "ADMIN", false, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:not_a_thing\",\"count\":1"));
            h.assertTrue(!bad.ok() && bad.message().contains("No item called"), "an unknown item: " + bad.message());
            WebActions.Answer tags = WebActions.handle(server, act("x2-" + m, now, "ADMIN", false, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"snbt\":\"{oops\",\"count\":1"));
            h.assertTrue(!tags.ok() && tags.message().contains("could not be read"), "unreadable item data: " + tags.message());
            WebActions.Answer many = WebActions.handle(server, act("x3-" + m, now, "ADMIN", false, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"count\":6401"));
            h.assertTrue(!many.ok() && stash.calls.isEmpty(), "over 100 stacks of 64: refused here");

            WebActions.Answer added = WebActions.handle(server, act("x4-" + m, now, "MODERATOR", true, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"count\":100,\"version\":10")).later().get();
            h.assertTrue(added.ok() && stash.calls.get(0).startsWith("add inventory 100 diamond v10 by Ana_Mod"), "a Moderator with the items power adds, through BSP-InventorySync: " + stash.calls);
            JsonObject undo = added.data().getAsJsonObject().getAsJsonObject("undo");
            h.assertTrue(undo.get("action").getAsString().equals("inv_take") && undo.get("count").getAsInt() == 100 && undo.get("hash").getAsString().equals(WebItems.hash(new ItemStack(Items.DIAMOND))),
                    "the answer carries its UNDO: " + undo);
            h.assertTrue(shown.size() == 1 && shown.get(0).getString().contains("100") && shown.get(0).getStyle().getColor() != null
                    && shown.get(0).getStyle().getColor().equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.GOLD)), "a gold line to the player: " + shown);
            h.assertTrue(ActionLog.recent(server, 30, false, List.of()).stream().anyMatch(e -> e.actor().equals("Ana_Mod (panel)") && e.text().contains("items add Vexa_" + m + " 100 minecraft:diamond into inventory")),
                    "in the Action Log");

            String tnt = WebItems.hash(new ItemStack(Items.TNT));
            WebActions.Answer wrong = WebActions.handle(server, act("x5-" + m, now, "ADMIN", false, "inv_remove", who + ",\"area\":\"inventory\",\"slot\":5,\"hash\":\"" + WebItems.hash(new ItemStack(Items.EMERALD)) + "\",\"count\":16")).later().get();
            h.assertTrue(!wrong.ok() && wrong.message().contains("changed since you looked") && stash.calls.size() == 1, "the slot holds something else: untouched: " + wrong.message());
            WebActions.Answer totem = WebActions.handle(server, act("x6-" + m, now, "ADMIN", false, "inv_remove", who + ",\"area\":\"inventory\",\"slot\":7,\"hash\":\"" + WebItems.hash(new ItemStack(Items.TOTEM_OF_UNDYING)) + "\",\"count\":1")).later().get();
            h.assertTrue(!totem.ok() && totem.message().contains("Shatter Totem") && stash.calls.size() == 1, "a locked slot: never moved");
            WebActions.Answer rem = WebActions.handle(server, act("x7-" + m, now, "OWNER", false, "inv_remove", who + ",\"area\":\"inventory\",\"slot\":5,\"hash\":\"" + tnt + "\",\"count\":99")).later().get();
            h.assertTrue(rem.ok() && stash.calls.get(1).equals("remove inventory 5 16 tnt v11") && stash.inv[5].isEmpty(), "removed, at most what is there, with the fresh version: " + stash.calls);
            JsonObject back = rem.data().getAsJsonObject().getAsJsonObject("undo");
            h.assertTrue(back.get("action").getAsString().equals("inv_add") && back.get("item").getAsString().equals("minecraft:tnt") && back.get("count").getAsInt() == 16, "its UNDO adds it back: " + back);
            h.assertTrue(shown.size() == 2 && shown.get(1).getString().contains("16"), "and the line: " + shown);
            WebActions.Answer boots = WebActions.handle(server, act("x8-" + m, now, "ADMIN", false, "inv_remove", who + ",\"area\":\"armor\",\"slot\":0,\"hash\":\"" + WebItems.hash(new ItemStack(Items.IRON_BOOTS)) + "\",\"count\":1")).later().get();
            h.assertTrue(boots.data().getAsJsonObject().getAsJsonObject("undo").get("area").getAsString().equals("inventory"), "armour taken off comes back into the inventory: " + boots.data());

            WebActions.Answer taken = WebActions.handle(server, act("x9-" + m, now, "ADMIN", false, "inv_take", who + ",\"area\":\"inventory\",\"hash\":\"" + undo.get("hash").getAsString() + "\",\"count\":100")).later().get();
            int diamonds = java.util.Arrays.stream(stash.inv).filter(x -> x.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
            h.assertTrue(taken.ok() && diamonds == 0 && taken.message().contains("all 100"), "the UNDO of the add takes all 100 back from the two stacks it filled: " + taken.message() + " " + diamonds);
            WebActions.Answer none = WebActions.handle(server, act("xa-" + m, now, "ADMIN", false, "inv_take", who + ",\"area\":\"inventory\",\"hash\":\"" + undo.get("hash").getAsString() + "\",\"count\":5")).later().get();
            h.assertTrue(!none.ok() && none.message().contains("nothing was taken back"), "taken already: said so");

            stash.nextOutcome = "ONLINE";
            WebActions.Answer away = WebActions.handle(server, act("xb-" + m, now, "ADMIN", false, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"count\":1")).later().get();
            h.assertTrue(!away.ok() && away.data().getAsJsonObject().get("outcome").getAsString().equals("ONLINE") && away.data().getAsJsonObject().get("serverId").getAsString().equals("Frostwarf"),
                    "online elsewhere: the server to ask is in the answer: " + away.data());
        } finally {
            InventorySync.useItemsForTests(null);
            InventorySync.useEditsForTests(null);
            com.mrgregles.bsp_server_utilities.web.WebItemEdits.useForTests(null, null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void deep_item_data_is_refused_and_undo_works_offline_and_says_why_it_was_refused(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        UUID id = UUID.randomUUID();
        Stash stash = new Stash();
        stash.offline = true;
        stash.inv[3] = new ItemStack(Items.TNT, 8);
        long now = System.currentTimeMillis();
        String m = "f" + Integer.toHexString(new java.util.Random().nextInt());
        String who = ",\"player\":\"" + id + "\",\"name\":\"Vexa_" + m + "\"";
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        InventorySync.useItemsForTests(stash);
        InventorySync.useEditsForTests(stash);
        com.mrgregles.bsp_server_utilities.web.WebItemEdits.useForTests((s, u) -> null, (p, c) -> { });
        try {
            // nesting that would overflow the server thread's stack (or that the game's reader refuses at 512): refused before reading (audit)
            String deep = "{a:" + "[".repeat(5000) + "]".repeat(5000) + "}";
            WebActions.Answer d = WebActions.handle(server, act("y0-" + m, now, "ADMIN", false, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"count\":1,\"snbt\":\"" + deep + "\""));
            h.assertTrue(!d.ok() && d.message().contains("nested too deeply") && stash.calls.isEmpty(), "deep item data: refused: " + d.message());
            h.assertTrue(com.mrgregles.bsp_server_utilities.web.WebItemEdits.depth("{a:'[[[[[[',b:\"{{{{\\\"{{\",c:[{d:1}]}") == 3, "brackets inside quotes do not count");
            String big = "{x:\\\"" + "a".repeat(70_000) + "\\\"}";
            WebActions.Answer typed = WebActions.handle(server, act("y1-" + m, now, "ADMIN", false, "inv_add", who + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"count\":1,\"snbt\":\"" + big + "\""));
            h.assertTrue(!typed.ok() && typed.message().contains("too long"), "typed item data over 64 KB: refused: " + typed.message());

            // UNDO of a removal from an offline player: the add names the version the removal was saved as (audit)
            WebActions.Answer rem = WebActions.handle(server, act("y2-" + m, now, "ADMIN", false, "inv_remove", who + ",\"area\":\"inventory\",\"slot\":3,\"hash\":\""
                    + WebItems.hash(new ItemStack(Items.TNT)) + "\",\"count\":8")).later().get();
            JsonObject back = rem.data().getAsJsonObject().getAsJsonObject("undo");
            h.assertTrue(rem.ok() && back.get("version").getAsLong() == stash.version, "its UNDO carries the saved version: " + back);
            back.addProperty("undo", true);
            StringBuilder extra = new StringBuilder(who);
            back.entrySet().forEach(e -> extra.append(",\"").append(e.getKey()).append("\":").append(e.getValue()));
            WebActions.Answer undone = WebActions.handle(server, act("y3-" + m, now, "ADMIN", false, "inv_add", extra.toString())).later().get();
            h.assertTrue(undone.ok() && stash.inv[0].is(Items.TNT) && stash.inv[0].getCount() == 8, "the UNDO puts it back offline: " + undone.message() + " " + stash.calls);
            WebActions.Answer bigUndo = WebActions.handle(server, act("y4-" + m, now, "ADMIN", false, "inv_add", who + ",\"undo\":true,\"version\":" + stash.version
                    + ",\"area\":\"inventory\",\"item\":\"minecraft:diamond\",\"count\":1,\"snbt\":\"" + big + "\"")).later().get();
            h.assertTrue(bigUndo.ok(), "an UNDO may carry larger data (the item removed): " + bigUndo.message());

            // the UNDO of an add refused by BSP-InventorySync says why, with the server to ask, not "gone already" (audit)
            stash.nextOutcome = "ONLINE";
            WebActions.Answer take = WebActions.handle(server, act("y5-" + m, now, "ADMIN", false, "inv_take", who + ",\"area\":\"inventory\",\"hash\":\""
                    + WebItems.hash(new ItemStack(Items.TNT)) + "\",\"count\":8")).later().get();
            h.assertTrue(!take.ok() && take.data() != null && take.data().getAsJsonObject().get("outcome").getAsString().equals("ONLINE")
                    && take.data().getAsJsonObject().get("serverId").getAsString().equals("Frostwarf"), "refused: the outcome and server in the answer: " + take.message() + " " + take.data());
        } finally {
            InventorySync.useItemsForTests(null);
            InventorySync.useEditsForTests(null);
            com.mrgregles.bsp_server_utilities.web.WebItemEdits.useForTests(null, null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    public record FakeResult(FakeOutcome outcome, String message, long version, long pinnedVersion, String serverId) {
    }

    public enum FakeOutcome { OK, NO_ROOM }

    @GameTest(template = EMPTY)
    public void an_edit_result_is_read_through_its_accessors(GameTestHelper h) {
        InventorySync.EditOut o = InventorySync.toEditOut(new FakeResult(FakeOutcome.NO_ROOM, "It did not fit.", 0, 0, "Moongrove"));
        h.assertTrue(o.outcome().equals("NO_ROOM") && !o.ok() && o.message().equals("It did not fit.") && o.serverId().equals("Moongrove"), "read: " + o);
        h.assertTrue(InventorySync.toEditOut(new FakeResult(FakeOutcome.OK, "", 12, 11, "Moongrove")).ok(), "OK is ok");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void item_pictures_are_taken_only_from_an_admin_asked_whole_and_in_order(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        net.minecraft.server.level.ServerPlayer admin = TestPlayers.mockServerPlayer(h), player = TestPlayers.mockServerPlayer(h);
        List<Object> asked = new ArrayList<>();
        List<String> said = new ArrayList<>();
        com.mrgregles.bsp_server_utilities.web.WebItemsUpload.useForTests(null, (p, packet) -> asked.add(packet), (p, c) -> said.add(c.getString()));
        com.mrgregles.bsp_server_utilities.storage.MemoryBackend db = new com.mrgregles.bsp_server_utilities.storage.MemoryBackend();
        Storage store = Storage.startWith(server, db, "alpha");
        store.drain();
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            h.assertTrue(!com.mrgregles.bsp_server_utilities.web.WebItemsUpload.ask(player) && asked.isEmpty(), "not an admin: nothing asked");
            Storage.useForTests(null);
            h.assertTrue(!com.mrgregles.bsp_server_utilities.web.WebItemsUpload.ask(admin) && said.get(said.size() - 1).contains("shared database"), "no shared storage: said so");
            Storage.useForTests(store);
            h.assertTrue(com.mrgregles.bsp_server_utilities.web.WebItemsUpload.ask(admin) && asked.size() == 1, "an admin is asked for the pictures");
            String ticket = ((com.mrgregles.bsp_server_utilities.network.IconsAskPacket) asked.get(0)).ticket();

            byte[] zip = new byte[75_000];
            new java.util.Random(7).nextBytes(zip);
            String sha = com.mrgregles.bsp_server_utilities.web.WebItemsUpload.sha256(zip);
            java.util.function.IntFunction<com.mrgregles.bsp_server_utilities.network.IconsPartPacket> piece = i -> new com.mrgregles.bsp_server_utilities.network.IconsPartPacket(ticket, i,
                    java.util.Arrays.copyOfRange(zip, i * 30_000, Math.min(zip.length, (i + 1) * 30_000)));
            // someone else, with the admin's ticket: ignored
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.begin(player, new com.mrgregles.bsp_server_utilities.network.IconsBeginPacket(ticket, zip.length, sha, 3, 1, 9));
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.part(player, piece.apply(0));
            store.drain();
            h.assertTrue(db.webItems.isEmpty(), "another player's pieces are ignored");
            // out of order: refused, and the ticket is gone
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.begin(admin, new com.mrgregles.bsp_server_utilities.network.IconsBeginPacket(ticket, zip.length, sha, 3, 1, 9));
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.part(admin, piece.apply(1));
            h.assertTrue(said.get(said.size() - 1).contains("out of order"), "a piece out of order: refused: " + said);
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.begin(admin, new com.mrgregles.bsp_server_utilities.network.IconsBeginPacket(ticket, zip.length, sha, 3, 1, 9));
            for (int i = 0; i < 3; i++) {
                com.mrgregles.bsp_server_utilities.web.WebItemsUpload.part(admin, piece.apply(i));
            }
            store.drain();
            h.assertTrue(db.webItems.isEmpty(), "after a refusal the old ticket takes nothing");

            // asked again: a whole export is stored in 512 KB parts with its row
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.ask(admin);
            String t2 = ((com.mrgregles.bsp_server_utilities.network.IconsAskPacket) asked.get(asked.size() - 1)).ticket();
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.begin(admin, new com.mrgregles.bsp_server_utilities.network.IconsBeginPacket(t2, zip.length, sha, 3, 1, 9));
            for (int i = 0; i < 3; i++) {
                byte[] d = java.util.Arrays.copyOfRange(zip, i * 30_000, Math.min(zip.length, (i + 1) * 30_000));
                com.mrgregles.bsp_server_utilities.web.WebItemsUpload.part(admin, new com.mrgregles.bsp_server_utilities.network.IconsPartPacket(t2, i, d));
            }
            store.drain();
            h.assertTrue(db.webItems.size() == 1 && db.webItems.get(0).items() == 3 && db.webItems.get(0).by().equals(admin.getGameProfile().getName())
                    && db.webItems.get(0).sha256().equals(sha) && db.webItems.get(0).server().equals("alpha"), "the export's row: " + db.webItems);
            java.io.ByteArrayOutputStream back = new java.io.ByteArrayOutputStream();
            db.webItemParts.forEach(back::writeBytes);
            h.assertTrue(java.util.Arrays.equals(back.toByteArray(), zip), "and its bytes, whole");

            // a corrupted export: not stored
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.ask(admin);
            String t3 = ((com.mrgregles.bsp_server_utilities.network.IconsAskPacket) asked.get(asked.size() - 1)).ticket();
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.begin(admin, new com.mrgregles.bsp_server_utilities.network.IconsBeginPacket(t3, 10, sha, 3, 1, 9));
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.part(admin, new com.mrgregles.bsp_server_utilities.network.IconsPartPacket(t3, 0, new byte[10]));
            store.drain();
            h.assertTrue(db.webItems.size() == 1 && said.get(said.size() - 1).contains("did not arrive whole"), "a wrong SHA-256: not stored: " + said);
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.ask(admin);
            String t4 = ((com.mrgregles.bsp_server_utilities.network.IconsAskPacket) asked.get(asked.size() - 1)).ticket();
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.begin(admin, new com.mrgregles.bsp_server_utilities.network.IconsBeginPacket(t4, com.mrgregles.bsp_server_utilities.web.WebItemsUpload.MAX_SIZE + 1, sha, 3, 1, 9));
            h.assertTrue(said.get(said.size() - 1).contains("too big"), "too big: refused before any piece");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            Storage.useForTests(null);
            com.mrgregles.bsp_server_utilities.web.WebItemsUpload.useForTests(null, null, null);
        }
        h.succeed();
    }

    public record FakeGraveItems(List<FakeArea> areas, int xp) {
    }

    @GameTest(template = EMPTY)
    public void a_graves_items_are_read_through_inventory_sync(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        UUID vexa = UUID.randomUUID();
        List<FakeSlot> in = List.of(new FakeSlot(0, sword(), null, false), new FakeSlot(1, new ItemStack(Items.TOTEM_OF_UNDYING), null, true));
        FakeGraveItems fake = new FakeGraveItems(List.of(new FakeArea("grave", FakeKind.INVENTORY, false, false, null, in),
                new FakeArea("backpack/abc", FakeKind.BACKPACK, false, false, new FakePlace("grave", 0, List.of()), List.of(new FakeSlot(0, new ItemStack(Items.COBBLESTONE, 640), null, false)))), 23);
        InventorySync.GraveContents contents = InventorySync.toGraveContents(fake);
        h.assertTrue(contents.xp() == 23 && contents.areas().size() == 2 && contents.areas().get(1).placeArea().equals("grave"), "its record read: " + contents);
        long now = System.currentTimeMillis();
        String m = "g" + Integer.toHexString(new java.util.Random().nextInt());
        List<String> asked = new ArrayList<>();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            InventorySync.useForTests(new InventorySync.Graves() {
                @Override
                public CompletableFuture<JsonArray> graves(UUID player) {
                    return CompletableFuture.completedFuture(new JsonArray());
                }

                @Override
                public CompletableFuture<JsonObject> returnGrave(UUID player, String id, String by) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }
            });
            WebActions.Answer old = WebActions.handle(server, action("v0-" + m, now, "grave_items", ",\"player\":\"" + vexa + "\",\"grave\":\"3f9a2c1b\"")).later().get();
            h.assertTrue(!old.ok() && old.message().contains("cannot list"), "a BSP-InventorySync without it: said so: " + old.message());
            InventorySync.useForTests(new InventorySync.Graves() {
                @Override
                public CompletableFuture<JsonArray> graves(UUID player) {
                    return CompletableFuture.completedFuture(new JsonArray());
                }

                @Override
                public CompletableFuture<JsonObject> returnGrave(UUID player, String id, String by) {
                    return CompletableFuture.completedFuture(new JsonObject());
                }

                @Override
                public CompletableFuture<Optional<InventorySync.GraveContents>> graveItems(UUID player, String id) {
                    asked.add(id);
                    return CompletableFuture.completedFuture(id.equals("3f9a2c1b") ? Optional.of(contents) : Optional.empty());
                }
            });
            WebActions.Answer bad = WebActions.handle(server, action("v1-" + m, now, "grave_items", ",\"player\":\"" + vexa + "\",\"grave\":\"../x\""));
            h.assertTrue(!bad.ok() && bad.later() == null && asked.isEmpty(), "not a grave id: refused first");
            WebActions.Answer got = WebActions.handle(server, action("v2-" + m, now, "grave_items", ",\"player\":\"" + vexa + "\",\"grave\":\"3f9a2c1b\"")).later().get();
            JsonObject d = got.data().getAsJsonObject();
            JsonArray areas = d.getAsJsonArray("areas");
            h.assertTrue(got.ok() && d.get("xp").getAsInt() == 23 && areas.size() == 2, "the grave's areas and XP: " + d);
            JsonObject grave = areas.get(0).getAsJsonObject();
            h.assertTrue(grave.get("id").getAsString().equals("grave") && grave.getAsJsonArray("slots").size() == 2 && grave.getAsJsonArray("slots").get(1).getAsJsonObject().get("lk").getAsBoolean(),
                    "its slots, a totem locked: " + grave);
            h.assertTrue(areas.get(1).getAsJsonObject().getAsJsonObject("place").get("area").getAsString().equals("grave"), "a backpack inside it, with its place");
            WebActions.Answer none = WebActions.handle(server, action("v3-" + m, now, "grave_items", ",\"player\":\"" + vexa + "\",\"grave\":\"b71e04d9\"")).later().get();
            h.assertTrue(!none.ok() && none.message().contains("empty"), "no such grave: said so");
        } finally {
            InventorySync.useForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }
}
