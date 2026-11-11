package com.mrgregles.bsp_server_utilities.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.IconsBeginPacket;
import com.mrgregles.bsp_server_utilities.network.IconsPartPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.web.WebItemsUpload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The item pictures for the staff web panel, drawn by an admin's own game (SU-41; D271, D340, D373). Asked by the server
 * after {@code /su icons}: every item of every creative tab (and every other item, under "Other") drawn at 32 pixels into
 * sheets of 64 x 64, a few hundred a frame so the game keeps running; then a zip of {@code items.json} (each item's id, tags,
 * English name and tab), {@code names.json} (English for item keys, enchantments and effects) and {@code sheet<n>.png},
 * sent back in pieces of {@link WebItemsUpload#PIECE} bytes a few a tick. Progress shows on the action bar.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class IconExport {
    public static final int CELL = 32, SHEET = 2048, PER_ROW = SHEET / CELL, PER_SHEET = PER_ROW * PER_ROW, PER_FRAME = 256, PIECES_PER_TICK = 3;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private record Entry(ItemStack stack, String id, String nbt) {
    }

    private static final class Job {
        final String ticket;
        final List<Entry> entries = new ArrayList<>();
        final JsonArray tabs = new JsonArray();
        final List<byte[]> sheets = new ArrayList<>();
        int drawn;
        @Nullable
        TextureTarget target;
        @Nullable
        byte[] zip;
        int sent;

        Job(String ticket) {
            this.ticket = ticket;
        }
    }

    @Nullable
    private static Job job;

    private IconExport() {
    }

    private static void say(String key, String fallback, boolean bar, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatableWithFallback("message.bsp_server_utilities.icons." + key, fallback, args).withStyle(ChatFormatting.GOLD), bar);
        }
    }

    /** The server asked (after {@code /su icons}): collect the items now, draw them over the next frames. */
    public static void start(String ticket) {
        if (job != null) {
            say("busy", "The item pictures are already being made.", false);
            return;
        }
        try {
            Job j = new Job(ticket);
            collect(j);
            job = j;
            say("drawing", "Drawing item pictures: %s / %s", false, 0, j.entries.size());
        } catch (RuntimeException e) {
            BSPServerUtilities.LOGGER.warn("Item pictures for the staff panel: {}", e.toString());
            say("failed", "The item pictures could not be drawn: %s", false, e.toString());
        }
    }

    private static void collect(Job j) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            throw new IllegalStateException("not in a world");
        }
        CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                continue;
            }
            JsonArray items = new JsonArray();
            for (ItemStack st : tab.getDisplayItems()) {
                int i = add(j, seen, st);
                if (i >= 0) {
                    items.add(i);
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            JsonObject t = new JsonObject();
            t.addProperty("name", tab.getDisplayName().getString());
            t.addProperty("icon", Math.max(0, add(j, seen, tab.getIconItem())));
            t.add("items", items);
            j.tabs.add(t);
        }
        JsonArray other = new JsonArray(); // items in no tab (and the search tab's extras): their pictures for inventories
        for (ItemStack st : CreativeModeTabs.searchTab().getDisplayItems()) {
            int before = j.entries.size(), i = add(j, seen, st);
            if (i >= before) {
                other.add(i);
            }
        }
        for (Item it : ForgeRegistries.ITEMS) {
            int before = j.entries.size(), i = add(j, seen, new ItemStack(it));
            if (i >= before) {
                other.add(i);
            }
        }
        if (!other.isEmpty()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", "Other");
            t.addProperty("icon", other.get(0).getAsInt());
            t.add("items", other);
            j.tabs.add(t);
        }
    }

    /** The entry's index, an earlier one for the same item and tags, or -1 for nothing. */
    private static int add(Job j, Map<String, Integer> seen, ItemStack st) {
        if (st == null || st.isEmpty() || st.is(Items.AIR)) {
            return -1;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
        if (id == null) {
            return -1;
        }
        String nbt = st.getTag() == null ? "" : st.getTag().toString();
        String key = id + "|" + nbt;
        Integer at = seen.get(key);
        if (at != null) {
            return at;
        }
        j.entries.add(new Entry(st.copyWithCount(1), id.toString(), nbt));
        seen.put(key, j.entries.size() - 1);
        return j.entries.size() - 1;
    }

    @SubscribeEvent
    public static void onRender(TickEvent.RenderTickEvent e) {
        Job j = job;
        if (e.phase != TickEvent.Phase.END || j == null || j.zip != null) {
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            stop(j); // left the server while drawing: nothing to send it to (audit)
            return;
        }
        try {
            drawSome(j);
            if (j.drawn >= j.entries.size()) {
                finishSheet(j);
                pack(j);
            }
        } catch (Exception x) {
            stop(j);
            BSPServerUtilities.LOGGER.warn("Item pictures for the staff panel: {}", x.toString());
            say("failed", "The item pictures could not be drawn: %s", false, x.toString());
        }
    }

    private static void drawSome(Job j) {
        Minecraft mc = Minecraft.getInstance();
        if (j.target == null) {
            j.target = new TextureTarget(SHEET, SHEET, true, Minecraft.ON_OSX);
            j.target.setClearColor(0, 0, 0, 0);
            j.target.clear(Minecraft.ON_OSX);
        }
        int end = Math.min(j.entries.size(), j.drawn + PER_FRAME);
        end = Math.min(end, (j.drawn / PER_SHEET + 1) * PER_SHEET); // never past this sheet
        j.target.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, SHEET, SHEET, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack mv = RenderSystem.getModelViewStack();
        mv.pushPose();
        mv.setIdentity();
        mv.translate(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        Lighting.setupFor3DItems();
        try {
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            g.pose().scale(CELL / 16f, CELL / 16f, 1f);
            for (int i = j.drawn; i < end; i++) {
                int cell = i % PER_SHEET;
                try {
                    g.renderItem(j.entries.get(i).stack(), (cell % PER_ROW) * 16, (cell / PER_ROW) * 16);
                } catch (RuntimeException bad) {
                    // an item that cannot draw itself stays empty; the panel shows its letters
                }
            }
            g.flush();
        } finally {
            mv.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            mc.getMainRenderTarget().bindWrite(true);
        }
        j.drawn = end;
        if (j.drawn % PER_SHEET == 0 && j.drawn < j.entries.size()) {
            finishSheet(j);
        }
        say("drawing", "Drawing item pictures: %s / %s", true, j.drawn, j.entries.size());
    }

    /** The sheet so far as a PNG (cut to the rows used), and the target cleared for the next. */
    private static void finishSheet(Job j) throws RuntimeException {
        TextureTarget t = j.target;
        if (t == null) {
            return;
        }
        int onSheet = j.drawn - j.sheets.size() * PER_SHEET;
        if (onSheet <= 0) {
            return;
        }
        int rows = (onSheet + PER_ROW - 1) / PER_ROW;
        try (NativeImage full = new NativeImage(SHEET, SHEET, false); NativeImage cut = new NativeImage(SHEET, rows * CELL, false)) {
            RenderSystem.bindTexture(t.getColorTextureId());
            full.downloadTexture(0, false);
            full.flipY();
            full.copyRect(cut, 0, 0, 0, 0, SHEET, rows * CELL, false, false);
            j.sheets.add(cut.asByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        t.clear(Minecraft.ON_OSX);
    }

    private static void pack(Job j) throws java.io.IOException {
        stopTarget(j);
        JsonObject items = new JsonObject();
        items.addProperty("cell", CELL);
        items.addProperty("perRow", PER_ROW);
        items.addProperty("perSheet", PER_SHEET);
        Minecraft mc = Minecraft.getInstance();
        items.addProperty("by", mc.player == null ? "" : mc.player.getGameProfile().getName());
        items.addProperty("mods", ModList.get().size());
        JsonArray list = new JsonArray();
        for (Entry en : j.entries) {
            JsonObject o = new JsonObject();
            o.addProperty("id", en.id());
            if (!en.nbt().isEmpty() && en.nbt().length() <= 4096) {
                o.addProperty("nbt", en.nbt());
            }
            o.addProperty("name", en.stack().getHoverName().getString());
            if (en.stack().getTag() != null && en.stack().getTag().contains("Potion")) {
                o.addProperty("v", en.stack().getTag().getString("Potion"));
            }
            list.add(o);
        }
        items.add("items", list);
        items.add("tabs", j.tabs);
        JsonObject names = new JsonObject(), keys = new JsonObject(), ench = new JsonObject(), fx = new JsonObject();
        for (Item it : ForgeRegistries.ITEMS) {
            ItemStack st = new ItemStack(it);
            keys.addProperty(st.getDescriptionId(), st.getHoverName().getString());
        }
        j.entries.forEach(en -> keys.addProperty(en.stack().getDescriptionId(), Component.translatable(en.stack().getDescriptionId()).getString()));
        for (Map.Entry<net.minecraft.resources.ResourceKey<Enchantment>, Enchantment> en : ForgeRegistries.ENCHANTMENTS.getEntries()) {
            ench.addProperty(en.getKey().location().toString(), Component.translatable(en.getValue().getDescriptionId()).getString());
        }
        for (Map.Entry<net.minecraft.resources.ResourceKey<MobEffect>, MobEffect> en : ForgeRegistries.MOB_EFFECTS.getEntries()) {
            fx.addProperty(en.getKey().location().toString(), en.getValue().getDisplayName().getString());
        }
        names.add("keys", keys);
        names.add("ench", ench);
        names.add("effects", fx);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("items.json"));
            z.write(GSON.toJson(items).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("names.json"));
            z.write(GSON.toJson(names).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            for (int i = 0; i < j.sheets.size(); i++) {
                z.putNextEntry(new ZipEntry("sheet" + i + ".png"));
                z.write(j.sheets.get(i));
                z.closeEntry();
            }
        }
        j.sheets.clear();
        j.zip = out.toByteArray();
        if (j.zip.length > WebItemsUpload.MAX_SIZE) {
            stop(j);
            say("failed", "The item pictures could not be drawn: %s", false, "too big (" + j.zip.length / 1048576 + " MB)");
            return;
        }
        ServerUtilsNetwork.sendToServer(new IconsBeginPacket(j.ticket, j.zip.length, WebItemsUpload.sha256(j.zip), j.entries.size(), j.tabs.size(), ModList.get().size()));
        say("sending", "Sending to the staff panel: %s / %s pieces", false, 0, pieces(j));
    }

    private static int pieces(Job j) {
        return j.zip == null ? 0 : (j.zip.length + WebItemsUpload.PIECE - 1) / WebItemsUpload.PIECE;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        Job j = job;
        if (e.phase != TickEvent.Phase.END || j == null || j.zip == null) {
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            stop(j);
            return;
        }
        int total = pieces(j);
        for (int k = 0; k < PIECES_PER_TICK && j.sent < total; k++) {
            int from = j.sent * WebItemsUpload.PIECE;
            byte[] part = java.util.Arrays.copyOfRange(j.zip, from, Math.min(j.zip.length, from + WebItemsUpload.PIECE));
            ServerUtilsNetwork.sendToServer(new IconsPartPacket(j.ticket, j.sent, part));
            j.sent++;
        }
        say("sending", "Sending to the staff panel: %s / %s pieces", true, j.sent, total);
        if (j.sent >= total) {
            job = null; // the server says when it is stored
        }
    }

    private static void stopTarget(Job j) {
        if (j.target != null) {
            j.target.destroyBuffers();
            j.target = null;
        }
    }

    private static void stop(Job j) {
        stopTarget(j);
        if (job == j) {
            job = null;
        }
    }
}
