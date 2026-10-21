package com.mrgregles.bsp_server_utilities.chunks;

import com.mrgregles.bsp_server_utilities.compat.RegionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Whose chunks a Forge ticket holds (owner decision D87), in order of trust: the owner the machine saves itself (most
 * chunk-loading mods keep one: {@code ownerUUID}, {@code playerUUID}, {@code owner}), else who placed the machine
 * (recorded by this mod), else the owner of the BSP-Region Guard claim the chunk is in. A ticket held by an entity is
 * the player when the UUID is a player's, else the entity's own saved owner. Null when nothing says.
 */
public final class ChunkOwners {
    private static final int MAX_DEPTH = 4;

    public enum How { MACHINE, PLACER, CLAIM, PLAYER, ENTITY }

    public record Found(UUID player, How how) {
    }

    private ChunkOwners() {
    }

    public static String posKey(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Nullable
    public static Found find(ServerLevel level, ChunkTickets.Ticket t, ChunkData data) {
        BlockPos pos = t.blockOwner();
        if (pos != null) {
            if (level.isLoaded(pos)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) {
                    UUID saved = savedOwner(be.saveWithoutMetadata(), 0, false);
                    if (saved != null) {
                        return new Found(saved, How.MACHINE);
                    }
                }
            }
            UUID placer = data.placer(posKey(level, pos));
            if (placer != null) {
                return new Found(placer, How.PLACER);
            }
            UUID claim = RegionGuard.claimOwner(level, t.chunkPos());
            return claim == null ? null : new Found(claim, How.CLAIM);
        }
        UUID id = t.entityOwner();
        if (id == null) {
            return null;
        }
        if (isPlayer(level.getServer(), id)) {
            return new Found(id, How.PLAYER);
        }
        Entity e = level.getEntity(id);
        if (e != null) {
            UUID saved = savedOwner(e.saveWithoutId(new CompoundTag()), 0, false);
            if (saved != null && !saved.equals(id)) {
                return new Found(saved, How.ENTITY);
            }
        }
        return null;
    }

    private static boolean isPlayer(MinecraftServer server, UUID id) {
        return server.getPlayerList().getPlayer(id) != null || (server.getProfileCache() != null && server.getProfileCache().get(id).isPresent());
    }

    /** The owner a machine or entity saves itself, or null. */
    @Nullable
    public static UUID savedOwner(CompoundTag tag) {
        return savedOwner(tag, 0, false);
    }

    /**
     * The first UUID saved under a key that names an owner, a player or a placer (an int array of four, a UUID string,
     * a pair of {@code ...Most} / {@code ...Least} longs, or anything inside a compound under such a key), searching
     * compounds a few levels deep.
     */
    @Nullable
    static UUID savedOwner(CompoundTag tag, int depth, boolean underOwnerKey) {
        for (String key : tag.getAllKeys()) {
            String k = key.toLowerCase(Locale.ROOT);
            boolean ownerKey = underOwnerKey || k.contains("owner") || k.contains("player") || k.contains("placer");
            Tag t = tag.get(key);
            if (ownerKey && t instanceof IntArrayTag a && a.size() == 4) {
                return NbtUtils.loadUUID(a);
            }
            if (ownerKey && t instanceof StringTag s) {
                try {
                    return UUID.fromString(s.getAsString());
                } catch (IllegalArgumentException ignored) {
                    // a name, not a UUID
                }
            }
            if (ownerKey && k.endsWith("most") && tag.contains(key.substring(0, key.length() - 4) + "Least")) {
                return new UUID(tag.getLong(key), tag.getLong(key.substring(0, key.length() - 4) + "Least"));
            }
            if (t instanceof CompoundTag c && depth < MAX_DEPTH) {
                UUID inner = savedOwner(c, depth + 1, ownerKey);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }
}
