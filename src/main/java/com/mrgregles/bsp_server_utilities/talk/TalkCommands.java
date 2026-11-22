package com.mrgregles.bsp_server_utilities.talk;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The commands of private staff messages (SU-107; owner decisions D637, D638, D652 R2 and R4), under {@code /su}:
 * <ul>
 * <li>{@code /su msg <player> <text>}: staff (and the console) write to a player, online or not; the answer says where
 * they are or that they will see it when they next log on.</li>
 * <li>{@code /su talk}: a player opens their own window (for players who hide the key).</li>
 * <li>{@code /su talk <player>}: staff open the staff window for any player who ever joined (refused for staff, D641).</li>
 * <li>{@code /su talks}: staff list the open conversations, those waiting on staff first and the longest wait first, each
 * name clickable.</li>
 * </ul>
 * The rules (who may write, the limits) are {@link TalkService}'s; these only find the player and answer.
 */
public final class TalkCommands {
    private static LongSupplier clock = System::currentTimeMillis;

    private TalkCommands() {
    }

    /** Staff in game, or the console (and command blocks) at operator level. */
    static boolean staff(CommandSourceStack s) {
        ServerPlayer p = s.getPlayer();
        return p != null ? TalkService.isStaff(p) : s.hasPermission(Perms.OPERATOR_LEVEL);
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> names(CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        ClientCheckService.players(c.getSource().getServer()).forEach(r -> b.suggest(r.held().name()));
        return b.buildFuture();
    }

    /** {@code msg}, {@code talk} and {@code talks} for the {@code /su} tree. */
    public static List<LiteralArgumentBuilder<CommandSourceStack>> nodes() {
        return List.of(
                Commands.literal("msg").requires(TalkCommands::staff)
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(TalkCommands::names)
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(c -> msg(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "text"))))),
                Commands.literal("talk")
                        .executes(c -> own(c.getSource()))
                        .then(Commands.argument("player", StringArgumentType.word()).requires(s -> s.getPlayer() != null && TalkService.isStaff(s.getPlayer()))
                                .suggests(TalkCommands::names)
                                .executes(c -> window(c.getSource(), StringArgumentType.getString(c, "player")))),
                Commands.literal("talks").requires(TalkCommands::staff).executes(c -> list(c.getSource())));
    }

    private static String who(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        if (p != null) {
            return p.getGameProfile().getName();
        }
        String name = source.getTextName();
        return name.equals("Rcon") ? "RCON" : name.equals("Server") ? "Console" : name;
    }

    private static void fail(CommandSourceStack s, String key, Object... args) {
        s.sendFailure(Component.translatable("message.bsp_server_utilities.talk.cmd." + key, args));
    }

    private static void say(CommandSourceStack s, Component line) {
        s.sendSuccess(() -> line, false);
    }

    // ------------------------------------------------------------------ /su msg

    static int msg(CommandSourceStack source, String name, String text) {
        MinecraftServer server = source.getServer();
        TalkService.By by = source.getPlayer() != null ? TalkService.By.of(source.getPlayer()) : TalkService.By.console(who(source));
        TalkService.find(server, name).whenComplete((found, err) -> {
            if (err != null) {
                fail(source, "refused.failed", name);
                return;
            }
            if (found.isEmpty()) {
                fail(source, "unknown", name);
                return;
            }
            TalkService.Found f = found.get();
            TalkService.staffSend(server, by, f.id(), f.name(), text).thenAccept(r -> {
                if (!r.ok()) {
                    fail(source, "refused." + r.outcome().name().toLowerCase(java.util.Locale.ROOT), f.name());
                    return;
                }
                String where = TalkWindows.where(server, f.id());
                say(source, where.isEmpty()
                        ? Component.translatable("message.bsp_server_utilities.talk.cmd.sent_offline", f.name())
                        : Component.translatable("message.bsp_server_utilities.talk.cmd.sent_online", f.name(), where));
            });
        });
        return 1;
    }

    // ------------------------------------------------------------------ /su talk

    static int own(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        if (p == null) {
            fail(source, "players_only");
            return 0;
        }
        TalkWindows.openOwn(p).whenComplete((opened, err) -> {
            if (err != null) {
                fail(source, "refused.failed", p.getGameProfile().getName());
            } else if (!opened) {
                fail(source, "none_open");
            }
        });
        return 1;
    }

    static int window(CommandSourceStack source, String name) {
        ServerPlayer staff = source.getPlayer();
        if (staff == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        TalkService.find(server, name).whenComplete((found, err) -> {
            if (err != null) {
                fail(source, "refused.failed", name);
            } else if (found.isEmpty()) {
                fail(source, "unknown", name);
            } else if (TalkService.isStaffTarget(server, found.get().id())) {
                fail(source, "refused.to_staff", found.get().name());
            } else {
                TalkWindows.openFor(staff, found.get().id(), found.get().name()).whenComplete((v, e) -> {
                    if (e != null) {
                        fail(source, "refused.failed", found.get().name());
                    }
                });
            }
        });
        return 1;
    }

    // ------------------------------------------------------------------ /su talks

    /** "4 min", "2 h", "1 d" (at least "1 min"). */
    static String ago(long ms) {
        long min = Math.max(1, ms / 60_000L);
        return min < 60 ? min + " min" : min < 48 * 60 ? (min / 60) + " h" : (min / 1440) + " d";
    }

    static int list(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        TalkService.openTalks(server).whenComplete((all, err) -> {
            if (err != null) {
                fail(source, "refused.failed", "");
                return;
            }
            long now = clock.getAsLong();
            List<TalkStore.Talk> talks = new ArrayList<>(all);
            // waiting on staff first, the longest wait first; then the rest, the newest staff line first (R7)
            talks.sort(Comparator.comparing((TalkStore.Talk t) -> !t.waitingOnStaff())
                    .thenComparingLong(t -> t.waitingOnStaff() ? t.lastPlayerAt() : -t.lastStaffAt()));
            if (talks.isEmpty()) {
                say(source, Component.translatable("message.bsp_server_utilities.talk.cmd.no_open").withStyle(ChatFormatting.GRAY));
                return;
            }
            say(source, Component.translatable("message.bsp_server_utilities.talk.cmd.open_header", talks.size())
                    .withStyle(s -> s.withColor(TextColor.fromRgb(TalkDelivery.STAFF_COLOUR))));
            for (TalkStore.Talk t : talks) {
                say(source, row(server, t, now));
            }
        });
        return 1;
    }

    static Component row(MinecraftServer server, TalkStore.Talk t, long now) {
        MutableComponent name = Component.literal(t.name()).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/su talk " + t.name()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("message.bsp_server_utilities.talk.open_hover"))));
        String where = TalkWindows.where(server, t.player());
        MutableComponent out = Component.literal("  ").append(name).append(" ");
        if (t.waitingOnStaff()) {
            out.append(Component.translatable("message.bsp_server_utilities.talk.cmd.waiting", ago(now - t.lastPlayerAt())).withStyle(ChatFormatting.RED));
        } else {
            out.append(Component.translatable("message.bsp_server_utilities.talk.cmd.sent", ago(now - t.lastStaffAt())).withStyle(ChatFormatting.GRAY));
        }
        out.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
        if (!where.isEmpty()) {
            out.append(Component.literal(where).withStyle(ChatFormatting.GRAY));
        } else {
            out.append(Component.translatable(t.untold() ? "message.bsp_server_utilities.talk.cmd.offline_unseen" : "message.bsp_server_utilities.talk.cmd.offline")
                    .withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** Tests: the clock for "waiting 4 min" (null: the real one). */
    public static void useClockForTests(LongSupplier c) {
        clock = c != null ? c : System::currentTimeMillis;
    }
}
