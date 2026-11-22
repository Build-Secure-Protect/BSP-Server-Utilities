package com.mrgregles.bsp_server_utilities;

import com.mojang.logging.LogUtils;
import com.mrgregles.bsp_server_utilities.access.StaffDirectory;
import com.mrgregles.bsp_server_utilities.command.ServerUtilsCommands;
import com.mrgregles.bsp_server_utilities.image.ImageService;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.registry.ModBlocks;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point for BSP-Server Utilities: server quality of life for the Build Secure Protect network. Features live in
 * their own packages and are wired up here.
 */
@Mod(BSPServerUtilities.MODID)
public class BSPServerUtilities {
    /** Mod id. Must match {@code mod_id} in gradle.properties and {@code modId} in mods.toml. */
    public static final String MODID = "bsp_server_utilities";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BSPServerUtilities(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.SERVER, ServerUtilsConfig.SPEC);
        // the network connection and its passwords: a COMMON config, never sent to players (D183)
        context.registerConfig(ModConfig.Type.COMMON, ServerUtilsNetworkConfig.SPEC, ServerUtilsNetworkConfig.FILE);
        // the player's own settings (the SUPPORT BSP key, D591); never read by a server
        context.registerConfig(ModConfig.Type.CLIENT, ServerUtilsClientConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerAboutToStartEvent e) -> ServerUtilsNetworkConfig.onAboutToStart());
        ModBlocks.register(context.getModEventBus());
        ServerUtilsNetwork.register();
        // Shared storage (MySQL and Redis) when the configs say so; otherwise records stay on this server.
        RulesService.registerNotices();
        StaffDirectory.registerNotices();
        com.mrgregles.bsp_server_utilities.log.ActionLog.registerNotices();
        com.mrgregles.bsp_server_utilities.access.StaffSync.init();
        com.mrgregles.bsp_server_utilities.log.LogSync.init();
        com.mrgregles.bsp_server_utilities.afk.AfkService.registerNotices();
        com.mrgregles.bsp_server_utilities.afk.AfkService.registerEvents();
        com.mrgregles.bsp_server_utilities.chunks.ChunkService.registerNotices();
        com.mrgregles.bsp_server_utilities.chunks.ChunkService.registerEvents();
        com.mrgregles.bsp_server_utilities.despawn.DespawnService.registerNotices();
        com.mrgregles.bsp_server_utilities.despawn.DespawnService.registerEvents();
        com.mrgregles.bsp_server_utilities.despawn.ClearService.registerNotices();
        com.mrgregles.bsp_server_utilities.check.ClientCheckService.registerNotices();
        com.mrgregles.bsp_server_utilities.setup.SetupService.registerNotices();
        com.mrgregles.bsp_server_utilities.players.PlayerCounter.registerNotices();
        com.mrgregles.bsp_server_utilities.support.SupportService.registerNotices(); // Support BSP (SU-89)
        com.mrgregles.bsp_server_utilities.support.NetworkJoins.registerNotices(); // a join to the network or a move (SU-90)
        com.mrgregles.bsp_server_utilities.talk.TalkDelivery.register(); // private staff messages reaching players and staff on every server (SU-106)
        com.mrgregles.bsp_server_utilities.web.WebTalk.register(); // and the staff web panel, live (SU-109)
        com.mrgregles.bsp_server_utilities.serverimages.ServerImages.registerNotices();
        com.mrgregles.bsp_server_utilities.announce.Broadcast.registerNotices();
        com.mrgregles.bsp_server_utilities.web.WebActions.registerNotices();
        com.mrgregles.bsp_server_utilities.web.WebChat.registerEvents();
        com.mrgregles.bsp_server_utilities.web.WebNotes.registerNotices();
        com.mrgregles.bsp_server_utilities.web.WebBans.registerNotices(); // network bans (SU-44)
        com.mrgregles.bsp_server_utilities.rooms.RoomList.registerNotices(); // Compact Machines rooms (SU-54)
        com.mrgregles.bsp_server_utilities.rooms.RoomLimit.registerEvents(); // the room limit (SU-55)
        com.mrgregles.bsp_server_utilities.rooms.RoomLosses.registerEvents(); // lost machines and the grace list (SU-56)
        com.mrgregles.bsp_server_utilities.rooms.RoomLosses.registerNotices();
        com.mrgregles.bsp_server_utilities.rooms.RoomNotices.registerEvents();
        com.mrgregles.bsp_server_utilities.rooms.RoomNotices.registerNotices();
        com.mrgregles.bsp_server_utilities.rooms.RoomClearing.registerEvents(); // rooms cleared after their grace (SU-57)
        com.mrgregles.bsp_server_utilities.mobs.MobLimits.registerNotices(); // mob limits (SU-62 to SU-64)
        com.mrgregles.bsp_server_utilities.mobs.MobLimits.registerEvents();
        com.mrgregles.bsp_server_utilities.mobs.Crowding.registerNotices(); // crowded drops and arrows (SU-66 to SU-68)
        com.mrgregles.bsp_server_utilities.steals.StealRecorder.register(); // totem steals for the staff panel (SU-95)
        com.mrgregles.bsp_server_utilities.check.ClientCheckService.registerEvents();
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            Storage.start(e.getServer());
            RulesService.onServerStarted(e.getServer());
            StaffDirectory.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.log.ActionLog.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.afk.AfkService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.chunks.ChunkService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.despawn.DespawnService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.mobs.MobLimits.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.mobs.Crowding.onServerStarted();
            com.mrgregles.bsp_server_utilities.despawn.ClearService.onServerStarted();
            com.mrgregles.bsp_server_utilities.check.ClientCheckService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.setup.SetupService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.players.PlayerCounter.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.support.SupportService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.serverimages.ServerImages.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.rooms.RoomList.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.steals.StealRecorder.onServerStarted();
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            com.mrgregles.bsp_server_utilities.web.WebAgent.farewell(e.getServer()); // the panel drops this server at once, not 20 s later (audit)
            // Support BSP: the players still here leave after the storage closes, so tell the network now (audit of 0.15.0)
            com.mrgregles.bsp_server_utilities.support.SupportChat.onServerStopping(e.getServer().getPlayerList().getPlayers());
            com.mrgregles.bsp_server_utilities.steals.StealRecorder.onServerStopping(); // waiting steal rows: one last try, the count said
            Storage.stop();
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) -> com.mrgregles.bsp_server_utilities.despawn.DespawnService.onServerStopped());
        MinecraftForge.EVENT_BUS.addListener(ServerUtilsCommands::onRegister);
        // The rules popup: the gate at login, the hold, the popup (RulesHold blocks actions while held).
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, RulesService::onLogin);
        MinecraftForge.EVENT_BUS.addListener(RulesService::onLogout);
        // Staff online: presence on join and leave (and a heartbeat in the tick), the last-seen record on leave.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                StaffDirectory.publishPresence(p.server);
                StaffDirectory.touch();
                com.mrgregles.bsp_server_utilities.access.StaffSync.sendTo(p);
                com.mrgregles.bsp_server_utilities.log.LogSync.sendTo(p);
                com.mrgregles.bsp_server_utilities.afk.AfkService.onLogin(p);
                com.mrgregles.bsp_server_utilities.chunks.ChunkService.onLogin(p);
                com.mrgregles.bsp_server_utilities.setup.SetupService.onLogin(p);
                com.mrgregles.bsp_server_utilities.players.PlayerCounter.onLogin(p);
                com.mrgregles.bsp_server_utilities.support.SupportService.onLogin(p);
                com.mrgregles.bsp_server_utilities.support.SupportChat.onLogin(p);
                com.mrgregles.bsp_server_utilities.talk.TalkDelivery.onLogin(p); // the MESSAGE STAFF key's state, the popup for lines sent while away
                com.mrgregles.bsp_server_utilities.serverimages.ServerImages.onLogin(p);
                com.mrgregles.bsp_server_utilities.web.WebNotes.sendTo(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                com.mrgregles.bsp_server_utilities.afk.AfkService.onLogout(p);
                com.mrgregles.bsp_server_utilities.chunks.ChunkService.onLogout(p);
                com.mrgregles.bsp_server_utilities.setup.SetupService.onLogout(p);
                StaffDirectory.onLogout(p);
                com.mrgregles.bsp_server_utilities.serverimages.ServerImages.onLogout(p);
                com.mrgregles.bsp_server_utilities.support.SupportChat.onLogout(p);
                com.mrgregles.bsp_server_utilities.talk.TalkDelivery.onLogout(p);
                com.mrgregles.bsp_server_utilities.talk.TalkWindows.onLogout(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> ImageService.forget(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> com.mrgregles.bsp_server_utilities.web.ProxyKick.forget(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> com.mrgregles.bsp_server_utilities.web.WebItemsUpload.forget(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) {
                RulesService.tick(e.getServer());
                ImageService.tick(e.getServer());
                StaffDirectory.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.log.ActionLog.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.access.StaffSync.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.log.LogSync.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.afk.AfkService.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.chunks.ChunkService.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.despawn.DespawnService.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.despawn.ClearService.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.check.ClientCheckService.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.setup.SetupService.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.players.PlayerCounter.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.support.SupportChat.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.support.SupportService.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.web.WebAgent.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.web.ProxyKick.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.web.WebNotes.tick(e.getServer());
                com.mrgregles.bsp_server_utilities.web.WebBans.tick(e.getServer()); // the bans in force: read at start and every minute
                com.mrgregles.bsp_server_utilities.rooms.RoomList.tick(e.getServer()); // this server's Compact Machines rooms, shared every minute
                com.mrgregles.bsp_server_utilities.rooms.RoomNotices.tick(e.getServer()); // owners told of rooms in grace after login
                com.mrgregles.bsp_server_utilities.rooms.RoomClearing.tick(e.getServer()); // rooms whose grace ended, cleared every minute
                com.mrgregles.bsp_server_utilities.mobs.Crowding.tick(e.getServer()); // crowded drops and arrows, every 30 s
                com.mrgregles.bsp_server_utilities.rooms.RoomOutbox.tick(e.getServer()); // notices about other servers' rooms, sent again when missed
                com.mrgregles.bsp_server_utilities.steals.StealRecorder.tick(e.getServer()); // totem steals: a failed write again each minute, 90-day prune
                com.mrgregles.bsp_server_utilities.talk.TalkService.tick(e.getServer()); // private staff messages: the 90-day prune every 6 hours
                com.mrgregles.bsp_server_utilities.talk.TalkDelivery.tick(e.getServer()); // popups for lines sent while away, once the rules let the player through
            }
        });
        LOGGER.info("BSP-Server Utilities loaded");
    }
}
