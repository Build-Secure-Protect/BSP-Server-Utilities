package com.mrgregles.bsp_server_utilities.check;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The trusted setup (owner decisions D121, D123, D132): an admin's own client as it was when they pressed TRUST MY
 * SETUP, so what other players have beyond it stands out. When and from whom; every mod with its version and file
 * fingerprint; the packs switched on; the shader. Written as JSON in the shared table, in notices and in
 * {@code config/bsp_server_utilities/trusted_setup.json} (laid out for people), so one server's setup can be copied to
 * others by hand.
 */
public record TrustedSetup(long takenAtMs, String takenBy, List<ClientReport.Mod> mods, List<ClientReport.Pack> packs, String shader, long revision) {
    public static final String FILE = "trusted_setup.json";
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public TrustedSetup {
        mods = List.copyOf(mods);
        packs = List.copyOf(packs);
    }

    /** The admin's report as a setup. */
    public static TrustedSetup of(ClientReport r, String by, long atMs) {
        List<ClientReport.Pack> packs = new ArrayList<>(r.packs());
        return new TrustedSetup(atMs, by, r.mods(), packs, r.shader(), 0);
    }

    public TrustedSetup withRevision(long r) {
        return new TrustedSetup(takenAtMs, takenBy, mods, packs, shader, r);
    }

    @Nullable
    public ClientReport.Mod mod(String id) {
        for (ClientReport.Mod m : mods) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** Whether a pack is in the setup: by fingerprint when it has one, else by id. */
    public boolean hasPack(ClientReport.Pack p) {
        for (ClientReport.Pack q : packs) {
            if (!p.sha1().isEmpty() ? p.sha1().equals(q.sha1()) : p.id().equals(q.id())) {
                return true;
            }
        }
        return false;
    }

    /** Compact, for the table, notices and the world data. */
    public String toJson() {
        return GSON.toJson(this);
    }

    /** Laid out for people, for the file. */
    public String toFileJson() {
        return PRETTY.toJson(this);
    }

    @Nullable
    public static TrustedSetup fromJson(String json) {
        try {
            TrustedSetup t = GSON.fromJson(json, TrustedSetup.class);
            if (t == null || t.takenBy() == null || t.mods() == null || t.packs() == null) {
                return null;
            }
            List<ClientReport.Mod> mods = new ArrayList<>();
            for (ClientReport.Mod m : t.mods()) {
                if (m != null && m.id() != null) {
                    mods.add(new ClientReport.Mod(m.id(), nz(m.name()), nz(m.version()), nz(m.sha1())));
                }
            }
            List<ClientReport.Pack> packs = new ArrayList<>();
            for (ClientReport.Pack p : t.packs()) {
                if (p != null && p.id() != null) {
                    packs.add(new ClientReport.Pack(p.id(), nz(p.name()), nz(p.sha1())));
                }
            }
            return new TrustedSetup(t.takenAtMs(), t.takenBy(), mods, packs, nz(t.shader()), t.revision());
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }
}
