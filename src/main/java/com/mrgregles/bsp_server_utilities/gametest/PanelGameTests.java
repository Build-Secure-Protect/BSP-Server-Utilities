package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlock;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlockEntity;
import com.mrgregles.bsp_server_utilities.panel.PanelEditing;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.network.PanelOpenPacket;
import com.mrgregles.bsp_server_utilities.network.PanelSavePacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import com.mrgregles.bsp_server_utilities.panel.PanelShape;
import com.mrgregles.bsp_server_utilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The Display Panel block (owner decisions D14 to D16, D41, D46, D47, D52): its settings and their limits, joining into
 * the largest full rectangle up to 8 x 6 with the bezel only round the outside, the settings following the panel when
 * blocks are added or the bottom-left one is broken, and admins only.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class PanelGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final Direction FACING = Direction.NORTH;

    private static BlockState panel() {
        return ModBlocks.DISPLAY_PANEL.get().defaultBlockState().setValue(DisplayPanelBlock.FACING, FACING);
    }

    /** Places a panel block (the state's join flags follow from its neighbours). */
    private static void put(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, DisplayPanelBlock.connected(panel(), level, pos), 3);
    }

    private static BlockPos at(BlockPos origin, int x, int y) {
        return origin.relative(PanelShape.right(FACING), x).above(y);
    }

    private static PanelSettings settingsAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity be ? be.settings() : null;
    }

    private static PanelSettings sample(String text) {
        return new PanelSettings(PanelSettings.Mode.TEXT, PanelSettings.DEFAULT_BACKGROUND,
                List.of(new PanelSettings.Line(text, PanelSettings.Size.XL, 0xFFAA00, PanelSettings.Align.CENTRE, true)),
                PanelSettings.Image.NONE, "admin", 0);
    }

    @GameTest(template = EMPTY)
    public void panel_settings_keep_to_their_limits_and_round_trip(GameTestHelper h) {
        List<PanelSettings.Line> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new PanelSettings.Line("x".repeat(100) + "§c", PanelSettings.Size.L, 0xFF123456, PanelSettings.Align.LEFT, false));
        }
        PanelSettings s = new PanelSettings(PanelSettings.Mode.IMAGE, 0x12345678, many,
                new PanelSettings.Image(PanelSettings.Source.WEB, "ftp://example.com/a.png", "../secret.png", PanelSettings.Fit.FILL, 7), "admin", 5).sanitised();
        h.assertTrue(s.lines().size() == PanelSettings.MAX_LINES && s.lines().get(0).text().length() == PanelSettings.MAX_LINE, "16 lines of 80: " + s.lines().size());
        h.assertTrue(s.lines().get(0).colour() == 0x123456 && !s.lines().get(0).text().contains("§"), "colour as 0xRRGGBB, no section signs");
        h.assertTrue(s.background() == PanelSettings.DEFAULT_BACKGROUND, "a background outside the swatches falls back to black");
        h.assertTrue(s.image().url().isEmpty() && s.image().file().isEmpty() && s.image().refreshMinutes() == 0, "only http(s) addresses, no paths in file names, a refresh from the choices: " + s.image());
        PanelSettings ok = new PanelSettings(PanelSettings.Mode.IMAGE, PanelSettings.CLEAR, List.of(), new PanelSettings.Image(PanelSettings.Source.FOLDER, "https://example.com/a.png", "spawn-map.png", PanelSettings.Fit.STRETCH, 15), "admin", 9).sanitised();
        h.assertTrue(ok.background() == PanelSettings.CLEAR && ok.image().url().equals("https://example.com/a.png") && ok.image().file().equals("spawn-map.png") && ok.image().refreshMinutes() == 15, "good values are kept: " + ok);
        h.assertTrue(PanelSettings.load(ok.save()).equals(ok) && PanelSettings.load(s.save()).equals(s), "saved and loaded unchanged");
        h.assertTrue(PanelSettings.load(new net.minecraft.nbt.CompoundTag()).equals(PanelSettings.defaults()), "an empty tag is the defaults");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void panels_join_into_the_largest_full_rectangle_with_the_bezel_outside(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(new BlockPos(16, 1, 4));
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                put(level, at(o, x, y));
            }
        }
        PanelShape.Rect r = PanelShape.rectOf(level, at(o, 2, 1), FACING);
        h.assertTrue(r.origin().equals(o) && r.width() == 4 && r.height() == 2, "any block finds the 4 x 2 panel and its bottom-left block: " + r);
        BlockState mid = level.getBlockState(at(o, 1, 0));
        h.assertTrue(mid.getValue(DisplayPanelBlock.LEFT) && mid.getValue(DisplayPanelBlock.RIGHT) && mid.getValue(DisplayPanelBlock.UP) && !mid.getValue(DisplayPanelBlock.DOWN),
                "a bottom-middle block joins left, right and up, not down: " + mid);
        BlockState corner = level.getBlockState(at(o, 3, 0));
        h.assertTrue(!corner.getValue(DisplayPanelBlock.RIGHT) && !corner.getValue(DisplayPanelBlock.DOWN), "the bottom-right block has the bezel right and down (the light): " + corner);

        put(level, at(o, 0, 2)); // one block above the top-left: not a full row
        h.assertTrue(PanelShape.rectOf(level, at(o, 1, 1), FACING).height() == 2, "a part row on top does not count");
        h.assertTrue(PanelShape.rectOf(level, at(o, 0, 2), FACING).width() == 1 && PanelShape.rectOf(level, at(o, 0, 2), FACING).height() == 1, "the odd block is a panel of its own");

        BlockPos o2 = h.absolutePos(new BlockPos(20, 5, 12));
        for (int x = 0; x < 10; x++) {
            put(level, at(o2, x, 0));
        }
        h.assertTrue(PanelShape.rectAt(level, o2, FACING).width() == PanelShape.MAX_W, "a row of 10 makes a panel of 8: " + PanelShape.rectAt(level, o2, FACING));
        BlockState other = ModBlocks.DISPLAY_PANEL.get().defaultBlockState().setValue(DisplayPanelBlock.FACING, Direction.SOUTH);
        level.setBlock(at(o, 4, 0), other, 3);
        h.assertTrue(PanelShape.rectOf(level, at(o, 3, 0), FACING).width() == 4, "a panel facing the other way does not join");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void settings_follow_the_panel_when_blocks_are_added_or_its_corner_is_broken(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(new BlockPos(16, 1, 8));
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                put(level, at(o, x, y));
            }
        }
        PanelSettings saved = PanelShape.save(level, at(o, 1, 1), FACING, sample("WELCOME"));
        h.assertTrue(saved.revision() > 0, "a save stamps a revision");
        for (BlockPos p : PanelShape.rectAt(level, o, FACING).blocks()) {
            h.assertTrue(saved.equals(settingsAt(level, p)), "every block holds the saved settings: " + p);
        }
        BlockPos newOrigin = at(o, -1, 0);
        put(level, newOrigin);
        put(level, at(o, -1, 1));
        PanelShape.Rect grown = PanelShape.rectOf(level, at(o, 0, 0), FACING);
        h.assertTrue(grown.origin().equals(newOrigin) && grown.width() == 4 && grown.height() == 2, "a column added on the left becomes the bottom-left: " + grown);
        h.assertTrue(saved.equals(settingsAt(level, newOrigin)), "and the new bottom-left block has the panel's settings, not the defaults: " + settingsAt(level, newOrigin));

        level.setBlock(newOrigin, Blocks.AIR.defaultBlockState(), 3);
        PanelShape.Rect after = PanelShape.rectOf(level, at(o, 1, 0), FACING);
        h.assertTrue(saved.equals(settingsAt(level, after.origin())), "with the bottom-left block broken the next one shows the same settings: " + after);

        BlockPos lone = at(o, 5, 0);
        put(level, lone);
        PanelSettings newer = PanelShape.save(level, lone, FACING, sample("NEWER"));
        put(level, at(o, 3, 0));
        put(level, at(o, 4, 0)); // the gap filled: the row joins up
        PanelShape.Rect joined = PanelShape.rectOf(level, at(o, 0, 0), FACING);
        PanelSettings won = settingsAt(level, at(o, 0, 0));
        h.assertTrue(won != null && won.lines().get(0).text().equals("NEWER") && won.equals(newer), "two panels joined keep the newer settings: " + joined + " " + won);
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void only_admins_place_open_and_break_panels(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            BlockPos floor = h.absolutePos(new BlockPos(8, 1, 16));
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
            ItemStack stack = new ItemStack(ModBlocks.DISPLAY_PANEL_ITEM.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
            InteractionResult refused = ((net.minecraft.world.item.BlockItem) stack.getItem()).place(new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, player.getMainHandItem(), hit));
            h.assertTrue(refused == InteractionResult.FAIL && !level.getBlockState(floor.above()).is(ModBlocks.DISPLAY_PANEL.get()), "a player who may not edit cannot place one: " + refused);
            admin.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
            InteractionResult placed = ((net.minecraft.world.item.BlockItem) stack.getItem()).place(new BlockPlaceContext(level, admin, InteractionHand.MAIN_HAND, admin.getMainHandItem(), hit));
            h.assertTrue(placed.consumesAction() && level.getBlockState(floor.above()).is(ModBlocks.DISPLAY_PANEL.get()), "an admin can: " + placed);
            BlockPos pos = floor.above();
            BlockState state = level.getBlockState(pos);
            h.assertTrue(state.getDestroyProgress(player, level, pos) == 0F && state.getDestroyProgress(admin, level, pos) > 0F, "a player cannot dig it, an admin can");
            BlockEvent.BreakEvent byPlayer = new BlockEvent.BreakEvent(level, pos, state, player);
            MinecraftForge.EVENT_BUS.post(byPlayer);
            BlockEvent.BreakEvent byAdmin = new BlockEvent.BreakEvent(level, pos, state, admin);
            MinecraftForge.EVENT_BUS.post(byAdmin);
            h.assertTrue(byPlayer.isCanceled() && !byAdmin.isCanceled(), "breaking is refused for a player (creative too) and allowed for an admin");
            h.assertTrue(state.getPistonPushReaction() == PushReaction.BLOCK && state.getBlock().getExplosionResistance() >= 3_600_000F, "pistons and explosions leave it alone");
            DisplayPanelBlockEntity be = (DisplayPanelBlockEntity) level.getBlockEntity(pos);
            h.assertTrue(be != null && PanelSettings.load(be.getUpdateTag().getCompound("panel")).equals(be.settings()), "the update tag carries the settings to clients");
        } finally {
            level.getServer().getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }

    /** Six pixels a character, formatting codes taking none: a stand-in for the client's font. */
    private static int fakeWidth(String s) {
        return s.replaceAll("\u00a7.", "").length() * 6;
    }

    private static PanelSettings.Line line(String text, PanelSettings.Size size, PanelSettings.Align align) {
        return new PanelSettings.Line(text, size, 0xFFFFFF, align, false);
    }

    @GameTest(template = EMPTY)
    public void text_lines_scale_with_the_panel_fit_its_width_and_take_and_codes(GameTestHelper h) {
        List<PanelSettings.Line> two = List.of(line("TITLE", PanelSettings.Size.XL, PanelSettings.Align.CENTRE), line("small", PanelSettings.Size.S, PanelSettings.Align.LEFT));
        List<PanelLayout.Placed> a = PanelLayout.layout(two, 4, 2, PanelGameTests::fakeWidth);
        List<PanelLayout.Placed> b = PanelLayout.layout(two, 8, 4, PanelGameTests::fakeWidth);
        h.assertTrue(Math.abs(a.get(0).slotHeight() / (2 * PanelLayout.PX) - b.get(0).slotHeight() / (4 * PanelLayout.PX)) < 0.001f
                && Math.abs(a.get(0).slotHeight() - 2 * PanelLayout.PX / 4f) < 0.01f, "an XL line is a quarter of the panel's height on any size of panel (D44): " + a.get(0).slotHeight() + " " + b.get(0).slotHeight());
        h.assertTrue(a.get(0).slotHeight() > a.get(1).slotHeight() * 2.9f, "XL is three times S: " + a.get(0).slotHeight() + " / " + a.get(1).slotHeight());
        float mid = (a.get(0).slotTop() + a.get(1).slotTop() + a.get(1).slotHeight()) / 2f;
        h.assertTrue(Math.abs(mid - PanelLayout.PX) < 0.5f, "the lines are centred top to bottom: " + mid);
        h.assertTrue(a.get(0).x() == 2 * PanelLayout.PX && a.get(1).x() == PanelLayout.INSET + PanelLayout.MARGIN, "centred and left anchors: " + a.get(0).x() + " " + a.get(1).x());
        h.assertTrue(Math.abs(a.get(0).left() - (a.get(0).x() - a.get(0).width() / 2)) < 0.01f, "a centred line's left edge is half its width before the centre");

        List<PanelLayout.Placed> wide = PanelLayout.layout(List.of(line("x".repeat(80), PanelSettings.Size.XL, PanelSettings.Align.RIGHT)), 2, 1, PanelGameTests::fakeWidth);
        float room = 2 * PanelLayout.PX - 2 * (PanelLayout.INSET + PanelLayout.MARGIN);
        h.assertTrue(Math.abs(wide.get(0).width() - room) < 0.01f && wide.get(0).scale() < wide.get(0).slotHeight() / PanelLayout.GLYPH, "a line too wide is shrunk to the face's width: " + wide.get(0).width());

        List<PanelSettings.Line> many = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            many.add(line("line " + i, PanelSettings.Size.XL, PanelSettings.Align.CENTRE));
        }
        List<PanelLayout.Placed> squeezed = PanelLayout.layout(many, 2, 1, PanelGameTests::fakeWidth);
        PanelLayout.Placed last = squeezed.get(squeezed.size() - 1);
        h.assertTrue(squeezed.get(0).slotTop() >= PanelLayout.INSET && last.slotTop() + last.slotHeight() <= PanelLayout.PX - PanelLayout.INSET + 0.01f, "too many lines are scaled down together to fit inside the bezel: " + last.slotTop());

        h.assertTrue(PanelLayout.codes("&6gold &lbold && &zno", false).equals("\u00a76gold \u00a7lbold && &zno"), "& codes become formatting codes, others stay: " + PanelLayout.codes("&6gold &lbold && &zno", false));
        h.assertTrue(PanelLayout.codes("Hi", true).equals("\u00a7lHi") && PanelLayout.plain("&6Gold &rText").equals("gold text"), "bold starts bold; plain drops the codes");
        h.assertTrue(PanelLayout.layout(List.of(), 4, 2, PanelGameTests::fakeWidth).isEmpty(), "no lines, nothing placed");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void saving_a_panel_is_checked_on_the_server(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            BlockPos o = h.absolutePos(new BlockPos(16, 1, 18));
            put(level, o);
            put(level, at(o, 1, 0));
            PanelShape.save(level, o, FACING, sample("FIRST").withPlacedBy("owner"));
            Vec3 near = Vec3.atCenterOf(o).add(0, 0, -3);
            player.moveTo(near.x, near.y, near.z);
            admin.moveTo(near.x, near.y, near.z);
            PanelSettings attempt = sample("SECOND").withPlacedBy("someone_else");
            h.assertTrue(PanelEditing.save(player, o, attempt.save()) == PanelEditing.Outcome.REFUSED && settingsAt(level, o).lines().get(0).text().equals("FIRST"), "a player who may not edit changes nothing");
            h.assertTrue(PanelEditing.save(admin, o.above(), attempt.save()) == PanelEditing.Outcome.NOT_A_PANEL, "a save aimed at something else is refused");
            admin.moveTo(near.x + 100, near.y, near.z);
            h.assertTrue(PanelEditing.save(admin, o, attempt.save()) == PanelEditing.Outcome.TOO_FAR, "an admin far away cannot save");
            admin.moveTo(near.x, near.y, near.z);
            h.assertTrue(PanelEditing.save(admin, o, attempt.save()) == PanelEditing.Outcome.SAVED, "an admin nearby saves");
            PanelSettings now = settingsAt(level, at(o, 1, 0));
            h.assertTrue(now.lines().get(0).text().equals("SECOND") && now.placedBy().equals("owner"), "every block has the new lines, and who placed it comes from the panel, not the client: " + now);

            PanelOpenPacket open = new PanelOpenPacket(o, 2, 1, now.save(), "BSP-Spawn", List.of(new com.mrgregles.bsp_server_utilities.image.ImageService.FolderFile("spawn-map.png", 312_000, 1024, 512)));
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            open.encode(buf);
            PanelOpenPacket back = PanelOpenPacket.decode(buf);
            h.assertTrue(back.origin().equals(o) && back.width() == 2 && back.server().equals("BSP-Spawn") && back.files().equals(open.files()) && PanelSettings.load(back.settings()).equals(now), "the open packet round trips");
            PanelSavePacket save = new PanelSavePacket(o, now.save());
            buf = new FriendlyByteBuf(Unpooled.buffer());
            save.encode(buf);
            h.assertTrue(PanelSettings.load(PanelSavePacket.decode(buf).settings()).equals(now), "the save packet round trips");
        } finally {
            level.getServer().getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }
}
