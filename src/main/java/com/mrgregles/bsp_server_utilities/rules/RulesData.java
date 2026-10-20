package com.mrgregles.bsp_server_utilities.rules;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The rules and the agreements kept in the world (SavedData {@code bsp_server_utilities_rules}): the record itself
 * without shared storage, and with it the local copy of the rules plus the agreements of players seen here.
 */
public final class RulesData extends SavedData {
    private static final String NAME = "bsp_server_utilities_rules";
    private RuleSet rules = RuleSet.defaults();
    private final Map<UUID, Agreement> agreements = new HashMap<>();

    public static RulesData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(RulesData::load, RulesData::new, NAME);
    }

    public RuleSet rules() {
        return rules;
    }

    void setRules(RuleSet set) {
        rules = set;
        setDirty();
    }

    @Nullable
    public Agreement agreement(UUID player) {
        return agreements.get(player);
    }

    void putAgreement(UUID player, @Nullable Agreement a) {
        if (a == null) {
            agreements.remove(player);
        } else {
            agreements.put(player, a);
        }
        setDirty();
    }

    /** Tests: sets or clears a player's agreement in the world's copy. */
    public void putAgreementForTests(UUID player, @Nullable Agreement a) {
        putAgreement(player, a);
    }

    /** Agreements to the given version among those kept here. */
    public int countAgreed(int version) {
        int n = 0;
        for (Agreement a : agreements.values()) {
            if (a.version() >= version) {
                n++;
            }
        }
        return n;
    }

    private static RulesData load(CompoundTag tag) {
        RulesData d = new RulesData();
        if (tag.contains("rules")) {
            d.rules = RuleSet.load(tag.getCompound("rules"));
        }
        CompoundTag agreed = tag.getCompound("agreed");
        for (String key : agreed.getAllKeys()) {
            try {
                d.agreements.put(UUID.fromString(key), Agreement.load(agreed.getCompound(key)));
            } catch (IllegalArgumentException ignored) {
                // not a player id
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put("rules", rules.save());
        CompoundTag agreed = new CompoundTag();
        agreements.forEach((id, a) -> agreed.put(id.toString(), a.save()));
        tag.put("agreed", agreed);
        return tag;
    }
}
