package com.mrgregles.bsp_server_utilities.serverimages;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * A server's tag in chat (owner decisions D189 to D197): a translatable part whose key names the server
 * ({@value #KEY}{@code <name in lower case>}) in this mod's font, white so the image keeps its own colours, with the
 * grey {@code [BSP-Spawn]} as its fallback and the server's name as its hover. This mod's client turns the key into the
 * server's image once it has it; any other reader (the console, the logs, a client without the image) reads the
 * fallback. Velocity Additions builds the same piece for relayed chat (request SU-VA1).
 */
public final class ServerTag {
    /** The start of a server image's translation key; the client answers it with the image's character. */
    public static final String KEY = "bsp_server_utilities.server_image.";
    /** The font server images are drawn in: their characters, then the default font for everything else. */
    public static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, "server_tags");

    private ServerTag() {
    }

    /** The translation key for a server's image. */
    public static String key(String server) {
        return KEY + server.toLowerCase(Locale.ROOT);
    }

    /** The tag for a server: its image where the client has one, else {@code [server]} in grey; the name on hover. */
    public static MutableComponent of(String server) {
        Component text = Component.literal("[" + server + "]").withStyle(ChatFormatting.GRAY);
        return Component.translatableWithFallback(key(server), "%s", text)
                .withStyle(s -> s.withFont(FONT).withColor(ChatFormatting.WHITE)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(server).withStyle(ChatFormatting.WHITE))));
    }
}
