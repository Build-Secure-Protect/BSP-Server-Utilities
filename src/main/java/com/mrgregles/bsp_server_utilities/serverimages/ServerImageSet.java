package com.mrgregles.bsp_server_utilities.serverimages;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The network's server images (owner decisions D191, D196): one entry per server, by the server's name as Velocity knows
 * it, with the image's id (the SHA-1 of its PNG bytes, which are kept apart under it), the file it came from, its size in
 * pixels and bytes; and a revision that marks every save (the newest wins). Entries are sorted by name, ignoring case.
 */
public record ServerImageSet(List<Entry> entries, long revision) {
    public static final ServerImageSet EMPTY = new ServerImageSet(List.of(), 0);
    private static final String FIELD = "\u001f", ENTRY = "\u001e";

    public record Entry(String name, String id, String file, int width, int height, int bytes) {
    }

    public ServerImageSet {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        entries = List.copyOf(sorted);
    }

    /** The entry for a server, ignoring case, or null. */
    @Nullable
    public Entry of(String name) {
        for (Entry e : entries) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    /** Every image id the set uses. */
    public Set<String> ids() {
        Set<String> out = new LinkedHashSet<>();
        entries.forEach(e -> out.add(e.id()));
        return out;
    }

    public ServerImageSet withRevision(long r) {
        return new ServerImageSet(entries, r);
    }

    /** Whether two sets show the same images for the same servers (the revision aside). */
    public boolean sameImages(ServerImageSet other) {
        if (entries.size() != other.entries.size()) {
            return false;
        }
        for (Entry e : entries) {
            Entry o = other.of(e.name());
            if (o == null || !o.id().equals(e.id())) {
                return false;
            }
        }
        return true;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder(Long.toString(revision));
        for (Entry e : entries) {
            sb.append(ENTRY).append(String.join(FIELD, e.name(), e.id(), e.file(), Integer.toString(e.width()), Integer.toString(e.height()), Integer.toString(e.bytes())));
        }
        return sb.toString();
    }

    @Nullable
    public static ServerImageSet decode(String s) {
        String[] parts = s.split(ENTRY, -1);
        try {
            long revision = Long.parseLong(parts[0]);
            List<Entry> out = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String[] f = parts[i].split(FIELD, -1);
                if (f.length != 6 || !ServerImages.validName(f[0]) || !ServerImages.validId(f[1])) {
                    return null;
                }
                out.add(new Entry(f[0], f[1], f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]), Integer.parseInt(f[5])));
            }
            return new ServerImageSet(out, revision);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("revision", revision);
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag c = new CompoundTag();
            c.putString("name", e.name());
            c.putString("id", e.id());
            c.putString("file", e.file());
            c.putInt("width", e.width());
            c.putInt("height", e.height());
            c.putInt("bytes", e.bytes());
            list.add(c);
        }
        t.put("entries", list);
        return t;
    }

    public static ServerImageSet load(CompoundTag t) {
        if (t.isEmpty()) {
            return EMPTY;
        }
        List<Entry> out = new ArrayList<>();
        ListTag list = t.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (ServerImages.validName(c.getString("name")) && ServerImages.validId(c.getString("id"))) {
                out.add(new Entry(c.getString("name"), c.getString("id"), c.getString("file"), c.getInt("width"), c.getInt("height"), c.getInt("bytes")));
            }
        }
        return new ServerImageSet(out, t.getLong("revision"));
    }
}
