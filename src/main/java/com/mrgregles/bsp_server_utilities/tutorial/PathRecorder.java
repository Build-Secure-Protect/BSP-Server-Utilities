package com.mrgregles.bsp_server_utilities.tutorial;

import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TutorialKeyPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialModePacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Path mode and area mode (SU-144; owner decisions D731, D737, D742), on the server thread.
 *
 * <p><b>Path mode</b>: DRAW PATH on a link between two points placed in the same world moves the admin to the first
 * point (a point on another server: there through BSP-Teleport, and they press DRAW PATH again there). G starts
 * recording: a point is kept each time the admin is 3 blocks from the last one (walking or flying), block-centred, at
 * most {@value Tutorial#MAX_PATH}; G again pauses and resumes; Z takes the last point off; Delete cancels. Inside the
 * next point's sphere the admin is asked to finish: FINISH PATH saves the path to the draft's link; KEEP WALKING goes on
 * recording, and walking out of the sphere and back in asks again.
 *
 * <p><b>Area mode</b>: SET AREA: G sets a corner where the admin stands, corner 1 then corner 2; Z undoes the last;
 * Delete cancels; with both set, SAVE AREA saves the box to the draft (RESET clears the corners).
 */
public final class PathRecorder {
    public static final int STEP = 3;
    private static BiConsumer<ServerPlayer, Object> sender = ServerUtilsNetwork::sendTo;

    enum Kind { PATH, AREA }

    static final class Mode {
        final Kind kind;
        final String tutorial;
        final int from, to;
        boolean recording, paused, asked, dismissed;
        final List<BlockPos> samples = new ArrayList<>();
        @Nullable
        BlockPos c1, c2;
        String dimension = "";

        Mode(Kind kind, String tutorial, int from, int to) {
            this.kind = kind;
            this.tutorial = tutorial;
            this.from = from;
            this.to = to;
        }
    }

    private static final Map<UUID, Mode> MODES = new HashMap<>();

    private PathRecorder() {
    }

    @Nullable
    static Mode mode(UUID admin) {
        return MODES.get(admin);
    }

    /** DRAW PATH: path mode on the link from one point to the next. */
    public static boolean beginPath(ServerPlayer admin, String tutorial, int from, int to) {
        if (!Perms.canEdit(admin)) {
            return false;
        }
        Optional<Tutorial> t = TutorialService.get(admin.server, TutorialStage.DRAFT, tutorial);
        Tutorial.Point a = t.map(x -> x.point(from)).orElse(null), b = t.map(x -> x.point(to)).orElse(null);
        if (a == null || b == null || !t.get().children(from).contains(to)) {
            return false;
        }
        if (!a.place().sameWorld(b.place())) {
            admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.path_needs_places"));
            return false;
        }
        if (!a.place().server().equalsIgnoreCase(Storage.currentServerId())) {
            TutorialRunner.moveThere(admin, a, true);
            admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.path_other_server", a.place().server()));
            return false;
        }
        TutorialRunner.moveThere(admin, a, true);
        if (!TutorialRunner.here(admin, a.place())) {
            return false;
        }
        Mode m = new Mode(Kind.PATH, tutorial, from, to);
        m.dimension = a.place().dimension();
        MODES.put(admin.getUUID(), m);
        send(admin, m);
        return true;
    }

    /** SET AREA: area mode for a tutorial's start area. */
    public static boolean beginArea(ServerPlayer admin, String tutorial) {
        if (!Perms.canEdit(admin) || TutorialService.get(admin.server, TutorialStage.DRAFT, tutorial).isEmpty()) {
            return false;
        }
        Mode m = new Mode(Kind.AREA, tutorial, -1, -1);
        m.dimension = admin.level().dimension().location().toString();
        MODES.put(admin.getUUID(), m);
        send(admin, m);
        return true;
    }

    public static void key(ServerPlayer admin, TutorialKeyPacket.Action action) {
        Mode m = MODES.get(admin.getUUID());
        if (m == null || !Perms.canEdit(admin)) {
            return;
        }
        switch (action) {
            case GO -> {
                if (m.kind == Kind.PATH) {
                    if (!m.recording) {
                        m.recording = true;
                        Tutorial.Point a = point(admin, m, m.from);
                        m.samples.add(a != null ? new BlockPos(a.place().x(), a.place().y(), a.place().z()) : admin.blockPosition());
                        sample(admin, m);
                    } else {
                        m.paused = !m.paused;
                    }
                } else if (m.c1 == null || m.c2 != null) {
                    m.c1 = admin.blockPosition();
                    m.c2 = null;
                    m.dimension = admin.level().dimension().location().toString();
                } else if (admin.level().dimension().location().toString().equals(m.dimension)) {
                    m.c2 = admin.blockPosition();
                }
            }
            case UNDO -> {
                if (m.kind == Kind.PATH) {
                    if (m.samples.size() > 1) {
                        m.samples.remove(m.samples.size() - 1);
                    }
                } else if (m.c2 != null) {
                    m.c2 = null;
                } else {
                    m.c1 = null;
                }
            }
            case CANCEL -> {
                MODES.remove(admin.getUUID());
                admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.mode_cancelled"));
            }
            case FINISH -> {
                Tutorial.Point b = m.kind == Kind.PATH ? point(admin, m, m.to) : null;
                if (m.kind == Kind.PATH && m.recording && b != null && (m.asked || TutorialRunner.near(admin, b.place()))) { // only in the next point's sphere (D731)
                    finishPath(admin, m);
                    return;
                }
            }
            case KEEP -> {
                m.asked = false;
                m.dismissed = true;
            }
            case SAVE_AREA -> {
                if (m.kind == Kind.AREA && m.c1 != null && m.c2 != null) {
                    saveArea(admin, m);
                    return;
                }
            }
            case RESET_AREA -> {
                m.c1 = null;
                m.c2 = null;
            }
        }
        send(admin, MODES.get(admin.getUUID()));
    }

    @Nullable
    private static Tutorial.Point point(ServerPlayer admin, Mode m, int id) {
        return TutorialService.get(admin.server, TutorialStage.DRAFT, m.tutorial).map(t -> t.point(id)).orElse(null);
    }

    private static void finishPath(ServerPlayer admin, Mode m) {
        List<BlockPos> path = new ArrayList<>(m.samples);
        Tutorial.Point b = point(admin, m, m.to);
        if (b != null) {
            BlockPos end = new BlockPos(b.place().x(), b.place().y(), b.place().z());
            if (path.isEmpty() || !path.get(path.size() - 1).equals(end)) {
                path.add(end);
            }
        }
        List<BlockPos> kept = path;
        if (path.size() > Tutorial.MAX_PATH) {
            kept = new ArrayList<>(path.subList(0, Tutorial.MAX_PATH - 1)); // the stop itself stays the last point (audit of SU-146)
            kept.add(path.get(path.size() - 1));
        }
        List<BlockPos> finalPath = kept;
        TutorialService.Result r = TutorialService.editDraft(admin.server, admin, admin.getGameProfile().getName(), m.tutorial, t -> {
            List<Tutorial.Link> links = new ArrayList<>();
            for (Tutorial.Link l : t.links()) {
                links.add(l.from() == m.from && l.to() == m.to ? l.withPath(finalPath) : l);
            }
            return t.withLinks(links);
        });
        MODES.remove(admin.getUUID());
        send(admin, null);
        boolean linked = r.tutorial() != null && r.tutorial().links().stream().anyMatch(l -> l.from() == m.from && l.to() == m.to && !l.path().isEmpty());
        if (!linked) {
            admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.path_lost"));
        } else {
            Tutorial.Point a = r.tutorial().point(m.from), c = r.tutorial().point(m.to);
            TutorialAdmin.log(admin, "drew the path from " + (a == null ? "?" : a.title()) + " to " + (c == null ? "?" : c.title()) + " in the tutorial "
                    + r.tutorial().name() + " (" + kept.size() + " points)");
            admin.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.path_saved", kept.size()));
        }
        TutorialAdmin.refresh(admin.server);
    }

    private static void saveArea(ServerPlayer admin, Mode m) {
        Tutorial.Area area = new Tutorial.Area(Storage.currentServerId(), m.dimension, m.c1.getX(), m.c1.getY(), m.c1.getZ(), m.c2.getX(), m.c2.getY(), m.c2.getZ());
        TutorialService.Result r = TutorialService.editDraft(admin.server, admin, admin.getGameProfile().getName(), m.tutorial, t -> t.withArea(area));
        MODES.remove(admin.getUUID());
        send(admin, null);
        if (r.tutorial() != null) {
            Tutorial.Area a = r.tutorial().area();
            TutorialAdmin.log(admin, "set the start area of the tutorial " + r.tutorial().name() + " on " + a.server() + ", " + a.dimension() + " from "
                    + a.x0() + " " + a.y0() + " " + a.z0() + " to " + a.x1() + " " + a.y1() + " " + a.z1());
        }
        TutorialAdmin.refresh(admin.server);
    }

    /** Every other tick: the recording follows the admin, and arriving in the next point's sphere asks to finish. */
    public static void tick(MinecraftServer server) {
        if (MODES.isEmpty() || server.getTickCount() % 2 != 0) {
            return;
        }
        for (Map.Entry<UUID, Mode> e : new ArrayList<>(MODES.entrySet())) {
            ServerPlayer admin = server.getPlayerList().getPlayer(e.getKey());
            if (admin == null) {
                MODES.remove(e.getKey());
                continue;
            }
            sample(admin, e.getValue());
        }
    }

    /** One look at the admin: a new point when 3 blocks from the last, and the finish question in the sphere. */
    static void sample(ServerPlayer admin, Mode m) {
        if (m.kind != Kind.PATH || !m.recording) {
            return;
        }
        boolean changed = false;
        if (!m.paused && admin.level().dimension().location().toString().equals(m.dimension) && m.samples.size() < Tutorial.MAX_PATH) {
            BlockPos at = admin.blockPosition(), last = m.samples.get(m.samples.size() - 1);
            if (Vec3.atCenterOf(at).distanceTo(Vec3.atCenterOf(last)) >= STEP) {
                m.samples.add(at);
                changed = true;
            }
        }
        Tutorial.Point b = point(admin, m, m.to);
        boolean in = b != null && TutorialRunner.near(admin, b.place());
        if (in && !m.asked && !m.dismissed) {
            m.asked = true;
            changed = true;
        } else if (!in && (m.asked || m.dismissed)) {
            m.asked = false;
            m.dismissed = false;
            changed = true;
        }
        if (changed) {
            send(admin, m);
        }
    }

    /** The mode as the admin's client draws it. */
    static CompoundTag state(ServerPlayer admin, @Nullable Mode m) {
        CompoundTag t = new CompoundTag();
        if (m == null) {
            return t;
        }
        Optional<Tutorial> tut = TutorialService.get(admin.server, TutorialStage.DRAFT, m.tutorial);
        t.putString("kind", m.kind.name());
        t.putString("tutorial", tut.map(Tutorial::name).orElse(""));
        t.putString("dimension", m.dimension);
        t.putBoolean("recording", m.recording);
        t.putBoolean("paused", m.paused);
        t.putBoolean("asked", m.asked);
        ListTag samples = new ListTag();
        m.samples.forEach(b -> samples.add(LongTag.valueOf(b.asLong())));
        t.put("samples", samples);
        if (m.kind == Kind.PATH) {
            Tutorial.Point a = point(admin, m, m.from), b = point(admin, m, m.to);
            if (a != null && b != null) {
                t.putString("fromTitle", a.title());
                t.putString("toTitle", b.title());
                t.putLong("from", new BlockPos(a.place().x(), a.place().y(), a.place().z()).asLong());
                t.putInt("fromRadius", a.place().radius());
                t.putLong("to", new BlockPos(b.place().x(), b.place().y(), b.place().z()).asLong());
                t.putInt("toRadius", b.place().radius());
            }
        } else {
            if (m.c1 != null) {
                t.putLong("c1", m.c1.asLong());
            }
            if (m.c2 != null) {
                t.putLong("c2", m.c2.asLong());
            }
        }
        return t;
    }

    private static void send(ServerPlayer admin, @Nullable Mode m) {
        sender.accept(admin, new TutorialModePacket(state(admin, m)));
    }

    public static void onLogout(UUID admin) {
        MODES.remove(admin);
    }

    // ------------------------------------------------------------------ tests

    public static void useSenderForTests(@Nullable BiConsumer<ServerPlayer, Object> s) {
        sender = s != null ? s : ServerUtilsNetwork::sendTo;
        MODES.clear();
    }

    /** Tests: the points recorded so far, or none. */
    public static List<BlockPos> samplesForTests(ServerPlayer admin) {
        Mode m = MODES.get(admin.getUUID());
        return m == null ? List.of() : List.copyOf(m.samples);
    }

    /** Tests: whether the admin is in a mode, and whether they are being asked to finish. */
    public static boolean inModeForTests(ServerPlayer admin) {
        return MODES.containsKey(admin.getUUID());
    }

    public static boolean askedForTests(ServerPlayer admin) {
        Mode m = MODES.get(admin.getUUID());
        return m != null && m.asked;
    }

    public static void sampleForTests(ServerPlayer admin) {
        Mode m = MODES.get(admin.getUUID());
        if (m != null) {
            sample(admin, m);
        }
    }
}
