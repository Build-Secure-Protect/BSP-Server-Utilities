package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlock;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlockEntity;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import com.mrgregles.bsp_server_utilities.panel.PanelShape;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/**
 * Draws a Display Panel's face across the whole joined panel, from its bottom-left block only (owner decisions D41,
 * D42, D44, D45, D47): the background (none when clear, so the wall shows), then the text lines placed by
 * {@link PanelLayout}, or the picture (once its bytes have arrived) in its fit, all at full brightness. The transform is BSP-Core's Score Screen's: the same block shape, the
 * same bottom-left origin, 64 canvas pixels a block.
 */
public class PanelRenderer implements BlockEntityRenderer<DisplayPanelBlockEntity> {
    private static final int PX = PanelLayout.PX;
    private final Font font;

    public PanelRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @Override
    public void render(DisplayPanelBlockEntity panel, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Level level = panel.getLevel();
        if (level == null || com.mrgregles.bsp_server_utilities.setup.Switches.off(com.mrgregles.bsp_server_utilities.setup.Feature.PANELS)) {
            return; // no level yet, or panels turned off (dark, D150)
        }
        Direction facing = panel.getBlockState().getValue(DisplayPanelBlock.FACING);
        PanelShape.Rect rect = PanelShape.rectOf(level, panel.getBlockPos(), facing);
        if (!rect.origin().equals(panel.getBlockPos())) {
            return; // the bottom-left block draws the whole panel
        }
        PanelSettings s = panel.settings();
        float w = rect.width() * PX, h = rect.height() * PX;
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(-0.5, rect.height() - 0.5, -0.3125 + 0.004); // the top-left corner of the joined face, just in front of it
        pose.scale(1f / PX, -1f / PX, 1f / PX);
        if (s.background() != PanelSettings.CLEAR) {
            rect(pose, buffers, PanelLayout.INSET, PanelLayout.INSET, w - PanelLayout.INSET, h - PanelLayout.INSET, s.background());
        }
        if (s.mode() == PanelSettings.Mode.IMAGE && s.loaded().ok()) {
            ClientPanelImages.Texture tex = ClientPanelImages.texture(s.loaded().hash());
            if (tex != null) {
                float in = PanelLayout.INSET;
                PanelLayout.Fitted f = PanelLayout.fit(s.image().fit(), tex.width(), tex.height(), w - 2 * in, h - 2 * in);
                picture(pose, buffers, tex, in + f.x(), in + f.y(), in + f.x() + f.w(), in + f.y() + f.h(), f);
            }
        }
        if (s.mode() == PanelSettings.Mode.STAFF) {
            StaffFace.draw(FaceCanvas.world(pose, buffers, font), rect.width(), rect.height(), s.staff());
        }
        if (s.mode() == PanelSettings.Mode.LOG) {
            LogFace.draw(FaceCanvas.world(pose, buffers, font), rect.width(), rect.height(), s.log());
        }
        if (s.mode() == PanelSettings.Mode.TEXT) {
            for (PanelLayout.Placed p : PanelLayout.layout(s.lines(), rect.width(), rect.height(), font::width)) {
                pose.pushPose();
                pose.translate(p.left(), p.y(), 0.4f); // a little in front of the background (towards the viewer)
                pose.scale(p.scale(), p.scale(), 1f);
                font.drawInBatch(p.text(), 0, 0, 0xFF000000 | p.colour(), false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
                pose.popPose();
            }
        }
        pose.popPose();
    }

    /** A filled rectangle at full brightness, both windings so it shows whichever way the layer culls (as the Score Screen does). */
    private static void rect(PoseStack pose, MultiBufferSource buffers, float x0, float y0, float x1, float y1, int argb) {
        VertexConsumer vc = buffers.getBuffer(RenderType.textBackground());
        Matrix4f m = pose.last().pose();
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        float[][] quad = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}};
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 4; i++) {
                float[] p = quad[pass == 0 ? i : 3 - i];
                vc.vertex(m, p[0], p[1], 0).color(r, g, b, a).uv2(LightTexture.FULL_BRIGHT).endVertex();
            }
        }
    }

    /** The picture as a textured rectangle at full brightness, slightly in front of the background, both windings. */
    private static void picture(PoseStack pose, MultiBufferSource buffers, ClientPanelImages.Texture tex, float x0, float y0, float x1, float y1, PanelLayout.Fitted f) {
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex.id()));
        Matrix4f m = pose.last().pose();
        float z = 0.2f;
        float[][] quad = {{x0, y0, f.u0(), f.v0()}, {x0, y1, f.u0(), f.v1()}, {x1, y1, f.u1(), f.v1()}, {x1, y0, f.u1(), f.v0()}};
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 4; i++) {
                float[] p = quad[pass == 0 ? i : 3 - i];
                vc.vertex(m, p[0], p[1], z).color(255, 255, 255, 255).uv(p[2], p[3]).uv2(LightTexture.FULL_BRIGHT).endVertex();
            }
        }
    }

    @Override
    public boolean shouldRenderOffScreen(DisplayPanelBlockEntity panel) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
