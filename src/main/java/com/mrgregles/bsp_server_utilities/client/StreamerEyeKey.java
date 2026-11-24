package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.streamer.StreamerRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Streamer Mode's eye key (owner decision D713, refined page R4): 14 x 14 in the top-right corner of the title screen and
 * the pause screen (singleplayer and servers), stepping left past anything already in that corner; its name on hover,
 * ringed red with a red dot while Streamer Mode is on. A click opens Streamer Mode's menu ({@link StreamerScreen}).
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class StreamerEyeKey {
    private static final int S = StreamerRules.EYE;
    private static final int BG = 0xF010151C, DIM = 0xFF2A2F3A, HOT = 0xFF3A4150, MUTED = 0xFF9AA3B5, TQ = 0xFF19D3B0, RED = 0xFFFF6B5C;

    private StreamerEyeKey() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen s = event.getScreen();
        if (s instanceof TitleScreen || s instanceof PauseScreen) {
            event.addListener(new Key(s));
        }
    }

    static final class Key extends Button {
        private final Screen screen;
        private boolean wasOn;

        Key(Screen screen) {
            super(screen.width - StreamerRules.MARGIN - S, StreamerRules.MARGIN, S, S, Component.literal(Tr.t("streamer.name")),
                    b -> Minecraft.getInstance().setScreen(new StreamerScreen(screen)), DEFAULT_NARRATION);
            this.screen = screen;
            tip();
        }

        private void tip() {
            wasOn = StreamerMode.on();
            setTooltip(Tooltip.create(Component.literal(Tr.t(wasOn ? "streamer.key_on" : "streamer.key_off"))));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            List<int[]> blocked = new ArrayList<>();
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget w && w != this && w.visible) {
                    blocked.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                }
            }
            blocked.addAll(com.mrgregles.bsp_server_utilities.compat.FancyMenu.areas(screen)); // its boxes are not widgets (first test)
            int[] at = StreamerRules.eyeKey(blocked, screen.width);
            if (at == null) {
                active = false;
                return;
            }
            active = true;
            setX(at[0]);
            setY(at[1]);
            if (wasOn != StreamerMode.on()) {
                tip();
            }
            boolean on = StreamerMode.on(), hot = isHoveredOrFocused();
            int x = getX(), y = getY();
            g.fill(x, y, x + S, y + S, on ? RED : hot ? TQ : DIM);
            g.fill(x + 1, y + 1, x + S - 1, y + S - 1, hot && !on ? 0xF0141B24 : BG);
            // the eye: an outline almond and a pupil, drawn with fills so it stays sharp at every GUI scale
            int c = on ? RED : hot ? TQ : MUTED;
            g.fill(x + 4, y + 5, x + 10, y + 6, c);
            g.fill(x + 3, y + 6, x + 4, y + 8, c);
            g.fill(x + 10, y + 6, x + 11, y + 8, c);
            g.fill(x + 4, y + 8, x + 10, y + 9, c);
            g.fill(x + 6, y + 6, x + 8, y + 8, c);
            if (on) {
                g.fill(x + S - 3, y, x + S, y + 3, RED);
            }
        }

        @Override
        public boolean isMouseOver(double mx, double my) {
            return active && super.isMouseOver(mx, my);
        }

    }
}
