package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.NotesCountPacket;
import com.mrgregles.bsp_server_utilities.web.WebNotes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The staff web panel's notes counts on this client (D321): the CLIENTS tab (by player) and the STAFF tab (by name) show
 * "2 notes / newest by Name, 1 h ago / read them on the staff panel" when the pointer is on a player with notes; nothing
 * for a player without.
 */
public final class ClientNotes {
    private static volatile Map<UUID, WebNotes.Count> byId = Map.of();
    private static volatile Map<String, WebNotes.Count> byName = Map.of();

    private ClientNotes() {
    }

    public static void accept(NotesCountPacket p) {
        Map<UUID, WebNotes.Count> ids = new HashMap<>();
        Map<String, WebNotes.Count> names = new HashMap<>();
        for (WebNotes.Count c : p.counts()) {
            ids.put(c.id(), c);
            if (!c.name().isEmpty()) {
                names.put(c.name().toLowerCase(Locale.ROOT), c);
            }
        }
        byId = ids;
        byName = names;
    }

    @Nullable
    public static WebNotes.Count of(@Nullable UUID id, @Nullable String name) {
        WebNotes.Count c = id == null ? null : byId.get(id);
        return c != null || name == null ? c : byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** The hover's lines (D321). */
    public static List<Component> lines(WebNotes.Count c, long now) {
        return List.of(
                Component.literal(Tr.t(c.n() == 1 ? "notes.one" : "notes.many", c.n())).withStyle(ChatFormatting.GOLD),
                Component.literal(Tr.t("notes.newest", c.by(), StaffFace.ago(Math.max(0, now - c.at())))).withStyle(ChatFormatting.GRAY),
                Component.literal(Tr.t("notes.read_on_panel")).withStyle(ChatFormatting.GRAY));
    }

    /** Draws the hover for a row when the pointer is on it and the player has notes. */
    public static void hover(GuiGraphics g, Font font, @Nullable UUID id, @Nullable String name, int mx, int my) {
        WebNotes.Count c = of(id, name);
        if (c == null) {
            return;
        }
        net.minecraft.client.gui.screens.Screen screen = net.minecraft.client.Minecraft.getInstance().screen;
        List<net.minecraft.util.FormattedCharSequence> seq = lines(c, System.currentTimeMillis()).stream().map(Component::getVisualOrderText).toList();
        if (screen != null) {
            screen.setTooltipForNextRenderPass(seq); // drawn after everything else on the screen (audit)
        } else {
            g.renderTooltip(font, seq, mx, my);
        }
    }
}
