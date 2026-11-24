package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mrgregles.bsp_server_utilities.streamer.StreamerCatalog;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.overlay.GuiOverlayManager;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Streamer Mode's menu, look C as confirmed (owner decisions D713, D714; {@code tools/preview/streamer_refined.html}).
 * The first page: the switch with its key, the output, the frame rate, a live preview of what OBS gets, the OBS line and a
 * tile per mod (each known mod that is installed, then "Other mods"), with "Other mods' menus" as one switch. A mod's
 * page: its items, each with a HIDDEN or SHOWN switch, HIDE ALL, SHOW ALL and BACK. One box 440 x 242, centred, made
 * smaller to fit a window narrower than that (GUI scale 5 on the owner's Mac). Every change is saved at once. In the
 * stream this menu is always a "Menu hidden" card, as it shows the stream itself.
 */
public final class StreamerScreen extends Screen {
    private static final int W = 440, H = 242, BAR = 20, FOOT = 13;
    private static final int P = 0xF010151C, S = 0xFF0B0E13, D = 0xFF2A2F3A, HOT = 0xFF3A4150, M = 0x9AA3B5, T = 0xE6EAF2, I = 0x6B7180,
            Q = 0x19D3B0, G = 0xFFD23A, RED = 0xFF6B5C, ROW_LINE = 0xFF1A1F27;
    private static final int TILE_W = 117, TILE_H = 34, TILE_GAP = 6, TILE_STEP = TILE_H + 5, TILES_X = 192, TOP = 26;
    private static final int LIST_X = 8, LIST_Y = 24, LIST_W = W - 16, LIST_H = H - FOOT - 2 - LIST_Y;

    @Nullable
    private final Screen parent;
    private final List<ModView> mods = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private int page = -1, scroll;
    private float s = 1f;
    private int left, top;
    @Nullable
    private Component tip;

    /** A row of a mod's page: an item (with its id) or, under "Other mods", a mod's name above its parts. */
    /** {@code note}: the gold line, shown only while its mod has not said it hides the item itself (checked as drawn). */
    private record Row(@Nullable String id, String title, String desc, @Nullable String note) {
        boolean header() {
            return id == null;
        }

        int height() {
            return header() ? 13 : desc.isEmpty() && note == null ? 15 : note != null ? 30 : 23;
        }
    }

    private record ModView(String title, List<Row> rows) {
        List<String> ids() {
            List<String> out = new ArrayList<>();
            for (Row r : rows) {
                if (!r.header()) {
                    out.add(r.id());
                }
            }
            return out;
        }

        int hiddenCount() {
            int n = 0;
            for (String id : ids()) {
                if (StreamerMode.isHidden(id)) {
                    n++;
                }
            }
            return n;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action, @Nullable Component tip) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public StreamerScreen(@Nullable Screen parent) {
        super(Component.literal(Tr.t("streamer.name")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        s = Math.min(1f, Math.min((width - 8) / (float) W, (height - 8) / (float) H));
        left = Math.round((width - W * s) / 2f);
        top = Math.round((height - H * s) / 2f);
        mods.clear();
        mods.addAll(build());
        page = Math.min(page, mods.size() - 1);
    }

    /** The tiles: each known mod that is installed, with its known items and then its other HUD parts; then "Other mods". */
    private static List<ModView> build() {
        List<ModView> out = new ArrayList<>();
        List<NamedGuiOverlay> overlays = GuiOverlayManager.getOverlays();
        for (StreamerCatalog.Mod m : StreamerCatalog.MODS) {
            if (m.modId() != null && !ModList.get().isLoaded(m.modId())) {
                continue;
            }
            List<Row> rows = new ArrayList<>();
            for (StreamerCatalog.Item i : m.items()) {
                rows.add(new Row(i.id(), Tr.t(StreamerCatalog.itemKey(i)), Tr.t(StreamerCatalog.descKey(i)),
                        i.note() == null ? null : Tr.t(StreamerCatalog.noteKey(i))));
            }
            for (NamedGuiOverlay o : overlays) {
                String id = o.id().toString();
                if (o.id().getNamespace().equals(m.namespace()) && !StreamerCatalog.known(id)) {
                    rows.add(new Row(id, StreamerCatalog.readable(o.id().getPath()), "", null));
                }
            }
            out.add(new ModView(Tr.t(StreamerCatalog.modKey(m.key())), rows));
        }
        Map<String, List<Row>> byMod = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (NamedGuiOverlay o : overlays) {
            String ns = o.id().getNamespace();
            if (StreamerCatalog.modOf(ns) == null) {
                String name = ModList.get().getModContainerById(ns).map(c -> c.getModInfo().getDisplayName()).orElse(ns);
                byMod.computeIfAbsent(name, k -> new ArrayList<>()).add(new Row(o.id().toString(), StreamerCatalog.readable(o.id().getPath()), "", null));
            }
        }
        List<Row> other = new ArrayList<>();
        byMod.forEach((name, rows) -> {
            other.add(new Row(null, name, "", null));
            other.addAll(rows);
        });
        out.add(new ModView(Tr.t(StreamerCatalog.modKey("other")), other));
        return out;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        hits.clear();
        tip = null;
        float lx = (mouseX - left) / s, ly = (mouseY - top) / s;
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        g.pose().scale(s, s, 1f);
        g.fill(-1, -1, W + 1, H + 1, D);
        g.fill(0, 0, W, H, P);
        g.fill(0, BAR, W, BAR + 1, D);
        if (page < 0) {
            overview(g, lx, ly);
        } else {
            modPage(g, mods.get(page), lx, ly);
        }
        g.fill(0, H - FOOT, W, H - FOOT + 1, D);
        g.fill(0, H - FOOT + 1, W, H, S);
        small(g, clip(Tr.t("streamer.never"), W - 16), 8, H - FOOT + 4, I);
        key(g, W - 16, 4, 12, 12, "✕", false, true, lx, ly, this::onClose, null);
        g.pose().popPose();
        for (Hit h : hits) {
            if (h.tip() != null && h.contains(lx, ly)) {
                tip = h.tip();
            }
        }
        if (tip != null) {
            g.renderTooltip(font, font.split(tip, 200), mouseX, mouseY);
        }
    }

    private void overview(GuiGraphics g, float lx, float ly) {
        g.drawString(font, Tr.t("streamer.title"), 8, 6, Q, false);
        boolean on = StreamerMode.on();
        // the switch
        int sx = 8, sy = TOP;
        boolean hot = in(lx, ly, sx, sy, 46, 15);
        g.fill(sx, sy, sx + 46, sy + 15, on ? 0xFF000000 | Q : hot ? HOT : D);
        g.fill(sx + 1, sy + 1, sx + 45, sy + 14, S);
        g.fill(sx + 4, sy + 4, sx + 20, sy + 11, on ? 0xFF000000 | Q : 0xFF000000 | I);
        g.fill(on ? sx + 13 : sx + 5, sy + 5, on ? sx + 19 : sx + 11, sy + 10, S);
        g.drawString(font, Tr.t(on ? "streamer.on_word" : "streamer.off_word"), sx + 24, sy + 4, on ? Q : I, false);
        hits.add(new Hit(sx, sy, 46, 15, StreamerMode::toggle, null));
        small(g, Tr.t("streamer.key", StreamerMode.keyName().getString()), 60, sy + 5, M);
        // the output
        label(g, Tr.t("streamer.output"), 8, 48);
        boolean spoutHere = SpoutOutput.available(), spoutChosen = spoutHere && "spout2".equals(StreamerMode.output());
        key(g, 8, 56, 86, 12, Tr.t("streamer.out_window"), !spoutChosen, true, lx, ly, () -> StreamerMode.setOutput("window"),
                Component.literal(Tr.t("streamer.out_window_tip")));
        key(g, 96, 56, 88, 12, Tr.t("streamer.out_spout"), spoutChosen, spoutHere, lx, ly, () -> StreamerMode.setOutput("spout2"),
                Component.literal(Tr.t(spoutHere ? "streamer.spout_tip" : "streamer.spout_windows")));
        // the frame rate
        label(g, Tr.t("streamer.fps"), 8, 75);
        int f = StreamerMode.fps();
        key(g, 64, 72, 34, 12, Tr.t("streamer.fps_match"), f == 0, true, lx, ly, () -> StreamerMode.setFps(0), Component.literal(Tr.t("streamer.fps_tip")));
        key(g, 100, 72, 22, 12, "60", f == 60, true, lx, ly, () -> StreamerMode.setFps(60), Component.literal(Tr.t("streamer.fps_tip")));
        key(g, 124, 72, 22, 12, "30", f == 30, true, lx, ly, () -> StreamerMode.setFps(30), Component.literal(Tr.t("streamer.fps_tip")));
        // the live preview
        label(g, Tr.t("streamer.preview"), 8, 90);
        int px = 8, py = 98, pw = 176, ph = 99;
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, D);
        g.fill(px, py, px + pw, py + ph, S);
        int[] pic = StreamerMode.preview();
        if (pic != null) {
            drawPicture(g, px, py, pw, ph, pic);
        } else {
            smallCentred(g, Tr.t("streamer.preview_off"), px + pw / 2, py + ph / 2 - 7, M);
            smallCentred(g, Tr.t("streamer.preview_off2"), px + pw / 2, py + ph / 2 + 2, I);
        }
        int lineY = py + ph + 5;
        String status = !on ? Tr.t("streamer.status_off") : StreamerMode.sendingSpout() ? Tr.t("streamer.status_spout") : Tr.t("streamer.status_on");
        for (FormattedCharSequence line : font.split(Component.literal(status), (int) (176 / 0.75f))) {
            small(g, line, 8, lineY, on ? M : I);
            lineY += 8;
        }
        // the tiles
        for (int i = 0; i < mods.size(); i++) {
            ModView m = mods.get(i);
            int tx = TILES_X + (i % 2) * (TILE_W + TILE_GAP), ty = TOP + (i / 2) * TILE_STEP;
            boolean th = in(lx, ly, tx, ty, TILE_W, TILE_H);
            g.fill(tx, ty, tx + TILE_W, ty + TILE_H, th ? HOT : D);
            g.fill(tx + 1, ty + 1, tx + TILE_W - 1, ty + TILE_H - 1, S);
            g.drawString(font, clip(m.title(), TILE_W - 18), tx + 5, ty + 4, T, false);
            g.drawString(font, "›", tx + TILE_W - 9, ty + 4, th ? Q : I, false);
            int n = m.hiddenCount(), all = m.ids().size();
            small(g, Tr.t("streamer.count", n, all), tx + 5, ty + 15, n > 0 ? Q : M);
            List<String> first = new ArrayList<>();
            for (Row r : m.rows()) {
                if (!r.header() && first.size() < 2) {
                    first.add(r.title());
                }
            }
            String peek = all == 0 ? Tr.t("streamer.no_other") : String.join(" · ", first) + (all > 2 ? "…" : "");
            small(g, clip(peek, (int) ((TILE_W - 10) / 0.75f)), tx + 5, ty + 24, I);
            final int idx = i;
            hits.add(new Hit(tx, ty, TILE_W, TILE_H, () -> {
                page = idx;
                scroll = 0;
            }, null));
        }
        int rows = (mods.size() + 1) / 2;
        int oy = TOP + rows * TILE_STEP, ow = 2 * TILE_W + TILE_GAP;
        boolean otherHidden = StreamerMode.isHidden(StreamerCatalog.OTHER_MENUS);
        boolean oh = in(lx, ly, TILES_X, oy, ow, 17);
        g.fill(TILES_X, oy, TILES_X + ow, oy + 17, oh ? HOT : D);
        g.fill(TILES_X + 1, oy + 1, TILES_X + ow - 1, oy + 16, S);
        g.drawString(font, Tr.t("streamer.other_menus"), TILES_X + 5, oy + 5, T, false);
        drawSwitch(g, TILES_X + ow - 50, oy + 3, otherHidden);
        hits.add(new Hit(TILES_X, oy, ow, 17, () -> StreamerMode.setHidden(List.of(StreamerCatalog.OTHER_MENUS), !otherHidden),
                Component.literal(Tr.t("streamer.other_menus.tip"))));
    }

    private void modPage(GuiGraphics g, ModView m, float lx, float ly) {
        key(g, 8, 4, 38, 12, "‹ " + Tr.t("streamer.back"), false, true, lx, ly, () -> page = -1, null);
        g.drawString(font, m.title(), 52, 6, Q, false);
        small(g, Tr.t("streamer.count", m.hiddenCount(), m.ids().size()), 56 + font.width(m.title()), 7, I);
        List<String> ids = m.ids();
        key(g, W - 124, 4, 50, 12, Tr.t("streamer.hide_all"), false, !ids.isEmpty(), lx, ly, () -> StreamerMode.setHidden(ids, true), null);
        key(g, W - 72, 4, 50, 12, Tr.t("streamer.show_all"), false, !ids.isEmpty(), lx, ly, () -> StreamerMode.setHidden(ids, false), null);
        g.fill(LIST_X - 1, LIST_Y - 1, LIST_X + LIST_W + 1, LIST_Y + LIST_H + 1, D);
        g.fill(LIST_X, LIST_Y, LIST_X + LIST_W, LIST_Y + LIST_H, S);
        int content = 0;
        for (Row r : m.rows()) {
            content += r.height();
        }
        int max = Math.max(0, content - LIST_H);
        scroll = Math.max(0, Math.min(scroll, max));
        g.enableScissor(Math.round(left + LIST_X * s), Math.round(top + LIST_Y * s), Math.round(left + (LIST_X + LIST_W) * s), Math.round(top + (LIST_Y + LIST_H) * s));
        int y = LIST_Y - scroll;
        boolean inList = in(lx, ly, LIST_X, LIST_Y, LIST_W, LIST_H);
        if (m.rows().isEmpty()) {
            small(g, Tr.t("streamer.no_other"), LIST_X + 6, LIST_Y + 6, M);
        }
        for (Row r : m.rows()) {
            int h = r.height();
            if (y + h > LIST_Y && y < LIST_Y + LIST_H) {
                if (r.header()) {
                    g.fill(LIST_X, y, LIST_X + LIST_W, y + h, 0xFF0E1218);
                    small(g, r.title().toUpperCase(java.util.Locale.ROOT), LIST_X + 6, y + 4, M);
                } else {
                    boolean hidden = StreamerMode.isHidden(r.id());
                    boolean rh = inList && in(lx, ly, LIST_X, y, LIST_W, h);
                    if (rh) {
                        g.fill(LIST_X, y, LIST_X + LIST_W, y + h, 0xFF141B24);
                    }
                    g.drawString(font, clip(r.title(), LIST_W - 70), LIST_X + 6, y + 4, T, false);
                    if (!r.desc().isEmpty()) {
                        small(g, clip(r.desc(), (int) ((LIST_W - 70) / 0.75f)), LIST_X + 6, y + 14, M);
                    }
                    if (r.note() != null && !StreamerMode.handled(r.id())) { // the gold line goes once the mod hides it itself
                        small(g, clip(r.note(), (int) ((LIST_W - 70) / 0.75f)), LIST_X + 6, y + 22, G);
                    }
                    drawSwitch(g, LIST_X + LIST_W - 52, y + (h - 11) / 2, hidden);
                    hits.add(new Hit(LIST_X, Math.max(y, LIST_Y), LIST_W, Math.min(y + h, LIST_Y + LIST_H) - Math.max(y, LIST_Y),
                            () -> StreamerMode.setHidden(List.of(r.id()), !hidden), null));
                }
                g.fill(LIST_X, y + h - 1, LIST_X + LIST_W, y + h, ROW_LINE);
            }
            y += h;
        }
        g.disableScissor();
        if (max > 0) {
            int bar = Math.max(10, LIST_H * LIST_H / content), at = LIST_Y + (LIST_H - bar) * scroll / max;
            g.fill(LIST_X + LIST_W - 3, LIST_Y, LIST_X + LIST_W - 1, LIST_Y + LIST_H, 0xFF1C2026);
            g.fill(LIST_X + LIST_W - 3, at, LIST_X + LIST_W - 1, at + bar, HOT);
        }
    }

    /** The stream picture, fitted whole into the box (the texture is upside down, as every framebuffer's is). */
    private void drawPicture(GuiGraphics g, int x, int y, int w, int h, int[] pic) {
        int[] r = StreamerRules.fit(pic[1], pic[2], w, h);
        g.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, pic[0]);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        Matrix4f m = g.pose().last().pose();
        float x0 = x + r[0], y0 = y + r[1], x1 = x0 + r[2], y1 = y0 + r[3];
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        b.vertex(m, x0, y0, 0).uv(0f, 1f).endVertex();
        b.vertex(m, x0, y1, 0).uv(0f, 0f).endVertex();
        b.vertex(m, x1, y1, 0).uv(1f, 0f).endVertex();
        b.vertex(m, x1, y0, 0).uv(1f, 1f).endVertex();
        BufferUploader.drawWithShader(b.end());
    }

    private void drawSwitch(GuiGraphics g, int x, int y, boolean hidden) {
        g.fill(x, y, x + 46, y + 11, hidden ? 0x99000000 | Q : D);
        g.fill(x + 1, y + 1, x + 45, y + 10, S);
        g.fill(x + 4, y + 4, x + 7, y + 7, hidden ? 0xFF000000 | Q : 0xFF000000 | I);
        small(g, Tr.t(hidden ? "streamer.hidden_sw" : "streamer.shown_sw"), x + 11, y + 3, hidden ? Q : I);
    }

    /** A key: edged box with its words, teal when chosen, dim when not usable; registers its click. */
    private void key(GuiGraphics g, int x, int y, int w, int h, String label, boolean chosen, boolean usable, float lx, float ly,
                     Runnable action, @Nullable Component tipText) {
        boolean hot = usable && in(lx, ly, x, y, w, h);
        g.fill(x, y, x + w, y + h, chosen ? 0xFF000000 | Q : hot ? HOT : D);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, S);
        int c = chosen ? Q : usable ? (hot ? T : M) : 0x4A5060;
        String text = label.toUpperCase(java.util.Locale.ROOT);
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - font.width(text) * 0.375f, y + (h - 6) / 2f, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, 0, 0, c, false);
        g.pose().popPose();
        hits.add(new Hit(x, y, w, h, usable ? action : () -> {
        }, tipText));
    }

    private void label(GuiGraphics g, String text, int x, int y) {
        small(g, text.toUpperCase(java.util.Locale.ROOT), x, y, I);
    }

    private void small(GuiGraphics g, String text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, 0, 0, colour, false);
        g.pose().popPose();
    }

    private void small(GuiGraphics g, FormattedCharSequence text, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, text, 0, 0, colour, false);
        g.pose().popPose();
    }

    private void smallCentred(GuiGraphics g, String text, int cx, int y, int colour) {
        small(g, text, Math.round(cx - font.width(text) * 0.375f), y, colour);
    }

    /** The words cut with an ellipsis to fit {@code w} pixels of this font. */
    private String clip(String text, int w) {
        if (font.width(text) <= w) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, w - font.width("…"))) + "…";
    }

    private static boolean in(float lx, float ly, int x, int y, int w, int h) {
        return lx >= x && lx < x + w && ly >= y && ly < y + h;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            float lx = (float) ((mouseX - left) / s), ly = (float) ((mouseY - top) / s);
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.contains(lx, ly)) {
                    h.action().run();
                    playClick();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (page >= 0) {
            scroll -= (int) Math.round(delta * 15);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE && page >= 0) {
            page = -1;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return parent instanceof net.minecraft.client.gui.screens.PauseScreen;
    }
}
