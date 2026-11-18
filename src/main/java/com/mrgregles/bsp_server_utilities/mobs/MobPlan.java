package com.mrgregles.bsp_server_utilities.mobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Which mobs REMOVE EXTRAS takes from a chunk (owner decision D485), worked out the same way by the server, which
 * removes them, and the tab, which says beforehand how many of which: the most numerous kind first, then the next,
 * until the chunk is at its limit; tamed and name-tagged mobs are never taken.
 */
public final class MobPlan {
    private MobPlan() {
    }

    /** One kind of mob in a chunk: its type id, how many, and how many of them are tamed or name-tagged (kept). */
    public record TypeCount(String type, int count, int kept) {
    }

    /** Most numerous first, then by type id, so the server and the tab agree. */
    public static final Comparator<TypeCount> ORDER = Comparator.comparingInt(TypeCount::count).reversed().thenComparing(TypeCount::type);

    /** How many of each kind go, in order, for a chunk of {@code count} mobs (made of {@code types}) and its {@code limit}. */
    public static List<TypeCount> plan(List<TypeCount> types, int count, int limit) {
        List<TypeCount> sorted = new ArrayList<>(types);
        sorted.sort(ORDER);
        int extra = Math.max(0, count - limit);
        List<TypeCount> out = new ArrayList<>();
        for (TypeCount t : sorted) {
            if (extra <= 0) {
                break;
            }
            int take = Math.min(extra, Math.max(0, t.count() - t.kept()));
            if (take > 0) {
                out.add(new TypeCount(t.type(), take, 0));
                extra -= take;
            }
        }
        return out;
    }

    public static int total(List<TypeCount> plan) {
        return plan.stream().mapToInt(TypeCount::count).sum();
    }

    /** A type id as words for the log: {@code minecraft:zombie_villager} is "zombie villager". */
    public static String words(String type) {
        return type.substring(type.indexOf(':') + 1).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    /** "71 Zombie, 3 Spider" with each type's name as {@code name} gives it (the tab, in the player's language). */
    public static String describeWords(List<TypeCount> plan, java.util.function.Function<String, String> name) {
        List<String> parts = new ArrayList<>();
        for (TypeCount t : plan) {
            parts.add(t.count() + " " + name.apply(t.type()).toLowerCase(Locale.ROOT));
        }
        return String.join(", ", parts);
    }

    /** "71 zombie, 3 spider" for the Action Log. */
    public static String describe(List<TypeCount> plan) {
        List<String> parts = new ArrayList<>();
        for (TypeCount t : plan) {
            parts.add(t.count() + " " + words(t.type()));
        }
        return String.join(", ", parts);
    }
}
