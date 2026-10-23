package com.mrgregles.bsp_server_utilities.setup;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The setup guide's state for the network (owner decisions D143 to D158): how far each shared feature's page got (not
 * yet, set up, left for later, turned off), the features whose own off switch is thrown (D150), and the admins who
 * chose to ignore the guide (D145), with a revision (the newest wins between servers).
 */
public record SetupState(Map<Feature, Status> status, Set<Feature> off, Set<UUID> ignored, long revision) {
    public enum Status { NOT_YET, DONE, LATER, OFF }

    public static final SetupState EMPTY = new SetupState(Map.of(), Set.of(), Set.of(), 0);
    private static final String SEP = "\u001f", ITEM = "\u001e";

    public SetupState {
        Map<Feature, Status> s = new EnumMap<>(Feature.class);
        status.forEach((f, v) -> {
            if (!f.perServer() && v != null) {
                s.put(f, v);
            }
        });
        status = Map.copyOf(s);
        Set<Feature> o = EnumSet.noneOf(Feature.class);
        off.stream().filter(Feature::ownSwitch).forEach(o::add);
        off = Set.copyOf(o);
        ignored = Set.copyOf(ignored);
    }

    public Status of(Feature f) {
        return status.getOrDefault(f, Status.NOT_YET);
    }

    public SetupState with(Feature f, Status s) {
        Map<Feature, Status> next = new EnumMap<>(Feature.class);
        next.putAll(status);
        next.put(f, s);
        return new SetupState(next, off, ignored, revision);
    }

    public SetupState withOff(Feature f, boolean isOff) {
        Set<Feature> next = EnumSet.noneOf(Feature.class);
        next.addAll(off);
        if (isOff) {
            next.add(f);
        } else {
            next.remove(f);
        }
        return new SetupState(status, next, ignored, revision);
    }

    public SetupState withIgnored(UUID admin) {
        Set<UUID> next = new java.util.HashSet<>(ignored);
        next.add(admin);
        return new SetupState(status, off, next, revision);
    }

    public SetupState withRevision(long r) {
        return new SetupState(status, off, ignored, r);
    }

    public String encode() {
        List<String> st = new ArrayList<>();
        status.forEach((f, v) -> st.add(f.name() + "=" + v.name()));
        st.sort(null);
        Set<String> o = new TreeSet<>();
        off.forEach(f -> o.add(f.name()));
        Set<String> ig = new TreeSet<>();
        ignored.forEach(u -> ig.add(u.toString()));
        return String.join(SEP, String.join(ITEM, st), String.join(ITEM, o), String.join(ITEM, ig), Long.toString(revision));
    }

    @Nullable
    public static SetupState decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 4) {
            return null;
        }
        try {
            Map<Feature, Status> st = new EnumMap<>(Feature.class);
            for (String item : f[0].isEmpty() ? new String[0] : f[0].split(ITEM)) {
                int eq = item.indexOf('=');
                if (eq > 0) {
                    st.put(Feature.valueOf(item.substring(0, eq)), Status.valueOf(item.substring(eq + 1)));
                }
            }
            Set<Feature> off = EnumSet.noneOf(Feature.class);
            for (String item : f[1].isEmpty() ? new String[0] : f[1].split(ITEM)) {
                off.add(Feature.valueOf(item));
            }
            Set<UUID> ig = new java.util.HashSet<>();
            for (String item : f[2].isEmpty() ? new String[0] : f[2].split(ITEM)) {
                ig.add(UUID.fromString(item));
            }
            return new SetupState(st, off, ig, Long.parseLong(f[3]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
