package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Set;

/**
 * Streamer Mode (owner decisions D700 to D714): a second, clean picture of the game for OBS, with what the streamer chose
 * left out, while the player's own window shows everything.
 *
 * <p>Each frame, after the world and before the HUD, {@link #streamPass} copies the world into the stream picture and draws
 * the HUD, the open menu and the toasts into it, leaving out the hidden items; then Minecraft draws them again for the
 * player, so whatever a menu keeps from drawing (hover, hit boxes) is the player's (D710). After the frame,
 * {@link #present} shows the picture in the stream window ({@link StreamWindow}). Hidden HUD parts are cancelled in
 * {@code RenderGuiOverlayEvent.Pre} during the stream pass only; JourneyMap's minimap through its adapter mixin; a hidden
 * menu becomes the game behind it with the "Menu hidden" card.
 *
 * <p>Switched in its menu ({@link StreamerScreen}, opened by the eye key or {@code /streamer}), with a key (unbound until
 * set) or {@code /streamer on|off}. What is hidden, the output and the frame rate are the client settings' {@code [streamer]}
 * section, saved as each is changed (D712, D714).
 */
public final class StreamerMode {
    public static final KeyMapping KEY = new KeyMapping("key.bsp_server_utilities.streamer", KeyConflictContext.UNIVERSAL,
            InputConstants.UNKNOWN, "key.categories.bsp_server_utilities");
    private static final long STATS_NS = 60_000_000_000L;
    private static final int CARD = 0xF010151C, EDGE = 0xFF2A2F3A, MUTED = 0x9AA3B5, DIMTXT = 0x6B7180;

    private static boolean on, drawing, ready, failed;
    @Nullable
    private static TextureTarget target;
    @Nullable
    private static Set<String> hidden; // read from the settings when first needed (D712)
    private static int fps = -1; // 0: every frame (Match); -1 until read
    private static long lastPass, passNs, presentNs, statsSince;
    private static int frames;

    private StreamerMode() {
    }

    /** From the client setup: the overlay filter, the key, {@code /streamer}. */
    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, StreamerMode::onOverlay);
        MinecraftForge.EVENT_BUS.addListener(StreamerMode::onTick);
        MinecraftForge.EVENT_BUS.addListener(StreamerMode::onCommands);
    }

    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(KEY);
    }

    public static boolean on() {
        return on;
    }

    /** Whether the stream pass is drawing now and the streamer hid this item: for adapters (JourneyMap's minimap). */
    public static boolean hidingNow(String id) {
        return drawing && StreamerRules.hides(hidden(), id);
    }

    // ------------------------------------------------------------------ the settings (the menu changes them)

    public static Set<String> hidden() {
        if (hidden == null) {
            Set<String> saved = com.mrgregles.bsp_server_utilities.streamer.StreamerCatalog.migrate(
                    com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig.streamHidden());
            Set<String> applied = com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig.streamApplied();
            var next = com.mrgregles.bsp_server_utilities.streamer.StreamerCatalog.applyDefaults(saved, applied);
            hidden = Set.copyOf(next.get(0));
            if (!next.get(1).equals(applied) || !hidden.equals(saved)) {
                com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig.setStreamApplied(next.get(1));
                save();
            }
        }
        return hidden;
    }

    /** Whether chat is hidden or filtered in the stream: chat's suggestions and hover text stay out of it then. */
    public static boolean chatFiltered() {
        return isHidden("minecraft:chat_panel") || isHidden("bsp_server_utilities:chat_coordinates");
    }

    /** Whether the chat box's text stays out of the stream ({@link StreamerRules#hideTyping}). */
    public static boolean hideTyping(String typed) {
        return StreamerRules.hideTyping(isHidden("minecraft:chat_panel"), isHidden("bsp_server_utilities:chat_coordinates"), typed);
    }

    /** Items another mod hides itself: it asked through {@code api/StreamerMode} at least once this session. */
    private static final Set<String> HANDLED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** {@code api/StreamerMode.hidesNow}: remembers that the asking mod handles this item, then answers. */
    public static boolean askedHidingNow(String id) {
        HANDLED.add(id);
        return hidingNow(id);
    }

    /** Whether a mod has said it hides this item itself (the menu then drops its gold line). */
    public static boolean handled(String id) {
        return HANDLED.contains(id);
    }

    public static boolean isHidden(String id) {
        return hidden().contains(id);
    }

    public static void setHidden(java.util.Collection<String> ids, boolean hide) {
        java.util.Set<String> next = new java.util.HashSet<>(hidden());
        if (hide) {
            next.addAll(ids);
        } else {
            next.removeAll(ids);
        }
        hidden = Set.copyOf(next);
        save();
    }

    public static int fps() {
        if (fps < 0) {
            fps = com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig.streamFps();
        }
        return fps;
    }

    public static void setFps(int f) {
        fps = f;
        lastPass = 0;
        save();
    }

    /** "window" or "spout2"; Spout2 is chosen only on Windows (D701). */
    public static String output() {
        return com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig.streamOutput();
    }

    /** Saves the output; while on, swaps to it at once. */
    public static void setOutput(String o) {
        if (o.equals(output())) {
            return;
        }
        com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig.setStream(o, fps(), hidden());
        if (on) {
            closeOutput();
            if (!openOutput()) {
                set(false); // the whole off path: picture freed, the player told (audit, SU-135)
            }
        }
    }

    private static void save() {
        com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig.setStream(output(), fps(), hidden());
    }

    /** The stream picture for the menu's live preview: {texture id, width, height}, or null while there is none. */
    @Nullable
    public static int[] preview() {
        return on && target != null ? new int[]{target.getColorTextureId(), target.width, target.height} : null;
    }

    /** The key's name for the menu ("not set" while unbound). */
    public static Component keyName() {
        return KEY.isUnbound() ? Component.literal(Tr.t("streamer.key_unset")) : KEY.getTranslatedKeyMessage();
    }

    /** Whether the stream pass is drawing now. */
    public static boolean drawingStream() {
        return drawing;
    }

    public static void toggle() {
        set(!on);
    }

    public static void set(boolean want) {
        if (want == on) {
            return;
        }
        if (want) {
            if (!openOutput()) {
                return;
            }
            on = true;
            failed = false;
            lastPass = 0;
            resetStats();
            say(Component.translatable(spout ? "message.bsp_server_utilities.streamer.on_spout" : "message.bsp_server_utilities.streamer.on")
                    .withStyle(ChatFormatting.AQUA));
        } else {
            on = false;
            ready = false;
            closeOutput();
            if (target != null) {
                target.destroyBuffers();
                target = null;
            }
            say(Component.translatable("message.bsp_server_utilities.streamer.off").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Whether this session's stream goes out over Spout2 (else the stream window). */
    private static boolean spout;

    /** Whether the stream goes out over Spout2 now: for the menu's line. */
    public static boolean sendingSpout() {
        return on && spout;
    }

    /**
     * Opens the chosen output (D701, D717): Spout2 when chosen and this is 64-bit Windows, else the stream window. When
     * Spout2 cannot start, the stream window opens instead and the player is told why.
     */
    private static boolean openOutput() {
        spout = false;
        if ("spout2".equals(output()) && SpoutOutput.available()) {
            if (SpoutOutput.open()) {
                spout = true;
                return true;
            }
            say(Component.translatable("message.bsp_server_utilities.streamer.spout_failed", String.valueOf(SpoutOutput.failure())).withStyle(ChatFormatting.GOLD));
        }
        if (!StreamWindow.open()) {
            say(Component.translatable("message.bsp_server_utilities.streamer.no_window").withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    private static void closeOutput() {
        SpoutOutput.close();
        StreamWindow.close();
        spout = false;
    }

    /** In chat in game; on the title screen, where there is no chat, as a popup in the corner (the first test's lesson). */
    private static void say(Component c) {
        Minecraft mc = Minecraft.getInstance();
        BSPServerUtilities.LOGGER.info("Streamer Mode: {}", c.getString());
        if (mc.player != null) {
            mc.player.displayClientMessage(c, false);
        } else {
            mc.getToasts().addToast(net.minecraft.client.gui.components.toasts.SystemToast.multiline(mc,
                    net.minecraft.client.gui.components.toasts.SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.literal(Tr.t("streamer.name")), Component.literal(c.getString())));
        }
    }

    // ------------------------------------------------------------------ the frame

    /** From the game renderer, after the world and before the HUD: the stream picture (D710). */
    public static void streamPass(float partialTick, boolean renderLevel) {
        if (!on || failed) {
            return;
        }
        long now = System.nanoTime();
        if (!StreamerRules.due(now, lastPass, fps())) {
            return;
        }
        lastPass = now;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        TextureTarget t = target(main);
        Throwable error = null;
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, t.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, t.width, t.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        t.bindWrite(true);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        drawing = true;
        try {
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            // the HUD exactly when Minecraft draws it: F1 hides it unless a menu is open (audit, SU-135)
            if (renderLevel && mc.level != null && (!mc.options.hideGui || mc.screen != null)) {
                mc.gui.render(g, partialTick);
                RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            }
            var window = mc.getWindow();
            int mx = (int) (mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth());
            int my = (int) (mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
            // no frame time here: menus that move by it (the title panorama, the credits) would move twice a frame;
            // the player's pass right after gives it (audit, SU-135)
            if (mc.getOverlay() != null) {
                mc.getOverlay().render(g, mx, my, 0f);
            } else if (mc.screen != null) {
                if (StreamerRules.menuHidden(hidden(), HANDLED, mc.screen.getClass().getName())) {
                    hiddenCard(g, mc.screen);
                } else {
                    ForgeHooksClient.drawScreen(mc.screen, g, mx, my, 0f);
                }
            }
            mc.getToasts().render(g);
            g.flush();
            // a solid picture: the world's copy keeps the alpha Minecraft clears with, which Spout would pass on as holes
            RenderSystem.colorMask(false, false, false, true);
            RenderSystem.clearColor(0f, 0f, 0f, 1f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.colorMask(true, true, true, true);
            ready = true;
        } catch (Throwable e) {
            error = e;
        } finally {
            drawing = false;
            if (mc.gui.getChat() instanceof StreamChatAccess chat) {
                chat.bspSu$restoreLines(); // chat's own lines back even if drawing stopped half way
            }
            main.bindWrite(true);
        }
        if (error != null) {
            fail(error); // after chat is whole again and the stream pass is over (audit, SU-135)
        }
        passNs += System.nanoTime() - now;
    }

    /** From the game renderer, when the frame is drawn: the stream window shows the picture. */
    public static void present() {
        if (!on || !ready) {
            return;
        }
        ready = false;
        long t0 = System.nanoTime();
        try {
            if (spout) {
                if (!SpoutOutput.send(target)) { // Spout refused: carry on in the stream window
                    say(Component.translatable("message.bsp_server_utilities.streamer.spout_failed", String.valueOf(SpoutOutput.failure())).withStyle(ChatFormatting.GOLD));
                    SpoutOutput.close();
                    spout = false;
                    if (!StreamWindow.open()) {
                        set(false);
                        return;
                    }
                }
            } else if (!StreamWindow.present(target)) {
                set(false); // the streamer closed the stream window
                return;
            }
        } catch (Throwable e) {
            fail(e);
            return;
        }
        presentNs += System.nanoTime() - t0;
        frames++;
        if (t0 - statsSince >= STATS_NS && frames > 0) {
            BSPServerUtilities.LOGGER.info("Streamer Mode: stream picture {} ms, stream window {} ms a frame on average ({} frames, {} x {})",
                    String.format("%.2f", passNs / 1e6 / frames), String.format("%.2f", presentNs / 1e6 / frames), frames,
                    target == null ? 0 : target.width, target == null ? 0 : target.height);
            resetStats();
        }
    }

    private static void resetStats() {
        passNs = presentNs = 0;
        frames = 0;
        statsSince = System.nanoTime();
    }

    private static void fail(Throwable e) {
        failed = true;
        BSPServerUtilities.LOGGER.error("Streamer Mode stopped: the stream picture could not be drawn", e);
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> { // at the next task run, never inside a frame's drawing (audit, SU-135)
            set(false);
            say(Component.translatable("message.bsp_server_utilities.streamer.failed").withStyle(ChatFormatting.RED));
        });
    }

    private static TextureTarget target(RenderTarget main) {
        int w = main.width, h = main.height;
        if (target == null) {
            target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            target.setClearColor(0, 0, 0, 1);
            target.setFilterMode(GL11.GL_LINEAR); // the menu's preview shows it small
        }
        if (main.isStencilEnabled() && !target.isStencilEnabled()) {
            target.enableStencil(); // as the game's: HUD or menu drawing that uses the stencil works in the stream too (audit)
        }
        if (target.width != w || target.height != h) {
            target.resize(w, h, Minecraft.ON_OSX);
        }
        return target;
    }

    /** A hidden menu in the stream: the game behind it, darkened, and a small card (refined page R5). */
    private static void hiddenCard(GuiGraphics g, Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        int w = screen.width, h = screen.height;
        g.fill(0, 0, w, h, 0x59050A0A);
        String title = Tr.t("streamer.hidden"), sub = Tr.t("streamer.name");
        int cw = Math.max(mc.font.width(title), mc.font.width(sub)) + 24, ch = 30;
        int x = (w - cw) / 2, y = (h - ch) / 2;
        g.fill(x - 1, y - 1, x + cw + 1, y + ch + 1, EDGE);
        g.fill(x, y, x + cw, y + ch, CARD);
        g.drawCenteredString(mc.font, title, w / 2, y + 6, MUTED);
        g.drawCenteredString(mc.font, sub, w / 2, y + 18, DIMTXT);
    }

    // ------------------------------------------------------------------ events

    private static void onOverlay(RenderGuiOverlayEvent.Pre event) {
        if (drawing && StreamerRules.hides(hidden(), event.getOverlay().id().toString())) {
            event.setCanceled(true);
        }
    }

    private static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (KEY.consumeClick()) {
                toggle();
            }
        }
    }

    private static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("streamer")
                .executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.tell(() -> mc.setScreen(new StreamerScreen(null))); // after the chat screen has closed
                    return 1;
                })
                .then(Commands.literal("on").executes(c -> {
                    set(true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(c -> {
                    set(false);
                    return 1;
                })));
    }
}
