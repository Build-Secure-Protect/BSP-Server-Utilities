package com.mrgregles.bsp_server_utilities.support;

import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The Support BSP chat line on joining the network (owner decisions D597 to D601): the admin's sentence in gold with the
 * address underlined and clickable (Minecraft's own "open this link?" prompt, following the player's chat settings), to
 * everyone, staff included, on every join to the network and never on a move between its servers. Sent
 * {@value #DELAY_MS} ms after the login, after the proxy's replay of earlier chat; a player at the rules' gate or held
 * by the rules gets it {@value #DELAY_MS} ms after they are let through (after AGREE). Nothing is sent when there is no
 * sentence or no address. Whether it was a move is decided when the line is due, so the old server's leaving, which
 * comes a moment after the login, is known by then; without Redis the line waits as long as another server's notice may
 * take to arrive ({@link Storage#noticeLagMs}). A player who leaves before their line is sent is owed it: the next server
 * they reach within a minute sends it even though that was a move (audit of SU-90).
 */
public final class SupportChat {
    public static final long DELAY_MS = 5_000L;
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static BiConsumer<ServerPlayer, Component> sender = ServerPlayer::sendSystemMessage;
    private static Predicate<UUID> busy = RulesService::busy;
    private static LongSupplier lag = () -> {
        Storage s = Storage.active();
        return s == null ? 0 : s.noticeLagMs();
    };

    private SupportChat() {
    }

    private static final class Pending {
        final ServerPlayer player;
        final NetworkJoins.Arrival arrival;
        long dueMs;

        Pending(ServerPlayer player, NetworkJoins.Arrival arrival, long waitMs) {
            this.player = player;
            this.arrival = arrival;
            this.dueMs = arrival.atMs() + waitMs;
        }

        /** Whether this login gets the line: a join, or a move by a player who left their last server before their line came. */
        boolean wanted() {
            UUID id = player.getUUID();
            return !NetworkJoins.isMove(id, arrival) || NetworkJoins.owed(id, arrival);
        }
    }

    public static void onLogin(ServerPlayer player) {
        PENDING.put(player.getUUID(), new Pending(player, NetworkJoins.arrived(player), DELAY_MS + Math.max(0, lag.getAsLong())));
    }

    /**
     * The server is stopping: everyone still here is told to the other servers as leaving (with any line they are owed)
     * while the storage is still open, since the logouts come after it closes (audit of 0.15.0). Their own logouts
     * later only note it here.
     */
    public static void onServerStopping(java.util.Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            Pending p = PENDING.remove(player.getUUID());
            NetworkJoins.onLogout(player, p != null && p.wanted());
        }
    }

    /** Leaving before the line was sent: owed on the next server, if it would have been sent here. */
    public static void onLogout(ServerPlayer player) {
        Pending p = PENDING.remove(player.getUUID());
        NetworkJoins.onLogout(player, p != null && p.wanted());
    }

    /** Every server tick: lines that are due go out; a player still at the rules waits {@value #DELAY_MS} ms after. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = NetworkJoins.now();
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> e = it.next();
            Pending p = e.getValue();
            if (p.player.hasDisconnected()) {
                it.remove();
            } else if (busy.test(e.getKey())) {
                p.dueMs = Math.max(p.dueMs, now + DELAY_MS);
            } else if (now >= p.dueMs) {
                it.remove();
                if (p.wanted()) {
                    Component line = line(SupportService.settings(server));
                    if (line != null) {
                        sender.accept(p.player, line);
                    }
                    NetworkJoins.sent(e.getKey()); // owed nowhere any more
                }
            }
        }
    }

    /** The chat line for these settings, or null when none is sent (no sentence, or no address). */
    @Nullable
    public static Component line(SupportSettings s) {
        if (!s.hasChatLine()) {
            return null;
        }
        String address = s.chatAddress();
        String sentence = s.sentence();
        MutableComponent out = Component.empty();
        if (!sentence.contains(SupportSettings.LINK)) {
            out.append(Component.literal(sentence + " ").withStyle(ChatFormatting.GOLD)).append(link(address));
            return out;
        }
        int from = 0;
        for (int at = sentence.indexOf(SupportSettings.LINK); at >= 0; at = sentence.indexOf(SupportSettings.LINK, from)) {
            if (at > from) {
                out.append(Component.literal(sentence.substring(from, at)).withStyle(ChatFormatting.GOLD));
            }
            out.append(link(address));
            from = at + SupportSettings.LINK.length();
        }
        if (from < sentence.length()) {
            out.append(Component.literal(sentence.substring(from)).withStyle(ChatFormatting.GOLD));
        }
        return out;
    }

    /** The address, underlined and clickable. */
    private static Component link(String address) {
        return Component.literal(address).withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, address)));
    }

    // ------------------------------------------------------------------ tests

    /** Where the line goes and who counts as at the rules (null: the real ones); no notice lag. */
    public static void useForTests(@Nullable BiConsumer<ServerPlayer, Component> lines, @Nullable Predicate<UUID> atTheRules) {
        sender = lines != null ? lines : ServerPlayer::sendSystemMessage;
        busy = atTheRules != null ? atTheRules : RulesService::busy;
        lag = () -> 0;
    }

    /** How late another server's notice may be (tests). */
    public static void useLagForTests(long ms) {
        lag = () -> ms;
    }

    public static void useReal() {
        useForTests(null, null);
        lag = () -> {
            Storage s = Storage.active();
            return s == null ? 0 : s.noticeLagMs();
        };
    }

    /** Whether a line waits for the player (tests). */
    public static boolean waiting(UUID player) {
        return PENDING.containsKey(player);
    }
}
