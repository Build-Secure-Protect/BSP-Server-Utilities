package com.mrgregles.bsp_server_utilities.chunks;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Chunk unloading's settings, one set for the network (owner decisions D88, D90, D92, D95): whether it runs, how many
 * minutes (1 to 60) an owner is away from a server before their machines' chunks unload there, the mods never unloaded
 * (BSP-Core never is, whatever this says), the players always loaded, and a revision that marks every save.
 */
public record ChunkSettings(boolean enabled, int minutes, List<String> exemptMods, List<Player> alwaysLoaded, long revision) {
    public static final int MIN_MINUTES = 1, MAX_MINUTES = 60, MAX_EXEMPT = 32, MAX_ALWAYS = 64;
    public static final ChunkSettings DEFAULTS = new ChunkSettings(true, 5, List.of(), List.of(), 0);
    private static final String SEP = "\u001f";

    /** A player always loaded: their UUID, and their name as it was when they were added. */
    public record Player(UUID id, String name) {
    }

    public ChunkSettings {
        exemptMods = List.copyOf(exemptMods);
        alwaysLoaded = List.copyOf(alwaysLoaded);
    }

    public ChunkSettings sanitised() {
        List<String> mods = exemptMods.stream().map(m -> m == null ? "" : m.strip().toLowerCase(Locale.ROOT))
                .filter(m -> m.matches("[a-z0-9_.-]{1,64}") && !m.equals(ChunkTickets.BSP_CORE)).distinct().limit(MAX_EXEMPT).toList();
        List<Player> players = new ArrayList<>();
        for (Player p : alwaysLoaded) {
            if (p != null && p.id() != null && players.stream().noneMatch(x -> x.id().equals(p.id())) && players.size() < MAX_ALWAYS) {
                String n = p.name() == null ? "" : p.name().replaceAll("[^A-Za-z0-9_]", "");
                players.add(new Player(p.id(), n.length() > 16 ? n.substring(0, 16) : n));
            }
        }
        return new ChunkSettings(enabled, Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes)), mods, players, revision);
    }

    public ChunkSettings withRevision(long r) {
        return new ChunkSettings(enabled, minutes, exemptMods, alwaysLoaded, r);
    }

    public long awayMs() {
        return minutes * 60_000L;
    }

    public boolean alwaysLoaded(UUID player) {
        return alwaysLoaded.stream().anyMatch(p -> p.id().equals(player));
    }

    public boolean exempt(String modId) {
        return ChunkTickets.BSP_CORE.equals(modId) || exemptMods.contains(modId);
    }

    public String encode() {
        StringBuilder players = new StringBuilder();
        for (Player p : alwaysLoaded) {
            if (players.length() > 0) {
                players.append(',');
            }
            players.append(p.id()).append(':').append(p.name());
        }
        return String.join(SEP, Boolean.toString(enabled), Integer.toString(minutes), String.join(",", exemptMods), players, Long.toString(revision));
    }

    @Nullable
    public static ChunkSettings decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 5) {
            return null;
        }
        try {
            List<Player> players = new ArrayList<>();
            for (String p : f[3].isEmpty() ? new String[0] : f[3].split(",")) {
                int c = p.indexOf(':');
                players.add(new Player(UUID.fromString(c < 0 ? p : p.substring(0, c)), c < 0 ? "" : p.substring(c + 1)));
            }
            return new ChunkSettings(Boolean.parseBoolean(f[0]), Integer.parseInt(f[1]), f[2].isEmpty() ? List.of() : List.of(f[2].split(",")), players, Long.parseLong(f[4])).sanitised();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("encoded", encode());
        return t;
    }

    public static ChunkSettings load(CompoundTag t) {
        ChunkSettings s = t.contains("encoded") ? decode(t.getString("encoded")) : null;
        return s == null ? DEFAULTS : s;
    }
}
