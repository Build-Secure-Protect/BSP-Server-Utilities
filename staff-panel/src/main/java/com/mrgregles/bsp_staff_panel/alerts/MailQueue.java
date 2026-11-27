package com.mrgregles.bsp_staff_panel.alerts;

import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Sends the alert emails on a thread of their own (SU-167), never on the watch's: one try, then again after 30 seconds,
 * 2 minutes and 10 minutes, then the alert keeps "email failed" with the mail server's reason (words that never hold the
 * host, the user or the password; {@link Smtp.Failed}).
 */
public final class MailQueue implements ServerWatch.Outbox {
    /** Sends one message; a test gives its own. */
    public interface Sender {
        void send(String subject, String body, ZonedDateTime date) throws Smtp.Failed;
    }

    public static final long[] RETRY_MS = {30_000L, 120_000L, 600_000L};

    private final AlertSettings settings;
    private final Sender sender;
    private final ScheduledExecutorService ex;
    private final long[] retries;
    private final LongSupplier clock;

    public MailQueue(AlertSettings settings, Sender sender, ScheduledExecutorService ex, long[] retries, LongSupplier clock) {
        this.settings = settings;
        this.sender = sender;
        this.ex = ex;
        this.retries = retries.clone();
        this.clock = clock;
    }

    /** The panel's own: the real mail sender, on a thread named panel-mail. */
    public static MailQueue real(AlertSettings settings) {
        Smtp smtp = new Smtp(settings);
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "panel-mail");
            t.setDaemon(true);
            return t;
        });
        return new MailQueue(settings, smtp::send, ex, RETRY_MS, System::currentTimeMillis);
    }

    @Override
    public boolean ready() {
        return settings.ready();
    }

    @Override
    public String why() {
        return settings.whyNot();
    }

    @Override
    public void send(Letters.Letter letter, Consumer<String> done) {
        if (!ready()) {
            done.accept("email is not set up: " + why());
            return;
        }
        ex.execute(() -> attempt(letter, done, 0));
    }

    private void attempt(Letters.Letter letter, Consumer<String> done, int tried) {
        try {
            sender.send(letter.subject(), letter.body(), Letters.date(clock.getAsLong()));
            done.accept(null);
        } catch (Smtp.Failed f) {
            if (tried < retries.length) {
                System.err.println("[panel] an alert email did not go (" + f.getMessage() + "); trying again in " + Letters.length(retries[tried]) + ".");
                ex.schedule(() -> attempt(letter, done, tried + 1), retries[tried], TimeUnit.MILLISECONDS);
            } else {
                System.err.println("[panel] an alert email did not go after " + (tried + 1) + " tries: " + f.getMessage());
                done.accept(f.getMessage());
            }
        } catch (RuntimeException e) {
            done.accept("sending failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** The test email (D829), once, on the caller's thread: null when it went, else why not. */
    public String sendNow(Letters.Letter letter) {
        if (!ready()) {
            return "email is not set up: " + why();
        }
        try {
            sender.send(letter.subject(), letter.body(), Letters.date(clock.getAsLong()));
            return null;
        } catch (Smtp.Failed f) {
            return f.getMessage();
        } catch (RuntimeException e) {
            return "sending failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    public void close() {
        ex.shutdownNow();
    }
}
