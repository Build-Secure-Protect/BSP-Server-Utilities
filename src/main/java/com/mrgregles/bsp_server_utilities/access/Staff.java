package com.mrgregles.bsp_server_utilities.access;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsConfig;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Who counts as staff (owner decisions D4, D57): members of the staff groups (kept for the network in {@link StaffData},
 * edited in the STAFF tab, seeded once from {@code staff.groups}), each with the label and colour the staff panel shows,
 * plus server operators when that switch is on. A player in more than one staff group takes the first one listed.
 */
public final class Staff {
    /** Violet, the BSP colour for staff and operator pages. */
    public static final int DEFAULT_COLOUR = 0xB58CFF;
    /** Gold, for operators who are in no staff group. */
    public static final int OPERATOR_COLOUR = 0xFFD23A;
    public static final String OPERATOR_GROUP = "", OPERATOR_LABEL = "Operator";
    private static final Pattern GROUP_NAME = Pattern.compile("[a-z0-9_.\\-]{1,64}");
    private static final Pattern COLOUR = Pattern.compile("#?[0-9a-fA-F]{6}");

    /** A staff role: the LuckPerms group (empty for operators), its label and its colour as 0xRRGGBB. */
    public record Role(String group, String label, int colour) {
        public boolean operator() {
            return group.isEmpty();
        }
    }

    public static final Role OPERATOR = new Role(OPERATOR_GROUP, OPERATOR_LABEL, OPERATOR_COLOUR);

    @org.jetbrains.annotations.Nullable
    private static Supplier<List<? extends String>> groupsOverride;
    @org.jetbrains.annotations.Nullable
    private static Supplier<Boolean> operatorsOverride;
    private static final Set<String> WARNED = new HashSet<>();

    private Staff() {
    }

    /** Tests: staff groups and the operator switch given directly instead of read from the config. */
    public static void useForTests(List<String> groups, boolean operators) {
        groupsOverride = () -> groups;
        operatorsOverride = () -> operators;
    }

    /** Back to the network's groups after {@link #useForTests}. */
    public static void useConfig() {
        groupsOverride = null;
        operatorsOverride = null;
    }

    /** The config's {@code staff.groups} and {@code staff.operators}, which seed the network's groups the first time. */
    public static StaffGroups fromConfig() {
        return new StaffGroups(parse(ServerUtilsConfig.getOr(ServerUtilsConfig.STAFF_GROUPS, List.of())), ServerUtilsConfig.getOr(ServerUtilsConfig.STAFF_OPERATORS, true), 0);
    }

    /**
     * Reads entries of the form {@code group:Label:#RRGGBB} (label and colour optional). Group names are lower-cased;
     * an entry with a bad group name is skipped with a warning, a bad colour falls back to violet, duplicates keep the first.
     */
    public static List<Role> parse(List<? extends String> entries) {
        List<Role> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : entries) {
            String[] parts = raw.trim().split(":", 3);
            String group = parts[0].trim().toLowerCase(Locale.ROOT);
            if (!GROUP_NAME.matcher(group).matches()) {
                warnOnce("staff.groups entry \"" + raw + "\" has no usable LuckPerms group name; skipped");
                continue;
            }
            if (!seen.add(group)) {
                continue;
            }
            String label = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : capitalise(group);
            int colour = DEFAULT_COLOUR;
            if (parts.length > 2) {
                String c = parts[2].trim();
                if (COLOUR.matcher(c).matches()) {
                    colour = Integer.parseInt(c.startsWith("#") ? c.substring(1) : c, 16);
                } else {
                    warnOnce("staff.groups entry \"" + raw + "\" has a colour that is not #RRGGBB; violet is used");
                }
            }
            out.add(new Role(group, label, colour));
        }
        return out;
    }

    /** The staff groups, highest first. */
    public static List<Role> roles(MinecraftServer server) {
        return groupsOverride != null ? parse(groupsOverride.get()) : StaffData.get(server).groups().roles();
    }

    public static boolean operatorsCount(MinecraftServer server) {
        return operatorsOverride != null ? operatorsOverride.get() : StaffData.get(server).groups().operators();
    }

    /** The player's staff role: their first staff group, else {@link #OPERATOR} when operators count, else none. */
    public static Optional<Role> roleOf(ServerPlayer player) {
        Set<String> groups = LuckPermsBridge.groupsOf(player.getUUID());
        for (Role r : roles(player.server)) {
            if (groups.contains(r.group())) {
                return Optional.of(r);
            }
        }
        return operatorsCount(player.server) && player.hasPermissions(Perms.OPERATOR_LEVEL) ? Optional.of(OPERATOR) : Optional.empty();
    }

    public static boolean isStaff(ServerPlayer player) {
        return roleOf(player).isPresent();
    }

    private static String capitalise(String group) {
        return group.isEmpty() ? group : Character.toUpperCase(group.charAt(0)) + group.substring(1);
    }

    private static void warnOnce(String message) {
        if (WARNED.add(message)) {
            BSPServerUtilities.LOGGER.warn(message);
        }
    }
}
