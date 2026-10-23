package com.mrgregles.bsp_server_utilities.check;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the client check makes of a report under a server's rules (owner decisions D120 to D126, D129): the things that
 * refuse the player (a blocked mod, pack or shader; a failed stone test) and the flags admins see without anyone being
 * refused (mods not in the trusted setup, pack mods whose file differs from it, packs not in it, a brightness past the
 * top, mods Forge's handshake listed that the report left out). An exempt player is never refused; their flags still
 * show. For a player already online, refusing items mean "blocked since they joined" (D139).
 */
public record Verdict(List<Item> refuse, List<String> notTrustedMods, List<String> fileDiffers, List<String> notTrustedPacks, boolean brightness,
                      List<String> hidden, boolean exempt) {
    public enum What { MOD, PACK, STONE, SHADER }

    /** Something to remove: its name and what it is. */
    public record Item(String name, What what) {
    }

    public static final Verdict CLEAN = new Verdict(List.of(), List.of(), List.of(), List.of(), false, List.of(), false);
    private static final String SEP = "\u001f", ITEM = "\u001e", FIELD = "\u001d";

    public Verdict {
        refuse = List.copyOf(refuse);
        notTrustedMods = List.copyOf(notTrustedMods);
        fileDiffers = List.copyOf(fileDiffers);
        notTrustedPacks = List.copyOf(notTrustedPacks);
        hidden = List.copyOf(hidden);
    }

    /** Whether the player is turned away. */
    public boolean refused() {
        return !refuse.isEmpty() && !exempt;
    }

    /** How many lines of flags the tab shows (refusing items count as one). */
    public int flagCount() {
        int n = refuse.isEmpty() ? 0 : 1;
        n += notTrustedMods.isEmpty() ? 0 : 1;
        n += fileDiffers.isEmpty() ? 0 : 1;
        n += notTrustedPacks.isEmpty() ? 0 : 1;
        n += brightness ? 1 : 0;
        n += hidden.isEmpty() ? 0 : 1;
        return n;
    }

    /**
     * Judges a report. {@code setup} null means no trusted setup has been taken: nothing is "not trusted" then.
     * {@code forgeMods} is Forge's list from the login handshake (empty when unknown).
     */
    public static Verdict judge(ClientReport r, List<String> forgeMods, CheckRules rules, @Nullable TrustedSetup setup, String playerName) {
        Set<Item> refuse = new LinkedHashSet<>();
        List<String> notTrusted = new ArrayList<>(), differs = new ArrayList<>(), packs = new ArrayList<>(), hidden = new ArrayList<>();
        Set<String> reported = new java.util.HashSet<>();
        for (ClientReport.Mod m : r.mods()) {
            reported.add(m.id());
            if (blockedMod(rules, m.id(), m.name())) {
                refuse.add(new Item(m.id(), What.MOD));
            }
            if (setup != null) {
                ClientReport.Mod t = setup.mod(m.id());
                if (t == null) {
                    notTrusted.add(m.id());
                } else if (!t.sha1().isEmpty() && !m.sha1().isEmpty() && !t.sha1().equals(m.sha1())) {
                    differs.add(m.id());
                }
            }
        }
        for (String id : forgeMods) {
            if (!reported.contains(id)) {
                hidden.add(id);
                if (blockedMod(rules, id, "")) {
                    refuse.add(new Item(id, What.MOD));
                }
            }
        }
        for (ClientReport.Pack p : r.packs()) {
            if (blockedPack(rules, p)) {
                refuse.add(new Item(p.name(), What.PACK));
            }
            if (setup != null && !setup.hasPack(p)) {
                packs.add(p.name());
            }
        }
        if (!r.stonePassed()) {
            refuse.add(new Item(r.stonePack(), What.STONE));
        }
        if (!r.shader().isEmpty() && !rules.isAllowed(r.shader()) && hasWord(rules, r.shader())) {
            refuse.add(new Item(r.shader(), What.SHADER));
        }
        notTrusted.sort(null);
        differs.sort(null);
        return new Verdict(new ArrayList<>(refuse), notTrusted, differs, packs, r.brightnessHigh(), hidden, rules.isExempt(playerName));
    }

    static boolean blockedMod(CheckRules rules, String id, String name) {
        if (rules.isAllowed(id) || (!name.isEmpty() && rules.isAllowed(name))) {
            return false;
        }
        String lid = id.toLowerCase(Locale.ROOT);
        for (CheckRules.Entry e : rules.block()) {
            if (e.kind() == CheckRules.Kind.MOD && e.value().equals(lid)) {
                return true;
            }
        }
        return hasWord(rules, id) || (!name.isEmpty() && hasWord(rules, name));
    }

    static boolean blockedPack(CheckRules rules, ClientReport.Pack p) {
        for (CheckRules.Entry e : rules.block()) {
            if (e.kind() == CheckRules.Kind.PACK && !p.sha1().isEmpty() && e.value().equals(p.sha1())) {
                return true;
            }
        }
        if (rules.isAllowed(p.name()) || rules.isAllowed(p.id())) {
            return false;
        }
        return hasWord(rules, p.name()) || hasWord(rules, p.id());
    }

    static boolean hasWord(CheckRules rules, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        for (CheckRules.Entry e : rules.block()) {
            if (e.kind() == CheckRules.Kind.WORD && t.contains(e.value())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ text, for notices and packets

    public String encode() {
        List<String> items = new ArrayList<>();
        for (Item i : refuse) {
            items.add(i.what().name() + FIELD + i.name());
        }
        return String.join(SEP, String.join(ITEM, items), String.join(ITEM, notTrustedMods), String.join(ITEM, fileDiffers), String.join(ITEM, notTrustedPacks),
                Boolean.toString(brightness), String.join(ITEM, hidden), Boolean.toString(exempt));
    }

    @Nullable
    public static Verdict decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 7) {
            return null;
        }
        try {
            List<Item> items = new ArrayList<>();
            for (String i : list(f[0])) {
                String[] p = i.split(FIELD, -1);
                if (p.length == 2) {
                    items.add(new Item(p[1], What.valueOf(p[0])));
                }
            }
            return new Verdict(items, list(f[1]), list(f[2]), list(f[3]), Boolean.parseBoolean(f[4]), list(f[5]), Boolean.parseBoolean(f[6]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> list(String s) {
        return s.isEmpty() ? List.of() : List.of(s.split(ITEM, -1));
    }
}
