package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.check.CheckRules;
import com.mrgregles.bsp_server_utilities.check.ClientReport;
import com.mrgregles.bsp_server_utilities.check.Verdict;
import com.mrgregles.bsp_server_utilities.network.AdminClientsEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminClientsPacket;
import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The CLIENTS tab of the admin panel (owner decisions D130, D134 to D141), look A of {@code tools/preview/clients_refined.html}:
 * two pages under the title.
 *
 * <ul>
 *   <li>PLAYERS: on the left everyone on the network with a report (flagged first, their flag count in gold, "blocked"
 *   in red for what would now refuse them, "exempt"), then today's refusals; on the right the picked player: their
 *   flags as chips, MODS / PACKS with the unusual ones first in gold and BLOCK on each line (SURE?), and KICK with a
 *   typed reason (SURE?).</li>
 *   <li>RULES: which rules this server follows (the network's or its own), the trusted setup with TRUST MY SETUP (SURE?)
 *   and EXPORT, the refusal message (sent a second after typing stops); on the right the block list, the names allowed
 *   anyway and the exempt players, each with + add and ×.</li>
 * </ul>
 */
final class ClientsTab {
    private static final int TOP = 22, BOX_Y = 36, BOX_H = 144, LEFT_W = 118, RIGHT_X = 130, RIGHT_W = 206, RULES_LEFT_W = 160, RULES_RIGHT_X = 172, RULES_RIGHT_W = 164;
    private static final int ROW = 10, REFRESH_TICKS = 100, SAVE_DELAY_TICKS = 20;
    private static final long SURE_MS = 3000;
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private static final int GREEN = 0xFF8FE04A, CHIP = 0xFF1B2230, GOLD_BG = 0xFF2A2414, RED_BG = 0xFF3A2020, GREEN_BG = 0xFF1A2A16;

    private enum Page { PLAYERS, RULES }

    private enum Adding { BLOCK, ALLOWED, EXEMPT }

    private final AdminScreen host;
    @Nullable
    private AdminClientsPacket data;
    private CheckRules rules = CheckRules.DEFAULTS;
    private Page page = Page.PLAYERS;
    private boolean showPacks;
    private int listScroll, detailScroll, rulesScroll, refreshIn;
    private String reason = "", message = "", addText = "";
    private CheckRules.Kind addKind = CheckRules.Kind.WORD;
    @Nullable
    private Adding adding;
    @Nullable
    private UUID picked;
    @Nullable
    private String sureKey;
    private long sureUntil;
    private boolean messageDirty;
    private int messageTicks;
    private String note = "";
    private long noteUntil;
    @Nullable
    private EditBox reasonBox, addBox;
    @Nullable
    private MultiLineEditBox messageBox;

    ClientsTab(AdminScreen host) {
        this.host = host;
    }

    /** The setup guide's Client check page opens on RULES, where TRUST MY SETUP is. */
    void showRules() {
        page = Page.RULES;
        adding = null;
    }

    void open() {
        refreshIn = REFRESH_TICKS;
        send(AdminClientsEditPacket.of(AdminClientsEditPacket.Action.REFRESH));
    }

    private static void send(AdminClientsEditPacket p) {
        ServerUtilsNetwork.CHANNEL.sendToServer(p);
    }

    void accept(AdminClientsPacket p) {
        boolean pickedChanged = data == null || (p.detail() == null) != (data.detail() == null);
        data = p;
        rules = p.rules();
        if (!messageDirty && (messageBox == null || !messageBox.isFocused())) {
            message = rules.message();
            if (messageBox != null && !messageBox.getValue().equals(message)) {
                messageBox.setValue(message);
            }
        }
        showNote(p.note());
        if (pickedChanged && page == Page.PLAYERS) {
            host.rebuildTab();
        }
    }

    private void showNote(String key) {
        String text = switch (key) {
            case "kicked" -> Tr.t("clients.note.kicked");
            case "not_found" -> Tr.t("clients.note.not_found");
            case "blocked" -> Tr.t("clients.note.blocked");
            case "saved" -> Tr.t("note.saved");
            case "trusted" -> Tr.t("clients.note.trusted");
            case "no_report" -> Tr.t("clients.note.no_report");
            case "exported" -> Tr.t("clients.note.exported");
            case "no_setup" -> Tr.t("clients.note.no_setup");
            default -> "";
        };
        if (!text.isEmpty()) {
            note = text;
            noteUntil = System.currentTimeMillis() + 3000;
        }
    }

    /** Sends a typed message that has not gone yet (switching tabs, closing). */
    void flush() {
        if (messageDirty) {
            messageDirty = false;
            sendRules(new CheckRules(rules.block(), rules.allowed(), rules.exempt(), message, rules.revision()));
        }
    }

    private void sendRules(CheckRules next) {
        rules = next.sanitised();
        send(new AdminClientsEditPacket(AdminClientsEditPacket.Action.RULES, AdminClientsEditPacket.NOBODY, rules.encode(), ""));
    }

    void tick() {
        for (EditBox b : new EditBox[]{reasonBox, addBox}) {
            if (b != null) {
                b.tick();
            }
        }
        if (messageBox != null) {
            messageBox.tick();
        }
        if (messageDirty && --messageTicks <= 0) {
            flush();
        }
        if (--refreshIn <= 0) {
            open();
        }
    }

    void addWidgets(int x, int y) {
        reasonBox = addBox = null;
        messageBox = null;
        Font font = host.font();
        if (page == Page.PLAYERS) {
            if (data != null && data.detail() != null) {
                reasonBox = new EditBox(font, x + RIGHT_X + 5, y + BOX_Y + BOX_H - 16, RIGHT_W - 44, 12, Component.literal(Tr.t("clients.reason")));
                reasonBox.setMaxLength(160);
                reasonBox.setHint(Component.literal(Tr.t("clients.reason")).withStyle(ChatFormatting.DARK_GRAY));
                reasonBox.setValue(reason);
                reasonBox.setResponder(s -> reason = s);
                host.add(reasonBox);
            }
            return;
        }
        messageBox = new MultiLineEditBox(font, x + 8 + 6, y + BOX_Y + 96, RULES_LEFT_W - 12, 42, Component.empty(), Component.literal(Tr.t("clients.message")));
        messageBox.setCharacterLimit(CheckRules.MAX_MESSAGE);
        messageBox.setValue(message);
        messageBox.setValueListener(s -> {
            if (!s.equals(message)) {
                message = s;
                messageDirty = true;
                messageTicks = SAVE_DELAY_TICKS;
            }
        });
        host.add(messageBox);
        if (adding != null) {
            addBox = new EditBox(font, x + RULES_RIGHT_X + 5, y + BOX_Y + 14, RULES_RIGHT_W - 10, 12, Component.literal(Tr.t("clients.add_hint")));
            addBox.setMaxLength(adding == Adding.EXEMPT ? 80 : CheckRules.MAX_VALUE);
            String hint = adding == Adding.EXEMPT ? Tr.t("clients.add_exempt_hint") : Tr.t("clients.add_hint");
            addBox.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
            addBox.setValue(addText);
            addBox.setResponder(s -> addText = s);
            host.add(addBox);
        }
    }

    private boolean sure(String key) {
        long now = System.currentTimeMillis();
        if (key.equals(sureKey) && now < sureUntil) {
            sureKey = null;
            return true;
        }
        sureKey = key;
        sureUntil = now + SURE_MS;
        return false;
    }

    private boolean isSure(String key) {
        return key.equals(sureKey) && System.currentTimeMillis() < sureUntil;
    }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int n = data == null ? 0 : data.rows().size();
        int cx = x + 8;
        cx += chip(g, cx, y + TOP, Tr.t("clients.players", n), page == Page.PLAYERS, hits, () -> showPage(Page.PLAYERS)) + 3;
        chip(g, cx, y + TOP, Tr.t("clients.rules"), page == Page.RULES, hits, () -> showPage(Page.RULES));
        String which = data == null ? "" : data.useOwn() ? Tr.t("clients.own_rules_short", data.here()) : Tr.t("clients.network_rules");
        String w = Ui.fit(font, which, 160, TINY);
        Ui.text(g, font, w, x + AdminScreen.W - 8 - Ui.width(font, w, TINY), y + TOP + 3, data != null && data.useOwn() ? Ui.VIOLET : Ui.MUTED, TINY);
        if (page == Page.PLAYERS) {
            drawPlayers(g, x, y, mx, my, hits);
            drawDetail(g, x, y, mx, my, hits);
        } else {
            drawRulesLeft(g, x, y, mx, my, hits);
            drawLists(g, x, y, mx, my, hits);
        }
        drawFooter(g, x, y, hits);
    }

    private void showPage(Page p) {
        if (page != p) {
            flush();
            page = p;
            adding = null;
            host.rebuildTab();
        }
    }

    private int chip(GuiGraphics g, int x, int y, String label, boolean on, List<Ui.Hit> hits, Runnable action) {
        Font font = host.font();
        String s = Ui.fit(font, label, 90, TINY);
        int w = Ui.width(font, s, TINY) + 8;
        g.fill(x, y, x + w, y + 11, Ui.SLOT);
        Ui.frame(g, x, y, w, 11, on ? Ui.VIOLET : Ui.DIM);
        Ui.text(g, font, s, x + 4, y + 3, on ? Ui.VIOLET : Ui.TEXT, TINY);
        hits.add(new Ui.Hit(x, y, w, 11, action));
        return w;
    }

    private void drawFooter(GuiGraphics g, int x, int y, List<Ui.Hit> hits) {
        Font font = host.font();
        int fy = y + AdminScreen.H - 8 - 14;
        String key = Tr.t("drops.refresh");
        int kw = Ui.width(font, key, TINY) + 16, kx = x + AdminScreen.W - 8 - kw;
        String foot;
        if (System.currentTimeMillis() < noteUntil) {
            foot = note;
        } else if (data == null) {
            foot = "";
        } else if (page == Page.RULES) {
            foot = data.useOwn() ? Tr.t("clients.footer_own", data.here()) : Tr.t("clients.footer_network");
        } else if (data.setup() == null) {
            foot = Tr.t("clients.footer_no_setup");
        } else {
            foot = Tr.t("clients.footer_setup", when(data.setup().takenAtMs()), data.setup().takenBy());
        }
        Ui.text(g, font, Ui.fit(font, foot, kx - x - 16, TINY), x + 8, fy + 5, System.currentTimeMillis() < noteUntil ? Ui.TEXT : Ui.MUTED, TINY);
        Ui.frame(g, kx, fy, kw, 14, Ui.DIM);
        Ui.text(g, font, Component.literal(key).withStyle(ChatFormatting.BOLD), kx + 8, fy + 5, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(kx, fy, kw, 14, this::open));
    }

    private static String when(long ms) {
        return new SimpleDateFormat("d MMM HH:mm", Locale.ROOT).format(new Date(ms));
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ms));
    }

    // ------------------------------------------------------------------ players

    /** One line of the left list: a header, a player or a refusal. */
    private record Line(@Nullable String header, @Nullable AdminClientsPacket.PlayerRow player, @Nullable AdminClientsPacket.Refused refused) {
    }

    private List<Line> lines() {
        List<Line> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        out.add(new Line(Tr.t("clients.online"), null, null));
        data.rows().forEach(r -> out.add(new Line(null, r, null)));
        if (data.rows().isEmpty()) {
            out.add(new Line("", null, null));
        }
        out.add(new Line(Tr.t("clients.refused_today", data.refusals().size()), null, null));
        data.refusals().forEach(r -> out.add(new Line(null, null, r)));
        return out;
    }

    private void drawPlayers(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + 8, ly = y + BOX_Y;
        Ui.inset(g, lx, ly, LEFT_W, BOX_H);
        List<Line> lines = lines();
        int visible = (BOX_H - 4) / ROW;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, lines.size() - visible)));
        for (int i = 0; i < visible && listScroll + i < lines.size(); i++) {
            Line l = lines.get(listScroll + i);
            int ry = ly + 2 + i * ROW;
            if (l.header() != null) {
                Ui.text(g, font, l.header().toUpperCase(Locale.ROOT), lx + 5, ry + 3, Ui.MUTED, TINY);
            } else if (l.player() != null) {
                AdminClientsPacket.PlayerRow r = l.player();
                boolean sel = r.id().equals(picked);
                if (sel) {
                    g.fill(lx + 1, ry, lx + LEFT_W - 1, ry + ROW, 0xFF1B2230);
                    g.fill(lx + 1, ry, lx + 3, ry + ROW, Ui.VIOLET);
                } else if (mx >= lx && mx < lx + LEFT_W && my >= ry && my < ry + ROW) {
                    g.fill(lx + 1, ry, lx + LEFT_W - 1, ry + ROW, 0x22B58CFF);
                }
                String name = Ui.fit(font, r.name(), 50, SMALL);
                Ui.text(g, font, Component.literal(name).withStyle(r.flags() > 0 ? ChatFormatting.BOLD : ChatFormatting.RESET), lx + 6, ry + 2, Ui.TEXT, SMALL);
                int sx = lx + 8 + Ui.width(font, name, SMALL);
                String srv = Ui.fit(font, r.server(), 30, TINY);
                int sw = Ui.width(font, srv, TINY) + 4;
                g.fill(sx, ry + 1, sx + sw, ry + 9, CHIP);
                Ui.text(g, font, srv, sx + 2, ry + 3, Ui.MUTED, TINY);
                String mark;
                int colour;
                if (r.exempt()) {
                    mark = Tr.t("clients.exempt");
                    colour = Ui.MUTED;
                } else if (r.blocked()) {
                    mark = Tr.t("clients.blocked");
                    colour = Ui.BAD;
                } else if (r.flags() > 0) {
                    mark = r.flags() + " ⚑";
                    colour = Ui.GOLD;
                } else {
                    mark = "✓";
                    colour = GREEN;
                }
                Ui.text(g, font, Component.literal(mark).withStyle(ChatFormatting.BOLD), lx + LEFT_W - 5 - Ui.width(font, mark, TINY), ry + 3, colour, TINY);
                UUID id = r.id();
                hits.add(new Ui.Hit(lx, ry, LEFT_W, ROW, () -> pick(id)));
            } else if (l.refused() != null) {
                AdminClientsPacket.Refused r = l.refused();
                String name = Ui.fit(font, r.name(), 44, SMALL);
                Ui.text(g, font, name, lx + 6, ry + 2, Ui.BAD, SMALL);
                Ui.text(g, font, clock(r.atMs()), lx + 8 + Ui.width(font, name, SMALL), ry + 3, Ui.MUTED, TINY);
                String why = Ui.fit(font, r.reason(), 40, TINY);
                Ui.text(g, font, why, lx + LEFT_W - 5 - Ui.width(font, why, TINY), ry + 3, Ui.BAD, TINY);
            }
        }
        if (data != null && data.rows().isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t("clients.nobody"), LEFT_W - 10, TINY), lx + 5, ly + 2 + ROW + 3, Ui.MUTED, TINY);
        }
    }

    private void pick(UUID id) {
        picked = id;
        detailScroll = 0;
        reason = "";
        send(new AdminClientsEditPacket(AdminClientsEditPacket.Action.SELECT, id, "", ""));
    }

    /** A line of the picked player's mods or packs: what to show, how it stands, and what BLOCK sends. */
    private record Item(String name, String detail, String status, int colour, boolean unusual, @Nullable AdminClientsEditPacket block) {
    }

    private List<Item> items(AdminClientsPacket.Detail d) {
        List<Item> unusual = new ArrayList<>(), rest = new ArrayList<>();
        Verdict v = d.verdict();
        Set<String> refusedNames = new java.util.HashSet<>();
        v.refuse().forEach(i -> refusedNames.add(i.name()));
        if (!showPacks) {
            for (ClientReport.Mod m : d.report().mods()) {
                String status;
                int colour;
                boolean odd = true;
                if (refusedNames.contains(m.id())) {
                    status = Tr.t("clients.status.blocked");
                    colour = Ui.BAD;
                } else if (v.notTrustedMods().contains(m.id())) {
                    status = Tr.t("clients.status.not_trusted");
                    colour = Ui.GOLD;
                } else if (v.fileDiffers().contains(m.id())) {
                    status = Tr.t("clients.status.differs");
                    colour = Ui.GOLD;
                } else {
                    status = d.hasSetup() ? Tr.t("clients.status.trusted") : "";
                    colour = Ui.TEXT;
                    odd = false;
                }
                AdminClientsEditPacket block = refusedNames.contains(m.id()) ? null : new AdminClientsEditPacket(AdminClientsEditPacket.Action.BLOCK_MOD, d.id(), m.id(), "");
                (odd ? unusual : rest).add(new Item(m.id(), m.version(), status, colour, odd, block));
            }
        } else {
            for (ClientReport.Pack p : d.report().packs()) {
                String status;
                int colour;
                boolean odd = true;
                if (refusedNames.contains(p.name())) {
                    status = Tr.t("clients.status.blocked");
                    colour = Ui.BAD;
                } else if (v.notTrustedPacks().contains(p.name())) {
                    status = Tr.t("clients.status.not_trusted");
                    colour = Ui.GOLD;
                } else {
                    status = d.hasSetup() ? Tr.t("clients.status.trusted") : "";
                    colour = Ui.TEXT;
                    odd = false;
                }
                String fp = p.sha1().length() == 40 ? p.sha1().substring(0, 4) + "…" + p.sha1().substring(36) : "";
                AdminClientsEditPacket block = p.sha1().length() == 40 && !refusedNames.contains(p.name())
                        ? new AdminClientsEditPacket(AdminClientsEditPacket.Action.BLOCK_PACK, d.id(), p.sha1(), p.name()) : null;
                (odd ? unusual : rest).add(new Item(p.name(), fp, status, colour, odd, block));
            }
        }
        rest.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        unusual.addAll(rest);
        return unusual;
    }

    private void drawDetail(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RIGHT_X, py = y + BOX_Y;
        Ui.inset(g, px, py, RIGHT_W, BOX_H);
        AdminClientsPacket.Detail d = data == null ? null : data.detail();
        if (d == null) {
            for (String line : Ui.wrap(font, Tr.t("clients.pick"), RIGHT_W - 10, TINY)) {
                Ui.text(g, font, line, px + 5, py + 6, Ui.MUTED, TINY);
                py += 7;
            }
            return;
        }
        String name = Ui.fit(font, d.name(), 70, SMALL);
        Ui.text(g, font, Component.literal(name).withStyle(ChatFormatting.BOLD), px + 5, py + 4, Ui.TEXT, SMALL);
        int sx = px + 8 + Ui.width(font, name, SMALL);
        String srv = Ui.fit(font, d.server(), 50, TINY);
        g.fill(sx, py + 3, sx + Ui.width(font, srv, TINY) + 4, py + 11, CHIP);
        Ui.text(g, font, srv, sx + 2, py + 5, Ui.MUTED, TINY);
        String at = Tr.t("clients.checked", clock(d.atMs()));
        Ui.text(g, font, at, px + RIGHT_W - 5 - Ui.width(font, at, TINY), py + 5, Ui.MUTED, TINY);
        // the flags
        Verdict v = d.verdict();
        ClientReport r = d.report();
        List<Object[]> flags = new ArrayList<>();
        if (v.exempt()) {
            flags.add(new Object[]{Tr.t("clients.flag.exempt"), CHIP, Ui.MUTED});
        }
        if (!v.refuse().isEmpty()) {
            flags.add(new Object[]{Tr.t("clients.flag.blocked", v.refuse().size()), RED_BG, Ui.BAD});
        }
        if (v.brightness()) {
            flags.add(new Object[]{Tr.t("clients.flag.bright", Math.round(r.brightness() * 100)), GOLD_BG, Ui.GOLD});
        }
        if (!v.notTrustedMods().isEmpty()) {
            flags.add(new Object[]{Tr.t("clients.flag.not_trusted", v.notTrustedMods().size()), GOLD_BG, Ui.GOLD});
        }
        if (!v.fileDiffers().isEmpty()) {
            flags.add(new Object[]{Tr.t("clients.flag.differs", v.fileDiffers().size()), GOLD_BG, Ui.GOLD});
        }
        if (!v.notTrustedPacks().isEmpty()) {
            flags.add(new Object[]{Tr.t("clients.flag.packs", v.notTrustedPacks().size()), GOLD_BG, Ui.GOLD});
        }
        if (!v.hidden().isEmpty()) {
            flags.add(new Object[]{Tr.t("clients.flag.hidden", v.hidden().size()), GOLD_BG, Ui.GOLD});
        }
        if (r.stonePassed()) {
            flags.add(new Object[]{Tr.t("clients.flag.stone_ok"), GREEN_BG, GREEN});
        } else {
            flags.add(new Object[]{Tr.t("clients.flag.stone_failed", r.stoneBlock()), RED_BG, Ui.BAD});
        }
        if (!r.shader().isEmpty()) {
            String sh;
            if (r.shadersOn()) {
                sh = Tr.t("clients.flag.shader_on", r.shader());
            } else {
                sh = Tr.t("clients.flag.shader_off", r.shader());
            }
            flags.add(new Object[]{sh, CHIP, Ui.TEXT});
        }
        int fx = px + 5, fy = py + 15;
        for (Object[] f : flags) {
            String s = Ui.fit(font, (String) f[0], 120, TINY);
            int fw = Ui.width(font, s, TINY) + 6;
            if (fx + fw > px + RIGHT_W - 5) {
                fx = px + 5;
                fy += 10;
            }
            if (fy > py + 25) {
                break;
            }
            g.fill(fx, fy, fx + fw, fy + 9, (int) f[1]);
            Ui.text(g, font, s, fx + 3, fy + 2, (int) f[2], TINY);
            fx += fw + 3;
        }
        // MODS / PACKS
        int cy = py + 37, cx = px + 5;
        cx += chip(g, cx, cy, Tr.t("clients.mods", r.mods().size()), !showPacks, hits, () -> {
            showPacks = false;
            detailScroll = 0;
        }) + 3;
        chip(g, cx, cy, Tr.t("clients.packs", r.packs().size()), showPacks, hits, () -> {
            showPacks = true;
            detailScroll = 0;
        });
        List<Item> items = items(d);
        int top = py + 51, visible = 7;
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) {
            Ui.text(g, font, Ui.fit(font, Tr.t(showPacks ? "clients.no_packs" : "clients.no_mods"), RIGHT_W - 10, TINY), px + 5, top + 3, Ui.MUTED, TINY);
        }
        for (int i = 0; i < visible && detailScroll + i < items.size(); i++) {
            Item it = items.get(detailScroll + i);
            int ry = top + i * ROW;
            if (i > 0) {
                g.fill(px + 3, ry, px + RIGHT_W - 3, ry + 1, Ui.ROW_LINE);
            }
            Ui.text(g, font, Component.literal(Ui.fit(font, it.name(), 72, SMALL)).withStyle(ChatFormatting.BOLD), px + 5, ry + 2, it.unusual() ? it.colour() : Ui.TEXT, SMALL);
            Ui.text(g, font, Ui.fit(font, it.detail(), 40, TINY), px + 82, ry + 3, Ui.MUTED, TINY);
            Ui.text(g, font, Ui.fit(font, it.status(), 42, TINY), px + 126, ry + 3, it.unusual() ? it.colour() : Ui.MUTED, TINY);
            if (it.block() != null) {
                String key = "block:" + it.name();
                boolean sureNow = isSure(key);
                int bx = px + RIGHT_W - 5 - 28;
                if (sureNow) {
                    g.fill(bx, ry + 1, bx + 28, ry + 9, 0xFFD63B2F);
                } else {
                    g.fill(bx, ry + 1, bx + 28, ry + 9, Ui.SLOT);
                    Ui.frame(g, bx, ry + 1, 28, 8, Ui.DIM);
                }
                String label = Tr.t(sureNow ? "rules.sure" : "clients.block");
                Ui.text(g, font, Component.literal(label).withStyle(ChatFormatting.BOLD), bx + (28 - Ui.width(font, label, TINY)) / 2, ry + 3, sureNow ? 0xFFFFFFFF : Ui.MUTED, TINY);
                AdminClientsEditPacket p = it.block();
                hits.add(new Ui.Hit(bx, ry + 1, 28, 8, () -> {
                    if (sure(key)) {
                        send(p);
                    }
                }));
            }
        }
        // KICK
        int ky = py + BOX_H - 16, kx = px + RIGHT_W - 5 - 32;
        boolean sureNow = isSure("kick");
        if (sureNow) {
            g.fill(kx, ky, kx + 32, ky + 12, 0xFFD63B2F);
        } else {
            g.fill(kx, ky, kx + 32, ky + 12, 0xFF3A2A2A);
            Ui.frame(g, kx, ky, 32, 12, 0xFFD63B2F);
        }
        String k = Tr.t(sureNow ? "rules.sure" : "clients.kick");
        Ui.text(g, font, Component.literal(k).withStyle(ChatFormatting.BOLD), kx + (32 - Ui.width(font, k, TINY)) / 2, ky + 4, sureNow ? 0xFFFFFFFF : 0xFFFF8F86, TINY);
        UUID id = d.id();
        hits.add(new Ui.Hit(kx, ky, 32, 12, () -> {
            if (sure("kick")) {
                send(new AdminClientsEditPacket(AdminClientsEditPacket.Action.KICK, id, reason.strip(), ""));
                reason = "";
                if (reasonBox != null) {
                    reasonBox.setValue("");
                }
            }
        }));
    }

    // ------------------------------------------------------------------ rules

    private void drawRulesLeft(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int lx = x + 8, ly = y + BOX_Y, ix = lx + 6, iw = RULES_LEFT_W - 12;
        Ui.inset(g, lx, ly, RULES_LEFT_W, BOX_H);
        boolean own = data != null && data.useOwn();
        Ui.text(g, font, Tr.t("clients.follows").toUpperCase(Locale.ROOT), ix, ly + 4, Ui.MUTED, TINY);
        int cx = ix;
        cx += chip(g, cx, ly + 11, Tr.t("clients.network_rules"), !own, hits, () -> useOwn(false)) + 3;
        chip(g, cx, ly + 11, Tr.t("clients.own_rules"), own, hits, () -> useOwn(true));
        int ty = ly + 25;
        if (own && data != null) {
            for (String line : Ui.wrap(font, Tr.t("clients.own_hint", data.here()), iw, TINY)) {
                if (ty > ly + 37) {
                    break;
                }
                Ui.text(g, font, line, ix, ty, Ui.VIOLET, TINY);
                ty += 6;
            }
        }
        Ui.text(g, font, Tr.t("clients.setup").toUpperCase(Locale.ROOT), ix, ly + 41, Ui.MUTED, TINY);
        AdminClientsPacket.Setup s = data == null ? null : data.setup();
        if (s == null) {
            Ui.text(g, font, Ui.fit(font, Tr.t("clients.setup_none"), iw, SMALL), ix, ly + 49, Ui.TEXT, SMALL);
        } else {
            Ui.text(g, font, Ui.fit(font, Tr.t("clients.setup_taken", when(s.takenAtMs()), s.takenBy()), iw, SMALL), ix, ly + 49, Ui.TEXT, SMALL);
            String sum = s.shader().isEmpty() ? Tr.t("clients.setup_counts", s.mods(), s.packs()) : Tr.t("clients.setup_counts_shader", s.mods(), s.packs(), s.shader());
            Ui.text(g, font, Ui.fit(font, sum, iw, TINY), ix, ly + 57, Ui.MUTED, TINY);
        }
        boolean sureNow = isSure("trust");
        String trust = Tr.t(sureNow ? "rules.sure" : "clients.trust");
        int tw = Ui.width(font, Tr.t("clients.trust"), TINY) + 12;
        g.fill(ix, ly + 66, ix + tw, ly + 78, sureNow ? 0xFFD63B2F : Ui.VIOLET);
        Ui.text(g, font, Component.literal(trust).withStyle(ChatFormatting.BOLD), ix + (tw - Ui.width(font, trust, TINY)) / 2, ly + 70, sureNow ? 0xFFFFFFFF : Ui.INK, TINY);
        hits.add(new Ui.Hit(ix, ly + 66, tw, 12, () -> {
            if (sure("trust")) {
                send(AdminClientsEditPacket.of(AdminClientsEditPacket.Action.TRUST));
            }
        }));
        String exp = Tr.t("clients.export");
        int ew = Ui.width(font, exp, TINY) + 12, ex = ix + tw + 4;
        Ui.frame(g, ex, ly + 66, ew, 12, Ui.DIM);
        Ui.text(g, font, Component.literal(exp).withStyle(ChatFormatting.BOLD), ex + 6, ly + 70, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(ex, ly + 66, ew, 12, () -> send(AdminClientsEditPacket.of(AdminClientsEditPacket.Action.EXPORT))));
        Ui.text(g, font, Tr.t("clients.message").toUpperCase(Locale.ROOT), ix, ly + 87, Ui.MUTED, TINY);
        String count = Tr.t("rules.count", message.length(), CheckRules.MAX_MESSAGE);
        Ui.text(g, font, count, ix + iw - Ui.width(font, count, TINY), ly + 87, message.length() >= CheckRules.MAX_MESSAGE ? Ui.GOLD : Ui.MUTED, TINY);
    }

    private void useOwn(boolean own) {
        if (data != null && data.useOwn() != own) {
            flush();
            send(new AdminClientsEditPacket(AdminClientsEditPacket.Action.USE_OWN, AdminClientsEditPacket.NOBODY, Boolean.toString(own), ""));
        }
    }

    /** A line of the rules lists: a section header with its + add, an entry (its kind before, a note after) with ×, or "None." */
    private record ListLine(@Nullable Adding section, String text, String kind, String note, @Nullable Runnable remove) {
    }

    private List<ListLine> listLines() {
        List<ListLine> out = new ArrayList<>();
        out.add(new ListLine(Adding.BLOCK, Tr.t("clients.block_list", rules.block().size()), "", "", null));
        for (CheckRules.Entry e : rules.block()) {
            String kind = switch (e.kind()) {
                case WORD -> Tr.t("clients.kind.word");
                case MOD -> Tr.t("clients.kind.mod");
                case PACK -> Tr.t("clients.kind.pack");
            };
            String shown = e.kind() == CheckRules.Kind.PACK && !e.label().isEmpty() ? e.label() : e.value();
            out.add(new ListLine(null, shown, kind, "", () -> {
                List<CheckRules.Entry> next = new ArrayList<>(rules.block());
                next.remove(e);
                sendRules(rules.withBlock(next));
            }));
        }
        out.add(new ListLine(Adding.ALLOWED, Tr.t("clients.allowed", rules.allowed().size()), "", "", null));
        if (rules.allowed().isEmpty()) {
            out.add(new ListLine(null, Tr.t("clients.none"), "", "", null));
        }
        for (String a : rules.allowed()) {
            out.add(new ListLine(null, a, "", "", () -> {
                List<String> next = new ArrayList<>(rules.allowed());
                next.remove(a);
                sendRules(new CheckRules(rules.block(), next, rules.exempt(), rules.message(), rules.revision()));
            }));
        }
        out.add(new ListLine(Adding.EXEMPT, Tr.t("clients.exempt_list", rules.exempt().size()), "", "", null));
        if (rules.exempt().isEmpty()) {
            out.add(new ListLine(null, Tr.t("clients.none"), "", "", null));
        }
        for (CheckRules.Exempt e : rules.exempt()) {
            out.add(new ListLine(null, e.name(), "", e.note(), () -> {
                List<CheckRules.Exempt> next = new ArrayList<>(rules.exempt());
                next.remove(e);
                sendRules(new CheckRules(rules.block(), rules.allowed(), next, rules.message(), rules.revision()));
            }));
        }
        return out;
    }

    private void drawLists(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        int px = x + RULES_RIGHT_X, py = y + BOX_Y;
        Ui.inset(g, px, py, RULES_RIGHT_W, BOX_H);
        if (adding != null) {
            drawAdding(g, px, py, hits);
            return;
        }
        List<ListLine> lines = listLines();
        int visible = (BOX_H - 4) / ROW;
        rulesScroll = Math.max(0, Math.min(rulesScroll, Math.max(0, lines.size() - visible)));
        for (int i = 0; i < visible && rulesScroll + i < lines.size(); i++) {
            ListLine l = lines.get(rulesScroll + i);
            int ry = py + 2 + i * ROW;
            if (l.section() != null) {
                Ui.text(g, font, Component.literal(l.text()).withStyle(ChatFormatting.BOLD), px + 5, ry + 3, Ui.VIOLET, TINY);
                String add = Tr.t("clients.add");
                int aw = Ui.width(font, add, TINY);
                Ui.text(g, font, Component.literal(add).withStyle(ChatFormatting.BOLD), px + RULES_RIGHT_W - 5 - aw, ry + 3, Ui.VIOLET, TINY);
                Adding which = l.section();
                hits.add(new Ui.Hit(px + RULES_RIGHT_W - 7 - aw, ry, aw + 4, ROW, () -> {
                    adding = which;
                    addText = "";
                    addKind = CheckRules.Kind.WORD;
                    host.rebuildTab();
                }));
                continue;
            }
            int tx = px + 7;
            if (!l.kind().isEmpty()) {
                Ui.text(g, font, l.kind(), tx, ry + 3, Ui.MUTED, TINY);
                tx += 22;
            }
            Ui.text(g, font, Component.literal(Ui.fit(font, l.text(), 80, SMALL)).withStyle(l.remove() != null ? ChatFormatting.BOLD : ChatFormatting.RESET), tx, ry + 2,
                    l.remove() != null ? Ui.TEXT : Ui.MUTED, SMALL);
            if (!l.note().isEmpty()) {
                Ui.text(g, font, Ui.fit(font, l.note(), 50, TINY), px + 100, ry + 3, Ui.MUTED, TINY);
            }
            if (l.remove() != null) {
                Ui.text(g, font, "×", px + RULES_RIGHT_W - 10, ry + 1, Ui.BAD, SMALL);
                Runnable rm = l.remove();
                hits.add(new Ui.Hit(px + RULES_RIGHT_W - 13, ry, 10, ROW, rm));
            }
        }
    }

    private void drawAdding(GuiGraphics g, int px, int py, List<Ui.Hit> hits) {
        Font font = host.font();
        String title;
        if (adding == Adding.BLOCK) {
            title = Tr.t("clients.add_block");
        } else if (adding == Adding.ALLOWED) {
            title = Tr.t("clients.add_allowed");
        } else {
            title = Tr.t("clients.add_exempt");
        }
        Ui.text(g, font, Component.literal(title).withStyle(ChatFormatting.BOLD), px + 5, py + 4, Ui.VIOLET, TINY);
        String back = Tr.t("drops.back");
        int bw = Ui.width(font, back, TINY);
        Ui.text(g, font, back, px + RULES_RIGHT_W - 5 - bw, py + 4, Ui.MUTED, TINY);
        hits.add(new Ui.Hit(px + RULES_RIGHT_W - 7 - bw, py + 1, bw + 4, 10, () -> {
            adding = null;
            host.rebuildTab();
        }));
        int y2 = py + 30;
        if (adding == Adding.BLOCK) {
            int cx = px + 5;
            cx += chip(g, cx, y2, Tr.t("clients.kind.word_long"), addKind == CheckRules.Kind.WORD, hits, () -> addKind = CheckRules.Kind.WORD) + 3;
            chip(g, cx, y2, Tr.t("clients.kind.mod_long"), addKind == CheckRules.Kind.MOD, hits, () -> addKind = CheckRules.Kind.MOD);
            y2 += 14;
        }
        String hint;
        if (adding == Adding.BLOCK) {
            hint = addKind == CheckRules.Kind.WORD ? Tr.t("clients.word_hint") : Tr.t("clients.mod_hint");
        } else if (adding == Adding.ALLOWED) {
            hint = Tr.t("clients.allowed_hint");
        } else {
            hint = Tr.t("clients.exempt_hint");
        }
        for (String line : Ui.wrap(font, hint, RULES_RIGHT_W - 10, TINY)) {
            Ui.text(g, font, line, px + 5, y2, Ui.MUTED, TINY);
            y2 += 7;
        }
        int ax = px + RULES_RIGHT_W - 5 - 26, ay = py + BOX_H - 16;
        g.fill(ax, ay, ax + 26, ay + 11, Ui.VIOLET);
        String a = Tr.t("drops.add");
        Ui.text(g, font, Component.literal(a).withStyle(ChatFormatting.BOLD), ax + (26 - Ui.width(font, a, TINY)) / 2, ay + 3, Ui.INK, TINY);
        hits.add(new Ui.Hit(ax, ay, 26, 11, this::addEntry));
    }

    private void addEntry() {
        String t = addText.strip();
        if (t.isEmpty() || adding == null) {
            return;
        }
        switch (adding) {
            case BLOCK -> {
                List<CheckRules.Entry> next = new ArrayList<>(rules.block());
                next.add(new CheckRules.Entry(addKind, t, ""));
                sendRules(rules.withBlock(next));
            }
            case ALLOWED -> {
                List<String> next = new ArrayList<>(rules.allowed());
                next.add(t);
                sendRules(new CheckRules(rules.block(), next, rules.exempt(), rules.message(), rules.revision()));
            }
            case EXEMPT -> {
                String[] parts = t.split("\\s+", 2);
                List<CheckRules.Exempt> next = new ArrayList<>(rules.exempt());
                next.add(new CheckRules.Exempt(parts[0], parts.length > 1 ? parts[1] : ""));
                sendRules(new CheckRules(rules.block(), rules.allowed(), next, rules.message(), rules.revision()));
            }
        }
        adding = null;
        addText = "";
        host.rebuildTab();
    }

    boolean scroll(double mx, double delta) {
        int step = (int) Math.signum(delta);
        if (page == Page.RULES) {
            rulesScroll -= step;
        } else if (mx >= host.width / 2.0 - AdminScreen.W / 2.0 + RIGHT_X) {
            detailScroll -= step;
        } else {
            listScroll -= step;
        }
        return true;
    }
}
