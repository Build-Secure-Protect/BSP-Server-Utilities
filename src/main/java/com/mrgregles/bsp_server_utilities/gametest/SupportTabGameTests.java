package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.AdminSupportEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminSupportPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.support.SupportAdmin;
import com.mrgregles.bsp_server_utilities.support.SupportService;
import com.mrgregles.bsp_server_utilities.support.SupportSettings;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The SUPPORT tab's requests on the server (SU-92; owner decisions D602, D605, D612): nothing done or answered for a
 * player who may not edit; REFRESH answers the settings; SAVE saves all three or answers why a link was refused, saving
 * nothing; the same again is "unchanged"; CLEAR empties all three; the packets carry what they hold. The tab's drawing,
 * boxes, previews, UNDO and SURE? need a client.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class SupportTabGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final String SHOP = "https://store.example.net/bsp";

    private static AdminSupportEditPacket save(String link, String sentence, String chat) {
        return new AdminSupportEditPacket(AdminSupportEditPacket.Action.SAVE, link, sentence, chat);
    }

    @GameTest(template = EMPTY)
    public void the_support_tab_saves_refuses_and_clears_for_admins_only(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        SupportSettings was = SupportService.settings(server);
        List<AdminSupportPacket> answers = new ArrayList<>();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        try {
            Storage.useForTests(null);
            SupportService.useForTests(System::currentTimeMillis, p -> {
            });
            SupportAdmin.useForTests((p, packet) -> answers.add(packet));
            SupportService.restoreForTests(server, SupportSettings.NONE);
            h.assertTrue(!SupportAdmin.apply(player, save(SHOP, "Hi", "")) && answers.isEmpty() && !SupportService.settings(server).hasLink(),
                    "a player who may not edit: nothing done, nothing answered (D602)");
            server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 2, false));
            h.assertTrue(SupportAdmin.apply(admin, AdminSupportEditPacket.ask()) && answers.size() == 1 && answers.get(0).note().isEmpty()
                    && answers.get(0).settings().equals(SupportSettings.NONE), "REFRESH answers the settings: " + answers);
            SupportAdmin.apply(admin, save(" " + SHOP, "Help keep BSP running: {link}", ""));
            AdminSupportPacket a = answers.get(answers.size() - 1);
            h.assertTrue(a.note().equals("saved") && a.settings().link().equals(SHOP) && a.settings().by().equals(admin.getGameProfile().getName()),
                    "SAVE saves all three and answers with who changed them (D612): " + a);
            SupportAdmin.apply(admin, save("http://store.example.net", "Other", ""));
            a = answers.get(answers.size() - 1);
            h.assertTrue(a.note().equals("bad_link") && a.problem().equals("HTTPS") && a.settings().link().equals(SHOP) && a.settings().sentence().equals("Help keep BSP running: {link}"),
                    "a refused support link: the reason, nothing saved: " + a);
            SupportAdmin.apply(admin, save(SHOP, "Other", "https://a b"));
            a = answers.get(answers.size() - 1);
            h.assertTrue(a.note().equals("bad_chat_link") && a.problem().equals("SPACES") && a.settings().sentence().equals("Help keep BSP running: {link}"),
                    "a refused chat link: the reason, nothing saved: " + a);
            SupportAdmin.apply(admin, save(SHOP, "Help keep BSP running: {link}", ""));
            h.assertTrue(answers.get(answers.size() - 1).note().equals("unchanged"), "the same again: unchanged");
            SupportAdmin.apply(admin, new AdminSupportEditPacket(AdminSupportEditPacket.Action.CLEAR, "ignored", "ignored", "ignored"));
            a = answers.get(answers.size() - 1);
            h.assertTrue(a.note().equals("cleared") && !a.settings().hasLink() && a.settings().sentence().isEmpty(), "CLEAR empties all three: " + a);
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            SupportAdmin.useForTests(null);
            SupportService.useReal();
            SupportService.restoreForTests(server, was);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_support_tab_packets_carry_what_they_hold(GameTestHelper h) {
        SupportSettings s = new SupportSettings(SHOP, "Help: {link} ✓", "https://other.example.net/x", "Kat", 1_800_000_000_000L, 42);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        new AdminSupportPacket(s, "bad_link", "HTTPS").encode(buf);
        AdminSupportPacket back = AdminSupportPacket.decode(buf);
        h.assertTrue(back.settings().equals(s) && back.note().equals("bad_link") && back.problem().equals("HTTPS"), "the tab's data: " + back);
        buf = new FriendlyByteBuf(Unpooled.buffer());
        save(SHOP, "x".repeat(300), "").encode(buf);
        AdminSupportEditPacket edit = AdminSupportEditPacket.decode(buf);
        h.assertTrue(edit.action() == AdminSupportEditPacket.Action.SAVE && edit.link().equals(SHOP) && edit.sentence().length() == 300,
                "SAVE carries the boxes as typed; the server cuts the sentence to 200");
        buf = new FriendlyByteBuf(Unpooled.buffer());
        save("https://" + "a".repeat(2000) + ".net", "", "").encode(buf);
        edit = AdminSupportEditPacket.decode(buf);
        h.assertTrue(edit.link().length() <= 1024, "an overlong box is cut before sending, so the packet never fails; the server then refuses it as too long");
        h.succeed();
    }
}
