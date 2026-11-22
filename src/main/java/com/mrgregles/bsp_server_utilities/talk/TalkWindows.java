package com.mrgregles.bsp_server_utilities.talk;

import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.network.TalkActionPacket;
import com.mrgregles.bsp_server_utilities.network.TalkAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.TalkLinePacket;
import com.mrgregles.bsp_server_utilities.network.TalkViewPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The private windows open on this server (SU-107; owner decisions D637, D638, D648, D652 R1 and R2): the player's own
 * ({@code /su talk}, the key, the popup) with their open conversation only, and the staff window for a player
 * ({@code /su talk <player>}) with 90 days of their conversations, where they are, who started the open one, and whether
 * they were told yet. While a window is open its lines arrive live, from whichever server they were written on; its SEND,
 * END and close come back as {@link TalkActionPacket}s, checked again here as every request is.
 */
public final class TalkWindows {
    /** Whose conversation each viewer here has open: the player's id and name, and whether it is the staff window. */
    private record Watch(UUID player, String name, boolean staff) {
    }

    private static final Map<UUID, Watch> WATCHING = new ConcurrentHashMap<>();

    private TalkWindows() {
    }

    // ------------------------------------------------------------------ opening

    /** The player's own window; false (nothing opened) when they have no open conversation. */
    public static CompletableFuture<Boolean> openOwn(ServerPlayer p) {
        UUID id = p.getUUID();
        return TalkService.playerView(p.server, id).thenApply(h -> {
            TalkStore.Talk t = h.open();
            if (t == null || p.hasDisconnected()) {
                return false;
            }
            WATCHING.put(id, new Watch(id, p.getGameProfile().getName(), false));
            TalkDelivery.send(p, new TalkViewPacket(false, id, p.getGameProfile().getName(), "", t.openedAt(), t.openedBy(), !t.untold(), entries(h.lines())));
            return true;
        });
    }

    /** The staff window for a player (refused for staff by the command, D641). */
    public static CompletableFuture<TalkViewPacket> openFor(ServerPlayer staff, UUID player, String name) {
        MinecraftServer server = staff.server;
        String where = where(server, player);
        return TalkService.history(server, player).thenApply(h -> {
            TalkStore.Talk open = h.open();
            String shown = h.talks().isEmpty() ? name : h.talks().get(0).name();
            TalkViewPacket v = new TalkViewPacket(true, player, shown, where, open == null ? 0 : open.openedAt(), open == null ? "" : open.openedBy(),
                    open == null || !open.untold(), entries(h.lines()));
            if (!staff.hasDisconnected()) {
                WATCHING.put(staff.getUUID(), new Watch(player, shown, true));
                TalkDelivery.send(staff, v);
            }
            return v;
        });
    }

    /** Where the player is on the network: this server's name, another's, or empty when offline. */
    public static String where(MinecraftServer server, UUID player) {
        for (ServerPlayer p : TalkDelivery.here(server)) {
            if (p.getUUID().equals(player)) {
                return Storage.currentServerId();
            }
        }
        return ClientCheckService.players(server).stream().filter(r -> r.held().id().equals(player)).map(ClientCheckService.Row::server).findFirst().orElse("");
    }

    static TalkViewPacket.Entry entry(long talk, long at, String kind, String staff, String text) {
        byte k = kind.equals(TalkStore.KIND_STAFF) ? TalkViewPacket.STAFF : kind.equals(TalkStore.KIND_PLAYER) ? TalkViewPacket.PLAYER : TalkViewPacket.END;
        return new TalkViewPacket.Entry(talk, at, k, staff, text);
    }

    private static List<TalkViewPacket.Entry> entries(List<TalkStore.Line> lines) {
        return lines.stream().map(l -> entry(l.talk(), l.at(), l.kind(), l.staff(), l.text())).toList();
    }

    // ------------------------------------------------------------------ live lines and the window's keys

    /** A line written anywhere: every window here showing that player's conversation gets it. */
    static void lineFor(Collection<ServerPlayer> here, TalkDelivery.Event e) {
        if (WATCHING.isEmpty()) {
            return;
        }
        TalkLinePacket line = new TalkLinePacket(e.player(), entry(e.talk(), e.at(), e.kind(), e.staff(), e.text()));
        for (ServerPlayer p : here) {
            Watch w = WATCHING.get(p.getUUID());
            if (w != null && w.player().equals(e.player())) { // the end too: the player's window shows "Staff ended the conversation" and closes
                TalkDelivery.send(p, line);
            }
        }
    }

    /** SEND, END or close from a window. */
    public static void onAction(ServerPlayer sender, TalkActionPacket a) {
        UUID me = sender.getUUID();
        if (a.action() == TalkActionPacket.CLOSE) {
            WATCHING.remove(me);
            return;
        }
        Watch w = WATCHING.get(me);
        CompletableFuture<TalkService.Result> r;
        if (a.action() == TalkActionPacket.SEND && a.player().equals(me)) {
            r = TalkService.playerSend(sender, a.text()); // the player's own window: only into an open conversation
        } else if (w == null || !w.staff() || !w.player().equals(a.player())) {
            return; // a staff action for a window that is not open here: nothing to act on
        } else if (a.action() == TalkActionPacket.SEND) {
            r = TalkService.staffSend(sender.server, TalkService.By.of(sender), w.player(), w.name(), a.text());
        } else if (a.action() == TalkActionPacket.END) {
            r = TalkService.end(sender.server, TalkService.By.of(sender), w.player(), w.name());
        } else {
            return;
        }
        r.thenAccept(res -> {
            if (!res.ok() && !sender.hasDisconnected()) {
                TalkDelivery.send(sender, new TalkAnswerPacket(res.outcome().name()));
            }
        });
    }

    public static void onLogout(ServerPlayer p) {
        WATCHING.remove(p.getUUID());
    }

    /** Whose conversation a viewer has open here, or null (tests). */
    @Nullable
    public static UUID watching(UUID viewer) {
        Watch w = WATCHING.get(viewer);
        return w == null ? null : w.player();
    }

    /** Tests: no window open. */
    public static void resetForTests() {
        WATCHING.clear();
    }
}
