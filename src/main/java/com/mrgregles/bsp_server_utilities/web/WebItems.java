package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.compat.InventorySync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * A player's items as the staff panel draws them (SU-39, D256, D338): every area BSP-InventorySync read, with only the
 * filled slots listed. A dedicated server cannot turn a language key into English, so each item carries its
 * {@code key} (the game's description id) and the panel finds the words in the picture export (D340), or makes them
 * from the id until there is one. Each slot also carries {@code h}, a short hash of the stack without its count, which a
 * removal names (SU-40) so the item taken is the one staff saw.
 *
 * <p>Per slot: {@code i} the slot, {@code id} the item id, {@code n} the count, {@code key}, {@code name} (a renamed item's
 * own name), {@code ench} ([id, level] pairs, stored ones for a book), {@code dmg} and {@code max} (durability),
 * {@code lore} (plain lines), {@code v} (a potion's id, for its picture), {@code h}, {@code lk} (locked: a live Shatter
 * Totem in it), {@code miss} (a missing mod's item id) and {@code in} (what a shulker box or other block item holds).
 */
public final class WebItems {
    public static final int MAX_LORE = 8, MAX_LINE = 120, MAX_INSIDE = 54;

    private WebItems() {
    }

    /** The whole read as the answer's data. */
    public static JsonObject toJson(UUID player, InventorySync.Read r) {
        JsonObject o = new JsonObject();
        o.addProperty("player", player.toString());
        o.addProperty("online", r.online());
        o.addProperty("server", r.serverId());
        o.addProperty("live", r.live());
        o.addProperty("version", r.version());
        o.addProperty("savedAt", r.savedAt());
        JsonArray areas = new JsonArray();
        for (InventorySync.ReadArea a : r.areas()) {
            areas.add(area(a));
        }
        o.add("areas", areas);
        return o;
    }

    public static JsonObject area(InventorySync.ReadArea a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id());
        o.addProperty("kind", a.kind());
        o.addProperty("add", a.addable());
        o.addProperty("rem", a.removable());
        o.addProperty("size", a.slots().size());
        if (a.placeArea() != null) {
            JsonObject p = new JsonObject();
            p.addProperty("area", a.placeArea());
            p.addProperty("slot", a.placeSlot());
            JsonArray in = new JsonArray();
            a.inside().forEach(in::add);
            p.add("inside", in);
            o.add("place", p);
        }
        JsonArray slots = new JsonArray();
        for (InventorySync.ReadSlot s : a.slots()) {
            if (s.stack() == null || s.stack().isEmpty()) {
                continue;
            }
            JsonObject j = stack(s.stack(), true);
            j.addProperty("i", s.index());
            if (s.locked()) {
                j.addProperty("lk", true);
            }
            if (s.missing() != null) {
                j.addProperty("miss", s.missing());
            }
            slots.add(j);
        }
        o.add("slots", slots);
        return o;
    }

    /** One stack: what the panel shows of it; {@code full} adds the hash, durability, lore and contents. */
    public static JsonObject stack(ItemStack st, boolean full) {
        return stack(st, full, true);
    }

    /** As {@link #stack(ItemStack, boolean)}; {@code hash} false leaves out {@code h} (views that never edit: the market). */
    public static JsonObject stack(ItemStack st, boolean full, boolean hash) {
        JsonObject o = new JsonObject();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
        o.addProperty("id", id == null ? "minecraft:air" : id.toString());
        o.addProperty("n", st.getCount());
        o.addProperty("key", st.getDescriptionId());
        if (st.hasCustomHoverName()) {
            o.addProperty("name", clip(st.getHoverName().getString()));
        }
        CompoundTag tag = st.getTag();
        if (tag != null && tag.contains("Potion", Tag.TAG_STRING)) {
            o.addProperty("v", tag.getString("Potion"));
        }
        Map<Enchantment, Integer> ench = EnchantmentHelper.getEnchantments(st);
        if (!ench.isEmpty()) {
            JsonArray e = new JsonArray();
            ench.forEach((en, lvl) -> {
                ResourceLocation k = ForgeRegistries.ENCHANTMENTS.getKey(en);
                JsonArray pair = new JsonArray();
                pair.add(k == null ? "unknown" : k.toString());
                pair.add(lvl);
                e.add(pair);
            });
            o.add("ench", e);
        }
        if (!full) {
            return o;
        }
        if (hash) {
            o.addProperty("h", hash(st));
        }
        if (st.isDamageableItem()) {
            o.addProperty("dmg", st.getDamageValue());
            o.addProperty("max", st.getMaxDamage());
        }
        JsonArray lore = lore(tag);
        if (!lore.isEmpty()) {
            o.add("lore", lore);
        }
        JsonArray in = inside(tag);
        if (!in.isEmpty()) {
            o.add("in", in);
        }
        return o;
    }

    /**
     * The stack's hash without its count: the first 12 hex characters of the SHA-256 of its saved form with a count of 1.
     * The same item with the same tags hashes alike whatever the count (BSP-InventorySync compares a removal the same way).
     */
    public static String hash(ItemStack st) {
        ItemStack one = st.copyWithCount(1);
        String saved = one.save(new CompoundTag()).toString();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(saved.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonArray lore(@Nullable CompoundTag tag) {
        JsonArray out = new JsonArray();
        if (tag == null || !tag.contains("display", Tag.TAG_COMPOUND)) {
            return out;
        }
        ListTag lines = tag.getCompound("display").getList("Lore", Tag.TAG_STRING);
        for (int i = 0; i < lines.size() && out.size() < MAX_LORE; i++) {
            try {
                Component c = Component.Serializer.fromJson(lines.getString(i));
                if (c != null) {
                    out.add(clip(c.getString()));
                }
            } catch (RuntimeException bad) {
                out.add(clip(lines.getString(i)));
            }
        }
        return out;
    }

    /** What a shulker box (or any block item keeping its block's items) holds, view only (D338). */
    private static JsonArray inside(@Nullable CompoundTag tag) {
        JsonArray out = new JsonArray();
        if (tag == null || !tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
            return out;
        }
        ListTag items = tag.getCompound("BlockEntityTag").getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size() && out.size() < MAX_INSIDE; i++) {
            ItemStack in = ItemStack.of(items.getCompound(i));
            if (!in.isEmpty()) {
                JsonObject j = stack(in, false);
                j.addProperty("i", items.getCompound(i).getByte("Slot") & 255);
                out.add(j);
            }
        }
        return out;
    }

    private static String clip(String s) {
        return s.length() > MAX_LINE ? s.substring(0, MAX_LINE) : s;
    }
}
