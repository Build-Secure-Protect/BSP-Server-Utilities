package com.mrgregles.bsp_server_utilities.rules;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * The network's rules (owner decisions D11, D13, D36): the popup's heading and intro line, the message a player sees
 * when they press LEAVE SERVER, and the rules in order, each a short title with optional longer text. {@code version}
 * is what players agree to and rises only when an admin asks everyone again (D7, D35); {@code revision} marks every
 * edit, so the newest copy wins across the servers.
 */
public record RuleSet(String heading, String intro, String leaveMessage, List<Rule> rules, int version, long revision) {
    public static final int MAX_RULES = 20, MAX_TITLE = 48, MAX_TEXT = 400, MAX_HEADING = 32, MAX_INTRO = 160, MAX_LEAVE = 160;
    public static final String DEFAULT_HEADING = "Server rules";
    public static final String DEFAULT_INTRO = "Tick each rule to show you have read it, then press AGREE.";
    public static final String DEFAULT_LEAVE = "You need to agree to the rules to play. Come back any time.";

    /** One rule: a bold title and optional text under it. */
    public record Rule(String title, String text) {
    }

    public RuleSet {
        rules = List.copyOf(rules);
    }

    public static RuleSet defaults() {
        return new RuleSet(DEFAULT_HEADING, DEFAULT_INTRO, DEFAULT_LEAVE, List.of(), 1, 0);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * The same set within the limits: single-line, trimmed texts cut to their lengths, rules without a title dropped,
     * at most {@value #MAX_RULES} rules, an empty heading back to the default, a version of at least 1.
     */
    public RuleSet sanitised() {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            String title = clean(r.title(), MAX_TITLE, true);
            if (!title.isEmpty() && out.size() < MAX_RULES) {
                out.add(new Rule(title, clean(r.text(), MAX_TEXT, false)));
            }
        }
        String h = clean(heading, MAX_HEADING, true);
        return new RuleSet(h.isEmpty() ? DEFAULT_HEADING : h, clean(intro, MAX_INTRO, false), clean(leaveMessage, MAX_LEAVE, false), out, Math.max(1, version), revision);
    }

    public RuleSet withRules(List<Rule> newRules) {
        return new RuleSet(heading, intro, leaveMessage, newRules, version, revision);
    }

    public RuleSet withTexts(String newHeading, String newIntro, String newLeave) {
        return new RuleSet(newHeading, newIntro, newLeave, rules, version, revision);
    }

    public RuleSet withVersion(int newVersion) {
        return new RuleSet(heading, intro, leaveMessage, rules, newVersion, revision);
    }

    public RuleSet withRevision(long newRevision) {
        return new RuleSet(heading, intro, leaveMessage, rules, version, newRevision);
    }

    /** Text within a limit; a title or heading keeps to one line, other text keeps its line breaks. */
    static String clean(String s, int max, boolean oneLine) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' && !oneLine) {
                b.append('\n');
            } else if (Character.isISOControl(c) || c == '§') {
                b.append(c == '\n' || c == '\t' ? ' ' : "");
            } else {
                b.append(c);
            }
        }
        String t = b.toString().strip();
        return t.length() > max ? t.substring(0, max).strip() : t;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("heading", heading);
        tag.putString("intro", intro);
        tag.putString("leave", leaveMessage);
        tag.putInt("version", version);
        tag.putLong("revision", revision);
        ListTag list = new ListTag();
        for (Rule r : rules) {
            CompoundTag rt = new CompoundTag();
            rt.putString("title", r.title());
            rt.putString("text", r.text());
            list.add(rt);
        }
        tag.put("rules", list);
        return tag;
    }

    public static RuleSet load(CompoundTag tag) {
        List<Rule> rules = new ArrayList<>();
        for (Tag t : tag.getList("rules", Tag.TAG_COMPOUND)) {
            CompoundTag rt = (CompoundTag) t;
            rules.add(new Rule(rt.getString("title"), rt.getString("text")));
        }
        return new RuleSet(tag.getString("heading"), tag.getString("intro"), tag.getString("leave"), rules, tag.getInt("version"), tag.getLong("revision")).sanitised();
    }
}
