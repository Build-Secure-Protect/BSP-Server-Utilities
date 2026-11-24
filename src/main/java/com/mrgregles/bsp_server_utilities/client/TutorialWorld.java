package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.TutorialGuidePacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Tutorials in the world (SU-141, SU-144; owner decisions D731, D732, D737; looks AZ A and AY A). For the player on a
 * tutorial: translucent gold dashes about a block above the walked line, faint through walls and fading with distance,
 * and at the end a translucent sign on a thin post naming the next stop. For an admin drawing a path or an area: the
 * yellow line with a mark at each point, the spheres of the two points, and the area's violet box. Camera-facing quads in
 * the text-background render types, which shader packs keep (as BSP-Core's X-ray does).
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class TutorialWorld {
    static final float GOLD_R = 1f, GOLD_G = 0.824f, GOLD_B = 0.227f;
    private static final double LIFT = 0.9, DASH = 0.6, GAP = 0.6, HALF_WIDTH = 0.06, MAX_DISTANCE = 128;
    private static final int LIGHT = 0xF000F0;

    private TutorialWorld() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        String dim = mc.level.dimension().location().toString();
        TutorialGuidePacket guide = ClientTutorial.guide();
        boolean drawGuide = guide != null && guide.dimension().equals(dim);
        boolean drawAdmin = ClientTutorialAdmin.drawing(dim);
        if (!drawGuide && !drawAdmin) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        if (drawGuide) {
            List<Vec3> line = new ArrayList<>();
            for (BlockPos b : guide.path()) {
                line.add(new Vec3(b.getX() + 0.5, b.getY() + LIFT, b.getZ() + 0.5));
            }
            BlockPos t = guide.target();
            line.add(new Vec3(t.getX() + 0.5, t.getY() + LIFT, t.getZ() + 0.5));
            if (line.size() > 1) {
                dashes(pose, buffers, line, cam, GOLD_R, GOLD_G, GOLD_B);
            }
            sign(pose, buffers, camera, mc.font, new Vec3(t.getX() + 0.5, t.getY(), t.getZ() + 0.5), guide.title(), Tr.t("tutorial.next_stop"), cam);
        }
        if (drawAdmin) {
            ClientTutorialAdmin.renderWorld(pose, buffers, camera, mc.font, cam);
        }
        buffers.endBatch();
        pose.popPose();
    }

    private static float fade(double distance) {
        return (float) Math.max(0.3, Math.min(1, 1.15 - distance / 55));
    }

    /** Gold dashes along a line: each a short ribbon turned to the camera, drawn faint through walls and solid in view. */
    static void dashes(PoseStack pose, MultiBufferSource buffers, List<Vec3> line, Vec3 cam, float r, float g, float b) {
        Matrix4f m = pose.last().pose();
        VertexConsumer through = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
        List<Vec3[]> parts = new ArrayList<>();
        double carry = 0;
        for (int i = 0; i < line.size() - 1; i++) {
            Vec3 a = line.get(i), c = line.get(i + 1);
            double len = a.distanceTo(c);
            if (len < 1e-3) {
                continue;
            }
            Vec3 dir = c.subtract(a).scale(1 / len);
            double s = carry;
            while (s < len) {
                double e = Math.min(len, s + DASH);
                parts.add(new Vec3[]{a.add(dir.scale(s)), a.add(dir.scale(e))});
                s += DASH + GAP;
            }
            carry = s - len;
        }
        for (Vec3[] p : parts) {
            double d = p[0].distanceTo(cam);
            if (d > MAX_DISTANCE) {
                continue;
            }
            ribbon(m, through, p[0], p[1], cam, HALF_WIDTH, r, g, b, 0.25f * fade(d));
        }
        VertexConsumer solid = buffers.getBuffer(RenderType.textBackground());
        for (Vec3[] p : parts) {
            double d = p[0].distanceTo(cam);
            if (d > MAX_DISTANCE) {
                continue;
            }
            ribbon(m, solid, p[0], p[1], cam, HALF_WIDTH, r, g, b, 0.85f * fade(d));
        }
    }

    /** A flat strip from a to b, turned to face the camera, both sides drawn. */
    static void ribbon(Matrix4f m, VertexConsumer v, Vec3 a, Vec3 b, Vec3 cam, double half, float r, float g, float bl, float alpha) {
        Vec3 dir = b.subtract(a), mid = a.add(b).scale(0.5);
        Vec3 side = dir.cross(cam.subtract(mid));
        if (side.lengthSqr() < 1e-9) {
            return;
        }
        side = side.normalize().scale(half);
        Vec3 p1 = a.add(side), p2 = a.subtract(side), p3 = b.subtract(side), p4 = b.add(side);
        quad(m, v, p1, p2, p3, p4, r, g, bl, alpha);
        quad(m, v, p4, p3, p2, p1, r, g, bl, alpha);
    }

    static void quad(Matrix4f m, VertexConsumer v, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, float r, float g, float b, float a) {
        for (Vec3 p : new Vec3[]{p1, p2, p3, p4}) {
            v.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).uv2(LIGHT).endVertex();
        }
    }

    /** The sign at the end of the path: a thin post from the ground and a translucent board facing the player, gold framed. */
    static void sign(PoseStack pose, MultiBufferSource buffers, Camera camera, Font font, Vec3 foot, String title, String under, Vec3 cam) {
        double d = foot.distanceTo(cam);
        if (d > MAX_DISTANCE) {
            return;
        }
        float a = fade(d);
        Vec3 top = foot.add(0, 2.6, 0);
        ribbon(pose.last().pose(), buffers.getBuffer(RenderType.textBackgroundSeeThrough()), foot, top, cam, 0.03, 1f, 1f, 1f, 0.55f * a);
        pose.pushPose();
        pose.translate(top.x, top.y + 0.75, top.z);
        pose.mulPose(camera.rotation());
        float s = 0.025f;
        pose.scale(-s, -s, s);
        Matrix4f m = pose.last().pose();
        int tw = Math.max(font.width(title), font.width(under) / 2) + 16;
        float w = Math.max(80, tw), h = 30;
        VertexConsumer v = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
        rect(m, v, -w / 2, -h / 2, w / 2, h / 2, 0.063f, 0.082f, 0.11f, 0.55f * a);
        float t = 1;
        rect(m, v, -w / 2, -h / 2, w / 2, -h / 2 + t, GOLD_R, GOLD_G, GOLD_B, 0.85f * a);
        rect(m, v, -w / 2, h / 2 - t, w / 2, h / 2, GOLD_R, GOLD_G, GOLD_B, 0.85f * a);
        rect(m, v, -w / 2, -h / 2, -w / 2 + t, h / 2, GOLD_R, GOLD_G, GOLD_B, 0.85f * a);
        rect(m, v, w / 2 - t, -h / 2, w / 2, h / 2, GOLD_R, GOLD_G, GOLD_B, 0.85f * a);
        int alpha = Math.round(255 * a) << 24;
        font.drawInBatch(title, -font.width(title) / 2f, -h / 2 + 6, alpha | 0xFFD23A, false, m, buffers, Font.DisplayMode.SEE_THROUGH, 0, LIGHT);
        pose.pushPose();
        pose.scale(0.5f, 0.5f, 0.5f);
        font.drawInBatch(under, -font.width(under) / 2f, (-h / 2 + 18) * 2, alpha | 0xE6EAF2, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0, LIGHT);
        pose.popPose();
        pose.popPose();
    }

    static void rect(Matrix4f m, VertexConsumer v, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        v.vertex(m, x1, y1, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
        v.vertex(m, x1, y2, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
        v.vertex(m, x2, y2, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
        v.vertex(m, x2, y1, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
        v.vertex(m, x2, y1, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
        v.vertex(m, x2, y2, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
        v.vertex(m, x1, y2, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
        v.vertex(m, x1, y1, 0).color(r, g, b, a).uv2(LIGHT).endVertex();
    }
}
