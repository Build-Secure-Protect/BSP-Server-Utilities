package com.mrgregles.bsp_server_utilities.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.access.Staff;
import com.mrgregles.bsp_server_utilities.afk.AfkService;
import com.mrgregles.bsp_server_utilities.compat.BspCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.Collection;

/**
 * What one game server tells the staff web panel every few seconds (owner decisions D251 to D253): each player online
 * here, where they are, what is above their hotbar (health, armour, hunger, XP, air), their potion effects, how long they
 * have been AFK, their ping, game mode, staff role and BSP-Core team. The JSON is the contract with the panel, documented
 * in docs/API.md ("Staff web panel"); version {@link #VERSION} changes only when a field changes meaning or goes away.
 *
 * <p>Built on the server thread (the players are read there), as plain numbers and strings: no inventories, nothing
 * that walks the world.
 */
public final class WebSnapshot {
    public static final int VERSION = 1;

    private WebSnapshot() {
    }

    /** The whole snapshot for this server. */
    public static JsonObject build(MinecraftServer server, String serverId, Collection<ServerPlayer> players, long nowMs) {
        JsonObject o = new JsonObject();
        o.addProperty("v", VERSION);
        o.addProperty("server", serverId);
        o.addProperty("at", nowMs);
        o.addProperty("mspt", round1(server.getAverageTickTime()));
        o.addProperty("max", server.getMaxPlayers());
        o.addProperty("graves", com.mrgregles.bsp_server_utilities.compat.InventorySync.graves() != null); // where the panel asks about graves (audit)
        JsonArray list = new JsonArray();
        for (ServerPlayer p : players) {
            list.add(player(server, p));
        }
        o.add("players", list);
        return o;
    }

    /** One player's entry. */
    public static JsonObject player(MinecraftServer server, ServerPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.getUUID().toString());
        o.addProperty("name", p.getGameProfile().getName());
        o.addProperty("dim", p.level().dimension().location().toString());
        o.addProperty("x", round1(p.getX()));
        o.addProperty("y", round1(p.getY()));
        o.addProperty("z", round1(p.getZ()));
        o.addProperty("yaw", Math.round(p.getYRot()));
        o.addProperty("mode", p.gameMode.getGameModeForPlayer().getName());
        o.addProperty("hp", round1(p.getHealth()));
        o.addProperty("maxHp", round1(p.getMaxHealth()));
        o.addProperty("armor", p.getArmorValue());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("sat", round1(p.getFoodData().getSaturationLevel()));
        o.addProperty("level", p.experienceLevel);
        o.addProperty("xp", Math.round(p.experienceProgress * 100) / 100.0);
        o.addProperty("air", Math.max(0, p.getAirSupply()));
        o.addProperty("maxAir", p.getMaxAirSupply());
        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject fx = new JsonObject();
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect());
            fx.addProperty("id", id == null ? "unknown" : id.toString());
            fx.addProperty("amp", e.getAmplifier());
            fx.addProperty("sec", e.isInfiniteDuration() ? -1 : e.getDuration() / 20);
            effects.add(fx);
        }
        o.add("effects", effects);
        o.addProperty("skin", skinUrl(p.getGameProfile()));
        o.addProperty("afkMs", AfkService.afkForMs(p.getUUID()));
        o.addProperty("ping", p.latency);
        Staff.roleOf(p).ifPresentOrElse(r -> o.addProperty("role", r.label()), () -> o.add("role", null));
        BspCore.team(server, p.getUUID()).ifPresentOrElse(t -> {
            JsonObject team = new JsonObject();
            team.addProperty("id", t.id().toString());
            team.addProperty("name", t.name());
            team.addProperty("colour", String.format("#%06X", t.colour() & 0xFFFFFF));
            o.add("team", team);
        }, () -> o.add("team", null));
        return o;
    }

    /**
     * The player's skin texture address from their profile's {@code textures} property (Mojang's
     * {@code textures.minecraft.net} only), so the panel can draw their head; null when there is none (an offline-mode
     * profile) or it points anywhere else.
     */
    @org.jetbrains.annotations.Nullable
    public static String skinUrl(com.mojang.authlib.GameProfile profile) {
        try {
            for (com.mojang.authlib.properties.Property prop : profile.getProperties().get("textures")) {
                String json = new String(java.util.Base64.getDecoder().decode(prop.getValue()), java.nio.charset.StandardCharsets.UTF_8);
                JsonObject t = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
                if (t == null || !t.has("SKIN")) {
                    continue;
                }
                String url = t.getAsJsonObject("SKIN").get("url").getAsString();
                java.net.URI u = java.net.URI.create(url);
                if (("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && "textures.minecraft.net".equals(u.getHost())
                        && u.getRawPath() != null && u.getRawPath().matches("/texture/[0-9a-fA-F]{20,80}") && u.getRawQuery() == null) {
                    return "https://textures.minecraft.net" + u.getRawPath();
                }
            }
        } catch (RuntimeException ignored) {
            // a property we cannot read: no head
        }
        return null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
