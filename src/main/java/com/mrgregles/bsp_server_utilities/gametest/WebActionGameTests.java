package com.mrgregles.bsp_server_utilities.gametest;

import com.google.gson.JsonObject;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.afk.AfkService;
import com.mrgregles.bsp_server_utilities.announce.Broadcast;
import com.mrgregles.bsp_server_utilities.log.ActionLog;
import com.mrgregles.bsp_server_utilities.rules.RuleSet;
import com.mrgregles.bsp_server_utilities.rules.RulesService;
import com.mrgregles.bsp_server_utilities.network.RulesAnswerPacket;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.web.ProxyKick;
import com.mrgregles.bsp_server_utilities.web.WebActions;
import com.mrgregles.bsp_server_utilities.web.WebAgent;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The staff web panel's actions (SU-35, D254, D262, D272): the network kick asked of Velocity Additions with the
 * server's own kick 3 s later as the fallback; LEAVE SERVER and the AFK kick going the same way; signed actions refused
 * when the signature, the age, a second use or the server is wrong; a kick and an announcement from the panel carried out
 * and written to the Action Log. The signature is checked against the same fixed value as the panel's own tests.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class WebActionGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) i;
        }
    }

    /** The same value is in the panel's ActionsTest: both sides sign alike. */
    private static final String VECTOR_JSON = "{\"v\":1,\"id\":\"vector-1\",\"at\":1800000000000,\"by\":\"Ana_Mod\",\"rank\":\"MODERATOR\",\"server\":\"BSP-Spawn\",\"action\":\"kick\",\"player\":\"00000000-0000-0000-0000-000000000001\",\"reason\":\"Testing\"}";
    private static final String VECTOR_SIGNED = "eyJ2IjoxLCJpZCI6InZlY3Rvci0xIiwiYXQiOjE4MDAwMDAwMDAwMDAsImJ5IjoiQW5hX01vZCIsInJhbmsiOiJNT0RFUkFUT1IiLCJzZXJ2ZXIiOiJCU1AtU3Bhd24iLCJhY3Rpb24iOiJraWNrIiwicGxheWVyIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAxIiwicmVhc29uIjoiVGVzdGluZyJ9.1d0ae001505e5b2b3e1bc51d0538c2e2c08bdcba370daf0be97b60cd57007223";

    private static String action(String id, long at, String action, String extra) {
        return WebActions.sign(KEY, "{\"v\":1,\"id\":\"" + id + "\",\"at\":" + at + ",\"by\":\"Ana_Mod\",\"rank\":\"MODERATOR\",\"server\":\"" + Storage.currentServerId()
                + "\",\"action\":\"" + action + "\"" + extra + "}");
    }

    @GameTest(template = EMPTY)
    public void the_network_kick_goes_to_the_proxy_and_the_server_kicks_three_seconds_later(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<byte[]> sent = new ArrayList<>();
        AtomicReference<Component> kicked = new AtomicReference<>();
        AtomicReference<ServerPlayer> here = new AtomicReference<>(p);
        ProxyKick.useForTests((pl, b) -> sent.add(b), (pl, why) -> kicked.set(why), (s, id) -> id.equals(p.getUUID()) ? here.get() : null);
        try {
            ProxyKick.kick(p, Component.literal("Bye now"));
            h.assertTrue(sent.size() == 1 && kicked.get() == null, "the message to the proxy, no kick yet");
            FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(sent.get(0)));
            h.assertTrue(b.readByte() == 1 && b.readUtf(32).equals("kick") && b.readUUID().equals(p.getUUID()), "version 1, kind kick, the player (Velocity Additions' contract)");
            Component reason = Component.Serializer.fromJson(b.readUtf(262_144));
            h.assertTrue(reason != null && reason.getString().equals("Bye now") && b.readableBytes() == 0, "and the reason as Component JSON, nothing after");
            for (int i = 0; i < ProxyKick.FALLBACK_TICKS - 1; i++) {
                ProxyKick.tick(server);
            }
            h.assertTrue(kicked.get() == null, "the proxy has 3 seconds");
            ProxyKick.tick(server);
            h.assertTrue(kicked.get() != null && kicked.get().getString().equals("Bye now") && !ProxyKick.pending(p.getUUID()), "still here after 3 s: kicked by this server");

            kicked.set(null);
            ProxyKick.kick(p, Component.literal("Again"));
            here.set(null); // the proxy removed them
            for (int i = 0; i < ProxyKick.FALLBACK_TICKS; i++) {
                ProxyKick.tick(server);
            }
            h.assertTrue(kicked.get() == null, "gone already: no second kick");
        } finally {
            ProxyKick.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void leave_server_and_the_afk_kick_leave_the_network(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<byte[]> sent = new ArrayList<>();
        ProxyKick.useForTests((pl, b) -> sent.add(b), (pl, why) -> { }, null);
        RuleSet before = RulesService.rules(server);
        try {
            RulesService.useRealDisconnector();
            RulesService.edit(server, s -> s.withRules(List.of(new RuleSet.Rule("Be kind", "To everyone."))));
            RulesService.check(p);
            RuleSet set = RulesService.rules(server);
            RulesService.answer(p, new RulesAnswerPacket(RulesAnswerPacket.Action.LEAVE, set.version(), set.revision(), List.of()));
            h.assertTrue(sent.size() == 1, "LEAVE SERVER asks the proxy (D272)");
            h.assertTrue(RulesService.isHeld(p.getUUID()), "and they stay held (frozen) until they are gone (audit)");
            h.assertTrue(RulesService.answer(p, new RulesAnswerPacket(RulesAnswerPacket.Action.LEAVE, set.version(), set.revision(), List.of())) == RulesService.Outcome.NOT_HELD
                    && sent.size() == 1, "a second LEAVE (or AGREE) counts for nothing (audit)");
            AfkService.useReal();
            AfkService.kickForTests(p, Component.literal("Idle"));
            h.assertTrue(sent.size() == 2, "and so does the AFK kick (D254)");
        } finally {
            ProxyKick.useReal();
            RulesService.forget(p.getUUID());
            RulesService.edit(server, s -> s.withRules(before.rules()));
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void only_a_signed_fresh_unused_action_for_this_server_is_taken(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        h.assertTrue(WebActions.sign(KEY, VECTOR_JSON).equals(VECTOR_SIGNED), "the same signature as the panel's");
        JsonObject v = WebActions.verify(KEY, VECTOR_SIGNED);
        h.assertTrue(v != null && v.get("reason").getAsString().equals("Testing"), "and it reads back");
        byte[] other = KEY.clone();
        other[0] = 9;
        h.assertTrue(WebActions.verify(other, VECTOR_SIGNED) == null, "another key: refused");
        String tampered = VECTOR_SIGNED.replace("eyJ2", "eyJ3");
        h.assertTrue(WebActions.verify(KEY, tampered) == null && WebActions.verify(KEY, "no-dot") == null && WebActions.verify(null, VECTOR_SIGNED) == null, "changed, malformed, no key");

        AtomicLong now = new AtomicLong(1_900_000_000_000L);
        WebActions.useForTests(KEY, now::get, (s, id) -> null);
        WebAgent.useForTests(false, null, null);
        h.assertTrue(WebActions.handle(server, action("a0", now.get(), "kick", "")) == null, "the panel off on this server: nothing is taken (audit)");
        WebAgent.useForTests(true, null, null);
        try {
            h.assertTrue(WebActions.handle(server, action("a1", now.get(), "kick", ",\"player\":\"" + UUID.randomUUID() + "\"")).message().contains("not on"), "a player not here");
            WebActions.Answer again = WebActions.handle(server, action("a1", now.get(), "kick", ",\"player\":\"" + UUID.randomUUID() + "\""));
            h.assertTrue(again != null && !again.ok() && again.message().contains("already used"), "an id works once");
            WebActions.Answer old = WebActions.handle(server, action("a2", now.get() - 31_000, "kick", ""));
            h.assertTrue(old != null && !old.ok() && old.message().contains("too long"), "older than 30 s: refused");
            String elsewhere = WebActions.sign(KEY, "{\"v\":1,\"id\":\"a3\",\"at\":" + now.get() + ",\"by\":\"x\",\"server\":\"another-server\",\"action\":\"kick\"}");
            h.assertTrue(WebActions.handle(server, elsewhere) == null, "for another server: left to it, no answer");
            h.assertTrue(WebActions.handle(server, WebActions.sign(other, "{\"id\":\"a4\"}")) == null, "a wrong signature: nothing");
            WebActions.Answer odd = WebActions.handle(server, action("a5", now.get(), "explode", ""));
            h.assertTrue(odd != null && !odd.ok(), "an unknown action is refused");
            WebActions.Answer badAt = WebActions.handle(server, WebActions.sign(KEY, "{\"v\":1,\"id\":\"a7\",\"at\":\"soon\",\"server\":\"" + Storage.currentServerId() + "\",\"action\":\"kick\"}"));
            h.assertTrue(badAt != null && !badAt.ok(), "a time that is not a number: refused, not an error (audit)");
            String signedText = WebActions.signText(KEY, "{\"ok\":true}");
            h.assertTrue(signedText.endsWith(".{\"ok\":true}") && signedText.indexOf('.') == 64, "answers and snapshots: 64 hex, a dot, the text");
            WebActions.useForTests(null, now::get, null);
            h.assertTrue(WebActions.handle(server, action("a6", now.get(), "kick", "")) == null && !WebActions.ready(), "without webPanel.actionKey nothing is taken");
        } finally {
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void graves_are_read_and_returned_through_inventory_sync(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        long now = System.currentTimeMillis();
        UUID vexa = UUID.randomUUID();
        List<String> returned = new ArrayList<>();
        WebActions.useForTests(KEY, () -> now, null);
        WebAgent.useForTests(true, null, null);
        try {
            com.mrgregles.bsp_server_utilities.compat.InventorySync.useForTests(null);
            String marker = "g" + Integer.toHexString(new java.util.Random().nextInt());
            WebActions.Answer none = WebActions.handle(server, action("c0-" + marker, now, "graves", ",\"player\":\"" + vexa + "\""));
            h.assertTrue(none != null && !none.ok() && none.message().contains("BSP-InventorySync 0.7.0"), "without BSP-InventorySync's graves: said plainly");

            com.mrgregles.bsp_server_utilities.compat.InventorySync.useForTests(new com.mrgregles.bsp_server_utilities.compat.InventorySync.Graves() {
                @Override
                public java.util.concurrent.CompletableFuture<com.google.gson.JsonArray> graves(UUID player) {
                    com.google.gson.JsonArray a = new com.google.gson.JsonArray();
                    JsonObject g = new JsonObject();
                    g.addProperty("shortId", "3f9a2c1b");
                    g.addProperty("stacks", 14);
                    g.addProperty("returnable", true);
                    a.add(g);
                    return java.util.concurrent.CompletableFuture.completedFuture(a);
                }

                @Override
                public java.util.concurrent.CompletableFuture<JsonObject> returnGrave(UUID player, String id, String by) {
                    returned.add(player + " " + id + " " + by);
                    JsonObject r = new JsonObject();
                    r.addProperty("outcome", "QUEUED");
                    r.addProperty("ok", true);
                    r.addProperty("message", "Vexa gets it at their next login.");
                    return java.util.concurrent.CompletableFuture.completedFuture(r);
                }
            });
            WebActions.Answer list = WebActions.handle(server, action("c1-" + marker, now, "graves", ",\"player\":\"" + vexa + "\""));
            WebActions.Answer done = list.later().get();
            h.assertTrue(done.ok() && done.data().getAsJsonArray().size() == 1 && done.data().getAsJsonArray().get(0).getAsJsonObject().get("shortId").getAsString().equals("3f9a2c1b"),
                    "the graves come back as the answer's data: " + done);
            WebActions.Answer ret = WebActions.handle(server, action("c2-" + marker, now, "grave_return", ",\"player\":\"" + vexa + "\",\"grave\":\"3f9a2c1b\",\"name\":\"Vexa_" + marker + "\""));
            WebActions.Answer r = ret.later().get();
            h.assertTrue(r.ok() && r.message().contains("next login") && returned.get(0).equals(vexa + " 3f9a2c1b Ana_Mod"), "returned through BSP-InventorySync, by the staff member: " + returned);
            boolean logged = ActionLog.recent(server, 20, false, List.of()).stream().anyMatch(e -> e.actor().equals("Ana_Mod (panel)") && e.text().contains("grave return Vexa_" + marker));
            h.assertTrue(logged, "and in the Action Log");
            WebActions.Answer bad = WebActions.handle(server, action("c3-" + marker, now, "grave_return", ",\"player\":\"" + vexa + "\",\"grave\":\"../x\""));
            h.assertTrue(bad != null && !bad.ok() && bad.later() == null && returned.size() == 1, "not a grave id: refused before BSP-InventorySync");
        } finally {
            com.mrgregles.bsp_server_utilities.compat.InventorySync.useForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    /** Shaped as BSP-InventorySync's GraveReturn: an enum, text, numbers, an ok(). */
    public record FakeReturn(Outcome outcome, String message, int returned, int left, int xp, String serverId) {
        public enum Outcome { OK, QUEUED }

        public boolean ok() {
            return true;
        }
    }

    @GameTest(template = EMPTY)
    public void a_late_answer_with_data_is_sent_signed_and_records_become_json(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        JsonObject f = com.mrgregles.bsp_server_utilities.compat.InventorySync.fields(new FakeReturn(FakeReturn.Outcome.QUEUED, "at next login", 14, 0, 23, "Moongrove"));
        h.assertTrue(f.get("outcome").getAsString().equals("QUEUED") && f.get("returned").getAsInt() == 14 && f.get("serverId").getAsString().equals("Moongrove"),
                "a record's components as JSON, an enum as its name: " + f);
        long now = System.currentTimeMillis();
        List<String> sent = new ArrayList<>();
        java.util.concurrent.CompletableFuture<com.google.gson.JsonArray> pending = new java.util.concurrent.CompletableFuture<>();
        WebActions.useForTests(KEY, () -> now, null);
        WebActions.answersForTests(sent::add);
        WebAgent.useForTests(true, null, null);
        com.mrgregles.bsp_server_utilities.compat.InventorySync.useForTests(new com.mrgregles.bsp_server_utilities.compat.InventorySync.Graves() {
            @Override
            public java.util.concurrent.CompletableFuture<com.google.gson.JsonArray> graves(UUID player) {
                return pending;
            }

            @Override
            public java.util.concurrent.CompletableFuture<JsonObject> returnGrave(UUID player, String id, String by) {
                return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("no"));
            }
        });
        try {
            String id = "d1-" + Integer.toHexString(new java.util.Random().nextInt());
            WebActions.dispatch(server, new com.mrgregles.bsp_server_utilities.storage.StorageBackend.Notice(0, id, now, "panel", WebActions.KIND_ACTION,
                    action(id, now, "graves", ",\"player\":\"" + UUID.randomUUID() + "\"")));
            h.assertTrue(sent.isEmpty(), "nothing sent while BSP-InventorySync has not answered (never waited for here)");
            com.google.gson.JsonArray list = new com.google.gson.JsonArray();
            list.add(f);
            pending.complete(list);
            h.assertTrue(sent.size() == 1, "sent once it answers");
            String text = sent.get(0).substring(65);
            h.assertTrue(WebActions.signText(KEY, text).equals(sent.get(0)), "signed with the key");
            JsonObject ans = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            h.assertTrue(ans.get("id").getAsString().equals(id) && ans.get("ok").getAsBoolean() && ans.getAsJsonArray("data").size() == 1, "with the graves as data: " + ans);
        } finally {
            com.mrgregles.bsp_server_utilities.compat.InventorySync.useForTests(null);
            WebActions.useReal();
            WebAgent.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_kick_and_an_announcement_from_the_panel_are_carried_out_and_logged(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer p = TestPlayers.mockServerPlayer(h);
        List<byte[]> sent = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        long now = System.currentTimeMillis();
        WebActions.useForTests(KEY, () -> now, (s, id) -> id.equals(p.getUUID()) ? p : null);
        WebAgent.useForTests(true, null, null);
        ProxyKick.useForTests((pl, b) -> sent.add(b), (pl, why) -> { }, null);
        Broadcast.useForTests(() -> List.of(p), (pl, s) -> shown.add(s.chat().getString()));
        try {
            String marker = "k" + Integer.toHexString(new java.util.Random().nextInt());
            WebActions.Answer k = WebActions.handle(server, action("b1-" + marker, now, "kick", ",\"player\":\"" + p.getUUID() + "\",\"reason\":\"spam " + marker + "\""));
            h.assertTrue(k != null && k.ok() && k.message().contains("kicked"), "kicked: " + k);
            FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(sent.get(0)));
            b.readByte();
            b.readUtf(32);
            b.readUUID();
            h.assertTrue(Component.Serializer.fromJson(b.readUtf(262_144)).getString().equals("You were kicked by staff: spam " + marker), "the player reads why");
            boolean logged = ActionLog.recent(server, 20, false, List.of()).stream().anyMatch(e -> e.actor().equals("Ana_Mod (panel)") && e.text().contains("kick") && e.text().contains(marker));
            h.assertTrue(logged, "the kick is in the Action Log by Ana_Mod (panel)");

            WebActions.Answer bc = WebActions.handle(server, action("b2-" + marker, now, "broadcast", ",\"title\":false,\"colour\":\"gold\",\"target\":\"all\",\"network\":false,\"message\":\"Hello " + marker + "\""));
            h.assertTrue(bc != null && bc.ok() && shown.size() == 1 && shown.get(0).endsWith("Hello " + marker), "the announcement shown as /broadcast shows it: " + bc + " " + shown);
            WebActions.Answer bad = WebActions.handle(server, action("b3-" + marker, now, "broadcast", ",\"title\":false,\"colour\":\"plaid\",\"target\":\"all\",\"network\":false,\"message\":\"x\""));
            h.assertTrue(bad != null && !bad.ok() && shown.size() == 1, "an unknown colour: refused, nothing shown");
        } finally {
            WebActions.useReal();
            WebAgent.useReal();
            ProxyKick.useReal();
            Broadcast.useReal();
        }
        h.succeed();
    }
}
