package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.TutorialKeyPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The cards of path and area mode (SU-144; look AY A): inside the next point's sphere, "You reached ..." with FINISH
 * PATH, KEEP WALKING and UNDO LAST (Escape keeps walking; the edge of the screen glows); in area mode with both corners
 * set, SAVE AREA and RESET (Escape just closes it).
 */
public class TutorialModeScreen extends Screen {
    private static final int W = 230, KEY_H = 16;
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private Ui.Hit pressed;

    TutorialModeScreen() {
        super(Component.literal("Tutorial"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean path() {
        CompoundTag m = ClientTutorialAdmin.modeState();
        return m != null && "PATH".equals(m.getString("kind"));
    }

    @Override
    public void onClose() {
        if (path()) {
            ClientTutorialAdmin.key(TutorialKeyPacket.Action.KEEP);
        }
        super.onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (path()) {
            // the sphere's edge: a glow round the screen
            for (int i = 0; i < 12; i++) {
                int a = (int) (90 * (1 - i / 12f)) << 24;
                Ui.frame(g, i, i, width - 2 * i, height - 2 * i, a | 0x96E1FF);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        hits.clear();
        CompoundTag m = ClientTutorialAdmin.modeState();
        if (m == null) {
            onClose();
            return;
        }
        int h = 82, x = (width - W) / 2, y = (height - h) / 2;
        g.fill(x, y, x + W, y + h, Ui.PANEL);
        Ui.frame(g, x, y, W, h, path() ? Ui.GOLD : Ui.VIOLET);
        String title, sub;
        if (path()) {
            List<BlockPos> s = new ArrayList<>();
            for (Tag t : m.getList("samples", Tag.TAG_LONG)) {
                s.add(BlockPos.of(((LongTag) t).getAsLong()));
            }
            title = Tr.t("tutorial.mode.reached", m.getString("toTitle"));
            sub = Tr.t("tutorial.mode.path_counts", s.size(), ClientTutorialAdmin.blocks(s), m.getString("fromTitle"));
        } else {
            title = Tr.t("tutorial.mode.both_set");
            sub = Tr.t("tutorial.mode.area_hint", m.getString("tutorial"));
        }
        Ui.text(g, font, Component.literal(Ui.fitBold(font, title.toUpperCase(Locale.ROOT), W - 20, 0.9f)).withStyle(ChatFormatting.BOLD), x + 10, y + 10, path() ? Ui.GOLD : Ui.VIOLET, 0.9f);
        int ty = y + 24;
        for (String line : Ui.wrap(font, sub, W - 20, 0.7f)) {
            Ui.text(g, font, line, x + 10, ty, Ui.MUTED, 0.7f);
            ty += 8;
        }
        int ky = y + h - 10 - KEY_H, kx = x + 10;
        if (path()) {
            kx += key(g, kx, ky, Tr.t("tutorial.mode.finish"), 0xFFD23A, mx, my, () -> ClientTutorialAdmin.key(TutorialKeyPacket.Action.FINISH)) + 4;
            kx += key(g, kx, ky, Tr.t("tutorial.mode.keep"), 0x2A2F3A, mx, my, this::onClose) + 4;
            key(g, kx, ky, Tr.t("tutorial.mode.undo"), 0x2A2F3A, mx, my, () -> {
                ClientTutorialAdmin.key(TutorialKeyPacket.Action.UNDO);
                onClose();
            });
        } else {
            kx += key(g, kx, ky, Tr.t("tutorial.mode.save_area"), 0xB58CFF, mx, my, () -> ClientTutorialAdmin.key(TutorialKeyPacket.Action.SAVE_AREA)) + 4;
            key(g, kx, ky, Tr.t("tutorial.mode.reset"), 0x2A2F3A, mx, my, () -> {
                ClientTutorialAdmin.key(TutorialKeyPacket.Action.RESET_AREA);
                minecraft.setScreen(null);
            });
        }
    }

    private int key(GuiGraphics g, int x, int y, String label, int colour, int mx, int my, Runnable action) {
        int w = Math.max(50, Ui.widthBold(font, label.toUpperCase(Locale.ROOT), 0.75f) + 16); // measured bold (audit of SU-146)
        boolean hot = mx >= x && mx < x + w && my >= y && my < y + KEY_H;
        TutorialDraw.button(g, font, x, y, w, KEY_H, label, colour, hot, true);
        hits.add(new Ui.Hit(x, y, w, KEY_H, action));
        return w;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        pressed = null;
        if (button == 0) {
            for (Ui.Hit h : hits) {
                if (h.contains(mx, my)) {
                    pressed = h;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && pressed != null) {
            Ui.Hit was = pressed;
            pressed = null;
            if (was.contains(mx, my)) {
                was.action().run();
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }
}
