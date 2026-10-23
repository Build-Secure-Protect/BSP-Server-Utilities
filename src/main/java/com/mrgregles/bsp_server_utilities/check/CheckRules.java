package com.mrgregles.bsp_server_utilities.check;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The client check's rules (owner decisions D122, D124, D128, D129): the block list (a word matching anywhere in a mod's
 * id or name, a pack's name or the shader's name; an exact mod id; a pack by its fingerprint), the names allowed anyway
 * (a word or mod entry never refuses them), the exempt players (by name, with a note) and the refusal message (up to
 * {@value #MAX_MESSAGE} characters; {@code <list>} marks where the list of things to remove goes). One set is the
 * network's; a server may keep its own (D134).
 */
public record CheckRules(List<Entry> block, List<String> allowed, List<Exempt> exempt, String message, long revision) {
    public static final int MAX_ENTRIES = 200, MAX_MESSAGE = 160, MAX_VALUE = 128;
    public static final String LIST = "<list>";
    public static final String DEFAULT_MESSAGE = "You can't join with: <list>. Remove them and join again; ask staff if you think this is wrong.";
    private static final String SEP = "\u001f", ITEM = "\u001e", FIELD = "\u001d";

    public enum Kind { WORD, MOD, PACK }

    /** A block-list entry: a word or mod id (lower case), or a pack's fingerprint with its name as the label. */
    public record Entry(Kind kind, String value, String label) {
    }

    /** A player never refused (their flags still show), with an admin's note. */
    public record Exempt(String name, String note) {
    }

    public static final CheckRules DEFAULTS = new CheckRules(List.of(word("xray"), word("x-ray"), word("freecam"), word("baritone"), word("wurst"), word("meteor")),
            List.of(), List.of(), DEFAULT_MESSAGE, 0);

    public static Entry word(String w) {
        return new Entry(Kind.WORD, w, "");
    }

    public CheckRules {
        block = List.copyOf(block);
        allowed = List.copyOf(allowed);
        exempt = List.copyOf(exempt);
    }

    private static String clean(@Nullable String s, int max) {
        String t = s == null ? "" : s.replaceAll("\\u00a7.?", "").replaceAll("[\\u0000-\\u001f]", "").strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    public CheckRules sanitised() {
        List<Entry> b = new ArrayList<>();
        for (Entry e : block) {
            if (e == null || e.kind() == null) {
                continue;
            }
            String v = clean(e.value(), MAX_VALUE).toLowerCase(Locale.ROOT);
            if (e.kind() == Kind.PACK && !v.matches("[0-9a-f]{40}")) {
                continue;
            }
            Entry c = new Entry(e.kind(), v, e.kind() == Kind.PACK ? clean(e.label(), MAX_VALUE) : "");
            if (!v.isEmpty() && b.size() < MAX_ENTRIES && b.stream().noneMatch(x -> x.kind() == c.kind() && x.value().equals(c.value()))) {
                b.add(c);
            }
        }
        List<String> a = new ArrayList<>();
        for (String s : allowed) {
            String v = clean(s, MAX_VALUE).toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && !a.contains(v) && a.size() < MAX_ENTRIES) {
                a.add(v);
            }
        }
        List<Exempt> x = new ArrayList<>();
        for (Exempt e : exempt) {
            String n = e == null ? "" : clean(e.name(), 16);
            if (n.matches("[A-Za-z0-9_]{1,16}") && x.stream().noneMatch(o -> o.name().equalsIgnoreCase(n)) && x.size() < MAX_ENTRIES) {
                x.add(new Exempt(n, clean(e.note(), 64)));
            }
        }
        String m = clean(message, MAX_MESSAGE);
        return new CheckRules(b, a, x, m.isEmpty() ? DEFAULT_MESSAGE : m, revision);
    }

    public CheckRules withRevision(long r) {
        return new CheckRules(block, allowed, exempt, message, r);
    }

    public CheckRules withBlock(List<Entry> b) {
        return new CheckRules(b, allowed, exempt, message, revision);
    }

    public boolean isExempt(String playerName) {
        return exempt.stream().anyMatch(e -> e.name().equalsIgnoreCase(playerName));
    }

    public boolean isAllowed(String name) {
        return allowed.contains(name.toLowerCase(Locale.ROOT));
    }

    public String encode() {
        List<String> b = new ArrayList<>();
        for (Entry e : block) {
            b.add(e.kind().name() + FIELD + e.value() + FIELD + e.label());
        }
        List<String> x = new ArrayList<>();
        for (Exempt e : exempt) {
            x.add(e.name() + FIELD + e.note());
        }
        return String.join(SEP, String.join(ITEM, b), String.join(ITEM, allowed), String.join(ITEM, x), message, Long.toString(revision));
    }

    @Nullable
    public static CheckRules decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 5) {
            return null;
        }
        try {
            List<Entry> b = new ArrayList<>();
            for (String item : f[0].isEmpty() ? new String[0] : f[0].split(ITEM, -1)) {
                String[] p = item.split(FIELD, -1);
                if (p.length == 3) {
                    b.add(new Entry(Kind.valueOf(p[0]), p[1], p[2]));
                }
            }
            List<String> a = f[1].isEmpty() ? List.of() : List.of(f[1].split(ITEM, -1));
            List<Exempt> x = new ArrayList<>();
            for (String item : f[2].isEmpty() ? new String[0] : f[2].split(ITEM, -1)) {
                String[] p = item.split(FIELD, -1);
                if (p.length == 2) {
                    x.add(new Exempt(p[0], p[1]));
                }
            }
            return new CheckRules(b, a, x, f[3], Long.parseLong(f[4])).sanitised();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putByteArray("encoded", encode().getBytes(java.nio.charset.StandardCharsets.UTF_8)); // full lists pass a string tag's 64 KB
        return t;
    }

    public static CheckRules load(CompoundTag t) {
        CheckRules r = t.contains("encoded") ? decode(new String(t.getByteArray("encoded"), java.nio.charset.StandardCharsets.UTF_8)) : null;
        return r == null ? DEFAULTS : r;
    }
}
