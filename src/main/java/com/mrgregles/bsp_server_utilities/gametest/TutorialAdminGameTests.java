package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.TutorialBlobPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialEditPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialKeyPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialModePacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.tutorial.PathRecorder;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Link;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Place;
import com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Point;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialAdmin;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialRunner;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialService;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialStage;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The tutorial builder's server side (SU-142 to SU-144; owner decisions D719, D728, D731, D734, D735, D737, D742): who
 * may use it, the view, a saved draft merged with what only the server sets (places, paths, the area), SET TO HERE,
 * publishing and deleting with their Action Log lines, the builder's data in pieces, path mode (a point every 3 blocks,
 * UNDO, the finish question in the sphere, KEEP WALKING, FINISH PATH) and area mode (corners, SAVE AREA). The screens
 * need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class TutorialAdminGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static ServerPlayer admin(GameTestHelper h) {
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        h.getLevel().getServer().getPlayerList().getOps().add(new ServerOpListEntry(p.getGameProfile(), 4, false));
        return p;
    }

    private static void reset(MinecraftServer server, List<ServerPlayer> ops) {
        Storage.useForTests(null);
        TutorialService.useRealClock();
        TutorialService.clearForTests(server);
        TutorialRunner.useReal();
        TutorialAdmin.useSenderForTests(null);
        PathRecorder.useSenderForTests(null);
        for (ServerPlayer p : ops) {
            server.getPlayerList().getOps().remove(p.getGameProfile());
        }
    }

    private static List<String> tutorialLines(MinecraftServer server, String text) {
        List<String> out = new ArrayList<>();
        for (LogEntry e : ActionLog.recent(server, 50, false, List.of(LogEntry.TUTORIAL))) {
            if (e.text().contains(text)) {
                out.add(e.text());
            }
        }
        return out;
    }

    /** A draft with two points placed here 9 blocks apart and a link between them. */
    private static String twoPoints(MinecraftServer server, ServerPlayer admin, String name) {
        String id = TutorialService.create(server, admin, "", name).tutorial().id();
        String d = admin.level().dimension().location().toString();
        List<Point> ps = List.of(
                new Point(0, "Gate", "", 0, 0, new Place(Storage.currentServerId(), d, 0, 64, 0, 2), TutorialPopup.defaults()),
                new Point(1, "Hall", "", 40, 0, new Place(Storage.currentServerId(), d, 9, 64, 0, 2), TutorialPopup.defaults()));
        TutorialService.editDraft(server, admin, "", id, t -> t.withPoints(ps).withLinks(List.of(new Link(0, 1, List.of()))));
        return id;
    }

    @GameTest(template = EMPTY)
    public void the_builder_saves_drafts_and_keeps_what_only_the_server_sets(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        ServerPlayer admin = admin(h), player = TestPlayers.mockServerPlayer(h);
        try {
            reset(server, List.of());
            TutorialAdmin.useSenderForTests((p, o) -> sent.add(o));
            // a player who may not edit gets nothing
            TutorialAdmin.edit(player, new TutorialEditPacket(TutorialEditPacket.Action.CREATE, "", -1, -1, "Sneaky"));
            h.assertTrue(TutorialService.list(server, TutorialStage.DRAFT).isEmpty() && sent.isEmpty() && !TutorialAdmin.openFor(player), "only those who may edit (D719)");
            // OPEN sends the view in pieces that read back whole
            TutorialAdmin.edit(admin, new TutorialEditPacket(TutorialEditPacket.Action.CREATE, "", -1, -1, "Welcome to BSP"));
            String id = TutorialService.list(server, TutorialStage.DRAFT).get(0).id();
            CompoundTag sneak = new CompoundTag();
            sneak.put("tutorial", TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().withName("Hacked").save());
            TutorialAdmin.part(player, TutorialBlobPacket.split(TutorialBlobPacket.Kind.SAVE, 1, sneak).get(0));
            h.assertTrue(!PathRecorder.beginPath(player, id, 0, 1) && !PathRecorder.beginArea(player, id)
                            && TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().name().equals("Welcome to BSP"),
                    "a player who may not edit cannot save a draft or start path or area mode");
            h.assertTrue(tutorialLines(server, "made the tutorial Welcome to BSP").size() >= 1, "making one is in the Action Log");
            sent.clear();
            TutorialAdmin.edit(admin, TutorialEditPacket.of(TutorialEditPacket.Action.OPEN, id));
            TutorialBlobPacket.Collector c = new TutorialBlobPacket.Collector();
            CompoundTag view = null;
            for (Object o : sent) {
                if (o instanceof TutorialBlobPacket b) {
                    view = c.add(b);
                }
            }
            h.assertTrue(view != null && view.getString("selected").equals(id) && view.getList("list", 10).size() == 1
                            && Tutorial.load(view.getCompound("draft")).id().equals(id) && view.contains("counts"),
                    "the view: the list, the open tutorial's draft, its counts: " + view);
            // a big draft goes up in pieces and reads back the same
            CompoundTag big = new CompoundTag();
            java.util.Random r = new java.util.Random(7);
            for (int k = 0; k < 6; k++) { // a string tag holds at most 64 KB, so several
                StringBuilder noise = new StringBuilder();
                for (int i = 0; i < 20_000; i++) {
                    noise.append((char) ('a' + r.nextInt(26)));
                }
                big.putString("noise" + k, noise.toString());
            }
            List<TutorialBlobPacket> parts = TutorialBlobPacket.split(TutorialBlobPacket.Kind.SAVE, 5, big);
            TutorialBlobPacket.Collector back = new TutorialBlobPacket.Collector();
            CompoundTag got = null;
            for (int i = parts.size() - 1; i >= 0; i--) {
                got = back.add(parts.get(i));
            }
            h.assertTrue(parts.size() > 1 && parts.stream().allMatch(p -> p.data().length <= TutorialBlobPacket.PART) && big.equals(got),
                    "the builder's data in pieces under a client's 32 KB, in any order: " + parts.size());
            // SET TO HERE, then a path recorded, then the builder saves its copy (no places, no path, an invented area)
            admin.setPos(12.5, 70, -3.5);
            String d = admin.level().dimension().location().toString();
            TutorialService.editDraft(server, admin, "", id, t -> t.withPoints(List.of(
                    new Point(0, "Gate", "", 0, 0, Place.NONE, TutorialPopup.defaults()), new Point(1, "Hall", "", 40, 0, Place.NONE, TutorialPopup.defaults())))
                    .withLinks(List.of(new Link(0, 1, List.of()))));
            TutorialAdmin.edit(admin, new TutorialEditPacket(TutorialEditPacket.Action.PLACE_HERE, id, 0, -1, ""));
            Point gate = TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().point(0);
            h.assertTrue(gate.place().equals(new Place(Storage.currentServerId(), d, 12, 70, -4, 2)), "SET TO HERE: this server, this world, the block the admin stands in: " + gate.place());
            TutorialAdmin.edit(admin, new TutorialEditPacket(TutorialEditPacket.Action.PLACE_HERE, id, 1, -1, ""));
            List<BlockPos> path = List.of(new BlockPos(12, 70, -4), new BlockPos(15, 70, -4));
            TutorialService.editDraft(server, admin, "", id, t -> t.withLinks(List.of(new Link(0, 1, path))).withArea(new Tutorial.Area(Storage.currentServerId(), d, 0, 60, 0, 5, 65, 5)));
            Tutorial client = TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow();
            List<Point> edited = new ArrayList<>();
            for (Point p : client.points()) {
                Place stripped = p.id() == 0 ? new Place(p.place().server(), p.place().dimension(), p.place().x(), p.place().y(), p.place().z(), 5) : new Place("hacked", "minecraft:the_end", 1, 2, 3, 3);
                edited.add(new Point(p.id(), p.id() == 0 ? "Front Gate" : p.title(), p.icon(), p.cx() + 10, p.cy(), stripped, p.popup()));
            }
            edited.add(new Point(2, "Extra", "", 80, 0, new Place("hacked", d, 0, 0, 0, 2), TutorialPopup.defaults()));
            Tutorial sentBack = client.withName("Renamed").withPoints(edited).withLinks(List.of(new Link(0, 1, List.of()), new Link(1, 2, List.of(BlockPos.ZERO))))
                    .withArea(new Tutorial.Area("hacked", d, 0, 0, 0, 1, 1, 1));
            CompoundTag save = new CompoundTag();
            save.put("tutorial", sentBack.save());
            TutorialAdmin.save(admin, save);
            Tutorial now = TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow();
            h.assertTrue(now.name().equals("Renamed") && now.point(0).title().equals("Front Gate") && now.point(0).cx() == 10, "the builder's words and layout are saved");
            h.assertTrue(now.point(0).place().equals(new Place(Storage.currentServerId(), d, 12, 70, -4, 5)), "a place stays the server's, with the builder's radius: " + now.point(0).place());
            h.assertTrue(now.point(1).place().equals(new Place(Storage.currentServerId(), d, 12, 70, -4, 3)) && !now.point(2).place().isSet(),
                    "the builder cannot move a place or make one up: " + now.point(1).place() + " " + now.point(2).place());
            h.assertTrue(now.links().get(0).path().equals(path) && now.links().get(1).path().isEmpty(), "paths stay the server's");
            h.assertTrue(now.area().server().equals(Storage.currentServerId()), "the area stays the server's: " + now.area());
            // a builder that read the draft before another admin saved cannot save over it (audit of SU-146)
            TutorialService.editDraft(server, null, "Other admin", id, t -> t.withOffer("Theirs"));
            CompoundTag stale = new CompoundTag();
            stale.put("tutorial", now.withName("Mine").save());
            stale.putLong("base", now.revision());
            TutorialAdmin.save(admin, stale);
            Tutorial after = TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow();
            h.assertTrue(after.offer().equals("Theirs") && after.name().equals("Renamed"), "a stale save is refused: " + after.name());
            now = after;
                        // clearing the area and a place from the builder is allowed
            save.put("tutorial", now.withArea(Tutorial.Area.NONE).save());
            TutorialAdmin.save(admin, save);
            h.assertTrue(!TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().area().isSet(), "the builder can clear the area");
            // a point removed is in the Action Log; publish and delete too (D735)
            save.put("tutorial", now.withPoints(List.of(now.point(0), now.point(1))).withLinks(List.of(now.links().get(0))).save());
            TutorialAdmin.save(admin, save);
            h.assertTrue(!tutorialLines(server, "removed the point Extra from the tutorial Renamed").isEmpty(), "removing a point is logged");
            TutorialAdmin.edit(admin, TutorialEditPacket.of(TutorialEditPacket.Action.PUBLISH, id));
            h.assertTrue(TutorialService.get(server, TutorialStage.PUBLISHED, id).isPresent() && !tutorialLines(server, "published the tutorial Renamed (2 points)").isEmpty(),
                    "PUBLISH from the builder, logged");
            TutorialAdmin.edit(admin, TutorialEditPacket.of(TutorialEditPacket.Action.DELETE, id));
            h.assertTrue(TutorialService.list(server, TutorialStage.DRAFT).isEmpty() && !tutorialLines(server, "deleted the tutorial Renamed").isEmpty(), "DELETE, logged");
        } finally {
            reset(server, List.of(admin));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void path_mode_records_a_point_every_three_blocks(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        ServerPlayer admin = admin(h);
        try {
            reset(server, List.of());
            PathRecorder.useSenderForTests((p, o) -> sent.add(o));
            TutorialAdmin.useSenderForTests((p, o) -> {
            });
            String id = twoPoints(server, admin, "Paths");
            admin.setPos(100.5, 64, 100.5);
            h.assertTrue(PathRecorder.beginPath(admin, id, 0, 1) && admin.position().distanceTo(new net.minecraft.world.phys.Vec3(0.5, 64, 0.5)) < 0.01,
                    "DRAW PATH moves the admin to the first point (D731): " + admin.position());
            h.assertTrue(!PathRecorder.beginPath(admin, id, 1, 0), "only along a link's direction");
            TutorialModePacket m = (TutorialModePacket) sent.get(sent.size() - 1);
            h.assertTrue(m.state().getString("kind").equals("PATH") && !m.state().getBoolean("recording") && m.state().getString("toTitle").equals("Hall"), "path mode, waiting for G");
            PathRecorder.key(admin, TutorialKeyPacket.Action.GO);
            h.assertTrue(PathRecorder.samplesForTests(admin).equals(List.of(new BlockPos(0, 64, 0))), "G starts at the first point");
            admin.setPos(2.5, 64, 0.5);
            PathRecorder.sampleForTests(admin);
            h.assertTrue(PathRecorder.samplesForTests(admin).size() == 1, "nothing before 3 blocks");
            admin.setPos(3.5, 64, 0.5);
            PathRecorder.sampleForTests(admin);
            admin.setPos(3.5, 67, 0.5); // flying up counts
            PathRecorder.sampleForTests(admin);
            h.assertTrue(PathRecorder.samplesForTests(admin).equals(List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0), new BlockPos(3, 67, 0))),
                    "a block-centred point each time 3 blocks from the last, walking or flying: " + PathRecorder.samplesForTests(admin));
            PathRecorder.key(admin, TutorialKeyPacket.Action.UNDO);
            PathRecorder.key(admin, TutorialKeyPacket.Action.GO); // pause
            admin.setPos(6.5, 64, 0.5);
            PathRecorder.sampleForTests(admin);
            h.assertTrue(PathRecorder.samplesForTests(admin).size() == 2, "Z takes the last point off; paused, nothing is kept");
            PathRecorder.key(admin, TutorialKeyPacket.Action.GO); // resume
            PathRecorder.sampleForTests(admin);
            admin.setPos(8.5, 64, 0.5); // inside the Hall's sphere (radius 2 round x 9.5)
            PathRecorder.sampleForTests(admin);
            h.assertTrue(PathRecorder.askedForTests(admin), "inside the next point's sphere the admin is asked to finish");
            PathRecorder.key(admin, TutorialKeyPacket.Action.KEEP);
            PathRecorder.sampleForTests(admin);
            h.assertTrue(!PathRecorder.askedForTests(admin), "KEEP WALKING: not asked again while still inside");
            admin.setPos(20.5, 64, 0.5);
            PathRecorder.sampleForTests(admin);
            admin.setPos(9.5, 64, 0.5);
            PathRecorder.sampleForTests(admin);
            h.assertTrue(PathRecorder.askedForTests(admin), "asked again after walking out and back in");
            PathRecorder.key(admin, TutorialKeyPacket.Action.FINISH);
            Tutorial t = TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow();
            List<BlockPos> path = t.links().get(0).path();
            h.assertTrue(!PathRecorder.inModeForTests(admin) && path.get(0).equals(new BlockPos(0, 64, 0)) && path.get(path.size() - 1).equals(new BlockPos(9, 64, 0)),
                    "FINISH PATH saves the path to the draft's link, ending at the point: " + path);
            h.assertTrue(!tutorialLines(server, "drew the path from Gate to Hall in the tutorial Paths").isEmpty(), "and it is in the Action Log (D735)");
            // CANCEL saves nothing
            PathRecorder.beginPath(admin, id, 0, 1);
            PathRecorder.key(admin, TutorialKeyPacket.Action.GO);
            PathRecorder.key(admin, TutorialKeyPacket.Action.CANCEL);
            h.assertTrue(!PathRecorder.inModeForTests(admin) && TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().links().get(0).path().equals(path), "Delete cancels: nothing saved");
        } finally {
            reset(server, List.of(admin));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void area_mode_sets_a_start_area(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        List<Object> sent = new ArrayList<>();
        ServerPlayer admin = admin(h);
        try {
            reset(server, List.of());
            PathRecorder.useSenderForTests((p, o) -> sent.add(o));
            TutorialAdmin.useSenderForTests((p, o) -> {
            });
            String id = TutorialService.create(server, admin, "", "Mining 101").tutorial().id();
            h.assertTrue(PathRecorder.beginArea(admin, id), "SET AREA");
            admin.setPos(10.5, 60, 10.5);
            PathRecorder.key(admin, TutorialKeyPacket.Action.GO);
            admin.setPos(-2.5, 66, 20.5);
            PathRecorder.key(admin, TutorialKeyPacket.Action.GO);
            CompoundTag st = ((TutorialModePacket) sent.get(sent.size() - 1)).state();
            h.assertTrue(st.contains("c1") && st.contains("c2"), "G sets corner 1, then corner 2, where the admin stands (D737)");
            PathRecorder.key(admin, TutorialKeyPacket.Action.UNDO);
            h.assertTrue(!((TutorialModePacket) sent.get(sent.size() - 1)).state().contains("c2"), "Z undoes the last corner");
            PathRecorder.key(admin, TutorialKeyPacket.Action.SAVE_AREA);
            h.assertTrue(PathRecorder.inModeForTests(admin) && !TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().area().isSet(), "SAVE AREA needs both corners");
            PathRecorder.key(admin, TutorialKeyPacket.Action.GO);
            PathRecorder.key(admin, TutorialKeyPacket.Action.SAVE_AREA);
            Tutorial.Area a = TutorialService.get(server, TutorialStage.DRAFT, id).orElseThrow().area();
            h.assertTrue(!PathRecorder.inModeForTests(admin) && a.equals(new Tutorial.Area(Storage.currentServerId(), admin.level().dimension().location().toString(), -3, 60, 10, 10, 66, 20)),
                    "the area is the box between the corners, on this server and world: " + a);
            h.assertTrue(!tutorialLines(server, "set the start area of the tutorial Mining 101").isEmpty(), "logged (D735)");
        } finally {
            reset(server, List.of(admin));
        }
        h.succeed();
    }
}
