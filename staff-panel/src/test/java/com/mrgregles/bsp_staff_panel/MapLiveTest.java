package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.web.MapLive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map's live data (SU-46): map ids from a server and dimension, every player on every map but shown only on their
 * own (BlueMap's {@code foreign}), names that are not plain left out, warps and spawns as pins on their own map with
 * labels made safe.
 */
class MapLiveTest {
    @Test
    void map_ids_name_the_server_and_dimension() {
        assertEquals("frostwarf__overworld", MapLive.mapId("Frostwarf", "minecraft:overworld"));
        assertEquals("bsp_spawn__nether", MapLive.mapId("BSP-Spawn", "minecraft:the_nether"));
        assertEquals("moongrove__end", MapLive.mapId("Moongrove", "minecraft:the_end"));
        assertEquals("stormfall__allthemodium_mining", MapLive.mapId("Stormfall", "allthemodium:mining"));
        assertEquals("unknown__unknown", MapLive.mapId("", null));
    }

    @Test
    void every_player_is_on_every_map_but_foreign_off_their_own() {
        JsonArray feed = JsonParser.parseString("[{\"id\":\"11111111-2222-3333-4444-555555555555\",\"name\":\"Vexa\",\"server\":\"Moongrove\",\"dim\":\"minecraft:overworld\",\"x\":-2210.5,\"y\":12,\"z\":905,\"yaw\":90},"
                + "{\"id\":\"21111111-2222-3333-4444-555555555555\",\"name\":\"Mossy_Fen\",\"server\":\"Frostwarf\",\"dim\":\"minecraft:the_nether\",\"x\":176,\"y\":33,\"z\":-49,\"yaw\":-45},"
                + "{\"id\":\"31111111-2222-3333-4444-555555555555\",\"name\":\"<img src=x>\",\"server\":\"Moongrove\",\"dim\":\"minecraft:overworld\"},"
                + "{\"id\":\"not-a-uuid\",\"name\":\"Pixl\",\"server\":\"Moongrove\",\"dim\":\"minecraft:overworld\"}]").getAsJsonArray();
        JsonArray on = MapLive.players(feed, "moongrove__overworld").getAsJsonArray("players");
        assertEquals(2, on.size(), "names that are not plain, and ids that are not ids, are left out (the page puts names in as markup)");
        JsonArray bedrock = JsonParser.parseString("[{\"id\":\"41111111-2222-3333-4444-555555555555\",\"name\":\".Steve_BE\",\"server\":\"Moongrove\",\"dim\":\"minecraft:overworld\"}]").getAsJsonArray();
        assertEquals(1, MapLive.players(bedrock, "moongrove__overworld").getAsJsonArray("players").size(), "a Bedrock name (\".\" first) is plain enough");
        JsonObject vexa = on.get(0).getAsJsonObject(), mossy = on.get(1).getAsJsonObject();
        assertFalse(vexa.get("foreign").getAsBoolean(), "on her own map");
        assertEquals(-2210.5, vexa.getAsJsonObject("position").get("x").getAsDouble());
        assertEquals(12, vexa.getAsJsonObject("position").get("y").getAsDouble(), "at the feet: BlueMap's page lifts the head itself");
        assertEquals(90, vexa.getAsJsonObject("rotation").get("yaw").getAsDouble());
        assertTrue(mossy.get("foreign").getAsBoolean(), "on another server and dimension: listed, hidden there");
        assertFalse(MapLive.players(feed, "frostwarf__nether").getAsJsonArray("players").get(1).getAsJsonObject().get("foreign").getAsBoolean());
    }

    @Test
    void warps_and_spawns_are_pins_on_their_own_map_with_safe_labels() {
        JsonObject places = JsonParser.parseString("{\"warps\":[{\"name\":\"my-shop\",\"server\":\"moongrove-tp\",\"world\":\"minecraft:overworld\",\"x\":0,\"y\":0,\"z\":0,\"restricted\":null,\"portal\":null},"
                + "{\"name\":\"my_shop\",\"server\":\"moongrove-tp\",\"world\":\"minecraft:overworld\",\"x\":0,\"y\":0,\"z\":0},"
                + "{\"name\":\"grove\",\"server\":\"moongrove-tp\",\"world\":\"minecraft:overworld\",\"x\":-2120,\"y\":70,\"z\":840,\"restricted\":true,\"portal\":false},"
                + "{\"name\":\"<b>hub</b>\",\"server\":\"moongrove-tp\",\"world\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3,\"restricted\":false,\"portal\":true},"
                + "{\"name\":\"mine\",\"server\":\"Frostwarf\",\"world\":\"minecraft:overworld\",\"x\":1500,\"y\":40,\"z\":-600}],"
                + "\"spawns\":[{\"server\":\"Moongrove\",\"world\":\"minecraft:overworld\",\"x\":-2300.5,\"y\":64,\"z\":962.5},{\"server\":\"Moongrove\",\"world\":\"minecraft:the_nether\",\"x\":0,\"y\":64,\"z\":0}]}").getAsJsonObject();
        JsonObject m = MapLive.markers(places, n -> n.equals("moongrove-tp") ? "Moongrove" : n, "moongrove__overworld");
        JsonObject warps = m.getAsJsonObject("bsp-warps").getAsJsonObject("markers");
        assertEquals(3, warps.size(), "only this map's warps, two names alike kept apart, null fields read as false (audit): " + warps);
        JsonObject grove = warps.entrySet().stream().filter(e -> e.getValue().getAsJsonObject().get("label").getAsString().equals("grove")).findFirst().orElseThrow().getValue().getAsJsonObject();
        assertEquals("poi", grove.get("type").getAsString());
        assertTrue(grove.has("classes") && grove.get("classes").isJsonArray(), "BlueMap's page refuses a pin without its classes list (seen in the browser)");
        assertEquals(-2120, grove.getAsJsonObject("position").get("x").getAsInt());
        assertTrue(grove.get("detail").getAsString().contains("restricted") && grove.get("detail").getAsString().contains("Moongrove"), "by the panel's server name");
        JsonObject portals = m.getAsJsonObject("bsp-portal-warps");
        assertTrue(portals.get("defaultHidden").getAsBoolean(), "portal warps folded away at first (D344)");
        JsonObject hub = portals.getAsJsonObject("markers").entrySet().iterator().next().getValue().getAsJsonObject();
        assertEquals("<b>hub</b>", hub.get("label").getAsString(), "the label as written: BlueMap shows it as text");
        assertFalse(hub.get("detail").getAsString().contains("<b>hub"), "the detail goes in as markup: the name escaped there (audit): " + hub.get("detail"));
        assertTrue(hub.get("detail").getAsString().contains("&lt;b&gt;hub"));
        assertEquals(1, m.getAsJsonObject("bsp-spawns").getAsJsonObject("markers").size(), "the overworld spawn only");
        assertEquals(0, MapLive.markers(null, n -> n, "x_overworld").getAsJsonObject("bsp-warps").getAsJsonObject("markers").size(), "no places: empty sets");
    }
}
