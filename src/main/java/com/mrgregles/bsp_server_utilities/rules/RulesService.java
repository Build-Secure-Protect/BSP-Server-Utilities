package com.mrgregles.bsp_server_utilities.rules;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.network.RulesAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.RulesOpenPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/**
 * The rules popup's rules (owner decisions D7 to D13, D31 to D37, R1 to R3), all on the server thread.
 *
 * <p><b>At login</b> a player passes a gate: their agreement is read (from the database with shared storage, else from
 * the world) and they settle in (BSP-InventorySync's restore event when that mod is loaded, with a timeout, else a short
 * delay). Then, if there are rules and they have not agreed to the current version, they are <b>held</b>: kept in place,
 * unable to act or be hurt ({@link RulesHold}), and sent the popup. <b>AGREE</b> is taken only for the version and
 * revision they were shown with every rule ticked; it is recorded for the network and the hold ends. <b>LEAVE SERVER</b>
 * disconnects them with the admin's message. <b>Ask everyone again</b> raises the version and holds every player online
 * at once (R3); an edit resends the popup to anyone held. With shared storage the rules are one row set for the
 * network (the newest revision wins) and every change goes out as a notice.
 */
public final class RulesService {
    public static final String KIND_RULES = "rules", KIND_ASK = "rules_ask", KIND_RESET = "rules_reset";
    /** A move of this size while held is a teleport (a landing), which moves the anchor instead of being undone. */
    private static final double TELEPORT_DISTANCE_SQ = 16.0;
    /** How long a settled player's agreement may take to come back from the database before the world's copy is used. */
    private static final int KNOWN_TIMEOUT_TICKS = 200;
    private static final String SYNC_MOD = "bsp_inventorysync";
    private static final String RESTORE_EVENT = "com.mrgregles.bsp_inventorysync.api.event.InventoryRestoredEvent";

    /** What an answer did. */
    public enum Outcome { AGREED, NOT_HELD, CHANGED, NOT_ALL_TICKED, LEFT }

    private static final class Gate {
        final ServerPlayer player;
        int ticksLeft, waited;
        boolean settled, known;

        Gate(ServerPlayer player, int ticks) {
            this.player = player;
            this.ticksLeft = ticks;
        }
    }

    private static final class Hold {
        final ServerPlayer player;
        Vec3 anchor;
        ResourceKey<Level> dimension;

        Hold(ServerPlayer player) {
            this.player = player;
            this.anchor = player.position();
            this.dimension = player.level().dimension();
        }
    }

    private static final Map<UUID, Gate> GATES = new HashMap<>();
    private static final Map<UUID, Hold> HELD = new HashMap<>();
    private static boolean restoreHooked;
    /** How LEAVE SERVER disconnects; tests swap it (a mock player's connection cannot be closed). */
    private static BiConsumer<ServerPlayer, Component> disconnector = (p, message) -> p.connection.disconnect(message);

    private RulesService() {
    }

    public static boolean enabled() {
        return ServerUtilsConfig.getOr(ServerUtilsConfig.RULES_ENABLED, true);
    }

    public static RuleSet rules(MinecraftServer server) {
        return RulesData.get(server).rules();
    }

    @Nullable
    public static Agreement agreement(MinecraftServer server, UUID player) {
        return RulesData.get(server).agreement(player);
    }

    /** Whether the player must still agree: there are rules and they have not agreed to the current version. */
    public static boolean needsAgreement(MinecraftServer server, UUID player) {
        RuleSet set = rules(server);
        if (!enabled() || set.isEmpty()) {
            return false;
        }
        Agreement a = agreement(server, player);
        return a == null || a.version() < set.version();
    }

    public static boolean isHeld(UUID player) {
        return HELD.containsKey(player);
    }

    // ------------------------------------------------------------------ editing (the RULES tab, /su rules)

    /** Changes the rules (not the version); the result is sanitised, saved, sent to the other servers and shown to anyone held. */
    public static RuleSet edit(MinecraftServer server, UnaryOperator<RuleSet> change) {
        RulesData data = RulesData.get(server);
        RuleSet old = data.rules();
        RuleSet next = change.apply(old).withVersion(old.version()).sanitised().withRevision(nextRevision(old));
        data.setRules(next);
        writeThrough(next, KIND_RULES);
        afterRulesChanged(server, next);
        return next;
    }

    /** Raises the version: everyone must agree again; players online now are asked at once (R3). */
    public static RuleSet askEveryoneAgain(MinecraftServer server) {
        RulesData data = RulesData.get(server);
        RuleSet old = data.rules();
        RuleSet next = old.withVersion(old.version() + 1).withRevision(nextRevision(old));
        data.setRules(next);
        writeThrough(next, KIND_ASK);
        BSPServerUtilities.LOGGER.info("Rules version {}: every player is asked to agree again", next.version());
        recheckOnline(server);
        return next;
    }

    /** Forgets one player's agreement, so they are asked again (at once when online on any server). */
    public static void reset(MinecraftServer server, UUID player) {
        RulesData.get(server).putAgreement(player, null);
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> s.backend().saveAgreement(player, null));
            s.publish(KIND_RESET, player.toString());
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            check(online);
        }
    }

    /** How many players agreed to the current version: across the network with shared storage, else in this world. */
    public static void countAgreed(MinecraftServer server, IntConsumer then) {
        int version = rules(server).version();
        Storage s = Storage.active();
        if (s == null) {
            then.accept(RulesData.get(server).countAgreed(version));
            return;
        }
        s.submit(() -> {
            int n = s.backend().countAgreed(version);
            server.execute(() -> then.accept(n));
        });
    }

    private static long nextRevision(RuleSet old) {
        return Math.max(System.currentTimeMillis(), old.revision() + 1);
    }

    private static void writeThrough(RuleSet set, String kind) {
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> s.backend().saveRules(set));
            s.publish(kind, Long.toString(set.revision()));
        }
    }

    private static void afterRulesChanged(MinecraftServer server, RuleSet set) {
        for (Hold h : new ArrayList<>(HELD.values())) {
            if (set.isEmpty() || !enabled()) {
                release(h.player.getUUID());
            } else {
                showPopup(h.player);
            }
        }
    }

    private static void recheckOnline(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!GATES.containsKey(p.getUUID())) {
                check(p);
            }
        }
    }

    /** Holds the player and shows the popup if they must agree (and are not held yet); used after the gate and on a recheck. */
    public static void check(ServerPlayer player) {
        if (needsAgreement(player.server, player.getUUID())) {
            if (!isHeld(player.getUUID())) {
                HELD.put(player.getUUID(), new Hold(player));
            }
            showPopup(player);
        }
    }

    private static void release(UUID player) {
        HELD.remove(player);
    }

    private static void showPopup(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, RulesOpenPacket.of(rules(player.server), true, 0));
    }

    /** {@code /rules}: the read-only copy, or the popup itself for a player who is held. */
    public static void showReadOnly(ServerPlayer player) {
        if (isHeld(player.getUUID())) {
            showPopup(player);
            return;
        }
        Agreement a = agreement(player.server, player.getUUID());
        ServerUtilsNetwork.sendTo(player, RulesOpenPacket.of(rules(player.server), false, a == null ? 0 : a.agreedAtMs()));
    }

    // ------------------------------------------------------------------ the player's answer

    public static Outcome answer(ServerPlayer player, RulesAnswerPacket packet) {
        UUID id = player.getUUID();
        if (!isHeld(id)) {
            return Outcome.NOT_HELD;
        }
        RuleSet set = rules(player.server);
        if (packet.action() == RulesAnswerPacket.Action.LEAVE) {
            String message = set.leaveMessage().isEmpty() ? RuleSet.DEFAULT_LEAVE : set.leaveMessage();
            release(id);
            disconnector.accept(player, Component.literal(message));
            return Outcome.LEFT;
        }
        if (packet.version() != set.version() || packet.revision() != set.revision()) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.rules.changed"));
            showPopup(player);
            return Outcome.CHANGED;
        }
        Set<Integer> ticked = new HashSet<>(packet.ticked());
        for (int i = 0; i < set.rules().size(); i++) {
            if (!ticked.contains(i)) {
                showPopup(player);
                return Outcome.NOT_ALL_TICKED;
            }
        }
        Agreement a = new Agreement(set.version(), System.currentTimeMillis(), Storage.currentServerId(), player.getGameProfile().getName());
        RulesData.get(player.server).putAgreement(id, a);
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> s.backend().saveAgreement(id, a));
        }
        release(id);
        player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.rules.agreed"));
        return Outcome.AGREED;
    }

    // ------------------------------------------------------------------ login, settling in, the hold

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            login(player);
        }
    }

    /** Starts the gate for a player who just logged in. */
    public static void login(ServerPlayer player) {
        if (!enabled()) {
            return;
        }
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        Gate gate = new Gate(player, waitsForRestore()
                ? ServerUtilsConfig.getOr(ServerUtilsConfig.RULES_RESTORE_TIMEOUT_TICKS, 400)
                : ServerUtilsConfig.getOr(ServerUtilsConfig.RULES_DELAY_TICKS, 40));
        GATES.put(id, gate);
        Storage s = Storage.active();
        if (s == null) {
            gate.known = true;
            return;
        }
        s.submit(() -> {
            Agreement a = s.backend().loadAgreement(id);
            server.execute(() -> {
                RulesData.get(server).putAgreement(id, a);
                Gate g = GATES.get(id);
                if (g != null) {
                    g.known = true;
                    decide(g);
                }
            });
        });
    }

    /** The player has settled in (their inventory arrived): the gate may open. */
    public static void settled(ServerPlayer player) {
        Gate g = GATES.get(player.getUUID());
        if (g != null) {
            g.settled = true;
            decide(g);
        }
    }

    private static void decide(Gate g) {
        if (!g.settled || !g.known) {
            return;
        }
        GATES.remove(g.player.getUUID());
        check(g.player);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity().getUUID());
    }

    public static void forget(UUID player) {
        GATES.remove(player);
        HELD.remove(player);
    }

    /** Every server tick: gates count down, and held players are kept where they are. */
    public static void tick(MinecraftServer server) {
        if (!GATES.isEmpty()) {
            for (Gate g : new ArrayList<>(GATES.values())) {
                if (!g.settled && --g.ticksLeft <= 0) {
                    g.settled = true;
                }
                if (g.settled && !g.known && ++g.waited > KNOWN_TIMEOUT_TICKS) {
                    g.known = true; // the database did not answer: the world's copy decides
                }
                decide(g);
            }
        }
        for (Hold h : HELD.values()) {
            holdStill(h);
        }
    }

    /** Keeps a held player where the hold began; a jump of a teleport's size (a landing) moves the anchor instead. */
    private static void holdStill(Hold h) {
        ServerPlayer player = h.player;
        Vec3 pos = player.position();
        if (player.level().dimension() != h.dimension || pos.distanceToSqr(h.anchor) >= TELEPORT_DISTANCE_SQ) {
            h.anchor = pos;
            h.dimension = player.level().dimension();
            return;
        }
        if (pos.distanceToSqr(h.anchor) > 1.0e-4) {
            player.teleportTo(h.anchor.x, h.anchor.y, h.anchor.z);
        }
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static boolean waitsForRestore() {
        return restoreHooked && ModList.get().isLoaded(SYNC_MOD);
    }

    /** Listens to BSP-InventorySync's restore event by class name, as BSP-Core's kits do; once, at server start. */
    public static void hookRestore() {
        if (restoreHooked || !ModList.get().isLoaded(SYNC_MOD)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<Event> type = (Class<Event>) Class.forName(RESTORE_EVENT);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, type, e -> {
                if (e instanceof PlayerEvent pe && pe.getEntity() instanceof ServerPlayer player) {
                    settled(player);
                }
            });
            restoreHooked = true;
            BSPServerUtilities.LOGGER.info("The rules popup waits for BSP-InventorySync's restore event");
        } catch (ClassNotFoundException | LinkageError e) {
            BSPServerUtilities.LOGGER.warn("BSP-InventorySync is loaded but its restore event was not found; the rules popup shows {} ticks after login", ServerUtilsConfig.getOr(ServerUtilsConfig.RULES_DELAY_TICKS, 40));
        }
    }

    // ------------------------------------------------------------------ the network

    /** The notice handlers for the other servers' rule changes; once, when the mod is constructed. */
    public static void registerNotices() {
        Storage.on(KIND_RULES, (srv, n) -> reloadShared(srv, false));
        Storage.on(KIND_ASK, (srv, n) -> reloadShared(srv, true));
        Storage.on(KIND_RESET, (srv, n) -> {
            try {
                UUID id = UUID.fromString(n.payload());
                RulesData.get(srv).putAgreement(id, null);
                ServerPlayer online = srv.getPlayerList().getPlayer(id);
                if (online != null) {
                    check(online);
                }
            } catch (IllegalArgumentException ignored) {
                // not a player id
            }
        });
    }

    /** At server start, after the storage: reads the shared rules (a newer revision replaces ours; none yet means ours are written). */
    public static void onServerStarted(MinecraftServer server) {
        hookRestore();
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                RuleSet shared = s.backend().loadRules();
                if (shared == null) {
                    RuleSet local = RulesData.get(server).rules();
                    s.backend().saveRules(local);
                } else {
                    server.execute(() -> apply(server, shared, false));
                }
            });
        }
    }

    /** Re-reads the shared rules after another server's notice; {@code recheck} after "ask everyone again". */
    public static void reloadShared(MinecraftServer server, boolean recheck) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        s.submit(() -> {
            RuleSet shared = s.backend().loadRules();
            if (shared != null) {
                server.execute(() -> apply(server, shared, recheck));
            }
        });
    }

    /** Takes a shared copy when it is newer than ours; then holds whoever must agree when asked to recheck. */
    public static void apply(MinecraftServer server, RuleSet shared, boolean recheck) {
        RulesData data = RulesData.get(server);
        if (shared.revision() > data.rules().revision()) {
            data.setRules(shared);
            afterRulesChanged(server, shared);
        }
        if (recheck) {
            recheckOnline(server);
        }
    }

    // ------------------------------------------------------------------ tests

    /** Tests: LEAVE SERVER reports here instead of closing a connection. */
    public static void useDisconnectorForTests(BiConsumer<ServerPlayer, Component> d) {
        disconnector = d;
    }

    public static void useRealDisconnector() {
        disconnector = (p, message) -> p.connection.disconnect(message);
    }

    /** Tests: the players held now. */
    public static List<UUID> heldPlayers() {
        return List.copyOf(HELD.keySet());
    }
}
