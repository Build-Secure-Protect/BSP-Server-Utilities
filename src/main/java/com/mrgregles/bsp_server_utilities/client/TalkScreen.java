package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TalkActionPacket;
import com.mrgregles.bsp_server_utilities.network.TalkViewPacket;
import com.mrgregles.bsp_server_utilities.talk.SeenWords;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The private window (owner decisions D648, D652 R1 and R2; {@code tools/preview/talk_refined.html}), a 200 GUI px card
 * with a violet frame, as tall as the screen allows up to 167:
 * <ul>
 * <li>The player's own: "STAFF MESSAGES", staff lines on the left in violet ("NAME · STAFF · 14:09"), their own on the
 * right in teal ("YOU · 14:11"), the box and SEND, "Only staff read this conversation...". When staff end it, "Staff ended
 * the conversation" and the window closes by itself after 5 seconds.</li>
 * <li>Staff's, for a player: the name and where they are, staff lines on the right with their writers, the player's on the
 * left, earlier conversations of the last 90 days dimmed above "Ended by ..." dividers, "Open since 14:02 · who · not seen
 * yet" and END CONVERSATION, which turns into a red SURE? END for 3 seconds. For a player online nowhere a second head line
 * says where they were last seen (SU-121; D672 to D683, {@code tools/preview/lastseen_refined.html}): a grey OFFLINE chip,
 * "last seen 3 h ago (21:04) on BSP-Main · nether", a red "· no logout recorded" that is always kept, the place and block
 * on hover; the conversation is 8 GUI px shorter then.</li>
 * </ul>
 * Newest at the foot, older up the wheel; lines arrive live from the server ({@link ClientTalk}). The box takes 256
 * characters with a count from 200; Enter or SEND sends; a refused line shows its reason in red under the box. Keys are
 * drawn by hand and act on release; key presses stay inside ({@link ScreenKeys}); Esc or CLOSE goes back.
 */
public class TalkScreen extends Screen {
    static final int W = 200, MAX_H = 167, MIN_H = 120, PAD = 8, KEY_H = 12, BOX_H = 16, SEND_W = 36, MAX_TEXT = 256, COUNT_FROM = 200, SEEN_H = 8;
    private static final float SMALL = 0.75f;
    private static final long ARM_MS = 3_000L, CLOSE_AFTER_MS = 5_000L;
    private static final int CHIP_INK = 0xFFC9CED8;
    private static final int STAFF_BG = 0xFF1D1830, PLAYER_BG = 0xFF12261F, OLD_BG = 0xFF15171D, OLD_INK = 0xFF6B7180, RED = 0xFFFF5A4F, ARMED = 0xFFD63B2F;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT), DAY = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT);

    @Nullable
    private final Screen parent;
    private final List<Ui.Hit> hits = new ArrayList<>();
    @Nullable
    private Ui.Hit pressed;
    private EditBox box;
    @Nullable
    private TalkViewPacket shownView;
    private int scroll, maxScroll;
    private long armedAt = -1, endedSeenAt = -1;

    public TalkScreen(@Nullable Screen parent) {
        super(Component.literal(Tr.t("talk.window.title")));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** Leaving the window however it goes: the server stops sending its lines. */
    @Override
    public void removed() {
        ClientTalk.closeView();
    }

    private int h() {
        return Math.max(Math.min(MIN_H, height - 4), Math.min(MAX_H, height - 8));
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - h()) / 2;
    }

    private int boxY() {
        return top() + h() - PAD - KEY_H - 4 - BOX_H;
    }

    @Override
    protected void init() {
        String typed = box == null ? "" : box.getValue();
        box = new EditBox(font, left() + PAD + 4, boxY() + 4, W - 2 * PAD - SEND_W - 4 - 30, 10, Component.literal(Tr.t("talk.window.box")));
        box.setBordered(false);
        box.setMaxLength(MAX_TEXT);
        box.setTextColor(Ui.TEXT);
        box.setValue(typed);
        addRenderableWidget(box);
        setInitialFocus(box);
    }

    // ------------------------------------------------------------------ the conversation as the window reads it

    /** The open conversation's id: the newest line's when it is not an end; -1 when none is open. */
    private static long openTalk(List<TalkViewPacket.Entry> lines) {
        if (lines.isEmpty()) {
            return -1;
        }
        TalkViewPacket.Entry last = lines.get(lines.size() - 1);
        return last.kind() == TalkViewPacket.END ? -1 : last.talk();
    }

    private static String when(long at) {
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        return (t.toLocalDate().equals(LocalDate.now()) ? HM : DAY).format(t);
    }

    /** One thing in the thread: a bubble (its side, colours and wrapped words) or a divider. */
    private record Item(boolean divider, boolean right, int bg, int bar, int ink, int whoInk, String who, List<String> words, int w, int h) {
    }

    private List<Item> items(Font f, TalkViewPacket v, List<TalkViewPacket.Entry> lines) {
        long open = openTalk(lines);
        int inner = W - 2 * PAD, maxW = (int) (inner * 0.78f);
        List<Item> out = new ArrayList<>();
        for (TalkViewPacket.Entry e : lines) {
            if (e.kind() == TalkViewPacket.END) {
                String text = !v.staff() ? Tr.t("talk.window.ended_player")
                        : e.staff().isEmpty() ? Tr.t("talk.window.ended_itself", when(e.at())) : Tr.t("talk.window.ended_by", e.staff(), when(e.at()));
                out.add(new Item(true, false, 0, 0, Ui.MUTED, 0, Ui.fit(f, text, inner - 30, SMALL), List.of(), inner, 10));
                continue;
            }
            boolean staffLine = e.kind() == TalkViewPacket.STAFF;
            boolean old = v.staff() && e.talk() != open;
            boolean right = v.staff() == staffLine; // the player: staff left, own right; staff: staff right, the player left
            String who = !v.staff()
                    ? (staffLine ? Tr.t("talk.window.who_staff", e.staff(), when(e.at())) : Tr.t("talk.window.who_you", when(e.at())))
                    : Tr.t("talk.window.who", staffLine ? e.staff() : v.name(), when(e.at()));
            who = who.toUpperCase(Locale.ROOT);
            List<String> words = Ui.wrap(f, e.text(), maxW - 10, SMALL);
            int textW = Ui.width(f, who, 0.6f);
            for (String w : words) {
                textW = Math.max(textW, Ui.width(f, w, SMALL));
            }
            int bw = Math.min(maxW, textW + 10), bh = 3 + 6 + Math.max(1, words.size()) * 8 + 2;
            int bg = old ? OLD_BG : staffLine ? STAFF_BG : PLAYER_BG, bar = old ? Ui.DIM : staffLine ? Ui.VIOLET : Ui.TEAL;
            out.add(new Item(false, right, bg, bar, old ? OLD_INK : Ui.TEXT, old ? OLD_INK : bar, Ui.fit(f, who, maxW - 10, 0.6f), words, bw, bh));
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        TalkViewPacket v = ClientTalk.view();
        if (v == null) {
            onClose();
            return;
        }
        if (v != shownView) {
            shownView = v;
            scroll = 0;
            armedAt = -1;
            endedSeenAt = -1;
        }
        renderBackground(g);
        hits.clear();
        Font f = font;
        List<TalkViewPacket.Entry> lines = ClientTalk.lines();
        int x = left(), y = top(), hh = h();
        g.fill(x, y, x + W, y + hh, Ui.PANEL);
        Ui.frame(g, x, y, W, hh, Ui.VIOLET);
        long now = Util.getMillis();
        boolean open = openTalk(lines) >= 0;
        boolean playerEnded = !v.staff() && (!lines.isEmpty() && !open || !ClientTalk.open());
        if (playerEnded && endedSeenAt < 0) {
            endedSeenAt = now;
        }
        if (playerEnded && now - endedSeenAt >= CLOSE_AFTER_MS) {
            onClose();
            return;
        }
        box.setEditable(!playerEnded);

        // the head: the title (the player's name for staff), where they are, CLOSE
        String close = Tr.t("talk.window.close");
        int cw = Ui.width(f, close, SMALL) + 12, cx = x + W - PAD - cw;
        String title = v.staff() ? v.name() : Tr.t("talk.window.title");
        boolean seenLine = seenLine(v);
        String sub = !v.staff() || seenLine ? "" : v.where().isEmpty() ? Tr.t("talk.window.offline") : Tr.t("talk.window.online", v.where());
        int titleRoom = sub.isEmpty() ? cx - (x + PAD) - 4 : W - 2 * PAD - cw - 50; // the whole width to CLOSE when nothing sits beside it (audit of SU-121)
        Component t = Component.literal(Ui.fit(f, title.toUpperCase(Locale.ROOT), titleRoom, 1f)).withStyle(ChatFormatting.BOLD);
        Ui.text(g, f, t, x + PAD, y + PAD, Ui.VIOLET, 1f);
        if (!sub.isEmpty()) {
            int sx = x + PAD + f.width(t) + 6;
            Ui.text(g, f, Ui.fit(f, sub, cx - sx - 4, 0.6f), sx, y + PAD + 2, Ui.MUTED, 0.6f);
        }
        ghostKey(g, f, cx, y + PAD - 2, cw, close, mouseX, mouseY, this::onClose);
        List<FormattedCharSequence> tip = seenLine ? drawSeen(g, f, v.last(), x + PAD, y + PAD + 12, W - 2 * PAD, mouseX, mouseY) : List.of();

        // the thread, newest at the foot
        int areaTop = y + PAD + 14 + (seenLine ? SEEN_H : 0), areaBottom = boxY() - 4, areaH = areaBottom - areaTop;
        List<Item> items = items(f, v, lines);
        int total = 0;
        for (Item it : items) {
            total += it.h() + 4;
        }
        maxScroll = Math.max(0, total - areaH);
        scroll = Math.min(scroll, maxScroll);
        Ui.scissor(g, x + 1, areaTop, x + W - 1, areaBottom);
        int cy = areaBottom - total + scroll;
        int inner = W - 2 * PAD;
        for (Item it : items) {
            if (cy + it.h() >= areaTop && cy <= areaBottom) {
                if (it.divider()) {
                    int tw = Ui.width(f, it.who(), SMALL), mid = x + PAD + inner / 2;
                    g.fill(x + PAD, cy + 4, mid - tw / 2 - 4, cy + 5, Ui.DIM);
                    g.fill(mid + tw / 2 + 4, cy + 4, x + PAD + inner, cy + 5, Ui.DIM);
                    Ui.text(g, f, it.who(), mid - tw / 2, cy + 1, Ui.MUTED, SMALL);
                } else {
                    int bx = it.right() ? x + PAD + inner - it.w() : x + PAD;
                    g.fill(bx, cy, bx + it.w(), cy + it.h(), it.bg());
                    if (it.right()) {
                        g.fill(bx + it.w() - 2, cy, bx + it.w(), cy + it.h(), it.bar());
                    } else {
                        g.fill(bx, cy, bx + 2, cy + it.h(), it.bar());
                    }
                    int wx = it.right() ? bx + it.w() - 5 - Ui.width(f, it.who(), 0.6f) : bx + 5;
                    Ui.text(g, f, it.who(), wx, cy + 3, it.whoInk(), 0.6f);
                    int ly = cy + 10;
                    for (String w : it.words()) {
                        int lx = it.right() ? bx + it.w() - 5 - Ui.width(f, w, SMALL) : bx + 5;
                        Ui.text(g, f, w, lx, ly, it.ink(), SMALL);
                        ly += 8;
                    }
                }
            }
            cy += it.h() + 4;
        }
        if (items.isEmpty() && v.staff()) {
            List<String> hint = Ui.wrap(f, Tr.t("talk.window.none_yet"), inner - 20, SMALL);
            int hy = areaBottom - hint.size() * 8 - 6;
            for (String l : hint) {
                Ui.text(g, f, l, x + (W - Ui.width(f, l, SMALL)) / 2, hy, Ui.MUTED, SMALL);
                hy += 8;
            }
        }
        g.disableScissor();

        // the box, its count and SEND
        int by = boxY();
        Ui.inset(g, x + PAD, by, W - 2 * PAD - SEND_W - 4, BOX_H);
        if (box.isFocused() && !playerEnded) {
            Ui.frame(g, x + PAD, by, W - 2 * PAD - SEND_W - 4, BOX_H, Ui.TEAL);
        }
        if (box.getValue().isEmpty() && !box.isFocused() || playerEnded) {
            String ph = playerEnded ? Tr.t("talk.window.box_ended") : v.staff() ? Tr.t("talk.window.box_staff", v.name()) : Tr.t("talk.window.box");
            Ui.text(g, f, Ui.fit(f, ph, W - 2 * PAD - SEND_W - 40, SMALL), x + PAD + 4, by + 5, OLD_INK, SMALL);
        }
        int len = box.getValue().length();
        if (len >= COUNT_FROM) {
            String c = len + "/" + MAX_TEXT;
            Ui.text(g, f, c, x + W - PAD - SEND_W - 8 - Ui.width(f, c, 0.6f), by + 6, len >= MAX_TEXT ? RED : OLD_INK, 0.6f);
        }
        int sx = x + W - PAD - SEND_W;
        boolean hotSend = !playerEnded && mouseX >= sx && mouseX < sx + SEND_W && mouseY >= by && mouseY < by + BOX_H;
        g.fill(sx, by, sx + SEND_W, by + BOX_H, playerEnded ? Ui.DIM : hotSend ? 0xFFCBAEFF : Ui.VIOLET);
        label(g, f, Tr.t("talk.window.send"), sx, by + 2, SEND_W, BOX_H, playerEnded ? Ui.MUTED : Ui.INK);
        if (!playerEnded) {
            hits.add(new Ui.Hit(sx, by, SEND_W, BOX_H, this::send));
        }
        super.render(g, mouseX, mouseY, partial); // the box's own text and cursor

        // the foot: a refusal in red, else the window's line; staff get END CONVERSATION
        int fy = y + hh - PAD - KEY_H;
        String refused = ClientTalk.refused();
        boolean armed = armedAt >= 0 && now - armedAt < ARM_MS;
        String foot;
        int footInk = Ui.MUTED;
        if (!refused.isEmpty()) {
            foot = refusedWords(refused);
            footInk = RED;
        } else if (playerEnded) {
            foot = Tr.t("talk.window.closing", Math.max(1, (CLOSE_AFTER_MS - (now - endedSeenAt) + 999) / 1000));
        } else if (!v.staff()) {
            foot = Tr.t("talk.window.private");
        } else if (armed) {
            foot = Tr.t("talk.window.end_warning");
            footInk = RED;
        } else {
            foot = staffFoot(v, lines);
        }
        int footW = W - 2 * PAD;
        if (v.staff() && open) {
            String end = armed ? Tr.t("talk.window.sure_end") : Tr.t("talk.window.end");
            int ew = Ui.width(f, end, SMALL) + 12, ex = x + W - PAD - ew;
            boolean hot = mouseX >= ex && mouseX < ex + ew && mouseY >= fy && mouseY < fy + KEY_H;
            if (armed) {
                g.fill(ex, fy, ex + ew, fy + KEY_H, ARMED);
                label(g, f, end, ex, fy, ew, KEY_H, 0xFFFFFFFF);
            } else {
                g.fill(ex, fy, ex + ew, fy + KEY_H, 0xFF3A2A2A);
                Ui.frame(g, ex, fy, ew, KEY_H, hot ? RED : ARMED);
                label(g, f, end, ex, fy, ew, KEY_H, 0xFFFF8F86);
            }
            hits.add(new Ui.Hit(ex, fy, ew, KEY_H, this::end));
            footW -= ew + 4;
        }
        List<String> fl = Ui.wrap(f, foot, footW, 0.6f);
        int ly = fl.size() > 1 ? fy : fy + 3;
        for (int i = 0; i < Math.min(2, fl.size()); i++) {
            Ui.text(g, f, fl.get(i), x + PAD, ly, footInk, 0.6f);
            ly += 6;
        }
        if (!tip.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            g.renderTooltip(f, tip, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    // ------------------------------------------------------------------ last seen (SU-121)

    /** The second head line shows for staff when the player is online nowhere and their place is known or known to be missing. */
    private static boolean seenLine(TalkViewPacket v) {
        return v.staff() && v.where().isEmpty() && v.last() != null && v.last().state() != TalkViewPacket.Place.UNKNOWN;
    }

    /** "3 h ago (21:04)", "yesterday (22:41)", "4 d ago", "18 Oct". */
    private static String whenWords(SeenWords.When w) {
        return switch (w.step()) {
            case MINUTES -> Tr.t("talk.window.seen.min", w.n(), w.clock());
            case HOURS -> Tr.t("talk.window.seen.hours", w.n(), w.clock());
            case YESTERDAY -> Tr.t("talk.window.seen.yesterday", w.clock());
            case DAYS -> Tr.t("talk.window.seen.days", w.n());
            case DATE -> w.date();
        };
    }

    /** The hover's "3 h ago", "yesterday", "4 d ago", "18 Oct". */
    private static String agoWords(SeenWords.When w) {
        return switch (w.step()) {
            case MINUTES -> Tr.t("talk.window.seen.ago_min", w.n());
            case HOURS -> Tr.t("talk.window.seen.ago_hours", w.n());
            case YESTERDAY -> Tr.t("talk.window.seen.ago_yesterday");
            case DAYS -> Tr.t("talk.window.seen.days", w.n());
            case DATE -> w.date();
        };
    }

    /** "BSP-Main · nether"; the Overworld named only when {@code always} (the hover). */
    private static String placeWords(TalkViewPacket.Place p, boolean always) {
        String world = switch (SeenWords.world(p.dimension())) {
            case OVERWORLD -> always ? Tr.t("talk.window.seen.overworld") : "";
            case NETHER -> Tr.t("talk.window.seen.nether");
            case END -> Tr.t("talk.window.seen.end");
            case OTHER -> SeenWords.otherName(p.dimension());
        };
        return world.isEmpty() ? p.server() : p.server() + " \u00b7 " + world;
    }

    /** Draws the chip and the line at {@code (lx, ly)} within {@code lw}; the hover's lines when the pointer is on it. */
    private List<FormattedCharSequence> drawSeen(GuiGraphics g, Font f, TalkViewPacket.Place p, int lx, int ly, int lw, int mx, int my) {
        Component chip = Component.literal(Tr.t("talk.window.seen.chip")).withStyle(ChatFormatting.BOLD);
        int chipW = (int) Math.ceil(f.width(chip) * 0.6f) + 6;
        g.fill(lx, ly, lx + chipW, ly + 7, Ui.DIM);
        Ui.text(g, f, chip, lx + 3, ly + 1, CHIP_INK, 0.6f);
        int tx = lx + chipW + 4, room = lx + lw - tx;
        List<Component> hover = new ArrayList<>();
        if (p.found()) {
            long seenAt = ClientTalk.viewAt() - p.ageMs(); // counts on while the window stays open
            SeenWords.When w = SeenWords.when(seenAt, System.currentTimeMillis(), ZoneId.systemDefault());
            String tail = p.logout() ? "" : Tr.t("talk.window.seen.no_logout");
            int tailW = tail.isEmpty() ? 0 : Ui.width(f, tail, 0.6f);
            String grey = Ui.fit(f, Tr.t("talk.window.seen.line", whenWords(w), placeWords(p, false)), room - (tailW > 0 ? tailW + 3 : 0), 0.6f);
            Ui.text(g, f, grey, tx, ly + 1, Ui.MUTED, 0.6f);
            if (!tail.isEmpty()) {
                Ui.text(g, f, tail, tx + Ui.width(f, grey, 0.6f) + 3, ly + 1, RED, 0.6f);
            }
            hover.add(Component.literal(w.step() == SeenWords.Step.DATE ? Tr.t("talk.window.seen.hover_on", w.full()) // never the date twice (audit)
                    : Tr.t("talk.window.seen.hover_when", agoWords(w), w.full())));
            String place = placeWords(p, true);
            hover.add(Component.literal(p.logout() ? Tr.t("talk.window.seen.hover_logout", place) : Tr.t("talk.window.seen.hover_record", place)).withStyle(ChatFormatting.GRAY));
            hover.add(Component.literal(Tr.t("talk.window.seen.hover_block", p.x(), p.y(), p.z())).withStyle(ChatFormatting.GRAY));
            if (!p.logout()) {
                hover.add(Component.literal(Tr.t("talk.window.seen.hover_crash")).withStyle(s -> s.withColor(RED & 0xFFFFFF)));
            }
        } else {
            Ui.text(g, f, Ui.fit(f, Tr.t("talk.window.seen.none"), room, 0.6f), tx, ly + 1, Ui.MUTED, 0.6f);
            hover.add(Component.literal(Tr.t("talk.window.seen.none_hover")).withStyle(ChatFormatting.GRAY));
        }
        if (mx < lx || mx >= lx + lw || my < ly - 1 || my >= ly + SEEN_H) {
            return List.of();
        }
        List<FormattedCharSequence> out = new ArrayList<>();
        hover.forEach(c -> out.addAll(f.split(c, 300))); // the no-logout sentence in two lines, as drawn (audit)
        return out;
    }

    /** Why a line or an end was refused, in words (each key written out, so the lang check sees it). */
    static String refusedWords(String outcome) {
        return switch (outcome) {
            case "NOT_STAFF" -> Tr.t("talk.refused.not_staff");
            case "TO_STAFF" -> Tr.t("talk.refused.to_staff");
            case "EMPTY" -> Tr.t("talk.refused.empty");
            case "TOO_LONG" -> Tr.t("talk.refused.too_long");
            case "TOO_FAST" -> Tr.t("talk.refused.too_fast");
            case "NO_TALK" -> Tr.t("talk.refused.no_talk");
            default -> Tr.t("talk.refused.failed");
        };
    }

    /** "Open since 14:02 · Ava_Builds · not seen yet", "Ended by ... Write to start a new conversation.", or the first-time line. */
    private String staffFoot(TalkViewPacket v, List<TalkViewPacket.Entry> lines) {
        long open = openTalk(lines);
        if (open >= 0) {
            long since = v.openSince();
            String by = v.openedBy();
            int newStaff = 0;
            for (TalkViewPacket.Entry e : lines) {
                if (e.talk() == open && since == 0) {
                    since = e.at(); // started after the window opened
                    by = e.staff();
                }
            }
            for (int i = v.lines().size(); i < lines.size(); i++) {
                if (lines.get(i).kind() == TalkViewPacket.STAFF) {
                    newStaff++;
                }
            }
            boolean unseen = !v.seen() || v.where().isEmpty() && newStaff > 0;
            String s = Tr.t("talk.window.open_since", when(since), by);
            return unseen ? s + " · " + Tr.t("talk.window.not_seen") : s;
        }
        if (!lines.isEmpty()) {
            TalkViewPacket.Entry last = lines.get(lines.size() - 1);
            return last.staff().isEmpty() ? Tr.t("talk.window.ended_itself_new") : Tr.t("talk.window.ended_new", last.staff());
        }
        return Tr.t("talk.window.start");
    }

    private void ghostKey(GuiGraphics g, Font f, int x, int y, int w, String s, int mx, int my, Runnable action) {
        boolean hot = mx >= x && mx < x + w && my >= y && my < y + KEY_H;
        Ui.frame(g, x, y, w, KEY_H, hot ? Ui.VIOLET : Ui.DIM);
        label(g, f, s, x, y, w, KEY_H, hot ? Ui.VIOLET : Ui.MUTED);
        hits.add(new Ui.Hit(x, y, w, KEY_H, action));
    }

    private static void label(GuiGraphics g, Font f, String s, int x, int y, int w, int h, int ink) {
        Component c = Component.literal(s).withStyle(ChatFormatting.BOLD);
        Ui.text(g, f, c, x + (w - (int) Math.ceil(f.width(c) * SMALL)) / 2, y + (h - 6) / 2, ink, SMALL);
    }

    // ------------------------------------------------------------------ acting

    private void send() {
        TalkViewPacket v = ClientTalk.view();
        String text = box.getValue().strip();
        if (v == null || text.isEmpty()) {
            return;
        }
        ServerUtilsNetwork.sendToServer(new TalkActionPacket(TalkActionPacket.SEND, v.player(), text));
        box.setValue("");
        scroll = 0;
    }

    /** END CONVERSATION, then SURE? END within 3 seconds (D638). */
    private void end() {
        TalkViewPacket v = ClientTalk.view();
        long now = Util.getMillis();
        if (v == null) {
            return;
        }
        if (armedAt < 0 || now - armedAt >= ARM_MS) {
            armedAt = now;
            return;
        }
        armedAt = -1;
        ServerUtilsNetwork.sendToServer(new TalkActionPacket(TalkActionPacket.END, v.player(), ""));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        pressed = null;
        if (button == 0) {
            pressed = hitAt(mouseX, mouseY);
            if (pressed != null) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pressed != null) {
            Ui.Hit was = pressed;
            pressed = null;
            Ui.Hit now = hitAt(mouseX, mouseY);
            if (now != null && now.x() == was.x() && now.y() == was.y()) {
                now.action().run();
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Nullable
    private Ui.Hit hitAt(double mx, double my) {
        for (Ui.Hit hit : hits) {
            if (hit.contains(mx, my)) {
                return hit;
            }
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = (int) Math.max(0, Math.min(maxScroll, scroll + delta * 16));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && box.isFocused()) {
            send();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
