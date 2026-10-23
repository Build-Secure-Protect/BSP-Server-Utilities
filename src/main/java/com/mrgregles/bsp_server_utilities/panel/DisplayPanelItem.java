package com.mrgregles.bsp_server_utilities.panel;

import com.mrgregles.bsp_server_utilities.access.Perms;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The Display Panel as an item: only admins can place it (owner decision D16); there is no recipe. */
public class DisplayPanelItem extends BlockItem {
    public DisplayPanelItem(Block block, Properties props) {
        super(block, props);
    }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        if (com.mrgregles.bsp_server_utilities.setup.Switches.off(com.mrgregles.bsp_server_utilities.setup.Feature.PANELS)) {
            if (ctx.getPlayer() instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.translatable("message.bsp_server_utilities.panel.off"), true);
            }
            return InteractionResult.FAIL; // turned off in the setup guide (D150)
        }
        if (ctx.getPlayer() instanceof ServerPlayer sp && !Perms.canEdit(sp)) {
            sp.displayClientMessage(Component.translatable("message.bsp_server_utilities.panel.admins_only"), true);
            return InteractionResult.FAIL;
        }
        return super.place(ctx);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.bsp_server_utilities.display_panel").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.bsp_server_utilities.display_panel.admins").withStyle(ChatFormatting.DARK_GRAY));
    }
}
