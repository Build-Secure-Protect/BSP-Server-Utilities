package com.mrgregles.bsp_server_utilities.network;

import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: the newest action log entries for Action log faces and boards (owner decisions D26, D53, D55, D59,
 * D61), sent to every player when the log changes (at most once a second) and at login. Panels hang where players read
 * them, so every row carries the text with coordinates hidden ({@link LogText#masked}); the full text is for the LOG
 * tab. Also the server the player is on (for "this server only") and how many days entries are kept.
 */
public record LogViewPacket(String here, int keepDays, List<Row> rows) {
    /** How many entries every player keeps: enough for a panel filtered to one server and one kind. */
    public static final int SENT = 200;
    public static final int MAX_TEXT = 200;

    /** One entry: how long ago (milliseconds at sending), who and their role colour, the kind, the masked text, the server. */
    public record Row(long agoMs, String actor, int colour, String kind, String text, String server) {
    }

    public static LogViewPacket of(List<LogEntry> newestFirst, String here, int keepDays, long now) {
        List<Row> rows = new ArrayList<>();
        for (LogEntry e : newestFirst) {
            if (rows.size() >= SENT) {
                break;
            }
            rows.add(row(e, LogText.masked(e.text()), MAX_TEXT, now));
        }
        return new LogViewPacket(cut(here, 64), keepDays, rows);
    }

    /** One entry as a row, its text as given (masked for players, full for the LOG tab) and cut to {@code maxText}. */
    public static Row row(LogEntry e, String text, int maxText, long now) {
        return new Row(Math.max(0, now - e.atMs()), cut(e.actor(), 64), e.colour() & 0xFFFFFF, cut(e.kind(), 16), cut(text, maxText), cut(e.server(), 64));
    }

    static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** The rows an Action log face shows (D59): this server's only unless every server, of the chosen kinds, at most its lines. */
    public static List<Row> visible(List<Row> rows, String here, PanelSettings.LogView view) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (out.size() >= view.lines()) {
                break;
            }
            if ((view.allServers() || r.server().equals(here)) && (view.kinds().isEmpty() || view.kinds().contains(r.kind()))) {
                out.add(r);
            }
        }
        return out;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(here, 64);
        buf.writeVarInt(keepDays);
        buf.writeVarInt(rows.size());
        rows.forEach(r -> writeRow(buf, r, MAX_TEXT));
    }

    static void writeRow(FriendlyByteBuf buf, Row r, int maxText) {
        buf.writeVarLong(r.agoMs());
        buf.writeUtf(r.actor(), 64);
        buf.writeInt(r.colour());
        buf.writeUtf(r.kind(), 16);
        buf.writeUtf(r.text(), maxText * 2);
        buf.writeUtf(r.server(), 64);
    }

    static Row readRow(FriendlyByteBuf buf, int maxText) {
        return new Row(buf.readVarLong(), buf.readUtf(64), buf.readInt(), buf.readUtf(16), buf.readUtf(maxText * 2), buf.readUtf(64));
    }

    public static LogViewPacket decode(FriendlyByteBuf buf) {
        String here = buf.readUtf(64);
        int keepDays = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), SENT);
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(readRow(buf, MAX_TEXT));
        }
        return new LogViewPacket(here, keepDays, rows);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.mrgregles.bsp_server_utilities.client.ClientLog.accept(this));
        ctx.get().setPacketHandled(true);
    }
}
