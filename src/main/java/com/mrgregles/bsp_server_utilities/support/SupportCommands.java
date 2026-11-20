package com.mrgregles.bsp_server_utilities.support;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrgregles.bsp_server_utilities.access.Perms;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * {@code /su support} for admins and the console (owner decision D606): shows the settings; {@code link <url>},
 * {@code message <text>}, {@code chatlink <url>} change one of them ({@code none} empties it); {@code clear} empties all
 * three at once. The same rules as the SUPPORT tab: who may change them, the link check, the Action Log.
 */
public final class SupportCommands {
    private static final String NONE = "none";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);

    private SupportCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("support").requires(Perms::canEdit)
                .executes(c -> show(c.getSource()))
                .then(Commands.literal("link").then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(c -> answer(c.getSource(), SupportService.editLink(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource()), typed(c.getArgument("url", String.class)))))))
                .then(Commands.literal("message").then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> answer(c.getSource(), SupportService.editSentence(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource()), typed(c.getArgument("text", String.class)))))))
                .then(Commands.literal("chatlink").then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(c -> answer(c.getSource(), SupportService.editChatLink(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource()), typed(c.getArgument("url", String.class)))))))
                .then(Commands.literal("clear")
                        .executes(c -> answer(c.getSource(), SupportService.clear(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource())))));
    }

    /** {@code /support} for every player: the popup, or a grey line when there is no support page (D593, D595). */
    public static void registerPlayer(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("support").executes(c -> {
            CommandSourceStack source = c.getSource();
            ServerPlayer p = source.getPlayer();
            if (p != null) {
                if (!SupportService.open(p)) {
                    say(source, Component.translatable("message.bsp_server_utilities.support.no_page").withStyle(ChatFormatting.GRAY));
                }
                return 1;
            }
            SupportSettings s = SupportService.settings(source.getServer());
            say(source, s.hasLink() ? Component.literal(s.link()) : Component.translatable("message.bsp_server_utilities.support.no_page"));
            return 1;
        }));
    }

    /** {@code none} empties a setting. */
    static String typed(String s) {
        return s.strip().equalsIgnoreCase(NONE) ? "" : s;
    }

    private static String who(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        if (p != null) {
            return p.getGameProfile().getName();
        }
        String name = source.getTextName();
        // the console and RCON by those words; a command block or another entity by its own name (audit of 0.15.0)
        return name.equals("Rcon") ? "RCON" : name.equals("Server") ? "Console" : name;
    }

    private static int show(CommandSourceStack source) {
        SupportSettings s = SupportService.settings(source.getServer());
        say(source, Component.translatable("message.bsp_server_utilities.support.header"));
        say(source, Component.translatable("message.bsp_server_utilities.support.link", orNone(s.link())));
        say(source, Component.translatable("message.bsp_server_utilities.support.sentence", orNone(s.sentence())));
        say(source, Component.translatable("message.bsp_server_utilities.support.chat_link",
                s.chatLink().isEmpty() ? Component.translatable("message.bsp_server_utilities.support.chat_link_same") : Component.literal(s.chatLink())));
        if (!s.by().isEmpty()) {
            say(source, Component.translatable("message.bsp_server_utilities.support.last", s.by(), WHEN.format(Instant.ofEpochMilli(s.atMs()).atZone(ZoneId.systemDefault()))));
        }
        return 1;
    }

    private static Component orNone(String s) {
        return s.isEmpty() ? Component.translatable("message.bsp_server_utilities.support.none") : Component.literal(s);
    }

    private static int answer(CommandSourceStack source, SupportService.Result r) {
        switch (r.outcome()) {
            case SAVED -> source.sendSuccess(() -> Component.translatable(r.settings().link().isEmpty() && r.settings().sentence().isEmpty() && r.settings().chatLink().isEmpty()
                    ? "message.bsp_server_utilities.support.cleared" : "message.bsp_server_utilities.support.saved"), true);
            case UNCHANGED -> say(source, Component.translatable("message.bsp_server_utilities.support.unchanged"));
            case NOT_ALLOWED -> source.sendFailure(Component.translatable("message.bsp_server_utilities.support.not_allowed"));
            case BAD_LINK -> source.sendFailure(Component.translatable("message.bsp_server_utilities.support.bad_link", reason(r)));
            case BAD_CHAT_LINK -> source.sendFailure(Component.translatable("message.bsp_server_utilities.support.bad_chat_link", reason(r)));
        }
        return r.saved() || r.outcome() == SupportService.Outcome.UNCHANGED ? 1 : 0;
    }

    private static Component reason(SupportService.Result r) {
        return Component.translatable("gui.bsp_server_utilities." + r.problem().langKey()).withStyle(ChatFormatting.RED);
    }

    private static void say(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }

    /** The {@code /su status} line. */
    public static Component status(SupportSettings s) {
        Component on = Component.translatable("message.bsp_server_utilities.status.support_on"), off = Component.translatable("message.bsp_server_utilities.status.support_off");
        return Component.translatable("message.bsp_server_utilities.status.support", s.hasLink() ? on : off, s.hasChatLine() ? on : off);
    }
}
