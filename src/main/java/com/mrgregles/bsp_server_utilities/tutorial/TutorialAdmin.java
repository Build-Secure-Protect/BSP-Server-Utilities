package com.mrgregles.bsp_server_utilities.tutorial;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TutorialBlobPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialEditPacket;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

/**
 * The tutorial builder's server side (SU-142, SU-143; owner decisions D719, D728, D734, D735): who has it open and which
 * tutorial they look at, the view sent to them (every tutorial's draft with its state, the open one's draft, its
 * pictures' hashes, the picture files, the counts), the drafts they save (merged with what only the server sets: the
 * places, the paths, the area), publishing, deleting, a point's place set to where the admin stands, going to a point,
 * and the Action Log lines (kind "tutorial"). Only those who may edit get anything.
 */
public final class TutorialAdmin {
    private static final Map<UUID, String> VIEWING = new HashMap<>();
    private static final Map<UUID, TutorialBlobPacket.Collector> UPLOADS = new HashMap<>();
    private static int uploads;
    private static BiConsumer<ServerPlayer, Object> sender = ServerUtilsNetwork::sendTo;

    private TutorialAdmin() {
    }

    /** {@code /su tutorial}: tells the admin's client to open the builder (it asks for the view itself). */
    public static boolean openFor(ServerPlayer admin) {
        if (!Perms.canEdit(admin)) {
            return false;
        }
        sender.accept(admin, new com.mrgregles.bsp_server_utilities.network.TutorialModePacket(openTag()));
        return true;
    }

    private static CompoundTag openTag() {
        CompoundTag t = new CompoundTag();
        t.putBoolean("openBuilder", true);
        return t;
    }

    public static void edit(ServerPlayer admin, TutorialEditPacket p) {
        if (!Perms.canEdit(admin)) {
            return;
        }
        MinecraftServer server = admin.server;
        String name = admin.getGameProfile().getName();
        switch (p.action()) {
            case OPEN -> {
                VIEWING.put(admin.getUUID(), p.tutorial());
                send(admin);
            }
            case CLOSE -> VIEWING.remove(admin.getUUID());
            case CREATE -> {
                TutorialService.Result r = TutorialService.create(server, admin, name, p.text());
                if (r.tutorial() != null) {
                    VIEWING.put(admin.getUUID(), r.tutorial().id());
                    SetupService.touched(server, Feature.TUTORIAL);
                    log(admin, "made the tutorial " + r.tutorial().name());
                }
                refresh(server);
            }
            case PUBLISH -> {
                TutorialService.Result r = TutorialService.publish(server, admin, name, p.tutorial());
                if (r.saved()) {
                    SetupService.touched(server, Feature.TUTORIAL);
                    log(admin, "published the tutorial " + r.tutorial().name() + " (" + r.tutorial().points().size() + " points)");
                }
                refresh(server);
            }
            case DELETE -> {
                Optional<Tutorial> was = TutorialService.get(server, TutorialStage.DRAFT, p.tutorial());
                if (TutorialService.delete(server, admin, name, p.tutorial()).saved()) {
                    log(admin, "deleted the tutorial " + was.map(Tutorial::name).orElse(p.tutorial()));
                }
                VIEWING.put(admin.getUUID(), "");
                refresh(server);
            }
            case PLACE_HERE -> {
                Tutorial.Place here = new Tutorial.Place(Storage.currentServerId(), admin.level().dimension().location().toString(),
                        admin.getBlockX(), admin.getBlockY(), admin.getBlockZ(), Tutorial.DEFAULT_RADIUS);
                TutorialService.editDraft(server, admin, name, p.tutorial(), t -> {
                    List<Tutorial.Point> ps = new ArrayList<>();
                    for (Tutorial.Point pt : t.points()) {
                        ps.add(pt.id() == p.a() ? pt.withPlace(new Tutorial.Place(here.server(), here.dimension(), here.x(), here.y(), here.z(), pt.place().radius())) : pt);
                    }
                    return t.withPoints(ps);
                });
                refresh(server);
            }
            case GO_THERE -> TutorialService.get(server, TutorialStage.DRAFT, p.tutorial()).map(t -> t.point(p.a())).ifPresent(pt -> {
                if (pt.place().isSet()) {
                    TutorialRunner.moveThere(admin, pt, true);
                }
            });
            case DRAW_PATH -> PathRecorder.beginPath(admin, p.tutorial(), p.a(), p.b());
            case SET_AREA -> PathRecorder.beginArea(admin, p.tutorial());
            case TEST -> {
                if (!TutorialRunner.start(admin, p.tutorial())) {
                    admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.test_unpublished"));
                }
            }
        }
    }

    /** A part of a saved draft; the whole draft is merged and saved once every part is in. */
    public static void part(ServerPlayer admin, TutorialBlobPacket p) {
        if (!Perms.canEdit(admin)) {
            return;
        }
        CompoundTag tag = UPLOADS.computeIfAbsent(admin.getUUID(), k -> new TutorialBlobPacket.Collector()).add(p);
        if (tag != null) {
            save(admin, tag);
        }
    }

    /** Saves a draft the builder sent: the admin's words, popups, points and links, the server's places, paths and area. */
    public static TutorialService.Result save(ServerPlayer admin, CompoundTag tag) {
        MinecraftServer server = admin.server;
        Tutorial client = Tutorial.load(tag.getCompound("tutorial"));
        Optional<Tutorial> was = TutorialService.get(server, TutorialStage.DRAFT, client.id());
        if (was.isEmpty()) {
            return new TutorialService.Result(TutorialService.Outcome.NOT_FOUND, null);
        }
        long base = tag.getLong("base");
        if (base > 0 && was.get().revision() > base && !was.get().editedBy().equals(admin.getGameProfile().getName())) {
            // another admin saved since this builder read the draft: not saved over theirs (audit of SU-146)
            admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.save_conflict", was.get().editedBy()));
            refresh(server);
            return new TutorialService.Result(TutorialService.Outcome.UNCHANGED, was.get());
        }
        TutorialService.Result r = TutorialService.editDraft(server, admin, admin.getGameProfile().getName(), client.id(), t -> merge(t, client));
        if (r.outcome() == TutorialService.Outcome.TOO_BIG) {
            admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.too_big"));
        }
        if (r.saved()) {
            SetupService.touched(server, Feature.TUTORIAL);
            Set<Integer> kept = new HashSet<>();
            r.tutorial().points().forEach(pt -> kept.add(pt.id()));
            for (Tutorial.Point pt : was.get().points()) {
                if (!kept.contains(pt.id())) {
                    log(admin, "removed the point " + pt.title() + " from the tutorial " + r.tutorial().name());
                }
            }
        }
        refresh(server);
        return r;
    }

    /**
     * The builder's copy over the server's: names, start, audience, offer, points (their places only as the server has
     * them, with the builder's radius, or none when the builder cleared one), popups and links (each keeping the path
     * the server has for it). The area only as the server has it, or none when the builder cleared it.
     */
    static Tutorial merge(Tutorial server, Tutorial client) {
        List<Tutorial.Point> points = new ArrayList<>();
        for (Tutorial.Point c : client.points()) {
            Tutorial.Point s = server.point(c.id());
            Tutorial.Place base = s == null ? Tutorial.Place.NONE : s.place();
            Tutorial.Place place = c.place().isSet() && base.isSet()
                    ? new Tutorial.Place(base.server(), base.dimension(), base.x(), base.y(), base.z(), c.place().radius())
                    : new Tutorial.Place("", "", 0, 0, 0, c.place().radius());
            points.add(c.withPlace(place));
        }
        List<Tutorial.Link> links = new ArrayList<>();
        for (Tutorial.Link l : client.links()) {
            List<net.minecraft.core.BlockPos> path = List.of();
            for (Tutorial.Link o : server.links()) {
                if (o.from() == l.from() && o.to() == l.to()) {
                    path = o.path();
                }
            }
            links.add(l.withPath(path));
        }
        return server.withName(client.name()).withStart(client.start()).withAudience(client.audience()).withOffer(client.offer())
                .withArea(client.area().isSet() ? server.area() : Tutorial.Area.NONE).withPoints(points).withLinks(links);
    }

    static void log(ServerPlayer admin, String text) {
        ActionLog.record(admin.server, ActionLog.entryFor(admin, ActionLog.tracked(admin).orElse(new Staff.Role("", "Admin", Staff.OPERATOR_COLOUR)), LogEntry.TUTORIAL, text));
    }

    // ------------------------------------------------------------------ the view

    /** Sends the view again to every admin with the builder open (after any change, or a picture arriving). */
    public static void refresh(MinecraftServer server) {
        for (UUID id : new ArrayList<>(VIEWING.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                VIEWING.remove(id);
            } else {
                send(p);
            }
        }
    }

    /** Builds the view and sends it, with the open tutorial's counts read first (from the network's table when shared). */
    public static void send(ServerPlayer admin) {
        MinecraftServer server = admin.server;
        CompoundTag view = view(server, VIEWING.getOrDefault(admin.getUUID(), ""));
        String id = view.getString("selected");
        Storage s = Storage.active();
        if (s == null || id.isEmpty()) {
            view.put("counts", counts(ProgressData.get(server).all(), id));
            deliver(admin, view);
            return;
        }
        UUID who = admin.getUUID();
        s.submit(() -> {
            List<TutorialProgress> rows = s.backend().tutorialProgressOf(id);
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(who);
                if (p != null) {
                    view.put("counts", counts(rows, id));
                    deliver(p, view);
                }
            });
        }, e -> server.execute(() -> {
            // the table could not be read: the builder opens with this world's counts (audit of SU-146)
            ServerPlayer p = server.getPlayerList().getPlayer(who);
            if (p != null) {
                view.put("counts", counts(ProgressData.get(server).all(), id));
                deliver(p, view);
            }
        }));
    }

    private static void deliver(ServerPlayer admin, CompoundTag view) {
        for (TutorialBlobPacket part : TutorialBlobPacket.split(TutorialBlobPacket.Kind.VIEW, ++uploads, view)) {
            sender.accept(admin, part);
        }
    }

    /** The builder's view of one tutorial (the first when none is named or it is gone). */
    public static CompoundTag view(MinecraftServer server, String selected) {
        CompoundTag v = new CompoundTag();
        ListTag list = new ListTag();
        List<Tutorial> drafts = TutorialService.list(server, TutorialStage.DRAFT);
        String sel = "";
        for (Tutorial d : drafts) {
            Optional<Tutorial> pub = TutorialService.get(server, TutorialStage.PUBLISHED, d.id());
            CompoundTag e = new CompoundTag();
            e.putString("id", d.id());
            e.putString("name", d.name());
            e.putString("start", d.start().name());
            e.putBoolean("published", pub.isPresent());
            e.putBoolean("firstJoin", pub.map(x -> x.start() == Tutorial.Start.FIRST_JOIN).orElse(false));
            e.putBoolean("changed", pub.isEmpty() || !TutorialService.sameContent(pub.get(), d));
            e.putInt("issues", d.issues().size());
            list.add(e);
            if (d.id().equals(selected)) {
                sel = d.id();
            }
        }
        if (sel.isEmpty() && !drafts.isEmpty()) {
            sel = drafts.get(0).id();
        }
        v.put("list", list);
        v.putString("selected", sel);
        int pubMax = -1;
        Optional<Tutorial> pub = sel.isEmpty() ? Optional.empty() : TutorialService.get(server, TutorialStage.PUBLISHED, sel);
        if (pub.isPresent()) {
            for (Tutorial.Point pt : pub.get().points()) {
                pubMax = Math.max(pubMax, pt.id());
            }
        }
        v.putInt("publishedMax", pubMax); // new points take ids above it, so a removed stop's id is never reused (audit of SU-146)
        v.putString("server", Storage.currentServerId());
        ListTag files = new ListTag();
        TutorialImages.files().forEach(f -> files.add(StringTag.valueOf(f)));
        v.put("files", files);
        if (!sel.isEmpty()) {
            Tutorial d = TutorialService.get(server, TutorialStage.DRAFT, sel).orElseThrow();
            v.put("draft", d.save());
            ListTag images = new ListTag();
            for (Tutorial.Point pt : d.points()) {
                List<TutorialPopup.Box> boxes = pt.popup().boxes();
                for (int i = 0; i < boxes.size(); i++) {
                    TutorialPopup.Box b = boxes.get(i);
                    if (b.kind() == TutorialPopup.Kind.IMAGE && b.image().isSet()) {
                        CompoundTag im = new CompoundTag();
                        im.putInt("point", pt.id());
                        im.putInt("box", i);
                        im.putString("key", b.image().source() + "|" + (b.image().source() == TutorialPopup.Source.WEB ? b.image().url() : b.image().file()) + "|" + b.w() + "x" + b.h());
                        im.putString("hash", TutorialImages.hash(server, b.image(), b.w(), b.h()));
                        im.putString("error", TutorialImages.failure(b.image(), b.w(), b.h()));
                        images.add(im);
                    }
                }
            }
            v.put("images", images);
        }
        return v;
    }

    /** Started, finished and ended, and where those who ended stopped (D735). */
    static CompoundTag counts(List<TutorialProgress> rows, String tutorial) {
        int started = 0, finished = 0, ended = 0;
        Map<Integer, Integer> at = new HashMap<>();
        for (TutorialProgress p : rows) {
            if (!p.tutorial().equals(tutorial)) {
                continue;
            }
            switch (p.state()) {
                case ACTIVE -> started++;
                case FINISHED -> {
                    started++;
                    finished++;
                }
                case ENDED -> {
                    started++;
                    ended++;
                    at.merge(p.point(), 1, Integer::sum);
                }
                default -> {
                }
            }
        }
        CompoundTag c = new CompoundTag();
        c.putInt("started", started);
        c.putInt("finished", finished);
        c.putInt("ended", ended);
        CompoundTag endedAt = new CompoundTag();
        at.forEach((k, n) -> endedAt.putInt(Integer.toString(k), n));
        c.put("endedAt", endedAt);
        return c;
    }

    public static void onLogout(UUID player) {
        VIEWING.remove(player);
        UPLOADS.remove(player);
    }

    // ------------------------------------------------------------------ tests

    public static void useSenderForTests(@Nullable BiConsumer<ServerPlayer, Object> s) {
        sender = s != null ? s : ServerUtilsNetwork::sendTo;
        VIEWING.clear();
        UPLOADS.clear();
    }
}
