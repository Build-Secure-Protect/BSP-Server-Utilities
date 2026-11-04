package com.mrgregles.bsp_server_utilities.afk;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One player's last ten minutes of input, to spot a macro (owner decisions D208, D209, D217). Each input is a key (what
 * was done and to what: {@code attack:minecraft:zombie}, {@code item:minecraft:fishing_rod}, a head turn to exact angles
 * {@code turn:12.5,30.0}), when, the head's angles and where the player stood.
 *
 * <p>A pattern holds when the player has been watched for the whole window, did at least {@value #MIN_INPUTS} things in
 * it, the same 1 to 3 keys make 90 % or more of them, and at least one of: the gaps between the same key hardly vary
 * (less than 20 % about their mean: a metronome), the same head angles again and again (to a thousandth of a degree, so
 * a float's rounding does not hide a macro), or every input within 8 blocks of their middle.
 */
public final class MacroWatch {
    public static final long WINDOW_MS = 600_000L;
    public static final int MIN_INPUTS = 20, MAX_INPUTS = 6000, MAX_KEYS = 3, MIN_GAPS = 5;
    public static final double SHARE = 0.9, EVEN = 0.2, SPOT = 8.0;
    /** The same key again sooner than this is the same input (a click reaches the server once for each hand). */
    private static final long SAME_MS = 50;
    private static final float ANGLE = 1000f;

    public record Input(String key, long atMs, float yaw, float pitch, double x, double y, double z) {
    }

    /** A pattern that holds: its keys, which signs were seen (and the beat when even), and since when it has held. */
    public record Pattern(Set<String> keys, boolean even, double everyS, boolean sameAngles, boolean oneSpot, long sinceMs) {
        /** What was seen, in the Action Log's words: "the same click on a zombie every 1.0 s at one spot, for 18 min". */
        public String seen(long now) {
            boolean turnsOnly = keys.stream().allMatch(k -> k.startsWith("turn:"));
            StringBuilder b = new StringBuilder();
            if (turnsOnly) {
                b.append(keys.size() == 1 ? "the same head turn to one exact angle" : "the same head turns between " + keys.size() + " exact angles");
            } else if (keys.size() == 1) {
                b.append("the same ").append(describe(keys.iterator().next()));
            } else {
                List<String> what = new ArrayList<>();
                for (String k : keys) {
                    what.add(describe(k));
                }
                b.append("the same ").append(keys.size()).append(" inputs (").append(String.join(", ", what)).append(')');
            }
            if (even && everyS > 0) {
                b.append(" every ").append(String.format(Locale.ROOT, "%.1f", everyS)).append(" s");
            }
            if (sameAngles && !turnsOnly) {
                b.append(" at the same head angle");
            }
            if (oneSpot) {
                b.append(" at one spot");
            }
            return b.append(", for ").append(Math.max(1, Math.round((now - sinceMs) / 60_000.0))).append(" min").toString();
        }
    }

    private final ArrayDeque<Input> inputs = new ArrayDeque<>();
    private final long watchedSince;
    private long heldSince;

    public MacroWatch(long now) {
        watchedSince = now;
    }

    public void add(Input in) {
        Input last = inputs.peekLast();
        if (last != null && last.key().equals(in.key()) && in.atMs() - last.atMs() < SAME_MS) {
            return;
        }
        inputs.addLast(in);
        while (inputs.size() > MAX_INPUTS) {
            inputs.pollFirst();
        }
    }

    /** Forgets inputs older than the window. */
    public void prune(long now) {
        while (!inputs.isEmpty() && inputs.peekFirst().atMs() < now - WINDOW_MS) {
            inputs.pollFirst();
        }
    }

    /** The pattern that holds now, or null. */
    @Nullable
    public Pattern find(long now) {
        prune(now);
        Pattern p = now - watchedSince < WINDOW_MS || inputs.size() < MIN_INPUTS ? null : evaluate();
        if (p == null) {
            heldSince = 0;
            return null;
        }
        if (heldSince == 0) {
            heldSince = now - WINDOW_MS; // it has held over the whole window
        }
        return new Pattern(p.keys(), p.even(), p.everyS(), p.sameAngles(), p.oneSpot(), heldSince);
    }

    @Nullable
    private Pattern evaluate() {
        int n = inputs.size();
        Map<String, Integer> counts = new HashMap<>();
        for (Input in : inputs) {
            counts.merge(in.key(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : b.getValue() - a.getValue());
        Set<String> keys = new LinkedHashSet<>();
        int share = 0;
        for (Map.Entry<String, Integer> e : ranked) {
            if (keys.size() == MAX_KEYS || share >= SHARE * n) {
                break;
            }
            keys.add(e.getKey());
            share += e.getValue();
        }
        if (share < SHARE * n) {
            return null;
        }

        // the beat: every key with enough gaps keeps time, and at least one has them
        Map<String, List<Long>> times = new HashMap<>();
        Map<Long, Integer> angles = new HashMap<>();
        double sx = 0, sy = 0, sz = 0;
        for (Input in : inputs) {
            sx += in.x();
            sy += in.y();
            sz += in.z();
            if (keys.contains(in.key())) {
                times.computeIfAbsent(in.key(), k -> new ArrayList<>()).add(in.atMs());
                angles.merge(Math.round(in.yaw() * ANGLE) * 1_000_003L + Math.round(in.pitch() * ANGLE), 1, Integer::sum);
            }
        }
        boolean timed = false, untimed = false;
        double everyS = 0;
        for (String k : keys) {
            List<Long> t = times.get(k);
            if (t.size() - 1 < MIN_GAPS) {
                continue;
            }
            double mean = (t.get(t.size() - 1) - t.get(0)) / (double) (t.size() - 1), var = 0;
            for (int i = 1; i < t.size(); i++) {
                double d = t.get(i) - t.get(i - 1) - mean;
                var += d * d;
            }
            double sd = Math.sqrt(var / (t.size() - 1));
            if (mean > 0 && sd / mean < EVEN) {
                timed = true;
                if (everyS == 0) {
                    everyS = mean / 1000.0; // the most frequent key's beat
                }
            } else {
                untimed = true;
            }
        }
        boolean even = timed && !untimed;

        // the head: the 3 commonest exact angles hold 90 % or more of the pattern's inputs
        List<Integer> angleCounts = new ArrayList<>(angles.values());
        angleCounts.sort((a, b) -> b - a);
        int sameAngle = 0;
        for (int i = 0; i < Math.min(3, angleCounts.size()); i++) {
            sameAngle += angleCounts.get(i);
        }
        boolean sameAngles = sameAngle >= SHARE * share;

        // the place: everything within 8 blocks of the middle
        double mx = sx / n, my = sy / n, mz = sz / n;
        boolean oneSpot = true;
        for (Input in : inputs) {
            double dx = in.x() - mx, dy = in.y() - my, dz = in.z() - mz;
            if (dx * dx + dy * dy + dz * dz > SPOT * SPOT) {
                oneSpot = false;
                break;
            }
        }
        if (!even && !sameAngles && !oneSpot) {
            return null;
        }
        return new Pattern(keys, even, even ? everyS : 0, sameAngles, oneSpot, 0);
    }

    /** One key in words: "click on a zombie", "use of the fishing rod", "right click on the lever". */
    public static String describe(String key) {
        int i = key.indexOf(':');
        String kind = i < 0 ? key : key.substring(0, i), target = i < 0 ? "" : key.substring(i + 1);
        String what = words(target);
        return switch (kind) {
            case "attack" -> "click on " + a(what);
            case "entity" -> "right click on " + a(what);
            case "block" -> "right click on the " + what;
            case "hit" -> "click on the " + what;
            case "item" -> what.equals("air") ? "right click with an empty hand" : "use of the " + what;
            case "break" -> "break of " + what;
            case "place" -> "placing of " + what;
            case "toss" -> "drop of the " + what;
            case "chat" -> "chat line";
            case "cmd" -> "command /" + target;
            case "open" -> "container opening";
            case "click" -> "container click";
            case "steer" -> "steering";
            case "turn" -> "head turn";
            default -> kind;
        };
    }

    /** A registry id as words: {@code minecraft:iron_golem} is "iron golem". */
    private static String words(String id) {
        return id.substring(id.lastIndexOf(':') + 1).replace('_', ' ');
    }

    private static String a(String noun) {
        return (!noun.isEmpty() && "aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }

    /** What a head turn was to, for its key: the angles to a thousandth of a degree. */
    public static String angles(float yaw, float pitch) {
        return Math.round(yaw * ANGLE) / ANGLE + "," + Math.round(pitch * ANGLE) / ANGLE;
    }
}
