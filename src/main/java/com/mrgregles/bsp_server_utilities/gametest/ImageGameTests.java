package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.image.ImageException;
import com.mrgregles.bsp_server_utilities.image.ImageService;
import com.mrgregles.bsp_server_utilities.network.ImageChunkPacket;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlock;
import com.mrgregles.bsp_server_utilities.panel.DisplayPanelBlockEntity;
import com.mrgregles.bsp_server_utilities.panel.PanelEditing;
import com.mrgregles.bsp_server_utilities.panel.PanelLayout;
import com.mrgregles.bsp_server_utilities.panel.PanelSettings;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.phys.Vec3;
import com.mrgregles.bsp_server_utilities.panel.PanelShape;
import com.mrgregles.bsp_server_utilities.registry.ModBlocks;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Pictures on Display Panels, server side (owner decisions D18 to D21, D49): reading PNG, JPEG and GIF, scaling down to
 * what the panel needs, refusing what is not a picture or too big, fetching from a web address (a local test server),
 * the images folder, cutting a picture into pieces and joining them again, and a panel taking the result.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class ImageGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    static byte[] picture(int w, int h, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, format.equals("png") ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3A5A8A));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.ORANGE);
        g.fillRect(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @GameTest(template = EMPTY)
    public void pictures_are_read_scaled_down_and_refused_when_they_are_not_pictures(GameTestHelper h) throws Exception {
        ImageService.Picture small = ImageService.process(picture(400, 200, "png"), 4, 2, 128);
        h.assertTrue(small.loaded().width() == 400 && small.loaded().height() == 200 && small.loaded().format().equals("PNG"), "a small picture is never scaled up: " + small.loaded());
        ImageService.Picture big = ImageService.process(picture(3000, 1500, "png"), 4, 2, 128);
        h.assertTrue(big.loaded().width() == 512 && big.loaded().height() == 256 && big.loaded().sourceWidth() == 3000, "a big one comes down to what a 4 x 2 panel needs at 128 a block: " + big.loaded());
        ImageService.Picture tall = ImageService.process(picture(500, 2000, "png"), 4, 2, 128);
        h.assertTrue(tall.loaded().height() == 512 && tall.loaded().width() == 128, "never more than the panel's longer side: " + tall.loaded());
        ImageService.Picture jpeg = ImageService.process(picture(300, 300, "jpg"), 2, 2, 128);
        ImageService.Picture gif = ImageService.process(picture(64, 32, "gif"), 1, 1, 128);
        h.assertTrue(jpeg.loaded().format().equals("JPEG") && gif.loaded().format().equals("GIF"), "JPEG and GIF are read: " + jpeg.loaded().format() + " " + gif.loaded().format());
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(big.png()));
        h.assertTrue(back.getWidth() == 512 && big.loaded().hash().length() == 32 && big.loaded().hash().equals(ImageService.hash(big.png())), "the result is a PNG under its hash");
        h.assertTrue(small.loaded().hash().equals(ImageService.process(picture(400, 200, "png"), 4, 2, 128).loaded().hash()), "the same picture has the same hash");

        String key = "";
        try {
            ImageService.process("not a picture".getBytes(), 1, 1, 128);
        } catch (ImageException e) {
            key = e.key();
        }
        h.assertTrue(key.equals("not_an_image"), "text is not a picture: " + key);
        key = "";
        try {
            ImageService.process(picture(ImageService.MAX_SOURCE_SIDE + 1, 1, "png"), 1, 1, 128);
        } catch (ImageException e) {
            key = e.key();
        }
        h.assertTrue(key.equals("too_many_pixels"), "a picture wider than 8192 is refused before it is decoded: " + key);
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 400)
    public void pictures_are_fetched_from_a_web_address_within_the_limits(GameTestHelper h) throws Exception {
        byte[] png = picture(200, 100, "png");
        HttpServer web = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        web.createContext("/map.png", ex -> {
            ex.sendResponseHeaders(200, png.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(png);
            }
        });
        web.createContext("/missing.png", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        web.start();
        try {
            String base = "http://127.0.0.1:" + web.getAddress().getPort();
            byte[] got = ImageService.fetch(base + "/map.png", 4096 * 1024, 5);
            h.assertTrue(java.util.Arrays.equals(got, png), "the picture comes back whole");
            String key = "";
            try {
                ImageService.fetch(base + "/missing.png", 4096 * 1024, 5);
            } catch (ImageException e) {
                key = e.key() + " " + e.detail();
            }
            h.assertTrue(key.equals("http 404"), "a missing picture says so: " + key);
            key = "";
            try {
                ImageService.fetch(base + "/map.png", png.length - 1, 5);
            } catch (ImageException e) {
                key = e.key();
            }
            h.assertTrue(key.equals("too_big"), "more than the limit is refused: " + key);
            key = "";
            try {
                ImageService.fetch("ftp://example.com/a.png", 1024, 5);
            } catch (ImageException e) {
                key = e.key();
            }
            h.assertTrue(key.equals("bad_address"), "only http and https: " + key);
            ImageService.Picture p = ImageService.loadNow(new PanelSettings.Image(PanelSettings.Source.WEB, base + "/map.png", "", PanelSettings.Fit.INSIDE, 0), 2, 1);
            h.assertTrue(p.loaded().ok() && p.loaded().sourceBytes() == png.length, "loading a panel's web picture: " + p.loaded());
        } finally {
            web.stop(0);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_images_folder_lists_and_reads_only_its_own_files(GameTestHelper h) throws Exception {
        String name = "su_test_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        Path file = ImageService.folder().resolve(name);
        Files.write(file, picture(320, 160, "png"));
        try {
            List<ImageService.FolderFile> files = ImageService.folderFiles();
            ImageService.FolderFile f = files.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
            h.assertTrue(f != null && f.width() == 320 && f.height() == 160 && f.bytes() == Files.size(file), "the file is listed with its size: " + f);
            h.assertTrue(ImageService.resolve("../server.properties").isEmpty() && ImageService.resolve("a/b.png").isEmpty() && ImageService.resolve(name).isPresent(), "no way out of the folder");
            ImageService.Picture p = ImageService.loadNow(new PanelSettings.Image(PanelSettings.Source.FOLDER, "", name, PanelSettings.Fit.FILL, 0), 2, 1);
            h.assertTrue(p.loaded().ok() && p.loaded().sourceWidth() == 320, "a panel's folder picture loads: " + p.loaded());
            String key = "";
            try {
                ImageService.loadNow(new PanelSettings.Image(PanelSettings.Source.FOLDER, "", "su_no_such_file.png", PanelSettings.Fit.FILL, 0), 2, 1);
            } catch (ImageException e) {
                key = e.key();
            }
            h.assertTrue(key.equals("no_file"), "a missing file says so: " + key);
        } finally {
            Files.deleteIfExists(file);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void pictures_go_out_in_pieces_and_a_panel_takes_the_result(GameTestHelper h) throws Exception {
        byte[] png = new byte[ImageService.CHUNK * 2 + 123];
        new java.util.Random(7).nextBytes(png);
        List<ImageChunkPacket> parts = ImageChunkPacket.split("abc", png);
        h.assertTrue(parts.size() == 3 && parts.get(2).data().length == 123, "cut into pieces of 32 KB: " + parts.size());
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (ImageChunkPacket part : parts) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            part.encode(buf);
            joined.writeBytes(ImageChunkPacket.decode(buf).data());
        }
        h.assertTrue(java.util.Arrays.equals(joined.toByteArray(), png), "the pieces join up again");

        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(new BlockPos(16, 1, 4));
        var state = ModBlocks.DISPLAY_PANEL.get().defaultBlockState().setValue(DisplayPanelBlock.FACING, Direction.NORTH);
        level.setBlock(o, DisplayPanelBlock.connected(state, level, o), 3);
        BlockPos second = o.relative(PanelShape.right(Direction.NORTH));
        level.setBlock(second, DisplayPanelBlock.connected(state, level, second), 3);
        PanelSettings.Image image = new PanelSettings.Image(PanelSettings.Source.WEB, "https://example.com/map.png", "", PanelSettings.Fit.INSIDE, 15);
        PanelSettings base = new PanelSettings(PanelSettings.Mode.IMAGE, PanelSettings.DEFAULT_BACKGROUND, List.of(), image, "admin", 0);
        PanelShape.save(level, o, Direction.NORTH, base);
        h.assertTrue(ImageService.nextRefresh(level, o) != null, "a web picture with a refresh time gets a timer");

        ImageService.Picture pic = ImageService.process(picture(256, 128, "png"), 2, 1, 128);
        ImageService.apply(level, o, image, pic, pic.loaded());
        PanelSettings now = ((DisplayPanelBlockEntity) level.getBlockEntity(second)).settings();
        h.assertTrue(now.loaded().hash().equals(pic.loaded().hash()) && now.loaded().width() == 256, "every block of the panel knows the picture: " + now.loaded());
        h.assertTrue(java.util.Arrays.equals(ImageService.cached(pic.loaded().hash()), pic.png()), "and the server keeps its bytes to send");

        PanelSettings.Image other = new PanelSettings.Image(PanelSettings.Source.WEB, "https://example.com/other.png", "", PanelSettings.Fit.INSIDE, 0);
        ImageService.apply(level, o, other, null, PanelSettings.Loaded.failed("http", "500"));
        h.assertTrue(((DisplayPanelBlockEntity) level.getBlockEntity(o)).settings().loaded().ok(), "a result for a source the panel no longer shows is ignored");
        ImageService.apply(level, o, image, null, PanelSettings.Loaded.failed("http", "404"));
        PanelSettings failed = ((DisplayPanelBlockEntity) level.getBlockEntity(o)).settings();
        h.assertTrue(failed.loaded().error().equals("http") && failed.loaded().detail().equals("404"), "a failure is written for the settings screen to show: " + failed.loaded());

        PanelShape.save(level, o, Direction.NORTH, failed.withImage(new PanelSettings.Image(PanelSettings.Source.WEB, image.url(), "", PanelSettings.Fit.INSIDE, 0)));
        h.assertTrue(ImageService.nextRefresh(level, o) == null, "with the timer off the panel has none");
        h.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public void a_panel_loads_its_picture_in_the_background(GameTestHelper h) throws Exception {
        String name = "su_test_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        Path file = ImageService.folder().resolve(name);
        Files.write(file, picture(300, 150, "png"));
        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(new BlockPos(16, 1, 10));
        var state = ModBlocks.DISPLAY_PANEL.get().defaultBlockState().setValue(DisplayPanelBlock.FACING, Direction.NORTH);
        level.setBlock(o, DisplayPanelBlock.connected(state, level, o), 3);
        PanelSettings.Image image = new PanelSettings.Image(PanelSettings.Source.FOLDER, "", name, PanelSettings.Fit.INSIDE, 0);
        PanelShape.save(level, o, Direction.NORTH, new PanelSettings(PanelSettings.Mode.IMAGE, PanelSettings.DEFAULT_BACKGROUND, List.of(), image, "admin", 0));
        ImageService.load(level, o);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(((DisplayPanelBlockEntity) level.getBlockEntity(o)).settings().loaded().ok(), "the picture arrives on the panel"))
                .thenExecute(() -> {
                    PanelSettings.Loaded l = ((DisplayPanelBlockEntity) level.getBlockEntity(o)).settings().loaded();
                    h.assertTrue(l.sourceWidth() == 300 && l.width() == 128 && ImageService.cached(l.hash()) != null, "scaled for a 1 x 1 panel and kept to send: " + l);
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // the test world's config folder keeps a stray file
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY)
    public void pictures_fit_inside_fill_and_crop_or_stretch(GameTestHelper h) {
        PanelLayout.Fitted in = PanelLayout.fit(PanelSettings.Fit.INSIDE, 200, 100, 400, 400);
        h.assertTrue(in.w() == 400 && in.h() == 200 && in.x() == 0 && in.y() == 100 && in.u0() == 0 && in.v1() == 1, "fit inside: all of it, centred, bands above and below: " + in);
        PanelLayout.Fitted fill = PanelLayout.fit(PanelSettings.Fit.FILL, 200, 100, 400, 400);
        h.assertTrue(fill.w() == 400 && fill.h() == 400 && Math.abs(fill.u0() - 0.25f) < 1e-4 && Math.abs(fill.u1() - 0.75f) < 1e-4 && fill.v0() == 0 && fill.v1() == 1,
                "fill and crop: the area covered, the middle half of the width shown: " + fill);
        PanelLayout.Fitted st = PanelLayout.fit(PanelSettings.Fit.STRETCH, 200, 100, 400, 400);
        h.assertTrue(st.w() == 400 && st.h() == 400 && st.u0() == 0 && st.u1() == 1 && st.v1() == 1, "stretch: all of it over the whole area: " + st);
        h.assertTrue(PanelLayout.fit(PanelSettings.Fit.INSIDE, 0, 0, 10, 10).w() == 0, "nothing to draw without a picture");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_saved_panel_keeps_the_servers_record_of_its_picture(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            BlockPos o = h.absolutePos(new BlockPos(16, 1, 16));
            var state = ModBlocks.DISPLAY_PANEL.get().defaultBlockState().setValue(DisplayPanelBlock.FACING, Direction.NORTH);
            level.setBlock(o, DisplayPanelBlock.connected(state, level, o), 3);
            Vec3 near = Vec3.atCenterOf(o).add(0, 0, -3);
            admin.moveTo(near.x, near.y, near.z);
            PanelSettings.Image image = new PanelSettings.Image(PanelSettings.Source.FOLDER, "", "su_none.png", PanelSettings.Fit.INSIDE, 0);
            PanelSettings.Loaded real = new PanelSettings.Loaded("0123456789abcdef0123456789abcdef", "PNG", 10, 10, 100, 10, 10, 1L, "", "");
            PanelShape.save(level, o, Direction.NORTH, new PanelSettings(PanelSettings.Mode.IMAGE, PanelSettings.DEFAULT_BACKGROUND, List.of(), image, "admin", 0).withLoaded(real));
            PanelSettings forged = new PanelSettings(PanelSettings.Mode.IMAGE, PanelSettings.CLEAR, List.of(), image, "x", 0)
                    .withLoaded(new PanelSettings.Loaded("ffffffffffffffffffffffffffffffff", "PNG", 1, 1, 1, 1, 1, 1L, "", ""));
            h.assertTrue(PanelEditing.save(admin, o, forged.save()) == PanelEditing.Outcome.SAVED, "saved");
            PanelSettings now = ((DisplayPanelBlockEntity) level.getBlockEntity(o)).settings();
            h.assertTrue(now.loaded().equals(real) && now.background() == PanelSettings.CLEAR, "the background changed, the picture record is the server's own: " + now.loaded());
        } finally {
            level.getServer().getPlayerList().getOps().remove(admin.getGameProfile());
        }
        h.succeed();
    }
}
