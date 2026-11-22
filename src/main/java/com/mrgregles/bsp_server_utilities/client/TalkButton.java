package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig;
import com.mrgregles.bsp_server_utilities.support.CornerPlace;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The MESSAGE STAFF key in the survival and creative inventory (owner decisions D650, D652 R5;
 * {@code tools/preview/talk_refined.html}): TEAM's dark key with a violet envelope, 66 x 14, shown only while staff have
 * a conversation open with the player, flashing red every half second while the newest line is from staff. It stands
 * right of SUPPORT BSP (where SUPPORT BSP would stand when there is no support link), placed every frame as SUPPORT BSP is:
 * past every other widget, the inventory panel with its tab rows, and the open recipe book, down a row when the left half
 * is full, not drawn when no place is left. Region Guard's BANNED steps past it (request SU-RG2 keeps the order on narrow
 * windows). A click asks the server for the window ({@code /su talk}); the player may hide the key ({@code messageButton}).
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class TalkButton {
    static final int W = 66, H = CornerPlace.H, GAP = CornerPlace.GAP;
    /** This key's class name, for SUPPORT BSP (which does not step round it) and Region Guard (SU-RG2). */
    static final String CORNER = "com.mrgregles.bsp_server_utilities.client.TalkButton$Corner";
    private static final String SUPPORT_CORNER = "com.mrgregles.bsp_server_utilities.client.SupportButton$Corner";
    private static final int TAB_ROWS = 32;
    private static final int BG = 0xF010151C, TQ = 0xFF19D3B0, DIM = 0xFF2A2F3A, MUTED = 0xFF9AA3B5, VIOLET = 0xFFB58CFF, RED = 0xFFFF5A4F;

    private TalkButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof InventoryScreen || event.getScreen() instanceof CreativeModeInventoryScreen) {
            event.addListener(new Corner((AbstractContainerScreen<?>) event.getScreen()));
        }
    }

    /** As SUPPORT BSP: hidden while the recipe book covers a narrow inventory (vanilla draws no widgets then). */
    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof InventoryScreen inv && inv.width < 379 && inv.getRecipeBookComponent().isVisible()) {
            for (var child : inv.children()) {
                if (child instanceof Corner c) {
                    c.visible = false;
                    c.placed = false;
                }
            }
        }
    }

    /** Where the key stands now as {x, y, width, height}, or null when hidden: for JEI and EMI. */
    @Nullable
    public static int[] area(@Nullable Screen screen) {
        if (screen != null) {
            for (var child : screen.children()) {
                if (child instanceof Corner c && c.visible && c.placed) {
                    return new int[]{c.getX(), c.getY(), W, H};
                }
            }
        }
        return null;
    }

    /** Whether the key shows: a conversation is open and the player has not hidden it. */
    static boolean shown() {
        return ClientTalk.open() && ServerUtilsClientConfig.messageButton();
    }

    /** The red half of the flash (every half second while a staff line waits, D634). */
    static boolean flashOn() {
        return ClientTalk.waiting() && (Util.getMillis() / 500) % 2 == 0;
    }

    @Nullable
    private static int[] place(AbstractContainerScreen<?> screen, AbstractWidget self) {
        List<int[]> blocked = new ArrayList<>();
        int rowY = Integer.MAX_VALUE, rowEnd = -1;
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w && w != self && w.visible) {
                String name = w.getClass().getName();
                if (name.equals(SupportButton.RG_CORNER)) {
                    continue; // BANNED steps past this key, not the other way round
                }
                blocked.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                if (name.equals(SupportButton.BSP_CORNER) || name.equals(SUPPORT_CORNER)) {
                    if (w.getY() < rowY) {
                        rowY = w.getY();
                        rowEnd = w.getX() + w.getWidth() + GAP;
                    } else if (w.getY() == rowY) {
                        rowEnd = Math.max(rowEnd, w.getX() + w.getWidth() + GAP);
                    }
                }
            }
        }
        blocked.add(new int[]{screen.getGuiLeft(), screen.getGuiTop() - TAB_ROWS, screen.getXSize(), screen.getYSize() + 2 * TAB_ROWS});
        if (screen instanceof InventoryScreen inv && inv.getRecipeBookComponent().isVisible()) {
            int bx = (screen.width - 147) / 2 - (screen.width < 379 ? 0 : 86);
            blocked.add(new int[]{bx - 32, (screen.height - 166) / 2, 147 + 32, 166});
        }
        return CornerPlace.place(blocked, rowEnd < 0 ? null : new int[]{rowEnd, rowY}, screen.width, screen.height, W);
    }

    /** Drawn as TEAM with a violet envelope; teal under the mouse; red on the flash, under the mouse too (R5). */
    static void drawKey(GuiGraphics g, int x, int y, boolean hot, boolean flash) {
        int edge = flash ? RED : hot ? TQ : DIM;
        g.fill(x, y, x + W, y + H, edge);
        g.fill(x + 1, y + 1, x + W - 1, y + H - 1, BG);
        var font = Minecraft.getInstance().font;
        String env = "✉", label = Tr.t("talk.button");
        float s = 0.75f;
        int total = (int) Math.ceil((font.width(env) + 2 + font.width(label)) * s);
        g.pose().pushPose();
        g.pose().translate(x + (W - total) / 2f, y + 4, 0);
        g.pose().scale(s, s, 1f);
        g.drawString(font, env, 0, 0, (flash ? RED : VIOLET) & 0xFFFFFF, false);
        g.drawString(font, label, font.width(env) + 2, 0, (flash ? RED : hot ? TQ : MUTED) & 0xFFFFFF, false);
        g.pose().popPose();
    }

    static final class Corner extends Button {
        private final AbstractContainerScreen<?> screen;
        boolean placed;
        private boolean wasWaiting;

        Corner(AbstractContainerScreen<?> screen) {
            super(CornerPlace.MARGIN, CornerPlace.MARGIN, W, H, Component.literal(Tr.t("talk.button")), b -> open(), DEFAULT_NARRATION);
            this.screen = screen;
            this.visible = false; // until placed
            tip();
        }

        private void tip() {
            wasWaiting = ClientTalk.waiting();
            setTooltip(Tooltip.create(Component.literal(Tr.t(wasWaiting ? "talk.button.tip_waiting" : "talk.button.tip"))));
        }

        private static void open() {
            var mc = Minecraft.getInstance();
            if (mc.player != null && ClientTalk.open()) {
                mc.player.connection.sendCommand("su talk"); // the server answers with the window
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            visible = shown();
            placed = false;
            if (visible) {
                int[] at = place(screen, this);
                if (at == null) {
                    visible = false;
                } else {
                    setX(at[0]);
                    setY(at[1]);
                    placed = true;
                }
            }
            if (wasWaiting != ClientTalk.waiting()) {
                tip();
            }
            super.render(g, mouseX, mouseY, partialTick);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            drawKey(g, getX(), getY(), isHoveredOrFocused(), flashOn());
        }
    }
}
