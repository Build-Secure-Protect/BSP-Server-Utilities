package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.RedisLink;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The staff web panel's snapshot (SU-32, owner decisions D247, D251 to D253): each player's place, the stats above
 * their hotbar, effects, game mode, ping, AFK time, staff role and team; nothing sent while it is off or without shared
 * storage, then once every few seconds as this server's row; on Redis's web channel and never to the other game
 * servers; and the cost of a snapshot on the server thread.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static final byte[] KEY = new byte[32];

    /** The JSON of a signed text (hex HMAC, a dot, the text), after checking the signature. */
    private static String unsigned(GameTestHelper h, String signed) {
        String text = signed.substring(signed.indexOf('.') + 1);
        h.assertTrue(WebActions.signText(KEY, text).equals(signed), "signed with the key: " + signed.substring(0, Math.min(40, signed.length())));
        return text;
    }

    private static JsonObject only(JsonObject snapshot) {
        JsonArray players = snapshot.getAsJsonArray("players");
        return players.get(0).getAsJsonObject();
    }

    @GameTest(template = EMPTY)
    public void a_snapshot_carries_each_player_s_place_stats_effects_and_team(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        p.setPos(1412.24, 71, -388.5);
        p.setHealth(9);
        p.getFoodData().setFoodLevel(6);
        p.setAirSupply(100);
        p.setExperienceLevels(12);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 2600, 1));
        JsonObject snap = WebSnapshot.build(server, "alpha", List.of(p), 1234L);
        h.assertTrue(snap.get("v").getAsInt() == WebSnapshot.VERSION && snap.get("server").getAsString().equals("alpha") && snap.get("at").getAsLong() == 1234L,
                "the snapshot names its version, server and time: " + snap);
        h.assertTrue(snap.has("mspt") && snap.get("max").getAsInt() == server.getMaxPlayers(), "and the server's tick time and slots");
        JsonObject o = only(snap);
        h.assertTrue(o.get("id").getAsString().equals(p.getUUID().toString()) && o.get("name").getAsString().equals(p.getGameProfile().getName()), "who: " + o);
        h.assertTrue(o.get("dim").getAsString().equals("minecraft:overworld") && o.get("x").getAsDouble() == 1412.2 && o.get("y").getAsDouble() == 71.0
                && o.get("z").getAsDouble() == -388.5, "where, to a tenth of a block (D251): " + o);
        h.assertTrue(o.get("hp").getAsDouble() == 9.0 && o.get("maxHp").getAsDouble() == 20.0 && o.get("food").getAsInt() == 6 && o.get("armor").getAsInt() == 0,
                "health, hunger and armour above the hotbar (D253): " + o);
        h.assertTrue(o.get("air").getAsInt() == 100 && o.get("maxAir").getAsInt() == p.getMaxAirSupply() && o.get("level").getAsInt() == 12, "air and the XP level");
        JsonArray fx = o.getAsJsonArray("effects");
        h.assertTrue(fx.size() == 1 && fx.get(0).getAsJsonObject().get("id").getAsString().equals("minecraft:speed")
                && fx.get(0).getAsJsonObject().get("amp").getAsInt() == 1 && fx.get(0).getAsJsonObject().get("sec").getAsInt() == 130, "effects with level and seconds left: " + fx);
        h.assertTrue(o.get("mode").getAsString().equals("survival") && o.has("ping") && o.get("afkMs").getAsLong() == 0, "game mode, ping, not AFK");
        h.assertTrue(o.get("role").isJsonNull() && o.get("team").isJsonNull(), "no staff role and no team for a plain player: " + o);
        h.assertTrue(!o.has("inventory") && !o.has("ip"), "nothing more than the panel's overview needs");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_skin_address_comes_from_the_profile_and_only_from_mojang(GameTestHelper h) {
        java.util.function.Function<String, com.mojang.authlib.GameProfile> withSkin = url -> {
            com.mojang.authlib.GameProfile g = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Skinny");
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
            g.getProperties().put("textures", new com.mojang.authlib.properties.Property("textures",
                    java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            return g;
        };
        h.assertTrue("https://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef".equals(WebSnapshot.skinUrl(withSkin.apply("http://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef"))),
                "Mojang's address, always as https, for the panel to draw the head");
        h.assertTrue(WebSnapshot.skinUrl(withSkin.apply("http://evil.example/texture/abc")) == null, "any other host is left out");
        h.assertTrue(WebSnapshot.skinUrl(withSkin.apply("https://textures.minecraft.net/other/abc")) == null, "and any other path");
        h.assertTrue(WebSnapshot.skinUrl(withSkin.apply("https://textures.minecraft.net/texture/%22onerror%3D0123456789abcdef")) == null, "nothing but hex after /texture/ (audit)");
        h.assertTrue(WebSnapshot.skinUrl(new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "NoSkin")) == null, "no textures: no head (offline-mode profiles)");
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        h.assertTrue(WebSnapshot.player(h.getLevel().getServer(), p).get("skin").isJsonNull(), "and the snapshot says null");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void nothing_is_sent_while_off_or_unshared_and_on_it_goes_every_few_seconds(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        try {
            WebActions.useForTests(KEY, null, null);
            WebAgent.useForTests(true, 5, s -> List.of(p));
            for (int i = 0; i < 200; i++) {
                WebAgent.tick(server);
            }
            h.assertTrue(WebAgent.lastSentMs() == 0, "on, but without shared storage nothing is sent");

            Storage.useForTests(alpha);
            WebAgent.useForTests(false, 5, null);
            for (int i = 0; i < 200; i++) {
                WebAgent.tick(server);
            }
            alpha.drain();
            h.assertTrue(db.loadWebServers().isEmpty() && WebAgent.lastSentMs() == 0, "off: nothing is sent (the default)");

            WebAgent.useForTests(true, 5, null);
            for (int i = 0; i < 99; i++) {
                WebAgent.tick(server);
            }
            alpha.drain();
            h.assertTrue(db.loadWebServers().isEmpty(), "not before 5 seconds (100 ticks)");
            WebAgent.tick(server);
            alpha.drain();
            List<StorageBackend.WebServer> rows = db.loadWebServers();
            h.assertTrue(rows.size() == 1 && rows.get(0).server().equals("alpha") && WebAgent.lastCount() == 1, "the 100th tick sends this server's row: " + rows);
            JsonObject snap = JsonParser.parseString(unsigned(h, rows.get(0).snapshot())).getAsJsonObject();
            h.assertTrue(only(snap).get("name").getAsString().equals(p.getGameProfile().getName()), "with the player in it, signed with the key");
            long first = rows.get(0).atMs();
            Thread.sleep(2);
            for (int i = 0; i < 100; i++) {
                WebAgent.tick(server);
            }
            alpha.drain();
            rows = db.loadWebServers();
            h.assertTrue(rows.size() == 1 && rows.get(0).atMs() > first, "the next one replaces it: one row per server");

            WebActions.useForTests(null, null, null);
            long before = WebAgent.lastSentMs();
            for (int i = 0; i < 100; i++) {
                WebAgent.tick(server);
            }
            h.assertTrue(WebAgent.lastSentMs() == before, "without webPanel.actionKey nothing is sent (it would be unsigned)");
        } finally {
            WebAgent.useReal();
            WebActions.useReal();
            Storage.useForTests(null);
            alpha.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "web-redis")
    public void the_snapshot_goes_on_the_web_channel_and_never_to_the_other_servers(GameTestHelper h) throws IOException {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        FakeRedis redis = FakeRedis.start("secret");
        Storage alpha = Storage.startWith(server, db, "alpha");
        Storage beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        RedisLink.Settings settings = new RedisLink.Settings("127.0.0.1", redis.port(), "", "secret", "su-web");
        alpha.connectRedis(settings);
        beta.connectRedis(settings);
        List<StorageBackend.Notice> atBeta = new CopyOnWriteArrayList<>();
        beta.onNotice(atBeta::add);
        List<String> atPanel = new CopyOnWriteArrayList<>();
        // the panel listens on the web channel: a Redis link whose channel name is the game servers' plus ":web"
        RedisLink panel = new RedisLink(new RedisLink.Settings("127.0.0.1", redis.port(), "", "secret", "su-web" + RedisLink.WEB_SUFFIX), atPanel::add);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(redis.subscribers(settings.fullChannel()) == 2 && redis.subscribers(settings.webChannel()) == 1
                        && alpha.redis().publishing(), "the servers on their channel, the panel on the web channel"))
                .thenExecute(() -> {
                    WebActions.useForTests(KEY, null, null);
                    WebAgent.send(server, alpha);
                })
                .thenWaitUntil(() -> h.assertTrue(atPanel.size() == 1, "the panel gets the snapshot through Redis"))
                .thenIdle(20)
                .thenExecute(() -> {
                    String[] parts = atPanel.get(0).split("\n", 5);
                    h.assertTrue(parts.length == 5 && parts[2].equals("alpha") && parts[3].equals(WebAgent.KIND_SNAPSHOT), "id, time, server, kind, payload: " + parts.length);
                    JsonObject snap = JsonParser.parseString(unsigned(h, parts[4])).getAsJsonObject();
                    h.assertTrue(snap.get("server").getAsString().equals("alpha") && snap.has("players"), "the payload is the snapshot");
                    beta.pollNotices();
                    beta.drain();
                    h.assertTrue(atBeta.isEmpty(), "the other game server receives nothing, by Redis or by the notice table: " + atBeta);
                })
                .thenExecute(() -> {
                    panel.close();
                    alpha.close();
                    beta.close();
                    redis.close();
                    WebAgent.useReal();
                    WebActions.useReal();
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public void a_snapshot_of_fifty_players_costs_a_few_milliseconds_at_most(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<ServerPlayer> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            p.setPos(i * 10.5, 64, -i * 3.25);
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 4000, 0));
            many.add(p);
        }
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        try {
            WebActions.useForTests(KEY, null, null);
            WebAgent.useForTests(true, 5, s -> many);
            for (int i = 0; i < 20; i++) {
                WebAgent.send(server, alpha); // warm up
            }
            int runs = 100;
            long start = System.nanoTime();
            String json = "";
            for (int i = 0; i < runs; i++) {
                json = WebAgent.send(server, alpha);
            }
            double ms = (System.nanoTime() - start) / 1e6 / runs;
            BSPServerUtilities.LOGGER.info("Staff web panel: a snapshot of 50 players takes {} ms on the server thread and is {} bytes", String.format("%.3f", ms), json.length());
            h.assertTrue(ms < 5.0, "a snapshot of 50 players is cheap on the server thread: " + ms + " ms");
            h.assertTrue(json.length() < 50 * 600, "and small: " + json.length() + " bytes");
        } finally {
            WebAgent.useReal();
            WebActions.useReal();
            alpha.close();
        }
        h.succeed();
    }
}
