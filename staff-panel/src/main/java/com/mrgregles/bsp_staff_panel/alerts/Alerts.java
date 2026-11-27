package com.mrgregles.bsp_staff_panel.alerts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.feed.Feed;
import com.mrgregles.bsp_staff_panel.store.PanelStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The server alerts put together for the running panel (SU-167): settings from the environment (Pterodactyl's variables),
 * the watch fed by every snapshot the feed takes, the servers of the last day from the servers' table, the alerts kept
 * in the panel's database, a tick every 2 seconds, and the mail queue.
 */
public final class Alerts {
    public static final long TICK_MS = 2_000L;

    private final AlertSettings settings;
    private final ServerWatch watch;
    private final MailQueue mail;
    private final Letters letters;
    private final Runnable stop;

    private Alerts(AlertSettings settings, ServerWatch watch, MailQueue mail, Letters letters, Runnable stop) {
        this.settings = settings;
        this.watch = watch;
        this.mail = mail;
        this.letters = letters;
        this.stop = stop;
    }

    /**
     * Starts the alerts. {@code key}: the action key the game servers sign with, to read their rows in the servers'
     * table; null in the demo, which has no table.
     */
    public static Alerts start(PanelStore store, Feed feed, String publicUrl, byte[] key, boolean demo) {
        AlertSettings settings = AlertSettings.fromEnv();
        MailQueue mail = MailQueue.real(settings);
        ExecutorService saves = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "panel-alerts-save");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<String> lastSaveError = new AtomicReference<>("");
        ServerWatch.Saver saver = row -> saves.execute(() -> {
            try {
                store.saveAlert(row);
                lastSaveError.set("");
            } catch (Exception e) {
                if (!String.valueOf(e.getMessage()).equals(lastSaveError.getAndSet(String.valueOf(e.getMessage())))) {
                    System.err.println("[panel] keeping a server alert in the database: " + e.getMessage()); // once, not for every change
                }
            }
        });
        Letters letters = new Letters(publicUrl);
        ServerWatch watch = new ServerWatch(System::currentTimeMillis, settings, PortKnock::knock, mail, saver, letters);
        if (!demo) {
            try {
                for (PanelStore.WebRow r : store.webServers()) {
                    JsonObject o = open(key, r.snapshot());
                    if (o == null || !o.has("server") || !o.get("server").isJsonPrimitive() || !o.get("server").getAsString().equalsIgnoreCase(r.server())) {
                        continue; // only what a game server signed, under its own name
                    }
                    long at = o.has("at") && o.get("at").isJsonPrimitive() ? o.get("at").getAsLong() : r.atMs(); // the signed time
                    watch.seed(o.get("server").getAsString(), at, o);
                }
            } catch (Exception e) {
                System.err.println("[panel] server alerts: the servers' table could not be read at the start (" + e.getMessage() + "); watching the servers as they are heard.");
            }
        }
        try {
            watch.restore(store.alerts(System.currentTimeMillis() - ServerWatch.KEEP_MS));
        } catch (Exception e) {
            System.err.println("[panel] server alerts: earlier alerts could not be read (" + e.getMessage() + ").");
        }
        feed.onSnapshot(watch::heard);
        ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "panel-alerts");
            t.setDaemon(true);
            return t;
        });
        ticks.scheduleWithFixedDelay(() -> {
            try {
                watch.tick();
            } catch (Throwable t) {
                System.err.println("[panel] server alerts: " + t); // the watch keeps going
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        ticks.scheduleWithFixedDelay(() -> saves.execute(() -> {
            try {
                store.pruneAlerts(System.currentTimeMillis() - ServerWatch.KEEP_MS);
            } catch (Exception ignored) {
                // tried again in an hour
            }
        }), 1, 60, TimeUnit.MINUTES);
        System.out.println("Server alerts: watching the game servers; " + settings + ".");
        return new Alerts(settings, watch, mail, letters, () -> {
            ticks.shutdownNow();
            mail.close();
            saves.shutdown();
        });
    }

    /** Tests: alerts made of these parts, with no thread of their own. */
    static Alerts of(AlertSettings settings, ServerWatch watch, MailQueue mail, Letters letters) {
        return new Alerts(settings, watch, mail, letters, mail::close);
    }

    private static JsonObject open(byte[] key, String payload) {
        String json = key == null ? payload : com.mrgregles.bsp_staff_panel.crypto.Signed.open(key, payload);
        if (json == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException bad) {
            return null;
        }
    }

    public ServerWatch watch() {
        return watch;
    }

    public AlertSettings settings() {
        return settings;
    }

    /** The test email (D829), sent now on the caller's thread: null when it went, else why not. */
    public String sendTest(String by) {
        return mail.sendNow(letters.test(System.currentTimeMillis(), by));
    }

    public void stop() {
        stop.run();
    }
}
