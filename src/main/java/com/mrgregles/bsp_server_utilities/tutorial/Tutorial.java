package com.mrgregles.bsp_server_utilities.tutorial;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One tutorial (owner decisions D719 to D744): its name, how it starts (on first join, by walking into an area, or only
 * with {@code /tutorial}; D720, D737), who the first-join one is offered to (D721, D743), the offer's text, its focus
 * points and the links between them, each link with the path an admin walked. {@code revision} marks every save, so the
 * newest copy wins across the servers; a deleted tutorial is kept as an empty copy with {@code deleted} set, so the
 * deletion wins the same way. Each tutorial has a draft and a published copy (D734); players only see the published.
 * {@code since} is when the published copy became the first-join tutorial (0 otherwise): "new players" (D721) are those
 * whose first join on the network is from then on.
 */
public record Tutorial(String id, String name, Start start, Audience audience, Area area, String offer, List<Point> points, List<Link> links,
                       String editedBy, long revision, boolean deleted, long since) {
    public static final int MAX_NAME = 32, MAX_TITLE = 32, MAX_OFFER = 300, MAX_POINTS = 64, MAX_LINKS = 128, MAX_PATH = 500;
    public static final int MAX_SERVER = 64, MAX_DIMENSION = 128, MAX_AREA_SIDE = 256, CANVAS = 10_000;
    public static final int MIN_RADIUS = 1, MAX_RADIUS = 8, DEFAULT_RADIUS = 2;
    public static final String DEFAULT_ICON = "minecraft:compass";

    /** How a tutorial starts. */
    public enum Start { FIRST_JOIN, AREA, COMMAND }

    /** Who the first-join tutorial is offered to. */
    public enum Audience { NEW_PLAYERS, NOT_FINISHED }

    /** Where a point is: a server, a world, a block, and the radius in blocks that triggers its popup (D729). */
    public record Place(String server, String dimension, int x, int y, int z, int radius) {
        public static final Place NONE = new Place("", "", 0, 0, 0, DEFAULT_RADIUS);

        public boolean isSet() {
            return !server.isEmpty() && !dimension.isEmpty();
        }

        public boolean sameWorld(Place o) {
            return isSet() && o.isSet() && server.equalsIgnoreCase(o.server) && dimension.equals(o.dimension);
        }

        Place sanitised() {
            String s = clean(server, MAX_SERVER), d = Tutorial.cleanDimension(dimension);
            if (s.isEmpty() || d.isEmpty()) {
                return new Place("", "", 0, 0, 0, TutorialPopup.clamp(radius, MIN_RADIUS, MAX_RADIUS));
            }
            return new Place(s, d, x, y, z, TutorialPopup.clamp(radius, MIN_RADIUS, MAX_RADIUS));
        }
    }

    /** A start area (D737): a box between two corners on one server and world, at most {@value #MAX_AREA_SIDE} blocks a side. */
    public record Area(String server, String dimension, int x0, int y0, int z0, int x1, int y1, int z1) {
        public static final Area NONE = new Area("", "", 0, 0, 0, 0, 0, 0);

        public boolean isSet() {
            return !server.isEmpty() && !dimension.isEmpty();
        }

        /** Whether a block position is inside, corners included. */
        public boolean contains(String onServer, String inDimension, double x, double y, double z) {
            return isSet() && server.equalsIgnoreCase(onServer) && dimension.equals(inDimension)
                    && x >= x0 && x < x1 + 1 && y >= y0 && y < y1 + 1 && z >= z0 && z < z1 + 1;
        }

        Area sanitised() {
            String s = clean(server, MAX_SERVER), d = Tutorial.cleanDimension(dimension);
            if (s.isEmpty() || d.isEmpty()) {
                return NONE;
            }
            int ax = Math.min(x0, x1), ay = Math.min(y0, y1), az = Math.min(z0, z1);
            int bx = Math.max(x0, x1), by = Math.max(y0, y1), bz = Math.max(z0, z1);
            return new Area(s, d, ax, ay, az, Math.min(bx, ax + MAX_AREA_SIDE - 1), Math.min(by, ay + MAX_AREA_SIDE - 1), Math.min(bz, az + MAX_AREA_SIDE - 1));
        }
    }

    /** A focus point: an id unique in its tutorial, a title, an item for its node, its place on the canvas, its place in the world, and its popup. */
    public record Point(int id, String title, String icon, int cx, int cy, Place place, TutorialPopup popup) {
        public Point withPopup(TutorialPopup p) {
            return new Point(id, title, icon, cx, cy, place, p);
        }

        public Point withPlace(Place p) {
            return new Point(id, title, icon, cx, cy, p, popup);
        }
    }

    /** A link from one point to the next, with the path an admin walked between them (block positions, D731), or none. */
    public record Link(int from, int to, List<BlockPos> path) {
        public Link {
            path = List.copyOf(path);
        }

        public Link withPath(List<BlockPos> p) {
            return new Link(from, to, p);
        }
    }

    /** Something the builder marks in red (D734): what, and the point or link it is about (-1 when none). */
    public record Issue(Problem problem, int point, int from, int to) {
    }

    public enum Problem {
        /** No points at all. */
        NO_POINTS,
        /** Every point has a link into it: nowhere to begin. */
        NO_START,
        /** More than one point has no link into it. */
        MANY_STARTS,
        /** A point that forks lacks a GO TO BRANCH button for one of its branches. */
        FORK_NEEDS_BRANCH,
        /** A point that forks keeps the player walking, so it cannot ask which way (D739). */
        WALKING_AT_FORK,
        /** A freezing popup with no button that moves the player on or ends the tutorial. */
        STUCK,
        /** A link between two points in the same world with no path drawn. */
        NO_PATH,
        /** A tutorial that starts by an area has none. */
        NO_AREA
    }

    public Tutorial {
        points = List.copyOf(points);
        links = List.copyOf(links);
    }

    /** A new, empty tutorial with a fresh id, started only with {@code /tutorial} until the admin chooses. */
    public static Tutorial create(String name) {
        return new Tutorial(UUID.randomUUID().toString(), name, Start.COMMAND, Audience.NEW_PLAYERS, Area.NONE, "", List.of(), List.of(), "", 0, false, 0).sanitised();
    }

    /** The copy that marks a deletion: same id, nothing in it. */
    public Tutorial asDeleted(String by, long newRevision) {
        return new Tutorial(id, name, Start.COMMAND, audience, Area.NONE, "", List.of(), List.of(), by, newRevision, true, 0);
    }

    public Tutorial withRevision(long r, String by) {
        return new Tutorial(id, name, start, audience, area, offer, points, links, by, r, deleted, since);
    }

    public Tutorial withSince(long ms) {
        return new Tutorial(id, name, start, audience, area, offer, points, links, editedBy, revision, deleted, ms);
    }

    public Tutorial withStart(Start s) {
        return new Tutorial(id, name, s, audience, area, offer, points, links, editedBy, revision, deleted, since);
    }

    public Tutorial withName(String n) {
        return new Tutorial(id, n, start, audience, area, offer, points, links, editedBy, revision, deleted, since);
    }

    public Tutorial withAudience(Audience a) {
        return new Tutorial(id, name, start, a, area, offer, points, links, editedBy, revision, deleted, since);
    }

    public Tutorial withArea(Area a) {
        return new Tutorial(id, name, start, audience, a, offer, points, links, editedBy, revision, deleted, since);
    }

    public Tutorial withOffer(String o) {
        return new Tutorial(id, name, start, audience, area, o, points, links, editedBy, revision, deleted, since);
    }

    public Tutorial withPoints(List<Point> p) {
        return new Tutorial(id, name, start, audience, area, offer, p, links, editedBy, revision, deleted, since);
    }

    public Tutorial withLinks(List<Link> l) {
        return new Tutorial(id, name, start, audience, area, offer, points, l, editedBy, revision, deleted, since);
    }

    @Nullable
    public Point point(int pointId) {
        for (Point p : points) {
            if (p.id() == pointId) {
                return p;
            }
        }
        return null;
    }

    /** The points the given point's links lead to, in link order. */
    public List<Integer> children(int pointId) {
        List<Integer> out = new ArrayList<>();
        for (Link l : links) {
            if (l.from() == pointId) {
                out.add(l.to());
            }
        }
        return out;
    }

    /** The points with no link into them: one for a tutorial that can begin. */
    public List<Integer> starts() {
        Set<Integer> into = new HashSet<>();
        links.forEach(l -> into.add(l.to()));
        List<Integer> out = new ArrayList<>();
        points.forEach(p -> {
            if (!into.contains(p.id())) {
                out.add(p.id());
            }
        });
        return out;
    }

    /** A point id not used yet. */
    public int nextPointId() {
        int max = -1;
        for (Point p : points) {
            max = Math.max(max, p.id());
        }
        return max + 1;
    }

    /**
     * Within the limits: texts cleaned and cut; points with a duplicate or negative id dropped, at most
     * {@value #MAX_POINTS}, kept on the canvas; links to a missing point, to itself, repeated, or closing a loop dropped,
     * at most {@value #MAX_LINKS}; a path kept only when both points are placed in the same world, repeats in a row
     * dropped, at most {@value #MAX_PATH} positions; each popup sanitised for its point's children. A deleted copy keeps
     * only its id, name and revision.
     */
    public Tutorial sanitised() {
        String n = clean(name, MAX_NAME);
        if (n.isEmpty()) {
            n = "Tutorial";
        }
        String sid = uuid(id);
        if (deleted) {
            return new Tutorial(sid, n, Start.COMMAND, audience == null ? Audience.NEW_PLAYERS : audience, Area.NONE, "", List.of(), List.of(), clean(editedBy, 32), revision, true, 0);
        }
        List<Point> ps = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (Point p : points) {
            if (p == null || p.id() < 0 || ps.size() >= MAX_POINTS || !ids.add(p.id())) {
                continue;
            }
            String title = clean(p.title(), MAX_TITLE);
            ps.add(new Point(p.id(), title.isEmpty() ? "Point " + (p.id() + 1) : title, icon(p.icon()),
                    TutorialPopup.clamp(p.cx(), -CANVAS, CANVAS), TutorialPopup.clamp(p.cy(), -CANVAS, CANVAS),
                    p.place() == null ? Place.NONE : p.place().sanitised(), p.popup() == null ? TutorialPopup.defaults() : p.popup()));
        }
        Map<Integer, Point> byId = new HashMap<>();
        ps.forEach(p -> byId.put(p.id(), p));
        List<Link> ls = new ArrayList<>();
        Map<Integer, List<Integer>> out = new HashMap<>();
        for (Link l : links) {
            if (l == null || ls.size() >= MAX_LINKS || l.from() == l.to() || !byId.containsKey(l.from()) || !byId.containsKey(l.to())) {
                continue;
            }
            if (out.getOrDefault(l.from(), List.of()).contains(l.to()) || reaches(out, l.to(), l.from())) {
                continue; // repeated, or a loop back to where it came from
            }
            out.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
            List<BlockPos> path = new ArrayList<>();
            if (byId.get(l.from()).place().sameWorld(byId.get(l.to()).place())) {
                for (BlockPos b : l.path()) {
                    if (path.size() >= MAX_PATH) {
                        break;
                    }
                    if (b != null && (path.isEmpty() || !path.get(path.size() - 1).equals(b))) {
                        path.add(b.immutable());
                    }
                }
            }
            ls.add(new Link(l.from(), l.to(), path));
        }
        List<Point> withPopups = new ArrayList<>();
        for (Point p : ps) {
            withPopups.add(p.withPopup(p.popup().sanitised(new HashSet<>(out.getOrDefault(p.id(), List.of())))));
        }
        return new Tutorial(sid, n, start == null ? Start.COMMAND : start, audience == null ? Audience.NEW_PLAYERS : audience,
                area == null ? Area.NONE : area.sanitised(), cleanLines(offer, MAX_OFFER), withPopups, ls, clean(editedBy, 32), revision, false, start == Start.FIRST_JOIN ? Math.max(0, since) : 0);
    }

    private static boolean reaches(Map<Integer, List<Integer>> out, int from, int target) {
        Deque<Integer> todo = new ArrayDeque<>(List.of(from));
        Set<Integer> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            int at = todo.pop();
            if (at == target) {
                return true;
            }
            if (seen.add(at)) {
                todo.addAll(out.getOrDefault(at, List.of()));
            }
        }
        return false;
    }

    /** What the builder marks in red; PUBLISH asks again while any are left (D734). */
    public List<Issue> issues() {
        List<Issue> out = new ArrayList<>();
        if (deleted) {
            return out;
        }
        if (points.isEmpty()) {
            out.add(new Issue(Problem.NO_POINTS, -1, -1, -1));
        } else {
            List<Integer> s = starts();
            if (s.isEmpty()) {
                out.add(new Issue(Problem.NO_START, -1, -1, -1));
            } else if (s.size() > 1) {
                s.forEach(id -> out.add(new Issue(Problem.MANY_STARTS, id, -1, -1)));
            }
        }
        if (start == Start.AREA && !area.isSet()) {
            out.add(new Issue(Problem.NO_AREA, -1, -1, -1));
        }
        for (Point p : points) {
            List<Integer> kids = children(p.id());
            TutorialPopup pop = p.popup();
            if (kids.size() > 1) {
                if (!pop.freezes()) {
                    out.add(new Issue(Problem.WALKING_AT_FORK, p.id(), -1, -1));
                } else {
                    Set<Integer> named = new HashSet<>();
                    pop.buttons().forEach(b -> {
                        if (b.job() == TutorialPopup.Job.BRANCH) {
                            named.add(b.target());
                        }
                    });
                    if (!named.containsAll(kids)) {
                        out.add(new Issue(Problem.FORK_NEEDS_BRANCH, p.id(), -1, -1));
                    }
                }
            }
            if (pop.freezes() && !pop.hasWayOn()) {
                out.add(new Issue(Problem.STUCK, p.id(), -1, -1));
            }
        }
        for (Link l : links) {
            Point a = point(l.from()), b = point(l.to());
            if (a != null && b != null && a.place().sameWorld(b.place()) && l.path().isEmpty()) {
                out.add(new Issue(Problem.NO_PATH, -1, l.from(), l.to()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ text helpers

    /** One line within a limit: control characters and section signs removed, trimmed, cut. */
    public static String clean(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') {
                b.append(' ');
            } else if (!Character.isISOControl(c) && c != '§') {
                b.append(c);
            }
        }
        String t = b.toString().strip();
        return t.length() > max ? t.substring(0, max).strip() : t;
    }

    /** As {@link #clean} but keeping line breaks. */
    static String cleanLines(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(clean(line, max));
        }
        String t = b.toString().strip();
        return t.length() > max ? t.substring(0, max).strip() : t;
    }

    /** A tutorial id: a UUID in its usual lower-case form, or "" when it is not one. */
    static String uuid(@Nullable String s) {
        try {
            return s == null ? "" : UUID.fromString(s.strip()).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    static String cleanDimension(@Nullable String s) {
        String d = clean(s, MAX_DIMENSION).toLowerCase(Locale.ROOT);
        return ResourceLocation.isValidResourceLocation(d) && d.contains(":") ? d : "";
    }

    private static String icon(@Nullable String s) {
        String d = clean(s, 128).toLowerCase(Locale.ROOT);
        return ResourceLocation.isValidResourceLocation(d) && !d.isEmpty() ? d : DEFAULT_ICON;
    }

    /** An enum constant by name, or the fallback. */
    static <E extends Enum<E>> E named(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ saving

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putString("name", name);
        t.putString("start", start.name());
        t.putString("audience", audience.name());
        t.putString("offer", offer);
        t.putString("by", editedBy);
        t.putLong("revision", revision);
        t.putBoolean("deleted", deleted);
        t.putLong("since", since);
        if (area.isSet()) {
            CompoundTag a = new CompoundTag();
            a.putString("server", area.server());
            a.putString("dim", area.dimension());
            a.putIntArray("box", new int[]{area.x0(), area.y0(), area.z0(), area.x1(), area.y1(), area.z1()});
            t.put("area", a);
        }
        ListTag pl = new ListTag();
        for (Point p : points) {
            CompoundTag pt = new CompoundTag();
            pt.putInt("id", p.id());
            pt.putString("title", p.title());
            pt.putString("icon", p.icon());
            pt.putIntArray("canvas", new int[]{p.cx(), p.cy()});
            if (p.place().isSet()) {
                CompoundTag at = new CompoundTag();
                at.putString("server", p.place().server());
                at.putString("dim", p.place().dimension());
                at.putIntArray("pos", new int[]{p.place().x(), p.place().y(), p.place().z()});
                pt.put("place", at);
            }
            pt.putInt("radius", p.place().radius());
            pt.put("popup", p.popup().save());
            pl.add(pt);
        }
        t.put("points", pl);
        ListTag ll = new ListTag();
        for (Link l : links) {
            CompoundTag lt = new CompoundTag();
            lt.putInt("from", l.from());
            lt.putInt("to", l.to());
            ListTag path = new ListTag();
            l.path().forEach(b -> path.add(LongTag.valueOf(b.asLong())));
            lt.put("path", path);
            ll.add(lt);
        }
        t.put("links", ll);
        return t;
    }

    public static Tutorial load(CompoundTag t) {
        Area area = Area.NONE;
        if (t.contains("area", Tag.TAG_COMPOUND)) {
            CompoundTag a = t.getCompound("area");
            int[] b = a.getIntArray("box");
            if (b.length == 6) {
                area = new Area(a.getString("server"), a.getString("dim"), b[0], b[1], b[2], b[3], b[4], b[5]);
            }
        }
        List<Point> points = new ArrayList<>();
        for (Tag x : t.getList("points", Tag.TAG_COMPOUND)) {
            CompoundTag pt = (CompoundTag) x;
            int[] c = pt.getIntArray("canvas");
            Place place = new Place("", "", 0, 0, 0, pt.getInt("radius"));
            if (pt.contains("place", Tag.TAG_COMPOUND)) {
                CompoundTag at = pt.getCompound("place");
                int[] pos = at.getIntArray("pos");
                if (pos.length == 3) {
                    place = new Place(at.getString("server"), at.getString("dim"), pos[0], pos[1], pos[2], pt.getInt("radius"));
                }
            }
            points.add(new Point(pt.getInt("id"), pt.getString("title"), pt.getString("icon"), c.length == 2 ? c[0] : 0, c.length == 2 ? c[1] : 0,
                    place, TutorialPopup.load(pt.getCompound("popup"))));
        }
        List<Link> links = new ArrayList<>();
        for (Tag x : t.getList("links", Tag.TAG_COMPOUND)) {
            CompoundTag lt = (CompoundTag) x;
            List<BlockPos> path = new ArrayList<>();
            for (Tag p : lt.getList("path", Tag.TAG_LONG)) {
                path.add(BlockPos.of(((LongTag) p).getAsLong()));
            }
            links.add(new Link(lt.getInt("from"), lt.getInt("to"), path));
        }
        return new Tutorial(t.getString("id"), t.getString("name"), named(Start.class, t.getString("start"), Start.COMMAND),
                named(Audience.class, t.getString("audience"), Audience.NEW_PLAYERS), area, t.getString("offer"), points, links,
                t.getString("by"), t.getLong("revision"), t.getBoolean("deleted"), t.getLong("since")).sanitised();
    }

    /** The text the shared table keeps: the saved tag as SNBT. */
    public String encode() {
        return save().toString();
    }

    /** A copy read back from the shared table, or null when it cannot be read. */
    @Nullable
    public static Tutorial decode(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            Tutorial t = load(TagParser.parseTag(s));
            return t.id().isEmpty() ? null : t;
        } catch (Exception e) {
            return null;
        }
    }
}
