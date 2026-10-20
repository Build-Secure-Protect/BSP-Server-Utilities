package com.mrgregles.bsp_server_utilities.storage;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Redis wire format (RESP2), as much of it as this mod needs (as in BSP RankUp and BSP Region Guard): commands go out as arrays of bulk strings;
 * replies come back as simple strings, errors, integers, bulk strings (null when absent) and arrays.
 */
public final class Resp {
    /** An error reply, such as {@code WRONGPASS ...} or {@code NOAUTH ...}. */
    public record Error(String message) {
    }

    private static final int MAX_BULK = 64 * 1024 * 1024;

    private Resp() {
    }

    /** Writes one command, for example {@code PUBLISH channel payload}, and flushes. */
    public static void command(OutputStream out, String... args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            bulk(buf, arg);
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    /** Appends one bulk string ({@code $len\r\nbytes\r\n}). */
    public static void bulk(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write('\r');
        out.write('\n');
    }

    /** Reads one reply: a {@code String}, {@link Error}, {@code Long}, {@code null} or a {@code List} of these. */
    public static Object read(InputStream in) throws IOException {
        return value(in, next(in));
    }

    /** The rest of a reply whose first byte was already read (used to tell an idle connection from a cut-off reply). */
    public static Object readAfter(InputStream in, int type) throws IOException {
        return value(in, type);
    }

    private static Object value(InputStream in, int type) throws IOException {
        switch (type) {
            case '+':
                return line(in);
            case '-':
                return new Error(line(in));
            case ':':
                return Long.parseLong(line(in));
            case '$': {
                int n = Integer.parseInt(line(in));
                if (n < 0) {
                    return null;
                }
                if (n > MAX_BULK) {
                    throw new IOException("Redis reply too large: " + n + " bytes");
                }
                byte[] data = in.readNBytes(n);
                if (data.length < n) {
                    throw new EOFException("Redis closed the connection");
                }
                next(in);
                next(in); // \r\n
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line(in));
                if (n < 0) {
                    return null;
                }
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(read(in));
                }
                return list;
            }
            default:
                throw new IOException("Not a Redis reply (first byte " + type + ")");
        }
    }

    /** The next byte; end of stream is an error. */
    public static int next(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Redis closed the connection");
        }
        return b;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = next(in)) != '\r') {
            buf.write(b);
        }
        next(in); // \n
        return buf.toString(StandardCharsets.UTF_8);
    }
}
