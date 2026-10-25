package com.mrgregles.bsp_server_utilities.setup;

import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.afk.AfkService;
import com.mrgregles.bsp_server_utilities.afk.AfkSettings;
import com.mrgregles.bsp_server_utilities.chunks.ChunkService;
import com.mrgregles.bsp_server_utilities.chunks.ChunkSettings;
import com.mrgregles.bsp_server_utilities.despawn.DespawnService;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.SetupCardPacket;
import com.mrgregles.bsp_server_utilities.network.SetupFlagsPacket;
import com.mrgregles.bsp_server_utilities.network.SetupViewPacket;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * The setup guide on this server (owner decisions D143 to D161).
 *
 * <ul>
 *   <li>A few seconds after an operator or BSP-Core admin joins, once the rules gate and popup are done with them, they
 *   are shown the corner card (and a clickable chat line) while anything is left to set up and they have not chosen to
 *   ignore the guide (D143 to D147, D159).</li>
 *   <li>Each page is NEXT (set up), SETUP LATER, TURN OFF FEATURE or, for a feature that is off, TURN ON; closing part
 *   way leaves the pages not reached for later (D158). Changing a feature in its own tab counts as setting it up (D152).</li>
 *   <li>The shared features' progress, the off switches and the admins who ignore the guide are one state for the network
 *   (a table and notices, the newest revision winning); this server's own page (its name and storage) is kept here.</li>
 *   <li>Off switches (D150): rules, the action log, despawn times, the client check, staff online and display panels are
 *   switched here ({@link Switches}); AFK and chunk unloading use their own settings' switch.</li>
 * </ul>
 */
public final class SetupService {
    public static final String KIND_STATE = "setup_state";
    /** Ticks after joining before the card shows (D144). */
    public static final int CARD_DELAY_TICKS = 100;
    private static final Map<UUID, Integer> WAITING = new HashMap<>();
    private static LongSupplier clock = System::currentTimeMillis;
    private static BiConsumer<ServerPlayer, SetupCardPacket> cardSender = SetupService::sendCard;
    private static BiConsumer<ServerPlayer, SetupViewPacket> viewSender = ServerUtilsNetwork::sendTo;

    private SetupService() {
    }

    // ------------------------------------------------------------------ state

    public static SetupState state(MinecraftServer server) {
        return SetupData.get(server).state();
    }

    /** Whether a feature is off: the guide's own switch, or AFK's and chunk unloading's settings. */
    public static boolean isOff(MinecraftServer server, Feature f) {
        return switch (f) {
            case WELCOME -> false;
            case AFK -> !AfkService.settings(server).enabled();
            case CHUNKS -> !ChunkService.settings(server).enabled();
            default -> state(server).off().contains(f);
        };
    }

    /** Where a page stands: off when the feature is off, else as far as its page got. */
    public static SetupState.Status status(MinecraftServer server, Feature f) {
        if (f.perServer()) {
            return SetupData.get(server).welcome();
        }
        if (isOff(server, f)) {
            return SetupState.Status.OFF;
        }
        SetupState.Status s = state(server).of(f);
        return s == SetupState.Status.OFF ? SetupState.Status.DONE : s; // turned on again elsewhere
    }

    public static Map<Feature, SetupState.Status> statuses(MinecraftServer server) {
        Map<Feature, SetupState.Status> out = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            out.put(f, status(server, f));
        }
        return out;
    }

    /** The pages not finished: not reached yet or left for later. */
    public static List<Feature> left(MinecraftServer server) {
        List<Feature> out = new ArrayList<>();
        for (Feature f : Feature.values()) {
            SetupState.Status s = status(server, f);
            if (s == SetupState.Status.NOT_YET || s == SetupState.Status.LATER) {
                out.add(f);
            }
        }
        return out;
    }

    private static void save(MinecraftServer server, SetupState next) {
        SetupData d = SetupData.get(server);
        SetupState s = next.withRevision(Math.max(clock.getAsLong(), d.state().revision() + 1));
        d.setState(s);
        Storage st = Storage.active();
        if (st != null) {
            st.submit(() -> st.backend().saveSetupState(s));
            st.publish(KIND_STATE, s.encode());
        }
        switches(server);
    }

    public static void apply(MinecraftServer server, SetupState shared) {
        SetupData d = SetupData.get(server);
        if (shared.revision() > d.state().revision()) {
            d.setState(shared);
            switches(server);
        }
    }

    /** Puts the network's off switches into force here, and tells every player when they changed. */
    static void switches(MinecraftServer server) {
        boolean rulesWere = Switches.off(Feature.RULES), staffWas = Switches.off(Feature.STAFF), despawnWas = Switches.off(Feature.DESPAWN);
        if (!Switches.set(state(server).off())) {
            return;
        }
        if (Switches.off(Feature.RULES) && !rulesWere) {
            RulesService.releaseAll();
        }
        if (Switches.off(Feature.STAFF) != staffWas) {
            StaffDirectory.touch();
        }
        if (Switches.off(Feature.DESPAWN) != despawnWas) {
            DespawnService.switched(server);
        }
        ServerUtilsNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new SetupFlagsPacket(Switches.all()));
    }

    // ------------------------------------------------------------------ the pages

    private static void set(MinecraftServer server, Feature f, SetupState.Status s) {
        if (f.perServer()) {
            SetupData.get(server).setWelcome(s);
        } else if (state(server).of(f) != s) {
            save(server, state(server).with(f, s));
        }
    }

    /** NEXT: the page is set up (a feature that is off stays off). */
    public static void next(MinecraftServer server, Feature f) {
        if (!isOff(server, f)) {
            set(server, f, SetupState.Status.DONE);
        }
    }

    /** SETUP LATER: the admin is reminded at their next join (D147). */
    public static void later(MinecraftServer server, Feature f) {
        if (!isOff(server, f)) {
            set(server, f, SetupState.Status.LATER);
        }
    }

    /** TURN OFF FEATURE (D150). */
    public static void turnOff(MinecraftServer server, Feature f) {
        if (!f.canTurnOff()) {
            return;
        }
        if (f == Feature.AFK) {
            AfkSettings a = AfkService.settings(server);
            AfkService.setSettings(server, new AfkSettings(false, a.afkMinutes(), a.kick(), a.kickMinutes(), a.message(), a.revision()));
        } else if (f == Feature.CHUNKS) {
            ChunkSettings c = ChunkService.settings(server);
            ChunkService.setSettings(server, new ChunkSettings(false, c.minutes(), c.exemptMods(), c.alwaysLoaded(), c.revision()));
        }
        SetupState s = state(server).with(f, SetupState.Status.OFF);
        save(server, f.ownSwitch() ? s.withOff(f, true) : s);
    }

    /** TURN ON: the feature works again and its page counts as set up. */
    public static void turnOn(MinecraftServer server, Feature f) {
        if (f == Feature.AFK) {
            AfkSettings a = AfkService.settings(server);
            AfkService.setSettings(server, new AfkSettings(true, a.afkMinutes(), a.kick(), a.kickMinutes(), a.message(), a.revision()));
        } else if (f == Feature.CHUNKS) {
            ChunkSettings c = ChunkService.settings(server);
            ChunkService.setSettings(server, new ChunkSettings(true, c.minutes(), c.exemptMods(), c.alwaysLoaded(), c.revision()));
        }
        SetupState s = state(server).with(f, SetupState.Status.DONE);
        save(server, f.ownSwitch() ? s.withOff(f, false) : s);
    }

    /** The guide closed on page {@code at}: that page and those after it not reached yet are left for later (D158). */
    public static void closedAt(MinecraftServer server, Feature at) {
        SetupState s = state(server);
        boolean changed = false;
        for (Feature f : Feature.values()) {
            if (f.ordinal() < at.ordinal() || status(server, f) != SetupState.Status.NOT_YET) {
                continue;
            }
            if (f.perServer()) {
                SetupData.get(server).setWelcome(SetupState.Status.LATER);
            } else {
                s = s.with(f, SetupState.Status.LATER);
                changed = true;
            }
        }
        if (changed) {
            save(server, s);
        }
    }

    /** A feature changed in its own tab counts as set up (D152); one that is off stays off. */
    public static void touched(MinecraftServer server, Feature f) {
        SetupState.Status s = status(server, f);
        if (s == SetupState.Status.NOT_YET || s == SetupState.Status.LATER) {
            set(server, f, SetupState.Status.DONE);
        }
    }

    /** IGNORE SERVER UTILITIES SETUP: this admin is not asked again (D145). */
    public static void ignore(MinecraftServer server, UUID admin) {
        if (!state(server).ignored().contains(admin)) {
            save(server, state(server).withIgnored(admin));
        }
    }

    /** This server's name for the config file, used after a restart (D149); false when it is not a valid name. */
    public static boolean setServerName(String name) {
        String n = name == null ? "" : name.strip();
        if (!n.matches("[A-Za-z0-9_.-]{1,32}")) {
            return false;
        }
        ServerUtilsConfig.STORAGE_SERVER_ID.set(n);
        ServerUtilsConfig.SPEC.save();
        return true;
    }

    // ------------------------------------------------------------------ the card

    public static void onLogin(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, new SetupFlagsPacket(Switches.all()));
        if (Perms.canEdit(player)) {
            WAITING.put(player.getUUID(), CARD_DELAY_TICKS);
        }
    }

    public static void onLogout(ServerPlayer player) {
        WAITING.remove(player.getUUID());
    }

    /** Every tick: an admin whose wait is over and who is past the rules gets the card. */
    public static void tick(MinecraftServer server) {
        if (WAITING.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(WAITING.keySet())) {
            int t = WAITING.get(id) - 1;
            WAITING.put(id, t);
            if (t > 0 || RulesService.busy(id)) {
                continue;
            }
            WAITING.remove(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                offerCard(p);
            }
        }
    }

    /** The card for an admin, if anything is left and they have not ignored the guide; whether it was shown. */
    public static boolean offerCard(ServerPlayer player) {
        MinecraftServer server = player.server;
        if (!Perms.canEdit(player) || state(server).ignored().contains(player.getUUID())) {
            return false;
        }
        List<Feature> left = left(server);
        if (left.isEmpty()) {
            return false;
        }
        boolean first = left.size() == Feature.values().length && left.stream().allMatch(f -> status(server, f) == SetupState.Status.NOT_YET);
        cardSender.accept(player, new SetupCardPacket(true, first, left));
        return true;
    }

    private static void sendCard(ServerPlayer player, SetupCardPacket card) {
        ServerUtilsNetwork.sendTo(player, card);
        MutableComponent line = Component.translatable(card.first() ? "message.bsp_server_utilities.setup.card_first" : "message.bsp_server_utilities.setup.card_left", card.left().size())
                .withStyle(ChatFormatting.GOLD);
        line.append(" ").append(link("message.bsp_server_utilities.setup.now", "/su setup now", ChatFormatting.AQUA));
        line.append(" ").append(link("message.bsp_server_utilities.setup.later", "/su setup later", ChatFormatting.GRAY));
        line.append(" ").append(link("message.bsp_server_utilities.setup.ignore", "/su setup ignore", ChatFormatting.GRAY));
        player.sendSystemMessage(line);
    }

    private static Component link(String key, String command, ChatFormatting colour) {
        return Component.translatable(key).withStyle(Style.EMPTY.withColor(colour).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    // ------------------------------------------------------------------ the guide

    /** Opens the admin panel in the guide, at the first page not finished (Welcome when all are). */
    public static void openGuide(ServerPlayer player) {
        ServerUtilsNetwork.sendTo(player, new SetupCardPacket(false, false, List.of()));
        // the guide's data must reach the client after the panel has opened (with shared storage the panel waits for the database)
        AdminPanel.open(player, () -> {
            List<Feature> left = left(player.server);
            viewSender.accept(player, view(player.server, true, left.isEmpty() ? Feature.WELCOME : left.get(0)));
        });
    }

    public static SetupViewPacket view(MinecraftServer server, boolean open, Feature startAt) {
        Storage st = Storage.active();
        String storage = st == null ? "" : st.status();
        String named = ServerUtilsConfig.getOr(ServerUtilsConfig.STORAGE_SERVER_ID, "");
        return new SetupViewPacket(open, startAt, statuses(server), Storage.currentServerId(), named, st != null, storage, StaffDirectory.knownServers());
    }

    // ------------------------------------------------------------------ lifecycle

    public static void registerNotices() {
        Storage.on(KIND_STATE, (srv, n) -> {
            SetupState s = SetupState.decode(n.payload());
            if (s != null) {
                apply(srv, s);
            }
        });
    }

    public static void onServerStarted(MinecraftServer server) {
        WAITING.clear();
        switches(server);
        Storage s = Storage.active();
        if (s != null) {
            s.submit(() -> {
                SetupState shared = s.backend().loadSetupState();
                if (shared == null) {
                    s.backend().saveSetupState(state(server));
                } else {
                    server.execute(() -> apply(server, shared));
                }
            });
        }
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(LongSupplier testClock, @Nullable BiConsumer<ServerPlayer, SetupCardPacket> cards) {
        clock = testClock;
        cardSender = cards != null ? cards : SetupService::sendCard;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        cardSender = SetupService::sendCard;
        viewSender = ServerUtilsNetwork::sendTo;
    }

    /** Catches the guide's data sent on opening (tests). */
    public static void catchViewsForTests(BiConsumer<ServerPlayer, SetupViewPacket> views) {
        viewSender = views;
    }

    /** Puts the state back as it was (tests), with its off switches. */
    public static void restoreForTests(MinecraftServer server, SetupState s, SetupState.Status welcome) {
        SetupData.get(server).setState(s);
        SetupData.get(server).setWelcome(welcome);
        switches(server);
    }

    /** How many ticks an admin still waits for the card, or -1 (tests). */
    public static int waiting(UUID player) {
        Integer t = WAITING.get(player);
        return t == null ? -1 : t;
    }
}
