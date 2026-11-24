package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.compat.BspTeleport;
import com.mrgregles.bsp_server_utilities.network.TutorialAnswerPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialAnswerPacket.Action;
import com.mrgregles.bsp_server_utilities.network.TutorialGuidePacket;
import com.mrgregles.bsp_server_utilities.network.TutorialOfferPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialPopupPacket;
import com.mrgregles.bsp_server_utilities.players.Joined;
import com.mrgregles.bsp_server_utilities.players.PlayersData;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Link;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Place;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Point;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Button;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Job;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialProgress;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialProgress.State;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialRunner;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialService;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialStage;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Players going through tutorials (SU-138, SU-139; owner decisions D720 to D739): the first-join offer after the rules
 * and who gets it, YES / LATER / NO THANKS, start areas, the buttons through a forked tree, reaching a point within its
 * radius and the golden path's data, PREVIOUS, finishing and ending, a popup that keeps the player walking, a stop on
 * another server (TAKE ME THERE), a stop removed in a newer version, one tutorial at a time, and places kept for the
 * network. The popups and the path need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TutorialRunGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long SINCE = 1_800_000_000_000L;

    private static String here() {
        return Storage.currentServerId();
    }

    private static String dim(ServerPlayer p) {
        return p.level().dimension().location().toString();
    }

    private static TutorialPopup popup(boolean freezes, Button... buttons) {
        return new TutorialPopup(TutorialPopup.Size.FULL, freezes, 8, TutorialPopup.DEFAULT_BACKGROUND, TutorialPopup.Placement.DEFAULT,
                List.of(new TutorialPopup.Box(0, 0, 6, 2, TutorialPopup.Kind.TEXT, TutorialText.of("Hi"), TutorialPopup.Image.NONE, 0)), List.of(buttons));
    }

    /** Welcome (no place) → Spawn Hall (here, x 100) → The Bank (here, x 130) or Starter Kit (on "elsewhere"). */
    private static Tutorial tree(ServerPlayer p, Tutorial.Start start) {
        String d = dim(p);
        List<Point> points = List.of(
                new Point(0, "Welcome", "", 0, 0, Place.NONE, popup(true, Button.of("Next", Job.NEXT))),
                new Point(1, "Spawn Hall", "", 0, 0, new Place(here(), d, 100, 64, 0, 2),
                        popup(true, new Button("Full tour", 0x19D3B0, Job.BRANCH, 2, ""), new Button("Quick start", 0xFFD23A, Job.BRANCH, 3, ""))),
                new Point(2, "The Bank", "", 0, 0, new Place(here(), d, 130, 64, 0, 2), popup(true, Button.of("Next", Job.NEXT), Button.of("Back", Job.PREVIOUS))),
                new Point(3, "Starter Kit", "", 0, 0, new Place("elsewhere", "minecraft:overworld", 5, 70, 5, 2), popup(true, Button.of("End", Job.END))));
        List<BlockPos> path = new ArrayList<>();
        for (int x = 100; x <= 130; x += 3) {
            path.add(new BlockPos(x, 64, 0));
        }
        List<Link> links = List.of(new Link(0, 1, List.of()), new Link(1, 2, path), new Link(1, 3, List.of()));
        return Tutorial.create("Welcome to BSP").withStart(start).withPoints(points).withLinks(links).sanitised();
    }

    /** Makes and publishes a tutorial (the clock at SINCE), returning its published copy. */
    private static Tutorial publish(MinecraftServer server, Tutorial shape) {
        String id = TutorialService.create(server, null, "Console", shape.name()).tutorial().id();
        TutorialService.editDraft(server, null, "Console", id, t -> t.withStart(shape.start()).withAudience(shape.audience()).withArea(shape.area())
                .withOffer("Want a tour?").withPoints(shape.points()).withLinks(shape.links()));
        TutorialService.publish(server, null, "Console", id);
        return TutorialService.get(server, TutorialStage.PUBLISHED, id).orElseThrow();
    }

    private static void reset(MinecraftServer server) {
        Storage.useForTests(null);
        BspTeleport.useForTests(null);
        TutorialService.useRealClock();
        TutorialService.clearForTests(server);
        TutorialRunner.useReal();
        TutorialRunner.clearForTests(server);
    }

    private static <T> List<T> of(List<Object> sent, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Object o : sent) {
            if (type.isInstance(o)) {
                out.add(type.cast(o));
            }
        }
        return out;
    }

    private static <T> T last(List<Object> sent, Class<T> type) {
        List<T> all = of(sent, type);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static void answer(ServerPlayer p, Action a, String tutorial, int point, int index) {
        TutorialRunner.answer(p, new TutorialAnswerPacket(a, tutorial, point, index));
    }

    @GameTest(template = EMPTY)
    public void the_first_join_tutorial_is_offered_after_the_rules(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        ServerPlayer fresh = TestPlayers.mockServerPlayer(h), old = TestPlayers.mockServerPlayer(h), waiting = TestPlayers.mockServerPlayer(h);
        try {
            reset(server);
            TutorialService.useClockForTests(() -> SINCE);
            TutorialRunner.useForTests(() -> SINCE + 1000, (p, o) -> sent.add(o));
            Tutorial t = publish(server, tree(fresh, Tutorial.Start.FIRST_JOIN));
            h.assertTrue(t.since() == SINCE, "publishing a first-join tutorial marks when it became that: " + t.since());
            PlayersData.get(server).add(new Joined(old.getUUID(), old.getGameProfile().getName(), SINCE - 60_000, "alpha"));
            // before the rules are behind them, nothing
            TutorialRunner.joinForTests(waiting);
            TutorialRunner.checkForTests(waiting);
            h.assertTrue(sent.isEmpty(), "nothing is offered before the rules popup is answered: " + sent);
            // a new player: offered once
            TutorialRunner.joinForTests(fresh);
            TutorialRunner.afterRules(fresh);
            TutorialOfferPacket offer = last(sent, TutorialOfferPacket.class);
            h.assertTrue(offer != null && offer.tutorial().equals(t.id()) && offer.text().equals("Want a tour?") && TutorialRunner.offeringForTests(fresh, t.id()),
                    "a player new to the network is offered the first-join tutorial right after the rules (D722): " + sent);
            // a player who joined the network before it was the first-join tutorial: not (D721, D743)
            TutorialRunner.joinForTests(old);
            TutorialRunner.afterRules(old);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 1, "a player from before is not offered it while it is for new players only");
            // LATER asks at the next login; NO THANKS never again
            answer(fresh, Action.LATER, t.id(), -1, -1);
            h.assertTrue(TutorialRunner.progress(server, fresh.getUUID(), t.id()).state() == State.LATER, "LATER is kept");
            TutorialRunner.joinForTests(fresh);
            TutorialRunner.afterRules(fresh);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 2, "LATER: offered again at the next login (D722)");
            answer(fresh, Action.NO, t.id(), -1, -1);
            h.assertTrue(TutorialRunner.progress(server, fresh.getUUID(), t.id()).state() == State.DECLINED, "NO THANKS is kept");
            TutorialRunner.joinForTests(fresh);
            TutorialRunner.afterRules(fresh);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 2, "NO THANKS: not offered again");
            answer(fresh, Action.YES, t.id(), -1, -1);
            h.assertTrue(TutorialRunner.active(server, fresh.getUUID()).isEmpty(), "an answer to an offer not on screen is ignored");
            // the audience changed to everyone who has not finished it
            TutorialService.editDraft(server, null, "Console", t.id(), x -> x.withAudience(Tutorial.Audience.NOT_FINISHED));
            TutorialService.publish(server, null, "Console", t.id());
            h.assertTrue(TutorialService.get(server, TutorialStage.PUBLISHED, t.id()).orElseThrow().since() == SINCE, "republishing keeps when it became the first-join tutorial");
            TutorialRunner.joinForTests(old);
            TutorialRunner.afterRules(old);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 3, "everyone who has not finished it is offered it (D721)");
            // YES starts it at the first point; its popup shows at once (no place)
            answer(old, Action.YES, t.id(), -1, -1);
            TutorialProgress p = TutorialRunner.active(server, old.getUUID()).orElseThrow();
            TutorialPopupPacket pop = last(sent, TutorialPopupPacket.class);
            h.assertTrue(p.point() == 0 && p.reached() && pop != null && pop.point() == 0 && pop.tutorial().equals(t.id()) && pop.stop() == 1 && pop.total() == 0,
                    "YES starts it: the first point's popup, stop 1 with a fork ahead: " + p + " " + pop);
            // a player under way is resumed at login, not offered again
            sent.clear();
            TutorialRunner.joinForTests(old);
            TutorialRunner.afterRules(old);
            h.assertTrue(of(sent, TutorialOfferPacket.class).isEmpty() && last(sent, TutorialPopupPacket.class) != null, "a tutorial under way resumes at login (D733)");
        } finally {
            PlayersData.get(server).remove(old.getUUID());
            reset(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void buttons_take_players_through_the_tree(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        List<BspTeleport.Place> moves = new ArrayList<>();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            reset(server);
            TutorialRunner.useForTests(System::currentTimeMillis, (pl, o) -> sent.add(o));
            BspTeleport.useForTests(new BspTeleport.Api() {
                public String serverName() {
                    return here();
                }

                public List<BspTeleport.Warp> warps() {
                    return List.of();
                }

                public Optional<BspTeleport.Place> spawn(String server) {
                    return Optional.empty();
                }

                public boolean teleport(ServerPlayer player, BspTeleport.Place to, String message) {
                    moves.add(to);
                    return true;
                }
            });
            Tutorial t = publish(server, tree(p, Tutorial.Start.COMMAND));
            p.setPos(0, 64, 0);
            TutorialRunner.joinForTests(p);
            TutorialRunner.afterRules(p);
            h.assertTrue(TutorialRunner.start(p, t.id()), "/tutorial start");
            // NEXT: on to Spawn Hall, here but far off: the guide to it, the popup closed
            answer(p, Action.BUTTON, t.id(), 0, 0);
            TutorialProgress at = TutorialRunner.active(server, p.getUUID()).orElseThrow();
            TutorialGuidePacket guide = last(sent, TutorialGuidePacket.class);
            h.assertTrue(at.point() == 1 && !at.reached() && guide != null && guide.point() == 1 && guide.target().equals(new BlockPos(100, 64, 0)) && guide.path().isEmpty()
                            && last(sent, TutorialPopupPacket.class).tutorial().isEmpty(),
                    "NEXT heads for the next point: the way there, no popup until it is reached: " + at + " " + guide);
            TutorialRunner.checkForTests(p);
            h.assertTrue(!TutorialRunner.active(server, p.getUUID()).orElseThrow().reached(), "not reached from far away");
            p.setPos(101.5, 64, 1.5); // 1.4 blocks from the point's middle
            TutorialRunner.checkForTests(p);
            TutorialPopupPacket pop = last(sent, TutorialPopupPacket.class);
            h.assertTrue(TutorialRunner.active(server, p.getUUID()).orElseThrow().reached() && pop.point() == 1 && pop.away().equals(List.of("", "elsewhere")) && pop.canMove() && pop.canBack(),
                    "within the radius its popup shows (D729); the branch on another server reads TAKE ME THERE (D730): " + pop);
            // a press that does not match what is shown is ignored
            answer(p, Action.BUTTON, t.id(), 0, 0);
            answer(p, Action.BUTTON, t.id(), 1, 7);
            h.assertTrue(TutorialRunner.active(server, p.getUUID()).orElseThrow().point() == 1, "a button for another point, or one that does not exist, changes nothing");
            // GO TO BRANCH: the full tour, with the path walked between the two points
            answer(p, Action.BUTTON, t.id(), 1, 0);
            guide = last(sent, TutorialGuidePacket.class);
            h.assertTrue(TutorialRunner.active(server, p.getUUID()).orElseThrow().point() == 2 && guide.point() == 2 && guide.path().size() == 11 && guide.path().get(0).equals(new BlockPos(100, 64, 0)),
                    "GO TO BRANCH follows its branch; the golden path is the link's walked path (D732): " + guide);
            p.setPos(130.5, 64, 0.5);
            TutorialRunner.checkForTests(p);
            h.assertTrue(last(sent, TutorialPopupPacket.class).point() == 2 && last(sent, TutorialPopupPacket.class).total() == 3, "reached: stop 3 of 3");
            // PREVIOUS back to Spawn Hall, then the quick start on another server
            answer(p, Action.BUTTON, t.id(), 2, 1);
            at = TutorialRunner.active(server, p.getUUID()).orElseThrow();
            h.assertTrue(at.point() == 1 && at.reached() && last(sent, TutorialPopupPacket.class).point() == 1, "PREVIOUS shows the point before again");
            answer(p, Action.BUTTON, t.id(), 1, 1);
            at = TutorialRunner.active(server, p.getUUID()).orElseThrow();
            h.assertTrue(at.point() == 3 && !at.reached() && moves.size() == 1 && moves.get(0).server().equals("elsewhere") && moves.get(0).x() == 5.5,
                    "a stop on another server moves the player through BSP-Teleport (D730): " + moves);
            // back from the other server: start again here and finish the full tour
            TutorialRunner.start(p, t.id());
            answer(p, Action.BUTTON, t.id(), 0, 0);
            p.setPos(100.5, 64, 0.5);
            TutorialRunner.checkForTests(p);
            answer(p, Action.BUTTON, t.id(), 1, 0);
            p.setPos(130.5, 64, 0.5);
            TutorialRunner.checkForTests(p);
            answer(p, Action.BUTTON, t.id(), 2, 0);
            h.assertTrue(TutorialRunner.progress(server, p.getUUID(), t.id()).state() == State.FINISHED && TutorialRunner.active(server, p.getUUID()).isEmpty()
                            && last(sent, TutorialGuidePacket.class).tutorial().isEmpty(),
                    "NEXT on the last point finishes it and clears the path");
            // END TUTORIAL, from the quick start
            TutorialRunner.start(p, t.id());
            h.assertTrue(TutorialRunner.endActive(p) && TutorialRunner.progress(server, p.getUUID(), t.id()).state() == State.ENDED, "/tutorial end ends it");
            h.assertTrue(!TutorialRunner.resume(p), "nothing to resume after");
        } finally {
            reset(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void walking_popups_new_versions_and_one_tutorial_at_a_time(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            reset(server);
            TutorialRunner.useForTests(System::currentTimeMillis, (pl, o) -> sent.add(o));
            String d = dim(p);
            List<Point> points = List.of(
                    new Point(0, "Walk on", "", 0, 0, Place.NONE, popup(false)),
                    new Point(1, "Here", "", 0, 0, new Place(here(), d, 0, 64, 0, 2), popup(true, Button.of("Next", Job.NEXT))),
                    new Point(2, "Last", "", 0, 0, Place.NONE, popup(true, Button.of("Next", Job.NEXT))));
            Tutorial walk = publish(server, Tutorial.create("Walk").withPoints(points).withLinks(List.of(new Link(0, 1, List.of()), new Link(1, 2, List.of()))).sanitised());
            Tutorial other = publish(server, tree(p, Tutorial.Start.COMMAND));
            p.setPos(0.5, 64, 0.5);
            TutorialRunner.joinForTests(p);
            TutorialRunner.afterRules(p);
            TutorialRunner.start(p, walk.id());
            h.assertTrue(last(sent, TutorialPopupPacket.class).point() == 0 && last(sent, TutorialPopupPacket.class).away().isEmpty(), "a walking popup is sent, with no buttons (D739)");
            answer(p, Action.BUTTON, walk.id(), 0, 0);
            h.assertTrue(TutorialRunner.active(server, p.getUUID()).orElseThrow().point() == 0, "it has no buttons to press");
            answer(p, Action.WALKED, walk.id(), 0, -1);
            h.assertTrue(TutorialRunner.active(server, p.getUUID()).orElseThrow().point() == 1 && last(sent, TutorialPopupPacket.class).point() == 1,
                    "when it has faded the tutorial moves on; the next point, where the player already stands, shows at once");
            answer(p, Action.WALKED, walk.id(), 1, -1);
            h.assertTrue(TutorialRunner.active(server, p.getUUID()).orElseThrow().point() == 1, "WALKED on a popup that freezes is ignored");
            // a newer version without the player's point ends it with a line (D734)
            TutorialService.editDraft(server, null, "Console", walk.id(), x -> x.withPoints(List.of(points.get(0), points.get(2))).withLinks(List.of(new Link(0, 2, List.of()))));
            TutorialService.publish(server, null, "Console", walk.id());
            h.assertTrue(TutorialRunner.progress(server, p.getUUID(), walk.id()).state() == State.ENDED,
                    "a stop deleted in a newer version ends the tutorial as soon as it is published (D734)");
            // one at a time
            TutorialRunner.start(p, walk.id());
            TutorialRunner.start(p, other.id());
            h.assertTrue(TutorialRunner.progress(server, p.getUUID(), walk.id()).state() == State.ENDED && TutorialRunner.active(server, p.getUUID()).orElseThrow().tutorial().equals(other.id()),
                    "starting another ends the one under way");
            List<TutorialRunner.Choice> choices = TutorialRunner.choices(p);
            h.assertTrue(choices.size() == 2 && choices.get(0).tutorial().id().equals(other.id()) && choices.get(0).progress().active(),
                    "/tutorial lists the one under way first, then the others: " + choices.size());
        } finally {
            reset(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void deletions_other_worlds_the_off_switch_and_ended_players(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        List<BspTeleport.Place> moves = new ArrayList<>();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            reset(server);
            TutorialRunner.useForTests(System::currentTimeMillis, (pl, o) -> sent.add(o));
            BspTeleport.useForTests(new BspTeleport.Api() {
                public String serverName() {
                    return here();
                }

                public List<BspTeleport.Warp> warps() {
                    return List.of();
                }

                public Optional<BspTeleport.Place> spawn(String server) {
                    return Optional.empty();
                }

                public boolean teleport(ServerPlayer player, BspTeleport.Place to, String message) {
                    moves.add(to);
                    return false; // refused (a cooldown, say): the player is told where it is
                }
            });
            List<Point> points = List.of(new Point(0, "Start", "", 0, 0, Place.NONE, popup(true, Button.of("Next", Job.NEXT))),
                    new Point(1, "Nether gate", "", 0, 0, new Place(here(), "minecraft:the_nether", 8, 70, 8, 2), popup(true, Button.of("Next", Job.NEXT))));
            Tutorial t = publish(server, Tutorial.create("Worlds").withPoints(points).withLinks(List.of(new Link(0, 1, List.of()))).sanitised());
            TutorialRunner.joinForTests(p);
            TutorialRunner.afterRules(p);
            TutorialRunner.start(p, t.id());
            TutorialPopupPacket pop = last(sent, TutorialPopupPacket.class);
            h.assertTrue(pop.away().equals(List.of("the nether")), "a next stop in another world on this server reads TAKE ME THERE too (D730): " + pop.away());
            answer(p, Action.BUTTON, t.id(), 0, 0);
            h.assertTrue(moves.size() == 1 && moves.get(0).world().equals("minecraft:the_nether") && p.level().dimension().location().toString().equals(dim(p))
                            && TutorialRunner.active(server, p.getUUID()).orElseThrow().point() == 1,
                    "the move goes through BSP-Teleport, whose checks apply; refused, the player stays and is told: " + moves);
            // the tutorial taken down while they are partway: it ends at once and offers are free again (D734)
            TutorialService.delete(server, null, "Console", t.id());
            h.assertTrue(TutorialRunner.progress(server, p.getUUID(), t.id()).state() == State.ENDED && TutorialRunner.active(server, p.getUUID()).isEmpty(),
                    "a deleted tutorial ends for the players partway through it");
            // the off switch (D736)
            Tutorial other = publish(server, tree(p, Tutorial.Start.COMMAND));
            com.mrgregles.bsp_server_utilities.setup.SetupService.turnOff(server, com.mrgregles.bsp_server_utilities.setup.Feature.TUTORIAL);
            h.assertTrue(!TutorialRunner.start(p, other.id()) && !TutorialRunner.enabled(server), "with tutorials turned off, none starts");
            com.mrgregles.bsp_server_utilities.setup.SetupService.turnOn(server, com.mrgregles.bsp_server_utilities.setup.Feature.TUTORIAL);
            // everyone not finished: one who ended early is offered it again
            Tutorial first = publish(server, tree(p, Tutorial.Start.FIRST_JOIN).withAudience(Tutorial.Audience.NOT_FINISHED));
            TutorialRunner.start(p, first.id());
            TutorialRunner.endActive(p);
            sent.clear();
            TutorialRunner.joinForTests(p);
            TutorialRunner.afterRules(p);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 1, "an ENDED place is offered again to everyone not finished (D721)");
        } finally {
            com.mrgregles.bsp_server_utilities.setup.SetupService.turnOn(server, com.mrgregles.bsp_server_utilities.setup.Feature.TUTORIAL);
            reset(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void second_audit_rules(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        List<BspTeleport.Place> moves = new ArrayList<>();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            reset(server);
            TutorialRunner.useForTests(System::currentTimeMillis, (pl, o) -> sent.add(o));
            BspTeleport.useForTests(new BspTeleport.Api() {
                public String serverName() {
                    return here();
                }

                public List<BspTeleport.Warp> warps() {
                    return List.of();
                }

                public Optional<BspTeleport.Place> spawn(String server) {
                    return Optional.empty();
                }

                public boolean teleport(ServerPlayer player, BspTeleport.Place to, String message) {
                    moves.add(to);
                    return true;
                }
            });
            // "new players" with the player counter off: nobody unknown is new
            Tutorial shape = tree(p, Tutorial.Start.FIRST_JOIN);
            com.mrgregles.bsp_server_utilities.setup.SetupService.turnOff(server, com.mrgregles.bsp_server_utilities.setup.Feature.PLAYERS);
            h.assertTrue(!TutorialRunner.isNew(null, shape), "with the counter off, a player it does not know is not new");
            com.mrgregles.bsp_server_utilities.setup.SetupService.turnOn(server, com.mrgregles.bsp_server_utilities.setup.Feature.PLAYERS);
            h.assertTrue(TutorialRunner.isNew(null, shape), "with it on, they are (their row is written at this login)");
            // a tutorial with no points is never offered
            Tutorial empty = publish(server, Tutorial.create("Empty").withStart(Tutorial.Start.FIRST_JOIN).withAudience(Tutorial.Audience.NOT_FINISHED));
            TutorialRunner.joinForTests(p);
            TutorialRunner.afterRules(p);
            h.assertTrue(of(sent, TutorialOfferPacket.class).isEmpty() && !TutorialRunner.offeringForTests(p, empty.id()), "a tutorial with nowhere to begin is not offered");
            // a popup hidden with Escape stays hidden on a republish
            Tutorial t = publish(server, tree(p, Tutorial.Start.COMMAND));
            TutorialRunner.start(p, t.id());
            answer(p, Action.HIDDEN, t.id(), 0, -1);
            sent.clear();
            TutorialService.editDraft(server, null, "Console", t.id(), x -> x.withName("Renamed"));
            TutorialService.publish(server, null, "Console", t.id());
            h.assertTrue(of(sent, TutorialPopupPacket.class).isEmpty(), "a republish does not bring back a hidden popup");
            h.assertTrue(TutorialRunner.resume(p) && last(sent, TutorialPopupPacket.class).point() == 0, "/tutorial resume shows it again");
            // a walking popup before a stop elsewhere moves on without moving the player
            List<Point> walkPoints = List.of(new Point(0, "Walk", "", 0, 0, Place.NONE, popup(false)),
                    new Point(1, "Far", "", 0, 0, new Place("elsewhere", "minecraft:overworld", 1, 64, 1, 2), popup(true, Button.of("Next", Job.NEXT))));
            Tutorial w = publish(server, Tutorial.create("Walk far").withPoints(walkPoints).withLinks(List.of(new Link(0, 1, List.of()))).sanitised());
            TutorialRunner.start(p, w.id());
            answer(p, Action.WALKED, w.id(), 0, -1);
            h.assertTrue(moves.isEmpty() && TutorialRunner.active(server, p.getUUID()).orElseThrow().point() == 1, "WALKED never moves anyone (TAKE ME THERE is a press)");
            // a press the server cannot take sends the popup again
            TutorialRunner.start(p, t.id());
            sent.clear();
            answer(p, Action.BUTTON, t.id(), 0, 9);
            h.assertTrue(last(sent, TutorialPopupPacket.class) != null && last(sent, TutorialPopupPacket.class).point() == 0, "a refused press shows the popup again");
            // deleting marks both copies, even one this server had not heard of
            String id = TutorialService.create(server, null, "Console", "Only a draft").tutorial().id();
            TutorialService.delete(server, null, "Console", id);
            com.mrgregles.bsp_server_utilities.tutorial.Tutorial marker = com.mrgregles.bsp_server_utilities.tutorial.TutorialData.get(server).get(TutorialStage.PUBLISHED, id);
            h.assertTrue(marker != null && marker.deleted(), "a deletion is written for the published copy too");
        } finally {
            com.mrgregles.bsp_server_utilities.setup.SetupService.turnOn(server, com.mrgregles.bsp_server_utilities.setup.Feature.PLAYERS);
            reset(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void start_areas_offer_their_tutorial(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        try {
            reset(server);
            TutorialRunner.useForTests(System::currentTimeMillis, (pl, o) -> sent.add(o));
            Tutorial shape = tree(p, Tutorial.Start.AREA).withArea(new Tutorial.Area(here(), dim(p), 10, 60, 10, 20, 70, 20));
            Tutorial t = publish(server, shape);
            p.setPos(0.5, 64, 0.5);
            TutorialRunner.joinForTests(p);
            TutorialRunner.afterRules(p);
            TutorialRunner.checkForTests(p);
            h.assertTrue(sent.isEmpty(), "outside the area: nothing");
            p.setPos(15.5, 64, 15.5);
            TutorialRunner.checkForTests(p);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 1 && TutorialRunner.offeringForTests(p, t.id()), "walking in offers it (D738)");
            answer(p, Action.LATER, t.id(), -1, -1);
            TutorialRunner.checkForTests(p);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 1, "LATER: not again while still inside");
            p.setPos(30.5, 64, 15.5);
            TutorialRunner.checkForTests(p);
            p.setPos(20.9, 70.9, 20.9); // the far corner block is inside
            TutorialRunner.checkForTests(p);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 2, "LATER: offered again on walking in after leaving");
            answer(p, Action.NO, t.id(), -1, -1);
            p.setPos(30.5, 64, 15.5);
            TutorialRunner.checkForTests(p);
            p.setPos(15.5, 64, 15.5);
            TutorialRunner.checkForTests(p);
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 2, "NO THANKS: that area never asks again");
            h.assertTrue(TutorialRunner.start(p, t.id()) && TutorialRunner.active(server, p.getUUID()).isPresent(), "the player can still start it with /tutorial");
        } finally {
            reset(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void places_in_tutorials_are_kept_for_the_network(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        ServerPlayer p = TestPlayers.mockServerPlayer(h), old = TestPlayers.mockServerPlayer(h);
        try {
            reset(server);
            Storage.useForTests(alpha);
            TutorialService.useClockForTests(() -> SINCE);
            TutorialRunner.useForTests(() -> SINCE + 5000, (pl, o) -> sent.add(o));
            Tutorial t = publish(server, tree(p, Tutorial.Start.FIRST_JOIN));
            alpha.drain();
            // at login the places are read from the network's table; the first-join check asks the table who is new
            db.insertPlayer(new Joined(old.getUUID(), old.getGameProfile().getName(), SINCE - 1, "beta"));
            TutorialRunner.onLogin(old);
            TutorialRunner.afterRules(old);
            alpha.drain();
            alpha.drain();
            h.assertTrue(of(sent, TutorialOfferPacket.class).isEmpty(), "a player the network's list knows from before is not new");
            TutorialRunner.onLogin(p);
            TutorialRunner.afterRules(p);
            alpha.drain();
            alpha.drain();
            h.assertTrue(of(sent, TutorialOfferPacket.class).size() == 1, "a player the list does not know is new: offered");
            answer(p, Action.YES, t.id(), -1, -1);
            answer(p, Action.BUTTON, t.id(), 0, 0);
            alpha.drain();
            TutorialProgress kept = db.loadTutorialProgress(p.getUUID()).get(0);
            h.assertTrue(kept.active() && kept.point() == 1 && kept.history().equals(List.of(0)), "each step is written to the network's table: " + kept);
            // another server: nothing in its world, the table's row is taken at login and the tutorial resumes
            TutorialRunner.clearForTests(server);
            sent.clear();
            TutorialRunner.onLogin(p);
            TutorialRunner.afterRules(p);
            h.assertTrue(sent.isEmpty(), "nothing before the places are read");
            alpha.drain();
            TutorialProgress there = TutorialRunner.active(server, p.getUUID()).orElseThrow();
            h.assertTrue(there.point() == 1 && last(sent, TutorialGuidePacket.class) != null && last(sent, TutorialGuidePacket.class).point() == 1,
                    "the place from the table resumes the tutorial on the other server (D733)");
        } finally {
            alpha.close();
            reset(server);
        }
        h.succeed();
    }
}
