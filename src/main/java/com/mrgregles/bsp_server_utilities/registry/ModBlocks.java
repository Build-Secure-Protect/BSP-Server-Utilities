package com.mrgregles.bsp_server_utilities.registry;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlock;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlockEntity;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The mod's blocks, items and block entities. */
public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BSPServerUtilities.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BSPServerUtilities.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BSPServerUtilities.MODID);

    public static final RegistryObject<DisplayPanelBlock> DISPLAY_PANEL = BLOCKS.register("display_panel", DisplayPanelBlock::new);
    public static final RegistryObject<Item> DISPLAY_PANEL_ITEM = ITEMS.register("display_panel", () -> new DisplayPanelItem(DISPLAY_PANEL.get(), new Item.Properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<DisplayPanelBlockEntity>> DISPLAY_PANEL_ENTITY = BLOCK_ENTITIES.register("display_panel",
            () -> BlockEntityType.Builder.of(DisplayPanelBlockEntity::new, DISPLAY_PANEL.get()).build(null));

    private ModBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(ModBlocks::onCreativeTabs);
    }

    /** The panel sits in the Functional Blocks tab; only admins can place it. */
    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(DISPLAY_PANEL_ITEM);
        }
    }
}
