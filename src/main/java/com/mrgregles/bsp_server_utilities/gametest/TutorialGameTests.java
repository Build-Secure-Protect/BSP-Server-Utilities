package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Area;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Issue;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Link;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Place;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Point;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Problem;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Box;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Button;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Image;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Job;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Kind;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup.Placement;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialService;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialService.Outcome;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialStage;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialText;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialText.Para;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialText.Run;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The tutorials' definitions, server side (SU-137; owner decisions D719 to D744): what a tutorial may hold and how it is
 * cut to that, a copy reading back as it was saved, what the builder marks as missing, drafts that change until
 * PUBLISH, one first-join tutorial, who may edit, deletions, and one set for the network with the newest copy winning.
 * The builder, the editor and the popups need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TutorialGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String OVERWORLD = "minecraft:overworld";

    private static Place at(String server, int x, int z) {
        return new Place(server, OVERWORLD, x, 64, z, 2);
    }

    private static TutorialPopup popup(Button... buttons) {
        return new TutorialPopup(TutorialPopup.Size.FULL, true, 8, TutorialPopup.DEFAULT_BACKGROUND, Placement.DEFAULT,
                List.of(new Box(0, 0, 6, 2, Kind.TEXT, TutorialText.of("Hello"), Image.NONE, 0)), List.of(buttons));
    }

    private static Point point(int id, String title, Place place, TutorialPopup popup) {
        return new Point(id, title, "minecraft:compass", id * 40, 0, place, popup);
    }

    private static List<BlockPos> walk(int fromX, int toX, int z) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = fromX; x <= toX; x += 3) {
            out.add(new BlockPos(x, 64, z));
        }
        return out;
    }

    /** A tutorial that forks: Welcome (no place) → Spawn Hall → The Bank or Starter Kit. */
    private static Tutorial forked(String name) {
        Button bank = new Button("Full tour", 0x19D3B0, Job.BRANCH, 2, ""), kit = new Button("Quick start", 0xFFD23A, Job.BRANCH, 3, "");
        List<Point> points = List.of(
                point(0, "Welcome", Place.NONE, popup(Button.of("Next", Job.NEXT))),
                point(1, "Spawn Hall", at("alpha", 0, 0), popup(bank, kit)),
                point(2, "The Bank", at("alpha", 30, 0), popup(Button.of("End", Job.END))),
                point(3, "Starter Kit", at("beta", 0, 0), popup(Button.of("End", Job.END))));
        List<Link> links = List.of(new Link(0, 1, List.of()), new Link(1, 2, walk(0, 30, 0)), new Link(1, 3, List.of()));
        return Tutorial.create(name).withPoints(points).withLinks(links).sanitised();
    }

    private static void reset(MinecraftServer server) {
        Storage.useForTests(null);
        TutorialService.useRealClock();
        TutorialService.clearForTests(server);
    }

    @GameTest(template = EMPTY)
    public void tutorials_are_kept_within_their_limits(GameTestHelper h) {
        List<String> wrong = new ArrayList<>();
        // text: control characters and section signs out, styles and colours masked, touching runs of one look joined
        TutorialText text = new TutorialText(List.of(new Para(TutorialText.Size.L, TutorialText.Align.CENTRE, List.of(
                new Run("§cRed\nline", 255, 0xFF123456), new Run(" and", 31, 0x123456), new Run("", 1, 0), new Run(" plain", 0, 0xE6EAF2))))).sanitised();
        if (!text.paras().get(0).runs().equals(List.of(new Run("cRed line and", 31, 0x123456), new Run(" plain", 0, 0xE6EAF2)))) {
            wrong.add("runs cleaned and joined: " + text);
        }
        List<Para> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Para(TutorialText.Size.M, TutorialText.Align.LEFT, List.of(new Run("x".repeat(100), 0, 0))));
        }
        many.add(new Para(TutorialText.Size.M, TutorialText.Align.LEFT, List.of()));
        TutorialText cut = new TutorialText(many).sanitised();
        if (cut.paras().size() != 10 || cut.length() != TutorialText.MAX_CHARS) {
            wrong.add("at most 1000 letters (and 16 paragraphs), the rest cut: " + cut.paras().size() + " / " + cut.length());
        }
        if (new TutorialText(many.subList(0, 20).stream().map(p -> new Para(p.size(), p.align(), List.of(new Run("y", 0, 0)))).toList()).sanitised().paras().size() != TutorialText.MAX_PARAS) {
            wrong.add("at most 16 paragraphs");
        }
        // boxes on the grid
        List<Box> boxes = new ArrayList<>();
        boxes.add(new Box(10, 7, 5, 5, Kind.TEXT, TutorialText.of("a"), Image.NONE, 0)); // cut to 2 x 1
        boxes.add(new Box(11, 7, 1, 1, Kind.TEXT, TutorialText.of("b"), Image.NONE, 0)); // overlaps the first: dropped
        boxes.add(new Box(-3, -3, 2, 2, Kind.IMAGE, null, new Image(TutorialPopup.Source.FILE, "../secret.png", "javascript:alert(1)", null), 0));
        for (int i = 0; i < 14; i++) {
            boxes.add(new Box(i % 8 + 2, 3 + i / 8, 1, 1, Kind.TEXT, TutorialText.of("c"), Image.NONE, 0));
        }
        List<Button> buttons = List.of(new Button("", 0xFF19D3B0, Job.NEXT, -1, ""), new Button("Branch", 0, Job.BRANCH, 9, ""),
                new Button("Http", 0, Job.LINK, -1, "http://example.net"), new Button("Shop", 0, Job.LINK, -1, "https://store.example.net"),
                Button.of("Back", Job.PREVIOUS), Button.of("End", Job.END), Button.of("Close", Job.CLOSE));
        TutorialPopup p = new TutorialPopup(TutorialPopup.Size.FULL, true, 0, 0, new Placement(990, -5, 10, 2000), boxes, buttons).sanitised(Set.of(4));
        Box first = p.boxes().get(0), img = p.boxes().get(1);
        if (first.col() != 10 || first.w() != 2 || first.h() != 1) {
            wrong.add("a box is cut to the grid: " + first);
        }
        if (img.col() != 0 || img.row() != 0 || img.image().isSet() || !img.image().file().isEmpty() || !img.image().url().isEmpty() || !img.text().isEmpty()) {
            wrong.add("a box moved inside the grid; a file name leaving the folder and a non-web address are dropped: " + img);
        }
        if (p.boxes().size() != TutorialPopup.MAX_BOXES) {
            wrong.add("at most 12 boxes, an overlapping one dropped: " + p.boxes().size());
        }
        if (p.buttons().size() != 4 || !p.buttons().get(0).label().equals("Next") || p.buttons().get(0).colour() != 0x19D3B0
                || !p.buttons().get(1).url().equals("https://store.example.net") || p.buttons().get(2).job() != Job.PREVIOUS) {
            wrong.add("a branch to a point that is not a child and an http link are dropped, an empty label gets the job's, at most 4: " + p.buttons());
        }
        if (p.seconds() != TutorialPopup.MIN_SECONDS || new TutorialPopup(TutorialPopup.Size.SMALL, false, 99, 0, Placement.DEFAULT, List.of(), List.of()).sanitised(Set.of()).seconds() != 60) {
            wrong.add("the time on screen is 2 to 60 seconds");
        }
        if (!p.place().equals(new Placement(1000 - Placement.MIN_W, 0, Placement.MIN_W, 1000))) {
            wrong.add("a small popup is at least 80 x 30 and kept on screen (D741): " + p.place());
        }
        TutorialPopup walking = new TutorialPopup(TutorialPopup.Size.SMALL, false, 8, 0, Placement.DEFAULT,
                List.of(new Box(4, 3, 5, 5, Kind.TEXT, TutorialText.of("Walk on"), Image.NONE, 0)), List.of(Button.of("Next", Job.NEXT))).sanitised(Set.of());
        if (!walking.buttons().isEmpty() || walking.boxes().get(0).w() != 2 || walking.boxes().get(0).h() != 1) {
            wrong.add("a popup that keeps the player walking has no buttons (D739), and a small one's grid is 6 x 4: " + walking);
        }
        // the tutorial
        List<Point> points = new ArrayList<>();
        points.add(new Point(0, "", "not an item!", 99_999, -99_999, new Place("alpha", OVERWORLD, 0, 64, 0, 20), popup(Button.of("Next", Job.NEXT))));
        points.add(new Point(0, "Duplicate", "", 0, 0, Place.NONE, null));
        points.add(new Point(-1, "Negative", "", 0, 0, Place.NONE, null));
        points.add(new Point(1, "§eGold\ttitle that is far too long for a node", "minecraft:diamond", 0, 0, new Place("alpha", "Not A Dimension", 1, 2, 3, 0), null));
        points.add(new Point(2, "Other world", "", 0, 0, new Place("alpha", "minecraft:the_nether", 0, 64, 0, 2), null));
        points.add(new Point(3, "Same world", "", 0, 0, at("ALPHA", 40, 0), null));
        for (int i = 10; i < 80; i++) {
            points.add(point(i, "P" + i, Place.NONE, popup(Button.of("Next", Job.NEXT))));
        }
        List<BlockPos> longPath = new ArrayList<>();
        for (int i = 0; i < 1400; i++) {
            longPath.add(new BlockPos(i / 2, 64, 0)); // every position twice in a row
        }
        List<Link> links = List.of(new Link(0, 0, List.of()), new Link(0, 99, List.of()), new Link(0, 1, List.of(BlockPos.ZERO)),
                new Link(0, 1, List.of()), new Link(1, 0, List.of()), new Link(0, 2, walk(0, 30, 0)), new Link(0, 3, longPath));
        Tutorial t = new Tutorial("not-a-uuid", "  ", null, null, new Area("alpha", OVERWORLD, 10, 70, 10, -500, 60, 0), "Line one\nLine §btwo",
                points, links, "Admin", 5, false, 0).sanitised();
        if (!t.id().isEmpty() || !t.name().equals("Tutorial") || t.start() != Tutorial.Start.COMMAND || t.audience() != Tutorial.Audience.NEW_PLAYERS) {
            wrong.add("an id that is not a UUID is dropped, an empty name becomes Tutorial, a missing start /tutorial only and the audience new players: " + t.id() + " " + t.name());
        }
        if (!t.offer().equals("Line one\nLine btwo")) {
            wrong.add("the offer keeps its lines: " + t.offer());
        }
        if (!t.area().equals(new Area("alpha", OVERWORLD, -500, 60, 0, -245, 70, 10))) {
            wrong.add("an area's corners are put in order and at most 256 blocks a side: " + t.area());
        }
        if (t.points().size() != Tutorial.MAX_POINTS || t.point(-1) != null || !t.point(0).title().equals("Point 1") || !t.point(0).icon().equals(Tutorial.DEFAULT_ICON)
                || t.point(0).cx() != Tutorial.CANVAS || t.point(0).place().radius() != Tutorial.MAX_RADIUS) {
            wrong.add("points: a duplicate or negative id dropped, at most 64, an empty title and a bad icon replaced, kept on the canvas, the radius at most 8: " + t.point(0));
        }
        Point one = t.point(1);
        if (one.place().isSet() || one.place().radius() != Tutorial.MIN_RADIUS || !one.title().equals("eGold title that is far too long") || !one.popup().equals(TutorialPopup.defaults())) {
            wrong.add("a place in a world that is not a world name is no place, the radius at least 1, a title cut to 32, a missing popup the default: " + one);
        }
        if (t.links().size() != 3 || !t.children(0).equals(List.of(1, 2, 3))) {
            wrong.add("links to itself, to a missing point, repeated, or back where they came from are dropped: " + t.links());
        }
        if (!t.links().get(0).path().isEmpty() || !t.links().get(1).path().isEmpty()) {
            wrong.add("a path is kept only between points placed in the same world: " + t.links());
        }
        List<BlockPos> kept = t.links().get(2).path();
        if (kept.size() != Tutorial.MAX_PATH || !kept.get(1).equals(new BlockPos(1, 64, 0))) {
            wrong.add("a path between two places on the same server (any case) is kept, repeats in a row dropped, at most 500: " + kept.size());
        }
        Tutorial gone = forked("Gone").asDeleted("Admin", 9).sanitised();
        if (!gone.deleted() || !gone.points().isEmpty() || !gone.links().isEmpty() || gone.revision() != 9 || !gone.name().equals("Gone")) {
            wrong.add("a deleted copy keeps only its id, name and revision: " + gone);
        }
        h.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_tutorial_reads_back_as_it_was_saved(GameTestHelper h) {
        Tutorial t = forked("Welcome to BSP").withStart(Tutorial.Start.FIRST_JOIN).withAudience(Tutorial.Audience.NOT_FINISHED)
                .withArea(new Area("beta", OVERWORLD, 1, 2, 3, 4, 5, 6)).withOffer("Want a tour?\nAbout five minutes.");
        TutorialText rich = new TutorialText(List.of(
                new Para(TutorialText.Size.XL, TutorialText.Align.CENTRE, List.of(new Run("The ", 0, 0xE6EAF2), new Run("Market", TutorialText.BOLD | TutorialText.UNDERLINE, 0xFFD23A))),
                new Para(TutorialText.Size.S, TutorialText.Align.RIGHT, List.of(new Run("secret", TutorialText.OBFUSCATED | TutorialText.ITALIC | TutorialText.STRIKE, 0xFF6B5C)))));
        TutorialPopup small = new TutorialPopup(TutorialPopup.Size.SMALL, false, 12, 0x801D2B44, new Placement(40, 50, 300, 200),
                List.of(new Box(0, 0, 4, 2, Kind.TEXT, rich, Image.NONE, 0xFF0B0E13),
                        new Box(4, 0, 2, 4, Kind.IMAGE, TutorialText.EMPTY, new Image(TutorialPopup.Source.WEB, "", "https://img.example.net/a.png", TutorialPopup.Fit.FILL), 0)), List.of());
        List<Point> ps = new ArrayList<>(t.points());
        ps.set(2, ps.get(2).withPopup(small));
        t = t.withPoints(ps).withRevision(1_700_000_000_123L, "Mrgregles").sanitised();
        List<String> wrong = new ArrayList<>();
        Tutorial back = Tutorial.load(t.save());
        if (!back.equals(t)) {
            wrong.add("saved and loaded: " + back + " vs " + t);
        }
        Tutorial decoded = Tutorial.decode(t.encode());
        if (!t.equals(decoded)) {
            wrong.add("through the shared table's text: " + decoded);
        }
        if (Tutorial.decode("{not snbt") != null || Tutorial.decode("") != null || Tutorial.decode("{name:\"No id\"}") != null) {
            wrong.add("text that is not a tutorial reads as none");
        }
        Tutorial gone = t.asDeleted("Mrgregles", t.revision() + 1);
        if (!gone.equals(Tutorial.decode(gone.encode()))) {
            wrong.add("a deleted copy reads back: " + Tutorial.decode(gone.encode()));
        }
        if (!t.point(1).popup().buttons().get(1).label().equals("Quick start") || t.links().get(1).path().size() != 11) {
            wrong.add("the sample kept its branch buttons and path: " + t.point(1).popup().buttons());
        }
        h.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_builder_marks_what_is_missing(GameTestHelper h) {
        List<String> wrong = new ArrayList<>();
        Tutorial good = forked("Good").withLinks(List.of(new Link(0, 1, List.of()), new Link(1, 2, walk(0, 30, 0)), new Link(1, 3, List.of()))).sanitised();
        if (!good.issues().isEmpty()) {
            wrong.add("a complete tutorial has nothing marked: " + good.issues());
        }
        if (!Tutorial.create("Empty").issues().equals(List.of(new Issue(Problem.NO_POINTS, -1, -1, -1)))) {
            wrong.add("no points: " + Tutorial.create("Empty").issues());
        }
        List<Point> ps = new ArrayList<>(good.points());
        ps.set(1, ps.get(1).withPopup(popup(Button.of("Next", Job.NEXT), new Button("Full tour", 0, Job.BRANCH, 2, ""))));
        ps.set(2, ps.get(2).withPopup(popup(Button.of("Back", Job.PREVIOUS))));
        ps.add(point(5, "Loose", Place.NONE, popup(Button.of("End", Job.END))));
        Tutorial bad = good.withPoints(ps).withLinks(List.of(new Link(0, 1, List.of()), new Link(1, 2, List.of()), new Link(1, 3, List.of())))
                .withStart(Tutorial.Start.AREA).sanitised();
        List<Issue> issues = bad.issues();
        List<Issue> want = List.of(new Issue(Problem.MANY_STARTS, 0, -1, -1), new Issue(Problem.MANY_STARTS, 5, -1, -1), new Issue(Problem.NO_AREA, -1, -1, -1),
                new Issue(Problem.FORK_NEEDS_BRANCH, 1, -1, -1), new Issue(Problem.STUCK, 2, -1, -1), new Issue(Problem.NO_PATH, -1, 1, 2));
        if (!issues.equals(want)) {
            wrong.add("two starts, no area, a fork missing a branch button, a popup with no way on, a path not drawn: " + issues);
        }
        ps.set(1, ps.get(1).withPopup(new TutorialPopup(TutorialPopup.Size.SMALL, false, 8, 0, Placement.DEFAULT, List.of(), List.of())));
        if (!bad.withPoints(ps).sanitised().issues().contains(new Issue(Problem.WALKING_AT_FORK, 1, -1, -1))) {
            wrong.add("a fork that keeps the player walking (D739)");
        }
        Tutorial loop = good.withLinks(List.of(new Link(0, 1, List.of()), new Link(1, 0, List.of()))).sanitised();
        if (loop.links().size() != 1) {
            wrong.add("a loop is never saved, so there is always a start: " + loop.links());
        }
        h.assertTrue(wrong.isEmpty(), String.join("; ", wrong));
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void drafts_change_until_published_and_one_starts_on_first_join(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        try {
            reset(server);
            TutorialService.Result made = TutorialService.create(server, null, "Console", "Welcome to BSP");
            String id = made.tutorial().id();
            h.assertTrue(made.saved() && made.tutorial().editedBy().equals("Console") && UUID.fromString(id) != null, "a new tutorial's draft: " + made);
            h.assertTrue(TutorialService.list(server, TutorialStage.PUBLISHED).isEmpty(), "players see nothing until it is published");
            Tutorial shape = forked("Welcome to BSP");
            TutorialService.Result edited = TutorialService.editDraft(server, null, "Console", id, t -> t.withPoints(shape.points()).withLinks(shape.links()));
            h.assertTrue(edited.saved() && edited.tutorial().points().size() == 4 && edited.tutorial().revision() > made.tutorial().revision(),
                    "an edit saves the draft with a higher revision: " + edited);
            h.assertTrue(TutorialService.editDraft(server, null, "Console", id, t -> t).outcome() == Outcome.UNCHANGED, "an edit that changes nothing saves nothing");
            TutorialService.Result pub = TutorialService.publish(server, null, "Console", id);
            h.assertTrue(pub.saved() && TutorialService.get(server, TutorialStage.PUBLISHED, id).orElseThrow().points().equals(edited.tutorial().points()),
                    "PUBLISH copies the draft for players (D734)");
            TutorialService.editDraft(server, null, "Console", id, t -> t.withName("Welcome, renamed"));
            h.assertTrue(TutorialService.get(server, TutorialStage.PUBLISHED, id).orElseThrow().name().equals("Welcome to BSP"), "later edits stay in the draft");
            h.assertTrue(TutorialService.publish(server, null, "Console", id).saved() && TutorialService.publish(server, null, "Console", id).outcome() == Outcome.UNCHANGED,
                    "publishing the same draft twice saves once");
            // only those who may edit
            ServerPlayer player = TestPlayers.mockServerPlayer(h);
            h.assertTrue(TutorialService.create(server, player, "", "Mine").outcome() == Outcome.NOT_ALLOWED
                            && TutorialService.editDraft(server, player, "", id, t -> t.withName("Mine")).outcome() == Outcome.NOT_ALLOWED
                            && TutorialService.publish(server, player, "", id).outcome() == Outcome.NOT_ALLOWED
                            && TutorialService.delete(server, player, "", id).outcome() == Outcome.NOT_ALLOWED
                            && TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().name().equals("Welcome, renamed"),
                    "a player who may not edit changes nothing");
            // one first-join tutorial
            TutorialService.editDraft(server, null, "Console", id, t -> t.withStart(Tutorial.Start.FIRST_JOIN));
            TutorialService.publish(server, null, "Console", id);
            String other = TutorialService.create(server, null, "Console", "Mining 101").tutorial().id();
            TutorialService.editDraft(server, null, "Console", other, t -> t.withStart(Tutorial.Start.FIRST_JOIN));
            h.assertTrue(TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().start() == Tutorial.Start.COMMAND
                            && TutorialService.firstJoin(server).orElseThrow().id().equals(id),
                    "a draft set to first join takes it from the other drafts; the published one is still offered");
            TutorialService.publish(server, null, "Console", other);
            h.assertTrue(TutorialService.firstJoin(server).orElseThrow().id().equals(other)
                            && TutorialService.get(server, TutorialStage.PUBLISHED, id).orElseThrow().start() == Tutorial.Start.COMMAND,
                    "publishing it takes first join from the one published before (D720)");
            // deleting
            TutorialService.Result del = TutorialService.delete(server, null, "Console", id);
            h.assertTrue(del.saved() && TutorialService.get(server, TutorialStage.DRAFT, id).isEmpty() && TutorialService.get(server, TutorialStage.PUBLISHED, id).isEmpty()
                            && TutorialService.list(server, TutorialStage.DRAFT).size() == 1,
                    "a deleted tutorial is gone, draft and published");
            h.assertTrue(TutorialService.editDraft(server, null, "Console", id, t -> t.withName("Back")).outcome() == Outcome.NOT_FOUND
                            && TutorialService.delete(server, null, "Console", id).outcome() == Outcome.NOT_FOUND,
                    "and cannot be edited or deleted again");
        } finally {
            reset(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void tutorials_are_one_set_for_the_network_and_the_newest_wins(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha"), beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        try {
            reset(server);
            Storage.useForTests(alpha);
            String id = TutorialService.create(server, null, "Console", "Welcome to BSP").tutorial().id();
            Tutorial shape = forked("Welcome to BSP");
            TutorialService.editDraft(server, null, "Console", id, t -> t.withPoints(shape.points()).withLinks(shape.links()));
            TutorialService.publish(server, null, "Console", id);
            alpha.drain();
            Tutorial draft = TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow(), pub = TutorialService.get(server, TutorialStage.PUBLISHED, id).orElseThrow();
            h.assertTrue(draft.equals(db.loadTutorial("draft", id)) && pub.equals(db.loadTutorial("published", id)) && db.loadTutorials().size() == 2,
                    "both copies are written to the network's table: " + db.loadTutorials().size());
            // the other server hears the notices and reads the copies back
            TutorialService.clearForTests(server);
            Storage.useForTests(beta);
            beta.pollNotices();
            beta.drain();
            h.assertTrue(draft.equals(TutorialService.get(server, TutorialStage.DRAFT, id).orElse(null)) && pub.equals(TutorialService.get(server, TutorialStage.PUBLISHED, id).orElse(null)),
                    "another server takes both copies");
            // older copies never win
            Tutorial older = draft.withName("Older").withRevision(draft.revision() - 1, "Console");
            h.assertTrue(!TutorialService.apply(server, TutorialStage.DRAFT, older) && TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().equals(draft),
                    "an older revision is ignored");
            db.saveTutorial("draft", older);
            h.assertTrue(db.loadTutorial("draft", id).equals(draft), "nor does a late older write replace the table's row");
            // a deletion travels as a copy too
            Storage.useForTests(alpha);
            TutorialService.delete(server, null, "Console", id);
            alpha.drain();
            TutorialService.clearForTests(server);
            TutorialService.putForTests(server, TutorialStage.DRAFT, draft);
            TutorialService.putForTests(server, TutorialStage.PUBLISHED, pub);
            Storage.useForTests(beta);
            beta.pollNotices();
            beta.drain();
            h.assertTrue(TutorialService.get(server, TutorialStage.DRAFT, id).isEmpty() && TutorialService.get(server, TutorialStage.PUBLISHED, id).isEmpty(),
                    "a deletion on one server deletes it on the others");
            // at start: the table's newer copies are taken, ours that are newer are written back
            Tutorial deleted = db.loadTutorial("draft", id);
            TutorialService.clearForTests(server);
            TutorialService.putForTests(server, TutorialStage.DRAFT, draft);
            String local = TutorialService.create(server, null, "Console", "Only here").tutorial().id();
            beta.drain();
            TutorialService.clearForTests(server);
            TutorialService.putForTests(server, TutorialStage.DRAFT, draft);
            Tutorial onlyHere = Tutorial.create("Not yet shared").withRevision(5, "Console");
            TutorialService.putForTests(server, TutorialStage.DRAFT, onlyHere);
            TutorialService.onServerStarted(server);
            beta.drain();
            h.assertTrue(TutorialService.get(server, TutorialStage.DRAFT, id).isEmpty() && TutorialService.get(server, TutorialStage.DRAFT, local).isPresent()
                            && deleted.equals(db.loadTutorial("draft", id)),
                    "a starting server takes the table's newer copies (the deletion, and one it never had)");
            h.assertTrue(onlyHere.equals(db.loadTutorial("draft", onlyHere.id())), "and writes the copies the table lacks");
            Tutorial newer = TutorialService.get(server, TutorialStage.DRAFT, local).orElseThrow().withName("Newer here").withRevision(Long.MAX_VALUE / 2, "Console");
            TutorialService.putForTests(server, TutorialStage.DRAFT, newer);
            TutorialService.onServerStarted(server);
            beta.drain();
            h.assertTrue(newer.equals(db.loadTutorial("draft", local)), "and puts back a copy newer than the table's: " + db.loadTutorial("draft", local));
            // a save the table refused is tried again until it lands
            TutorialService.useClockForTests(System::currentTimeMillis);
            String later = TutorialService.create(server, null, "Console", "Later").tutorial().id();
            beta.drain();
            db.setFailing(true);
            TutorialService.editDraft(server, null, "Console", later, t -> t.withOffer("Written later"));
            beta.drain();
            db.setFailing(false);
            h.assertTrue(TutorialService.unwritten() && db.loadTutorial("draft", later).offer().isEmpty(), "the table was down: the save is kept to write again");
            TutorialService.tick(server);
            beta.drain();
            h.assertTrue(!TutorialService.unwritten() && db.loadTutorial("draft", later).offer().equals("Written later"), "and lands when the table is back");
            TutorialService.clearForTests(server);
            Storage.useForTests(alpha);
            alpha.pollNotices();
            alpha.drain();
            h.assertTrue(TutorialService.get(server, TutorialStage.DRAFT, later).map(Tutorial::offer).orElse("").equals("Written later"),
                    "and the other servers are told when it lands (audit of SU-146)");
        } finally {
            alpha.close();
            beta.close();
            reset(server);
        }
        h.succeed();
    }
}
