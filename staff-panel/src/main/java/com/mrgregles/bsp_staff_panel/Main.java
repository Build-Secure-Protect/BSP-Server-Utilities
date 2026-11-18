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
 * memory, no Cloudflare, nothing that reaches a game server. It never reads {@code panel.properties}. {@code --demo <port>
 * <folder>} also serves a BlueMap web folder at {@code /map/}.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // a connection that sends its request slowly is closed after 15 s instead of holding one of the 16 threads (audit)
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        boolean demo = args.length > 0 && args[0].equals("--demo");
        PanelStore store;
        SecretBox box;
        String publicUrl, host;
        int port;
        PanelServer.Gate gate;
        Feed feed;
        Runnable stopFeed;
        com.mrgregles.bsp_staff_panel.act.Actions actions;
        com.mrgregles.bsp_staff_panel.chat.Chat chat;
        String actionKey, mapRoot;
        java.util.function.Supplier<String> redisState = () -> "Redis: not used (demo)";
        if (demo) {
            mapRoot = args.length > 2 ? args[2] : ""; // --demo <port> <BlueMap web folder>: a real map beside the made-up players
            store = new MemoryStore();
            box = new SecretBox(SecretBox.newKey());
            host = "127.0.0.1";
            port = args.length > 1 ? Integer.parseInt(args[1]) : 8100;
            publicUrl = "http://localhost:" + port;
            gate = header -> new AccessJwt.Identity(Demo.EMAIL, Long.MAX_VALUE);
            feed = new Feed(System::currentTimeMillis);
            stopFeed = Demo.feed(feed);
            actions = Demo.actions();
            chat = new com.mrgregles.bsp_staff_panel.chat.Chat(store, null, System::currentTimeMillis);
            Runnable stopDemo = stopFeed, stopChat = Demo.chat(store);
            stopFeed = () -> {
                stopDemo.run();
                stopChat.run();
            };
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
            mapRoot = c.mapRoot();
            store = new JdbcStore(c.dbHost(), c.dbPort(), c.dbName(), c.dbPanelName(), c.dbUser(), c.dbPassword(), c.dbSsl(), c.tablePrefix());
            if (c.dbPanelName().equals(c.dbName())) {
                System.out.println("database.panelName is the shared database: anyone with the game servers' database password can change the panel's accounts. The setup guide's own database and user prevent that.");
            }
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
            java.util.concurrent.atomic.AtomicReference<RedisListener> listening = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> lastSendError = new java.util.concurrent.atomic.AtomicReference<>("");
            actions = new com.mrgregles.bsp_staff_panel.act.Actions(c.actionKey(), (uid, at, kind, payload) -> {
                boolean action = com.mrgregles.bsp_staff_panel.act.Actions.KIND_ACTION.equals(kind);
                RedisListener l = listening.get();
                if (action && (l == null || !l.subscribed())) {
                    // answers come back on Redis only: sent now, it would be done with staff told "no answer" (audit)
                    throw new IllegalStateException("the panel is not connected to Redis (" + (l == null ? "starting" : l.lastError()) + "), so no answer could reach it; nothing was sent");
                }
                Exception redisFailed = null;
                try {
                    sender.publish(c.serverChannel(), com.mrgregles.bsp_staff_panel.act.Actions.line(uid, at, kind, payload));
                    lastSendError.set("");
                } catch (Exception e) {
                    redisFailed = e;
                    if (!String.valueOf(e.getMessage()).equals(lastSendError.getAndSet(String.valueOf(e.getMessage())))) {
                        System.err.println("[panel] Redis did not take a message: " + e.getMessage());
                    }
                    if (action) {
                        // the table alone reaches a listening server only every 30 s, past the action's time (audit)
                        throw new IllegalStateException("Redis did not take it (" + e.getMessage() + "); nothing was sent");
                    }
                }
                try {
                    forNotices.insertNotice(uid, at, com.mrgregles.bsp_staff_panel.act.Actions.SOURCE, kind, payload); // the servers' safety net
                } catch (Exception e) {
                    if (redisFailed != null) {
                        throw new IllegalStateException("neither Redis nor the database took it (" + redisFailed.getMessage() + "; " + e.getMessage() + ")");
                    }
                }
            }, System::currentTimeMillis);
            actions.signWith(new com.mrgregles.bsp_staff_panel.crypto.PanelSigner(c.signingKey())); // only the panel can order anything (D426)
            com.mrgregles.bsp_staff_panel.act.Actions acts = actions;
            actionKey = c.panelKey();
            Feed signedFeed = new Feed(System::currentTimeMillis, c.actionKey()); // only what the game servers signed
            feed = signedFeed;
            actions.refuseWhen(signedFeed::whyNoAnswers);
            com.mrgregles.bsp_staff_panel.chat.Chat signedChat = new com.mrgregles.bsp_staff_panel.chat.Chat(store, c.actionKey(), System::currentTimeMillis);
            chat = signedChat;
            java.util.concurrent.atomic.AtomicLong lastRedis = new java.util.concurrent.atomic.AtomicLong();
            RedisListener redis = new RedisListener(c.redisHost(), c.redisPort(), c.redisUser(), c.redisPassword(), c.webChannel(),
                    m -> {
                        if (!signedChat.acceptMessage(m) && !acts.acceptMessage(m) && signedFeed.acceptMessage(m)) {
                            lastRedis.set(System.currentTimeMillis());
                        }
                    }).start();
            listening.set(redis);
            redisState = redis::status;
            ScheduledExecutorService poll = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "panel-table");
                t.setDaemon(true);
                return t;
            });
            PanelStore s = store;
            java.util.concurrent.atomic.AtomicReference<String> lastTableError = new java.util.concurrent.atomic.AtomicReference<>("");
            poll.scheduleWithFixedDelay(() -> {
                if (redis.subscribed() && System.currentTimeMillis() - lastRedis.get() < 15_000L) {
                    return; // Redis is carrying snapshots; the table is the fallback (audit: only Redis's own count here)
                }
                try {
                    s.webServers().forEach(r -> signedFeed.accept(r.server(), r.atMs(), r.snapshot()));
                    lastTableError.set("");
                } catch (Exception e) {
                    if (!String.valueOf(e.getMessage()).equals(lastTableError.getAndSet(String.valueOf(e.getMessage())))) {
                        System.err.println("[panel] reading the servers' table: " + e.getMessage()); // once, not every 5 s (audit)
                    }
                }
            }, 0, 5, TimeUnit.SECONDS);
            stopFeed = () -> {
                redis.close();
                poll.shutdownNow();
            };
        }
        try {
            store.init();
        } catch (java.sql.SQLException e) {
            System.out.println("The panel cannot use its database: " + e.getMessage()
                    + "\nCheck database.host, .port, .name, .user and .password in " + PanelConfig.FILE + " (from inside the panel's container), then start the panel again.");
            System.exit(1);
            return;
        }
        Auth auth = new Auth(store, box, System::currentTimeMillis);
        com.mrgregles.bsp_staff_panel.talk.StaffTalk talk = new com.mrgregles.bsp_staff_panel.talk.StaffTalk(store, System::currentTimeMillis);
        com.mrgregles.bsp_staff_panel.notes.PlayerNotes notes = new com.mrgregles.bsp_staff_panel.notes.PlayerNotes(store, System::currentTimeMillis);
        com.mrgregles.bsp_staff_panel.items.ItemPictures pictures = new com.mrgregles.bsp_staff_panel.items.ItemPictures(store);
        if (demo) {
            Demo.pictures(store); // made-up item pictures, as an admin's /su icons would store them
            Demo.bans(store);
        }
        final com.mrgregles.bsp_staff_panel.act.Actions marketActions = actions;
        PanelServer server = new PanelServer(host, port, publicUrl, gate, auth, store, feed, System::currentTimeMillis).withActions(actions).withChat(chat).withTalk(talk)
                .withNotes(notes).withPictures(pictures).withMap(mapRoot.isEmpty() ? null : Path.of(mapRoot))
                .withMarket(new com.mrgregles.bsp_staff_panel.market.MarketDesk(feed, () -> marketActions, System::currentTimeMillis))
                .withBank(new com.mrgregles.bsp_staff_panel.market.BankDesk(feed, () -> marketActions, System::currentTimeMillis)).start();
        if (!mapRoot.isEmpty() && !java.nio.file.Files.isRegularFile(Path.of(mapRoot, "index.html"))) {
            System.out.println("The map's folder " + mapRoot + " holds no index.html yet: BlueMap writes it on its first start (or check the mount).");
        }
        Runnable stopCounts = notesToGame(feed, notes, actions), stopBefore = stopFeed;
        stopFeed = () -> {
            stopCounts.run();
            stopBefore.run();
        };
        Runnable stopChat = chatPolling(chat, talk, pictures), stopRest = stopFeed;
        stopFeed = () -> {
            stopChat.run();
            stopRest.run();
        };
        System.out.println("BSP staff panel listening on " + host + ":" + server.port() + " for " + publicUrl);
        if (demo) {
            Auth.NewLink l = auth.createOwner(Demo.EMAIL, "Demo_Owner");
            Demo.talk(store, l.account());
            System.out.println("DEMO: made-up data, nothing reaches a game server. Set up the demo Owner at " + publicUrl + "/setup#" + l.token());
        }
        console(auth, store, publicUrl, server, stopFeed, actionKey, redisState, feed);
    }

    /**
     * The chat table read every 3 seconds for lines Redis did not bring (SU-36), and every hour chat, staff room lines and
     * read messages older than 30 days dropped (D263, D316).
     */
    private static Runnable chatPolling(com.mrgregles.bsp_staff_panel.chat.Chat chat, com.mrgregles.bsp_staff_panel.talk.StaffTalk talk,
                                        com.mrgregles.bsp_staff_panel.items.ItemPictures pictures) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "panel-chat");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.atomic.AtomicReference<String> lastError = new java.util.concurrent.atomic.AtomicReference<>("");
        s.scheduleWithFixedDelay(() -> {
            try {
                chat.poll();
                lastError.set("");
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).equals(lastError.getAndSet(String.valueOf(e.getMessage())))) {
                    System.err.println("[panel] reading the chat table: " + e.getMessage());
                }
            }
        }, 0, 3, TimeUnit.SECONDS);
        s.scheduleWithFixedDelay(() -> {
            try {
                int gone = chat.prune();
                int[] staff = talk.prune(); // the staff room and read messages, 30 days (D316)
                if (gone + staff[0] + staff[1] > 0) {
                    System.out.println("[panel] dropped as older than 30 days: " + gone + " chat lines, " + staff[0] + " staff room lines, " + staff[1] + " read messages.");
                }
            } catch (Exception e) {
                System.err.println("[panel] dropping old chat: " + e.getMessage());
            }
        }, 1, 60, TimeUnit.MINUTES);
        java.util.concurrent.atomic.AtomicReference<String> lastPictures = new java.util.concurrent.atomic.AtomicReference<>("");
        ScheduledExecutorService pics = Executors.newSingleThreadScheduledExecutor(r -> { // its own thread: a big export never holds up chat (audit)
            Thread t = new Thread(r, "panel-pictures");
            t.setDaemon(true);
            return t;
        });
        pics.scheduleWithFixedDelay(() -> { // the newest item pictures (SU-41): checked every minute, read when new
            try {
                pictures.refresh();
                String p = pictures.problem();
                if (!p.isEmpty() && !p.equals(lastPictures.getAndSet(p))) {
                    System.err.println("[panel] " + p);
                }
            } catch (Exception | OutOfMemoryError e) { // never stops for good (audit)
                if (!String.valueOf(e.getMessage()).equals(lastPictures.getAndSet(String.valueOf(e.getMessage())))) {
                    System.err.println("[panel] reading the item pictures: " + e.getMessage());
                }
            }
        }, 0, 60, TimeUnit.SECONDS);
        return () -> {
            s.shutdownNow();
            pics.shutdownNow();
        };
    }

    /**
     * Each online player's notes count, with the newest's author and time, to every game server (D332): a signed notice
     * {@code web_notes} about every 30 seconds, and within a second of a note being added or deleted. Staff in game see it
     * when they hover the player on the CLIENTS or STAFF tab (D321).
     */
    private static Runnable notesToGame(Feed feed, com.mrgregles.bsp_staff_panel.notes.PlayerNotes notes, com.mrgregles.bsp_staff_panel.act.Actions actions) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "panel-notes");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicLong last = new java.util.concurrent.atomic.AtomicLong();
        notes.onNotes(p -> changed.set(true));
        s.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            if (!changed.get() && now - last.get() < 30_000L) {
                return;
            }
            try {
                java.util.Map<String, String> online = new java.util.HashMap<>();
                feed.json().getAsJsonArray("players").forEach(p -> online.put(p.getAsJsonObject().get("id").getAsString(), p.getAsJsonObject().get("name").getAsString()));
                if (online.isEmpty()) {
                    return;
                }
                com.google.gson.JsonObject counts = notes.counts(new java.util.ArrayList<>(online.keySet()));
                counts.entrySet().forEach(e -> e.getValue().getAsJsonObject().addProperty("name", online.get(e.getKey()))); // the STAFF tab finds staff by name
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("type", "web_notes"); // never read as an action, which carries the same signature
                o.addProperty("v", 1);
                o.add("counts", counts);
                actions.notice("web_notes", o);
                changed.set(false);
                last.set(now);
            } catch (Exception e) {
                System.err.println("[panel] sending the notes counts: " + e.getMessage());
            }
        }, 2, 1, TimeUnit.SECONDS);
        return s::shutdownNow;
    }

    private static void console(Auth auth, PanelStore store, String publicUrl, PanelServer server, Runnable stopFeed, String actionKey,
                                java.util.function.Supplier<String> redisState, Feed feed) throws Exception {
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
                            action-key                             the panel's public key, every game server's webPanel.panelKey
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
                    case "action-key" -> System.out.println("The panel's public key, for every game server's config/bsp_server_utilities-network.toml, [webPanel] panelKey = \""
                            + actionKey + "\"\n(and actionKey from " + PanelConfig.FILE + ", which is not printed here)");
                    case "status" -> {
                        System.out.println(server.liveCount() + " pages open; on the panel: " + server.onPanel());
                        System.out.println(redisState.get());
                        System.out.println("Game servers sending: " + (feed.live().isEmpty() ? "none" : String.join(", ", feed.live()))
                                + feed.live().stream().map(x -> feed.whyNoAnswers(x)).filter(java.util.Objects::nonNull).map(x -> "\n  " + x).collect(java.util.stream.Collectors.joining()));
                    }
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
