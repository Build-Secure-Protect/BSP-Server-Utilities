package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.network.ServerUtilsNetwork;
import com.mrgregles.bsp_server_utilities.network.TutorialEditPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The TUTORIAL tab of the admin panel (owner decisions D719, D735): every tutorial with how it starts, whether players
 * see it and whether its draft has changes or missing parts, PUBLISH on a row whose draft has changes, the picked
 * tutorial's counts (started, finished, ended early and where), and OPEN BUILDER (the builder is its own screen).
 */
final class TutorialTab {
    private static final float TINY = 0.6f, SMALL = 0.7f;
    private final AdminScreen host;

    TutorialTab(AdminScreen host) {
        this.host = host;
    }

    private String picked = "";
    /** Rows scrolled past at the top of the list (audit of SU-146). */
    private int scroll;
    /** The row whose PUBLISH was clicked once with parts missing, and until when the second click publishes (audit of SU-146). */
    private String sureId = "";
    private long sureUntil;
    private static final long SURE_MS = 3000;

    void open() {
        ServerUtilsNetwork.CHANNEL.sendToServer(TutorialEditPacket.of(TutorialEditPacket.Action.OPEN, picked));
    }

    /** Leaving the tab: the server stops sending views (audit of SU-146). */
    void close() {
        ServerUtilsNetwork.CHANNEL.sendToServer(TutorialEditPacket.of(TutorialEditPacket.Action.CLOSE, ""));
    }

    /** The mouse wheel over the tab: the list moves a row at a time (audit of SU-146). */
    boolean scroll(double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }

    /** PUBLISH: straight away when nothing is missing, else a first click arms SURE for three seconds (audit of SU-146). */
    private void publish(String id, int issues) {
        long now = System.currentTimeMillis();
        if (issues > 0 && !(id.equals(sureId) && now < sureUntil)) {
            sureId = id;
            sureUntil = now + SURE_MS;
            return;
        }
        sureId = "";
        sureUntil = 0;
        ServerUtilsNetwork.CHANNEL.sendToServer(TutorialEditPacket.of(TutorialEditPacket.Action.PUBLISH, id));
    }

    void render(GuiGraphics g, int x, int y, int mx, int my, List<Ui.Hit> hits) {
        Font font = host.font();
        // inside the setup guide the guide's keys take the footer and its clip ends at y + H - 24, so the list is shorter
        // and OPEN BUILDER sits above that line (audit of SU-146)
        boolean guide = host.inGuide();
        int lx = x + 8, ly = y + 24, lw = host.pageW() - 16, lh = host.pageH() - 24 - 30 - 26 - (guide ? 18 : 0);
        Ui.inset(g, lx, ly, lw, lh);
        CompoundTag v = ClientTutorialAdmin.view();
        int ry = ly + 3;
        if (v == null) {
            Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.b.loading"), lw - 12, SMALL), lx + 6, ry + 2, Ui.MUTED, SMALL);
        } else if (v.getList("list", Tag.TAG_COMPOUND).isEmpty()) {
            int ty = ry + 2;
            for (String line : Ui.wrap(font, Tr.t("tutorial.tab.none"), lw - 12, SMALL)) {
                Ui.text(g, font, line, lx + 6, ty, Ui.MUTED, SMALL);
                ty += 9;
            }
        } else {
            var list = v.getList("list", Tag.TAG_COMPOUND);
            int fits = Math.max(1, (lh - 3) / 14);
            scroll = Math.max(0, Math.min(scroll, list.size() - fits));
            for (int i = scroll; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                if (ry > ly + lh - 14) {
                    break;
                }
                String id = e.getString("id");
                if (id.equals(v.getString("selected"))) {
                    g.fill(lx + 1, ry, lx + lw - 1, ry + 13, 0xFF1A1530);
                }
                int issues = e.getInt("issues");
                int infoEnd = lx + lw - 66;
                if (e.getBoolean("changed")) {
                    boolean sure = id.equals(sureId) && System.currentTimeMillis() < sureUntil;
                    String pub = (sure ? Tr.t("tutorial.b.publish_sure", issues) : Tr.t("tutorial.b.publish")).toUpperCase(Locale.ROOT);
                    int pw = Ui.widthBold(font, pub, TINY) + 10, px = lx + lw - 4 - pw; // measured bold (audit of SU-146)
                    boolean hotPub = mx >= px && mx < px + pw && my >= ry + 1 && my < ry + 12;
                    g.fill(px, ry + 1, px + pw, ry + 12, sure ? 0xFFD63B2F : hotPub ? 0xFFCDB0FF : Ui.VIOLET);
                    Ui.text(g, font, Component.literal(pub).withStyle(ChatFormatting.BOLD), px + 5, ry + 4, sure ? 0xFFFFFFFF : Ui.INK, TINY);
                    hits.add(new Ui.Hit(px, ry + 1, pw, 11, () -> publish(id, issues)));
                    infoEnd = Math.min(infoEnd, px - 4);
                }
                // after PUBLISH, so a click on that key (wider while SURE) is not taken by the row (audit of SU-146)
                hits.add(new Ui.Hit(lx + 1, ry, lw - 60, 13, () -> {
                    picked = id;
                    open();
                }));
                String name = (e.getBoolean("firstJoin") ? "★ " : "") + e.getString("name");
                Ui.text(g, font, Component.literal(Ui.fitBold(font, name, lw / 2 - 8, SMALL)).withStyle(ChatFormatting.BOLD), lx + 6, ry + 3, Ui.TEXT, SMALL);
                String start = TutorialDraw.word(com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Start.valueOf(e.getString("start")));
                String state = !e.getBoolean("published") ? Tr.t("tutorial.b.state_draft") : e.getBoolean("changed") ? Tr.t("tutorial.b.state_changed") : Tr.t("tutorial.b.state_live");
                String info = start + " · " + state + (issues > 0 ? " · " + Tr.t("tutorial.b.issues", issues) : "");
                Ui.text(g, font, Ui.fit(font, info, infoEnd - (lx + lw / 2 + 4), TINY), lx + lw / 2 + 4, ry + 4, issues > 0 ? Ui.GOLD : Ui.MUTED, TINY);
                g.fill(lx + 1, ry + 13, lx + lw - 1, ry + 14, Ui.ROW_LINE);
                ry += 14;
            }
        }
        if (v != null && v.contains("counts") && !v.getString("selected").isEmpty()) {
            CompoundTag c = v.getCompound("counts");
            int cy = ly + lh + 4;
            Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.b.counts", c.getInt("started"), c.getInt("finished"), c.getInt("ended")), lw, SMALL), lx, cy, Ui.TEXT, SMALL);
            StringBuilder at = new StringBuilder();
            CompoundTag ended = c.getCompound("endedAt");
            com.mrgregles.bsp_server_utilities.tutorial.Tutorial d = v.contains("draft") ? com.mrgregles.bsp_server_utilities.tutorial.Tutorial.load(v.getCompound("draft")) : null;
            for (String k : ended.getAllKeys()) {
                com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Point p = d == null ? null : d.point(Integer.parseInt(k));
                at.append(at.length() == 0 ? "" : " · ").append(Tr.t("tutorial.b.ended_at", p == null ? "?" : p.title(), ended.getInt(k)));
            }
            if (at.length() > 0) {
                Ui.text(g, font, Ui.fit(font, at.toString(), lw, TINY), lx, cy + 10, Ui.MUTED, TINY);
            }
        }
        int fy = guide ? y + host.pageH() - 24 - 16 : y + host.pageH() - 8 - 14;
        String open = Tr.t("tutorial.tab.open");
        int w = Ui.widthBold(font, open.toUpperCase(Locale.ROOT), TINY) + 16, kx = x + host.pageW() - 8 - w;
        boolean hot = mx >= kx && mx < kx + w && my >= fy && my < fy + 14;
        g.fill(kx, fy, kx + w, fy + 14, Ui.VIOLET);
        if (hot) {
            Ui.frame(g, kx, fy, w, 14, 0xFFFFFFFF);
        }
        Ui.text(g, font, Component.literal(open.toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD), kx + 8, fy + 4, Ui.INK, TINY);
        hits.add(new Ui.Hit(kx, fy, w, 14, () -> net.minecraft.client.Minecraft.getInstance().setScreen(new TutorialBuilderScreen(host))));
        Ui.text(g, font, Ui.fit(font, Tr.t("tutorial.tab.hint"), host.pageW() - 24 - w, TINY), x + 8, fy + 4, Ui.MUTED, TINY);
    }
}
