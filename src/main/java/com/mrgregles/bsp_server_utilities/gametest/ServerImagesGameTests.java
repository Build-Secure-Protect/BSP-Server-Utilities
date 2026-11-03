package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.log.LogEntry;
import com.mrgregles.bsp_server_utilities.log.LogText;
import com.mrgregles.bsp_server_utilities.network.AdminServersEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminServersPacket;
import com.mrgregles.bsp_server_utilities.network.ServerImageListPacket;
import com.mrgregles.bsp_server_utilities.network.ServerImagePacket;
import com.mrgregles.bsp_server_utilities.serverimages.ServerImageSet;
import com.mrgregles.bsp_server_utilities.serverimages.ServerImages;
import com.mrgregles.bsp_server_utilities.serverimages.ServerImagesData;
import com.mrgregles.bsp_server_utilities.serverimages.ServerTag;
import com.mrgregles.bsp_server_utilities.serverimages.ServersAdmin;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.SetupData;
import com.mrgregles.bsp_server_utilities.setup.SetupService;
import com.mrgregles.bsp_server_utilities.setup.SetupState;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Server images in chat, server side (SU-26; owner decisions D189 to D204): each file checked against the limits (PNG,
 * not animated, 512 x 64, 256 KB, 96 pixels wide in chat, a server's name); a refresh becoming the network's set on every
 * server through the shared storage, with the bytes fetched by a server that lacks them; replacing or removing an image
 * in use waiting for a second click while adding goes through; each player sent the list and every image once a login,
 * and an empty list when the feature is off; the tag's translatable with its grey fallback, white, in the font, with the
 * server's name on hover; the SERVERS tab's names and marks, for admins only. The font, the language wrapper beside
 * RankUp's and the tab's drawing need a client; the MySQL tables need a database (MemoryBackend stands in).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class ServerImagesGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    /** Everything these tests change, put back afterwards. */
    private record Saved(ServerImageSet set, Map<String, byte[]> bytes, SetupState setup, SetupState.Status welcome, Path folder) {
        static Saved of(MinecraftServer server) throws IOException {
            ServerImageSet set = ServerImages.set(server);
            Map<String, byte[]> bytes = new HashMap<>();
            set.ids().forEach(id -> {
                byte[] b = ServerImages.bytes(server, id);
                if (b != null) {
                    bytes.put(id, b);
                }
            });
            return new Saved(set, bytes, SetupService.state(server), SetupData.get(server).welcome(), Files.createTempDirectory("su-server-images"));
        }

        void restore(MinecraftServer server) {
            Storage.useForTests(null);
            ServerImages.useReal();
            ServersAdmin.useForTests(null);
            ServerImagesData data = ServerImagesData.get(server);
            bytes.forEach(data::put);
            data.setSet(set);
            SetupService.restoreForTests(server, setup, welcome);
            try (Stream<Path> files = Files.walk(folder)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // a temporary folder
            }
        }
    }

    /** No images, older than anything a refresh writes, and the feature on. */
    private static void fresh(MinecraftServer server) {
        ServerImagesData.get(server).setSet(ServerImageSet.EMPTY);
        SetupService.restoreForTests(server, SetupService.state(server).withOff(Feature.SERVERS, false), SetupData.get(server).welcome());
    }

    static byte[] png(int w, int h, Color colour) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @GameTest(template = EMPTY)
    public void each_file_is_checked_against_the_limits(GameTestHelper h) throws IOException {
        byte[] ok = png(384, 64, Color.ORANGE);
        ServerImages.FileInfo good = ServerImages.check("BSP-Spawn", "BSP-Spawn.png", ok);
        h.assertTrue(good.usable() && good.width() == 384 && good.height() == 64 && good.id().equals(ServerImages.idOf(ok)) && good.id().length() == 40,
                "a 384 x 64 PNG is taken, known by its hash: " + good);
        h.assertTrue(ServerImages.drawnWidth(384, 64) == 54 && ServerImages.drawnWidth(512, 48) == 96, "drawn one chat line high (9): 384 x 64 is 54 wide");
        h.assertTrue(ServerImages.check("BSP-Spawn", "f.png", png(640, 64, Color.RED)).problem().equals("640 x 64 is over 512 x 64"), "over 512 x 64 is refused (D192)");
        h.assertTrue(ServerImages.check("BSP-Spawn", "f.png", png(512, 32, Color.RED)).problem().startsWith("too wide: 144 px"), "wider than 96 in chat is refused");
        byte[] big = Arrays.copyOf(ok, ServerImages.MAX_BYTES + 1);
        h.assertTrue(ServerImages.check("BSP-Spawn", "f.png", big).problem().equals("over 256 KB"), "over 256 KB is refused");
        h.assertTrue(ServerImages.check("BSP-Spawn", "f.png", "hello, not a picture at all, just text".getBytes()).problem().equals("not a PNG"), "not a PNG");
        byte[] animated = new byte[ok.length + 20];
        System.arraycopy(ok, 0, animated, 0, 33); // the signature and IHDR
        byte[] actl = {0, 0, 0, 8, 'a', 'c', 'T', 'L', 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0};
        System.arraycopy(actl, 0, animated, 33, 20);
        System.arraycopy(ok, 33, animated, 53, ok.length - 33);
        h.assertTrue(ServerImages.check("BSP-Spawn", "f.png", animated).problem().equals("animated"), "an animated PNG is refused");
        h.assertTrue(ServerImages.check("my server!", "my server!.png", ok).problem().equals("not a server name"), "a file that cannot be a server's name is refused");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_refresh_becomes_the_network_set_on_every_server(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha"), beta = Storage.startWith(server, db, "beta");
        alpha.drain();
        beta.drain();
        try {
            fresh(server);
            byte[] spawn = png(256, 64, Color.ORANGE), wild = png(128, 32, Color.RED);
            Files.write(saved.folder().resolve("BSP-Spawn.png"), spawn);
            Files.write(saved.folder().resolve("BSP-Wild.png"), wild);
            ServerImages.useForTests(System::currentTimeMillis, saved.folder(), (p, packet) -> {
            });
            Storage.useForTests(alpha);
            ServerImages.Plan plan = ServerImages.refresh(server, null, "Console", false);
            alpha.drain();
            h.assertTrue(plan.applied() && plan.added().equals(List.of("BSP-Spawn", "BSP-Wild")), "adding alone goes through on the first click (D201): " + plan);
            ServerImageSet set = ServerImages.set(server);
            h.assertTrue(set.entries().size() == 2 && set.of("bsp-spawn") != null && set.of("BSP-Spawn").id().equals(ServerImages.idOf(spawn)), "the folder is the set: " + set);
            ServerImageSet shared = db.loadServerImages();
            h.assertTrue(shared != null && shared.sameImages(set) && Arrays.equals(db.loadServerImage(ServerImages.idOf(wild)), wild), "kept in the shared storage with its bytes (D196)");
            LogEntry e = ActionLog.recent(server, 1, false, List.of(LogEntry.CMD)).get(0);
            h.assertTrue(e.actor().equals("Console") && e.text().equals("refreshed the server images: added BSP-Spawn, BSP-Wild"), "written to the Action Log: " + e);
            // the second server never had the files: it hears the notice and fetches the bytes
            ServerImagesData.get(server).setSet(ServerImageSet.EMPTY);
            h.assertTrue(ServerImages.bytes(server, ServerImages.idOf(spawn)) == null, "a server without the images");
            Storage.useForTests(beta);
            beta.pollNotices();
            beta.drain();
            h.assertTrue(ServerImages.set(server).sameImages(set), "takes the newer set from the notice");
            h.assertTrue(Arrays.equals(ServerImages.bytes(server, ServerImages.idOf(spawn)), spawn) && Arrays.equals(ServerImages.bytes(server, ServerImages.idOf(wild)), wild),
                    "and fetches the images it lacked from the shared storage");
            // removing one on the first server drops its bytes from the table
            Files.delete(saved.folder().resolve("BSP-Wild.png"));
            Storage.useForTests(alpha);
            plan = ServerImages.refresh(server, null, "Console", true);
            alpha.drain();
            h.assertTrue(plan.applied() && plan.removed().equals(List.of("BSP-Wild")) && db.loadServerImage(ServerImages.idOf(wild)) == null && db.loadServerImages().entries().size() == 1,
                    "a file left out removes that server's image for the network (D196)");
        } finally {
            alpha.close();
            beta.close();
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void replacing_or_removing_waits_for_the_second_click(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        try {
            fresh(server);
            Storage.useForTests(null);
            ServerImages.useForTests(System::currentTimeMillis, saved.folder(), (p, packet) -> {
            });
            Path a = saved.folder().resolve("BSP-A.png"), b = saved.folder().resolve("BSP-B.png");
            Files.write(a, png(64, 16, Color.BLUE));
            h.assertTrue(ServerImages.refresh(server, null, "Console", false).applied(), "adding goes through");
            String first = ServerImages.set(server).of("BSP-A").id();
            Files.write(a, png(64, 16, Color.GREEN));
            ServerImages.Plan plan = ServerImages.refresh(server, null, "Console", false);
            h.assertTrue(!plan.applied() && plan.needsConfirm() && plan.replaced().equals(List.of("BSP-A")) && ServerImages.set(server).of("BSP-A").id().equals(first),
                    "a different picture for a server in use waits for SURE? and changes nothing (D201, D203): " + plan);
            h.assertTrue(ServerImages.refresh(server, null, "Console", true).applied() && !ServerImages.set(server).of("BSP-A").id().equals(first), "confirmed, it is replaced");
            Files.write(b, png(64, 16, Color.CYAN));
            Files.delete(a);
            plan = ServerImages.refresh(server, null, "Console", false);
            h.assertTrue(!plan.applied() && plan.added().equals(List.of("BSP-B")) && plan.removed().equals(List.of("BSP-A")) && ServerImages.set(server).of("BSP-B") == null,
                    "adding beside a removal still waits: " + plan);
            h.assertTrue(ServerImages.refresh(server, null, "Console", true).applied() && ServerImages.set(server).entries().size() == 1, "confirmed");
            plan = ServerImages.refresh(server, null, "Console", false);
            h.assertTrue(!plan.applied() && !plan.changes(), "the folder unchanged: nothing to do");
            Files.write(b, png(640, 64, Color.CYAN));
            plan = ServerImages.refresh(server, null, "Console", false);
            h.assertTrue(!plan.applied() && plan.removed().equals(List.of("BSP-B")), "a refused file for a server in use counts as removing its image: " + plan);
            byte[] wide = png(512, 64, Color.PINK);
            Files.write(saved.folder().resolve("BSP-Wide.png"), wide);
            ServerImages.Read scaled = ServerImages.scan().stream().filter(r -> r.info().server().equals("BSP-Wide")).findFirst().orElseThrow();
            h.assertTrue(scaled.info().usable() && scaled.info().width() == ServerImages.MAX_SHEET && scaled.info().height() == 32 && scaled.png() != null
                            && scaled.info().id().equals(ServerImages.idOf(scaled.png())) && !scaled.info().id().equals(ServerImages.idOf(wide)),
                    "a file wider than the game's 256-pixel font sheets is kept scaled to 256 wide, under its new hash: " + scaled.info());
            h.assertTrue(ServerImages.scan().stream().filter(r -> r.info().server().equals("BSP-Wide")).findFirst().orElseThrow().info().equals(scaled.info()),
                    "an unchanged file is read the same again (from the cache)");
        } finally {
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void players_get_each_image_once_a_login_and_nothing_when_off(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        List<Object> sent = new ArrayList<>();
        try {
            fresh(server);
            Storage.useForTests(null);
            ServerImages.useForTests(System::currentTimeMillis, saved.folder(), (p, packet) -> sent.add(packet));
            byte[] spawn = png(256, 64, Color.ORANGE);
            Files.write(saved.folder().resolve("BSP-Spawn.png"), spawn);
            Files.write(saved.folder().resolve("BSP-Moongrove.png"), png(128, 32, Color.GRAY));
            ServerImages.refresh(server, null, "Console", false);
            ServerPlayer p = TestPlayers.mockServerPlayer(h);
            sent.clear();
            ServerImages.onLogin(p);
            ServerImageListPacket list = (ServerImageListPacket) sent.get(0);
            h.assertTrue(list.on() && list.items().size() == 2 && list.items().contains(new ServerImageListPacket.Item("BSP-Spawn", ServerImages.idOf(spawn))),
                    "at login: the list of servers and their images: " + list);
            h.assertTrue(sent.size() == 3 && sent.stream().skip(1).allMatch(o -> o instanceof ServerImagePacket) && ServerImages.wasSent(p.getUUID(), ServerImages.idOf(spawn)),
                    "then each image's bytes: " + sent.size());
            sent.clear();
            ServerImages.sendTo(p);
            h.assertTrue(sent.size() == 1 && sent.get(0) instanceof ServerImageListPacket, "again in the same login: the list, no bytes twice");
            SetupService.turnOff(server, Feature.SERVERS);
            h.assertTrue(Switches.off(Feature.SERVERS) && ServerImages.off(), "TURN OFF FEATURE (D198)");
            sent.clear();
            ServerImages.sendTo(p);
            h.assertTrue(sent.size() == 1 && !((ServerImageListPacket) sent.get(0)).on() && ((ServerImageListPacket) sent.get(0)).items().isEmpty(),
                    "off: an empty list, so every tag reads as its text");
            SetupService.turnOn(server, Feature.SERVERS);
            sent.clear();
            ServerImages.onLogin(p);
            h.assertTrue(((ServerImageListPacket) sent.get(0)).on() && sent.size() == 3, "on again: the list and the images at the next login");
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            list.encode(buf);
            h.assertTrue(ServerImageListPacket.decode(buf).equals(list), "the list's bytes");
            buf = new FriendlyByteBuf(Unpooled.buffer());
            new ServerImagePacket(ServerImages.idOf(spawn), spawn).encode(buf);
            ServerImagePacket back = ServerImagePacket.decode(buf);
            h.assertTrue(back.id().equals(ServerImages.idOf(spawn)) && Arrays.equals(back.png(), spawn), "an image's bytes");
        } finally {
            saved.restore(server);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_tag_is_the_image_key_with_the_name_as_its_fallback(GameTestHelper h) {
        Component tag = ServerTag.of("BSP-Spawn");
        h.assertTrue(tag.getContents() instanceof TranslatableContents tc && tc.getKey().equals("bsp_server_utilities.server_image.bsp-spawn") && "%s".equals(tc.getFallback()),
                "the key names the server in lower case, the fallback is its argument (D194)");
        TranslatableContents tc = (TranslatableContents) tag.getContents();
        Component arg = (Component) tc.getArgs()[0];
        h.assertTrue(arg.getString().equals("[BSP-Spawn]") && arg.getStyle().getColor() != null && arg.getStyle().getColor().getValue() == ChatFormatting.GRAY.getColor(),
                "a reader without the image sees today's grey [BSP-Spawn] (D195)");
        h.assertTrue(tag.getString().equals("[BSP-Spawn]"), "and so do the console and the logs: " + tag.getString());
        h.assertTrue(ServerTag.FONT.equals(tag.getStyle().getFont()) && tag.getStyle().getColor() != null && tag.getStyle().getColor().getValue() == 0xFFFFFF,
                "white in the server images' font, so the image keeps its colours");
        HoverEvent hover = tag.getStyle().getHoverEvent();
        h.assertTrue(hover != null && hover.getValue(HoverEvent.Action.SHOW_TEXT) != null && hover.getValue(HoverEvent.Action.SHOW_TEXT).getString().equals("BSP-Spawn"),
                "hovering shows the server's name (D193)");
        ServerImageSet set = new ServerImageSet(List.of(new ServerImageSet.Entry("BSP-Spawn", "a".repeat(40), "BSP-Spawn.png", 256, 64, 900),
                new ServerImageSet.Entry("BSP-Frostwarf", "b".repeat(40), "BSP-Frostwarf.png", 384, 64, 1800)), 42);
        h.assertTrue(set.equals(ServerImageSet.decode(set.encode())) && set.equals(ServerImageSet.load(set.save())) && set.entries().get(0).name().equals("BSP-Frostwarf"),
                "the set is sorted by name and survives its encoding and NBT");
        h.assertTrue(LogText.selfLogged("su serverimages refresh") && LogText.selfLogged("/serverutils serverimages refresh confirm") && !LogText.selfLogged("su serverimages list"),
                "a refresh writes its own Action Log entry, so the command is not logged twice");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_servers_tab_lists_every_name_with_its_mark_for_admins_only(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        Saved saved = Saved.of(server);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h), player = TestPlayers.mockServerPlayer(h);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        List<Object> sent = new ArrayList<>(), images = new ArrayList<>();
        try {
            fresh(server);
            Storage.useForTests(null);
            ServerImages.useForTests(System::currentTimeMillis, saved.folder(), (p, packet) -> images.add(packet));
            ServersAdmin.useForTests((p, packet) -> sent.add(packet));
            Files.write(saved.folder().resolve("BSP-Wild.png"), png(128, 32, Color.RED));
            Files.write(saved.folder().resolve("BSP-Moongrove.png"), png(640, 64, Color.GRAY));
            h.assertTrue(!ServersAdmin.apply(player, new AdminServersEditPacket(AdminServersEditPacket.Action.REFRESH, true)) && sent.isEmpty() && ServerImages.set(server).entries().isEmpty(),
                    "players who may not edit change nothing and see nothing");
            ServersAdmin.apply(admin, new AdminServersEditPacket(AdminServersEditPacket.Action.VIEW, false));
            AdminServersPacket view = (AdminServersPacket) sent.get(0);
            String here = Storage.currentServerId();
            List<String> names = view.rows().stream().map(AdminServersPacket.Row::name).toList();
            List<String> sorted = new ArrayList<>(names);
            sorted.sort(String.CASE_INSENSITIVE_ORDER);
            h.assertTrue(names.equals(sorted) && names.contains("BSP-Wild") && names.contains("BSP-Moongrove") && names.stream().anyMatch(n -> n.equalsIgnoreCase(here)),
                    "one alphabetical list: this server and every name with a file (D202): " + names);
            AdminServersPacket.Row wild = view.rows().stream().filter(r -> r.name().equals("BSP-Wild")).findFirst().orElseThrow();
            AdminServersPacket.Row moon = view.rows().stream().filter(r -> r.name().equals("BSP-Moongrove")).findFirst().orElseThrow();
            AdminServersPacket.Row self = view.rows().stream().filter(r -> r.name().equalsIgnoreCase(here)).findFirst().orElseThrow();
            h.assertTrue(self.mark() == AdminServersPacket.Mark.HERE && wild.mark() == AdminServersPacket.Mark.UNKNOWN, "marked here, and ? for a server never heard from");
            h.assertTrue(!wild.inUse() && !wild.refused() && wild.folderWidth() == 128 && moon.refused() && moon.problem().equals("640 x 64 is over 512 x 64"),
                    "the ready file and the refused one with its problem");
            h.assertTrue(images.stream().anyMatch(o -> o instanceof ServerImagePacket i && i.id().equals(wild.folderId())), "the admin gets the folder's usable files for the previews");
            h.assertTrue(!view.shared() && view.files() == 2 && view.outcome() == AdminServersPacket.Outcome.NONE, "without shared storage the tab says this server only (D204)");
            sent.clear();
            ServersAdmin.apply(admin, new AdminServersEditPacket(AdminServersEditPacket.Action.REFRESH, false));
            view = (AdminServersPacket) sent.get(0);
            h.assertTrue(view.outcome() == AdminServersPacket.Outcome.DONE && view.added().equals(List.of("BSP-Wild")) && ServerImages.set(server).of("BSP-Wild") != null,
                    "REFRESH that only adds goes through and says so (D203)");
            sent.clear();
            ServersAdmin.apply(admin, new AdminServersEditPacket(AdminServersEditPacket.Action.REFRESH, false));
            h.assertTrue(((AdminServersPacket) sent.get(0)).outcome() == AdminServersPacket.Outcome.NOTHING, "again: nothing changed");
            Files.delete(saved.folder().resolve("BSP-Wild.png"));
            sent.clear();
            ServersAdmin.apply(admin, new AdminServersEditPacket(AdminServersEditPacket.Action.REFRESH, false));
            view = (AdminServersPacket) sent.get(0);
            h.assertTrue(view.outcome() == AdminServersPacket.Outcome.PENDING && view.removed().equals(List.of("BSP-Wild")) && ServerImages.set(server).of("BSP-Wild") != null,
                    "removing waits for SURE?, naming what goes");
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            view.encode(buf);
            h.assertTrue(AdminServersPacket.decode(buf).equals(view), "the tab's bytes");
            buf = new FriendlyByteBuf(Unpooled.buffer());
            new AdminServersEditPacket(AdminServersEditPacket.Action.REFRESH, true).encode(buf);
            h.assertTrue(AdminServersEditPacket.decode(buf).equals(new AdminServersEditPacket(AdminServersEditPacket.Action.REFRESH, true)), "the tab's answer");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            saved.restore(server);
        }
        h.succeed();
    }
}
