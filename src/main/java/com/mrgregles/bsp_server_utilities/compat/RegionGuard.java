package com.mrgregles.bsp_server_utilities.compat;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * The only class that touches BSP-Region Guard, which is optional: the owner of the claim a chunk is in (owner decision
 * D87), through its api ({@code RegionGuardApi.get().claims().claimsNear(level, Cuboid)}) by reflection, so this mod
 * needs nothing of Region Guard to build. Null when Region Guard is absent, older or newer than expected, or the chunk
 * is in nobody's claim.
 */
public final class RegionGuard {
    public static final String MODID = "bsp_region_guard";
    private static boolean failed;

    private RegionGuard() {
    }

    @Nullable
    public static UUID claimOwner(ServerLevel level, ChunkPos chunk) {
        if (failed || ModList.get() == null || !ModList.get().isLoaded(MODID)) {
            return null;
        }
        try {
            Class<?> apiType = Class.forName("com.mrgregles.bsp_region_guard.api.RegionGuardApi");
            Class<?> claimsType = Class.forName("com.mrgregles.bsp_region_guard.api.ClaimService");
            Class<?> cuboidType = Class.forName("com.mrgregles.bsp_region_guard.api.Cuboid");
            Object api = apiType.getMethod("get").invoke(null);
            Object claims = apiType.getMethod("claims").invoke(api);
            Object area = cuboidType.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                    .newInstance(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(), chunk.getMaxBlockX(), level.getMaxBuildHeight(), chunk.getMaxBlockZ());
            Method near = claimsType.getMethod("claimsNear", ServerLevel.class, cuboidType);
            for (Object nearby : (List<?>) near.invoke(claims, level, area)) {
                Object owner = nearby.getClass().getMethod("owner").invoke(nearby);
                if (owner instanceof UUID id) {
                    return id;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            BSPServerUtilities.LOGGER.warn("BSP-Region Guard's claims could not be read for chunk owners: {}", e.toString());
        }
        return null;
    }
}
