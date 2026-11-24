package com.mrgregles.bsp_server_utilities.lag;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.chunks.ChunkCommands;
import com.mrgregles.bsp_server_utilities.compat.BspTeleport;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.LagAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.LagAskPacket;
import com.mrgregles.bsp_server_utilities.network.LagGoPacket;
import com.mrgregles.bsp_server_utilities.network.RoomsViewPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * The LAG tab's questions and answers across the network (SU-149, D748): asked when the tab opens or the map moves to
 * another world, never on a timer. This server answers at once; another server is asked with a notice ({@value #KIND_ASK},
 * naming it, or every server for the summaries), answers with one ({@value #KIND_ANSWER}), and the answer is passed to
 * the editor who asked while the question is open ({@value #OPEN_MS} ms). Only players who may edit are answered; one
 * editor's asks of a kind (and the profiler's START and STOP, each its own kind) are at most one each {@value #ASK_GAP_MS} ms.
 * <p>
 * An answer sent between servers goes to every server and into the notices table, so it is at most
 * {@value #NOTICE_MAX} characters (a map keeps the chunks nearest the middle asked for, a PROFILE its slowest chunks); an
 * answer to an editor on this server may be as long as the packet allows ({@link LagAnswerPacket#MAX_PAYLOAD}). A question
 * that comes more than {@value #ASK_LATE_MS} ms after it was asked (the time it stays open) is not answered, nor a START
 * or STOP more than {@value #LATE_MS} ms old (the table is read back for longer); this needs the servers' clocks to agree
 * (NTP), as the shared storage's own age filter does. The first notice dropped as late is logged once.
 * <p>
 * The open questions and the ask gaps hold players, so they are cleared when the server stops (a single player world's
 * server is not kept after Save and Quit), and a player's gaps when they log out.
 */
public final class LagNetwork {
    public static final String KIND_ASK = "lag_ask", KIND_ANSWER = "lag_answer";
    /** How long a question stays open for its answer; the screen waits as long for GO THERE and TP on another server. */
    public static final long OPEN_MS = 15_000L;
    static final long ASK_GAP_MS = 300L;
    /** The longest answer sent between servers, head included (audit of SU-153). */
    public static final int NOTICE_MAX = 256_000;
    /** How old a START or STOP (or what the profiler tells an editor) from another server may be and still be acted on. */
    public static final long LATE_MS = 60_000L;
    /** How old another server's question may be and still be answered: no older than it stays open where it was asked. */
    public static final long ASK_LATE_MS = OPEN_MS;
    /** What BSP-Teleport shows an editor arriving in a chunk on another server: plain text, as its API takes (a lang key would arrive as the key). */
    public static final String ARRIVED = "You are at the chunk from the LAG tab.";
    /** The gap slots after the ask kinds: the profiler's START and STOP. */
    static final int SAMPLE_SLOT = LagAskPacket.Kind.values().length;
    private static final String SEP = "|";
    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, long[]> LAST_ASK = new HashMap<>();
    private static BiConsumer<ServerPlayer, LagAnswerPacket> sender = LagNetwork::send;
    private static LongSupplier clock = System::currentTimeMillis;
    private static boolean lateLogged;

    private record Pending(ServerPlayer player, LagAskPacket.Kind kind, long until) {
    }

    private LagNetwork() {
    }

    private static void send(ServerPlayer p, LagAnswerPacket packet) {
        ServerUtilsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), packet);
    }

    /** An editor's question from the screen. */
    public static void ask(ServerPlayer player, LagAskPacket a) {
        if (!Perms.canEdit(player)) {
            return;
        }
        if (!pass(player, a.kind().ordinal())) {
            return;
        }
        long now = clock.getAsLong();
        prune(now);
        MinecraftServer server = player.server;
        String here = Storage.currentServerId();
        Storage s = Storage.active();
        boolean local = a.kind() == LagAskPacket.Kind.SUMMARY || a.server().isEmpty() || sameServer(a.server(), here);
        if (local) {
            sender.accept(player, new LagAnswerPacket(a.kind(), RoomsViewPacket.cut(here, 64),
                    payload(server, a.kind(), a.dimension(), a.x(), a.z(), LagAnswerPacket.MAX_PAYLOAD)));
        }
        boolean others = a.kind() == LagAskPacket.Kind.SUMMARY || !local;
        if (others && s != null) {
            String id = UUID.randomUUID().toString();
            PENDING.put(id, new Pending(player, a.kind(), now + OPEN_MS));
            String target = a.kind() == LagAskPacket.Kind.SUMMARY ? "" : a.server();
            s.publish(KIND_ASK, String.join(SEP, id, clean(target), a.kind().name(), clean(a.dimension()), Integer.toString(a.x()), Integer.toString(a.z())));
        }
    }

    /**
     * Whether an editor's ask or START or STOP of one kind ({@code slot}: an ask kind's ordinal, or {@link #SAMPLE_SLOT}
     * plus the action's) is heard: at most one each {@value #ASK_GAP_MS} ms; a closer one is dropped.
     */
    static boolean pass(ServerPlayer player, int slot) {
        long now = clock.getAsLong();
        long[] last = LAST_ASK.computeIfAbsent(player.getUUID(), k -> new long[SAMPLE_SLOT + 2]);
        if (last[slot] != 0 && now - last[slot] < ASK_GAP_MS) {
            return false;
        }
        last[slot] = now;
        return true;
    }

    /** Whether two server names are the same server, as the notices write them ({@code |} and line breaks cleaned). */
    public static boolean sameServer(String a, String b) {
        return clean(a).equals(clean(b));
    }

    /**
     * Whether a notice from another server is too old to act on (more than {@code limitMs} on this clock). The first one
     * dropped is logged once, as clocks that disagree drop every notice.
     */
    static boolean late(StorageBackend.Notice n, long now, long limitMs) {
        long age = now - n.createdAt();
        if (age <= limitMs) {
            return false;
        }
        if (!lateLogged) {
            lateLogged = true;
            BSPServerUtilities.LOGGER.info("BSP-Server Utilities: a LAG notice ({}) from {} was {} s old on arrival and was dropped (at most {} s); "
                    + "if the servers' clocks disagree, set them by NTP. Logged once.", n.kind(), n.source(), age / 1000, limitMs / 1000);
        }
        return true;
    }

    /**
     * GO THERE from the LAG tab (D750, D752), for editors only. A chunk on this server: {@link ChunkCommands#teleport}
     * works the spot out again, moves, logs and says how it went; TP beside a block (the PROFILER, SU-152):
     * {@link ChunkCommands#teleportBeside} the same way. On another server: through BSP-Teleport to the spot that
     * server gave (none installed: the editor is told so and nothing else happens), logged as {@link LogEntry#CHUNK_TP}
     * once BSP-Teleport has taken the move. True when the editor went, or the move to the other server started.
     */
    public static boolean go(ServerPlayer player, LagGoPacket p) {
        if (!Perms.canEdit(player)) {
            return false;
        }
        String here = Storage.currentServerId();
        if (p.server().isEmpty() || sameServer(p.server(), here)) {
            ServerLevel level = LagSnapshot.level(player.server, p.dimension());
            if (level == null) {
                return false;
            }
            return p.beside() ? ChunkCommands.teleportBeside(player, level, new BlockPos(p.x(), p.y(), p.z())) == 1
                    : ChunkCommands.teleport(player, level, p.chunkX(), p.chunkZ()) == 1;
        }
        BspTeleport.Api tp = BspTeleport.api();
        if (tp == null) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.chunks.tp_needs_teleport").withStyle(ChatFormatting.RED));
            return false;
        }
        if (!tp.teleport(player, new BspTeleport.Place(p.server(), p.dimension(), p.x() + 0.5, p.y(), p.z() + 0.5),
                ARRIVED)) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.chunks.tp_other_refused", p.server()).withStyle(ChatFormatting.RED));
            return false;
        }
        ActionLog.record(player.server, ActionLog.entryFor(player, ActionLog.tracked(player).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.CHUNK_TP,
                "went to chunk " + p.chunkX() + ", " + p.chunkZ() + " in " + p.dimension() + " on " + p.server() + " (" + p.x() + " " + p.y() + " " + p.z() + ")"));
        return true;
    }

    /**
     * This server's answer of a kind, in {@link LagSnapshot}'s text form, at most {@code budget} characters: a map keeps the
     * chunks nearest {@code x, z} (the middle of the editor's map, or {@link LagSnapshot#BUSIEST} for both: the world's
     * busiest chunk), a PROFILE the slowest chunks, the rest whole rows.
     */
    static String payload(MinecraftServer server, LagAskPacket.Kind kind, String dimension, int x, int z, int budget) {
        String text = switch (kind) {
            case SUMMARY -> LagSnapshot.encodeSummaries(LagSnapshot.summariesForAsk(server));
            case MAP -> LagSnapshot.encodeMap(dimension, LagSnapshot.map(server, dimension), budget, x, z);
            case DETAIL -> LagSnapshot.encodeDetail(LagSnapshot.detail(server, dimension, x, z));
            case PROFILE -> LagProfiler.profilePayload(budget); // cut by whole chunks, the slowest kept (SU-151)
        };
        if (text.length() > budget) {
            int cut = text.lastIndexOf(';', budget - 1); // whole rows only
            text = text.substring(0, Math.max(0, cut));
        }
        return text;
    }

    static String clean(String s) {
        return s.replace('|', '/').replace('\n', ' ');
    }

    /** An answer of a kind from this server, sent to an editor here at once (the profiler's START and STOP, SU-151). */
    static void sendHere(ServerPlayer player, LagAskPacket.Kind kind, String server, String payload) {
        sender.accept(player, new LagAnswerPacket(kind, RoomsViewPacket.cut(server, 64), payload));
    }

    /** Opens a question of one kind for an editor, answered by one server's {@link #answer}; its id. */
    static String open(ServerPlayer player, LagAskPacket.Kind kind) {
        long now = clock.getAsLong();
        prune(now);
        String id = UUID.randomUUID().toString();
        PENDING.put(id, new Pending(player, kind, now + OPEN_MS));
        return id;
    }

    /** This server's answer to an open question from another server: its head and the payload, {@value #NOTICE_MAX} characters at most. */
    static void answer(Storage s, String id, LagAskPacket.Kind kind, IntFunction<String> payload) {
        String head = String.join(SEP, id, clean(s.serverId()), kind.name()) + "\n";
        s.publish(KIND_ANSWER, head + payload.apply(NOTICE_MAX - head.length()));
    }

    /** Another server's question: answered when it names this server, or every server, and is not late. */
    static void onAsk(MinecraftServer server, StorageBackend.Notice n) {
        Storage s = Storage.active();
        String[] f = n.payload().split("\\|", -1);
        if (s == null || f.length != 6 || late(n, clock.getAsLong(), ASK_LATE_MS)) {
            return;
        }
        if (!f[1].isEmpty() && !f[1].equals(clean(s.serverId()))) {
            return;
        }
        LagAskPacket.Kind kind;
        int x, z;
        try {
            kind = LagAskPacket.Kind.valueOf(f[2]);
            x = Integer.parseInt(f[4]);
            z = Integer.parseInt(f[5]);
        } catch (IllegalArgumentException e) {
            return;
        }
        answer(s, f[0], kind, budget -> payload(server, kind, f[3], x, z, budget));
    }

    /** An answer: passed on to the editor who asked here, while the question is open (the summaries stay open for every server). */
    static void onAnswer(MinecraftServer server, StorageBackend.Notice n) {
        String text = n.payload();
        int nl = text.indexOf('\n');
        if (nl < 0) {
            return;
        }
        String[] f = text.substring(0, nl).split("\\|", -1);
        if (f.length != 3) {
            return;
        }
        long now = clock.getAsLong();
        prune(now);
        Pending p = PENDING.get(f[0]);
        if (p == null || p.player().hasDisconnected()) {
            return;
        }
        if (p.kind() != LagAskPacket.Kind.SUMMARY) {
            PENDING.remove(f[0]); // one server answers a map or a detail
        }
        LagAskPacket.Kind kind;
        try {
            kind = LagAskPacket.Kind.valueOf(f[2]);
        } catch (IllegalArgumentException e) {
            return;
        }
        sender.accept(p.player(), new LagAnswerPacket(kind, RoomsViewPacket.cut(f[1], 64), text.substring(nl + 1)));
    }

    private static void prune(long now) {
        for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.until() < now || p.player().hasDisconnected()) {
                it.remove();
            }
        }
    }

    /** Notice handlers, and a player's ask gaps dropped as they log out; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND_ASK, LagNetwork::onAsk);
        Storage.on(KIND_ANSWER, LagNetwork::onAnswer);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> loggedOut(e.getEntity().getUUID()));
    }

    /** A player logged out: their ask gaps are dropped (their open questions go with the next prune). */
    public static void loggedOut(UUID player) {
        LAST_ASK.remove(player);
    }

    /**
     * The server stops: the open questions and the ask gaps (which hold its players) are dropped, so a single player
     * world's server is not kept after Save and Quit. Where answers go and the clock stay as they are.
     */
    static void serverStopped() {
        PENDING.clear();
        LAST_ASK.clear();
        lateLogged = false;
    }

    /** Tests: where answers go and the clock (null: the real ones); open questions and the ask gap are cleared. */
    public static void useForTests(@Nullable BiConsumer<ServerPlayer, LagAnswerPacket> s, @Nullable LongSupplier c) {
        sender = s != null ? s : LagNetwork::send;
        clock = c != null ? c : System::currentTimeMillis;
        PENDING.clear();
        LAST_ASK.clear();
    }

    /** Tests: this server's answer of a kind within a budget, as another server would get it. */
    public static String payloadForTests(MinecraftServer server, LagAskPacket.Kind kind, String dimension, int x, int z, int budget) {
        return payload(server, kind, dimension, x, z, budget);
    }

    /** Tests: how many questions are open. */
    public static int openForTests() {
        return PENDING.size();
    }

    /** Tests: how many players have an ask gap kept. */
    public static int askersForTests() {
        return LAST_ASK.size();
    }
}
