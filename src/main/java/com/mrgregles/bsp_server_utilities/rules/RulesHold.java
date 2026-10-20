package com.mrgregles.bsp_server_utilities.rules;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * While a player is held by the rules popup (owner decision D8) they cannot use, break or place blocks, attack, pick up
 * or drop items, open containers, chat or run commands (except {@code /rules}, which shows the popup again), and nothing
 * can hurt them. Movement is held by {@link RulesService#tick}. Runs at HIGHEST so no other listener acts first.
 */
@Mod.EventBusSubscriber(modid = BSPServerUtilities.MODID)
public final class RulesHold {
    private RulesHold() {
    }

    private static boolean held(Entity entity) {
        return entity instanceof ServerPlayer player && RulesService.isHeld(player.getUUID());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        if (event.isCancelable() && held(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (held(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPickup(EntityItemPickupEvent event) {
        if (held(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /**
     * Forge has already taken the item out of the inventory when it asks (ForgeHooks.onPlayerTossEvent), and a cancelled
     * toss spawns nothing, so the item goes back into the inventory here; what does not fit is dropped as usual.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onToss(ItemTossEvent event) {
        if (held(event.getPlayer())) {
            ItemStack stack = event.getEntity().getItem();
            event.getPlayer().getInventory().add(stack);
            if (stack.isEmpty()) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (held(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (held(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onHurt(LivingAttackEvent event) {
        if (held(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (held(event.getEntity()) && event.getEntity() instanceof ServerPlayer player && event.getContainer() != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChat(ServerChatEvent event) {
        if (held(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().sendSystemMessage(Component.translatable("message.bsp_server_utilities.rules.held"));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        ServerPlayer player = event.getParseResults().getContext().getSource().getPlayer();
        if (player == null || !RulesService.isHeld(player.getUUID())) {
            return;
        }
        String typed = event.getParseResults().getReader().getString().trim();
        if (typed.startsWith("/")) {
            typed = typed.substring(1);
        }
        if (typed.equals("rules") || typed.startsWith("rules ")) {
            return;
        }
        event.setCanceled(true);
        player.sendSystemMessage(Component.translatable("message.bsp_server_utilities.rules.held"));
    }
}
