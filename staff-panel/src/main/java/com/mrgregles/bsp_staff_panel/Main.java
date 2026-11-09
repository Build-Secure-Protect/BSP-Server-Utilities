package com.mrgregles.bsp_staff_panel;

import com.mrgregles.bsp_staff_panel.auth.AccessJwt;
import com.mrgregles.bsp_staff_panel.auth.AccessKeys;
import com.mrgregles.bsp_staff_panel.auth.Auth;
import com.mrgregles.bsp_staff_panel.crypto.SecretBox;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.feed.RedisListener;
import com.mrgregles.bsp_staff_panel.store.Account;
import com.mrgregles.bsp_staff_panel.store.JdbcStore;
import com.mrgregles.bsp_staff_panel.store.MemoryStore;
import com.mrgregles.bsp_staff_panel.store.PanelStore;
import com.mrgregles.bsp_staff_panel.web.PanelServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The BSP staff web panel: {@code java -jar bsp-staff-panel.jar} in a folder holding {@code panel.properties}. Its
 * console (Pterodactyl's) takes {@code create-owner}, {@code reset-owner}, {@code reset-login}, {@code staff},
 * {@code status} and {@code stop}.
 *
 * <p>{@code --demo} starts a panel to look at on this computer only (127.0.0.1): made-up servers and players, records in
 * memory, no Cloudflare, nothing that reaches a game server. It never reads {@code panel.properties}.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        boolean demo = args.length > 0 && args[0].equals("--demo");
        PanelStore store;
        SecretBox box;
        String publicUrl, host;
        int port;
        PanelServer.Gate gate;
        Feed feed;
        Runnable stopFeed;
        com.mrgregles.bsp_staff_panel.act.Actions actions;
        String actionKey;
        if (demo) {
            store = new MemoryStore();
            box = new SecretBox(SecretBox.newKey());
            host = "127.0.0.1";
            port = args.length > 1 ? Integer.parseInt(args[1]) : 8100;
            publicUrl = "http://localhost:" + port;
            gate = header -> new AccessJwt.Identity(Demo.EMAIL, Long.MAX_VALUE);
            feed = new Feed(System::currentTimeMillis);
            stopFeed = Demo.feed(feed);
            actions = Demo.actions();
            actionKey = "(demo)";
        } else {
            PanelConfig c;
            try {
                c = PanelConfig.load(Path.of(PanelConfig.FILE));
            } catch (PanelConfig.NotReady e) {
                System.out.println(e.getMessage());
                System.exit(1);
                return;
            }
            System.out.println("Starting the " + c);
            store = new JdbcStore(c.dbHost(), c.dbPort(), c.dbName(), c.dbUser(), c.dbPassword(), c.dbSsl(), c.tablePrefix());
            box = new SecretBox(c.secretKey());
            host = c.listenHost();
            port = c.listenPort();
            publicUrl = c.publicUrl();
            AccessKeys keys = AccessKeys.forTeam(c.teamDomain());
            keys.refresh();
            System.out.println(keys.count() > 0 ? "Cloudflare Access: " + keys.count() + " signing keys read." : "Cloudflare Access keys not read yet (" + keys.lastError() + "); retried on the first request.");
            gate = header -> AccessJwt.verify(header, keys::key, c.issuer(), c.aud(), System.currentTimeMillis() / 1000);
            com.mrgregles.bsp_staff_panel.act.RedisSender sender = new com.mrgregles.bsp_staff_panel.act.RedisSender(c.redisHost(), c.redisPort(), c.redisUser(), c.redisPassword());
            PanelStore forNotices = store;
            actions = new com.mrgregles.bsp_staff_panel.act.Actions(c.actionKey(), (uid, at, kind, payload) -> {
                Exception redisFailed = null;
                try {
                    sender.publish(c.serverChannel(), com.mrgregles.bsp_staff_panel.act.Actions.line(uid, at, kind, payload));
                } catch (Exception e) {
                    redisFailed = e; // the table still carries it
                }
                try {
                    forNotices.insertNotice(uid, at, com.mrgregles.bsp_staff_panel.act.Actions.SOURCE, kind, payload); // the servers' safety net
                } catch (Exception e) {
                    if (redisFailed != null) {
                        throw new IllegalStateException("neither Redis nor the database took it (" + redisFailed.getMessage() + "; " + e.getMessage() + ")");
                    }
                }
            }, System::currentTimeMillis);
            com.mrgregles.bsp_staff_panel.act.Actions acts = actions;
            actionKey = java.util.Base64.getEncoder().encodeToString(c.actionKey());
            Feed signedFeed = new Feed(System::currentTimeMillis, c.actionKey()); // only what the game servers signed
            feed = signedFeed;
            java.util.concurrent.atomic.AtomicLong lastRedis = new java.util.concurrent.atomic.AtomicLong();
            RedisListener redis = new RedisListener(c.redisHost(), c.redisPort(), c.redisUser(), c.redisPassword(), c.webChannel(),
                    m -> {
                        if (!acts.acceptMessage(m) && signedFeed.acceptMessage(m)) {
                            lastRedis.set(System.currentTimeMillis());
                        }
                    }).start();
            ScheduledExecutorService poll = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "panel-table");
                t.setDaemon(true);
                return t;
            });
            PanelStore s = store;
            poll.scheduleWithFixedDelay(() -> {
                if (redis.subscribed() && System.currentTimeMillis() - lastRedis.get() < 15_000L) {
                    return; // Redis is carrying snapshots; the table is the fallback (audit: only Redis's own count here)
                }
                try {
                    s.webServers().forEach(r -> signedFeed.accept(r.server(), r.atMs(), r.snapshot()));
                } catch (Exception e) {
                    System.err.println("[panel] reading the servers' table: " + e.getMessage());
                }
            }, 0, 5, TimeUnit.SECONDS);
            stopFeed = () -> {
                redis.close();
                poll.shutdownNow();
            };
        }
        store.init();
        Auth auth = new Auth(store, box, System::currentTimeMillis);
        PanelServer server = new PanelServer(host, port, publicUrl, gate, auth, store, feed, System::currentTimeMillis).withActions(actions).start();
        System.out.println("BSP staff panel listening on " + host + ":" + server.port() + " for " + publicUrl);
        if (demo) {
            Auth.NewLink l = auth.createOwner(Demo.EMAIL, "Demo_Owner");
            System.out.println("DEMO: made-up data, nothing reaches a game server. Set up the demo Owner at " + publicUrl + "/setup#" + l.token());
        }
        console(auth, store, publicUrl, server, stopFeed, actionKey);
    }

    private static void console(Auth auth, PanelStore store, String publicUrl, PanelServer server, Runnable stopFeed, String actionKey) throws Exception {
        System.out.println("Type help for the console's commands.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String[] a = line.trim().split("\\s+");
            try {
                switch (a[0]) {
                    case "" -> {
                    }
                    case "help" -> System.out.println("""
                            create-owner <email> <minecraft name>  an Owner, with a setup link valid 15 minutes
                            reset-owner <email>                    clears that person's authenticator; they set up a new one at their next login
                            reset-login <email>                    clears their password and authenticator; prints a new setup link
                            staff                                  every staff account
                            action-key                             the key every game server needs as webPanel.actionKey
                            status                                 open pages and servers heard from
                            stop                                   stops the panel""");
                    case "create-owner" -> {
                        if (a.length != 3) {
                            System.out.println("create-owner <email> <minecraft name>");
                            continue;
                        }
                        Auth.NewLink l = auth.createOwner(a[1], a[2]);
                        System.out.println("Owner " + l.account().email + " made. Their setup link, valid 15 minutes, works once:");
                        System.out.println(publicUrl + "/setup#" + l.token());
                    }
                    case "reset-owner", "reset-login" -> {
                        if (a.length != 2) {
                            System.out.println(a[0] + " <email>");
                            continue;
                        }
                        Optional<Auth.NewLink> l = auth.resetFromConsole(a[1], a[0].equals("reset-login"));
                        System.out.println(l.map(x -> "Login cleared. New setup link, valid 15 minutes: " + publicUrl + "/setup#" + x.token())
                                .orElse("Authenticator cleared: they set up a new one after their password at their next login."));
                    }
                    case "staff" -> {
                        for (Account x : store.accounts()) {
                            System.out.println((x.active ? "" : "(removed) ") + x.rank.label() + " " + x.mcName + " <" + x.email + ">" + (x.setUp() ? "" : " not set up")
                                    + (x.lockedUntil > System.currentTimeMillis() ? " LOCKED" : ""));
                        }
                    }
                    case "action-key" -> System.out.println("Put this in every game server's config/bsp_server_utilities-network.toml, [webPanel] actionKey = \"" + actionKey + "\"");
                    case "status" -> System.out.println(server.liveCount() + " pages open; on the panel: " + server.onPanel());
                    case "stop" -> {
                        server.stop();
                        stopFeed.run();
                        System.out.println("Stopped.");
                        System.exit(0);
                    }
                    default -> System.out.println("Unknown command; type help.");
                }
            } catch (Auth.Refused e) {
                System.out.println(e.getMessage());
            } catch (Exception e) {
                System.out.println("Failed: " + e.getMessage());
            }
        }
    }
}
