package com.mrgregles.bsp_server_utilities.panel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a Display Panel shows (owner decisions D14, D17, D41 to D51): the mode, the background (0 = clear, D47), the text
 * lines for TEXT (D44 to D46), the image settings for IMAGE (D49), who placed it, and a revision that marks every save.
 * Every block of a joined panel keeps a copy; the newest revision wins when blocks join or the bottom-left block goes.
 * {@code loaded} is what the server last made of the picture (SU-7): set by the server, never by a client.
 */
public record PanelSettings(Mode mode, int background, List<Line> lines, Image image, String placedBy, long revision, Loaded loaded, StaffView staff, LogView log) {
    public static final int MAX_LINES = 16, MAX_LINE = 80, MAX_URL = 512;
    public static final int DEFAULT_BACKGROUND = 0xFF0C0E12, CLEAR = 0;
    /** The background swatches the settings screen offers (D47); CLEAR is the last. */
    public static final int[] BACKGROUNDS = {0xFF0C0E12, 0xFF10151C, 0xFF1D2B44, 0xFF2B1D2F, 0xFF1F2B1F, CLEAR};
    /** Refresh choices for an image from the web, in minutes (0 = off). */
    public static final int[] REFRESH_MINUTES = {0, 5, 15, 60};
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}");

    public enum Mode { TEXT, IMAGE, STAFF, LOG }

    public enum Size { S, M, L, XL }

    public enum Align { LEFT, CENTRE, RIGHT }

    public enum Source { WEB, FOLDER }

    public enum Fit { INSIDE, FILL, STRETCH }

    /** One line of text: up to 80 characters (& codes allowed), a size, a colour (0xRRGGBB), the alignment and bold. */
    public record Line(String text, Size size, int colour, Align align, boolean bold) {
    }

    /** The image settings: a web address or a file in the server folder, the fit, and the refresh in minutes. */
    public record Image(Source source, String url, String file, Fit fit, int refreshMinutes) {
        public static final Image NONE = new Image(Source.WEB, "", "", Fit.INSIDE, 0);
    }

    /**
     * The picture as the server last loaded it: the hash players ask for its bytes by, the source's format, size and file
     * size, the size kept, when it was loaded, and an error key and detail when it failed ("" when it worked).
     */
    public record Loaded(String hash, String format, int sourceWidth, int sourceHeight, int sourceBytes, int width, int height, long atMs, String error, String detail) {
        public static final Loaded NONE = new Loaded("", "", 0, 0, 0, 0, 0, 0, "", "");

        public boolean ok() {
            return !hash.isEmpty() && error.isEmpty();
        }

        public static Loaded failed(String error, String detail) {
            return new Loaded("", "", 0, 0, 0, 0, 0, System.currentTimeMillis(), error, detail);
        }
    }

    /** What a Staff online face shows (D59): offline staff too, and which roles (empty for every role). */
    public record StaffView(boolean offline, List<String> roles) {
        public static final StaffView ALL = new StaffView(true, List.of());

        public StaffView {
            roles = List.copyOf(roles);
        }
    }

    /** What an Action log face shows (D59): every server or this one, which kinds (empty for every kind), how many lines. */
    public record LogView(boolean allServers, List<String> kinds, int lines) {
        public static final int[] LINE_CHOICES = {6, 8, 12};
        public static final LogView ALL = new LogView(true, List.of(), 8);

        public LogView {
            kinds = List.copyOf(kinds);
        }
    }

    public PanelSettings {
        lines = List.copyOf(lines);
        loaded = loaded == null ? Loaded.NONE : loaded;
        staff = staff == null ? StaffView.ALL : staff;
        log = log == null ? LogView.ALL : log;
    }

    public PanelSettings(Mode mode, int background, List<Line> lines, Image image, String placedBy, long revision) {
        this(mode, background, lines, image, placedBy, revision, Loaded.NONE, StaffView.ALL, LogView.ALL);
    }

    public PanelSettings(Mode mode, int background, List<Line> lines, Image image, String placedBy, long revision, Loaded loaded) {
        this(mode, background, lines, image, placedBy, revision, loaded, StaffView.ALL, LogView.ALL);
    }

    public PanelSettings withLoaded(Loaded l) {
        return new PanelSettings(mode, background, lines, image, placedBy, revision, l, staff, log);
    }

    public PanelSettings withImage(Image i) {
        return new PanelSettings(mode, background, lines, i, placedBy, revision, loaded, staff, log);
    }

    public PanelSettings withViews(StaffView s, LogView l) {
        return new PanelSettings(mode, background, lines, image, placedBy, revision, loaded, s, l);
    }

    public static PanelSettings defaults() {
        return new PanelSettings(Mode.TEXT, DEFAULT_BACKGROUND, List.of(), Image.NONE, "", 0);
    }

    public PanelSettings withRevision(long r) {
        return new PanelSettings(mode, background, lines, image, placedBy, r, loaded, staff, log);
    }

    public PanelSettings withPlacedBy(String name) {
        return new PanelSettings(mode, background, lines, image, name, revision, loaded, staff, log);
    }

    /**
     * The same settings within the limits: at most 16 lines of 80 characters on one line each (section signs out, &
     * codes kept), colours as 0xRRGGBB, a background from the swatches, a web address only when it is http or https, a
     * file name without any path, a refresh from the choices.
     */
    public PanelSettings sanitised() {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            if (out.size() >= MAX_LINES) {
                break;
            }
            out.add(new Line(clean(l.text(), MAX_LINE), l.size() == null ? Size.M : l.size(), l.colour() & 0xFFFFFF,
                    l.align() == null ? Align.CENTRE : l.align(), l.bold()));
        }
        int bg = DEFAULT_BACKGROUND;
        for (int b : BACKGROUNDS) {
            if (b == background) {
                bg = b;
            }
        }
        Image img = image == null ? Image.NONE : image;
        String url = clean(img.url(), MAX_URL).trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            url = "";
        }
        String file = img.file() == null ? "" : img.file().trim();
        if (!FILE_NAME.matcher(file).matches() || file.contains("..")) {
            file = "";
        }
        int refresh = 0;
        for (int m : REFRESH_MINUTES) {
            if (m == img.refreshMinutes()) {
                refresh = m;
            }
        }
        Image cleanImage = new Image(img.source() == null ? Source.WEB : img.source(), url, file, img.fit() == null ? Fit.INSIDE : img.fit(), refresh);
        List<String> roles = staff.roles().stream().map(r -> clean(r, 64).toLowerCase(Locale.ROOT)).filter(r -> !r.isBlank()).distinct().limit(12).toList();
        List<String> kinds = log.kinds().stream().map(k -> clean(k, 16).toLowerCase(Locale.ROOT)).filter(com.mrgregles.bsp_server_utilities.log.LogEntry.KINDS::contains).distinct().toList();
        int logLines = LogView.ALL.lines();
        for (int n : LogView.LINE_CHOICES) {
            if (n == log.lines()) {
                logLines = n;
            }
        }
        return new PanelSettings(mode == null ? Mode.TEXT : mode, bg, out, cleanImage, clean(placedBy, 32), revision, loaded,
                new StaffView(staff.offline(), roles), new LogView(log.allServers(), kinds, logLines));
    }

    static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                continue;
            }
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        String t = b.toString();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", mode.name());
        tag.putInt("background", background);
        ListTag list = new ListTag();
        for (Line l : lines) {
            CompoundTag lt = new CompoundTag();
            lt.putString("text", l.text());
            lt.putString("size", l.size().name());
            lt.putInt("colour", l.colour());
            lt.putString("align", l.align().name());
            lt.putBoolean("bold", l.bold());
            list.add(lt);
        }
        tag.put("lines", list);
        CompoundTag it = new CompoundTag();
        it.putString("source", image.source().name());
        it.putString("url", image.url());
        it.putString("file", image.file());
        it.putString("fit", image.fit().name());
        it.putInt("refresh", image.refreshMinutes());
        tag.put("image", it);
        tag.putString("placedBy", placedBy);
        tag.putLong("revision", revision);
        CompoundTag lt = new CompoundTag();
        lt.putString("hash", loaded.hash());
        lt.putString("format", loaded.format());
        lt.putInt("sw", loaded.sourceWidth());
        lt.putInt("sh", loaded.sourceHeight());
        lt.putInt("sb", loaded.sourceBytes());
        lt.putInt("w", loaded.width());
        lt.putInt("h", loaded.height());
        lt.putLong("at", loaded.atMs());
        lt.putString("error", loaded.error());
        lt.putString("detail", loaded.detail());
        tag.put("loaded", lt);
        CompoundTag st = new CompoundTag();
        st.putBoolean("offline", staff.offline());
        st.putString("roles", String.join(",", staff.roles()));
        tag.put("staff", st);
        CompoundTag lg = new CompoundTag();
        lg.putBoolean("all", log.allServers());
        lg.putString("kinds", String.join(",", log.kinds()));
        lg.putInt("lines", log.lines());
        tag.put("log", lg);
        return tag;
    }

    public static PanelSettings load(CompoundTag tag) {
        if (tag.isEmpty()) {
            return defaults();
        }
        List<Line> lines = new ArrayList<>();
        for (Tag t : tag.getList("lines", Tag.TAG_COMPOUND)) {
            CompoundTag lt = (CompoundTag) t;
            lines.add(new Line(lt.getString("text"), parse(Size.class, lt.getString("size"), Size.M), lt.getInt("colour"),
                    parse(Align.class, lt.getString("align"), Align.CENTRE), lt.getBoolean("bold")));
        }
        CompoundTag it = tag.getCompound("image");
        Image img = new Image(parse(Source.class, it.getString("source"), Source.WEB), it.getString("url"), it.getString("file"),
                parse(Fit.class, it.getString("fit"), Fit.INSIDE), it.getInt("refresh"));
        int bg = tag.contains("background") ? tag.getInt("background") : DEFAULT_BACKGROUND;
        CompoundTag lt = tag.getCompound("loaded");
        Loaded loaded = lt.isEmpty() ? Loaded.NONE : new Loaded(lt.getString("hash"), lt.getString("format"), lt.getInt("sw"), lt.getInt("sh"), lt.getInt("sb"),
                lt.getInt("w"), lt.getInt("h"), lt.getLong("at"), lt.getString("error"), lt.getString("detail"));
        CompoundTag st = tag.getCompound("staff"), lg = tag.getCompound("log");
        StaffView sv = st.isEmpty() ? StaffView.ALL : new StaffView(st.getBoolean("offline"), split(st.getString("roles")));
        LogView lv = lg.isEmpty() ? LogView.ALL : new LogView(lg.getBoolean("all"), split(lg.getString("kinds")), lg.getInt("lines"));
        return new PanelSettings(parse(Mode.class, tag.getString("mode"), Mode.TEXT), bg, lines, img, tag.getString("placedBy"), tag.getLong("revision"), loaded, sv, lv).sanitised();
    }

    private static List<String> split(String csv) {
        return csv.isBlank() ? List.of() : List.of(csv.split(","));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
