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
        com.mrgregles.bsp_server_utilities.check.ClientCheckService.registerEvents();
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            Storage.start(e.getServer());
            RulesService.onServerStarted(e.getServer());
            StaffDirectory.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.log.ActionLog.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.afk.AfkService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.chunks.ChunkService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.despawn.DespawnService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.despawn.ClearService.onServerStarted();
            com.mrgregles.bsp_server_utilities.check.ClientCheckService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.setup.SetupService.onServerStarted(e.getServer());
            com.mrgregles.bsp_server_utilities.players.PlayerCounter.onServerStarted(e.getServer());
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> Storage.stop());
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
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                com.mrgregles.bsp_server_utilities.afk.AfkService.onLogout(p);
                com.mrgregles.bsp_server_utilities.chunks.ChunkService.onLogout(p);
                com.mrgregles.bsp_server_utilities.setup.SetupService.onLogout(p);
                StaffDirectory.onLogout(p);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> ImageService.forget(e.getEntity().getUUID()));
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
            }
        });
        LOGGER.info("BSP-Server Utilities loaded");
    }
}
