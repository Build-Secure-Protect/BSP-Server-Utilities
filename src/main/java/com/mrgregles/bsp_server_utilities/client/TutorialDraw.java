package com.mrgregles.bsp_server_utilities.client;

import com.mrgregles.bsp_server_utilities.tutorial.TutorialPopup;
import com.mrgregles.bsp_server_utilities.tutorial.TutorialText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Function;

/**
 * Draws a tutorial popup's boxes (SU-140; owner decisions D724 to D726, D740): the grid laid over a rectangle, each
 * box's fill, its text (paragraphs of styled runs, wrapped to the box, each paragraph at its size and alignment) or its
 * picture (fitted inside, filling, or stretched), cut to the box. Shared by the player's popups and the editor's preview.
 */
final class TutorialDraw {
    static final float[] SIZES = {0.75f, 1f, 1.5f, 2f};

    private TutorialDraw() {
    }

    /** A paragraph as a styled line for the font (styles and colours run by run). */
    static Component paragraph(TutorialText.Para p) {
        MutableComponent c = Component.empty();
        for (TutorialText.Run r : p.runs()) {
            Style s = Style.EMPTY.withColor(TextColor.fromRgb(r.colour())).withBold(r.has(TutorialText.BOLD)).withItalic(r.has(TutorialText.ITALIC))
                    .withUnderlined(r.has(TutorialText.UNDERLINE)).withStrikethrough(r.has(TutorialText.STRIKE)).withObfuscated(r.has(TutorialText.OBFUSCATED));
            c.append(Component.literal(r.text()).withStyle(s));
        }
        return c;
    }

    /**
     * Draws the popup's boxes into the rectangle; {@code picture} gives a box's texture by its index (null while it
     * loads, when a grey "picture" note is drawn).
     */
    static void boxes(GuiGraphics g, Font font, TutorialPopup popup, int x, int y, int w, int h, Function<Integer, ClientPanelImages.Texture> picture) {
        TutorialPopup.Size size = popup.size();
        float cw = w / (float) size.cols, ch = h / (float) size.rows;
        for (int i = 0; i < popup.boxes().size(); i++) {
            TutorialPopup.Box b = popup.boxes().get(i);
            int bx = Math.round(x + b.col() * cw), by = Math.round(y + b.row() * ch);
            int bw = Math.round(b.w() * cw) - 3, bh = Math.round(b.h() * ch) - 3;
            if (bw < 4 || bh < 4) {
                continue;
            }
            if ((b.fill() >>> 24) != 0) {
                g.fill(bx, by, bx + bw, by + bh, b.fill());
            }
            Ui.scissor(g, bx, by, bx + bw, by + bh);
            if (b.kind() == TutorialPopup.Kind.TEXT) {
                text(g, font, b.text(), bx + 2, by + 2, bw - 4, bh - 4);
            } else {
                ClientPanelImages.Texture t = picture.apply(i);
                if (t == null) {
                    g.fill(bx, by, bx + bw, by + bh, 0x802A2F3A);
                    String note = Tr.t("tutorial.picture");
                    Ui.text(g, font, Ui.fit(font, note, bw - 4, 0.75f), bx + 3, by + 3, Ui.MUTED, 0.75f);
                } else {
                    image(g, t, b.image().fit(), bx, by, bw, bh);
                }
            }
            g.disableScissor();
        }
    }

    /** The text of a box from its top: each paragraph wrapped at its own size, aligned; what does not fit is cut. */
    static void text(GuiGraphics g, Font font, TutorialText text, int x, int y, int w, int h) {
        int cy = y;
        for (TutorialText.Para p : text.paras()) {
            float scale = SIZES[p.size().ordinal()];
            int lineH = Math.round(10 * scale);
            List<FormattedCharSequence> lines = p.runs().isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(paragraph(p), Math.max(8, (int) (w / scale)));
            for (FormattedCharSequence line : lines) {
                if (cy > y + h) {
                    return;
                }
                int lw = Math.round(font.width(line) * scale);
                int lx = switch (p.align()) {
                    case LEFT -> x;
                    case CENTRE -> x + (w - lw) / 2;
                    case RIGHT -> x + w - lw;
                };
                g.pose().pushPose();
                g.pose().translate(lx, cy, 0);
                g.pose().scale(scale, scale, 1);
                g.drawString(font, line, 0, 0, 0xFFFFFFFF, false);
                g.pose().popPose();
                cy += lineH;
            }
        }
    }

    /** A picture in a box: whole inside it, filling it (cut to it), or stretched to it. */
    static void image(GuiGraphics g, ClientPanelImages.Texture t, TutorialPopup.Fit fit, int x, int y, int w, int h) {
        float tw = t.width(), th = t.height();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend(); // fills turn blending off; a fading picture needs it (audit of SU-146)
        switch (fit) {
            case STRETCH -> g.blit(t.id(), x, y, w, h, 0, 0, t.width(), t.height(), t.width(), t.height());
            case FILL -> {
                float s = Math.max(w / tw, h / th);
                int uw = Math.round(w / s), vh = Math.round(h / s);
                g.blit(t.id(), x, y, w, h, (t.width() - uw) / 2f, (t.height() - vh) / 2f, uw, vh, t.width(), t.height());
            }
            default -> {
                float s = Math.min(w / tw, h / th);
                int dw = Math.round(tw * s), dh = Math.round(th * s);
                g.blit(t.id(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, 0, 0, t.width(), t.height(), t.width(), t.height());
            }
        }
    }

    /** A button key: filled with its colour, the label dark on a light colour and light on a dark one. */
    static void button(GuiGraphics g, Font font, int x, int y, int w, int h, String label, int colour, boolean hot, boolean enabled) {
        int c = 0xFF000000 | colour;
        int r = (colour >> 16) & 255, gr = (colour >> 8) & 255, b = colour & 255;
        boolean light = r * 0.299 + gr * 0.587 + b * 0.114 > 140;
        if (!enabled) {
            g.fill(x, y, x + w, y + h, Ui.SLOT);
            Ui.frame(g, x, y, w, h, Ui.DIM);
        } else {
            g.fill(x, y, x + w, y + h, c);
            if (hot) {
                Ui.frame(g, x, y, w, h, 0xFFFFFFFF);
            }
        }
        int ink = !enabled ? 0xFF6B7180 : light ? Ui.INK : Ui.TEXT;
        String s = Ui.fitBold(font, label.toUpperCase(java.util.Locale.ROOT), w - 6, 0.75f); // measured bold (audit of SU-146)
        Ui.text(g, font, Component.literal(s).withStyle(net.minecraft.ChatFormatting.BOLD), x + (w - Ui.widthBold(font, s, 0.75f)) / 2, y + (h - 6) / 2, ink, 0.75f);
    }

    // ------------------------------------------------------------------ words for the builder's choices (each key written out, for tools/check_lang.py)

    static String word(com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Start s) {
        return switch (s) {
            case FIRST_JOIN -> Tr.t("tutorial.start.first_join");
            case AREA -> Tr.t("tutorial.start.area");
            case COMMAND -> Tr.t("tutorial.start.command");
        };
    }

    static String word(com.mrgregles.bsp_server_utilities.tutorial.Tutorial.Audience a) {
        return switch (a) {
            case NEW_PLAYERS -> Tr.t("tutorial.audience.new_players");
            case NOT_FINISHED -> Tr.t("tutorial.audience.not_finished");
        };
    }

    static String word(TutorialPopup.Job j) {
        return switch (j) {
            case NEXT -> Tr.t("tutorial.job.next");
            case PREVIOUS -> Tr.t("tutorial.job.previous");
            case END -> Tr.t("tutorial.job.end");
            case CLOSE -> Tr.t("tutorial.job.close");
            case BRANCH -> Tr.t("tutorial.job.branch");
            case LINK -> Tr.t("tutorial.job.link");
        };
    }

    static String word(TutorialPopup.Fit f) {
        return switch (f) {
            case INSIDE -> Tr.t("tutorial.e.fit.inside");
            case FILL -> Tr.t("tutorial.e.fit.fill");
            case STRETCH -> Tr.t("tutorial.e.fit.stretch");
        };
    }
}
