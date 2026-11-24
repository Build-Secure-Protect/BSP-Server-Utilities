package com.mrgregles.bsp_server_utilities.tutorial;

import com.mrgregles.bsp_server_utilities.compat.BspTeleport;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TutorialAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialGuidePacket;
import com.mrgregles.bsp_server_utilities.network.TutorialOfferPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialPopupPacket;
import com.mrgregles.bsp_server_utilities.players.Joined;
import com.mrgregles.bsp_server_utilities.players.PlayersData;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Players going through tutorials (SU-138, SU-139; owner decisions D720 to D739), all on the server thread.
 *
 * <p><b>The offer.</b> When a player has passed the rules popup (straight after login when there is nothing to agree
 * to; never while the rules hold them) and their places in the tutorials are known, a tutorial under way is resumed;
 * else the published first-join tutorial is offered once if they may have it: "new players" (D721, D743) are those
 * whose first join on the network is from the moment it became the first-join tutorial (or who are not on the
 * player counter's list at all), "everyone who has not finished it" is anyone without a finished or declined place.
 * A start area offers its tutorial each time the player walks in (D738): not after NO THANKS, nor once finished.
 * YES starts it; LATER asks again at the next login (or the next walk-in); NO THANKS says how to start it later.
 *
 * <p><b>The flow.</b> A point with no place shows its popup at once; a point here shows its popup when the player
 * comes within its radius (D729), the golden path from the point before leading there; a point on another server or in
 * another world is reached through TAKE ME THERE (D730). Buttons (D728): NEXT and CLOSE go to the next point (the first
 * of a fork's), GO TO BRANCH to the one it names, PREVIOUS back one, END TUTORIAL ends it; a popup that keeps the
 * player walking moves on when it has faded (D739). The last point's NEXT finishes it. Places are kept for the network
 * (D733), so a logout or another server resumes where they were.
 */
public final class TutorialRunner {
    private static final int CHECK_TICKS = 10;
    private static LongSupplier clock = System::currentTimeMillis;
    private static BiConsumer<ServerPlayer, Object> sender = ServerUtilsNetwork::sendTo;
    private static int ticks;

    /** An online player's state here. */
    static final class Live {
        ServerPlayer player;
        /** The point whose guide was last sent ("tutorial|point"), so arriving in its world sends it. */
        String guided = "";
        boolean known, rulesDone, firstJoinChecked;
        @Nullable
        String offering;
        /** The popup hidden with Escape ("tutorial|point"): not shown again until /tutorial resume. */
        String hidden = "";
        @Nullable
        String showingTutorial;
        int showingPoint = -1;
        final Set<String> inside = new HashSet<>();

        Live(ServerPlayer player) {
            this.player = player;
        }
    }

    private static final Map<UUID, Live> LIVE = new HashMap<>();

    private TutorialRunner() {
    }

    /** The setup guide's switch (D736): off until a tutorial is first published, and when an admin turns it off. */
    public static boolean enabled(MinecraftServer server) {
        return !Switches.off(Feature.TUTORIAL) && !TutorialService.list(server, TutorialStage.PUBLISHED).isEmpty();
    }

    // ------------------------------------------------------------------ places in the tutorials

    @Nullable
    public static TutorialProgress progress(MinecraftServer server, UUID player, String tutorial) {
        return ProgressData.get(server).get(player, tutorial);
    }

    /** The player's tutorial under way (only one at a time), if any. */
    public static Optional<TutorialProgress> active(MinecraftServer server, UUID player) {
        return ProgressData.get(server).of(player).stream().filter(TutorialProgress::active).findFirst();
    }

    private static void put(MinecraftServer server, TutorialProgress given) {
        // times keep rising for a player and tutorial whatever the servers' clocks say (audit of SU-146)
        TutorialProgress old = ProgressData.get(server).get(given.player(), given.tutorial());
        TutorialProgress p = old != null && given.atMs() <= old.atMs()
                ? new TutorialProgress(given.player(), given.tutorial(), given.state(), given.point(), given.reached(), given.history(), old.atMs() + 1) : given;
        ProgressData data = ProgressData.get(server);
        data.put(p);
        Storage s = Storage.active();
        if (s != null) {
            data.markUnwritten(p.player() + "|" + p.tutorial());
            s.submit(() -> {
                s.backend().saveTutorialProgress(p);
                server.execute(() -> data.written(p.player(), p.tutorial(), p.atMs()));
            });
        }
    }

    private static long now() {
        return clock.getAsLong();
    }

    // ------------------------------------------------------------------ login and the rules

    /** At login: the player's places are read (from the network's table with shared storage). */
    public static void onLogin(ServerPlayer player) {
        Live live = new Live(player);
        LIVE.put(player.getUUID(), live);
        Storage s = Storage.active();
        if (s == null) {
            live.known = true;
            return;
        }
        UUID id = player.getUUID();
        MinecraftServer server = player.server;
        s.submit(() -> {
            List<TutorialProgress> rows = s.backend().loadTutorialProgress(id);
            server.execute(() -> {
                ProgressData data = ProgressData.get(server);
                Map<String, TutorialProgress> merged = new HashMap<>();
                data.of(id).forEach(p -> merged.put(p.tutorial(), p));
                rows.forEach(p -> merged.merge(p.tutorial(), p, (a, b) -> b.atMs() >= a.atMs() ? b : a));
                data.replace(id, new ArrayList<>(merged.values()));
                Live l = LIVE.get(id);
                if (l != null) {
                    l.known = true;
                    decide(l);
                }
            });
        }, e -> server.execute(() -> {
            // the table could not be read: this world's copy decides (audit of SU-146)
            Live l = LIVE.get(id);
            if (l != null) {
                l.known = true;
                decide(l);
            }
        }));
    }

    /** A respawn (or leaving the End) makes a new player entity: the tutorial follows it (audit of SU-146). */
    public static void rebind(ServerPlayer player) {
        Live l = LIVE.get(player.getUUID());
        if (l != null) {
            l.player = player;
        }
    }

    public static void onLogout(UUID player) {
        LIVE.remove(player);
    }

    /** The rules popup is behind the player (agreed, or nothing to agree to): tutorials may start (D722). */
    public static void afterRules(ServerPlayer player) {
        Live live = LIVE.get(player.getUUID());
        if (live == null) {
            return;
        }
        live.rulesDone = true;
        decide(live);
    }

    private static boolean ready(Live live) {
        return live.known && live.rulesDone && !RulesService.isHeld(live.player.getUUID()) && !live.player.isRemoved();
    }

    /** Resumes a tutorial under way, or offers the first-join tutorial once this login. */
    private static void decide(Live live) {
        if (!ready(live)) {
            return;
        }
        MinecraftServer server = live.player.server;
        Optional<TutorialProgress> going = active(server, live.player.getUUID());
        if (going.isPresent()) {
            if (TutorialService.get(server, TutorialStage.PUBLISHED, going.get().tutorial()).isPresent()) {
                if (enabled(server)) {
                    step(live, going.get());
                }
            } else if (deleted(server, going.get().tutorial())) {
                gone(live, going.get());
            } else {
                return;
            }
            if (active(server, live.player.getUUID()).isPresent()) {
                return;
            }
        }
        if (live.firstJoinChecked || !enabled(server)) {
            return;
        }
        live.firstJoinChecked = true;
        Optional<Tutorial> first = TutorialService.firstJoin(server);
        if (first.isEmpty()) {
            return;
        }
        Tutorial t = first.get();
        TutorialProgress row = progress(server, live.player.getUUID(), t.id());
        if (row != null && row.state() == TutorialProgress.State.LATER) {
            offerFirst(live, t); // LATER asks again at the next login (D722)
            return;
        }
        if (t.audience() == Tutorial.Audience.NOT_FINISHED) {
            if (row == null || row.state() == TutorialProgress.State.ENDED) {
                offerFirst(live, t); // everyone who has not finished it (D721), ended early included (audit of SU-146)
            }
            return;
        }
        if (row != null) {
            return;
        }
        UUID id = live.player.getUUID();
        Storage s = Storage.active();
        if (s == null) {
            Joined j = PlayersData.get(server).get(id);
            if (isNew(j, t)) {
                offerFirst(live, t);
            }
            return;
        }
        s.submit(() -> {
            Joined j = s.backend().playerById(id);
            server.execute(() -> {
                Live l = LIVE.get(id);
                if (l != null && isNew(j, t) && ready(l)) {
                    offerFirst(l, t);
                }
            });
        }, e -> {
            // the table could not be read: not offered this time (they are asked again at the next login)
        });
    }

    /**
     * New for the first-join tutorial (D721): first joined the network since it became that. A player the counter's
     * list does not know is new only while the counter runs (its row is written at this login); with the counter off
     * nobody is known, so nobody counts as new (audit of SU-146).
     */
    public static boolean isNew(@Nullable Joined j, Tutorial t) {
        return j != null ? j.atMs() >= t.since() : !com.mrgregles.bsp_server_utilities.players.PlayerCounter.off();
    }

    /** Whether the network deleted the tutorial (not merely that this server has not heard of it yet). */
    private static boolean deleted(MinecraftServer server, String tutorial) {
        Tutorial marker = TutorialData.get(server).get(TutorialStage.PUBLISHED, tutorial);
        return marker != null && marker.deleted();
    }

    /** The tutorial under way was deleted: it ends, with a line (D734). */
    private static void gone(Live live, TutorialProgress p) {
        Tutorial marker = TutorialData.get(live.player.server).get(TutorialStage.PUBLISHED, p.tutorial());
        put(live.player.server, p.with(TutorialProgress.State.ENDED, now()));
        clear(live);
        live.player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.removed", marker == null ? "" : marker.name()));
    }

    /**
     * A tutorial was published again or deleted (here or on another server): every player partway through it is shown
     * the new version at once (so a button they press is the button they see), or ends it (audit of SU-146).
     */
    public static void changed(MinecraftServer server, String tutorial) {
        for (Live live : new ArrayList<>(LIVE.values())) {
            TutorialProgress p = progress(server, live.player.getUUID(), tutorial);
            if (p == null || !p.active() || !ready(live)) {
                continue;
            }
            if (TutorialService.get(server, TutorialStage.PUBLISHED, tutorial).isPresent()) {
                if (live.hidden.equals(tutorial + "|" + p.point()) && p.reached()) {
                    continue; // hidden with Escape: stays hidden until /tutorial resume (audit of SU-146)
                }
                live.showingTutorial = null;
                step(live, p);
            } else if (deleted(server, tutorial)) {
                gone(live, p);
            }
        }
    }

    // ------------------------------------------------------------------ offers

    /** The first-join offer; when another offer is on screen it is tried again once that is answered (audit of SU-146). */
    private static void offerFirst(Live live, Tutorial t) {
        if (!offer(live, t) && live.offering != null) {
            live.firstJoinChecked = false;
        }
    }

    private static boolean offer(Live live, Tutorial t) {
        if (live.offering != null || t.starts().isEmpty() || active(live.player.server, live.player.getUUID()).isPresent()) {
            return false; // never a tutorial with nowhere to begin (audit of SU-146)
        }
        live.offering = t.id();
        sender.accept(live.player, new TutorialOfferPacket(t.id(), t.name(), t.offer()));
        return true;
    }

    /** The player's answer from the client; anything that does not match what was shown is ignored. */
    public static void answer(ServerPlayer player, TutorialAnswerPacket a) {
        Live live = LIVE.get(player.getUUID());
        if (live == null) {
            return;
        }
        if (!ready(live) || !enabled(player.server)) {
            // never while the rules hold them, nor with tutorials off; an offer answered then is forgotten, not left blocking (audit of SU-146)
            if (a.tutorial().equals(live.offering) && (a.action() == TutorialAnswerPacket.Action.YES || a.action() == TutorialAnswerPacket.Action.LATER
                    || a.action() == TutorialAnswerPacket.Action.NO)) {
                live.offering = null;
            }
            return;
        }
        switch (a.action()) {
            case YES, LATER, NO -> answerOffer(live, a.tutorial(), a.action());
            case BUTTON -> press(live, a.tutorial(), a.point(), a.index());
            case WALKED -> walked(live, a.tutorial(), a.point());
            case HIDDEN -> {
                if (a.tutorial().equals(live.showingTutorial) && a.point() == live.showingPoint) {
                    live.showingTutorial = null; // not sent again when a picture arrives or on a republish (audit of SU-146)
                    live.showingPoint = -1;
                    live.hidden = a.tutorial() + "|" + a.point();
                }
            }
        }
    }

    private static void answerOffer(Live live, String tutorial, TutorialAnswerPacket.Action action) {
        if (!tutorial.equals(live.offering)) {
            return;
        }
        live.offering = null;
        MinecraftServer server = live.player.server;
        Optional<Tutorial> t = TutorialService.get(server, TutorialStage.PUBLISHED, tutorial);
        if (t.isEmpty()) {
            return;
        }
        UUID id = live.player.getUUID();
        switch (action) {
            case YES -> start(live, t.get());
            case LATER -> put(server, TutorialProgress.of(id, tutorial, TutorialProgress.State.LATER, now()));
            default -> {
                put(server, TutorialProgress.of(id, tutorial, TutorialProgress.State.DECLINED, now()));
                live.player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.declined", t.get().name()));
            }
        }
        if (action != TutorialAnswerPacket.Action.YES) {
            decide(live); // a first-join offer that waited behind this one comes now (audit of SU-146)
        }
    }

    // ------------------------------------------------------------------ the flow

    /** Starts a tutorial from its first point; any other under way ends. */
    public static boolean start(ServerPlayer player, String tutorial) {
        Live live = LIVE.get(player.getUUID());
        Optional<Tutorial> t = TutorialService.get(player.server, TutorialStage.PUBLISHED, tutorial);
        if (live == null || t.isEmpty() || !ready(live) || !enabled(player.server)) {
            return false;
        }
        return start(live, t.get());
    }

    private static boolean start(Live live, Tutorial t) {
        List<Integer> starts = t.starts();
        if (starts.isEmpty()) {
            return false;
        }
        MinecraftServer server = live.player.server;
        UUID id = live.player.getUUID();
        live.offering = null;
        for (TutorialProgress p : ProgressData.get(server).of(id)) {
            if (p.active() && !p.tutorial().equals(t.id())) {
                put(server, p.with(TutorialProgress.State.ENDED, now()));
            }
        }
        TutorialProgress p = new TutorialProgress(id, t.id(), TutorialProgress.State.ACTIVE, starts.get(0), false, List.of(), now());
        put(server, p);
        live.player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.started", t.name()));
        step(live, p);
        return true;
    }

    /** Shows what the player's place calls for: the popup when the point is reached, else the way there. */
    private static void step(Live live, TutorialProgress p) {
        MinecraftServer server = live.player.server;
        Optional<Tutorial> found = TutorialService.get(server, TutorialStage.PUBLISHED, p.tutorial());
        if (found.isEmpty()) {
            if (deleted(server, p.tutorial())) {
                gone(live, p);
            }
            return;
        }
        Tutorial t = found.get();
        Tutorial.Point point = t.point(p.point());
        if (point == null) {
            // the point was deleted in a newer version (D734)
            put(server, p.with(TutorialProgress.State.ENDED, now()));
            clear(live);
            live.player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.changed", t.name()));
            return;
        }
        if (p.reached()) {
            showPopup(live, t, point, p);
            return;
        }
        Tutorial.Place place = point.place();
        if (!place.isSet() || near(live.player, place)) {
            arrive(live, t, point, p);
            return;
        }
        closePopup(live);
        if (here(live.player, place)) {
            live.guided = t.id() + "|" + point.id();
            sender.accept(live.player, guide(t, point, p));
        } else {
            live.guided = "";
            sender.accept(live.player, TutorialGuidePacket.CLEAR);
        }
    }

    private static void arrive(Live live, Tutorial t, Tutorial.Point point, TutorialProgress p) {
        TutorialProgress now = p.arrived(now());
        put(live.player.server, now);
        sender.accept(live.player, TutorialGuidePacket.CLEAR);
        showPopup(live, t, point, now);
    }

    /** The guide to a point here: the path of the link from the point before when it has one, and the sign over the point. */
    static TutorialGuidePacket guide(Tutorial t, Tutorial.Point point, TutorialProgress p) {
        List<BlockPos> path = List.of();
        if (!p.history().isEmpty()) {
            int from = p.history().get(p.history().size() - 1);
            for (Tutorial.Link l : t.links()) {
                if (l.from() == from && l.to() == point.id()) {
                    path = l.path();
                }
            }
        }
        Tutorial.Place place = point.place();
        return new TutorialGuidePacket(t.id(), point.id(), place.dimension(), path, new BlockPos(place.x(), place.y(), place.z()), place.radius(), point.title());
    }

    /** Whether a place is on this server and in the player's world. */
    static boolean here(ServerPlayer player, Tutorial.Place place) {
        return place.isSet() && place.server().equalsIgnoreCase(Storage.currentServerId())
                && place.dimension().equals(player.level().dimension().location().toString());
    }

    /** Within the place's radius (D729), measured from the player's feet to the middle of the block's bottom. */
    static boolean near(ServerPlayer player, Tutorial.Place place) {
        return here(player, place) && player.position().distanceTo(new Vec3(place.x() + 0.5, place.y(), place.z() + 0.5)) <= place.radius();
    }

    private static void showPopup(Live live, Tutorial t, Tutorial.Point point, TutorialProgress p) {
        MinecraftServer server = live.player.server;
        TutorialPopup pop = point.popup();
        List<String> hashes = new ArrayList<>();
        for (TutorialPopup.Box b : pop.boxes()) {
            hashes.add(b.kind() == TutorialPopup.Kind.IMAGE ? TutorialImages.hash(server, b.image(), b.w(), b.h()) : "");
        }
        List<Integer> kids = t.children(point.id());
        List<String> away = new ArrayList<>();
        for (TutorialPopup.Button b : pop.buttons()) {
            int target = switch (b.job()) {
                case NEXT, CLOSE -> kids.isEmpty() ? -1 : kids.get(0);
                case BRANCH -> b.target();
                default -> -1;
            };
            Tutorial.Point to = target < 0 ? null : t.point(target);
            away.add(to != null && to.place().isSet() && !here(live.player, to.place()) ? where(to.place()) : "");
        }
        int stop = p.history().size() + 1, a = ahead(t, point.id());
        int total = a < 0 ? 0 : stop + a;
        live.showingTutorial = t.id();
        live.showingPoint = point.id();
        sender.accept(live.player, new TutorialPopupPacket(t.id(), t.name(), point.id(), point.title(), stop, total, pop.save(), hashes, away,
                BspTeleport.api() != null, !p.history().isEmpty()));
    }

    /** How many points follow, or -1 when a fork lies ahead (the popup then shows only the stop). */
    static int ahead(Tutorial t, int from) {
        int n = 0, at = from;
        Set<Integer> seen = new HashSet<>();
        while (seen.add(at)) {
            List<Integer> kids = t.children(at);
            if (kids.isEmpty()) {
                return n;
            }
            if (kids.size() > 1) {
                return -1;
            }
            n++;
            at = kids.get(0);
        }
        return n;
    }

    private static void closePopup(Live live) {
        if (live.showingTutorial != null) {
            live.showingTutorial = null;
            live.showingPoint = -1;
            sender.accept(live.player, TutorialPopupPacket.CLOSE);
        }
    }

    private static void clear(Live live) {
        closePopup(live);
        sender.accept(live.player, TutorialGuidePacket.CLEAR);
    }

    /** The player's place, when it is the popup they answered: under way, at that point, reached. */
    @Nullable
    private static TutorialProgress at(Live live, String tutorial, int point) {
        TutorialProgress p = progress(live.player.server, live.player.getUUID(), tutorial);
        return p != null && p.active() && p.point() == point && p.reached() ? p : null;
    }

    /** A press the server could not take: the popup the player should see is sent again, so they are never left without one. */
    private static void reshow(Live live, String tutorial) {
        TutorialProgress p = progress(live.player.server, live.player.getUUID(), tutorial);
        Optional<Tutorial> t = TutorialService.get(live.player.server, TutorialStage.PUBLISHED, tutorial);
        if (p != null && p.active() && p.reached() && t.isPresent() && t.get().point(p.point()) != null) {
            showPopup(live, t.get(), t.get().point(p.point()), p);
        }
    }

    private static void press(Live live, String tutorial, int point, int index) {
        TutorialProgress p = at(live, tutorial, point);
        Optional<Tutorial> t = TutorialService.get(live.player.server, TutorialStage.PUBLISHED, tutorial);
        if (p == null || t.isEmpty()) {
            reshow(live, tutorial);
            return;
        }
        Tutorial.Point here = t.get().point(point);
        if (here == null || index < 0 || index >= here.popup().buttons().size()) {
            reshow(live, tutorial);
            return;
        }
        TutorialPopup.Button b = here.popup().buttons().get(index);
        List<Integer> kids = t.get().children(point);
        switch (b.job()) {
            case NEXT, CLOSE -> {
                if (kids.isEmpty()) {
                    finish(live, t.get(), p);
                } else {
                    go(live, t.get(), p, kids.get(0), true);
                }
            }
            case BRANCH -> {
                if (kids.contains(b.target())) {
                    go(live, t.get(), p, b.target(), true);
                } else {
                    reshow(live, tutorial);
                }
            }
            case PREVIOUS -> {
                TutorialProgress back = p.back(now());
                put(live.player.server, back);
                step(live, back);
            }
            case END -> end(live, t.get(), p);
            case LINK -> {
                // opened on the client; the popup stays
            }
        }
    }

    private static void walked(Live live, String tutorial, int point) {
        TutorialProgress p = at(live, tutorial, point);
        Optional<Tutorial> t = TutorialService.get(live.player.server, TutorialStage.PUBLISHED, tutorial);
        if (p == null || t.isEmpty()) {
            return;
        }
        Tutorial.Point here = t.get().point(point);
        if (here == null || here.popup().freezes()) {
            return;
        }
        List<Integer> kids = t.get().children(point);
        if (kids.isEmpty()) {
            finish(live, t.get(), p);
        } else {
            go(live, t.get(), p, kids.get(0), false); // a fading popup never moves anyone: TAKE ME THERE is a press (D730)
        }
    }

    /** On to the next point; one on another server or in another world is reached by moving the player when {@code move} (D730). */
    private static void go(Live live, Tutorial t, TutorialProgress p, int next, boolean move) {
        TutorialProgress now = p.toward(next, true, now());
        put(live.player.server, now);
        Tutorial.Point to = t.point(next);
        if (to != null && to.place().isSet() && !here(live.player, to.place())) {
            if (move) {
                moveThere(live.player, to, false);
            } else {
                live.player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.elsewhere", to.title(), where(to.place())));
            }
            closePopup(live);
            sender.accept(live.player, TutorialGuidePacket.CLEAR);
            if (!here(live.player, to.place())) {
                return; // on another server: the tutorial picks up there
            }
        }
        step(live, now);
    }

    /** Where a place is, for TAKE ME THERE: its server, or its world when it is on this server. */
    static String where(Tutorial.Place place) {
        if (!place.server().equalsIgnoreCase(Storage.currentServerId())) {
            return place.server();
        }
        String d = place.dimension();
        return d.substring(d.indexOf(':') + 1).replace('_', ' ');
    }

    /**
     * Moves a player to a point elsewhere through BSP-Teleport (its own checks apply; D730), or tells them where it is.
     * {@code direct}: an admin (GO, DRAW PATH) is moved straight there within this server.
     */
    static void moveThere(ServerPlayer player, Tutorial.Point to, boolean direct) {
        Tutorial.Place place = to.place();
        if (direct && place.server().equalsIgnoreCase(Storage.currentServerId())) {
            ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(place.dimension())));
            if (level != null) {
                player.teleportTo(level, place.x() + 0.5, place.y(), place.z() + 0.5, player.getYRot(), player.getXRot());
            }
            return;
        }
        BspTeleport.Api api = BspTeleport.api();
        if (api == null || !api.teleport(player, new BspTeleport.Place(place.server(), place.dimension(), place.x() + 0.5, place.y(), place.z() + 0.5),
                "Taking you to " + to.title())) { // plain text: BSP-Teleport shows it as it is (a lang key would arrive as the key)
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.elsewhere", to.title(), where(place)));
        }
    }

    private static void finish(Live live, Tutorial t, TutorialProgress p) {
        put(live.player.server, p.with(TutorialProgress.State.FINISHED, now()));
        clear(live);
        live.player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.finished", t.name()));
    }

    private static void end(Live live, Tutorial t, TutorialProgress p) {
        put(live.player.server, p.with(TutorialProgress.State.ENDED, now()));
        clear(live);
        live.player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.ended", t.name()));
    }

    // ------------------------------------------------------------------ /tutorial

    /** What {@code /tutorial} lists: the tutorial under way first, then every published one the player may start. */
    public record Choice(Tutorial tutorial, @Nullable TutorialProgress progress) {
    }

    public static List<Choice> choices(ServerPlayer player) {
        List<Choice> out = new ArrayList<>();
        MinecraftServer server = player.server;
        Optional<TutorialProgress> going = active(server, player.getUUID());
        going.flatMap(p -> TutorialService.get(server, TutorialStage.PUBLISHED, p.tutorial())).ifPresent(t -> out.add(new Choice(t, going.get())));
        for (Tutorial t : TutorialService.list(server, TutorialStage.PUBLISHED)) {
            if (going.isEmpty() || !going.get().tutorial().equals(t.id())) {
                if (!t.starts().isEmpty()) {
                    out.add(new Choice(t, progress(server, player.getUUID(), t.id())));
                }
            }
        }
        return out;
    }

    /** RESUME: shows the popup or the way again. False when nothing is under way. */
    public static boolean resume(ServerPlayer player) {
        Live live = LIVE.get(player.getUUID());
        Optional<TutorialProgress> going = active(player.server, player.getUUID());
        if (live == null || going.isEmpty() || !enabled(player.server) || TutorialService.get(player.server, TutorialStage.PUBLISHED, going.get().tutorial()).isEmpty()) {
            return false;
        }
        live.showingTutorial = null;
        live.hidden = "";
        step(live, going.get());
        return true;
    }

    /** {@code /tutorial end}: ends the tutorial under way. */
    public static boolean endActive(ServerPlayer player) {
        Live live = LIVE.get(player.getUUID());
        Optional<TutorialProgress> going = active(player.server, player.getUUID());
        if (live == null || going.isEmpty()) {
            return false;
        }
        Optional<Tutorial> t = TutorialService.get(player.server, TutorialStage.PUBLISHED, going.get().tutorial());
        if (t.isEmpty()) {
            put(player.server, going.get().with(TutorialProgress.State.ENDED, now()));
            clear(live);
            return true;
        }
        end(live, t.get(), going.get());
        return true;
    }

    // ------------------------------------------------------------------ every tick

    public static void tick(MinecraftServer server) {
        if (++ticks % 1200 == 0) {
            retryWrites(server); // once a minute, whoever is online (audit of SU-146)
        }
        if (ticks % CHECK_TICKS != 0 || LIVE.isEmpty()) {
            return;
        }
        if (!enabled(server)) {
            return;
        }
        List<Tutorial> areas = new ArrayList<>();
        for (Tutorial t : TutorialService.list(server, TutorialStage.PUBLISHED)) {
            if (t.start() == Tutorial.Start.AREA && t.area().isSet()) {
                areas.add(t);
            }
        }
        for (Live live : new ArrayList<>(LIVE.values())) {
            check(live, areas);
        }
    }

    /** One player's check: reaching the point they are heading for, and walking into or out of start areas. */
    static void check(Live live, List<Tutorial> areas) {
        if (!ready(live)) {
            return;
        }
        MinecraftServer server = live.player.server;
        Optional<TutorialProgress> going = active(server, live.player.getUUID());
        if (going.isPresent() && !going.get().reached()) {
            Optional<Tutorial> t = TutorialService.get(server, TutorialStage.PUBLISHED, going.get().tutorial());
            Tutorial.Point point = t.map(x -> x.point(going.get().point())).orElse(null);
            if (point != null && near(live.player, point.place())) {
                arrive(live, t.get(), point, going.get());
            } else if (point != null && here(live.player, point.place()) && !live.guided.equals(t.get().id() + "|" + point.id())) {
                step(live, going.get()); // arrived in the point's world (a move, a portal): the way there shows
            }
        }
        String serverId = Storage.currentServerId(), dim = live.player.level().dimension().location().toString();
        Vec3 pos = live.player.position();
        for (Tutorial t : areas) {
            boolean in = t.area().contains(serverId, dim, pos.x, pos.y, pos.z);
            if (!in) {
                live.inside.remove(t.id());
                continue;
            }
            if (live.inside.contains(t.id())) {
                continue;
            }
            TutorialProgress row = progress(server, live.player.getUUID(), t.id());
            boolean ask = going.isEmpty() && (row == null || row.state() == TutorialProgress.State.LATER || row.state() == TutorialProgress.State.ENDED);
            if (!ask || offer(live, t)) {
                live.inside.add(t.id()); // a walk-in while another offer waits is counted when that one is answered
            }
        }
    }

    /** Rows the table did not take (an outage, then perhaps a restart) are written again; also at server start. */
    public static void retryWrites(MinecraftServer server) {
        Storage s = Storage.active();
        if (s == null) {
            return;
        }
        ProgressData data = ProgressData.get(server);
        for (TutorialProgress p : data.unwrittenRows()) {
            s.submit(() -> {
                s.backend().saveTutorialProgress(p);
                server.execute(() -> data.written(p.player(), p.tutorial(), p.atMs()));
            });
        }
    }

    /** A picture finished loading: popups on screen are sent again so they show it. */
    public static void picturesArrived(MinecraftServer server) {
        for (Live live : LIVE.values()) {
            if (live.showingTutorial == null) {
                continue;
            }
            TutorialProgress p = progress(server, live.player.getUUID(), live.showingTutorial);
            Optional<Tutorial> t = TutorialService.get(server, TutorialStage.PUBLISHED, live.showingTutorial);
            if (p != null && p.active() && p.reached() && t.isPresent() && t.get().point(p.point()) != null) {
                showPopup(live, t.get(), t.get().point(p.point()), p);
            }
        }
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a player as if logged in and past the rules, places known, packets to the given sink. */
    public static void joinForTests(ServerPlayer player) {
        Live live = new Live(player);
        live.known = true;
        LIVE.put(player.getUUID(), live);
    }

    public static void useForTests(LongSupplier c, BiConsumer<ServerPlayer, Object> s) {
        clock = c;
        sender = s;
    }

    public static void useReal() {
        clock = System::currentTimeMillis;
        sender = ServerUtilsNetwork::sendTo;
        LIVE.clear();
    }

    /** Tests: one player's check now (reaching a point, start areas), without waiting for the tick. */
    public static void checkForTests(ServerPlayer player) {
        Live live = LIVE.get(player.getUUID());
        if (live == null) {
            return;
        }
        List<Tutorial> areas = new ArrayList<>();
        for (Tutorial t : TutorialService.list(player.server, TutorialStage.PUBLISHED)) {
            if (t.start() == Tutorial.Start.AREA && t.area().isSet()) {
                areas.add(t);
            }
        }
        check(live, areas);
    }

    /** Tests: forgets every place in this world. */
    public static void clearForTests(MinecraftServer server) {
        ProgressData.get(server).clear();
    }

    /** Tests: whether an offer of this tutorial waits for the player's answer. */
    public static boolean offeringForTests(ServerPlayer player, String tutorial) {
        Live live = LIVE.get(player.getUUID());
        return live != null && tutorial.equals(live.offering);
    }
}
