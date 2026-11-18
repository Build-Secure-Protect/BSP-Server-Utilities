package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.feed.Feed;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Made-up servers and players for {@code --demo}, sent every 5 seconds as the game servers would. */
final class Demo {
    static final String EMAIL = "owner@demo.test";
    private static final String[][] PLAYERS = {
            {"BSP-Spawn", "RuneSmith", "Newbie_Sam", "Glimmer"}, {"Frostwarf", "Kestrel_88", "BrickLayerTom", "Mossy_Fen"},
            {"Moongrove", "Vexa", "ObsidianOtter"}, {"Boulderbrook", "TallyHo", "Juniper_K"}, {"Stormfall", "Pixl"}};
    private static final String[][] TEAMS = {{"Iron Wolves", "#FFAA00"}, {"Nightshade", "#AA00AA"}, {"Deepcore", "#55FFFF"}};

    private Demo() {
    }

    /** Actions in the demo: answered "done" after a moment, nothing sent anywhere. */
    static com.mrgregles.bsp_staff_panel.act.Actions actions() {
        com.mrgregles.bsp_staff_panel.act.Actions[] holder = new com.mrgregles.bsp_staff_panel.act.Actions[1];
        byte[] key = new byte[32];
        holder[0] = new com.mrgregles.bsp_staff_panel.act.Actions(key, (uid, at, kind, payload) -> { if (kind.equals("web_action")) new Thread(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                return;
            }
            String body = new String(java.util.Base64.getUrlDecoder().decode(payload.substring(0, payload.indexOf('.'))), java.nio.charset.StandardCharsets.UTF_8);
            String what = com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("action").getAsString();
            long now = System.currentTimeMillis();
            String extra = what.equals("graves") ? ",\"data\":[{\"shortId\":\"3f9a2c1b\",\"serverId\":\"Moongrove\",\"dimension\":\"minecraft:overworld\",\"x\":-2214,\"y\":9,\"z\":902,"
                    + "\"placed\":false,\"archived\":false,\"diedAt\":" + (now - 7_200_000L) + ",\"stacks\":14,\"xp\":23,\"returnable\":true},"
                    + "{\"shortId\":\"b71e04d9\",\"serverId\":\"Moongrove\",\"dimension\":\"minecraft:the_nether\",\"x\":112,\"y\":31,\"z\":-40,"
                    + "\"placed\":true,\"archived\":false,\"diedAt\":" + (now - 86_400_000L) + ",\"stacks\":6,\"xp\":5,\"returnable\":false}]" : "";
            com.google.gson.JsonObject act = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            boolean undoing = act.has("undo");
            if ((what.equals("inv_add") || what.equals("inv_remove")) && !undoing) { // SU-40: an answer with its UNDO, as the game server's
                String area = act.get("area").getAsString(), count = act.get("count").getAsString();
                extra = ",\"data\":{\"outcome\":\"OK\",\"hash\":\"" + (what.equals("inv_add") ? "0123456789ab" : act.get("hash").getAsString()) + "\",\"undo\":"
                        + (what.equals("inv_add") ? "{\"action\":\"inv_take\",\"area\":\"" + area + "\",\"hash\":\"0123456789ab\",\"count\":" + count + "}"
                        : "{\"action\":\"inv_add\",\"area\":\"" + area + "\",\"item\":\"minecraft:stone\",\"snbt\":\"\",\"count\":" + count + "}") + "}";
            }
            if (what.equals("tp_places")) { // SU-43: made-up warps and spawns, as BSP-Teleport's API 2
                extra = ",\"data\":{\"tpName\":\"BSP-Spawn\",\"warps\":["
                        + "{\"name\":\"spawn\",\"server\":\"BSP-Spawn\",\"world\":\"minecraft:overworld\",\"x\":0.5,\"y\":80,\"z\":0.5,\"restricted\":false,\"portal\":false},"
                        + "{\"name\":\"market\",\"server\":\"BSP-Spawn\",\"world\":\"minecraft:overworld\",\"x\":120,\"y\":71,\"z\":-40,\"restricted\":false,\"portal\":false},"
                        + "{\"name\":\"arena\",\"server\":\"BSP-Spawn\",\"world\":\"minecraft:overworld\",\"x\":-310,\"y\":64,\"z\":220,\"restricted\":true,\"portal\":false},"
                        + "{\"name\":\"mine\",\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":1500,\"y\":40,\"z\":-600,\"restricted\":false,\"portal\":false},"
                        + "{\"name\":\"iceport\",\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":2210,\"y\":63,\"z\":140,\"restricted\":false,\"portal\":false},"
                        + "{\"name\":\"grove\",\"server\":\"Moongrove\",\"world\":\"minecraft:overworld\",\"x\":-2000,\"y\":70,\"z\":800,\"restricted\":false,\"portal\":false},"
                        + "{\"name\":\"frost_gate\",\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":10,\"y\":70,\"z\":10,\"restricted\":false,\"portal\":true}],"
                        + "\"spawns\":[{\"server\":\"BSP-Spawn\",\"world\":\"minecraft:overworld\",\"x\":0.5,\"y\":80,\"z\":0.5},{\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":0.5,\"y\":70,\"z\":0.5},"
                        + "{\"server\":\"Moongrove\",\"world\":\"minecraft:overworld\",\"x\":0.5,\"y\":66,\"z\":0.5}]}";
            }
            if (what.equals("grave_items")) { // SU-42: a made-up grave, as BSP-InventorySync's graveItems
                extra = ",\"data\":{\"xp\":23,\"areas\":[{\"id\":\"grave\",\"kind\":\"GRAVE\",\"add\":false,\"rem\":false,\"size\":14,\"slots\":["
                        + "{\"i\":0,\"id\":\"minecraft:netherite_pickaxe\",\"n\":1,\"key\":\"item.minecraft.netherite_pickaxe\",\"ench\":[[\"minecraft:efficiency\",5],[\"minecraft:unbreaking\",3]],\"dmg\":151,\"max\":2031,\"h\":\"a1\"},"
                        + "{\"i\":1,\"id\":\"minecraft:netherite_chestplate\",\"n\":1,\"key\":\"item.minecraft.netherite_chestplate\",\"ench\":[[\"minecraft:protection\",4]],\"h\":\"a2\"},"
                        + "{\"i\":2,\"id\":\"minecraft:cooked_beef\",\"n\":48,\"key\":\"item.minecraft.cooked_beef\",\"h\":\"a3\"},"
                        + "{\"i\":3,\"id\":\"minecraft:oak_log\",\"n\":64,\"key\":\"block.minecraft.oak_log\",\"h\":\"a4\"},"
                        + "{\"i\":4,\"id\":\"minecraft:iron_ingot\",\"n\":37,\"key\":\"item.minecraft.iron_ingot\",\"h\":\"a5\"},"
                        + "{\"i\":5,\"id\":\"minecraft:torch\",\"n\":61,\"key\":\"block.minecraft.torch\",\"h\":\"a6\"},"
                        + "{\"i\":6,\"id\":\"sophisticatedbackpacks:iron_backpack\",\"n\":1,\"key\":\"item.sophisticatedbackpacks.iron_backpack\",\"h\":\"a7\"},"
                        + "{\"i\":7,\"id\":\"minecraft:ender_pearl\",\"n\":9,\"key\":\"item.minecraft.ender_pearl\",\"h\":\"a8\"},"
                        + "{\"i\":8,\"id\":\"minecraft:golden_apple\",\"n\":3,\"key\":\"item.minecraft.golden_apple\",\"h\":\"a9\"}]},"
                        + "{\"id\":\"backpack/77\",\"kind\":\"BACKPACK\",\"add\":false,\"rem\":false,\"size\":27,\"place\":{\"area\":\"grave\",\"slot\":6,\"inside\":[]},\"slots\":["
                        + "{\"i\":0,\"id\":\"minecraft:cobblestone\",\"n\":640,\"key\":\"block.minecraft.cobblestone\",\"h\":\"b1\"},{\"i\":1,\"id\":\"minecraft:redstone\",\"n\":128,\"key\":\"item.minecraft.redstone\",\"h\":\"b2\"}]}]}";
            }
            if (what.equals("market_read")) { // SU-51: a made-up market, as BSP-Core 1.16's BspMarket
                extra = ",\"data\":" + demoMarket(now);
            }
            if (what.equals("lottery_read")) {
                extra = ",\"data\":" + demoLottery(now);
            }
            if (what.equals("lottery_draw")) {
                boolean busy = now - drawAt < 8_000L;
                if (!busy) {
                    drawAt = now;
                    Runnable push = pushNow;
                    if (push != null) {
                        push.run(); // every page sees the countdown at once, as a game server's snapshot on a phase change
                    }
                }
                extra = ",\"data\":{\"result\":\"" + (busy ? "busy" : "ok") + "\",\"server\":\"BSP-Spawn\"}";
            }
            boolean refused = false;
            if (what.equals("bank_read")) { // SU-72: a made-up bank and market, as BSP-Core 1.18's staff calls
                extra = ",\"data\":" + demoBank(now, act.get("player").getAsString());
            }
            if (what.equals("bank_refund")) { // a payment also on the other player is refused once, to show the offer (D508)
                refused = act.has("other") && act.get("other").getAsBoolean();
                extra = ",\"data\":{\"result\":\"" + (refused ? "other_short" : "ok") + "\",\"withoutOther\":" + refused + "}";
            }
            if (what.equals("market_cancel_lot") || what.equals("market_cancel_order")) {
                extra = ",\"data\":{\"result\":\"ok\",\"piecesBack\":" + (what.endsWith("order") ? 7200 : 0) + "}";
            }
            if (what.equals("inv_read")) {
                extra = ",\"data\":" + demoItems(com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("player").getAsString(), now);
            }
            String msg = what.equals("ban") ? act.get("name").getAsString() + " is banned from the network (demo: nothing was sent to a game server)."
                    : what.equals("unban") ? "They may join again (demo: nothing was sent to a game server)." : what.equals("ban_edit") ? "The ban was changed (demo: nothing was sent to a game server)."
                    : what.equals("tp") ? "Moved " + act.get("name").getAsString() + " to " + act.getAsJsonObject("to").get("what").getAsString() + " (demo: nothing was sent to a game server)."
                    : what.equals("inv_add") ? (undoing ? "Added back (demo: nothing was sent to a game server)." : "Added " + act.get("count").getAsString() + " (demo: nothing was sent to a game server).")
                    : what.equals("inv_remove") ? "Removed (demo: nothing was sent to a game server)." : what.equals("inv_take") ? "Took back all of it (demo: nothing was sent to a game server)."
                    : what.equals("grave_return") ? "Returned 14 stacks and 23 XP (demo: nothing was sent to a game server)."
                    : what.equals("say") ? "Shown to 3 players (demo: nothing was sent to a game server)."
                    : what.equals("market_read") ? "demo market" : what.equals("lottery_read") ? "demo lottery" : what.equals("bank_read") ? "demo bank"
                    : what.equals("bank_refund") ? (refused ? "The other player no longer has that much. Nothing changed: refund it without them if you mean to."
                    : "Refunded (demo: nothing was sent to a game server). They are told in game.")
                    : what.equals("market_cancel_lot") ? "Cancelled: the items go to their Collect box (demo: nothing was sent to a game server)."
                    : what.equals("market_cancel_order") ? "Cancelled: 7,200 pieces went back to their bank (demo: nothing was sent to a game server)."
                    : what.equals("lottery_draw") ? (extra.contains("busy") ? "A draw is already running on the network." : "Drawn on BSP-Spawn: the countdown has begun on every server (demo: nothing was paid).")
                    : "Done (demo: nothing was sent to a game server).";
            holder[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("a", at, "answer",
                    com.mrgregles.bsp_staff_panel.crypto.Signed.seal(key, "{\"id\":\"" + uid + "\",\"ok\":" + (!extra.contains("\"busy\"") && !refused) + ",\"message\":\"" + msg + "\"" + extra + "}")));
        }).start(); }, System::currentTimeMillis);
        return holder[0];
    }

    /**
     * Made-up item pictures for {@code --demo} (SU-41): what an admin's {@code /su icons} would store, drawn here as coloured
     * tiles with letters (the real ones are the game's own pictures), in a few tabs.
     */
    static void pictures(com.mrgregles.bsp_staff_panel.store.PanelStore store) throws Exception {
        if (!(store instanceof com.mrgregles.bsp_staff_panel.store.MemoryStore m)) {
            return;
        }
        String[][] tabs = {
                {"Building Blocks", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:glass", "minecraft:stone", "minecraft:bricks", "minecraft:sandstone",
                        "minecraft:shulker_box", "minecraft:tnt", "minecraft:torch", "minecraft:lantern", "minecraft:ladder"},
                {"Tools & Utilities", "minecraft:netherite_pickaxe", "minecraft:diamond_pickaxe", "minecraft:iron_pickaxe", "minecraft:shears", "minecraft:fishing_rod", "minecraft:compass",
                        "minecraft:clock", "minecraft:ender_pearl", "minecraft:bucket"},
                {"Combat", "minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:bow", "minecraft:arrow", "minecraft:shield", "minecraft:netherite_boots",
                        "minecraft:netherite_chestplate", "minecraft:iron_helmet", "minecraft:totem_of_undying"},
                {"Food & Drinks", "minecraft:cooked_beef", "minecraft:bread", "minecraft:golden_apple", "minecraft:apple", "minecraft:cake", "minecraft:potion|minecraft:swiftness",
                        "minecraft:potion|minecraft:healing"},
                {"Ingredients", "minecraft:iron_ingot", "minecraft:redstone", "minecraft:coal", "minecraft:diamond", "minecraft:emerald", "minecraft:lapis_lazuli", "minecraft:gunpowder",
                        "minecraft:string", "minecraft:bone_meal"},
                {"Create", "create:brass_ingot", "create:brass_sheet", "create:brass_nugget", "create:brass_block", "create:brass_casing", "create:andesite_alloy", "create:cogwheel",
                        "create:shaft", "create:mechanical_press", "create:precision_mechanism", "create:electron_tube"},
                {"Sophisticated Backpacks", "sophisticatedbackpacks:backpack", "sophisticatedbackpacks:iron_backpack", "sophisticatedbackpacks:gold_backpack"},
                {"Other", "bsp_core:shatter_totem", "artifacts:ring_of_haste"}};
        int cell = 32, perRow = 64;
        java.util.List<String> entries = new java.util.ArrayList<>();
        com.google.gson.JsonArray tabList = new com.google.gson.JsonArray();
        for (String[] t : tabs) {
            com.google.gson.JsonArray idx = new com.google.gson.JsonArray();
            for (int i = 1; i < t.length; i++) {
                idx.add(entries.size());
                entries.add(t[i]);
            }
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("name", t[0]);
            o.addProperty("icon", idx.get(0).getAsInt());
            o.add("items", idx);
            tabList.add(o);
        }
        int rows = (entries.size() + perRow - 1) / perRow;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(perRow * cell, rows * cell, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        com.google.gson.JsonObject keys = new com.google.gson.JsonObject();
        for (int i = 0; i < entries.size(); i++) {
            String[] e = entries.get(i).split("\\|");
            String path = e[0].substring(e[0].indexOf(':') + 1), name = words(path) + (e.length > 1 ? " of " + words(e[1].substring(e[1].indexOf(':') + 1)) : "");
            int x = (i % perRow) * cell, y = (i / perRow) * cell, h = Math.abs(entries.get(i).hashCode());
            java.awt.Color c = java.awt.Color.getHSBColor((h % 360) / 360f, 0.45f, 0.75f);
            g.setColor(c.darker());
            g.fillRoundRect(x + 3, y + 3, cell - 6, cell - 6, 8, 8);
            g.setColor(c);
            g.fillRoundRect(x + 5, y + 5, cell - 10, cell - 12, 6, 6);
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11));
            String[] w = words(path).split(" ");
            String l = (w.length > 1 ? "" + w[0].charAt(0) + w[1].charAt(0) : w[0].substring(0, Math.min(2, w[0].length()))).toUpperCase(java.util.Locale.ROOT);
            g.drawString(l, x + 9, y + 21);
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", e[0]);
            o.addProperty("name", name);
            if (e.length > 1) {
                o.addProperty("nbt", "{Potion:\"" + e[1] + "\"}");
                o.addProperty("v", e[1]);
            }
            items.add(o);
            String ns = e[0].substring(0, e[0].indexOf(':'));
            keys.addProperty((path.contains("log") || path.contains("block") || path.contains("glass") || path.contains("stone") || path.equals("tnt") || path.equals("torch") ? "block." : "item.") + ns + "." + path, words(path));
        }
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        com.google.gson.JsonObject itemsJson = new com.google.gson.JsonObject();
        itemsJson.addProperty("cell", cell);
        itemsJson.addProperty("perRow", perRow);
        itemsJson.addProperty("perSheet", perRow * perRow);
        itemsJson.add("items", items);
        itemsJson.add("tabs", tabList);
        com.google.gson.JsonObject names = new com.google.gson.JsonObject(), ench = new com.google.gson.JsonObject();
        for (String en : new String[]{"sharpness", "unbreaking", "mending", "efficiency", "fortune", "feather_falling", "protection", "power", "infinity"}) {
            ench.addProperty("minecraft:" + en, words(en));
        }
        keys.addProperty("item.minecraft.tnt", "TNT");
        names.add("keys", keys);
        names.add("ench", ench);
        names.add("effects", new com.google.gson.JsonObject());
        java.io.ByteArrayOutputStream zip = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(zip)) {
            z.putNextEntry(new java.util.zip.ZipEntry("items.json"));
            z.write(itemsJson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.putNextEntry(new java.util.zip.ZipEntry("names.json"));
            z.write(names.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.putNextEntry(new java.util.zip.ZipEntry("sheet0.png"));
            z.write(png.toByteArray());
        }
        byte[] b = zip.toByteArray();
        long now = System.currentTimeMillis();
        m.exports.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.ItemsExport(now - 9 * 86_400_000L, now - 9 * 86_400_000L, "Mrgregles", "BSP-Spawn", 21112, 31, 263, 6_100_000, 12, "0"));
        m.exports.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.ItemsExport(now - 3_600_000L, now - 3_600_000L, "Juniper_K", "BSP-Spawn", entries.size(), tabs.length, 265, b.length, 1,
                com.mrgregles.bsp_staff_panel.items.ItemPictures.sha256(b)));
        m.exportBytes.put(now - 3_600_000L, b);
    }

    private static String words(String path) {
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("_")) {
            if (!w.isEmpty()) {
                sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    /** Made-up bans for {@code --demo} (SU-44): three in force, three past. */
    static void bans(com.mrgregles.bsp_staff_panel.store.PanelStore store) {
        if (!(store instanceof com.mrgregles.bsp_staff_panel.store.MemoryStore m)) {
            return;
        }
        long now = System.currentTimeMillis(), h = 3_600_000L, d = 24 * h;
        java.util.function.Function<String, String> id = n -> java.util.UUID.nameUUIDFromBytes(n.getBytes()).toString();
        m.bans.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.Ban(1, id.apply("Vexa"), "Vexa", "Griefing at the Frostwarf docks", "", "Mrgregles", now - 68 * d, now - 67 * d, 0, "", 0));
        m.bans.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.Ban(2, id.apply("Vexa"), "Vexa", "X-ray on Moongrove", "Two reports", "Rook", now - 20 * d, now - 13 * d, now - 18 * d, "Saffi", 0));
        m.bans.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.Ban(3, id.apply("Old_Timer"), "Old_Timer", "Mistaken ban", "", "Rook", now - 15 * d, 0, now - 9 * d, "Demo_Owner", 0));
        m.bans.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.Ban(4, id.apply("GriefLord22"), "GriefLord22", "Burning the Frostwarf market", "", "Saffi", now - h, now + 23 * h, 0, "", 0));
        m.bans.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.Ban(5, id.apply("Spamz"), "Spamz", "Advertising another server", "", "Bramble", now - 22 * h, now + 2 * h, 0, "", 0));
        m.bans.add(new com.mrgregles.bsp_staff_panel.store.PanelStore.Ban(6, id.apply("Dupe_Wizard"), "Dupe_Wizard", "Duplicating items with a dank", "Logs in the staff room", "Demo_Owner", now - 5 * d, 0, 0, "", 0));
        for (String n : new String[]{"GriefLord22", "Spamz", "Dupe_Wizard"}) {
            m.players.put(id.apply(n), n);
        }
    }

    private static final String[] OFFLINE = {"Velvet_Hare", "Vermillion", "Old_Timer", "GriefLord22"};

    /** A made-up inventory for {@code --demo} (SU-39): the same shape as BSP-Server Utilities' {@code WebItems}. */
    static String demoItems(String player, long now) {
        Random r = new Random(player.hashCode());
        String[][] pool = {{"minecraft:cobblestone", "block.minecraft.cobblestone"}, {"minecraft:oak_planks", "block.minecraft.oak_planks"},
                {"minecraft:iron_ingot", "item.minecraft.iron_ingot"}, {"minecraft:redstone", "item.minecraft.redstone"}, {"minecraft:coal", "item.minecraft.coal"},
                {"minecraft:bread", "item.minecraft.bread"}, {"minecraft:arrow", "item.minecraft.arrow"}, {"minecraft:glass", "block.minecraft.glass"},
                {"create:brass_ingot", "item.create.brass_ingot"}, {"create:andesite_alloy", "item.create.andesite_alloy"}, {"minecraft:tnt", "block.minecraft.tnt"},
                {"minecraft:diamond", "item.minecraft.diamond"}, {"minecraft:torch", "block.minecraft.torch"}};
        StringBuilder inv = new StringBuilder();
        inv.append("{\"i\":0,\"id\":\"minecraft:netherite_sword\",\"n\":1,\"key\":\"item.minecraft.netherite_sword\",\"ench\":[[\"minecraft:sharpness\",5],[\"minecraft:unbreaking\",3],[\"minecraft:mending\",1]],\"dmg\":120,\"max\":2031,\"h\":\"a1b2c3d4e5f6\"}");
        inv.append(",{\"i\":1,\"id\":\"minecraft:netherite_pickaxe\",\"n\":1,\"key\":\"item.minecraft.netherite_pickaxe\",\"name\":\"Old Faithful\",\"ench\":[[\"minecraft:efficiency\",5],[\"minecraft:fortune\",3]],\"dmg\":151,\"max\":2031,\"lore\":[\"Dug the Moongrove mine\"],\"h\":\"b1b2c3d4e5f6\"}");
        inv.append(",{\"i\":3,\"id\":\"minecraft:cooked_beef\",\"n\":48,\"key\":\"item.minecraft.cooked_beef\",\"h\":\"c1b2c3d4e5f6\"}");
        inv.append(",{\"i\":7,\"id\":\"bsp_core:shatter_totem\",\"n\":1,\"key\":\"item.bsp_core.shatter_totem\",\"lk\":true,\"h\":\"d1b2c3d4e5f6\"}");
        inv.append(",{\"i\":8,\"id\":\"minecraft:shulker_box\",\"n\":1,\"key\":\"block.minecraft.shulker_box\",\"h\":\"e1b2c3d4e5f6\",\"in\":[{\"i\":0,\"id\":\"minecraft:tnt\",\"n\":16,\"key\":\"block.minecraft.tnt\"},{\"i\":5,\"id\":\"minecraft:diamond\",\"n\":64,\"key\":\"item.minecraft.diamond\"}]}");
        for (int i = 9; i < 36; i++) {
            if (r.nextInt(10) < 7) {
                String[] it = pool[r.nextInt(pool.length)];
                inv.append(",{\"i\":").append(i).append(",\"id\":\"").append(it[0]).append("\",\"n\":").append(new int[]{1, 8, 16, 32, 64, 64}[r.nextInt(6)])
                        .append(",\"key\":\"").append(it[1]).append("\",\"h\":\"f").append(Integer.toHexString(i)).append("b2c3d4e5f6\"}");
            }
        }
        inv.append(",{\"i\":20,\"id\":\"bsp_inventorysync:missing\",\"n\":12,\"key\":\"item.bsp_inventorysync.missing\",\"miss\":\"bigmod:strange_gem\",\"h\":\"0102c3d4e5f6\"}");
        String area = "{\"id\":\"%s\",\"kind\":\"%s\",\"add\":%s,\"rem\":true,\"size\":%d,%s\"slots\":[%s]}";
        StringBuilder ender = new StringBuilder();
        for (int i = 0; i < 27; i += 2) {
            String[] it = pool[(i * 7 + 3) % pool.length];
            ender.append(i == 0 ? "" : ",").append("{\"i\":").append(i).append(",\"id\":\"").append(it[0]).append("\",\"n\":64,\"key\":\"").append(it[1]).append("\",\"h\":\"e").append(i).append("b2c3d4e5f6\"}");
        }
        String areas = String.join(",",
                String.format(area, "inventory", "INVENTORY", "true", 36, "", inv),
                String.format(area, "armor", "ARMOR", "false", 4, "", "{\"i\":0,\"id\":\"minecraft:netherite_boots\",\"n\":1,\"key\":\"item.minecraft.netherite_boots\",\"ench\":[[\"minecraft:feather_falling\",4]],\"dmg\":30,\"max\":481,\"h\":\"aa1\"},"
                        + "{\"i\":2,\"id\":\"minecraft:netherite_chestplate\",\"n\":1,\"key\":\"item.minecraft.netherite_chestplate\",\"ench\":[[\"minecraft:protection\",4]],\"dmg\":12,\"max\":592,\"h\":\"aa2\"},"
                        + "{\"i\":3,\"id\":\"minecraft:iron_helmet\",\"n\":1,\"key\":\"item.minecraft.iron_helmet\",\"dmg\":40,\"max\":165,\"h\":\"aa3\"}"),
                String.format(area, "offhand", "OFFHAND", "false", 1, "", "{\"i\":0,\"id\":\"minecraft:shield\",\"n\":1,\"key\":\"item.minecraft.shield\",\"dmg\":3,\"max\":336,\"h\":\"ab1\"}"),
                String.format(area, "cursor", "CURSOR", "false", 1, "", ""),
                String.format(area, "ender_chest", "ENDER_CHEST", "true", 27, "", ender),
                String.format(area, "curios/back", "CURIOS", "false", 1, "", "{\"i\":0,\"id\":\"sophisticatedbackpacks:iron_backpack\",\"n\":1,\"key\":\"item.sophisticatedbackpacks.iron_backpack\",\"h\":\"ac1\"}"),
                String.format(area, "curios/ring", "CURIOS", "false", 2, "", "{\"i\":0,\"id\":\"artifacts:ring_of_haste\",\"n\":1,\"key\":\"item.artifacts.ring_of_haste\",\"h\":\"ad1\"}"),
                String.format(area, "backpack/5c1d", "BACKPACK", "true", 54, "\"place\":{\"area\":\"curios/back\",\"slot\":0,\"inside\":[]},",
                        "{\"i\":0,\"id\":\"minecraft:cobblestone\",\"n\":640,\"key\":\"block.minecraft.cobblestone\",\"h\":\"ae1\"},{\"i\":1,\"id\":\"minecraft:redstone\",\"n\":128,\"key\":\"item.minecraft.redstone\",\"h\":\"ae2\"}"));
        boolean off = java.util.Arrays.stream(OFFLINE).anyMatch(n -> java.util.UUID.nameUUIDFromBytes(n.getBytes()).toString().equals(player));
        if (off) { // offline: from the last save; backpacks give items up only (BSP-InventorySync)
            areas = areas.replace("\"id\":\"backpack/5c1d\",\"kind\":\"BACKPACK\",\"add\":true", "\"id\":\"backpack/5c1d\",\"kind\":\"BACKPACK\",\"add\":false");
        }
        return "{\"player\":\"" + player + "\",\"online\":" + !off + ",\"server\":\"Moongrove\",\"live\":" + !off + ",\"version\":41,\"savedAt\":" + (now - (off ? 2_400_000L : 40_000L)) + ",\"areas\":[" + areas + "]}";
    }

    private static final String[][] LINES = {
            {"Frostwarf", "Kestrel_88", "raid at 9 tonight, bring tnt"}, {"Moongrove", "Vexa", "anyone selling diamonds?"},
            {"Moongrove", "ObsidianOtter", "@Vexa check the market"}, {"BSP-Spawn", "Newbie_Sam", "how do i get to the market"},
            {"Boulderbrook", "TallyHo", "lol"}, {"Stormfall", "Pixl", "help im stuck in a hole, can a mod help"},
            {"Boulderbrook", "Juniper_K", "who has spare iron"}, {"Frostwarf", "BrickLayerTom", "gg"}, {"BSP-Spawn", "Glimmer", "is the lottery on today?"},
            {"Moongrove", "Vexa", "found 12 diamonds already"}, {"Stormfall", "Pixl", "nvm got out"}, {"Frostwarf", "Mossy_Fen", "brb"},
            {"BSP-Spawn", "RuneSmith", "the new shop is open at spawn"}, {"Frostwarf", "Kestrel_88", "bring food too"}, {"BSP-Spawn", "Newbie_Sam", "thanks!"}};

    /** Made-up chat for {@code --demo}: two days of lines at the start, then a line every few seconds, as the game servers write them. */
    static Runnable chat(com.mrgregles.bsp_staff_panel.store.PanelStore store) {
        Random r = new Random(11);
        long now = System.currentTimeMillis();
        try {
            for (int i = 0; i < 240; i++) {
                demoLine(store, LINES[i % LINES.length], now - (240 - i) * 12 * 60_000L);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(t -> {
            Thread x = new Thread(t, "panel-demo-chat");
            x.setDaemon(true);
            return x;
        });
        s.scheduleWithFixedDelay(() -> {
            try {
                demoLine(store, LINES[r.nextInt(LINES.length)], System.currentTimeMillis());
            } catch (Exception ignored) {
                // the demo only
            }
        }, 4, 6, TimeUnit.SECONDS);
        return s::shutdownNow;
    }

    private static void demoLine(com.mrgregles.bsp_staff_panel.store.PanelStore store, String[] l, long at) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("v", 1);
        String uid = java.util.UUID.randomUUID().toString();
        o.addProperty("uid", uid);
        o.addProperty("at", at);
        o.addProperty("server", l[0]);
        o.addProperty("kind", "player");
        o.addProperty("player", java.util.UUID.nameUUIDFromBytes(l[1].getBytes()).toString());
        o.addProperty("name", l[1]);
        o.addProperty("text", l[2]);
        store.insertChat(uid, at, l[0], l[1], l[2], o.toString());
    }

    /** Made-up staff, a staff room and two messages waiting for the demo Owner (SU-37). */
    static void talk(com.mrgregles.bsp_staff_panel.store.PanelStore store, com.mrgregles.bsp_staff_panel.store.Account owner) throws Exception {
        long now = System.currentTimeMillis();
        String[][] staff = {{"Saffi", "ADMIN"}, {"Kip", "MODERATOR"}, {"Rook", "MODERATOR"}, {"Bramble", "ADMIN"}};
        java.util.Map<String, Long> ids = new java.util.HashMap<>();
        for (String[] s : staff) {
            com.mrgregles.bsp_staff_panel.store.Account a = new com.mrgregles.bsp_staff_panel.store.Account();
            a.email = s[0].toLowerCase() + "@demo.test";
            a.mcName = s[0];
            a.rank = com.mrgregles.bsp_staff_panel.auth.Rank.parse(s[1]);
            a.createdBy = EMAIL;
            a.createdAt = now;
            ids.put(s[0], store.insert(a));
        }
        ids.put("Demo_Owner", owner.id);
        String[][] room = {{"Bramble", "evening all"}, {"Kip", "hi! quiet on spawn so far"}, {"Saffi", "anyone else seeing lag spikes on Frostwarf?"},
                {"Kip", "not on spawn"}, {"Rook", "Vexa has 12 diamonds in 5 min on Moongrove, watching her"}, {"Saffi", "lag gone"},
                {"Kip", "restart at 22:00? @Demo_Owner can you post it"}};
        for (int i = 0; i < room.length; i++) {
            com.mrgregles.bsp_staff_panel.auth.Rank r = room[i][0].equals("Saffi") || room[i][0].equals("Bramble") ? com.mrgregles.bsp_staff_panel.auth.Rank.ADMIN : com.mrgregles.bsp_staff_panel.auth.Rank.MODERATOR;
            store.roomInsert(now - (room.length - i) * 7 * 60_000L, ids.get(room[i][0]), room[i][0], r.name(), room[i][1]);
        }
        store.messageInsert(now - 20 * 3_600_000L, ids.get("Saffi"), 0, "Restart on Sunday at 10:00 for the BSP-Core update. Please tell players in chat from 09:30.");
        store.messageInsert(now - 3_600_000L, ids.get("Rook"), owner.id, "Can you keep an eye on Moongrove tonight? Two reports of x-ray there.");
        long mine = store.messageInsert(now - 2 * 3_600_000L, owner.id, ids.get("Kip"), "Thanks for covering spawn earlier!");
        store.markRead(mine, ids.get("Kip"), now - 100 * 60_000L);
        // everyone who "joined" (Find a player, SU-39): the demo's players and a few who are offline
        if (store instanceof com.mrgregles.bsp_staff_panel.store.MemoryStore m) {
            for (String[] server : PLAYERS) {
                for (int i = 1; i < server.length; i++) {
                    m.players.put(java.util.UUID.nameUUIDFromBytes(server[i].getBytes()).toString(), server[i]);
                }
            }
            for (String off : OFFLINE) {
                m.players.put(java.util.UUID.nameUUIDFromBytes(off.getBytes()).toString(), off);
            }
        }
        // notes and tags (SU-38)
        String[][] tags = {{"Suspected cheater", "#ff6b5c"}, {"Watch", "#ffd23a"}, {"New", "#7fb2ff"}, {"Supporter", "#ffd23a"}, {"Builder", "#8fe04a"}, {"Helpful", "#19d3b0"}};
        java.util.Map<String, Long> tagIds = new java.util.HashMap<>();
        for (String[] t : tags) {
            tagIds.put(t[0], store.tagInsert(t[0], t[1], false, ids.get("Saffi"), "Saffi", now - 5 * 86_400_000L));
        }
        tagIds.put("x-ray?", store.tagInsert("x-ray?", "#6b7180", true, ids.get("Kip"), "Kip", now - 3_600_000L));
        java.util.function.Function<String, String> uuid = n -> java.util.UUID.nameUUIDFromBytes(n.getBytes()).toString();
        String[][] tagged = {{"Vexa", "Suspected cheater"}, {"Vexa", "Watch"}, {"Newbie_Sam", "New"}, {"BrickLayerTom", "Supporter"}, {"RuneSmith", "Builder"},
                {"Pixl", "x-ray?"}, {"Glimmer", "Helpful"}, {"Vexa", "x-ray?"}};
        for (String[] t : tagged) {
            store.playerTagAdd(uuid.apply(t[0]), tagIds.get(t[1]), ids.get("Rook"), now - 7_200_000L);
        }
        store.noteInsert(uuid.apply("Vexa"), "Vexa", now - 7_200_000L, ids.get("Rook"), "Rook", "Found 12 diamonds in 5 minutes at y -58 on Moongrove. Watching her tonight; screenshots in the staff drive.");
        store.noteInsert(uuid.apply("Vexa"), "Vexa", now - 3_600_000L, ids.get("Kip"), "Kip", "Asked in chat if x-ray is allowed. Told her no and pointed her to /rules.");
        store.noteInsert(uuid.apply("Pixl"), "Pixl", now - 86_400_000L, owner.id, "Demo_Owner", "Gets stuck a lot; new to the pack. Be patient.");
    }

    static Runnable feed(Feed feed) {
        Random r = new Random(7);
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(t -> {
            Thread x = new Thread(t, "panel-demo");
            x.setDaemon(true);
            return x;
        });
        Runnable tick = () -> {
            long now = System.currentTimeMillis();
            for (String[] server : PLAYERS) {
                JsonObject o = new JsonObject();
                o.addProperty("v", 1);
                o.addProperty("server", server[0]);
                o.addProperty("at", now);
                o.addProperty("mspt", 18 + r.nextInt(20));
                o.addProperty("max", 100);
                o.addProperty("graves", true);
                o.addProperty("items", true);
                o.addProperty("tp", true);
                o.addProperty("tpName", server[0]);
                o.addProperty("market", true); // SU-51: BSP-Core 1.16 on every demo server
                o.addProperty("bank", true); // SU-72: and 1.18's staff calls
                o.addProperty("lotteryUp", true);
                boolean counting = now - drawAt < 7_000L;
                o.addProperty("lottery", counting ? "COUNTING" : "IDLE");
                o.addProperty("lotterySince", counting ? drawAt : 0);
                JsonArray ps = new JsonArray();
                for (int i = 1; i < server.length; i++) {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", java.util.UUID.nameUUIDFromBytes(server[i].getBytes()).toString());
                    p.addProperty("name", server[i]);
                    if (server[i].equals("Mossy_Fen")) {
                        p.addProperty("arriving", true); // D366 in the demo
                    }
                    p.addProperty("dim", "minecraft:overworld");
                    int home = server[i].hashCode(); // a home within 1500 of 0, 0 and a slow walk round it (SU-46: heads that move on the map)
                    double walk = now / 9000.0 + (home & 63);
                    p.addProperty("x", Math.round(Math.floorMod(home, 3000) - 1500 + 40 * Math.sin(walk)));
                    p.addProperty("y", 64);
                    p.addProperty("z", Math.round(Math.floorMod(home >> 12, 3000) - 1500 + 40 * Math.cos(walk * 0.8)));
                    p.addProperty("yaw", Math.floorMod((long) Math.toDegrees(walk), 360));
                    p.addProperty("mode", "survival");
                    p.addProperty("hp", 2 + r.nextInt(19));
                    p.addProperty("maxHp", 20);
                    p.addProperty("armor", r.nextInt(21));
                    p.addProperty("food", r.nextInt(21));
                    p.addProperty("sat", 0);
                    p.addProperty("level", r.nextInt(40));
                    p.addProperty("xp", r.nextInt(100) / 100.0);
                    p.addProperty("air", 300);
                    p.addProperty("maxAir", 300);
                    JsonArray fx = new JsonArray();
                    if (server[i].equals("Vexa") || server[i].equals("Juniper_K")) {
                        JsonObject e = new JsonObject();
                        e.addProperty("id", "minecraft:night_vision");
                        e.addProperty("amp", 0);
                        e.addProperty("sec", 280);
                        fx.add(e);
                        JsonObject e2 = new JsonObject();
                        e2.addProperty("id", "minecraft:haste");
                        e2.addProperty("amp", 1);
                        e2.addProperty("sec", 55);
                        fx.add(e2);
                        p.addProperty("air", 120);
                    }
                    p.add("effects", fx);
                    p.addProperty("afkMs", server[i].equals("Mossy_Fen") ? 14 * 60_000 : 0);
                    if (server[i].equals("Pixl")) {
                        p.addProperty("hp", 4);
                    }
                    if (server[i].equals("RuneSmith")) {
                        p.addProperty("mode", "creative");
                    }
                    p.addProperty("ping", 20 + r.nextInt(120));
                    if (server[i].equals("Glimmer")) {
                        p.addProperty("role", "Moderator");
                    } else {
                        p.add("role", null);
                    }
                    p.add("skin", null);
                    if (i % 3 != 0) {
                        String[] t = TEAMS[(server[0].length() + i) % TEAMS.length];
                        JsonObject team = new JsonObject();
                        team.addProperty("id", t[0]);
                        team.addProperty("name", t[0]);
                        team.addProperty("colour", t[1]);
                        p.add("team", team);
                    } else {
                        p.add("team", null);
                    }
                    ps.add(p);
                }
                o.add("players", ps);
                feed.accept(server[0], now, o.toString());
            }
        };
        s.scheduleWithFixedDelay(tick, 0, 5, TimeUnit.SECONDS);
        pushNow = () -> {
            s.execute(tick);
            s.schedule(tick, 7_200, TimeUnit.MILLISECONDS); // and as the draw ends
        };
        return s::shutdownNow;
    }

    // ------------------------------------------------------------------ the market and the lottery (SU-51)

    private static volatile long drawAt;
    private static volatile Runnable pushNow;
    private static final String[] IN = {"Ash_Tree", "Brick_Ivy", "BrickLayerTom", "Glimmer", "Hazel_Moth", "Juniper_K", "Kestrel_88", "Mossy_Fen", "Newbie_Sam",
            "ObsidianOtter", "Old_Oak", "Pixl", "RuneSmith", "TallyHo", "Vexa", "Willow_Brook"};

    private static String uuidOf(String name) {
        return java.util.UUID.nameUUIDFromBytes(name.getBytes()).toString();
    }

    private static JsonObject item(String id, int n, String key, String... ench) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("n", n);
        o.addProperty("key", key);
        if (ench.length > 0) {
            JsonArray e = new JsonArray();
            for (int i = 0; i + 1 < ench.length; i += 2) {
                JsonArray pair = new JsonArray();
                pair.add(ench[i]);
                pair.add(Integer.parseInt(ench[i + 1]));
                e.add(pair);
            }
            o.add("ench", e);
        }
        o.addProperty("h", Integer.toHexString((id + n).hashCode()));
        return o;
    }

    /** A made-up bank and market for one player, as {@code bank_read} answers (SU-72). */
    static String demoBank(long now, String player) {
        long h = 3_600_000L;
        String rows = String.join(",",
                row(1234, now - 2 * 60_000, "PAID", "Steve_42", "for the diamonds", -320, -1, 0, "Frostwarf", 0, 0, 0, 320, 0, true),
                row(1231, now - 100 * 60_000, "MARKET", "Mossy_Fen", "lot sold: Diamond Pickaxe", 4560, -1, 0, "Frostwarf", 0, 0, 0, 4560, 0, false),
                row(1230, now - 100 * 60_000, "TAX", "", "market tax", -240, -1, 0, "Frostwarf", 0, 0, 0, 240, 0, false),
                row(1200, now - 2 * h, "CONVERT", "", "", 900, 2, -3, "Frostwarf", 100, 1, 0, 800, 2, false),
                row(1199, now - 20 * h, "LOTTERY", "", "a ticket", -50, -1, 0, "BSP-Spawn", 0, 0, 0, 50, 0, false),
                row(1190, now - 26 * h, "REFUND", "Ana_Admin (panel)", "", -100, 2, 1, "Frostwarf", 0, 0, 1200, 0, 0, false),
                row(1180, now - 27 * h, "BOUGHT", "Land Office", "plot 14", -1200, -1, 0, "BSP-Spawn", 0, 0, 0, 1200, 0, false),
                row(1170, now - 30 * h, "RECEIVED", "Kestrel_88", "for the iron", 640, -1, 0, "Moongrove", 0, 0, 0, 640, 0, true),
                row(1160, now - 33 * h, "DEPOSIT", "", "", 0, 1, 5, "Frostwarf", 0, 0, 0, 0, 0, false),
                row(1150, now - 50 * h, "SHIPPING", "", "a type newer than this page", -20, -1, 0, "Frostwarf", 0, 0, 0, 20, 0, false));
        return "{\"player\":{\"id\":\"" + player + "\",\"name\":\"?\",\"known\":true},\"pieces\":12400,\"coins\":[3,0,2,0,0],"
                + "\"tiers\":[\"copper\",\"gold\",\"diamond\",\"netherite\",\"illyrium\"],\"server\":\"Frostwarf\",\"logDown\":false,\"log\":[" + rows + "],"
                + "\"lots\":[{\"id\":\"lot-7\",\"price\":4800,\"listed\":" + (now - 2 * h) + ",\"ends\":" + (now + 140 * h) + ",\"server\":\"Frostwarf\",\"items\":["
                + "{\"id\":\"minecraft:written_book\",\"n\":1,\"key\":\"item.minecraft.written_book\",\"name\":\"A long story\"},"
                + "{\"id\":\"minecraft:shulker_box\",\"n\":1,\"key\":\"block.minecraft.shulker_box\"}]},"
                + "{\"id\":\"lot-8\",\"price\":2000,\"listed\":" + (now - 30 * h) + ",\"ends\":" + (now + 50 * h) + ",\"server\":\"Moongrove\",\"items\":["
                + "{\"id\":\"minecraft:bow\",\"n\":1,\"key\":\"item.minecraft.bow\",\"ench\":[[\"minecraft:power\",5]]}]}],"
                + "\"orders\":[{\"id\":\"ord-3\",\"item\":{\"id\":\"minecraft:netherite_ingot\",\"n\":1,\"key\":\"item.minecraft.netherite_ingot\"},\"wanted\":4,\"filled\":1,"
                + "\"each\":2400,\"placed\":" + (now - 5 * h) + ",\"ends\":" + (now + 120 * h) + ",\"server\":\"Frostwarf\"}],\"cut\":false,\"marketReadMs\":" + now + "}";
    }

    private static String row(long id, long ms, String type, String with, String detail, long pieces, int tier, int coins, String server, long rp, int rc, long of, long pl, int cl, boolean pay) {
        return "{\"id\":" + id + ",\"ms\":" + ms + ",\"type\":\"" + type + "\",\"with\":\"" + with + "\",\"detail\":\"" + detail + "\",\"pieces\":" + pieces + ",\"tier\":" + tier
                + ",\"coins\":" + coins + ",\"server\":\"" + server + "\",\"refundedPieces\":" + rp + ",\"refundedCoins\":" + rc + ",\"refundOf\":" + of + ",\"piecesLeft\":" + pl
                + ",\"coinsLeft\":" + cl + ",\"payment\":" + pay + "}";
    }

    /** A made-up Market Board and Wanted Board, as {@code market_read} answers. */
    static String demoMarket(long now) {
        JsonObject d = new JsonObject();
        d.addProperty("lotsReadMs", now - 12_000L);
        d.addProperty("ordersReadMs", now - 12_000L);
        d.addProperty("down", false);
        d.addProperty("cut", false);
        JsonArray lots = new JsonArray();
        JsonObject pick = item("minecraft:diamond_pickaxe", 1, "item.minecraft.diamond_pickaxe", "minecraft:efficiency", "5", "minecraft:unbreaking", "3");
        pick.addProperty("dmg", 41);
        pick.addProperty("max", 1561);
        JsonObject box = item("minecraft:purple_shulker_box", 1, "block.minecraft.purple_shulker_box");
        JsonArray in = new JsonArray();
        JsonObject a = item("minecraft:diamond", 24, "item.minecraft.diamond");
        a.addProperty("i", 0);
        JsonObject b = item("minecraft:ancient_debris", 8, "block.minecraft.ancient_debris");
        b.addProperty("i", 1);
        JsonObject c = item("minecraft:totem_of_undying", 1, "item.minecraft.totem_of_undying");
        c.addProperty("i", 13);
        in.add(a);
        in.add(b);
        in.add(c);
        box.add("in", in);
        JsonObject book = item("minecraft:enchanted_book", 1, "item.minecraft.enchanted_book", "minecraft:mending", "1");
        JsonObject named = item("minecraft:netherite_sword", 1, "item.minecraft.netherite_sword", "minecraft:sharpness", "5", "minecraft:looting", "3");
        named.addProperty("name", "Frostbite");
        Object[][] list = {
                {"Kestrel_88", 4800L, 2L * 3_600_000, "Frostwarf", new JsonObject[]{pick, item("minecraft:diamond_shovel", 1, "item.minecraft.diamond_shovel"), item("minecraft:torch", 64, "block.minecraft.torch")}},
                {"Vexa", 12500L, 5L * 3_600_000, "Moongrove", new JsonObject[]{box, item("create:brass_ingot", 64, "item.create.brass_ingot"), item("create:brass_ingot", 64, "item.create.brass_ingot"), item("create:brass_ingot", 40, "item.create.brass_ingot")}},
                {"Willow_Brook", 950L, 26L * 3_600_000, "Boulderbrook", new JsonObject[]{item("minecraft:golden_carrot", 16, "item.minecraft.golden_carrot"), item("minecraft:cooked_beef", 32, "item.minecraft.cooked_beef")}},
                {"TallyHo", 30000L, 50L * 3_600_000, "Boulderbrook", new JsonObject[]{item("minecraft:elytra", 1, "item.minecraft.elytra", "minecraft:unbreaking", "3")}},
                {"RuneSmith", 2200L, 75L * 3_600_000, "BSP-Spawn", new JsonObject[]{book, item("minecraft:enchanted_book", 1, "item.minecraft.enchanted_book", "minecraft:silk_touch", "1"), item("minecraft:enchanted_book", 1, "item.minecraft.enchanted_book", "minecraft:fortune", "3")}},
                {"Old_Oak", 18000L, 100L * 3_600_000, "Stormfall", new JsonObject[]{named}},
                {"Hazel_Moth", 640L, 154L * 3_600_000, "Stormfall", new JsonObject[]{item("create:andesite_alloy", 64, "item.create.andesite_alloy"), item("create:andesite_alloy", 64, "item.create.andesite_alloy"), item("create:andesite_alloy", 64, "item.create.andesite_alloy"),
                        item("create:andesite_alloy", 64, "item.create.andesite_alloy"), item("create:andesite_alloy", 64, "item.create.andesite_alloy"), item("create:andesite_alloy", 64, "item.create.andesite_alloy"),
                        item("create:andesite_alloy", 64, "item.create.andesite_alloy"), item("create:andesite_alloy", 64, "item.create.andesite_alloy"), item("create:andesite_alloy", 64, "item.create.andesite_alloy")}}};
        int n = 0;
        for (Object[] l : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", "lot-" + (++n));
            o.addProperty("seller", uuidOf((String) l[0]));
            o.addProperty("sellerName", (String) l[0]);
            o.addProperty("price", (Long) l[1]);
            o.addProperty("listed", now - (Long) l[2]);
            o.addProperty("ends", now - (Long) l[2] + 7L * 86_400_000L);
            o.addProperty("server", (String) l[3]);
            JsonArray items = new JsonArray();
            for (JsonObject it : (JsonObject[]) l[4]) {
                items.add(it);
            }
            o.add("items", items);
            lots.add(o);
        }
        d.add("lots", lots);
        JsonArray orders = new JsonArray();
        Object[][] wanted = {
                {"ObsidianOtter", item("minecraft:shulker_shell", 1, "item.minecraft.shulker_shell"), 16, 10, 300L, 6L * 86_400_000 + 5 * 3_600_000},
                {"Old_Oak", item("minecraft:golden_carrot", 1, "item.minecraft.golden_carrot"), 128, 0, 6L, 4L * 86_400_000},
                {"Juniper_K", item("minecraft:netherite_ingot", 1, "item.minecraft.netherite_ingot"), 4, 1, 2400L, 2L * 86_400_000},
                {"BrickLayerTom", item("create:brass_ingot", 1, "item.create.brass_ingot"), 64, 52, 18L, 19L * 3_600_000},
                {"Vexa", item("minecraft:ancient_debris", 1, "block.minecraft.ancient_debris"), 32, 20, 900L, 3L * 86_400_000}};
        n = 0;
        for (Object[] w : wanted) {
            JsonObject o = new JsonObject();
            o.addProperty("id", "order-" + (++n));
            o.addProperty("buyer", uuidOf((String) w[0]));
            o.addProperty("buyerName", (String) w[0]);
            o.add("item", (JsonObject) w[1]);
            o.addProperty("wanted", (Integer) w[2]);
            o.addProperty("filled", (Integer) w[3]);
            o.addProperty("each", (Long) w[4]);
            o.addProperty("ends", now + (Long) w[5]);
            o.addProperty("placed", now + (Long) w[5] - 7L * 86_400_000L);
            o.addProperty("server", "Frostwarf");
            orders.add(o);
        }
        d.add("orders", orders);
        return d.toString();
    }

    /** A made-up lottery, as {@code lottery_read} answers; after a demo draw, Kestrel_88 has won and the pot starts again. */
    static String demoLottery(long now) {
        JsonObject d = new JsonObject();
        boolean drawn = drawAt > 0 && now - drawAt >= 6_000L, counting = drawAt > 0 && now - drawAt < 7_000L;
        d.addProperty("pot", drawn ? (now - drawAt - 6_000L) / 4_000L * 5 : 12400);
        d.addProperty("entrants", IN.length);
        d.addProperty("phase", counting ? "COUNTING" : "IDLE");
        JsonArray wins = new JsonArray();
        if (drawn) {
            JsonObject w = new JsonObject();
            w.addProperty("name", "Kestrel_88");
            w.addProperty("pieces", 12400);
            w.addProperty("at", drawAt + 6_000L);
            wins.add(w);
        }
        String[][] past = {{"Mossy_Fen", "11800", "8"}, {"Willow_Brook", "9650", "15"}, {"Pixl", "14200", "22"}, {"Kestrel_88", "8100", "29"}, {"Old_Oak", "10450", "36"},
                {"Juniper_K", "7700", "43"}, {"TallyHo", "12100", "50"}, {"Glimmer", "6800", "57"}, {"RuneSmith", "9900", "64"}, {"Hazel_Moth", "5400", "71"}};
        for (String[] p : past) {
            if (wins.size() >= 10) {
                break;
            }
            JsonObject w = new JsonObject();
            w.addProperty("name", p[0]);
            w.addProperty("pieces", Long.parseLong(p[1]));
            w.addProperty("at", now - Long.parseLong(p[2]) * 86_400_000L);
            wins.add(w);
        }
        d.add("wins", wins);
        JsonArray names = new JsonArray();
        for (String s : IN) {
            names.add(s);
        }
        d.add("names", names);
        return d.toString();
    }
}
