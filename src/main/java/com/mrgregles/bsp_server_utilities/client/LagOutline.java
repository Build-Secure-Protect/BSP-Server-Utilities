package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.lag.LagResult;
import com.mrgregles.bsp_server_utilities.lag.LagView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;

/**
 * OUTLINE TOP 10 from the LAG tab's PROFILER (SU-152; D751; frame R6 of {@code tools/preview/lag_refined.html}): a red
 * box round each of a sample's slowest blocks, seen through walls, with a small label of the block and its ms per tick
 * over it, and a line on the HUD with how many and the time left. Only the editor who pressed it sees them, only for
 * blocks in the world they are in on the server that ran the sample (the screen offers it only there), for
 * {@value LagView#OUTLINE_MS} ms; pressing it again, leaving the server, joining one, going to another world or the time
 * running out clears it. Drawn as camera-facing ribbons in the see-through text-background render type, as the tutorials'
 * boxes are, through a buffer of its own so only its own drawing is flushed. The HUD line steps aside for the F3 screen.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class LagOutline {
    private static final float R = 1f, G = 0.42f, B = 0.36f;
    private static final double EDGE = 0.03, MAX_DISTANCE = 256, LABEL_DISTANCE = 96;
    private static final int LIGHT = 0xF000F0;

    private static List<LagResult.Block> blocks = List.of();
    private static long until, key;
    private static String server = "", world = "";
    /** Its own buffers: flushing them leaves the game's batches alone. */
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(256));

    private LagOutline() {
    }

    /**
     * Shows a result's blocks for {@value LagView#OUTLINE_MS} ms; false, and nothing shown, when none of them is in the
     * viewer's world (an outline with no box in it would only say "0 blocks").
     */
    static boolean show(String fromServer, LagResult r) {
        if (LagView.blocksIn(r.blocks(), dim(Minecraft.getInstance())).isEmpty()) {
            return false;
        }
        blocks = r.blocks();
        server = fromServer;
        key = r.startedAtMs();
        until = System.currentTimeMillis() + LagView.OUTLINE_MS;
        world = dim(Minecraft.getInstance());
        return true;
    }

    static void clear() {
        blocks = List.of();
        server = "";
        world = "";
        until = 0;
        key = 0;
    }

    /** Whether the outline is up: the time not run out, and the viewer still in the world it was shown in. */
    static boolean on() {
        if (until != 0 && System.currentTimeMillis() >= until) {
            clear();
        }
        if (until != 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && !world.isEmpty() && !dim(mc).equals(world)) {
                clear(); // another world: the outline was of the one left
            }
        }
        return until != 0;
    }

    /** Whether the outline is up for this result of this server. */
    static boolean showing(String fromServer, LagResult r) {
        return on() && server.equals(fromServer) && key == r.startedAtMs();
    }

    static long leftMs() {
        return on() ? Math.max(0, until - System.currentTimeMillis()) : 0;
    }

    private static String dim(Minecraft mc) {
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !on()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        List<LagResult.Block> here = LagView.blocksIn(blocks, dim(mc));
        if (here.isEmpty()) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = BUFFERS;
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();
        VertexConsumer v = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
        for (LagResult.Block b : here) {
            Vec3 mid = new Vec3(b.x() + 0.5, b.y() + 0.5, b.z() + 0.5);
            if (mid.distanceTo(cam) <= MAX_DISTANCE) {
                box(m, v, b.x(), b.y(), b.z(), cam);
            }
        }
        for (LagResult.Block b : here) {
            Vec3 top = new Vec3(b.x() + 0.5, b.y() + 1.35, b.z() + 0.5);
            if (top.distanceTo(cam) <= LABEL_DISTANCE) {
                label(pose, buffers, camera, mc.font, top, Tr.t("lag.outline.label", shortName(b.label()), LagView.ms(b.ms())));
            }
        }
        buffers.endBatch();
        pose.popPose();
    }

    /** A block's id without the game's own namespace. */
    static String shortName(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** A block's box, a little larger than the block: faint red faces and solid red edges. */
    private static void box(Matrix4f m, VertexConsumer v, int x, int y, int z, Vec3 cam) {
        double e = 0.02, x0 = x - e, y0 = y - e, z0 = z - e, x1 = x + 1 + e, y1 = y + 1 + e, z1 = z + 1 + e;
        Vec3[] c = {new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0), new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)};
        int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0}};
        for (int[] f : faces) {
            TutorialWorld.quad(m, v, c[f[0]], c[f[1]], c[f[2]], c[f[3]], R, G, B, 0.07f);
            TutorialWorld.quad(m, v, c[f[3]], c[f[2]], c[f[1]], c[f[0]], R, G, B, 0.07f);
        }
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] ed : edges) {
            TutorialWorld.ribbon(m, v, c[ed[0]], c[ed[1]], cam, EDGE, R, G, B, 0.95f);
        }
    }

    /** The label over a box, turned to the camera, on a dark backing, seen through walls. */
    private static void label(PoseStack pose, MultiBufferSource buffers, Camera camera, Font font, Vec3 at, String text) {
        String t = Ui.fit(font, text, 240, 1f);
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        pose.mulPose(camera.rotation());
        pose.scale(-0.02f, -0.02f, 0.02f);
        font.drawInBatch(t, -font.width(t) / 2f, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0x8C000000, LIGHT);
        pose.popPose();
    }

    /** The line on the HUD: how many blocks in this world and the time left. */
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!on() || mc.player == null || mc.options.hideGui || mc.options.renderDebug) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int n = LagView.blocksIn(blocks, dim(mc)).size();
        String s = Ui.fit(font, Tr.t("lag.outline.hud", n, LagView.clock(leftMs())), g.guiWidth() - 16, 0.75f);
        int w = Ui.width(font, s, 0.75f);
        g.fill(4, 4, 4 + w + 8, 4 + 12, 0x80000000);
        Ui.text(g, font, s, 8, 7, 0xFFFFFFFF, 0.75f);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        clear(); // another server through the proxy
    }
}
