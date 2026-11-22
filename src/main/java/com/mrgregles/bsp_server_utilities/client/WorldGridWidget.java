package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mrgregles.bsp_server_utilities.menus.WorldGridLayout;
import com.mrgregles.bsp_server_utilities.mixin.WorldListEntryAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.LoadingDotsText;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.storage.LevelSummary;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The Select World grid (artboard W1): "+ Create new world" first, then a card for each of the vanilla list's worlds in
 * its own order (last played first), drawn in the Holo HUD look. It reads the list every frame and never keeps its own
 * copy, so the list's loading, search and rebuilds show at once; every action goes to vanilla: a click selects through
 * {@code list.setSelected} (which also sets the screen's buttons), a double-click or Enter calls the entry's
 * {@code joinWorld}, and the buttons below act on the list's selection as always.
 */
final class WorldGridWidget extends AbstractWidget {
    private static final int CARD = 0xFF10151C, BORDER = 0xFF2A2F3A, HOVER = 0xFF3A4150, TEAL = 0xFF19D3B0,
            GLOW = 0x5919D3B0, TEXT = 0xFFE6EAF2, MUTED = 0xFF9AA3B5, DIM_TEXT = 0xFF6B7180, TAG_BG = 0xD90B0E13,
            TRACK = 0xFF1C2026, THUMB = 0xFF3A4150, CORAL = 0xFFFF6B5C, GOLD = 0xFFFFD23A, CREATE_BG = 0x9910151C;
    /** Vanilla's own date format for the last-played time (its world list uses a plain {@code SimpleDateFormat}). */
    private static final DateFormat DATE = new SimpleDateFormat();
    /** Its date part alone ("22/11/2026"), for a card too narrow for the time as well (cards 96 wide at 640, SU-126). */
    private static final DateFormat DAY = DateFormat.getDateInstance(DateFormat.SHORT);
    /** Two clicks on the same card within this many ms play it, as in vanilla's list. */
    private static final long DOUBLE_CLICK_MS = 250L;
    /** The picture's brightness at rest; hovered it is drawn full. */
    private static final float DIMMED = 0.85f;

    private final SelectWorldScreen screen;
    private final WorldSelectionList list;
    private final Minecraft mc = Minecraft.getInstance();
    private double scroll;
    private boolean dragging;
    private double dragOffset;
    /** The keyboard stands on "+ Create new world" (the list then has no selection). */
    private boolean onCreate;
    @Nullable
    private Object lastSelected;
    private boolean firstFrame = true;
    @Nullable
    private Object lastClicked;
    private long lastClickAt;
    private int hovered = -1;

    WorldGridWidget(SelectWorldScreen screen, WorldSelectionList list, int x, int y, int w, int h) {
        super(x, y, w, h, Component.translatable("selectWorld.title"));
        this.screen = screen;
        this.list = list;
    }

    private List<WorldSelectionList.WorldListEntry> worlds() {
        List<WorldSelectionList.WorldListEntry> out = new ArrayList<>();
        for (WorldSelectionList.Entry e : list.children()) {
            if (e instanceof WorldSelectionList.WorldListEntry w) {
                out.add(w);
            }
        }
        return out;
    }

    /** The list still shows vanilla's "Loading" row: the worlds are being read. */
    private boolean loading() {
        for (WorldSelectionList.Entry e : list.children()) {
            if (!(e instanceof WorldSelectionList.WorldListEntry)) {
                return true;
            }
        }
        return false;
    }

    private WorldGridLayout.Layout layout(int worlds) {
        return WorldGridLayout.of(width, getY(), getY() + height, worlds + 1);
    }

    /** The card the keyboard and the selection stand on: 0 for create, i + 1 for world i, -1 for none. */
    private int current(List<WorldSelectionList.WorldListEntry> worlds) {
        if (onCreate) {
            return 0;
        }
        int i = list.getSelected() instanceof WorldSelectionList.WorldListEntry w ? worlds.indexOf(w) : -1;
        return i < 0 ? -1 : i + 1;
    }

    private static LevelSummary summary(WorldSelectionList.WorldListEntry e) {
        return ((WorldListEntryAccess) (Object) e).bspSu$summary();
    }

    private static boolean plain(LevelSummary s) {
        return !(s instanceof LevelSummary.SymlinkLevelSummary);
    }

    // ---- drawing ----

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        List<WorldSelectionList.WorldListEntry> worlds = worlds();
        WorldGridLayout.Layout l = layout(worlds.size());
        if (dragging && GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            dragging = false; // released outside the grid, where the release never reached it
        }
        Object selected = list.getSelected();
        if (selected != lastSelected || firstFrame) {
            if (selected != null) {
                onCreate = false;
                int i = selected instanceof WorldSelectionList.WorldListEntry w ? worlds.indexOf(w) : -1;
                if (i >= 0) {
                    scroll = l.scrollToShow(l.clamp(scroll), i + 1);
                }
            }
            lastSelected = selected;
            firstFrame = false;
        }
        scroll = l.clamp(scroll);
        int s = (int) scroll;
        boolean over = !dragging && mouseX >= getX() && mouseX < getX() + width && mouseY >= l.top() && mouseY < l.bottom();
        hovered = over ? l.cardAt(mouseX, mouseY, s) : -1;

        // one pixel of room left and right for the selected card's glow
        g.enableScissor(getX(), l.top(), getX() + width, l.bottom());
        for (int i = 0; i < l.cards(); i++) {
            int x = l.cardX(i), y = l.cardY(i, s);
            if (y + l.cardH() < l.top() || y > l.bottom()) {
                continue;
            }
            if (i == 0) {
                drawCreate(g, l, x, y, hovered == 0, onCreate && isFocused());
            } else {
                WorldSelectionList.WorldListEntry e = worlds.get(i - 1);
                drawWorld(g, l, e, i - 1, x, y, hovered == i, e == selected, mouseY);
            }
        }
        if (loading()) {
            Font font = mc.font;
            int below = l.cardY(0, s) + l.cardH();
            int mid = below + Math.max(0, l.bottom() - below) / 2 - 9;
            String text = Ui.fit(font, Tr.t("worlds.loading"), width - 2 * WorldGridLayout.MARGIN, 1f);
            g.drawString(font, text, getX() + (width - font.width(text)) / 2, mid, TEXT, false);
            String dots = LoadingDotsText.get(Util.getMillis());
            g.drawString(font, dots, getX() + (width - font.width(dots)) / 2, mid + 10, DIM_TEXT, false);
        }
        g.disableScissor();

        // the scrollbar's track always, its thumb when there is more than fits
        g.fill(l.barX(), l.top(), l.barX() + WorldGridLayout.BAR, l.bottom(), TRACK);
        int[] thumb = l.thumb(s);
        if (thumb != null) {
            boolean onThumb = dragging || (mouseX >= l.barX() - 1 && mouseX < l.barX() + WorldGridLayout.BAR + 1
                    && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1]);
            g.fill(l.barX(), thumb[0], l.barX() + WorldGridLayout.BAR, thumb[0] + thumb[1], onThumb ? TEAL : THUMB);
        }
    }

    private void drawCreate(GuiGraphics g, WorldGridLayout.Layout l, int x, int y, boolean hover, boolean cursor) {
        int w = l.cardW(), h = l.cardH();
        g.fill(x, y, x + w, y + h, CREATE_BG);
        if (cursor) {
            Ui.frame(g, x - 1, y - 1, w + 2, h + 2, GLOW);
            Ui.frame(g, x, y, w, h, TEAL);
        } else {
            dashed(g, x, y, w, h, hover ? HOVER : BORDER);
        }
        Font font = mc.font;
        int block = 16 + 5 + 8; // the "+" at twice the size, a gap, the words
        int top = y + (h - block) / 2;
        int plusW = font.width("+") * 2;
        Ui.text(g, font, "+", x + (w - plusW) / 2, top, TEAL, 2f);
        String words = Ui.fit(font, Tr.t("worlds.create"), w - 8, 1f);
        g.drawString(font, words, x + (w - font.width(words)) / 2, top + 21, hover || cursor ? TEXT : MUTED, false);
    }

    private static void dashed(GuiGraphics g, int x, int y, int w, int h, int colour) {
        for (int i = 0; i < w; i += 4) {
            int e = Math.min(x + i + 2, x + w);
            g.fill(x + i, y, e, y + 1, colour);
            g.fill(x + i, y + h - 1, e, y + h, colour);
        }
        for (int i = 0; i < h; i += 4) {
            int e = Math.min(y + i + 2, y + h);
            g.fill(x, y + i, x + 1, e, colour);
            g.fill(x + w - 1, y + i, x + w, e, colour);
        }
    }

    private void drawWorld(GuiGraphics g, WorldGridLayout.Layout l, WorldSelectionList.WorldListEntry e, int index, int x, int y,
                           boolean hover, boolean selected, int mouseY) {
        LevelSummary sum = summary(e);
        int w = l.cardW(), h = l.cardH(), ih = l.imageH();
        Font font = mc.font;
        g.fill(x, y, x + w, y + h, CARD);

        // the picture, "cover" style inside the border: vanilla's own icon texture (or its default), nearest-neighbour
        int pw = w - 2, ph = ih - 1;
        if (pw > 0 && ph > 0) {
            ResourceLocation tex = ((WorldListEntryAccess) (Object) e).bspSu$icon().textureLocation();
            float v = WorldGridLayout.coverRows(pw, ph)[0] * pw;
            float b = hover ? 1f : DIMMED;
            RenderSystem.enableBlend();
            g.setColor(b, b, b, 1f);
            g.blit(tex, x + 1, y + 1, pw, ph, 0f, v, pw, ph, pw, pw);
            g.setColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        }

        // what vanilla marks, in one row at the picture's top left, the most important first
        int tx = x + 4, ty = y + 4;
        boolean first = true;
        for (Tag t : tags(sum, selected)) {
            String text = t.text;
            int tw = font.width(text) + 6;
            if (tx + tw > x + w - 4) {
                if (!first) {
                    break; // the rest do not fit in the one row
                }
                // the first tag is always shown, cut with "…" on a card narrower than it
                text = Ui.fit(font, text, Math.max(0, x + w - 4 - tx - 6), 1f);
                tw = font.width(text) + 6;
            }
            g.fill(tx, ty, tx + tw, ty + 11, TAG_BG);
            g.drawString(font, text, tx + 3, ty + 2, t.colour, false);
            tx += tw + 3;
            first = false;
        }

        // the name and the info line
        String name = sum.getLevelName();
        if (name == null || name.isEmpty()) {
            name = I18n.get("selectWorld.world") + " " + (index + 1);
        }
        int room = w - 8;
        g.drawString(font, Ui.fit(font, name, room, 1f), x + 4, y + ih + 3, TEXT, false);
        String date = sum.getLastPlayed() != -1L ? DATE.format(new Date(sum.getLastPlayed())) : "";
        boolean bad = !plain(sum) || sum.isDisabled();
        String mode = bad ? sum.getInfo().getString() : modeText(sum);
        String info = date.isEmpty() ? mode : mode + " · " + date;
        if (font.width(info) > room && !date.isEmpty() && !bad) {
            info = date; // only the date when the mode does not fit beside it
            if (font.width(info) > room) {
                info = DAY.format(new Date(sum.getLastPlayed())); // and without the time when that is still too wide
            }
        }
        g.drawString(font, Ui.fit(font, info, room, 1f), x + 4, y + ih + 12, bad ? CORAL : MUTED, false);

        // the border: selected teal with a faint glow, hovered lighter
        if (selected) {
            Ui.frame(g, x - 1, y - 1, w + 2, h + 2, GLOW);
            Ui.frame(g, x, y, w, h, TEAL);
        } else {
            Ui.frame(g, x, y, w, h, hover ? HOVER : BORDER);
        }

        // vanilla's warnings, on the picture as vanilla shows them on its icon
        if (hover && mouseY < y + ih) {
            List<FormattedCharSequence> tip = warning(sum, font);
            if (!tip.isEmpty()) {
                screen.setTooltipForNextRenderPass(tip);
            }
        }
    }

    /** "Survival", "Creative, Cheats", "Hardcore": vanilla's mode words without the version it adds. */
    private static String modeText(LevelSummary s) {
        String mode = I18n.get(s.isHardcore() ? "gameMode.hardcore" : "gameMode." + s.getGameMode().getName());
        if (s.hasCheats()) {
            mode += ", " + I18n.get("selectWorld.cheats");
        }
        return mode;
    }

    private record Tag(String text, int colour) {
    }

    private static List<Tag> tags(LevelSummary s, boolean selected) {
        List<Tag> out = new ArrayList<>();
        if (plain(s)) {
            if (s.isLocked()) {
                out.add(new Tag(Tr.t("worlds.tag.locked"), CORAL));
            }
            if (s.requiresManualConversion() || !s.isCompatible()) {
                out.add(new Tag(Tr.t("worlds.tag.unsupported"), CORAL));
            } else if (s.askToOpenWorld()) {
                out.add(new Tag(Tr.t("worlds.tag.newer"), CORAL));
            } else if (s.levelVersion().minecraftVersion().getVersion() < SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
                out.add(new Tag(Tr.t("worlds.tag.older"), CORAL));
            }
            if (s.isHardcore()) {
                out.add(new Tag(Tr.t("worlds.tag.hardcore"), GOLD));
            }
            if (s.isExperimental() || s.isLifecycleExperimental()) {
                out.add(new Tag(Tr.t("worlds.tag.experimental"), GOLD));
            }
        }
        if (selected) {
            out.add(new Tag(Tr.t("worlds.tag.selected"), TEAL));
        }
        return out;
    }

    /** Vanilla's hover warnings for its world icon, in vanilla's words. */
    private static List<FormattedCharSequence> warning(LevelSummary s, Font font) {
        List<FormattedCharSequence> out = new ArrayList<>();
        if (!plain(s)) {
            out.addAll(font.split(s.getInfo(), 175));
        } else if (s.isLocked()) {
            out.addAll(font.split(Component.translatable("selectWorld.locked").withStyle(ChatFormatting.RED), 175));
        } else if (s.requiresManualConversion()) {
            out.addAll(font.split(Component.translatable("selectWorld.conversion.tooltip").withStyle(ChatFormatting.RED), 175));
        } else if (s.markVersionInList() && s.askToOpenWorld()) {
            out.add(Component.translatable("selectWorld.tooltip.fromNewerVersion1").withStyle(ChatFormatting.RED).getVisualOrderText());
            out.add(Component.translatable("selectWorld.tooltip.fromNewerVersion2").withStyle(ChatFormatting.RED).getVisualOrderText());
        }
        if (plain(s) && s.isLifecycleExperimental()) {
            out.addAll(font.split(Component.translatable("forge.experimentalsettings.tooltip"), 200));
        }
        return out;
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        List<WorldSelectionList.WorldListEntry> worlds = worlds();
        WorldGridLayout.Layout l = layout(worlds.size());
        int s = l.clamp(scroll);
        int[] thumb = l.thumb(s);
        if (thumb != null && mouseX >= l.barX() - 2 && mouseX < l.barX() + WorldGridLayout.BAR + 2) {
            if (mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1]) {
                dragOffset = mouseY - thumb[0];
            } else {
                dragOffset = thumb[1] / 2.0;
                scroll = l.scrollForThumb(mouseY - dragOffset, thumb[1]);
            }
            dragging = true;
            return true;
        }
        int i = l.cardAt(mouseX, mouseY, s);
        if (i < 0) {
            return false;
        }
        if (i == 0) {
            onCreate = true;
            create();
            return true;
        }
        WorldSelectionList.WorldListEntry e = worlds.get(i - 1);
        if (summary(e).isDisabled()) {
            return true; // as vanilla: a locked or unreadable world is not selected by a click
        }
        onCreate = false;
        list.setSelected(e);
        long now = Util.getMillis();
        if (e == lastClicked && now - lastClickAt < DOUBLE_CLICK_MS) {
            lastClicked = null;
            e.joinWorld();
            return true;
        }
        lastClicked = e;
        lastClickAt = now;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!dragging) {
            return false;
        }
        WorldGridLayout.Layout l = layout(worlds().size());
        int[] thumb = l.thumb(l.clamp(scroll));
        if (thumb != null) {
            scroll = l.scrollForThumb(mouseY - dragOffset, thumb[1]);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        WorldGridLayout.Layout l = layout(worlds().size());
        if (l.maxScroll() <= 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scroll = l.clamp(scroll - delta * l.rowH() / 2.0);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!active || !visible) {
            return false;
        }
        List<WorldSelectionList.WorldListEntry> worlds = worlds();
        int cur = current(worlds);
        if (CommonInputs.selected(key)) {
            if (cur == 0) {
                create();
                return true;
            }
            var chosen = list.getSelectedOpt();
            chosen.ifPresent(WorldSelectionList.WorldListEntry::joinWorld);
            return chosen.isPresent();
        }
        int dx = 0, dy = 0;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> dx = -1;
            case GLFW.GLFW_KEY_RIGHT -> dx = 1;
            case GLFW.GLFW_KEY_UP -> dy = -1;
            case GLFW.GLFW_KEY_DOWN -> dy = 1;
            default -> {
                return false;
            }
        }
        WorldGridLayout.Layout l = layout(worlds.size());
        int to = l.move(cur, dx, dy);
        if (to < 0) {
            return false; // nothing that way: the screen moves the focus on, as with vanilla's list
        }
        if (to == 0) {
            onCreate = true;
            list.setSelected(null);
        } else {
            onCreate = false;
            list.setSelected(worlds.get(to - 1)); // as vanilla's arrows, a locked world can be chosen to delete it
        }
        scroll = l.scrollToShow(l.clamp(scroll), to);
        return true;
    }

    /** Reached by Tab or an arrow with nothing chosen: the first world (the create card when there is none), as vanilla's list. */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused && !onCreate && list.getSelected() == null && mc.getLastInputType().isKeyboard() && !loading()) {
            List<WorldSelectionList.WorldListEntry> worlds = worlds();
            if (worlds.isEmpty()) {
                onCreate = true;
            } else {
                list.setSelected(worlds.get(0));
            }
            WorldGridLayout.Layout l = layout(worlds.size());
            scroll = l.scrollToShow(l.clamp(scroll), worlds.isEmpty() ? 0 : 1);
        }
    }

    private void create() {
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        CreateWorldScreen.openFresh(mc, screen);
    }

    // ---- narration ----

    private Component narration(List<WorldSelectionList.WorldListEntry> worlds, int card) {
        if (card == 0) {
            return Component.translatable("narrator.select", Tr.t("worlds.create"));
        }
        return worlds.get(card - 1).getNarration();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        List<WorldSelectionList.WorldListEntry> worlds = worlds();
        if (loading()) {
            out.add(NarratedElementType.TITLE, Component.translatable("selectWorld.loading_list"));
            return;
        }
        int cur = current(worlds);
        if (isFocused() && cur >= 0) {
            out.add(NarratedElementType.TITLE, narration(worlds, cur));
            out.add(NarratedElementType.POSITION, Component.translatable("narrator.position.list", cur + 1, worlds.size() + 1));
        } else if (hovered >= 0 && hovered <= worlds.size()) {
            out.add(NarratedElementType.TITLE, narration(worlds, hovered));
        } else {
            out.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
