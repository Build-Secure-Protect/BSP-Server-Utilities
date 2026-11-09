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
        holder[0] = new com.mrgregles.bsp_staff_panel.act.Actions(key, (uid, at, kind, payload) -> new Thread(() -> {
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
            String msg = what.equals("grave_return") ? "Returned 14 stacks and 23 XP (demo: nothing was sent to a game server)." : "Done (demo: nothing was sent to a game server).";
            holder[0].acceptMessage(com.mrgregles.bsp_staff_panel.act.Actions.line("a", at, "answer",
                    com.mrgregles.bsp_staff_panel.crypto.Signed.seal(key, "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"" + msg + "\"" + extra + "}")));
        }).start(), System::currentTimeMillis);
        return holder[0];
    }

    static Runnable feed(Feed feed) {
        Random r = new Random(7);
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(t -> {
            Thread x = new Thread(t, "panel-demo");
            x.setDaemon(true);
            return x;
        });
        s.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            for (String[] server : PLAYERS) {
                JsonObject o = new JsonObject();
                o.addProperty("v", 1);
                o.addProperty("server", server[0]);
                o.addProperty("at", now);
                o.addProperty("mspt", 18 + r.nextInt(20));
                o.addProperty("max", 100);
                o.addProperty("graves", true);
                JsonArray ps = new JsonArray();
                for (int i = 1; i < server.length; i++) {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", java.util.UUID.nameUUIDFromBytes(server[i].getBytes()).toString());
                    p.addProperty("name", server[i]);
                    p.addProperty("dim", "minecraft:overworld");
                    p.addProperty("x", r.nextInt(4000) - 2000);
                    p.addProperty("y", 64);
                    p.addProperty("z", r.nextInt(4000) - 2000);
                    p.addProperty("yaw", r.nextInt(360));
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
        }, 0, 5, TimeUnit.SECONDS);
        return s::shutdownNow;
    }
}
