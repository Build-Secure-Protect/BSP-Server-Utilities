package com.mrgregles.bsp_server_utilities.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_server_utilities.access.Perms;
import com.mrgregles.bsp_server_utilities.admin.AdminPanel;
import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.check.ClientReport;
import com.mrgregles.bsp_server_utilities.check.TrustedSetup;
import com.mrgregles.bsp_server_utilities.check.Verdict;
import com.mrgregles.bsp_server_utilities.players.Joined;
import com.mrgregles.bsp_server_utilities.players.PlayerCounter;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupService;
import com.mrgregles.bsp_server_utilities.setup.SetupState;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.compat.LuckPermsBridge;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code /serverutils} and its short form {@code /su} (owner decision D6): {@code status} (where the records are kept,
 * this server's name, LuckPerms, BSP-Core, the staff groups) and {@code rules} (list, add, remove, status, reset a player,
 * ask everyone again), for admins, and {@code /su} alone opens the admin panel; {@code /rules} for everyone (D12);
 * {@code /actionlog} for everyone, the log read only with coordinates hidden (D58); {@code /afk} for everyone (D77);
 * {@code client <player>} and {@code clientcheck export | reload} for admins (the client check, D130, D132); {@code setup [now |
 * later | ignore]} for admins (the setup guide, D154); {@code players [set <n> | add <n> | reset | true]} for admins (the
 * player counter, D170: each change written to the Action Log).
 */
public final class ServerUtilsCommands {
    private ServerUtilsCommands() {
    }

    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        LiteralCommandNode<CommandSourceStack> root = d.register(Commands.literal("serverutils").requires(ServerUtilsCommands::editorOrStaff)
                .executes(ServerUtilsCommands::openPanel)
                .then(Commands.literal("away").requires(ServerUtilsCommands::staff).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    boolean away = StaffDirectory.toggleAway(p);
                    c.getSource().sendSuccess(() -> Component.translatable(away ? "message.bsp_server_utilities.staff.away" : "message.bsp_server_utilities.staff.back"), false);
                    return 1;
                }))
                .then(Commands.literal("status").requires(Perms::canEdit).executes(c -> status(c.getSource())))
                .then(Commands.literal("clearitems").requires(Perms::canEdit)
                        .then(Commands.argument("where", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    b.suggest("here");
                                    b.suggest("all");
                                    com.mrgregles.bsp_server_utilities.access.StaffDirectory.knownServers().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(c -> clearItems(c.getSource(), StringArgumentType.getString(c, "where")))))
                .then(Commands.literal("setup").requires(Perms::canEdit)
                        .executes(c -> setupOpen(c.getSource()))
                        .then(Commands.literal("now").executes(c -> setupOpen(c.getSource())))
                        .then(Commands.literal("later").executes(c -> {
                            c.getSource().getPlayerOrException();
                            say(c.getSource(), Component.translatable("message.bsp_server_utilities.setup.later_done"));
                            return 1;
                        }))
                        .then(Commands.literal("ignore").executes(c -> {
                            SetupService.ignore(c.getSource().getServer(), c.getSource().getPlayerOrException().getUUID());
                            say(c.getSource(), Component.translatable("message.bsp_server_utilities.setup.ignored"));
                            return 1;
                        })))
                .then(Commands.literal("players").requires(Perms::canEdit)
                        .executes(c -> players(c.getSource()))
                        .then(Commands.literal("set").then(Commands.argument("number", IntegerArgumentType.integer(0))
                                .executes(c -> playersChanged(c.getSource(), PlayerCounter.set(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource()), IntegerArgumentType.getInteger(c, "number"))))))
                        .then(Commands.literal("add").then(Commands.argument("number", IntegerArgumentType.integer())
                                .executes(c -> playersChanged(c.getSource(), PlayerCounter.add(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource()), IntegerArgumentType.getInteger(c, "number"))))))
                        .then(Commands.literal("reset").executes(c -> playersChanged(c.getSource(), PlayerCounter.reset(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource())))))
                        .then(Commands.literal("true").executes(c -> playersChanged(c.getSource(), PlayerCounter.backToReal(c.getSource().getServer(), c.getSource().getPlayer(), who(c.getSource()))))))
                .then(Commands.literal("client").requires(Perms::canEdit)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    ClientCheckService.players(c.getSource().getServer()).forEach(r -> b.suggest(r.held().name()));
                                    return b.buildFuture();
                                })
                                .executes(c -> clientInfo(c.getSource(), StringArgumentType.getString(c, "player")))))
                .then(Commands.literal("clientcheck").requires(Perms::canEdit)
                        .then(Commands.literal("export").executes(c -> clientExport(c.getSource())))
                        .then(Commands.literal("reload").executes(c -> clientReload(c.getSource()))))
                .then(Commands.literal("rules").requires(Perms::canEdit)
                        .then(Commands.literal("list").executes(c -> rulesList(c.getSource())))
                        .then(Commands.literal("status").executes(c -> rulesStatus(c.getSource())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("title", StringArgumentType.string())
                                        .executes(c -> rulesAdd(c.getSource(), StringArgumentType.getString(c, "title"), ""))
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(c -> rulesAdd(c.getSource(), StringArgumentType.getString(c, "title"), StringArgumentType.getString(c, "text"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("number", IntegerArgumentType.integer(1, RuleSet.MAX_RULES))
                                        .executes(c -> rulesRemove(c.getSource(), IntegerArgumentType.getInteger(c, "number")))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(c -> rulesReset(c.getSource(), StringArgumentType.getString(c, "player")))))
                        .then(Commands.literal("askagain").executes(c -> rulesAskAgain(c.getSource())))));
        d.register(Commands.literal("su").requires(ServerUtilsCommands::editorOrStaff)
                .executes(ServerUtilsCommands::openPanel)
                .redirect(root));
        d.register(Commands.literal("rules").executes(c -> {
            RulesService.showReadOnly(c.getSource().getPlayerOrException());
            return 1;
        }));
        d.register(Commands.literal("afk").executes(c -> {
            com.mrgregles.bsp_server_utilities.afk.AfkService.markAfk(c.getSource().getPlayerOrException());
            return 1;
        }));
        d.register(Commands.literal("actionlog").executes(c -> {
            com.mrgregles.bsp_server_utilities.log.LogSync.open(c.getSource().getPlayerOrException());
            return 1;
        }));
    }

    private static boolean staff(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        return p != null && Staff.isStaff(p);
    }

    /** {@code /su} is for editors; staff who are not editors may still use {@code /su away}. */
    private static boolean editorOrStaff(CommandSourceStack source) {
        return Perms.canEdit(source) || staff(source);
    }

    private static int openPanel(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        if (!Perms.canEdit(c.getSource())) {
            c.getSource().sendFailure(Component.translatable("message.bsp_server_utilities.staff.away_only"));
            return 0;
        }
        AdminPanel.open(c.getSource().getPlayerOrException());
        return 1;
    }

    private static int rulesList(CommandSourceStack source) {
        RuleSet set = RulesService.rules(source.getServer());
        if (set.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.rules.none"), false);
            return 0;
        }
        for (int i = 0; i < set.rules().size(); i++) {
            RuleSet.Rule r = set.rules().get(i);
            int n = i + 1;
            source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.rules.line", n, r.title(), r.text()), false);
        }
        return set.rules().size();
    }

    private static int rulesStatus(CommandSourceStack source) {
        RuleSet set = RulesService.rules(source.getServer());
        RulesService.countAgreed(source.getServer(), n -> source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.rules.status",
                set.version(), set.rules().size(), n), false));
        return 1;
    }

    private static int rulesAdd(CommandSourceStack source, String title, String text) {
        RuleSet set = RulesService.rules(source.getServer());
        if (set.rules().size() >= RuleSet.MAX_RULES) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.rules.full", RuleSet.MAX_RULES));
            return 0;
        }
        RuleSet next = RulesService.edit(source.getServer(), s -> {
            List<RuleSet.Rule> rules = new ArrayList<>(s.rules());
            rules.add(new RuleSet.Rule(title, text));
            return s.withRules(rules);
        });
        if (next.rules().size() == set.rules().size()) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.rules.no_title"));
            return 0;
        }
        RuleSet.Rule added = next.rules().get(next.rules().size() - 1);
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.rules.added", next.rules().size(), added.title()), true);
        return 1;
    }

    private static int rulesRemove(CommandSourceStack source, int number) {
        RuleSet set = RulesService.rules(source.getServer());
        if (number > set.rules().size()) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.rules.no_such", number));
            return 0;
        }
        RuleSet.Rule gone = set.rules().get(number - 1);
        RulesService.edit(source.getServer(), s -> {
            List<RuleSet.Rule> rules = new ArrayList<>(s.rules());
            rules.remove(number - 1);
            return s.withRules(rules);
        });
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.rules.removed", number, gone.title()), true);
        return 1;
    }

    private static int rulesReset(CommandSourceStack source, String name) throws CommandSyntaxException {
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
        Optional<GameProfile> profile = online != null ? Optional.of(online.getGameProfile()) : source.getServer().getProfileCache().get(name);
        if (profile.isEmpty()) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.rules.unknown_player", name));
            return 0;
        }
        RulesService.reset(source.getServer(), profile.get().getId());
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.rules.reset", profile.get().getName()), true);
        return 1;
    }

    private static int rulesAskAgain(CommandSourceStack source) {
        RuleSet next = RulesService.askEveryoneAgain(source.getServer());
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.rules.asked_again", next.version()), true);
        return 1;
    }

    /** {@code /su clearitems here | all | <server>} (D110, D113, D115). */
    private static int clearItems(CommandSourceStack source, String where) {
        String w = where.strip();
        boolean known = w.equalsIgnoreCase("here") || w.equalsIgnoreCase("all") || w.equals(Storage.currentServerId())
                || com.mrgregles.bsp_server_utilities.access.StaffDirectory.knownServers().contains(w);
        if (!known) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.clear.unknown", w));
            return 0;
        }
        String to = com.mrgregles.bsp_server_utilities.despawn.ClearService.start(source.getServer(), w, source.getPlayer());
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.clear.started",
                to.equals(com.mrgregles.bsp_server_utilities.despawn.ClearService.ALL) ? Component.translatable("message.bsp_server_utilities.clear.every_server") : Component.literal(to)), true);
        return 1;
    }

    /** {@code /su client <player>}: what a player's client reported and what the check made of it (D130). */
    private static int clientInfo(CommandSourceStack source, String name) {
        ClientCheckService.Row row = ClientCheckService.findByName(name);
        if (row == null) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.check.info.unknown", name));
            return 0;
        }
        ClientCheckService.Held h = row.held();
        Verdict v = h.verdict();
        ClientReport r = h.report();
        long minutes = Math.max(0, (System.currentTimeMillis() - h.atMs()) / 60_000);
        say(source, Component.translatable("message.bsp_server_utilities.check.info.head", h.name(), row.server(), r.mods().size(), r.packs().size(), minutes));
        if (v.exempt()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.exempt").withStyle(ChatFormatting.GRAY));
        }
        if (!v.refuse().isEmpty()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.blocked", ClientCheckService.describe(v.refuse())).withStyle(ChatFormatting.RED));
        }
        if (!v.notTrustedMods().isEmpty()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.not_trusted", String.join(", ", v.notTrustedMods())).withStyle(ChatFormatting.GOLD));
        }
        if (!v.fileDiffers().isEmpty()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.differs", String.join(", ", v.fileDiffers())).withStyle(ChatFormatting.GOLD));
        }
        if (!v.notTrustedPacks().isEmpty()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.packs_not_trusted", String.join(", ", v.notTrustedPacks())).withStyle(ChatFormatting.GOLD));
        }
        if (!v.hidden().isEmpty()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.hidden", String.join(", ", v.hidden())).withStyle(ChatFormatting.GOLD));
        }
        if (v.brightness()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.bright", Math.round(r.brightness() * 100)).withStyle(ChatFormatting.GOLD));
        }
        String packs = r.packs().isEmpty() ? "-" : r.packs().stream().map(ClientReport.Pack::name).collect(Collectors.joining(", "));
        say(source, Component.translatable("message.bsp_server_utilities.check.info.packs", packs));
        if (!r.shader().isEmpty()) {
            say(source, Component.translatable(r.shadersOn() ? "message.bsp_server_utilities.check.info.shader_on" : "message.bsp_server_utilities.check.info.shader_off", r.shader()));
        }
        if (r.stonePassed()) {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.stone_ok").withStyle(ChatFormatting.GREEN));
        } else {
            say(source, Component.translatable("message.bsp_server_utilities.check.info.stone_failed", r.stoneBlock(), r.stonePack().isEmpty() ? "?" : r.stonePack()).withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    /** The pages in the given states, by name, or a dash. */
    private static Component names(java.util.Map<Feature, SetupState.Status> pages, SetupState.Status... states) {
        java.util.List<SetupState.Status> want = java.util.List.of(states);
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        boolean any = false;
        for (java.util.Map.Entry<Feature, SetupState.Status> e : pages.entrySet()) {
            if (want.contains(e.getValue())) {
                if (any) {
                    out.append(", ");
                }
                out.append(Component.translatable("message.bsp_server_utilities.setup.feature." + e.getKey().name().toLowerCase(java.util.Locale.ROOT)));
                any = true;
            }
        }
        return any ? out : Component.literal("-");
    }

    private static void say(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }

    /** {@code /su setup}: the setup guide, at the first page not finished (D154). */
    private static int setupOpen(CommandSourceStack source) throws CommandSyntaxException {
        SetupService.openGuide(source.getPlayerOrException());
        return 1;
    }

    /** Who changed the counter, for the Action Log when it is not a player: the console or RCON. */
    private static String who(CommandSourceStack source) {
        return source.getTextName().equals("Rcon") ? "RCON" : "Console";
    }

    /** {@code /su players}: the number shown, the real count and the adjustment, today's and this week's, the newest (D170). */
    private static int players(CommandSourceStack source) {
        if (PlayerCounter.off()) {
            say(source, Component.translatable("message.bsp_server_utilities.players.off"));
            return 0;
        }
        var server = source.getServer();
        int offset = PlayerCounter.settings(server).offset();
        long now = System.currentTimeMillis();
        say(source, Component.translatable("message.bsp_server_utilities.players.shown", PlayerCounter.format(PlayerCounter.shown(server)), PlayerCounter.format(PlayerCounter.real()),
                (offset >= 0 ? "+" : "-") + PlayerCounter.format(Math.abs(offset))));
        Joined n = PlayerCounter.counts().newest();
        say(source, Component.translatable("message.bsp_server_utilities.players.recent", PlayerCounter.format(PlayerCounter.counts().today(now)), PlayerCounter.format(PlayerCounter.counts().week(now)),
                n == null ? "-" : n.name() + " (" + n.server() + ")"));
        return 1;
    }

    private static int playersChanged(CommandSourceStack source, int shown) {
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.players.changed", PlayerCounter.format(shown), PlayerCounter.format(PlayerCounter.real())), true);
        return 1;
    }

    /** {@code /su clientcheck export} (D132). */
    private static int clientExport(CommandSourceStack source) {
        java.nio.file.Path f = ClientCheckService.exportFile(source.getServer());
        if (f == null) {
            source.sendFailure(Component.translatable("message.bsp_server_utilities.check.no_setup"));
            return 0;
        }
        say(source, Component.translatable("message.bsp_server_utilities.check.exported", f.toString()));
        return 1;
    }

    /** {@code /su clientcheck reload} (D132). */
    private static int clientReload(CommandSourceStack source) {
        ClientCheckService.FileResult r = ClientCheckService.readFile(source.getServer());
        String f = ClientCheckService.file().toString();
        switch (r) {
            case LOADED -> say(source, Component.translatable("message.bsp_server_utilities.check.file.loaded", f));
            case OLDER -> say(source, Component.translatable("message.bsp_server_utilities.check.file.older", f));
            case NONE -> source.sendFailure(Component.translatable("message.bsp_server_utilities.check.file.none", f));
            case BAD -> source.sendFailure(Component.translatable("message.bsp_server_utilities.check.file.bad", f));
        }
        return r == ClientCheckService.FileResult.LOADED ? 1 : 0;
    }

    private static int status(CommandSourceStack source) {
        Storage s = Storage.active();
        StorageSettings.Resolved settings = StorageSettings.current();
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.status.server", Storage.currentServerId()), false);
        source.sendSuccess(() -> s == null
                ? Component.translatable("message.bsp_server_utilities.status.local")
                : Component.translatable("message.bsp_server_utilities.status.shared", s.status()), false);
        for (String note : settings.notes()) {
            source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.status.note", note), false);
        }
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.status.luckperms", LuckPermsBridge.route().name()), false);
        source.sendSuccess(() -> Component.translatable(BspCore.isLoaded() ? "message.bsp_server_utilities.status.bsp_core" : "message.bsp_server_utilities.status.no_bsp_core"), false);
        List<Staff.Role> roles = Staff.roles(source.getServer());
        String groups = roles.isEmpty() ? "-" : roles.stream().map(r -> r.group() + " (" + r.label() + ")").collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.status.staff", groups,
                Component.translatable(Staff.operatorsCount(source.getServer()) ? "message.bsp_server_utilities.status.yes" : "message.bsp_server_utilities.status.no")), false);
        com.mrgregles.bsp_server_utilities.afk.AfkSettings afk = com.mrgregles.bsp_server_utilities.afk.AfkService.settings(source.getServer());
        source.sendSuccess(() -> !afk.enabled() ? Component.translatable("message.bsp_server_utilities.status.afk_off")
                : afk.kick() ? Component.translatable("message.bsp_server_utilities.status.afk_kick", afk.afkMinutes(), afk.kickMinutes())
                : Component.translatable("message.bsp_server_utilities.status.afk", afk.afkMinutes()), false);
        com.mrgregles.bsp_server_utilities.chunks.ChunkSettings chunks = com.mrgregles.bsp_server_utilities.chunks.ChunkService.settings(source.getServer());
        int unloaded = com.mrgregles.bsp_server_utilities.chunks.ChunkData.get(source.getServer()).suspended().size();
        source.sendSuccess(() -> chunks.enabled() ? Component.translatable("message.bsp_server_utilities.status.chunks", chunks.minutes(), unloaded)
                : Component.translatable("message.bsp_server_utilities.status.chunks_off"), false);
        com.mrgregles.bsp_server_utilities.despawn.DespawnSettings drops = com.mrgregles.bsp_server_utilities.despawn.DespawnService.settings(source.getServer());
        long[] gone = com.mrgregles.bsp_server_utilities.despawn.DespawnService.removedLastHour(source.getServer());
        source.sendSuccess(() -> Component.translatable("message.bsp_server_utilities.status.drops", drops.itemMinutes(), drops.deathMinutes(), gone[0], gone[1]), false);
        TrustedSetup setup = ClientCheckService.setup(source.getServer());
        Component which = Component.translatable(ClientCheckService.useOwn(source.getServer()) ? "message.bsp_server_utilities.status.check_own" : "message.bsp_server_utilities.status.check_network");
        Component taken = setup == null ? Component.translatable("message.bsp_server_utilities.status.check_no_setup")
                : Component.translatable("message.bsp_server_utilities.status.check_setup", java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(setup.takenAtMs()), java.time.ZoneId.systemDefault()).toString(), setup.takenBy());
        say(source, Component.translatable("message.bsp_server_utilities.status.check", which, ClientCheckService.rules(source.getServer()).block().size(), taken, ClientCheckService.refusedToday().size()));
        java.util.Map<Feature, SetupState.Status> pages = SetupService.statuses(source.getServer());
        long done = pages.values().stream().filter(st -> st == SetupState.Status.DONE).count();
        say(source, PlayerCounter.off() ? Component.translatable("message.bsp_server_utilities.status.players_off")
                : Component.translatable("message.bsp_server_utilities.status.players", PlayerCounter.format(PlayerCounter.shown(source.getServer())), PlayerCounter.format(PlayerCounter.real()),
                Component.translatable(s == null ? "message.bsp_server_utilities.status.players_here" : "message.bsp_server_utilities.status.players_network")));
        say(source, Component.translatable("message.bsp_server_utilities.status.setup", done, pages.size(), names(pages, SetupState.Status.OFF), names(pages, SetupState.Status.LATER, SetupState.Status.NOT_YET)));
        return 1;
    }
}
