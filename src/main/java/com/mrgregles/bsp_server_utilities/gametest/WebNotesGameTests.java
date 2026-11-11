package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.network.NotesCountPacket;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import com.mrgregles.bsp_server_utilities.web.WebNotes;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The staff web panel's notes counts in game (SU-38, D266, D321, D332): a signed notice from the panel is taken and its
 * counts kept; unsigned, old, repeated ones, or any while the panel is off, are refused; odd entries are left out; the
 * counts go only to staff who can open the admin panel; the packet carries them whole (protocol 6).
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebNotesGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];
    private static final UUID VEXA = UUID.fromString("5f0c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b"), PIXL = UUID.fromString("6a1b2c3d-4e5f-4a6b-9c7d-8e9f0a1b2c3d");

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i * 3 + 1);
        }
    }

    /** As the panel's Actions.notice signs it. */
    private static String notice(byte[] key, long at, String counts) {
        return WebActions.sign(key, "{\"type\":\"web_notes\",\"v\":1,\"counts\":" + counts + ",\"at\":" + at + "}");
    }

    @GameTest(template = EMPTY)
    public void a_signed_notice_is_kept_and_odd_ones_are_refused(GameTestHelper h) {
        long now = 1_800_000_000_000L;
        long[] clock = {now};
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        WebNotes.useForTests(() -> clock[0], (p, packet) -> { });
        try {
            String counts = "{\"" + VEXA + "\":{\"n\":2,\"by\":\"Kip\",\"at\":" + (now - 3_600_000L) + ",\"name\":\"Vexa\"},\"not-a-uuid\":{\"n\":1},\"" + PIXL + "\":{\"n\":0,\"by\":\"Ana\"}}";
            h.assertTrue(WebNotes.accept(notice(KEY, now, counts)), "a notice signed with the action key is taken");
            WebNotes.Count v = WebNotes.of(VEXA);
            h.assertTrue(v != null && v.n() == 2 && v.by().equals("Kip") && v.at() == now - 3_600_000L && v.name().equals("Vexa"), "Vexa: 2 notes, the newest by Kip an hour ago: " + v);
            h.assertTrue(WebNotes.of(PIXL) == null && WebNotes.packet().counts().size() == 1, "an odd id and a count of 0 are left out");

            byte[] other = KEY.clone();
            other[0] ^= 1;
            h.assertFalse(WebNotes.accept(notice(other, now + 1, "{}")), "another key: refused");
            h.assertFalse(WebNotes.accept(notice(KEY, now, "{}")), "the same notice again: refused (only newer ones)");
            h.assertTrue(WebNotes.of(VEXA) != null, "and the counts kept meanwhile");
            h.assertFalse(WebNotes.accept(WebActions.sign(KEY, "{\"v\":1,\"counts\":{},\"at\":" + (now + 1) + ",\"server\":\"x\",\"action\":\"kick\"}")),
                    "an action signed with the same key is not a notes notice");
            WebAgent.useForTests(false, null, null);
            h.assertFalse(WebNotes.accept(notice(KEY, now + 2, "{}")), "the panel off on this server: refused");
            WebAgent.useForTests(true, null, null);
            clock[0] = now + 30_000L;
            h.assertTrue(WebNotes.accept(notice(KEY, now + 30_000L, "{}")) && WebNotes.of(VEXA) == null, "a newer notice replaces the counts: her notes were deleted");
            WebNotes.useReal(); // a fresh start: only the age decides below
            WebNotes.useForTests(() -> clock[0], (p, packet) -> { });
            h.assertFalse(WebNotes.accept(notice(KEY, clock[0] - WebNotes.MAX_AGE_MS - 1, "{}")), "over two minutes old: refused");
            h.assertFalse(WebNotes.accept(notice(KEY, clock[0] + WebNotes.MAX_AGE_MS + 1, "{}")), "over two minutes ahead: refused");
            h.assertTrue(WebNotes.accept(notice(KEY, clock[0], "{\"" + VEXA + "\":{\"n\":1,\"by\":\"Kip\",\"at\":1}}")) && WebNotes.of(VEXA) != null, "a fresh one is taken");
            clock[0] += WebNotes.STALE_MS + 1;
            h.assertTrue(WebNotes.of(VEXA) == null && WebNotes.packet().counts().isEmpty(), "no notice for over two minutes: the counts are not shown any more");
            clock[0] -= WebNotes.STALE_MS + 1;
            WebAgent.useForTests(false, null, null);
            h.assertTrue(WebNotes.of(VEXA) == null, "nor while the panel is off here");
        } finally {
            WebNotes.useReal();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void counts_go_only_to_staff_who_can_open_the_admin_panel(GameTestHelper h) {
        ServerPlayer staff = TestPlayers.mockServerPlayer(h), player = TestPlayers.mockServerPlayer(h);
        PlayerList list = h.getLevel().getServer().getPlayerList();
        List<ServerPlayer> got = new ArrayList<>();
        long now = System.currentTimeMillis();
        WebActions.useForTests(KEY, null, null);
        WebAgent.useForTests(true, null, null);
        WebNotes.useForTests(null, (p, packet) -> got.add(p));
        try {
            WebNotes.accept(notice(KEY, now, "{\"" + VEXA + "\":{\"n\":1,\"by\":\"Kip\",\"at\":" + now + ",\"name\":\"Vexa\"}}"));
            list.getOps().add(new ServerOpListEntry(staff.getGameProfile(), 4, false));
            WebNotes.sendTo(staff);
            WebNotes.sendTo(player);
            h.assertTrue(got.size() == 1 && got.get(0) == staff, "the staff member gets the counts at login, the player does not");
        } finally {
            list.getOps().remove(staff.getGameProfile());
            WebNotes.useReal();
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_packet_carries_the_counts_whole(GameTestHelper h) {
        NotesCountPacket p = new NotesCountPacket(List.of(new WebNotes.Count(VEXA, "Vexa", 3, "Rook", 1_800_000_000_000L), new WebNotes.Count(PIXL, "", 1, "Ana_Mod", 5L)));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        p.encode(buf);
        NotesCountPacket back = NotesCountPacket.decode(buf);
        h.assertTrue(back.counts().equals(p.counts()) && buf.readableBytes() == 0, "the same counts after the trip: " + back);
        h.succeed();
    }
}
