package com.mrgregles.bsp_server_utilities.announce;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Server announcements (the owner's brief of 2026-11-05; decisions D233 to D245):
 * {@code /broadcast <chat|title> <colour> <player|all> <server|network> <message>}, for operators, BSP-Core admins, the
 * console and RCON.
 *
 * <ul>
 *   <li>Chat: a gold "[Announcement]", then the message in the colour (D235). Title: in the middle of the screen for
 *   about 5 seconds, a {@code |} splitting it from a smaller subtitle (D236, D237), and written in chat too (D238).
 *   Every announcement plays a short chime (D239). {@code &} codes work as on Display Panels (D240). Players see only
 *   the message (D241); the action log keeps the command.</li>
 *   <li>Colours: Minecraft's 16 names, everyday names mapped onto them, and {@code RRGGBB} or {@code "#RRGGBB"} (D234).</li>
 *   <li>{@code network} shows it here and sends a {@value #KIND} notice to the other servers, each showing it to its own
 *   players (all, or the named player if they are there) and writing it to its console (D243). A named player is found
 *   here, or on the network through the client check's reports; one online nowhere is refused (D242). Without shared
 *   storage {@code network} reaches this server only, and says so.</li>
 * </ul>
 * Everything is vanilla chat, title and sound packets: players need nothing new.
 */
public final class Broadcast {
    public static final String KIND = "broadcast";
    public static final int MAX_TEXT = 256, FADE_IN = 10, STAY = 100, FADE_OUT = 20;

    /** One announcement: in chat or as a title, its colour (0xRRGGBB), the player named (empty for all), the text as typed. */
    public record Announcement(boolean title, int colour, String player, String text) {
        public String encode() {
            return String.join("\n", title ? "title" : "chat", String.format(Locale.ROOT, "%06X", colour), player, text);
        }

        @Nullable
        public static Announcement decode(String s) {
            String[] f = s.split("\n", 4);
            if (f.length != 4 || !(f[0].equals("title") || f[0].equals("chat"))) {
                return null;
            }
            try {
                return new Announcement(f[0].equals("title"), Integer.parseInt(f[1], 16) & 0xFFFFFF, f[2], clean(f[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** The big line of a title (everything before {@code |}). */
        public String titleText() {
            int i = text.indexOf('|');
            return (i < 0 ? text : text.substring(0, i)).strip();
        }

        /** The smaller line under a title (everything after the first {@code |}), or empty. */
        public String subtitleText() {
            int i = text.indexOf('|');
            return i < 0 ? "" : text.substring(i + 1).strip();
        }

        /** What chat shows: the message, a title's two lines joined. */
        public String chatText() {
            if (!title) {
                return text;
            }
            String t = titleText(), s = subtitleText();
            return t.isEmpty() ? s : s.isEmpty() ? t : t + " " + s;
        }
    }

    /** What one player was shown (tests): the chat line, and a title's two lines (null for chat only). */
    public record Shown(Component chat, @Nullable Component title, @Nullable Component subtitle) {
    }

    /** Minecraft's 16 colours by name, then everyday names for them (D234). */
    private static final Map<String, ChatFormatting> COLOURS = new LinkedHashMap<>();

    static {
        for (ChatFormatting f : ChatFormatting.values()) {
            if (f.isColor()) {
                COLOURS.put(f.getName(), f);
            }
        }
        String[][] also = {{"grey", "gray"}, {"dark_grey", "dark_gray"}, {"light_gray", "gray"}, {"light_grey", "gray"}, {"silver", "gray"},
                {"orange", "gold"}, {"purple", "dark_purple"}, {"pink", "light_purple"}, {"magenta", "light_purple"}, {"cyan", "aqua"},
                {"light_blue", "aqua"}, {"lime", "green"}, {"light_green", "green"}, {"navy", "dark_blue"}, {"teal", "dark_aqua"}, {"maroon", "dark_red"}};
        for (String[] a : also) {
            COLOURS.put(a[0], COLOURS.get(a[1]));
        }
    }

    @Nullable
    private static Supplier<Collection<ServerPlayer>> playersOverride;
    @Nullable
    private static BiConsumer<ServerPlayer, Shown> shownOverride;

    private Broadcast() {
    }

    /** Minecraft's 16 colour names, for suggestions and the refusal. */
    public static List<String> colourNames() {
        List<String> out = new ArrayList<>();
        for (ChatFormatting f : ChatFormatting.values()) {
            if (f.isColor()) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /** A colour word as 0xRRGGBB: a name, an everyday name, {@code RRGGBB} or {@code #RRGGBB}; null when it is none. */
    @Nullable
    public static Integer colour(String word) {
        String w = word.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        ChatFormatting f = COLOURS.get(w);
        if (f != null) {
            return f.getColor();
        }
        String hex = w.startsWith("#") ? w.substring(1) : w;
        if (hex.matches("[0-9a-f]{6}")) {
            return Integer.parseInt(hex, 16);
        }
        return null;
    }

    /** The message as it may be shown: no control characters or section signs typed in, at most {@value #MAX_TEXT} characters. */
    public static String clean(String raw) {
        String t = raw.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").strip();
        return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT);
    }

    private static MutableComponent body(String text, int colour) {
        return Component.literal(PanelLayout.codes(text, false)).withStyle(s -> s.withColor(TextColor.fromRgb(colour)));
    }

    /** The chat line: the gold prefix, then the message in its colour (D235). */
    public static Component chatLine(Announcement a) {
        return Component.translatable("message.bsp_server_utilities.broadcast.prefix").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" ")).append(body(a.chatText(), a.colour()));
    }

    // ------------------------------------------------------------------ showing

    private static Collection<ServerPlayer> localPlayers(MinecraftServer server) {
        return playersOverride != null ? playersOverride.get() : server.getPlayerList().getPlayers();
    }

    @Nullable
    private static ServerPlayer localPlayer(MinecraftServer server, String name) {
        for (ServerPlayer p : localPlayers(server)) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /** Shows an announcement to this server's players (all, or the named one if here); returns how many saw it. */
    public static int show(MinecraftServer server, Announcement a) {
        List<ServerPlayer> to = new ArrayList<>();
        if (a.player().isEmpty()) {
            to.addAll(localPlayers(server));
        } else {
            ServerPlayer p = localPlayer(server, a.player());
            if (p != null) {
                to.add(p);
            }
        }
        if (to.isEmpty() && !a.player().isEmpty()) {
            return 0; // the named player is not here: nothing to write either
        }
        Component chat = chatLine(a);
        Component title = a.title() ? body(a.titleText(), a.colour()) : null;
        Component subtitle = a.title() ? body(a.subtitleText(), a.colour()) : null;
        for (ServerPlayer p : to) {
            if (shownOverride != null) {
                shownOverride.accept(p, new Shown(chat, title, subtitle));
                continue;
            }
            if (title != null) {
                p.connection.send(new ClientboundSetTitlesAnimationPacket(FADE_IN, STAY, FADE_OUT));
                p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
                p.connection.send(new ClientboundSetTitleTextPacket(title));
            }
            p.sendSystemMessage(chat);
            p.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.MASTER, 1f, 1.2f);
        }
        BSPServerUtilities.LOGGER.info("[Announcement] {}{}", a.player().isEmpty() ? "" : "(to " + a.player() + ") ", a.chatText().replaceAll("(?i)&[0-9a-fk-or]", ""));
        return to.size();
    }

    // ------------------------------------------------------------------ the command

    /** {@code /broadcast}; once, from the commands' registration. */
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("broadcast").requires(Perms::canEdit)
                .then(place("chat", false))
                .then(place("title", true)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> place(String word, boolean title) {
        return Commands.literal(word).then(Commands.argument("colour", StringArgumentType.string())
                .suggests((c, b) -> {
                    colourNames().forEach(b::suggest);
                    return b.buildFuture();
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((c, b) -> {
                            b.suggest("all");
                            for (ServerPlayer p : localPlayers(c.getSource().getServer())) {
                                b.suggest(p.getGameProfile().getName());
                            }
                            for (ClientCheckService.Row r : ClientCheckService.players(c.getSource().getServer())) {
                                b.suggest(r.held().name());
                            }
                            return b.buildFuture();
                        })
                        .then(Commands.literal("server").then(message(title, false)))
                        .then(Commands.literal("network").then(message(title, true)))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> message(boolean title, boolean network) {
        return Commands.argument("message", StringArgumentType.greedyString()).executes(c -> run(c, title, network));
    }

    private static int run(CommandContext<CommandSourceStack> c, boolean title, boolean network) {
        return send(c.getSource(), title, StringArgumentType.getString(c, "colour"), StringArgumentType.getString(c, "player"), network,
                StringArgumentType.getString(c, "message"));
    }

    /** Runs {@code /broadcast} for {@code source}; returns how many players on this server saw it (0 when refused or sent away). */
    public static int send(CommandSourceStack source, boolean title, String colourWord, String target, boolean network, String message) {
        Integer colour = colour(colourWord);
        if (colour == null) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.broadcast.bad_colour", colourWord, String.join(", ", colourNames())));
            return 0;
        }
        String text = clean(message);
        boolean all = target.equalsIgnoreCase("all");
        Announcement a = new Announcement(title, colour, all ? "" : target, text);
        if (PanelLayout.plain(title ? a.titleText() + a.subtitleText() : text).isBlank()) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.broadcast.empty"));
            return 0;
        }
        MinecraftServer server = source.getServer();
        String here = Storage.currentServerId();
        Storage st = Storage.active();
        if (all) {
            int n = show(server, a);
            if (network && st != null) {
                st.publish(KIND, a.encode());
                say(source, Component.translatable("message.bsp_server_utilities.broadcast.sent_network", players(n), here));
            } else if (network) {
                say(source, Component.translatable("message.bsp_server_utilities.broadcast.sent_alone", players(n), here));
            } else {
                say(source, Component.translatable("message.bsp_server_utilities.broadcast.sent_here", players(n), here));
            }
            return n;
        }
        ServerPlayer p = localPlayer(server, target);
        if (p != null) {
            show(server, new Announcement(title, colour, p.getGameProfile().getName(), text));
            say(source, Component.translatable("message.bsp_server_utilities.broadcast.sent_player", p.getGameProfile().getName(), here));
            return 1;
        }
        if (!network || st == null) {
            source.sendFailure(Component.translatable(network ? "message.bsp_server_utilities.broadcast.not_here_alone" : "message.bsp_server_utilities.broadcast.not_here", target));
            return 0;
        }
        ClientCheckService.Row r = onNetwork(server, target, here);
        if (r != null) {
            st.publish(KIND, new Announcement(title, colour, r.held().name(), text).encode());
            say(source, Component.translatable("message.bsp_server_utilities.broadcast.sent_player", r.held().name(), r.server()));
        } else if (Switches.off(Feature.CHECK)) {
            st.publish(KIND, a.encode()); // with the client check off nobody knows who is where: the servers look for them
            say(source, Component.translatable("message.bsp_server_utilities.broadcast.sent_somewhere", target));
        } else {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.broadcast.not_network", target));
        }
        return 0;
    }

    /** A player online on another server, from the client check's list (servers gone quiet left out); null when none. */
    @Nullable
    private static ClientCheckService.Row onNetwork(MinecraftServer server, String name, String here) {
        for (ClientCheckService.Row r : ClientCheckService.players(server)) {
            if (r.held().name().equalsIgnoreCase(name) && !r.server().equals(here)) {
                return r;
            }
        }
        return null;
    }

    /** "1 player", "14 players". */
    private static Component players(int n) {
        return n == 1 ? Component.translatable("message.bsp_server_utilities.broadcast.one_player") : Component.translatable("message.bsp_server_utilities.broadcast.players", n);
    }

    private static void say(CommandSourceStack source, Component c) {
        source.sendSuccess(() -> c, false);
    }

    /** Notice handlers; once, in the mod constructor. */
    public static void registerNotices() {
        Storage.on(KIND, (srv, n) -> {
            Announcement a = Announcement.decode(n.payload());
            if (a != null) {
                show(srv, a);
            }
        });
    }

    // ------------------------------------------------------------------ tests

    public static void useForTests(@Nullable Supplier<Collection<ServerPlayer>> players, @Nullable BiConsumer<ServerPlayer, Shown> shown) {
        playersOverride = players;
        shownOverride = shown;
    }

    public static void useReal() {
        playersOverride = null;
        shownOverride = null;
    }
}
