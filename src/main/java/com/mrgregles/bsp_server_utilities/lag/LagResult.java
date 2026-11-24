package com.mrgregles.bsp_server_utilities.lag;

import com.mrgregles.bsp_server_utilities.chunks.ChunkCensus;
import com.mrgregles.bsp_server_utilities.network.LagAnswerPacket;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One lag sample on one server (SU-151; D749 to D761; frames R4 to R6 of {@code tools/preview/lag_refined.html}), as plain
 * data with one text form for the notices between servers and the packet to the screen.
 * <p>
 * Times are milliseconds per tick, the average over the ticks sampled, to the microsecond. {@code tickMs} is the whole
 * server tick; {@code outsideMs} the part not in any chunk (world generation, saving, the network, other mods' tick
 * handlers), so the chunks and it add up to the tick (D760). A running sample ({@code running}) carries who, when and how
 * far, and no chunks yet; {@code endsAtMs} is when it should end at 20 ticks a second.
 */
public record LagResult(String server, String by, long startedAtMs, int lengthS, int ticksAsked, int ticks, double tickMs, double outsideMs,
                        boolean running, long endsAtMs, String stoppedBy, List<Chunk> chunks, List<Block> blocks) {
    /** A tick lasts 50 ms at 20 ticks a second: what a share is of. */
    public static final double TICK_MS = 50.0;
    /** The chunks a result keeps, the slowest first. */
    public static final int TOP_CHUNKS = 200;
    /** The entries each chunk keeps, the slowest first. */
    public static final int TOP_ENTRIES = 12;
    /** The blocks OUTLINE marks (D751). */
    public static final int TOP_BLOCKS = 10;
    /** The labels of the two kinds with no type. */
    public static final String SCHEDULED_LABEL = "scheduled ticks", CHUNK_LABEL = "chunk tick";
    private static final String SEP = "|", PARTS = "\n--\n";

    /** What an entry in a chunk is: block entities or entities of one type, the chunk's scheduled ticks, its own tick. */
    public enum Kind { BLOCK_ENTITY, ENTITY, SCHEDULED, CHUNK }

    /**
     * One kind in a chunk: its label (a type id, or {@link #SCHEDULED_LABEL} or {@link #CHUNK_LABEL}), how many, and the worst
     * one's place (null: none). {@code count} is how many of the kind ticked in the chunk during the sample (block entities
     * and scheduled ticks: places; entities: each entity that ticked there at least once, so one that walked through
     * counts), not how many are there when the sample ends; the chunk's own tick is 1.
     */
    public record Entry(Kind kind, String label, int count, double ms, @Nullable BlockPos worst) {
    }

    /** One chunk: where, its time, why it was loaded and by whom at the end of the sample, and its entries. */
    public record Chunk(String dimension, int x, int z, double ms, ChunkCensus.Why why, String who, List<Entry> entries) {
        public Chunk {
            entries = List.copyOf(entries);
        }

        /** Its share of a 50 ms tick, 0 to 1 (or more). */
        public double share() {
            return ms / TICK_MS;
        }
    }

    /** One block for OUTLINE: where, what (a block entity type, or the block or fluid of a scheduled tick), its time. */
    public record Block(String dimension, int x, int y, int z, String label, double ms) {
    }

    /** What a server says when asked (PROFILE): the sample running there, and its latest finished one (D759); either may be null. */
    public record Profile(@Nullable LagResult running, @Nullable LagResult last) {
    }

    public LagResult {
        chunks = List.copyOf(chunks);
        blocks = List.copyOf(blocks);
    }

    /** The share of the tick not in any chunk. */
    public double outsideShare() {
        return outsideMs / TICK_MS;
    }

    /** A running sample's progress, 0 to 1. */
    public double progress() {
        return ticksAsked <= 0 ? 1 : Math.min(1.0, ticks / (double) ticksAsked);
    }

    /** A time rounded to the microsecond, as every time here is. */
    public static double ms(long nanos, int ticks) {
        return ticks <= 0 ? 0 : Math.round(nanos / (double) ticks / 1000.0) / 1000.0;
    }

    // ------------------------------------------------------------------ text form

    private static String clean(String s) {
        return s.replace('|', '/').replace('\n', ' ');
    }

    private static long us(double ms) {
        return Math.round(ms * 1000.0);
    }

    private static double ofUs(String s) {
        return Long.parseLong(s) / 1000.0;
    }

    /** The text form, cut by whole chunks to stay under {@link LagAnswerPacket#MAX_PAYLOAD}. */
    public static String encode(LagResult r) {
        return encode(r, LagAnswerPacket.MAX_PAYLOAD);
    }

    /**
     * Lines: the head ({@code H|...}), each chunk ({@code C|...}) followed by its entries ({@code E|...}), then the blocks
     * ({@code B|...}); chunks are left out from the end where the text would be longer than {@code budget}.
     */
    static String encode(LagResult r, int budget) {
        StringBuilder b = new StringBuilder();
        b.append(String.join(SEP, "H", clean(r.server()), clean(r.by()), Long.toString(r.startedAtMs()), Integer.toString(r.lengthS()),
                Integer.toString(r.ticksAsked()), Integer.toString(r.ticks()), Long.toString(us(r.tickMs())), Long.toString(us(r.outsideMs())),
                r.running() ? "1" : "0", Long.toString(r.endsAtMs()), clean(r.stoppedBy())));
        StringBuilder blocks = new StringBuilder();
        for (Block k : r.blocks()) {
            blocks.append('\n').append(String.join(SEP, "B", clean(k.dimension()), Integer.toString(k.x()), Integer.toString(k.y()), Integer.toString(k.z()),
                    clean(k.label()), Long.toString(us(k.ms()))));
        }
        StringBuilder c = new StringBuilder();
        for (Chunk ch : r.chunks()) {
            c.setLength(0);
            c.append('\n').append(String.join(SEP, "C", clean(ch.dimension()), Integer.toString(ch.x()), Integer.toString(ch.z()), Long.toString(us(ch.ms())),
                    Integer.toString(ch.why().ordinal()), clean(ch.who())));
            for (Entry e : ch.entries()) {
                c.append('\n').append(String.join(SEP, "E", Integer.toString(e.kind().ordinal()), clean(e.label()), Integer.toString(e.count()),
                        Long.toString(us(e.ms())), e.worst() == null ? "" : e.worst().getX() + "," + e.worst().getY() + "," + e.worst().getZ()));
            }
            if (b.length() + c.length() + blocks.length() > budget) {
                break;
            }
            b.append(c);
        }
        return b.append(blocks).toString();
    }

    @Nullable
    public static LagResult decode(String text) {
        if (text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\n");
        String[] h = lines[0].split("\\|", -1);
        if (h.length != 12 || !h[0].equals("H")) {
            return null;
        }
        try {
            List<Chunk> chunks = new ArrayList<>();
            List<Block> blocks = new ArrayList<>();
            String[] open = null;
            List<Entry> entries = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].split("\\|", -1);
                switch (f[0]) {
                    case "C" -> {
                        if (open != null) {
                            chunks.add(chunk(open, entries));
                        }
                        if (f.length != 7) {
                            return null;
                        }
                        open = f;
                        entries = new ArrayList<>();
                    }
                    case "E" -> {
                        if (open == null || f.length != 6) {
                            return null;
                        }
                        int k = Integer.parseInt(f[1]);
                        BlockPos worst = null;
                        if (!f[5].isEmpty()) {
                            String[] p = f[5].split(",");
                            if (p.length != 3) {
                                return null;
                            }
                            worst = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                        }
                        entries.add(new Entry(k >= 0 && k < Kind.values().length ? Kind.values()[k] : Kind.CHUNK, f[2], Integer.parseInt(f[3]), ofUs(f[4]), worst));
                    }
                    case "B" -> {
                        if (f.length != 7) {
                            return null;
                        }
                        blocks.add(new Block(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]), f[5], ofUs(f[6])));
                    }
                    default -> {
                        return null;
                    }
                }
            }
            if (open != null) {
                chunks.add(chunk(open, entries));
            }
            return new LagResult(h[1], h[2], Long.parseLong(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5]), Integer.parseInt(h[6]), ofUs(h[7]), ofUs(h[8]),
                    h[9].equals("1"), Long.parseLong(h[10]), h[11], chunks, blocks);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Chunk chunk(String[] f, List<Entry> entries) {
        int w = Integer.parseInt(f[5]);
        ChunkCensus.Why why = w >= 0 && w < ChunkCensus.Why.values().length ? ChunkCensus.Why.values()[w] : ChunkCensus.Why.OTHER;
        return new Chunk(f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), ofUs(f[4]), why, f[6], entries);
    }

    /** A PROFILE answer: the running sample's text, a {@code --} line, the latest finished one's (either part empty for none). */
    public static String encodeProfile(Profile p) {
        return encodeProfile(p, LagAnswerPacket.MAX_PAYLOAD);
    }

    /** As {@link #encodeProfile(Profile)}, in at most {@code budget} characters: the finished sample's slowest chunks kept. */
    public static String encodeProfile(Profile p, int budget) {
        String running = p.running() == null ? "" : encode(p.running(), budget);
        String last = p.last() == null ? "" : encode(p.last(), budget - running.length() - PARTS.length());
        return running + PARTS + last;
    }

    public static Profile decodeProfile(String text) {
        int at = text.indexOf(PARTS);
        if (at < 0) {
            return new Profile(null, null);
        }
        return new Profile(decode(text.substring(0, at)), decode(text.substring(at + PARTS.length())));
    }
}
