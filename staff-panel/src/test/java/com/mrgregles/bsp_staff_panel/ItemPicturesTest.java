package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.items.ItemPictures;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The item pictures (SU-41): the newest export read, checked and unpacked; a damaged or odd one refused. */
class ItemPicturesTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};

    static byte[] zip(String items, String names, boolean sheet, String extra) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            if (items != null) {
                z.putNextEntry(new ZipEntry("items.json"));
                z.write(items.getBytes(StandardCharsets.UTF_8));
            }
            if (names != null) {
                z.putNextEntry(new ZipEntry("names.json"));
                z.write(names.getBytes(StandardCharsets.UTF_8));
            }
            if (sheet) {
                z.putNextEntry(new ZipEntry("sheet0.png"));
                z.write(PNG);
            }
            if (extra != null) {
                z.putNextEntry(new ZipEntry(extra));
                z.write(new byte[]{1, 2, 3});
            }
        }
        return out.toByteArray();
    }

    static final String ITEMS = "{\"cell\":32,\"perRow\":64,\"perSheet\":4096,\"items\":[{\"id\":\"minecraft:diamond\",\"name\":\"Diamond\"},{\"id\":\"create:brass_ingot\",\"name\":\"Brass Ingot\"}],"
            + "\"tabs\":[{\"name\":\"Ingredients\",\"icon\":0,\"items\":[0,1]}]}";
    static final String NAMES = "{\"keys\":{\"item.minecraft.diamond\":\"Diamond\"},\"ench\":{\"minecraft:sharpness\":\"Sharpness\"},\"effects\":{}}";

    static void put(MemoryStore m, long id, byte[] zip, String sha) {
        m.exports.add(new PanelStore.ItemsExport(id, id, "Juniper_K", "BSP-Spawn", 2, 1, 265, zip.length, 1, sha));
        m.exportBytes.put(id, zip);
    }

    @Test
    void the_newest_export_is_read_once_checked_and_unpacked() throws Exception {
        MemoryStore m = new MemoryStore();
        ItemPictures p = new ItemPictures(m);
        p.refresh();
        assertNull(p.current(), "nothing exported yet");
        byte[] z = zip(ITEMS, NAMES, true, "../../evil.sh");
        put(m, 100, z, ItemPictures.sha256(z));
        p.refresh();
        ItemPictures.Loaded l = p.current();
        assertNotNull(l);
        JsonObject cat = JsonParser.parseString(l.catalog()).getAsJsonObject();
        assertEquals(100, cat.get("id").getAsLong());
        assertEquals(2, cat.getAsJsonArray("items").size());
        assertEquals("Sharpness", cat.getAsJsonObject("names").getAsJsonObject("ench").get("minecraft:sharpness").getAsString());
        assertEquals(1, l.sheets().size(), "only items.json, names.json and the sheets are read; other names are skipped");

        byte[] bad = zip(ITEMS, NAMES, true, null);
        put(m, 200, bad, "0".repeat(64));
        p.refresh();
        assertEquals(100, p.current().export().id(), "a newer export that is not whole: the one held stays");
        assertTrue(p.problem().contains("not whole"), p.problem());

        byte[] noNames = zip(ITEMS, null, true, null);
        put(m, 300, noNames, ItemPictures.sha256(noNames));
        p.refresh();
        assertTrue(p.problem().contains("missing"), p.problem());
        byte[] good = zip(ITEMS, NAMES, true, null);
        put(m, 400, good, ItemPictures.sha256(good));
        p.refresh();
        assertEquals(400, p.current().export().id(), "the next good one is taken");
        assertEquals("", p.problem());
    }

    @Test
    void a_sheet_that_is_not_a_png_or_parts_that_are_gone_are_refused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("items.json"));
            z.write(ITEMS.getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("names.json"));
            z.write(NAMES.getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("sheet0.png"));
            z.write("<script>".getBytes(StandardCharsets.UTF_8));
        }
        byte[] b = out.toByteArray();
        PanelStore.ItemsExport e = new PanelStore.ItemsExport(1, 1, "x", "y", 2, 1, 1, b.length, 1, ItemPictures.sha256(b));
        Exception x = null;
        try {
            ItemPictures.unpack(e, b);
        } catch (java.io.IOException io) {
            x = io;
        }
        assertNotNull(x);
        assertTrue(x.getMessage().contains("not a PNG"), x.getMessage());
        Exception gone = null;
        try {
            ItemPictures.unpack(e, null);
        } catch (java.io.IOException io) {
            gone = io;
        }
        assertNotNull(gone);
        assertFalse(gone.getMessage().isEmpty());
    }

    @Test
    void an_export_that_failed_is_not_read_again_and_the_limits_hold() throws Exception {
        MemoryStore m = new MemoryStore();
        ItemPictures p = new ItemPictures(m);
        byte[] bad = zip(ITEMS, NAMES, true, null);
        put(m, 10, bad, "0".repeat(64));
        p.refresh();
        assertTrue(p.problem().contains("not whole"), p.problem());
        m.exportBytes.remove(10L); // read again, it would now say the pictures are gone
        p.refresh();
        p.refresh();
        assertTrue(p.problem().contains("not whole"), "a broken export is read once, not every minute (audit): " + p.problem());
        byte[] good = zip(ITEMS, NAMES, true, null);
        put(m, 11, good, ItemPictures.sha256(good));
        p.refresh();
        assertEquals(11, p.current().export().id(), "a newer one is read");

        // one file over 24 MB (zeros pack small): refused while reading, before it is held whole
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("items.json"));
            z.write(new byte[ItemPictures.MAX_ENTRY + 1]);
        }
        byte[] huge = out.toByteArray();
        PanelStore.ItemsExport e = new PanelStore.ItemsExport(12, 12, "x", "y", 2, 1, 1, huge.length, 1, ItemPictures.sha256(huge));
        java.io.IOException x = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> ItemPictures.unpack(e, huge));
        assertTrue(x.getMessage().contains("larger than"), x.getMessage());
        // a sheet past the last one allowed
        ByteArrayOutputStream o2 = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o2)) {
            z.putNextEntry(new ZipEntry("sheet" + ItemPictures.MAX_SHEETS + ".png"));
            z.write(PNG);
        }
        byte[] many = o2.toByteArray();
        PanelStore.ItemsExport e2 = new PanelStore.ItemsExport(13, 13, "x", "y", 2, 1, 1, many.length, 1, ItemPictures.sha256(many));
        assertThrows(java.io.IOException.class, () -> ItemPictures.unpack(e2, many), "sheet " + ItemPictures.MAX_SHEETS);
        // a row saying it is larger than the game server ever stores: not fetched at all
        put(m, 14, good, ItemPictures.sha256(good));
        m.exports.set(m.exports.size() - 1, new PanelStore.ItemsExport(14, 14, "x", "y", 2, 1, 1, (int) ItemPictures.MAX_ZIP + 1, 1, ItemPictures.sha256(good)));
        p.refresh();
        assertEquals(11, p.current().export().id(), "too large: not fetched, the pictures held stay");
        assertTrue(p.problem().contains("larger than"), p.problem());
    }
}
