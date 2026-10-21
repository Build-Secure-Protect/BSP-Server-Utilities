package com.mrgregles.bsp_server_utilities.chunks;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only code that reads or changes other mods' chunk tickets: Forge's saved tickets (owner decision D86), each held
 * by a mod for a block position or an entity, ticking or not. Temporary tickets of a mod's own type, vanilla
 * {@code /forceload} and Bukkit plugin chunks are never seen here. Forge keeps a ticket's mod id and owner in private
 * fields with no getters, so they are read by reflection (Forge's own classes keep their names on Forge and Mohist).
 */
public final class ChunkTickets {
    /** BSP-Core's tickets (totems and projectors) are never touched (the owner's brief). */
    public static final String BSP_CORE = "bsp_core";
    private static final String SEP = "\u001e";
    @Nullable
    private static Field modIdField, ownerField;
    private static boolean warned;

    private ChunkTickets() {
    }

    /**
     * One Forge ticket: the dimension, the mod id, whether an entity holds it (else a block), the owner ({@code x,y,z}
     * or the entity's UUID), the chunk, and whether it ticks.
     */
    public record Ticket(String dimension, String modId, boolean entity, String owner, long chunk, boolean ticking) {
        public String key() {
            return encode();
        }

        public String encode() {
            return String.join(SEP, dimension, modId, entity ? "e" : "b", owner, Long.toString(chunk), ticking ? "t" : "n");
        }

        @Nullable
        public static Ticket decode(String s) {
            String[] f = s.split(SEP, -1);
            if (f.length != 6) {
                return null;
            }
            try {
                return new Ticket(f[0], f[1], f[2].equals("e"), f[3], Long.parseLong(f[4]), f[5].equals("t"));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Nullable
        public BlockPos blockOwner() {
            if (entity) {
                return null;
            }
            String[] p = owner.split(",");
            try {
                return p.length == 3 ? new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Nullable
        public UUID entityOwner() {
            try {
                return entity ? UUID.fromString(owner) : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public ChunkPos chunkPos() {
            return new ChunkPos(chunk);
        }

        @Nullable
        public ServerLevel level(MinecraftServer server) {
            ResourceLocation id = ResourceLocation.tryParse(dimension);
            return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        }

        public static Ticket ofBlock(ServerLevel level, String modId, BlockPos pos, long chunk, boolean ticking) {
            return new Ticket(level.dimension().location().toString(), modId, false, pos.getX() + "," + pos.getY() + "," + pos.getZ(), chunk, ticking);
        }
    }

    private static boolean reflect() {
        if (modIdField != null) {
            return true;
        }
        try {
            Field m = ForgeChunkManager.TicketOwner.class.getDeclaredField("modId");
            Field o = ForgeChunkManager.TicketOwner.class.getDeclaredField("owner");
            m.setAccessible(true);
            o.setAccessible(true);
            modIdField = m;
            ownerField = o;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                BSPServerUtilities.LOGGER.warn("Chunk unloading is off: Forge's chunk tickets could not be read ({})", e.toString());
            }
            return false;
        }
    }

    /** Every Forge ticket in the level now. */
    public static List<Ticket> live(ServerLevel level) {
        List<Ticket> out = new ArrayList<>();
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData::load, "chunks");
        if (data == null || !reflect()) {
            return out;
        }
        String dim = level.dimension().location().toString();
        collect(out, dim, data.getBlockForcedChunks().getChunks(), false, false);
        collect(out, dim, data.getBlockForcedChunks().getTickingChunks(), false, true);
        collect(out, dim, data.getEntityForcedChunks().getChunks(), true, false);
        collect(out, dim, data.getEntityForcedChunks().getTickingChunks(), true, true);
        return out;
    }

    private static <T extends Comparable<? super T>> void collect(List<Ticket> out, String dim, Map<ForgeChunkManager.TicketOwner<T>, LongSet> map, boolean entity, boolean ticking) {
        for (Map.Entry<ForgeChunkManager.TicketOwner<T>, LongSet> e : Map.copyOf(map).entrySet()) {
            try {
                String modId = (String) modIdField.get(e.getKey());
                Object owner = ownerField.get(e.getKey());
                String o = owner instanceof BlockPos p ? p.getX() + "," + p.getY() + "," + p.getZ() : String.valueOf(owner);
                for (long chunk : e.getValue().toLongArray()) {
                    out.add(new Ticket(dim, modId, entity, o, chunk, ticking));
                }
            } catch (IllegalAccessException | ClassCastException ignored) {
                // an owner we cannot read is left alone
            }
        }
    }

    /** Takes the ticket from Forge: the chunk unloads unless something else holds it. */
    public static boolean remove(ServerLevel level, Ticket t) {
        return force(level, t, false);
    }

    /** Gives the ticket back to Forge, under its own mod id and owner, as it was. */
    public static boolean add(ServerLevel level, Ticket t) {
        return force(level, t, true);
    }

    private static boolean force(ServerLevel level, Ticket t, boolean add) {
        ChunkPos c = t.chunkPos();
        BlockPos pos = t.blockOwner();
        UUID id = t.entityOwner();
        if (pos != null) {
            return ForgeChunkManager.forceChunk(level, t.modId(), pos, c.x, c.z, add, t.ticking());
        }
        return id != null && ForgeChunkManager.forceChunk(level, t.modId(), id, c.x, c.z, add, t.ticking());
    }
}
