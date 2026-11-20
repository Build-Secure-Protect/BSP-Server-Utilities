package com.mrgregles.bsp_server_utilities.support;

import com.mrgregles.bsp_server_utilities.players.PlayersSettings;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Support BSP's settings, one set for the network (owner decisions D596, D597, D604, D612): the support link (the
 * inventory key and {@code /support}; empty: none, the key hidden), the chat line's sentence on joining the network (up
 * to {@value #MAX_SENTENCE} characters, plain words; empty: no chat line), the chat line's own link (empty: the support
 * link), who changed them last and when, and a revision that marks every save (the newest wins). A link that does not
 * pass {@link SupportLinks#check} is never kept.
 */
public record SupportSettings(String link, String sentence, String chatLink, String by, long atMs, long revision) {
    public static final int MAX_SENTENCE = 200, MAX_BY = 32;
    /** Where the chat line's address goes in the sentence; without it the address follows the sentence. */
    public static final String LINK = "{link}";
    public static final SupportSettings NONE = new SupportSettings("", "", "", "", 0, 0);
    private static final String SEP = "\u001f";

    /** The same settings within the limits: refused links dropped, the sentence cleaned of formatting codes and cut. */
    public SupportSettings sanitised() {
        return new SupportSettings(cleanLink(link), cleanSentence(sentence), cleanLink(chatLink),
                PlayersSettings.clean(by, MAX_BY, ""), Math.max(0, atMs), revision);
    }

    public static String cleanLink(@Nullable String s) {
        String t = SupportLinks.tidy(s);
        return SupportLinks.check(t) == SupportLinks.Problem.OK ? t : "";
    }

    /** Typed words without formatting codes or control characters, at most {@value #MAX_SENTENCE} characters; may be empty. */
    public static String cleanSentence(@Nullable String s) {
        return PlayersSettings.clean(s, MAX_SENTENCE, "");
    }

    /** Whether there is a support link: the key shows and {@code /support} opens the popup. */
    public boolean hasLink() {
        return !link.isEmpty();
    }

    /** The chat line's address: its own link, else the support link; empty when neither is set. */
    public String chatAddress() {
        return chatLink.isEmpty() ? link : chatLink;
    }

    /** Whether a chat line is sent on joining the network: a sentence and an address for it. */
    public boolean hasChatLine() {
        return !sentence.isEmpty() && !chatAddress().isEmpty();
    }

    /** Whether the three things players see are the same (who and when aside). */
    public boolean sameAs(SupportSettings o) {
        return link.equals(o.link) && sentence.equals(o.sentence) && chatLink.equals(o.chatLink);
    }

    public SupportSettings withRevision(long r) {
        return new SupportSettings(link, sentence, chatLink, by, atMs, r);
    }

    public String encode() {
        return String.join(SEP, link, sentence, chatLink, by, Long.toString(atMs), Long.toString(revision));
    }

    @Nullable
    public static SupportSettings decode(String s) {
        String[] f = s.split(SEP, -1);
        if (f.length != 6) {
            return null;
        }
        try {
            return new SupportSettings(f[0], f[1], f[2], f[3], Long.parseLong(f[4]), Long.parseLong(f[5])).sanitised();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("link", link);
        t.putString("sentence", sentence);
        t.putString("chatLink", chatLink);
        t.putString("by", by);
        t.putLong("at", atMs);
        t.putLong("revision", revision);
        return t;
    }

    public static SupportSettings load(CompoundTag t) {
        if (t.isEmpty()) {
            return NONE;
        }
        return new SupportSettings(t.getString("link"), t.getString("sentence"), t.getString("chatLink"), t.getString("by"), t.getLong("at"), t.getLong("revision")).sanitised();
    }
}
