package com.mrgregles.bsp_server_utilities.support;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.SupportLinkPacket;
import com.mrgregles.bsp_server_utilities.network.SupportOpenPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Support BSP's settings (owner decisions D587 to D612): one set for the network, the newest revision winning (D604);
 * changed only by those who may edit (operator level 2 or a BSP-Core admin, D602), every change in the Action Log; a
 * link refused with its reason and nothing saved (D603, D612). Every player's client holds the support link (for the
 * inventory key and the popup): sent at login and to everyone when it changes.
 */
public final class SupportService {
    public static final String KIND_SETTINGS = "support_settings";
    private static LongSupplier clock = System::currentTimeMillis;
    private static Consumer<SupportLinkPacket> toEveryone = p -> ServerUtilsNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), p);
    private static java.util.function.BiConsumer<ServerPlayer, SupportOpenPacket> opener = ServerUtilsNetwork::sendTo;

    private SupportService() {
    }

    /** What an edit came to. */
    public enum Outcome { SAVED, UNCHANGED, NOT_ALLOWED, BAD_LINK, BAD_CHAT_LINK }

    /** An edit's answer: what happened, the reason when a link was refused, and the settings in force afterwards. */
    public record Result(Outcome outcome, SupportLinks.Problem problem, SupportSettings settings) {
        public boolean saved() {
            return outcome == Outcome.SAVED;
        }
    }

    public static SupportSettings settings(MinecraftServer server) {
        return SupportData.get(server).settings();
    }

    // ------------------------------------------------------------------ editing

    /**
     * Saves the support link, the chat sentence and the chat line's link together (SAVE in the SUPPORT tab, D612).
     * {@code by} is the admin (refused unless they may edit), null for the console with {@code byName}.
     */
    public static Result edit(MinecraftServer server, @Nullable ServerPlayer by, String byName, String link, String sentence, String chatLink) {
        SupportSettings now = settings(server);
        if (by != null && !Perms.canEdit(by)) {
            return new Result(Outcome.NOT_ALLOWED, SupportLinks.Problem.OK, now);
        }
        SupportLinks.Problem p = SupportLinks.check(link);
        if (p != SupportLinks.Problem.OK) {
            return new Result(Outcome.BAD_LINK, p, now);
        }
        p = SupportLinks.check(chatLink);
        if (p != SupportLinks.Problem.OK) {
            return new Result(Outcome.BAD_CHAT_LINK, p, now);
        }
        String who = by != null ? by.getGameProfile().getName() : byName;
        SupportSettings next = new SupportSettings(link, sentence, chatLink, who, clock.getAsLong(), 0).sanitised();
        if (next.sameAs(now)) {
            return new Result(Outcome.UNCHANGED, SupportLinks.Problem.OK, now);
        }
        next = save(server, next);
        log(server, by, byName, changes(now, next));
        return new Result(Outcome.SAVED, SupportLinks.Problem.OK, next);
    }

    /** Only the support link (the console's {@code /su support link}); the rest kept. */
    public static Result editLink(MinecraftServer server, @Nullable ServerPlayer by, String byName, String link) {
        SupportSettings s = settings(server);
        return edit(server, by, byName, link, s.sentence(), s.chatLink());
    }

    /** Only the chat sentence. */
    public static Result editSentence(MinecraftServer server, @Nullable ServerPlayer by, String byName, String sentence) {
        SupportSettings s = settings(server);
        return edit(server, by, byName, s.link(), sentence, s.chatLink());
    }

    /** Only the chat line's own link. */
    public static Result editChatLink(MinecraftServer server, @Nullable ServerPlayer by, String byName, String chatLink) {
        SupportSettings s = settings(server);
        return edit(server, by, byName, s.link(), s.sentence(), chatLink);
    }

    /** CLEAR (after SURE?) and {@code /su support clear}: all three emptied, the key hidden everywhere. */
    public static Result clear(MinecraftServer server, @Nullable ServerPlayer by, String byName) {
        return edit(server, by, byName, "", "", "");
    }

    /** The change in words for the Action Log. */
    static String changes(SupportSettings was, SupportSettings now) {
        if (now.link().isEmpty() && now.sentence().isEmpty() && now.chatLink().isEmpty()) {
            return "cleared the support link and the support chat line";
        }
        List<String> parts = new ArrayList<>();
        if (!was.link().equals(now.link())) {
            parts.add(now.link().isEmpty() ? "removed the support link" : "set the support link to " + now.link());
        }
        if (!was.sentence().equals(now.sentence())) {
            parts.add(now.sentence().isEmpty() ? "removed the support chat line" : "set the support chat line to \"" + now.sentence() + "\"");
        }
        if (!was.chatLink().equals(now.chatLink())) {
            parts.add(now.chatLink().isEmpty() ? "set the support chat line to use the support link" : "set the support chat line's link to " + now.chatLink());
        }
        return String.join("; ", parts);
    }

    /** Writes new settings for the network with a rising revision; the clients follow when the link changed. */
    private static SupportSettings save(MinecraftServer server, SupportSettings s) {
        SupportData data = SupportData.get(server);
        SupportSettings was = data.settings();
        SupportSettings next = s.withRevision(Math.max(clock.getAsLong(), was.revision() + 1));
        data.setSettings(next);
        Storage st = Storage.active();
        if (st != null) {
            SupportSettings saved = next;
            st.submit(() -> st.backend().saveSupportSettings(saved));
            st.publish(KIND_SETTINGS, next.encode());
        }
        if (!was.link().equals(next.link())) {
            sendToAll(next);
        }
        return next;
    }

    /** Takes a shared copy of the settings when it is newer. */
    public static void apply(MinecraftServer server, SupportSettings shared) {
        SupportData data = SupportData.get(server);
        SupportSettings was = data.settings();
        if (shared.revision() > was.revision()) {
            data.setSettings(shared);
            if (!was.link().equals(shared.link())) {
                sendToAll(shared);
            }
        }
    }

    /** The change in the Action Log: under the admin's role, "Admin" for an admin who is not staff, or the console. */
    private static void log(MinecraftServer server, @Nullable ServerPlayer by, String byName, String text) {
        LogEntry e;
        if (by != null) {
            Optional<Staff.Role> role = ActionLog.tracked(by);
            e = ActionLog.entryFor(by, role.orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.CMD, text);
        } else {
            e = ActionLog.entryForServer(byName, LogEntry.CMD, text);
        }
        ActionLog.record(server, e);
    }

    // ------------------------------------------------------------------ /support

    /** {@code /support}: the popup at the support link; false (nothing opened) when there is none (D595). */
    public static boolean open(ServerPlayer player) {
        SupportSettings s = settings(player.server);
        if (!s.hasLink()) {
            return false;
        }
        opener.accept(player, new SupportOpenPacket(s.link()));
        return true;
    }

    // ------------------------------------------------------------------ players' copies

    public static SupportLinkPacket view(SupportSettings s) {
        return new SupportLinkPacket(s.link());
    }

    public static void sendTo(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, view(settings(player.server)));
    }

    private static void sendToAll(SupportSettings s) {
        toEveryone.accept(view(s));
    }

    // ------------------------------------------------------------------ lifecycle

    public static void onLogin(ServerPlayer player) {
        sendTo(player);
    }

    public static void registerNotices() {
        Storage.on(KIND_SETTINGS, (srv, n) -> {
            SupportSettings s = SupportSettings.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
    }

    /**
     * Takes the network's settings when they are newer; writes ours (and tells the other servers) when the table has
     * none yet or an older copy, as when a save reached the other servers but not the table (audit of SU-89). This
     * server's copy is read here, on the server thread, never on the storage worker.
     */
    public static void onServerStarted(MinecraftServer server) {
        Storage s = Storage.active();
        if (s != null) {
            SupportSettings mine = settings(server);
            s.submit(() -> {
                SupportSettings shared = s.backend().loadSupportSettings();
                if (shared == null || mine.revision() > shared.revision()) {
                    s.backend().saveSupportSettings(mine);
                    if (shared != null) {
                        s.publish(KIND_SETTINGS, mine.encode());
                    }
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    // ------------------------------------------------------------------ tests

    /** A test clock and where the packet to everyone goes (null: to every client). */
    public static void useForTests(LongSupplier testClock, @Nullable Consumer<SupportLinkPacket> everyone) {
        clock = testClock;
        toEveryone = everyone != null ? everyone : p -> ServerUtilsNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), p);
    }

    public static void useReal() {
        useForTests(System::currentTimeMillis, null);
        opener = ServerUtilsNetwork::sendTo;
    }

    /** Where {@code /support} sends its popup (tests). */
    public static void useOpenerForTests(java.util.function.BiConsumer<ServerPlayer, SupportOpenPacket> o) {
        opener = o;
    }

    /** Puts settings back as they were (tests), without a revision, a log line or a notice. */
    public static void restoreForTests(MinecraftServer server, SupportSettings s) {
        SupportData.get(server).setSettings(s);
    }
}
