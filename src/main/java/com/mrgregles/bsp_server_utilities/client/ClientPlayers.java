package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.compat.BspCore;
import com.mrgregles.bsp_server_utilities.network.PlayersViewPacket;
import com.mrgregles.bsp_server_utilities.players.PlayerCounter;
import com.mrgregles.bsp_server_utilities.players.PlayersSettings;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The client's copy of the player counter (owner decisions D168, D172, D175, D178), with the time it arrived so the
 * newest player's "ago" keeps counting, and the PLAYERS board on BSP-Core's Score Screens: the counter's title, then
 * "Have played BSP" with the number in gold, "New today", "New this week" and "Newest" with the name and how long ago.
 * The board's title is the one admins type, so the board is registered again when it changes; while the counter is
 * off (D173) it shows no lines.
 */
public final class ClientPlayers {
    private static final int GOLD = 0xFFD23A;
    private static volatile PlayersViewPacket view = PlayersViewPacket.NONE;
    private static long receivedAt;
    private static String boardTitle = "";

    private ClientPlayers() {
    }

    public static void accept(PlayersViewPacket p) {
        view = p;
        receivedAt = System.currentTimeMillis();
        if (!p.off() && !p.title().isEmpty() && !p.title().equals(boardTitle)) {
            registerBoard(p.title());
        }
    }

    public static PlayersViewPacket view() {
        return view;
    }

    /** How long ago the newest player joined, now. */
    public static long newestAgoMs() {
        PlayersViewPacket v = view;
        return v.newest().isEmpty() ? 0 : v.newestAgoMs() + (System.currentTimeMillis() - receivedAt);
    }

    /** The PLAYERS board under the given title (registering again replaces it in place). */
    static void registerBoard(String title) {
        boardTitle = title;
        BspCore.registerBoard("players", Component.literal(title), ClientPlayers::boardLines);
    }

    /** The board's lines: before the counter's first copy arrives, under its default title. */
    static void registerDefaultBoard() {
        if (boardTitle.isEmpty()) {
            registerBoard(PlayersSettings.DEFAULT_TITLE);
        }
    }

    static List<BspCore.Line> boardLines() {
        PlayersViewPacket v = view;
        if (v.off()) {
            return List.of();
        }
        String newest = v.newest().isEmpty() ? "-" : v.newest() + " · " + StaffFace.ago(newestAgoMs());
        return List.of(new BspCore.Line(Tr.t("players.board.total"), PlayerCounter.format(v.shown()), GOLD),
                new BspCore.Line(Tr.t("players.board.today"), PlayerCounter.format(v.today()), GOLD),
                new BspCore.Line(Tr.t("players.board.week"), PlayerCounter.format(v.week()), GOLD),
                new BspCore.Line(Tr.t("players.board.newest"), newest, GOLD));
    }
}
