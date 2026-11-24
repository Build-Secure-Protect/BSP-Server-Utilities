package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TutorialBlobPacket;
import com.mrgregles.bsp_server_utilities.network.TutorialKeyPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The admins' side of tutorials on the client (SU-142 to SU-144): the builder's view as the server last sent it (the
 * builder and the TUTORIAL tab read it), and path or area mode (owner decisions D731, D737, D742; look AY A): the strip
 * across the top with the mode's keys (G, Z and Delete by default, changeable in Controls, live only in a mode), the
 * yellow line with a mark at each point, the two points' spheres, the area's violet box, and the cards that finish a
 * path or save an area.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class ClientTutorialAdmin {
    static final String CATEGORY = "key.categories.bsp_server_utilities";
    public static final KeyMapping KEY_GO = new KeyMapping("key.bsp_server_utilities.tutorial_go", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G), CATEGORY);
    public static final KeyMapping KEY_UNDO = new KeyMapping("key.bsp_server_utilities.tutorial_undo", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Z), CATEGORY);
    public static final KeyMapping KEY_CANCEL = new KeyMapping("key.bsp_server_utilities.tutorial_cancel", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_DELETE), CATEGORY);
    private static final float YEL_R = 1f, YEL_G = 0.894f, YEL_B = 0.361f, VIO_R = 0.71f, VIO_G = 0.55f, VIO_B = 1f;

    private static final TutorialBlobPacket.Collector VIEW_PARTS = new TutorialBlobPacket.Collector();
    @Nullable
    private static CompoundTag view;
    private static int viewVersion;
    @Nullable
    private static CompoundTag mode;

    private ClientTutorialAdmin() {
    }

    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(KEY_GO);
        event.register(KEY_UNDO);
        event.register(KEY_CANCEL);
    }

    // ------------------------------------------------------------------ the builder's view

    public static void part(TutorialBlobPacket p) {
        CompoundTag tag = VIEW_PARTS.add(p);
        if (tag != null) {
            view = tag;
            viewVersion++;
        }
    }

    @Nullable
    static CompoundTag view() {
        return view;
    }

    /** Rises with each view that arrives, so the screens know to read it again. */
    static int viewVersion() {
        return viewVersion;
    }

    // ------------------------------------------------------------------ path and area mode

    public static void mode(CompoundTag state) {
        Minecraft mc = Minecraft.getInstance();
        if (state.getBoolean("openBuilder")) {
            mc.setScreen(new TutorialBuilderScreen(null));
            return;
        }
        boolean wasAsked = mode != null && mode.getBoolean("asked");
        boolean hadBoth = mode != null && mode.contains("c1") && mode.contains("c2");
        mode = state.isEmpty() ? null : state;
        if (mode == null) {
            if (mc.screen instanceof TutorialModeScreen) {
                mc.setScreen(null);
            }
            return;
        }
        if (mc.screen instanceof TutorialBuilderScreen || mc.screen instanceof TutorialEditorScreen) {
            mc.setScreen(null); // path and area mode happen in the world
        }
        boolean asked = mode.getBoolean("asked"), both = mode.contains("c1") && mode.contains("c2");
        if (asked && !wasAsked || both && !hadBoth) {
            cardPending = true; // opened when no other screen is up (an open chat box keeps its words)
            if (mc.screen == null) {
                cardPending = false;
                mc.setScreen(new TutorialModeScreen());
            }
        } else if (!asked && !both) {
            cardPending = false;
            if (mc.screen instanceof TutorialModeScreen) {
                mc.setScreen(null);
            }
        }
    }

    @Nullable
    static CompoundTag modeState() {
        return mode;
    }

    static boolean drawing(String dimension) {
        return mode != null && mode.getString("dimension").equals(dimension);
    }

    static void key(TutorialKeyPacket.Action a) {
        ServerUtilsNetwork.CHANNEL.sendToServer(new TutorialKeyPacket(a));
    }

    private static List<BlockPos> samples() {
        List<BlockPos> out = new ArrayList<>();
        if (mode != null) {
            for (Tag t : mode.getList("samples", Tag.TAG_LONG)) {
                out.add(BlockPos.of(((LongTag) t).getAsLong()));
            }
        }
        return out;
    }

    static int blocks(List<BlockPos> s) {
        double d = 0;
        for (int i = 1; i < s.size(); i++) {
            d += Math.sqrt(s.get(i).distSqr(s.get(i - 1)));
        }
        return (int) Math.round(d);
    }

    /** The keys are read every tick, so presses outside a mode never pile up for the next one (audit of SU-146). */
    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean live = mode != null && mc.player != null && mc.screen == null;
        while (KEY_GO.consumeClick()) {
            if (live) {
                key(TutorialKeyPacket.Action.GO);
            }
        }
        while (KEY_UNDO.consumeClick()) {
            if (live) {
                key(TutorialKeyPacket.Action.UNDO);
            }
        }
        while (KEY_CANCEL.consumeClick()) {
            if (live) {
                key(TutorialKeyPacket.Action.CANCEL);
            }
        }
        if (cardPending && mode != null && mc.player != null && mc.screen == null) {
            cardPending = false;
            mc.setScreen(new TutorialModeScreen());
        }
    }

    private static boolean cardPending;
    /** The foot of the boss bars drawn this frame, so the strip sits under them rather than over them (audit of SU-146). */
    private static int bossBottom;

    @SubscribeEvent
    public static void onHudStart(RenderGuiEvent.Pre event) {
        bossBottom = 0;
    }

    @SubscribeEvent
    public static void onBossBar(net.minecraftforge.client.event.CustomizeGuiOverlayEvent.BossEventProgress event) {
        bossBottom = Math.max(bossBottom, event.getY() + 7); // the bar is 5 tall, its name above it
    }

    /** The strip across the top (AY A): what is being drawn, the numbers, and the keys under it. */
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mode == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int w = Math.min(360, g.guiWidth() - 16), x = (g.guiWidth() - w) / 2, y = Math.max(6, bossBottom + 2), h = 30;
        g.fill(x, y, x + w, y + h, Ui.PANEL);
        Ui.frame(g, x, y, w, h, Ui.DIM);
        boolean path = "PATH".equals(mode.getString("kind"));
        List<BlockPos> s = samples();
        String title, left, right;
        List<String[]> keys = new ArrayList<>();
        int dot;
        if (path) {
            boolean rec = mode.getBoolean("recording"), paused = mode.getBoolean("paused");
            title = Tr.t(rec ? "tutorial.mode.drawing" : "tutorial.mode.draw");
            left = mode.getString("fromTitle") + " → " + mode.getString("toTitle");
            BlockPos to = BlockPos.of(mode.getLong("to"));
            int toGo = (int) Math.round(Math.sqrt(mc.player.blockPosition().distSqr(to)));
            right = rec ? Tr.t("tutorial.mode.counts", s.size(), blocks(s), toGo) : Tr.t("tutorial.mode.at_start", mode.getString("fromTitle"), toGo);
            dot = rec && !paused ? Ui.BAD : Ui.DIM;
            keys.add(new String[]{KEY_GO.getTranslatedKeyMessage().getString(), Tr.t(!rec ? "tutorial.mode.start" : paused ? "tutorial.mode.resume" : "tutorial.mode.pause")});
            if (rec) {
                keys.add(new String[]{KEY_UNDO.getTranslatedKeyMessage().getString(), Tr.t("tutorial.mode.undo")});
            }
        } else {
            title = Tr.t("tutorial.mode.area");
            left = mode.getString("tutorial");
            boolean c1 = mode.contains("c1"), c2 = mode.contains("c2");
            right = Tr.t("tutorial.mode.corners", c1 ? "✓" : "·", c2 ? "✓" : "·");
            if (c1 && c2) {
                BlockPos a = BlockPos.of(mode.getLong("c1")), b = BlockPos.of(mode.getLong("c2"));
                right += " · " + (Math.abs(a.getX() - b.getX()) + 1) + " × " + (Math.abs(a.getY() - b.getY()) + 1) + " × " + (Math.abs(a.getZ() - b.getZ()) + 1);
            }
            dot = Ui.VIOLET;
            keys.add(new String[]{KEY_GO.getTranslatedKeyMessage().getString(), Tr.t("tutorial.mode.corner")});
            keys.add(new String[]{KEY_UNDO.getTranslatedKeyMessage().getString(), Tr.t("tutorial.mode.undo")});
        }
        keys.add(new String[]{KEY_CANCEL.getTranslatedKeyMessage().getString(), Tr.t("tutorial.mode.cancel")});
        g.fill(x + 7, y + 7, x + 11, y + 11, dot);
        Ui.text(g, font, Component.literal(title.toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD), x + 15, y + 6, Ui.VIOLET, 0.75f);
        int tx = x + 15 + Ui.widthBold(font, title.toUpperCase(Locale.ROOT), 0.75f) + 8; // measured bold (audit of SU-146)
        int rw = Ui.width(font, right, 0.7f);
        Ui.text(g, font, Ui.fit(font, left, x + w - 12 - rw - tx, 0.75f), tx, y + 6, Ui.TEXT, 0.75f);
        Ui.text(g, font, right, x + w - 8 - rw, y + 7, Ui.MUTED, 0.7f);
        int kx = x + 8;
        for (String[] k : keys) {
            String cap = k[0].toUpperCase(Locale.ROOT);
            int cw = Ui.width(font, cap, 0.6f) + 6;
            g.fill(kx, y + 17, kx + cw, y + 26, Ui.SLOT);
            Ui.frame(g, kx, y + 17, cw, 9, Ui.DIM);
            Ui.text(g, font, cap, kx + 3, y + 19, Ui.MUTED, 0.6f);
            Ui.text(g, font, k[1], kx + cw + 3, y + 19, Ui.TEXT, 0.6f);
            kx += cw + 3 + Ui.width(font, k[1], 0.6f) + 10;
        }
    }

    /** The yellow line, the spheres, or the area's box. */
    static void renderWorld(PoseStack pose, MultiBufferSource buffers, Camera camera, Font font, Vec3 cam) {
        if (mode == null) {
            return;
        }
        Matrix4f m = pose.last().pose();
        Minecraft mc = Minecraft.getInstance();
        if ("PATH".equals(mode.getString("kind"))) {
            List<BlockPos> s = samples();
            VertexConsumer v = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
            for (int i = 1; i < s.size(); i++) {
                TutorialWorld.ribbon(m, v, Vec3.atBottomCenterOf(s.get(i - 1)).add(0, 0.15, 0), Vec3.atBottomCenterOf(s.get(i)).add(0, 0.15, 0), cam, 0.06, YEL_R, YEL_G, YEL_B, 0.9f);
            }
            for (BlockPos b : s) {
                Vec3 c = Vec3.atBottomCenterOf(b).add(0, 0.15, 0);
                TutorialWorld.ribbon(m, v, c.add(-0.12, 0, 0), c.add(0.12, 0, 0), cam, 0.12, YEL_R, YEL_G, YEL_B, 0.95f);
            }
            if (!s.isEmpty() && mode.getBoolean("recording") && mc.player != null) {
                // the live part, from the last point to the feet
                List<Vec3> live = List.of(Vec3.atBottomCenterOf(s.get(s.size() - 1)).add(0, 0.15, 0), mc.player.position().add(0, 0.15, 0));
                TutorialWorld.dashes(pose, buffers, live, cam, YEL_R, YEL_G, YEL_B);
            }
            if (mode.contains("from")) {
                sphere(pose, buffers, camera, font, Vec3.atBottomCenterOf(BlockPos.of(mode.getLong("from"))), mode.getInt("fromRadius"), mode.getString("fromTitle"), cam);
                sphere(pose, buffers, camera, font, Vec3.atBottomCenterOf(BlockPos.of(mode.getLong("to"))), mode.getInt("toRadius"), mode.getString("toTitle"), cam);
            }
        } else if (mode.contains("c1")) {
            BlockPos a = BlockPos.of(mode.getLong("c1"));
            BlockPos b = mode.contains("c2") ? BlockPos.of(mode.getLong("c2")) : mc.player != null ? mc.player.blockPosition() : a;
            box(m, buffers.getBuffer(RenderType.textBackgroundSeeThrough()), a, b, cam);
        }
    }

    /** A see-through sphere of the point's radius: three rings and its title over it. */
    private static void sphere(PoseStack pose, MultiBufferSource buffers, Camera camera, Font font, Vec3 c, int radius, String title, Vec3 cam) {
        Matrix4f m = pose.last().pose();
        VertexConsumer v = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
        int n = 36;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < n; i++) {
                double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
                Vec3 p0 = ringPoint(c, radius, ring, a0), p1 = ringPoint(c, radius, ring, a1);
                TutorialWorld.ribbon(m, v, p0, p1, cam, 0.035, 0.66f, 0.92f, 1f, 0.6f);
            }
        }
        pose.pushPose();
        pose.translate(c.x, c.y + radius + 0.6, c.z);
        pose.mulPose(camera.rotation());
        pose.scale(-0.025f, -0.025f, 0.025f);
        String t = title.toUpperCase(Locale.ROOT);
        font.drawInBatch(t, -font.width(t) / 2f, 0, 0xFFFFFFFF, false, pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, 0x60000000, 0xF000F0);
        pose.popPose();
    }

    private static Vec3 ringPoint(Vec3 c, double r, int ring, double a) {
        double cos = Math.cos(a) * r, sin = Math.sin(a) * r;
        return switch (ring) {
            case 0 -> c.add(cos, r, sin); // round the middle, the sphere's foot at the point
            case 1 -> c.add(cos, r + sin, 0);
            default -> c.add(0, r + sin, cos);
        };
    }

    /** The area's box between two corner blocks (both included): faint violet faces and edges. */
    private static void box(Matrix4f m, VertexConsumer v, BlockPos a, BlockPos b, Vec3 cam) {
        double x0 = Math.min(a.getX(), b.getX()), y0 = Math.min(a.getY(), b.getY()), z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1, y1 = Math.max(a.getY(), b.getY()) + 1, z1 = Math.max(a.getZ(), b.getZ()) + 1;
        Vec3[] c = {new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0), new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)};
        int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0}};
        for (int[] f : faces) {
            TutorialWorld.quad(m, v, c[f[0]], c[f[1]], c[f[2]], c[f[3]], VIO_R, VIO_G, VIO_B, 0.08f);
            TutorialWorld.quad(m, v, c[f[3]], c[f[2]], c[f[1]], c[f[0]], VIO_R, VIO_G, VIO_B, 0.08f);
        }
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            TutorialWorld.ribbon(m, v, c[e[0]], c[e[1]], cam, 0.04, VIO_R, VIO_G, VIO_B, 0.9f);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        view = null;
        mode = null;
    }
}
