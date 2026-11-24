package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;

/**
 * The stream window (owner decisions D701, D714 R2 and R5): a second window titled "BSP Stream" that only shows Streamer
 * Mode's picture, for OBS's Window Capture (Windows) or macOS Screen Capture in window mode. Its OpenGL context shares
 * Minecraft's, so it reads the picture's texture where it is: each frame it makes its own context current, copies the
 * texture to its own screen (fitted whole, black bars where the shapes differ) with {@code glBlitFramebuffer}, shows it
 * without waiting for the screen's refresh, and gives the context back. Only raw OpenGL calls run in its context, so
 * Minecraft's cached GL state is never touched. It takes no input. Opening, drawing and closing all happen on the render
 * thread, which on macOS is the main thread GLFW needs.
 */
final class StreamWindow {
    static final String TITLE = "BSP Stream";

    private static long window;
    @Nullable
    private static GLCapabilities caps;
    /** A framebuffer of the stream window's own context reading the picture's texture (framebuffers are not shared). */
    private static int readFbo;
    private static int readTexture = -1;
    private static final int[] FW = new int[1], FH = new int[1];

    private StreamWindow() {
    }

    static boolean isOpen() {
        return window != 0;
    }

    /** Opens the window at the game window's size; false when GLFW could not make it. */
    static boolean open() {
        if (window != 0) {
            return true;
        }
        Window game = Minecraft.getInstance().getWindow();
        long ctx = GLFW.glfwGetCurrentContext();
        GLCapabilities gameCaps = GL.getCapabilities();
        try {
            GLFW.glfwDefaultWindowHints();
            // exactly the kind of context the game's window has, so the two can share: Forge's early loading screen makes
            // that window with the newest version it can get (OpenGL 4.1 core on a Mac), and macOS refuses to share a
            // context asked for as 3.2 with it ("NSGL: Failed to create OpenGL context", seen in the first test)
            long main = game.getWindow();
            int major = GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CONTEXT_VERSION_MAJOR), minor = GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CONTEXT_VERSION_MINOR);
            int profile = GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_OPENGL_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CLIENT_API));
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CONTEXT_CREATION_API));
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, major > 0 ? major : 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, major > 0 ? minor : 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, profile != 0 ? profile : GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_OPENGL_FORWARD_COMPAT));
            BSPServerUtilities.LOGGER.info("Streamer Mode: opening the stream window with OpenGL {}.{} (profile {}), as the game's window", major, minor, profile);
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            long w = GLFW.glfwCreateWindow(Math.max(320, game.getScreenWidth()), Math.max(180, game.getScreenHeight()), TITLE, 0L, game.getWindow());
            GLFW.glfwDefaultWindowHints();
            if (w == 0L) {
                BSPServerUtilities.LOGGER.error("Streamer Mode: the stream window could not be made (GLFW)");
                return false;
            }
            GLFW.glfwMakeContextCurrent(w);
            caps = GL.createCapabilities();
            GLFW.glfwSwapInterval(0); // never wait for the screen's refresh: the game's frame rate stays the game's
            window = w;
            readFbo = 0;
            readTexture = -1;
            return true;
        } catch (Throwable e) {
            BSPServerUtilities.LOGGER.error("Streamer Mode: the stream window could not be made", e);
            return false;
        } finally {
            GLFW.glfwMakeContextCurrent(ctx);
            GL.setCapabilities(gameCaps);
        }
    }

    /** Shows the picture; false when the streamer closed the window (it is then gone). */
    static boolean present(@Nullable RenderTarget picture) {
        if (window == 0L) {
            return false;
        }
        if (GLFW.glfwWindowShouldClose(window)) {
            close();
            return false;
        }
        if (picture == null) {
            return true;
        }
        GL11.glFlush(); // the picture drawn in the game's context is finished before the other context reads it
        long ctx = GLFW.glfwGetCurrentContext();
        GLCapabilities gameCaps = GL.getCapabilities();
        GLFW.glfwMakeContextCurrent(window);
        GL.setCapabilities(caps);
        try {
            GLFW.glfwGetFramebufferSize(window, FW, FH);
            if (FW[0] <= 0 || FH[0] <= 0) {
                return true; // minimised: nothing to draw into
            }
            if (readFbo == 0) {
                readFbo = GL30.glGenFramebuffers();
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            // attached afresh every frame: after a resize the game may hand the new texture the old one's name, and a texture
            // changed in another context is only certain to be seen once attached again (audit, SU-135)
            int tex = picture.getColorTextureId();
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
            readTexture = tex;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL11.glViewport(0, 0, FW[0], FH[0]);
            GL11.glClearColor(0f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            int[] r = StreamerRules.fit(picture.width, picture.height, FW[0], FH[0]);
            GL30.glBlitFramebuffer(0, 0, picture.width, picture.height, r[0], r[1], r[0] + r[2], r[1] + r[3], GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            GLFW.glfwSwapBuffers(window);
            return true;
        } finally {
            GLFW.glfwMakeContextCurrent(ctx);
            GL.setCapabilities(gameCaps);
        }
    }

    /** Closes the window; its framebuffer goes with its context. */
    static void close() {
        if (window != 0L) {
            long w = window;
            window = 0L;
            caps = null;
            readFbo = 0;
            readTexture = -1;
            GLFW.glfwDestroyWindow(w);
        }
    }
}
