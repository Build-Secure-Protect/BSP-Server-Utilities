package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.announce.Broadcast;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * The staff panel's bans (SU-44; D255, D350 to D356, D370): {@code ban}, {@code unban} and {@code ban_edit}, for Admins
 * and Owners only (the panel checks first; staff are banned by the Owner alone, D355). The row is written to the shared
 * {@code <prefix>bans}, this server knows it at once, the other servers are told to read the bans again, and a banned
 * player on this server leaves the network with the ban's screen. Each is an Action Log entry.
 *
 * <ul>
 * <li>{@code ban}: {@code player}, {@code name}, {@code lengthMs} (0: permanent; 1 minute to 10 years), {@code reason}
 * (1 to {@link #MAX_REASON}), {@code note} (staff only, up to {@link #MAX_NOTE}).</li>
 * <li>{@code unban}: {@code ban} (its id).</li>
 * <li>{@code ban_edit}: {@code ban}, {@code player} (the ban's own), {@code lengthMs} (from now; 0 permanent; -1 keeps the end), {@code reason}, {@code note}.</li>
 * </ul>
 */
public final class WebBanActions {
    public static final int MAX_REASON = 160, MAX_NOTE = 500;
    public static final long MAX_LENGTH = 3650L * 86_400_000L;
    private static BiFunction<MinecraftServer, UUID, ServerPlayer> lookup = (s, id) -> s.getPlayerList().getPlayer(id);
    private static LongSupplier clock = System::currentTimeMillis;
    private static java.util.function.BiPredicate<MinecraftServer, UUID> staffCheck = WebBanActions::onRoster;

    private WebBanActions() {
    }

    private static final String NOT_ALLOWED = "Bans are for Admins and Owners.", NO_STORAGE = "Bans need the shared database: this server keeps its records alone.";

    /**
     * A ban's staff note: up to {@link #MAX_NOTE} characters with its line breaks kept (audit: it went through the chat
     * cleaner, which cut it to 256 and joined the lines). Control and invisible format characters become spaces.
     */
    public static String cleanNote(String raw) {
        String t = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a7', ' ');
        StringBuilder b = new StringBuilder(t.length());
        t.codePoints().forEach(c -> b.appendCodePoint(c == '\n' ? c : Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? ' ' : c));
        return b.toString().strip();
    }

    private static boolean admin(JsonObject a) {
        String r = str(a, "rank");
        return r.equals("ADMIN") || r.equals("OWNER");
    }

    /** Runs a database step on the storage worker; its result back on the server thread. */
    private static <T> CompletableFuture<T> onStorage(MinecraftServer server, Storage s, java.util.concurrent.Callable<T> step) {
        CompletableFuture<T> out = new CompletableFuture<>();
        s.submit(() -> {
            try {
                T v = step.call();
                server.execute(() -> out.complete(v));
            } catch (Exception e) {
                server.execute(() -> out.completeExceptionally(e));
            }
        });
        return out;
    }

    public static WebActions.Answer ban(MinecraftServer server, JsonObject a, String id, String by) {
        Storage s = Storage.active();
        UUID who = uuid(str(a, "player"));
        String reason = Broadcast.clean(str(a, "reason")).strip(), note = cleanNote(str(a, "note")), name = str(a, "name");
        long length = number(a, "lengthMs");
        String why = !admin(a) ? NOT_ALLOWED : s == null ? NO_STORAGE : who == null || !name.matches("[A-Za-z0-9_]{1,16}") ? "No such player."
                : reason.isEmpty() || reason.length() > MAX_REASON ? "A reason of 1 to " + MAX_REASON + " characters (they see it)."
                : note.length() > MAX_NOTE ? "The note is longer than " + MAX_NOTE + " characters."
                : length != 0 && (length < 60_000L || length > MAX_LENGTH) ? "A ban lasts from a minute to ten years, or for good." : null;
        if (why == null && !str(a, "rank").equals("OWNER") && staff(server, who)) {
            why = name + " is staff: only the Owner bans staff.";
        }
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        long now = clock.getAsLong();
        StorageBackend.Ban draft = new StorageBackend.Ban(0, who, name, reason, note, by, now, length == 0 ? 0 : now + length, 0, "", 0);
        return new WebActions.Answer(id, true, "", null, onStorage(server, s, () -> s.backend().insertBan(draft)).handle((banId, t) -> {
            if (t != null) {
                return new WebActions.Answer(id, false, "The ban could not be written: " + WebActions.cause(t));
            }
            StorageBackend.Ban b = new StorageBackend.Ban(banId, who, name, reason, note, by, now, draft.untilMs(), 0, "", 0);
            WebBans.put(b);
            s.publish(WebBans.KIND, String.valueOf(banId));
            ServerPlayer p = lookup.apply(server, who);
            if (p != null) {
                ProxyKick.kick(p, WebBans.screen(b)); // leaves the network now (D350)
            }
            String len = length == 0 ? "permanent" : WebBans.left(length);
            ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "ban " + name + " (" + len + "): " + reason));
            JsonObject data = new JsonObject();
            data.addProperty("ban", banId);
            return new WebActions.Answer(id, true, name + " is banned from the network (" + len + ")" + (p != null ? " and has left it." : "."), data, null);
        }));
    }

    public static WebActions.Answer unban(MinecraftServer server, JsonObject a, String id, String by) {
        Storage s = Storage.active();
        long banId = number(a, "ban");
        UUID who = uuid(str(a, "player"));
        if (!admin(a) || s == null || banId <= 0 || who == null) {
            return new WebActions.Answer(id, false, !admin(a) ? NOT_ALLOWED : s == null ? NO_STORAGE : "No such ban.");
        }
        if (!str(a, "rank").equals("OWNER") && staff(server, who)) {
            return new WebActions.Answer(id, false, "They are staff: only the Owner lifts a ban on staff."); // as only the Owner gives one (D427)
        }
        long now = clock.getAsLong();
        return new WebActions.Answer(id, true, "", null, onStorage(server, s, () -> {
            StorageBackend.Ban b = s.backend().ban(banId);
            // the ban named must be that player's, so the staff check above was about the right person
            return b != null && b.player().equals(who) && s.backend().liftBan(banId, by, now) ? b : null;
        }).handle((b, t) -> {
            if (t != null || b == null) {
                return new WebActions.Answer(id, false, t != null ? "The ban could not be lifted: " + WebActions.cause(t) : "That ban was lifted or has ended already.");
            }
            WebBans.forget(b.player(), b.id()); // another ban of theirs stays in force (audit)
            WebBans.reload(server);
            s.publish(WebBans.KIND, String.valueOf(banId));
            ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "unban " + b.name()));
            return new WebActions.Answer(id, true, b.name() + " may join again.");
        }));
    }

    public static WebActions.Answer edit(MinecraftServer server, JsonObject a, String id, String by) {
        Storage s = Storage.active();
        long banId = number(a, "ban"), length = number(a, "lengthMs");
        UUID who = uuid(str(a, "player"));
        String reason = Broadcast.clean(str(a, "reason")).strip(), note = cleanNote(str(a, "note"));
        String why = !admin(a) ? NOT_ALLOWED : s == null ? NO_STORAGE : banId <= 0 || who == null ? "No such ban."
                : !str(a, "rank").equals("OWNER") && staff(server, who) ? "They are staff: only the Owner changes a ban on staff."
                : reason.isEmpty() || reason.length() > MAX_REASON ? "A reason of 1 to " + MAX_REASON + " characters (they see it)."
                : note.length() > MAX_NOTE ? "The note is longer than " + MAX_NOTE + " characters."
                : length != 0 && length != -1 && (length < 60_000L || length > MAX_LENGTH) ? "A ban lasts from a minute to ten years, or for good." : null;
        if (why != null) {
            return new WebActions.Answer(id, false, why);
        }
        long now = clock.getAsLong();
        return new WebActions.Answer(id, true, "", null, onStorage(server, s, () -> {
            StorageBackend.Ban b = s.backend().ban(banId);
            if (b == null || !b.activeAt(now) || !b.player().equals(who)) { // the player checked above is the ban's own
                return null;
            }
            long until = length == -1 ? b.untilMs() : length == 0 ? 0 : now + length;
            return s.backend().editBan(banId, until, reason, note, now) ? new StorageBackend.Ban(banId, b.player(), b.name(), reason, note, b.by(), b.atMs(), until, 0, "", now) : null;
        }).handle((b, t) -> {
            if (t != null || b == null) {
                return new WebActions.Answer(id, false, t != null ? "The ban could not be changed: " + WebActions.cause(t) : "That ban was lifted or has ended already.");
            }
            WebBans.forget(b.player(), b.id());
            WebBans.put(b);
            WebBans.reload(server);
            s.publish(WebBans.KIND, String.valueOf(banId));
            String len = b.untilMs() == 0 ? "permanent" : "ends in " + WebBans.left(b.untilMs() - now);
            ActionLog.record(server, ActionLog.entryForServer(by + " (panel)", LogEntry.CMD, "ban edit " + b.name() + " (" + len + "): " + reason));
            return new WebActions.Answer(id, true, b.name() + "'s ban was changed (" + len + ").");
        }));
    }

    /**
     * Staff (D355): a staff role here, or anyone the staff roster knows (online on any server, the staff groups' members
     * in LuckPerms while offline, operators who count as staff), so an Admin cannot ban staff who are elsewhere (audit).
     */
    private static boolean staff(MinecraftServer server, UUID who) {
        ServerPlayer here = lookup.apply(server, who);
        return here != null && Staff.roleOf(here).isPresent() || staffCheck.test(server, who);
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static long number(JsonObject o, String k) {
        try {
            return o.get(k).getAsLong();
        } catch (RuntimeException e) {
            return -2;
        }
    }

    private static boolean onRoster(MinecraftServer server, UUID who) {
        return StaffDirectory.roster(server).stream().anyMatch(m -> m.id().equals(who));
    }

    public static void useForTests(@Nullable BiFunction<MinecraftServer, UUID, ServerPlayer> find, @Nullable LongSupplier testClock) {
        lookup = find != null ? find : (s, id) -> s.getPlayerList().getPlayer(id);
        clock = testClock != null ? testClock : System::currentTimeMillis;
        staffCheck = WebBanActions::onRoster;
    }

    /** Who the roster counts as staff, for tests (LuckPerms is not loaded there). */
    public static void useStaffForTests(java.util.function.BiPredicate<MinecraftServer, UUID> test) {
        staffCheck = test;
    }
}
