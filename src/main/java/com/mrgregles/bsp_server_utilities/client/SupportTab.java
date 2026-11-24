package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.AdminSupportEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminSupportPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.support.SupportChat;
import com.mrgregles.bsp_server_utilities.support.SupportLinks;
import com.mrgregles.bsp_server_utilities.support.SupportSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The SUPPORT tab of the admin panel (owner decisions D605, D609, D612; look A of set AI,
 * {@code tools/preview/support_refined.html} R3): on the left the support link, the chat line's sentence and the chat
 * line's own link, each link checked as it is typed (a green line, or the reason in red); on the right the key as the
 * inventory shows it and the chat line as players read it, both following the boxes before SAVE, and who changed the
 * settings last. SAVE (dim while a link is refused or nothing changed) sends all three at once; the server checks again
 * and answers. UNDO puts the boxes back to what is saved; CLEAR, then SURE? within three seconds, empties all three.
 */
final class SupportTab {
    /** The settings column keeps its width on every page (D757); the preview beside it is at most {@link #RW_MAX} wide. */
    private static final int LX = 8, LW = 166, RX = 182, RW_MAX = 154, TOP = 22, KEY_H = 14;
    private static final int REFRESH_TICKS = 100;
    private static final long SURE_MS = 3000, NOTE_MS = 3000;
    private static final float TINY = 0.6f, CHAT = 0.75f;
    private static final int OK = 0xFF8FE04A, WARN_BG = 0xFF3A2A2A, WARN_EDGE = 0xFFD63B2F, WARN_TEXT = 0xFFFF8F86, AMBER = 0xFFF2B544;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private final AdminScreen host;
    @Nullable
    private AdminSupportPacket data;
    private SupportSettings saved = SupportSettings.NONE;
    private String fLink = "", fSentence = "", fChat = "";
    @Nullable
    private EditBox linkBox, chatBox;
    @Nullable
    private MultiLineEditBox sentenceBox;
    private boolean loading;
    private int refreshIn;
    private long sureUntil, noteUntil;
    private String note = "";
    private int noteColour = Ui.MUTED;
    /** The boxes as SAVE or CLEAR sent them, until the answer comes. */
    @Nullable
    private SupportSettings sent;

    SupportTab(AdminScreen host) {
        this.host = host;
    }

    /** The preview's width: its own 154, less on a page narrower than the old fixed panel's 344 (140 at 330). */
    private int rw() {
        return Math.min(RW_MAX, host.pageW() - RX - 8);
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        ServerUtilsNetwork.CHANNEL.sendToServer(AdminSupportEditPacket.ask());
    }

    void accept(AdminSupportPacket p) {
        boolean first = data == null;
        boolean wasDirty = dirty();
        data = p;
        saved = p.settings();
        // after SAVE or CLEAR the boxes take what was saved, unless they were typed in since (SU-93 audit)
        boolean answered = (p.note().equals("saved") || p.note().equals("cleared")) && sent != null && typed().sameAs(sent);
        if (first || !wasDirty || answered) {
            load(); // the first answer always fills the boxes, whatever was typed while waiting for it
        }
        if (!p.note().isEmpty()) {
            sent = null;
        }
        switch (p.note()) {
            case "saved" -> showNote(Tr.t("support.tab.saved"), OK);
            case "cleared" -> showNote(Tr.t("support.tab.cleared"), OK);
            case "unchanged" -> showNote(Tr.t("support.tab.unchanged"), Ui.MUTED);
            case "bad_link" -> showNote(Tr.t("support.tab.refused_link", reason(problem(p.problem()))), Ui.BAD);
            case "bad_chat_link" -> showNote(Tr.t("support.tab.refused_chat", reason(problem(p.problem()))), Ui.BAD);
            default -> {
            }
        }
    }

    private static SupportLinks.Problem problem(String name) {
        try {
            return SupportLinks.Problem.valueOf(name);
        } catch (IllegalArgumentException e) {
            return SupportLinks.Problem.NOT_ADDRESS;
        }
    }

    /** The line under a link box: "A link players can open." or why it is refused. */
    private static String reason(SupportLinks.Problem p) {
        return switch (p) {
            case OK -> Tr.t("support.link.ok");
            case TOO_LONG -> Tr.t("support.link.too_long");
            case SPACES -> Tr.t("support.link.spaces");
            case HTTPS -> Tr.t("support.link.https");
            case USER -> Tr.t("support.link.user");
            case NOT_ADDRESS -> Tr.t("support.link.not_address");
        };
    }

    private void showNote(String text, int colour) {
        note = text;
        noteColour = colour;
        noteUntil = System.currentTimeMillis() + NOTE_MS;
    }

    /** The boxes back to what is saved. */
    private void load() {
        fLink = saved.link();
        fSentence = saved.sentence();
        fChat = saved.chatLink();
        loading = true;
        // a box whose text already says the same once tidied keeps its text and cursor (a space typed before the next
        // word survives the 5-second refresh; audits of SU-93 and 0.15.0)
        if (linkBox != null) {
            if (SupportLinks.tidy(linkBox.getValue()).equals(fLink)) {
                fLink = linkBox.getValue();
            } else {
                linkBox.setValue(fLink);
            }
        }
        if (sentenceBox != null) {
            if (SupportSettings.cleanSentence(sentenceBox.getValue()).equals(fSentence)) {
                fSentence = sentenceBox.getValue();
            } else {
                sentenceBox.setValue(fSentence);
            }
        }
        if (chatBox != null) {
            if (SupportLinks.tidy(chatBox.getValue()).equals(fChat)) {
                fChat = chatBox.getValue();
            } else {
                chatBox.setValue(fChat);
            }
        }
        loading = false;
    }

    /** The boxes as they would be saved (the server tidies them the same way). */
    private SupportSettings typed() {
        return new SupportSettings(SupportLinks.tidy(fLink), SupportSettings.cleanSentence(fSentence), SupportLinks.tidy(fChat), "", 0, 0);
    }

    private boolean dirty() {
        return !typed().sameAs(saved);
    }

    private boolean canSave() {
        return data != null && dirty() && SupportLinks.check(fLink) == SupportLinks.Problem.OK && SupportLinks.check(fChat) == SupportLinks.Problem.OK;
    }

    void tick() {
        if (linkBox != null) {
            linkBox.tick();
        }
        if (chatBox != null) {
            chatBox.tick();
        }
        if (sentenceBox != null) {
            sentenceBox.tick();
        }
        if (--refreshIn <= 0) {
            open();
        }
    }

    void addWidgets(int x, int y) {
        Font font = host.font();
        loading = true;
        linkBox = new EditBox(font, x + LX + 1, y + 31, LW - 2, 12, Component.literal(Tr.t("support.tab.link")));
        linkBox.setMaxLength(SupportLinks.MAX);
        linkBox.setValue(fLink);
        linkBox.setResponder(s -> {
            if (!loading) {
                fLink = s;
            }
        });
        host.add(linkBox);
        sentenceBox = new MultiLineEditBox(font, x + LX, y + 63, LW, 42, Component.empty(), Component.literal(Tr.t("support.tab.sentence")));
        sentenceBox.setCharacterLimit(SupportSettings.MAX_SENTENCE);
        sentenceBox.setValue(fSentence);
        sentenceBox.setValueListener(s -> {
            if (!loading) {
                fSentence = s;
            }
        });
        host.add(sentenceBox);
        chatBox = new EditBox(font, x + LX + 1, y + 135, LW - 2, 12, Component.literal(Tr.t("support.tab.chat_link")));
        chatBox.setMaxLength(SupportLinks.MAX);
        chatBox.setValue(fChat);
        chatBox.setResponder(s -> {
            if (!loading) {
                fChat = s;
            }
        });
        host.add(chatBox);
        loading = false;
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        // the boxes' labels and lines
        int lx = x + LX;
        label(g, lx, y + 24, Tr.t("support.tab.link"));
        checkLine(g, lx, y + 46, fLink, false);
        label(g, lx, y + 56, Tr.t("support.tab.sentence"));
        String count = Tr.t("support.tab.count", SupportSettings.cleanSentence(fSentence).length(), SupportSettings.MAX_SENTENCE);
        Ui.text(g, font, count, lx + LW - Ui.width(font, count, TINY), y + 56, fSentence.length() >= SupportSettings.MAX_SENTENCE ? Ui.GOLD : Ui.MUTED, TINY);
        int hy = y + 108;
        for (String line : Ui.wrap(font, Tr.t("support.tab.sentence_hint"), LW, TINY).stream().limit(3).toList()) {
            Ui.text(g, font, line, lx, hy, Ui.MUTED, TINY);
            hy += 6;
        }
        label(g, lx, y + 128, Tr.t("support.tab.chat_link"));
        checkLine(g, lx, y + 150, fChat, true);
        drawPreview(g, x, y);
        drawFooter(g, x, y, mx, my, hits);
    }

    private void label(GuiGraphics g, int x, int y, String s) {
        Font font = host.font();
        Ui.text(g, font, Ui.fit(font, s.toUpperCase(Locale.ROOT), LW - 30, TINY), x, y, Ui.MUTED, TINY);
    }

    private void checkLine(GuiGraphics g, int x, int y, String typed, boolean chat) {
        Font font = host.font();
        SupportLinks.Problem p = SupportLinks.check(typed);
        String s;
        int colour;
        if (SupportLinks.tidy(typed).isEmpty()) {
            s = Tr.t(chat ? "support.tab.chat_link_empty" : "support.tab.link_empty");
            colour = Ui.MUTED;
        } else {
            s = reason(p);
            colour = p == SupportLinks.Problem.OK ? OK : Ui.BAD;
        }
        Ui.text(g, font, Ui.fit(font, s, LW, TINY), x, y, colour, TINY);
    }

    private void drawPreview(GuiGraphics g, int x, int y) {
        Font font = host.font();
        int px = x + RX, rw = rw();
        Ui.text(g, font, Ui.fit(font, Tr.t("support.tab.preview").toUpperCase(Locale.ROOT), rw, TINY), px, y + 24, Ui.MUTED, TINY);
        SupportSettings t = typed().sanitised();
        // the key, as the inventory shows it
        Ui.inset(g, px, y + 31, rw, 30);
        Ui.text(g, font, Ui.fit(font, Tr.t("support.tab.in_inventory"), rw - 8, TINY), px + 4, y + 35, Ui.MUTED, TINY);
        if (t.hasLink()) {
            // TEAM and KITS share what the support key leaves (40 each in the full 154)
            int kw = Math.min(40, (rw - 12 - SupportButton.W) / 2);
            SupportButton.drawPlainKey(g, px + 4, y + 43, kw, Tr.t("support.tab.team"));
            SupportButton.drawPlainKey(g, px + 6 + kw, y + 43, kw, Tr.t("support.tab.kits"));
            SupportButton.drawKey(g, px + 8 + 2 * kw, y + 43, false);
        } else {
            Ui.text(g, font, Ui.fit(font, Tr.t("support.tab.no_key"), rw - 8, TINY), px + 4, y + 47, Ui.MUTED, TINY);
        }
        // the chat line, as players read it
        int cy = y + 65, ch = 74;
        Ui.inset(g, px, cy, rw, ch);
        Ui.text(g, font, Ui.fit(font, Tr.t("support.tab.in_chat"), rw - 8, TINY), px + 4, cy + 4, Ui.MUTED, TINY);
        Component line = SupportChat.line(t);
        if (line == null) {
            Ui.text(g, font, Ui.fit(font, Tr.t("support.tab.no_chat"), rw - 8, TINY), px + 4, cy + 14, Ui.MUTED, TINY);
        } else {
            List<FormattedCharSequence> lines = font.split(line, (int) ((rw - 12) / CHAT));
            int shown = Math.min(lines.size(), 7);
            g.fill(px + 3, cy + 12, px + rw - 3, cy + 14 + shown * 8, 0x80000000);
            g.pose().pushPose();
            g.pose().translate(px + 5, cy + 14, 0);
            g.pose().scale(CHAT, CHAT, 1);
            for (int i = 0; i < shown; i++) {
                g.drawString(font, lines.get(i), 0, Math.round(i * 8 / CHAT), 0xFFFFFF, false);
            }
            g.pose().popPose();
        }
        int ly = cy + ch + 4;
        if (dirty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("support.tab.unsaved"), rw, TINY), px, ly, AMBER, TINY);
            ly += 8;
        }
        String last = saved.by().isEmpty() ? Tr.t("support.tab.never")
                : Tr.t("support.tab.last", saved.by(), WHEN.format(Instant.ofEpochMilli(saved.atMs()).atZone(ZoneId.systemDefault())));
        Ui.text(g, font, Ui.fit(font, last, rw, TINY), px, ly, Ui.MUTED, TINY);
    }

    private void drawFooter(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int fy = y + host.pageH() - 8 - KEY_H;
        boolean sure = System.currentTimeMillis() < sureUntil;
        String save = Tr.t("support.tab.save"), undo = Tr.t("support.tab.undo"), clear = Tr.t(sure ? "support.tab.sure" : "support.tab.clear");
        int ws = Ui.widthBold(font, save, TINY) + 16, wu = Ui.widthBold(font, undo, TINY) + 16, wc = Ui.widthBold(font, clear, TINY) + 16;
        int kx = x + host.pageW() - 8 - ws;
        boolean canSave = canSave(), canUndo = dirty();
        boolean anything = !saved.link().isEmpty() || !saved.sentence().isEmpty() || !saved.chatLink().isEmpty();
        // SAVE
        g.fill(kx, fy, kx + ws, fy + KEY_H, canSave ? Ui.VIOLET : 0xFF3D2F5C);
        Ui.text(g, font, Component.literal(save).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, canSave ? Ui.INK : 0xFF7A6A99, TINY);
        if (canSave) {
            hits.add(new Ui.Hit(kx, fy, ws, KEY_H, this::save));
        }
        // UNDO
        kx -= wu + 4;
        Ui.frame(g, kx, fy, wu, KEY_H, Ui.DIM);
        Ui.text(g, font, Component.literal(undo).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, canUndo ? Ui.TEXT : 0xFF5A6170, TINY);
        if (canUndo) {
            hits.add(new Ui.Hit(kx, fy, wu, KEY_H, this::load));
        }
        // CLEAR, then SURE?
        kx -= wc + 4;
        g.fill(kx, fy, kx + wc, fy + KEY_H, anything ? WARN_BG : Ui.DIM);
        Ui.frame(g, kx, fy, wc, KEY_H, anything ? WARN_EDGE : Ui.DIM);
        Ui.text(g, font, Component.literal(clear).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, anything ? WARN_TEXT : 0xFF5A6170, TINY);
        if (anything && data != null) {
            hits.add(new Ui.Hit(kx, fy, wc, KEY_H, this::clear));
        }
        // the line on the left: SURE?'s warning, the last answer, or where the settings apply
        String left;
        int colour;
        if (sure) {
            left = Tr.t("support.tab.sure_text");
            colour = Ui.BAD;
        } else if (System.currentTimeMillis() < noteUntil) {
            left = note;
            colour = noteColour;
        } else {
            left = Tr.t(data == null ? "support.tab.waiting" : "support.tab.shared");
            colour = Ui.MUTED;
        }
        int room = kx - x - 16;
        List<String> lines = Ui.wrap(font, left, room, TINY);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            Ui.text(g, font, lines.get(i), x + 8, fy + (lines.size() == 1 ? 5 : 2 + i * 6), colour, TINY);
        }
    }

    private void save() {
        sureUntil = 0;
        sent = typed();
        ServerUtilsNetwork.CHANNEL.sendToServer(new AdminSupportEditPacket(AdminSupportEditPacket.Action.SAVE, fLink, fSentence, fChat));
    }

    private void clear() {
        if (System.currentTimeMillis() < sureUntil) {
            sureUntil = 0;
            sent = typed();
            ServerUtilsNetwork.CHANNEL.sendToServer(new AdminSupportEditPacket(AdminSupportEditPacket.Action.CLEAR, "", "", ""));
        } else {
            sureUntil = System.currentTimeMillis() + SURE_MS;
        }
    }
}
