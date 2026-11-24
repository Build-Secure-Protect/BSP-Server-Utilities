package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Streamer Mode's Spout2 output (owner decisions D701, D717): the stream picture sent as the Spout sender "BSP Stream",
 * which OBS takes with Off-World-Live's Spout2 plugin ("Spout2 Capture"), with no window. Windows 64-bit (Intel/AMD) only.
 *
 * <p>It uses Spout's own {@code SpoutLibrary.dll} (Spout SDK 2.007.018, BSD-2-Clause, the statically linked "MT" build that
 * needs only Windows' own libraries), shipped unchanged in the jar under {@code natives/windows-x64/} with its licence. On
 * first use it is copied to {@code <game folder>/bsp_server_utilities/natives/} (checked against its SHA-256 every time)
 * and loaded through JNA, which Minecraft carries. {@code GetSpout()} gives the Spout object; its methods are called
 * through its method table, using only slots declared before the first overloaded name in {@code SpoutLibrary.h}
 * (0 SetSenderName, 2 ReleaseSender, 4 SendTexture, 6 IsInitialized), whose places do not depend on how the compiler
 * orders overloads. The object is kept for the session ({@code Release} is never called).
 *
 * <p>Spout draws with OpenGL in Minecraft's own context, so the bindings it may change (framebuffers, the texture unit
 * and its 2D texture, buffers, the program, the vertex array, the viewport, pixel packing) are read before each send and
 * put back after it: Minecraft's cached GL state stays true.
 */
final class SpoutOutput {
    static final String SENDER = "BSP Stream";
    static final String VERSION = StreamerRules.SPOUT_VERSION, SHA256 = StreamerRules.SPOUT_SHA256, RESOURCE = StreamerRules.SPOUT_RESOURCE;
    private static final int SET_SENDER_NAME = 0, RELEASE_SENDER = 2, SEND_TEXTURE = 4, IS_INITIALIZED = 6;

    @Nullable
    private static Pointer spout;
    private static boolean open;
    @Nullable
    private static String failure;

    private SpoutOutput() {
    }

    /** Whether this computer can use Spout2 at all (Windows on Intel or AMD, 64-bit). */
    static boolean available() {
        return StreamerRules.spoutAvailable(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    /** Why Spout2 could not start, for the menu and chat; null when it has not failed. */
    @Nullable
    static String failure() {
        return failure;
    }

    /** Starts sending as "BSP Stream"; false (with {@link #failure}) when Spout could not be loaded. */
    static boolean open() {
        if (open) {
            return true;
        }
        if (!available()) {
            failure = "not Windows 64-bit";
            return false;
        }
        try {
            if (spout == null) {
                Path dll = extract();
                NativeLibrary lib = NativeLibrary.getInstance(dll.toAbsolutePath().toString());
                spout = lib.getFunction("GetSpout").invokePointer(new Object[0]);
                if (spout == null) {
                    failure = "GetSpout gave nothing";
                    return false;
                }
            }
            call(SET_SENDER_NAME, Void.class, SENDER);
            open = true;
            failure = null;
            BSPServerUtilities.LOGGER.info("Streamer Mode: Spout {} loaded; sending as \"{}\"", VERSION, SENDER);
            return true;
        } catch (Throwable e) {
            failure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            BSPServerUtilities.LOGGER.error("Streamer Mode: Spout2 could not start", e);
            return false;
        }
    }

    /** Sends this frame's stream picture; false when Spout refused it (Streamer Mode then falls back to the stream window). */
    static boolean send(@Nullable RenderTarget picture) {
        if (!open || picture == null || spout == null) {
            return open;
        }
        int[] saved = saveGl();
        try {
            // SendTexture(TextureID, TextureTarget, width, height, bInvert, HostFBO): the framebuffer texture is upside down
            // for Spout's DirectX side, so Spout flips it; HostFBO is the framebuffer to leave bound afterwards
            int host = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
            boolean ok = callBool(SEND_TEXTURE, picture.getColorTextureId(), GL11.GL_TEXTURE_2D, picture.width, picture.height, 1, host);
            if (!ok && !callBool(IS_INITIALIZED)) {
                failure = "Spout refused the picture";
                return false;
            }
            return true;
        } finally {
            restoreGl(saved);
        }
    }

    /** Stops sending: OBS's source goes empty. The Spout object stays for the next switch-on. */
    static void close() {
        if (open && spout != null) {
            try {
                call(RELEASE_SENDER, Void.class, 0);
            } catch (Throwable e) {
                BSPServerUtilities.LOGGER.warn("Streamer Mode: Spout's ReleaseSender failed: {}", e.toString());
            }
        }
        open = false;
    }

    // ------------------------------------------------------------------ the native side

    /** The library in the game folder, copied from the jar when missing or not the shipped file. */
    private static Path extract() throws IOException {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("bsp_server_utilities").resolve("natives");
        Path dll = dir.resolve("SpoutLibrary-" + VERSION + ".dll");
        if (Files.isRegularFile(dll) && SHA256.equals(sha256(Files.readAllBytes(dll)))) {
            return dll;
        }
        byte[] bytes;
        try (InputStream in = SpoutOutput.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("SpoutLibrary.dll is missing from the jar");
            }
            bytes = in.readAllBytes();
        }
        if (!SHA256.equals(sha256(bytes))) {
            throw new IOException("SpoutLibrary.dll in the jar is not the shipped file");
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "SpoutLibrary", ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, dll, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return dll;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Calls slot {@code slot} of the Spout object's method table, the object itself first (x64: one calling convention). */
    private static Object call(int slot, Class<?> returns, Object... args) {
        Pointer table = spout.getPointer(0);
        Function f = Function.getFunction(table.getPointer((long) slot * com.sun.jna.Native.POINTER_SIZE));
        Object[] all = new Object[args.length + 1];
        all[0] = spout;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invoke(returns, all);
    }

    /** A C++ {@code bool} comes back in the lowest byte only. */
    private static boolean callBool(int slot, Object... args) {
        Object r = call(slot, Integer.class, args);
        return r instanceof Integer i && (i & 0xFF) != 0;
    }

    // ------------------------------------------------------------------ Minecraft's GL state around Spout

    private static int[] saveGl() {
        int unit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        return new int[]{
                unit,
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING),
                GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING),
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),
                GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),
                vp[0], vp[1], vp[2], vp[3]};
    }

    private static void restoreGl(int[] s) {
        GL13.glActiveTexture(s[0]);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, s[1]);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, s[2]);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, s[3]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, s[4]);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s[5]);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, s[6]);
        GL20.glUseProgram(s[7]);
        GL30.glBindVertexArray(s[8]);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, s[9]);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, s[10]);
        GL11.glViewport(s[11], s[12], s[13], s[14]);
    }
}
