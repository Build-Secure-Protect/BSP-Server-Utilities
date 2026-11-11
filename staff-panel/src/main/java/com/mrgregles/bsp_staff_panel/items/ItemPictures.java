package com.mrgregles.bsp_staff_panel.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The item pictures the panel shows (SU-41; D271, D340, D341, D373): the newest export BSP-Server Utilities stored from
 * an admin's game ({@code <prefix>web_items}). Checked against its SHA-256, unpacked once in memory: {@code items.json}
 * (each item's id, tags, English name; the tabs), {@code names.json} (English for item keys, enchantments, effects) and
 * the {@code sheet<n>.png} pictures. Only those names are read, each at most {@link #MAX_ENTRY} bytes and
 * {@link #MAX_TOTAL} in all, so a damaged or hostile zip cannot fill the panel's memory.
 */
public final class ItemPictures {
    public static final int MAX_ENTRY = 24 * 1024 * 1024, MAX_TOTAL = 160 * 1024 * 1024, MAX_SHEETS = 64;
    /** The game server stores at most 48 MB (BSP-Server Utilities' {@code WebItemsUpload}). */
    public static final long MAX_ZIP = 48L * 1024 * 1024;

    /** One unpacked export: its row, the catalog for the pages (items, tabs, names, the grid), and the sheets. */
    public record Loaded(PanelStore.ItemsExport export, String catalog, Map<Integer, byte[]> sheets) {
    }

    private final PanelStore store;
    private volatile Loaded loaded;
    private volatile String problem = "";
    /** The export that could not be read: not fetched again until a newer one is stored (audit). */
    private volatile long failed = -1;

    public ItemPictures(PanelStore store) {
        this.store = store;
    }

    public Loaded current() {
        return loaded;
    }

    private volatile Loaded variantsOf;
    private volatile java.util.Set<String> variants = java.util.Set.of();

    /**
     * Whether an item's data is one of the variants the picture export lists for that item (a potion, an enchanted book):
     * typed item data is taken only from the picker, never made up (audit). Empty data is always allowed.
     */
    public boolean knowsVariant(String item, String snbt) {
        if (snbt == null || snbt.isEmpty()) {
            return true;
        }
        Loaded l = loaded;
        if (l == null) {
            return false;
        }
        if (variantsOf != l) {
            java.util.Set<String> set = new java.util.HashSet<>();
            try {
                com.google.gson.JsonArray items = com.google.gson.JsonParser.parseString(l.catalog()).getAsJsonObject().getAsJsonArray("items");
                for (com.google.gson.JsonElement e : items) {
                    com.google.gson.JsonObject o = e.getAsJsonObject();
                    if (o.has("nbt") && o.has("id")) {
                        set.add(o.get("id").getAsString() + "\u0000" + o.get("nbt").getAsString());
                    }
                }
            } catch (RuntimeException bad) {
                // nothing known
            }
            variants = set;
            variantsOf = l;
        }
        return variants.contains(item + "\u0000" + snbt);
    }

    /** Why the newest export could not be used ("" when fine). */
    public String problem() {
        return problem;
    }

    /** Reads the newest export when it is not the one held (every minute, and at start). */
    public void refresh() throws Exception {
        List<PanelStore.ItemsExport> rows = store.itemsExports(1);
        if (rows.isEmpty()) {
            return;
        }
        PanelStore.ItemsExport e = rows.get(0);
        Loaded now = loaded;
        if (now != null && now.export().id() == e.id() || e.id() == failed) {
            return;
        }
        if (e.size() > MAX_ZIP) {
            failed = e.id();
            problem = "The newest item pictures (" + e.by() + ") could not be read: larger than " + MAX_ZIP / 1048576 + " MB";
            return;
        }
        byte[] zip = store.itemsExportBytes(e.id());
        try {
            loaded = unpack(e, zip);
            problem = "";
        } catch (IOException | RuntimeException bad) {
            failed = e.id(); // the pictures held stay in use
            problem = "The newest item pictures (" + e.by() + ") could not be read: " + bad.getMessage();
        }
    }

    /** One export's zip, checked and unpacked. */
    public static Loaded unpack(PanelStore.ItemsExport e, byte[] zip) throws IOException {
        if (zip == null) {
            throw new IOException("its pictures are not in the database any more");
        }
        if (!sha256(zip).equals(e.sha256())) {
            throw new IOException("it is not whole (its SHA-256 differs)");
        }
        String items = null, names = null;
        Map<Integer, byte[]> sheets = new HashMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry en;
            while ((en = z.getNextEntry()) != null) {
                String n = en.getName();
                boolean sheet = n.matches("sheet([0-9]{1,2})\\.png");
                if (!n.equals("items.json") && !n.equals("names.json") && !sheet) {
                    continue; // nothing else is read
                }
                byte[] b = read(z);
                total += b.length;
                if (total > MAX_TOTAL) {
                    throw new IOException("it unpacks to more than " + MAX_TOTAL / 1048576 + " MB");
                }
                if (n.equals("items.json")) {
                    items = new String(b, StandardCharsets.UTF_8);
                } else if (n.equals("names.json")) {
                    names = new String(b, StandardCharsets.UTF_8);
                } else {
                    int i = Integer.parseInt(n.substring(5, n.length() - 4));
                    if (i >= MAX_SHEETS || b.length < 8 || (b[0] & 0xff) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
                        throw new IOException("sheet " + i + " is not a PNG picture");
                    }
                    sheets.put(i, b);
                }
            }
        }
        if (items == null || names == null) {
            throw new IOException("items.json or names.json is missing");
        }
        JsonObject it = JsonParser.parseString(items).getAsJsonObject();
        JsonElement nm = JsonParser.parseString(names);
        if (!it.has("items") || !it.get("items").isJsonArray() || !it.has("tabs") || !nm.isJsonObject()) {
            throw new IOException("items.json is not an export");
        }
        JsonObject catalog = new JsonObject();
        catalog.addProperty("id", e.id());
        catalog.addProperty("cell", it.has("cell") ? it.get("cell").getAsInt() : 32);
        catalog.addProperty("perRow", it.has("perRow") ? it.get("perRow").getAsInt() : 64);
        catalog.addProperty("perSheet", it.has("perSheet") ? it.get("perSheet").getAsInt() : 4096);
        catalog.addProperty("sheets", sheets.size());
        catalog.add("items", it.get("items"));
        catalog.add("tabs", it.get("tabs"));
        catalog.add("names", nm);
        return new Loaded(e, catalog.toString(), sheets);
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_ENTRY) {
                throw new IOException("a file in it is larger than " + MAX_ENTRY / 1048576 + " MB");
            }
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
