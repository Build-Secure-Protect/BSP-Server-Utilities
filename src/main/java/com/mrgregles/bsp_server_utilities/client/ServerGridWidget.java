package com.mrgregles.bsp_server_utilities.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mrgregles.bsp_server_utilities.ServerUtilsClientConfig;
import com.mrgregles.bsp_server_utilities.compat.BuiltinServers;
import com.mrgregles.bsp_server_utilities.menus.ServerGridLayout;
import com.mrgregles.bsp_server_utilities.menus.WorldGridLayout;
import com.mrgregles.bsp_server_utilities.mixin.ServerEntryAccess;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.ForgeHooksClient;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Multiplayer grid: the featured server (BSP, the client setting {@code menus.featuredServer}) in a full-width card on
 * top, then every other saved server and every LAN game as a square card, in the vanilla list's own order, then vanilla's
 * "Scanning for games on your local network". It reads the list every frame and keeps no copy. Vanilla still pings each
 * server and uploads its icon: the grid runs each shown entry's own render with drawing switched off (an empty clip), as
 * vanilla's list runs it for each shown row. Every action goes to vanilla: a click selects through
 * {@code screen.setSelected} (which also sets the buttons), a double-click or Enter joins through
 * {@code joinSelectedServer}, Shift and an arrow moves a server in the list through the entry's own keys.
 */
final class ServerGridWidget extends AbstractWidget {
    private static final int CARD = 0xFF10151C, HERO_TINT = 0x0F19D3B0, BORDER = 0xFF2A2F3A, HOVER = 0xFF3A4150,
            TEAL = 0xFF19D3B0, GLOW = 0x5919D3B0, TEXT = 0xFFE6EAF2, MUTED = 0xFF9AA3B5, DIM_TEXT = 0xFF6B7180,
            TAG_BG = 0xD90B0E13, TRACK = 0xFF1C2026, THUMB = 0xFF3A4150, CORAL = 0xFFFF6B5C, GOLD = 0xFFFFD23A,
            WELL = 0xFF0C0E12, GREEN = 0xFF8FE04A, BAR_OFF = 0xFF2A2F3A;
    private static final ResourceLocation NO_ICON = new ResourceLocation("textures/misc/unknown_server.png");
    private static final long DOUBLE_CLICK_MS = 250L;
    private static final float DIMMED = 0.85f;
    /** The hero card's icon. */
    private static final int HERO_ICON = 48;

    private final JoinMultiplayerScreen screen;
    private final ServerSelectionList list;
    private final Minecraft mc = Minecraft.getInstance();
    private double scroll;
    private boolean dragging;
    private double dragOffset;
    @Nullable
    private Object lastSelected;
    private boolean firstFrame = true;
    @Nullable
    private Object lastClicked;
    private long lastClickAt;
    private int hovered = -1;
    /** The keys drawn on the hovered card this frame (move earlier, join, move later), checked before a card click. */
    private final List<Ui.Hit> keys = new ArrayList<>();

    ServerGridWidget(JoinMultiplayerScreen screen, ServerSelectionList list, int x, int y, int w, int h) {
        super(x, y, w, h, Component.translatable("multiplayer.title"));
        this.screen = screen;
        this.list = list;
        this.scroll = GriddedLists.scrollOf(list); // vanilla keeps this list (and its scroll) across re-inits
    }

    /** The cards in order (the featured server first when there is one), and whether vanilla's scanning row is there. */
    private record Model(List<ServerSelectionList.Entry> cards, boolean hero, boolean scanning) {
    }

    private Model model() {
        List<ServerSelectionList.Entry> online = new ArrayList<>(), lan = new ArrayList<>();
        boolean scanning = false;
        for (ServerSelectionList.Entry e : list.children()) {
            if (e instanceof ServerSelectionList.OnlineServerEntry) {
                online.add(e);
            } else if (e instanceof ServerSelectionList.NetworkServerEntry) {
                lan.add(e);
            } else {
                scanning = true; // vanilla's LAN header
            }
        }
        String featured = ServerUtilsClientConfig.featuredServer();
        // the first match, one Builtin Servers added before a player's own (the mod adds the pack's servers last)
        ServerSelectionList.Entry hero = null;
        for (ServerSelectionList.Entry e : online) {
            ServerData d = ((ServerSelectionList.OnlineServerEntry) e).getServerData();
            if (ServerGridLayout.isFeatured(d.ip, featured)) {
                if (BuiltinServers.isBuiltin(d)) {
                    hero = e;
                    break;
                }
                if (hero == null) {
                    hero = e;
                }
            }
        }
        List<ServerSelectionList.Entry> cards = new ArrayList<>();
        if (hero != null) {
            cards.add(hero);
        }
        for (ServerSelectionList.Entry e : online) {
            if (e != hero) {
                cards.add(e);
            }
        }
        cards.addAll(lan);
        return new Model(cards, hero != null, scanning);
    }

    private ServerGridLayout.Layout layout(Model m) {
        return ServerGridLayout.of(width, getY(), getY() + height, m.hero, m.cards.size() - (m.hero ? 1 : 0), m.scanning);
    }

    private int current(Model m) {
        Object sel = list.getSelected();
        return sel == null ? -1 : m.cards.indexOf(sel);
    }

    // ---- what vanilla knows about a server ----

    private static boolean pingDone(ServerData d) {
        return d.pinged && d.ping != -2L;
    }

    private static boolean compatible(ServerData d) {
        return d.protocol == SharedConstants.getCurrentVersion().getProtocolVersion();
    }

    // ---- drawing ----

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Model m = model();
        ServerGridLayout.Layout l = layout(m);
        if (dragging && GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            dragging = false; // released outside the grid, where the release never reached it
        }
        Object selected = list.getSelected();
        if (selected != lastSelected || firstFrame) {
            int i = selected == null ? -1 : m.cards.indexOf(selected);
            if (i >= 0) {
                scroll = l.scrollToShow(l.clamp(scroll), i);
            }
            lastSelected = selected;
            firstFrame = false;
        }
        scroll = l.clamp(scroll);
        int s = (int) scroll;
        boolean inArea = mouseX >= getX() && mouseX < getX() + width && mouseY >= l.top() && mouseY < l.bottom();
        boolean over = !dragging && inArea;
        hovered = over ? l.cardAt(mouseX, mouseY, s) : -1;
        keys.clear();
        // the hovers of a card partly scrolled out answer only inside the area, never over the title or the buttons
        int mx = inArea ? mouseX : -10000, my = inArea ? mouseY : -10000;

        g.enableScissor(getX(), l.top(), getX() + width, l.bottom());
        for (int i = 0; i < l.cards(); i++) {
            int x = l.cardX(i), y = l.cardY(i, s), w = l.cardW(i), h = l.cardH(i);
            if (y + h < l.top() || y > l.bottom()) {
                continue;
            }
            ServerSelectionList.Entry e = m.cards.get(i);
            boolean hover = hovered == i, sel = e == selected;
            if (e instanceof ServerSelectionList.OnlineServerEntry o) {
                vanillaWork(g, o, x, y, w, h, partialTick);
                if (l.isHero(i)) {
                    drawHero(g, o, x, y, w, h, hover, sel, mx, my);
                } else {
                    drawSquare(g, o, x, y, w, hover, sel, mx, my);
                }
                if (hover || mc.options.touchscreen().get()) {
                    drawKeys(g, o, l.isHero(i) ? x + w - 6 : x + w / 2, l.isHero(i) ? y + h - 16 : y + w - ServerGridLayout.SQUARE_TEXT_H - 15,
                            l.isHero(i), mx, my);
                }
            } else if (e instanceof ServerSelectionList.NetworkServerEntry n) {
                drawLan(g, n, x, y, w, hover, sel);
            }
            border(g, x, y, w, h, hover, sel);
        }
        if (l.line()) {
            Font font = mc.font;
            int y = l.lineY(s);
            String text = Ui.fit(font, Component.translatable("lanServer.scanning").getString(), width - 2 * WorldGridLayout.MARGIN, 1f);
            g.drawString(font, text, getX() + (width - font.width(text)) / 2, y + 1, DIM_TEXT, false);
            String dots = LoadingDotsText.get(Util.getMillis());
            g.drawString(font, dots, getX() + (width - font.width(dots)) / 2, y + 11, DIM_TEXT, false);
        }
        g.disableScissor();
        GriddedLists.rememberScroll(list, scroll);

        g.fill(l.barX(), l.top(), l.barX() + WorldGridLayout.BAR, l.bottom(), TRACK);
        int[] thumb = l.thumb(s);
        if (thumb != null) {
            boolean onThumb = dragging || (mouseX >= l.barX() - 1 && mouseX < l.barX() + WorldGridLayout.BAR + 1
                    && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1]);
            g.fill(l.barX(), thumb[0], l.barX() + WorldGridLayout.BAR, thumb[0] + thumb[1], onThumb ? TEAL : THUMB);
        }
    }

    /**
     * Vanilla's own work for a shown server, with nothing drawn: its render pings the server the first time it is shown
     * and uploads the icon when the server sends a new one (and other mods' additions to that render run too). The mouse
     * is passed far away so it sets no tooltip.
     */
    private void vanillaWork(GuiGraphics g, ServerSelectionList.OnlineServerEntry e, int x, int y, int w, int h, float partialTick) {
        int index = list.children().indexOf(e);
        g.enableScissor(0, 0, 0, 0);
        try {
            e.render(g, Math.max(0, index), y, x, w, h, -10000, -10000, false, partialTick);
        } finally {
            g.disableScissor();
        }
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean sel) {
        if (sel) {
            Ui.frame(g, x - 1, y - 1, w + 2, h + 2, GLOW);
            Ui.frame(g, x, y, w, h, TEAL);
        } else {
            Ui.frame(g, x, y, w, h, hover ? HOVER : BORDER);
        }
    }

    private static void icon(GuiGraphics g, ResourceLocation tex, int x, int y, int size, boolean hover) {
        float b = hover ? 1f : DIMMED;
        RenderSystem.enableBlend();
        g.setColor(b, b, b, 1f);
        g.blit(tex, x, y, size, size, 0f, 0f, size, size, size, size);
        g.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    private record Tag(String text, int colour, @Nullable Component hover) {
    }

    private static List<Tag> tags(ServerData d, boolean lan, boolean selected) {
        List<Tag> out = new ArrayList<>();
        if (lan) {
            out.add(new Tag(Tr.t("servers.tag.lan"), TEAL, null));
        } else {
            if (!compatible(d)) {
                out.add(new Tag(Tr.t("servers.tag.incompatible"), CORAL, null));
            }
            if (BuiltinServers.isBuiltin(d) && BuiltinServers.badgeVisible()) {
                out.add(new Tag(BuiltinServers.badge().getString().toUpperCase(java.util.Locale.ROOT), GOLD, BuiltinServers.description()));
            }
        }
        if (selected) {
            out.add(new Tag(Tr.t("worlds.tag.selected"), TEAL, null));
        }
        return out;
    }

    /** Tags in one row from {@code x} to {@code right}: the first always (fitted), the rest while they fit. */
    private void drawTags(GuiGraphics g, List<Tag> tags, int x, int y, int right, int mouseX, int mouseY) {
        Font font = mc.font;
        boolean first = true;
        for (Tag t : tags) {
            String text = t.text;
            int tw = font.width(text) + 6;
            if (x + tw > right) {
                if (!first || right - x - 6 < 12) {
                    break; // the rest do not fit, or there is no room even for a cut first tag
                }
                text = Ui.fit(font, text, right - x - 6, 1f);
                tw = font.width(text) + 6;
            }
            g.fill(x, y, x + tw, y + 11, TAG_BG);
            g.drawString(font, text, x + 3, y + 2, t.colour, false);
            if (t.hover != null && mouseX >= x && mouseX < x + tw && mouseY >= y && mouseY < y + 11) {
                screen.setToolTip(List.of(t.hover));
            }
            x += tw + 3;
            first = false;
        }
    }

    /** Five bars, as vanilla's ping icon tells it: green by the ping, cycling while pinging, coral when unreachable. */
    private void pingBars(GuiGraphics g, ServerData d, int index, int x, int y) {
        int lit;
        int colour = GREEN;
        if (!compatible(d) || (pingDone(d) && d.ping < 0)) {
            lit = 1;
            colour = CORAL;
        } else if (pingDone(d)) {
            lit = d.ping < 150 ? 5 : d.ping < 300 ? 4 : d.ping < 600 ? 3 : d.ping < 1000 ? 2 : 1;
        } else {
            int step = (int) (Util.getMillis() / 100L + index * 2L & 7L);
            lit = 1 + (step > 4 ? 8 - step : step);
            lit = Math.min(5, lit);
            colour = TEAL;
        }
        int[] heights = {2, 3, 5, 6, 8};
        for (int i = 0; i < 5; i++) {
            int bx = x + i * 3;
            g.fill(bx, y + 8 - heights[i], bx + 2, y + 8, i < lit ? colour : BAR_OFF);
        }
    }

    /** The hover over the bars: vanilla's words for the ping. */
    private static Component pingWords(ServerData d) {
        if (!compatible(d)) {
            return Component.translatable("multiplayer.status.incompatible");
        }
        if (!pingDone(d)) {
            return Component.translatable("multiplayer.status.pinging");
        }
        if (d.ping < 0) {
            return Component.translatable("multiplayer.status.no_connection");
        }
        return Component.translatable("multiplayer.status.ping", d.ping);
    }

    /** What stands where vanilla puts the player count: the version in red when it cannot be joined. */
    private static Component countWords(ServerData d) {
        if (!compatible(d)) {
            return d.version.copy().withStyle(ChatFormatting.RED);
        }
        return d.status == null ? Component.empty() : d.status;
    }

    /**
     * The player count with its bars at the right end ({@code right} the bars' right edge); the count only when it fits
     * right of {@code left}. Returns where the drawn part begins.
     */
    private int countAndBars(GuiGraphics g, ServerData d, int index, int left, int right, int y, int mouseX, int mouseY) {
        Font font = mc.font;
        int barsX = right - 14;
        pingBars(g, d, index, barsX, y);
        if (mouseX >= barsX - 1 && mouseX < right + 1 && mouseY >= y - 1 && mouseY < y + 9) {
            screen.setToolTip(Collections.singletonList(pingWords(d)));
        }
        Component count = countWords(d);
        int cw = font.width(count);
        int cx = barsX - 5 - cw;
        if (cx < left) {
            return barsX; // too narrow for the count: the bars alone
        }
        g.drawString(font, count, cx, y, MUTED, false);
        boolean listed = !compatible(d) || (pingDone(d) && d.ping >= 0); // as vanilla: also over an incompatible version
        if (listed && !d.playerList.isEmpty() && mouseX >= cx && mouseX < cx + cw && mouseY >= y - 1 && mouseY < y + 9) {
            screen.setToolTip(d.playerList); // who is on, as vanilla's hover over the count
        }
        return cx;
    }

    private void forgeInfo(GuiGraphics g, ServerData d, int x, int y, int w, int mouseX, int mouseY) {
        // Forge's mods-match icon and its hover, placed below the count: at (x + w - 22, y + 18)
        ForgeHooksClient.drawForgePingInfo(screen, d, g, x, y + 8, w - 4, mouseX - x, mouseY - (y + 8));
    }

    private void drawHero(GuiGraphics g, ServerSelectionList.OnlineServerEntry e, int x, int y, int w, int h, boolean hover,
                          boolean sel, int mouseX, int mouseY) {
        ServerData d = e.getServerData();
        Font font = mc.font;
        g.fill(x, y, x + w, y + h, CARD);
        g.fill(x, y, x + w, y + h, HERO_TINT);
        int ix = x + 6, iy = y + (h - HERO_ICON) / 2;
        g.fill(ix - 1, iy - 1, ix + HERO_ICON + 1, iy + HERO_ICON + 1, WELL);
        icon(g, ((ServerEntryAccess) (Object) e).bspSu$icon().textureLocation(), ix, iy, HERO_ICON, hover);
        int tx = ix + HERO_ICON + 8, right = x + w - 6;
        int index = list.children().indexOf(e);
        int countLeft = countAndBars(g, d, index, tx + 40, right, y + 7, mouseX, mouseY);
        // the name, then its tags, in the room left before the count: the tags may take up to half of it
        List<Tag> tags = tags(d, false, sel);
        int room = Math.max(0, countLeft - 8 - tx), tagsW = 0;
        for (Tag t : tags) {
            tagsW += font.width(t.text) + 6 + 3;
        }
        int nameRoom = tags.isEmpty() ? room : Math.max(room / 2, room - tagsW - 3);
        String name = Ui.fit(font, d.name, nameRoom, 1f);
        g.drawString(font, name, tx, y + 7, TEXT, false);
        drawTags(g, tags, tx + font.width(name) + 6, y + 5, countLeft - 6, mouseX, mouseY);
        // the message of the day, two lines as vanilla shows it, in the server's own colours
        List<FormattedCharSequence> motd = font.split(d.motd == null ? Component.empty() : d.motd, Math.max(1, right - 22 - tx));
        for (int i = 0; i < Math.min(2, motd.size()); i++) {
            g.drawString(font, motd.get(i), tx, y + 22 + 11 * i, MUTED, false);
        }
        forgeInfo(g, d, x, y, w, mouseX, mouseY);
    }

    private void drawSquare(GuiGraphics g, ServerSelectionList.OnlineServerEntry e, int x, int y, int w, boolean hover,
                            boolean sel, int mouseX, int mouseY) {
        ServerData d = e.getServerData();
        Font font = mc.font;
        int ih = w - ServerGridLayout.SQUARE_TEXT_H;
        g.fill(x, y, x + w, y + w, CARD);
        g.fill(x + 1, y + 1, x + w - 1, y + ih, WELL);
        int size = iconSize(w, ih);
        icon(g, ((ServerEntryAccess) (Object) e).bspSu$icon().textureLocation(), x + (w - size) / 2, y + (ih - size) / 2, size, hover);
        // the tags stop short of Forge's mods-match icon in the top right corner when the server reports one
        drawTags(g, tags(d, false, sel), x + 4, y + 4, d.forgeData != null ? x + w - 24 : x + w - 4, mouseX, mouseY);
        int room = w - 8;
        g.drawString(font, Ui.fit(font, d.name, room, 1f), x + 4, y + ih + 3, TEXT, false);
        List<FormattedCharSequence> motd = font.split(d.motd == null ? Component.empty() : d.motd, Math.max(1, room));
        if (!motd.isEmpty()) {
            g.drawString(font, motd.get(0), x + 4, y + ih + 12, MUTED, false);
        }
        int index = list.children().indexOf(e);
        countAndBars(g, d, index, x + 4, x + w - 4, y + ih + 22, mouseX, mouseY);
        // Forge's mods-match icon in the picture's top right corner, clear of the tags and the icon
        ForgeHooksClient.drawForgePingInfo(screen, d, g, x, y - 6, w - 4, mouseX - x, mouseY - (y - 6));
    }

    /** A square's icon: as big as its picture area allows, leaving 22 px each side for the tags and Forge's icon. */
    private static int iconSize(int w, int ih) {
        return Math.max(8, Math.min(ih - 8, w - 44));
    }

    private void drawLan(GuiGraphics g, ServerSelectionList.NetworkServerEntry e, int x, int y, int w, boolean hover, boolean sel) {
        Font font = mc.font;
        int ih = w - ServerGridLayout.SQUARE_TEXT_H;
        g.fill(x, y, x + w, y + w, CARD);
        g.fill(x + 1, y + 1, x + w - 1, y + ih, WELL);
        int size = iconSize(w, ih);
        icon(g, NO_ICON, x + (w - size) / 2, y + (ih - size) / 2, size, hover);
        drawTags(g, tags(null, true, sel), x + 4, y + 4, x + w - 4, -1, -1);
        int room = w - 8;
        g.drawString(font, Ui.fit(font, Component.translatable("lanServer.title").getString(), room, 1f), x + 4, y + ih + 3, TEXT, false);
        g.drawString(font, Ui.fit(font, e.getServerData().getMotd(), room, 1f), x + 4, y + ih + 12, MUTED, false);
        String address = mc.options.hideServerAddress ? Component.translatable("selectServer.hiddenAddress").getString()
                : e.getServerData().getAddress();
        g.drawString(font, Ui.fit(font, address, room, 1f), x + 4, y + ih + 22, DIM_TEXT, false);
    }

    /**
     * Vanilla's icon actions on a hovered server: move it one place earlier in the list, join it, move it one place later
     * (vanilla's arrows and join overlay on its row icon). The row is centred on {@code x} (right-aligned on it for the
     * featured card) at {@code y}.
     */
    private void drawKeys(GuiGraphics g, ServerSelectionList.OnlineServerEntry e, int x, int y, boolean rightAligned, int mouseX, int mouseY) {
        Font font = mc.font;
        int index = list.children().indexOf(e);
        if (index < 0) {
            return;
        }
        boolean earlier = index > 0, later = index < screen.getServers().size() - 1;
        String join = Tr.t("servers.join");
        int jw = font.width(join) + 8, kw = 13, gap = 3;
        int total = kw + gap + jw + gap + kw;
        int left = rightAligned ? x - total : x - total / 2;
        key(g, left, y, kw, "<", earlier, mouseX, mouseY, () -> ((ServerEntryAccess) (Object) e).bspSu$swap(index, index - 1));
        key(g, left + kw + gap, y, jw, join, true, mouseX, mouseY, () -> {
            select(e);
            screen.joinSelectedServer();
        });
        key(g, left + kw + gap + jw + gap, y, kw, ">", later, mouseX, mouseY, () -> ((ServerEntryAccess) (Object) e).bspSu$swap(index, index + 1));
    }

    private void key(GuiGraphics g, int x, int y, int w, String text, boolean on, int mouseX, int mouseY, Runnable action) {
        Font font = mc.font;
        boolean hot = on && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 11;
        g.fill(x, y, x + w, y + 11, TAG_BG);
        Ui.frame(g, x, y, w, 11, hot ? TEAL : HOVER);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + 2, !on ? DIM_TEXT : hot ? TEAL : TEXT, false);
        if (on) {
            keys.add(new Ui.Hit(x, y, w, 11, action));
        }
    }

    // ---- input ----

    private void select(ServerSelectionList.Entry e) {
        screen.setSelected(e); // vanilla: the list's selection and the buttons
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        Model m = model();
        ServerGridLayout.Layout l = layout(m);
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
        if (mouseY >= l.top() && mouseY < l.bottom()) {
            for (Ui.Hit k : List.copyOf(keys)) {
                if (k.contains(mouseX, mouseY)) {
                    k.action().run();
                    return true;
                }
            }
        }
        int i = l.cardAt(mouseX, mouseY, s);
        if (i < 0) {
            return false;
        }
        ServerSelectionList.Entry e = m.cards.get(i);
        select(e);
        long now = Util.getMillis();
        if (e == lastClicked && now - lastClickAt < DOUBLE_CLICK_MS) {
            lastClicked = null;
            screen.joinSelectedServer();
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
        ServerGridLayout.Layout l = layout(model());
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
        ServerGridLayout.Layout l = layout(model());
        if (l.maxScroll() <= 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }
        scroll = l.clamp(scroll - delta * (l.cardW() + WorldGridLayout.GAP) / 2.0);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!active || !visible) {
            return false;
        }
        Model m = model();
        int cur = current(m);
        if (CommonInputs.selected(key)) {
            if (cur < 0) {
                return false;
            }
            screen.joinSelectedServer();
            return true;
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
        if (Screen.hasShiftDown()) {
            // move the chosen server one place in the list, with vanilla's own Shift+Up / Shift+Down
            if (list.getSelected() instanceof ServerSelectionList.OnlineServerEntry o) {
                o.keyPressed(dx < 0 || dy < 0 ? GLFW.GLFW_KEY_UP : GLFW.GLFW_KEY_DOWN, scanCode, modifiers);
                return true;
            }
            return false;
        }
        ServerGridLayout.Layout l = layout(m);
        int to = l.move(cur, dx, dy);
        if (to < 0) {
            return false; // nothing that way: the screen moves the focus on, as with vanilla's list
        }
        select(m.cards.get(to));
        scroll = l.scrollToShow(l.clamp(scroll), to);
        return true;
    }

    /** Reached by Tab or an arrow with nothing chosen: the first card, as vanilla's list. */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused && list.getSelected() == null && mc.getLastInputType().isKeyboard()) {
            Model m = model();
            if (!m.cards.isEmpty()) {
                select(m.cards.get(0));
                ServerGridLayout.Layout l = layout(m);
                scroll = l.scrollToShow(l.clamp(scroll), 0);
            }
        }
    }

    // ---- narration ----

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        Model m = model();
        int cur = current(m);
        if (isFocused() && cur >= 0) {
            out.add(NarratedElementType.TITLE, m.cards.get(cur).getNarration());
            out.add(NarratedElementType.POSITION, Component.translatable("narrator.position.list", cur + 1, m.cards.size()));
        } else if (hovered >= 0 && hovered < m.cards.size()) {
            out.add(NarratedElementType.TITLE, m.cards.get(hovered).getNarration());
        } else if (m.scanning && m.cards.isEmpty()) {
            out.add(NarratedElementType.TITLE, Component.translatable("lanServer.scanning"));
        } else {
            out.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
