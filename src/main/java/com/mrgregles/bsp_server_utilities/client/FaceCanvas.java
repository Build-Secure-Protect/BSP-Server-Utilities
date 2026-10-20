package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * Something a panel face is drawn on, in panel pixels (64 a block, y down): the panel in the world (full bright) or the
 * preview on the settings screen. Faces with live data (staff online, the action log) draw once for both.
 */
interface FaceCanvas {
    Font font();

    void rect(float x0, float y0, float x1, float y1, int argb);

    /** Text whose glyphs are {@code px} panel pixels tall, top-left at (x, y). */
    void text(String s, float x, float y, float px, int argb);

    default float width(String s, float px) {
        return font().width(s) * px / 8f;
    }

    /** The panel in the world: the renderer's pose already maps panel pixels. */
    static FaceCanvas world(PoseStack pose, MultiBufferSource buffers, Font font) {
        return new FaceCanvas() {
            @Override
            public Font font() {
                return font;
            }

            @Override
            public void rect(float x0, float y0, float x1, float y1, int argb) {
                VertexConsumer vc = buffers.getBuffer(RenderType.textBackground());
                Matrix4f m = pose.last().pose();
                int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                float[][] quad = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}};
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < 4; i++) {
                        float[] p = quad[pass == 0 ? i : 3 - i];
                        vc.vertex(m, p[0], p[1], 0.2f).color(r, g, b, a).uv2(LightTexture.FULL_BRIGHT).endVertex();
                    }
                }
            }

            @Override
            public void text(String s, float x, float y, float px, int argb) {
                pose.pushPose();
                pose.translate(x, y, 0.4f);
                float k = px / 8f;
                pose.scale(k, k, 1f);
                font.drawInBatch(s, 0, 0, argb, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
                pose.popPose();
            }
        };
    }

    /** The preview: panel pixels scaled by {@code k} and moved to (ox, oy) on the screen. */
    static FaceCanvas gui(GuiGraphics g, Font font, float ox, float oy, float k) {
        return new FaceCanvas() {
            @Override
            public Font font() {
                return font;
            }

            @Override
            public void rect(float x0, float y0, float x1, float y1, int argb) {
                g.fill(Math.round(ox + x0 * k), Math.round(oy + y0 * k), Math.round(ox + x1 * k), Math.round(oy + y1 * k), argb);
            }

            @Override
            public void text(String s, float x, float y, float px, int argb) {
                g.pose().pushPose();
                g.pose().translate(ox + x * k, oy + y * k, 0);
                float scale = px / 8f * k;
                g.pose().scale(scale, scale, 1);
                g.drawString(font, s, 0, 0, argb, false);
                g.pose().popPose();
            }
        };
    }
}
