package com.mrgregles.bsp_staff_panel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_staff_panel.act.Actions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The panel's actions (SU-35): signed as the game servers check them, sent in their notice format, answered or timed out. */
class ActionsTest {
    static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) i;
        }
    }

    @Test
    void the_signature_matches_the_game_servers() {
        // the same value is in BSP-Server Utilities' WebActionGameTests
        String json = "{\"v\":1,\"id\":\"vector-1\",\"at\":1800000000000,\"by\":\"Ana_Mod\",\"rank\":\"MODERATOR\",\"server\":\"BSP-Spawn\",\"action\":\"kick\",\"player\":\"00000000-0000-0000-0000-000000000001\",\"reason\":\"Testing\"}";
        assertEquals("eyJ2IjoxLCJpZCI6InZlY3Rvci0xIiwiYXQiOjE4MDAwMDAwMDAwMDAsImJ5IjoiQW5hX01vZCIsInJhbmsiOiJNT0RFUkFUT1IiLCJzZXJ2ZXIiOiJCU1AtU3Bhd24iLCJhY3Rpb24iOiJraWNrIiwicGxheWVyIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAxIiwicmVhc29uIjoiVGVzdGluZyJ9.1d0ae001505e5b2b3e1bc51d0538c2e2c08bdcba370daf0be97b60cd57007223",
                Actions.sign(KEY, json));
    }

    @Test
    void an_action_is_sent_signed_and_its_answer_comes_back() {
        List<String[]> sent = new CopyOnWriteArrayList<>();
        Actions[] a = new Actions[1];
        a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
            sent.add(new String[]{uid, String.valueOf(at), kind, payload});
            new Thread(() -> a[0].acceptMessage(Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(KEY, "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"Vexa was kicked.\"}")))).start();
        }, () -> 1234L, 3);
        JsonObject f = new JsonObject();
        f.addProperty("action", "kick");
        f.addProperty("player", "abc");
        Actions.Answer ans = a[0].ask("BSP-Spawn", "Ana_Mod", "MODERATOR", f);
        assertTrue(ans.ok());
        assertEquals("Vexa was kicked.", ans.message());
        assertEquals("web_action", sent.get(0)[2]);
        String payload = sent.get(0)[3];
        String body = payload.substring(0, payload.indexOf('.'));
        assertEquals(Actions.sign(KEY, new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8)), payload, "signed with the key");
        JsonObject j = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("BSP-Spawn", j.get("server").getAsString());
        assertEquals("Ana_Mod", j.get("by").getAsString());
        assertEquals(1234L, j.get("at").getAsLong());
        assertEquals(sent.get(0)[0], j.get("id").getAsString(), "the notice's id is the action's");
        assertEquals("u\n5\npanel\nweb_action\np", Actions.line("u", 5, "web_action", "p"), "the game servers' five lines");
    }

    @Test
    void no_answer_is_said_plainly_and_strays_are_ignored() {
        Actions a = new Actions(KEY, (uid, at, kind, payload) -> { }, System::currentTimeMillis, 1);
        Actions.Answer ans = a.ask("Frostwarf", "Ana_Mod", "MODERATOR", new JsonObject());
        assertFalse(ans.ok());
        assertTrue(ans.message().contains("No answer from Frostwarf"), ans.message());
        assertTrue(a.acceptMessage(Actions.line("x", 1, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(KEY, "{\"id\":\"late\",\"ok\":true}"))), "a late answer is taken and dropped");
        assertFalse(a.acceptMessage(Actions.line("x", 1, "snapshot", "{}")), "a snapshot is not an answer");
        Actions failing = new Actions(KEY, (uid, at, kind, payload) -> {
            throw new IllegalStateException("down");
        }, System::currentTimeMillis, 1);
        assertTrue(failing.ask("x", "y", "z", new JsonObject()).message().contains("could not be sent"));
    }

    @Test
    void an_unsigned_or_forged_answer_is_dropped_and_actions_are_limited() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> id = new java.util.concurrent.atomic.AtomicReference<>();
        Actions[] a = new Actions[1];
        byte[] other = KEY.clone();
        other[0] = 9;
        a[0] = new Actions(KEY, (uid, at, kind, payload) -> {
            id.set(uid);
            new Thread(() -> {
                a[0].acceptMessage(Actions.line("x", at, "answer", "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"forged\"}"));
                a[0].acceptMessage(Actions.line("x", at, "answer", com.mrgregles.bsp_staff_panel.crypto.Signed.seal(other, "{\"id\":\"" + uid + "\",\"ok\":true,\"message\":\"forged\"}")));
            }).start();
        }, System::currentTimeMillis, 1);
        Actions.Answer ans = a[0].ask("S", "Ana_Mod", "MODERATOR", new JsonObject());
        assertFalse(ans.ok(), "neither the unsigned nor the wrongly signed answer counts (audit): " + ans.message());
        assertTrue(ans.message().contains("check the LOG tab"), "and the wait says it may still have happened");
        Actions quick = new Actions(KEY, (uid, at, kind, payload) -> { }, System::currentTimeMillis, 0);
        for (int i = 0; i < Actions.PER_MINUTE; i++) {
            quick.ask("S", "Busy_Bee", "MODERATOR", new JsonObject());
        }
        assertTrue(quick.ask("S", "Busy_Bee", "MODERATOR", new JsonObject()).message().contains("in a minute"), "ten a minute for one staff member");
        assertFalse(quick.ask("S", "Other", "MODERATOR", new JsonObject()).message().contains("in a minute"), "others are not held up");
    }
}
