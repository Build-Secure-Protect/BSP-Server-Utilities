package com.mrgregles.bsp_server_utilities.tutorial;

import com.mrgregles.bsp_server_utilities.support.SupportLinks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A focus point's popup (owner decisions D723 to D728, D739 to D741): full screen on a 12 x 8 grid or small on a 6 x 4
 * one placed anywhere; freezing the player (with up to four buttons) or keeping them walking (no buttons, on screen for
 * a few seconds, then a fade); a background; boxes of text or a picture covering rectangles of cells.
 */
public record TutorialPopup(Size size, boolean freezes, int seconds, int background, Placement place, List<Box> boxes, List<Button> buttons) {
    public static final int MAX_BOXES = 12, MAX_BUTTONS = 4, MAX_LABEL = 24, MAX_URL = 512;
    public static final int MIN_SECONDS = 2, MAX_SECONDS = 60, DEFAULT_SECONDS = 8;
    public static final int DEFAULT_BACKGROUND = 0xFF10151C;
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}");
    private static final Pattern WEB = Pattern.compile("https?://[^\\s§<>\"]+", Pattern.CASE_INSENSITIVE);

    /** The grid each size lays its boxes on. */
    public enum Size {
        FULL(12, 8), SMALL(6, 4);

        public final int cols, rows;

        Size(int cols, int rows) {
            this.cols = cols;
            this.rows = rows;
        }
    }

    public enum Kind { TEXT, IMAGE }

    public enum Source { FILE, WEB }

    public enum Fit { INSIDE, FILL, STRETCH }

    /** What a button does (D728): GO TO BRANCH names a child point, OPEN LINK a web address. */
    public enum Job { NEXT, PREVIOUS, END, CLOSE, BRANCH, LINK }

    /**
     * Where a small popup sits and how big it is, in thousandths of the screen's width and height, so it lands in the
     * same place at every window size (D741). At least 80 x 30 GUI pixels of a 480 x 270 screen, and kept on screen.
     */
    public record Placement(int x, int y, int w, int h) {
        public static final int MIN_W = 167, MIN_H = 112;
        public static final Placement DEFAULT = new Placement(30, 60, 333, 193);

        public Placement sanitised() {
            int nw = Math.max(MIN_W, Math.min(1000, w)), nh = Math.max(MIN_H, Math.min(1000, h));
            return new Placement(Math.max(0, Math.min(1000 - nw, x)), Math.max(0, Math.min(1000 - nh, y)), nw, nh);
        }
    }

    /** A picture: a file from {@code config/bsp_server_utilities/tutorial_images/} or a web address (D726). */
    public record Image(Source source, String file, String url, Fit fit) {
        public static final Image NONE = new Image(Source.FILE, "", "", Fit.INSIDE);

        public Image sanitised() {
            String f = file == null ? "" : file.strip();
            if (!FILE_NAME.matcher(f).matches() || f.contains("..")) {
                f = "";
            }
            String u = url == null ? "" : url.strip();
            if (u.length() > MAX_URL || !WEB.matcher(u).matches()) {
                u = "";
            }
            return new Image(source == null ? Source.FILE : source, f, u, fit == null ? Fit.INSIDE : fit);
        }

        public boolean isSet() {
            return source == Source.FILE ? !file.isEmpty() : !url.isEmpty();
        }
    }

    /** A box on the grid: its top-left cell, its size in cells, text or a picture, and a fill colour (0 = none). */
    public record Box(int col, int row, int w, int h, Kind kind, TutorialText text, Image image, int fill) {
        public boolean overlaps(Box o) {
            return col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h;
        }
    }

    /** A button: its label, its colour (0xRRGGBB), its job, the branch's point id for BRANCH, the address for LINK. */
    public record Button(String label, int colour, Job job, int target, String url) {
        public static Button of(String label, Job job) {
            return new Button(label, 0x19D3B0, job, -1, "");
        }
    }

    public TutorialPopup {
        boxes = List.copyOf(boxes);
        buttons = List.copyOf(buttons);
    }

    /** A new popup: full screen, freezing, with a NEXT button. */
    public static TutorialPopup defaults() {
        return new TutorialPopup(Size.FULL, true, DEFAULT_SECONDS, DEFAULT_BACKGROUND, Placement.DEFAULT, List.of(), List.of(Button.of("Next", Job.NEXT)));
    }

    /**
     * Within the limits, for a point whose children (the points its links lead to) are given: boxes moved inside the
     * grid and cut to it, a box overlapping an earlier one dropped, at most {@value #MAX_BOXES}; a popup that keeps the
     * player walking has no buttons (D739); a GO TO BRANCH that names no child, or an OPEN LINK whose address is not an
     * https address the support link check passes, is dropped; at most {@value #MAX_BUTTONS} buttons; the time on screen
     * kept to {@value #MIN_SECONDS} to {@value #MAX_SECONDS} seconds; the placement kept on screen.
     */
    public TutorialPopup sanitised(Set<Integer> children) {
        Size s = size == null ? Size.FULL : size;
        List<Box> outBoxes = new ArrayList<>();
        for (Box b : boxes) {
            if (outBoxes.size() >= MAX_BOXES || b == null) {
                break;
            }
            int col = clamp(b.col(), 0, s.cols - 1), row = clamp(b.row(), 0, s.rows - 1);
            int w = clamp(b.w(), 1, s.cols - col), h = clamp(b.h(), 1, s.rows - row);
            Kind kind = b.kind() == null ? Kind.TEXT : b.kind();
            Box next = new Box(col, row, w, h, kind,
                    kind == Kind.TEXT && b.text() != null ? b.text().sanitised() : TutorialText.EMPTY,
                    kind == Kind.IMAGE && b.image() != null ? b.image().sanitised() : Image.NONE, b.fill());
            if (outBoxes.stream().noneMatch(next::overlaps)) {
                outBoxes.add(next);
            }
        }
        List<Button> outButtons = new ArrayList<>();
        if (freezes) {
            for (Button b : buttons) {
                if (outButtons.size() >= MAX_BUTTONS || b == null || b.job() == null) {
                    continue;
                }
                String label = Tutorial.clean(b.label(), MAX_LABEL);
                if (label.isEmpty()) {
                    label = defaultLabel(b.job());
                }
                if (b.job() == Job.BRANCH && !children.contains(b.target())) {
                    continue;
                }
                String url = "";
                if (b.job() == Job.LINK) {
                    url = b.url() == null ? "" : b.url().strip();
                    if (url.isEmpty() || SupportLinks.check(url) != SupportLinks.Problem.OK) {
                        continue;
                    }
                }
                outButtons.add(new Button(label, b.colour() & 0xFFFFFF, b.job(), b.job() == Job.BRANCH ? b.target() : -1, url));
            }
        }
        return new TutorialPopup(s, freezes, clamp(seconds, MIN_SECONDS, MAX_SECONDS), background,
                place == null ? Placement.DEFAULT : place.sanitised(), outBoxes, outButtons);
    }

    public static String defaultLabel(Job job) {
        return switch (job) {
            case NEXT -> "Next";
            case PREVIOUS -> "Previous";
            case END -> "End tutorial";
            case CLOSE -> "Close";
            case BRANCH -> "Go";
            case LINK -> "Open link";
        };
    }

    /** Whether a button moves the player on, or out, so a freezing popup never leaves them stuck. */
    public boolean hasWayOn() {
        return buttons.stream().anyMatch(b -> b.job() == Job.NEXT || b.job() == Job.BRANCH || b.job() == Job.END || b.job() == Job.CLOSE);
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("size", size.name());
        t.putBoolean("freezes", freezes);
        t.putInt("seconds", seconds);
        t.putInt("background", background);
        t.putIntArray("place", new int[]{place.x(), place.y(), place.w(), place.h()});
        ListTag bl = new ListTag();
        for (Box b : boxes) {
            CompoundTag bt = new CompoundTag();
            bt.putIntArray("cells", new int[]{b.col(), b.row(), b.w(), b.h()});
            bt.putString("kind", b.kind().name());
            bt.putInt("fill", b.fill());
            if (b.kind() == Kind.TEXT) {
                bt.put("text", b.text().save());
            } else {
                bt.putString("source", b.image().source().name());
                bt.putString("file", b.image().file());
                bt.putString("url", b.image().url());
                bt.putString("fit", b.image().fit().name());
            }
            bl.add(bt);
        }
        t.put("boxes", bl);
        ListTag kl = new ListTag();
        for (Button b : buttons) {
            CompoundTag kt = new CompoundTag();
            kt.putString("label", b.label());
            kt.putInt("colour", b.colour());
            kt.putString("job", b.job().name());
            kt.putInt("target", b.target());
            kt.putString("url", b.url());
            kl.add(kt);
        }
        t.put("buttons", kl);
        return t;
    }

    /** Read back as saved; the caller sanitises with the point's children. */
    public static TutorialPopup load(CompoundTag t) {
        int[] p = t.getIntArray("place");
        Placement place = p.length == 4 ? new Placement(p[0], p[1], p[2], p[3]) : Placement.DEFAULT;
        List<Box> boxes = new ArrayList<>();
        for (Tag x : t.getList("boxes", Tag.TAG_COMPOUND)) {
            CompoundTag bt = (CompoundTag) x;
            int[] c = bt.getIntArray("cells");
            if (c.length != 4) {
                continue;
            }
            Kind kind = Tutorial.named(Kind.class, bt.getString("kind"), Kind.TEXT);
            Image image = new Image(Tutorial.named(Source.class, bt.getString("source"), Source.FILE), bt.getString("file"), bt.getString("url"),
                    Tutorial.named(Fit.class, bt.getString("fit"), Fit.INSIDE));
            boxes.add(new Box(c[0], c[1], c[2], c[3], kind, TutorialText.load(bt.getList("text", Tag.TAG_COMPOUND)), image, bt.getInt("fill")));
        }
        List<Button> buttons = new ArrayList<>();
        for (Tag x : t.getList("buttons", Tag.TAG_COMPOUND)) {
            CompoundTag kt = (CompoundTag) x;
            Job job = Tutorial.named(Job.class, kt.getString("job"), null);
            if (job != null) {
                buttons.add(new Button(kt.getString("label"), kt.getInt("colour"), job, kt.getInt("target"), kt.getString("url")));
            }
        }
        return new TutorialPopup(Tutorial.named(Size.class, t.getString("size"), Size.FULL), t.getBoolean("freezes"), t.getInt("seconds"),
                t.getInt("background"), place, boxes, buttons);
    }
}
