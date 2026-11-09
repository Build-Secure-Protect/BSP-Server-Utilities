package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.feed.RedisListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The feed of the game servers' snapshots, the Redis listener, and the panel's config file (SU-33b). */
class FeedAndConfigTest {
    private static String snap(String server, long at, String players) {
        return "{\"v\":1,\"server\":\"" + server + "\",\"at\":" + at + ",\"mspt\":20,\"max\":100,\"players\":[" + players + "]}";
    }

    @Test
    void the_feed_keeps_each_server_s_newest_and_drops_quiet_servers() {
        AtomicLong clock = new AtomicLong(1_000_000);
        Feed f = new Feed(clock::get);
        int[] changes = {0};
        f.onChange(() -> changes[0]++);
        assertTrue(f.accept("Spawn", clock.get(), snap("Spawn", 10, "{\"name\":\"b\"},{\"name\":\"A\"}")));
        assertFalse(f.accept("Spawn", clock.get(), snap("Spawn", 9, "")), "an older snapshot is ignored");
        assertFalse(f.accept("Spawn", clock.get(), "{\"v\":2,\"players\":[]}"), "another version is ignored");
        assertFalse(f.accept("Spawn", clock.get(), "not json"));
        assertTrue(f.acceptMessage("id\n123\nFrostwarf\nsnapshot\n" + snap("Frostwarf", 5, "")), "a message as it travels on Redis");
        assertFalse(f.acceptMessage("id\n123\nFrostwarf\nchat\n{}"), "other kinds are not snapshots");
        assertEquals(2, changes[0]);
        JsonObject j = f.json();
        assertEquals(2, j.getAsJsonArray("servers").size());
        assertEquals("A", j.getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString(), "players by name, any case");
        clock.addAndGet(Feed.STALE_MS + 1);
        f.accept("Frostwarf", clock.get(), snap("Frostwarf", 6, ""));
        assertEquals(List.of("Frostwarf"), f.live(), "a server quiet for 20 s is gone");
    }

    @Test
    void the_redis_listener_logs_in_subscribes_and_hands_on_messages() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>(), saw = new CopyOnWriteArrayList<>();
        try (ServerSocket fake = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            Thread t = new Thread(() -> {
                try (Socket s = fake.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    for (int cmd = 0; cmd < 2; cmd++) {
                        int n = Integer.parseInt(in.readLine().substring(1));
                        List<String> args = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            in.readLine();
                            args.add(in.readLine());
                        }
                        saw.add(String.join(" ", args));
                        out.write((args.get(0).equals("AUTH") ? "+OK\r\n" : "*3\r\n$9\r\nsubscribe\r\n$" + args.get(1).length() + "\r\n" + args.get(1) + "\r\n:1\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                    String msg = "id\n1\nSpawn\nsnapshot\n{}";
                    out.write(("*3\r\n$7\r\nmessage\r\n$18\r\nbsp_su:network:web\r\n$" + msg.getBytes(StandardCharsets.UTF_8).length + "\r\n" + msg + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // the test checks what arrived
                }
            });
            t.start();
            RedisListener l = new RedisListener("127.0.0.1", fake.getLocalPort(), "", "secret", "bsp_su:network:web", got::add).start();
            long until = System.currentTimeMillis() + 5000;
            while (got.isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            l.close();
            t.join(2000);
        }
        assertEquals(List.of("AUTH secret", "SUBSCRIBE bsp_su:network:web"), saw);
        assertEquals(List.of("id\n1\nSpawn\nsnapshot\n{}"), got);
    }

    @Test
    void the_config_is_written_on_the_first_start_and_checked(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(PanelConfig.FILE);
        PanelConfig.NotReady first = assertThrows(PanelConfig.NotReady.class, () -> PanelConfig.load(f));
        assertTrue(first.getMessage().contains("fill in"));
        String text = Files.readString(f);
        assertTrue(text.contains("secretKey=") && !text.contains("secretKey=\n"), "the panel's key is made at once");
        PanelConfig.NotReady missing = assertThrows(PanelConfig.NotReady.class, () -> PanelConfig.load(f));
        assertTrue(missing.getMessage().contains("cloudflare.teamDomain") && missing.getMessage().contains("cloudflare.aud"), missing.getMessage());
        Files.writeString(f, text.replace("cloudflare.teamDomain=", "cloudflare.teamDomain=https://BSP.cloudflareaccess.com/").replace("cloudflare.aud=", "cloudflare.aud=abc123")
                .replace("database.password=", "database.password=hunter2-secret"));
        PanelConfig c = PanelConfig.load(f);
        assertEquals("https://bsp.cloudflareaccess.com", c.issuer());
        assertEquals("bsp_su:network:web", c.webChannel());
        assertEquals(32, c.secretKey().length);
        assertFalse(c.toString().contains("hunter2"), "passwords never printed");
        Files.writeString(f, Files.readString(f).replace("publicUrl=https://", "publicUrl=http://"));
        assertThrows(PanelConfig.NotReady.class, () -> PanelConfig.load(f), "only an https address");
    }
}
