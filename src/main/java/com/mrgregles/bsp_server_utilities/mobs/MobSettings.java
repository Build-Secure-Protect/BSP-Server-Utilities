package com.mrgregles.bsp_server_utilities.mobs;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mob limits, one set for the network (owner decisions D471 to D477, D484): how many mobs one chunk may hold, as a number
 * for every chunk, a number for each chunk of a dimension, and a number for one chunk of one server; the most specific
 * wins (D472). {@link #OFF} (0) is no limit at that level: OFF for every chunk is no limit anywhere else unset, and a
 * dimension or single chunk set to OFF has none whatever the wider number says. Mob types never counted (D477). With a
 * revision, the newest copy wins.
 */
public record MobSettings(int every, List<DimLimit> dims, List<ChunkLimit> chunks, List<String> never, Drops drops, long revision) {
    public static final int OFF = 0, MAX_LIMIT = 1000, MAX_DIMS = 64, MAX_CHUNKS = 200, MAX_NEVER = 100, MAX_SERVER = 64;
    public static final MobSettings DEFAULTS = new MobSettings(OFF, List.of(), List.of(), List.of(), Drops.DEFAULTS, 0);
    private static final String SEP = "\u001f", ID = "[a-z0-9_.-]+:[a-z0-9_./-]+", SERVER = "[^|;,=\u001f\n\r]+";

    /**
     * Crowded drops and arrows (owner decisions D487 to D501), on the same page set: how many dropped items and arrows one
     * chunk may hold (each to {@link #OFF}), and for how many minutes over before it is cleared down to the number.
     */
    public record Drops(int items, int arrows, int minutes) {
        public static final int MAX = 10_000, MIN_MINUTES = 1, MAX_MINUTES = 60;
        public static final Drops DEFAULTS = new Drops(256, 128, 5); // on from the start (D488, D489)

        public Drops sanitised() {
            return new Drops(Math.max(OFF, Math.min(MAX, items)), Math.max(OFF, Math.min(MAX, arrows)), Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes)));
        }
    }

    /** Mob limits with the drops' numbers as they start. */
    public MobSettings(int every, List<DimLimit> dims, List<ChunkLimit> chunks, List<String> never, long revision) {
        this(every, dims, chunks, never, Drops.DEFAULTS, revision);
    }

    /** A dimension's number for each of its chunks. */
    public record DimLimit(String dim, int limit) {
    }

    /** One chunk's own number, on one server (D476: the same chunk on two servers is two places). */
    public record ChunkLimit(String server, String dim, int x, int z, int limit) {
        public boolean is(String server, String dim, int x, int z) {
            return this.server.equals(server) && this.dim.equals(dim) && this.x == x && this.z == z;
        }
    }

    /** Where a chunk's number comes from, for the tab ("limit 25 (Compact Machines rooms)"). */
    public enum From { NONE, EVERY, DIMENSION, CHUNK }

    /** A chunk's number and where it comes from; {@link #OFF} when none applies. */
    public record Limit(int limit, From from) {
        public boolean on() {
            return limit > OFF;
        }
    }

    public MobSettings {
        dims = List.copyOf(dims);
        chunks = List.copyOf(chunks);
        never = List.copyOf(never);
    }

    private static int clamp(int n) {
        return Math.max(OFF, Math.min(MAX_LIMIT, n));
    }

    private static String id(@Nullable String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    public MobSettings sanitised() {
        List<DimLimit> d = new ArrayList<>();
        for (DimLimit x : dims) {
            String dim = x == null ? "" : id(x.dim());
            if (dim.matches(ID) && d.stream().noneMatch(y -> y.dim().equals(dim)) && d.size() < MAX_DIMS) {
                d.add(new DimLimit(dim, clamp(x.limit())));
            }
        }
        List<ChunkLimit> c = new ArrayList<>();
        for (ChunkLimit x : chunks) {
            if (x == null || x.server() == null) {
                continue;
            }
            String server = x.server().strip(), dim = id(x.dim());
            if (server.length() <= MAX_SERVER && server.matches(SERVER) && dim.matches(ID) && c.stream().noneMatch(y -> y.is(server, dim, x.x(), x.z())) && c.size() < MAX_CHUNKS) {
                c.add(new ChunkLimit(server, dim, x.x(), x.z(), clamp(x.limit())));
            }
        }
        List<String> n = new ArrayList<>();
        for (String x : never) {
            String type = id(x);
            if (type.matches(ID) && !n.contains(type) && n.size() < MAX_NEVER) {
                n.add(type);
            }
        }
        return new MobSettings(clamp(every), d, c, n, drops == null ? Drops.DEFAULTS : drops.sanitised(), revision);
    }

    public MobSettings withRevision(long r) {
        return new MobSettings(every, dims, chunks, never, drops, r);
    }

    public MobSettings withEvery(int n) {
        return new MobSettings(clamp(n), dims, chunks, never, drops, revision);
    }

    public MobSettings withDrops(Drops d) {
        return new MobSettings(every, dims, chunks, never, d.sanitised(), revision);
    }

    /** The dimension's number set (added at the every-chunk number when new, D483), or taken away when {@code limit} is null. */
    public MobSettings withDim(String dim, @Nullable Integer limit) {
        List<DimLimit> d = new ArrayList<>();
        boolean found = false;
        for (DimLimit x : dims) {
            if (x.dim().equals(dim)) {
                found = true;
                if (limit != null) {
                    d.add(new DimLimit(dim, clamp(limit)));
                }
            } else {
                d.add(x);
            }
        }
        if (!found && limit != null) {
            d.add(new DimLimit(dim, clamp(limit)));
        }
        return new MobSettings(every, d, chunks, never, drops, revision);
    }

    /** One chunk's number set (added when new), or taken away when {@code limit} is null. */
    public MobSettings withChunk(String server, String dim, int x, int z, @Nullable Integer limit) {
        List<ChunkLimit> c = new ArrayList<>();
        boolean found = false;
        for (ChunkLimit y : chunks) {
            if (y.is(server, dim, x, z)) {
                found = true;
                if (limit != null) {
                    c.add(new ChunkLimit(server, dim, x, z, clamp(limit)));
                }
            } else {
                c.add(y);
            }
        }
        if (!found && limit != null) {
            c.add(new ChunkLimit(server, dim, x, z, clamp(limit)));
        }
        return new MobSettings(every, dims, c, never, drops, revision);
    }

    /** A mob type added to the never-counted list, or taken off it. */
    public MobSettings withNever(String type, boolean add) {
        List<String> n = new ArrayList<>(never);
        n.remove(type);
        if (add) {
            n.add(type);
        }
        return new MobSettings(every, dims, chunks, n, drops, revision);
    }

    /** The number for a chunk of {@code dim} at chunk {@code x, z} on {@code server}: its own, else its dimension's, else every chunk's (D472). */
    public Limit limitFor(String server, String dim, int x, int z) {
        for (ChunkLimit c : chunks) {
            if (c.is(server, dim, x, z)) {
                return new Limit(c.limit(), From.CHUNK);
            }
        }
        for (DimLimit d : dims) {
            if (d.dim().equals(dim)) {
                return new Limit(d.limit(), From.DIMENSION);
            }
        }
        return new Limit(every, every > OFF ? From.EVERY : From.NONE);
    }

    /** Whether any number is set anywhere (nothing to check while every number is OFF). */
    public boolean anyOn() {
        return every > OFF || dims.stream().anyMatch(d -> d.limit() > OFF) || chunks.stream().anyMatch(c -> c.limit() > OFF);
    }

    public String encode() {
        StringBuilder d = new StringBuilder();
        for (DimLimit x : dims) {
            d.append(d.length() == 0 ? "" : ",").append(x.dim()).append('=').append(x.limit());
        }
        StringBuilder c = new StringBuilder();
        for (ChunkLimit x : chunks) {
            c.append(c.length() == 0 ? "" : ";").append(x.server()).append('|').append(x.dim()).append('|').append(x.x()).append('|').append(x.z()).append('|').append(x.limit());
        }
        return String.join(SEP, Integer.toString(every), d, c, String.join(",", never), drops.items() + "," + drops.arrows() + "," + drops.minutes(), Long.toString(revision));
    }

    @Nullable
    public static MobSettings decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 6) {
            return null;
        }
        try {
            List<DimLimit> d = new ArrayList<>();
            for (String x : f[1].isEmpty() ? new String[0] : f[1].split(",")) {
                int eq = x.lastIndexOf('=');
                if (eq > 0) {
                    d.add(new DimLimit(x.substring(0, eq), Integer.parseInt(x.substring(eq + 1))));
                }
            }
            List<ChunkLimit> c = new ArrayList<>();
            for (String x : f[2].isEmpty() ? new String[0] : f[2].split(";")) {
                String[] p = x.split("\\|", -1);
                if (p.length == 5) {
                    c.add(new ChunkLimit(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])));
                }
            }
            List<String> n = f[3].isEmpty() ? List.of() : List.of(f[3].split(","));
            String[] dr = f[4].split(",");
            if (dr.length != 3) {
                return null;
            }
            Drops drops = new Drops(Integer.parseInt(dr[0]), Integer.parseInt(dr[1]), Integer.parseInt(dr[2]));
            return new MobSettings(Integer.parseInt(f[0]), d, c, n, drops, Long.parseLong(f[5])).sanitised();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("encoded", encode());
        return t;
    }

    public static MobSettings load(CompoundTag t) {
        MobSettings s = t.contains("encoded") ? decode(t.getString("encoded")) : null;
        return s == null ? DEFAULTS : s;
    }
}
