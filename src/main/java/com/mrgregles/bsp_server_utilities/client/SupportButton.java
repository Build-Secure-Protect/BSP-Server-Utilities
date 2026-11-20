package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig;
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
 * The SUPPORT BSP key in the top left corner of the survival and creative inventory (owner decisions D587 to D592,
 * D607, D610; {@code tools/preview/support_refined.html} R1). It stands two pixels right of BSP-Core's TEAM and KITS, in
 * their look with a pink heart, 62 x 14; Region Guard's BANNED, when shown, steps past it to its right. Each frame it
 * steps right past any other widget, the inventory panel with room for its tab rows above and below, and the open recipe
 * book, and down a row when the left half of the window is used up; with no free place left it is not drawn. Shown only
 * while the server has a support link and the player's {@code supportButton} is on; a click opens the popup over the
 * inventory. BSP-Core and Region Guard are found by class name: both are optional.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID, value = Dist.CLIENT)
public final class SupportButton {
    static final int W = com.mrgregles.bsp_server_utilities.support.CornerPlace.W, H = com.mrgregles.bsp_server_utilities.support.CornerPlace.H,
            GAP = com.mrgregles.bsp_server_utilities.support.CornerPlace.GAP, MARGIN = com.mrgregles.bsp_server_utilities.support.CornerPlace.MARGIN;
    /** Room kept free above and below the inventory panel for tab rows, as BSP-Core keeps. */
    private static final int TAB_ROWS = 32;
    /** BSP-Core's TEAM and KITS keys. */
    static final String BSP_CORNER = "com.mrgregles.bsp_core.client.TeamInventoryButton$Corner";
    /** Region Guard's BANNED key, which stands right of this one (D587), so it is not stepped past. */
    static final String RG_CORNER = "com.mrgregles.bsp_region_guard.client.BannedInventoryButton$Corner";
    private static final int BG = 0xF010151C, TQ = 0xFF19D3B0, DIM = 0xFF2A2F3A, MUTED = 0xFF9AA3B5, HEART = 0xFFFF6B8A;

    private SupportButton() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!ServerUtilsClientConfig.supportButton()) {
            return;
        }
        if (event.getScreen() instanceof InventoryScreen || event.getScreen() instanceof CreativeModeInventoryScreen) {
            event.addListener(new Corner((AbstractContainerScreen<?>) event.getScreen()));
        }
    }

    /**
     * With the recipe book open on a window narrower than 379, the inventory draws the book over itself and none of its
     * widgets, so the key is not rendered and would keep its last place: it is hidden then (audit of 0.15.0).
     */
    @SubscribeEvent
    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof InventoryScreen inv && bookHidesWidgets(inv)) {
            for (var child : inv.children()) {
                if (child instanceof Corner c) {
                    c.visible = false;
                    c.placed = false;
                }
            }
        }
    }

    private static boolean bookHidesWidgets(InventoryScreen inv) {
        return inv.width < 379 && inv.getRecipeBookComponent().isVisible();
    }

    /** Where the key stands now as {x, y, width, height}, or null when it is hidden: for JEI and EMI, which keep their overlays out of it. */
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

    /** What stands in the way on this screen now, and where BSP-Core's row ends ({x, y} to start from, or null). */
    @Nullable
    private static int[] place(AbstractContainerScreen<?> screen, AbstractWidget self) {
        List<int[]> blocked = new ArrayList<>();
        int rowY = Integer.MAX_VALUE, rowEnd = -1;
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w && w != self && w.visible) {
                String name = w.getClass().getName();
                if (name.equals(RG_CORNER)) {
                    continue; // BANNED steps past this key, not the other way round
                }
                blocked.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                if (name.equals(BSP_CORNER)) {
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
            // the open book: 147 x 166, left of the panel (over it when the window is too narrow), centred upright, with
            // its column of recipe tabs (35 wide, from 30 px left of the book) drawn by the book itself (SU-93 audit)
            int bx = (screen.width - 147) / 2 - (screen.width < 379 ? 0 : 86);
            blocked.add(new int[]{bx - 32, (screen.height - 166) / 2, 147 + 32, 166});
        }
        return com.mrgregles.bsp_server_utilities.support.CornerPlace.place(blocked, rowEnd < 0 ? null : new int[]{rowEnd, rowY}, screen.width, screen.height);
    }

    /** Drawn as BSP-Core draws TEAM, with a pink heart before the words: the edge and the words teal under the mouse (also the SUPPORT tab's preview). */
    static void drawKey(GuiGraphics g, int x, int y, boolean hot) {
        g.fill(x, y, x + W, y + H, hot ? TQ : DIM);
        g.fill(x + 1, y + 1, x + W - 1, y + H - 1, BG);
        var font = Minecraft.getInstance().font;
        String heart = "\u2764", label = Tr.t("support.button");
        float s = 0.75f;
        int total = (int) Math.ceil((font.width(heart) + 2 + font.width(label)) * s);
        g.pose().pushPose();
        g.pose().translate(x + (W - total) / 2f, y + 4, 0);
        g.pose().scale(s, s, 1f);
        g.drawString(font, heart, 0, 0, HEART, false);
        g.drawString(font, label, font.width(heart) + 2, 0, hot ? TQ & 0xFFFFFF : MUTED & 0xFFFFFF, false);
        g.pose().popPose();
    }

    /** A grey corner key as BSP-Core draws TEAM and KITS (the SUPPORT tab's preview). */
    static void drawPlainKey(GuiGraphics g, int x, int y, int w, String label) {
        g.fill(x, y, x + w, y + H, DIM);
        g.fill(x + 1, y + 1, x + w - 1, y + H - 1, BG);
        var font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(x + w / 2f - font.width(label) * 3 / 8f, y + 4, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawString(font, label, 0, 0, MUTED & 0xFFFFFF, false);
        g.pose().popPose();
    }

    /** The key, placed afresh every frame; hidden with no support link or no free place. */
    static final class Corner extends Button {
        private final AbstractContainerScreen<?> screen;
        boolean placed;

        Corner(AbstractContainerScreen<?> screen) {
            super(MARGIN, MARGIN, W, H, Component.literal(Tr.t("support.button")), b -> open(screen), DEFAULT_NARRATION);
            this.screen = screen;
            this.visible = false; // until placed, so BSP-Core's keys never step round it at (2, 2) on the first frame
            setTooltip(Tooltip.create(Component.literal(Tr.t("support.button.tip"))));
        }

        private static void open(AbstractContainerScreen<?> screen) {
            String link = ClientSupport.link();
            if (!link.isEmpty()) {
                Minecraft.getInstance().setScreen(new SupportScreen(link, screen));
            }
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            visible = !ClientSupport.link().isEmpty();
            placed = false;
            if (visible) {
                int[] at = place(screen, this);
                if (at == null) {
                    visible = false; // no free place: not drawn over anything (D610)
                } else {
                    setX(at[0]);
                    setY(at[1]);
                    placed = true;
                }
            }
            super.render(g, mouseX, mouseY, partialTick);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            drawKey(g, getX(), getY(), isHoveredOrFocused());
        }
    }
}
