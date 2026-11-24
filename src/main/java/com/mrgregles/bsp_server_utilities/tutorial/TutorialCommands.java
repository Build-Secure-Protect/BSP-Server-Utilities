package com.mrgregles.bsp_server_utilities.tutorial;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * {@code /tutorial} for everyone (owner decision D733): the tutorial under way with RESUME and END, then every
 * published tutorial with START (START AGAIN for one finished or under way); {@code /tutorial start <id>},
 * {@code resume} and {@code end} are what the chat keys run.
 */
public final class TutorialCommands {
    private TutorialCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("tutorial")
                .executes(c -> list(c.getSource().getPlayerOrException()))
                .then(Commands.literal("resume").executes(c -> say(c.getSource(), TutorialRunner.resume(c.getSource().getPlayerOrException()), "resume_none")))
                .then(Commands.literal("end").executes(c -> say(c.getSource(), TutorialRunner.endActive(c.getSource().getPlayerOrException()), "resume_none")))
                .then(Commands.literal("start").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> say(c.getSource(), TutorialRunner.start(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "id")), "not_found")))));
    }

    private static int say(CommandSourceStack source, boolean done, String otherwise) {
        if (!done) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.tutorial." + otherwise));
            return 0;
        }
        return 1;
    }

    private static MutableComponent key(String label, String command, ChatFormatting colour) {
        return Component.literal("[").append(Component.translatable(label)).append("]").withStyle(s -> s.withColor(colour).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    static int list(ServerPlayer player) {
        List<TutorialRunner.Choice> choices = TutorialRunner.choices(player);
        if (choices.isEmpty() || !TutorialRunner.enabled(player.server)) {
            player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.none"));
            return 0;
        }
        player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.tutorial.list").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (TutorialRunner.Choice c : choices) {
            MutableComponent line = Component.literal(" " + c.tutorial().name() + " ").withStyle(ChatFormatting.WHITE);
            TutorialProgress p = c.progress();
            String start = "/tutorial start " + c.tutorial().id();
            if (p != null && p.active()) {
                line.append(key("message.bsp_server_utilities.tutorial.key.resume", "/tutorial resume", ChatFormatting.AQUA)).append(" ")
                        .append(key("message.bsp_server_utilities.tutorial.key.again", start, ChatFormatting.GOLD)).append(" ")
                        .append(key("message.bsp_server_utilities.tutorial.key.end", "/tutorial end", ChatFormatting.GRAY));
            } else if (p != null && p.state() == TutorialProgress.State.FINISHED) {
                line.append(Component.translatable("message.bsp_server_utilities.tutorial.done").withStyle(ChatFormatting.GREEN)).append(" ")
                        .append(key("message.bsp_server_utilities.tutorial.key.again", start, ChatFormatting.GOLD));
            } else {
                line.append(key("message.bsp_server_utilities.tutorial.key.start", start, ChatFormatting.GOLD));
            }
            player.sendSystemMessage(line);
        }
        return 1;
    }
}
